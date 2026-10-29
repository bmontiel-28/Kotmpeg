package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.pipeline.Remuxer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `concat` con entradas que no empiezan en cero, y con muchas entradas: los dos casos en que la
 * concatenación se separaba del demuxer `concat` de FFmpeg que dice imitar.
 */
class ApiConcatTest {

    @TempDir
    lateinit var dir: File

    private val video = TrackInfo.Video(VideoCodec.H264, 320, 240) {
        frameRate = 30.0
        codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22)))
    }.withId(1)
    private val audio = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) }.withId(2)

    /** Un demuxer sobre una lista de paquetes ya hecha: la entrada mínima de `Remuxer.concat`. */
    private class ListDemuxer(
        override val tracks: List<TrackInfo>,
        packets: List<MediaPacket>,
        override val durationUs: Long,
    ) : Demuxer {
        private val remaining = packets.iterator()

        override fun readPacket(): MediaPacket? = if (remaining.hasNext()) remaining.next() else null

        override fun seekTo(timestampUs: Long): Long = throw UnsupportedOperationException()

        override fun close(): Unit = Unit
    }

    /**
     * Un segundo de vídeo y audio que empieza en [startUs], como los trozos de `ffmpeg -f segment`
     * sin `-reset_timestamps`: cada uno conserva los tiempos que tenía en la grabación entera. Su
     * duración declarada es la del trozo, no el instante en que acaba.
     */
    private fun segment(startUs: Long): ListDemuxer {
        val packets = ArrayList<MediaPacket>()
        for (i in 0 until 30) {
            val pts = startUs + i * 33_333L
            packets += MediaPacket(1, NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x65, i.toByte()))), pts, isKeyFrame = i == 0, durationUs = 33_333)
            while (packets.count { it.trackId == 2 } * 21_333L <= i * 33_333L) {
                val k = packets.count { it.trackId == 2 }
                packets += MediaPacket(2, ByteArray(8), startUs + k * 21_333L, isKeyFrame = true, durationUs = 21_333)
            }
        }
        return ListDemuxer(listOf(video, audio), packets, durationUs = 1_000_000)
    }

    /** Concatena [demuxers] con `Remuxer.concat` en un archivo [format] en memoria y devuelve el PTS de cada paquete, por pista. */
    private fun concatenated(demuxers: List<Demuxer>, format: ContainerFormat): Map<Int, List<Long>> {
        val channel = RecordingFileChannel()
        Kotmpeg.createMuxer(SeekableOutput(channel), format) { creationTimeMillis = null }.use { muxer ->
            Remuxer.concat(demuxers, muxer)
        }
        return Kotmpeg.openDemuxer(memoryInput(channel.bytes())).use { demuxer ->
            generateSequence { demuxer.readPacket() }.groupBy({ it.trackId }, { it.ptsUs })
        }
    }

    /**
     * Tres trozos que empiezan en 10, 11 y 12 s. Hasta la 4.0 a cada uno se le sumaba el final
     * absoluto del anterior sin restarle su propio principio, y entre trozo y trozo quedaba un hueco
     * de diez segundos, luego de once… Ahora se empalman como en FFmpeg: sin hueco, y con el audio de
     * cada trozo empezando con su vídeo.
     */
    @Test
    fun `concat subtracts the start of each input so segments that do not start at zero join without gaps`() {
        for (format in ContainerFormat.entries) {
            val starts = listOf(10_000_000L, 11_000_000L, 12_000_000L)
            val tracks = concatenated(starts.map { segment(it) }, format)
            val videoPts = tracks.getValue(1).sorted()
            assertEquals(90, videoPts.size, "$format: fotogramas")
            val gaps = videoPts.zipWithNext { a, b -> b - a }
            assertTrue(gaps.all { it in 30_000..36_000 }, "$format: huecos entre fotogramas ${gaps.filter { it !in 30_000..36_000 }}")
            assertTrue(videoPts.last() < 3_000_000, "$format: el último fotograma está en ${videoPts.last()} µs")
            val audioPts = tracks.getValue(2).sorted()
            val firstAudioOfEachSegment = audioPts.chunked(audioPts.size / 3).map { it.first() }
            val firstVideoOfEachSegment = videoPts.chunked(30).map { it.first() }
            for ((index, pair) in firstVideoOfEachSegment.zip(firstAudioOfEachSegment).withIndex()) {
                assertTrue(abs(pair.first - pair.second) <= 1_000, "$format: el trozo $index empieza desalineado: $pair")
            }
        }
    }

    /**
     * Dos MKV con B-frames escritos sin `frameRate`, como los de una app que no conoce la cadencia:
     * sin `DefaultDuration` sus bloques no llevan duración, y al releerlos cada paquete trae
     * `durationUs = 0`. Son 31 fotogramas en orden de decodificación I P B B, así que el último P
     * —el de mayor PTS— llega tres fotogramas por delante del P anterior.
     *
     * La segunda entrada tiene que empezar un fotograma después del último de la primera, como dentro
     * de cada una. Contar ese final con el último salto hacia delante en orden de decodificación
     * dejaba en la unión un hueco de tres fotogramas: 100 ms a 30 fps en vez de 33.
     */
    @Test
    fun `mkv inputs with b-frames and no frame rate join one frame after the last`() {
        val noFrameRate = TrackInfo.Video(VideoCodec.H264, 320, 240) { codecPrivate = video.codecPrivate }
        val frames = 31
        val decodeOrder = listOf(0) + (1 until frames step 3).flatMap { listOf(it + 2, it, it + 1) }
        assertEquals((0 until frames).toList(), decodeOrder.sorted())
        fun write(file: File) = Kotmpeg.createMuxer(file) { creationTimeMillis = null }.use { muxer ->
            val v = muxer.addTrack(noFrameRate)
            muxer.start()
            for ((index, frame) in decodeOrder.withIndex()) {
                val data = NalUnits.joinLengthPrefixed(listOf(byteArrayOf(if (frame == 0) 0x65 else 0x41, frame.toByte())))
                muxer.writePacket(MediaPacket(v, data, frame * 33_333L, (index - 2) * 33_333L, isKeyFrame = frame == 0))
            }
        }
        val inputs = (0 until 2).map { File(dir, "bframes$it.mkv").also(::write) }
        Kotmpeg.openDemuxer(inputs.first()).use { demuxer ->
            assertTrue(generateSequence { demuxer.readPacket() }.all { it.durationUs == 0L }, "los paquetes releídos traen duración")
        }
        val joined = File(dir, "bframes.mkv")
        Kotmpeg.concat(inputs, joined)
        val pts = Kotmpeg.openDemuxer(joined).use { demuxer -> generateSequence { demuxer.readPacket() }.map { it.ptsUs }.toList().sorted() }
        assertEquals(2 * frames, pts.size)
        val gaps = pts.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it in 33_000..34_000 }, "huecos entre fotogramas: ${gaps.withIndex().filter { it.value !in 33_000..34_000 }}")
    }

    private fun writeSegment(file: File) {
        Kotmpeg.createMuxer(file) { creationTimeMillis = null }.use { muxer ->
            val v = muxer.addTrack(video)
            muxer.start()
            for (i in 0 until 10) {
                muxer.writePacket(MediaPacket(v, NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x65, i.toByte()))), i * 33_333L, isKeyFrame = i == 0))
            }
        }
    }

    /**
     * Hasta la 4.0 `Kotmpeg.concat` abría todas las entradas antes de copiar la primera y las tenía
     * abiertas hasta el final: con un millar largo de segmentos, «Too many open files». Ahora, al
     * empezar a copiar cada entrada, es la única abierta.
     */
    @Test
    fun `concat keeps a single input open at a time`() {
        val inputs = (0 until 4).map { File(dir, "trozo$it.mkv").also(::writeSegment) }
        var copied = 0
        val openWhileCopying = ArrayList<String>()
        Kotmpeg.concat(inputs, File(dir, "unido.mkv"), onProgress = {
            if (copied % 10 == 0) {
                val current = copied / 10
                assertTrue(OpenFiles.isOpen(inputs[current]), "la entrada $current no está abierta mientras se copia: la medida no vale")
                for ((index, file) in inputs.withIndex()) {
                    if (index != current && OpenFiles.isOpen(file)) openWhileCopying += "la ${file.name} mientras se copia la $current"
                }
            }
            copied++
        })
        assertEquals(40, copied)
        assertEquals(emptyList(), openWhileCopying, "entradas abiertas a la vez")
        for (file in inputs) assertTrue(!OpenFiles.isOpen(file), "${file.name} quedó abierta")
    }

    /**
     * El caso real del primer test, con FFmpeg: `-f segment` sin `-reset_timestamps` deja cada trozo
     * con los tiempos de la grabación entera. Unidos tienen que durar lo que duraba esta, no el doble.
     */
    @Test
    fun `segments cut by ffmpeg without resetting timestamps join to the original duration`() {
        assumeTrue(ffmpegInstalled, "ffmpeg no está instalado")
        val pattern = File(dir, "seg%d.mkv")
        val (out, code) = runProcess(
            "ffmpeg", "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30:duration=4",
            "-c:v", "libx264", "-g", "30", "-f", "segment", "-segment_time", "1", pattern.absolutePath,
        )
        assertEquals(0, code, out)
        val segments = (0 until 10).map { File(dir, "seg$it.mkv") }.filter { it.isFile }
        assertTrue(segments.size >= 3, "FFmpeg dio ${segments.size} trozos")
        val joined = File(dir, "unido.mkv")
        Kotmpeg.concat(segments, joined)
        Kotmpeg.openDemuxer(joined).use { demuxer ->
            val pts = generateSequence { demuxer.readPacket() }.map { it.ptsUs }.toList().sorted()
            assertEquals(120, pts.size)
            assertTrue(pts.last() in 3_900_000..4_100_000, "el último fotograma está en ${pts.last()} µs")
            val gaps = pts.zipWithNext { a, b -> b - a }
            assertTrue(gaps.all { it < 100_000 }, "huecos en la unión: ${gaps.filter { it >= 100_000 }}")
        }
    }
}
