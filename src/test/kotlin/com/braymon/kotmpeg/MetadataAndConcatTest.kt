package com.braymon.kotmpeg

import com.braymon.kotmpeg.audio.PcmMixer
import com.braymon.kotmpeg.audio.PcmResampler
import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ColorInfo
import com.braymon.kotmpeg.model.HdrStaticInfo
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetadataAndConcatTest {

    @TempDir
    lateinit var dir: File

    private val avcC = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    private val hdr = HdrStaticInfo(
        redX = 0.708, redY = 0.292, greenX = 0.170, greenY = 0.797,
        blueX = 0.131, blueY = 0.046, whiteX = 0.3127, whiteY = 0.3290,
        maxMasteringLuminance = 1000.0, minMasteringLuminance = 0.005,
        maxContentLightLevel = 1000, maxFrameAverageLightLevel = 400,
    )

    private fun hdrVideoTrack(rotation: Int) = TrackInfo.Video(
        codec = VideoCodec.H264, width = 320, height = 240, frameRate = 30.0,
        rotationDegrees = rotation, color = ColorInfo.hdr10(hdr), codecPrivate = avcC,
    )

    private fun writeOneVideoTrack(file: File, track: TrackInfo.Video, packets: Int = 10, startUs: Long = 0) {
        val muxer = MkvKotlin.createMuxer(file)
        val id = muxer.addTrack(track)
        muxer.start()
        for (i in 0 until packets) {
            val data = NalUnits.joinLengthPrefixed(listOf(ByteArray(50) { i.toByte() }))
            muxer.writePacket(
                MediaPacket(id, data, startUs + i * 33_333L, startUs + i * 33_333L, i % 5 == 0, 33_333),
            )
        }
        muxer.stop()
    }

    private fun assertMetadataSurvives(file: File) {
        val demuxer = MkvKotlin.openDemuxer(file)
        val video = demuxer.tracks.single() as TrackInfo.Video
        assertEquals(90, video.rotationDegrees, "rotation in ${file.extension}")
        val color = assertNotNull(video.color, "color info in ${file.extension}")
        assertEquals(ColorInfo.PRIMARIES_BT2020, color.primaries)
        assertEquals(ColorInfo.TRANSFER_PQ, color.transfer)
        assertEquals(ColorInfo.MATRIX_BT2020_NCL, color.matrix)
        val gotHdr = assertNotNull(color.hdr, "hdr static info in ${file.extension}")
        assertTrue(abs(gotHdr.redX - hdr.redX) < 0.0001, "mastering red x")
        assertTrue(abs(gotHdr.maxMasteringLuminance - 1000.0) < 0.01, "max luminance")
        assertEquals(1000, gotHdr.maxContentLightLevel)
        assertEquals(400, gotHdr.maxFrameAverageLightLevel)
        demuxer.close()
    }

    @Test
    fun `rotation and hdr color survive mkv round trip`() {
        val file = File(dir, "meta.mkv")
        writeOneVideoTrack(file, hdrVideoTrack(rotation = 90))
        assertMetadataSurvives(file)
    }

    @Test
    fun `rotation and hdr color survive mp4 round trip`() {
        val file = File(dir, "meta.mp4")
        writeOneVideoTrack(file, hdrVideoTrack(rotation = 90))
        assertMetadataSurvives(file)
    }

    @Test
    fun `rotation and hdr color survive cross-container remux`() {
        val mkv = File(dir, "meta2.mkv")
        writeOneVideoTrack(mkv, hdrVideoTrack(rotation = 90))
        val mp4 = File(dir, "meta2.mp4")
        MkvKotlin.remux(mkv, mp4)
        assertMetadataSurvives(mp4)
        val back = File(dir, "meta3.mkv")
        MkvKotlin.remux(mp4, back)
        assertMetadataSurvives(back)
    }

    @Test
    fun `all rotations round trip in mp4`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val file = File(dir, "rot$rotation.mp4")
            writeOneVideoTrack(
                file,
                TrackInfo.Video(codec = VideoCodec.H264, width = 320, height = 240,
                                rotationDegrees = rotation, codecPrivate = avcC),
            )
            val demuxer = MkvKotlin.openDemuxer(file)
            assertEquals(rotation, (demuxer.tracks.single() as TrackInfo.Video).rotationDegrees, "rotation $rotation")
            demuxer.close()
        }
    }

    @Test
    fun `concat joins segments with shifted timestamps`() {
        val segmentFrames = 10
        val segments = (0 until 3).map { s ->
            File(dir, "seg$s.mkv").also {
                writeOneVideoTrack(it, hdrVideoTrack(rotation = 90), packets = segmentFrames)
            }
        }
        val out = File(dir, "joined.mp4")
        val written = MkvKotlin.concat(segments, out)
        assertEquals(3L * segmentFrames, written)

        val demuxer = MkvKotlin.openDemuxer(out)
        var count = 0
        var lastPts = -1L
        var maxPts = 0L
        while (true) {
            val p = demuxer.readPacket() ?: break
            count++
            assertTrue(p.ptsUs > lastPts, "pts strictly increasing across segments")
            lastPts = p.ptsUs
            maxPts = maxOf(maxPts, p.ptsUs)
        }
        demuxer.close()
        assertEquals(3 * segmentFrames, count)
        assertTrue(maxPts > 2 * segmentFrames * 33_333L, "timestamps shifted (max=$maxPts)")
    }

    @Test
    fun `concat rejects incompatible segments`() {
        val a = File(dir, "ca.mkv").also { writeOneVideoTrack(it, hdrVideoTrack(90)) }
        val b = File(dir, "cb.mkv").also {
            writeOneVideoTrack(
                it,
                TrackInfo.Video(codec = VideoCodec.H264, width = 640, height = 480, codecPrivate = avcC),
            )
        }
        val result = runCatching { MkvKotlin.concat(listOf(a, b), File(dir, "cc.mkv")) }
        assertTrue(result.isFailure, "dimension mismatch must be rejected")
    }

    @Test
    fun `audio only concat`() {
        val asc = AacConfig.build(48000, 2)
        fun segment(name: String): File {
            val f = File(dir, name)
            val muxer = MkvKotlin.createMuxer(f)
            val id = muxer.addTrack(TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000,
                                                    channelCount = 2, codecPrivate = asc))
            muxer.start()
            for (i in 0 until 50) {
                muxer.writePacket(MediaPacket(id, ByteArray(32) { i.toByte() },
                                              i * 21_333L, i * 21_333L, true, 21_333))
            }
            muxer.stop()
            return f
        }
        val out = File(dir, "audio-joined.mkv")
        val written = MkvKotlin.concat(listOf(segment("a1.mkv"), segment("a2.mkv")), out)
        assertEquals(100L, written)
        val demuxer = MkvKotlin.openDemuxer(out)
        assertTrue(demuxer.durationUs > 2_000_000, "joined duration ${demuxer.durationUs}")
        demuxer.close()
    }
}

