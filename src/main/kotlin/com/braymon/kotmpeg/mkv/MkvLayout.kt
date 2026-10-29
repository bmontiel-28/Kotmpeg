package com.braymon.kotmpeg.mkv

import com.braymon.kotmpeg.ebml.EbmlWriter
import com.braymon.kotmpeg.ebml.MatroskaIds
import java.io.ByteArrayOutputStream

/**
 * Las piezas de Matroska que escriben igual [MkvMuxer] y la reparación en el sitio: el `SeekHead`
 * en su hueco fijo y el índice `Cues`, incluido el hueco reservado delante de los clusters en el
 * que el muxer lo va ampliando mientras graba.
 *
 * ## Ampliar sin que un corte lo rompa
 *
 * Los dos se escriben con un `Void` libre al final de su contenido, que cubre lo que queda de su
 * hueco:
 *
 * ```
 * SeekHead(120 bytes)   [Seek Info][Seek Tracks][Seek Cues][Void libre ………]
 * Cues(tamaño fijo)     [CuePoint…][Void corto][CuePoint…][Void libre ……………………]
 * ```
 *
 * Añadirles algo **no puede dejar el elemento roto si el proceso muere a mitad**, así que se hace
 * en dos escrituras ([append]):
 *
 * 1. Lo nuevo y la cabecera del `Void` libre que irá detrás se escriben justo después de la
 *    cabecera del `Void` libre actual, es decir, **dentro de su carga**: para cualquier lector
 *    siguen siendo relleno, se corte donde se corte esta escritura.
 * 2. Un solo byte —el primero del tamaño del `Void` libre actual— lo convierte en un `Void` corto
 *    que solo cubre su propia cabecera ([separator]). Desde ese momento lo escrito en el paso 1
 *    forma parte del elemento. Una escritura de un byte no se queda a medias.
 *
 * Cada ampliación deja así un `Void` entre tandas tan largo como la cabecera del `Void` libre que
 * había: de 2 a 5 bytes con las reservas que admite el muxer (5 pasados los 2 MiB), y hasta 9 en
 * el hueco de otro programa. Es válido en cualquier elemento maestro (RFC 8794 §17.2) y los
 * lectores lo saltan como cualquier otro relleno.
 */
internal object MkvLayout {

    /** Espacio fijo reservado al inicio del segmento para el `SeekHead`. */
    const val SEEK_HEAD_RESERVED = 120

    /** Cabecera del `SeekHead` y de los `Cues` que escribe esta librería: id de 4 bytes y tamaño de 8. */
    const val MASTER_HEADER_SIZE = 12

    /** Cabecera de `Void` con tamaño de 8 bytes: la que neutraliza una reserva entera. */
    const val LONG_VOID_HEADER_SIZE = 9

    /** Bytes de la posición de una entrada de [seekEntry], que son siempre los últimos. */
    private const val SEEK_POSITION_SIZE = 8

    /**
     * Un `SeekHead` de [seekHead]: sus [bytes] y, contados desde su principio, dónde empieza su
     * `Void` libre y cuánto mide su cabecera ([freeOffset] es -1 si no le queda), y dónde están los
     * 8 bytes de la posición de su entrada `Cues` ([cuesPositionOffset] es -1 si no la tiene).
     */
    class SeekHeadBytes(val bytes: ByteArray, val freeOffset: Int, val freeHeaderLength: Int, val cuesPositionOffset: Int)

    /**
     * Un `SeekHead` de exactamente [total] bytes con una entrada por cada `(id, posición)` de
     * [targets] y, detrás de ellas, el `Void` libre en el que se pueden añadir más. `null` si no
     * caben. Las posiciones son relativas a los datos del segmento.
     */
    fun seekHead(targets: List<Pair<Long, Long>>, total: Int): SeekHeadBytes? {
        val entries = targets.map { (id, position) -> seekEntry(id, position) }
        val used = entries.sumOf { it.size }
        val free = total - MASTER_HEADER_SIZE - used
        if (free != 0 && free < 2) return null
        val out = ByteArrayOutputStream(total)
        out.write(EbmlWriter.idBytes(MatroskaIds.SEEK_HEAD))
        out.write(EbmlWriter.encodeVintSize((total - MASTER_HEADER_SIZE).toLong(), 8))
        var cuesPositionOffset = -1
        for ((i, entry) in entries.withIndex()) {
            out.write(entry)
            if (targets[i].first == MatroskaIds.CUES) cuesPositionOffset = out.size() - SEEK_POSITION_SIZE
        }
        var freeHeaderLength = 0
        if (free > 0) {
            val header = freeSpaceHeader(free.toLong())
            out.write(header)
            out.write(ByteArray(free - header.size))
            freeHeaderLength = header.size
        }
        return SeekHeadBytes(out.toByteArray(), if (free > 0) MASTER_HEADER_SIZE + used else -1, freeHeaderLength, cuesPositionOffset)
    }

