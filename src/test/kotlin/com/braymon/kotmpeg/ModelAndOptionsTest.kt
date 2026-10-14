package com.braymon.kotmpeg

import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ColorInfo
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import com.braymon.kotmpeg.mp4.Mp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * El modelo con bloque de configuración y las opciones de los muxers, que sustituyen en la 4.0.0
 * a las `data class` y a los parámetros con valor por defecto.
 */
class ModelAndOptionsTest {

    @TempDir
    lateinit var dir: File

    /**
     * `copy` conserva el id y lo no tocado. Las dimensiones de presentación que coincidían con las
     * codificadas las siguen al cambiarlas; las que eran distintas se quedan como estaban.
     */
    @Test
    fun `copy keeps what it does not touch and display size follows the coded one`() {
        val original = TrackInfo.Video(VideoCodec.H264, 320, 240) { frameRate = 30.0; codecPrivate = byteArrayOf(1, 2) }.withId(7)
        val resized = original.copy { width = 640; height = 480 }
        assertEquals(7, resized.id)
        assertEquals(640 to 480, resized.displayWidth to resized.displayHeight)
        assertEquals(30.0, resized.frameRate)

        val anamorphic = original.copy { displayWidth = 427 }
        assertEquals(427 to 480, anamorphic.copy { height = 480 }.let { it.displayWidth to it.displayHeight })
        assertEquals(427, anamorphic.copy { width = 640 }.displayWidth)

        assertEquals(original, original.copy { }, "una copia sin cambios es igual")
        assertNotEquals(original, original.copy { codecPrivate = byteArrayOf(1, 3) })
        assertEquals(original.hashCode(), original.copy { codecPrivate = byteArrayOf(1, 2) }.hashCode(), "codecPrivate se compara por contenido")
    }

    @Test
    fun `the builders still validate`() {
        assertFailsWith<IllegalArgumentException> { TrackInfo.Video(VideoCodec.H264, 0, 240) }
        assertFailsWith<IllegalArgumentException> { TrackInfo.Video(VideoCodec.H264, 320, 240) { rotationDegrees = 45 } }
        assertFailsWith<IllegalArgumentException> { TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecDelayUs = -1 } }
        assertFailsWith<IllegalArgumentException> { ColorInfo { primaries = 256 } }
        assertEquals(ColorInfo.bt709(), ColorInfo { primaries = 1; transfer = 1; matrix = 1 })
        assertEquals(ColorInfo.UNSPECIFIED, ColorInfo().primaries)
    }

    /** Un valor imposible se rechaza al construir el muxer, sin crear el archivo. */
    @Test
    fun `invalid options are rejected before the file is created`() {
        val cases = listOf<Pair<String, (File) -> Unit>>(
            "mkv" to { f -> MkvMuxer(f) { maxClusterDurationMs = 0 } },
            "mp4" to { f -> Mp4Muxer(f) { fastStart = true; fragmented = true } },
            "frag" to { f -> FragmentedMp4Muxer(f) { maxFragmentBytes = 0 } },
            "fachada" to { f -> Kotmpeg.createMuxer(f, ContainerFormat.MP4) { fragmentDurationUs = -1 } },
        )
        for ((name, create) in cases) {
            val file = File(dir, "$name.out")
            assertFailsWith<IllegalArgumentException>(name) { create(file) }
            assertFalse(file.exists(), "$name: se creó el archivo")
        }
    }

    /**
     * `fragmented` elige el muxer en la fachada; `Mp4Muxer` solo escribe MP4 plano, y si lo
     * ignorara, quien lo pidió tendría un MP4 normal creyendo que es un fMP4.
     */
    @Test
    fun `the plain mp4 muxer refuses to be asked for a fragmented file`() {
        val file = File(dir, "plano.mp4")
        val error = assertFailsWith<IllegalArgumentException> { Mp4Muxer(file) { fragmented = true } }
        assertTrue("FragmentedMp4Muxer" in error.message.orEmpty(), "el error no dice qué usar: ${error.message}")
        assertFalse(file.exists(), "se creó el archivo")

        val raf = RandomAccessFile(File(dir, "plano-abierto.mp4"), "rw")
        assertFailsWith<IllegalArgumentException> { Mp4Muxer(SeekableOutput(raf)) { fragmented = true } }
        assertFalse(raf.channel.isOpen, "el destino quedó abierto")

        Kotmpeg.createMuxer(File(dir, "fachada.mp4")) { fragmented = true }.use { assertTrue(it is FragmentedMp4Muxer) }
    }

    /**
     * Un destino abierto solo para leer se rechaza al construir la salida, diciendo qué hacer, sin
     * tocar el archivo y cerrándolo. Salía una `NonWritableChannelException` sin mensaje, y con un
     * archivo vacío, en la primera escritura de verdad.
     */
    @Test
    fun `a read only destination is refused when built, with what to do`() {
        val full = File(dir, "lleno.mkv").apply { writeBytes(ByteArray(1000) { 7 }) }
        val empty = File(dir, "vacio.mkv").apply { writeBytes(ByteArray(0)) }
        for (file in listOf(full, empty)) {
            val raf = RandomAccessFile(file, "r")
            val byRaf = assertFailsWith<IllegalArgumentException> { SeekableOutput(raf) }
            assertTrue("solo lectura" in byRaf.message.orEmpty() && "\"rw\"" in byRaf.message.orEmpty(), "mensaje: ${byRaf.message}")
            assertFalse(raf.channel.isOpen, "${file.name}: el destino quedó abierto")
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
            assertFailsWith<IllegalArgumentException> { MkvMuxer(SeekableOutput(channel)) }
            assertFalse(channel.isOpen, "${file.name}: el canal quedó abierto")
        }
        assertEquals(1000L, full.length(), "se tocó el archivo")
    }

    /** Con un destino ya abierto, el muxer que no llega a existir lo cierra al rechazar las opciones. */
    @Test
    fun `invalid options close an output that was already open`() {
        val raf = RandomAccessFile(File(dir, "abierto.mkv"), "rw")
        assertFailsWith<IllegalArgumentException> {
            Kotmpeg.createMuxer(SeekableOutput(raf), ContainerFormat.MKV) { maxClusterDurationMs = -5 }
        }
        assertFalse(raf.channel.isOpen, "el destino quedó abierto")
    }
}
