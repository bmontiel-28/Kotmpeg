package com.braymon.kotmpeg

import com.braymon.kotmpeg.audio.PcmMixer
import com.braymon.kotmpeg.audio.PcmResampler
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `PcmMixer` y `PcmResampler` se reescribieron por dentro en la 3.0 sin tocar su API: son API
 * pública, y quien los use para mezclar micrófono y audio del sistema tiene que obtener lo mismo
 * que antes. Este test fija qué se promete, comparando contra una **copia literal de la
 * implementación 2.1.1**:
 *
 *  - **El mezclador da exactamente los mismos bytes**, con y sin ganancias, con fuentes de
 *    longitudes distintas y con valores extremos. La ruta entera nueva tiene que coincidir bit a
 *    bit con la suma en coma flotante de antes.
 *  - **El remuestreador da la misma señal** salvo el redondeo del último bit, y el mismo número de
 *    frames, durante segundos de audio; donde se separa es en sesiones largas, porque el antiguo
 *    acumulaba la fase en `double` y se desviaba frames enteros en horas. El nuevo no se desvía
 *    ni uno.
 */
class PcmFastPathEquivalenceTest {

    /** `PcmMixer.mix` de la 2.1.1, tal cual. */
    private fun referenceMix(sources: List<ShortArray>, gains: List<Float>? = null): ShortArray {
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

    /** `PcmMixer.stereoToMono` de la 2.1.1. */
    private fun referenceStereoToMono(input: ShortArray): ShortArray = ShortArray(input.size / 2) { i ->
        Math.round((input[2 * i] + input[2 * i + 1]) / 2.0).toInt().toShort()
    }

    /** `PcmResampler` de la 2.1.1, con la fase acumulada en `double`. */
    private class ReferenceResampler(private val inputRate: Int, private val outputRate: Int, private val channels: Int) {
        private var position = 0.0
        private val step = inputRate.toDouble() / outputRate
        private val lastFrame = ShortArray(channels)
        private var framesConsumed = 0L

        fun resample(input: ShortArray): ShortArray {
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
                    val s0 = if (index - 1 < 0) lastFrame[ch].toDouble() else input[(index - 1) * channels + ch].toDouble()
                    val s1 = if (index < 0) lastFrame[ch].toDouble() else input[index * channels + ch].toDouble()
                    out[w++] = Math.round(s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
                }
                position += step
            }
            for (ch in 0 until channels) lastFrame[ch] = input[(inFrames - 1) * channels + ch]
            framesConsumed = available
            return if (w == out.size) out else out.copyOf(w)
        }
    }

    private fun randomPcm(random: Random, size: Int): ShortArray = ShortArray(size) {
        when (random.nextInt(20)) {
            0 -> Short.MAX_VALUE
            1 -> Short.MIN_VALUE
            else -> random.nextInt(-32768, 32768).toShort()
        }
    }

    @Test
    fun `the mixer produces the same bytes as version 2_1_1`() {
        val random = Random(42)
        repeat(400) { round ->
            val count = random.nextInt(1, 5)
            val sources = List(count) { randomPcm(random, random.nextInt(0, 700)) }.toMutableList()
            if (sources.all { it.isEmpty() }) sources[0] = randomPcm(random, 3)
            val gains = when (round % 4) {
                0 -> null
                1 -> List(count) { 1f }
                2 -> List(count) { listOf(0.5f, 0.75f, 1.3f, -1f, 0f, 2.5f)[random.nextInt(6)] }
                else -> List(count) { random.nextFloat() * 4f - 2f }
            }
            assertContentEquals(referenceMix(sources, gains), PcmMixer.mix(sources, gains), "ronda $round")
        }
        repeat(200) { round ->
            val length = random.nextInt(1, 2_000)
            val microphoneAndSystem = listOf(randomPcm(random, length), randomPcm(random, length))
            val gains = if (round % 2 == 0) null else listOf(random.nextFloat() * 3f - 1f, random.nextFloat() * 3f - 1f)
            assertContentEquals(
                referenceMix(microphoneAndSystem, gains), PcmMixer.mix(microphoneAndSystem, gains),
                "dos fuentes de igual longitud, ronda $round",
            )
        }
    }

    @Test
    fun `stereo to mono rounds exactly like version 2_1_1`() {
        val random = Random(7)
        val extremes = shortArrayOf(Short.MIN_VALUE, Short.MIN_VALUE, Short.MAX_VALUE, Short.MAX_VALUE, -1, 0, 1, 0, -3, 0, 3, 0, -1, -2)
        assertContentEquals(referenceStereoToMono(extremes), PcmMixer.stereoToMono(extremes))
        val noise = randomPcm(random, 200_000)
        assertContentEquals(referenceStereoToMono(noise), PcmMixer.stereoToMono(noise))
    }

    /**
     * Se compara el **flujo concatenado**, que es lo que acaba en el codificador, y no trozo a trozo:
     * cuando la posición exacta cae justo en la frontera de un trozo, el remuestreador antiguo podía
     * entregar ese frame en una llamada por quedarse una millonésima corto, y el nuevo lo entrega
     * en la siguiente. El valor es el mismo; solo cambia en qué llamada sale.
     */
    @Test
    fun `over a few seconds the resampler matches version 2_1_1 within one bit`() {
        for ((inRate, outRate, channels) in listOf(Triple(44_100, 48_000, 2), Triple(48_000, 44_100, 1), Triple(16_000, 48_000, 1))) {
            val reference = ReferenceResampler(inRate, outRate, channels)
            val optimized = PcmResampler(inRate, outRate, channels)
            val expected = ArrayList<Short>()
            val actual = ArrayList<Short>()
            var phase = 0.0
            repeat(inRate * 5 / 480) {
                val chunk = ShortArray(480 * channels) { i ->
                    phase += 0.03
                    (Math.sin(phase + i % channels) * 20_000).toInt().toShort()
                }
                reference.resample(chunk).forEach { expected += it }
                optimized.resample(chunk).forEach { actual += it }
            }
            assertTrue(abs(expected.size - actual.size) <= channels, "$inRate->$outRate: ${expected.size} frente a ${actual.size} muestras")
            for (k in 0 until minOf(expected.size, actual.size)) {
                assertTrue(abs(expected[k] - actual[k]) <= 1, "$inRate->$outRate muestra $k: ${expected[k]} frente a ${actual[k]}")
            }
        }
    }

    /**
     * Quince minutos de 44,1 a 48 kHz en trozos de 960 frames. Tras cada trozo, los frames emitidos
     * tienen que ser exactamente los que caben antes del último frame recibido: ni uno más, ni uno
     * menos. La 2.1.1 ya se salía a los 6 minutos y medio con este patrón, y llegaba a 5 frames de
     * desviación en tres horas.
     */
    @Test
    fun `a long session does not drift by a single frame`() {
        val inRate = 44_100L
        val outRate = 48_000L
        val resampler = PcmResampler(inRate.toInt(), outRate.toInt(), channels = 1)
        val chunk = ShortArray(960)
        var consumed = 0L
        var produced = 0L
        repeat((inRate * 15 * 60 / chunk.size).toInt()) {
            produced += resampler.resample(chunk).size
            consumed += chunk.size
            val exact = Math.floorDiv((consumed - 1) * outRate + inRate - 1, inRate)
            if (produced != exact) throw AssertionError("deriva tras $consumed frames: $produced frente a $exact")
        }
        produced += resampler.flush().size
        assertEquals(Math.round(consumed.toDouble() * outRate / inRate), produced)
    }
}
