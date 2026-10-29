package com.braymon.kotmpeg.mkv

import com.braymon.kotmpeg.Muxer
import com.braymon.kotmpeg.MuxerOptions
import com.braymon.kotmpeg.TimelineGate
import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.AudioPriming
import com.braymon.kotmpeg.codecconfig.DefaultCodecPrivate
import com.braymon.kotmpeg.ebml.EbmlWriter
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.Timestamps
import com.braymon.kotmpeg.model.TrackDefaults
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.TrackRequirements
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

/**
 * Muxer Matroska (.mkv).
 *
 * Produce archivos conformes a la especificación: cabecera EBML, SeekHead, Info, Tracks, un
 * índice Cues para búsqueda fiable en reproductores y Clusters alineados a keyframe con
 * SimpleBlocks.
 *
 * **El archivo es válido en todo momento**, también si el proceso muere antes de [stop]. El
 * `SeekHead` se escribe al empezar, y el índice va en un hueco reservado delante de los clusters
 * (`cuesReserveBytes` de [MuxerOptions]): cada vez que se cierra un cluster —con su tamaño ya
 * escrito y sus datos en el archivo— se actualiza la `Duration` y se amplía el índice con él. Un
 * archivo cortado se abre con la duración y el índice hasta el último cluster cerrado, sin
 * reempaquetarlo. Lo único que deja a medias el corte es el cluster en curso, que se lee hasta
 * donde llegue; `Kotmpeg.repair` lo cierra en el sitio. Cómo se amplía el índice sin que un corte
 * a mitad lo rompa está en `MkvLayout`.
 *
 * Si el índice no cabe en la reserva, deja de crecer ahí —lo publicado sigue valiendo— y [stop]
 * lo escribe entero al final, como cuando la reserva es 0.
 *
 * Las marcas de tiempo usan el TimestampScale estándar de 1 ms. Los bloques llevan tiempos
 * de presentación; los paquetes deben llegar en orden de decodificación por pista (Matroska
 * guarda los fotogramas en orden de decodificación con PTS, así que los B-frames no
 * necesitan señalización extra aquí).
 *
 * La línea de tiempo empieza en el paquete **más temprano de todas las pistas**, igual que en los
 * dos muxers MP4. Es lo que permite pasarle directamente las marcas de un reloj absoluto —las de
 * `MediaCodec` cuentan desde el arranque del dispositivo—: escritas tal cual, un archivo de diez
 * segundos declaraba una duración de días. Como este muxer escribe en vivo, retiene los primeros
 * paquetes hasta que todas las pistas han entregado el suyo (ver `TimelineGate`): el primero que
 * llega no tiene por qué ser el más temprano. Un PTS inicial negativo (el cebado de un AAC) se
 * conserva sin desplazar.
 *
 * **No es seguro entre hilos**: toda la secuencia addTrack/start/writePacket/stop debe
 * ejecutarse desde un solo hilo, o serializarse por fuera. Es lo que hace falta en cuanto hay un
 * hilo por pista, que es el caso normal cuando los paquetes vienen de varios codificadores.
 *
 * De [MuxerOptions] usa `maxClusterDurationMs`, `cuesReserveBytes`, `writingApp` y
 * `creationTimeMillis` (el `DateUTC`); las de MP4 no le afectan.
 */
