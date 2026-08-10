package com.braymon.kotmpeg.audio

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Remuestreador en streaming por interpolación lineal para PCM de 16 bits intercalado
 * (el equivalente práctico del `aresample` de FFmpeg para pipelines de captura y
 * transcodificación; la calidad lineal sobra para voz y contenido de pantalla).
 *
 * Acepta trozos de cualquier tamaño; la fase se conserva entre llamadas, así que los
 * streams largos no derivan: tras N frames de entrada la salida converge exactamente a
 * N * outputRate / inputRate.
 */
public class PcmResampler(
    public val inputRate: Int,
    public val outputRate: Int,
    public val channels: Int,
) {
    init {
        require(inputRate > 0 && outputRate > 0) { "frecuencias inválidas $inputRate -> $outputRate" }
        require(channels in 1..8) { "número de canales inválido: $channels" }
    }

    /** Posición fraccional de lectura dentro del stream de entrada, en frames. */
    private var position = 0.0
    private val step = inputRate.toDouble() / outputRate

    /** Último frame del trozo anterior, para interpolar entre fronteras de trozos. */
    private var lastFrame = ShortArray(channels)
    private var framesConsumed = 0L
    private var framesEmitted = 0L

    public val isPassthrough: Boolean get() = inputRate == outputRate

    /**
     * Remuestrea [input] (intercalado, frames completos) y devuelve los frames producidos.
     *
     * **Con frecuencias iguales devuelve el mismo array que se le pasó**, no una copia: es lo
     * eficiente para el caso en que no hay nada que convertir, pero significa que mutar el
     * resultado muta la entrada. Copia tú si necesitas que sean independientes.
     *
     * El buffer de salida se reserva de una vez y con **un frame de más a propósito**. Las dos
     * cosas son deliberadas y ninguna es un descuido que optimizar:
     *
     *  - `ShortArray` y no una lista: `ArrayList<Short>` boxea *cada* muestra —la caché de
     *    `Short.valueOf` solo cubre −128..127, así que en audio real casi ninguna se
     *    reaprovecha—, lo que son millones de objetos por minuto en el hilo de captura, donde
     *    un GC a destiempo se oye. El número de frames de salida se conoce de antemano.
     *  - El `+ 1`: `ceil` sobre una división en coma flotante puede quedarse corto por un ulp, y
     *    quedarse corto aquí no solo truncaría la salida, también dejaría `position` sin avanzar
     *    y rompería la convergencia exacta que promete la clase. Sobrar un frame se recorta al
     *    final; faltar sería un fallo silencioso.
     */
    public fun resample(input: ShortArray): ShortArray {
        if (isPassthrough) return input
        require(input.size % channels == 0) {
            "el buffer de entrada (${input.size}) no es múltiplo de $channels canales: " +
                "hay un frame incompleto que se perdería en silencio"
        }
        val inFrames = input.size / channels
        if (inFrames == 0) return ShortArray(0)

        val available = framesConsumed + inFrames

        val span = (available - 1) - position
        val outFrames = if (span <= 0) 0 else ceil(span / step).toInt() + 1
        val out = ShortArray(outFrames * channels)
        var w = 0

        while (position < available - 1) {
            val base = position - (framesConsumed - 1)
            val index = floor(base).toInt()
            val frac = base - index
            for (ch in 0 until channels) {
                val s0 = sampleAt(input, index - 1, ch)
                val s1 = sampleAt(input, index, ch)
                val v = s0 + (s1 - s0) * frac
                out[w++] = Math.round(v).toInt().coerceIn(-32768, 32767).toShort()
            }
            position += step
            framesEmitted++
        }

        for (ch in 0 until channels) lastFrame[ch] = input[(inFrames - 1) * channels + ch]
        framesConsumed = available
        return if (w == out.size) out else out.copyOf(w)
    }

    /** El índice es relativo al frame histórico: -1 = último frame del trozo anterior. */
    private fun sampleAt(input: ShortArray, index: Int, ch: Int): Double =
        if (index < 0) lastFrame[ch].toDouble() else input[index * channels + ch].toDouble()

    /**
     * Emite el frame final del stream.
     *
     * **No reinicia el remuestreador**: la fase, el frame retenido y los contadores siguen
     * donde estaban, así que la instancia sirve para terminar *este* stream y no para empezar
     * otro. Para un stream nuevo, crea otro [PcmResampler] — es barato y evita arrastrar la
     * fase del anterior.
     *
     * [resample] solo puede interpolar hasta el penúltimo frame recibido: el último se
     * guarda para poder interpolar con el trozo siguiente. Al terminar de verdad no hay
     * trozo siguiente, así que sin esta llamada ese frame se perdía — un desajuste de
     * hasta un frame frente a la "convergencia exacta" que promete la clase.
     */
    public fun flush(): ShortArray {
        if (isPassthrough || framesConsumed == 0L) return ShortArray(0)
        val target = Math.round(framesConsumed.toDouble() * outputRate / inputRate)
        val missing = (target - framesEmitted).coerceAtLeast(0)
        if (missing == 0L) return ShortArray(0)
        val out = ShortArray((missing * channels).toInt())
        var i = 0
        repeat(missing.toInt()) {
            for (ch in 0 until channels) out[i++] = lastFrame[ch]
            position += step
            framesEmitted++
        }
        return out
    }
}
