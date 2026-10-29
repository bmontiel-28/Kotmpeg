package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.io.SeekableOutput
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
 * Lo que reserva `Mp4Muxer.stop()` frente a lo que ocupa el `moov` que escribe.
 *
 * El `moov` se construía en un `BoxBuilder` que empezaba en 256 bytes y crecía duplicándose, y se
 * copiaba a un array al terminar: para un `moov` de S bytes, entre 3S y 5S reservados contando la
 * copia, y con inicio rápido el doble. Ahora se reserva de una vez con su tamaño —exacto en el de cabeza, por arriba en el de
 * cola— y ninguno se copia. Lo demás que reserva `stop()` son las tablas por chunk, de 4 bytes, y
 * en el sitio el búfer de 1 MiB con el que se desplazan los datos.
 */
class SharedMoovCapacityTest {

    @TempDir
    lateinit var dir: File

    /** Graba [audio] paquetes de AAC y la mitad de fotogramas de vídeo intercalados, y devuelve lo que reserva `stop()`. */
    private fun stopAllocation(muxer: Mp4Muxer, audio: Int): Long {
        val a = muxer.addTrack(TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) })
        val v = muxer.addTrack(Mp4ReadFixtures.video())
        muxer.start()
        val payload = byteArrayOf(1, 2, 3)
        for (i in 0 until audio) {
            muxer.writePacket(MediaPacket(a, payload, i * 21_333L, isKeyFrame = true))
            if (i % 2 == 0) muxer.writePacket(MediaPacket(v, payload, i * 10_666L, isKeyFrame = i % 60 == 0))
        }
        return allocatedBytes { muxer.stop() }
    }

    @Test
    fun `closing allocates about the moov once, not its doubling growth`() {
        stopAllocation(Mp4Muxer(File(dir, "calentamiento.mp4")), 2_000)
        val modes = listOf<Triple<String, (File) -> Mp4Muxer, Double>>(
            Triple("plano", { f -> Mp4Muxer(f) }, 2.0),
            Triple("faststart", { f -> Mp4Muxer(f) { fastStart = true } }, 3.0),
            Triple("faststart-en-el-sitio", { f -> Mp4Muxer(SeekableOutput(f)) { fastStart = true } }, 4.0),
        )
        for ((name, open, limit) in modes) {
            val file = File(dir, "$name.mp4")
            val allocated = stopAllocation(open(file), 120_000)
            val bytes = file.readBytes()
            val moov = Mp4ReadFixtures.topLevel(bytes, "moov")
            val moovSize = Mp4ReadFixtures.u32(bytes, moov)
            val ratio = allocated.toDouble() / moovSize
            assertTrue(ratio < limit, "$name: stop() reservó $allocated bytes para un moov de $moovSize, ${"%.1f".format(ratio)} veces")
            assertEquals(180_000, Mp4ReadFixtures.readAll(file).size, "$name: el archivo se relee entero")
        }
    }
}
