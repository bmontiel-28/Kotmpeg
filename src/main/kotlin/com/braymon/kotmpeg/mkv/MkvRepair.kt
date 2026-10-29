package com.braymon.kotmpeg.mkv

import com.braymon.kotmpeg.RepairResult
import com.braymon.kotmpeg.ebml.EbmlElement
import com.braymon.kotmpeg.ebml.EbmlException
import com.braymon.kotmpeg.ebml.EbmlReader
import com.braymon.kotmpeg.ebml.EbmlWriter
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.io.RepairTarget
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.Timestamps
import java.io.ByteArrayOutputStream
import java.io.EOFException

/**
 * Cierra en el sitio un MKV que se quedó sin cerrar, como lo habría cerrado [MkvMuxer.stop]: es
 * `Kotmpeg.repair` para Matroska.
 *
 * Un archivo así tiene el `Segment` de tamaño desconocido —es lo último que escribe `stop()`— y,
 * como mucho, un cluster sin terminar al final. La reparación:
 *
 * 1. Recorre los clusters leyendo solo las cabeceras de los bloques, y corta el archivo tras el
 *    último bloque entero. Si el último cluster no tiene ninguno, lo quita entero.
 * 2. Escribe el tamaño de los clusters que no lo tenían: el último, como mínimo.
 * 3. Reconstruye el índice `Cues` con el criterio del muxer (ver `MkvTimeline`). Si lo que el muxer
 *    publicó en la reserva es su principio y el resto cabe, lo añade ahí; si no, como `stop()`, lo
 *    escribe al final, apunta a él el `SeekHead` y anula la reserva.
 * 4. Escribe la `Duration` y, lo último, el tamaño del `Segment`.
 *
 * El orden importa: hasta el último paso el archivo sigue pareciendo sin cerrar, así que si la
 * reparación se interrumpe, se puede volver a lanzar, y acaba como si no se hubiera cortado. Y
 * mientras tanto sigue siendo legible, porque no reescribe nada que un lector pueda estar usando: lo
 * que añade a la reserva o al `SeekHead` se confirma con un byte (ver `MkvLayout`), lo demás son
 * parches de 4 a 9 bytes, y un índice entero al final igual al que calcula —el que deja un `stop()`
 * o una reparación cortados justo después— se queda como está. La única excepción es un índice
 * delante de los clusters en el que el nuevo no cabe, en un archivo sin `SeekHead` que pueda apuntar
 * a otro al final: ahí no queda más salida que reescribir la reserva entera. El muxer no deja
 * archivos así.
 *
 * La duración es la que daría reempaquetar el archivo con [MkvMuxer]: la de `MkvTimeline` con los
 * paquetes como los entrega el demuxer. Solo los de las pistas que lee, uno por fotograma de cada
 * bloque con lacing, y con la duración de su `BlockDuration` o, si no la trae, la `DefaultDuration`
 * de su pista.
 *
 * **No modifica nada hasta haberlo leído todo y comprobado que sabe cerrarlo.** Lo que no reconoce
 * —otro contenedor, una cabecera incompleta o con un hijo que se sale de su padre, daños en mitad
 * del archivo en vez de al final, un elemento tras los clusters que no sabría conservar— se rechaza
 * con `IllegalArgumentException` y el archivo queda como estaba. Un archivo con el `Segment` de
 * tamaño conocido ya está cerrado y tampoco se toca.
 */
internal class MkvRepair(private val target: RepairTarget) {

    private val input = target.input
    private val reader = EbmlReader(input)
    private val length = target.length

    private class Header(val start: Long, val element: EbmlElement) {
        val id: Long get() = element.id
        val size: Long get() = element.size
        val dataStart: Long get() = element.dataStart
        val end: Long get() = element.dataEnd
        val sizeFieldPos: Long get() = start + EbmlWriter.idLength(id)
        val sizeFieldLength: Int get() = (dataStart - sizeFieldPos).toInt()
    }

    /**
     * Una pista que lee el demuxer: su número, si es de vídeo, su `DefaultDuration` y dónde acaba
     * (ver `MkvTimeline`).
     */
    private class Track(val number: Long, val video: Boolean, val defaultDurationNs: Long) {
        val end = MkvTimeline.Track(defaultDurationNs)
    }

    /** Pista, desfase con signo y flags de la cabecera de un bloque, y cuántos fotogramas lleva. */
    private class BlockHeader(val track: Long, val relative: Int, val flags: Int, val frames: Int)

    /**
     * Un bloque entero: su pista, su tiempo en tics, si es keyframe, cuántos fotogramas lleva y su
     * `BlockDuration` en tics (-1 si no la trae).
     */
    private class Block(val track: Long, val time: Long, val key: Boolean, val frames: Int, val durationTicks: Long)

