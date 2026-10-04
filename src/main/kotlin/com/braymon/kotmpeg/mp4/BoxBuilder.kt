package com.braymon.kotmpeg.mp4

/**
 * Serializador en memoria de cajas ISO BMFF; con él se construye el árbol `moov`/`moof`.
 *
 * Todo el árbol se escribe sobre **un único array** y el tamaño de cada caja se rellena al
 * cerrarla. La versión anterior construía cada caja hija en su propio buffer y lo copiaba al
 * padre, así que una tabla `stsz` de un archivo largo se copiaba entera una vez por nivel de
 * anidamiento (seis hasta `stbl`), y cada byte pasaba por un `ByteArrayOutputStream`, cuyos
 * métodos están sincronizados. Aquí cada byte se escribe una sola vez y sin cerrojos.
 */
public class BoxBuilder {
    private var buf = ByteArray(INITIAL_CAPACITY)
    private var count = 0

    public val size: Int get() = count

    public fun toByteArray(): ByteArray = buf.copyOf(count)

    private fun ensureCapacity(extra: Int) {
        val needed = count.toLong() + extra
        if (needed <= buf.size) return
        require(needed <= MAX_CAPACITY) { "el árbol de cajas no cabe en memoria: $needed bytes" }
        var capacity = buf.size.toLong()
        while (capacity < needed) capacity *= 2
        buf = buf.copyOf(minOf(capacity, MAX_CAPACITY.toLong()).toInt())
    }

    public fun u8(v: Int) {
        ensureCapacity(1)
        buf[count++] = v.toByte()
    }

    public fun u16(v: Int) {
        ensureCapacity(2)
        buf[count++] = (v shr 8).toByte()
        buf[count++] = v.toByte()
    }

    public fun u24(v: Int) {
        ensureCapacity(3)
        buf[count++] = (v shr 16).toByte()
        buf[count++] = (v shr 8).toByte()
        buf[count++] = v.toByte()
    }

    public fun u32(v: Long) {
        ensureCapacity(4)
        writeU32At(count, v)
        count += 4
    }

    public fun u32(v: Int): Unit = u32(v.toLong() and 0xFFFFFFFFL)

    public fun u64(v: Long) {
        u32(v ushr 32)
        u32(v and 0xFFFFFFFFL)
    }

    public fun s16(v: Int): Unit = u16(v and 0xFFFF)

    public fun fourcc(code: String) {
        require(code.length == 4) { "un fourcc tiene 4 caracteres: '$code'" }
        ensureCapacity(4)
        for (c in code) buf[count++] = c.code.toByte()
    }

    public fun bytes(data: ByteArray) {
        ensureCapacity(data.size)
        System.arraycopy(data, 0, buf, count, data.size)
        count += data.size
    }

    public fun zeros(count: Int) {
        require(count >= 0) { "número de ceros negativo: $count" }
        ensureCapacity(count)
        this.count += count
    }

    /**
     * Escribe una caja hija del tipo dado; el contenido lo produce [content].
     *
     * El campo de tamaño es de 32 bits: las cajas construidas en memoria (todo el árbol
     * `moov`/`moof`) no pueden pasar de 4 GiB. El `mdat`, que es el único que crece con los
     * datos, no se construye aquí — se escribe en streaming con `largesize` de 64 bits.
     */
    public fun box(type: String, content: BoxBuilder.() -> Unit) {
        val start = count
        u32(0)
        fourcc(type)
        content()
        val total = (count - start).toLong()
        require(total <= 0xFFFFFFFFL) {
            "la caja '$type' ocupa $total bytes y no cabe en un tamaño de 32 bits"
        }
        writeU32At(start, total)
    }

    /** Escribe una full box (versión + flags de 24 bits). */
    public fun fullBox(type: String, version: Int, flags: Int, content: BoxBuilder.() -> Unit) {
        box(type) {
            u8(version)
            u24(flags)
            content()
        }
    }

    /** Reescribe 4 bytes ya escritos en [at]; lo usan los muxers para rellenar offsets al final. */
    internal fun patchU32(at: Int, value: Long) {
        require(at >= 0 && at + 4 <= count) { "patch fuera de lo escrito: $at" }
        writeU32At(at, value)
    }

    private fun writeU32At(at: Int, v: Long) {
        buf[at] = (v shr 24).toByte()
        buf[at + 1] = (v shr 16).toByte()
        buf[at + 2] = (v shr 8).toByte()
        buf[at + 3] = v.toByte()
    }

    private companion object {
        private const val INITIAL_CAPACITY = 256
        private const val MAX_CAPACITY = Int.MAX_VALUE - 8
    }
}
