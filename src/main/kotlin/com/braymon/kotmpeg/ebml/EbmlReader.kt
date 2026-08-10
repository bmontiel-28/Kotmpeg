package com.braymon.kotmpeg.ebml

import com.braymon.kotmpeg.io.SeekableInput

/** Una cabecera de elemento parseada. [size] es -1 para maestros de tamaño desconocido. */
public class EbmlElement(
    public val id: Long,
    public val size: Long,
    /** Posición absoluta del primer byte de la carga. */
    public val dataStart: Long,
) {
    public val dataEnd: Long get() = if (size < 0) Long.MAX_VALUE else dataStart + size
}

/**
 * Parser EBML de bajo nivel (RFC 8794).
 */
public class EbmlReader(public val input: SeekableInput) {

    /** Lee un id de elemento, conservando el bit marcador (así se definen los ids). */
    public fun readId(): Long {
        val first = input.readByte()
        val length = lengthFromMarker(first, "id de elemento")
        if (length > 4) {
            throw EbmlException("id de elemento inválido de $length octetos en ${input.position - 1}")
        }
        var id = first.toLong()
        repeat(length - 1) { id = (id shl 8) or input.readByte().toLong() }
        return id
    }

    /** Lee un VINT de tamaño. Devuelve -1 para el valor reservado "tamaño desconocido". */
    public fun readVintSize(): Long {
        val first = input.readByte()
        val length = lengthFromMarker(first, "vint de tamaño")
        var value = (first.toLong() and ((1L shl (8 - length)) - 1))
        var allOnes = value == (1L shl (8 - length)) - 1
        repeat(length - 1) {
            val b = input.readByte().toLong()
            allOnes = allOnes && b == 0xFFL
            value = (value shl 8) or b
        }
        return if (allOnes) -1 else value
    }

    private fun lengthFromMarker(firstByte: Int, what: String): Int {
        if (firstByte == 0) throw EbmlException("$what inválido (byte inicial 0) en ${input.position - 1}")
        var length = 1
        var mask = 0x80
        while (firstByte and mask == 0) {
            length++
            mask = mask shr 1
        }
        return length
    }

    /** Lee la cabecera del siguiente elemento en la posición actual. */
    public fun readElement(): EbmlElement {
        val id = readId()
        val size = readVintSize()
        return EbmlElement(id, size, input.position)
    }

    /**
     * Lee un entero sin signo de hasta 8 bytes.
     *
     * El valor se devuelve en un `Long` con signo: un uint de 8 bytes con el bit más alto a
     * 1 saldría negativo. En la práctica no ocurre — los campos que usan esto (posiciones,
     * marcas de tiempo, dimensiones) son de 4 bytes o menos — y quien lo consume comprueba
     * `>= 0` antes de usarlo.
     */
    public fun readUInt(element: EbmlElement): Long {
        require(element.size in 0..8) { "uint de tamaño ${element.size}" }
        return input.readBits(element.size.toInt())
    }

    public fun readSInt(element: EbmlElement): Long {
        require(element.size in 0..8)
        if (element.size == 0L) return 0
        var v = input.readByte().toLong()
        if (v and 0x80L != 0L) v = v or -0x100L
        repeat(element.size.toInt() - 1) { v = (v shl 8) or input.readByte().toLong() }
        return v
    }

    public fun readFloat(element: EbmlElement): Double = when (element.size) {
        0L -> 0.0
        4L -> Float.fromBits(input.readInt32()).toDouble()
        8L -> Double.fromBits(input.readInt64())
        else -> throw EbmlException("tamaño de float inválido ${element.size}")
    }

    public fun readString(element: EbmlElement): String =
        String(readPayload(element), Charsets.UTF_8).trimEnd('\u0000', ' ')

    public fun readBinary(element: EbmlElement): ByteArray = readPayload(element)

    /**
     * Lee la carga completa de un elemento. Valida el tamaño antes de truncarlo a Int: un
     * tamaño declarado >= 2 GiB envolvería a un entero positivo pequeño y se leerían muchos
     * menos bytes de los declarados, desincronizando el parser sin ningún error inmediato.
     */
    private fun readPayload(element: EbmlElement): ByteArray {
        val size = element.size
        if (size < 0) {
            throw EbmlException("elemento 0x${element.id.toString(16)} de tamaño desconocido sin carga legible")
        }
        if (size > Int.MAX_VALUE) {
            throw EbmlException("elemento 0x${element.id.toString(16)} demasiado grande: $size bytes")
        }
        if (size > input.remaining) {
            throw EbmlException("elemento 0x${element.id.toString(16)} de $size bytes excede el final del archivo")
        }
        return input.readBytes(size.toInt())
    }

    public fun skip(element: EbmlElement) {
        if (element.size < 0) throw EbmlException("no se puede saltar un elemento de tamaño desconocido 0x${element.id.toString(16)}")
        input.position = element.dataStart + element.size
    }
}

public class EbmlException(message: String) : RuntimeException(message)
