package com.braymon.kotmpeg

import com.braymon.kotmpeg.Mp4ReadFixtures.anyBox
import com.braymon.kotmpeg.Mp4ReadFixtures.u32
import com.braymon.kotmpeg.SharedMp4Headers.audioEntryRate
import com.braymon.kotmpeg.SharedMp4Headers.mdhd
import com.braymon.kotmpeg.SharedMp4Headers.timescale
import com.braymon.kotmpeg.codecconfig.OpusConfig
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Opus a menos de 48 kHz en MP4. Su encapsulado en ISOBMFF exige 48 000 en el `samplerate` de la
 * entrada de muestra y recomienda esa escala en el `mdhd`, porque Opus se decodifica siempre a 48
 * kHz; la frecuencia de entrada viaja en el `InputSampleRate` del `dOps`. Los dos muxers MP4
 * escribían en los dos sitios la frecuencia de la pista, y el demuxer la leía del `samplerate`.
 *
 * Ahora escriben 48 000 y el demuxer lee la del `dOps`, así que la ida y vuelta conserva la de la
 * pista. Para eso la `OpusHead` por defecto declara la frecuencia de la pista, también en MKV, que
 * por lo demás sigue escribiendo en `SamplingFrequency` la frecuencia de la pista.
 */
class SharedOpusSampleRateTest {

    @TempDir
    lateinit var dir: File

    private fun write(name: String, track: TrackInfo.Audio): File {
        val file = File(dir, name)
        val format = if (name.endsWith(".mkv")) ContainerFormat.MKV else ContainerFormat.MP4
        Kotmpeg.createMuxer(file, format) { fragmented = name.contains("frag") }.use { muxer ->
            val id = muxer.addTrack(track)
            muxer.start()
            for (i in 0 until 50) muxer.writePacket(MediaPacket(id, ByteArray(12) { (i + it).toByte() }, i * 20_000L, isKeyFrame = true))
        }
        return file
    }

    @Test
    fun `opus below 48 kHz declares 48 kHz in mp4 and reads back its own rate`() {
        val withoutHead = TrackInfo.Audio(AudioCodec.OPUS, 16_000, 1)
        val withHead = TrackInfo.Audio(AudioCodec.OPUS, 16_000, 1) { codecPrivate = OpusConfig.buildOpusHead(1, inputSampleRate = 16_000) }
        for ((label, track) in listOf("sin cabecera" to withoutHead, "con cabecera" to withHead)) {
            for (name in listOf("opus16.mp4", "opus16-frag.mp4")) {
                val file = write(name, track)
                val bytes = file.readBytes()
                assertEquals(48_000L, audioEntryRate(bytes, 0), "$label, $name: samplerate de la entrada")
                assertEquals(48_000L, timescale(bytes, mdhd(bytes, 0)), "$label, $name: escala del mdhd")
                assertEquals(16_000L, u32(bytes, anyBox(bytes, "dOps") + 12), "$label, $name: InputSampleRate del dOps")
                Kotmpeg.openDemuxer(file).use { demuxer ->
                    assertEquals(16_000, (demuxer.tracks.single() as TrackInfo.Audio).sampleRate, "$label, $name: frecuencia releída")
                    val times = generateSequence { demuxer.readPacket() }.map { it.ptsUs }.toList()
                    assertEquals(List(50) { it * 20_000L }, times, "$label, $name: tiempos")
                }
            }
        }
    }

    @Test
    fun `mkv keeps the track rate and its default OpusHead declares it`() {
        val file = write("opus16.mkv", TrackInfo.Audio(AudioCodec.OPUS, 16_000, 1))
        val read = Kotmpeg.openDemuxer(file).use { it.tracks.single() as TrackInfo.Audio }
        assertEquals(16_000, read.sampleRate, "SamplingFrequency")
        val head = OpusConfig.parseOpusHead(assertNotNull(read.codecPrivate, "sin OpusHead"))
        assertEquals(16_000, head.inputSampleRate, "frecuencia de entrada de la OpusHead por defecto")
    }
}
