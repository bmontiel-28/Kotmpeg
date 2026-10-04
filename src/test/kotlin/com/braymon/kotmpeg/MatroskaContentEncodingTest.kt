package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.ebml.EbmlWriter
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.mkv.MkvDemuxer
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ContentEncoding` de Matroska y archivos cortados a mitad de un bloque.
 *
 * - **La compresión de contenido se ignoraba.** Un MKV con eliminación de cabecera —lo que
 *   `mkvmerge` aplicaba por defecto en sus versiones antiguas— entregaba fotogramas a los que les
 *   faltaban los primeros bytes, y uno con zlib entregaba los datos comprimidos. Nada lo delataba:
 *   el decodificador recibía basura. Ahora se deshacen las dos, y una pista cifrada o con un
 *   algoritmo no soportado se descarta con aviso en vez de entregarse rota.
 * - **Deshacer zlib abre la puerta a una bomba de descompresión**: unos pocos KB que se convierten
 *   en cientos de MB al leer un solo bloque. Por eso hay un techo de 64 MiB por fotograma, y los
 *   dos lados del límite están fijados aquí.
 * - **Un bloque cortado lanzaba.** La política documentada del demuxer es que un archivo truncado
 *   se lea hasta donde llegue, pero solo se protegía la cabecera de cada elemento: si el corte caía
 *   dentro de la carga de un `SimpleBlock`, `readPacket` lanzaba en el caso que esa política existe
 *   para cubrir.
 */
class MatroskaContentEncodingTest {

    @TempDir
    lateinit var dir: File

    private companion object {
        /**
         * El techo por fotograma que documentan `SECURITY.md` y el CHANGELOG. Se repite aquí en vez
         * de leer la constante del demuxer a propósito: si alguien la cambia, este test falla y
         * obliga a cambiar también lo que se ha prometido por escrito.
         */
        const val INFLATE_CEILING = 64L * 1024 * 1024
    }

    private val frames = List(6) { i -> ByteArray(40) { k -> (0x55 + i + k).toByte() } }

    private class Encoding(val algorithm: Long, val settings: ByteArray? = null, val encrypted: Boolean = false)

    /**
     * MKV mínimo escrito a mano, porque ningún muxer de aquí produce `ContentEncodings`: una pista
     * de audio AAC con la codificación pedida y un cluster con [payloads] tal cual.
     */
    private fun handMadeMkv(
        file: File,
        encoding: Encoding,
        payloads: List<ByteArray>,
        timestampScale: Long = 1_000_000,
    ): File {
        SeekableOutput(file).use { out ->
            val ebml = EbmlWriter(out)
            val header = ebml.beginMaster(MatroskaIds.EBML)
            ebml.writeString(MatroskaIds.DOCTYPE, "matroska")
            ebml.endMaster(header)
            val segment = ebml.beginMaster(MatroskaIds.SEGMENT)
            val info = ebml.beginMaster(MatroskaIds.INFO)
            ebml.writeUInt(MatroskaIds.TIMESTAMP_SCALE, timestampScale)
            ebml.endMaster(info)
            val tracks = ebml.beginMaster(MatroskaIds.TRACKS)
            val entry = ebml.beginMaster(MatroskaIds.TRACK_ENTRY)
            ebml.writeUInt(MatroskaIds.TRACK_NUMBER, 1)
            ebml.writeUInt(MatroskaIds.TRACK_TYPE, MatroskaIds.TRACK_TYPE_AUDIO)
            ebml.writeString(MatroskaIds.CODEC_ID, AudioCodec.AAC.matroskaId)
            ebml.writeElement(MatroskaIds.CODEC_PRIVATE, AacConfig.build(48000, 2))
            val encodings = ebml.beginMaster(MatroskaIds.CONTENT_ENCODINGS)
            val single = ebml.beginMaster(MatroskaIds.CONTENT_ENCODING)
            ebml.writeUInt(MatroskaIds.CONTENT_ENCODING_SCOPE, 1)
            if (encoding.encrypted) {
                ebml.writeUInt(MatroskaIds.CONTENT_ENCODING_TYPE, 1)
                val encryption = ebml.beginMaster(MatroskaIds.CONTENT_ENCRYPTION)
                ebml.endMaster(encryption)
            } else {
                val compression = ebml.beginMaster(MatroskaIds.CONTENT_COMPRESSION)
                ebml.writeUInt(MatroskaIds.CONTENT_COMP_ALGO, encoding.algorithm)
                encoding.settings?.let { ebml.writeElement(MatroskaIds.CONTENT_COMP_SETTINGS, it) }
                ebml.endMaster(compression)
            }
            ebml.endMaster(single)
            ebml.endMaster(encodings)
            val audio = ebml.beginMaster(MatroskaIds.AUDIO)
            ebml.writeFloat(MatroskaIds.SAMPLING_FREQUENCY, 48000.0)
            ebml.writeUInt(MatroskaIds.CHANNELS, 2)
            ebml.endMaster(audio)
            ebml.endMaster(entry)
            ebml.endMaster(tracks)
            val cluster = ebml.beginMaster(MatroskaIds.CLUSTER)
            ebml.writeUInt(MatroskaIds.CLUSTER_TIMESTAMP, 0)
            for ((i, payload) in payloads.withIndex()) {
                val block = ByteArrayOutputStream()
                block.write(0x81)
                block.write(0)
                block.write(i * 21)
                block.write(0x80)
                block.write(payload)
                ebml.writeElement(MatroskaIds.SIMPLE_BLOCK, block.toByteArray())
            }
            ebml.endMaster(cluster)
            ebml.endMaster(segment)
        }
        return file
    }

    private fun zlib(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(256)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }

    /**
     * zlib de [size] bytes a cero sin reservarlos en memoria: el compresor se alimenta por trozos
     * con el mismo array. Los ceros son el peor caso real de una bomba de descompresión —unos
     * 65 KB comprimidos se convierten en 64 MiB—, y es lo que un archivo manipulado mete en un
     * bloque.
     */
    private fun zlibOfZeros(size: Long): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val zeros = ByteArray(1 shl 20)
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = minOf(remaining, zeros.size.toLong()).toInt()
            deflater.setInput(zeros, 0, n)
            while (!deflater.needsInput()) out.write(chunk, 0, deflater.deflate(chunk))
            remaining -= n
        }
        deflater.finish()
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }

    private fun read(file: File, warnings: MutableList<String> = ArrayList()): List<MediaPacket> =
        MkvDemuxer(file, onWarning = { warnings += it }).use { demuxer ->
            generateSequence { demuxer.readPacket() }.toList()
        }

    @Test
    fun `header stripping is undone so every frame comes back whole`() {
        val sharedHeader = byteArrayOf(0x21, 0x10, 0x05)
        val file = handMadeMkv(File(dir, "strip.mkv"), Encoding(algorithm = 3, settings = sharedHeader), frames)
        val packets = read(file)
        assertEquals(frames.size, packets.size)
        for ((frame, packet) in frames.zip(packets)) assertContentEquals(sharedHeader + frame, packet.data)
    }

    @Test
    fun `zlib compressed frames are inflated`() {
        val file = handMadeMkv(File(dir, "zlib.mkv"), Encoding(algorithm = 0), frames.map(::zlib))
        val packets = read(file)
        assertEquals(frames.size, packets.size)
        for ((expected, packet) in frames.zip(packets)) assertContentEquals(expected, packet.data)
    }

    @Test
    fun `encrypted or unsupported encodings drop the track with a warning`() {
        for ((name, encoding) in listOf("cifrado" to Encoding(0, encrypted = true), "bzlib" to Encoding(1))) {
            val warnings = ArrayList<String>()
            val file = handMadeMkv(File(dir, "$name.mkv"), encoding, frames)
            MkvDemuxer(file, onWarning = { warnings += it }).use { demuxer ->
                assertTrue(demuxer.tracks.isEmpty(), "$name: la pista no debería exponerse")
                assertEquals(null, demuxer.readPacket())
            }
            assertTrue(warnings.any { "ContentEncoding" in it }, "$name: sin aviso: $warnings")
        }
    }

    @Test
    fun `a corrupt zlib frame is skipped and the rest are delivered`() {
        val payloads = frames.map(::zlib).toMutableList()
        payloads[2] = byteArrayOf(1, 2, 3, 4)
        val warnings = ArrayList<String>()
        val packets = read(handMadeMkv(File(dir, "zlib-roto.mkv"), Encoding(algorithm = 0), payloads), warnings)
        assertEquals(frames.size - 1, packets.size)
        assertTrue(warnings.isNotEmpty())
    }

    /**
     * El techo anti-bomba que anuncia `SECURITY.md`: un bloque zlib que descomprime **un byte** más
     * de 64 MiB se descarta con aviso, y el resto del archivo se sigue leyendo.
     *
     * Se comprueba el **motivo** del aviso y no solo que el bloque falte: un bloque se descarta por
     * cualquier fallo de descompresión, así que un test que solo contara paquetes seguiría en verde
     * aunque el techo desapareciera y el bloque cayera por otra causa.
     */
    @Test
    fun `a zlib frame one byte over the 64 MiB ceiling is dropped with a warning`() {
        val payloads = frames.map(::zlib).toMutableList()
        payloads[2] = zlibOfZeros(INFLATE_CEILING + 1)
        val warnings = ArrayList<String>()
        val packets = read(handMadeMkv(File(dir, "bomba.mkv"), Encoding(algorithm = 0), payloads), warnings)

        assertEquals(frames.size - 1, packets.size, "solo debía faltar el bloque que pasa del techo")
        val expected = frames.filterIndexed { i, _ -> i != 2 }
        for ((frame, packet) in expected.zip(packets)) assertContentEquals(frame, packet.data)
        assertTrue(
            warnings.any { "descomprime más de $INFLATE_CEILING bytes" in it },
            "el bloque se descartó, pero no por el techo: $warnings",
        )
    }

    /** El otro lado del límite: un fotograma de exactamente 64 MiB es legítimo y llega entero. */
    @Test
    fun `a zlib frame of exactly 64 MiB is still delivered whole`() {
        val payloads = frames.map(::zlib).toMutableList()
        payloads[2] = zlibOfZeros(INFLATE_CEILING)
        val warnings = ArrayList<String>()
        val packets = read(handMadeMkv(File(dir, "limite.mkv"), Encoding(algorithm = 0), payloads), warnings)

        assertEquals(frames.size, packets.size)
        assertEquals(INFLATE_CEILING, packets[2].data.size.toLong())
        assertTrue(packets[2].data.all { it == 0.toByte() }, "el fotograma no se descomprimió bien")
        assertTrue(warnings.isEmpty(), "un fotograma dentro del techo no debería avisar: $warnings")
    }

    /**
     * Un `TimestampScale` de cero anulaba todas las marcas (todo salía en 0 µs). Se ignora con aviso
     * y se usa el valor por defecto de la especificación, 1 ms.
     */
    @Test
    fun `a zero timestamp scale falls back to milliseconds`() {
        val warnings = ArrayList<String>()
        val file = handMadeMkv(File(dir, "escala-cero.mkv"), Encoding(algorithm = 3), frames, timestampScale = 0)
        val packets = read(file, warnings)
        assertEquals(frames.indices.map { it * 21_000L }, packets.map { it.ptsUs })
        assertTrue(warnings.any { "TimestampScale" in it }, "sin aviso: $warnings")
    }

    @Test
    fun `a file cut inside a block payload ends the stream instead of throwing`() {
        val file = File(dir, "completo.mkv")
        MkvMuxer(file).use { muxer ->
            val id = muxer.addTrack(
                TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2, codecPrivate = AacConfig.build(48000, 2)),
            )
            muxer.start()
            for (i in 0 until 20) muxer.writePacket(MediaPacket(id, ByteArray(1000) { i.toByte() }, ptsUs = i * 21_333L))
        }
        val bytes = file.readBytes()
        val lastBlock = bytes.size - 2_000
        val cut = File(dir, "cortado.mkv").apply { writeBytes(bytes.copyOfRange(0, lastBlock)) }
        val warnings = ArrayList<String>()
        val packets = read(cut, warnings)
        assertTrue(packets.size in 15..19, "se leyeron ${packets.size} paquetes de un archivo cortado")
        assertTrue(packets.all { it.data.size == 1000 }, "se entregó un paquete incompleto")
        assertTrue(warnings.isNotEmpty(), "el corte debería avisarse")
    }
}
