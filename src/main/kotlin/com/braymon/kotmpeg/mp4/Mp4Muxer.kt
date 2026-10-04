package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.Muxer
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.Timestamps
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Muxer MP4 (ISO BMFF).
 *
 * Los datos de muestra se escriben en streaming a un único `mdat` (tamaño de 64 bits, así
 * que los archivos >4 GiB funcionan) mientras los metadatos se mantienen en memoria; el
 * `moov` completo (stts/ctts/stss/stsc/stsz/stco o co64, edit lists) se escribe en [stop].
 *
 * Con [fastStart] (el equivalente de `-movflags +faststart` de FFmpeg) el archivo se
 * reescribe al finalizar como `ftyp moov mdat`, para poder reproducir antes de descargarlo
 * entero (streaming progresivo). Requiere el constructor con [File].
 *
 * El manejo de tiempos es espec-exacto para la sincronización A/V:
 *  - Si los paquetes llevan DTS monótono válido, se usa directamente.
 *  - Si no (MediaCodec de Android y las fuentes MKV solo dan PTS), se deriva un DTS
 *    monótono desde la secuencia de PTS: en orden de decodificación,
 *    `dts_i = sortedPts_i - delta` con `delta = max(sortedPts_i - pts_i)`, lo que
 *    garantiza `dts_i <= pts_i`, conserva las duraciones y produce offsets `ctts` mínimos
 *    no negativos.
 *  - Una edit list alinea el inicio de presentación a cero (y conserva los offsets de
 *    inicio por pista), así que los streams con B-frames quedan en sincronía perfecta.
 */
