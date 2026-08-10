package com.braymon.kotmpeg.mp4

import java.io.ByteArrayOutputStream
import kotlin.text.iterator

/**
 * Serializador en memoria de cajas ISO BMFF; con él se construye el árbol `moov`/`moof`.
 */
public class BoxBuilder {
    private val buf = ByteArrayOutputStream()

    public val size: Int get() = buf.size()

    public fun toByteArray(): ByteArray = buf.toByteArray()

    public fun u8(v: Int) {
        buf.write(v and 0xFF)
    }

    public fun u16(v: Int) {
        buf.write((v shr 8) and 0xFF); buf.write(v and 0xFF)
    }

    public fun u24(v: Int) {
        buf.write((v shr 16) and 0xFF); buf.write((v shr 8) and 0xFF); buf.write(v and 0xFF)
    }

    public fun u32(v: Long) {
        buf.write(((v shr 24) and 0xFF).toInt()); buf.write(((v shr 16) and 0xFF).toInt())
        buf.write(((v shr 8) and 0xFF).toInt()); buf.write((v and 0xFF).toInt())
    }

    public fun u32(v: Int): Unit = u32(v.toLong() and 0xFFFFFFFFL)

    public fun u64(v: Long) {
        u32((v ushr 32)); u32(v and 0xFFFFFFFFL)
    }

    public fun s16(v: Int): Unit = u16(v and 0xFFFF)

    public fun fourcc(code: String) {
        require(code.length == 4)
        for (c in code) buf.write(c.code and 0xFF)
    }

    public fun bytes(data: ByteArray) {
        buf.write(data, 0, data.size)
    }

    public fun zeros(count: Int): Unit = repeat(count) { buf.write(0) }

    /**
     * Escribe una caja hija del tipo dado; el contenido lo produce [content].
     *
     * El campo de tamaño es de 32 bits: las cajas construidas en memoria (todo el árbol
     * `moov`/`moof`) no pueden pasar de 4 GiB. El `mdat`, que es el único que crece con los
     * datos, no se construye aquí — se escribe en streaming con `largesize` de 64 bits.
     */
    public fun box(type: String, content: BoxBuilder.() -> Unit) {
        val inner = BoxBuilder()
        inner.content()
        val total = 8L + inner.size
        require(total <= 0xFFFFFFFFL) {
            "la caja '$type' ocupa $total bytes y no cabe en un tamaño de 32 bits"
        }
        u32(total)
        fourcc(type)
        bytes(inner.toByteArray())
    }

    /** Escribe una full box (versión + flags de 24 bits). */
    public fun fullBox(type: String, version: Int, flags: Int, content: BoxBuilder.() -> Unit) {
        box(type) {
            u8(version)
            u24(flags)
            content()
        }
    }
}
