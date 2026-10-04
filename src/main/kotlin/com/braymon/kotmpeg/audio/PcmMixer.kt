package com.braymon.kotmpeg.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mezcla y adaptación de canales para PCM de 16 bits (la esquina `amix`/`pan` de FFmpeg):
 * combinar micrófono + audio del sistema en una pista, o adaptar mono<->estéreo entre un
 * decodificador y un codificador. Toda la aritmética es con saturación.
 *
 * Es la mitad de la regla de audio de la librería: el audio del sistema y el del micrófono se
 * pueden grabar **mezclados en una pista** (con este objeto) o **cada uno en la suya** (una
 * pista por fuente en el muxer), y las dos formas a la vez. `RecordingAudioLayoutsTest` lo hace
 * cumplir.
 */
public object PcmMixer {

    /**
     * Mezcla [sources] (mismo layout intercalado) en un solo buffer, con ganancia opcional
     * por fuente en [gains] (1.0 = sin cambio). Las fuentes más cortas se tratan como
     * silencio a partir de su final, así la alineación por bloques es tolerante.
     *
     * Sin ganancias, o con todas a 1.0 —el caso de mezclar micrófono y sistema tal cual—, la suma
     * va en enteros: da exactamente el mismo resultado que la suma en coma flotante, sin convertir
     * cada muestra a `double` ni desempaquetar un `Float` por muestra y fuente, que es lo que
     * pesaba en el hilo de captura. Con ganancias distintas se conserva la aritmética de siempre,
     * producto en `float` y acumulación en `double`, para que la salida no cambie ni en un bit.
     */
    public fun mix(sources: List<ShortArray>, gains: List<Float>? = null): ShortArray {
        require(sources.isNotEmpty()) { "sin fuentes" }
        if (gains != null) require(gains.size == sources.size) { "gains y sources no coinciden en tamaño" }
        val inputs = sources.toTypedArray()
        var length = 0
        for (input in inputs) length = maxOf(length, input.size)
        val out = ShortArray(length)
        if (gains == null || gains.all { it == 1f }) {
            mixUnity(inputs, out)
        } else {
            mixWithGains(inputs, FloatArray(gains.size) { gains[it] }, out)
        }
        return out
    }

    private fun mixUnity(inputs: Array<ShortArray>, out: ShortArray) {
        if (inputs.size == 2 && inputs[0].size == out.size && inputs[1].size == out.size) {
            val first = inputs[0]
            val second = inputs[1]
            for (i in out.indices) out[i] = saturate(first[i].toLong() + second[i])
            return
        }
        for (i in out.indices) {
            var acc = 0L
            for (input in inputs) if (i < input.size) acc += input[i]
            out[i] = saturate(acc)
        }
    }

    private fun mixWithGains(inputs: Array<ShortArray>, gains: FloatArray, out: ShortArray) {
        if (inputs.size == 2 && inputs[0].size == out.size && inputs[1].size == out.size) {
            val first = inputs[0]
            val second = inputs[1]
            val firstGain = gains[0]
            val secondGain = gains[1]
            for (i in out.indices) {
                val acc = (first[i] * firstGain).toDouble() + (second[i] * secondGain).toDouble()
                out[i] = saturate(Math.round(acc))
            }
            return
        }
        for (i in out.indices) {
            var acc = 0.0
            for (s in inputs.indices) {
                val input = inputs[s]
                if (i < input.size) acc += input[i] * gains[s]
            }
            out[i] = saturate(Math.round(acc))
        }
    }

    private fun saturate(value: Long): Short = value.coerceIn(-32768L, 32767L).toShort()

    /**
     * Estéreo -> mono promediando cada par de canales y redondeando la mitad hacia arriba.
     *
     * `(a + b + 1) shr 1` es exactamente `Math.round((a + b) / 2.0)` —el desplazamiento aritmético
     * es un suelo— sin pasar por coma flotante.
     */
    public fun stereoToMono(input: ShortArray): ShortArray {
        require(input.size % 2 == 0) { "buffer estéreo con un frame incompleto: ${input.size} muestras" }
        return ShortArray(input.size / 2) { i -> ((input[2 * i] + input[2 * i + 1] + 1) shr 1).toShort() }
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
