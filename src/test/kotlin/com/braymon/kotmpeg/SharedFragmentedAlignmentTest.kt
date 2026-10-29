package com.braymon.kotmpeg

import com.braymon.kotmpeg.SharedMp4Headers.compositionOffsets
import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import com.braymon.kotmpeg.mp4.Mp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `FragmentedMp4Muxer` con las mismas reglas que `Mp4Muxer` en dos sitios en los que se habían
 * separado: cuándo se valida la entrada de muestra de una pista, y cómo se ordenan los tiempos del
 * audio.
 */
class SharedFragmentedAlignmentTest {

    @TempDir
    lateinit var dir: File

    private val aac = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) }

    /**
     * Una pista que el MP4 no puede describir se rechaza al registrarla, como en el plano, y el muxer
     * sigue sirviendo. Antes la entrada de muestra se construía al escribir la cabecera: el error salía
     * en `start()`, o con `nonNegativeCompositionOffsets` al cerrar el primer fragmento, con la
     * grabación ya en marcha.
     */
    @Test
    fun `the fragmented muxer rejects an unwritable track when it is added`() {
        val surround = TrackInfo.Audio(AudioCodec.OPUS, 48_000, 6)
        for (nonNegative in listOf(false, true)) {
            val file = File(dir, "surround-$nonNegative.mp4")
            FragmentedMp4Muxer(file) { nonNegativeCompositionOffsets = nonNegative }.use { muxer ->
                val error = assertFailsWith<IllegalArgumentException> { muxer.addTrack(surround) }
                assertTrue(error.message.orEmpty().contains("mapeo"), "el error tiene que explicar la tabla de mapeo: ${error.message}")
                assertEquals(1, muxer.addTrack(aac), "una pista rechazada no consume id")
                muxer.start()
                muxer.writePacket(MediaPacket(1, ByteArray(8), 0L, isKeyFrame = true))
            }
            assertEquals(1, Kotmpeg.openDemuxer(file).use { it.tracks.size })
        }
    }

    /**
     * Vídeo con keyframe cada medio segundo, que corta los fragmentos, y audio cada 21 333 µs salvo un
     * paquete, el [moved], que llega [shiftUs] antes de su sitio. El 24 es el primero del segundo
     * fragmento, y con 300 µs sigue siendo posterior al anterior: un vaivén normal de la fuente, sin
     * retroceder, que hace que el fragmento empiece antes de donde se estimó que acababa el primero.
     * Con 24 000 µs, el 40 retrocede por detrás del 39.
     */
    private fun packets(moved: Int, shiftUs: Long): List<MediaPacket> {
        val video = List(60) { i -> MediaPacket(1, Mp4ReadFixtures.nal(i), i * 33_333L, isKeyFrame = i % 15 == 0) }
        val audio = List(90) { i ->
            val pts = i * 21_333L - if (i == moved) shiftUs else 0L
            MediaPacket(2, ByteArray(10) { (i + it).toByte() }, pts, isKeyFrame = true)
        }
        val out = ArrayList<MediaPacket>()
        var a = 0
        for (frame in video) {
            while (a < audio.size && a * 21_333L <= frame.ptsUs) out += audio[a++]
            out += frame
        }
        while (a < audio.size) out += audio[a++]
        return out
    }

    private fun write(file: File, fragmented: Boolean, packets: List<MediaPacket>): File {
        val muxer: Muxer = if (fragmented) FragmentedMp4Muxer(file) else Mp4Muxer(file)
        muxer.use {
            it.addTrack(Mp4ReadFixtures.video())
            it.addTrack(aac)
            it.start()
            for (packet in packets) it.writePacket(packet)
        }
        return file
    }

    private fun audioTimes(file: File): List<Pair<Long, Long>> =
        Mp4ReadFixtures.readAll(file).filter { it.trackId == 2 }.map { it.ptsUs to it.dtsUs }

    /**
     * En audio el DTS es la secuencia de PTS, ordenada si alguno retrocede, el PTS se iguala a él y no
     * hay desfases de composición: la regla de `Mp4Muxer`. El fragmentado trataba el audio como vídeo,
     * y el paquete que retrocedía —o el primero de un fragmento que empezaba antes de donde se había
     * estimado que acababa el anterior— salía con un desfase de composición negativo. FFmpeg retrasa
     * la pista entera lo que mida el mayor, así que un vaivén de unos cientos de microsegundos en un
     * solo paquete movía todo el audio.
     */
    @Test
    fun `fragmented audio has no composition offsets, like plain mp4 audio`() {
        for ((label, moved, shiftUs) in listOf(Triple("vaivén", 24, 300L), Triple("retroceso", 40, 24_000L))) {
            val input = packets(moved, shiftUs)
            val fragmented = write(File(dir, "$label-frag.mp4"), true, input)
            val offsets = compositionOffsets(fragmented.readBytes(), 2)
            assertEquals(90, offsets.size, "$label: muestras de audio")
            assertEquals(List(90) { 0L }, offsets, "$label: desfases de composición del audio")
            val times = audioTimes(fragmented)
            assertTrue(times.all { (pts, dts) -> pts == dts }, "$label: el PTS del audio es su DTS: $times")
            assertEquals(times.sortedBy { it.first }, times, "$label: el audio se presenta en orden")
        }
        val input = packets(40, 24_000L)
        val plain = write(File(dir, "retroceso.mp4"), false, input)
        assertEquals(audioTimes(plain), audioTimes(File(dir, "retroceso-frag.mp4")), "los mismos tiempos de audio que en el MP4 plano")
    }
}
