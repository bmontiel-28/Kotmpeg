package com.braymon.kotmpeg

import com.braymon.kotmpeg.ebml.MatroskaIds
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Matroska escrito byte a byte para los tests `MkvRead*`.
 *
 * Ni `MkvMuxer` ni `EbmlWriter` sirven para estos casos: no escriben `ContentEncodings`, ni
 * elementos de tamaño desconocido donde no tocan, ni números de pista imposibles, ni índices que
 * apuntan fuera del archivo, que es justo lo que hay que dar al demuxer. Aquí cada elemento son
 * sus bytes, con el tamaño en la codificación más corta —un ataque de cardinalidad depende de que
 * cada elemento ocupe lo mínimo— y todo en memoria.
 */
internal object MkvReadBuilder {

    /** Los bytes de un id, que ya lleva su marcador de longitud. */
    fun id(id: Long): ByteArray {
        val length = (64 - java.lang.Long.numberOfLeadingZeros(id) + 7) / 8
        return ByteArray(length) { (id shr (8 * (length - 1 - it))).toByte() }
    }

    /** VINT de tamaño en la longitud mínima, sin usar nunca el valor reservado de todo unos. */
    fun vint(value: Long): ByteArray {
        var length = 1
        while (length < 8 && value >= (1L shl (7 * length)) - 1) length++
        val out = ByteArray(length) { (value shr (8 * (length - 1 - it))).toByte() }
        out[0] = (out[0].toInt() or (0x80 shr (length - 1))).toByte()
        return out
    }

    fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun element(id: Long, vararg payload: ByteArray): ByteArray = elementOf(id, payload.toList())

    fun elementOf(id: Long, payload: List<ByteArray>): ByteArray {
        val body = concat(payload)
        return concat(listOf(id(id), vint(body.size.toLong()), body))
    }

