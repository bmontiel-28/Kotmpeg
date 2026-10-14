package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Una pista que arranca después que las demás —un micrófono que entra medio segundo tarde— en
 * MP4 fragmentado. `FragmentedMp4Muxer` empezaba el tiempo de decodificación de todas las pistas
 * en cero y metía ese medio segundo en el desfase de composición: la presentación salía bien,
 * pero el audio quedaba con el DTS medio segundo por detrás del PTS. `seekTo`, que en audio
 * busca por DTS, lo colocaba ese medio segundo tarde, y la duración de la pista no lo contaba.
 * Ahora el tiempo de decodificación empieza donde empieza la pista, como en MP4 plano, y el
 * demuxer descuenta ese desfase en los fMP4 que ya se escribieron así.
 */
class LateStartTrackTest {

    @TempDir
    lateinit var dir: File

    private val avcC = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22)))

    /**
     * Cuatro segundos de vídeo a 25 fps, keyframe cada segundo, y AAC desde el medio segundo. Con
     * [gap], el audio se calla en ese intervalo, como un micrófono en pausa.
     */
    private fun write(file: File, gap: LongRange = LongRange.EMPTY, options: MuxerOptions.() -> Unit) {
        Kotmpeg.createMuxer(file, options = options).use { m ->
            val v = m.addTrack(TrackInfo.Video(VideoCodec.H264, 64, 48) { frameRate = 25.0; codecPrivate = avcC })
            val a = m.addTrack(TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) })
            m.start()
            var audioUs = 500_000L
            for (i in 0 until 100) {
                val pts = i * 40_000L
                while (audioUs <= pts) {
                    if (audioUs !in gap) m.writePacket(MediaPacket(a, ByteArray(10) { (audioUs / 1000).toByte() }, audioUs, isKeyFrame = true))
                    audioUs += 21_333
                }
                m.writePacket(MediaPacket(v, NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x41, i.toByte()))), pts, isKeyFrame = i % 25 == 0))
            }
        }
    }

    private fun timesOf(file: File): Pair<Long, List<String>> = Kotmpeg.openDemuxer(file).use { d ->
        d.durationUs to generateSequence { d.readPacket() }.map { "${it.trackId} ${it.ptsUs} ${it.dtsUs}" }.toList()
    }

    /** Tras buscar cada segundo, el audio empieza en el paquete que suena en el keyframe. */
    private fun assertSeeksLikeMp4(file: File) {
        val audio = Kotmpeg.openDemuxer(file).use { d -> generateSequence { d.readPacket() }.filter { it.trackId == 2 }.map { it.ptsUs }.toList() }
        Kotmpeg.openDemuxer(file).use { d ->
            for (target in listOf(0L, 1_000_000L, 2_500_000L, 3_000_000L)) {
                val landed = d.seekTo(target)
                val first = generateSequence { d.readPacket() }.first { it.trackId == 2 }.ptsUs
                val expected = audio.lastOrNull { it <= landed } ?: audio.first()
                assertEquals(expected, first, "${file.name}: tras seekTo($target) el audio empieza en $first µs y no en el que suena en $landed")
            }
        }
    }

    @Test
    fun `a late track reads back with the same times and duration in fmp4 as in mp4`() {
        val plain = File(dir, "plano.mp4").also { write(it) { creationTimeMillis = 0 } }
        val (duration, times) = timesOf(plain)
        assertTrue(times.first { it.startsWith("2 ") }.endsWith(" 500000 500000"), "el MP4 plano de referencia no empieza el audio en 0,5 s")
        for (nonNegative in listOf(false, true)) {
            val fragmented = File(dir, "frag-$nonNegative.mp4").also { write(it) { fragmented = true; nonNegativeCompositionOffsets = nonNegative } }
            val (fragmentedDuration, fragmentedTimes) = timesOf(fragmented)
            assertEquals(times.filter { it.startsWith("2 ") }, fragmentedTimes.filter { it.startsWith("2 ") }, "sin signo: $nonNegative")
            assertEquals(duration, fragmentedDuration, "sin signo: $nonNegative")
            assertSeeksLikeMp4(fragmented)
        }
    }

    /**
     * Lo mismo con una pausa del audio que cruza el corte de fragmento de los 2 s. Al cerrar el
     * fragmento aún no ha llegado el paquete siguiente, y la duración del último se estima: el
     * tiempo de decodificación del fragmento siguiente, encadenado al final del anterior, empezaba
     * 1,3 s antes que su presentación, y desde ahí la búsqueda colocaba el audio 1,3 s tarde. Ahora
     * cada fragmento se decodifica, como mínimo, desde su primer instante presentado.
     */
    @Test
    fun `a track that pauses across a fragment boundary reads back like in mp4`() {
        val gap = 1_200_000L until 2_500_000L
        val plain = File(dir, "pausa-plano.mp4").also { write(it, gap) { creationTimeMillis = 0 } }
        val (duration, times) = timesOf(plain)
        for (nonNegative in listOf(false, true)) {
            val fragmented = File(dir, "pausa-frag-$nonNegative.mp4").also {
                write(it, gap) { fragmented = true; nonNegativeCompositionOffsets = nonNegative }
            }
            val (fragmentedDuration, fragmentedTimes) = timesOf(fragmented)
            assertEquals(times.filter { it.startsWith("2 ") }, fragmentedTimes.filter { it.startsWith("2 ") }, "sin signo: $nonNegative")
            assertEquals(duration, fragmentedDuration, "sin signo: $nonNegative")
            assertSeeksLikeMp4(fragmented)
        }
    }

    /**
     * Un fMP4 escrito como lo hacía la librería hasta ahora —el tiempo de decodificación de la
     * pista desde cero y su retraso en el desfase de composición— se busca igual de bien. Se
     * obtiene del actual pasando ese retraso del `tfdt` a los desfases del `trun`, que es la única
     * diferencia entre los dos.
     */
    @Test
    fun `an fmp4 that carries the delay as composition offset still seeks right`() {
        val current = File(dir, "actual.mp4").also { write(it) { fragmented = true } }
        val bytes = current.readBytes()
        val delayTicks = 24_000L
        moveDelayIntoCompositionOffsets(bytes, trackId = 2, delayTicks = delayTicks)
        val old = File(dir, "como-antes.mp4").also { it.writeBytes(bytes) }

        val (_, oldTimes) = timesOf(old)
        val audio = oldTimes.filter { it.startsWith("2 ") }.map { it.split(' ') }
        assertEquals("500000", audio.first()[1], "el archivo parcheado no presenta el audio en 0,5 s")
        assertEquals("0", audio.first()[2], "el archivo parcheado no reproduce la disposición antigua")
        assertSeeksLikeMp4(old)
    }

    private fun u32(b: ByteArray, at: Int): Long = (0 until 4).fold(0L) { v, k -> (v shl 8) or (b[at + k].toLong() and 0xFF) }

    private fun putU32(b: ByteArray, at: Int, value: Long) {
        for (k in 0 until 4) b[at + k] = (value ushr (8 * (3 - k))).toByte()
    }

    /** En cada `traf` de [trackId]: `tfdt` menos [delayTicks] y cada desfase del `trun` más [delayTicks]. */
    private fun moveDelayIntoCompositionOffsets(bytes: ByteArray, trackId: Long, delayTicks: Long) {
        fun children(start: Int, end: Int): List<Triple<Int, Int, String>> {
            val out = ArrayList<Triple<Int, Int, String>>()
            var p = start
            while (p + 8 <= end) {
                val size = u32(bytes, p).toInt()
                out += Triple(p, size, String(bytes, p + 4, 4, Charsets.ISO_8859_1))
                p += size
            }
            return out
        }
        var patched = 0
        for ((moof, moofSize, type) in children(0, bytes.size)) {
            if (type != "moof") continue
            for ((traf, trafSize, trafType) in children(moof + 8, moof + moofSize)) {
                if (trafType != "traf") continue
                val boxes = children(traf + 8, traf + trafSize)
                val tfhd = boxes.first { it.third == "tfhd" }.first
                if (u32(bytes, tfhd + 12) != trackId) continue
                val tfdt = boxes.first { it.third == "tfdt" }.first
                assertEquals(1, bytes[tfdt + 8].toInt(), "se esperaba un tfdt de versión 1")
                val decode = (u32(bytes, tfdt + 12) shl 32) or u32(bytes, tfdt + 16)
                val shifted = decode - delayTicks
                putU32(bytes, tfdt + 12, shifted ushr 32)
                putU32(bytes, tfdt + 16, shifted and 0xFFFFFFFFL)
                val trun = boxes.first { it.third == "trun" }.first
                val flags = u32(bytes, trun + 8) and 0xFFFFFF
                assertEquals(0xF01L, flags, "disposición del trun inesperada")
                val count = u32(bytes, trun + 12).toInt()
                for (s in 0 until count) {
                    val at = trun + 20 + s * 16 + 12
                    putU32(bytes, at, u32(bytes, at) + delayTicks)
                }
                patched++
            }
        }
        assertTrue(patched > 0, "no se encontró ningún fragmento de la pista $trackId")
    }
}
