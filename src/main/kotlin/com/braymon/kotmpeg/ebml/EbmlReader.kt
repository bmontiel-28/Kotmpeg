package com.braymon.kotmpeg.ebml

import com.braymon.kotmpeg.io.SeekableInput
import java.io.EOFException

/** Una cabecera de elemento parseada. [size] es -1 para maestros de tamaño desconocido. */
internal class EbmlElement(
    val id: Long,
    val size: Long,
    /** Posición absoluta del primer byte de la carga. */
    val dataStart: Long,
) {
    val dataEnd: Long get() = if (size < 0) Long.MAX_VALUE else dataStart + size
}

/**
 * Parser EBML de bajo nivel (RFC 8794).
 */
internal class EbmlReader(val input: SeekableInput) {

    /** Lee un id de elemento, conservando el bit marcador (así se definen los ids). */
    fun readId(): Long {
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
    fun readVintSize(): Long {
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
        val length = vintLength(firstByte)
        if (length == 0) throw EbmlException("$what inválido (byte inicial 0) en ${input.position - 1}")
        return length
    }

    /** Lee la cabecera del siguiente elemento en la posición actual. */
    fun readElement(): EbmlElement {
        val id = readId()
        val size = readVintSize()
        return EbmlElement(id, size, input.position)
    }

    /**
     * Lee un entero sin signo de hasta 8 bytes, o lanza `IllegalArgumentException` si el elemento
     * declara más; la reparación de Matroska cuenta con ese tipo.
     *
     * El valor se devuelve en un `Long` con signo: un uint de 8 bytes con el bit más alto a 1 sale
     * **negativo**. Un archivo sano no lo escribe nunca —posiciones, tiempos y dimensiones caben de
     * sobra—, pero uno manipulado sí, así que quien lo consume tiene que comprobar el rango que
     * espera antes de usarlo: `>= 0` para un tiempo o una posición, y que quepa en `Int` antes de
     * un `toInt()`, que si no se queda en silencio con los 32 bits bajos.
     */
    fun readUInt(element: EbmlElement): Long {
        require(element.size in 0..8) { "uint de tamaño ${element.size}" }
        return input.readUInt(element.size.toInt())
    }

    /**
     * Lee un `SeekID`: el id del elemento al que apunta una entrada del `SeekHead`, guardado como
     * binario con su marcador, que es leerlo como entero sin signo. Uno de más de 8 bytes no puede
     * ser un id —ninguno pasa de 4— y no se lee: se salta y da 0, que no es el de ningún elemento.
     * Su tamaño solo está acotado por el del archivo, y leerlo entero reservaba esa memoria.
     *
     * Es el único sitio donde se decodifica: el demuxer y la reparación lo repetían cada uno.
     */
    fun readSeekId(element: EbmlElement): Long {
        if (element.size in 1..8) return readUInt(element)
        skip(element)
        return 0L
    }

    fun readFloat(element: EbmlElement): Double = when (element.size) {
        0L -> 0.0
        4L -> Float.fromBits(input.readInt32()).toDouble()
        8L -> Double.fromBits(input.readInt64())
        else -> throw EbmlException("tamaño de float inválido ${element.size}")
    }

    fun readString(element: EbmlElement): String =
        String(readPayload(element), Charsets.UTF_8).trimEnd('\u0000', ' ')

    fun readBinary(element: EbmlElement): ByteArray = readPayload(element)

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

    fun skip(element: EbmlElement) {
        if (element.size < 0) throw EbmlException("no se puede saltar un elemento de tamaño desconocido 0x${element.id.toString(16)}")
        input.position = element.dataStart + element.size
    }

    /**
     * Lo que no depende de una lectura en curso. Va en el compañero y no como funciones de nivel
     * superior porque estas generan una clase fachada `EbmlReaderKt` que el bytecode expone como
     * pública aunque las funciones sean `internal`, y aparecería en `public-api.txt`.
     */
    companion object {
        /**
         * Longitud de un VINT de EBML por su primer byte —de 1 a 8, lo que marca su primer bit a
         * 1—, o 0 si el byte es 0 y no marca ninguna.
         *
         * Es la única decodificación del marcador: la usan los ids y tamaños de [EbmlReader], el
         * número de pista de la cabecera de un bloque y los tamaños del lacing EBML, que antes
         * repetían cada uno su propio bucle.
         */
        fun vintLength(firstByte: Int): Int =
            if (firstByte and 0xFF == 0) 0 else Integer.numberOfLeadingZeros(firstByte and 0xFF) - 23

        /**
         * Si [e] es de los fallos que da un archivo EBML dañado o manipulado —un elemento
         * imposible ([EbmlException]), un corte (`EOFException`), un valor fuera de rango
         * (`IllegalArgumentException`, el de [readUInt] y el de una posición imposible)— y no un
         * error de E/S de verdad, que no es un archivo dañado sino un disco o un descriptor que
         * fallan y tiene que llegar a quien lee.
         */
        fun isCorruptData(e: Exception): Boolean =
            e is EbmlException || e is EOFException || e is IllegalArgumentException
    }
}

public class EbmlException(message: String) : RuntimeException(message)