    /** Un maestro con el tamaño reservado "desconocido": el de una grabación sin cerrar. */
    fun unknownSize(id: Long, vararg payload: ByteArray): ByteArray =
        concat(listOf(id(id), byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1)) + payload.toList())

    /** Entero sin signo en los bytes justos (al menos uno). */
    fun uint(id: Long, value: Long): ByteArray {
        var length = 1
        while (length < 8 && (value ushr (8 * length)) != 0L) length++
        return element(id, ByteArray(length) { (value shr (8 * (length - 1 - it))).toByte() })
    }

    fun float(id: Long, value: Double): ByteArray {
        val bits = value.toRawBits()
        return element(id, ByteArray(8) { (bits shr (8 * (7 - it))).toByte() })
    }

    fun string(id: Long, value: String): ByteArray = element(id, value.toByteArray(Charsets.UTF_8))

    fun ebmlHeader(): ByteArray = element(MatroskaIds.EBML, string(MatroskaIds.DOCTYPE, "matroska"))

    /** Archivo completo: cabecera EBML y un `Segment` de tamaño conocido con [children]. */
    fun file(vararg children: ByteArray): ByteArray = concat(listOf(ebmlHeader(), element(MatroskaIds.SEGMENT, *children)))

    fun fileOf(children: List<ByteArray>): ByteArray = concat(listOf(ebmlHeader(), elementOf(MatroskaIds.SEGMENT, children)))

    /** Igual, con el `Segment` de tamaño desconocido, como lo deja una grabación cortada. */
    fun openFile(vararg children: ByteArray): ByteArray =
        concat(listOf(ebmlHeader(), unknownSize(MatroskaIds.SEGMENT, *children)))

    fun info(timestampScaleNs: Long = 1_000_000): ByteArray =
        element(MatroskaIds.INFO, uint(MatroskaIds.TIMESTAMP_SCALE, timestampScaleNs))

    fun tracks(vararg entries: ByteArray): ByteArray = element(MatroskaIds.TRACKS, *entries)

    /** Pista H.264 de 64x48; [video] se añade dentro del elemento `Video` (p. ej. un `Colour`). */
    fun videoTrack(
        number: Long,
        codecId: String = "V_MPEG4/ISO/AVC",
        video: List<ByteArray> = emptyList(),
        width: Long = 64,
    ): ByteArray = element(
        MatroskaIds.TRACK_ENTRY,
        uint(MatroskaIds.TRACK_NUMBER, number),
        uint(MatroskaIds.TRACK_TYPE, MatroskaIds.TRACK_TYPE_VIDEO),
        string(MatroskaIds.CODEC_ID, codecId),
        elementOf(MatroskaIds.VIDEO, listOf(uint(MatroskaIds.PIXEL_WIDTH, width), uint(MatroskaIds.PIXEL_HEIGHT, 48)) + video),
    )

    fun audioTrack(number: Long, vararg extra: ByteArray): ByteArray =
        element(
            MatroskaIds.TRACK_ENTRY,
            uint(MatroskaIds.TRACK_NUMBER, number),
            uint(MatroskaIds.TRACK_TYPE, MatroskaIds.TRACK_TYPE_AUDIO),
            string(MatroskaIds.CODEC_ID, "A_OPUS"),
            element(MatroskaIds.AUDIO, float(MatroskaIds.SAMPLING_FREQUENCY, 48_000.0), uint(MatroskaIds.CHANNELS, 2)),
            *extra,
        )

    /** `ContentEncodings` con una sola compresión de [algorithm] (0 = zlib, 3 = eliminación de cabecera). */
    fun compression(algorithm: Long, settings: ByteArray? = null): ByteArray = element(
        MatroskaIds.CONTENT_ENCODINGS,
        element(
            MatroskaIds.CONTENT_ENCODING,
            elementOf(
                MatroskaIds.CONTENT_COMPRESSION,
                listOfNotNull(uint(MatroskaIds.CONTENT_COMP_ALGO, algorithm), settings?.let { element(MatroskaIds.CONTENT_COMP_SETTINGS, it) }),
            ),
        ),
    )

    fun cluster(timestamp: Long, vararg children: ByteArray): ByteArray =
        element(MatroskaIds.CLUSTER, uint(MatroskaIds.CLUSTER_TIMESTAMP, timestamp), *children)

    fun clusterOf(timestamp: Long, children: List<ByteArray>): ByteArray =
        elementOf(MatroskaIds.CLUSTER, listOf(uint(MatroskaIds.CLUSTER_TIMESTAMP, timestamp)) + children)

    /** Cabecera de bloque: pista, desfase en tics y flags, seguidos de [payload]. */
    fun blockBody(track: Long, relative: Int, flags: Int, payload: ByteArray): ByteArray =
        concat(listOf(vint(track), byteArrayOf((relative shr 8).toByte(), relative.toByte(), flags.toByte()), payload))

    fun simpleBlock(track: Long, relative: Int, key: Boolean, payload: ByteArray = byteArrayOf(0x11)): ByteArray =
        element(MatroskaIds.SIMPLE_BLOCK, blockBody(track, relative, if (key) 0x80 else 0, payload))

    /** `SimpleBlock` con lacing Xiph (1) o fijo (2) que lleva [frames] tal cual. */
    fun lacedBlock(track: Long, relative: Int, lacing: Int, frames: List<ByteArray>): ByteArray {
        val laced = ByteArrayOutputStream()
        laced.write(frames.size - 1)
        if (lacing == 1) {
            for (frame in frames.dropLast(1)) {
                var size = frame.size
                while (size >= 255) { laced.write(255); size -= 255 }
                laced.write(size)
            }
        }
        frames.forEach { laced.write(it) }
        return element(MatroskaIds.SIMPLE_BLOCK, blockBody(track, relative, 0x80 or (lacing shl 1), laced.toByteArray()))
    }

    fun cues(vararg points: ByteArray): ByteArray = element(MatroskaIds.CUES, *points)

    /**
     * Un `CuePoint` con un `CueTrackPositions` por cada par (pista, posición del cluster). La
     * posición va siempre en 8 bytes, para que el tamaño del índice no dependa de dónde caen los
     * clusters y se puedan calcular sus posiciones antes de escribirlo.
     */
    fun cuePoint(timeTicks: Long, vararg positions: Pair<Long, Long>): ByteArray = elementOf(
        MatroskaIds.CUE_POINT,
        listOf(uint(MatroskaIds.CUE_TIME, timeTicks)) + positions.map { (track, position) ->
            element(
                MatroskaIds.CUE_TRACK_POSITIONS,
                uint(MatroskaIds.CUE_TRACK, track),
                element(MatroskaIds.CUE_CLUSTER_POSITION, ByteArray(8) { (position shr (8 * (7 - it))).toByte() }),
            )
        },
    )

    fun zlib(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }

    /** zlib de [size] bytes a cero sin reservarlos de una vez: el peor caso de una bomba. */
    fun zlibOfZeros(size: Long): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val zeros = ByteArray(1 shl 20)
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = minOf(remaining, zeros.size.toLong()).toInt()
            deflater.setInput(zeros, 0, n)
            while (!deflater.needsInput()) out.write(chunk, 0, deflater.deflate(chunk))
            remaining -= n
        }
        deflater.finish()
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }

    /** Dónde empieza la primera aparición de [needle] en [haystack], o -1. */
    fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
