package com.braymon.kotmpeg

import com.braymon.kotmpeg.SharedMp4Headers.edits
import com.braymon.kotmpeg.SharedMp4Headers.mehdDuration
import com.braymon.kotmpeg.SharedMp4Headers.movieOrMediaDuration
import com.braymon.kotmpeg.SharedMp4Headers.mvhd
import com.braymon.kotmpeg.SharedMp4Headers.tkhdDuration
import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La duración de cada `tkhd` es la suma de sus ediciones, en la escala de la película, y la del
 * `mvhd` —y la del `mehd` en un fMP4— la mayor de ellas, como pide ISO/IEC 14496-12 §8.3.2.3.
 *
 * El `tkhd` contaba el cebado del codificador y la lista de edición lo descontaba, en el MP4 plano, en
 * el fragmentado y en la reparación de este. Con el audio como pista más larga, el archivo declaraba
 * unos 21 ms de más con AAC-LC: justo lo que salta la edición.
 */
class SharedEditDurationsTest {

    @TempDir
    lateinit var dir: File

    private val aac = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2); codecDelayUs = 21_333 }

    /**
     * Un segundo de vídeo a 30 fps y algo más de audio con cebado, que es la pista más larga y
     * arranca [audioStartUs] tarde: un desfase de milisegundos no enteros, para que el hueco inicial y
     * el resto de la presentación no redondeen por su cuenta a una suma distinta.
     */
    private fun write(name: String, fragmented: Boolean, audioStartUs: Long = 0L): File {
        val file = File(dir, name)
        Kotmpeg.createMuxer(file, ContainerFormat.MP4) { this.fragmented = fragmented; fragmentDurationUs = 500_000 }.use { muxer ->
            val v = muxer.addTrack(Mp4ReadFixtures.video())
            val a = muxer.addTrack(aac)
            muxer.start()
            val packets = List(30) { i -> MediaPacket(v, Mp4ReadFixtures.nal(i), i * 33_333L, isKeyFrame = i % 10 == 0) } +
                List(60) { i -> MediaPacket(a, ByteArray(16) { (i + it).toByte() }, audioStartUs + i * 21_333L, isKeyFrame = true) }
            for (packet in packets.sortedBy { it.ptsUs }) muxer.writePacket(packet)
        }
        return file
    }

    /** Cada `tkhd` es la suma de sus ediciones, o lo que dura su medio si no tiene; el `mvhd`, el mayor. */
    private fun assertDurationsAddUp(name: String, bytes: ByteArray, fragmented: Boolean) {
        val tracks = (0 until 2).map { tkhdDuration(bytes, it) }
        val audioEdits = edits(bytes, 1)
        assertTrue(audioEdits.isNotEmpty(), "$name: el audio con cebado lleva lista de edición")
        assertEquals(audioEdits.sumOf { it.duration }, tracks[1], "$name: el tkhd del audio no es la suma de sus ediciones $audioEdits")
        val videoEdits = edits(bytes, 0)
        if (videoEdits.isNotEmpty()) assertEquals(videoEdits.sumOf { it.duration }, tracks[0], "$name: tkhd del vídeo")
        assertEquals(tracks.max(), movieOrMediaDuration(bytes, mvhd(bytes)), "$name: el mvhd es el mayor tkhd")
        if (fragmented) assertEquals(tracks.max(), mehdDuration(bytes), "$name: el mehd es el mayor tkhd")
    }

    @Test
    fun `track durations are the sum of their edits and the movie the longest`() {
        for ((name, fragmented) in listOf("plano.mp4" to false, "fragmentado.mp4" to true)) {
            val bytes = write(name, fragmented).readBytes()
            assertDurationsAddUp(name, bytes, fragmented)
            assertEquals(1_259L, movieOrMediaDuration(bytes, mvhd(bytes)), "$name: 60 paquetes de AAC menos el cebado")
        }
        val late = write("tardio.mp4", fragmented = false, audioStartUs = 487_600L).readBytes()
        assertDurationsAddUp("tardio.mp4", late, fragmented = false)
        assertEquals(488L, edits(late, 1).first().duration, "el hueco inicial del audio")
    }

    /** La reparación de un fMP4 cortado pone las duraciones con el mismo criterio que el muxer. */
    @Test
    fun `a repaired fragmented file keeps its track durations equal to their edits`() {
        val file = write("cortado.mp4", fragmented = true)
        val mfra = Mp4ReadFixtures.topLevel(file.readBytes(), "mfra")
        RandomAccessFile(file, "rw").use { it.setLength(mfra - 50L) }
        val result = Kotmpeg.repair(file)
        assertTrue(result.repaired, "el archivo cortado tenía que repararse")
        val bytes = file.readBytes()
        assertDurationsAddUp("reparado", bytes, fragmented = true)
        assertEquals(movieOrMediaDuration(bytes, mvhd(bytes)) * 1000, result.durationUs, "la duración que devuelve la reparación")
    }
}
