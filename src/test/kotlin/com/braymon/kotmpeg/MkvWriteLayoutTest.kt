package com.braymon.kotmpeg

import com.braymon.kotmpeg.ebml.EbmlReader
import com.braymon.kotmpeg.ebml.EbmlWriter
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.mkv.MkvLayout
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Los límites de `MkvLayout`, donde un byte de más o de menos rompe el elemento entero: el `Void`
 * libre que no puede medir 1 byte, los saltos de longitud del tamaño VINT, el byte de confirmación
 * de una cabecera de 9 bytes y las posiciones que devuelve el `SeekHead`.
 */
class MkvWriteLayoutTest {

    /** Los elementos que hay seguidos en [bytes]: id, dónde empieza cada uno y dónde acaba. */
    private fun elements(bytes: ByteArray): List<Triple<Long, Long, Long>> {
        val input = SeekableInput(MemoryFileChannel(bytes))
        val reader = EbmlReader(input)
        val out = ArrayList<Triple<Long, Long, Long>>()
        while (input.position < bytes.size) {
            val start = input.position
            val el = reader.readElement()
            out += Triple(el.id, start, el.dataEnd)
            input.position = el.dataEnd
        }
        return out
    }

    /** Un `Void` de [total] bytes como los de `MkvLayout`: su cabecera y ceros. */
    private fun void(total: Int): ByteArray = MkvLayout.freeSpaceHeader(total.toLong()).let { it + ByteArray(total - it.size) }

    @Test
    fun `a free void changes its size length right where the vint does`() {
        for ((total, headerLength) in listOf(2 to 2, 128 to 2, 129 to 3, 16_385 to 3, 16_386 to 4)) {
            assertEquals(headerLength, MkvLayout.freeSpaceHeader(total.toLong()).size, "Void de $total")
            assertEquals(listOf(Triple(MatroskaIds.VOID, 0L, total.toLong())), elements(void(total)), "Void de $total")
        }
    }

    @Test
    fun `the commit byte turns any free void header into a void of exactly that header`() {
        assertEquals(0x80.toByte(), MkvLayout.separator(2))
        assertEquals(0x87.toByte(), MkvLayout.separator(9))
        for (header in listOf(MkvLayout.freeSpaceHeader(2), MkvLayout.freeSpaceHeader(100_000_000), MkvLayout.voidOver(1_000))) {
            val committed = header.copyOf().also { it[1] = MkvLayout.separator(header.size) }
            assertEquals(listOf(Triple(MatroskaIds.VOID, 0L, header.size.toLong())), elements(committed), "cabecera de ${header.size} bytes")
        }
    }

    @Test
    fun `an append never leaves a one byte void, nor none at all`() {
        val data = ByteArray(10)
        for ((room, fits) in listOf(10 to false, 11 to false, 12 to true)) {
            val total = 2 + room
            val append = MkvLayout.append(0, 2, total.toLong(), data)
            if (!fits) {
                assertNull(append, "hueco de $room bytes para 10")
                continue
            }
            assertNotNull(append)
            assertEquals(12L, append.nextFreePos)
            assertEquals(2, append.nextFreeHeaderLength)
        }
    }

    @Test
    fun `an append is padding until its commit byte, and then exactly what was added`() {
        val total = 300
        val added = EbmlWriter.encodeVoid(40).also { it[0] = 0xBB.toByte() }
        val bytes = void(total)
        val append = assertNotNull(MkvLayout.append(0, 3, total.toLong(), added))
        val (data, commit) = append.writes
        data.second.copyInto(bytes, data.first.toInt())
        assertEquals(listOf(Triple(MatroskaIds.VOID, 0L, total.toLong())), elements(bytes), "antes de confirmar")
        commit.second.copyInto(bytes, commit.first.toInt())
        assertEquals(
            listOf(Triple(MatroskaIds.VOID, 0L, 3L), Triple(MatroskaIds.CUE_POINT, 3L, 43L), Triple(MatroskaIds.VOID, 43L, total.toLong())),
            elements(bytes),
        )
        assertEquals(43L, append.nextFreePos)
    }

    @Test
    fun `a seek head with one byte to spare does not exist, and with none it has no free void`() {
        val targets = listOf(MatroskaIds.INFO to 10L, MatroskaIds.TRACKS to 200L)
        val exact = MkvLayout.MASTER_HEADER_SIZE + 2 * 21
        assertNull(MkvLayout.seekHead(targets, exact + 1))
        val full = assertNotNull(MkvLayout.seekHead(targets, exact))
        assertEquals(-1, full.freeOffset)
        assertEquals(-1, full.cuesPositionOffset)
        assertEquals(listOf(MatroskaIds.SEEK_HEAD), elements(full.bytes).map { it.first })
    }

    @Test
    fun `a seek head says where its cues position and its free void are`() {
        val head = assertNotNull(MkvLayout.seekHead(listOf(MatroskaIds.INFO to 10L, MatroskaIds.CUES to 0x1234_5678L, MatroskaIds.TRACKS to 200L), 120))
        assertEquals(120, head.bytes.size)
        assertContentEquals(EbmlWriter.bigEndian(0x1234_5678L, 8), head.bytes.copyOfRange(head.cuesPositionOffset, head.cuesPositionOffset + 8))
        val free = head.bytes.copyOfRange(head.freeOffset, head.bytes.size)
        assertEquals(listOf(Triple(MatroskaIds.VOID, 0L, free.size.toLong())), elements(free))
        assertEquals(head.freeHeaderLength, MkvLayout.freeSpaceHeader(free.size.toLong()).size)
    }
}