public class MkvMuxer internal constructor(
    private val out: SeekableOutput,
    options: MuxerOptions,
) : Muxer {

    /** Escribe en [out], que el muxer cierra al terminar. */
    public constructor(out: SeekableOutput, options: MuxerOptions.() -> Unit = {}) :
        this(out, MuxerOptions().apply(options).validatedFor(out))

    /** Escribe en [file], truncándolo. Las opciones se validan antes de abrirlo. */
    public constructor(file: File, options: MuxerOptions.() -> Unit = {}) :
        this(MuxerOptions().apply(options).validated(), file)

    internal constructor(options: MuxerOptions, file: File) : this(SeekableOutput(file), options)

    private val maxClusterDurationMs = options.maxClusterDurationMs
    private val cuesReserveBytes = options.cuesReserveBytes
    private val writingApp = options.writingApp
    private val dateUtcMillis = options.creationTimeMillis

    private val ebml = EbmlWriter(out)
    private val tracks = ArrayList<TrackInfo>()
    private val random = Random()

    private var started = false
    private var stopped = false

    private var segmentSizePos = 0L
    private var segmentDataStart = 0L
    private var seekHeadPos = 0L
    private var infoPos = 0L
    private var tracksPos = 0L

    private var durationValuePos = 0L

    /** Duración ya escrita en `Duration`, en ms, para no reescribirla si no cambia. */
    private var declaredDurationMs = 0L

    /** Inicio de la reserva del índice, o -1 sin reserva (ver `MkvLayout`). */
    private var cuesReservePos = -1L

    /** Cabecera del `Void` libre de la reserva y cuánto mide; -1 cuando se ha llenado. */
    private var cuesFreePos = -1L
    private var cuesFreeHeaderLength = 0

    /** Cuántas entradas de [cues] están ya publicadas en la reserva. */
    private var cuesPublished = 0

    /** Cabecera del `Void` libre dentro del `SeekHead` y cuánto mide (ver `MkvLayout`). */
    private var seekFreePos = -1L
    private var seekFreeHeaderLength = 0

    /** Dónde están los 8 bytes de la posición de la entrada `Cues` del `SeekHead`, o -1 si no la tiene. */
    private var cuesSeekPositionPos = -1L

    private var clusterSizePos = -1L
    private var clusterStartPos = 0L
    private var clusterTimestampMs = 0L

    /**
     * Origen de la línea de tiempo en µs. Se fija cuando [gate] lo permite: con el primer paquete
     * de cada pista entregado, o al agotar la espera.
     */
    private var timelineOriginUs = 0L

    /** Retención de arranque; `null` en cuanto el origen está fijado. */
    private var gate: TimelineGate? = null

    /**
     * El índice que se va deduciendo de los bloques escritos, con el mismo código con el que lo
     * deduce la reparación (ver `MkvTimeline`). Se crea en [start], con las pistas.
     */
    private var timeline = MkvTimeline(0)

    /** Dónde acaba cada pista, por número de pista menos uno, para la `Duration`. */
    private var trackEnds: List<MkvTimeline.Track> = emptyList()

    private companion object {
        /** TimestampScale de Matroska: 1_000_000 ns = ticks de 1 ms. */
        private const val TIMESTAMP_SCALE_NS = 1_000_000L

        /**
         * `DateUTC` cuenta desde 2001-01-01T00:00:00 UTC y no desde la época de Unix, así que
         * cualquier fecha anterior a 2001 es negativa. De ahí que se escriba con signo.
         */
        private const val MATROSKA_EPOCH_MILLIS = 978_307_200_000L

        /**
         * Topes de la retención de arranque. Cinco segundos de diferencia entre pistas es mucho
         * más que la latencia de cualquier codificador; por encima, lo razonable es que una pista
         * no vaya a llegar. Los 16 MB acotan la memoria aunque una sola pista llene ese tiempo.
         */
        private const val START_WAIT_US = 5_000_000L
        private const val START_HELD_BYTES = 16L * 1024 * 1024
    }

    /**
     * Registra una pista con las mismas exigencias de `codecPrivate` que los muxers MP4.
     *
     * Antes el MKV aceptaba cualquier pista sin configuración de códec y la escribía tal cual.
     * FFmpeg lo tolera, pero Media3/ExoPlayer lanza "Missing CodecPrivate" y el extractor nativo de
     * Android descarta la pista AAC: el archivo se grababa sin error y luego no se reproducía en el
     * móvil. Ahora el vídeo sin `avcC`/`hvcC` se rechaza aquí, igual que en MP4, y el audio sin
     * configuración recibe la misma por defecto que escribe el MP4.
     */
    override fun addTrack(track: TrackInfo): Int {
        check(!started) { "no se pueden añadir pistas después de start()" }
        val trackNumber = tracks.size + 1
        require(trackNumber in 1..126) { "Matroska admite como máximo 126 pistas en este muxer" }
        TrackRequirements.requireVideoCodecPrivate(track) {
            "las pistas de vídeo MKV requieren codecPrivate (avcC/hvcC): sin él, " +
                "los reproductores de Android no pueden abrirlas"
        }
        val complete = when (track) {
            is TrackInfo.Video -> track
            is TrackInfo.Audio ->
                if (track.codecPrivate != null) track else track.copy { codecPrivate = DefaultCodecPrivate.forAudio(track) }
        }
        tracks.add(complete.withId(trackNumber))
        return trackNumber
    }

    /**
     * Escribe la cabecera entera: `SeekHead`, `Info`, `Tracks` y la reserva del índice.
     *
     * El `SeekHead` se escribe antes que el cuerpo de la reserva —ya se sabe dónde va a estar— para
     * que llegue al archivo en la misma escritura que el resto de la cabecera. Escrito después, sobre
     * su `Void`, era una escritura propia de 120 bytes tras los 64 KiB de la reserva, y un corte a
     * mitad dejaba un `SeekHead` con ceros que ni el demuxer ni `Kotmpeg.repair` sabían leer.
     */
    override fun start() {
        check(!started) { "ya iniciado" }
        check(tracks.isNotEmpty()) { "sin pistas" }
        started = true

        timeline = MkvTimeline(MkvTimeline.cueTrack(tracks) { it is TrackInfo.Video }.id.toLong())
        trackEnds = tracks.map { MkvTimeline.Track(it.defaultDurationNs) }
        gate = TimelineGate(tracks.size, START_WAIT_US, START_HELD_BYTES)

        writeEbmlHeader()

        ebml.writeId(MatroskaIds.SEGMENT)
        segmentSizePos = out.position
        ebml.writeUnknownSize(8)
        segmentDataStart = out.position

        seekHeadPos = out.position
        ebml.writeVoid(MkvLayout.SEEK_HEAD_RESERVED)

        writeInfo()
        writeTracks()
        if (cuesReserveBytes > 0) cuesReservePos = out.position
        writeSeekHead()
        if (cuesReservePos >= 0) reserveCues()
        out.flush()
    }

    /** Escribe en [cuesReservePos] la reserva del índice: un `Cues` vacío que la ocupa entera (ver `MkvLayout`). */
    private fun reserveCues() {
        val header = MkvLayout.reserveHeader(cuesReserveBytes)
        out.write(header)
        val zeros = ByteArray(minOf(cuesReserveBytes - header.size, 1 shl 16))
        var remaining = cuesReserveBytes - header.size
        while (remaining > 0) {
            val n = minOf(remaining, zeros.size)
            out.write(zeros, 0, n)
            remaining -= n
        }
        cuesFreePos = cuesReservePos + MkvLayout.MASTER_HEADER_SIZE
        cuesFreeHeaderLength = header.size - MkvLayout.MASTER_HEADER_SIZE
    }

    private fun writeEbmlHeader() {
        val sizePos = ebml.beginMaster(MatroskaIds.EBML)
        ebml.writeUInt(MatroskaIds.EBML_VERSION, 1)
        ebml.writeUInt(MatroskaIds.EBML_READ_VERSION, 1)
        ebml.writeUInt(MatroskaIds.EBML_MAX_ID_LENGTH, 4)
        ebml.writeUInt(MatroskaIds.EBML_MAX_SIZE_LENGTH, 8)
        ebml.writeString(MatroskaIds.DOCTYPE, "matroska")
        ebml.writeUInt(MatroskaIds.DOCTYPE_VERSION, 4)
        ebml.writeUInt(MatroskaIds.DOCTYPE_READ_VERSION, 2)
        ebml.endMaster(sizePos)
    }

    private fun writeInfo() {
        infoPos = out.position
        val sizePos = ebml.beginMaster(MatroskaIds.INFO)
        val uid = ByteArray(16).also { random.nextBytes(it) }
        ebml.writeElement(MatroskaIds.SEGMENT_UID, uid)
        ebml.writeUInt(MatroskaIds.TIMESTAMP_SCALE, TIMESTAMP_SCALE_NS)
        ebml.writeString(MatroskaIds.MUXING_APP, "Kotmpeg")
        ebml.writeString(MatroskaIds.WRITING_APP, writingApp)
        dateUtcMillis?.let {
            ebml.writeSInt(MatroskaIds.DATE_UTC, (it - MATROSKA_EPOCH_MILLIS) * 1_000_000L)
        }
        ebml.writeId(MatroskaIds.DURATION)
        ebml.writeVintSize(8)
        durationValuePos = out.position
        out.writeInt64(0.0.toRawBits())
        ebml.endMaster(sizePos)
    }

    private fun writeTracks() {
        tracksPos = out.position
        val sizePos = ebml.beginMaster(MatroskaIds.TRACKS)
        val defaults = TrackDefaults.resolve(tracks)
        for ((i, track) in tracks.withIndex()) writeTrackEntry(track, defaults[i])
        ebml.endMaster(sizePos)
    }

    /**
     * Escribe un `TrackEntry`.
     *
     * Dos elementos de audio que parecen ir juntos y no van: **`CodecDelay` vale para cualquier
     * códec con retardo de arranque** —Matroska lo define así— mientras que el `SeekPreRoll` de
     * 80 ms sí es propio de Opus. Estuvieron los dos dentro del mismo condicional, y la
     * consecuencia era que un `codecDelayUs` puesto en una pista AAC se aceptaba y se descartaba
     * en silencio: el archivo salía sin el elemento y el audio quedaba por detrás del vídeo los
     * ~21 ms que tarda en arrancar un codificador AAC-LC a 48 kHz.
     *
     * Para Opus el `OpusHead` sigue mandando sobre el campo, porque es el dato que de verdad
     * describe el bitstream; los demás códecs no tienen ninguna cabecera de la que deducirlo. Esa
     * preferencia está en `AudioPriming`, que es de donde sacan el cebado también los dos muxers MP4.
     */
    private fun writeTrackEntry(track: TrackInfo, default: Boolean) {
        val entryPos = ebml.beginMaster(MatroskaIds.TRACK_ENTRY)
        ebml.writeUInt(MatroskaIds.TRACK_NUMBER, track.id.toLong())
        ebml.writeUInt(MatroskaIds.TRACK_UID, (random.nextLong().and(Long.MAX_VALUE)).coerceAtLeast(1))
        ebml.writeUInt(MatroskaIds.FLAG_LACING, 0)
        ebml.writeString(MatroskaIds.LANGUAGE, track.language)
        track.name?.let { ebml.writeString(MatroskaIds.NAME, it) }
        if (!default) ebml.writeUInt(MatroskaIds.FLAG_DEFAULT, 0)
        when (track) {
            is TrackInfo.Video -> {
                ebml.writeUInt(MatroskaIds.TRACK_TYPE, MatroskaIds.TRACK_TYPE_VIDEO)
                ebml.writeString(MatroskaIds.CODEC_ID, track.codec.matroskaId)
                track.codecPrivate?.let { ebml.writeElement(MatroskaIds.CODEC_PRIVATE, it) }
                if (track.defaultDurationNs > 0) {
                    ebml.writeUInt(MatroskaIds.DEFAULT_DURATION, track.defaultDurationNs)
                }
                val vPos = ebml.beginMaster(MatroskaIds.VIDEO)
                ebml.writeUInt(MatroskaIds.PIXEL_WIDTH, track.width.toLong())
                ebml.writeUInt(MatroskaIds.PIXEL_HEIGHT, track.height.toLong())
                if (track.displayWidth != track.width || track.displayHeight != track.height) {
                    ebml.writeUInt(MatroskaIds.DISPLAY_WIDTH, track.displayWidth.toLong())
                    ebml.writeUInt(MatroskaIds.DISPLAY_HEIGHT, track.displayHeight.toLong())
                }
                track.color?.let { color ->
                    val cPos = ebml.beginMaster(MatroskaIds.COLOUR)
                    ebml.writeUInt(MatroskaIds.MATRIX_COEFFICIENTS, color.matrix.toLong())
                    ebml.writeUInt(MatroskaIds.COLOUR_RANGE, if (color.fullRange) 2 else 1)
                    ebml.writeUInt(MatroskaIds.TRANSFER_CHARACTERISTICS, color.transfer.toLong())
                    ebml.writeUInt(MatroskaIds.COLOUR_PRIMARIES, color.primaries.toLong())
                    color.hdr?.let { hdr ->
                        if (hdr.maxContentLightLevel > 0) ebml.writeUInt(MatroskaIds.MAX_CLL, hdr.maxContentLightLevel.toLong())
                        if (hdr.maxFrameAverageLightLevel > 0) ebml.writeUInt(MatroskaIds.MAX_FALL, hdr.maxFrameAverageLightLevel.toLong())
                        val mPos = ebml.beginMaster(MatroskaIds.MASTERING_METADATA)
                        ebml.writeFloat(MatroskaIds.PRIMARY_R_X, hdr.redX)
                        ebml.writeFloat(MatroskaIds.PRIMARY_R_Y, hdr.redY)
                        ebml.writeFloat(MatroskaIds.PRIMARY_G_X, hdr.greenX)
                        ebml.writeFloat(MatroskaIds.PRIMARY_G_Y, hdr.greenY)
                        ebml.writeFloat(MatroskaIds.PRIMARY_B_X, hdr.blueX)
                        ebml.writeFloat(MatroskaIds.PRIMARY_B_Y, hdr.blueY)
                        ebml.writeFloat(MatroskaIds.WHITE_POINT_X, hdr.whiteX)
                        ebml.writeFloat(MatroskaIds.WHITE_POINT_Y, hdr.whiteY)
                        ebml.writeFloat(MatroskaIds.LUMINANCE_MAX, hdr.maxMasteringLuminance)
                        ebml.writeFloat(MatroskaIds.LUMINANCE_MIN, hdr.minMasteringLuminance)
                        ebml.endMaster(mPos)
                    }
                    ebml.endMaster(cPos)
                }
                if (track.rotationDegrees != 0) {
                    val pPos = ebml.beginMaster(MatroskaIds.PROJECTION)
                    ebml.writeUInt(MatroskaIds.PROJECTION_TYPE, 0)
                    val roll = when (track.rotationDegrees) {
                        90 -> -90.0
                        180 -> 180.0
                        270 -> 90.0
                        else -> 0.0
                    }
                    ebml.writeFloat(MatroskaIds.PROJECTION_POSE_ROLL, roll)
                    ebml.endMaster(pPos)
                }
                ebml.endMaster(vPos)
            }
            is TrackInfo.Audio -> {
                ebml.writeUInt(MatroskaIds.TRACK_TYPE, MatroskaIds.TRACK_TYPE_AUDIO)
                ebml.writeString(MatroskaIds.CODEC_ID, track.codec.matroskaId)
                track.codecPrivate?.let { ebml.writeElement(MatroskaIds.CODEC_PRIVATE, it) }
                val delayNs = AudioPriming.us(track) * 1000
                if (delayNs > 0) ebml.writeUInt(MatroskaIds.CODEC_DELAY, delayNs)
                if (track.codec == AudioCodec.OPUS) {
                    ebml.writeUInt(MatroskaIds.SEEK_PRE_ROLL, 80_000_000)
                }
                val aPos = ebml.beginMaster(MatroskaIds.AUDIO)
                val coreRate = sbrCoreSampleRate(track)
                if (coreRate != null) {
                    ebml.writeFloat(MatroskaIds.SAMPLING_FREQUENCY, coreRate.toDouble())
                    ebml.writeFloat(MatroskaIds.OUTPUT_SAMPLING_FREQUENCY, track.sampleRate.toDouble())
                } else {
                    ebml.writeFloat(MatroskaIds.SAMPLING_FREQUENCY, track.sampleRate.toDouble())
                }
                ebml.writeUInt(MatroskaIds.CHANNELS, track.channelCount.toLong())
                if (track.bitDepth > 0) ebml.writeUInt(MatroskaIds.BIT_DEPTH, track.bitDepth.toLong())
                ebml.endMaster(aPos)
            }
        }
        ebml.endMaster(entryPos)
    }

    /**
     * Frecuencia del núcleo de una pista HE-AAC, o `null` si no es HE-AAC con SBR explícito.
     *
     * Matroska pide para SBR las dos tasas: `SamplingFrequency` con la del núcleo y
     * `OutputSamplingFrequency` con la de salida, que es lo que escribe FFmpeg. Antes solo se
     * escribía la de salida en el primer campo: no causaba desajustes, pero no era conforme. Solo
     * se separan cuando el ASC lo declara y su tasa de salida coincide con la de la pista; en
     * cualquier otro caso se mantiene una sola tasa, la de la pista.
     */
    private fun sbrCoreSampleRate(track: TrackInfo.Audio): Int? {
        if (track.codec != AudioCodec.AAC) return null
        val parsed = track.codecPrivate?.let { runCatching { AacConfig.parse(it) }.getOrNull() } ?: return null
        val isSbr = parsed.coreSampleRate in 1 until parsed.sampleRate
        return if (isSbr && parsed.sampleRate == track.sampleRate) parsed.coreSampleRate else null
    }

    /**
     * Escribe un paquete como `SimpleBlock` dentro del cluster que le corresponda.
     *
     * Tres detalles que parecen de más y no lo son, los tres por la misma razón: **una marca de
     * tiempo negativa no es un error**, es como se expresa el `priming` de un codificador AAC y
     * así llega desde cualquier MP4 escrito por FFmpeg.
     *
     *  - `Math.floorDiv` y no `/`: la división de `Long` trunca hacia cero, así que con un pts
     *    negativo el redondeo se iba al lado contrario que en el resto de la librería y los dos
     *    muxers redondeaban distinto.
     *  - El tiempo del cue se acota a 0 porque `CueTime` es un uint EBML. Un cue negativo
     *    reventaba dentro de `stop()`, con el archivo ya sin SeekHead, sin duración parcheada y
     *    sin tamaño de segmento: ilegible por una muestra que sí se había escrito bien.
     *  - El desfase respecto al cluster va en 16 bits **con signo**, y ahí está el límite real de
     *    lo que este contenedor puede expresar (unos ±32,7 s). Sin la comprobación se truncaba
     *    en silencio y salía un archivo que se abre pero suena descolocado.
     *
     * El audio se escribe siempre como fotograma clave: `MediaPacket.isKeyFrame` vale `false` por
     * defecto, y un archivo solo de audio salía sin un solo cue y con todos sus bloques marcados
     * como dependientes.
     *
     * Hasta que todas las pistas han entregado su primer paquete, los paquetes se retienen (una
     * copia) en vez de escribirse: el origen de la línea de tiempo tiene que ser el más temprano,
     * y el primero que llega no siempre lo es.
     */
    override fun writePacket(packet: MediaPacket) {
        check(started) { "start() no llamado" }
        check(!stopped) { "muxer ya detenido" }
        val track = tracks.getOrNull(packet.trackId - 1)
            ?: throw IllegalArgumentException("pista desconocida ${packet.trackId}")
        val waiting = gate
        if (waiting != null) {
            if (waiting.hold(packet, packet.trackId - 1)) openTimeline(waiting)
            return
        }
        writeBlock(track, packet)
    }

    /**
     * Fija el origen en el paquete retenido más temprano y escribe lo retenido. Con un PTS inicial
     * negativo el origen se queda en cero, para conservar el cebado sin desplazar el resto.
     */
    private fun openTimeline(waiting: TimelineGate) {
        gate = null
        timelineOriginUs = maxOf(0L, waiting.earliestPtsUs ?: 0L)
        for (held in waiting.release()) writeBlock(tracks[held.trackId - 1], held)
    }

    private fun writeBlock(track: TrackInfo, packet: MediaPacket) {
        val relativePtsUs = try {
            Math.subtractExact(packet.ptsUs, timelineOriginUs)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("pts ${packet.ptsUs} µs fuera de rango para la pista ${packet.trackId}")
        }
        val ptsMs = Timestamps.rescaleRounded(relativePtsUs, 1, 1000)
        val isVideo = track is TrackInfo.Video
        val isKeyFrame = packet.isKeyFrame || track is TrackInfo.Audio

        val needNewCluster = clusterSizePos < 0 ||
            (isVideo && isKeyFrame) ||
            ptsMs - clusterTimestampMs > maxClusterDurationMs ||
            ptsMs - clusterTimestampMs > Short.MAX_VALUE ||
            ptsMs < clusterTimestampMs + Short.MIN_VALUE

        if (needNewCluster) startCluster(ptsMs)
        timeline.block(clusterStartPos - segmentDataStart, track.id.toLong(), isKeyFrame, ptsMs)

        val relative = ptsMs - clusterTimestampMs
        require(relative >= Short.MIN_VALUE && relative <= Short.MAX_VALUE) {
            "el paquete de la pista ${packet.trackId} queda a $relative ms de su cluster, " +
                "fuera del rango [${Short.MIN_VALUE}, ${Short.MAX_VALUE}] de un SimpleBlock " +
                "(pts ${packet.ptsUs} µs); rebasa la línea de tiempo antes de muxear"
        }
        val header = trackNumberVint(track.id)
        ebml.writeId(MatroskaIds.SIMPLE_BLOCK)
        ebml.writeVintSize(header.size.toLong() + 3L + packet.data.size.toLong())
        out.write(header)
        out.writeUInt(relative and 0xFFFF, 2)
        out.writeByte(if (isKeyFrame) 0x80 else 0x00)
        out.write(packet.data)
        trackEnds[track.id - 1].add(ptsMs, packet.durationUs)
    }

    private fun trackNumberVint(number: Int): ByteArray {
        require(number in 1..126) { "número de pista $number fuera de rango para vint de 1 byte" }
        return byteArrayOf((0x80 or number).toByte())
    }

    private fun startCluster(timestampMs: Long) {
        closeCluster()
        clusterStartPos = out.position
        clusterTimestampMs = maxOf(0, timestampMs)
        ebml.writeId(MatroskaIds.CLUSTER)
        clusterSizePos = out.position
        ebml.writeUnknownSize(8)
        ebml.writeUInt(MatroskaIds.CLUSTER_TIMESTAMP, clusterTimestampMs)
    }

    /**
     * Cierra el cluster en curso escribiendo su tamaño y lo publica: actualiza la `Duration` y
     * amplía el índice reservado con su cue. Las dos son parches de la cabecera, que ya está en el
     * archivo, así que cada una lleva antes al archivo todo lo pendiente —el cluster entero, con su
     * tamaño— (ver `SeekableOutput.patch`). En ese orden, un corte en cualquier punto deja un
     * índice que solo apunta a clusters completos y una duración que llega al menos hasta ellos.
     */
    private fun closeCluster() {
        if (clusterSizePos >= 0) {
            ebml.endMaster(clusterSizePos)
            clusterSizePos = -1
            declareDuration()
            publishCues()
        }
    }

    /**
     * Añade a la reserva los cues que aún no están en ella, en las dos escrituras que describe
     * `MkvLayout`. Si no caben, la reserva deja de crecer y [stop] escribirá el índice al final.
     */
    private fun publishCues() {
        val cues = timeline.cues
        if (cuesFreePos < 0 || cuesPublished == cues.size) return
        val points = ByteArrayOutputStream()
        for (i in cuesPublished until cues.size) points.write(cues[i].encoded())
        val append = MkvLayout.append(cuesFreePos, cuesFreeHeaderLength, cuesReservePos + cuesReserveBytes, points.toByteArray())
        if (append == null) {
            cuesFreePos = -1
            return
        }
        for ((at, bytes) in append.writes) out.patch(at, bytes)
        cuesFreePos = append.nextFreePos
        cuesFreeHeaderLength = append.nextFreeHeaderLength
        cuesPublished = cues.size
    }

    /** Escribe en `Duration` el fin de la línea de tiempo (ver `MkvTimeline`), si ha cambiado. */
    private fun declareDuration() {
        val end = MkvTimeline.endMs(trackEnds)
        if (end == declaredDurationMs) return
        out.patch(durationValuePos, EbmlWriter.bigEndian(end.toDouble().toRawBits(), 8))
        declaredDurationMs = end
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        if (!started) {
            out.close()
            return
        }
        try {
            gate?.let { openTimeline(it) }
            closeCluster()
            if (cuesReservePos < 0 || cuesFreePos < 0) moveCuesToEnd()
            declareDuration()
            out.patch(segmentSizePos, EbmlWriter.encodeVintSize(out.position - segmentDataStart, 8))
        } finally {
            out.close()
        }
    }

    /**
     * Sin reserva, o con la reserva llena, escribe el índice entero al final, apunta a él el
     * `SeekHead` y convierte la reserva en relleno: un segmento solo puede tener un `Cues`.
     *
     * Ninguna de esas escrituras deja el archivo roto si se corta: la entrada del `SeekHead` se añade
     * en su hueco libre como en `MkvLayout`, o se mueve con un parche de 8 bytes, y la reserva se
     * anula con otro de 9. Entre el segundo y el tercero hay dos `Cues` a la vez, los dos válidos.
     */
    private fun moveCuesToEnd() {
        val position = out.position
        val points = ByteArrayOutputStream()
        for (cue in timeline.cues) points.write(cue.encoded())
        out.write(MkvLayout.cues(points.toByteArray()))
        if (cuesSeekPositionPos >= 0) {
            out.patch(cuesSeekPositionPos, EbmlWriter.bigEndian(position - segmentDataStart, 8))
        } else {
            addSeekEntry(MkvLayout.seekEntry(MatroskaIds.CUES, position - segmentDataStart))
        }
        if (cuesReservePos >= 0) out.patch(cuesReservePos, MkvLayout.voidOver(cuesReserveBytes.toLong()))
    }

    /**
     * Escribe el `SeekHead` en su hueco: Info, Tracks y, si ya se sabe dónde va a estar, el índice,
     * con el resto del hueco libre para añadirle la entrada del índice en [stop] si hace falta.
     */
    private fun writeSeekHead() {
        val targets = arrayListOf(
            MatroskaIds.INFO to infoPos - segmentDataStart,
            MatroskaIds.TRACKS to tracksPos - segmentDataStart,
        )
        if (cuesReservePos >= 0) targets += MatroskaIds.CUES to cuesReservePos - segmentDataStart
        val seekHead = checkNotNull(MkvLayout.seekHead(targets, MkvLayout.SEEK_HEAD_RESERVED)) { "el SeekHead excede el espacio reservado" }
        out.patch(seekHeadPos, seekHead.bytes)
        if (seekHead.cuesPositionOffset >= 0) cuesSeekPositionPos = seekHeadPos + seekHead.cuesPositionOffset
        seekFreePos = if (seekHead.freeOffset >= 0) seekHeadPos + seekHead.freeOffset else -1
        seekFreeHeaderLength = seekHead.freeHeaderLength
    }

    /** Añade [entry] al `SeekHead` en su hueco libre, en las dos escrituras de `MkvLayout`. */
    private fun addSeekEntry(entry: ByteArray) {
        val append = if (seekFreePos < 0) null else MkvLayout.append(seekFreePos, seekFreeHeaderLength, seekHeadPos + MkvLayout.SEEK_HEAD_RESERVED, entry)
        checkNotNull(append) { "el SeekHead no tiene sitio para la entrada del índice" }
        for ((at, bytes) in append.writes) out.patch(at, bytes)
        seekFreePos = append.nextFreePos
        seekFreeHeaderLength = append.nextFreeHeaderLength
    }

    override fun close() {
        stop()
    }
}
