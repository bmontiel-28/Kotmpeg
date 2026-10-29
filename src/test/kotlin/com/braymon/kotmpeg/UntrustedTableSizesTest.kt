package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.ebml.EbmlWriter
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.io.TempDir
import java.io.File

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Resistencia a las cuentas que declara el propio archivo.
 *
 * Tablas de muestras que dicen contener más de lo que el archivo tiene, y series de
 * `stts`/`ctts` cuya cuenta es un uint32 sin acotar. Son entradas perfectamente alcanzables —un
 * fMP4 cuya grabación se cortó, un archivo que el usuario elige con el selector del sistema— y
 * se traducían en una excepción a mitad de lectura o en segundos de CPU al abrir.
 */
class UntrustedTableSizesTest {

    @TempDir
    lateinit var dir: File

    private fun videoTrack() = TrackInfo.Video(codec = VideoCodec.H264, width = 320, height = 240) {
        frameRate = 30.0
        codecPrivate = NalUnits.buildAvcC(
            listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
            listOf(byteArrayOf(0x68, 0x11, 0x22)),
        )
    }

    private fun audioTrack() = TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2) {
        codecPrivate = AacConfig.build(48000, 2)
    }

    /** NAL con prefijo de longitud, que es el formato canónico del modelo. */
    private fun payload(i: Int, size: Int = 40): ByteArray {
        val nal = ByteArray(size - 4) { (i + it).toByte() }
        return NalUnits.joinLengthPrefixed(listOf(nal))
    }

    private fun writeFragmented(file: File, packets: Int): File {
        Kotmpeg.createMuxer(file, ContainerFormat.MP4) { fragmented = true }.use { muxer ->
            val vid = muxer.addTrack(videoTrack())
            muxer.start()
            repeat(packets) { i ->
                muxer.writePacket(
                    MediaPacket(
                        trackId = vid,
                        data = payload(i),
                        ptsUs = i * 33_333L,
                        dtsUs = i * 33_333L,
                        isKeyFrame = i % 10 == 0,
                    ),
                )
            }
        }
        return file
    }

    /**
     * El caso que fMP4 existe para sobrevivir: el proceso muere escribiendo un fragmento, así
     * que el `moof` queda en disco declarando muestras cuyos bytes nunca llegaron al `mdat`.
     *
     * Se barre todo el rango de cortes en vez de fijar uno: el porcentaje concreto que
     * reventaba dependía de si el corte caía dentro de la carga o antes del `moof`, y esa
     * intermitencia es justo lo que hacía difícil de ver el fallo.
     */
    @Test
    fun `a truncated fragmented mp4 ends the stream instead of throwing`() {
        val full = writeFragmented(File(dir, "frag.mp4"), packets = 60)
        val bytes = full.readBytes()

        for (percent in 50..99) {
            val cut = File(dir, "cut-$percent.mp4")
            cut.writeBytes(bytes.copyOf(bytes.size * percent / 100))

            val read = ArrayList<MediaPacket>()
            Kotmpeg.openDemuxer(cut).use { demuxer ->
                while (true) {
                    val p = demuxer.readPacket() ?: break
                    read.add(p)
                }
            }
            for ((i, p) in read.withIndex()) {
                assertEquals(payload(i).size, p.data.size, "paquete $i truncado con corte al $percent%")
                assertTrue(
                    Math.abs(p.ptsUs - i * 33_333L) <= 1_000,
                    "pts del paquete $i fuera de sitio con corte al $percent%: ${p.ptsUs}",
                )
            }
        }
    }

    @Test
    fun `an intact fragmented mp4 still returns every packet`() {
        val full = writeFragmented(File(dir, "intact.mp4"), packets = 60)
        var count = 0
        Kotmpeg.openDemuxer(full).use { demuxer ->
            while (demuxer.readPacket() != null) count++
        }
        assertEquals(60, count, "un fMP4 íntegro no puede perder paquetes")
    }

    /**
     * Encontrado por el fuzzing: un fMP4 de 6 KB hacía reservar 230 MiB. Un `trun` sin campos por
     * muestra puede declarar tantas como bytes tiene el archivo, todas con la duración y el tamaño
     * por defecto, y un `tfdt` dañado les da tiempos de decodificación enormes. Convertirlos a µs
     * desbordaba en cada muestra, y el desbordamiento se detectaba capturando una
     * `ArithmeticException`, que reserva su traza: unos 3 KB por conversión, en cada pasada por el
     * fragmento. Ahora se detecta sin excepciones.
     *
     * El archivo se obtiene de uno sano cambiando lo mismo que la mutación: el `tfdt` del primer
     * fragmento y las banderas y la cuenta de su `trun`. Se mide la segunda lectura, no la primera:
     * la primera carga clases y eso también cuenta como memoria reservada por el hilo.
     */
    @Test
    fun `a fragment with enormous decode times does not allocate per sample`() {
        val bytes = writeFragmented(File(dir, "sano.mp4"), packets = 60).readBytes()
        val moof = boxAt(bytes, 0, bytes.size, "moof")
        val traf = boxAt(bytes, moof + 8, moof + u32(bytes, moof).toInt(), "traf")
        val trafEnd = traf + u32(bytes, traf).toInt()
        val tfdt = boxAt(bytes, traf + 8, trafEnd, "tfdt")
        val trun = boxAt(bytes, traf + 8, trafEnd, "trun")
        assertEquals(1, bytes[tfdt + 8].toInt(), "se esperaba un tfdt de versión 1")
        putU32(bytes, tfdt + 12, 0x00FF0000L)
        putU32(bytes, tfdt + 16, 0x00003C00L)
        putU32(bytes, trun + 8, 0x01000001L)
        putU32(bytes, trun + 12, 0x00120000L)
        val file = File(dir, "tiempos-enormes.mp4").also { it.writeBytes(bytes) }

        fun readAll(): Int = Kotmpeg.openDemuxer(file).use { d -> generateSequence { d.readPacket() }.count() }
        val packets = readAll()
        val allocated = allocatedBytes { assertEquals(packets, readAll()) }

        assertTrue(packets > 1_000, "el trun manipulado tenía que dar miles de muestras: $packets")
        assertTrue(allocated < 8L * 1024 * 1024, "leer ${bytes.size} bytes reservó ${allocated / 1024} KiB")
    }

    /**
     * `stts` declara la longitud de cada serie como uint32 sin acotar. Con `repeat`, el
     * `return@repeat` que cortaba solo saltaba a la vuelta siguiente, así que el bucle giraba
     * hasta agotar la cuenta declarada sin escribir nada: ~0,6 s de CPU en JVM de escritorio
     * con **una sola** entrada manipulada, y varios segundos en ART.
     */
    @Test
    fun `an inflated stts run length does not stall the parser`() {
        val file = File(dir, "stts.mp4")
        Kotmpeg.createMuxer(file, ContainerFormat.MP4).use { muxer ->
            val vid = muxer.addTrack(videoTrack())
            muxer.start()
            repeat(30) { i ->
                muxer.writePacket(
                    MediaPacket(vid, payload(i), i * 33_333L, i * 33_333L, isKeyFrame = i == 0),
                )
            }
        }

        val bytes = file.readBytes()
        val stts = indexOf(bytes, "stts")
        assertTrue(stts > 0, "no se encontró la caja stts")
        val firstCountAt = stts + 4 + 4 + 4
        val inflated = 2_000_000_000L
        val patched = bytes.copyOf()
        for (b in 0 until 4) {
            patched[firstCountAt + b] = ((inflated shr (8 * (3 - b))) and 0xFF).toByte()
        }
        val target = File(dir, "stts-inflado.mp4").also { it.writeBytes(patched) }

        val repeats = 5
        val baseline = timeOpening(file, repeats)
        val inflatedMs = timeOpening(target, repeats)
        assertTrue(
            inflatedMs < baseline + 400,
            "abrir con stts.count=$inflated costó ${inflatedMs}ms frente a ${baseline}ms del " +
                "mismo archivo sano: la serie declarada se está recorriendo entera",
        )
    }

    private fun timeOpening(file: File, repeats: Int): Long {
        val start = System.nanoTime()
        repeat(repeats) {
            Kotmpeg.openDemuxer(file).use { assertEquals(1, it.tracks.size) }
        }
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun `a normal file still gets its timestamps from stts`() {
        val file = File(dir, "normal.mp4")
        Kotmpeg.createMuxer(file, ContainerFormat.MP4).use { muxer ->
            val vid = muxer.addTrack(videoTrack())
            muxer.start()
            repeat(30) { i ->
                muxer.writePacket(
                    MediaPacket(vid, payload(i), i * 33_333L, i * 33_333L, isKeyFrame = i == 0),
                )
            }
        }
        Kotmpeg.openDemuxer(file).use { demuxer ->
            for (i in 0 until 30) {
                val p = assertNotNull(demuxer.readPacket(), "falta el paquete $i")
                assertTrue(
                    Math.abs(p.ptsUs - i * 33_333L) <= 1_000,
                    "pts del paquete $i fuera de sitio: ${p.ptsUs}",
                )
            }
            assertNull(demuxer.readPacket(), "no debería haber un paquete 31")
        }
    }

    /**
     * El remuestreador pasó de `ArrayList<Short>` (que boxeaba cada muestra) a un `ShortArray`
     * dimensionado de antemano. Lo que hay que fijar es que el redimensionado no pierde ni
     * inventa frames, porque quedarse corto truncaría la salida **y** dejaría la fase sin
     * avanzar, rompiendo la convergencia exacta que promete la clase.
     */
    @Test
    fun `the resampler emits exactly the frames its contract promises`() {
        for ((inRate, outRate) in listOf(44_100 to 48_000, 48_000 to 44_100, 8_000 to 48_000)) {
            val resampler = com.braymon.kotmpeg.audio.PcmResampler(inRate, outRate, channels = 2)
            var emitted = 0L
            var consumed = 0L
            for (chunkFrames in listOf(1, 7, 100, 333, 1024, 17)) {
                val input = ShortArray(chunkFrames * 2) { (it % 3000 - 1500).toShort() }
                val out = resampler.resample(input)
                assertEquals(0, out.size % 2, "salida no alineada a frames de 2 canales")
                emitted += out.size / 2
                consumed += chunkFrames
            }
            emitted += resampler.flush().size / 2
            val expected = Math.round(consumed.toDouble() * outRate / inRate)
            assertEquals(
                expected, emitted,
                "convergencia $inRate->$outRate: se esperaban $expected frames y salieron $emitted",
            )
        }
    }

    @Test
    fun `nal type helpers reject an empty nal with a useful message`() {
        val h264 = assertFailsWith<IllegalArgumentException> { NalUnits.h264NalType(ByteArray(0)) }
        assertTrue(h264.message.orEmpty().contains("NAL vacía"), "mensaje poco útil: ${h264.message}")
        val h265 = assertFailsWith<IllegalArgumentException> { NalUnits.h265NalType(ByteArray(0)) }
        assertTrue(h265.message.orEmpty().contains("NAL vacía"), "mensaje poco útil: ${h265.message}")
    }

    @Test
    fun `both vint size encoders reject the same inputs`() {
        val out = com.braymon.kotmpeg.io.SeekableOutput(File(dir, "vint.bin"))
        out.use {
            val writer = EbmlWriter(it)
            assertFailsWith<IllegalArgumentException>("writeVintSize aceptó un negativo") {
                writer.writeVintSize(-1L, 4)
            }
            assertFailsWith<IllegalArgumentException>("writeVintSize aceptó una longitud inválida") {
                writer.writeVintSize(1L, 0)
            }
        }
        assertFailsWith<IllegalArgumentException>("encodeVintSize aceptó un negativo") {
            EbmlWriter.encodeVintSize(-1L, 4)
        }
    }

    @Test
    fun `an unparseable track is reported through the warning callback`() {
        val file = File(dir, "twotracks.mp4")
        Kotmpeg.createMuxer(file, ContainerFormat.MP4).use { muxer ->
            val vid = muxer.addTrack(videoTrack())
            val aud = muxer.addTrack(audioTrack())
            muxer.start()
            repeat(10) { i ->
                muxer.writePacket(MediaPacket(vid, payload(i), i * 33_333L, i * 33_333L, true))
                muxer.writePacket(MediaPacket(aud, payload(i, 24), i * 21_333L, i * 21_333L, true))
            }
        }
        val bytes = file.readBytes()
        val at = indexOf(bytes, "mp4a")
        assertTrue(at > 0, "no se encontró la sample entry de audio")
        "zzzz".toByteArray(Charsets.US_ASCII).copyInto(bytes, at)
        val broken = File(dir, "broken.mp4").also { it.writeBytes(bytes) }

        val warnings = ArrayList<String>()
        com.braymon.kotmpeg.mp4.Mp4Demuxer(
            com.braymon.kotmpeg.io.SeekableInput(broken),
            onWarning = { warnings.add(it) },
        ).use { demuxer ->
            assertTrue(demuxer.tracks.any { it is TrackInfo.Video }, "se perdió también el vídeo")
        }
        assertTrue(warnings.isNotEmpty(), "la pista descartada no generó ningún aviso")
    }

    private fun indexOf(haystack: ByteArray, type: String): Int {
        val needle = type.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun u32(b: ByteArray, at: Int): Long = (0 until 4).fold(0L) { v, k -> (v shl 8) or (b[at + k].toLong() and 0xFF) }

    private fun putU32(b: ByteArray, at: Int, value: Long) {
        for (k in 0 until 4) b[at + k] = (value ushr (8 * (3 - k))).toByte()
    }

    /** Posición de la primera caja [type] entre [start] y [end], recorriendo solo ese nivel. */
    private fun boxAt(b: ByteArray, start: Int, end: Int, type: String): Int {
        var p = start
        while (p + 8 <= end) {
            if (String(b, p + 4, 4, Charsets.ISO_8859_1) == type) return p
            p += u32(b, p).toInt()
        }
        error("no hay caja $type entre $start y $end")
    }
}
