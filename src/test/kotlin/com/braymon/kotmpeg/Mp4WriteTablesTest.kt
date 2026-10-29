package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.Mp4Muxer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Las tablas de tiempos y de chunks que `Mp4Muxer` calcula al cerrar: lo que se escribe para el
 * audio cuyo PTS retrocede, y la memoria que cuesta calcularlas.
 */
class Mp4WriteTablesTest {

    @TempDir
    lateinit var dir: File

    private companion object {
        /** Un tic de la escala de una pista a 48 kHz, redondeado: lo que se pierde al pasar de µs a tics y volver. */
        const val ONE_TICK_AT_48K_US = 21L

        /**
         * Lo que puede reservar `stop()` por muestra en cada modo de cierre. Con las tablas de la
         * 4.0.0 eran 152, 251 y 255 bytes; con las de la 4.1, 78, 147 y 136, casi todo el propio
         * `moov`, que `BoxBuilder` hacía crecer duplicando. Ahora se reserva con su tamaño y no se
         * copia (ver `SharedMoovCapacityTest`): 31, 38 y 41. El margen sobre lo medido absorbe la
         * variación entre JDK sin dejar pasar la regresión.
         */
        val LIMITS = mapOf("plano" to 50L, "faststart" to 60L, "faststart-en-el-sitio" to 65L)
    }

    private fun aac(delayUs: Long) = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) {
        codecPrivate = AacConfig.build(48_000, 2)
        codecDelayUs = delayUs
    }

    private fun containsBox(file: File, type: String): Boolean {
        val bytes = file.readBytes()
        val marker = type.toByteArray(Charsets.US_ASCII)
        return (0 until bytes.size - 4).any { i -> (0..3).all { bytes[i + it] == marker[it] } }
    }

    /**
     * **Un PTS de audio que retrocede no se convierte en cebado.** La derivación del DTS por
     * reordenación, pensada para los B-frames, se aplicaba también al audio: un paquete que llegaba
     * 8,7 ms tarde respecto al siguiente generaba un `ctts` y un `media_time` de 8,7 ms más el
     * cebado, y el demuxer —que en audio toma esa edición como cebado— devolvía `codecDelayUs` 8,7 ms
     * más grande. En audio el orden de decodificación es el de presentación: el DTS es la secuencia
     * de PTS ordenada, sin `ctts`, y el cebado declarado vuelve tal cual.
     */
    @Test
    fun `an audio pts that goes backwards neither writes ctts nor grows the priming`() {
        for (delayUs in listOf(0L, 21_333L)) {
            val file = File(dir, "audio-retrocede-$delayUs.mp4")
            val pts = LongArray(50) { it * 21_333L }
            pts[10] += 30_000L
            Mp4Muxer(file).use { muxer ->
                val id = muxer.addTrack(aac(delayUs))
                muxer.start()
                for (t in pts) muxer.writePacket(MediaPacket(id, ByteArray(8), t, isKeyFrame = true))
            }
            assertFalse(containsBox(file, "ctts"), "$delayUs: el audio no lleva ctts")

            Kotmpeg.openDemuxer(file).use { demuxer ->
                val track = demuxer.tracks.single() as TrackInfo.Audio
                assertEquals(delayUs, track.codecDelayUs, "el cebado creció con el retroceso")
                val read = generateSequence { demuxer.readPacket() }.toList()
                assertEquals(pts.size, read.size)
                val sorted = pts.sorted()
                for (i in read.indices) {
                    assertTrue(
                        abs(read[i].ptsUs - sorted[i]) <= ONE_TICK_AT_48K_US,
                        "paquete $i: ${read[i].ptsUs} µs, se esperaba el PTS ordenado ${sorted[i]} µs",
                    )
                }
                assertTrue(read.all { it.ptsUs == it.dtsUs }, "en audio el DTS es el PTS")
            }
        }
    }

    private val avcC = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x4D, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    /**
     * Graba [frames] fotogramas con B-frames y sin DTS —como los da MediaCodec— intercalados con
     * su audio, y devuelve los bytes que reserva el hilo dentro de `stop()`, por muestra.
     */
    private fun stopAllocationPerSample(
        threads: com.sun.management.ThreadMXBean,
        muxer: Mp4Muxer,
        frames: Int,
    ): Long {
        val order = intArrayOf(0, 3, 1, 2)
        val payload = byteArrayOf(1)
        var samples = 0L
        val v = muxer.addTrack(TrackInfo.Video(VideoCodec.H264, 1280, 720) { codecPrivate = avcC })
        val a = muxer.addTrack(aac(21_333))
        muxer.start()
        var audio = 0
        for (i in 0 until frames) {
            val pts = ((i / 4) * 4 + order[i % 4]) * 33_333L + 66_666L
            muxer.writePacket(MediaPacket(v, payload, pts, isKeyFrame = i % 60 == 0))
            samples++
            while (audio * 21_333L <= i * 33_333L) {
                muxer.writePacket(MediaPacket(a, payload, audio * 21_333L, isKeyFrame = true))
                audio++
                samples++
            }
        }
        val before = threads.currentThreadAllocatedBytes
        muxer.stop()
        return (threads.currentThreadAllocatedBytes - before) / samples
    }

    /**
     * **Cerrar no reserva varias veces la tabla de muestras.** `stop()` creaba ocho arrays de una
     * entrada por muestra para las tablas de tiempos —uno de ellos, el DTS en tics, retenido sin
     * que nadie lo leyera—, y cada una de las dos o tres construcciones del `moov` volvía a reservar
     * las series del `stts`/`ctts` y la lista de chunks, también por muestra, con el `moov` de cola
     * todavía vivo mientras se construía el de cabeza. Al final de una grabación larga ese pico
     * duplicaba o triplicaba lo que ya ocupa la tabla de muestras, y un `OutOfMemoryError` ahí
     * deja el archivo sin `moov`. Se mide lo que reserva el hilo dentro de `stop()`, en el caso más
     * caro: B-frames sin DTS, audio intercalado —un chunk por muestra— y los tres modos de cierre.
     */
    @Test
    fun `closing allocates a bounded amount per sample`() {
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(threads != null && threads.isThreadAllocatedMemorySupported, "este JDK no mide la memoria por hilo")
        threads!!.isThreadAllocatedMemoryEnabled = true
        val frames = 120_000
        val modes = listOf<Pair<String, (File) -> Mp4Muxer>>(
            "plano" to { f -> Mp4Muxer(f) },
            "faststart" to { f -> Mp4Muxer(f) { fastStart = true } },
            "faststart-en-el-sitio" to { f -> Mp4Muxer(SeekableOutput(f)) { fastStart = true } },
        )
        for ((name, open) in modes) {
            val file = File(dir, "$name.mp4")
            val perSample = stopAllocationPerSample(threads, open(file), frames)
            println("$name: $perSample B/muestra")
            val limit = LIMITS.getValue(name)
            assertTrue(perSample <= limit, "$name: stop() reservó $perSample B por muestra, el límite es $limit")
            val read = Kotmpeg.openDemuxer(file).use { demuxer -> generateSequence { demuxer.readPacket() }.count() }
            assertTrue(read > frames, "$name: el archivo no se relee entero ($read paquetes)")
        }
    }
}
