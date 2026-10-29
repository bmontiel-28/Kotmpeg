package com.braymon.kotmpeg

import com.braymon.kotmpeg.io.RepairTarget
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * Canal en memoria que registra cada operación en orden, para reconstruir el archivo tal como
 * quedaría si el proceso muriera en cualquier punto ([cutAfter]): el de una grabación, que empieza
 * vacío, o el de una reparación, que empieza con el archivo cortado ([initial]).
 *
 * El modelo de corte es el de un proceso que muere: lo que llegó al sistema antes del corte está en
 * el archivo, y la escritura en curso puede quedarse a medias —el kernel copia una escritura grande
 * página a página y comprueba entre una y otra si el proceso tiene que morir—. Las escrituras de
 * hasta [ATOMIC_BYTES] bytes, en cambio, entran enteras o no entran: así son en la práctica los
 * parches de tamaños y duraciones, y no es eso lo que se quiere probar aquí. Un truncado tampoco se
 * parte. Un corte de luz es otro modelo, en el que ni siquiera lo escrito tiene por qué estar en
 * disco.
 */
internal class RecordingFileChannel(private val initial: ByteArray = ByteArray(0)) : FileChannel() {

    /**
     * Una operación sobre el archivo: una escritura de [bytes] en [position] o, con [truncatedTo]
     * mayor o igual que cero, un truncado a ese tamaño. Un truncado no se puede partir y cuenta como
     * un byte en el presupuesto de [cutAfter]: así [boundaries] da un corte justo antes y otro justo
     * después de cada operación.
     */
    class Write(val position: Long, val bytes: ByteArray, val truncatedTo: Long = -1) {
        val cost: Int get() = if (truncatedTo >= 0) 1 else bytes.size
    }

    val writes = ArrayList<Write>()
    private var content = initial.copyOf(maxOf(1 shl 16, initial.size))
    private var size = initial.size.toLong()
    private var position = 0L

    /** Lo que cuestan en total las operaciones: el rango de cortes posibles es `0..totalBytes`. */
    val totalBytes: Long get() = writes.sumOf { it.cost.toLong() }

    /** El archivo completo, tal como quedó al cerrar. */
    fun bytes(): ByteArray = content.copyOf(size.toInt())

    /**
     * El archivo si el proceso hubiera muerto tras gastar [budget] del coste de las operaciones,
     * contándolas en el orden en que se hicieron.
     */
    fun cutAfter(budget: Long): ByteArray {
        val out = initial.copyOf(content.size)
        var length = initial.size
        var left = budget
        for (write in writes) {
            if (left <= 0) break
            if (write.truncatedTo >= 0) {
                val to = write.truncatedTo.toInt()
                if (to < length) out.fill(0, to, length)
                length = minOf(length, to)
                left -= 1
                continue
            }
            val n = when {
                left >= write.bytes.size -> write.bytes.size
                write.bytes.size <= ATOMIC_BYTES -> 0
                else -> left.toInt()
            }
            write.bytes.copyInto(out, write.position.toInt(), 0, n)
            if (n > 0) length = maxOf(length, write.position.toInt() + n)
            left -= write.bytes.size
        }
        return out.copyOf(length)
    }

    /** Dónde acaba cada operación dentro del total: los cortes justo entre dos operaciones. */
    fun boundaries(): List<Long> {
        var total = 0L
        return writes.map { total += it.cost; total }
    }

    override fun write(src: ByteBuffer, position: Long): Int {
        val bytes = ByteArray(src.remaining())
        src.get(bytes)
        if (bytes.isEmpty()) return 0
        writes.add(Write(position, bytes))
        val end = position + bytes.size
        if (end > content.size) content = content.copyOf(maxOf(end.toInt(), content.size * 2))
        bytes.copyInto(content, position.toInt())
        size = maxOf(size, end)
        return bytes.size
    }

    override fun write(src: ByteBuffer): Int = write(src, position).also { position += it }

    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        var total = 0L
        for (i in offset until offset + length) total += write(srcs[i])
        return total
    }

    override fun read(dst: ByteBuffer, position: Long): Int {
        if (position >= size) return -1
        val n = minOf(dst.remaining().toLong(), size - position).toInt()
        dst.put(content, position.toInt(), n)
        return n
    }

    override fun read(dst: ByteBuffer): Int = read(dst, position).also { if (it > 0) position += it }

    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        var total = 0L
        for (i in offset until offset + length) {
            val n = read(dsts[i])
            if (n < 0) return if (total == 0L) -1 else total
            total += n
        }
        return total
    }

    override fun position(): Long = position

    override fun position(newPosition: Long): FileChannel {
        position = newPosition
        return this
    }

    override fun size(): Long = size

    /** Como `FileChannel.truncate`: no hace nada si el archivo ya no es más grande que [size]. */
    override fun truncate(size: Long): FileChannel {
        if (size >= this.size) return this
        writes.add(Write(size, ByteArray(0), truncatedTo = size))
        content.fill(0, size.toInt(), this.size.toInt())
        this.size = size
        position = minOf(position, size)
        return this
    }

    override fun force(metaData: Boolean): Unit = Unit
    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = throw UnsupportedOperationException()
    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = throw UnsupportedOperationException()
    override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = throw UnsupportedOperationException()
    override fun lock(position: Long, size: Long, shared: Boolean): FileLock = throw UnsupportedOperationException()
    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock = throw UnsupportedOperationException()
    override fun implCloseChannel(): Unit = Unit

    companion object {
        /** Escrituras que se consideran indivisibles: los parches de tamaño y duración, de hasta 9 bytes, con margen. */
        const val ATOMIC_BYTES = 16

        /**
         * Repara [bytes] sobre un canal que registra cada operación: con [cutAfter] sale el archivo
         * tal como quedaría si la reparación muriera en cualquier punto, para relanzarla sobre él.
         */
        fun repairRecorded(bytes: ByteArray): Pair<RepairResult, RecordingFileChannel> {
            val channel = RecordingFileChannel(bytes)
            val result = RepairTarget.open(channel).use { Kotmpeg.repair(it) }
            return result to channel
        }
    }
}
