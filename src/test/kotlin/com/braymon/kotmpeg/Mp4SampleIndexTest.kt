package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.Mp4Demuxer
import com.braymon.kotmpeg.mp4.TrackSamples
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El índice de muestras bajo demanda de `Mp4Demuxer` (3.1.0): las muestras ya no se cargan al
 * abrir, se recorren desde el archivo con un punto de control cada
 * [TrackSamples.CHECKPOINT_INTERVAL] muestras.
 *
 * Lo que hay que demostrar es doble. Que **la memoria ya no crece con la duración**, que es para lo
 * que existe. Y que **el resultado es el mismo de antes**: cada búsqueda aterriza en la misma
 * muestra que el algoritmo documentado aplicado a la pista entera, también justo en los bordes de
 * los puntos de control, que es donde un error de índice se esconde. La equivalencia con la 3.0.0
 * se verificó además, al implementarlo, paquete a paquete sobre 600 000 archivos mutados con la
 * versión final, y más de 1,2 millones contando las intermedias (ver `ANALISIS.md`, §9.2).
 */
class Mp4SampleIndexTest {

    @TempDir
    lateinit var dir: File

    private class Sample(val pts: Long, val dts: Long, val key: Boolean)

    /** Las muestras de cada pista en su orden, leídas de principio a fin. */
    private fun readAll(file: File): Pair<List<TrackInfo>, Map<Int, List<Sample>>> =
        Mp4Demuxer(file).use { demuxer ->
            val samples = demuxer.tracks.associate { it.id to ArrayList<Sample>() }
            while (true) {
                val packet = demuxer.readPacket() ?: break
                samples.getValue(packet.trackId) += Sample(packet.ptsUs, packet.dtsUs, packet.isKeyFrame)
            }
            demuxer.tracks to samples
        }

    /**
     * El algoritmo de `seekTo` tal como está documentado, sobre arrays: último keyframe con
     * `pts <= destino` entre las muestras del ancla que no empiezan a decodificarse después del
     * destino, y en las demás pistas la primera muestra que no se decodifica antes que él.
     * Devuelve el `pts` de destino y la posición de cada pista.
     */
    private fun expectedSeek(tracks: List<TrackInfo>, samples: Map<Int, List<Sample>>, t: Long): Pair<Long, Map<Int, Int>> {
        val anchor = (tracks.firstOrNull { it is TrackInfo.Video } ?: tracks.first()).id
        val a = samples.getValue(anchor)
        val limit = (a.indexOfFirst { it.dts > t }.let { if (it < 0) a.size else it }).coerceAtMost(a.size - 1)
        val target = (limit downTo 0).firstOrNull { a[it].key && a[it].pts <= t } ?: 0
        val targetDts = a[target].dts
        val positions = tracks.associate { track ->
            val list = samples.getValue(track.id)
            track.id to if (track.id == anchor) target else list.indexOfFirst { it.dts >= targetDts }.let { if (it < 0) list.size else it }
        }
        return a[target].pts to positions
    }

    /** Los siguientes [count] paquetes desde [positions], intercalados como lo hace `readPacket`. */
    private fun expectedNext(tracks: List<TrackInfo>, samples: Map<Int, List<Sample>>, positions: Map<Int, Int>, count: Int): List<String> {
        val cursor = positions.toMutableMap()
        val out = ArrayList<String>()
        while (out.size < count) {
            var best: Int? = null
            var bestDts = Long.MAX_VALUE
            for (track in tracks) {
                val list = samples.getValue(track.id)
                val i = cursor.getValue(track.id)
                if (i < list.size && list[i].dts < bestDts) {
                    bestDts = list[i].dts
                    best = track.id
                }
            }
            val id = best ?: break
            val sample = samples.getValue(id)[cursor.getValue(id)]
            out += "$id ${sample.pts} ${sample.dts}"
            cursor[id] = cursor.getValue(id) + 1
        }
        return out
    }

