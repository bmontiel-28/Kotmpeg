package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import com.braymon.kotmpeg.mp4.Mp4Muxer
import com.braymon.kotmpeg.mp4.SampleEntries
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * Idioma, nombre y pista predeterminada sobreviven a MP4 en los dos sentidos.
 *
 * Los muxers MP4 escribían el `mdhd` siempre con `und` y el demuxer no leía ni el idioma, ni el
 * `udta`/`name` ni el bit `track_enabled` del `tkhd`. El resultado era que un MKV con audio en
 * dos idiomas pasado a MP4 y vuelta salía con las dos pistas en `und`, sin nombre y ambas
 * predeterminadas: cada reproductor elegía la que quisiera.
 */
class TrackLanguageRoundTripTest {

    @TempDir
    lateinit var dir: File

    private val avcC = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    private val tracks = listOf(
        TrackInfo.Video(codec = VideoCodec.H264, width = 320, height = 240, codecPrivate = avcC, language = "jpn"),
        TrackInfo.Audio(
            codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2, codecPrivate = AacConfig.build(48000, 2),
            language = "spa", name = "Español", default = true,
        ),
        TrackInfo.Audio(
            codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2, codecPrivate = AacConfig.build(48000, 2),
            language = "eng", name = "Commentary", default = false,
        ),
    )

    private fun write(muxer: Muxer) {
        val ids = tracks.map { muxer.addTrack(it) }
        muxer.start()
        for (step in 0 until 5) {
            for (id in ids) {
                muxer.writePacket(MediaPacket(id, ByteArray(16) { step.toByte() }, ptsUs = step * 40_000L, isKeyFrame = true))
            }
        }
        muxer.stop()
    }

    private fun metadataOf(file: File): List<Triple<String, String?, Boolean>> =
        MkvKotlin.openDemuxer(file).use { demuxer -> demuxer.tracks.map { Triple(it.language, it.name, it.default) } }

    private val expected = tracks.map { Triple(it.language, it.name, it.default) }

    @Test
    fun `plain and fragmented mp4 keep language name and default flag`() {
        val plain = File(dir, "plano.mp4").also { write(Mp4Muxer(it)) }
        val fragmented = File(dir, "frag.mp4").also { write(FragmentedMp4Muxer(it)) }
        assertEquals(expected, metadataOf(plain))
        assertEquals(expected, metadataOf(fragmented))
    }

    @Test
    fun `an mkv to mp4 to mkv remux keeps the track metadata`() {
        val source = File(dir, "origen.mkv").also { write(MkvMuxer(it)) }
        val middle = File(dir, "medio.mp4")
        val back = File(dir, "vuelta.mkv")
        MkvKotlin.remux(source, middle)
        MkvKotlin.remux(middle, back)
        assertEquals(expected, metadataOf(back))
    }

    @Test
    fun `language codes are packed as iso 639-2 and malformed ones fall back to und`() {
        assertEquals(0x55C4, SampleEntries.packLanguage("und"))
        assertEquals("spa", SampleEntries.unpackLanguage(SampleEntries.packLanguage("SPA")))
        for (malformed in listOf("", "es", "español", "e1g", "en-US")) {
            assertEquals(0x55C4, SampleEntries.packLanguage(malformed), malformed)
        }
        assertEquals("und", SampleEntries.unpackLanguage(0), "un código de idioma Macintosh no es ISO")
    }
}