    /** Un cluster que se conserva: dónde acaba y si hay que escribirle el tamaño. */
    private class Cluster(val header: Header, val end: Long) {
        val needsSize: Boolean get() = header.size < 0 || header.end != end
    }

    private var segmentDataStart = 0L
    private var timestampScale = 1_000_000L
    private var infoPos = -1L
    private var durationPos = -1L
    private var durationSize = 0L
    private var durationTicks = 0.0
    private var tracksPos = -1L
    private val tracks = LinkedHashMap<Long, Track>()

    /**
     * El índice de los bloques que se conservan, que se va calculando según se recorren los
     * clusters. Se crea al acabar la cabecera, cuando ya se sabe la pista por la que se indexa.
     */
    private var timeline = MkvTimeline(0)

    /**
     * El `SeekHead` al principio del segmento: dónde está la posición de su entrada `Cues` (-1 si
     * no la tiene) y su `Void` libre final (-1 si no lo tiene).
     */
    private class SeekHead(val header: Header, val cuesPositionPos: Long, val cuesPositionLength: Int, val freePos: Long, val freeHeaderLength: Int)

    private var seekHead: SeekHead? = null

    /**
     * Un `Void` al principio del segmento: el hueco del `SeekHead` en un archivo de la 4.0, o lo que
     * queda de él —su cabecera— cuando una reparación ya escribió el `SeekHead` detrás.
     */
    private var seekHeadVoid: Header? = null

    /** El `Cues` delante de los clusters, que es la reserva del muxer. */
    private var reserve: Header? = null

    /**
     * Un `Cues` entero que acaba justo al final del archivo, tras los clusters: lo deja ahí un
     * `stop()` o una reparación que se cortaron después de escribir el índice al final.
     */
    private var trailingCues: Header? = null

    fun run(): RepairResult {
        val clusters = ArrayList<Cluster>()
        val (segment, keepEnd) = readable { analyze(clusters) } ?: return RepairResult(
            ContainerFormat.MKV, false, MkvHeaderParser.declaredDurationUs(durationTicks, timestampScale), 0,
        )
        val plan = plan(segment, clusters, keepEnd)
        apply(plan)
        return plan.result
    }

    /**
     * Lee todo lo que la reparación necesita y devuelve el `Segment` y dónde acaban los datos que
     * se conservan, o `null` si ya está cerrado.
     */
    private fun analyze(clusters: MutableList<Cluster>): Pair<Header, Long>? {
        val ebmlHeader = headerAt(0)
        if (ebmlHeader == null || ebmlHeader.id != MatroskaIds.EBML || ebmlHeader.size < 0 || ebmlHeader.end > length) {
            reject("no empieza por una cabecera EBML completa")
        }
        checkDocType(ebmlHeader)
        val segment = headerAt(ebmlHeader.end)
        if (segment == null || segment.id != MatroskaIds.SEGMENT) reject("no tiene un Segment tras la cabecera EBML")
        segmentDataStart = segment.dataStart
        if (segment.size >= 0 && segment.end > length) {
            reject("el Segment declara más bytes de los que tiene el archivo: no es una grabación interrumpida")
        }

        val headersEnd = parseHeaders()
        if (segment.size >= 0) return null
        if (infoPos < 0 || durationPos < 0) reject("no tiene Info con Duration")
        if (tracksPos < 0) reject("no tiene pistas")
        if (tracks.isEmpty()) reject("ninguna de sus pistas es de un códec que se sepa leer")
        timeline = MkvTimeline(MkvTimeline.cueTrack(tracks.values) { it.video }.number)
        val keepEnd = if (headersEnd.truncated) headersEnd.position else walkClusters(headersEnd.position, clusters)
        return segment to keepEnd
    }

    /**
     * [block] con los errores de lectura convertidos en rechazo: un elemento mal formado donde se
     * esperaba uno legible es un archivo que la reparación no reconoce, y todavía no se ha escrito
     * nada.
     */
    private fun <T> readable(block: () -> T): T = try {
        block()
    } catch (e: EbmlException) {
        reject("estructura ilegible (${e.message})")
    } catch (e: EOFException) {
        reject("estructura cortada (${e.message})")
    }

    /**
     * Recorre los hijos de [parent], que tiene que tener tamaño, con [action], que recibe dónde
     * empieza cada uno; tras cada hijo, la lectura sigue donde acaba. Un hijo de tamaño desconocido
     * o que se sale de su padre se rechaza como [what] mal formado: lo que la reparación parchea
     * —la posición del índice, la duración— caería fuera del elemento, encima del siguiente, y lo
     * que lee sería parte de otro.
     */
    private inline fun forEachChild(parent: EbmlElement, what: String, action: (start: Long, child: EbmlElement) -> Unit) {
        var pos = parent.dataStart
        while (pos < parent.dataEnd) {
            input.position = pos
            val child = reader.readElement()
            if (child.size < 0 || child.dataEnd > parent.dataEnd) reject("$what está mal formado")
            action(pos, child)
            pos = child.dataEnd
        }
    }

