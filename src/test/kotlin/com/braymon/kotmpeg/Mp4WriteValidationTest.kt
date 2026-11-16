package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.Mp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **Una pista que el MP4 no puede describir se rechaza al registrarla, no al cerrar.**
 *
 * La entrada de muestra del audio (`mp4a` con su `esds`, `Opus` con su `dOps`) se construía por
 * primera vez en `stop()`. Un Opus de seis canales sin tabla de mapeo, un AAC de siete canales sin
 * `codecPrivate` o un `codecPrivate` Opus que no es una `OpusHead` pasaban `addTrack`, se grababa
 * una hora entera, y `stop()` lanzaba con el `mdat` ya escrito: quedaba un archivo con `ftyp` y
 * `mdat` y sin `moov`, que no se puede reproducir ni reparar. `MkvMuxer` ya los rechazaba en
 * `addTrack`.
 */
class Mp4WriteValidationTest {

    @TempDir
    lateinit var dir: File

    private val invalid = listOf(
        "opus 5.1 sin tabla de mapeo" to TrackInfo.Audio(AudioCodec.OPUS, 48_000, 6),
        "aac de 7 canales sin configuración" to TrackInfo.Audio(AudioCodec.AAC, 48_000, 7),
        "aac de 10 canales sin configuración" to TrackInfo.Audio(AudioCodec.AAC, 48_000, 10),
        "opus con un codecPrivate que no es OpusHead" to
            TrackInfo.Audio(AudioCodec.OPUS, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) },
    )

    @Test
    fun `an audio track the sample entry cannot describe is rejected by addTrack`() {
        for ((name, track) in invalid) {
            Mp4Muxer(File(dir, "invalida.mp4")).use { muxer ->
                assertFailsWith<IllegalArgumentException>(name) { muxer.addTrack(track) }
            }
        }
    }

    /**
     * El rechazo no deja el muxer a medias: la pista no se registra, la siguiente recibe el id 1 y
     * el archivo se cierra con su `moov`.
     */
    @Test
    fun `a rejected track leaves the muxer usable`() {
        val file = File(dir, "sigue.mp4")
        Mp4Muxer(file).use { muxer ->
            assertFailsWith<IllegalArgumentException> { muxer.addTrack(TrackInfo.Audio(AudioCodec.OPUS, 48_000, 6)) }
            val id = muxer.addTrack(TrackInfo.Audio(AudioCodec.OPUS, 48_000, 2))
            assertEquals(1, id)
            muxer.start()
            for (i in 0 until 5) muxer.writePacket(MediaPacket(id, ByteArray(20), i * 20_000L, isKeyFrame = true))
        }
        val track = Kotmpeg.openDemuxer(file).use { it.tracks.single() as TrackInfo.Audio }
        assertEquals(AudioCodec.OPUS to 2, track.codec to track.channelCount)
    }

    /**
     * Un hueco entre dos fotogramas que no cabe en los 32 bits del `stts` —14 h en la escala de
     * 90 kHz— se rechaza en `writePacket`, el muxer sigue admitiendo paquetes, y lo grabado se
     * cierra como siempre. Hasta la 4.1.0 se escribía truncado y sin error: 50 400 s se leían como
     * 2 678 s.
     */
    @Test
    fun `a gap that does not fit the 32 bit stts is rejected and the rest still closes`() {
        val avcC = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22)))
        fun frame(i: Int) = NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x65, i.toByte())))
        val file = File(dir, "hueco.mp4")
        Mp4Muxer(file).use { muxer ->
            val id = muxer.addTrack(TrackInfo.Video(VideoCodec.H264, 320, 240) { codecPrivate = avcC })
            muxer.start()
            for (i in 0 until 3) muxer.writePacket(MediaPacket(id, frame(i), i * 40_000L, isKeyFrame = i == 0))
            val gap = assertFailsWith<IllegalArgumentException> {
                muxer.writePacket(MediaPacket(id, frame(3), 50_400_000_000L, isKeyFrame = true))
            }
            assertTrue("stts" in gap.message.orEmpty(), "el error dice qué no cabe: ${gap.message}")
            assertFailsWith<IllegalArgumentException> {
                muxer.writePacket(MediaPacket(id, frame(3), 120_000L, isKeyFrame = false, durationUs = 50_400_000_000L))
            }
            muxer.writePacket(MediaPacket(id, frame(3), 120_000L, isKeyFrame = false))
        }
        val pts = Kotmpeg.openDemuxer(file).use { d -> generateSequence { d.readPacket() }.map { it.ptsUs }.toList() }
        assertEquals(listOf(0L, 40_000L, 80_000L, 120_000L), pts)
    }
}
