package com.braymon.kotmpeg

import com.braymon.kotmpeg.SharedMp4Headers.Edit
import com.braymon.kotmpeg.SharedMp4Headers.edits
import com.braymon.kotmpeg.SharedMp4Headers.mdhd
import com.braymon.kotmpeg.SharedMp4Headers.timescale
import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.OpusConfig
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El cebado que declaran los tres muxers sale de un solo sitio, `AudioPriming`, con el orden de
 * preferencia de `TrackInfo.Audio.codecDelayUs`: en Opus, el `preSkip` de su `OpusHead`.
 *
 * Solo `MkvMuxer` lo miraba. Un Opus con una `OpusHead` de 312 muestras de `preSkip` y sin
 * `codecDelayUs` —como lo entrega más de un codificador— salía del MKV con 6,5 ms de `CodecDelay` y de
 * los dos MP4 sin lista de edición: el mismo audio sonaba 6,5 ms adelantado según el contenedor.
 */
class SharedAudioPrimingTest {

    @TempDir
    lateinit var dir: File

    private val opus = TrackInfo.Audio(AudioCodec.OPUS, 48_000, 2) { codecPrivate = OpusConfig.buildOpusHead(2, preSkip = 312) }

    /** Cinco segundos de paquetes de 20 ms de [track], en el contenedor de [name]. */
    private fun write(name: String, track: TrackInfo.Audio): File {
        val file = File(dir, name)
        val format = if (name.endsWith(".mkv")) ContainerFormat.MKV else ContainerFormat.MP4
        Kotmpeg.createMuxer(file, format) { fragmented = name.contains("frag") }.use { muxer ->
            val id = muxer.addTrack(track)
            muxer.start()
            for (i in 0 until 250) muxer.writePacket(MediaPacket(id, ByteArray(20) { (i + it).toByte() }, i * 20_000L, isKeyFrame = true))
        }
        return file
    }

    @Test
    fun `opus takes its priming from the OpusHead pre skip in all three muxers`() {
        for (name in listOf("opus.mkv", "opus.mp4", "opus-frag.mp4")) {
            val file = write(name, opus)
            val read = Kotmpeg.openDemuxer(file).use { it.tracks.single() as TrackInfo.Audio }
            assertEquals(6_500L, read.codecDelayUs, "$name: cebado releído")
            if (name.endsWith(".mp4")) {
                val bytes = file.readBytes()
                assertEquals(48_000L, timescale(bytes, mdhd(bytes, 0)), "$name: escala del mdhd")
                assertEquals(312L, edits(bytes, 0).single().mediaTime, "$name: la edición tiene que saltar el preSkip")
            }
        }
    }

    /** En los demás códecs manda `codecDelayUs`, como siempre. */
    @Test
    fun `aac takes its priming from codecDelayUs`() {
        val aac = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2); codecDelayUs = 21_333 }
        for (name in listOf("aac.mp4", "aac-frag.mp4")) {
            val bytes = write(name, aac).readBytes()
            assertEquals(Edit(4_979, 1_024), edits(bytes, 0).single(), "$name: edición")
        }
    }

    /**
     * FFmpeg salta el `preSkip` de un Opus en MP4 por su lista de edición. Sin ella, la duración que
     * daba eran los cinco segundos enteros de paquetes; ahora es la del audio que de verdad suena.
     */
    @Test
    fun `ffmpeg reads an opus mp4 without its pre skip`() {
        assumeTrue(ffmpegInstalled, "FFmpeg no está instalado")
        val file = write("ffprobe.mp4", opus)
        val (output, exit) = runProcess(
            "ffprobe", "-v", "quiet", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", file.absolutePath,
        )
        assertEquals(0, exit, output)
        val seconds = output.trim().lines().last().toDouble()
        assertTrue(abs(seconds - 4.9935) < 0.001, "FFmpeg da $seconds s; sin el cebado son 4,9935")
    }
}
