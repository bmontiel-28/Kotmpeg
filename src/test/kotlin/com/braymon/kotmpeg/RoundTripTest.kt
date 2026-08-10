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
import java.io.RandomAccessFile
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Muxes synthetic packets and reads them back, for both containers.
 */
class RoundTripTest {

    @TempDir
    lateinit var dir: File

    private val fakeSps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)
    private val fakePps = byteArrayOf(0x68, 0x11, 0x22)
    private val avcC = NalUnits.buildAvcC(listOf(fakeSps), listOf(fakePps))

    private fun videoTrack() = TrackInfo.Video(
        codec = VideoCodec.H264, width = 320, height = 240, frameRate = 30.0, codecPrivate = avcC,
    )

    private fun audioTrack() = TrackInfo.Audio(
        codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2,
        codecPrivate = AacConfig.build(48000, 2),
    )

    /** 30 fps video with a B-frame pattern (IBBP..), pts != dts, plus interleaved audio. */
    private data class Synthetic(val packets: List<MediaPacket>, val videoId: Int, val audioId: Int)

    private fun writeSynthetic(muxer: Muxer, withDts: Boolean): Synthetic {
        val rnd = Random(7)
        val videoId = muxer.addTrack(videoTrack())
        val audioId = muxer.addTrack(audioTrack())
        muxer.start()
        val packets = ArrayList<MediaPacket>()

        val frameUs = 33_333L
        val ptsByDecodeIndex = longArrayOf(0, 3, 1, 2)
        var audioPts = 0L
        for (g in 0 until 8) {
            for (k in 0 until 4) {
                val decodeIndex = g * 4 + k
                val pts = (g * 4 + ptsByDecodeIndex[k]) * frameUs
                val data = ByteArray(64 + rnd.nextInt(64))
                rnd.nextBytes(data)
                data[0] = decodeIndex.toByte()
                val packet = MediaPacket(
                    trackId = videoId,
                    data = NalUnits.joinLengthPrefixed(listOf(data)),
                    ptsUs = pts,
                    dtsUs = if (withDts) decodeIndex * frameUs else pts,
                    isKeyFrame = k == 0 && g % 4 == 0,
                    durationUs = frameUs,
                )
                packets.add(packet)
                muxer.writePacket(packet)
                while (audioPts <= pts) {
                    val adata = ByteArray(32)
                    rnd.nextBytes(adata)
                    val apkt = MediaPacket(audioId, adata, audioPts, audioPts, isKeyFrame = true, durationUs = 21_333)
                    packets.add(apkt)
                    muxer.writePacket(apkt)
                    audioPts += 21_333
                }
            }
        }
        muxer.stop()
        return Synthetic(packets, videoId, audioId)
    }

    /**
     * [ptsToleranceUs]: MKV timestamps quantize to 1 ms (±500 us); MP4 to the 90 kHz media
     * timescale (±12 us) - same quantization FFmpeg applies.
     */
    private fun verify(file: File, synthetic: Synthetic, ptsToleranceUs: Long) {
        val demuxer = MkvKotlin.openDemuxer(file)
        val tracks = demuxer.tracks
        assertEquals(2, tracks.size, "expected 2 tracks")
        val video = tracks.filterIsInstance<TrackInfo.Video>().single()
        val audio = tracks.filterIsInstance<TrackInfo.Audio>().single()
        assertEquals(VideoCodec.H264, video.codec)
        assertEquals(320, video.width)
        assertEquals(240, video.height)
        assertEquals(AudioCodec.AAC, audio.codec)
        assertEquals(48000, audio.sampleRate)
        assertEquals(2, audio.channelCount)
        assertTrue(video.codecPrivate!!.contentEquals(avcC), "avcC preserved")

        val expectedVideo = synthetic.packets.filter { it.trackId == synthetic.videoId }
        val expectedAudio = synthetic.packets.filter { it.trackId == synthetic.audioId }
        val gotVideo = ArrayList<MediaPacket>()
        val gotAudio = ArrayList<MediaPacket>()
        while (true) {
            val p = demuxer.readPacket() ?: break
            when (p.trackId) {
                video.id -> gotVideo.add(p)
                audio.id -> gotAudio.add(p)
            }
        }
        demuxer.close()

        assertEquals(expectedVideo.size, gotVideo.size, "video packet count")
        assertEquals(expectedAudio.size, gotAudio.size, "audio packet count")

        for (i in expectedVideo.indices) {
            val exp = expectedVideo[i]
            val got = gotVideo[i]
            assertTrue(exp.data.contentEquals(got.data), "video payload $i intact")
            assertEquals(exp.isKeyFrame, got.isKeyFrame, "keyframe flag $i")
            assertTrue(
                Math.abs(exp.ptsUs - got.ptsUs) <= ptsToleranceUs,
                "video pts $i within tolerance (exp=${exp.ptsUs} got=${got.ptsUs})",
            )
        }
        for (i in expectedAudio.indices) {
            assertTrue(expectedAudio[i].data.contentEquals(gotAudio[i].data), "audio payload $i intact")
        }
    }

    @Test
    fun `mkv round trip`() {
        val file = File(dir, "test.mkv")
        val synthetic = writeSynthetic(MkvKotlin.createMuxer(file), withDts = false)
        assertEquals(ContainerFormat.MKV, MkvKotlin.detectFormat(file))
        verify(file, synthetic, ptsToleranceUs = 500)
    }

    @Test
    fun `mp4 round trip with explicit dts`() {
        val file = File(dir, "test.mp4")
        val synthetic = writeSynthetic(MkvKotlin.createMuxer(file), withDts = true)
        assertEquals(ContainerFormat.MP4, MkvKotlin.detectFormat(file))
        verify(file, synthetic, ptsToleranceUs = 12)
    }

    @Test
    fun `mp4 round trip with derived dts`() {
        val file = File(dir, "test-derived.mp4")
        val synthetic = writeSynthetic(MkvKotlin.createMuxer(file), withDts = false)
        verify(file, synthetic, ptsToleranceUs = 12)

        val demuxer = MkvKotlin.openDemuxer(file)
        val videoId = demuxer.tracks.first { it is TrackInfo.Video }.id
        var lastDts = Long.MIN_VALUE
        while (true) {
            val p = demuxer.readPacket() ?: break
            if (p.trackId != videoId) continue
            assertTrue(p.dtsUs >= lastDts, "dts monotonic")
            assertTrue(p.dtsUs <= p.ptsUs, "dts <= pts")
            lastDts = p.dtsUs
        }
        demuxer.close()
    }

    @Test
    fun `mp4 faststart places moov before mdat and stays readable`() {
        val file = File(dir, "faststart.mp4")
        val synthetic = writeSynthetic(
            MkvKotlin.createMuxer(file, mp4FastStart = true),
            withDts = true,
        )
        val order = ArrayList<String>()
        RandomAccessFile(file, "r").use { raf ->
            var pos = 0L
            while (pos + 8 <= raf.length()) {
                raf.seek(pos)
                var size = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
                if (size == 1L) size = raf.readLong()
                order.add(type)
                pos += size
            }
        }
        assertEquals(listOf("ftyp", "moov", "mdat"), order)
        verify(file, synthetic, ptsToleranceUs = 12)
    }

    @Test
    fun `fragmented mp4 round trip with b-frames`() {
        val file = File(dir, "frag.mp4")
        val synthetic = writeSynthetic(
            MkvKotlin.createMuxer(file, mp4Fragmented = true),
            withDts = false,
        )
        val order = ArrayList<String>()
        RandomAccessFile(file, "r").use { raf ->
            var pos = 0L
            while (pos + 8 <= raf.length()) {
                raf.seek(pos)
                var size = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
                if (size == 1L) size = raf.readLong()
                order.add(type)
                pos += size
            }
        }
        assertEquals(listOf("ftyp", "moov"), order.take(2))
        assertEquals("mfra", order.last())
        assertTrue(order.count { it == "moof" } > 1, "expected multiple fragments, got $order")
        assertEquals(order.count { it == "moof" }, order.count { it == "mdat" })

        verify(file, synthetic, ptsToleranceUs = 12)
    }

    @Test
    fun `fragmented mp4 audio only uses duration-based fragments`() {
        val file = File(dir, "frag-audio.mp4")
        val muxer = MkvKotlin.createMuxer(file, mp4Fragmented = true)
        val audioId = muxer.addTrack(audioTrack())
        muxer.start()
        val rnd = Random(3)
        val expected = ArrayList<MediaPacket>()
        var pts = 0L
        repeat(300) {
            val data = ByteArray(64).also { rnd.nextBytes(it) }
            val p = MediaPacket(audioId, data, pts, pts, isKeyFrame = true, durationUs = 21_333)
            expected.add(p)
            muxer.writePacket(p)
            pts += 21_333
        }
        muxer.stop()

        val demuxer = MkvKotlin.openDemuxer(file)
        val track = demuxer.tracks.single() as TrackInfo.Audio
        assertEquals(AudioCodec.AAC, track.codec)
        val got = ArrayList<MediaPacket>()
        while (true) got.add(demuxer.readPacket() ?: break)
        demuxer.close()
        assertEquals(expected.size, got.size)
        for (i in expected.indices) {
            assertTrue(expected[i].data.contentEquals(got[i].data), "payload $i intact")
            assertTrue(Math.abs(expected[i].ptsUs - got[i].ptsUs) <= 21, "pts $i within one 48kHz tick")
        }
    }

    @Test
    fun `remux mkv to mp4 and back`() {
        val mkv = File(dir, "a.mkv")
        val synthetic = writeSynthetic(MkvKotlin.createMuxer(mkv), withDts = false)
        val mp4 = File(dir, "b.mp4")
        MkvKotlin.remux(mkv, mp4)
        val back = File(dir, "c.mkv")
        MkvKotlin.remux(mp4, back)
        verify(back, synthetic, ptsToleranceUs = 500)
    }

    @Test
    fun `mkv seek uses cues`() {
        val file = File(dir, "seek.mkv")
        writeSynthetic(MkvKotlin.createMuxer(file), withDts = false)
        val demuxer = MkvKotlin.openDemuxer(file)
        val pos = demuxer.seekTo(2_000_000)
        assertTrue(pos in 0..2_000_000, "seek position $pos")
        val p = demuxer.readPacket()
        assertTrue(p != null)
        demuxer.close()
    }
}
