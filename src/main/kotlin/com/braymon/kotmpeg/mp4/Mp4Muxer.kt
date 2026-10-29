package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.Muxer
import com.braymon.kotmpeg.MuxerOptions
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.Timestamps
import com.braymon.kotmpeg.model.TrackDefaults
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.TrackRequirements
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
 * Con `fastStart` de [MuxerOptions] (el equivalente de `-movflags +faststart` de FFmpeg) el
 * archivo queda al finalizar como `ftyp moov mdat`, para poder reproducir antes de descargarlo
 * entero (streaming progresivo). Con un [File] se reescribe en un temporal que sustituye al
 * original de forma atómica; con un [SeekableOutput] se desplazan los datos en el sitio (ver
 * [stop]). De las demás opciones usa `creationTimeMillis` y `writingApp`.
 *
 * El manejo de tiempos es espec-exacto para la sincronización A/V:
 *  - Si los paquetes llevan DTS monótono válido, se usa directamente.
 *  - Si no (MediaCodec de Android y las fuentes MKV solo dan PTS), se deriva un DTS
 *    monótono desde la secuencia de PTS: en orden de decodificación,
 *    `dts_i = sortedPts_i - delta` con `delta = max(sortedPts_i - pts_i)`, lo que
 *    garantiza `dts_i <= pts_i`, conserva las duraciones y produce offsets `ctts` mínimos
 *    no negativos.
 *  - Eso es en vídeo. En audio no hay reordenación: el DTS es la secuencia de PTS, ordenada
 *    si algún paquete llega con un PTS que retrocede, y no se escribe `ctts` (ver `TrackTiming`).
 *  - Una edit list alinea el inicio de presentación a cero (y conserva los offsets de
 *    inicio por pista), así que los streams con B-frames quedan en sincronía perfecta.
 */
