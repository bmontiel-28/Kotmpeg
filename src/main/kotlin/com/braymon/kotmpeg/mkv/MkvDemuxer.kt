package com.braymon.kotmpeg.mkv

import com.braymon.kotmpeg.Demuxer
import com.braymon.kotmpeg.ebml.EbmlElement
import com.braymon.kotmpeg.ebml.EbmlException
import com.braymon.kotmpeg.ebml.EbmlReader
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.ebml.EbmlReader.Companion.vintLength
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.Timestamps
import com.braymon.kotmpeg.model.TrackInfo
import java.io.EOFException
import java.io.File
import java.util.ArrayDeque

/**
 * Demuxer Matroska (.mkv).
 *
 * Lee pistas (H.264/H.265/AAC/Opus), SimpleBlocks y BlockGroups, los tres esquemas de
 * lacing, la compresión de contenido de `ContentEncoding` (eliminación de cabecera y zlib), y
 * usa el índice Cues para las búsquedas. Las pistas no soportadas (p. ej. subtítulos) se
 * descartan con un único aviso cada una, y sus bloques se saltan sin leer su carga.
 *
 * Está repartido en piezas internas: `MkvHeaderParser` lee lo que hay por encima del primer
 * cluster y lo deja en un `MkvHeader` inmutable, `MkvContentDecoder` deshace la compresión de
 * contenido y `MkvSeekFilter` decide por dónde empieza cada pista tras [seekTo]. Aquí quedan la
 * lectura de clusters y bloques y la búsqueda del keyframe.
 *
 * **No es seguro entre hilos**: readPacket/seekTo comparten la posición del archivo. Tras [close],
 * [readPacket] y [seekTo] lanzan `IllegalStateException`.
 */
