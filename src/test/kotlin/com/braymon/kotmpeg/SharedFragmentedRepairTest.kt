package com.braymon.kotmpeg

import com.braymon.kotmpeg.Mp4ReadFixtures.children
import com.braymon.kotmpeg.Mp4ReadFixtures.type
import com.braymon.kotmpeg.SharedMp4Headers.movieOrMediaDuration
import com.braymon.kotmpeg.SharedMp4Headers.mvhd
import com.braymon.kotmpeg.SharedMp4Headers.tkhdDuration
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La reparación de un fMP4 lee los `trun` como el demuxer: el desfase de composición, con signo
 * también en la versión 0. La versión 0 lo declara sin signo, pero los escritores que necesitan uno
 * negativo escriben a menudo esa versión, y FFmpeg y el demuxer lo leen con signo.
 *
 * La reparación lo leía sin signo. El desfase de −1 fotograma de un B-frame pasaba por uno de más de
 * 13 horas, y como la reparación alarga la última muestra que conserva hasta cubrir el fotograma que
 * más tarde se presenta, el archivo reparado declaraba esas 13 horas.
 */
class SharedFragmentedRepairTest {

    @TempDir
    lateinit var dir: File

    /**
     * Cuatro GOP de diez fotogramas a 30 fps con B-frames, en un fMP4 con desfases firmados, y con
     * todos sus `trun` pasados a la versión 0 sin tocar los desfases: lo que escribe un programa que
     * usa la versión 0 con valores negativos.
     */
    private fun writeVersion0(file: File): ByteArray {
        val order = intArrayOf(0, 3, 1, 2, 6, 4, 5, 9, 7, 8)
        FragmentedMp4Muxer(file).use { muxer ->
            muxer.addTrack(Mp4ReadFixtures.video())
            muxer.start()
            for (gop in 0 until 4) {
                for ((k, shown) in order.withIndex()) {
                    val frame = gop * 10 + shown
                    muxer.writePacket(MediaPacket(1, Mp4ReadFixtures.nal(frame), frame * 33_333L, isKeyFrame = k == 0))
                }
            }
        }
        val bytes = file.readBytes()
        var truns = 0
        for (moof in children(bytes).filter { type(bytes, it) == "moof" }) {
            for (traf in children(bytes, moof).filter { type(bytes, it) == "traf" }) {
                for (trun in children(bytes, traf).filter { type(bytes, it) == "trun" }) {
                    bytes[trun + 8] = 0
                    truns++
                }
            }
        }
        assertEquals(4, truns, "un trun por fragmento")
        assertTrue(SharedMp4Headers.compositionOffsets(bytes, 1).any { it < 0 }, "los B-frames llevan desfases negativos")
        return bytes
    }

    @Test
    fun `negative offsets in version 0 truns are repaired as the demuxer reads them`() {
        val file = File(dir, "v0.mp4")
        val bytes = writeVersion0(file)
        val lastMdat = children(bytes).last { type(bytes, it) == "mdat" }
        val frameBytes = Mp4ReadFixtures.nal(0).size
        file.writeBytes(bytes.copyOf(lastMdat + 8 + 4 * frameBytes + 10))

        val result = Kotmpeg.repair(file)
        assertTrue(result.repaired, "el archivo cortado tenía que repararse")
        assertTrue(result.durationUs in 1_000_000L..1_200_000L, "la reparación declara ${result.durationUs} µs para 34 fotogramas")
        val repaired = file.readBytes()
        assertTrue(tkhdDuration(repaired, 0) in 1_000L..1_200L, "tkhd de ${tkhdDuration(repaired, 0)} ms")
        assertEquals(tkhdDuration(repaired, 0), movieOrMediaDuration(repaired, mvhd(repaired)), "mvhd")
        assertEquals(34, Mp4ReadFixtures.readAll(file).size, "los tres fragmentos enteros y las cuatro muestras completas del cortado")
    }
}