    /**
     * Comprueba el `DocType`. Uno de más de [MAX_DOCTYPE_BYTES] no puede ser ninguno de los dos que
     * se admiten, y se rechaza sin leerlo: su tamaño solo está acotado por el del archivo.
     */
    private fun checkDocType(ebmlHeader: Header) {
        var docType = "matroska"
        forEachChild(ebmlHeader.element, "la cabecera EBML") { _, el ->
            if (el.id == MatroskaIds.DOCTYPE) {
                if (el.size > MAX_DOCTYPE_BYTES) reject("doctype no soportado (de ${el.size} bytes)")
                docType = reader.readString(el)
            }
        }
        if (docType != "matroska" && docType != "webm") reject("doctype no soportado: $docType")
    }

    private class HeadersEnd(val position: Long, val truncated: Boolean)

    /**
     * Lee lo que hay delante del primer cluster y devuelve dónde empieza. Si lo corta el final del
     * archivo —el muxer no llegó a terminar de escribir la reserva— o un resto de ceros —una
     * grabación que reservó el espacio y murió antes de su primer cluster—, devuelve ese punto como
     * corte. Los ceros se aceptaban como corte tras los clusters y no aquí, y ese archivo, que el
     * demuxer abre, se rechazaba.
     *
     * El `SeekHead` se busca al principio del segmento o, en un archivo de la 4.0 cuya reparación
     * se cortó tras escribirlo, justo detrás de lo que queda del `Void` que ocupaba su hueco (ver
     * [seekHeadWrites]). Sin eso, el relanzamiento tomaba ese `Void` de 2 bytes por el hueco, no
     * cabía nada en él y el archivo se quedaba sin arreglo.
     */
    private fun parseHeaders(): HeadersEnd {
        var pos = segmentDataStart
        while (pos < length) {
            val el = try {
                headerAt(pos)
            } catch (e: EbmlException) {
                requireZeroTail(pos, e)
                null
            } ?: return HeadersEnd(pos, truncated = true)
            if (el.id == MatroskaIds.CLUSTER) return HeadersEnd(pos, truncated = false)
            if (el.size < 0) reject("el elemento 0x${el.id.toString(16)} tiene tamaño desconocido")
            if (el.end > length) {
                if (el.id == MatroskaIds.INFO || el.id == MatroskaIds.TRACKS) reject("la cabecera está cortada")
                return HeadersEnd(pos, truncated = true)
            }
            when (el.id) {
                MatroskaIds.SEEK_HEAD -> if (pos == segmentDataStart || pos == seekHeadVoid?.end) seekHead = parseSeekHead(el)
                MatroskaIds.VOID -> if (pos == segmentDataStart) seekHeadVoid = el
                MatroskaIds.INFO -> parseInfo(el)
                MatroskaIds.TRACKS -> parseTracks(el)
                MatroskaIds.CUES -> if (reserve == null) reserve = el
            }
            pos = el.end
        }
        return HeadersEnd(pos, truncated = false)
    }

    /** Lee del `SeekHead` lo que la reparación puede tener que parchear. */
    private fun parseSeekHead(seekHead: Header): SeekHead {
        var cuesPositionPos = -1L
        var cuesPositionLength = 0
        var freePos = -1L
        var freeHeaderLength = 0
        try {
            forEachChild(seekHead.element, "el SeekHead") { start, child ->
                when (child.id) {
                    MatroskaIds.SEEK -> {
                        var target = 0L
                        var positionPos = -1L
                        var positionLength = 0
                        forEachChild(child, "el SeekHead") { _, el ->
                            when (el.id) {
                                MatroskaIds.SEEK_ID -> target = reader.readSeekId(el)
                                MatroskaIds.SEEK_POSITION -> {
                                    positionPos = el.dataStart
                                    positionLength = if (el.size in 1..8) el.size.toInt() else 0
                                }
                            }
                        }
                        if (target == MatroskaIds.CUES && positionPos >= 0) {
                            cuesPositionPos = positionPos
                            cuesPositionLength = positionLength
                        }
                    }
                    MatroskaIds.VOID -> if (child.dataEnd == seekHead.end) {
                        freePos = start
                        freeHeaderLength = (child.dataStart - start).toInt()
                    }
                }
            }
        } catch (e: EbmlException) {
            reject("el SeekHead está dañado (${e.message})")
        } catch (e: EOFException) {
            reject("el SeekHead está dañado (${e.message})")
        }
        return SeekHead(seekHead, cuesPositionPos, cuesPositionLength, freePos, freeHeaderLength)
    }

