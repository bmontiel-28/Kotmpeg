package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.Demuxer
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.Timestamps
import com.braymon.kotmpeg.model.TrackInfo
import java.io.File

/**
 * Demuxer MP4 (ISO BMFF) para archivos planos y fragmentados (fMP4).
 *
 * Archivos planos: recorre las muestras desde las tablas `stbl`
 * (stts/ctts/stss/stsc/stsz/stco/co64), respetando las edit lists para los offsets de
 * presentación. Archivos fragmentados (`moov` vacío + pares `moof`/`mdat`): recorre cada
 * fragmento leyendo tfhd/tfdt/trun —incluidos los defaults de trex, el direccionamiento
 * default-base-is-moof y los offsets de composición firmados— y aplicando la misma edit list que a
 * una pista plana (ver [FragmentWalker]). Los paquetes se emiten intercalados en orden de
 * decodificación entre pistas; las no soportadas se saltan, con aviso.
 *
 * Un MP4 **híbrido** —muestras en el `moov` y además fragmentos, lo que escribe FFmpeg con
 * `-movflags frag_keyframe` sin `empty_moov`— no se lee entero: de las pistas que tienen muestras
 * en el `moov` solo se entregan esas, y se avisa. Se abre y se lee sin errores; lo que falta es el
 * resto de sus fragmentos.
 *
 * Las muestras **no se cargan en memoria**: al abrir se recorren una vez para validarlas y dejar
 * un punto de control cada pocos cientos, y después se leen del archivo a medida que hacen falta
 * (ver [TrackSamples]). Un archivo de dos horas a 60 fps con audio se abre con menos de 0,5 MB,
 * donde la 3.0.0 necesitaba unos 28, y la diferencia crece con la duración. Lo que un archivo
 * manipulado puede declarar está acotado por su tamaño (ver [SampleBudget]) y por [MAX_TRACKS].
 */
