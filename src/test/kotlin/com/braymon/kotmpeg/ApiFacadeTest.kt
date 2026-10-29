package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.Mp4Muxer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lo que la fachada [Kotmpeg] decide antes de tocar un archivo: con qué formato se lee o se escribe,
 * qué pistas se copian y si la salida es una de las entradas.
 */
class ApiFacadeTest {

    @TempDir
    lateinit var dir: File

    private fun writeSample(file: File, fastStart: Boolean = false) {
        Mp4Muxer(file) {
            creationTimeMillis = null
            this.fastStart = fastStart
        }.use { muxer ->
            val v = muxer.addTrack(
                TrackInfo.Video(VideoCodec.H264, 320, 240) {
                    codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22)))
                },
            )
            val a = muxer.addTrack(TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) })
            muxer.start()
            for (i in 0 until 30) {
                muxer.writePacket(MediaPacket(v, NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x65, i.toByte()))), i * 33_333L, isKeyFrame = i % 10 == 0))
                muxer.writePacket(MediaPacket(a, ByteArray(8) { i.toByte() }, i * 21_333L, isKeyFrame = true))
            }
        }
    }

    private fun packets(input: File): List<String> = Kotmpeg.openDemuxer(input).use { demuxer ->
        generateSequence { demuxer.readPacket() }.map { "${it.trackId}/${it.ptsUs}/${it.data.contentHashCode()}" }.toList()
    }

    /**
     * Un filtro con estado —que selecciona las primeras pistas que ve, por ejemplo— seleccionaba
     * todo al comprobar y nada al copiar, porque se le preguntaba dos veces por cada pista. El error
     * llegaba con la salida ya abierta, y un archivo que existía quedaba vacío.
     */
    @Test
    fun `remux asks the track filter once per track`() {
        val input = File(dir, "entrada.mp4").also(::writeSample)
        val output = File(dir, "salida.mkv").apply { writeBytes(ByteArray(100) { 7 }) }
        var asked = 0
        val written = Kotmpeg.remux(input, output, trackFilter = { asked++ < 2 })
        assertEquals(2, asked, "el filtro se evaluó $asked veces para dos pistas")
        assertEquals(60L, written)
        assertEquals(2, Kotmpeg.openDemuxer(output).use { it.tracks.size })
    }

    /**
     * Un enlace duro es el mismo archivo con otro nombre, y ninguna ruta canónica lo delata: `remux`
     * hacia él truncaba la entrada mientras la leía.
     */
    @Test
    fun `the output cannot be a hard link to an input`() {
        val input = File(dir, "original.mp4").also(::writeSample)
        val link = File(dir, "enlace.mp4")
        val linked = runCatching { Files.createLink(link.toPath(), input.toPath()) }.isSuccess
        assumeTrue(linked, "este sistema de archivos no admite enlaces duros")
        val before = input.readBytes()

        assertFailsWith<IllegalArgumentException> { Kotmpeg.requireDistinct(listOf(input), link) }
        assertFailsWith<IllegalArgumentException> { Kotmpeg.remux(input, link) }
        val other = File(dir, "otro.mp4").also(::writeSample)
        assertFailsWith<IllegalArgumentException> { Kotmpeg.concat(listOf(other, input), link) }
        assertContentEquals(before, input.readBytes(), "la entrada cambió")

        Kotmpeg.requireDistinct(listOf(input), File(dir, "nueva.mp4"))
        Kotmpeg.requireDistinct(listOf(input), other)
    }

    /**
     * Un MP4 que empieza por `skip`, `pnot` o `uuid` en vez de `ftyp`. Se fabrica renombrando el
     * `ftyp` de uno de esta librería, que deja todos los desplazamientos donde estaban.
     */
    @Test
    fun `an mp4 whose first box is skip, pnot or uuid is detected and read`() {
        val original = File(dir, "original.mp4").also(::writeSample)
        val bytes = original.readBytes()
        assertEquals("ftyp", String(bytes, 4, 4, Charsets.US_ASCII))
        val ftypSize = bytes.copyOfRange(0, 4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        assertTrue(ftypSize >= 24, "el ftyp ($ftypSize bytes) no da para el usertype de un uuid")
        for (type in listOf("skip", "pnot", "uuid")) {
            val renamed = bytes.copyOf()
            type.toByteArray(Charsets.US_ASCII).copyInto(renamed, 4)
            val file = File(dir, "$type.mp4").apply { writeBytes(renamed) }
            assertEquals(ContainerFormat.MP4, Kotmpeg.detectFormat(file), type)
            assertEquals(packets(original), packets(file), "$type: no se lee igual que el original")
        }
    }

    /**
     * Un `.mov` con vista previa de verdad: el `pnot` dice qué átomo es la imagen, y ese átomo va
     * detrás, normalmente un `PICT` —FFmpeg acepta también `pict`— antes del `moov`. Se fabrica
     * partiendo en dos el `ftyp` de un MP4 con inicio rápido, que deja el `moov` justo detrás y todos
     * los desplazamientos donde estaban: un `pnot` de 20 bytes, con sus campos, y la imagen en el
     * resto.
     *
     * Sin un `pnot` delante, una caja de imagen no prueba nada, como las de relleno.
     */
    @Test
    fun `a mov with a preview, pnot then its picture, is detected and read`() {
        val original = File(dir, "original.mp4").also { writeSample(it, fastStart = true) }
        val bytes = original.readBytes()
        val ftypSize = bytes.copyOfRange(0, 4).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        assertEquals("ftyp", String(bytes, 4, 4, Charsets.US_ASCII))
        assertEquals("moov", String(bytes, ftypSize + 4, 4, Charsets.US_ASCII))
        val pnotSize = 20
        assertTrue(ftypSize >= pnotSize + 8, "el ftyp ($ftypSize bytes) no da para un pnot y una imagen")
        for (picture in listOf("PICT", "pict")) {
            val preview = bytes.copyOf()
            ByteArray(ftypSize).copyInto(preview)
            byteArrayOf(0, 0, 0, pnotSize.toByte()).copyInto(preview, 0)
            "pnot".toByteArray(Charsets.US_ASCII).copyInto(preview, 4)
            picture.toByteArray(Charsets.US_ASCII).copyInto(preview, 14)
            preview[19] = 1
            byteArrayOf(0, 0, 0, (ftypSize - pnotSize).toByte()).copyInto(preview, pnotSize)
            picture.toByteArray(Charsets.US_ASCII).copyInto(preview, pnotSize + 4)
            val file = File(dir, "vista-$picture.mov").apply { writeBytes(preview) }
            assertEquals(ContainerFormat.MP4, Kotmpeg.detectFormat(file), picture)
            assertEquals(packets(original), packets(file), "$picture: no se lee igual que el original")

            val alone = bytes.copyOf().also { picture.toByteArray(Charsets.US_ASCII).copyInto(it, 4) }
            assertNull(Kotmpeg.detectFormat(File(dir, "sin-pnot-$picture.mov").apply { writeBytes(alone) }), "$picture sin pnot")
        }
    }

    /**
     * Las cajas de relleno solas no prueban nada: si su tamaño no cabe en el archivo, o lo que viene
     * detrás no es una caja de MP4, el archivo sigue siendo desconocido.
     */
    @Test
    fun `a leading skip box alone does not make a file an mp4`() {
        fun box(size: Int, type: String, total: Int): ByteArray = ByteArray(total).also { out ->
            for (i in 0 until 4) out[i] = (size ushr (24 - 8 * i)).toByte()
            type.toByteArray(Charsets.US_ASCII).copyInto(out, 4)
        }
        val cases = mapOf(
            "tamaño mayor que el archivo" to box(4_000, "skip", 64),
            "tamaño menor que su cabecera" to box(4, "skip", 64),
            "uuid sin usertype" to box(16, "uuid", 64),
            "basura detrás" to box(16, "skip", 64).also { "zzzz".toByteArray(Charsets.US_ASCII).copyInto(it, 20) },
            "nada detrás" to box(64, "skip", 64),
        )
        for ((name, bytes) in cases) {
            assertNull(Kotmpeg.detectFormat(File(dir, "caso.bin").apply { writeBytes(bytes) }), name)
        }
        val followed = box(16, "skip", 64).also { bytes ->
            byteArrayOf(0, 0, 0, 48).copyInto(bytes, 16)
            "moov".toByteArray(Charsets.US_ASCII).copyInto(bytes, 20)
        }
        assertEquals(ContainerFormat.MP4, Kotmpeg.detectFormat(File(dir, "seguido.bin").apply { writeBytes(followed) }))
    }

    /** Un nombre sin punto no tiene extensión: un archivo llamado `mp4` a secas no es un MP4. */
    @Test
    fun `a file name without a dot has no extension`() {
        for (name in listOf("mp4", "mkv", "MOV", "m4a")) assertNull(ContainerFormat.fromFileName(name), name)
        assertEquals(ContainerFormat.MKV, ContainerFormat.fromFileName("grabacion.mkv"))
        assertEquals(ContainerFormat.MP4, ContainerFormat.fromFileName("clip.MP4"))
        assertNull(ContainerFormat.fromFileName("sin-extension"))

        val bare = File(dir, "mkv")
        val error = assertFailsWith<IllegalArgumentException> { Kotmpeg.createMuxer(bare) }
        assertTrue("no se puede deducir" in error.message.orEmpty(), "${error.message}")
        assertFalse(bare.exists(), "createMuxer creó el archivo antes de rechazarlo")
        val webm = assertFailsWith<IllegalArgumentException> { Kotmpeg.createMuxer(File(dir, "webm")) }
        assertFalse("WebM solo admite" in webm.message.orEmpty(), "un archivo llamado webm no es un WebM: ${webm.message}")
    }
}
