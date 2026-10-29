package com.braymon.kotmpeg.ebml

import com.braymon.kotmpeg.io.SeekableOutput
import java.io.ByteArrayOutputStream

/**
 * Serializador EBML de bajo nivel (RFC 8794), la base binaria de Matroska.
 */
internal class EbmlWriter(val out: SeekableOutput) {

    fun writeId(id: Long) {
        out.writeUInt(id, idLength(id))
    }

    /** Codificación VINT mínima de un tamaño de datos. */
    fun writeVintSize(value: Long) {
        val length = vintSizeLength(value)
        val marker = 1L shl (7 * length)
        out.writeUInt(marker or value, length)
    }

    /** Escribe un tamaño como VINT de exactamente [length] bytes (para tamaños parcheables). */
    fun writeVintSize(value: Long, length: Int) {
        require(length in 1..8) { "longitud de vint inválida: $length" }
        require(value >= 0 && value < (1L shl (7 * length)) - 1) {
            "el tamaño $value no cabe en un vint de $length bytes"
        }
        out.writeUInt((1L shl (7 * length)) or value, length)
    }

    fun writeUnknownSize(length: Int = 8) {
        out.writeUInt((1L shl (7 * length + 1)) - 1, length)
    }

    fun writeElement(id: Long, payload: ByteArray) {
        writeId(id)
        writeVintSize(payload.size.toLong())
        out.write(payload)
    }

    fun writeUInt(id: Long, value: Long) {
        writeElement(id, encodeUInt(value))
    }

    /**
     * Entero **con signo**, en complemento a dos y con carga fija de 8 bytes.
     *
     * Los 8 bytes son deliberados: la forma mínima con signo depende del bit alto del primer
     * byte —un 0x80 de un byte es −128, no 128— y elegirla mal produce un valor con el signo
     * cambiado que ninguna herramienta señala como error. Con anchura fija no hay ambigüedad, y
     * el único elemento que hoy la usa (`DateUTC`) declara 8 bytes de todas formas.
     */
    fun writeSInt(id: Long, value: Long) {
        val payload = ByteArray(8) { i -> (value shr (56 - 8 * i)).toByte() }
        writeElement(id, payload)
    }

    fun writeFloat(id: Long, value: Double) {
        writeId(id)
        writeVintSize(8)
        out.writeInt64(value.toRawBits())
    }

    fun writeString(id: Long, value: String) {
        writeElement(id, value.toByteArray(Charsets.UTF_8))
    }

    /** Escribe un elemento Void que ocupa exactamente [totalSize] bytes (id + tamaño + relleno). */
    fun writeVoid(totalSize: Int) {
        out.write(encodeVoid(totalSize))
    }

    /**
     * Abre un elemento maestro con campo de tamaño fijo de 8 bytes que [endMaster] parchea.
     * Devuelve la posición absoluta del campo de tamaño.
     */
    fun beginMaster(id: Long): Long {
        writeId(id)
        val sizePos = out.position
        writeUnknownSize(8)
        return sizePos
    }

    /** Cierra un maestro abierto con [beginMaster], parcheando su tamaño real. */
    fun endMaster(sizePos: Long) {
        val contentSize = out.position - (sizePos + 8)
        out.patch(sizePos, encodeVintSize(contentSize, 8))
    }

    companion object {
        /**
         * Bytes de un elemento Void que ocupa exactamente [totalSize] (id + tamaño + relleno).
         *
         * La longitud del campo de tamaño se elige según lo que haya que rellenar, y ese es
         * justo el detalle que hay que no reimplementar por ahí suelto: con un campo de 1
         * byte solo caben 126 de carga, porque el valor 127 (todo unos) está reservado para
         * "tamaño desconocido". Pasado ese punto hace falta un campo de 8 bytes.
         */
        fun encodeVoid(totalSize: Int): ByteArray {
            require(totalSize >= 2) { "un void necesita al menos 2 bytes" }
            val out = ByteArrayOutputStream(totalSize)
            out.write(MatroskaIds.VOID.toInt() and 0xFF)   // el id de Void es de 1 byte: 0xEC
            val payload = if (totalSize - 2 < 127) {
                out.write(encodeVintSize((totalSize - 2).toLong(), 1))
                totalSize - 2
            } else {
                out.write(encodeVintSize((totalSize - 9).toLong(), 8))
                totalSize - 9
            }
            repeat(payload) { out.write(0) }
            return out.toByteArray()
        }

        fun vintSizeLength(value: Long): Int {
            var length = 1
            while (value >= (1L shl (7 * length)) - 1) length++
            require(length <= 8) { "valor demasiado grande para un vint: $value" }
            return length
        }

        fun encodeVintSize(value: Long, length: Int): ByteArray {
            require(length in 1..8) { "longitud de vint inválida: $length" }
            require(value >= 0 && value < (1L shl (7 * length)) - 1) {
                "el tamaño $value no cabe en un vint de $length bytes"
            }
            return bigEndian((1L shl (7 * length)) or value, length)
        }

        fun encodeUInt(value: Long): ByteArray {
            require(value >= 0) { "un uint EBML no puede ser negativo: $value" }
            var len = 1
            while (len < 8 && (value ushr (8 * len)) != 0L) len++
            return bigEndian(value, len)
        }

        /** Bytes que ocupa un id de elemento (el bit marcador forma parte del id). */
        fun idLength(id: Long): Int = when {
            id <= 0xFF -> 1
            id <= 0xFFFF -> 2
            id <= 0xFFFFFF -> 3
            else -> 4
        }

        /** Un id de elemento tal como va en el archivo. */
        fun idBytes(id: Long): ByteArray = bigEndian(id, idLength(id))

        /**
         * Los [count] bytes menos significativos de [value], big-endian: el orden de todos los
         * enteros de EBML. Es la única versión, para los muxers y la reparación, de lo que antes
         * estaba escrito a mano en cuatro sitios.
         */
        fun bigEndian(value: Long, count: Int): ByteArray =
            ByteArray(count) { i -> (value ushr (8 * (count - 1 - i))).toByte() }
    }
}
