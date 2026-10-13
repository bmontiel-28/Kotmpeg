package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.Demuxer
import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.OpusConfig
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ColorInfo
import com.braymon.kotmpeg.model.HdrStaticInfo
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.Timestamps
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import java.io.File

/**
 * Demuxer MP4 (ISO BMFF) para archivos planos y fragmentados (fMP4).
 *
 * Archivos planos: recorre las muestras desde las tablas `stbl`
 * (stts/ctts/stss/stsc/stsz/stco/co64), respetando las edit lists para los offsets de
 * presentación. Archivos fragmentados (`moov` vacío + pares `moof`/`mdat`): recorre cada
 * fragmento leyendo tfhd/tfdt/trun —incluidos los defaults de trex, el direccionamiento
 * default-base-is-moof y los offsets de composición firmados (versión 1)— y aplicando la misma
 * edit list que a una pista plana. Los paquetes se emiten intercalados en orden de decodificación
 * entre pistas; las no soportadas se saltan.
 *
 * Las muestras **no se cargan en memoria**: al abrir se recorren una vez para validarlas y dejar
 * un punto de control cada pocos cientos, y después se leen del archivo a medida que hacen falta
 * (ver [TrackSamples]). Un archivo de dos horas a 60 fps con audio se abre con menos de 0,5 MB,
 * donde la 3.0.0 necesitaba unos 28, y la diferencia crece con la duración.
 */
