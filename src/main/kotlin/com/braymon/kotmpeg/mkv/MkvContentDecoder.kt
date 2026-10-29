package com.braymon.kotmpeg.mkv

import com.braymon.kotmpeg.ebml.EbmlException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Un `ContentEncoding` de compresión. Solo se conservan los que se saben deshacer —
 * eliminación de cabecera ([ALGO_HEADER_STRIPPING]) y zlib ([ALGO_ZLIB])—; una pista con
 * cualquier otro, o con cifrado, se descarta con aviso, porque entregar sus bloques tal cual sería
 * entregar datos que ningún decodificador puede usar.
 */
internal class ContentEncoding(val order: Long, val scope: Long, val algorithm: Long, val settings: ByteArray?) {

    /**
     * Los valores de la especificación y los topes de lo que se acepta de un archivo. Son
     * `internal` uno a uno por lo mismo que las constantes de `MkvDemuxer` son `private`: un
     * `const val` de un objeto compañero se compila como campo estático con su propia visibilidad.
     */
    internal companion object {
        /** `ContentEncodingScope`: bit 1 = fotogramas, bit 2 = `CodecPrivate`. */
        internal const val SCOPE_FRAMES = 1L
        internal const val SCOPE_PRIVATE = 2L

        /** `ContentEncodingType`: 0 = compresión, 1 = cifrado. */
        internal const val TYPE_COMPRESSION = 0L
        internal const val TYPE_ENCRYPTION = 1L

        /** `ContentCompAlgo`: 0 = zlib, 3 = eliminación de cabecera. bzlib (1) y lzo (2) no. */
        internal const val ALGO_ZLIB = 0L
        internal const val ALGO_HEADER_STRIPPING = 3L

        /**
         * Lo más que se acepta en una cadena de `ContentEncoding`. Un muxer real escribe una, y la
         * especificación solo contempla apilar compresión y cifrado; sin tope, 100 000
         * eliminaciones de cabecera de 14 bytes concatenaban en cadena unos 5 GB por fotograma.
         */
        internal const val MAX_ENCODINGS = 8

        /**
         * Lo más que se acepta de `ContentCompSettings` en una eliminación de cabecera. Lo que
         * quitan los muxers son unos pocos bytes —una palabra de sincronía, un prefijo de NAL—, y
         * ese prefijo se copia delante de **cada** fotograma: con 1 MiB, un bloque de 7 bytes con
         * 256 fotogramas vacíos eran 256 MiB.
         */
        internal const val MAX_HEADER_STRIPPING_BYTES = 4096
    }
}

/**
 * Lo que deja un bloque al deshacer sus codificaciones: un fotograma por cada uno de entrada,
 * `null` el que no se pudo, cuántos fueron y el motivo del primero, para un único aviso por bloque.
 */
internal class DecodedFrames(val frames: List<ByteArray?>, val discarded: Int, val firstCause: String?)

/**
 * Deshace la compresión de contenido de Matroska con dos techos: [MAX_INFLATED_BYTES] por
 * fotograma, el que promete `SECURITY.md`, y el mismo para **todo un bloque**. Uno solo por
 * fotograma no bastaba: un bloque con lacing lleva hasta 256, y 256 fotogramas Xiph de 1 KB que
 * inflan 1 MiB cada uno eran 256 MiB en la cola con un archivo de 256 KB. El del bloque cuenta
 * todo lo que producen sus pasos, también los intermedios de una cadena, así que lo que reserva un
 * bloque queda acotado por él.
 *
 * El descompresor zlib y su buffer de trabajo se reutilizan entre fotogramas. Crearlos para cada
 * uno costaba 64 KB de basura por fotograma más la memoria nativa de un `Inflater` nuevo: con
 * lacing, un bloque de 256 fotogramas diminutos eran 16 MB por bloque, y un MKV de menos de 1 KB
 * llegaba a reservar más de 100 MB en una lectura. Se crean la primera vez que hacen falta, porque
 * casi ningún MKV comprime nada, y [close] libera el `Inflater`.
 */
internal class MkvContentDecoder {

    private var inflater: Inflater? = null
    private var inflateChunk: ByteArray? = null

    /** Lo que aún puede producir el bloque en curso. */
    private class Budget(var remaining: Long)

    /**
     * Deshace [encodings] sobre cada fotograma de un bloque. Un fotograma que no se puede deshacer
     * —corrupto, o que no cabe en lo que le queda al bloque— queda como `null` y los demás siguen.
     *
     * Se captura `Exception` y no cualquier `Throwable`: un fotograma que no se puede descomprimir
     * se descarta, pero un `OutOfMemoryError` no es un bloque dañado sino el proceso sin memoria.
     * Tragárselo descartaba fotogramas en silencio mientras todo lo demás empezaba a fallar; así
     * llega a quien lee, que es quien puede decidir qué hacer.
     */
    fun decodeFrames(frames: List<ByteArray>, encodings: List<ContentEncoding>): DecodedFrames {
        val budget = Budget(MAX_DECODED_BLOCK_BYTES)
        var discarded = 0
        var cause: String? = null
        val decoded = frames.map { frame ->
            try {
                decode(frame, encodings, ContentEncoding.SCOPE_FRAMES, budget)
            } catch (e: Exception) {
                if (discarded++ == 0) cause = e.message
                null
            }
        }
        return DecodedFrames(decoded, discarded, cause)
    }

