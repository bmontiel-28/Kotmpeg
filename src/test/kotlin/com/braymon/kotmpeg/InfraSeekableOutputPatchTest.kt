package com.braymon.kotmpeg

import com.braymon.kotmpeg.io.SeekableOutput
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `SeekableOutput.patch`, que en la 4.1 dejó de vaciar el buffer siempre: si la zona sigue en el
 * buffer se reescribe ahí, y si ya está en el archivo, todo lo escrito antes llega al archivo antes
 * que el parche. Los muxers dependen de ese orden —`MkvMuxer` publica un cluster en el índice
 * sabiendo que ya está entero en el archivo— y hasta ahora solo lo probaban de rebote.
 *
 * [RecordingFileChannel] registra cada escritura que llega al canal, en orden, y es lo que se mira.
 */
class InfraSeekableOutputPatchTest {

    private fun bytes(count: Int, from: Int = 0): ByteArray = ByteArray(count) { (from + it).toByte() }

    @Test
    fun `a patch inside the buffer is applied there and reaches the channel with the next flush`() {
        val channel = RecordingFileChannel()
        val out = SeekableOutput(channel)
        out.write(bytes(100))
        out.patch(10, byteArrayOf(-1, -2, -3))
        assertEquals(0, channel.writes.size, "un parche dentro del buffer no tiene por qué llegar al canal")
        assertEquals(100L, out.position, "el parche movió la posición de escritura")
        out.patch(97, byteArrayOf(-4, -5, -6))
        out.flush()
        assertEquals(1, channel.writes.size, "el parche no viajó con el resto del buffer")
        val expected = bytes(100).also {
            byteArrayOf(-1, -2, -3).copyInto(it, 10)
            byteArrayOf(-4, -5, -6).copyInto(it, 97)
        }
        assertContentEquals(expected, channel.bytes())
    }

    @Test
    fun `a patch of what is already in the channel goes after everything written before it`() {
        val channel = RecordingFileChannel()
        val out = SeekableOutput(channel)
        out.write(bytes(100))
        out.flush()
        out.write(bytes(50, from = 100))
        out.patch(5, byteArrayOf(-1, -2))
        assertEquals(listOf(0L to 100, 100L to 50, 5L to 2), channel.writes.map { it.position to it.bytes.size })
        assertEquals(150L, out.position)
        out.close()
        val expected = bytes(150).also { byteArrayOf(-1, -2).copyInto(it, 5) }
        assertContentEquals(expected, channel.bytes())
    }

    /** Un parche a caballo entre lo vaciado y el buffer va por la segunda rama: vaciar y escribir. */
    @Test
    fun `a patch across the flushed boundary is written whole after the buffer`() {
        val channel = RecordingFileChannel()
        val out = SeekableOutput(channel)
        out.write(bytes(20))
        out.flush()
        out.write(bytes(20, from = 20))
        out.patch(18, byteArrayOf(-1, -2, -3, -4))
        assertEquals(listOf(0L to 20, 20L to 20, 18L to 4), channel.writes.map { it.position to it.bytes.size })
        out.close()
        assertContentEquals(bytes(40).also { byteArrayOf(-1, -2, -3, -4).copyInto(it, 18) }, channel.bytes())
    }

    @Test
    fun `a patch outside what was written is rejected`() {
        val out = SeekableOutput(RecordingFileChannel())
        out.write(bytes(10))
        out.patch(8, byteArrayOf(1, 2))
        assertFailsWith<IllegalArgumentException> { out.patch(9, byteArrayOf(1, 2)) }
        assertFailsWith<IllegalArgumentException> { out.patch(-1, byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { out.patch(11, ByteArray(0)) }
    }
}
