package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.Mp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El inicio rápido sobre un [SeekableOutput], que es lo único que da `MediaStore`/SAF. Hasta la
 * 3.x no existía —necesitaba una ruta para el temporal— y la única salida era el MP4
 * fragmentado. Ahora el `moov` se inserta delante de los datos desplazándolos en el sitio.
 *
 * Lo que se exige es que el resultado sea **el mismo archivo, byte a byte**, que el inicio rápido
 * por ruta: así todo lo que ya prueban los tests de [Mp4Muxer] vale también para este camino.
 */
class FastStartInPlaceTest {

    @TempDir
    lateinit var dir: File

    private val video = TrackInfo.Video(VideoCodec.H264, 320, 240) {
        frameRate = 30.0
        codecPrivate = NalUnits.buildAvcC(
            listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
            listOf(byteArrayOf(0x68, 0x11, 0x22)),
        )
    }
    private val audio = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) }

    /**
     * Unos 2,5 MB de datos, para que el desplazamiento pase por varios bloques de 1 MiB y por el
     * resto final, con vídeo y audio intercalados. La fecha es fija para comparar byte a byte.
     */
    private fun write(muxer: Mp4Muxer) {
        muxer.use {
            val v = it.addTrack(video)
            val a = it.addTrack(audio)
            it.start()
            for (i in 0 until 120) {
                val frame = ByteArray(20_000 + (i % 7) * 100) { k -> (i + k).toByte() }
                it.writePacket(MediaPacket(v, NalUnits.joinLengthPrefixed(listOf(frame)), i * 33_333L, isKeyFrame = i % 30 == 0))
                it.writePacket(MediaPacket(a, ByteArray(300) { k -> (i * k).toByte() }, i * 21_333L, isKeyFrame = true))
            }
        }
    }

    private fun topLevelTypes(file: File): List<String> {
        val bytes = file.readBytes()
        val types = ArrayList<String>()
        var at = 0L
        while (at + 8 <= bytes.size) {
            val p = at.toInt()
            var size = ((bytes[p].toLong() and 0xFF) shl 24) or ((bytes[p + 1].toLong() and 0xFF) shl 16) or
                ((bytes[p + 2].toLong() and 0xFF) shl 8) or (bytes[p + 3].toLong() and 0xFF)
            types += String(bytes, p + 4, 4, Charsets.US_ASCII)
            if (size == 1L) size = (0 until 8).fold(0L) { acc, k -> (acc shl 8) or (bytes[p + 8 + k].toLong() and 0xFF) }
            at += size
        }
        return types
    }

    @Test
    fun `faststart through an output gives the same file as through a path`() {
        val byPath = File(dir, "ruta.mp4")
        write(Mp4Muxer(byPath) { fastStart = true; creationTimeMillis = 0 })

        val byChannel = File(dir, "canal.mp4")
        RandomAccessFile(byChannel, "rw").use { raf ->
            write(Mp4Muxer(SeekableOutput(raf)) { fastStart = true; creationTimeMillis = 0 })
        }
        val byDescriptor = File(dir, "descriptor.mp4").apply { writeBytes(ByteArray(5_000_000) { 0x55 }) }
        RandomAccessFile(byDescriptor, "rw").use { raf ->
            write(Kotmpeg.createMuxer(SeekableOutput(raf.fd), ContainerFormat.MP4) { fastStart = true; creationTimeMillis = 0 } as Mp4Muxer)
        }

        assertEquals(listOf("ftyp", "moov", "mdat"), topLevelTypes(byPath))
        assertContentEquals(byPath.readBytes(), byChannel.readBytes(), "por canal")
        assertContentEquals(byPath.readBytes(), byDescriptor.readBytes(), "por descriptor, sobre un archivo que ya tenía datos")
        Kotmpeg.openDemuxer(byDescriptor).use { demuxer ->
            assertEquals(240, generateSequence { demuxer.readPacket() }.count())
        }
    }

    /**
     * Desplazar los datos exige leerlos, y un destino de solo escritura no puede: un canal de
     * `FileOutputStream` o un descriptor abierto en `"w"`, que es lo que da por defecto
     * `ContentResolver`. Se rechaza al construir el muxer, diciendo qué hacer y cerrando el destino.
     * Hasta ahora fallaba en `stop()`, con la grabación entera escrita y sin `moov` en ninguna
     * parte: un archivo que no abre nadie. Por la fachada, igual.
     */
    @Test
    fun `a destination that cannot be read is refused before recording, with what to do`() {
        val channel = FileOutputStream(File(dir, "canal-solo-escritura.mp4")).channel
        val byChannel = assertFailsWith<IllegalArgumentException> { Mp4Muxer(SeekableOutput(channel)) { fastStart = true } }
        assertTrue("\"rw\"" in byChannel.message.orEmpty(), "el error no dice cómo abrir el destino: ${byChannel.message}")
        assertFalse(channel.isOpen, "el destino quedó abierto")

        val stream = FileOutputStream(File(dir, "descriptor-solo-escritura.mp4"))
        val byDescriptor = assertFailsWith<IllegalArgumentException> {
            Kotmpeg.createMuxer(SeekableOutput(stream.fd), ContainerFormat.MP4) { fastStart = true }
        }
        assertTrue("\"rw\"" in byDescriptor.message.orEmpty(), "el error no dice cómo abrir el destino: ${byDescriptor.message}")
        assertFalse(stream.fd.valid(), "el descriptor quedó abierto")
    }

    /** Sin `fastStart` no hace falta leer nada, y un destino de solo escritura sigue sirviendo. */
    @Test
    fun `without faststart a write only destination still works`() {
        val file = File(dir, "sin-faststart.mp4")
        FileOutputStream(file).use { stream -> write(Mp4Muxer(SeekableOutput(stream.fd)) { creationTimeMillis = 0 }) }
        Kotmpeg.openDemuxer(file).use { demuxer ->
            assertEquals(240, generateSequence { demuxer.readPacket() }.count())
        }
    }
}
