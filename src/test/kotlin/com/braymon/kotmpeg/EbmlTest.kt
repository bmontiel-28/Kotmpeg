package com.braymon.kotmpeg

import com.braymon.kotmpeg.ebml.EbmlReader
import com.braymon.kotmpeg.ebml.EbmlWriter
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.io.SeekableOutput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class EbmlTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `vint sizes`() {
        assertEquals(1, EbmlWriter.vintSizeLength(0))
        assertEquals(1, EbmlWriter.vintSizeLength(126))
        assertEquals(2, EbmlWriter.vintSizeLength(127))
        assertEquals(2, EbmlWriter.vintSizeLength(16382))
        assertEquals(3, EbmlWriter.vintSizeLength(16383))
    }

    @Test
    fun `roundtrip elements`() {
        val file = File(dir, "test.ebml")
        val out = SeekableOutput(file)
        val w = EbmlWriter(out)
        val master = w.beginMaster(0x1A45DFA3L)
        w.writeUInt(0x4286, 1)
        w.writeUInt(0x42F7, 260)
        w.writeString(0x4282, "matroska")
        w.writeFloat(0x4489, 1234.5)
        w.endMaster(master)
        out.close()

        val input = SeekableInput(file)
        val r = EbmlReader(input)
        val m = r.readElement()
        assertEquals(0x1A45DFA3L, m.id)
        val e1 = r.readElement()
        assertEquals(0x4286L, e1.id)
        assertEquals(1L, r.readUInt(e1))
        val e2 = r.readElement()
        assertEquals(260L, r.readUInt(e2))
        val e3 = r.readElement()
        assertEquals("matroska", r.readString(e3))
        val e4 = r.readElement()
        assertEquals(1234.5, r.readFloat(e4))
        assertEquals(m.dataEnd, input.position)
        input.close()
    }
}