    /** Deshace [encodings] sobre un `CodecPrivate`, con el mismo techo que un fotograma. */
    fun decodePrivate(data: ByteArray, encodings: List<ContentEncoding>): ByteArray =
        decode(data, encodings, ContentEncoding.SCOPE_PRIVATE, Budget(MAX_INFLATED_BYTES.toLong()))

    /** Deshace sobre [data] las codificaciones que se aplican a [scope], en el orden de [encodings]. */
    private fun decode(data: ByteArray, encodings: List<ContentEncoding>, scope: Long, budget: Budget): ByteArray {
        var current = data
        for (encoding in encodings) {
            if (encoding.scope and scope == 0L) continue
            current = when (encoding.algorithm) {
                ContentEncoding.ALGO_HEADER_STRIPPING -> encoding.settings?.let { prefix ->
                    take(budget, prefix.size.toLong() + current.size)
                    prefix + current
                } ?: current
                else -> inflate(current, budget)
            }
        }
        return current
    }

    private fun take(budget: Budget, bytes: Long) {
        if (bytes > budget.remaining) throw EbmlException(BLOCK_CEILING_EXCEEDED)
        budget.remaining -= bytes
    }

    /**
     * Descompresión zlib con un techo de tamaño: el menor entre [MAX_INFLATED_BYTES] y lo que le
     * queda al bloque. Un bloque comprimido de unos pocos KB puede declarar cientos de MB al
     * descomprimirse; sin el techo, un archivo manipulado agotaba la memoria del proceso con un
     * solo fotograma.
     *
     * El techo se comprueba **antes** de copiar cada trozo, y el buffer crece por duplicación pero
     * nunca por encima del techo. Con un `ByteArrayOutputStream` y la comprobación después de
     * escribir, un fotograma de 64 MiB y un byte duplicaba el buffer a 128 MiB para descubrir que no
     * cabía, y uno legítimo de 64 MiB se copiaba una vez más al final; ahora, si el buffer acaba
     * justo lleno, se devuelve tal cual.
     */
    private fun inflate(data: ByteArray, budget: Budget): ByteArray {
        val limit = minOf(MAX_INFLATED_BYTES.toLong(), budget.remaining).toInt()
        val inflater = inflater ?: Inflater().also { inflater = it }
        val chunk = inflateChunk ?: ByteArray(INFLATE_CHUNK_BYTES).also { inflateChunk = it }
        inflater.reset()
        inflater.setInput(data)
        var out = ByteArray(minOf(data.size * 2L, INFLATE_CHUNK_BYTES.toLong(), limit.toLong()).toInt())
        var size = 0
        while (!inflater.finished()) {
            val n = try {
                inflater.inflate(chunk)
            } catch (e: DataFormatException) {
                throw EbmlException("bloque zlib corrupto: ${e.message}")
            }
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                throw EbmlException("bloque zlib incompleto")
            }
            if (n > limit - size) {
                throw EbmlException(if (limit == MAX_INFLATED_BYTES) FRAME_CEILING_EXCEEDED else BLOCK_CEILING_EXCEEDED)
            }
            if (size + n > out.size) out = out.copyOf(minOf(maxOf(out.size * 2L, size.toLong() + n), limit.toLong()).toInt())
            System.arraycopy(chunk, 0, out, size, n)
            size += n
        }
        budget.remaining -= size
        return if (size == out.size) out else out.copyOf(size)
    }

    /** Libera la memoria nativa del `Inflater`, si se llegó a crear. */
    fun close() {
        inflater?.end()
        inflater = null
    }

    private companion object {
        private const val INFLATE_CHUNK_BYTES = 1 shl 16

        /** Techo de un fotograma descomprimido: muy por encima de cualquier fotograma real. */
        private const val MAX_INFLATED_BYTES = 64 * 1024 * 1024

        /**
         * Techo de todo lo que produce un bloque. Es el mismo que el de un fotograma: un bloque con
         * lacing lleva fotogramas de audio, diminutos, y uno sin lacing es un solo fotograma, así
         * que ningún archivo real se acerca y el pico de memoria de un bloque no pasa del de un
         * fotograma máximo.
         */
        private const val MAX_DECODED_BLOCK_BYTES = MAX_INFLATED_BYTES.toLong()

        private const val FRAME_CEILING_EXCEEDED = "bloque zlib que descomprime más de $MAX_INFLATED_BYTES bytes"
        private const val BLOCK_CEILING_EXCEEDED = "el bloque descomprime en total más de $MAX_DECODED_BLOCK_BYTES bytes"
    }
}
