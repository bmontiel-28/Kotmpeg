package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `nonNegativeCompositionOffsets` de `MuxerOptions`: el fMP4 con desfases de composición sin signo
 * (`trun` versión 0) y una lista de edición que los compensa, en vez de los firmados de CMAF.
 *
 * Lo que tiene que cumplir: los mismos tiempos de presentación que el modo firmado —es la misma
 * grabación—, ningún desfase negativo aunque el reordenamiento crezca después del primer
 * fragmento, y la cabecera escrita con el primer fragmento, que es cuando se conoce el desfase.
 */
class CompositionOffsetsTest {

    @TempDir
    lateinit var dir: File

    private val video = TrackInfo.Video(VideoCodec.H264, 64, 48) {
        frameRate = 30.0
        codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22)))
    }
    private val audio = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) {
        codecPrivate = AacConfig.build(48_000, 2)
        codecDelayUs = 21_333
    }

    /**
     * Seis GOP de un segundo con B-frames (I P B B P B B...). Los tres primeros a 30 fps y los
     * siguientes a 10 fps: un vídeo de frecuencia variable cuyo reordenamiento, en tiempo, triplica
     * el del primer fragmento, que es el que fija la lista de edición.
     */
    private fun write(file: File, nonNegative: Boolean, afterStart: (File) -> Unit = {}) {
        FragmentedMp4Muxer(file) {
            nonNegativeCompositionOffsets = nonNegative
            creationTimeMillis = 0
        }.use { muxer ->
            val v = muxer.addTrack(video)
            val a = muxer.addTrack(audio)
            muxer.start()
            afterStart(file)
            var base = 0L
            var audioUs = 0L
            for (gop in 0 until 6) {
                val frame = if (gop < 3) 33_333L else 100_000L
                val count = (1_000_000 / frame).toInt()
                val order = ArrayList<Int>()
                order += 0
                var i = 1
                while (i < count) {
                    val p = minOf(i + 2, count - 1)
                    order += p
                    for (b in i until p) order += b
                    i = p + 1
                }
                for ((position, k) in order.withIndex()) {
                    while (audioUs <= base + position * frame) {
                        muxer.writePacket(MediaPacket(a, ByteArray(6) { (audioUs / 1000).toByte() }, audioUs, isKeyFrame = true))
                        audioUs += 21_333
                    }
                    val payload = NalUnits.joinLengthPrefixed(listOf(byteArrayOf(if (k == 0) 0x65 else 0x41, k.toByte(), gop.toByte())))
                    muxer.writePacket(MediaPacket(v, payload, base + k * frame, isKeyFrame = k == 0))
                }
                base += count * frame
            }
        }
    }

    private class Box(val type: String, val offset: Int, val size: Int)

    private fun children(bytes: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            val size = u32(bytes, p).toInt()
            out += Box(String(bytes, p + 4, 4, Charsets.US_ASCII), p, size)
            p += size
        }
        return out
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    /** Versión de cada `trun` y todos sus desfases de composición, leídos como enteros con signo. */
    private fun truns(file: File): List<Pair<Int, List<Int>>> {
        val bytes = file.readBytes()
        val result = ArrayList<Pair<Int, List<Int>>>()
        for (moof in children(bytes, 0, bytes.size).filter { it.type == "moof" }) {
            for (traf in children(bytes, moof.offset + 8, moof.offset + moof.size).filter { it.type == "traf" }) {
                for (trun in children(bytes, traf.offset + 8, traf.offset + traf.size).filter { it.type == "trun" }) {
                    val version = bytes[trun.offset + 8].toInt()
                    val count = u32(bytes, trun.offset + 12).toInt()
                    val offsets = (0 until count).map { u32(bytes, trun.offset + 20 + it * 16 + 12).toInt() }
                    result += version to offsets
                }
            }
        }
        return result
    }

    private fun read(file: File): List<String> = Kotmpeg.openDemuxer(file).use { demuxer ->
        generateSequence { demuxer.readPacket() }.map { "${it.trackId} ${it.ptsUs} ${it.data.toList()}" }.toList()
    }

    @Test
    fun `unsigned offsets present every sample exactly where the signed ones do`() {
        val signed = File(dir, "firmado.mp4").also { write(it, nonNegative = false) }
        val unsigned = File(dir, "sin-signo.mp4").also { write(it, nonNegative = true) }

        assertTrue(truns(signed).any { (_, offsets) -> offsets.any { it < 0 } }, "la prueba necesita desfases negativos en el modo firmado")
        assertTrue(truns(unsigned).all { (version, offsets) -> version == 0 && offsets.all { it >= 0 } }, "quedó algún desfase negativo")
        assertEquals(read(signed).sorted(), read(unsigned).sorted())
        Kotmpeg.openDemuxer(unsigned).use { demuxer ->
            assertEquals(21_333L, (demuxer.tracks[1] as TrackInfo.Audio).codecDelayUs, "el cebado no puede mezclarse con el desfase")
            generateSequence { demuxer.readPacket() }.forEach { assertTrue(it.dtsUs <= it.ptsUs, "dts ${it.dtsUs} > pts ${it.ptsUs}") }
        }
    }

    /** La cabecera espera al primer fragmento: antes no se sabe cuánto hay que compensar. */
    @Test
    fun `the header waits for the first fragment only with unsigned offsets`() {
        val sizes = HashMap<Boolean, Long>()
        for (nonNegative in listOf(false, true)) {
            write(File(dir, "cabecera-$nonNegative.mp4"), nonNegative) { sizes[nonNegative] = it.length() }
        }
        assertTrue(sizes.getValue(false) > 0, "el modo firmado escribe la cabecera en start()")
        assertEquals(0L, sizes.getValue(true))
    }
}
