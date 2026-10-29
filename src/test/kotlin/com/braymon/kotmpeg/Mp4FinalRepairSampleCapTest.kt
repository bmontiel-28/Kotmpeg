package com.braymon.kotmpeg

import com.braymon.kotmpeg.io.RepairTarget
import com.braymon.kotmpeg.mp4.FragmentFlags
import com.braymon.kotmpeg.mp4.FragmentWalker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * El tope de muestras por `moof` de la reparación de fMP4, [FragmentWalker.MAX_TRUN_SAMPLES] entre
 * todos sus `trun`, se comprobaba `trun` a `trun` según se creaban sus muestras. Un `moof` con un
 * `trun` justo en el tope y otro detrás se rechazaba, pero solo después de construir en memoria el
 * primero entero: un millón de muestras, unos 70 MB, que en Android bastan para un `OutOfMemoryError`.
 */
class Mp4FinalRepairSampleCapTest {

    @TempDir
    lateinit var dir: File

    /**
     * Un fMP4 cortado tras un `moof` con dos `trun` sin tamaño por muestra, de 2²⁰ y de 2 muestras,
     * cuyo `mdat` tiene un byte para cada una: el primero cabe solo en el tope y en el `mdat`, y entre
     * los dos lo pasan. Se rechaza sin tocar el archivo y reservando poco más que lo que cuesta leer
     * la cabecera. Se mide una segunda pasada, porque la primera también carga clases.
     */
    @Test
    fun `a moof over the sample cap is rejected before building any sample`() {
        val header = Mp4ReadFixtures.fragmentedHeader(dir, Mp4ReadFixtures.audio())

        fun moof(dataOffset: Long): ByteArray = Mp4ReadFixtures.box("moof") {
            fullBox("mfhd", 0, 0) { u32(1) }
            box("traf") {
                fullBox("tfhd", 0, FragmentFlags.TFHD_DEFAULT_BASE_IS_MOOF) { u32(1) }
                fullBox("tfdt", 1, 0) { u64(0) }
                fullBox("trun", 0, FragmentFlags.TRUN_DATA_OFFSET) { u32(FragmentWalker.MAX_TRUN_SAMPLES); u32(dataOffset) }
                fullBox("trun", 0, 0) { u32(2) }
            }
        }

        val moof = moof(moof(0).size + 8L)
        val mdat = Mp4ReadFixtures.box("mdat") { bytes(ByteArray(FragmentWalker.MAX_TRUN_SAMPLES + 2) { 7 }) }
        val bytes = header + moof + mdat

        fun rejectedUntouched(channel: RecordingFileChannel) {
            assertFailsWith<IllegalArgumentException> { RepairTarget.open(channel).use { Kotmpeg.repair(it) } }
            assertEquals(0, channel.writes.size, "se modificó el archivo al rechazarlo")
        }

        rejectedUntouched(RecordingFileChannel(bytes))
        val channel = RecordingFileChannel(bytes)
        val allocated = allocatedBytes { rejectedUntouched(channel) }
        assertTrue(allocated < 4L shl 20, "rechazarlo reservó ${allocated shr 20} MB")
    }
}
