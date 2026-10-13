package com.braymon.kotmpeg

import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.NonWritableChannelException
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * Un [FileChannel] de solo lectura sobre un array, para dar a [com.braymon.kotmpeg.io.SeekableInput]
 * miles de entradas por segundo sin pasar por el disco.
 *
 * El fuzzing ejecuta decenas de miles de variantes, y escribir y borrar un archivo por cada una
 * costaba más que parsearla —en Windows, con el antivirus revisando cada archivo nuevo, unas veinte
 * veces más—. Además deja la comprobación de descriptores en algo exacto: [isOpen] dice si alguien
 * llamó a `close`, sin depender del sistema operativo.
 */
internal class MemoryFileChannel(private val bytes: ByteArray) : FileChannel() {

    private var position = 0L

    override fun read(dst: ByteBuffer): Int {
        val n = read(dst, position)
        if (n > 0) position += n
        return n
    }

    override fun read(dst: ByteBuffer, position: Long): Int {
        require(position >= 0) { "posición negativa" }
        if (position >= bytes.size) return -1
        val n = minOf(dst.remaining().toLong(), bytes.size - position).toInt()
        dst.put(bytes, position.toInt(), n)
        return n
    }

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

    override fun size(): Long = bytes.size.toLong()

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = throw NonWritableChannelException()
    override fun write(src: ByteBuffer, position: Long): Int = throw NonWritableChannelException()
    override fun truncate(size: Long): FileChannel = throw NonWritableChannelException()
    override fun force(metaData: Boolean): Unit = Unit
    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = throw UnsupportedOperationException()
    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = throw NonWritableChannelException()
    override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = throw UnsupportedOperationException()
    override fun lock(position: Long, size: Long, shared: Boolean): FileLock = throw UnsupportedOperationException()
    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock = throw UnsupportedOperationException()
    override fun implCloseChannel(): Unit = Unit
}
