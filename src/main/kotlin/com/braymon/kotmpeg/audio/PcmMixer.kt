package com.braymon.kotmpeg.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mezcla y adaptación de canales para PCM de 16 bits (la esquina `amix`/`pan` de FFmpeg):
 * combinar micrófono + audio del sistema en una pista, o adaptar mono<->estéreo entre un
 * decodificador y un codificador. Toda la aritmética es con saturación.
 */
public object PcmMixer {

    /**
     * Mezcla [sources] (mismo layout intercalado) en un solo buffer, con ganancia opcional
     * por fuente en [gains] (1.0 = sin cambio). Las fuentes más cortas se tratan como
     * silencio a partir de su final, así la alineación por bloques es tolerante.
     */
    public fun mix(sources: List<ShortArray>, gains: List<Float>? = null): ShortArray {
        require(sources.isNotEmpty()) { "sin fuentes" }
        if (gains != null) require(gains.size == sources.size) { "gains y sources no coinciden en tamaño" }
        val length = sources.maxOf { it.size }
        val out = ShortArray(length)
        for (i in 0 until length) {
            var acc = 0.0
            for ((s, source) in sources.withIndex()) {
                if (i < source.size) acc += source[i] * (gains?.get(s) ?: 1f)
            }
            out[i] = Math.round(acc).coerceIn(-32768L, 32767L).toShort()
        }
        return out
    }

    /** Estéreo -> mono promediando cada par de canales. */
    public fun stereoToMono(input: ShortArray): ShortArray {
        require(input.size % 2 == 0) { "buffer estéreo con un frame incompleto: ${input.size} muestras" }
        return ShortArray(input.size / 2) { i ->
            Math.round((input[2 * i] + input[2 * i + 1]) / 2.0).toInt().toShort()
        }
    }

    /** Mono -> estéreo duplicando cada muestra. */
    public fun monoToStereo(input: ShortArray): ShortArray {
        val out = ShortArray(input.size * 2)
        for (i in input.indices) {
            out[2 * i] = input[i]
            out[2 * i + 1] = input[i]
        }
        return out
    }

    /** Adaptación genérica de canales (1<->2 soportado; cuentas iguales pasan tal cual). */
    public fun convertChannels(input: ShortArray, from: Int, to: Int): ShortArray = when {
        from == to -> input
        from == 2 && to == 1 -> stereoToMono(input)
        from == 1 && to == 2 -> monoToStereo(input)
        else -> throw IllegalArgumentException("conversión de canales no soportada: $from -> $to")
    }

    /** Copia un ByteBuffer PCM (little-endian) a un ShortArray sin consumirlo. */
    public fun toShortArray(buffer: ByteBuffer): ShortArray {
        val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(duplicate.remaining() / 2)
        duplicate.asShortBuffer().get(out)
        return out
    }

    /** Envuelve un ShortArray como ByteBuffer PCM little-endian. */
    public fun toByteBuffer(samples: ShortArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(samples)
        return buffer
    }
}