    private fun parseInfo(info: Header) {
        infoPos = info.start
        forEachChild(info.element, "Info") { _, el ->
            when (el.id) {
                MatroskaIds.TIMESTAMP_SCALE -> timestampScale = reader.readUInt(el).takeIf { it > 0 } ?: 1_000_000L
                MatroskaIds.DURATION -> {
                    if (el.size != 4L && el.size != 8L) reject("Duration de ${el.size} bytes")
                    durationPos = el.dataStart
                    durationSize = el.size
                    durationTicks = reader.readFloat(el)
                }
            }
        }
    }

    /**
     * Se queda con las pistas que entrega el demuxer: un número que cabe en un `Int` y un códec que
     * lee (ver `MkvHeaderParser.readsCodec`). La duración y el índice son los de reempaquetar el
     * archivo, y una pista de subtítulos con bloques en 10 y 50 s hacía declarar 90 s a un vídeo de
     * 52. Como en el demuxer, una entrada que no lee no sustituye a una anterior con su número.
     *
     * Un `CodecID` de más de [MAX_CODEC_ID_BYTES] no es de ningún códec que se lea, y no se lee:
     * su tamaño solo está acotado por el del archivo.
     */
    private fun parseTracks(tracksElement: Header) {
        tracksPos = tracksElement.start
        forEachChild(tracksElement.element, "Tracks") { _, entry ->
            if (entry.id == MatroskaIds.TRACK_ENTRY) {
                var number = 0L
                var type = 0L
                var codecId = ""
                var defaultDuration = 0L
                forEachChild(entry, "Tracks") { _, el ->
                    when (el.id) {
                        MatroskaIds.TRACK_NUMBER -> number = reader.readUInt(el)
                        MatroskaIds.TRACK_TYPE -> type = reader.readUInt(el)
                        MatroskaIds.CODEC_ID -> codecId = if (el.size <= MAX_CODEC_ID_BYTES) reader.readString(el) else ""
                        MatroskaIds.DEFAULT_DURATION -> defaultDuration = reader.readUInt(el)
                    }
                }
                if (number in 1..Int.MAX_VALUE && MkvHeaderParser.readsCodec(type, codecId)) {
                    tracks[number] = Track(number, type == MatroskaIds.TRACK_TYPE_VIDEO, defaultDuration)
                }
            }
        }
    }

    /**
     * Recorre los clusters desde [firstCluster], añade a [clusters] los que se conservan y devuelve
     * dónde acaban los datos que se conservan: tras el último bloque entero.
     *
     * Detrás del último cluster solo se acepta como final lo que dejaría un corte: algo que se sale
     * del archivo, un resto de ceros o el `Cues` que escriben `stop()` y la reparación. Un `Void`
     * entero es relleno, y lo que viene detrás se sigue recorriendo: tomarlo por el final tiraba
     * en silencio los clusters de detrás. Un `Cues` con más datos detrás, o cualquier otro elemento
     * entero, no se sabría conservar junto al índice nuevo, y se rechaza.
     */
    private fun walkClusters(firstCluster: Long, clusters: MutableList<Cluster>): Long {
        var pos = firstCluster
        while (pos < length) {
            val el = try {
                headerAt(pos)
            } catch (e: EbmlException) {
                requireZeroTail(pos, e)
                null
            } ?: return pos
            when (el.id) {
                MatroskaIds.CLUSTER -> {
                    val walk = walkCluster(el)
                    if (!walk.complete && !walk.closed) {
                        if (walk.blocks == 0) return pos
                        clusters.add(Cluster(el, walk.end))
                        return walk.end
                    }
                    clusters.add(Cluster(el, walk.end))
                    pos = walk.end
                }
                MatroskaIds.VOID -> {
                    if (el.size < 0 || el.end >= length) return pos
                    pos = el.end
                }
                MatroskaIds.CUES -> {
                    if (el.size < 0 || el.end > length) return pos
                    if (el.end == length) {
                        trailingCues = el
                    } else if (!isZeroTail(el.end)) {
                        reject("tras los clusters hay un Cues con más datos detrás, y un segmento solo puede tener un índice")
                    }
                    return pos
                }
                else -> {
                    if (el.size < 0 || el.end <= length) {
                        reject("tras los clusters hay un elemento 0x${el.id.toString(16)} que la reparación no sabría conservar")
                    }
                    return pos
                }
            }
        }
        return pos
    }

    /**
     * Hasta dónde llegan los hijos enteros de un cluster ([end]) y cuántos bloques tiene. [complete]:
     * el cluster tiene tamaño y está entero. [closed]: no tiene tamaño, pero detrás empieza un
     * elemento de nivel 1, así que acaba ahí. Si no es ninguna de las dos, el archivo se acaba
     * dentro de él.
     */
    private class ClusterWalk(val end: Long, val blocks: Int, val complete: Boolean, val closed: Boolean)

