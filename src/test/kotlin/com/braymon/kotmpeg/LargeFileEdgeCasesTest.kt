package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ColorInfo
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.HdrStaticInfo
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Los caminos que un archivo normal nunca ejercita.
 *
 * A diferencia de [RobustnessTest], que ataca a lo ancho (fuzzing, truncados), aquí cada test
 * apunta a **uno** concreto: descriptores que no se cierran cuando algo falla a mitad, cuentas
 * de 64 bits, y las variantes de caja que solo aparecen en archivos grandes (`co64`, `mdat` con
 * `largesize`). Ninguno se alcanza grabando y reproduciendo, y todos vienen de un fallo real.
 */
class LargeFileEdgeCasesTest {

    @TempDir
    lateinit var dir: File

    private val avcC = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    private fun videoTrack(width: Int = 320) = TrackInfo.Video(
        codec = VideoCodec.H264, width = width, height = 240, frameRate = 30.0, codecPrivate = avcC,
    )

    private fun payload(i: Int) = NalUnits.joinLengthPrefixed(listOf(ByteArray(64) { (i + it).toByte() }))

    private fun writeVideo(file: File, track: TrackInfo.Video = videoTrack(), frames: Int = 8): File {
        MkvKotlin.createMuxer(file).use { muxer ->
            val id = muxer.addTrack(track)
            muxer.start()
            for (i in 0 until frames) {
                muxer.writePacket(
                    MediaPacket(id, payload(i), i * 33_333L, i * 33_333L, i % 4 == 0, 33_333L),
                )
            }
        }
        return file
    }

    /**
     * Cuenta los descriptores abiertos que apuntan a [file]. Solo Linux (`/proc`); en el
     * resto el test se salta, que es preferible a dar por bueno lo que no se ha medido.
     *
     * En Windows este mismo fallo se manifestaba como un error al borrar el directorio
     * temporal; en Linux el borrado funciona igual con el archivo abierto, así que la fuga
     * pasaba desapercibida y hacía falta mirar los descriptores directamente.
     */
    private fun openDescriptorsFor(file: File): Int {
        val fdDir = File("/proc/self/fd")
        assumeTrue(fdDir.isDirectory, "solo medible en Linux")
        System.gc()
        val target = file.canonicalPath
        return fdDir.listFiles().orEmpty().count { fd ->
            runCatching { fd.canonicalPath }.getOrNull() == target
        }
    }

    @Test
    fun `concat closes the output file when the segments turn out to be incompatible`() {
        val a = writeVideo(File(dir, "a.mkv"))
        val b = writeVideo(File(dir, "b.mkv"), videoTrack(width = 640))
        val out = File(dir, "bad-concat.mkv")

        val result = runCatching { MkvKotlin.concat(listOf(a, b), out) }
        assertTrue(result.isFailure, "un desajuste de dimensiones debe rechazarse")
        assertEquals(0, openDescriptorsFor(out), "el muxer de salida quedó abierto tras fallar")
    }

    @Test
    fun `remux closes the output file when no track survives the filter`() {
        val input = writeVideo(File(dir, "in.mkv"))
        val out = File(dir, "bad-remux.mp4")

        val result = runCatching { MkvKotlin.remux(input, out, trackFilter = { false }) }
        assertTrue(result.isFailure, "sin pistas seleccionadas el remux debe fallar")
        assertEquals(0, openDescriptorsFor(out), "el muxer de salida quedó abierto tras fallar")
    }

    private fun ebmlHeaderOf(file: File): ByteArray = file.readBytes()

    /**
     * Un tamaño de elemento por encima de 2^31 debe tratarse como `Long`. Truncado a `Int`
     * salía negativo, y el error resultante no tenía nada que ver con la causa real.
     *
     * Se parchea `Tracks` y no `Segment`: el elemento raíz admite tamaño desconocido por
     * diseño (es lo que permite grabar en streaming), así que no pasa por la validación que
     * aquí se quiere ejercitar.
     */
    @Test
    fun `an mkv element larger than 2 GiB is reported as out of bounds, not truncated to int`() {
        val file = writeVideo(File(dir, "huge-size.mkv"))
        val bytes = file.readBytes()

        val tracksId = byteArrayOf(0x16, 0x54.toByte(), 0xAE.toByte(), 0x6B)
        val at = lastIndexOf(bytes, tracksId)
        assertTrue(at >= 0, "no se encontró el elemento Tracks")

        val out = ByteArrayOutputStream()
        out.write(bytes, 0, at + 4)
        out.write(byteArrayOf(0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00))
        val oldSizeLen = vintLength(bytes[at + 4])
        out.write(bytes, at + 4 + oldSizeLen, bytes.size - at - 4 - oldSizeLen)

        val broken = File(dir, "huge-size-broken.mkv").also { it.writeBytes(out.toByteArray()) }
        val error = assertFailsWith<Exception> { MkvKotlin.openDemuxer(broken).use { it.tracks } }
        assertTrue(
            error.message.orEmpty().contains("más allá del final"),
            "se esperaba un error de tamaño fuera del archivo, y llegó: ${error.message}",
        )
    }