public class Mp4Demuxer(
    private val input: SeekableInput,
    /**
     * Avisos no fatales: pistas que no se pueden parsear o que no se soportan, fragmentos
     * ilegibles y metadatos que se ignoran. Lo que se descarta se descarta para que el resto del
     * archivo siga siendo usable, y siempre con un aviso por aquí.
     *
     * Es la **única** forma de enterarse de que algo se perdió: sin él, una pista ilegible
     * desaparece sin rastro y el síntoma que llega —"mi vídeo se abre pero sin audio"— se queda
     * sin ningún diagnóstico posible.
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

    private val boxes = Mp4BoxReader(input)
    private val sampleEntries = Mp4SampleEntryReader(boxes, ::warn)

    /**
     * Pista ya leída. [nextIndex] es la siguiente muestra que entregará [readPacket], y [reader]
     * está siempre colocado en ella mientras quede alguna. `samples.dtsMonotonic` decide si
     * [seekTo] puede buscar por bisección: es lo normal, pero un fMP4 manipulado puede traer `tfdt`
     * que retroceden, y ahí se recorre la pista entera.
     */
    private class ParsedTrack(val info: TrackInfo, val samples: TrackSamples) {
        val reader: SampleCursor = samples.cursor()
        var nextIndex: Int = 0

        /**
         * Cuánto va la presentación de la pista por delante de su decodificación, medido en su
         * primera muestra. En audio es cero, salvo en los fMP4 escritos por esta librería hasta la
         * 4.0.0 con una pista que arranca después que las demás, que llevan ese retraso como
         * desfase de composición. Se calcula la primera vez que se busca, con un cursor aparte.
         */
        val presentationLeadUs: Long by lazy { samples.cursor().let { it.ptsUs - it.dtsUs } }
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
     * para toda la línea de tiempo de la pista, fragmentos incluidos. Hasta la 3.0.0 se perdía. En
     * audio, la edición inicial es el cebado y llega ya convertida en `codecDelayUs` (ver
     * [audioPriming]), así que en [elstMediaTime] solo queda lo que afecta a los tiempos.
     *
     * [primingTicks] es ese cebado en tics del medio, o cero. No mueve ningún paquete, pero la
     * duración no lo cuenta (ver [parseFragments]).
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
        val primingTicks: Long,
    )

    /**
     * La parte de una lista de edición que se aplica: el tiempo del medio en que empieza la primera
     * edición con medios, los huecos (ediciones vacías) que la preceden, en tics de la película, y
     * cuántas ediciones más trae, que no se aplican.
     */
    private class EditList(val mediaTime: Long, val emptyTicks: Long, val ignored: Int)

    private val parsedTracks = ArrayList<ParsedTrack>()
    private var trackList: List<TrackInfo> = emptyList()
    private val fragmentShells = ArrayList<FragmentShell>()
    private val trexDefaults = HashMap<Int, TrexDefaults>()

    /** `track_ID` de las pistas ya aceptadas, planas o de fragmentos, para descartar los repetidos. */
    private val acceptedTrackIds = HashSet<Int>()

    /** Pistas usables que no se leen por pasar de [MAX_TRACKS]. */
    private var tracksOverLimit = 0

    /** Pistas ya anunciadas como ilegibles, para no repetir el aviso por cada muestra. */
    private val unreadableWarned = HashSet<Int>()

    /** Si ya se llamó a [close]: desde entonces [readPacket] y [seekTo] lanzan. */
    private var closed = false

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

    /** Pasa un aviso a [onWarning], sin dejar que una excepción suya interrumpa la lectura. */
    private fun warn(message: String) {
        runCatching { onWarning(message) }
    }

    /**
     * Lee el `moov` y, si tiene pistas sin muestras propias, los fragmentos.
     *
     * Una duración del `mvhd` a cero o «todo unos» (ISO/IEC 14496-12 §8.2.2: desconocida) no es
     * una duración: el valor de 32 bits daba 49 días y el de 64, -1000 µs. Entonces manda el final
     * de las pistas, que en fMP4 ya se tomaba siempre que fuera mayor.
     */
    private fun parse() {
        var moov: Mp4Box? = null
        var sawMoof = false
        runCatching {
            boxes.scanChildren(0, input.length) { box ->
                if (box.type == "moov") moov = box
                if (box.type == "moof") sawMoof = true
            }
        }
        val moovBox = moov ?: throw IllegalStateException("no se encontró la caja moov (¿archivo truncado?)")

        var movieTimescale = 1000L
        var movieDuration = 0L
        val trakBoxes = ArrayList<Mp4Box>()
        runCatching {
            boxes.scanChildren(moovBox.dataStart, moovBox.dataEnd) { box ->
                when (box.type) {
                    "mvex" -> boxes.scanChildren(box.dataStart, box.dataEnd) { child ->
                        if (child.type == "trex") {
                            input.position = child.dataStart
                            input.skip(4)
                            val trackId = input.readInt32()
                            input.skip(4) // default_sample_description_index
                            val duration = input.readUInt(4) // default_sample_duration
                            val size = input.readUInt(4) // default_sample_size
                            val flags = input.readUInt(4) // default_sample_flags
                            trexDefaults[trackId] = TrexDefaults(duration, size, flags)
                        }
                    }
                    "mvhd" -> {
                        input.position = box.dataStart
                        val version = input.readByte()
                        input.skip(3)
                        if (version == 1) {
                            input.skip(16)
                            movieTimescale = input.readUInt(4)
                            movieDuration = input.readInt64()
                        } else {
                            input.skip(8)
                            movieTimescale = input.readUInt(4)
                            movieDuration = input.readUInt(4).let { if (it == 0xFFFFFFFFL) 0L else it }
                        }
                    }
                    "trak" -> trakBoxes.add(box)
                }
            }
        }
        val movieDurationKnown = movieTimescale > 0 && movieDuration > 0
        if (movieDurationKnown) durationUs = Timestamps.rescaleFloor(movieDuration, 1_000_000, movieTimescale)

        val budget = SampleBudget(input.length)
        var plainEndUs = 0L
        for (trak in trakBoxes) {
            try {
                parseTrak(trak, movieTimescale, budget)?.let { endUs -> plainEndUs = maxOf(plainEndUs, endUs) }
            } catch (e: Exception) {
                warn("pista descartada: no se pudo parsear su trak (${e.message})")
            }
        }
        if (!movieDurationKnown) durationUs = plainEndUs
        if (tracksOverLimit > 0) {
            warn("el archivo tiene más de $MAX_TRACKS pistas de audio y vídeo: se ignoran las $tracksOverLimit últimas")
        }
        if (sawMoof && parsedTracks.isNotEmpty()) {
            warn(
                "MP4 híbrido: las pistas ${parsedTracks.joinToString { it.info.id.toString() }} tienen " +
                    "muestras en el moov y solo se leen esas; sus fragmentos se ignoran",
            )
        }

        if (fragmentShells.isNotEmpty()) parseFragments(budget)
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
     * Cada pista que no se lee se avisa, también las que antes desaparecían en silencio: sin
     * `stbl`, con escala de tiempo cero, de un tipo que no es audio ni vídeo, o con tablas que no
     * ubican ninguna muestra (un `stco` vacío). Una que repite el `track_ID` de otra ya leída se
     * descarta: con dos pistas con el mismo id, en MP4 plano salían dos `TrackInfo` indistinguibles
     * y en fMP4 la segunda pisaba a la primera.
     *
     * Devuelve dónde acaba la pista en µs si es plana y se lee, para la duración de respaldo. Sin el
     * cebado del audio, como la del `mvhd`, que desde la 4.1.0 es la suma de las ediciones: con él,
     * un archivo con la duración del `mvhd` perdida declaraba 21 ms más que con ella.
     */
    private fun parseTrak(trak: Mp4Box, movieTimescale: Long, budget: SampleBudget): Long? {
        var trackId = 0
        var mediaTimescale = 0L
        var handlerType = ""
        var width = 0
        var height = 0
        var rotationDegrees = 0
        var stblBox: Mp4Box? = null
        var edits = EditList(0L, 0L, 0)
        var sampleDescription: SampleDescription? = null
        var language = "und"
        var name: String? = null
        var enabled = true

        boxes.scanChildren(trak.dataStart, trak.dataEnd) { box ->
            when (box.type) {
                "tkhd" -> {
                    input.position = box.dataStart
                    val version = input.readByte()
                    enabled = (input.readUInt(3) and TKHD_TRACK_ENABLED) != 0L
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
                "edts" -> boxes.scanChildren(box.dataStart, box.dataEnd) { elst ->
                    if (elst.type == "elst") edits = readEditList(elst)
                }
                "mdia" -> boxes.scanChildren(box.dataStart, box.dataEnd) { child ->
                    when (child.type) {
                        "mdhd" -> {
                            input.position = child.dataStart
                            val version = input.readByte()
                            input.skip(3)
                            input.skip(if (version == 1) 16L else 8L)
                            mediaTimescale = input.readUInt(4)
                            input.skip(if (version == 1) 8L else 4L)
                            language = SampleEntries.unpackLanguage(input.readUInt(2).toInt()) // idioma ISO 639-2/T
                        }
                        "hdlr" -> {
                            input.position = child.dataStart
                            input.skip(8)
                            handlerType = String(input.readBytes(4), Charsets.US_ASCII)
                        }
                        "minf" -> boxes.scanChildren(child.dataStart, child.dataEnd) { minfChild ->
                            if (minfChild.type == "stbl") stblBox = minfChild
                        }
                    }
                }
                "udta" -> boxes.scanChildren(box.dataStart, box.dataEnd) { child ->
                    if (child.type == "name") name = readTrackName(child)
                }
            }
        }
        val header = TrackHeader(language, name, enabled)
        val elstEmptyDurationUs = if (movieTimescale > 0) {
            Timestamps.rescaleFloor(edits.emptyTicks, 1_000_000, movieTimescale)
        } else {
            0L
        }

        if (handlerType != "vide" && handlerType != "soun") {
            warn("pista $trackId ('$handlerType') descartada: solo se leen pistas de audio y vídeo")
            return null
        }
        val stbl = stblBox ?: run {
            warn("pista $trackId ($handlerType) descartada: no tiene tabla de muestras (stbl)")
            return null
        }
        if (mediaTimescale <= 0) {
            warn("pista $trackId ($handlerType) descartada: su escala de tiempo (mdhd) es cero")
            return null
        }
        if (edits.ignored > 0) {
            warn(
                "pista $trackId: su lista de edición trae ${edits.ignored} ediciones más tras la primera " +
                    "con medios, que no se aplican",
            )
        }

        var stts = FileTable(input, 0, 0, 8)
        var ctts = FileTable(input, 0, 0, 8)
        var stss: FileTable? = null
        var stsc = FileTable(input, 0, 0, 12)
        var sampleCount = 0
        var constantSize = 0L
        var sampleSizes: FileTable? = null
        var chunkOffsets = FileTable(input, 0, 0, 4)
        var chunkOffsets64 = false

        boxes.scanChildren(stbl.dataStart, stbl.dataEnd) { box ->
            input.position = box.dataStart
            when (box.type) {
                "stsd" -> {
                    val stsdVersion = input.readByte()
                    input.skip(3)
                    val count = input.readInt32()
                    if (count >= 1) sampleDescription = sampleEntries.read(box.dataEnd, stsdVersion, trackId)
                }
                "stts" -> {
                    input.skip(4)
                    val n = boxes.boundedCount(input.readInt32(), 8, box.dataEnd)
                    stts = FileTable(input, input.position, n, 8) // sample_count, sample_delta
                }
                "ctts" -> {
                    input.skip(4)
                    val n = boxes.boundedCount(input.readInt32(), 8, box.dataEnd)
                    ctts = FileTable(input, input.position, n, 8) // sample_count, sample_offset (may be signed, v1)
                }
                "stss" -> {
                    input.skip(4)
                    val n = boxes.boundedCount(input.readInt32(), 4, box.dataEnd)
                    stss = FileTable(input, input.position, n, 4) // sample_number, desde 1
                }
                "stsc" -> {
                    input.skip(4)
                    val n = boxes.boundedCount(input.readInt32(), 12, box.dataEnd)
                    stsc = FileTable(input, input.position, n, 12) // first_chunk, samples_per_chunk, sample_description_index
                }
                "stsz" -> {
                    input.skip(4)
                    constantSize = input.readUInt(4) // sample_size
                    val declared = input.readInt32() // sample_count
                    if (constantSize != 0L) {
                        sampleCount = boxes.physicalCount(declared, constantSize)
                        sampleSizes = null
                    } else {
                        sampleCount = boxes.boundedCount(declared, 4, box.dataEnd)
                        sampleSizes = FileTable(input, input.position, sampleCount, 4) // entry_size
                    }
                }
                "stco" -> {
                    input.skip(4)
                    val n = boxes.boundedCount(input.readInt32(), 4, box.dataEnd)
                    chunkOffsets = FileTable(input, input.position, n, 4) // chunk_offset
                    chunkOffsets64 = false
                }
                "co64" -> {
                    input.skip(4)
                    val n = boxes.boundedCount(input.readInt32(), 8, box.dataEnd)
                    chunkOffsets = FileTable(input, input.position, n, 8) // chunk_offset de 64 bits
                    chunkOffsets64 = true
                }
            }
        }

        val desc = sampleDescription ?: run {
            warn(
                "pista $trackId ($handlerType) descartada: su formato no se pudo " +
                    "interpretar (códec no soportado o stsd ilegible)",
            )
            return null
        }
        if (trackId in acceptedTrackIds) {
            warn("pista $trackId ($handlerType) descartada: otra pista anterior ya usa ese track_ID")
            return null
        }
        if (acceptedTrackIds.size >= MAX_TRACKS) {
            tracksOverLimit++
            return null
        }
        val priming = audioPriming(handlerType, desc, edits.mediaTime, mediaTimescale)
        val trackDesc = priming?.let { desc.withCodecDelay(it) } ?: desc
        val primingTicks = if (priming != null) edits.mediaTime else 0L
        val mediaTime = edits.mediaTime - primingTicks

        if (sampleCount == 0) {
            acceptedTrackIds.add(trackId)
            fragmentShells.add(
                FragmentShell(
                    trackId, handlerType, trackDesc, mediaTimescale, width, height, rotationDegrees, header,
                    mediaTime, elstEmptyDurationUs, primingTicks,
                ),
            )
            return null
        }

        val samples = StblSamples(
            sampleCount, constantSize, sampleSizes, chunkOffsets, chunkOffsets64, stsc, stts, ctts, stss,
            mediaTimescale, mediaTime, elstEmptyDurationUs, budget,
        )
        val truncation = samples.truncation
        if (samples.size == 0) {
            warn(
                "pista $trackId ($handlerType) descartada: " +
                    (truncation ?: "sus tablas no ubican ninguna muestra en un chunk (stsc o stco vacíos)"),
            )
            return null
        }
        if (truncation != null) {
            warn("pista $trackId: se ignoran sus muestras a partir de la ${samples.size}: $truncation")
        }

        val frameRateHint = samples.firstPositiveDelta()?.let { mediaTimescale.toDouble() / it } ?: 0.0
        val info = buildTrackInfo(handlerType, trackDesc, trackId, width, height, frameRateHint, rotationDegrees, header)
            ?: return null
        acceptedTrackIds.add(trackId)
        parsedTracks.add(ParsedTrack(info, samples))
        return (samples.endUs - (priming ?: 0L)).coerceAtLeast(0L)
    }

    /**
     * Lee un `elst`: suma los huecos —las ediciones vacías, `media_time` = -1— que hay **antes** de
     * la primera edición con medios, toma el `media_time` de esa, y cuenta las que vienen detrás,
     * que no se aplican. Hasta ahora `[{0}, {M}]` adelantaba la pista entera M tics, de dos huecos
     * solo contaba el último, y uno al final se tomaba por un hueco inicial.
     */
    private fun readEditList(elst: Mp4Box): EditList {
        input.position = elst.dataStart
        val version = input.readByte()
        input.skip(3)
        val count = boxes.boundedCount(input.readInt32(), if (version == 1) 20 else 12, elst.dataEnd)
        var emptyTicks = 0L
        var mediaTime = 0L
        var foundMedia = false
        var ignored = 0
        repeat(count) {
            val segmentDuration: Long
            val entryMediaTime: Long
            if (version == 1) {
                segmentDuration = input.readInt64(); entryMediaTime = input.readInt64()
            } else {
                segmentDuration = input.readUInt(4) // segment_duration
                entryMediaTime = input.readInt32().toLong() // media_time
            }
            input.skip(4) // media_rate
            when {
                foundMedia -> ignored++
                entryMediaTime == -1L -> {
                    val gap = segmentDuration.coerceAtLeast(0)
                    emptyTicks = if (emptyTicks > Long.MAX_VALUE - gap) Long.MAX_VALUE else emptyTicks + gap
                }
                else -> {
                    mediaTime = entryMediaTime
                    foundMedia = true
                }
            }
        }
        return EditList(mediaTime, emptyTicks, ignored)
    }

    /**
     * El cebado de una pista de audio, en µs, si su lista de edición lo declara; `null` si no.
     *
     * En audio, la edición inicial (`media_time` > 0) es el cebado del codificador: las muestras que
     * hay que descartar al decodificar. Se entrega en `codecDelayUs` y **no se resta** de los
     * tiempos de los paquetes, igual que el `CodecDelay` de Matroska en `MkvDemuxer` y que lo que
     * esperan los muxers, que vuelven a escribirlo como edición o como `CodecDelay`. Hasta la 3.x
     * este demuxer lo restaba y declaraba un retardo cero, así que el mismo audio llegaba con 21 ms
     * de diferencia según viniera de un MP4 o de un MKV.
     *
     * En vídeo la edición inicial es el desfase de composición de los B-frames y sí se resta, como
     * siempre: ahí no hay nada que descartar.
     */
    private fun audioPriming(handlerType: String, desc: SampleDescription, mediaTime: Long, timescale: Long): Long? =
        if (handlerType == "soun" && desc.audioCodec != null && mediaTime > 0 && timescale > 0) {
            Timestamps.rescaleFloor(mediaTime, 1_000_000, timescale)
        } else {
            null
        }

    /**
     * Texto de `udta`/`name`. Puede venir con un terminador nulo (QuickTime) o sin él (lo que
     * escriben los muxers de aquí); se corta en el primero y se ignora si queda vacío.
     */
    private fun readTrackName(box: Mp4Box): String? {
        val length = box.dataEnd - box.dataStart
        if (length <= 0 || length > MAX_TRACK_NAME_BYTES) return null
        input.position = box.dataStart
        val raw = input.readBytes(length.toInt())
        val end = raw.indexOf(0.toByte()).let { if (it < 0) raw.size else it }
        return String(raw, 0, end, Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    /**
     * Convierte una pista ya parseada en [TrackInfo], o null —con aviso— si su formato no
     * corresponde a su tipo: una pista de vídeo con una entrada de audio, o al revés.
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
     *
     * El `pasp` es el **respaldo** del `tkhd`, no al revés: solo cuenta cuando el `tkhd` no da un
     * tamaño de presentación distinto del codificado, o cuando no vale y se ignora. Un archivo con
     * los dos coherentes, como los de `ffmpeg -aspect`, se lee igual que antes, y uno que solo
     * declara la proporción en el `pasp` ya no la pierde.
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
            val fromTkhd = usable && (tkhdWidth != width || tkhdHeight != height)
            val fromPasp = if (fromTkhd) null else desc.pixelAspectWidth(width)

            TrackInfo.Video(desc.videoCodec, width, height) {
                displayWidth = if (fromTkhd) tkhdWidth else fromPasp ?: width
                displayHeight = if (fromTkhd) tkhdHeight else height
                frameRate = frameRateHint
                this.rotationDegrees = rotationDegrees
                color = desc.color
                codecPrivate = desc.codecConfig
                language = header.language
                name = header.name
                default = header.default
            }.withId(trackId)
        }
        handlerType == "soun" && desc.audioCodec != null -> TrackInfo.Audio(desc.audioCodec, desc.sampleRate, desc.channels) {
            codecDelayUs = desc.codecDelayUs
            codecPrivate = desc.codecConfig
            language = header.language
            name = header.name
            default = header.default
        }.withId(trackId)
        else -> {
            warn("pista $trackId ($handlerType) descartada: su formato no corresponde a su tipo")
            null
        }
    }

    /**
     * Recorre los fragmentos (ver [FragmentWalker.scan]) y convierte en pistas las candidatas que
     * tienen muestras. La duración es el mayor final de pista, calculado en tics de cada una y
     * convertido una sola vez: sumar el DTS y la duración ya redondeados a µs daba 1 µs de
     * diferencia entre dos archivos que acaban en el mismo tic.
     *
     * El final de una pista de audio con cebado no lo cuenta ([FragmentShell.primingTicks]), igual
     * que el `mvhd`, el `mehd` y el `tkhd`, que son la suma de sus ediciones, y que `Mp4Muxer` y
     * `RepairResult`. Hasta ahora sí: con el audio como pista más larga, un fMP4 duraba 21 ms más que
     * el mismo contenido en MP4 plano con AAC, y `Kotmpeg.repair` devolvía esos 21 ms menos de lo
     * que luego leía este demuxer. La resta satura para que un `tfdt` manipulado no dé la vuelta.
     */
    private fun parseFragments(budget: SampleBudget) {
        val fragmentTracks = fragmentShells.map {
            FragmentTrack(it.trackId, it.mediaTimescale, it.elstMediaTime, it.elstEmptyDurationUs)
        }
        val walker = FragmentWalker(boxes, fragmentTracks, trexDefaults)
        val scan = walker.scan(budget, ::warn)

        for ((j, shell) in fragmentShells.withIndex()) {
            val track = scan.stats[j]
            if (track.count == 0) continue
            val frameRateHint = if (track.firstPositiveDurationUs > 0) 1_000_000.0 / track.firstPositiveDurationUs else 0.0
            val info = try {
                buildTrackInfo(
                    shell.handlerType, shell.desc, shell.trackId, shell.width, shell.height,
                    frameRateHint, shell.rotationDegrees, shell.header,
                )
            } catch (e: Exception) {
                warn("pista ${shell.trackId} (${shell.handlerType}) descartada: ${e.message}")
                null
            } ?: continue
            parsedTracks.add(ParsedTrack(info, FragmentSamples(walker, j, track, scan.checkpoints)))
            val priming = shell.primingTicks
            val end = fragmentTracks[j].timeUs(maxOf(scan.endTicks(j), Long.MIN_VALUE + priming) - priming)
            if (end > durationUs) durationUs = end
        }
    }

    override fun readPacket(): MediaPacket? {
        check(!closed) { CLOSED }
        while (true) {
            var best: ParsedTrack? = null
            var bestDts = Long.MAX_VALUE
            for (t in parsedTracks) {
                if (t.nextIndex < t.samples.size) {
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
            track.nextIndex++
            if (track.nextIndex < track.samples.size) sample.next()

            if (offset < 0 || size < 0 || offset > input.length || size > input.length - offset) {
                if (unreadableWarned.add(track.info.id)) {
                    warn(
                        "pista ${track.info.id}: se saltan muestras cuyos bytes no están " +
                            "en el archivo (¿grabación cortada o archivo dañado?)",
                    )
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
     * Busca el último keyframe de la pista ancla —la primera de vídeo, o la primera si no hay
     * vídeo— con `pts <= destino`, y coloca **todas las demás pistas por tiempo de presentación**
     * en ese keyframe, que es donde de verdad empieza la imagen:
     *
     * - otra pista de vídeo, en su propio último keyframe con `pts` no posterior;
     * - una de audio, en el paquete que suena en ese instante, contado en su tiempo de paquete. Con
     *   cebado eso deja delante justo el margen que el decodificador necesita para arrancar (ver
     *   `TrackInfo.Audio.codecDelayUs`);
     * - una que ya ha terminado en ese instante, en ninguna parte: no entrega nada (ver
     *   [startAfterSeek]).
     *
     * Hasta la 3.x las demás pistas se colocaban por tiempo de **decodificación**, como hace el
     * demuxer de FFmpeg. En un MP4 normal da igual, pero las grabaciones de Android con B-frames
     * llevan el DTS hasta varios segundos por detrás del PTS, y tras buscar el audio empezaba esos
     * segundos antes que la imagen: un recorte desde ahí abría con audio sin vídeo.
     *
     * Los límites se localizan por bisección entre los puntos de control de [TrackSamples]: una
     * veintena de pasos y, como mucho, unos cientos de muestras por pista. Los cursores de lectura
     * sirven también para buscar, porque al terminar se recolocan todos.
     */
    override fun seekTo(timestampUs: Long): Long {
        check(!closed) { CLOSED }
        val anchor = parsedTracks.firstOrNull { it.info is TrackInfo.Video } ?: parsedTracks.firstOrNull() ?: return 0
        val target = keyFrameAtOrBefore(anchor, timestampUs)
        anchor.reader.moveTo(target)
        val targetPts = anchor.reader.ptsUs
        for (t in parsedTracks) {
            t.nextIndex = if (t === anchor) target else startAfterSeek(t, targetPts)
            if (t.nextIndex < t.samples.size) t.reader.moveTo(t.nextIndex)
        }
        return targetPts
    }

    /**
     * Dónde sigue [track], que no es el ancla, tras buscar el keyframe de [timestampUs]: si es de
     * vídeo, en su último keyframe no posterior; si es de audio, en el paquete que suena en ese
     * instante. Y en ninguna parte —[TrackSamples.size], la pista agotada— si ya ha terminado:
     * desde ahí hasta su última muestra, nada se presenta en ese instante ni después. Hasta ahora
     * entregaba igualmente su último keyframe o su último paquete, imagen o sonido de segundos
     * antes, y un recorte desde ahí empezaba con ellos.
     *
     * Saber si ha terminado solo cuesta algo cuando la búsqueda ya llegó a su última muestra; si
     * no, alguna se decodifica —y se presenta— después, y la pista sigue.
     */
    private fun startAfterSeek(track: ParsedTrack, timestampUs: Long): Int {
        val samples = track.samples
        if (samples.size == 0) return 0
        val start: Int
        val reached: Int
        if (track.info is TrackInfo.Video) {
            reached = decodeLimit(track, timestampUs)
            start = samples.lastKeyAtOrBefore(reached, timestampUs, track.reader)
        } else {
            start = sampleSoundingAt(track, timestampUs)
            reached = start
        }
        return if (reached == samples.size - 1 && endsBefore(track, start, timestampUs)) samples.size else start
    }

    /**
     * Si ninguna muestra de [track] desde [from] hasta la última se presenta en [timestampUs] o
     * después. Cada una se presenta desde su `pts` durante su duración, y una que empieza justo en
     * ese instante cuenta aunque no declare duración. Se llama con la última muestra ya alcanzada,
     * así que recorre como mucho lo que hay desde un keyframe hasta el final.
     */
    private fun endsBefore(track: ParsedTrack, from: Int, timestampUs: Long): Boolean {
        val scan = track.reader
        scan.moveTo(from)
        var index = from
        while (true) {
            if (scan.ptsUs >= timestampUs || timestampUs - scan.ptsUs < scan.durationUs) return false
            if (++index >= track.samples.size) return true
            scan.next()
        }
    }

    /**
     * Último keyframe con `pts <= `[timestampUs] entre las muestras que no empiezan a decodificarse
     * después de él, o la primera muestra si no hay ninguno. Es el algoritmo de siempre del ancla,
     * con el límite localizado por bisección.
     */
    private fun keyFrameAtOrBefore(track: ParsedTrack, timestampUs: Long): Int =
        track.samples.lastKeyAtOrBefore(decodeLimit(track, timestampUs), timestampUs, track.reader)

    /**
     * Hasta dónde puede estar el keyframe de [timestampUs]: la primera muestra que se decodifica
     * después de ese instante, o la última si ninguna lo hace.
     */
    private fun decodeLimit(track: ParsedTrack, timestampUs: Long): Int {
        val samples = track.samples
        return if (samples.dtsMonotonic) {
            samples.firstIndexWithDtsAbove(timestampUs, track.reader).coerceAtMost(samples.size - 1)
        } else {
            samples.lastIndexWithDtsAtMostLinear(timestampUs, track.reader)
        }
    }

    /**
     * La muestra que suena en [timestampUs]: la última con tiempo no posterior, o la primera si
     * todas empiezan después. En audio el DTS sigue al PTS a una distancia fija —cero, salvo en
     * los fMP4 que describe [ParsedTrack.presentationLeadUs]—, así que se busca por DTS el
     * instante menos esa distancia, por bisección y sin pasarse (ver
     * [TrackSamples.lastIndexWithDtsAtMost]). Sin descontarla, el audio de esos archivos empezaba
     * tarde justo lo que tardó su pista en arrancar.
     */
    private fun sampleSoundingAt(track: ParsedTrack, timestampUs: Long): Int {
        val samples = track.samples
        val scan = track.reader
        val lead = track.presentationLeadUs
        val decodeUs = when {
            lead > 0 && timestampUs < Long.MIN_VALUE + lead -> Long.MIN_VALUE
            lead < 0 && timestampUs > Long.MAX_VALUE + lead -> Long.MAX_VALUE
            else -> timestampUs - lead
        }
        if (samples.dtsMonotonic) return samples.lastIndexWithDtsAtMost(decodeUs, scan)
        val index = samples.lastIndexWithDtsAtMostLinear(decodeUs, scan)
        scan.moveTo(index)
        val firstAfter = if (scan.dtsUs > decodeUs) index else index + 1
        return (firstAfter - 1).coerceAtLeast(0)
    }

    /**
     * Cierra la entrada. Idempotente, y desde aquí [readPacket] y [seekTo] lanzan
     * `IllegalStateException`, como en `MkvDemuxer`. Antes seguían sirviendo paquetes de lo que
     * quedaba en el búfer de la entrada ya cerrada, y solo fallaban, con un error de E/S, cuando
     * tocaba leer del archivo: según dónde cayera la lectura, un uso tras cerrar pasaba sin aviso.
     */
    override fun close() {
        if (closed) return
        closed = true
        input.close()
    }

    private companion object {
        /** Bit `track_enabled` de los flags del `tkhd`. */
        private const val TKHD_TRACK_ENABLED = 0x1L

        /** Un nombre de pista no necesita más; algo mayor es un archivo dañado o manipulado. */
        private const val MAX_TRACK_NAME_BYTES = 4096L

        /**
         * Pistas de audio y vídeo que se leen de un archivo. Un archivo real lleva unas pocas; sin
         * límite, cada pista declarada costaba memoria en cada punto de control y en cada cursor de
         * fMP4, y 3 300 `trak` mínimos con 10 000 `moof` de 48 bytes pedían unos 660 MB con 1 MB de
         * archivo.
         */
        private const val MAX_TRACKS = 64

        private const val CLOSED = "el Mp4Demuxer está cerrado"
    }
}