    /**
     * Lee las cabeceras de los hijos de un cluster, sin sus cargas, y cuenta sus bloques para el
     * índice y la duración (ver [count]).
     *
     * Solo se acepta como corte un final de archivo: un hijo que se sale del archivo, o un resto de
     * ceros (lo que deja un sistema de archivos que llegó a reservar el espacio sin escribirlo). Un
     * hijo imposible en mitad de un cluster que sí está entero es un daño de otro tipo, y se rechaza
     * en vez de tirar todo lo que viene detrás.
     *
     * Uno de tamaño desconocido acaba en el primer id de nivel 1, como dice Matroska y como lo lee
     * el demuxer, y no solo ante otro cluster: un `Cues` o unos `Tags` detrás no son suyos, y
     * tomarlos por hijos los metía dentro del cluster al escribirle el tamaño.
     */
    private fun walkCluster(cluster: Header): ClusterWalk {
        val declaredEnd = if (cluster.size >= 0) cluster.end else -1L
        val limit = if (declaredEnd in 0..length) declaredEnd else length
        var p = cluster.dataStart
        var timestamp: Long? = null
        var blocks = 0
        while (p < limit) {
            val child = try {
                headerAt(p)
            } catch (e: EbmlException) {
                requireZeroTail(p, e)
                null
            } ?: break
            if (child.id == MatroskaIds.CLUSTER && declaredEnd >= 0) reject("un cluster dentro de otro en $p")
            if (declaredEnd < 0 && child.id in MatroskaIds.TOP_LEVEL) return ClusterWalk(p, blocks, complete = false, closed = true)
            if (child.size < 0) reject("elemento de tamaño desconocido dentro de un cluster en $p")
            if (child.end > limit) {
                if (limit < length) reject("un elemento se sale de su cluster en $p")
                break
            }
            when (child.id) {
                MatroskaIds.CLUSTER_TIMESTAMP -> timestamp = reader.readUInt(child.element)
                MatroskaIds.SIMPLE_BLOCK -> { count(cluster, simpleBlock(child, timestamp)); blocks++ }
                MatroskaIds.BLOCK_GROUP -> { count(cluster, blockGroup(child, timestamp)); blocks++ }
            }
            p = child.end
        }
        return ClusterWalk(p, blocks, complete = p == declaredEnd, closed = false)
    }

    /**
     * Cuenta un bloque entero de [cluster] para el índice y para la duración, como lo entregaría el
     * demuxer (ver `MkvDemuxer.emitBlock`): un paquete por fotograma, cada uno con el PTS
     * redondeado a ms del muxer y la duración del bloque repartida entre ellos o, si no la trae, la
     * `DefaultDuration` de la pista. Contar solo el primero con la `DefaultDuration` dejaba la
     * reparación corta: 467 ms con una `BlockDuration` de 500 sobre una pista de 33, y cuatro de
     * cada cinco fotogramas de un bloque de audio con lacing. Solo se cuentan bloques enteros, y un
     * cluster con alguno se conserva siempre.
     */
    private fun count(cluster: Header, block: Block) {
        timeline.block(cluster.start - segmentDataStart, block.track, block.key, block.time)
        val track = tracks[block.track] ?: return
        val startUs = ticksToUs(block.time)
        val blockUs = if (block.durationTicks >= 0) ticksToUs(block.durationTicks) else 0L
        val frameUs = when {
            blockUs > 0 -> blockUs / block.frames
            track.defaultDurationNs > 0 -> track.defaultDurationNs / 1000
            else -> 0L
        }
        for (k in 0 until block.frames) track.end.add(Timestamps.rescaleRounded(startUs + k * frameUs, 1, 1000), frameUs)
    }

    private fun simpleBlock(el: Header, timestamp: Long?): Block {
        if (timestamp == null) reject("bloque sin marca de tiempo de cluster en ${el.start}")
        val header = readBlockHeader(el)
        return Block(header.track, timestamp + header.relative, (header.flags and 0x80) != 0, header.frames, durationTicks = -1)
    }

    private fun blockGroup(group: Header, timestamp: Long?): Block {
        if (timestamp == null) reject("bloque sin marca de tiempo de cluster en ${group.start}")
        input.position = group.dataStart
        var block: BlockHeader? = null
        var referenced = false
        var durationTicks = -1L
        while (input.position < group.end) {
            val start = input.position
            val el = reader.readElement()
            if (el.size < 0 || el.dataEnd > group.end) reject("BlockGroup mal formado en ${group.start}")
            when (el.id) {
                MatroskaIds.BLOCK -> block = readBlockHeader(Header(start, el))
                MatroskaIds.REFERENCE_BLOCK -> referenced = true
                MatroskaIds.BLOCK_DURATION -> durationTicks = reader.readUInt(el)
            }
            input.position = el.dataEnd
        }
        val header = block ?: reject("BlockGroup sin Block en ${group.start}")
        return Block(header.track, timestamp + header.relative, !referenced, header.frames, durationTicks)
    }