    /** Los IDs de EBML son de 4 octetos como mucho; uno más largo es corrupción. */
    @Test
    fun `an ebml id longer than four octets is rejected`() {
        val file = writeVideo(File(dir, "long-id.mkv"))
        val bytes = file.readBytes()
        val segmentId = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67)
        val at = indexOf(bytes, segmentId)
        assertTrue(at >= 0)

        bytes[at] = 0x08
        val broken = File(dir, "long-id-broken.mkv").also { it.writeBytes(bytes) }
        assertFailsWith<Exception> { MkvKotlin.openDemuxer(broken).use { it.tracks } }
    }

    private fun vintLength(first: Byte): Int {
        val v = first.toInt() and 0xFF
        for (i in 0 until 8) if (v and (0x80 shr i) != 0) return i + 1
        return 8
    }

    private fun lastIndexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in haystack.size - needle.size downTo 0) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /**
     * Con más de ~49,7 días de contenido la duración deja de caber en los 32 bits de las
     * cajas versión 0, y `mvhd`/`tkhd`/`mdhd` tienen que emitirse en versión 1.
     *
     * Se comprueban las tres cosas que pueden salir mal por separado: que el byte de versión
     * sea realmente 1, que el archivo siga siendo legible, y que la duración vuelva sin
     * truncarse — un round trip que pase pero con la duración recortada sería un falso verde.
     */
    @Test
    fun `durations beyond 32 bits are written as version 1 boxes and survive the round trip`() {
        val farOutUs = 5_000_000_000_000L
        val file = File(dir, "long.mp4")
        MkvKotlin.createMuxer(file, ContainerFormat.MP4).use { muxer ->
            val id = muxer.addTrack(videoTrack())
            muxer.start()
            muxer.writePacket(MediaPacket(id, payload(0), 0, 0, true, 33_333L))
            muxer.writePacket(MediaPacket(id, payload(1), farOutUs, farOutUs, true, 33_333L))
        }

        val bytes = file.readBytes()
        for (type in listOf("mvhd", "tkhd", "mdhd")) {
            val at = indexOf(bytes, type.toByteArray(Charsets.US_ASCII))
            assertTrue(at >= 0, "no se encontró la caja $type")
            assertEquals(1, bytes[at + 4].toInt(), "$type debería ir en versión 1")
        }

        MkvKotlin.openDemuxer(file).use { demuxer ->
            assertEquals(1, demuxer.tracks.size)
            val packets = generateSequence { demuxer.readPacket() }.toList()
            assertEquals(2, packets.size)
            assertTrue(
                demuxer.durationUs >= farOutUs,
                "la duración de 64 bits se truncó al releer: ${demuxer.durationUs}",
            )
        }
    }

    private fun u32(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xFF) shl 24) or ((data[at + 1].toLong() and 0xFF) shl 16) or
            ((data[at + 2].toLong() and 0xFF) shl 8) or (data[at + 3].toLong() and 0xFF)

    private fun putU32(data: ByteArray, at: Int, v: Long) {
        data[at] = ((v shr 24) and 0xFF).toByte(); data[at + 1] = ((v shr 16) and 0xFF).toByte()
        data[at + 2] = ((v shr 8) and 0xFF).toByte(); data[at + 3] = (v and 0xFF).toByte()
    }

    private fun ByteArrayOutputStream.u32(v: Long) {
        write(((v shr 24) and 0xFF).toInt()); write(((v shr 16) and 0xFF).toInt())
        write(((v shr 8) and 0xFF).toInt()); write((v and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.u64(v: Long) {
        u32((v ushr 32) and 0xFFFFFFFFL); u32(v and 0xFFFFFFFFL)
    }

    private val containerBoxes = setOf("moov", "trak", "mdia", "minf", "stbl", "edts")

    /**
     * Tamaño total de la caja en [at] y longitud de su cabecera. `size == 1` significa que
     * el tamaño real va en un `largesize` de 64 bits detrás del tipo — que es justo lo que
     * escribe siempre nuestro `mdat`, así que un recorrido que no lo contemple no pasa de
     * la primera caja de datos.
     */
    private fun boxSizeAndHeader(data: ByteArray, at: Int): Pair<Long, Int> {
        val declared = u32(data, at)
        return when (declared) {
            1L -> {
                var v = 0L
                for (i in 0 until 8) v = (v shl 8) or (data[at + 8 + i].toLong() and 0xFF)
                v to 16
            }
            0L -> (data.size - at).toLong() to 8
            else -> declared to 8
        }
    }

    /** Cadena de offsets de caja, de la raíz hasta [type]. Necesaria para parchear tamaños. */
    private fun pathTo(data: ByteArray, type: String, start: Int = 0, end: Int = data.size): List<Int>? {
        var p = start
        while (p + 8 <= end) {
            val (size, headerLen) = boxSizeAndHeader(data, p)
            if (size < headerLen || p + size > end) return null
            val t = String(data, p + 4, 4, Charsets.US_ASCII)
            if (t == type) return listOf(p)
            if (t in containerBoxes) {
                pathTo(data, type, p + headerLen, (p + size).toInt())?.let { return listOf(p) + it }
            }
            p += size.toInt()
        }
        return null
    }

    /**
     * Reescribe el `stco` del archivo como `co64`, creciendo las cajas contenedoras.
     *
     * El muxer solo emite `co64` cuando los offsets pasan de 4 GiB, así que sin esta
     * transformación haría falta un archivo de ese tamaño para ejercitar la lectura. Como el
     * `moov` va al final (sin faststart), agrandarlo no mueve el `mdat` y los offsets de
     * chunk siguen siendo válidos tal cual.
     */
    private fun stcoToCo64(data: ByteArray): ByteArray {
        val path = assertNotNull(pathTo(data, "stco"), "el archivo debería tener un stco")
        val start = path.last()
        val oldSize = u32(data, start).toInt()
        val count = u32(data, start + 12).toInt()

        val box = ByteArrayOutputStream()
        box.u32((16 + count * 8).toLong())
        box.write("co64".toByteArray(Charsets.US_ASCII))
        box.u32(0)
        box.u32(count.toLong())
        for (i in 0 until count) box.u64(u32(data, start + 16 + i * 4))
        val replacement = box.toByteArray()

        val result = data.copyOfRange(0, start) + replacement + data.copyOfRange(start + oldSize, data.size)
        val delta = replacement.size - oldSize
        for (ancestor in path.dropLast(1)) putU32(result, ancestor, u32(result, ancestor) + delta)
        return result
    }

    /**
     * Convierte la cabecera del `mdat` de la forma de 64 bits (`size == 1` + `largesize`)
     * a la compacta de 32 bits.
     *
     * Nuestro muxer escribe **siempre** largesize, así que la forma compacta —la que emiten
     * casi todos los demás muxers— no la ejercitaba ningún test pese a ser la que más se
     * encontrará en la práctica al leer archivos ajenos. Quitar esos 8 bytes desplaza todo
     * lo que va detrás, incluidas las muestras, así que hay que restar 8 a cada offset de
     * chunk o el test estaría midiendo el desajuste en vez del camino que quiere cubrir.
     */
    private fun mdatToCompactSize(data: ByteArray): ByteArray {
        var p = 0
        var mdatStart = -1
        var mdatSize = 0L
        while (p + 8 <= data.size) {
            val (size, _) = boxSizeAndHeader(data, p)
            if (size < 8) break
            if (String(data, p + 4, 4, Charsets.US_ASCII) == "mdat") {
                mdatStart = p; mdatSize = size; break
            }
            p += size.toInt()
        }
        assertTrue(mdatStart >= 0, "el archivo debería tener un mdat")
        assertEquals(1L, u32(data, mdatStart), "se esperaba un mdat con largesize de 64 bits")
        assertTrue(mdatSize - 8 <= 0xFFFFFFFFL, "el mdat no cabe en un tamaño de 32 bits")

        val header = ByteArrayOutputStream()
        header.u32(mdatSize - 8)
        header.write("mdat".toByteArray(Charsets.US_ASCII))

        val result = data.copyOfRange(0, mdatStart) + header.toByteArray() +
            data.copyOfRange(mdatStart + 16, data.size)

        val path = assertNotNull(pathTo(result, "stco"), "el archivo debería tener un stco")
        val start = path.last()
        val count = u32(result, start + 12).toInt()
        for (i in 0 until count) {
            val at = start + 16 + i * 4
            putU32(result, at, u32(result, at) - 8)
        }
        return result
    }

    @Test
    fun `a 64 bit chunk offset table reads back the same packets as the 32 bit one`() {
        val source = File(dir, "co64-src.mp4")
        MkvKotlin.createMuxer(source, ContainerFormat.MP4).use { muxer ->
            val id = muxer.addTrack(videoTrack())
            muxer.start()
            for (i in 0 until 10) {
                muxer.writePacket(MediaPacket(id, payload(i), i * 33_333L, i * 33_333L, i % 4 == 0, 33_333L))
            }
        }
        val original = MkvKotlin.openDemuxer(source).use { d -> generateSequence { d.readPacket() }.toList() }
        assertEquals(10, original.size)

        val patched = File(dir, "co64.mp4").also { it.writeBytes(stcoToCo64(source.readBytes())) }
        MkvKotlin.openDemuxer(patched).use { demuxer ->
            val packets = generateSequence { demuxer.readPacket() }.toList()
            assertEquals(original.size, packets.size, "co64 perdió muestras")
            for (i in original.indices) {
                assertEquals(original[i].ptsUs, packets[i].ptsUs, "pts distinto en la muestra $i")
                assertTrue(original[i].data.contentEquals(packets[i].data), "datos distintos en la muestra $i")
            }
        }
    }

    @Test
    fun `an mdat with a compact 32 bit header is parsed like our own largesize one`() {
        val source = File(dir, "compact-src.mp4")
        MkvKotlin.createMuxer(source, ContainerFormat.MP4).use { muxer ->
            val id = muxer.addTrack(videoTrack())
            muxer.start()
            for (i in 0 until 6) {
                muxer.writePacket(MediaPacket(id, payload(i), i * 33_333L, i * 33_333L, true, 33_333L))
            }
        }
        val original = MkvKotlin.openDemuxer(source).use { d -> generateSequence { d.readPacket() }.toList() }
        assertEquals(6, original.size)

        val patched = File(dir, "compact.mp4").also { it.writeBytes(mdatToCompactSize(source.readBytes())) }
        MkvKotlin.openDemuxer(patched).use { demuxer ->
            val packets = generateSequence { demuxer.readPacket() }.toList()
            assertEquals(original.size, packets.size, "el mdat de 32 bits perdió muestras")
            for (i in original.indices) {
                assertTrue(
                    original[i].data.contentEquals(packets[i].data),
                    "datos distintos en la muestra $i: los offsets no siguieron al desplazamiento",
                )
            }
        }
    }

    @Test
    fun `mastering luminance is rejected above what the mdcv box can represent`() {
        fun hdr(max: Double, min: Double = 0.005) = HdrStaticInfo(
            redX = 0.708, redY = 0.292, greenX = 0.170, greenY = 0.797,
            blueX = 0.131, blueY = 0.046, whiteX = 0.3127, whiteY = 0.3290,
            maxMasteringLuminance = max, minMasteringLuminance = min,
        )

        assertNotNull(hdr(HdrStaticInfo.MAX_MASTERING_LUMINANCE))
        assertFailsWith<IllegalArgumentException> { hdr(HdrStaticInfo.MAX_MASTERING_LUMINANCE + 1.0) }
        assertFailsWith<IllegalArgumentException> { hdr(-1.0) }
        assertFailsWith<IllegalArgumentException> { hdr(max = 100.0, min = 500.0) }

        val track = videoTrack().copy(color = ColorInfo.hdr10(hdr(1000.0)))
        val file = File(dir, "hdr.mp4")
        MkvKotlin.createMuxer(file, ContainerFormat.MP4).use { muxer ->
            val id = muxer.addTrack(track)
            muxer.start()
            muxer.writePacket(MediaPacket(id, payload(0), 0, 0, true, 33_333L))
        }
        MkvKotlin.openDemuxer(file).use { demuxer ->
            val video = demuxer.tracks.filterIsInstance<TrackInfo.Video>().single()
            assertEquals(1000.0, video.color?.hdr?.maxMasteringLuminance)
        }
    }

    /**
     * Un `stsz` de tamaño constante no lleva tabla, así que su cuenta no se puede contrastar
     * con el tamaño de la caja. La cota real es física: cada muestra ocupa `constant` bytes
     * en el archivo. Antes se aceptaba cualquier cuenta hasta el tamaño del archivo, lo que
     * con un archivo mediano bastaba para reservar cientos de MB.
     */
    @Test
    fun `a constant size stsz with an absurd sample count does not allocate beyond the file`() {
        val source = File(dir, "stsz-src.mp4")
        MkvKotlin.createMuxer(source, ContainerFormat.MP4).use { muxer ->
            val id = muxer.addTrack(TrackInfo.Audio(
                codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2,
                codecPrivate = AacConfig.build(48000, 2),
            ))
            muxer.start()
            for (i in 0 until 20) {
                muxer.writePacket(MediaPacket(id, ByteArray(64) { i.toByte() }, i * 21_333L, i * 21_333L, true, 21_333L))
            }
        }

        val data = source.readBytes()
        val path = assertNotNull(pathTo(data, "stsz"))
        val start = path.last()
        putU32(data, start + 12, 64)
        putU32(data, start + 16, 1_000_000_000L)

        val broken = File(dir, "stsz-broken.mp4").also { it.writeBytes(data) }
        MkvKotlin.openDemuxer(broken).use { demuxer ->
            val packets = generateSequence { demuxer.readPacket() }.take(5_000).toList()
            assertTrue(
                packets.size < 100_000,
                "la cuenta declarada se aceptó sin acotar: ${packets.size} muestras",
            )
        }
    }
}