public class MkvDemuxer(
    private val input: SeekableInput,
    /**
     * Avisos no fatales: lo que se descarta o se ignora del archivo en vez de rechazarlo. Pistas
     * cuyos parámetros son imposibles o cuyo códec no está soportado, metadatos de color
     * imposibles, un índice `Cues` ilegible o recortado, bloques que no se pueden leer o
     * descomprimir, y el final anticipado de la lectura por datos ilegibles.
     *
     * Mismo papel que en `Mp4Demuxer`, y por el mismo motivo: sin este canal una pista
     * desaparece sin dejar rastro y el síntoma que llega es "el vídeo se abre pero no tiene
     * audio", sin nada que lo explique.
     */
    private val onWarning: (String) -> Unit = {},
) : Demuxer {

    /**
     * Atajo desde una ruta. Acepta [onWarning] porque el README presenta instanciar el
     * demuxer directamente como una opción válida, y sin el parámetro aquí quien lo hiciera
     * se quedaba sin diagnóstico y sin ninguna pista de que ese canal existiera.
     */
    public constructor(file: File, onWarning: (String) -> Unit = {}) :
        this(SeekableInput(file), onWarning = onWarning)

    private val reader = EbmlReader(input)

    private val decoder = MkvContentDecoder()

    private val header: MkvHeader = try {
        MkvHeaderParser(input, reader, decoder, ::warn).parse()
    } catch (t: Throwable) {
        runCatching { decoder.close() }
        runCatching { input.close() }
        throw t
    }

    override val durationUs: Long get() = header.durationUs

    override val tracks: List<TrackInfo> = header.tracks.values.toList()

    /**
     * Pistas sin declarar de las que ya se avisó, para no repetir el aviso por cada bloque. Se
     * deja de añadir a [MAX_UNKNOWN_TRACK_WARNINGS]: cada número distinto que traiga un bloque era
     * un aviso y una entrada más, sin límite.
     */
    private val unknownWarned = HashSet<Int>()

    /** Cabecera de un Block o SimpleBlock: pista, desfase respecto al cluster, flags y lo que ocupa. */
    private class BlockHeader(val trackNumber: Int, val relative: Int, val flags: Int, val headerLength: Int)

    /** Lo que deja [walkCluster]: el tiempo del cluster, los keyframes de una pista y dónde sigue el archivo, si se sabe. */
    private class ClusterWalk(val timeUs: Long?, val keyFramesUs: List<Long>, val next: Long?)

    /** Un keyframe hallado por [scanForKeyFrame]: desde dónde leer para llegar a él, y su `pts`. */
    private class KeyFrameAt(val readFrom: Long, val ptsUs: Long)

    /** Activo desde un [seekTo] hasta que se decide por dónde empieza cada pista; `null` el resto del tiempo. */
    private var seekFilter: MkvSeekFilter? = null

    /**
     * Pistas de vídeo que tras una búsqueda no tenían ningún keyframe en lo leído: se descarta lo
     * suyo hasta el siguiente, que es el primero que se puede decodificar.
     */
    private val awaitingKeyFrame = HashSet<Int>()

    private val pending = ArrayDeque<MediaPacket>()
    private var clusterTimestampTicks = 0L
    private var clusterEnd = -1L
    private var eof = false
    private var closed = false

    init {
        val firstCluster = header.firstClusterPosition
        if (firstCluster != null) input.position = firstCluster else eof = true
    }

    private fun warn(message: String) {
        runCatching { onWarning(message) }
    }

    private fun ticksToUs(ticks: Long): Long = Timestamps.rescaleFloor(ticks, header.timestampScaleNs, 1000)

    override fun readPacket(): MediaPacket? {
        check(!closed) { CLOSED }
        while (true) {
            while (pending.isEmpty()) {
                if (!advance() && !releaseHeld()) return null
            }
            val packet = pending.poll()
            val filter = seekFilter
            if (filter == null) {
                if (packet.trackId !in awaitingKeyFrame) return packet
                if (!packet.isKeyFrame) continue
                awaitingKeyFrame.remove(packet.trackId)
                return packet
            }
            filter.offer(packet)?.let(::land)
        }
    }

    /**
     * Se decidió la búsqueda: lo que devuelve el filtro va delante de lo que quede en la cola, y
     * desde aquí todo pasa por el filtro de keyframes de [readPacket].
     */
    private fun land(landing: SeekLanding) {
        seekFilter = null
        awaitingKeyFrame += landing.awaitingKeyFrame
        val rest = ArrayList(pending)
        pending.clear()
        pending.addAll(landing.packets)
        pending.addAll(rest)
    }

    /**
     * El archivo se acabó con una búsqueda por decidir: lo que haya vuelve a la cola ([land]), o
     * `false` si no había búsqueda pendiente.
     *
     * Antes se devolvía directamente el primer paquete de lo guardado, sin pasar por el filtro de
     * keyframes: con dos pistas de vídeo y una búsqueda que se decidía al llegar al final, salía
     * primero un fotograma de la segunda que no se puede decodificar.
     */
    private fun releaseHeld(): Boolean {
        val filter = seekFilter ?: return false
        land(filter.finish())
        return true
    }

    /**
     * Lee el siguiente elemento dentro de/entre clusters, encolando los paquetes hallados.
     *
     * Aquí la política ante datos corruptos es deliberadamente distinta a la del parseo de
     * cabeceras: **se termina el stream en vez de lanzar**. Un archivo truncado (una
     * grabación cortada de golpe, lo normal en este caso de uso) debe reproducirse hasta donde
     * llegue, no fallar entero por la cola dañada. Las cabeceras sí lanzan, porque sin ellas no hay nada que reproducir.
     *
     * Antes solo se protegía la lectura de la cabecera de cada elemento: un `SimpleBlock` cortado
     * a mitad de su carga lanzaba al leerla, justo en el caso que esta política existe para
     * cubrir. Ahora cualquier dato ilegible termina el stream con un aviso; un error de E/S de
     * verdad —que no es un archivo dañado sino un disco o un descriptor que fallan— se propaga.
     */
    private fun advance(): Boolean {
        if (eof || input.remaining <= 0 || input.position >= header.segmentEnd) return false
        return try {
            advanceUnchecked()
        } catch (e: EbmlException) {
            endOfReadableData(e.message)
        } catch (e: EOFException) {
            endOfReadableData(e.message)
        } catch (e: IllegalArgumentException) {
            endOfReadableData(e.message)
        }
    }

    /**
     * Termina la lectura por datos ilegibles, con aviso y para siempre: todos los finales que no
     * son el del archivo pasan por aquí. Antes, un elemento de tamaño desconocido fuera de lugar
     * terminaba sin aviso, y entre clusters ni siquiera marcaba el final: `readPacket` devolvía
     * `null` y la llamada siguiente leía lo de dentro como si fueran clusters y entregaba paquetes.
     */
    private fun endOfReadableData(reason: String?): Boolean {
        eof = true
        warn("datos de Matroska ilegibles en el offset ${input.position}: fin del stream ($reason)")
        return false
    }

    private fun unknownSizeEnd(el: EbmlElement): Boolean =
        endOfReadableData("elemento 0x${el.id.toString(16)} de tamaño desconocido donde no se admite")

    private fun startCluster(el: EbmlElement) {
        clusterEnd = if (el.size >= 0) el.dataEnd else Long.MAX_VALUE
        clusterTimestampTicks = 0
    }

    /**
     * Un paso de [advance]. Dentro de un cluster, cualquier id de nivel 1 lo cierra, tenga o no
     * tamaño: ninguno puede ir dentro de uno (ver `MatroskaIds.TOP_LEVEL`). Es la lista con la que
     * lo recorren [walkCluster] y `MkvRepair`; aquí se miraban solo seis, y un `Info` detrás de un
     * cluster de tamaño desconocido dejaba leer lo que lo seguía como si fuera del cluster.
     */
    private fun advanceUnchecked(): Boolean {
        if (clusterEnd < 0) {
            while (true) {
                if (input.remaining <= 0 || input.position >= header.segmentEnd) return false
                val el = reader.readElement()
                if (el.id == MatroskaIds.CLUSTER) {
                    startCluster(el)
                    return true
                }
                if (el.size < 0) return unknownSizeEnd(el)
                reader.skip(el)
            }
        }

        if (input.position >= clusterEnd || input.remaining <= 0) {
            clusterEnd = -1
            return true
        }
        val el = reader.readElement()
        when (el.id) {
            MatroskaIds.CLUSTER_TIMESTAMP -> clusterTimestampTicks = readClusterTimestamp(el)
            MatroskaIds.SIMPLE_BLOCK -> readSimpleBlock(el)
            MatroskaIds.BLOCK_GROUP -> parseBlockGroup(el)
            MatroskaIds.CLUSTER -> startCluster(el)
            in MatroskaIds.TOP_LEVEL -> {
                clusterEnd = -1
                if (el.size < 0) return unknownSizeEnd(el)
                reader.skip(el)
            }
            else -> {
                if (el.size < 0) return unknownSizeEnd(el)
                reader.skip(el)
            }
        }
        return true
    }

    /**
     * `ClusterTimestamp` es un uint, y uno de 8 bytes con el bit alto llega negativo (ver
     * `EbmlReader.readUInt`): sin comprobarlo daba paquetes con tiempos negativos sin ningún aviso.
     */
    private fun readClusterTimestamp(el: EbmlElement): Long {
        val ticks = reader.readUInt(el)
        if (ticks < 0) throw EbmlException("ClusterTimestamp imposible: ${java.lang.Long.toUnsignedString(ticks)}")
        return ticks
    }

    /**
     * Comprueba que la carga de un bloque está entera en el archivo antes de leer nada de ella.
     * Si no lo está, es la cola de un archivo cortado: se lanza para que [advance] cierre el
     * stream limpiamente.
     */
    private fun requireBlockPayload(el: EbmlElement) {
        if (el.size < 0 || el.size > Int.MAX_VALUE || el.dataEnd > input.length) {
            throw EbmlException("bloque de ${el.size} bytes en ${el.dataStart} cortado o imposible")
        }
    }

    /**
     * Lee la cabecera de un bloque (número de pista, desfase y flags) desde la posición actual,
     * o `null` si es imposible. La carga se lee aparte, directamente a su array definitivo: así
     * un fotograma sin lacing —el caso normal— no se copia dos veces.
     */
    private fun readBlockHeader(el: EbmlElement): BlockHeader? {
        if (el.size < 4) return null
        val first = input.readByte()
        val length = vintLength(first)
        if (length == 0 || el.size < length + 3) return null
        var trackNumber = (first and (0xFF shr length)).toLong()
        repeat(length - 1) { trackNumber = (trackNumber shl 8) or input.readByte().toLong() }
        val relative = input.readUInt(2).toInt().toShort().toInt()
        val flags = input.readByte()
        if (trackNumber !in 1..Int.MAX_VALUE) return null
        return BlockHeader(trackNumber.toInt(), relative, flags, length + 3)
    }

    /** Un bloque cuya cabecera no se puede leer se descarta, pero no en silencio, como hasta ahora. */
    private fun warnImpossibleBlock(el: EbmlElement) {
        warn("se descarta un bloque de ${el.size} bytes en ${el.dataStart} cuya cabecera es imposible")
    }

    /**
     * Pista de un bloque, o `null` si no está entre las que se leen. Las que se descartaron al
     * leer la cabecera ya avisaron entonces, y no repiten; de las que no se declararon se avisa
     * una vez por pista, hasta [MAX_UNKNOWN_TRACK_WARNINGS].
     */
    private fun trackFor(number: Int): TrackInfo? {
        header.tracks[number]?.let { return it }
        if (number in header.discardedTracks) return null
        if (unknownWarned.size < MAX_UNKNOWN_TRACK_WARNINGS && unknownWarned.add(number)) {
            val more = if (unknownWarned.size == MAX_UNKNOWN_TRACK_WARNINGS) "; no se avisará de más pistas sin declarar" else ""
            warn("se descartan los bloques de la pista $number, que no está declarada en Tracks$more")
        }
        return null
    }

    /** Si el filtro de una búsqueda en curso va a tirar [block]: así no se lee su carga. */
    private fun droppedBySeek(block: BlockHeader): Boolean =
        seekFilter?.drops(block.trackNumber, ticksToUs(clusterTimestampTicks + block.relative)) == true

    private fun readSimpleBlock(el: EbmlElement) {
        requireBlockPayload(el)
        val block = readBlockHeader(el)
        if (block == null) warnImpossibleBlock(el)
        val track = block?.let { trackFor(it.trackNumber) }
        if (block == null || track == null || droppedBySeek(block)) {
            input.position = el.dataEnd
            return
        }
        val payload = input.readBytes((el.size - block.headerLength).toInt())
        emitBlock(track, block, payload, keyOverride = null, blockDurationTicks = -1, simpleFlags = true, at = el.dataStart)
    }

    /**
     * Un `BlockGroup`. De `ReferenceBlock` solo importa que esté —el bloque no es keyframe—, así
     * que se salta sin leerlo: leerlo como entero hacía que uno de más de 8 bytes, que no se usa
     * para nada, terminara el stream.
     */
    private fun parseBlockGroup(group: EbmlElement) {
        requireBlockPayload(group)
        var block: BlockHeader? = null
        var track: TrackInfo? = null
        var payload: ByteArray? = null
        var hasReference = false
        var durationTicks = -1L
        while (input.position < group.dataEnd) {
            val el = reader.readElement()
            when (el.id) {
                MatroskaIds.BLOCK -> {
                    requireBlockPayload(el)
                    block = readBlockHeader(el)
                    if (block == null) warnImpossibleBlock(el)
                    track = block?.let { trackFor(it.trackNumber) }
                    val current = block
                    if (current != null && track != null && !droppedBySeek(current)) {
                        payload = input.readBytes((el.size - current.headerLength).toInt())
                    } else {
                        input.position = el.dataEnd
                    }
                }
                MatroskaIds.REFERENCE_BLOCK -> { reader.skip(el); hasReference = true }
                MatroskaIds.BLOCK_DURATION -> durationTicks = reader.readUInt(el)
                else -> reader.skip(el)
            }
        }
        val blockHeader = block ?: return
        val blockTrack = track ?: return
        val blockPayload = payload ?: return
        emitBlock(blockTrack, blockHeader, blockPayload, keyOverride = !hasReference, blockDurationTicks = durationTicks, simpleFlags = false, at = group.dataStart)
    }

    /**
     * Separa los fotogramas de un bloque según su lacing. [payload] empieza justo después de los
     * flags; devuelve `null` si las longitudes declaradas no cuadran con los bytes que hay.
     */
    private fun splitLaces(payload: ByteArray, lacing: Int): List<ByteArray>? {
        var i = 0
        if (i >= payload.size) return null
        val frameCountMinus1 = payload[i].toInt() and 0xFF
        i++
        val sizes = IntArray(frameCountMinus1 + 1)
        when (lacing) {
            LACING_FIXED -> {
                val each = (payload.size - i) / (frameCountMinus1 + 1)
                for (k in sizes.indices) sizes[k] = each
            }
            LACING_XIPH -> {
                for (k in 0 until frameCountMinus1) {
                    var size = 0
                    while (true) {
                        if (i >= payload.size) return null
                        val b = payload[i].toInt() and 0xFF; i++
                        size += b
                        if (b != 255) break
                    }
                    sizes[k] = size
                }
            }
            LACING_EBML -> {
                var prev = 0L
                for (k in 0 until frameCountMinus1) {
                    if (i >= payload.size) return null
                    val b0 = payload[i].toInt() and 0xFF
                    val l2 = vintLength(b0)
                    if (l2 == 0 || i + l2 > payload.size) return null
                    var v = (b0 and (0xFF shr l2)).toLong()
                    repeat(l2 - 1) { i++; v = (v shl 8) or (payload[i].toLong() and 0xFF) }
                    i++
                    prev = if (k == 0) v else prev + (v - ((1L shl (7 * l2 - 1)) - 1))
                    if (prev < 0 || prev > payload.size) return null
                    sizes[k] = prev.toInt()
                }
            }
        }
        var used = 0L
        for (k in 0 until frameCountMinus1) {
            if (sizes[k] < 0) return null
            used += sizes[k]
        }
        val lastSize = payload.size - i - used
        if (lastSize < 0) return null
        sizes[frameCountMinus1] = lastSize.toInt()
        val frames = ArrayList<ByteArray>(sizes.size)
        for (k in sizes.indices) {
            if (i + sizes[k] > payload.size) return null
            frames.add(payload.copyOfRange(i, i + sizes[k]))
            i += sizes[k]
        }
        return frames
    }

    /**
     * Encola los fotogramas de un bloque que empieza en [at]. Con lacing, la duración del bloque
     * se reparte por **posición**: un fotograma cuya compresión no se puede deshacer se descarta
     * dejando su hueco, y los siguientes conservan su tiempo. Hasta la 3.0.0 se numeraban solo los
     * que quedaban, así que cada descarte adelantaba el resto del bloque, y se avisaba una vez por
     * fotograma: 255 avisos para un solo bloque dañado. Ahora es un aviso por bloque, y también lo
     * hay para un bloque cuyo lacing no cuadra, que se descartaba en silencio.
     *
     * La duración es la de `BlockDuration` si el bloque la trae, repartida entre sus fotogramas;
     * si no, la `DefaultDuration` de la pista. Es lo que manda la especificación, y hasta ahora un
     * bloque sin lacing con las dos tomaba la de la pista.
     */
    private fun emitBlock(
        track: TrackInfo,
        block: BlockHeader,
        payload: ByteArray,
        keyOverride: Boolean?,
        blockDurationTicks: Long,
        simpleFlags: Boolean,
        at: Long,
    ) {
        val number = block.trackNumber
        val keyFrame = keyOverride ?: (track is TrackInfo.Audio || (simpleFlags && (block.flags and 0x80) != 0))
        val lacing = (block.flags shr 1) and 0x03
        val rawFrames = if (lacing == LACING_NONE) listOf(payload) else splitLaces(payload, lacing)
        if (rawFrames == null) {
            warn("pista $number: se descarta el bloque en $at, cuyo lacing no cuadra con sus ${payload.size} bytes")
            return
        }

        val encodings = header.contentEncodings[number]
        val frames: List<ByteArray?> = if (encodings == null) {
            rawFrames
        } else {
            val decoded = decoder.decodeFrames(rawFrames, encodings)
            if (decoded.discarded > 0) {
                val what = if (rawFrames.size == 1) "se descarta un bloque" else "se descartan ${decoded.discarded} de ${rawFrames.size} fotogramas de un bloque"
                warn("pista $number: $what cuya compresión no se pudo deshacer (${decoded.firstCause})")
            }
            decoded.frames
        }

        val basePtsUs = ticksToUs(clusterTimestampTicks + block.relative)
        val defaultNs = header.defaultDurationNs[number] ?: 0L
        val explicitDurUs = if (blockDurationTicks >= 0) ticksToUs(blockDurationTicks) else 0L
        val perFrameUs = when {
            explicitDurUs > 0 -> explicitDurUs / frames.size
            defaultNs > 0 -> defaultNs / 1000
            else -> 0L
        }
        for ((k, frame) in frames.withIndex()) {
            if (frame == null) continue
            val pts = basePtsUs + k * perFrameUs
            pending.add(
                MediaPacket(
                    trackId = number,
                    data = frame,
                    ptsUs = pts,
                    dtsUs = pts,
                    isKeyFrame = keyFrame,
                    durationUs = perFrameUs,
                ),
            )
        }
    }

    /**
     * Se coloca en el último keyframe de la pista ancla —la primera de vídeo, o la primera si no
     * hay vídeo— con `pts <= `[timestampUs], y devuelve su `pts`. Es el mismo contrato que
     * `Mp4Demuxer`:
     *
     * - Con índice, el último cue de esa pista en o antes del destino, por bisección, y se recorren
     *   las cabeceras de los bloques desde el cue anterior hasta el destino (ver
     *   [scanForKeyFrame]): un cue no tiene por qué marcar cada keyframe —FFmpeg solo marca el
     *   primero de cada cluster—, ni su cluster empezar en él.
     * - Sin índice, o con un destino anterior al primer cue, lo mismo desde el primer cluster.
     *   Antes del primer cue se tomaba ese cue tal cual —su cluster y su tiempo—, y eso fallaba
     *   de dos maneras: se leía desde su cluster y no desde el anterior, perdiendo el audio que
     *   ya sonaba en el keyframe, y su tiempo no tiene por qué ser el del keyframe. Con tiempos
     *   negativos no lo es nunca —`CueTime` no tiene signo y el muxer lo acota a 0—: buscar antes
     *   del principio devolvía 0 y entregaba desde el siguiente keyframe, un segundo más tarde.
     *
     * La lectura empieza **un cluster antes** que el del keyframe y, pasado este, sigue hasta que
     * cada una de las demás pistas da un paquete posterior, para que el de audio que suena en ese
     * instante esté entre lo leído tanto si el muxer lo dejó en el cluster anterior como si lo
     * escribió detrás del keyframe, y para saber qué pista ha terminado ya; lo de la pista ancla
     * anterior al keyframe se descarta sin leer su carga. Las demás pistas siguen por tiempo de
     * presentación, como en MP4 (ver `MkvSeekFilter`).
     *
     * Hasta la 3.x se devolvía el tiempo del cue o, sin índice, el del cluster, y se leía desde el
     * principio de ese cluster: con un archivo cuyos clusters no empiezan en keyframe —los de
     * otros muxers, o cualquiera sin índice— salían primero fotogramas que no se pueden
     * decodificar, y el tiempo devuelto no era el de ningún keyframe.
     */
    override fun seekTo(timestampUs: Long): Long {
        check(!closed) { CLOSED }
        pending.clear()
        seekFilter = null
        eof = false
        clusterEnd = -1
        val anchor = seekAnchor
        val firstCluster = header.firstClusterPosition
        if (anchor == null || firstCluster == null) {
            eof = true
            return 0L
        }
        awaitingKeyFrame.clear()
        val index = lastCueIndexAtOrBefore(anchorCues, timestampUs)
        val known = anchorCues.getOrNull(index)?.let { KeyFrameAt(header.segmentDataStart + it.clusterPosition, ticksToUs(it.timeTicks)) }
        val from = anchorCues.getOrNull(index - 1)?.let { header.segmentDataStart + it.clusterPosition } ?: firstCluster
        val landing = scanForKeyFrame(from, timestampUs, anchor, known) ?: KeyFrameAt(firstCluster, 0L)
        input.position = landing.readFrom
        seekFilter = MkvSeekFilter(anchor, landing.ptsUs, header.tracks)
        return landing.ptsUs
    }

    /** La pista ancla de [seekTo]: la primera de vídeo, o la primera si no hay vídeo. */
    private val seekAnchor: Int? by lazy { (tracks.firstOrNull { it is TrackInfo.Video } ?: tracks.firstOrNull())?.id }

    /** Los cues de [seekAnchor], ya ordenados; todos si ninguno declara esa pista. */
    private val anchorCues: List<MkvCue> by lazy { header.cues.filter { it.track == seekAnchor }.ifEmpty { header.cues } }

    /** Índice del último cue de [sorted] en o antes de [timestampUs], o -1 si no hay ninguno. */
    private fun lastCueIndexAtOrBefore(sorted: List<MkvCue>, timestampUs: Long): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (ticksToUs(sorted[mid].timeTicks) <= timestampUs) low = mid + 1 else high = mid
        }
        return low - 1
    }

    /**
     * El último keyframe de [anchor] con `pts <= `[timestampUs], recorriendo desde [from] los
     * clusters y las cabeceras de sus bloques, sin leer las cargas; [known] es el que ya se sabe
     * que hay, el de un cue. Si no aparece ninguno antes del destino, el primero que aparezca.
     * Devuelve dónde empezar a leer —el cluster anterior al del keyframe, o el suyo si es el
     * primero recorrido— y su `pts`, o `null` si no se encuentra ningún keyframe legible de esa
     * pista.
     *
     * Para en el primer cluster posterior al destino, así que desde un cue cuesta lo que ocupen
     * los bloques hasta el siguiente, y sin índice lo que ocupen hasta el destino. Ante un archivo
     * dañado o truncado se queda con lo mejor que haya encontrado; un error de E/S de verdad se
     * propaga, que antes se tragaba y la búsqueda devolvía en silencio un valor de reserva.
     */
    private fun scanForKeyFrame(from: Long, timestampUs: Long, anchor: Int, known: KeyFrameAt?): KeyFrameAt? {
        val saved = input.position
        val anchorIsAudio = header.tracks[anchor] is TrackInfo.Audio
        var best: KeyFrameAt? = known
        var first: KeyFrameAt? = known
        var previous = -1L
        try {
            var pos = from
            while (pos in 0 until header.segmentEnd && pos < input.length) {
                val walk = walkCluster(pos, anchor, anchorIsAudio)
                val past = walk.timeUs != null && walk.timeUs > timestampUs
                if (past && best != null) break
                val readFrom = if (previous >= 0) previous else pos
                for (key in walk.keyFramesUs) {
                    if (first == null) first = KeyFrameAt(readFrom, key)
                    if (key <= timestampUs) best = KeyFrameAt(readFrom, key)
                }
                if (past && first != null) break
                if (walk.timeUs != null) previous = pos
                pos = walk.next ?: break
            }
        } catch (e: Exception) {
            if (!EbmlReader.isCorruptData(e)) throw e
        } finally {
            input.position = saved
        }
        return best ?: first
    }

    /**
     * Recorre el elemento que empieza en [start] sin entregar nada. Si es un cluster, devuelve su
     * tiempo y los `pts` de los keyframes de [anchor] en el orden del archivo; en todo caso, dónde
     * empieza el elemento siguiente, o `null` si no se puede saber.
     *
     * Admite clusters de tamaño desconocido —los de una grabación cortada antes de cerrarse—. Un
     * cluster acaba en el primer id de nivel 1, como al leerlo ([advanceUnchecked]): acabarlo solo
     * en el siguiente cluster contaba un keyframe suelto detrás de unos `Tags` que `readPacket` no
     * entrega, y la búsqueda caía en él sin entregar nada.
     *
     * El criterio de keyframe es el de [emitBlock], y solo cuenta un bloque entero en el archivo,
     * que es el que `readPacket` entrega. Con la cabecera entera y la carga cortada —una grabación
     * que murió escribiendo el keyframe que abre el cluster en curso—, la búsqueda al final caía en
     * él: el filtro tiraba todo el vídeo anterior y el bloque terminaba la lectura, así que no salía
     * ni un fotograma.
     */
    private fun walkCluster(start: Long, anchor: Int, anchorIsAudio: Boolean): ClusterWalk {
        input.position = start
        val el = reader.readElement()
        if (el.id != MatroskaIds.CLUSTER) return ClusterWalk(null, emptyList(), if (el.size >= 0) el.dataEnd else null)
        val end = if (el.size >= 0) minOf(el.dataEnd, header.segmentEnd) else header.segmentEnd
        var ticks: Long? = null
        val keys = ArrayList<Long>()
        while (input.position < end && input.remaining > 0) {
            val childStart = input.position
            val child = reader.readElement()
            if (child.id in MatroskaIds.TOP_LEVEL) return ClusterWalk(ticks?.let(::ticksToUs), keys, childStart)
            if (child.size < 0) return ClusterWalk(ticks?.let(::ticksToUs), keys, null)
            var block: BlockHeader? = null
            var key = false
            when (child.id) {
                MatroskaIds.CLUSTER_TIMESTAMP -> ticks = readClusterTimestamp(child)
                MatroskaIds.SIMPLE_BLOCK -> {
                    block = readBlockHeader(child)
                    key = anchorIsAudio || (block != null && (block.flags and 0x80) != 0)
                }
                MatroskaIds.BLOCK_GROUP -> {
                    var referenced = false
                    while (input.position < child.dataEnd) {
                        val part = reader.readElement()
                        if (part.size < 0) break
                        when (part.id) {
                            MatroskaIds.BLOCK -> block = readBlockHeader(part)?.takeIf { part.dataEnd <= input.length }
                            MatroskaIds.REFERENCE_BLOCK -> referenced = true
                        }
                        input.position = part.dataEnd
                    }
                    key = !referenced
                }
            }
            input.position = child.dataEnd
            if (key && block != null && child.dataEnd <= input.length && block.trackNumber == anchor) keys += ticksToUs((ticks ?: 0L) + block.relative)
        }
        return ClusterWalk(ticks?.let(::ticksToUs), keys, if (el.size >= 0) el.dataEnd else null)
    }

    /**
     * Libera el descompresor y cierra la entrada. Idempotente. Lo pendiente se suelta, y desde
     * aquí [readPacket] y [seekTo] lanzan: antes seguían entregando lo que quedaba en la cola, y
     * un bloque comprimido creaba un `Inflater` nuevo que ya nadie cerraba.
     */
    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        seekFilter = null
        try {
            decoder.close()
        } finally {
            input.close()
        }
    }

    /**
     * Constantes internas. Van marcadas `private` una a una y no solo el `companion`: un
     * `const val` de un objeto compañero se compila como campo estático de la clase que lo
     * contiene y con **su propia** visibilidad, así que sin el modificador se colarían en la
     * API pública y en `public-api.txt`.
     */
    private companion object {
        private const val LACING_NONE = 0
        private const val LACING_XIPH = 1
        private const val LACING_FIXED = 2
        private const val LACING_EBML = 3

        /** Pistas sin declarar de las que se avisa; de las demás, sus bloques se descartan sin más. */
        private const val MAX_UNKNOWN_TRACK_WARNINGS = 64

        private const val CLOSED = "el MkvDemuxer está cerrado"
    }
}
