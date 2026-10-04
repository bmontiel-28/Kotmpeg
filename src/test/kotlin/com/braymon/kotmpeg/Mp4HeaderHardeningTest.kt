package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.mp4.Mp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dos campos de cabecera MP4 que el demuxer usaba sin validar:
 *
 *  - **`entry_count` del `elst`** se recorría tal cual. Un valor de dos mil millones hacía que el
 *    parser leyera la lista de edición atravesando el resto del archivo, entrada a entrada.
 *  - **La escala del `mvhd`** se usaba como divisor. Con un cero, cada pista con lista de edición
 *    lanzaba una división entre cero y se descartaba: el archivo se abría sin pistas.
 */
class Mp4HeaderHardeningTest {

    @TempDir
    lateinit var dir: File

    /**
     * MP4 con dos pistas AAC que llevan `elst`: la primera por su cebado y la segunda, que arranca
     * 200 ms después, también con una edición vacía, que es la que divide por la escala del `mvhd`.
     */
    private fun mp4WithEditList(): File {
        val file = File(dir, "elst.mp4")
        Mp4Muxer(file).use { muxer ->
            val track = TrackInfo.Audio(
                codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2,
                codecPrivate = AacConfig.build(48000, 2), codecDelayUs = 21_333,
            )
            val first = muxer.addTrack(track)
            val late = muxer.addTrack(track)
            muxer.start()
            for (i in 0 until 50) {
                muxer.writePacket(MediaPacket(first, ByteArray(20), ptsUs = i * 21_333L))
                muxer.writePacket(MediaPacket(late, ByteArray(20), ptsUs = 200_000 + i * 21_333L))
            }
        }
        return file
    }

    private fun indexOf(bytes: ByteArray, type: String): Int {
        val pattern = type.toByteArray(Charsets.US_ASCII)
        for (i in 0..bytes.size - pattern.size) {
            if ((pattern.indices).all { bytes[i + it] == pattern[it] }) return i
        }
        error("no se encontró $type")
    }

    private fun packetsOf(file: File): Int = MkvKotlin.openDemuxer(file).use { demuxer ->
        assertEquals(2, demuxer.tracks.size, "se descartó alguna pista")
        generateSequence { demuxer.readPacket() }.count()
    }

    @Test
    fun `an absurd edit list entry count is bounded by the box size`() {
        val bytes = mp4WithEditList().readBytes()
        val countAt = indexOf(bytes, "elst") + 4 + 4
        bytes[countAt] = 0x7F; bytes[countAt + 1] = -1; bytes[countAt + 2] = -1; bytes[countAt + 3] = -1
        val damaged = File(dir, "elst-inflado.mp4").apply { writeBytes(bytes) }
        val started = System.nanoTime()
        assertEquals(100, packetsOf(damaged))
        assertTrue(System.nanoTime() - started < 5_000_000_000L, "abrir el archivo tardó demasiado")
    }

    @Test
    fun `a zero movie timescale does not discard tracks with an edit list`() {
        val bytes = mp4WithEditList().readBytes()
        val timescaleAt = indexOf(bytes, "mvhd") + 4 + 4 + 8
        for (k in 0 until 4) bytes[timescaleAt + k] = 0
        val damaged = File(dir, "mvhd-cero.mp4").apply { writeBytes(bytes) }
        assertEquals(100, packetsOf(damaged))
    }
}
