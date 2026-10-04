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
import com.braymon.kotmpeg.model.Timestamps
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.util.ArrayDeque
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Demuxer Matroska (.mkv).
 *
 * Lee pistas (H.264/H.265/AAC/Opus), SimpleBlocks y BlockGroups, los tres esquemas de
 * lacing, la compresión de contenido de `ContentEncoding` (eliminación de cabecera y zlib), y
 * usa el índice Cues para las búsquedas. Las pistas no soportadas (p. ej. subtítulos) se
 * saltan de forma transparente.
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
    private var trackList: List<TrackInfo> = emptyList()
    override val tracks: List<TrackInfo> get() = trackList

    /** Duración por defecto de fotograma por número de pista, en ns (0 = desconocida). */
    private val defaultDurationNs = HashMap<Int, Long>()

    /** Codificaciones de contenido por número de pista, ya ordenadas para deshacerlas. */
    private val contentEncodings = HashMap<Int, List<ContentEncoding>>()

    private class Cue(val timeUs: Long, val clusterPosition: Long)

    /**
     * Un `ContentEncoding` de compresión. Solo se conservan los que se saben deshacer —
     * eliminación de cabecera (algoritmo 3) y zlib (0)—; una pista con cualquier otro, o con
     * cifrado, se descarta con aviso, porque entregar sus bloques tal cual sería entregar datos
     * que ningún decodificador puede usar.
     */
    private class ContentEncoding(val order: Long, val scope: Long, val algorithm: Long, val settings: ByteArray?)

    /** Cabecera de un Block o SimpleBlock: pista, desfase respecto al cluster y flags. */
    private class BlockHeader(val trackNumber: Int, val relative: Int, val flags: Int, val size: Int)

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
        trackList = trackMap.values.toList()
    }

    private fun warn(message: String) {
        runCatching { onWarning(message) }
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
        cues.sortBy { it.timeUs }

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
                MatroskaIds.TIMESTAMP_SCALE -> {
                    val scale = reader.readUInt(el)
                    if (scale > 0) {
                        timestampScaleNs = scale
                    } else {
                        warn("TimestampScale inválido ($scale): se usa el valor por defecto de 1 ms")
                    }
                }
                MatroskaIds.DURATION -> durationTicks = reader.readFloat(el)
                else -> skipOrAbort(el)
            }
        }
        val duration = durationTicks * timestampScaleNs / 1000.0
        durationUs = if (duration.isFinite() && duration > 0) duration.toLong() else 0L
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
        /** El valor por omisión de `FlagDefault` en la especificación es 1: ausente = predeterminada. */
        var default = true
        var encodings: List<ContentEncoding>? = emptyList()

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
                MatroskaIds.FLAG_DEFAULT -> default = reader.readUInt(el) != 0L
                MatroskaIds.DEFAULT_DURATION -> defaultDurNs = reader.readUInt(el)
                MatroskaIds.CODEC_DELAY -> codecDelayNs = reader.readUInt(el)
                MatroskaIds.CONTENT_ENCODINGS -> encodings = parseContentEncodings(requireSized(el))
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

        val usableEncodings = encodings ?: run {
            warn(
                "pista $number descartada: usa una compresión o un cifrado de contenido " +
                    "(ContentEncoding) que no está soportado",
            )
            return
        }
        if (usableEncodings.isNotEmpty() && codecPrivate != null) {
            codecPrivate = try {
                decode(codecPrivate, usableEncodings, CONTENT_SCOPE_PRIVATE)
            } catch (_: Exception) {
                warn("pista $number descartada: su CodecPrivate comprimido no se pudo descomprimir")
                return
            }
        }

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
            rotationDegrees, color, default,
        ) }.getOrNull()
        if (track != null) {
            trackMap[number] = track
            defaultDurationNs[number] = defaultDurNs
            if (usableEncodings.isNotEmpty()) contentEncodings[number] = usableEncodings
        } else {
            warn(
                "pista $number descartada: no se pudo construir desde su cabecera " +
                    "(codecId '$codecId', tipo $type)",
            )
        }
    }

    /**
     * Lee `ContentEncodings`. Devuelve las codificaciones ordenadas de mayor a menor
     * `ContentEncodingOrder`, que es el orden en que la especificación manda deshacerlas, o `null`
     * si alguna no se sabe deshacer.
     *
     * Ignorarlas, que era lo que se hacía, no fallaba: entregaba en silencio fotogramas sin los
     * bytes de cabecera que el muxer había quitado —lo que `mkvmerge` hacía por defecto en sus
     * versiones antiguas—, y el decodificador recibía basura sin ningún aviso.
     */
    private fun parseContentEncodings(container: EbmlElement): List<ContentEncoding>? {
        val result = ArrayList<ContentEncoding>()
        var supported = true
        while (input.position < container.dataEnd) {
            val encodingEl = reader.readElement()
            if (encodingEl.id != MatroskaIds.CONTENT_ENCODING) { skipOrAbort(encodingEl); continue }
            requireSized(encodingEl)
            var order = 0L
            var scope = CONTENT_SCOPE_FRAMES
            var type = 0L
            var algorithm = 0L
            var settings: ByteArray? = null
            while (input.position < encodingEl.dataEnd) {
                val el = reader.readElement()
                when (el.id) {
                    MatroskaIds.CONTENT_ENCODING_ORDER -> order = reader.readUInt(el)
                    MatroskaIds.CONTENT_ENCODING_SCOPE -> scope = reader.readUInt(el)
                    MatroskaIds.CONTENT_ENCODING_TYPE -> type = reader.readUInt(el)
                    MatroskaIds.CONTENT_COMPRESSION -> while (input.position < requireSized(el).dataEnd) {
                        val c = reader.readElement()
                        when (c.id) {
                            MatroskaIds.CONTENT_COMP_ALGO -> algorithm = reader.readUInt(c)
                            MatroskaIds.CONTENT_COMP_SETTINGS -> settings = reader.readBinary(c)
                            else -> skipOrAbort(c)
                        }
                    }
                    MatroskaIds.CONTENT_ENCRYPTION -> { type = CONTENT_TYPE_ENCRYPTION; reader.skip(el) }
                    else -> skipOrAbort(el)
                }
            }
            val known = type == CONTENT_TYPE_COMPRESSION &&
                (algorithm == CONTENT_ALGO_ZLIB || algorithm == CONTENT_ALGO_HEADER_STRIPPING)
            if (!known) supported = false
            result.add(ContentEncoding(order, scope, algorithm, settings))
        }
        if (!supported) return null
        result.sortByDescending { it.order }
        return result
    }

    /**
     * Deshace sobre [data] las codificaciones que se aplican a [scope].
     *
     * Quien llama captura `Exception` y no cualquier `Throwable`: un bloque que no se puede
     * descomprimir se descarta con aviso, pero un `OutOfMemoryError` no es un bloque dañado sino
     * el proceso sin memoria. Tragárselo descartaba fotogramas en silencio mientras todo lo demás
     * empezaba a fallar; ahora llega a quien lee, que es quien puede decidir qué hacer.
     */
    private fun decode(data: ByteArray, encodings: List<ContentEncoding>, scope: Long): ByteArray {
        var current = data
        for (encoding in encodings) {
            if (encoding.scope and scope == 0L) continue
            current = when (encoding.algorithm) {
                CONTENT_ALGO_HEADER_STRIPPING -> encoding.settings?.let { it + current } ?: current
                else -> inflate(current)
            }
        }
        return current
    }

    /**
     * Descompresión zlib con un techo de tamaño. Un bloque comprimido de unos pocos KB puede
     * declarar cientos de MB al descomprimirse; sin el techo, un archivo manipulado agotaba la
     * memoria del proceso con un solo fotograma.
     */
    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 2)
            val chunk = ByteArray(INFLATE_CHUNK_BYTES)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(chunk)
                } catch (e: DataFormatException) {
                    throw EbmlException("bloque zlib corrupto: ${e.message}")
                }
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw EbmlException("bloque zlib incompleto")
                }
                out.write(chunk, 0, n)
                if (out.size() > MAX_INFLATED_BYTES) {
                    throw EbmlException("bloque zlib que descomprime más de $MAX_INFLATED_BYTES bytes")
                }
            }
            return out.toByteArray()
        } finally {
            inflater.end()
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
        default: Boolean,
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
                    default = default,
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
                    default = default,
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

    private fun ticksToUs(ticks: Long): Long = Timestamps.rescaleFloor(ticks, timestampScaleNs, 1000)

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
     * grabación cortada de golpe, lo normal en este caso de uso) debe reproducirse hasta donde
     * llegue, no fallar entero por la cola dañada. Las cabeceras sí lanzan, porque sin ellas no hay nada que reproducir.
     *
     * Antes solo se protegía la lectura de la cabecera de cada elemento: un `SimpleBlock` cortado
     * a mitad de su carga lanzaba al leerla, justo en el caso que esta política existe para
     * cubrir. Ahora cualquier dato ilegible termina el stream con un aviso; un error de E/S de
     * verdad —que no es un archivo dañado sino un disco o un descriptor que fallan— se propaga.
     */
    private fun advance(): Boolean {
        if (eof || input.remaining <= 0 || input.position >= segmentEnd) return false
        return try {
            advanceUnchecked()
        } catch (e: EbmlException) {
            endOfReadableData(e)
        } catch (e: EOFException) {
            endOfReadableData(e)
        } catch (e: IllegalArgumentException) {
            endOfReadableData(e)
        }
    }

    private fun endOfReadableData(cause: Exception): Boolean {
        eof = true
        warn("datos de Matroska ilegibles en el offset ${input.position}: fin del stream (${cause.message})")
        return false
    }

    private fun advanceUnchecked(): Boolean {
        if (clusterEnd < 0) {
            while (true) {
                if (input.remaining <= 0 || input.position >= segmentEnd) return false
                val el = reader.readElement()
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
        val el = reader.readElement()
        when (el.id) {
            MatroskaIds.CLUSTER_TIMESTAMP -> clusterTimestampTicks = reader.readUInt(el)
            MatroskaIds.SIMPLE_BLOCK -> readSimpleBlock(el)
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
        if (first == 0) return null
        var length = 1
        var mask = 0x80
        while (first and mask == 0) { length++; mask = mask shr 1 }
        if (length > 8 || el.size < length + 3) return null
        var trackNumber = (first and (mask - 1)).toLong()
        repeat(length - 1) { trackNumber = (trackNumber shl 8) or input.readByte().toLong() }
        val relative = input.readBits(2).toInt().toShort().toInt()
        val flags = input.readByte()
        if (trackNumber !in 1..Int.MAX_VALUE) return null
        return BlockHeader(trackNumber.toInt(), relative, flags, length + 3)
    }

    /** Pista de un bloque, o `null` (con un único aviso por pista) si no está soportada. */
    private fun trackFor(number: Int): TrackInfo? = trackMap[number] ?: run {
        if (unsupportedWarned.add(number)) {
            warn("se descartan los bloques de la pista $number, que no está soportada")
        }
        null
    }

    private fun readSimpleBlock(el: EbmlElement) {
        requireBlockPayload(el)
        val header = readBlockHeader(el)
        val track = header?.let { trackFor(it.trackNumber) }
        if (header == null || track == null) {
            input.position = el.dataEnd
            return
        }
        val payload = input.readBytes((el.size - header.size).toInt())
        emitBlock(track, header, payload, keyOverride = null, blockDurationTicks = -1, simpleFlags = true)
    }

    private fun parseBlockGroup(groupEl: EbmlElement) {
        requireBlockPayload(groupEl)
        val group = requireSized(groupEl)
        var header: BlockHeader? = null
        var track: TrackInfo? = null
        var payload: ByteArray? = null
        var hasReference = false
        var durationTicks = -1L
        while (input.position < group.dataEnd) {
            val el = reader.readElement()
            when (el.id) {
                MatroskaIds.BLOCK -> {
                    requireBlockPayload(el)
                    header = readBlockHeader(el)
                    track = header?.let { trackFor(it.trackNumber) }
                    val current = header
                    if (current != null && track != null) {
                        payload = input.readBytes((el.size - current.size).toInt())
                    } else {
                        input.position = el.dataEnd
                    }
                }
                MatroskaIds.REFERENCE_BLOCK -> { reader.readSInt(el); hasReference = true }
                MatroskaIds.BLOCK_DURATION -> durationTicks = reader.readUInt(el)
                else -> skipOrAbort(el)
            }
        }
        val blockHeader = header ?: return
        val blockTrack = track ?: return
        val blockPayload = payload ?: return
        emitBlock(blockTrack, blockHeader, blockPayload, keyOverride = !hasReference, blockDurationTicks = durationTicks, simpleFlags = false)
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
                    if (b0 == 0) return null
                    var l2 = 1
                    var m2 = 0x80
                    while (b0 and m2 == 0) { l2++; m2 = m2 shr 1 }
                    if (l2 > 8 || i + l2 > payload.size) return null
                    var v = (b0 and (m2 - 1)).toLong()
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

    private fun emitBlock(
        track: TrackInfo,
        header: BlockHeader,
        payload: ByteArray,
        keyOverride: Boolean?,
        blockDurationTicks: Long,
        simpleFlags: Boolean,
    ) {
        val number = header.trackNumber
        val keyFrame = keyOverride ?: (track is TrackInfo.Audio || (simpleFlags && (header.flags and 0x80) != 0))
        val lacing = (header.flags shr 1) and 0x03
        val rawFrames = if (lacing == LACING_NONE) listOf(payload) else splitLaces(payload, lacing) ?: return

        val encodings = contentEncodings[number]
        val frames = if (encodings == null) rawFrames else rawFrames.mapNotNull { frame ->
            try {
                decode(frame, encodings, CONTENT_SCOPE_FRAMES)
            } catch (e: Exception) {
                warn("pista $number: se descarta un bloque cuya compresión no se pudo deshacer (${e.message})")
                null
            }
        }

        val basePtsUs = ticksToUs(clusterTimestampTicks + header.relative)
        val defaultNs = defaultDurationNs[number] ?: 0L
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
     * Las cues se ordenan por tiempo al abrir, así que el último punto en o antes de
     * [timestampUs] se encuentra por bisección en vez de recorrer el índice entero.
     */
    override fun seekTo(timestampUs: Long): Long {
        pending.clear()
        eof = false
        clusterEnd = -1
        val cue = lastCueAtOrBefore(timestampUs) ?: cues.firstOrNull()
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

    private fun lastCueAtOrBefore(timestampUs: Long): Cue? {
        var low = 0
        var high = cues.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cues[mid].timeUs <= timestampUs) low = mid + 1 else high = mid
        }
        return if (low > 0) cues[low - 1] else null
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

        private const val LACING_NONE = 0
        private const val LACING_XIPH = 1
        private const val LACING_FIXED = 2
        private const val LACING_EBML = 3

        /** `ContentEncodingScope`: bit 1 = fotogramas, bit 2 = `CodecPrivate`. */
        private const val CONTENT_SCOPE_FRAMES = 1L
        private const val CONTENT_SCOPE_PRIVATE = 2L

        /** `ContentEncodingType`: 0 = compresión, 1 = cifrado. */
        private const val CONTENT_TYPE_COMPRESSION = 0L
        private const val CONTENT_TYPE_ENCRYPTION = 1L

        /** `ContentCompAlgo`: 0 = zlib, 3 = eliminación de cabecera. bzlib (1) y lzo (2) no. */
        private const val CONTENT_ALGO_ZLIB = 0L
        private const val CONTENT_ALGO_HEADER_STRIPPING = 3L

        private const val INFLATE_CHUNK_BYTES = 1 shl 16

        /** Techo de un fotograma descomprimido: muy por encima de cualquier fotograma real. */
        private const val MAX_INFLATED_BYTES = 64 * 1024 * 1024
    }
}