    /**
     * La cabecera de un bloque. Con lacing, el byte que sigue a los flags es el número de
     * fotogramas menos uno, como lo lee el demuxer; sin él, el bloque es un fotograma.
     */
    private fun readBlockHeader(el: Header): BlockHeader {
        input.position = el.dataStart
        if (el.size < 4) reject("bloque de ${el.size} bytes en ${el.start}")
        val first = input.readByte()
        val length = EbmlReader.vintLength(first)
        if (length == 0) reject("número de pista inválido en ${el.start}")
        if (el.size < length + 3) reject("bloque de ${el.size} bytes en ${el.start}")
        var track = (first and (0xFF shr length)).toLong()
        repeat(length - 1) { track = (track shl 8) or input.readByte().toLong() }
        val relative = input.readUInt(2).toInt().toShort().toInt()
        val flags = input.readByte()
        val laced = ((flags shr 1) and 0x03) != 0
        val frames = if (laced && el.size > length + 3) input.readByte() + 1 else 1
        return BlockHeader(track, relative, flags, frames)
    }

    /** Si lo que queda desde [at] es todo ceros: lo que deja un corte de luz tras reservar el espacio. */
    private fun isZeroTail(at: Long): Boolean {
        input.position = at
        val chunk = ByteArray(1 shl 16)
        var remaining = length - at
        while (remaining > 0) {
            val n = minOf(remaining, chunk.size.toLong()).toInt()
            input.readFully(chunk, 0, n)
            for (i in 0 until n) if (chunk[i].toInt() != 0) return false
            remaining -= n
        }
        return true
    }

    /** Si lo que queda desde [at] no es todo ceros, el elemento inválido es un daño y no un corte. */
    private fun requireZeroTail(at: Long, cause: Exception) {
        if (!isZeroTail(at)) reject("datos ilegibles en $at (${cause.message})")
    }

    /** Lo que la reparación va a hacer, ya comprobado: dónde se trunca y qué se escribe, en orden. */
    private class Plan(val truncateAt: Long, val writes: List<Pair<Long, ByteArray>>, val result: RepairResult)

    /**
     * Dónde queda el índice: las escrituras que lo dejan ahí, en orden; dónde acaba el segmento con
     * él, y si el `Cues` del final ya era el índice y se conserva tal cual ([keepsTrailing]).
     */
    private class IndexPlan(val writes: List<Pair<Long, ByteArray>>, val segmentEnd: Long, val keepsTrailing: Boolean)

    /**
     * Calcula todo lo que hay que escribir y comprueba que cabe en los campos que ya existen, sin
     * escribir nada todavía: lo que no sabe cerrar se rechaza aquí, con el archivo intacto. Las
     * escrituras van en el orden de la descripción de la clase.
     */
    private fun plan(segment: Header, clusters: List<Cluster>, keepEnd: Long): Plan {
        val endMs = MkvTimeline.endMs(tracks.values.map { it.end })
        val index = placeIndex(keepEnd)
        val sizePatches = clusters.filter { it.needsSize }.map { cluster ->
            val size = cluster.end - cluster.header.dataStart
            cluster.header.sizeFieldPos to encodeSize(size, cluster.header.sizeFieldLength, "el tamaño de un cluster")
        }
        val segmentSize = encodeSize(index.segmentEnd - segmentDataStart, segment.sizeFieldLength, "el tamaño del Segment")
        val durationValue = endMs.toDouble() * 1_000_000.0 / timestampScale
        val durationBytes = if (durationSize == 8L) {
            EbmlWriter.bigEndian(durationValue.toRawBits(), 8)
        } else {
            EbmlWriter.bigEndian(durationValue.toFloat().toRawBits().toLong(), 4)
        }
        val truncateAt = if (index.keepsTrailing) length else keepEnd
        val writes = sizePatches + index.writes + listOf(durationPos to durationBytes, segment.sizeFieldPos to segmentSize)
        return Plan(truncateAt, writes, RepairResult(ContainerFormat.MKV, true, endMs * 1000, length - truncateAt))
    }

    private fun apply(plan: Plan) {
        if (plan.truncateAt < length) target.truncate(plan.truncateAt)
        for ((at, bytes) in plan.writes) target.write(at, bytes)
        target.force()
    }