    /** Una entrada `Seek` que apunta a [targetId] en [position], relativa a los datos del segmento. */
    fun seekEntry(targetId: Long, position: Long): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(element(MatroskaIds.SEEK_ID, EbmlWriter.idBytes(targetId)))
        body.write(EbmlWriter.idBytes(MatroskaIds.SEEK_POSITION))
        body.write(EbmlWriter.encodeVintSize(SEEK_POSITION_SIZE.toLong(), 1))
        body.write(EbmlWriter.bigEndian(position, SEEK_POSITION_SIZE))
        return element(MatroskaIds.SEEK, body.toByteArray())
    }

    /**
     * Un `CuePoint` con todos sus tamaños en la forma más corta: unos 18 bytes. [time] va en
     * ticks de `TimestampScale` y [clusterPosition] es relativa a los datos del segmento.
     */
    fun cuePoint(time: Long, track: Long, clusterPosition: Long): ByteArray {
        val positions = uint(MatroskaIds.CUE_TRACK, track) + uint(MatroskaIds.CUE_CLUSTER_POSITION, clusterPosition)
        return element(MatroskaIds.CUE_POINT, uint(MatroskaIds.CUE_TIME, time) + element(MatroskaIds.CUE_TRACK_POSITIONS, positions))
    }

    /** Un elemento `Cues` completo con [points] dentro, para escribirlo al final del archivo. */
    fun cues(points: ByteArray): ByteArray =
        EbmlWriter.idBytes(MatroskaIds.CUES) + EbmlWriter.encodeVintSize(points.size.toLong(), 8) + points

    /**
     * Lo que va al principio de una reserva de [total] bytes: la cabecera de `Cues`, que la cubre
     * entera, y la del `Void` libre, que cubre el resto. Detrás solo hace falta relleno.
     */
    fun reserveHeader(total: Int): ByteArray =
        EbmlWriter.idBytes(MatroskaIds.CUES) +
            EbmlWriter.encodeVintSize((total - MASTER_HEADER_SIZE).toLong(), 8) +
            freeSpaceHeader((total - MASTER_HEADER_SIZE).toLong())

    /**
     * Una ampliación de [append]: sus dos escrituras, en orden —la segunda es el byte que la
     * confirma—, y dónde queda el `Void` libre siguiente y cuánto mide su cabecera.
     */
    class Append(val writes: List<Pair<Long, ByteArray>>, val nextFreePos: Long, val nextFreeHeaderLength: Int)

    /**
     * Las escrituras que añaden [data] en el `Void` libre de [freePos], cuya cabecera mide
     * [freeHeaderLength] bytes, de un hueco que acaba en [end], en los dos pasos descritos arriba.
     * `null` si no cabe dejando detrás otro `Void` libre, que necesita al menos 2 bytes.
     *
     * Ese `Void` libre se deja siempre, aunque [data] cupiera justo: es lo que hacía el muxer, y con
     * una sola versión del protocolo, el muxer y la reparación deciden igual cuándo el índice ya no
     * cabe en la reserva y hay que llevarlo al final.
     */
    fun append(freePos: Long, freeHeaderLength: Int, end: Long, data: ByteArray): Append? {
        val nextFreePos = freePos + freeHeaderLength + data.size
        val nextFree = end - nextFreePos
        if (nextFree < 2) return null
        val nextHeader = freeSpaceHeader(nextFree)
        val writes = listOf(
            freePos + freeHeaderLength to data + nextHeader,
            freePos + 1 to byteArrayOf(separator(freeHeaderLength)),
        )
        return Append(writes, nextFreePos, nextHeader.size)
    }

    /**
     * Cabecera de un `Void` que ocupa [total] bytes en total contando ella misma, con el campo de
     * tamaño más corto posible (que es el que luego reduce [separator]). [total] tiene que ser al
     * menos 2.
     */
    fun freeSpaceHeader(total: Long): ByteArray {
        require(total >= 2) { "un Void necesita al menos 2 bytes: $total" }
        var length = 1
        while (total - 1 - length >= (1L shl (7 * length)) - 1) length++
        return byteArrayOf(MatroskaIds.VOID.toByte()) + EbmlWriter.encodeVintSize(total - 1 - length, length)
    }

    /**
     * El byte que, escrito tras la id de un `Void` libre cuya cabecera mide [headerLength] bytes,
     * lo convierte en un `Void` de exactamente ese tamaño: un tamaño de 1 byte que cubre lo que
     * queda del campo original.
     */
    fun separator(headerLength: Int): Byte = (0x80 or (headerLength - 2)).toByte()

    /** Cabecera de `Void` que convierte en relleno una reserva entera de [total] bytes. */
    fun voidOver(total: Long): ByteArray {
        require(total >= LONG_VOID_HEADER_SIZE) { "no cabe un Void de 8 bytes de tamaño en $total" }
        return byteArrayOf(MatroskaIds.VOID.toByte()) + EbmlWriter.encodeVintSize(total - LONG_VOID_HEADER_SIZE, 8)
    }

    private fun uint(id: Long, value: Long): ByteArray = element(id, EbmlWriter.encodeUInt(value))

    private fun element(id: Long, payload: ByteArray): ByteArray {
        val size = payload.size.toLong()
        return EbmlWriter.idBytes(id) + EbmlWriter.encodeVintSize(size, EbmlWriter.vintSizeLength(size)) + payload
    }
}
