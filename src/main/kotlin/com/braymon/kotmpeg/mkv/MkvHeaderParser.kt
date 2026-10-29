package com.braymon.kotmpeg.mkv

import com.braymon.kotmpeg.ebml.EbmlElement
import com.braymon.kotmpeg.ebml.EbmlException
import com.braymon.kotmpeg.ebml.EbmlReader
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ColorInfo
import com.braymon.kotmpeg.model.HdrStaticInfo
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import java.io.EOFException

/**
 * Un punto del índice: tiempo en tics de `TimestampScale`, posición del cluster relativa al
 * principio de los datos del `Segment` y pista (`CueTrack`, 0 si no la declara).
 *
 * El tiempo se guarda en tics y no en microsegundos porque `Cues` puede ir antes que `Info`, que
 * es un orden legal: convertirlo al leerlo usaba la escala por defecto en vez de la del archivo.
 */
internal class MkvCue(val timeTicks: Long, val clusterPosition: Long, val track: Int)

/**
 * Lo que hay por encima del primer cluster de un MKV, ya validado y sin estado que cambie: lo
 * produce [MkvHeaderParser] y lo consulta `MkvDemuxer` durante toda la lectura.
 *
 * [discardedTracks] son las pistas declaradas que se descartaron con aviso al leer la cabecera,
 * para que sus bloques no repitan el aviso; [cues] va ordenado por tiempo.
 */
internal class MkvHeader(
    val timestampScaleNs: Long,
    val segmentDataStart: Long,
    val segmentEnd: Long,
    val firstClusterPosition: Long?,
    val durationUs: Long,
    val tracks: Map<Int, TrackInfo>,
    val defaultDurationNs: Map<Int, Long>,
    val contentEncodings: Map<Int, List<ContentEncoding>>,
    val discardedTracks: Set<Int>,
    val cues: List<MkvCue>,
)

/**
 * Lee la cabecera EBML y todo lo que hay por encima del primer cluster.
 *
 * Aquí la política ante datos imposibles es la de las cabeceras: un elemento que no se puede
 * recorrer lanza, porque sin estructura no hay nada que reproducir, pero un dato imposible de
 * **una pista** o del índice solo descarta eso, con aviso, y el resto del archivo se abre.
 */
