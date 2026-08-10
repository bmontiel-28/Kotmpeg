package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.Muxer
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
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

    private companion object {
        const val MOVIE_TIMESCALE = 1000L
        const val VIDEO_TIMESCALE = 90000L
        /** Mayor offset que puede expresar una entrada stco (32 bits sin signo). */
        const val UINT32_MAX = 0xFFFFFFFFL
    }

    private class Sample(
        val offset: Long,
        val size: Int,
        val ptsUs: Long,
        val dtsUs: Long,
        val key: Boolean,
        val durationUs: Long,
    )

    private class TrackState(val info: TrackInfo) {
        val samples = ArrayList<Sample>()
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

    override fun writePacket(packet: MediaPacket) {
        check(started) { "start() no llamado" }
        check(!stopped) { "muxer ya detenido" }
        val track = tracks.getOrNull(packet.trackId - 1)
            ?: throw IllegalArgumentException("pista desconocida ${packet.trackId}")
        val offset = out.position
        out.write(packet.data)
        track.samples.add(
            Sample(offset, packet.data.size, packet.ptsUs, packet.dtsUs, packet.isKeyFrame, packet.durationUs),
        )
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
     * vez: cada construcción recorre todas las muestras de todas las pistas y serializa varios
     * MB, y en una grabación larga se nota en lo que tarda este método.
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

            val maxOffset = tracks.maxOfOrNull { t -> t.samples.maxOfOrNull { it.offset } ?: 0L } ?: 0L
            useCo64 = maxOffset > UINT32_MAX

            val tailMoov = buildMoov(0)
            out.write(tailMoov)
            out.close()
            closed = true

            if (fastStart && file != null) {
                var moovSize = tailMoov.size.toLong()
                if (!useCo64 && maxOffset + moovSize > UINT32_MAX) {
                    useCo64 = true
                    moovSize = buildMoov(0).size.toLong()
                }
                try {
                    rewriteFastStart(file, buildMoov(moovSize), mdatSize)
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
     */
    private fun rewriteFastStart(target: File, moov: ByteArray, mdatSize: Long) {
        val temp = File(target.parentFile, target.name + ".faststart.tmp")
        try {
            RandomAccessFile(target, "r").use { source ->
                FileOutputStream(temp).use { sink ->
                    val buffer = ByteArray(1 shl 16)
                    fun copy(from: Long, count: Long) {
                        source.seek(from)
                        var left = count
                        while (left > 0) {
                            val n = source.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                            if (n <= 0) throw EOFException("mp4 truncado durante la reescritura faststart")
                            sink.write(buffer, 0, n)
                            left -= n
                        }
                    }
                    copy(0, mdatStart)          // ftyp (todo lo anterior al mdat)
                    sink.write(moov)
                    copy(mdatStart, mdatSize)   // mdat
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

    private fun computeTables(t: TrackState, globalStartUs: Long): TrackTables {
        val n = t.samples.size
        val pts = LongArray(n) { t.samples[it].ptsUs - globalStartUs }
        var dts = LongArray(n) { t.samples[it].dtsUs - globalStartUs }

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
        fun toTicks(us: Long): Long = Math.floorDiv(us * ts + 500_000, 1_000_000)

        val dts0 = if (n > 0) dts[0] else 0L
        val dtsTicks = LongArray(n) { toTicks(dts[it] - dts0) }
        val ptsTicks = LongArray(n) { toTicks(pts[it] - dts0) }
        val ctts = LongArray(n) { ptsTicks[it] - dtsTicks[it] }

        val stts = LongArray(n)
        for (i in 0 until n - 1) stts[i] = dtsTicks[i + 1] - dtsTicks[i]
        if (n > 0) {
            val last = t.samples[n - 1]
            stts[n - 1] = when {
                last.durationUs > 0 -> toTicks(last.durationUs)
                n > 1 -> stts[n - 2]
                else -> toTicks(defaultSampleDurationUs(t.info))
            }
        }

        val mediaDuration = if (n > 0) dtsTicks[n - 1] + stts[n - 1] else 0
        val earliestPts = ptsTicks.minOrNull() ?: 0L
        val startOffsetUs = maxOf(0L, (pts.minOrNull() ?: 0L))
        val lastEndUs = if (n > 0) (pts.maxOrNull() ?: 0L) + Math.floorDiv(stts[n - 1] * 1_000_000, ts) else 0L
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
    private fun toMovieTicks(us: Long): Long = Math.floorDiv(us * MOVIE_TIMESCALE + 500_000, 1_000_000)

    /** Una duración solo cabe en las cajas versión 0 si entra en 32 bits sin signo. */
    private fun versionFor(duration: Long): Int = if (duration > 0xFFFFFFFFL) 1 else 0

    private fun buildMoov(offsetDelta: Long): ByteArray {
        val globalStartUs = tracks.mapNotNull { t -> t.samples.minOfOrNull { it.ptsUs } }.minOrNull() ?: 0L
        val tables = tracks.map { computeTables(it, globalStartUs) }
        val movieDurationMs = tracks.indices.maxOfOrNull { i ->
            toMovieTicks(tables[i].startOffsetUs + tables[i].presentationDurationUs)
        } ?: 0L
        val mvhdVersion = versionFor(movieDurationMs)

        val moov = BoxBuilder()
        moov.box("moov") {
            fullBox("mvhd", mvhdVersion, 0) {
                if (mvhdVersion == 1) {
                    u64(0); u64(0)                   // tiempos de creación/modificación
                    u32(MOVIE_TIMESCALE)
                    u64(movieDurationMs)
                } else {
                    u32(0); u32(0)
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
        parent.box("trak") {
            fullBox("tkhd", tkhdVersion, 3) {        // habilitada | en la película
                if (tkhdVersion == 1) {
                    u64(0); u64(0)                   // tiempos de creación/modificación
                    u32(info.id)
                    u32(0)                           // reservado
                    u64(trackDurationMs)
                } else {
                    u32(0); u32(0)                   // tiempos de creación/modificación
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
                        u64(0); u64(0)
                        u32(t.timescale)
                        u64(tab.mediaDuration)
                    } else {
                        u32(0); u32(0)
                        u32(t.timescale)
                        u32(tab.mediaDuration)
                    }
                    u16(0x55C4)                      // idioma: und
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
        }
    }

    private fun writeEdts(parent: BoxBuilder, t: TrackState, tab: TrackTables) {
        val needsShift = tab.earliestPts > 0
        val needsDelay = tab.startOffsetUs > 0
        if (!needsShift && !needsDelay) return

        val delayTicks = toMovieTicks(tab.startOffsetUs)
        val durationTicks = toMovieTicks(tab.presentationDurationUs)
        val version = if (
            versionFor(durationTicks) == 1 ||
            (needsDelay && versionFor(delayTicks) == 1) ||
            tab.earliestPts > Int.MAX_VALUE
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
                    u64(durationTicks); u64(tab.earliestPts)
                } else {
                    u32(durationTicks); u32(tab.earliestPts)
                }
                u16(1); u16(0)                       // media_rate 1.0
            }
        }
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

            val sttsRuns = ArrayList<Pair<Long, Long>>() // count, delta
            for (d in tab.sttsDeltas) {
                val lastRun = sttsRuns.lastOrNull()
                if (lastRun != null && lastRun.second == d) {
                    sttsRuns[sttsRuns.size - 1] = lastRun.first + 1 to d
                } else {
                    sttsRuns.add(1L to d)
                }
            }
            fullBox("stts", 0, 0) {
                u32(sttsRuns.size)
                for ((count, delta) in sttsRuns) { u32(count); u32(delta) }
            }

            if (tab.cttsTicks.any { it != 0L }) {
                val cttsRuns = ArrayList<Pair<Long, Long>>()
                for (c in tab.cttsTicks) {
                    val lastRun = cttsRuns.lastOrNull()
                    if (lastRun != null && lastRun.second == c) {
                        cttsRuns[cttsRuns.size - 1] = lastRun.first + 1 to c
                    } else {
                        cttsRuns.add(1L to c)
                    }
                }
                fullBox("ctts", 0, 0) {
                    u32(cttsRuns.size)
                    for ((count, offset) in cttsRuns) { u32(count); u32(offset) }
                }
            }

            if (samples.any { !it.key }) {
                val keys = samples.indices.filter { samples[it].key }
                fullBox("stss", 0, 0) {
                    u32(keys.size)
                    for (k in keys) u32(k + 1)
                }
            }

            val chunkFirstSample = ArrayList<Int>()
            val chunkSampleCount = ArrayList<Int>()
            var i = 0
            while (i < samples.size) {
                var j = i
                var end = samples[j].offset + samples[j].size
                while (j + 1 < samples.size && samples[j + 1].offset == end) {
                    j++
                    end = samples[j].offset + samples[j].size
                }
                chunkFirstSample.add(i)
                chunkSampleCount.add(j - i + 1)
                i = j + 1
            }

            fullBox("stsc", 0, 0) {
                val entries = ArrayList<Pair<Int, Int>>() // firstChunk (1-based), samplesPerChunk
                for (c in chunkSampleCount.indices) {
                    if (entries.isEmpty() || entries.last().second != chunkSampleCount[c]) {
                        entries.add(c + 1 to chunkSampleCount[c])
                    }
                }
                u32(entries.size)
                for ((first, count) in entries) { u32(first); u32(count); u32(1) }
            }

            fullBox("stsz", 0, 0) {
                u32(0)                               // sample_size: no constante
                u32(samples.size)
                for (s in samples) u32(s.size)
            }

            if (useCo64) {
                fullBox("co64", 0, 0) {
                    u32(chunkFirstSample.size)
                    for (first in chunkFirstSample) u64(samples[first].offset + offsetDelta)
                }
            } else {
                fullBox("stco", 0, 0) {
                    u32(chunkFirstSample.size)
                    for (first in chunkFirstSample) u32(samples[first].offset + offsetDelta)
                }
            }
        }
    }
}
