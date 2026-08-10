package com.braymon.kotmpeg.mkv

import com.braymon.kotmpeg.Demuxer
import com.braymon.kotmpeg.ebml.EbmlElement
import com.braymon.kotmpeg.ebml.EbmlException
import com.braymon.kotmpeg.ebml.EbmlReader
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ColorInfo
import com.braymon.kotmpeg.model.HdrStaticInfo
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import java.io.File
import java.util.ArrayDeque

/**
 * Demuxer Matroska (.mkv).
 *
 * Lee pistas (H.264/H.265/AAC/Opus), SimpleBlocks y BlockGroups, los tres esquemas de
 * lacing, y usa el índice Cues para las búsquedas. Las pistas no soportadas (p. ej.
 * subtítulos) se saltan de forma transparente.
 *
 * **No es seguro entre hilos**: readPacket/seekTo comparten la posición del archivo.
 */
public class MkvDemuxer(
    private val input: SeekableInput,
    /**
     * Avisos no fatales: pistas que se descartan porque sus parámetros son imposibles o su
     * códec no está soportado.
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

    /** Pistas ya anunciadas como no soportadas, para no repetir el aviso por cada bloque. */
    private val unsupportedWarned = HashSet<Int>()

    private var timestampScaleNs = 1_000_000L
    private var segmentDataStart = 0L
    private var segmentEnd = Long.MAX_VALUE
    private var firstClusterPos = -1L
    private var cuesPosFromSeekHead = -1L

    override var durationUs: Long = 0
        private set

    private val trackMap = LinkedHashMap<Int, TrackInfo>()
    override val tracks: List<TrackInfo> get() = trackMap.values.toList()

    /** Duración por defecto de fotograma por número de pista, en ns (0 = desconocida). */
    private val defaultDurationNs = HashMap<Int, Long>()

    private class Cue(val timeUs: Long, val clusterPosition: Long)

    private val cues = ArrayList<Cue>()

    private val pending = ArrayDeque<MediaPacket>()
    private var clusterTimestampTicks = 0L
    private var clusterEnd = -1L
    private var eof = false

    init {
        try {
            parseHeaders()
        } catch (t: Throwable) {
            runCatching { input.close() }
            throw t
        }
    }

    /**
     * Un elemento maestro de tamaño desconocido no acota el bucle de sus hijos: su
     * `dataEnd` es `Long.MAX_VALUE` y el parser leería el resto del archivo creyendo que
     * sigue dentro. Solo el Segment y los Cluster admiten tamaño desconocido, y ambos se
     * tratan aparte; en cualquier otra posición es un archivo corrupto o manipulado.
     */
    private fun requireSized(el: EbmlElement): EbmlElement {
        if (el.size < 0) {
            throw EbmlException("elemento maestro 0x${el.id.toString(16)} de tamaño desconocido no admitido aquí")
        }
        if (el.dataEnd > input.length) {
            throw EbmlException("elemento 0x${el.id.toString(16)} se extiende más allá del final del archivo")
        }
        return el
    }

    /**
     * Lee la cabecera EBML y todo lo que hay por encima del primer Cluster.
     *
     * El índice `Cues` se busca además por la posición que anuncie el `SeekHead` cuando no
     * apareció en el recorrido. Ese segundo intento va dentro de un `catch` vacío a propósito:
     * un índice roto solo deshabilita la búsqueda rápida y no es motivo para rechazar un
     * archivo que por lo demás se lee entero.
     */
    private fun parseHeaders() {
        val ebmlHeader = requireSized(reader.readElement())
        if (ebmlHeader.id != MatroskaIds.EBML) throw EbmlException("no es un archivo EBML")
        var docType = "matroska"
        while (input.position < ebmlHeader.dataEnd) {
            val el = reader.readElement()
            if (el.id == MatroskaIds.DOCTYPE) docType = reader.readString(el) else reader.skip(el)
        }
        if (docType != "matroska" && docType != "webm") throw EbmlException("doctype no soportado: $docType")

        val segment = reader.readElement()
        if (segment.id != MatroskaIds.SEGMENT) throw EbmlException("no se encontró el Segment")
        segmentDataStart = segment.dataStart
        segmentEnd = if (segment.size >= 0) minOf(segment.dataEnd, input.length) else input.length

        while (input.position < segmentEnd && input.remaining > 0) {
            val elementStart = input.position
            val el = reader.readElement()
            when (el.id) {
                MatroskaIds.INFO -> parseInfo(el)
                MatroskaIds.TRACKS -> parseTracks(el)
                MatroskaIds.SEEK_HEAD -> parseSeekHead(el)
                MatroskaIds.CUES -> parseCues(el)
                MatroskaIds.CLUSTER -> {
                    firstClusterPos = elementStart
                    break
                }
                else -> skipOrAbort(el)
            }
        }

        if (cues.isEmpty() && cuesPosFromSeekHead >= 0) {
            val saved = input.position
            try {
                input.position = segmentDataStart + cuesPosFromSeekHead
                val el = reader.readElement()
                if (el.id == MatroskaIds.CUES) parseCues(el)
            } catch (_: Exception) {
            }
            input.position = saved
        }

        if (firstClusterPos >= 0) {
            input.position = firstClusterPos
        } else {
            eof = true
        }
        clusterEnd = -1
    }

    private fun skipOrAbort(el: EbmlElement) {
        if (el.size < 0) {
            throw EbmlException("elemento de tamaño desconocido inesperado 0x${el.id.toString(16)}")
        }
        reader.skip(el)
    }

    private fun parseInfo(infoEl: EbmlElement) {
        val info = requireSized(infoEl)
        var durationTicks = 0.0
        while (input.position < info.dataEnd) {
            val el = reader.readElement()
            when (el.id) {
                MatroskaIds.TIMESTAMP_SCALE -> timestampScaleNs = reader.readUInt(el)
                MatroskaIds.DURATION -> durationTicks = reader.readFloat(el)
                else -> skipOrAbort(el)
            }
        }
        durationUs = (durationTicks * timestampScaleNs / 1000.0).toLong()
    }

    private fun parseSeekHead(seekHeadEl: EbmlElement) {
        val seekHead = requireSized(seekHeadEl)
        while (input.position < seekHead.dataEnd) {
            val seek = reader.readElement()
            if (seek.id != MatroskaIds.SEEK) { skipOrAbort(seek); continue }
            requireSized(seek)
            var targetId = 0L
            var position = -1L
            while (input.position < seek.dataEnd) {
                val el = reader.readElement()
                when (el.id) {
                    MatroskaIds.SEEK_ID -> {
                        val bytes = reader.readBinary(el)
                        targetId = bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                    }
                    MatroskaIds.SEEK_POSITION -> position = reader.readUInt(el)
                    else -> skipOrAbort(el)
                }
            }
            if (targetId == MatroskaIds.CUES && position >= 0) cuesPosFromSeekHead = position
        }
    }

    private fun parseTracks(tracksElement: EbmlElement) {
        val tracksEl = requireSized(tracksElement)
        while (input.position < tracksEl.dataEnd) {
            val entry = reader.readElement()
            if (entry.id != MatroskaIds.TRACK_ENTRY) { skipOrAbort(entry); continue }
            parseTrackEntry(requireSized(entry))
        }
    }

    /**
     * Lee un `TrackEntry` y lo convierte en [TrackInfo], resolviendo antes los dos elementos que
     * **cualifican** a otros y que sin mirar dejan un dato mal interpretado en silencio:
     *
     *  - `DisplayUnit` dice si `DisplayWidth`/`DisplayHeight` son píxeles (0, el valor por
     *    defecto) o una **proporción** (3, lo que escribe `ffmpeg -aspect 16:9`: literalmente 16
     *    y 9 sobre un fotograma de 640x480). Tomar la proporción por píxeles daba una pista que
     *    dice medir 16x9 en pantalla, y ese par se reescribía tal cual al convertir. Con 1 (cm)
     *    y 2 (pulgadas) no hay medida en píxeles aprovechable y se cae a las codificadas.
     *  - `OutputSamplingFrequency` lleva la frecuencia real de una pista con SBR (HE-AAC),
     *    mientras que `SamplingFrequency` lleva la del núcleo, que es la mitad. Solo se toma si
     *    **sube** —SBR nunca baja— y cabe en el rango de una frecuencia real: sin esa cota un
     *    `0,5` de un archivo mal formado pisaba un `24000` válido y la pista se descartaba
     *    entera. Un `NaN` falla las dos comparaciones, que es lo que queremos.
     *
     * Una pista con parámetros imposibles se descarta con aviso en vez de tumbar la lectura del
     * archivo: las demás siguen siendo usables.
     */
    private fun parseTrackEntry(entry: EbmlElement) {
        var number = 0
        var type = 0L
        var codecId = ""
        var codecPrivate: ByteArray? = null
        var language = "und"
        var name: String? = null
        var defaultDurNs = 0L
        var codecDelayNs = 0L
        var width = 0; var height = 0
        var displayWidth = 0; var displayHeight = 0
        /** Cualifica a los dos de arriba: 0 = píxeles (el valor por defecto), 3 = proporción. */
        var displayUnit = DISPLAY_UNIT_PIXELS
        var sampleRate = 0.0
        /** Solo la escriben las pistas con SBR; 0 significa "no venía". */
        var outputSampleRate = 0.0
        var channels = 1
        var bitDepth = 0
        var rotationDegrees = 0
        var color: ColorInfo? = null

        while (input.position < entry.dataEnd) {
            val el = reader.readElement()
            when (el.id) {
                MatroskaIds.TRACK_NUMBER -> number = reader.readUInt(el)
                    .also { require(it in 1..Int.MAX_VALUE) { "número de pista fuera de rango: $it" } }
                    .toInt()
                MatroskaIds.TRACK_TYPE -> type = reader.readUInt(el)
                MatroskaIds.CODEC_ID -> codecId = reader.readString(el)
                MatroskaIds.CODEC_PRIVATE -> codecPrivate = reader.readBinary(el)
                MatroskaIds.LANGUAGE -> language = reader.readString(el)
                MatroskaIds.NAME -> name = reader.readString(el)
                MatroskaIds.DEFAULT_DURATION -> defaultDurNs = reader.readUInt(el)
                MatroskaIds.CODEC_DELAY -> codecDelayNs = reader.readUInt(el)
                MatroskaIds.VIDEO -> while (input.position < requireSized(el).dataEnd) {
                    val v = reader.readElement()
                    when (v.id) {
                        MatroskaIds.PIXEL_WIDTH -> width = reader.readUInt(v).toInt()
                        MatroskaIds.PIXEL_HEIGHT -> height = reader.readUInt(v).toInt()
                        MatroskaIds.DISPLAY_WIDTH -> displayWidth = reader.readUInt(v).toInt()
                        MatroskaIds.DISPLAY_HEIGHT -> displayHeight = reader.readUInt(v).toInt()
                        MatroskaIds.DISPLAY_UNIT -> displayUnit = reader.readUInt(v).toInt()
                        MatroskaIds.COLOUR -> color = parseColour(v)
                        MatroskaIds.PROJECTION -> rotationDegrees = parseProjectionRotation(v)
                        else -> skipOrAbort(v)
                    }
                }
                MatroskaIds.AUDIO -> while (input.position < requireSized(el).dataEnd) {
                    val a = reader.readElement()
                    when (a.id) {
                        MatroskaIds.SAMPLING_FREQUENCY -> sampleRate = reader.readFloat(a)
                        MatroskaIds.OUTPUT_SAMPLING_FREQUENCY ->
                            outputSampleRate = reader.readFloat(a)
                        MatroskaIds.CHANNELS -> channels = reader.readUInt(a).toInt()
                        MatroskaIds.BIT_DEPTH -> bitDepth = reader.readUInt(a).toInt()
                        else -> skipOrAbort(a)
                    }
                }
                else -> skipOrAbort(el)
            }
        }
        if (number <= 0) return

        when (displayUnit) {
            DISPLAY_UNIT_PIXELS -> Unit
            DISPLAY_UNIT_ASPECT_RATIO -> {
                val derived = if (displayWidth > 0 && displayHeight > 0 && height > 0) {
                    Math.round(height.toDouble() * displayWidth / displayHeight).toInt()
                } else {
                    0
                }
                displayWidth = derived
                displayHeight = if (derived > 0) height else 0
            }
            else -> { displayWidth = 0; displayHeight = 0 }
        }

        if (outputSampleRate > sampleRate && outputSampleRate <= MAX_SAMPLE_RATE_HZ) {
            sampleRate = outputSampleRate
        }

        val track: TrackInfo? = runCatching { buildTrack(
            type, number, codecId, codecPrivate, language, name, defaultDurNs, codecDelayNs,
            width, height, displayWidth, displayHeight, sampleRate, channels, bitDepth,
            rotationDegrees, color,
        ) }.getOrNull()
        if (track != null) {
            trackMap[number] = track
            defaultDurationNs[number] = defaultDurNs
        } else {
            runCatching {
                onWarning(
                    "pista $number descartada: no se pudo construir desde su cabecera " +
                        "(codecId '$codecId', tipo $type)",
                )
            }
        }
    }

    /**
     * Construye la pista, o lanza para que [parseTrackEntry] la descarte.
     *
     * El tope de frecuencia es lo segundo: una tasa fuera del rango de lo que puede ser audio
     * viene siempre de un campo corrupto y al truncar a `Int` se convierte en `Int.MAX_VALUE`,
     * que envenenaría el `MediaFormat` y el `SampleEntry` de cualquier conversión. Ahí descartar
     * es la única política posible, porque no queda ningún valor bueno al que caer.
     */
    @Suppress("LongParameterList")
    private fun buildTrack(
        type: Long,
        number: Int,
        codecId: String,
        codecPrivate: ByteArray?,
        language: String,
        name: String?,
        defaultDurNs: Long,
        codecDelayNs: Long,
        width: Int,
        height: Int,
        displayWidth: Int,
        displayHeight: Int,
        sampleRate: Double,
        channels: Int,
        bitDepth: Int,
        rotationDegrees: Int,
        color: ColorInfo?,
    ): TrackInfo? {
        return when (type) {
            MatroskaIds.TRACK_TYPE_VIDEO -> VideoCodec.fromMatroskaId(codecId)?.let { codec ->
                TrackInfo.Video(
                    id = number, codec = codec, width = width, height = height,
                    displayWidth = if (displayWidth > 0) displayWidth else width,
                    displayHeight = if (displayHeight > 0) displayHeight else height,
                    frameRate = if (defaultDurNs > 0) 1e9 / defaultDurNs else 0.0,
                    rotationDegrees = rotationDegrees,
                    color = color,
                    codecPrivate = codecPrivate, language = language, name = name,
                )
            }
            MatroskaIds.TRACK_TYPE_AUDIO -> AudioCodec.fromMatroskaId(codecId)?.let { codec ->
                require(sampleRate <= MAX_SAMPLE_RATE_HZ) {
                    "frecuencia de audio imposible: $sampleRate"
                }
                TrackInfo.Audio(
                    id = number, codec = codec,
                    sampleRate = sampleRate.toInt(), channelCount = channels, bitDepth = bitDepth,
                    codecDelayUs = codecDelayNs / 1000,
                    codecPrivate = codecPrivate, language = language, name = name,
                )
            }
            else -> null
        }
    }

    private fun parseColour(colourEl: EbmlElement): ColorInfo {
        val colour = requireSized(colourEl)
        var primaries = ColorInfo.UNSPECIFIED
        var transfer = ColorInfo.UNSPECIFIED
        var matrix = ColorInfo.UNSPECIFIED
        var fullRange = false
        var maxCll = 0
        var maxFall = 0
        var rX = 0.0; var rY = 0.0; var gX = 0.0; var gY = 0.0; var bX = 0.0; var bY = 0.0
        var wX = 0.0; var wY = 0.0; var lumMax = 0.0; var lumMin = 0.0
        var hasMastering = false
        while (input.position < colour.dataEnd) {
            val el = reader.readElement()
            when (el.id) {
                MatroskaIds.COLOUR_PRIMARIES -> primaries = reader.readUInt(el).toInt()
                MatroskaIds.TRANSFER_CHARACTERISTICS -> transfer = reader.readUInt(el).toInt()
                MatroskaIds.MATRIX_COEFFICIENTS -> matrix = reader.readUInt(el).toInt()
                MatroskaIds.COLOUR_RANGE -> fullRange = reader.readUInt(el) == 2L
                MatroskaIds.MAX_CLL -> maxCll = reader.readUInt(el).toInt()
                MatroskaIds.MAX_FALL -> maxFall = reader.readUInt(el).toInt()
                MatroskaIds.MASTERING_METADATA -> {
                    hasMastering = true
                    while (input.position < requireSized(el).dataEnd) {
                        val m = reader.readElement()
                        when (m.id) {
                            MatroskaIds.PRIMARY_R_X -> rX = reader.readFloat(m)
                            MatroskaIds.PRIMARY_R_Y -> rY = reader.readFloat(m)
                            MatroskaIds.PRIMARY_G_X -> gX = reader.readFloat(m)
                            MatroskaIds.PRIMARY_G_Y -> gY = reader.readFloat(m)
                            MatroskaIds.PRIMARY_B_X -> bX = reader.readFloat(m)
                            MatroskaIds.PRIMARY_B_Y -> bY = reader.readFloat(m)
                            MatroskaIds.WHITE_POINT_X -> wX = reader.readFloat(m)
                            MatroskaIds.WHITE_POINT_Y -> wY = reader.readFloat(m)
                            MatroskaIds.LUMINANCE_MAX -> lumMax = reader.readFloat(m)
                            MatroskaIds.LUMINANCE_MIN -> lumMin = reader.readFloat(m)
                            else -> skipOrAbort(m)
                        }
                    }
                }
                else -> skipOrAbort(el)
            }
        }
        val hdr = if (hasMastering || maxCll > 0 || maxFall > 0) {
            HdrStaticInfo(rX, rY, gX, gY, bX, bY, wX, wY, lumMax, lumMin, maxCll, maxFall)
        } else null
        return ColorInfo(primaries, transfer, matrix, fullRange, hdr)
    }

    /** Mapea el PoseRoll de una Projection rectangular de vuelta a rotación 0/90/180/270. */
    private fun parseProjectionRotation(projectionEl: EbmlElement): Int {
        val projection = requireSized(projectionEl)
        var roll = 0.0
        while (input.position < projection.dataEnd) {
            val el = reader.readElement()
            when (el.id) {
                MatroskaIds.PROJECTION_POSE_ROLL -> roll = reader.readFloat(el)
                else -> skipOrAbort(el)
            }
        }
        val normalized = ((-roll).mod(360.0))
        return when {
            Math.abs(normalized - 90) < 45 -> 90
            Math.abs(normalized - 180) < 45 -> 180
            Math.abs(normalized - 270) < 45 -> 270
            else -> 0
        }
    }

    private fun parseCues(cuesElement: EbmlElement) {
        val cuesEl = requireSized(cuesElement)
        while (input.position < cuesEl.dataEnd) {
            val point = reader.readElement()
            if (point.id != MatroskaIds.CUE_POINT) { skipOrAbort(point); continue }
            requireSized(point)
            var timeTicks = -1L
            var clusterPos = -1L
            while (input.position < point.dataEnd) {
                val el = reader.readElement()
                when (el.id) {
                    MatroskaIds.CUE_TIME -> timeTicks = reader.readUInt(el)
                    MatroskaIds.CUE_TRACK_POSITIONS -> while (input.position < requireSized(el).dataEnd) {
                        val p = reader.readElement()
                        when (p.id) {
                            MatroskaIds.CUE_CLUSTER_POSITION -> clusterPos = reader.readUInt(p)
                            else -> skipOrAbort(p)
                        }
                    }
                    else -> skipOrAbort(el)
                }
            }
            if (timeTicks >= 0 && clusterPos >= 0) {
                cues.add(Cue(ticksToUs(timeTicks), clusterPos))
            }
        }
    }

    private fun ticksToUs(ticks: Long): Long = ticks * timestampScaleNs / 1000

    override fun readPacket(): MediaPacket? {
        while (pending.isEmpty()) {
            if (!advance()) return null
        }
        return pending.poll()
    }

    /**
     * Lee el siguiente elemento dentro de/entre clusters, encolando los paquetes hallados.
     *
     * Aquí la política ante datos corruptos es deliberadamente distinta a la del parseo de
     * cabeceras: **se termina el stream en vez de lanzar**. Un archivo truncado (una
     * grabación cortada de golpe, lo normal en este caso de uso) debe reproducirse hasta
     * donde llegue, no fallar entero por la cola dañada. Las cabeceras sí lanzan, porque
     * sin ellas no hay nada que reproducir.
     */
    private fun advance(): Boolean {
        if (eof || input.remaining <= 0 || input.position >= segmentEnd) return false

        if (clusterEnd < 0) {
            while (true) {
                if (input.remaining <= 0 || input.position >= segmentEnd) return false
                val el = try { reader.readElement() } catch (_: Exception) { return false }
                when (el.id) {
                    MatroskaIds.CLUSTER -> {
                        clusterEnd = if (el.size >= 0) el.dataEnd else Long.MAX_VALUE
                        clusterTimestampTicks = 0
                        return true
                    }
                    else -> {
                        if (el.size < 0) return false
                        reader.skip(el)
                    }
                }
            }
        }

        if (input.position >= clusterEnd || input.remaining <= 0) {
            clusterEnd = -1
            return true
        }
        val el = try { reader.readElement() } catch (_: Exception) { eof = true; return false }
        when (el.id) {
            MatroskaIds.CLUSTER_TIMESTAMP -> clusterTimestampTicks = reader.readUInt(el)
            MatroskaIds.SIMPLE_BLOCK -> parseBlock(el, simple = true, keyOverride = null, blockDurationTicks = -1)
            MatroskaIds.BLOCK_GROUP -> parseBlockGroup(el)
            MatroskaIds.CLUSTER -> {
                clusterEnd = if (el.size >= 0) el.dataEnd else Long.MAX_VALUE
                clusterTimestampTicks = 0
            }
            MatroskaIds.CUES, MatroskaIds.TAGS, MatroskaIds.CHAPTERS, MatroskaIds.ATTACHMENTS, MatroskaIds.SEEK_HEAD -> {
                clusterEnd = -1
                if (el.size < 0) { eof = true; return false }
                reader.skip(el)
            }
            else -> {
                if (el.size < 0) { eof = true; return false }
                reader.skip(el)
            }
        }
        return true
    }

    private fun parseBlockGroup(groupEl: EbmlElement) {
        val group = requireSized(groupEl)
        var blockEl: EbmlElement? = null
        var blockData: ByteArray? = null
        var hasReference = false
        var durationTicks = -1L
        while (input.position < group.dataEnd) {
            val el = reader.readElement()
            when (el.id) {
                MatroskaIds.BLOCK -> { blockEl = el; blockData = reader.readBinary(el) }
                MatroskaIds.REFERENCE_BLOCK -> { reader.readSInt(el); hasReference = true }
                MatroskaIds.BLOCK_DURATION -> durationTicks = reader.readUInt(el)
                else -> skipOrAbort(el)
            }
        }
        if (blockEl != null && blockData != null) {
            parseBlockPayload(blockData, keyOverride = !hasReference, blockDurationTicks = durationTicks, simpleFlags = false)
        }
    }

    private fun parseBlock(el: EbmlElement, simple: Boolean, keyOverride: Boolean?, blockDurationTicks: Long) {
        val data = reader.readBinary(el)
        parseBlockPayload(data, keyOverride, blockDurationTicks, simpleFlags = simple)
    }

    private fun parseBlockPayload(data: ByteArray, keyOverride: Boolean?, blockDurationTicks: Long, simpleFlags: Boolean) {
        var i = 0
        if (data.isEmpty()) return
        val first = data[i].toInt() and 0xFF
        if (first == 0) return
        var len = 1
        var mask = 0x80
        while (first and mask == 0) { len++; mask = mask shr 1 }
        if (len > 8 || data.size < len + 3) return
        var trackNumber = (first and (mask - 1)).toLong()
        repeat(len - 1) { i++; trackNumber = (trackNumber shl 8) or (data[i].toLong() and 0xFF) }
        i++
        val relative = (((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)).toShort().toInt()
        i += 2
        val flags = data[i].toInt() and 0xFF
        i++

        if (trackNumber !in 1..Int.MAX_VALUE) return
        val track = trackMap[trackNumber.toInt()] ?: run {
            val id = trackNumber.toInt()
            if (unsupportedWarned.add(id)) {
                runCatching { onWarning("se descartan los bloques de la pista $id, que no está soportada") }
            }
            return
        }

        val keyFrame = keyOverride ?: (track is TrackInfo.Audio || (simpleFlags && (flags and 0x80) != 0))
        val lacing = (flags shr 1) and 0x03

        val frames = ArrayList<ByteArray>()
        when (lacing) {
            0 -> frames.add(data.copyOfRange(i, data.size))
            else -> {
                if (i >= data.size) return
                val frameCountMinus1 = data[i].toInt() and 0xFF
                i++
                val sizes = IntArray(frameCountMinus1 + 1)
                when (lacing) {
                    2 -> {
                        val total = data.size - i
                        val each = total / (frameCountMinus1 + 1)
                        for (k in sizes.indices) sizes[k] = each
                    }
                    1 -> {
                        for (k in 0 until frameCountMinus1) {
                            var size = 0
                            while (true) {
                                if (i >= data.size) return
                                val b = data[i].toInt() and 0xFF; i++
                                size += b
                                if (b != 255) break
                            }
                            sizes[k] = size
                        }
                    }
                    3 -> {
                        var prev = 0L
                        for (k in 0 until frameCountMinus1) {
                            if (i >= data.size) return
                            val b0 = data[i].toInt() and 0xFF
                            if (b0 == 0) return
                            var l2 = 1
                            var m2 = 0x80
                            while (b0 and m2 == 0) { l2++; m2 = m2 shr 1 }
                            if (l2 > 8 || i + l2 > data.size) return
                            var v = (b0 and (m2 - 1)).toLong()
                            repeat(l2 - 1) { i++; v = (v shl 8) or (data[i].toLong() and 0xFF) }
                            i++
                            prev = if (k == 0) v else prev + (v - ((1L shl (7 * l2 - 1)) - 1))
                            sizes[k] = prev.toInt()
                        }
                    }
                }
                var used = 0L
                for (k in 0 until frameCountMinus1) {
                    if (sizes[k] < 0) return
                    used += sizes[k]
                }
                val lastSize = data.size - i - used
                if (lastSize < 0) return
                sizes[frameCountMinus1] = lastSize.toInt()
                for (k in sizes.indices) {
                    if (i + sizes[k] > data.size) return
                    frames.add(data.copyOfRange(i, i + sizes[k]))
                    i += sizes[k]
                }
            }
        }

        val basePtsUs = ticksToUs(clusterTimestampTicks + relative)
        val defaultNs = defaultDurationNs[trackNumber.toInt()] ?: 0L
        val explicitDurUs = if (blockDurationTicks >= 0) ticksToUs(blockDurationTicks) else 0L
        val perFrameUs = when {
            frames.size > 1 && explicitDurUs > 0 -> explicitDurUs / frames.size
            defaultNs > 0 -> defaultNs / 1000
            else -> explicitDurUs
        }
        for ((k, frame) in frames.withIndex()) {
            val pts = basePtsUs + k * perFrameUs
            pending.add(
                MediaPacket(
                    trackId = trackNumber.toInt(),
                    data = frame,
                    ptsUs = pts,
                    dtsUs = pts,
                    isKeyFrame = keyFrame,
                    durationUs = perFrameUs,
                ),
            )
        }
    }

    override fun seekTo(timestampUs: Long): Long {
        pending.clear()
        eof = false
        clusterEnd = -1
        val cue = cues.lastOrNull { it.timeUs <= timestampUs } ?: cues.firstOrNull()
        if (cue != null) {
            input.position = segmentDataStart + cue.clusterPosition
            return cue.timeUs
        }
        if (firstClusterPos < 0) {
            eof = true
            return 0L
        }
        val (pos, timeUs) = scanForCluster(timestampUs)
        input.position = pos
        return timeUs
    }

    /**
     * Localiza por escaneo lineal el último cluster que empieza en o antes de
     * [timestampUs]. Devuelve (posición absoluta, marca de tiempo real); ante un archivo
     * truncado o un cluster de tamaño desconocido se queda con el mejor hallado hasta ahí.
     */
    private fun scanForCluster(timestampUs: Long): Pair<Long, Long> {
        val saved = input.position
        var bestPos = firstClusterPos
        var bestUs = 0L
        try {
            var pos = firstClusterPos
            while (pos < segmentEnd) {
                input.position = pos
                if (input.remaining <= 0) break
                val el = reader.readElement()
                if (el.size < 0) break
                if (el.id != MatroskaIds.CLUSTER) { pos = el.dataEnd; continue }
                var timeUs = -1L
                while (input.position < el.dataEnd) {
                    val child = reader.readElement()
                    if (child.id == MatroskaIds.CLUSTER_TIMESTAMP) {
                        timeUs = ticksToUs(reader.readUInt(child))
                        break
                    }
                    if (child.size < 0) break
                    reader.skip(child)
                }
                if (timeUs > timestampUs) break
                if (timeUs >= 0) { bestPos = pos; bestUs = timeUs }
                pos = el.dataEnd
            }
        } catch (_: Exception) {
        } finally {
            input.position = saved
        }
        return bestPos to bestUs
    }

    override fun close(): Unit = input.close()

    /**
     * Constantes internas. Van marcadas `private` una a una y no solo el `companion`: un
     * `const val` de un objeto compañero se compila como campo estático de la clase que lo
     * contiene y con **su propia** visibilidad, así que sin el modificador se colarían en la
     * API pública y en `public-api.txt`.
     */
    private companion object {
        /** `DisplayUnit` = 0: `DisplayWidth`/`DisplayHeight` están en píxeles. Es el defecto. */
        private const val DISPLAY_UNIT_PIXELS = 0

        /** `DisplayUnit` = 3: son una proporción (1 y 2 son centímetros y pulgadas). */
        private const val DISPLAY_UNIT_ASPECT_RATIO = 3

        /**
         * Techo de lo que puede ser una frecuencia de muestreo real. El máximo del PCM
         * profesional son 384 kHz; el doble deja holgura para cualquier caso legítimo y deja
         * fuera cualquier campo corrupto.
         */
        private const val MAX_SAMPLE_RATE_HZ = 768_000.0
    }
}