public class Mp4Demuxer(
    private val input: SeekableInput,
    /**
     * Avisos no fatales: pistas que no se pueden parsear y fragmentos ilegibles, que se
     * descartan para que el resto del archivo siga siendo usable.
     *
     * Es la **única** forma de enterarse de que algo se perdió: sin él, una pista ilegible
     * desaparece en silencio absoluto y el síntoma que llega —"mi vídeo se abre pero sin
     * audio"— se queda sin ningún diagnóstico posible.
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

    private class Box(val type: String, val dataStart: Long, val dataEnd: Long)

    /**
     * Pista ya leída. [cursor] es la siguiente muestra que entregará [readPacket], y [reader] está
     * siempre colocado en ella mientras quede alguna. `samples.dtsMonotonic` decide si [seekTo]
     * puede buscar por bisección: es lo normal, pero un fMP4 manipulado puede traer `tfdt` que
     * retroceden, y ahí se recorre la pista entera.
     */
    private class ParsedTrack(val info: TrackInfo, val samples: TrackSamples) {
        val reader: SampleCursor = samples.cursor()
        var cursor: Int = 0
    }

    /** Metadatos de la pista que no dependen del códec: los del `tkhd`, `mdhd` y `udta`. */
    private class TrackHeader(
        val language: String,
        val name: String?,
        val default: Boolean,
    )

    /**
     * Pista declarada en el moov pero sin muestras stbl: candidata a fragmentos.
     *
     * Lleva la lista de edición de su `trak` igual que una pista plana: el `elst` del `moov` vale
     * para toda la línea de tiempo de la pista, fragmentos incluidos. Hasta la 3.0.0 se perdía, y un
     * fMP4 con cebado de audio —el propio `FragmentedMp4Muxer` lo escribe así— se leía con el audio
     * 21 ms por detrás de donde lo ponen FFmpeg y el MP4 plano equivalente.
     */
    private class FragmentShell(
        val trackId: Int,
        val handlerType: String,
        val desc: SampleDescription,
        val mediaTimescale: Long,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val header: TrackHeader,
        val elstMediaTime: Long,
        val elstEmptyDurationUs: Long,
    )

    private class TrexDefaults(val duration: Long, val size: Long, val flags: Long)

    private val parsedTracks = ArrayList<ParsedTrack>()
    private var trackList: List<TrackInfo> = emptyList()
    private val fragmentShells = LinkedHashMap<Int, FragmentShell>()
    private val trexDefaults = HashMap<Int, TrexDefaults>()

    /** Pistas ya anunciadas como ilegibles, para no repetir el aviso por cada muestra. */
    private val unreadableWarned = HashSet<Int>()

    override var durationUs: Long = 0
        private set

    override val tracks: List<TrackInfo> get() = trackList

    init {
        try {
            parse()
        } catch (t: Throwable) {
            runCatching { input.close() }
            throw t
        }
        trackList = parsedTracks.map { it.info }
    }

    /**
     * Lee la cabecera de la siguiente caja, o null si no cabe o es imposible.
     *
     * El fin de la caja se calcula con [Math.addExact] porque solo la vía de `largesize` puede
     * desbordar el `Long`: es el patrón crudo de 64 bits del archivo, y un desbordamiento en
     * Kotlin no lanza, da la vuelta en silencio. Un valor elegido a propósito puede hacer que el
     * resultado caiga de vuelta dentro de la ventana válida, y entonces el parser seguiría
     * leyendo desde un offset inventado como si fuera una cabecera. Se trata como fin de
     * escaneo, igual que cualquier otra cabecera imposible.
     */
    private fun readBoxHeader(end: Long): Box? {
        if (input.position + 8 > end || input.remaining < 8) return null
        val start = input.position
        var size = input.readInt32().toLong() and 0xFFFFFFFFL
        val type = String(input.readBytes(4), Charsets.US_ASCII)
        var headerLen = 8L
        if (size == 1L) {
            if (input.position + 8 > end || input.remaining < 8) return null
            size = input.readInt64()
            headerLen = 16
        } else if (size == 0L) {
            size = end - start
        }
        if (size < headerLen) return null
        val dataEnd = try {
            Math.addExact(start, size)
        } catch (_: ArithmeticException) {
            return null
        }
        return Box(type, start + headerLen, dataEnd)
    }

    /**
     * Número de entradas que caben realmente en lo que queda de la caja. Las cuentas de
     * las tablas vienen del archivo: reservar `LongArray(n)` sin validarlas permite que
     * unos pocos bytes manipulados provoquen un OutOfMemoryError (que es `Error`, no
     * `Exception`, así que ni siquiera lo atrapa el try/catch por pista).
     */
    private fun boundedCount(declared: Int, bytesPerEntry: Int, end: Long): Int {
        val declaredUnsigned = declared.toLong() and 0xFFFFFFFFL
        if (declaredUnsigned == 0L) return 0
        val available = end - input.position
        if (available <= 0) return 0
        val fits = available / bytesPerEntry
        return minOf(declaredUnsigned, fits).toInt()
    }

    /**
     * Cota para las tablas que **no** guardan una entrada por muestra —`stsz` con tamaño
     * constante y `trun` sin campos por muestra—, donde el tamaño de la caja no dice nada
     * de la cuenta. El límite lo pone la física del archivo: cada muestra ocupa al menos
     * [bytesPerSampleInFile] bytes de datos, así que no puede haber más de
     * `length / bytesPerSampleInFile`.
     *
     * Se mide contra el archivo **completo**, no contra lo que queda por leer: con el `moov`
     * al final las muestras están *detrás* de la caja que se está parseando, y acotar por
     * el remanente truncaría pistas legítimas.
     */
    private fun physicalCount(declared: Int, bytesPerSampleInFile: Long): Int {
        val declaredUnsigned = declared.toLong() and 0xFFFFFFFFL
        val physicalMax = input.length / bytesPerSampleInFile.coerceAtLeast(1)
        return minOf(declaredUnsigned, physicalMax).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private inline fun scanChildren(start: Long, end: Long, handler: (Box) -> Unit) {
        input.position = start
        while (input.position + 8 <= end) {
            val boxStart = input.position
            val box = readBoxHeader(end) ?: break
            if (box.dataEnd <= boxStart || box.dataEnd > end) break // corrupto; evita bucles
            handler(box)
            input.position = box.dataEnd
        }
    }

    private fun parse() {
        var moov: Box? = null
        runCatching {
            scanChildren(0, input.length) { box ->
                if (box.type == "moov") moov = box
            }
        }
        val moovBox = moov ?: throw IllegalStateException("no se encontró la caja moov (¿archivo truncado?)")

        var movieTimescale = 1000L
        var movieDuration = 0L
        val trakBoxes = ArrayList<Box>()
        runCatching {
            scanChildren(moovBox.dataStart, moovBox.dataEnd) { box ->
                when (box.type) {
                    "mvex" -> scanChildren(box.dataStart, box.dataEnd) { child ->
                        if (child.type == "trex") {
                            input.position = child.dataStart
                            input.skip(4)
                            val trackId = input.readInt32()
                            input.skip(4) // default_sample_description_index
                            val duration = input.readInt32().toLong() and 0xFFFFFFFFL
                            val size = input.readInt32().toLong() and 0xFFFFFFFFL
                            val flags = input.readInt32().toLong() and 0xFFFFFFFFL
                            trexDefaults[trackId] = TrexDefaults(duration, size, flags)
                        }
                    }
                    "mvhd" -> {
                        input.position = box.dataStart
                        val version = input.readByte()
                        input.skip(3)
                        if (version == 1) {
                            input.skip(16)
                            movieTimescale = input.readInt32().toLong() and 0xFFFFFFFFL
                            movieDuration = input.readInt64()
                        } else {
                            input.skip(8)
                            movieTimescale = input.readInt32().toLong() and 0xFFFFFFFFL
                            movieDuration = input.readInt32().toLong() and 0xFFFFFFFFL
                        }
                    }
                    "trak" -> trakBoxes.add(box)
                }
            }
        }
        if (movieTimescale > 0) durationUs = Timestamps.rescaleFloor(movieDuration, 1_000_000, movieTimescale)

        for (trak in trakBoxes) {
            try {
                parseTrak(trak, movieTimescale)
            } catch (e: Exception) {
                runCatching { onWarning("pista descartada: no se pudo parsear su trak (${e.message})") }
            }
        }

        if (fragmentShells.isNotEmpty()) parseFragments()
    }

    /** Mapea la parte de rotación de la matriz tkhd de vuelta a 0/90/180/270 grados. */
    private fun rotationFromMatrix(m: IntArray): Int {
        val a = m[0] shr 16; val b = m[1] shr 16
        val c = m[3] shr 16; val d = m[4] shr 16
        return when {
            a == 0 && b == 1 && c == -1 && d == 0 -> 90
            a == -1 && b == 0 && c == 0 && d == -1 -> 180
            a == 0 && b == -1 && c == 1 && d == 0 -> 270
            else -> 0
        }
    }

    /**
     * Lee un `trak`. Además de lo que hace falta para decodificar, recoge los tres metadatos que
     * escriben los muxers de esta librería y que antes se perdían al releer: el idioma del
     * `mdhd`, el nombre de `udta`/`name` y el bit `track_enabled` del `tkhd`, que es como MP4
     * expresa la pista predeterminada. Sin ellos, un MKV con pistas en varios idiomas convertido
     * a MP4 y vuelta salía con todas en `und` y todas predeterminadas.
     *
     * La escala de la película se valida antes de dividir por ella: un `mvhd` con escala cero
     * hacía que cualquier pista con lista de edición se descartara por una división entre cero.
     *
     * El reparto de muestras en chunks avanza por `stsc` **una sola vez**, en paralelo a los chunks.
     * Antes, para cada chunk se recorría `stsc` desde el principio: con audio y vídeo intercalados
     * hay casi tantas entradas como chunks, y abrir un MP4 de dos horas costaba unos 10 segundos
     * de CPU antes de entregar el primer paquete.
     */
    private fun parseTrak(trak: Box, movieTimescale: Long) {
        var trackId = 0
        var mediaTimescale = 0L
        var handlerType = ""
        var width = 0
        var height = 0
        var rotationDegrees = 0
        var stblBox: Box? = null
        var elstMediaTime = 0L
        var elstEmptyDurationUs = 0L
        var sampleDescription: SampleDescription? = null
        var language = "und"
        var name: String? = null
        var enabled = true

        scanChildren(trak.dataStart, trak.dataEnd) { box ->
            when (box.type) {
                "tkhd" -> {
                    input.position = box.dataStart
                    val version = input.readByte()
                    enabled = (input.readBits(3) and TKHD_TRACK_ENABLED) != 0L
                    input.skip(if (version == 1) 16L else 8L)
                    trackId = input.readInt32()
                    input.skip(4)
                    input.skip(if (version == 1) 8L else 4L)
                    input.skip(8 + 2 + 2 + 2 + 2)
                    val matrix = IntArray(9) { input.readInt32() }
                    rotationDegrees = rotationFromMatrix(matrix)
                    width = (input.readInt32() ushr 16)
                    height = (input.readInt32() ushr 16)
                }
                "edts" -> scanChildren(box.dataStart, box.dataEnd) { elst ->
                    if (elst.type == "elst") {
                        input.position = elst.dataStart
                        val version = input.readByte()
                        input.skip(3)
                        val count = boundedCount(input.readInt32(), if (version == 1) 20 else 12, elst.dataEnd)
                        repeat(count) {
                            val segDuration: Long
                            val mediaTime: Long
                            if (version == 1) {
                                segDuration = input.readInt64(); mediaTime = input.readInt64()
                            } else {
                                segDuration = input.readInt32().toLong() and 0xFFFFFFFFL
                                mediaTime = input.readInt32().toLong()
                            }
                            input.skip(4) // media_rate
                            if (mediaTime == -1L) {
                                if (movieTimescale > 0) {
                                    elstEmptyDurationUs = Timestamps.rescaleFloor(segDuration, 1_000_000, movieTimescale)
                                }
                            } else if (elstMediaTime == 0L) {
                                elstMediaTime = mediaTime
                            }
                        }
                    }
                }
                "mdia" -> scanChildren(box.dataStart, box.dataEnd) { child ->
                    when (child.type) {
                        "mdhd" -> {
                            input.position = child.dataStart
                            val version = input.readByte()
                            input.skip(3)
                            input.skip(if (version == 1) 16L else 8L)
                            mediaTimescale = input.readInt32().toLong() and 0xFFFFFFFFL
                            input.skip(if (version == 1) 8L else 4L)
                            language = SampleEntries.unpackLanguage(input.readBits(2).toInt()) // idioma ISO 639-2/T
                        }
                        "hdlr" -> {
                            input.position = child.dataStart
                            input.skip(8)
                            handlerType = String(input.readBytes(4), Charsets.US_ASCII)
                        }
                        "minf" -> scanChildren(child.dataStart, child.dataEnd) { minfChild ->
                            if (minfChild.type == "stbl") stblBox = minfChild
                        }
                    }
                }
                "udta" -> scanChildren(box.dataStart, box.dataEnd) { child ->
                    if (child.type == "name") name = readTrackName(child)
                }
            }
        }
        val header = TrackHeader(language, name, enabled)

        val stbl = stblBox ?: return
        if (mediaTimescale <= 0) return
        if (handlerType != "vide" && handlerType != "soun") return

        var stts = FileTable(input, 0, 0, 8)
        var ctts = FileTable(input, 0, 0, 8)
        var stss: FileTable? = null
        var stsc = FileTable(input, 0, 0, 12)
        var sampleCount = 0
        var constantSize = 0L
        var sampleSizes: FileTable? = null
        var chunkOffsets = FileTable(input, 0, 0, 4)
        var chunkOffsets64 = false

        scanChildren(stbl.dataStart, stbl.dataEnd) { box ->
            input.position = box.dataStart
            when (box.type) {
                "stsd" -> {
                    input.skip(4)
                    val count = input.readInt32()
                    if (count >= 1) sampleDescription = parseSampleDescription(box.dataEnd)
                }
                "stts" -> {
                    input.skip(4)
                    val n = boundedCount(input.readInt32(), 8, box.dataEnd)
                    stts = FileTable(input, input.position, n, 8) // sample_count, sample_delta
                }
                "ctts" -> {
                    input.skip(4)
                    val n = boundedCount(input.readInt32(), 8, box.dataEnd)
                    ctts = FileTable(input, input.position, n, 8) // sample_count, sample_offset (may be signed, v1)
                }
                "stss" -> {
                    input.skip(4)
                    val n = boundedCount(input.readInt32(), 4, box.dataEnd)
                    stss = FileTable(input, input.position, n, 4) // sample_number, desde 1
                }
                "stsc" -> {
                    input.skip(4)
                    val n = boundedCount(input.readInt32(), 12, box.dataEnd)
                    stsc = FileTable(input, input.position, n, 12) // first_chunk, samples_per_chunk, sample_description_index
                }
                "stsz" -> {
                    input.skip(4)
                    constantSize = input.readInt32().toLong() and 0xFFFFFFFFL // sample_size
                    val declared = input.readInt32() // sample_count
                    if (constantSize != 0L) {
                        sampleCount = physicalCount(declared, constantSize)
                        sampleSizes = null
                    } else {
                        sampleCount = boundedCount(declared, 4, box.dataEnd)
                        sampleSizes = FileTable(input, input.position, sampleCount, 4) // entry_size
                    }
                }
                "stco" -> {
                    input.skip(4)
                    val n = boundedCount(input.readInt32(), 4, box.dataEnd)
                    chunkOffsets = FileTable(input, input.position, n, 4) // chunk_offset
                    chunkOffsets64 = false
                }
                "co64" -> {
                    input.skip(4)
                    val n = boundedCount(input.readInt32(), 8, box.dataEnd)
                    chunkOffsets = FileTable(input, input.position, n, 8) // chunk_offset de 64 bits
                    chunkOffsets64 = true
                }
            }
        }

        val desc = sampleDescription ?: run {
            runCatching {
                onWarning(
                    "pista $trackId ($handlerType) descartada: su formato no se pudo " +
                        "interpretar (códec no soportado o stsd ilegible)",
                )
            }
            return
        }
        if (sampleCount == 0) {
            fragmentShells[trackId] =
                FragmentShell(
                    trackId, handlerType, desc, mediaTimescale, width, height, rotationDegrees, header,
                    elstMediaTime, elstEmptyDurationUs,
                )
            return
        }

        val samples = StblSamples(
            sampleCount, constantSize, sampleSizes, chunkOffsets, chunkOffsets64, stsc, stts, ctts, stss,
            mediaTimescale, elstMediaTime, elstEmptyDurationUs,
        )
        if (samples.size == 0) return

        val frameRateHint = samples.firstPositiveDelta()?.let { mediaTimescale.toDouble() / it } ?: 0.0
        val info = buildTrackInfo(handlerType, desc, trackId, width, height, frameRateHint, rotationDegrees, header)
        if (info != null) parsedTracks.add(ParsedTrack(info, samples))
    }

    /**
     * Texto de `udta`/`name`. Puede venir con un terminador nulo (QuickTime) o sin él (lo que
     * escriben los muxers de aquí); se corta en el primero y se ignora si queda vacío.
     */
    private fun readTrackName(box: Box): String? {
        val length = box.dataEnd - box.dataStart
        if (length <= 0 || length > MAX_TRACK_NAME_BYTES) return null
        input.position = box.dataStart
        val raw = input.readBytes(length.toInt())
        val end = raw.indexOf(0.toByte()).let { if (it < 0) raw.size else it }
        return String(raw, 0, end, Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    /**
     * Convierte una pista ya parseada en [TrackInfo].
     *
     * El tamaño **codificado** lo manda el `SampleEntry`; el `tkhd` hace de respaldo suyo y es,
     * por definición (ISO/IEC 14496-12), el tamaño de **presentación**: aquel al que se escalan
     * las imágenes *antes* de aplicar la matriz. Sin propagarlo, un MP4 con píxeles no cuadrados
     * perdía su geometría al releerlo aunque `Mp4Muxer` sí la escriba.
     *
     * Al ser previo a la matriz no viene intercambiado por una rotación —así lo escriben FFmpeg
     * y el propio `Mp4Muxer`—, pero hay escritores que meten ahí la imagen ya rotada. Ese caso se
     * reconoce porque el par es exactamente la transpuesta del codificado: entonces no aporta
     * ninguna información y se ignora, que es lo que evita darle la vuelta a las dimensiones de
     * todo vídeo vertical.
     */
    private fun buildTrackInfo(
        handlerType: String,
        desc: SampleDescription,
        trackId: Int,
        tkhdWidth: Int,
        tkhdHeight: Int,
        frameRateHint: Double,
        rotationDegrees: Int,
        header: TrackHeader,
    ): TrackInfo? = when {
        handlerType == "vide" && desc.videoCodec != null -> {
            val width = if (desc.width > 0) desc.width else tkhdWidth
            val height = if (desc.height > 0) desc.height else tkhdHeight

            val rotatedQuarter = rotationDegrees == 90 || rotationDegrees == 270
            val transposed = rotatedQuarter && tkhdWidth == height && tkhdHeight == width
            val usable = tkhdWidth > 0 && tkhdHeight > 0 && !transposed

            TrackInfo.Video(
                id = trackId,
                codec = desc.videoCodec,
                width = width,
                height = height,
                displayWidth = if (usable) tkhdWidth else width,
                displayHeight = if (usable) tkhdHeight else height,
                frameRate = frameRateHint,
                rotationDegrees = rotationDegrees,
                color = desc.color,
                codecPrivate = desc.codecConfig,
                language = header.language,
                name = header.name,
                default = header.default,
            )
        }
        handlerType == "soun" && desc.audioCodec != null -> TrackInfo.Audio(
            id = trackId,
            codec = desc.audioCodec,
            sampleRate = desc.sampleRate,
            channelCount = desc.channels,
            codecDelayUs = desc.codecDelayUs,
            codecPrivate = desc.codecConfig,
            language = header.language,
            name = header.name,
            default = header.default,
        )
        else -> null
    }

    /**
     * Recorre los fragmentos una vez, sin guardar sus muestras: solo las cuenta por pista, comprueba
     * si sus DTS avanzan, y deja puntos de control desde los que [FragmentCursor] retoma el
     * recorrido. Los avisos de fragmentos ilegibles salen aquí, una vez; los cursores repiten el
     * mismo recorrido en silencio.
     */
    private fun parseFragments() {
        val ids = fragmentShells.keys.toIntArray()
        val checkpoints = FragmentCheckpoints(ids)
        val stats = Array(ids.size) { FragmentStats(it, checkpoints) }
        val sinks = HashMap<Int, SampleSink>()
        for ((j, id) in ids.withIndex()) sinks[id] = SampleSink { _, _, _, dtsUs, _, durationUs -> stats[j].add(dtsUs, durationUs) }
        val ticks = HashMap<Int, Long>()

        input.position = 0
        while (true) {
            if (checkpoints.count == 0 || stats.any { it.count - it.countAtCheckpoint >= TrackSamples.CHECKPOINT_INTERVAL }) {
                checkpoints.record(input.position, ticks, stats)
            }
            val next = walkTopLevelBox(ticks, sinks) { boxStart, e ->
                runCatching { onWarning("fragmento ilegible en el offset $boxStart (${e.message})") }
            } ?: break
            input.position = next
        }
        for (track in stats) track.closePendingCheckpoints()
        checkpoints.trim()

        for ((j, shell) in fragmentShells.values.withIndex()) {
            val track = stats[j]
            if (track.count == 0) continue
            val frameRateHint = if (track.firstPositiveDurationUs > 0) 1_000_000.0 / track.firstPositiveDurationUs else 0.0
            val info = runCatching {
                buildTrackInfo(
                    shell.handlerType, shell.desc, shell.trackId, shell.width, shell.height,
                    frameRateHint, shell.rotationDegrees, shell.header,
                )
            }.getOrNull() ?: continue
            parsedTracks.add(ParsedTrack(info, FragmentSamples(j, track, checkpoints)))
            val end = track.lastDtsUs + track.lastDurationUs
            if (end > durationUs) durationUs = end
        }
    }

    /**
     * Lee la caja de primer nivel que empieza en la posición actual y, si es un `moof`, la parsea.
     * Devuelve dónde empieza la siguiente, o null si el recorrido termina ahí. Es el paso común de
     * la pasada al abrir y de los cursores, que así recorren el archivo exactamente igual.
     */
    private fun walkTopLevelBox(
        ticks: HashMap<Int, Long>,
        sinks: Map<Int, SampleSink>,
        onError: ((Long, Exception) -> Unit)?,
    ): Long? {
        if (input.position + 8 > input.length) return null
        val boxStart = input.position
        val box = readBoxHeader(input.length) ?: return null
        if (box.dataEnd <= boxStart || box.dataEnd > input.length) return null
        if (box.type == "moof") {
            try {
                parseMoof(boxStart, box, sinks, ticks)
            } catch (e: Exception) {
                onError?.invoke(boxStart, e)
            }
        }
        return box.dataEnd
    }

    /**
     * Cuenta de una pista de fMP4 durante la pasada al abrir. Además rellena, en los puntos de
     * control que la esperan, el DTS de la primera muestra de esta pista que llega tras ellos.
     */
    private class FragmentStats(private val shell: Int, private val checkpoints: FragmentCheckpoints) {
        var count = 0
        var countAtCheckpoint = 0
        var monotonic = true
        var lastDtsUs = 0L
        var lastDurationUs = 0L
        var firstPositiveDurationUs = 0L

        /**
         * Primer punto de control que aún espera el DTS de esta pista. Se sigue por posición y no
         * con un valor centinela, porque cualquier `Long` puede ser un DTS real: un `elst` dañado da
         * `Long.MIN_VALUE`, y con ese centinela la búsqueda empezaba en la muestra equivocada.
         */
        private var pendingCheckpoint = 0

        fun add(dtsUs: Long, durationUs: Long) {
            require(count < TrackSamples.MAX_SAMPLES) { "demasiadas muestras en una pista: $count" }
            while (pendingCheckpoint < checkpoints.count && checkpoints.index(pendingCheckpoint, shell) == count) {
                checkpoints.setDts(pendingCheckpoint, shell, dtsUs)
                pendingCheckpoint++
            }
            if (count > 0 && dtsUs < lastDtsUs) monotonic = false
            if (firstPositiveDurationUs == 0L && durationUs > 0) firstPositiveDurationUs = durationUs
            lastDtsUs = dtsUs
            lastDurationUs = durationUs
            count++
        }

        /** Los puntos tras los que esta pista ya no tiene muestras se quedan con el DTS máximo. */
        fun closePendingCheckpoints() {
            for (c in pendingCheckpoint until checkpoints.count) checkpoints.setDts(c, shell, Long.MAX_VALUE)
            pendingCheckpoint = checkpoints.count
        }
    }

    /**
     * Puntos de control del recorrido de fragmentos, compartidos por todas las pistas: dónde
     * retomar, el tiempo de decodificación acumulado de cada pista (para los `traf` sin `tfdt`), y
     * cuántas muestras de cada una van ya, con el DTS de la siguiente.
     */
    private class FragmentCheckpoints(private val ids: IntArray) {
        var count = 0
            private set
        private var positions = LongArray(16)
        private var ticks = LongArray(16 * ids.size)
        private var indices = IntArray(16 * ids.size)
        private var dts = LongArray(16 * ids.size)

        fun record(position: Long, decodeTicks: Map<Int, Long>, stats: Array<FragmentStats>) {
            if (count == positions.size) {
                positions = positions.copyOf(count * 2)
                ticks = ticks.copyOf(count * 2 * ids.size)
                indices = indices.copyOf(count * 2 * ids.size)
                dts = dts.copyOf(count * 2 * ids.size)
            }
            positions[count] = position
            for (j in ids.indices) {
                ticks[count * ids.size + j] = decodeTicks[ids[j]] ?: 0L
                indices[count * ids.size + j] = stats[j].count
                stats[j].countAtCheckpoint = stats[j].count
            }
            count++
        }

        fun position(c: Int): Long = positions[c]
        fun ticks(c: Int, shell: Int): Long = ticks[c * ids.size + shell]
        fun index(c: Int, shell: Int): Int = indices[c * ids.size + shell]
        fun dts(c: Int, shell: Int): Long = dts[c * ids.size + shell]

        fun setDts(c: Int, shell: Int, value: Long) {
            dts[c * ids.size + shell] = value
        }

        /** Suelta la holgura de los arrays, que crecen duplicando, cuando ya no se añaden puntos. */
        fun trim() {
            positions = positions.copyOf(count)
            ticks = ticks.copyOf(count * ids.size)
            indices = indices.copyOf(count * ids.size)
            dts = dts.copyOf(count * ids.size)
        }
    }

    /** Las muestras de una pista de fMP4, la [shell]-ésima de [fragmentShells]. */
    private inner class FragmentSamples(
        val shell: Int,
        stats: FragmentStats,
        val checkpoints: FragmentCheckpoints,
    ) : TrackSamples() {
        override val size: Int = stats.count
        override val dtsMonotonic: Boolean = stats.monotonic
        override val checkpointCount: Int get() = checkpoints.count
        override fun checkpointIndex(c: Int): Int = checkpoints.index(c, shell)
        override fun checkpointDtsUs(c: Int): Long = checkpoints.dts(c, shell)
        override fun cursor(): SampleCursor = FragmentCursor(this).also { it.moveTo(0) }

        /** El último punto de control que no ha pasado de la muestra [target]. */
        fun checkpointFor(target: Int): Int {
            var low = 0
            var high = checkpoints.count
            while (low < high) {
                val mid = (low + high) ushr 1
                if (checkpoints.index(mid, shell) <= target) low = mid + 1 else high = mid
            }
            return low - 1
        }
    }

    /**
     * Cursor de una pista de fMP4: retoma el recorrido de fragmentos desde un punto de control y
     * guarda en [buffer] las muestras de su pista del último `moof` leído. Parsea los fragmentos
     * completos —todas las pistas— porque el tiempo de un `traf` sin `tfdt` depende de lo anterior,
     * pero solo se queda con lo suyo.
     */
    private inner class FragmentCursor(private val owner: FragmentSamples) : SampleCursor() {
        private val ids = fragmentShells.keys.toIntArray()
        private val ticks = HashMap<Int, Long>()
        private val buffer = SampleBuffer()
        private val sinks: Map<Int, SampleSink> = ids.associateWith { id ->
            if (id == ids[owner.shell]) buffer else IGNORED_SAMPLES
        }
        private var walkPosition = 0L
        private var bufferStart = 0
        private var restored = false
        private var at = 0

        override var index: Int = -1
            private set
        override val offset: Long get() = buffer.offset(at)
        override val size: Int get() = buffer.size(at)
        override val ptsUs: Long get() = buffer.ptsUs(at)
        override val dtsUs: Long get() = buffer.dtsUs(at)
        override val key: Boolean get() = buffer.isKey(at)
        override val durationUs: Long get() = buffer.durationUs(at)

        override fun moveTo(target: Int) {
            require(target in 0 until owner.size) { "muestra $target fuera de 0..${owner.size - 1}" }
            val bufferEnd = bufferStart + buffer.size
            if (!restored || target < bufferStart || target - bufferEnd >= TrackSamples.CHECKPOINT_INTERVAL) {
                restore(owner.checkpointFor(target))
            }
            while (bufferStart + buffer.size <= target) {
                check(loadNextFragment()) { "el recorrido de fragmentos terminó antes de la muestra $target" }
            }
            at = target - bufferStart
            index = target
        }

        private fun restore(c: Int) {
            val checkpoints = owner.checkpoints
            walkPosition = checkpoints.position(c)
            ticks.clear()
            for ((j, id) in ids.withIndex()) ticks[id] = checkpoints.ticks(c, j)
            bufferStart = checkpoints.index(c, owner.shell)
            buffer.clear()
            restored = true
        }

        private fun loadNextFragment(): Boolean {
            bufferStart += buffer.size
            buffer.clear()
            input.position = walkPosition
            while (true) {
                val next = walkTopLevelBox(ticks, sinks, null) ?: return false
                walkPosition = next
                input.position = next
                if (buffer.size > 0) return true
            }
        }
    }

    private fun parseMoof(moofStart: Long, moof: Box, sinks: Map<Int, SampleSink>, ticks: HashMap<Int, Long>) {
        var chainedOffset = -1L
        scanChildren(moof.dataStart, moof.dataEnd) { child ->
            if (child.type == "traf") chainedOffset = parseTraf(moofStart, child, sinks, chainedOffset, ticks)
        }
    }

    /**
     * Devuelve el offset de archivo donde terminan los datos de este traf. [ticks] es el tiempo de
     * decodificación acumulado por pista, para los traf que no llevan tfdt.
     */
    private fun parseTraf(
        moofStart: Long,
        traf: Box,
        sinks: Map<Int, SampleSink>,
        chainedOffset: Long,
        ticks: HashMap<Int, Long>,
    ): Long {
        var trackId = 0
        var baseDataOffset: Long? = null
        var defaultDuration = 0L
        var defaultSize = 0L
        var defaultFlags = 0L
        var baseDecodeTicks = 0L
        var nextSampleOffset = -1L
        var dtsTicks = 0L
        var sawTfdt = false
        var defaultBaseIsMoof = false
        var shell: FragmentShell? = null
        var collected: SampleSink? = null

        scanChildren(traf.dataStart, traf.dataEnd) { child ->
            input.position = child.dataStart
            when (child.type) {
                "tfhd" -> {
                    val versionAndFlags = input.readInt32()
                    val flags = versionAndFlags and 0xFFFFFF
                    defaultBaseIsMoof = flags and 0x020000 != 0
                    trackId = input.readInt32()
                    if (flags and 0x01 != 0) baseDataOffset = input.readInt64()
                    if (flags and 0x02 != 0) input.skip(4)
                    val trex = trexDefaults[trackId]
                    defaultDuration = if (flags and 0x08 != 0) {
                        input.readInt32().toLong() and 0xFFFFFFFFL
                    } else trex?.duration ?: 0
                    defaultSize = if (flags and 0x10 != 0) {
                        input.readInt32().toLong() and 0xFFFFFFFFL
                    } else trex?.size ?: 0
                    defaultFlags = if (flags and 0x20 != 0) {
                        input.readInt32().toLong() and 0xFFFFFFFFL
                    } else trex?.flags ?: 0
                    shell = fragmentShells[trackId]
                    collected = sinks[trackId]
                    if (!sawTfdt) dtsTicks = ticks[trackId] ?: 0L
                }
                "tfdt" -> {
                    val version = input.readByte()
                    input.skip(3)
                    baseDecodeTicks = if (version == 1) input.readInt64()
                    else input.readInt32().toLong() and 0xFFFFFFFFL
                    dtsTicks = baseDecodeTicks
                    sawTfdt = true
                }
                "trun" -> {
                    val currentShell = shell
                    val sink = collected
                    if (currentShell != null && sink != null) {
                        val versionAndFlags = input.readInt32()
                        val version = versionAndFlags ushr 24
                        val flags = versionAndFlags and 0xFFFFFF
                        val declaredCount = input.readInt32()
                        val trafBase = baseDataOffset ?: when {
                            defaultBaseIsMoof -> moofStart
                            chainedOffset >= 0 -> chainedOffset
                            else -> moofStart
                        }
                        var offset = when {
                            flags and 0x01 != 0 -> trafBase + input.readInt32()
                            nextSampleOffset >= 0 -> nextSampleOffset
                            else -> trafBase
                        }
                        val firstSampleFlags: Long? = if (flags and 0x04 != 0) {
                            input.readInt32().toLong() and 0xFFFFFFFFL
                        } else null
                        val ts = currentShell.mediaTimescale
                        val mediaTime = currentShell.elstMediaTime
                        val emptyUs = currentShell.elstEmptyDurationUs
                        fun ticksToUs(t: Long): Long = Timestamps.rescaleFloor(t, 1_000_000, ts)

                        var bytesPerSample = 0
                        if (flags and 0x100 != 0) bytesPerSample += 4
                        if (flags and 0x200 != 0) bytesPerSample += 4
                        if (flags and 0x400 != 0) bytesPerSample += 4
                        if (flags and 0x800 != 0) bytesPerSample += 4
                        val count = if (bytesPerSample > 0) {
                            boundedCount(declaredCount, bytesPerSample, child.dataEnd)
                        } else {
                            physicalCount(declaredCount, defaultSize)
                        }

                        repeat(count) { s ->
                            val duration = if (flags and 0x100 != 0) {
                                input.readInt32().toLong() and 0xFFFFFFFFL
                            } else defaultDuration
                            val size = if (flags and 0x200 != 0) {
                                input.readInt32().toLong() and 0xFFFFFFFFL
                            } else defaultSize
                            val sampleFlags = when {
                                flags and 0x400 != 0 -> input.readInt32().toLong() and 0xFFFFFFFFL
                                s == 0 && firstSampleFlags != null -> firstSampleFlags
                                else -> defaultFlags
                            }
                            val ctts = if (flags and 0x800 != 0) {
                                val raw = input.readInt32()
                                if (version == 0) raw.toLong() and 0xFFFFFFFFL else raw.toLong()
                            } else 0L
                            if (offset >= 0 && size >= 0 &&
                                offset <= input.length && size <= input.length - offset
                            ) {
                                sink.add(
                                    offset = offset,
                                    size = if (size > Int.MAX_VALUE) UNREADABLE_SIZE else size.toInt(),
                                    ptsUs = ticksToUs(dtsTicks + ctts - mediaTime) + emptyUs,
                                    dtsUs = ticksToUs(dtsTicks - mediaTime) + emptyUs,
                                    key = sampleFlags and 0x10000L == 0L, // !non_sync
                                    durationUs = ticksToUs(duration),
                                )
                            }
                            offset += size
                            dtsTicks += duration
                        }
                        nextSampleOffset = offset
                        ticks[trackId] = dtsTicks
                    }
                }
            }
        }
        return if (nextSampleOffset >= 0) nextSampleOffset else chainedOffset
    }

    private class SampleDescription(
        val videoCodec: VideoCodec? = null,
        val audioCodec: AudioCodec? = null,
        val codecConfig: ByteArray? = null,
        val width: Int = 0,
        val height: Int = 0,
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val codecDelayUs: Long = 0,
        val color: ColorInfo? = null,
    )

    /** Se llama con la entrada posicionada justo tras el contador de entradas del stsd. */
    private fun parseSampleDescription(end: Long): SampleDescription? {
        val start = input.position
        val entry = readBoxHeader(end) ?: return null
        if (entry.dataEnd <= start || entry.dataEnd > end) return null
        return when (entry.type) {
            "avc1", "avc3" -> parseVisual(entry, VideoCodec.H264, "avcC")
            "hvc1", "hev1" -> parseVisual(entry, VideoCodec.H265, "hvcC")
            "mp4a" -> parseMp4a(entry)
            "Opus" -> parseOpus(entry)
            else -> null
        }
    }

    private fun parseVisual(entry: Box, codec: VideoCodec, configBoxType: String): SampleDescription {
        input.position = entry.dataStart
        input.skip(6 + 2 + 2 + 2 + 12)
        val w = input.readBits(2).toInt()
        val h = input.readBits(2).toInt()
        input.skip(4 + 4 + 4 + 2 + 32 + 2 + 2)
        var config: ByteArray? = null
        var primaries = ColorInfo.UNSPECIFIED
        var transfer = ColorInfo.UNSPECIFIED
        var matrix = ColorInfo.UNSPECIFIED
        var fullRange = false
        var hasColr = false
        var mastering: DoubleArray? = null
        var maxCll = 0
        var maxFall = 0
        scanChildren(input.position, entry.dataEnd) { box ->
            input.position = box.dataStart
            when (box.type) {
                configBoxType -> config = input.readBytes((box.dataEnd - box.dataStart).toInt())
                "colr" -> {
                    val kind = String(input.readBytes(4), Charsets.US_ASCII)
                    if (kind == "nclx" || kind == "nclc") {
                        hasColr = true
                        primaries = input.readBits(2).toInt()
                        transfer = input.readBits(2).toInt()
                        matrix = input.readBits(2).toInt()
                        if (kind == "nclx") fullRange = input.readByte() and 0x80 != 0
                    }
                }
                "mdcv" -> {
                    val v = DoubleArray(8) { (input.readBits(2)) * 0.00002 }
                    val maxLum = (input.readInt32().toLong() and 0xFFFFFFFFL) * 0.0001
                    val minLum = (input.readInt32().toLong() and 0xFFFFFFFFL) * 0.0001
                    mastering = doubleArrayOf(v[4], v[5], v[0], v[1], v[2], v[3], v[6], v[7], maxLum, minLum)
                }
                "clli" -> {
                    maxCll = input.readBits(2).toInt()
                    maxFall = input.readBits(2).toInt()
                }
            }
        }
        val hdr = mastering?.let {
            HdrStaticInfo(
                it[0],
                it[1],
                it[2],
                it[3],
                it[4],
                it[5],
                it[6],
                it[7],
                it[8],
                it[9],
                maxCll,
                maxFall
            )
        } ?: if (maxCll > 0 || maxFall > 0) {
            HdrStaticInfo(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, maxCll, maxFall)
        } else null
        val color = if (hasColr || hdr != null) ColorInfo(
            primaries,
            transfer,
            matrix,
            fullRange,
            hdr
        ) else null
        return SampleDescription(videoCodec = codec, codecConfig = config, width = w, height = h, color = color)
    }

    private fun parseMp4a(entry: Box): SampleDescription {
        input.position = entry.dataStart
        input.skip(6 + 2 + 8)
        var channels = input.readBits(2).toInt()
        input.skip(2 + 2 + 2)
        var sampleRate = (input.readInt32() ushr 16)
        var asc: ByteArray? = null
        scanChildren(input.position, entry.dataEnd) { box ->
            if (box.type == "esds") {
                input.position = box.dataStart
                input.skip(4)
                asc = extractAscFromEsds(box.dataEnd)
            }
        }
        asc?.let {
            runCatching { AacConfig.parse(it) }.getOrNull()?.let { parsed ->
                sampleRate = parsed.sampleRate
                channels = parsed.channelCount
            }
        }
        return SampleDescription(
            audioCodec = AudioCodec.AAC, codecConfig = asc,
            sampleRate = sampleRate, channels = channels,
        )
    }

    private fun parseOpus(entry: Box): SampleDescription {
        input.position = entry.dataStart
        input.skip(6 + 2 + 8)
        val channels = input.readBits(2).toInt()
        input.skip(2 + 2 + 2)
        val sampleRate = (input.readInt32() ushr 16)
        var head: ByteArray? = null
        var delayUs = 0L
        scanChildren(input.position, entry.dataEnd) { box ->
            if (box.type == "dOps") {
                input.position = box.dataStart
                val dops = input.readBytes((box.dataEnd - box.dataStart).toInt())
                head = OpusConfig.dopsToOpusHead(dops)
                delayUs = OpusConfig.parseOpusHead(head!!).codecDelayUs
            }
        }
        return SampleDescription(
            audioCodec = AudioCodec.OPUS, codecConfig = head,
            sampleRate = if (sampleRate > 0) sampleRate else 48000, channels = channels,
            codecDelayUs = delayUs,
        )
    }

    /** Recorre el árbol de descriptores MPEG-4 del esds hasta el DecoderSpecificInfo. */
    private fun extractAscFromEsds(end: Long): ByteArray? {
        fun readDescriptorLength(): Int {
            var length = 0
            var count = 0
            while (count < 4) {
                val b = input.readByte()
                length = (length shl 7) or (b and 0x7F)
                count++
                if (b and 0x80 == 0) break
            }
            return length
        }
        while (input.position < end) {
            val tag = input.readByte()
            val len = readDescriptorLength()
            val payloadEnd = input.position + len
            when (tag) {
                0x03 -> {
                    input.skip(2)
                    val flags = input.readByte()
                    if (flags and 0x80 != 0) input.skip(2)             // streamDependenceFlag
                    if (flags and 0x40 != 0) input.skip(input.readByte().toLong()) // URL_Flag
                    if (flags and 0x20 != 0) input.skip(2)             // OCRstreamFlag
                }
                0x04 -> input.skip(13)                                  // luego hijos en línea
                0x05 -> return if (payloadEnd <= end) input.readBytes(len) else null
                else -> input.position = payloadEnd
            }
        }
        return null
    }

    override fun readPacket(): MediaPacket? {
        while (true) {
            var best: ParsedTrack? = null
            var bestDts = Long.MAX_VALUE
            for (t in parsedTracks) {
                if (t.cursor < t.samples.size) {
                    val dts = t.reader.dtsUs
                    if (dts < bestDts) {
                        bestDts = dts
                        best = t
                    }
                }
            }
            val track = best ?: return null
            val sample = track.reader
            val offset = sample.offset
            val size = sample.size
            val ptsUs = sample.ptsUs
            val dtsUs = sample.dtsUs
            val key = sample.key
            val durationUs = sample.durationUs
            track.cursor++
            if (track.cursor < track.samples.size) sample.next()

            if (offset < 0 || size < 0 || offset > input.length || size > input.length - offset) {
                if (unreadableWarned.add(track.info.id)) {
                    runCatching {
                        onWarning(
                            "pista ${track.info.id}: se saltan muestras cuyos bytes no están " +
                                "en el archivo (¿grabación cortada o archivo dañado?)",
                        )
                    }
                }
                continue
            }
            input.position = offset
            val data = input.readBytes(size)
            return MediaPacket(
                trackId = track.info.id,
                data = data,
                ptsUs = ptsUs,
                dtsUs = dtsUs,
                isKeyFrame = key,
                durationUs = durationUs,
            )
        }
    }

    /**
     * Mismo resultado que el recorrido lineal de siempre —último keyframe con `pts <= destino`
     * entre las muestras que no empiezan a decodificarse después del destino—, pero localizando
     * ese límite por bisección entre los puntos de control de [TrackSamples]. En un archivo de dos
     * horas el recorrido tocaba cientos de miles de muestras por cada búsqueda; aquí son una
     * veintena de pasos de bisección y, como mucho, unos cientos de muestras por pista.
     *
     * Los cursores de lectura sirven también para buscar, porque al terminar se recolocan todos.
     */
    override fun seekTo(timestampUs: Long): Long {
        val videoTrack = parsedTracks.firstOrNull { it.info is TrackInfo.Video }
        val anchor = videoTrack ?: parsedTracks.firstOrNull() ?: return 0
        val samples = anchor.samples
        val scan = anchor.reader
        val limit = if (samples.dtsMonotonic) {
            samples.firstIndexWithDtsAbove(timestampUs, scan).coerceAtMost(samples.size - 1)
        } else {
            samples.lastIndexWithDtsAtMostLinear(timestampUs, scan)
        }
        val target = samples.lastKeyAtOrBefore(limit, timestampUs, scan)
        scan.moveTo(target)
        val targetDts = scan.dtsUs
        val targetPts = scan.ptsUs
        for (t in parsedTracks) {
            t.cursor = when {
                t === anchor -> target
                t.samples.dtsMonotonic -> t.samples.firstIndexWithDtsAtLeast(targetDts, t.reader)
                else -> t.samples.firstIndexWithDtsAtLeastLinear(targetDts, t.reader)
            }
            if (t.cursor < t.samples.size) t.reader.moveTo(t.cursor)
        }
        return targetPts
    }

    override fun close(): Unit = input.close()

    private companion object {
        /** Destino de las muestras de las pistas que un cursor de fMP4 no está leyendo. */
        private val IGNORED_SAMPLES = SampleSink { _, _, _, _, _, _ -> }

        /** Bit `track_enabled` de los flags del `tkhd`. */
        private const val TKHD_TRACK_ENABLED = 0x1L

        /** Un nombre de pista no necesita más; algo mayor es un archivo dañado o manipulado. */
        private const val MAX_TRACK_NAME_BYTES = 4096L

        /**
         * Tamaño que marca una muestra como ilegible: una muestra de más de 2 GiB no cabe en un
         * array, y `readPacket` la salta con aviso en vez de intentar reservarla.
         */
        private const val UNREADABLE_SIZE = -1
    }
}
