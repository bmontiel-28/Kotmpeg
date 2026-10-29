package com.braymon.kotmpeg.io

import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.NonReadableChannelException

/**
 * Un archivo abierto para leerlo y modificarlo **sin truncarlo**, que es lo que necesita la
 * reparación en el sitio y lo que [SeekableOutput] no puede dar: sus constructores vacían el
 * destino.
 *
 * Se lee con [input] y se escribe con [write] y [truncate]. Que se pueda leer se comprueba al
 * abrir, antes de decidir nada. Que se pueda escribir no se puede comprobar sin escribir —en la JVM,
 * una escritura vacía ni siquiera llega al sistema—, pero tampoco hace falta: la primera escritura
 * de una reparación es ya su primera modificación, y si el descriptor es de solo lectura falla
 * entera, sin cambiar nada.
 */
internal class RepairTarget private constructor(
    private val reader: FileChannel,
    private val writer: FileChannel,
) : Closeable {

    val input: SeekableInput = SeekableInput(reader)

    /** Tamaño del archivo al abrirlo. */
    val length: Long get() = input.length

    fun write(at: Long, data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        var written = 0L
        while (buffer.hasRemaining()) {
            val n = writer.write(buffer, at + written)
            if (n <= 0) throw IOException("el destino no aceptó más bytes en ${at + written}")
            written += n
        }
    }

    fun truncate(size: Long) {
        writer.truncate(size)
    }

    /** Lleva lo escrito al almacenamiento: una reparación es para no perder la grabación. */
    fun force() {
        writer.force(false)
    }

    override fun close() {
        try {
            writer.close()
        } finally {
            if (reader !== writer) runCatching { reader.close() }
        }
    }

    companion object {
        fun open(file: File): RepairTarget = open(RandomAccessFile(file, "rw").channel)

        /**
         * Sobre un canal que ya lee y escribe. Además de [open] con un archivo, lo usan los tests
         * para registrar cada operación de una reparación y cortarla en cualquier punto.
         */
        fun open(channel: FileChannel): RepairTarget = RepairTarget(channel, channel)

        /**
         * Sobre un descriptor se lee con un canal y se escribe con otro, como en [SeekableOutput]:
         * el de `FileOutputStream` no lee.
         *
         * [close] cierra los canales que abre sobre el descriptor, pero el descriptor es de quien lo
         * abrió: en Android `FileInputStream`/`FileOutputStream` no se adueñan de un
         * `FileDescriptor` ajeno, así que el `ParcelFileDescriptor` hay que cerrarlo aparte (con
         * `use`).
         */
        fun open(fd: FileDescriptor): RepairTarget {
            val reader = FileInputStream(fd).channel
            val writer = FileOutputStream(fd).channel
            try {
                reader.read(ByteBuffer.allocate(1), 0)
            } catch (e: NonReadableChannelException) {
                closeBoth(reader, writer)
                throw IllegalArgumentException(UNREADABLE, e)
            } catch (e: IOException) {
                closeBoth(reader, writer)
                throw IllegalArgumentException("$UNREADABLE (${e.message})", e)
            }
            return RepairTarget(reader, writer)
        }

        private fun closeBoth(reader: FileChannel, writer: FileChannel) {
            runCatching { writer.close() }
            runCatching { reader.close() }
        }

        private const val UNREADABLE =
            "reparar necesita leer el archivo, y este no se puede leer: ábrelo en modo \"rw\" " +
                "(con ContentResolver, openFileDescriptor(uri, \"rw\"))"
    }
}