    /**
     * Dónde va el índice y qué hay que escribir para dejarlo ahí, con el criterio de
     * [MkvMuxer.stop]:
     *
     * - En la reserva, si lo publicado en ella es el principio del índice y el resto cabe
     *   añadiéndolo como el muxer; sin escribir nada si ya está entero, quede o no `Void` libre.
     * - Si no, al final: tras el último cluster —salvo que ya esté ahí, entero e igual—, con el
     *   `SeekHead` apuntando a él y la reserva anulada, en parches de 8 y 9 bytes. Reescribir la
     *   reserva entera, que es lo que se hacía, eran unos 64 KiB sobre el índice que un lector está
     *   usando, y un corte a mitad mezclaba cues viejos y nuevos y podía dejar el archivo sin arreglo.
     * - Reescribir la reserva queda para cuando no hay `SeekHead` que pueda apuntar al final, o la
     *   reserva no da para anularla.
     */
    private fun placeIndex(keepEnd: Long): IndexPlan {
        val cues = timeline.cues
        val reserveHeader = reserve
        if (reserveHeader != null) {
            val inReserve = appendToReserve(reserveHeader, cues)
            if (inReserve != null) {
                return IndexPlan(inReserve + seekHeadWrites(reserveHeader.start - segmentDataStart).orEmpty(), keepEnd, keepsTrailing = false)
            }
        }
        val points = encode(cues)
        val element = MkvLayout.cues(points)
        val seekWrites = seekHeadWrites(keepEnd - segmentDataStart)
        val voidable = reserveHeader == null || reserveHeader.end - reserveHeader.start >= MkvLayout.LONG_VOID_HEADER_SIZE
        if (seekWrites != null && voidable) {
            val keep = isTrailingIndex(element)
            val writes = ArrayList<Pair<Long, ByteArray>>()
            if (!keep) writes += keepEnd to element
            writes += seekWrites
            if (reserveHeader != null) writes += reserveHeader.start to MkvLayout.voidOver(reserveHeader.end - reserveHeader.start)
            return IndexPlan(writes, keepEnd + element.size, keep)
        }
        if (reserveHeader == null) reject("no hay un SeekHead con sitio para apuntar a un índice al final")
        val rewrite = rewriteReserve(reserveHeader, points)
            ?: reject("el índice no cabe en la reserva, y no se puede llevar al final: no hay un SeekHead con sitio para apuntar a él, o la reserva es demasiado pequeña para anularla")
        return IndexPlan(rewrite + seekHeadWrites(reserveHeader.start - segmentDataStart).orEmpty(), keepEnd, keepsTrailing = false)
    }

    private fun encode(cues: List<MkvTimeline.Cue>): ByteArray {
        val out = ByteArrayOutputStream()
        for (cue in cues) out.write(cue.encoded())
        return out.toByteArray()
    }

    /** Lo que hay publicado en la reserva: sus cues y su `Void` libre final ([freePos] -1 si no lo tiene). */
    private class Published(val points: List<MkvTimeline.Cue>, val freePos: Long, val freeHeaderLength: Int)

    /**
     * Las escrituras que dejan [cues] en la reserva sin tocar lo ya publicado, o `null` si no se
     * puede: ninguna si ya está entero; si lo publicado es su principio, el resto en el `Void` libre
     * con el protocolo de `MkvLayout`, si cabe.
     */
    private fun appendToReserve(reserve: Header, cues: List<MkvTimeline.Cue>): List<Pair<Long, ByteArray>>? {
        val published = publishedIndex(reserve) ?: return null
        val count = published.points.size
        if (published.points == cues) return emptyList()
        if (published.freePos < 0 || count > cues.size || cues.subList(0, count) != published.points) return null
        return MkvLayout.append(published.freePos, published.freeHeaderLength, reserve.end, encode(cues.subList(count, cues.size)))?.writes
    }

