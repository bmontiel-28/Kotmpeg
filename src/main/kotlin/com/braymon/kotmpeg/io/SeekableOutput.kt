package com.braymon.kotmpeg.io

import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Sumidero de bytes con buffer y posicionamiento, usado por los muxers. Las escrituras
 * secuenciales van con buffer; [patch] reescribe regiones ya escritas (tamaños, duración,
 * índices).
 *
 * Trabaja sobre un [FileChannel] y no sobre una ruta, así que además de un [File] admite
 * cualquier descriptor ya abierto — que es la vía para escribir a `MediaStore`/SAF en
 * Android:
 *
 * ```kotlin
 * contentResolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
 *     MkvKotlin.createMuxer(SeekableOutput(pfd.fileDescriptor), ContainerFormat.MP4).use { ... }
 * }
 * ```
 *
 * El descriptor tiene que ser **posicionable y legible-escribible**: los tres muxers
 * vuelven atrás a parchear tamaños e índices al finalizar. Una tubería no sirve; para
 * salida puramente secuencial está el MP4 fragmentado, que igualmente pasa por aquí.
 *
 * **No es seguro entre hilos**: igual que los muxers que lo usan, asume un único hilo
 * escritor. Con varias pistas produciendo a la vez, serializar las escrituras es
 * responsabilidad de quien integra.
 */
public class SeekableOutput(private val channel: FileChannel) : Closeable {

    public constructor(file: File) : this(
        RandomAccessFile(file, "rw").channel.also { it.truncate(0) },
    )

    public constructor(file: RandomAccessFile) : this(file.channel)

    /**
     * Escribe en un descriptor ya abierto (p. ej. `ParcelFileDescriptor.getFileDescriptor()`
     * en modo `"rw"`).
     *
     * **No trunca**: a diferencia del constructor de [File], aquí se escribe desde la
     * posición 0 sobre lo que hubiera. Si el destino puede traer contenido previo más largo
     * que el nuevo, ábrelo con `"rwt"` o trunca tú antes. [close] cierra el descriptor.
     */
    public constructor(fd: FileDescriptor) : this(FileOutputStream(fd).channel)

    private val buffer = ByteArray(1 shl 16)
    private var bufferUsed = 0
    private var flushedPosition = 0L

    /** Posición lógica de escritura actual. */
    public val position: Long get() = flushedPosition + bufferUsed

    public fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (length > buffer.size) {
            flush()
            writeAt(flushedPosition, data, offset, length)
            flushedPosition += length
            return
        }
        if (bufferUsed + length > buffer.size) flush()
        System.arraycopy(data, offset, buffer, bufferUsed, length)
        bufferUsed += length
    }

    public fun writeByte(value: Int) {
        if (bufferUsed + 1 > buffer.size) flush()
        buffer[bufferUsed++] = value.toByte()
    }

    /** Escribe los [count] bytes menos significativos de [value], big-endian. */
    public fun writeBits(value: Long, count: Int) {
        for (i in count - 1 downTo 0) writeByte(((value ushr (8 * i)) and 0xFF).toInt())
    }

    public fun writeInt32(value: Int): Unit = writeBits(value.toLong() and 0xFFFFFFFFL, 4)
    public fun writeInt64(value: Long): Unit = writeBits(value, 8)

    /** Reescribe [data] en la posición absoluta [at] sin mover la posición de escritura. */
    public fun patch(at: Long, data: ByteArray) {
        require(at + data.size <= position) { "patch fuera de la zona escrita" }
        flush()
        writeAt(at, data, 0, data.size)
    }

    public fun flush() {
        if (bufferUsed > 0) {
            writeAt(flushedPosition, buffer, 0, bufferUsed)
            flushedPosition += bufferUsed
            bufferUsed = 0
        }
    }

    /**
     * Escritura posicional completa. [FileChannel.write] puede escribir menos de lo pedido,
     * así que insiste hasta agotar el bloque; darlo por escrito a la primera dejaría huecos
     * silenciosos en el archivo.
     */
    private fun writeAt(at: Long, data: ByteArray, offset: Int, length: Int) {
        try {
            val bb = ByteBuffer.wrap(data, offset, length)
            var written = 0L
            while (bb.hasRemaining()) {
                val n = channel.write(bb, at + written)
                if (n <= 0) throw IOException("el canal no aceptó más bytes")
                written += n
            }
        } catch (e: IOException) {
            throw IOException(
                "fallo al escribir $length bytes en la posición $at: ${e.message}", e,
            )
        }
    }

    override fun close() {
        flush()
        channel.close()
    }
}
