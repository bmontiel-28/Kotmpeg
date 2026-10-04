package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `MkvKotlin.synchronizedMuxer` con dos hilos productores a la vez, que es como llegan los
 * paquetes en una app móvil: el codificador de vídeo y el de audio entregan cada uno desde su
 * propio callback. Sin la envoltura los muxers comparten buffer y posición de escritura, y dos
 * escrituras simultáneas se pisan.
 */
class SynchronizedMuxerTest {

    @TempDir
    lateinit var dir: File

    private val avcC = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    private fun writeFromTwoThreads(file: File, format: ContainerFormat, fragmented: Boolean): Pair<Int, Int> {
        val muxer = MkvKotlin.synchronizedMuxer(MkvKotlin.createMuxer(file, format, mp4Fragmented = fragmented))
        val videoId = muxer.addTrack(
            TrackInfo.Video(codec = VideoCodec.H264, width = 320, height = 240, frameRate = 30.0, codecPrivate = avcC),
        )
        val audioId = muxer.addTrack(
            TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2, codecPrivate = AacConfig.build(48000, 2)),
        )
        muxer.start()
        val videoFrames = 300
        val audioFrames = 470
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val video = pool.submit {
            start.await()
            for (i in 0 until videoFrames) {
                val payload = NalUnits.joinLengthPrefixed(listOf(ByteArray(700) { (i + it).toByte() }))
                muxer.writePacket(MediaPacket(videoId, payload, ptsUs = i * 33_333L, isKeyFrame = i % 30 == 0))
            }
        }
        val audio = pool.submit {
            start.await()
            for (i in 0 until audioFrames) {
                muxer.writePacket(MediaPacket(audioId, ByteArray(300) { (i * 3 + it).toByte() }, ptsUs = i * 21_333L))
            }
        }
        start.countDown()
        video.get(60, TimeUnit.SECONDS)
        audio.get(60, TimeUnit.SECONDS)
        pool.shutdown()
        muxer.stop()
        return videoFrames to audioFrames
    }

    @Test
    fun `two producer threads write a complete and readable file in every container`() {
        val cases = listOf(
            Triple("concurrente.mkv", ContainerFormat.MKV, false),
            Triple("concurrente.mp4", ContainerFormat.MP4, false),
            Triple("concurrente-frag.mp4", ContainerFormat.MP4, true),
        )
        for ((name, format, fragmented) in cases) {
            val file = File(dir, name)
            val (videoFrames, audioFrames) = writeFromTwoThreads(file, format, fragmented)
            MkvKotlin.openDemuxer(file).use { demuxer ->
                val packets = generateSequence { demuxer.readPacket() }.toList()
                val byTrack = packets.groupBy { it.trackId }
                val videoTrack = demuxer.tracks.first { it is TrackInfo.Video }.id
                val audioTrack = demuxer.tracks.first { it is TrackInfo.Audio }.id
                assertEquals(videoFrames, byTrack[videoTrack]?.size, "$name: vídeo incompleto")
                assertEquals(audioFrames, byTrack[audioTrack]?.size, "$name: audio incompleto")
                assertTrue(byTrack.getValue(videoTrack).all { it.data.size == 704 }, "$name: vídeo corrupto")
            }
        }
    }

    @Test
    fun `wrapping an already synchronized muxer returns the same instance`() {
        val muxer = MkvKotlin.synchronizedMuxer(MkvKotlin.createMuxer(File(dir, "x.mkv")))
        assertSame(muxer, MkvKotlin.synchronizedMuxer(muxer))
        muxer.close()
    }
}
