package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.OpusConfig
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Una pista que llega sin `codecPrivate` sale igual en MKV que en MP4.
 *
 * El MP4 generaba la configuración del audio y rechazaba el vídeo sin `avcC`/`hvcC`; el MKV escribía
 * la pista tal cual, sin `CodecPrivate`. FFmpeg lo tolera, pero Media3/ExoPlayer lanza "Missing
 * CodecPrivate" y el extractor nativo de Android descarta la pista AAC: el archivo se grababa sin
 * error y no se reproducía en el móvil.
 */
class MissingCodecPrivateTest {

    @TempDir
    lateinit var dir: File

    private fun writeAudio(file: File, format: ContainerFormat, fragmented: Boolean, track: TrackInfo.Audio): TrackInfo.Audio {
        MkvKotlin.createMuxer(file, format, mp4Fragmented = fragmented).use { muxer ->
            val id = muxer.addTrack(track)
            muxer.start()
            for (i in 0 until 5) muxer.writePacket(MediaPacket(id, ByteArray(16), ptsUs = i * 20_000L))
        }
        return MkvKotlin.openDemuxer(file).use { it.tracks.single() as TrackInfo.Audio }
    }

    private val containers = listOf(
        Triple("mkv", ContainerFormat.MKV, false),
        Triple("mp4", ContainerFormat.MP4, false),
        Triple("frag.mp4", ContainerFormat.MP4, true),
    )

    @Test
    fun `aac without codecPrivate gets the same default config in every container`() {
        val expected = AacConfig.build(44_100, 1)
        for ((suffix, format, fragmented) in containers) {
            val track = TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 44_100, channelCount = 1)
            val read = writeAudio(File(dir, "aac.$suffix"), format, fragmented, track)
            assertContentEquals(expected, assertNotNull(read.codecPrivate, "$suffix sin CodecPrivate"), suffix)
        }
    }

    @Test
    fun `opus without codecPrivate takes its pre skip from the declared delay`() {
        for ((suffix, format, fragmented) in containers) {
            val track = TrackInfo.Audio(codec = AudioCodec.OPUS, sampleRate = 48_000, channelCount = 2, codecDelayUs = 10_000)
            val read = writeAudio(File(dir, "opus.$suffix"), format, fragmented, track)
            val head = OpusConfig.parseOpusHead(assertNotNull(read.codecPrivate, "$suffix sin OpusHead"))
            assertEquals(480, head.preSkip, "$suffix: 10 ms a 48 kHz son 480 muestras")
            assertEquals(2, head.channelCount, suffix)
        }
    }

    @Test
    fun `an mkv video track without avcC is rejected like in mp4`() {
        val video = TrackInfo.Video(codec = VideoCodec.H264, width = 64, height = 64)
        val mkv = MkvMuxer(File(dir, "video.mkv"))
        val error = assertFailsWith<IllegalArgumentException> { mkv.addTrack(video) }
        assertEquals(true, error.message?.contains("codecPrivate"), error.message)
        mkv.close()
        MkvKotlin.createMuxer(File(dir, "video.mp4")).use { mp4 ->
            assertFailsWith<IllegalArgumentException> { mp4.addTrack(video) }
        }
    }

    @Test
    fun `an opus track of more than two channels without a mapping table fails clearly`() {
        val surround = TrackInfo.Audio(codec = AudioCodec.OPUS, sampleRate = 48_000, channelCount = 6)
        MkvMuxer(File(dir, "surround.mkv")).use { muxer ->
            val error = assertFailsWith<IllegalArgumentException> { muxer.addTrack(surround) }
            assertEquals(true, error.message?.contains("mapeo"), error.message)
        }
    }
}
