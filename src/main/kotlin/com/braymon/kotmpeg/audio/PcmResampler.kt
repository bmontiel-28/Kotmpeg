package com.braymon.kotmpeg.audio

/**
 * Remuestreador en streaming por interpolación lineal para PCM de 16 bits intercalado
 * (el equivalente práctico del `aresample` de FFmpeg para pipelines de captura y
 * transcodificación; la calidad lineal sobra para voz y contenido de pantalla).
 *
 * Acepta trozos de cualquier tamaño; la fase se conserva entre llamadas, así que los
 * streams largos no derivan: tras N frames de entrada la salida converge exactamente a
 * N * outputRate / inputRate.
 *
 * Es la pieza que permite mezclar micrófono y audio del sistema cuando no llegan a la misma
 * frecuencia (44,1 kHz frente a 48 kHz es lo habitual en Android): se lleva una de las dos a la
 * tasa de la otra y después [PcmMixer] las suma.
 *
 * **La fase se lleva en enteros, no en coma flotante.** La posición de lectura es la fracción
 * exacta `frames emitidos * inputRate / outputRate`, guardada como parte entera más resto. Antes
 * se acumulaba sumando un `double` por cada frame de salida, y el error de redondeo crecía con la
 * sesión: en tres horas de 44,1 a 48 kHz la salida iba hasta 5 frames por delante o por detrás de
 * lo que promete esta clase. Ahora la cuenta es exacta durante cualquier duración.
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

    private val divisor = gcd(inputRate.toLong(), outputRate.toLong())

    /** Avance por frame de salida, en frames de entrada: `inputStep / outputStep`, ya reducido. */
    private val inputStep = inputRate / divisor
    private val outputStep = outputRate / divisor
    private val wholeStep = inputStep / outputStep
    private val partialStep = inputStep % outputStep

    /**
     * Posición de lectura: frame de entrada absoluto ([phaseFrame]) más la fracción
     * `phaseRemainder / outputStep` hacia el siguiente.
     */
    private var phaseFrame = 0L
    private var phaseRemainder = 0L

    /** Último frame del trozo anterior, para interpolar entre fronteras de trozos. */
    private val lastFrame = ShortArray(channels)
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
     * El número de frames de salida se calcula **exacto** antes de reservar, con la misma
     * aritmética entera que la fase, así que el array sale del tamaño justo: ni falta sitio ni
     * hay que recortarlo con una copia al final. `ShortArray` y no una lista a propósito: una
     * `ArrayList<Short>` boxearía cada muestra, millones de objetos por minuto en el hilo de
     * captura, donde un GC a destiempo se oye.
     */
    public fun resample(input: ShortArray): ShortArray {
        if (isPassthrough) return input
        require(input.size % channels == 0) {
            "el buffer de entrada (${input.size}) no es múltiplo de $channels canales: " +
                "hay un frame incompleto que se perdería en silencio"
        }
        val inFrames = input.size / channels
        if (inFrames == 0) return ShortArray(0)

        val lastIndex = framesConsumed + inFrames - 1
        val outFrames = framesBefore(lastIndex)
        val out = ShortArray(outFrames * channels)
        var w = 0
        val scale = 1.0 / outputStep
        repeat(outFrames) {
            val relative = (phaseFrame - framesConsumed).toInt()
            val frac = phaseRemainder * scale
            for (ch in 0 until channels) {
                val s0 = if (relative < 0) lastFrame[ch].toInt() else input[relative * channels + ch].toInt()
                val s1 = input[(relative + 1) * channels + ch].toInt()
                val v = s0 + (s1 - s0) * frac
                out[w++] = Math.round(v).toInt().coerceIn(-32768, 32767).toShort()
            }
            advance()
        }

        System.arraycopy(input, (inFrames - 1) * channels, lastFrame, 0, channels)
        framesConsumed += inFrames
        return out
    }

    /**
     * Cuántos frames de salida caben antes de que la posición alcance [lastIndex]: los `j` con
     * `fase + j * paso < lastIndex`, contados en enteros como `ceil(distancia / paso)`.
     */
    private fun framesBefore(lastIndex: Long): Int {
        val distance = (lastIndex - phaseFrame) * outputStep - phaseRemainder
        if (distance <= 0) return 0
        val frames = (distance + inputStep - 1) / inputStep
        require(frames * channels <= Int.MAX_VALUE) { "trozo de entrada demasiado grande: $frames frames de salida" }
        return frames.toInt()
    }

    private fun advance() {
        phaseFrame += wholeStep
        phaseRemainder += partialStep
        if (phaseRemainder >= outputStep) {
            phaseRemainder -= outputStep
            phaseFrame++
        }
        framesEmitted++
    }

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
            advance()
        }
        return out
    }

    private companion object {
        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}
