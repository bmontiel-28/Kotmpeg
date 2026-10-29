package com.braymon.kotmpeg.io

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException

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
 *     Kotmpeg.createMuxer(SeekableOutput(pfd.fileDescriptor), ContainerFormat.MP4).use { ... }
 * }
 * ```
 *
 * El descriptor tiene que ser **posicionable y legible-escribible**: los tres muxers
 * vuelven atrás a parchear tamaños e índices al finalizar. Una tubería no sirve; para
 * salida puramente secuencial está el MP4 fragmentado, que igualmente pasa por aquí.
 *
 * **Todos los constructores truncan el destino** si trae contenido, igual que abrir un archivo
 * para escribirlo: lo que se escribe empieza siempre en la posición 0, y un resto del contenido
 * anterior detrás del final haría que el archivo pareciera más largo de lo que es —en Matroska,
 * con su escritura a prueba de cortes, ese resto se leería como parte del último cluster—. Hasta
 * la 3.x solo truncaba el constructor de [File]; el de descriptor dependía de que se abriera en
 * modo `"rwt"`.
 *
 * **No es seguro entre hilos**: igual que los muxers que lo usan, asume un único hilo
 * escritor. Con varias pistas produciendo a la vez, serializar las escrituras es
 * responsabilidad de quien integra (o de `Kotmpeg.synchronizedMuxer`).
 */
public class SeekableOutput private constructor(
    private val channel: FileChannel,
    /** El descriptor del constructor que lo recibe, para poder leer de él (ver [insert]). */
    private val fd: FileDescriptor?,
) : Closeable {

    /** Escribe en [channel], que tiene que ser posicionable; [close] lo cierra. */
    public constructor(channel: FileChannel) : this(channel, null)

    /**
     * Si no se puede truncar —un descriptor de solo lectura, por ejemplo—, el canal se cierra
     * antes de propagar el error: quien lo pasó ya no tiene otra forma de recuperarlo.
     *
     * Un canal de solo lectura —un `RandomAccessFile` en `"r"`, un `FileChannel` abierto solo con
     * `READ`— se rechaza aquí con `IllegalArgumentException`, con una escritura vacía que no toca
     * el archivo. Antes salía una `NonWritableChannelException` sin mensaje, y con un destino
     * vacío ni siquiera aquí, sino en la primera escritura de verdad, con la grabación empezada.
     */
    init {
        try {
            channel.write(ByteBuffer.allocate(0), 0)
            if (channel.size() > 0) channel.truncate(0)
        } catch (e: NonWritableChannelException) {
            runCatching { channel.close() }
            throw IllegalArgumentException("el destino es de solo lectura: ábrelo para escribir (\"rw\")", e)
        } catch (t: Throwable) {
            runCatching { channel.close() }
            throw t
        }
    }

    public constructor(file: File) : this(RandomAccessFile(file, "rw").channel, null)

    public constructor(file: RandomAccessFile) : this(file.channel, null)

    /**
     * Escribe en un descriptor ya abierto (p. ej. `ParcelFileDescriptor.getFileDescriptor()`
     * en modo `"rw"`).
     *
     * [close] cierra los canales que abre sobre el descriptor, pero el descriptor es de quien lo
     * abrió: en Android `FileInputStream`/`FileOutputStream` no se adueñan de un `FileDescriptor`
     * ajeno, así que el `ParcelFileDescriptor` hay que cerrarlo aparte (con `use`, como en el
     * ejemplo de arriba).
     */
    public constructor(fd: FileDescriptor) : this(FileOutputStream(fd).channel, fd)

    private val buffer = ByteArray(BUFFER_SIZE)
    private var bufferUsed = 0
    private var flushedPosition = 0L
    private var closed = false

    /** El canal de lectura de un descriptor, abierto la primera vez que hace falta (ver [readableChannel]). */
    private var readable: FileChannel? = null

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

    /**
     * Escribe los [byteCount] bytes menos significativos de [value], big-endian (de 0 a 8).
     *
     * Hasta la 3.x se llamaba `writeBits`, aunque lo que cuenta son **bytes**, igual que
     * `SeekableInput.readUInt`.
     */
    public fun writeUInt(value: Long, byteCount: Int) {
        require(byteCount in 0..8) { "solo se pueden escribir de 0 a 8 bytes como entero: $byteCount" }
        if (bufferUsed + byteCount > buffer.size) flush()
        for (i in byteCount - 1 downTo 0) buffer[bufferUsed++] = (value ushr (8 * i)).toByte()
    }

    public fun writeInt32(value: Int): Unit = writeUInt(value.toLong() and 0xFFFFFFFFL, 4)
    public fun writeInt64(value: Long): Unit = writeUInt(value, 8)

    /**
     * Reescribe [data] en la posición absoluta [at] sin mover la posición de escritura.
     *
     * Si esa zona sigue en el buffer, se reescribe ahí y no llega al archivo hasta el próximo
     * vaciado, junto con lo demás. Si ya está en el archivo, antes se vacía el buffer: **todo lo
     * escrito antes del parche llega al archivo antes que él**. Es lo que permite a `MkvMuxer`
     * publicar un cluster en el índice sabiendo que el cluster ya está entero en el archivo.
     *
     * Hasta la 4.0 vaciaba el buffer siempre, y cerrar cada elemento de la cabecera de un MKV
     * —que se hace parcheando su tamaño— era una escritura al sistema: la cabecera llegaba al
     * archivo en decenas de trozos.
     */
    public fun patch(at: Long, data: ByteArray) {
        require(at >= 0 && at + data.size <= position) { "patch fuera de la zona escrita" }
        if (at >= flushedPosition) {
            System.arraycopy(data, 0, buffer, (at - flushedPosition).toInt(), data.size)
            return
        }
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
     * Vacía el buffer y obliga al sistema a llevar los datos al almacenamiento físico.
     *
     * Sin esto, lo escrito puede vivir solo en la caché del kernel: sobrevive a que muera el
     * proceso, pero no a un corte de batería. Es caro (en un móvil, decenas o cientos de
     * milisegundos), así que los muxers no lo llaman por su cuenta; quien necesite durabilidad
     * lo invoca en los puntos que elija.
     */
    public fun sync() {
        flush()
        channel.force(false)
    }

    /**
     * Inserta [data] en la posición [at] desplazando hacia el final todo lo escrito desde ahí, y
     * deja la posición de escritura al final. Es como `Mp4Muxer` recoloca el `moov` delante de los
     * datos cuando no hay una ruta con la que crear un temporal.
     *
     * Copia de atrás hacia delante, de [SHIFT_CHUNK] en [SHIFT_CHUNK] bytes, para que ningún bloque
     * pise lo que aún no se ha movido; no necesita más memoria que ese bloque ni más disco que el
     * que ocupe [data]. No es atómico: un fallo a mitad deja el archivo inservible.
     *
     * Necesita **leer** el destino. Un [RandomAccessFile] en `"rw"` lo permite, y un descriptor se
     * lee a través de otro canal sobre el mismo descriptor; un canal abierto solo para escribir
     * lanza `IOException`. Quien lo vaya a usar lo comprueba antes con [requireReadable], porque
     * descubrirlo aquí, al final de una grabación, es descubrirlo cuando ya no tiene arreglo.
     */
    internal fun insert(at: Long, data: ByteArray) {
        require(at in 0..position) { "inserción fuera de la zona escrita: $at" }
        flush()
        val end = flushedPosition
        val source = readableChannel()
        val chunk = ByteArray(SHIFT_CHUNK)
        var remaining = end - at
        try {
            while (remaining > 0) {
                val length = minOf(remaining, SHIFT_CHUNK.toLong()).toInt()
                val from = at + remaining - length
                val view = ByteBuffer.wrap(chunk, 0, length)
                while (view.hasRemaining()) {
                    if (source.read(view, from + view.position()) < 0) throw EOFException("el destino acaba antes de $end")
                }
                writeAt(from + data.size, chunk, 0, length)
                remaining -= length
            }
        } catch (e: NonReadableChannelException) {
            throw IOException("no se puede leer el destino para desplazar sus datos: ábrelo en modo \"rw\"", e)
        }
        writeAt(at, data, 0, data.size)
        flushedPosition = end + data.size
    }

    /**
     * Comprueba que [insert] podrá leer el destino, leyendo un byte de su principio, y si no, lanza
     * `IllegalArgumentException` con [usage] —para qué hacía falta— y cómo abrirlo. Un destino
     * vacío se puede leer igual: lo que falla es el permiso, no la falta de datos.
     *
     * Existe por los descriptores de Android: `ContentResolver.openFileDescriptor(uri, "w")`, el
     * modo más habitual, da uno de solo escritura, y sin esta comprobación el inicio rápido
     * fallaba en `stop()`, con la grabación entera escrita y sin índice en ninguna parte.
     */
    internal fun requireReadable(usage: String) {
        try {
            readableChannel().read(ByteBuffer.allocate(1), 0)
        } catch (e: NonReadableChannelException) {
            throw IllegalArgumentException(unreadable(usage, "el canal es solo de escritura"), e)
        } catch (e: IOException) {
            throw IllegalArgumentException(unreadable(usage, e.message), e)
        }
    }

    private fun unreadable(usage: String, cause: String?): String =
        "$usage necesita leer el destino, y este no se puede leer ($cause): ábrelo en modo \"rw\" " +
            "(con ContentResolver, openFileDescriptor(uri, \"rw\"))"

    /**
     * Un canal del que leer lo escrito. El de un descriptor no sirve —`FileOutputStream` solo
     * escribe—, así que se abre otro de lectura sobre el mismo descriptor, una sola vez, y [close]
     * lo cierra después del de escritura. Cerrarlo antes no se puede: en la JVM, cerrar un
     * `FileInputStream` cierra su descriptor, que es el de la salida. Dejarlo sin cerrar tampoco:
     * en Android, un canal abandonado es lo que `StrictMode` denuncia como recurso perdido.
     */
    private fun readableChannel(): FileChannel {
        val descriptor = fd ?: return channel
        return readable ?: FileInputStream(descriptor).channel.also { readable = it }
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

    /**
     * El canal se cierra **aunque falle el vaciado del buffer**: sin el `finally`, un disco
     * lleno en el último `flush` dejaba el descriptor abierto para siempre, y en Android eso
     * acaba agotando el límite de descriptores del proceso. Después se cierra el de lectura, si
     * se abrió (ver [readableChannel]). Llamarlo dos veces no hace nada.
     */
    override fun close() {
        if (closed) return
        closed = true
        try {
            flush()
        } finally {
            try {
                channel.close()
            } finally {
                readable?.let { runCatching { it.close() } }
            }
        }
    }

    private companion object {
        private const val BUFFER_SIZE = 1 shl 16

        /** Bloque de copia de [insert]: 1 MiB, bastante para que el coste sea el del disco. */
        private const val SHIFT_CHUNK = 1 shl 20
    }
}