    /**
     * Un minuto de vídeo a 60 fps con B-frames —pts reordenados, así que hay `ctts`— y un keyframe
     * cada dos segundos, más AAC: 3600 muestras de vídeo, que cruzan una docena de puntos de control.
     */
    private fun write(file: File, format: ContainerFormat, fastStart: Boolean = false, fragmented: Boolean = false): File {
        val muxer = MkvKotlin.createMuxer(file, format, mp4FastStart = fastStart, mp4Fragmented = fragmented, mp4FragmentDurationUs = 1_000_000)
        val video = muxer.addTrack(
            TrackInfo.Video(
                codec = VideoCodec.H264, width = 64, height = 48, frameRate = 60.0,
                codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22))),
            ),
        )
        val audio = muxer.addTrack(
            TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2, codecPrivate = AacConfig.build(48000, 2)),
        )
        muxer.start()
        val frame = 16_667L
        val order = intArrayOf(0, 2, 1, 3, 5, 4)
        val packets = ArrayList<MediaPacket>()
        for (i in 0 until 3600) {
            val shown = (i / 6) * 6 + order[i % 6]
            packets += MediaPacket(video, NalUnits.joinLengthPrefixed(listOf(byteArrayOf(i.toByte(), 1, 2))), shown * frame + frame, i * frame, i % 120 == 0, frame)
        }
        for (i in 0 until 2813) packets += MediaPacket(audio, byteArrayOf(i.toByte(), 7), i * 21_333L, i * 21_333L, true, 21_333)
        packets.sortedBy { it.dtsUs }.forEach { muxer.writePacket(it) }
        muxer.stop()
        return file
    }

    private fun checkSeeksAgainstTheModel(file: File) {
        val (tracks, samples) = readAll(file)
        val video = samples.getValue(tracks.first { it is TrackInfo.Video }.id)
        assertTrue(video.size > 3 * TrackSamples.CHECKPOINT_INTERVAL, "el archivo de prueba no cruza varios puntos de control")
        val targets = ArrayList<Long>()
        for (c in 1 until video.size / TrackSamples.CHECKPOINT_INTERVAL) {
            for (delta in -2..2) {
                val i = c * TrackSamples.CHECKPOINT_INTERVAL + delta
                targets += video[i].dts
                targets += video[i].pts
                targets += video[i].pts - 1
            }
        }
        targets += listOf(Long.MIN_VALUE, -1, 0, 1, video.last().pts, video.last().dts + 1, Long.MAX_VALUE)
        val random = Random(5)
        repeat(200) { targets += random.nextLong(-100_000, 61_000_000) }

        Mp4Demuxer(file).use { demuxer ->
            for (t in targets) {
                val (pts, positions) = expectedSeek(tracks, samples, t)
                assertEquals(pts, demuxer.seekTo(t), "${file.name}: seekTo($t)")
                val actual = (0 until 12).mapNotNull { demuxer.readPacket()?.let { p -> "${p.trackId} ${p.ptsUs} ${p.dtsUs}" } }
                assertEquals(expectedNext(tracks, samples, positions, 12), actual, "${file.name}: paquetes tras seekTo($t)")
            }
        }
    }

    @Test
    fun `seeking a plain mp4 lands where a scan of the whole track would, across checkpoints`() {
        checkSeeksAgainstTheModel(write(File(dir, "plano.mp4"), ContainerFormat.MP4))
        checkSeeksAgainstTheModel(write(File(dir, "faststart.mp4"), ContainerFormat.MP4, fastStart = true))
    }

    @Test
    fun `seeking a fragmented mp4 lands where a scan of the whole track would, across checkpoints`() {
        checkSeeksAgainstTheModel(write(File(dir, "fragmentado.mp4"), ContainerFormat.MP4, fragmented = true))
    }

    /**
     * Lo que justifica el índice: abrir no reserva memoria por muestra. Con un millón de muestras,
     * el mapa completo de la 3.0.0 eran 37 MB solo en sus columnas; aquí la apertura entera tiene
     * que quedarse en una fracción, y la lectura completa tiene que seguir dando todas las muestras.
     */
    @Test
    fun `opening a file with a million samples does not allocate per sample`() {
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(threads != null, "este JDK no mide la memoria por hilo")
        val samples = 1_000_000
        for ((name, fragmented) in listOf("millon.mp4" to false, "millon-fragmentado.mp4" to true)) {
            val file = File(dir, name)
            val muxer = MkvKotlin.createMuxer(file, ContainerFormat.MP4, mp4Fragmented = fragmented)
            val id = muxer.addTrack(
                TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2, codecPrivate = AacConfig.build(48000, 2)),
            )
            muxer.start()
            val payload = byteArrayOf(1)
            for (i in 0 until samples) muxer.writePacket(MediaPacket(id, payload, i * 1_000L, i * 1_000L, true, 1_000))
            muxer.stop()

            Mp4Demuxer(file).close()
            val before = threads!!.currentThreadAllocatedBytes
            val demuxer = Mp4Demuxer(file)
            val allocated = threads.currentThreadAllocatedBytes - before
            demuxer.use {
                assertTrue(allocated < 8L * 1024 * 1024, "$name: abrir reservó ${allocated / 1024} KiB para $samples muestras")
                assertEquals(500_000_000L, it.seekTo(500_000_000L), "$name: seek a la mitad")
                assertEquals(500_000_000L, it.readPacket()?.ptsUs)
                it.seekTo(0)
                var count = 0
                var last = -1_000L
                while (true) {
                    val packet = it.readPacket() ?: break
                    assertEquals(last + 1_000, packet.ptsUs, "$name: muestra $count fuera de orden")
                    last = packet.ptsUs
                    count++
                }
                assertEquals(samples, count, "$name: no se leyeron todas las muestras")
            }
        }
    }

    /**
     * Encontrado al comparar contra la 3.0.0 sobre archivos mutados: los puntos de control de fMP4
     * marcaban «DTS aún sin conocer» con `Long.MIN_VALUE`, y un `tfdt` dañado puede dar justo ese
     * DTS a la primera muestra de una pista. Esa muestra pasaba a parecer posterior a todo y
     * `seekTo(0)` la entregaba en vez de saltarla.
     */
    @Test
    fun `a track whose first decode time is the minimum long is still sought correctly`() {
        val file = write(File(dir, "tfdt.mp4"), ContainerFormat.MP4, fragmented = true)
        val audioId = Mp4Demuxer(file).use { demuxer -> demuxer.tracks.first { it is TrackInfo.Audio }.id }
        val bytes = file.readBytes()
        fun typeAt(i: Int) = String(bytes, i, 4, Charsets.ISO_8859_1)
        fun u32At(i: Int) = (0 until 4).fold(0) { v, k -> (v shl 8) or (bytes[i + k].toInt() and 0xFF) }
        val tfhd = (0 until bytes.size - 12).first { typeAt(it) == "tfhd" && u32At(it + 8) == audioId }
        val tfdt = (tfhd until bytes.size - 4).first { typeAt(it) == "tfdt" }
        assertEquals(1, bytes[tfdt + 4].toInt(), "se esperaba un tfdt de versión 1")
        for (k in 0 until 8) bytes[tfdt + 8 + k] = if (k == 0) 0x80.toByte() else 0
        val broken = File(dir, "tfdt-minimo.mp4").apply { writeBytes(bytes) }

        val (tracks, samples) = readAll(broken)
        val audio = samples.getValue(tracks.first { it is TrackInfo.Audio }.id)
        assertEquals(Long.MIN_VALUE, audio.first().dts, "el parche no produjo el DTS mínimo")
        Mp4Demuxer(broken).use { demuxer ->
            for (t in listOf(0L, 1L, 500_000L, 2_000_000L)) {
                val (pts, positions) = expectedSeek(tracks, samples, t)
                assertEquals(pts, demuxer.seekTo(t))
                val actual = (0 until 8).mapNotNull { demuxer.readPacket()?.let { p -> "${p.trackId} ${p.ptsUs} ${p.dtsUs}" } }
                assertEquals(expectedNext(tracks, samples, positions, 8), actual, "paquetes tras seekTo($t)")
            }
        }
    }
}
