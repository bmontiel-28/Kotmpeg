package com.braymon.kotmpeg

import com.braymon.kotmpeg.SharedMp4Headers.movieOrMediaDuration
import com.braymon.kotmpeg.SharedMp4Headers.mvhd
import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.OpusConfig
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La duración que da `Mp4Demuxer` de un fMP4 cuyo audio lleva cebado.
 *
 * En un fMP4 la duración es la mayor entre la del `mvhd` y el final de cada pista. El cebado del
 * audio pasa de su lista de edición a `codecDelayUs` y no mueve los tiempos de los paquetes, así que
 * el final de esa pista lo seguía contando, mientras que el `mvhd`, el `mehd` y el `tkhd` —la suma de
 * sus ediciones—, `Mp4Muxer` y `RepairResult` lo descuentan. Con la pista de audio como la más larga,
 * el mismo contenido duraba 21 ms más en fMP4 que en MP4 plano con AAC, y 6,5 ms más con Opus, y
 * `Kotmpeg.repair` devolvía esos milisegundos menos de lo que luego leía el demuxer.
 */
class Mp4FinalPrimingDurationTest {

    @TempDir
    lateinit var dir: File

    private val aac = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2); codecDelayUs = 21_333 }
    private val opus = TrackInfo.Audio(AudioCodec.OPUS, 48_000, 2) { codecPrivate = OpusConfig.buildOpusHead(2, preSkip = 312) }

    /** [count] paquetes de audio de 20 ms, que caen en tics exactos de 48 kHz. */
    private fun packets(trackId: Int, count: Int): List<MediaPacket> =
        List(count) { i -> MediaPacket(trackId, ByteArray(20) { (i + it).toByte() }, i * 20_000L, isKeyFrame = true) }

    /** Diez segundos de [track] en MP4 plano o fragmentado. */
    private fun write(name: String, track: TrackInfo.Audio, fragmented: Boolean): File {
        val file = File(dir, name)
        Kotmpeg.createMuxer(file, ContainerFormat.MP4) { this.fragmented = fragmented }.use { muxer ->
            val id = muxer.addTrack(track)
            muxer.start()
            for (packet in packets(id, 500)) muxer.writePacket(packet)
        }
        return file
    }

    private fun durationUs(bytes: ByteArray): Long = Kotmpeg.openDemuxer(memoryInput(bytes)).use { it.durationUs }

    /**
     * Diez segundos de audio son 9 979 ms sin el cebado de un AAC-LC (21,333 ms) y 9 994 ms sin el
     * `preSkip` de 312 muestras de un Opus (6,5 ms, redondeado al milisegundo del `mvhd`). Es lo que
     * declara la cabecera de los dos archivos y lo que da el MP4 plano; el fMP4 daba los 10 000 ms.
     */
    @Test
    fun `a primed fmp4 lasts what its plain mp4 and its own header say`() {
        for ((name, track, expectedMs) in listOf(Triple("aac", aac, 9_979L), Triple("opus", opus, 9_994L))) {
            val plain = write("$name.mp4", track, fragmented = false).readBytes()
            val fragmented = write("$name-frag.mp4", track, fragmented = true).readBytes()
            assertEquals(expectedMs, movieOrMediaDuration(fragmented, mvhd(fragmented)), "$name: mvhd del fMP4")
            assertEquals(expectedMs * 1000, durationUs(plain), "$name: MP4 plano")
            assertEquals(durationUs(plain), durationUs(fragmented), "$name: el fMP4 no dura lo que el MP4 plano")
        }
    }

    /**
     * Sin la duración del `mvhd` —un archivo dañado la deja a cero—, el MP4 plano usa de respaldo el
     * final de sus pistas, que también tiene que descontar el cebado: daba los 10 000 ms en vez de los
     * 9 979 que declara la cabecera del mismo archivo intacto.
     */
    @Test
    fun `a primed plain mp4 without a movie duration falls back to its tracks without the priming`() {
        val bytes = write("sin-duracion.mp4", aac, fragmented = false).readBytes()
        val type = String(bytes, Charsets.ISO_8859_1).indexOf("mvhd")
        val wide = bytes[type + 4].toInt() == 1
        bytes.fill(0, type + if (wide) 28 else 20, type + if (wide) 36 else 24)
        val fallback = durationUs(bytes)
        assertTrue(abs(fallback - 9_979_000) < 1_000, "sin duración en el mvhd da $fallback µs")
    }

    /**
     * `RepairResult.durationUs` es lo que lee el demuxer del archivo que deja la reparación, en cada
     * corte de una grabación de solo audio con cebado, y también del archivo entero, que ya está
     * cerrado y no se toca. La grabación se registra escritura a escritura, como en `Fmp4RepairTest`.
     *
     * Se admite menos de un milisegundo de diferencia: la reparación declara la duración del `mvhd`,
     * que va en milisegundos, y el demuxer toma el final exacto de la pista si pasa de ella. Con el
     * cebado contado, la diferencia eran los 21 ms del cebado.
     */
    @Test
    fun `the repair reports the duration the demuxer reads from a primed fmp4`() {
        val channel = RecordingFileChannel()
        FragmentedMp4Muxer(SeekableOutput(channel)) { fragmentDurationUs = 500_000 }.use { muxer ->
            val id = muxer.addTrack(aac)
            muxer.start()
            for (packet in packets(id, 250)) muxer.writePacket(packet)
        }
        var compared = 0
        for (cut in channel.boundaries().flatMap { listOf(it - 1, it) }) {
            val bytes = channel.cutAfter(cut)
            val (result, repaired) = runCatching { RecordingFileChannel.repairRecorded(bytes) }.getOrNull() ?: continue
            val read = durationUs(repaired.bytes())
            if (read == 0L) continue
            assertTrue(
                abs(result.durationUs - read) < 1_000,
                "corte en $cut: la reparación dice ${result.durationUs} µs y el demuxer lee $read",
            )
            compared++
        }
        assertTrue(compared > 20, "solo $compared cortes comparados")
    }
}