class AudioDspTest {

    @Test
    fun `resampler converges to expected length and preserves a sine`() {
        val inRate = 44100
        val outRate = 48000
        val resampler = PcmResampler(inRate, outRate, channels = 1)
        val freq = 440.0
        var produced = 0
        var maxError = 0.0
        var inputFrames = 0
        var t = 0
        while (t < inRate) {
            val chunk = minOf(997, inRate - t)
            val input = ShortArray(chunk) { i ->
                (Math.sin(2 * Math.PI * freq * (t + i) / inRate) * 16000).toInt().toShort()
            }
            val out = resampler.resample(input)
            for ((k, sample) in out.withIndex()) {
                val outIndex = produced + k
                val expected = Math.sin(2 * Math.PI * freq * outIndex / outRate) * 16000
                maxError = maxOf(maxError, Math.abs(sample - expected))
            }
            produced += out.size
            t += chunk
            inputFrames += chunk
        }
        val expectedFrames = inputFrames.toLong() * outRate / inRate
        assertTrue(Math.abs(produced - expectedFrames) <= 2, "length $produced vs $expectedFrames")
        assertTrue(maxError < 320, "max error $maxError")
    }

    @Test
    fun `passthrough resampler returns input`() {
        val resampler = PcmResampler(48000, 48000, 2)
        val input = ShortArray(96) { it.toShort() }
        assertTrue(resampler.resample(input) === input)
    }

    @Test
    fun `mixer saturates and applies gains`() {
        val a = shortArrayOf(30000, -30000, 100)
        val b = shortArrayOf(10000, -10000, 50)
        val mixed = PcmMixer.mix(listOf(a, b))
        assertEquals(32767, mixed[0].toInt())
        assertEquals(-32768, mixed[1].toInt())
        assertEquals(150, mixed[2].toInt())
        val gained = PcmMixer.mix(listOf(a, b), gains = listOf(0.5f, 0.5f))
        assertEquals(20000, gained[0].toInt())
        val uneven = PcmMixer.mix(listOf(shortArrayOf(10), shortArrayOf(1, 2, 3)))
        assertEquals(11, uneven[0].toInt())
        assertEquals(2, uneven[1].toInt())
    }

    @Test
    fun `channel conversion`() {
        val stereo = shortArrayOf(100, 200, -50, 50)
        assertTrue(PcmMixer.stereoToMono(stereo).contentEquals(shortArrayOf(150, 0)))
        assertTrue(PcmMixer.monoToStereo(shortArrayOf(7)).contentEquals(shortArrayOf(7, 7)))
        val same = shortArrayOf(1, 2)
        assertTrue(PcmMixer.convertChannels(same, 2, 2) === same)
    }
}