internal class MkvHeaderParser(
    private val input: SeekableInput,
    private val reader: EbmlReader,
    private val decoder: MkvContentDecoder,
    private val warn: (String) -> Unit,
) {
    private var timestampScaleNs = 1_000_000L
    private var durationUs = 0L
    private var segmentDataStart = 0L
    private var segmentEnd = Long.MAX_VALUE
    private var firstClusterPosition: Long? = null
    private var cuesPosFromSeekHead = -1L

    /** Dónde estaba el `Cues` que se leyó al recorrer la cabecera, para no leerlo otra vez por el `SeekHead`. */
    private var inlineCuesPos = -1L

    private val tracks = LinkedHashMap<Int, TrackInfo>()
    private val defaultDurationNs = HashMap<Int, Long>()
    private val contentEncodings = HashMap<Int, List<ContentEncoding>>()
    private val discardedTracks = HashSet<Int>()
    private val cues = ArrayList<MkvCue>()

    /** Los `TrackEntry` vistos, para el tope [MAX_TRACK_ENTRIES]. */
    private var trackEntries = 0

    /** Los campos de un `TrackEntry` a medida que se leen, en el orden que sea. */
    private class TrackFields {
        /** `null` si la entrada no lo declara. */
        var number: Long? = null
        var type = 0L
        var codecId = ""
        var codecPrivate: ByteArray? = null
        var language = "und"
        var name: String? = null
        var defaultDurationNs = 0L
        var codecDelayNs = 0L
        var width = 0
        var height = 0
        var displayWidth = 0
        var displayHeight = 0

        /** Cualifica a los dos de arriba: 0 = píxeles (el valor por defecto), 3 = proporción. */
        var displayUnit = DISPLAY_UNIT_PIXELS
        var sampleRate = 0.0

        /** Solo la escriben las pistas con SBR; 0 significa "no venía". */
        var outputSampleRate = 0.0
        var channels = 1
        var bitDepth = 0
        var rotationDegrees = 0
        var colour: Colour? = null

        /** El valor por omisión de `FlagDefault` en la especificación es 1: ausente = predeterminada. */
        var default = true
        var encodings = ParsedEncodings(emptyList(), null)
    }

    /**
     * Un elemento `Colour` tal como viene. Se convierte en [ColorInfo] al final de la pista, con
     * su número ya conocido para el aviso, porque un valor imposible no debe tumbar la pista.
     */
    private class Colour {
        var primaries = ColorInfo.UNSPECIFIED
        var transfer = ColorInfo.UNSPECIFIED
        var matrix = ColorInfo.UNSPECIFIED
        var fullRange = false
        var maxCll = 0
        var maxFall = 0

        /** Los diez valores de `MasteringMetadata` en el orden de [HdrStaticInfo], o `null` si no venía. */
        var mastering: DoubleArray? = null
    }

    /**
     * Las codificaciones de una pista: [encodings] en el orden en que se deshacen, o el motivo
     * ([unsupported]) por el que no se saben deshacer y la pista se descarta.
     */
    private class ParsedEncodings(val encodings: List<ContentEncoding>, val unsupported: String?)

    /**
     * Recorre la cabecera y devuelve lo leído, dejando [input] donde haya acabado.
     *
     * El índice `Cues` se busca además por la posición que anuncie el `SeekHead` cuando no
     * apareció en el recorrido. Esté donde esté, un índice roto no rechaza el archivo: ver
     * [readCuesOrWarn]. Si el que no se pudo leer es el mismo al que apunta el `SeekHead` —el
     * muxer lo reserva delante de los clusters y lo anuncia ahí—, no se vuelve a intentar: daba
     * dos avisos del mismo índice.
     *
     * Un archivo que acaba a mitad de la cabecera de un elemento —una grabación cortada justo
     * cuando empezaba a escribir el primer cluster— se abre sin paquetes, con un aviso. Hasta la
     * 4.0 se rechazaba, y es un archivo que el muxer deja válido en todo momento. Lo mismo si donde
     * debería empezar un elemento hay un byte 0, que ningún id puede tener: es una grabación que
     * reservó espacio con ceros y se cortó antes de llenarlo, y hasta ahora el «id» de byte 0
     * rechazaba el archivo mientras que el corte a mitad de cabecera se abría.
     */
    fun parse(): MkvHeader {
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
            if (input.readByte() == 0) {
                warn("el archivo tiene ceros en $elementStart, donde debería empezar un elemento: no hay clusters que leer")
                break
            }
            input.position = elementStart
            val el = try {
                reader.readElement()
            } catch (e: EOFException) {
                warn("el archivo acaba a mitad de la cabecera de un elemento en $elementStart: no hay clusters que leer")
                break
            }
            when (el.id) {
                MatroskaIds.INFO -> parseInfo(el)
                MatroskaIds.TRACKS -> parseTracks(el)
                MatroskaIds.SEEK_HEAD -> parseSeekHead(el)
                MatroskaIds.CUES -> if (el.size < 0) {
                    reader.skip(el)
                } else {
                    inlineCuesPos = elementStart - segmentDataStart
                    readCuesOrWarn(el)
                    input.position = el.dataEnd
                }
                MatroskaIds.CLUSTER -> {
                    firstClusterPosition = elementStart
                    break
                }
                else -> reader.skip(el)
            }
        }

        if (cues.isEmpty() && cuesPosFromSeekHead >= 0 && cuesPosFromSeekHead != inlineCuesPos) {
            val saved = input.position
            try {
                input.position = segmentDataStart + cuesPosFromSeekHead
                val el = reader.readElement()
                if (el.id == MatroskaIds.CUES) readCuesOrWarn(el)
            } catch (e: Exception) {
                if (!EbmlReader.isCorruptData(e)) throw e
                warn("el SeekHead apunta a un índice Cues que no se puede leer (${e.message}): $NO_INDEX")
            }
            input.position = saved
        }
        cues.sortBy { it.timeTicks }

        return MkvHeader(
            timestampScaleNs, segmentDataStart, segmentEnd, firstClusterPosition, durationUs,
            tracks, defaultDurationNs, contentEncodings, discardedTracks, cues,
        )
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
     * Un uint que el modelo guarda en `Int`, o -1 —que todo constructor de pista o de color
     * rechaza— si no cabe. Un `toInt()` a secas se quedaba con los 32 bits bajos: un `PixelWidth`
     * de 2³² + 64 se leía como 64, sin ningún aviso.
     */
    private fun readIntField(el: EbmlElement): Int = reader.readUInt(el).let { if (it in 0..Int.MAX_VALUE) it.toInt() else -1 }

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
                else -> reader.skip(el)
            }
        }
        durationUs = declaredDurationUs(durationTicks, timestampScaleNs)
    }

    private fun parseSeekHead(seekHeadEl: EbmlElement) {
        val seekHead = requireSized(seekHeadEl)
        while (input.position < seekHead.dataEnd) {
            val seek = reader.readElement()
            if (seek.id != MatroskaIds.SEEK) { reader.skip(seek); continue }
            requireSized(seek)
            var targetId = 0L
            var position = -1L
            while (input.position < seek.dataEnd) {
                val el = reader.readElement()
                when (el.id) {
                    MatroskaIds.SEEK_ID -> targetId = reader.readSeekId(el)
                    MatroskaIds.SEEK_POSITION -> position = reader.readUInt(el)
                    else -> reader.skip(el)
                }
            }
            if (targetId == MatroskaIds.CUES && position >= 0) cuesPosFromSeekHead = position
        }
    }

    /**
     * Lee los `TrackEntry` hasta [MAX_TRACK_ENTRIES]; los que pasen se saltan con un solo aviso.
     * Sin tope, 50 000 pistas declaradas —35 bytes cada una— hacían recorrerlas todas por cada
     * paquete leído tras una búsqueda.
     */
    private fun parseTracks(tracksElement: EbmlElement) {
        val tracksEl = requireSized(tracksElement)
        while (input.position < tracksEl.dataEnd) {
            val entry = reader.readElement()
            if (entry.id != MatroskaIds.TRACK_ENTRY) { reader.skip(entry); continue }
            if (trackEntries >= MAX_TRACK_ENTRIES) {
                if (trackEntries == MAX_TRACK_ENTRIES) warn("el archivo declara más de $MAX_TRACK_ENTRIES pistas: se ignoran las siguientes")
                trackEntries++
                input.position = tracksEl.dataEnd
                return
            }
            trackEntries++
            parseTrackEntry(requireSized(entry))
        }
    }

    /** Avisa de que la pista [number] se descarta, y la recuerda para que sus bloques no repitan el aviso. */
    private fun discard(number: Int, reason: String) {
        warn("pista $number descartada: $reason")
        discardedTracks += number
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
     * Una entrada con parámetros imposibles se descarta con aviso en vez de tumbar la lectura del
     * archivo: las demás siguen siendo usables. Eso incluye un `TrackNumber` ausente, 0 o que no
     * cabe en `Int`, que hasta ahora lanzaba desde el constructor o desaparecía en silencio; y un
     * color imposible solo se ignora, con aviso, conservando la pista (ver [colorInfo]).
     *
     * Un número repetido sustituye a la entrada anterior con aviso, y con **sus** codificaciones:
     * antes la segunda heredaba las de la primera y sus fotogramas salían con una cabecera ajena.
     */
    private fun parseTrackEntry(entry: EbmlElement) {
        val f = TrackFields()
        while (input.position < entry.dataEnd) {
            val el = reader.readElement()
            when (el.id) {
                MatroskaIds.TRACK_NUMBER -> f.number = reader.readUInt(el)
                MatroskaIds.TRACK_TYPE -> f.type = reader.readUInt(el)
                MatroskaIds.CODEC_ID -> f.codecId = reader.readString(el)
                MatroskaIds.CODEC_PRIVATE -> f.codecPrivate = reader.readBinary(el)
                MatroskaIds.LANGUAGE -> f.language = reader.readString(el)
                MatroskaIds.NAME -> f.name = reader.readString(el)
                MatroskaIds.FLAG_DEFAULT -> f.default = reader.readUInt(el) != 0L
                MatroskaIds.DEFAULT_DURATION -> f.defaultDurationNs = reader.readUInt(el)
                MatroskaIds.CODEC_DELAY -> f.codecDelayNs = reader.readUInt(el)
                MatroskaIds.CONTENT_ENCODINGS -> f.encodings = parseContentEncodings(requireSized(el))
                MatroskaIds.VIDEO -> parseVideo(requireSized(el), f)
                MatroskaIds.AUDIO -> parseAudio(requireSized(el), f)
                else -> reader.skip(el)
            }
        }
        val rawNumber = f.number ?: run {
            warn("se descarta un TrackEntry sin TrackNumber (codecId '${f.codecId}')")
            return
        }
        if (rawNumber !in 1..Int.MAX_VALUE) {
            warn("se descarta un TrackEntry con TrackNumber ${java.lang.Long.toUnsignedString(rawNumber)}, fuera de rango (codecId '${f.codecId}')")
            return
        }
        val number = rawNumber.toInt()

        f.encodings.unsupported?.let { reason -> discard(number, reason); return }
        val encodings = f.encodings.encodings
        val privateData = f.codecPrivate
        if (encodings.isNotEmpty() && privateData != null) {
            f.codecPrivate = try {
                decoder.decodePrivate(privateData, encodings)
            } catch (e: Exception) {
                discard(number, "su CodecPrivate comprimido no se pudo descomprimir (${e.message})")
                return
            }
        }

        when (f.displayUnit) {
            DISPLAY_UNIT_PIXELS -> Unit
            DISPLAY_UNIT_ASPECT_RATIO -> {
                val derived = if (f.displayWidth > 0 && f.displayHeight > 0 && f.height > 0) {
                    Math.round(f.height.toDouble() * f.displayWidth / f.displayHeight)
                } else {
                    0L
                }
                f.displayWidth = if (derived in 1..Int.MAX_VALUE) derived.toInt() else 0
                f.displayHeight = if (f.displayWidth > 0) f.height else 0
            }
            else -> { f.displayWidth = 0; f.displayHeight = 0 }
        }

        if (f.outputSampleRate > f.sampleRate && f.outputSampleRate <= MAX_SAMPLE_RATE_HZ) {
            f.sampleRate = f.outputSampleRate
        }

        val color = f.colour?.let { colorInfo(it, number) }
        val built = try {
            buildTrack(f, number, color)
        } catch (e: IllegalArgumentException) {
            discard(number, "no se pudo construir desde su cabecera (${e.message}; codecId '${f.codecId}', tipo ${f.type})")
            return
        }
        val track = built ?: run {
            discard(number, "su códec '${f.codecId}' (tipo ${f.type}) no está soportado")
            return
        }
        if (number in tracks) warn("pista $number declarada más de una vez: se usa la última entrada")
        tracks[number] = track
        defaultDurationNs[number] = f.defaultDurationNs
        if (encodings.isNotEmpty()) contentEncodings[number] = encodings else contentEncodings.remove(number)
    }

    private fun parseVideo(video: EbmlElement, f: TrackFields) {
        while (input.position < video.dataEnd) {
            val v = reader.readElement()
            when (v.id) {
                MatroskaIds.PIXEL_WIDTH -> f.width = readIntField(v)
                MatroskaIds.PIXEL_HEIGHT -> f.height = readIntField(v)
                MatroskaIds.DISPLAY_WIDTH -> f.displayWidth = readIntField(v)
                MatroskaIds.DISPLAY_HEIGHT -> f.displayHeight = readIntField(v)
                MatroskaIds.DISPLAY_UNIT -> f.displayUnit = readIntField(v)
                MatroskaIds.COLOUR -> f.colour = parseColour(v)
                MatroskaIds.PROJECTION -> f.rotationDegrees = parseProjectionRotation(v)
                else -> reader.skip(v)
            }
        }
    }

    private fun parseAudio(audio: EbmlElement, f: TrackFields) {
        while (input.position < audio.dataEnd) {
            val a = reader.readElement()
            when (a.id) {
                MatroskaIds.SAMPLING_FREQUENCY -> f.sampleRate = reader.readFloat(a)
                MatroskaIds.OUTPUT_SAMPLING_FREQUENCY -> f.outputSampleRate = reader.readFloat(a)
                MatroskaIds.CHANNELS -> f.channels = readIntField(a)
                MatroskaIds.BIT_DEPTH -> f.bitDepth = readIntField(a)
                else -> reader.skip(a)
            }
        }
    }

    /**
     * Lee `ContentEncodings`. Devuelve las codificaciones ordenadas de mayor a menor
     * `ContentEncodingOrder`, que es el orden en que la especificación manda deshacerlas, o el
     * motivo por el que no se saben deshacer.
     *
     * Ignorarlas, que era lo que se hacía, no fallaba: entregaba en silencio fotogramas sin los
     * bytes de cabecera que el muxer había quitado —lo que `mkvmerge` hacía por defecto en sus
     * versiones antiguas—, y el decodificador recibía basura sin ningún aviso.
     *
     * Una cadena de más de [ContentEncoding.MAX_ENCODINGS] o una eliminación de cabecera de más
     * de [ContentEncoding.MAX_HEADER_STRIPPING_BYTES] tampoco se aceptan: ver sus KDoc. Lo que
     * sobra no llega a leerse.
     */
    private fun parseContentEncodings(container: EbmlElement): ParsedEncodings {
        val result = ArrayList<ContentEncoding>()
        var unsupported: String? = null
        while (input.position < container.dataEnd) {
            val encodingEl = reader.readElement()
            if (encodingEl.id != MatroskaIds.CONTENT_ENCODING) { reader.skip(encodingEl); continue }
            requireSized(encodingEl)
            if (result.size == ContentEncoding.MAX_ENCODINGS) {
                input.position = container.dataEnd
                return ParsedEncodings(emptyList(), "encadena más de ${ContentEncoding.MAX_ENCODINGS} codificaciones de contenido (ContentEncoding)")
            }
            var order = 0L
            var scope = ContentEncoding.SCOPE_FRAMES
            var type = 0L
            var algorithm = 0L
            var settings: ByteArray? = null
            var oversizedSettings = -1L
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
                            MatroskaIds.CONTENT_COMP_SETTINGS -> if (c.size > ContentEncoding.MAX_HEADER_STRIPPING_BYTES) {
                                oversizedSettings = c.size
                                reader.skip(c)
                            } else {
                                settings = reader.readBinary(c)
                            }
                            else -> reader.skip(c)
                        }
                    }
                    MatroskaIds.CONTENT_ENCRYPTION -> { type = ContentEncoding.TYPE_ENCRYPTION; reader.skip(el) }
                    else -> reader.skip(el)
                }
            }
            val known = type == ContentEncoding.TYPE_COMPRESSION &&
                (algorithm == ContentEncoding.ALGO_ZLIB || algorithm == ContentEncoding.ALGO_HEADER_STRIPPING)
            if (!known) {
                unsupported = unsupported ?: "usa una compresión o un cifrado de contenido (ContentEncoding) que no está soportado"
            } else if (algorithm == ContentEncoding.ALGO_HEADER_STRIPPING && oversizedSettings >= 0) {
                unsupported = unsupported ?: "su eliminación de cabecera (ContentCompSettings) ocupa $oversizedSettings bytes, " +
                    "más de los ${ContentEncoding.MAX_HEADER_STRIPPING_BYTES} que se aceptan"
            }
            result.add(ContentEncoding(order, scope, algorithm, settings))
        }
        if (unsupported != null) return ParsedEncodings(emptyList(), unsupported)
        result.sortByDescending { it.order }
        return ParsedEncodings(result, null)
    }

    /**
     * Construye la pista, `null` si su tipo o su códec no están soportados —lo mismo que dice
     * [readsCodec] sin construirla—, o lanza para que [parseTrackEntry] la descarte con el motivo.
     *
     * El tope de frecuencia es lo segundo: una tasa fuera del rango de lo que puede ser audio
     * viene siempre de un campo corrupto y al truncar a `Int` se convierte en `Int.MAX_VALUE`,
     * que envenenaría el `MediaFormat` y el `SampleEntry` de cualquier conversión. Ahí descartar
     * es la única política posible, porque no queda ningún valor bueno al que caer.
     */
    private fun buildTrack(f: TrackFields, number: Int, color: ColorInfo?): TrackInfo? = when (f.type) {
        MatroskaIds.TRACK_TYPE_VIDEO -> VideoCodec.fromMatroskaId(f.codecId)?.let { codec ->
            TrackInfo.Video(codec, f.width, f.height) {
                displayWidth = if (f.displayWidth > 0) f.displayWidth else f.width
                displayHeight = if (f.displayHeight > 0) f.displayHeight else f.height
                frameRate = if (f.defaultDurationNs > 0) 1e9 / f.defaultDurationNs else 0.0
                rotationDegrees = f.rotationDegrees
                this.color = color
                codecPrivate = f.codecPrivate
                language = f.language
                name = f.name
                default = f.default
            }.withId(number)
        }
        MatroskaIds.TRACK_TYPE_AUDIO -> AudioCodec.fromMatroskaId(f.codecId)?.let { codec ->
            require(f.sampleRate <= MAX_SAMPLE_RATE_HZ) {
                "frecuencia de audio imposible: ${f.sampleRate}"
            }
            TrackInfo.Audio(codec, f.sampleRate.toInt(), f.channels) {
                bitDepth = f.bitDepth
                codecDelayUs = f.codecDelayNs / 1000
                codecPrivate = f.codecPrivate
                language = f.language
                name = f.name
                default = f.default
            }.withId(number)
        }
        else -> null
    }

    private fun parseColour(colourEl: EbmlElement): Colour {
        val colour = requireSized(colourEl)
        val result = Colour()
        while (input.position < colour.dataEnd) {
            val el = reader.readElement()
            when (el.id) {
                MatroskaIds.COLOUR_PRIMARIES -> result.primaries = readIntField(el)
                MatroskaIds.TRANSFER_CHARACTERISTICS -> result.transfer = readIntField(el)
                MatroskaIds.MATRIX_COEFFICIENTS -> result.matrix = readIntField(el)
                MatroskaIds.COLOUR_RANGE -> result.fullRange = reader.readUInt(el) == 2L
                MatroskaIds.MAX_CLL -> result.maxCll = readIntField(el)
                MatroskaIds.MAX_FALL -> result.maxFall = readIntField(el)
                MatroskaIds.MASTERING_METADATA -> {
                    val values = result.mastering ?: DoubleArray(MASTERING_FIELDS.size).also { result.mastering = it }
                    while (input.position < requireSized(el).dataEnd) {
                        val m = reader.readElement()
                        val field = MASTERING_FIELDS.indexOf(m.id)
                        if (field >= 0) values[field] = reader.readFloat(m) else reader.skip(m)
                    }
                }
                else -> reader.skip(el)
            }
        }
        return result
    }

    /**
     * El [ColorInfo] de la pista [number], o lo que se pueda salvar de él. Unos metadatos HDR
     * imposibles —luminancia mínima por encima de la máxima, una cromaticidad fuera de [0, 1] o
     * `NaN`— se ignoran conservando el resto del color, y unos códigos fuera de rango ignoran el
     * color entero; las dos cosas con aviso, como hace FFmpeg. Hasta ahora los constructores
     * lanzaban fuera de todo `try` y el archivo entero no se abría por un metadato.
     */
    private fun colorInfo(colour: Colour, number: Int): ColorInfo? {
        val mastering = colour.mastering
        val hdr = if (mastering != null || colour.maxCll > 0 || colour.maxFall > 0) {
            val m = mastering ?: DoubleArray(MASTERING_FIELDS.size)
            try {
                HdrStaticInfo(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], colour.maxCll, colour.maxFall)
            } catch (e: IllegalArgumentException) {
                warn("pista $number: se ignoran sus metadatos HDR, que son imposibles (${e.message})")
                null
            }
        } else {
            null
        }
        return try {
            ColorInfo {
                primaries = colour.primaries
                transfer = colour.transfer
                matrix = colour.matrix
                fullRange = colour.fullRange
                this.hdr = hdr
            }
        } catch (e: IllegalArgumentException) {
            warn("pista $number: se ignora su descripción de color, que es imposible (${e.message})")
            null
        }
    }

    /** Mapea el PoseRoll de una Projection rectangular de vuelta a rotación 0/90/180/270. */
    private fun parseProjectionRotation(projectionEl: EbmlElement): Int {
        val projection = requireSized(projectionEl)
        var roll = 0.0
        while (input.position < projection.dataEnd) {
            val el = reader.readElement()
            when (el.id) {
                MatroskaIds.PROJECTION_POSE_ROLL -> roll = reader.readFloat(el)
                else -> reader.skip(el)
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

    /**
     * Lee el índice [cuesElement] sin dejar que lo tumbe: si está roto se descarta **entero**
     * —uno a medias podría mandar una búsqueda a un sitio equivocado— y se avisa por `onWarning`.
     *
     * El archivo se sigue leyendo completo y las búsquedas funcionan, solo que recorriendo los
     * clusters. Hasta la 3.x el fallo se tragaba en silencio, y quien veía búsquedas lentas en un
     * archivo concreto no tenía nada que lo explicara; y si el índice roto estaba delante de los
     * clusters, se rechazaba el archivo entero. Un error de E/S de verdad no es un índice roto y
     * llega a quien abre el archivo.
     */
    private fun readCuesOrWarn(cuesElement: EbmlElement) {
        try {
            parseCues(cuesElement)
        } catch (e: Exception) {
            if (!EbmlReader.isCorruptData(e)) throw e
            cues.clear()
            warn("índice Cues ilegible (${e.message}): $NO_INDEX")
        }
    }

    /**
     * Lee los puntos del índice: uno por cada `CueTrackPositions`, y no solo el último de cada
     * `CuePoint`, que era lo que se guardaba —si era el del audio, el punto dejaba de contar para
     * la pista ancla de las búsquedas—.
     *
     * Se descartan, con un aviso por índice, los que no apuntan dentro del `Segment`: uno cerca de
     * `Long.MAX_VALUE` desbordaba al sumarle el principio del `Segment` y `seekTo` lanzaba, y uno
     * más allá del final colocaba la lectura fuera del archivo y la búsqueda terminaba en
     * silencio. Pasados [MAX_CUES] puntos se ignora el resto, también con aviso: cada uno ocupa en
     * memoria unas cinco veces lo que en el archivo, y lo que se conserva sigue siendo un índice
     * correcto, solo menos denso al final.
     */
    private fun parseCues(cuesElement: EbmlElement) {
        val cuesEl = requireSized(cuesElement)
        val segmentSize = segmentEnd - segmentDataStart
        var outside = 0
        var capped = false
        /** Los de cada `CuePoint` hasta tener su `CueTime`, que no tiene por qué ir delante. */
        val positions = ArrayList<MkvCue>()
        points@ while (input.position < cuesEl.dataEnd) {
            val point = reader.readElement()
            if (point.id != MatroskaIds.CUE_POINT) { reader.skip(point); continue }
            requireSized(point)
            var timeTicks = -1L
            positions.clear()
            while (input.position < point.dataEnd) {
                val el = reader.readElement()
                when (el.id) {
                    MatroskaIds.CUE_TIME -> timeTicks = reader.readUInt(el)
                    MatroskaIds.CUE_TRACK_POSITIONS -> {
                        var clusterPos = -1L
                        var track = 0L
                        while (input.position < requireSized(el).dataEnd) {
                            val p = reader.readElement()
                            when (p.id) {
                                MatroskaIds.CUE_CLUSTER_POSITION -> clusterPos = reader.readUInt(p)
                                MatroskaIds.CUE_TRACK -> track = reader.readUInt(p)
                                else -> reader.skip(p)
                            }
                        }
                        if (clusterPos !in 0 until segmentSize) {
                            outside++
                        } else if (cues.size + positions.size >= MAX_CUES) {
                            capped = true
                            break@points
                        } else {
                            positions += MkvCue(0, clusterPos, track.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                        }
                    }
                    else -> reader.skip(el)
                }
            }
            if (timeTicks >= 0) for (p in positions) cues += MkvCue(timeTicks, p.clusterPosition, p.track)
        }
        if (outside > 0) warn("$outside puntos del índice Cues no apuntan a un cluster dentro del Segment: se ignoran")
        if (capped) warn("el índice Cues tiene más de $MAX_CUES puntos: se ignoran los siguientes")
    }

    /**
     * Constantes internas, y los dos criterios de la cabecera que necesita también `MkvRepair`
     * para declarar lo mismo que lee el demuxer. Las constantes van marcadas `private` una a una
     * por lo mismo que en `MkvDemuxer`: un `const val` de un objeto compañero se compila como
     * campo estático de la clase que lo contiene y con **su propia** visibilidad.
     */
    companion object {
        /**
         * Si el demuxer lee una pista de tipo [type] con el códec [codecId]: vídeo de [VideoCodec]
         * o audio de [AudioCodec]. Es lo que mira [buildTrack], y la reparación cuenta para la
         * duración y el índice solo las pistas que lo cumplen: contar las demás —subtítulos, un
         * códec sin soporte— le hacía declarar una duración que no tenía ningún paquete.
         */
        fun readsCodec(type: Long, codecId: String): Boolean = when (type) {
            MatroskaIds.TRACK_TYPE_VIDEO -> VideoCodec.fromMatroskaId(codecId) != null
            MatroskaIds.TRACK_TYPE_AUDIO -> AudioCodec.fromMatroskaId(codecId) != null
            else -> false
        }

        /**
         * La `Duration` de [durationTicks] tics de [timestampScaleNs] ns en µs, o 0 si no es un
         * valor positivo. Conserva la fracción de tic, que mkvmerge escribe: la reparación de un
         * archivo ya cerrado la truncaba a tics y daba 1 234 000 µs donde el demuxer lee 1 234 567.
         */
        fun declaredDurationUs(durationTicks: Double, timestampScaleNs: Long): Long {
            val duration = durationTicks * timestampScaleNs / 1000.0
            return if (duration.isFinite() && duration > 0) duration.toLong() else 0L
        }

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

        /**
         * Lo más que se acepta en `Tracks`. `MkvMuxer` no pasa de 126, porque es lo que cabe en el
         * número de pista de un byte de un bloque, y ningún archivo real se acerca; el tope acota
         * lo que cuesta cada paquete tras una búsqueda, que mira las pistas que le quedan por oír.
         */
        private const val MAX_TRACK_ENTRIES = 256

        /**
         * Lo más que se conserva del índice: medio millón de puntos, unos 20 MB en memoria. Con
         * un keyframe por segundo son más de cien horas, y con un vídeo todo intra a 30 fps, casi
         * cinco.
         */
        private const val MAX_CUES = 1 shl 19

        /** Los ids de `MasteringMetadata`, en el orden de los parámetros de [HdrStaticInfo]. */
        private val MASTERING_FIELDS = longArrayOf(
            MatroskaIds.PRIMARY_R_X, MatroskaIds.PRIMARY_R_Y,
            MatroskaIds.PRIMARY_G_X, MatroskaIds.PRIMARY_G_Y,
            MatroskaIds.PRIMARY_B_X, MatroskaIds.PRIMARY_B_Y,
            MatroskaIds.WHITE_POINT_X, MatroskaIds.WHITE_POINT_Y,
            MatroskaIds.LUMINANCE_MAX, MatroskaIds.LUMINANCE_MIN,
        )

        /** Lo que supone para quien lee perder el índice, en los avisos. */
        private const val NO_INDEX = "se ignora, y las búsquedas recorrerán los clusters en vez de saltar"
    }
}
