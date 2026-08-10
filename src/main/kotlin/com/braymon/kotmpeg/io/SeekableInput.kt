package com.braymon.kotmpeg.io

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Fuente de bytes con buffer y posicionamiento, usada por los demuxers.
 *
 * Trabaja sobre un [FileChannel] y no sobre una ruta, así que además de un [File] admite
 * cualquier descriptor ya abierto — que es la vía para leer de `MediaStore`/SAF en Android:
 *
 * ```kotlin
 * contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
 *     MkvKotlin.openDemuxer(SeekableInput(pfd.fileDescriptor), ContainerFormat.MP4).use { ... }
 * }
 * ```
 *
 * **No es seguro entre hilos**: mantiene una posición de lectura y un buffer compartidos.
 */
public class SeekableInput(private val channel: FileChannel) : Closeable {

    public constructor(file: File) : this(RandomAccessFile(file, "r").channel)

    public constructor(file: RandomAccessFile) : this(file.channel)

    /**
     * Lee de un descriptor ya abierto (p. ej. `ParcelFileDescriptor.getFileDescriptor()`).
     *
     * El descriptor tiene que ser posicionable: vale un archivo normal, no una tubería ni
     * un socket. [close] cierra el descriptor, así que hay que pasar uno del que esta
     * instancia pueda adueñarse (con `ParcelFileDescriptor` lo natural es dejar que el
     * `use {}` del propio pfd lo cierre y no llamar a [close] aquí).
     */
    public constructor(fd: FileDescriptor) : this(FileInputStream(fd).channel)

    public val length: Long = channel.size()

    private val buffer = ByteArray(1 shl 16)
    private var bufferStart = 0L
    private var bufferLen = 0
    private var pos = 0L

    public var position: Long
        get() = pos
        set(value) {
            require(value >= 0) { "posición negativa" }
            pos = value
        }

    public val remaining: Long get() = length - pos

    private fun fill(at: Long): Int {
        if (at >= bufferStart && at < bufferStart + bufferLen) return (at - bufferStart).toInt()
        bufferStart = at
        bufferLen = 0
        val n = channel.read(ByteBuffer.wrap(buffer), at)
        if (n <= 0) throw EOFException("fin de archivo en $at")
        bufferLen = n
        return 0
    }

    public fun readByte(): Int {
        val idx = fill(pos)
        pos++
        return buffer[idx].toInt() and 0xFF
    }

    public fun readFully(dst: ByteArray, offset: Int = 0, length: Int = dst.size - offset) {
        var done = 0
        while (done < length) {
            val idx = fill(pos)
            val n = minOf(length - done, bufferLen - idx)
            System.arraycopy(buffer, idx, dst, offset + done, n)
            done += n
            pos += n
        }
    }

    public fun readBytes(count: Int): ByteArray {
        require(count >= 0 && count <= remaining) { "no se pueden leer $count bytes, quedan $remaining" }
        val out = ByteArray(count)
        readFully(out)
        return out
    }

    /** Lee [count] bytes big-endian como valor sin signo (count <= 8). */
    public fun readBits(count: Int): Long {
        var v = 0L
        repeat(count) { v = (v shl 8) or readByte().toLong() }
        return v
    }

    public fun readInt32(): Int = readBits(4).toInt()
    public fun readInt64(): Long = readBits(8)

    public fun skip(count: Long) {
        require(count >= 0)
        pos += count
    }

    override fun close(): Unit = channel.close()
}