public class Mp4Muxer private constructor(
    private val out: SeekableOutput,
    private val file: File?,
    private val fastStart: Boolean,
) : Muxer {

    public constructor(out: SeekableOutput) : this(out, file = null, fastStart = false)

    public constructor(file: File, fastStart: Boolean = false) :
        this(SeekableOutput(file), file, fastStart)

    /**
     * Fecha de creación que se escribe en `mvhd`, `tkhd` y `mdhd`, en milisegundos desde la época
     * de Unix. `null` deja los campos a cero, que es lo que hacían siempre.
     *
     * **Hay que asignarla antes de [start]**, que es cuando se congelan las cabeceras. Es una
     * propiedad y no un parámetro del constructor para que añadirla no cambiara la firma de los
     * constructores públicos, que habría obligado a recompilar a todo el mundo por un metadato.
     */
    public var creationTimeMillis: Long? = System.currentTimeMillis()

    private companion object {
        const val MOVIE_TIMESCALE = 1000L
        const val VIDEO_TIMESCALE = 90000L
        /** Mayor offset que puede expresar una entrada stco (32 bits sin signo). */
        const val UINT32_MAX = 0xFFFFFFFFL

        /**
         * El origen de tiempos de MP4 es 1904-01-01 UTC y no 1970, así que hay que sumar estos
         * segundos a una marca de Unix. Con `version = 0` el campo es de 32 bits sin signo, que
         * alcanza hasta 2040.
         */
        const val MP4_EPOCH_OFFSET_S = 2_082_844_800L

        /** Flags de `tkhd`: `track_enabled` (0x1) y `track_in_movie` (0x2). */
        const val TKHD_ENABLED_IN_MOVIE = 3
        const val TKHD_IN_MOVIE_ONLY = 2
    }

    /** La fecha en la escala de MP4, o 0 si no hay ninguna que escribir. */
    private fun mp4Time(): Long =
        creationTimeMillis?.let { (Math.floorDiv(it, 1000L) + MP4_EPOCH_OFFSET_S).coerceAtLeast(0L) } ?: 0L

    private class TrackState(val info: TrackInfo) {
        val samples = SampleTable()
        val timescale: Long = when (info) {
            is TrackInfo.Video -> VIDEO_TIMESCALE
            is TrackInfo.Audio -> info.sampleRate.toLong()
        }
    }

    private val tracks = ArrayList<TrackState>()
    private var started = false
    private var stopped = false
    private var mdatStart = 0L

    /**
     * Tabla de offsets de chunk de 64 bits. Se decide una sola vez antes de construir el
     * moov: las dos pasadas de [fastStart] tienen que producir un moov exactamente del
     * mismo tamaño (los offsets se desplazan, pero el ancho de las entradas no cambia).
     */
    private var useCo64 = false

    override fun addTrack(track: TrackInfo): Int {
        check(!started) { "no se pueden añadir pistas después de start()" }
        if (track is TrackInfo.Video) {
            requireNotNull(track.codecPrivate) { "las pistas de vídeo MP4 requieren codecPrivate (avcC/hvcC)" }
        }
        val id = tracks.size + 1
        tracks.add(TrackState(track.withId(id)))
        return id
    }

    override fun start() {
        check(!started) { "ya iniciado" }
        check(tracks.isNotEmpty()) { "sin pistas" }
        started = true

        val brands = BoxBuilder()
        brands.box("ftyp") {
            fourcc("isom")
            u32(0x200)
            fourcc("isom"); fourcc("iso2")
            if (tracks.any { (it.info as? TrackInfo.Video)?.codec == VideoCodec.H264 }) fourcc("avc1")
            if (tracks.any { (it.info as? TrackInfo.Audio)?.codec == AudioCodec.OPUS }) fourcc("iso6")
            fourcc("mp41")
        }
        out.write(brands.toByteArray())

        mdatStart = out.position
        out.writeInt32(1)
        out.write("mdat".toByteArray(Charsets.US_ASCII))
        out.writeInt64(0)
    }

    /**
     * Toda muestra de audio se registra como muestra de sincronización, la marque o no quien
     * llama. En AAC y Opus cada paquete se decodifica por sí solo, y `MediaPacket.isKeyFrame`
     * vale `false` por defecto: con el audio marcado tal cual llegaba, la pista salía con un
     * `stss` sin una sola entrada, y los reproductores la tomaban por imposible de buscar.
     */
    override fun writePacket(packet: MediaPacket) {
        check(started) { "start() no llamado" }
        check(!stopped) { "muxer ya detenido" }
        val track = tracks.getOrNull(packet.trackId - 1)
            ?: throw IllegalArgumentException("pista desconocida ${packet.trackId}")
        val offset = out.position
        out.write(packet.data)
        val key = packet.isKeyFrame || track.info is TrackInfo.Audio
        track.samples.add(offset, packet.data.size, packet.ptsUs, packet.dtsUs, key, packet.durationUs)
    }

    /**
     * Cierra el archivo: parchea el tamaño del `mdat`, escribe el `moov` y, si se pidió inicio
     * rápido, lo recoloca al principio.
     *
     * El orden es lo importante: **el archivo se termina y se cierra completo con el `moov` al
     * final antes de intentar la optimización**. Así, si la recolocación falla —quedarse sin
     * espacio es lo más probable, porque necesita una segunda copia entera— lo que queda en
     * disco es una grabación válida sin inicio rápido, y no un archivo con todos los datos
     * dentro y ningún índice, que es irreproducible.
     *
     * Ese mismo `moov` de cola se reutiliza para conocer su tamaño en vez de construirlo otra
     * vez, y las tablas de tiempos se calculan una sola vez para las dos construcciones: cada
     * una recorre todas las muestras de todas las pistas, y en una grabación larga se nota en lo
     * que tarda este método.
     */
    override fun stop() {
        if (stopped) return
        stopped = true
        if (!started) {
            out.close()
            return
        }

        var closed = false
        try {
            val mdatSize = out.position - mdatStart
            val sizeBytes = ByteArray(8)
            for (i in 0 until 8) sizeBytes[i] = ((mdatSize ushr (8 * (7 - i))) and 0xFF).toByte()
            out.patch(mdatStart + 8, sizeBytes)

            val maxOffset = tracks.maxOfOrNull { t ->
                var max = 0L
                for (i in 0 until t.samples.size) max = maxOf(max, t.samples.offset(i))
                max
            } ?: 0L
            useCo64 = maxOffset > UINT32_MAX

            val globalStartUs = globalStartUs()
            val tables = tracks.map { computeTables(it, globalStartUs) }
            val tailMoov = buildMoov(tables, 0)
            out.write(tailMoov)
            out.close()
            closed = true

            if (fastStart && file != null) {
                var moovSize = tailMoov.size.toLong()
                if (!useCo64 && maxOffset + moovSize > UINT32_MAX) {
                    useCo64 = true
                    moovSize = buildMoov(tables, 0).size.toLong()
                }
                try {
                    rewriteFastStart(file, buildMoov(tables, moovSize), mdatSize)
                } catch (t: Throwable) {
                    throw IOException(
                        "no se pudo recolocar el moov al principio de ${file.name} " +
                            "(${t.message}). El archivo está completo y se reproduce con " +
                            "normalidad, pero sin inicio rápido: la reescritura necesita " +
                            "espacio libre igual al tamaño final del archivo.",
                        t,
                    )
                }
            }
        } finally {
            if (!closed) runCatching { out.close() }
        }
    }

    /**
     * Reescribe [target] como `ftyp moov mdat` (moov recolocado antes de los datos).
     *
     * Nunca sobrescribe [target] en el sitio: construye la versión reordenada en un archivo
     * aparte y la pone en su sitio con un movimiento atómico, así que **en todo momento hay en
     * disco al menos una copia completa y legible**, incluida la salida por excepción. El
     * precio es que durante la operación conviven las dos, y por eso el modo pide espacio libre
     * para las dos.
     *
     * El movimiento va por [Files.move] y no por `File.renameTo`, que es lo que hace que la
     * invariante sea de una sola línea: `renameTo` no garantiza por contrato reemplazar un
     * destino existente —falla en Windows con algunos JDK y reemplaza con otros—, así que
     * defenderse de él obligaba a apartar el original a un `.bak`, encadenar tres renombrados y
     * saber deshacer un intercambio a medias. `ATOMIC_MOVE` dentro del mismo directorio no
     * tiene estado intermedio: o el destino queda sustituido, o lanza y el original sigue
     * exactamente donde estaba. El respaldo sin `ATOMIC_MOVE` está por si el sistema de archivos
     * no lo soporta, que es una capacidad suya y no una promesa de la API.
     *
     * El borrado del temporal en el `finally` es incondicional **y seguro**: si el movimiento
     * salió bien, el temporal ya no existe con ese nombre; si lanzó, el destino no se ha tocado
     * y lo que se borra es una copia desechable. Nunca es la única copia.
     *
     * La copia va de canal a canal con `transferTo`, que el sistema puede resolver sin pasar los
     * datos por el heap, y el temporal se fuerza al disco **antes** de sustituir el original. Sin
     * ese `force`, un corte de batería justo después del movimiento podía dejar en el sitio del
     * archivo bueno uno con el nombre correcto y el contenido aún sin escribir.
     */
    private fun rewriteFastStart(target: File, moov: ByteArray, mdatSize: Long) {
        val temp = File(target.parentFile, target.name + ".faststart.tmp")
        try {
            RandomAccessFile(target, "r").use { source ->
                RandomAccessFile(temp, "rw").use { sink ->
                    sink.setLength(0)
                    val from = source.channel
                    val to = sink.channel
                    fun copy(start: Long, count: Long) {
                        var done = 0L
                        while (done < count) {
                            val n = from.transferTo(start + done, count - done, to)
                            if (n <= 0) throw EOFException("mp4 truncado durante la reescritura faststart")
                            done += n
                        }
                    }
                    copy(0, mdatStart)          // ftyp (todo lo anterior al mdat)
                    sink.write(moov)
                    copy(mdatStart, mdatSize)   // mdat
                    to.force(true)
                }
            }
            try {
                Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    override fun close(): Unit = stop()

    private class TrackTables(
        val dtsTicks: LongArray,
        val cttsTicks: LongArray,
        val sttsDeltas: LongArray,
        /** Duración de la pista en ticks de la escala de tiempo del medio. */
        val mediaDuration: Long,
        /** Tiempo de presentación más temprano en ticks (media_time de la edit list). */
        val earliestPts: Long,
        /** Offset de inicio de presentación de la pista respecto a la película, en us. */
        val startOffsetUs: Long,
        /** Duración de presentación en us (para tkhd/mvhd). */
        val presentationDurationUs: Long,
    )

    /** Inicio de presentación de la película: el menor PTS entre todas las pistas. */
    private fun globalStartUs(): Long {
        var start = Long.MAX_VALUE
        for (t in tracks) for (i in 0 until t.samples.size) start = minOf(start, t.samples.ptsUs(i))
        return if (start == Long.MAX_VALUE) 0L else start
    }

    private fun computeTables(t: TrackState, globalStartUs: Long): TrackTables {
        val samples = t.samples
        val n = samples.size
        val pts = LongArray(n) { samples.ptsUs(it) - globalStartUs }
        var dts = LongArray(n) { samples.dtsUs(it) - globalStartUs }

        var dtsValid = true
        for (i in 0 until n) {
            if (dts[i] > pts[i] || (i > 0 && dts[i] < dts[i - 1])) { dtsValid = false; break }
        }
        if (!dtsValid) {
            val sorted = pts.sortedArray()
            var delta = 0L
            for (i in 0 until n) delta = maxOf(delta, sorted[i] - pts[i])
            dts = LongArray(n) { sorted[it] - delta }
        }

        val ts = t.timescale
        fun toTicks(us: Long): Long = Timestamps.rescaleRounded(us, ts, 1_000_000)

        val dts0 = if (n > 0) dts[0] else 0L
        val dtsTicks = LongArray(n) { toTicks(dts[it] - dts0) }
        val ptsTicks = LongArray(n) { toTicks(pts[it] - dts0) }
        val ctts = LongArray(n) { ptsTicks[it] - dtsTicks[it] }

        val stts = LongArray(n)
        for (i in 0 until n - 1) stts[i] = dtsTicks[i + 1] - dtsTicks[i]
        if (n > 0) {
            val lastDurationUs = samples.durationUs(n - 1)
            stts[n - 1] = when {
                lastDurationUs > 0 -> toTicks(lastDurationUs)
                n > 1 -> stts[n - 2]
                else -> toTicks(defaultSampleDurationUs(t.info))
            }
        }

        val mediaDuration = if (n > 0) dtsTicks[n - 1] + stts[n - 1] else 0
        val earliestPts = ptsTicks.minOrNull() ?: 0L
        val startOffsetUs = maxOf(0L, (pts.minOrNull() ?: 0L))
        val lastEndUs = if (n > 0) (pts.maxOrNull() ?: 0L) + Timestamps.rescaleFloor(stts[n - 1], 1_000_000, ts) else 0L
        return TrackTables(dtsTicks, ctts, stts, mediaDuration, earliestPts, startOffsetUs, lastEndUs - startOffsetUs)
    }

    /**
     * Duración estimada de una muestra cuando no hay ninguna otra de la que deducirla: un
     * frame de AAC (1024 muestras) en audio, 30 fps en vídeo. Los mismos valores que usa
     * [FragmentedMp4Muxer] para el caso equivalente.
     */
    private fun defaultSampleDurationUs(info: TrackInfo): Long = when (info) {
        is TrackInfo.Audio -> 1024L * 1_000_000 / info.sampleRate
        is TrackInfo.Video -> 33_333L
    }

    /** Microsegundos -> ticks de la escala de la película, con el mismo redondeo que el resto. */
    private fun toMovieTicks(us: Long): Long = Timestamps.rescaleRounded(us, MOVIE_TIMESCALE, 1_000_000)

    /** Una duración solo cabe en las cajas versión 0 si entra en 32 bits sin signo. */
    private fun versionFor(duration: Long): Int = if (duration > 0xFFFFFFFFL) 1 else 0

    private fun buildMoov(tables: List<TrackTables>, offsetDelta: Long): ByteArray {
        val movieDurationMs = tracks.indices.maxOfOrNull { i ->
            toMovieTicks(tables[i].startOffsetUs + tables[i].presentationDurationUs)
        } ?: 0L
        val mvhdVersion = versionFor(movieDurationMs)

        val moov = BoxBuilder()
        moov.box("moov") {
            val created = mp4Time()
            fullBox("mvhd", mvhdVersion, 0) {
                if (mvhdVersion == 1) {
                    u64(created); u64(created)       // tiempos de creación/modificación
                    u32(MOVIE_TIMESCALE)
                    u64(movieDurationMs)
                } else {
                    u32(created); u32(created)
                    u32(MOVIE_TIMESCALE)
                    u32(movieDurationMs)
                }
                u32(0x00010000)                      // velocidad 1.0
                u16(0x0100)                          // volumen 1.0
                u16(0); u32(0); u32(0)               // reservado
                identityMatrix()
                zeros(24)                            // pre_defined
                u32(tracks.size + 1)                 // next_track_ID
            }
            for ((i, t) in tracks.withIndex()) {
                writeTrak(this, t, tables[i], offsetDelta)
            }
        }
        return moov.toByteArray()
    }

    private fun BoxBuilder.identityMatrix() {
        u32(0x00010000); u32(0); u32(0)
        u32(0); u32(0x00010000); u32(0)
        u32(0); u32(0); u32(0x40000000)
    }

    private fun writeTrak(parent: BoxBuilder, t: TrackState, tab: TrackTables, offsetDelta: Long) {
        val info = t.info
        val trackDurationMs = toMovieTicks(tab.startOffsetUs + tab.presentationDurationUs)
        val tkhdVersion = versionFor(trackDurationMs)
        val created = mp4Time()
        val tkhdFlags = if (info.default) TKHD_ENABLED_IN_MOVIE else TKHD_IN_MOVIE_ONLY
        parent.box("trak") {
            fullBox("tkhd", tkhdVersion, tkhdFlags) {
                if (tkhdVersion == 1) {
                    u64(created); u64(created)       // tiempos de creación/modificación
                    u32(info.id)
                    u32(0)                           // reservado
                    u64(trackDurationMs)
                } else {
                    u32(created); u32(created)       // tiempos de creación/modificación
                    u32(info.id)
                    u32(0)                           // reservado
                    u32(trackDurationMs)
                }
                u32(0); u32(0)                       // reservado
                u16(0)                               // capa
                u16(0)                               // alternate_group
                u16(if (info is TrackInfo.Audio) 0x0100 else 0) // volumen
                u16(0)
                if (info is TrackInfo.Video) {
                    SampleEntries.writeDisplayMatrix(this, info.rotationDegrees, info.displayWidth, info.displayHeight)
                    u32(info.displayWidth.toLong() shl 16)
                    u32(info.displayHeight.toLong() shl 16)
                } else {
                    identityMatrix()
                    u32(0); u32(0)
                }
            }
            writeEdts(this, t, tab)
            box("mdia") {
                val mdhdVersion = versionFor(tab.mediaDuration)
                fullBox("mdhd", mdhdVersion, 0) {
                    if (mdhdVersion == 1) {
                        u64(created); u64(created)   // tiempos de creación/modificación
                        u32(t.timescale)
                        u64(tab.mediaDuration)
                    } else {
                        u32(created); u32(created)   // tiempos de creación/modificación
                        u32(t.timescale)
                        u32(tab.mediaDuration)
                    }
                    u16(SampleEntries.packLanguage(info.language)) // idioma ISO 639-2/T
                    u16(0)
                }
                fullBox("hdlr", 0, 0) {
                    u32(0)
                    fourcc(if (info is TrackInfo.Video) "vide" else "soun")
                    u32(0); u32(0); u32(0)
                    bytes("Kotmpeg".toByteArray(Charsets.US_ASCII)); u8(0) // null-terminated name
                }
                box("minf") {
                    if (info is TrackInfo.Video) {
                        fullBox("vmhd", 0, 1) { u16(0); u16(0); u16(0); u16(0) }
                    } else {
                        fullBox("smhd", 0, 0) { u16(0); u16(0) }
                    }
                    box("dinf") {
                        fullBox("dref", 0, 0) {
                            u32(1)
                            fullBox("url ", 0, 1) {} // autocontenido
                        }
                    }
                    writeStbl(this, t, tab, offsetDelta)
                }
            }
            writeTrackName(this, info)
        }
    }

    /**
     * Nombre legible de la pista, en `udta` > `name` dentro del `trak`.
     *
     * No vale el texto del `hdlr`: ese es el nombre del *manejador* —sale «Kotmpeg» en todas las
     * pistas— y ningún reproductor lo usa para distinguirlas. Con varias pistas del mismo tipo,
     * esto es lo único que permite saber cuál es cuál.
     */
    private fun writeTrackName(parent: BoxBuilder, info: TrackInfo) {
        val name = info.name ?: return
        parent.box("udta") {
            box("name") {
                bytes(name.toByteArray(Charsets.UTF_8))
            }
        }
    }

    /**
     * Lista de edición de la pista, que resuelve **dos cosas distintas** que es fácil confundir.
     *
     *  - La **entrada vacía** (`media_time = -1`) coloca el desfase de arranque de la pista
     *    respecto al inicio de la película: la pista simplemente empieza más tarde.
     *  - La **entrada real** dice desde qué punto del medio se presenta. Ahí es donde se compensa
     *    el **cebado del codificador**: un códec con solapamiento de ventanas no emite su primer
     *    paquete hasta haber consumido más muestras de las que ese paquete representa, así que sin
     *    saltarlas el audio se reproduce adelantado respecto al vídeo — unos 21 ms con AAC-LC a
     *    48 kHz. Es lo que un reproductor lee como `initial_padding`.
     *
     * Estaba resuelta solo la primera, y la segunda arrancaba en la primera muestra sin saltar
     * nada. El cebado se suma a `media_time` en ticks **del medio** (no de la película) y se resta
     * de la duración del segmento, para que la edición no se salga del final del medio.
     */
    private fun writeEdts(parent: BoxBuilder, t: TrackState, tab: TrackTables) {
        val primingTicks = primingTicks(t)
        val primingUs = if (primingTicks > 0) (t.info as TrackInfo.Audio).codecDelayUs else 0L
        val needsShift = tab.earliestPts > 0
        val needsDelay = tab.startOffsetUs > 0
        if (!needsShift && !needsDelay && primingTicks <= 0) return

        val mediaTime = tab.earliestPts + primingTicks
        val delayTicks = toMovieTicks(tab.startOffsetUs)
        val durationTicks = toMovieTicks((tab.presentationDurationUs - primingUs).coerceAtLeast(0L))
        val version = if (
            versionFor(durationTicks) == 1 ||
            (needsDelay && versionFor(delayTicks) == 1) ||
            mediaTime > Int.MAX_VALUE
        ) 1 else 0

        parent.box("edts") {
            fullBox("elst", version, 0) {
                u32(if (needsDelay) 2 else 1)
                if (needsDelay) {
                    if (version == 1) {
                        u64(delayTicks); u64(-1L)
                    } else {
                        u32(delayTicks); u32(-1)
                    }
                    u16(1); u16(0)
                }
                if (version == 1) {
                    u64(durationTicks); u64(mediaTime)
                } else {
                    u32(durationTicks); u32(mediaTime)
                }
                u16(1); u16(0)                       // media_rate 1.0
            }
        }
    }

    /**
     * Cebado de la pista en ticks de su propia escala, redondeando: a 48 kHz los 21 333 µs de un
     * AAC-LC son 1023,98 muestras, y truncar dejaría el `initial_padding` una muestra corto.
     */
    private fun primingTicks(t: TrackState): Long {
        val info = t.info
        if (info !is TrackInfo.Audio || info.codecDelayUs <= 0) return 0L
        return Timestamps.rescaleRounded(info.codecDelayUs, t.timescale, 1_000_000)
    }

    private fun writeStbl(parent: BoxBuilder, t: TrackState, tab: TrackTables, offsetDelta: Long) {
        val samples = t.samples
        parent.box("stbl") {
            fullBox("stsd", 0, 0) {
                u32(1)
                when (val info = t.info) {
                    is TrackInfo.Video -> SampleEntries.writeVisual(this, info)
                    is TrackInfo.Audio -> SampleEntries.writeAudio(this, info)
                }
            }

            val sttsRuns = runLengths(tab.sttsDeltas)
            fullBox("stts", 0, 0) {
                u32(sttsRuns.size)
                for (r in 0 until sttsRuns.size) { u32(sttsRuns.count(r)); u32(sttsRuns.value(r)) }
            }

            if (tab.cttsTicks.any { it != 0L }) {
                val cttsRuns = runLengths(tab.cttsTicks)
                fullBox("ctts", 0, 0) {
                    u32(cttsRuns.size)
                    for (r in 0 until cttsRuns.size) { u32(cttsRuns.count(r)); u32(cttsRuns.value(r)) }
                }
            }

            var keyCount = 0
            for (s in 0 until samples.size) if (samples.isKey(s)) keyCount++
            if (keyCount < samples.size) {
                fullBox("stss", 0, 0) {
                    u32(keyCount)
                    for (s in 0 until samples.size) if (samples.isKey(s)) u32(s + 1)
                }
            }

            val chunkFirstSample = IntArray(samples.size)
            val chunkSampleCount = IntArray(samples.size)
            var chunkCount = 0
            var i = 0
            while (i < samples.size) {
                var j = i
                var end = samples.offset(j) + samples.size(j)
                while (j + 1 < samples.size && samples.offset(j + 1) == end) {
                    j++
                    end = samples.offset(j) + samples.size(j)
                }
                chunkFirstSample[chunkCount] = i
                chunkSampleCount[chunkCount] = j - i + 1
                chunkCount++
                i = j + 1
            }

            fullBox("stsc", 0, 0) {
                var entries = 0
                for (c in 0 until chunkCount) {
                    if (c == 0 || chunkSampleCount[c] != chunkSampleCount[c - 1]) entries++
                }
                u32(entries)
                for (c in 0 until chunkCount) {
                    if (c == 0 || chunkSampleCount[c] != chunkSampleCount[c - 1]) {
                        u32(c + 1); u32(chunkSampleCount[c]); u32(1) // first_chunk, samples_per_chunk, sample_description_index
                    }
                }
            }

            fullBox("stsz", 0, 0) {
                u32(0)                               // sample_size: no constante
                u32(samples.size)
                for (s in 0 until samples.size) u32(samples.size(s))
            }

            if (useCo64) {
                fullBox("co64", 0, 0) {
                    u32(chunkCount)
                    for (c in 0 until chunkCount) u64(samples.offset(chunkFirstSample[c]) + offsetDelta)
                }
            } else {
                fullBox("stco", 0, 0) {
                    u32(chunkCount)
                    for (c in 0 until chunkCount) u32(samples.offset(chunkFirstSample[c]) + offsetDelta)
                }
            }
        }
    }

    /** Series de valores repetidos consecutivos, en arrays primitivos y sin un `Pair` por serie. */
    private class Runs(private val counts: LongArray, private val values: LongArray, val size: Int) {
        fun count(i: Int): Long = counts[i]
        fun value(i: Int): Long = values[i]
    }

    private fun runLengths(values: LongArray): Runs {
        val counts = LongArray(values.size)
        val runValues = LongArray(values.size)
        var runs = 0
        for (v in values) {
            if (runs > 0 && runValues[runs - 1] == v) {
                counts[runs - 1]++
            } else {
                runValues[runs] = v
                counts[runs] = 1
                runs++
            }
        }
        return Runs(counts, runValues, runs)
    }
}