    /**
     * Lee el índice publicado en la reserva, o `null` si no se puede usar: un hijo que se sale de su
     * padre, un entero de más de 8 bytes o cualquier otra cosa que no se lea. Un índice publicado
     * inservible no impide reparar —antes, el entero de más de 8 bytes rechazaba la reparación para
     * siempre—: se deja de usar y se escribe otro.
     */
    private fun publishedIndex(reserve: Header): Published? = try {
        val points = ArrayList<MkvTimeline.Cue>()
        var freePos = -1L
        var freeHeaderLength = 0
        forEachChild(reserve.element, "el índice publicado") { start, child ->
            when (child.id) {
                MatroskaIds.CUE_POINT -> points += readCuePoint(child)
                MatroskaIds.VOID -> if (child.dataEnd == reserve.end) {
                    freePos = start
                    freeHeaderLength = (child.dataStart - start).toInt()
                }
            }
        }
        Published(points, freePos, freeHeaderLength)
    } catch (_: EbmlException) {
        null
    } catch (_: EOFException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun readCuePoint(point: EbmlElement): MkvTimeline.Cue {
        var time = -1L
        var track = 0L
        var position = -1L
        forEachChild(point, "el índice publicado") { _, el ->
            when (el.id) {
                MatroskaIds.CUE_TIME -> time = reader.readUInt(el)
                MatroskaIds.CUE_TRACK_POSITIONS -> forEachChild(el, "el índice publicado") { _, p ->
                    when (p.id) {
                        MatroskaIds.CUE_TRACK -> track = reader.readUInt(p)
                        MatroskaIds.CUE_CLUSTER_POSITION -> position = reader.readUInt(p)
                    }
                }
            }
        }
        return MkvTimeline.Cue(time, track, position)
    }

    /** Si el `Cues` que acaba el archivo ya es, byte a byte, [element]: entonces se deja como está. */
    private fun isTrailingIndex(element: ByteArray): Boolean {
        val cues = trailingCues ?: return false
        if (cues.end - cues.start != element.size.toLong()) return false
        input.position = cues.start
        return input.readBytes(element.size).contentEquals(element)
    }

    /** La escritura que deja [points] como contenido entero de la reserva, o `null` si no caben. */
    private fun rewriteReserve(reserve: Header, points: ByteArray): List<Pair<Long, ByteArray>>? {
        val free = reserve.end - reserve.dataStart - points.size
        if (free != 0L && free < 2) return null
        return listOf(reserve.dataStart to if (free > 0) points + MkvLayout.freeSpaceHeader(free) else points)
    }

    /**
     * Las escrituras que dejan el `SeekHead` apuntando al índice en [cuesPosition], o `null` si no
     * hay dónde. Con un `SeekHead` del muxer, se mueve su entrada `Cues` (8 bytes) o se añade en su
     * `Void` libre; con el `Void` de la 4.0, se escribe un `SeekHead` dentro y se confirma con un
     * byte. Así ningún corte deja a medias algo que un lector ya pudiera estar usando.
     *
     * El `SeekHead` que se escribe en el `Void` tiene el tamaño del que escribe el muxer, y si el
     * `Void` es mayor, lo que sobra sigue siendo relleno detrás: un `Void` de cientos de MB daba un
     * `SeekHead` de cientos de MB, construido en memoria.
     */
    private fun seekHeadWrites(cuesPosition: Long): List<Pair<Long, ByteArray>>? {
        seekHead?.let { head ->
            if (head.cuesPositionPos >= 0) {
                val fits = head.cuesPositionLength == 8 ||
                    (head.cuesPositionLength in 1..7 && cuesPosition ushr (8 * head.cuesPositionLength) == 0L)
                if (!fits) return null
                return listOf(head.cuesPositionPos to EbmlWriter.bigEndian(cuesPosition, head.cuesPositionLength))
            }
            if (head.freePos < 0) return null
            return MkvLayout.append(head.freePos, head.freeHeaderLength, head.header.end, MkvLayout.seekEntry(MatroskaIds.CUES, cuesPosition))?.writes
        }
        val slot = seekHeadVoid ?: return null
        val headerLength = (slot.dataStart - slot.start).toInt()
        val room = slot.end - slot.dataStart
        val size = if (room >= MkvLayout.SEEK_HEAD_RESERVED + 2) MkvLayout.SEEK_HEAD_RESERVED else room.toInt()
        val targets = listOf(
            MatroskaIds.INFO to infoPos - segmentDataStart,
            MatroskaIds.TRACKS to tracksPos - segmentDataStart,
            MatroskaIds.CUES to cuesPosition,
        )
        val head = MkvLayout.seekHead(targets, size) ?: return null
        val rest = room - size
        val bytes = if (rest > 0) head.bytes + MkvLayout.freeSpaceHeader(rest) else head.bytes
        return listOf(slot.dataStart to bytes, slot.start + 1 to byteArrayOf(MkvLayout.separator(headerLength)))
    }

    private fun encodeSize(size: Long, fieldLength: Int, what: String): ByteArray {
        if (fieldLength !in 1..8 || size >= (1L shl (7 * fieldLength)) - 1) {
            reject("$what ($size) no cabe en su campo de $fieldLength bytes")
        }
        return EbmlWriter.encodeVintSize(size, fieldLength)
    }

    private fun ticksToUs(ticks: Long): Long = Timestamps.rescaleFloor(ticks, timestampScale, 1000)

    /** La cabecera del elemento en [at], o `null` si el archivo se acaba antes de terminarla. */
    private fun headerAt(at: Long): Header? {
        if (at >= length) return null
        input.position = at
        return try {
            Header(at, reader.readElement())
        } catch (_: EOFException) {
            null
        }
    }

    private companion object {
        /** El `DocType` más largo que se lee; los que se admiten, `matroska` y `webm`, caben de sobra. */
        const val MAX_DOCTYPE_BYTES = 64

        /** El `CodecID` más largo que se lee; los de los códecs que lee el demuxer caben de sobra. */
        const val MAX_CODEC_ID_BYTES = 64

        fun reject(reason: String): Nothing = throw IllegalArgumentException("no se puede reparar el MKV: $reason")
    }
}
