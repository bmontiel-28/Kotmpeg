package com.braymon.kotmpeg

import com.braymon.kotmpeg.model.Timestamps
import com.braymon.kotmpeg.mp4.FragmentFlags
import com.braymon.kotmpeg.mp4.FragmentWalker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El tope de [FragmentWalker.MAX_TRUN_SAMPLES] en un `trun` sin campos por muestra.
 *
 * Recortaba en silencio, aunque todos los límites ante archivos manipulados avisan por `onWarning`.
 * Y el recorte movía lo que va detrás: el siguiente `trun` sin `data_offset` —o el `traf` siguiente,
 * encadenado— empieza donde acaban los datos y el tiempo de decodificación del anterior, y se
 * calculaba con la cuenta recortada en vez de con las muestras que el `trun` ocupa de verdad en el
 * archivo. Su muestra se leía de bytes del `trun` anterior y con un tiempo que no era el suyo.
 */
class Mp4FinalTrunCapTest {

    @TempDir
    lateinit var dir: File

    /**
     * Un fMP4 de audio con un solo fragmento: un `trun` sin campos por muestra que declara cien
     * muestras más que el tope, de un byte y un tic cada una y todas dentro del archivo, y detrás otro
     * `trun` sin `data_offset` con una muestra de cuatro bytes reconocibles. La última muestra que se
     * lee tiene que ser esa, en su sitio y en su tiempo, y el recorte tiene que avisarse.
     */
    @Test
    fun `a trun over the cap warns and the run behind it keeps its data and time`() {
        val declared = FragmentWalker.MAX_TRUN_SAMPLES + 100
        val tail = byteArrayOf(1, 2, 3, 4)

        fun moof(dataOffset: Long): ByteArray = Mp4ReadFixtures.box("moof") {
            fullBox("mfhd", 0, 0) { u32(1) }
            box("traf") {
                val tfhdFlags = FragmentFlags.TFHD_DEFAULT_BASE_IS_MOOF or FragmentFlags.TFHD_DEFAULT_SAMPLE_DURATION or
                    FragmentFlags.TFHD_DEFAULT_SAMPLE_SIZE
                fullBox("tfhd", 0, tfhdFlags) { u32(1); u32(1); u32(1) }
                fullBox("tfdt", 1, 0) { u64(0) }
                fullBox("trun", 0, FragmentFlags.TRUN_DATA_OFFSET) { u32(declared.toLong()); u32(dataOffset) }
                fullBox("trun", 0, FragmentFlags.TRUN_SAMPLE_SIZE) { u32(1); u32(tail.size.toLong()) }
            }
        }

        val header = Mp4ReadFixtures.fragmentedHeader(dir, Mp4ReadFixtures.audio())
        val moof = moof(moof(0).size + 8L)
        val payload = ByteArray(declared) { 0x11 } + tail
        val mdat = Mp4ReadFixtures.box("mdat") { bytes(payload) }
        val file = File(dir, "trun-enorme.mp4").apply { writeBytes(header + moof + mdat) }

        val warnings = ArrayList<String>()
        Mp4ReadFixtures.open(file, warnings).use { demuxer ->
            demuxer.seekTo(Long.MAX_VALUE)
            val last = demuxer.readPacket()!!
            assertContentEquals(tail, last.data, "la muestra del trun de detrás se leyó de otro sitio")
            assertEquals(Timestamps.rescaleFloor(declared.toLong(), 1_000_000, 48_000), last.dtsUs, "tiempo de la muestra de detrás")
            assertNull(demuxer.readPacket())
        }
        assertTrue(
            warnings.any { "$declared" in it && "${FragmentWalker.MAX_TRUN_SAMPLES}" in it },
            "el recorte no se avisó: $warnings",
        )
    }
}