public class Mp4Muxer internal constructor(
    private val out: SeekableOutput,
    private val file: File?,
    options: MuxerOptions,
) : Muxer {

    /**
     * Escribe en [out], que el muxer cierra al terminar. Rechaza `fragmented = true`, y con
     * `fastStart` exige que [out] se pueda leer, porque el índice se recoloca en el sitio: un
     * descriptor abierto solo para escribir se rechaza aquí, antes de grabar nada, y no en [stop].
     * En los dos casos [out] queda cerrado.
     */
    public constructor(out: SeekableOutput, options: MuxerOptions.() -> Unit = {}) :
        this(out, null, MuxerOptions().apply(options).validatedFor(out) { validatedForPlainMp4() })

    /**
     * Escribe en [file], truncándolo. Las opciones se validan antes de abrirlo, y
     * `fragmented = true` se rechaza: para eso está `FragmentedMp4Muxer`.
     */
    public constructor(file: File, options: MuxerOptions.() -> Unit = {}) :
        this(MuxerOptions().apply(options).validatedForPlainMp4(), file)

    internal constructor(options: MuxerOptions, file: File) : this(SeekableOutput(file), file, options)

    private val fastStart = options.fastStart
    private val writingApp = options.writingApp

    /** La fecha de creación en la escala de MP4, o 0 si no hay ninguna que escribir. */
    private val created = MovieBoxes.mp4Time(options.creationTimeMillis)

    init {
        if (fastStart && file == null) {
            try {
                out.requireReadable("fastStart sobre un SeekableOutput recoloca el índice en el sitio y")
            } catch (t: Throwable) {
                runCatching { out.close() }
                throw t
            }
        }
    }

    private companion object {
        /** Mayor offset que puede expresar una entrada stco (32 bits sin signo). */
        private const val UINT32_MAX = 0xFFFFFFFFL

        /**
         * Lo que ocupa como mucho un `moov` aparte de sus pistas y del nombre de la aplicación: su
         * cabecera, el `mvhd` de la versión 1 y la etiqueta del codificador (ver [moovCapacity]).
         */
        private const val MOOV_FIXED_BYTES = 256L

        /**
         * Lo que ocupa como mucho un `trak` aparte de su entrada de muestra, su nombre y el contenido de
         * sus tablas: `tkhd` y `mdhd` de la versión 1, una lista de edición de dos entradas, `hdlr`,
         * `minf`, `dinf` y las cabeceras de las seis tablas, unos 470 bytes.
         */
        private const val TRAK_FIXED_BYTES = 512L
    }

    /**
     * Una pista registrada, con su entrada de `stsd` ya construida (ver [addTrack]) y su cebado en
     * µs y en tics de su escala, que no cambian y se consultan en cada construcción del `moov`.
     */
    private class TrackState(val info: TrackInfo, val sampleEntry: ByteArray) {
        val samples = SampleTable()
        val timescale: Long = MovieBoxes.mediaTimescale(info)
        val primingUs: Long = MovieBoxes.primingUs(info, timescale)
        val primingTicks: Long = MovieBoxes.primingTicks(info, timescale)
    }

    private val tracks = ArrayList<TrackState>()

    /** [TrackInfo.default] resuelto por pista, en el orden de [tracks]; se fija en [start]. */
    private var defaultFlags = BooleanArray(0)
    private var started = false
    private var stopped = false
    private var mdatStart = 0L

    /**
     * Tabla de offsets de chunk de 64 bits. Se decide una sola vez antes de construir el
     * moov: las dos pasadas de [fastStart] tienen que producir un moov exactamente del
     * mismo tamaño (los offsets se desplazan, pero el ancho de las entradas no cambia).
     */
    private var useCo64 = false

    /**
     * Registra una pista y construye ya su entrada de `stsd`, que [stop] copia tal cual.
     *
     * Construirla aquí es lo que valida la pista: un Opus de más de dos canales sin tabla de mapeo,
     * un AAC sin `codecPrivate` con un número de canales que el ASC no expresa o un `codecPrivate`
     * Opus que no es una `OpusHead` se rechazan con [IllegalArgumentException] antes de grabar nada,
     * como en `MkvMuxer`. Hasta ahora la entrada se construía por primera vez en [stop]: se grababa
     * una hora entera y el error salía al cerrar, con el `mdat` escrito y sin `moov`, un archivo que
     * no se puede reproducir ni reparar. Una pista rechazada no se registra y no consume id.
     */
    override fun addTrack(track: TrackInfo): Int {
        check(!started) { "no se pueden añadir pistas después de start()" }
        TrackRequirements.requireVideoCodecPrivate(track) { "las pistas de vídeo MP4 requieren codecPrivate (avcC/hvcC)" }
        val id = tracks.size + 1
        val info = track.withId(id)
        tracks.add(TrackState(info, SampleEntries.sampleEntry(info)))
        return id
    }

    override fun start() {
        check(!started) { "ya iniciado" }
        check(tracks.isNotEmpty()) { "sin pistas" }
        started = true
        defaultFlags = TrackDefaults.resolve(tracks.map { it.info })

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
     * llama. En AAC y Opus cada paquete se decodifica por sí solo, y con el audio marcado tal
     * cual llegaba —un codificador de audio no tiene por qué marcarlos como keyframes— la pista salía
     * con un `stss` sin una sola entrada, y los reproductores la tomaban por imposible de buscar.
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
     * vez, y las tablas de tiempos y de chunks se calculan una sola vez para las dos
     * construcciones ([TrackTables]): cada una recorre todas las muestras de todas las pistas, y
     * en una grabación larga se nota en lo que tarda este método.
     *
     * Y en lo que reserva, que es lo que de verdad importa aquí: un `OutOfMemoryError` al cerrar
     * deja el archivo sin `moov`. Ninguna tabla lleva una entrada por muestra salvo la que no se
     * puede evitar —los PTS ordenados, cuando hay que derivar el DTS— y **nunca hay dos `moov`
     * vivos a la vez**: el de cola se suelta al escribirlo, y para conocer el tamaño del de cabeza
     * en el sitio basta con construirlo, sin copiarlo. Antes se reservaban ocho arrays por muestra
     * para los tiempos, otros cuatro en cada construcción del `moov`, y el de cola seguía vivo
     * mientras se construía el otro: unos 250 bytes por muestra con inicio rápido, cuando la tabla
     * de muestras entera ocupa 37.
     *
     * Sobre un [SeekableOutput] no hay ruta con la que crear un temporal, así que el inicio rápido
     * se hace **en el sitio**: el `moov` se inserta delante del `mdat` desplazando los datos hacia
     * el final (ver `SeekableOutput.insert`). No necesita espacio extra, pero no tiene la red de
     * seguridad del temporal: si el proceso muere durante el desplazamiento, el archivo queda
     * inservible. Hasta la 3.x esta combinación no existía y la única salida era el MP4
     * fragmentado.
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
            if (fastStart && file == null) {
                val tailSize = moovBuilder(tables, 0, moovCapacity(tables)).size.toLong()
                out.insert(mdatStart, frontMoov(tables, frontMoovSize(tables, tailSize, maxOffset)))
                out.close()
                closed = true
                return
            }
            val tailSize = writeTailMoov(tables)
            out.close()
            closed = true

            if (fastStart && file != null) {
                val moovSize = frontMoovSize(tables, tailSize, maxOffset)
                try {
                    rewriteFastStart(file, frontMoov(tables, moovSize), mdatSize)
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
     * Tamaño del `moov` que va delante de los datos, sabiendo que el de cola mide [tailSize].
     *
     * Es el mismo salvo en un caso: si al desplazar los datos lo que mide el propio `moov` el mayor
     * offset deja de caber en 32 bits, la tabla pasa a `co64` y el `moov` crece. Por eso
     * [useCo64] se decide aquí una sola vez, antes de construir el definitivo.
     */
    private fun frontMoovSize(tables: List<TrackTables>, tailSize: Long, maxOffset: Long): Long {
        if (useCo64 || maxOffset + tailSize <= UINT32_MAX) return tailSize
        useCo64 = true
        return moovBuilder(tables, 0, moovCapacity(tables)).size.toLong()
    }

    /**
     * Escribe el `moov` de cola y devuelve su tamaño. Va del builder al archivo sin copiarse a un
     * array propio, y el builder vive solo dentro de esta función, así que ya no existe cuando [stop]
     * construye el de cabeza.
     */
    private fun writeTailMoov(tables: List<TrackTables>): Long {
        val moov = moovBuilder(tables, 0, moovCapacity(tables))
        moov.writeTo(out)
        return moov.size.toLong()
    }

    /**
     * El `moov` de cabeza, que mide exactamente [moovSize] bytes —lo que también desplaza los datos—:
     * se construye en un array de ese tamaño y se entrega sin copiarlo.
     */
    private fun frontMoov(tables: List<TrackTables>, moovSize: Long): ByteArray =
        moovBuilder(tables, moovSize, moovSize).release()

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

    /**
     * Lo que [stop] calcula de una pista una sola vez para todas las construcciones del `moov`:
     * todo menos los offsets de chunk, que dependen de dónde vaya el `moov`. Las series del
     * `stts` y del `ctts` y la lista de chunks tienen el tamaño justo, una entrada por serie o por
     * chunk; las columnas por muestra (`stsz`, `stss`) se escriben leyendo la tabla de muestras.
     */
    private class TrackTables(
        val stts: Runs,
        /** Null si todos los desfases de composición son cero, y entonces no hay `ctts`. */
        val ctts: Runs?,
        /** Primera muestra de cada chunk, en orden: ver [chunkStarts]. */
        val chunkStarts: IntArray,
        /** Duración de la pista en ticks de la escala de tiempo del medio. */
        val mediaDuration: Long,
        /** Tiempo de presentación más temprano en ticks (media_time de la edit list). */
        val earliestPts: Long,
        /** Offset de inicio de presentación de la pista respecto a la película, en us. */
        val startOffsetUs: Long,
        /** Duración de presentación en us (para tkhd/mvhd). */
        val presentationDurationUs: Long,
        /** Muestras de sincronización: si son todas, no hay `stss`. */
        val keyCount: Int,
        /** Entradas del `stsc`: una cada vez que cambia el número de muestras por chunk. */
        val stscEntries: Int,
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
        if (n == 0) return TrackTables(Runs(IntArray(0), LongArray(0)), null, IntArray(0), 0L, 0L, 0L, 0L, 0, 0)
        val timing = TrackTiming.of(samples, t.timescale, audio = t.info is TrackInfo.Audio)

        val lastDelta = when {
            samples.durationUs(n - 1) > 0 -> timing.toTicks(samples.durationUs(n - 1))
            n > 1 -> timing.dtsTicks(n - 1) - timing.dtsTicks(n - 2)
            else -> timing.toTicks(MovieBoxes.defaultSampleDurationUs(t.info))
        }
        val stts = RunLengths()
        val ctts = RunLengths()
        timing.forEachSample(lastDelta) { duration, offset -> stts.add(duration); ctts.add(offset) }
        stts.allocate()
        val hasCtts = ctts.anyNonZero
        if (hasCtts) ctts.allocate()
        timing.forEachSample(lastDelta) { duration, offset ->
            stts.add(duration)
            if (hasCtts) ctts.add(offset)
        }

        var minPtsUs = Long.MAX_VALUE
        var maxPtsUs = Long.MIN_VALUE
        for (i in 0 until n) {
            val pts = timing.ptsUs(i)
            minPtsUs = minOf(minPtsUs, pts)
            maxPtsUs = maxOf(maxPtsUs, pts)
        }
        val mediaDuration = timing.dtsTicks(n - 1) + lastDelta
        val earliestPts = timing.ptsTicks(minPtsUs)
        val startOffsetUs = maxOf(0L, minPtsUs - globalStartUs)
        val lastEndUs = maxPtsUs - globalStartUs + Timestamps.rescaleFloor(lastDelta, 1_000_000, t.timescale)
        var keyCount = 0
        for (i in 0 until n) if (samples.isKey(i)) keyCount++
        val chunkStarts = chunkStarts(samples)
        return TrackTables(
            stts.toRuns(), if (hasCtts) ctts.toRuns() else null, chunkStarts,
            mediaDuration, earliestPts, startOffsetUs, lastEndUs - startOffsetUs,
            keyCount, stscEntries(chunkStarts, n),
        )
    }

    /**
     * Los tiempos de una pista en orden de decodificación, leídos de la tabla de muestras sin copiar
     * ninguna columna: el único array por muestra es el de los PTS ordenados, y solo cuando hace
     * falta derivar con él el DTS.
     *
     * El DTS sale de tres sitios:
     *  - **El del paquete**, en vídeo, si es monótono y nunca posterior a su PTS.
     *  - **El PTS**, si los PTS ya llegan en orden, que es lo normal sin B-frames.
     *  - **Los PTS ordenados**, en vídeo menos el mayor adelanto de una muestra sobre su orden
     *    (ver el KDoc de [Mp4Muxer]): `dts_i = sortedPts_i - delta`.
     *
     * **En audio el DTS nunca sale del paquete ni hay desfases de composición**: AAC y Opus se
     * decodifican en el orden en que se presentan, así que el DTS es la secuencia de PTS (ordenada
     * si hace falta) y el PTS se iguala a él. Antes la derivación para B-frames se aplicaba también
     * al audio, y un paquete que llegaba unos milisegundos tarde respecto al siguiente —un PTS que
     * retrocede, que es jitter de la fuente y no reordenación— generaba un `ctts` y desplazaba el
     * `media_time` de la lista de edición; el demuxer, que en audio toma esa edición como cebado,
     * devolvía un `codecDelayUs` mayor que el declarado. Ahora cada paquete suena en su turno con
     * el siguiente PTS de la secuencia ordenada, a lo sumo a la distancia del retroceso del suyo, y
     * el cebado vuelve tal cual. `MkvMuxer` escribe los tiempos como llegan, porque Matroska no
     * tiene DTS; el resultado al releer cada contenedor es una línea de tiempo monótona.
     */
    private class TrackTiming private constructor(
        val samples: SampleTable,
        private val timescale: Long,
        val audio: Boolean,
        private val callerDts: Boolean,
        private val sortedPtsUs: LongArray?,
        private val reorderDeltaUs: Long,
    ) {
        /** DTS de la primera muestra, el cero de los tics de la pista. */
        private val originUs = if (samples.size > 0) dtsUs(0) else 0L

        fun dtsUs(i: Int): Long = when {
            sortedPtsUs != null -> sortedPtsUs[i] - reorderDeltaUs
            callerDts -> samples.dtsUs(i)
            else -> samples.ptsUs(i)
        }

        fun ptsUs(i: Int): Long = if (audio) dtsUs(i) else samples.ptsUs(i)

        fun toTicks(us: Long): Long = Timestamps.rescaleRounded(us, timescale, 1_000_000)

        fun dtsTicks(i: Int): Long = toTicks(dtsUs(i) - originUs)

        /** Un PTS en µs, en tics desde [originUs]. */
        fun ptsTicks(ptsUs: Long): Long = toTicks(ptsUs - originUs)

        /**
         * Para cada muestra, en orden de decodificación, su duración en el `stts` y su desfase en
         * el `ctts`, en tics; la última dura [lastDelta]. Cada DTS se convierte una sola vez.
         */
        inline fun forEachSample(lastDelta: Long, action: (duration: Long, offset: Long) -> Unit) {
            val n = samples.size
            if (n == 0) return
            var dts = dtsTicks(0)
            for (i in 0 until n) {
                val next = if (i + 1 < n) dtsTicks(i + 1) else dts + lastDelta
                action(next - dts, if (audio) 0L else ptsTicks(ptsUs(i)) - dts)
                dts = next
            }
        }

        companion object {
            fun of(samples: SampleTable, timescale: Long, audio: Boolean): TrackTiming {
                val n = samples.size
                if (!audio && hasValidDts(samples)) return TrackTiming(samples, timescale, audio, true, null, 0L)
                var ordered = true
                for (i in 1 until n) if (samples.ptsUs(i) < samples.ptsUs(i - 1)) { ordered = false; break }
                if (ordered) return TrackTiming(samples, timescale, audio, false, null, 0L)
                val sorted = LongArray(n) { samples.ptsUs(it) }
                sorted.sort()
                var delta = 0L
                if (!audio) for (i in 0 until n) delta = maxOf(delta, sorted[i] - samples.ptsUs(i))
                return TrackTiming(samples, timescale, audio, false, sorted, delta)
            }

            /** El DTS de los paquetes vale si es monótono y ninguno es posterior a su PTS. */
            private fun hasValidDts(samples: SampleTable): Boolean {
                for (i in 0 until samples.size) {
                    val dts = samples.dtsUs(i)
                    if (dts > samples.ptsUs(i) || (i > 0 && dts < samples.dtsUs(i - 1))) return false
                }
                return true
            }
        }
    }

    /**
     * Series de valores repetidos consecutivos, en arrays primitivos del tamaño justo. Se llenan en
     * dos pasadas: la primera ([add] antes de [allocate]) solo cuenta las series, la segunda las
     * guarda. Contar antes cuesta recorrer otra vez los tiempos, que es aritmética, y evita la
     * holgura de un array que crece duplicándose, que con vídeo de frecuencia variable —una serie
     * por muestra— dobla lo que ocupa la tabla.
     */
    private class RunLengths {
        private var counts: IntArray? = null
        private var values: LongArray? = null
        private var runs = 0
        private var last = 0L

        /** Si algún valor de la primera pasada no es cero: un `ctts` todo a cero no se escribe. */
        var anyNonZero: Boolean = false
            private set

        fun add(value: Long) {
            if (runs > 0 && value == last) {
                counts?.let { it[runs - 1]++ }
                return
            }
            counts?.let { it[runs] = 1; values!![runs] = value }
            if (value != 0L) anyNonZero = true
            last = value
            runs++
        }

        fun allocate() {
            counts = IntArray(runs)
            values = LongArray(runs)
            runs = 0
        }

        fun toRuns(): Runs = Runs(counts ?: IntArray(0), values ?: LongArray(0))
    }

    /**
     * Primera muestra de cada chunk: una serie de muestras contiguas en el `mdat`, que es lo que
     * indexan `stsc` y `stco`. Se cuenta en una pasada y se guarda en otra, con el tamaño justo.
     */
    private fun chunkStarts(samples: SampleTable): IntArray {
        var count = 0
        forEachChunkStart(samples) { count++ }
        val starts = IntArray(count)
        var c = 0
        forEachChunkStart(samples) { starts[c++] = it }
        return starts
    }

    private inline fun forEachChunkStart(samples: SampleTable, action: (Int) -> Unit) {
        for (i in 0 until samples.size) {
            if (i == 0 || samples.offset(i) != samples.offset(i - 1) + samples.size(i - 1)) action(i)
        }
    }

    /**
     * Lo que va a ocupar el `moov`, por arriba y con menos de un KiB de holgura por pista, para
     * reservar su [BoxBuilder] de una vez. Las tablas, que en una grabación larga son casi todo, se
     * cuentan exactas; las cajas de tamaño fijo, por su máximo, el de la versión 1.
     *
     * Antes el builder empezaba en 256 bytes y crecía duplicándose: para un `moov` de S bytes
     * reservaba por el camino entre 2S y 4S en arrays que se tiraban, más S de la copia final, y con
     * inicio rápido todo dos veces. Ahora el de cola se escribe desde el builder y el de cabeza, cuyo
     * tamaño ya se conoce, se entrega sin copiar ([BoxBuilder.release]). Con 200 000 paquetes de audio
     * y 100 000 de vídeo —una hora larga— y un `moov` de 2,2 MB, cerrar reservaba 11,3 MB, y 21,5 con
     * inicio rápido; ahora, 3,3 y 5,4.
     */
    private fun moovCapacity(tables: List<TrackTables>): Long {
        var bytes = MOOV_FIXED_BYTES + writingApp.toByteArray(Charsets.UTF_8).size
        val offsetBytes = if (useCo64) 8L else 4L
        for ((i, t) in tracks.withIndex()) {
            val tab = tables[i]
            val n = t.samples.size.toLong()
            bytes += TRAK_FIXED_BYTES + t.sampleEntry.size + (t.info.name?.toByteArray(Charsets.UTF_8)?.size ?: 0)
            bytes += 8L * tab.stts.size + 8L * (tab.ctts?.size ?: 0)
            bytes += 4L * n + if (tab.keyCount < n) 4L * tab.keyCount else 0L
            bytes += 12L * tab.stscEntries + offsetBytes * tab.chunkStarts.size
        }
        return bytes
    }

    /** Una duración de la lista de edición solo cabe en la versión 0 si entra en 32 bits sin signo. */
    private fun versionFor(duration: Long): Int = if (duration > UINT32_MAX) 1 else 0

    /**
     * La duración de la pista en la escala de la película, que es la del `tkhd`: la suma de sus
     * ediciones, como pide ISO/IEC 14496-12 §8.3.2.3. Es el instante en que acaba de presentarse
     * —su desfase de arranque más su presentación, sin el cebado que la edición salta—, redondeado
     * una sola vez; [writeEdts] le resta el hueco inicial para que las dos sumen exactamente esto.
     *
     * Antes el `tkhd` contaba el cebado y la edición no, así que un archivo cuya pista más larga era
     * el audio declaraba en el `mvhd` unos 21 ms de más con AAC-LC: lo que salta la edición.
     */
    private fun trackDurationTicks(t: TrackState, tab: TrackTables): Long =
        MovieBoxes.toMovieTicks(tab.startOffsetUs + (tab.presentationDurationUs - t.primingUs).coerceAtLeast(0L))

    /**
     * El `moov` sin copiar a su array final: para conocer su tamaño basta con construirlo, y
     * copiarlo solo para medirlo duplicaba lo reservado en ese paso. Se construye en un array de
     * [capacity] bytes, que con [moovCapacity] nunca tiene que crecer. El `mvhd` declara la mayor de
     * las duraciones de las pistas.
     */
    private fun moovBuilder(tables: List<TrackTables>, offsetDelta: Long, capacity: Long): BoxBuilder {
        val movieDuration = tracks.indices.maxOfOrNull { i -> trackDurationTicks(tracks[i], tables[i]) } ?: 0L
        val moov = BoxBuilder(capacity.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        moov.box("moov") {
            MovieBoxes.writeMvhd(this, created, movieDuration, tracks.size + 1)
            for ((i, t) in tracks.withIndex()) {
                val tab = tables[i]
                MovieBoxes.writeTrak(
                    this, t.info, defaultFlags[i], created, trackDurationTicks(t, tab), t.timescale, tab.mediaDuration,
                    edts = { writeEdts(this, t, tab) },
                    stbl = { writeStbl(this, t, tab, offsetDelta) },
                )
            }
            SampleEntries.writeEncoderTag(this, writingApp)
        }
        return moov
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
     * de la duración del segmento, para que la edición no se salga del final del medio. Es el de
     * `AudioPriming`, el mismo que declara `MkvMuxer`: en Opus, el `preSkip` de su `OpusHead`.
     *
     * Las dos entradas suman la duración del `tkhd` ([trackDurationTicks]): el segmento real es lo
     * que queda de ella tras el hueco, y no una duración redondeada por su cuenta, que con un
     * desfase de arranque de milisegundos no enteros podía sumar uno de más o de menos.
     */
    private fun writeEdts(parent: BoxBuilder, t: TrackState, tab: TrackTables) {
        val primingTicks = t.primingTicks
        val needsShift = tab.earliestPts > 0
        val needsDelay = tab.startOffsetUs > 0
        if (!needsShift && !needsDelay && primingTicks <= 0) return

        val mediaTime = tab.earliestPts + primingTicks
        val delayTicks = MovieBoxes.toMovieTicks(tab.startOffsetUs)
        val durationTicks = trackDurationTicks(t, tab) - delayTicks
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
     * La tabla de muestras de la pista. Solo los offsets de chunk dependen de [offsetDelta], lo que
     * se desplazan los datos cuando el `moov` va delante; todo lo demás sale ya calculado de [tab].
     */
    private fun writeStbl(parent: BoxBuilder, t: TrackState, tab: TrackTables, offsetDelta: Long) {
        parent.box("stbl") {
            fullBox("stsd", 0, 0) {
                u32(1)                               // entry_count
                bytes(t.sampleEntry)
            }
            writeRuns(this, "stts", tab.stts)
            tab.ctts?.let { writeRuns(this, "ctts", it) }
            writeStss(this, t.samples, tab.keyCount)
            writeStsc(this, tab.chunkStarts, t.samples.size, tab.stscEntries)
            writeStsz(this, t.samples)
            writeChunkOffsets(this, t.samples, tab.chunkStarts, offsetDelta)
        }
    }

    /** `stts` o `ctts` (versión 0): las dos son series de (número de muestras, valor). */
    private fun writeRuns(parent: BoxBuilder, type: String, runs: Runs) {
        parent.fullBox(type, 0, 0) {
            u32(runs.size)                           // entry_count
            for (r in 0 until runs.size) { u32(runs.count(r)); u32(runs.value(r)) } // sample_count, sample_delta/offset
        }
    }

    /** `stss` con las muestras de sincronización; si lo son todas, no se escribe. */
    private fun writeStss(parent: BoxBuilder, samples: SampleTable, keyCount: Int) {
        if (keyCount == samples.size) return
        parent.fullBox("stss", 0, 0) {
            u32(keyCount)                            // entry_count
            for (s in 0 until samples.size) if (samples.isKey(s)) u32(s + 1)
        }
    }

    /** `stsc`: una entrada cada vez que cambia el número de muestras por chunk ([entries], de [stscEntries]). */
    private fun writeStsc(parent: BoxBuilder, chunkStarts: IntArray, sampleCount: Int, entries: Int) {
        parent.fullBox("stsc", 0, 0) {
            u32(entries)                             // entry_count
            forEachStscEntry(chunkStarts, sampleCount) { chunk, samples ->
                u32(chunk + 1); u32(samples); u32(1) // first_chunk, samples_per_chunk, sample_description_index
            }
        }
    }

    /** Las entradas del `stsc` de una pista con estos chunks, sin escribirlas. */
    private fun stscEntries(chunkStarts: IntArray, sampleCount: Int): Int {
        var entries = 0
        forEachStscEntry(chunkStarts, sampleCount) { _, _ -> entries++ }
        return entries
    }

    /** Para cada chunk en que cambia el número de muestras por chunk, su índice y ese número. */
    private inline fun forEachStscEntry(chunkStarts: IntArray, sampleCount: Int, action: (chunk: Int, samples: Int) -> Unit) {
        var previous = -1
        for (c in chunkStarts.indices) {
            val samples = (if (c + 1 < chunkStarts.size) chunkStarts[c + 1] else sampleCount) - chunkStarts[c]
            if (c == 0 || samples != previous) action(c, samples)
            previous = samples
        }
    }

    private fun writeStsz(parent: BoxBuilder, samples: SampleTable) {
        parent.fullBox("stsz", 0, 0) {
            u32(0)                                   // sample_size: no constante
            u32(samples.size)                        // sample_count
            for (s in 0 until samples.size) u32(samples.size(s))
        }
    }

    /** `stco`, o `co64` si [useCo64]: el offset de cada chunk, desplazado [offsetDelta]. */
    private fun writeChunkOffsets(parent: BoxBuilder, samples: SampleTable, chunkStarts: IntArray, offsetDelta: Long) {
        if (useCo64) {
            parent.fullBox("co64", 0, 0) {
                u32(chunkStarts.size)                // entry_count
                for (first in chunkStarts) u64(samples.offset(first) + offsetDelta)
            }
        } else {
            parent.fullBox("stco", 0, 0) {
                u32(chunkStarts.size)                // entry_count
                for (first in chunkStarts) u32(samples.offset(first) + offsetDelta)
            }
        }
    }

    /** Series de valores repetidos consecutivos, en arrays primitivos y sin un `Pair` por serie. */
    private class Runs(private val counts: IntArray, private val values: LongArray) {
        val size: Int get() = counts.size
        fun count(i: Int): Int = counts[i]
        fun value(i: Int): Long = values[i]
    }
}
