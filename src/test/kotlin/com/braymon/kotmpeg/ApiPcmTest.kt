package com.braymon.kotmpeg

import com.braymon.kotmpeg.audio.PcmMixer
import com.braymon.kotmpeg.audio.PcmResampler
import com.braymon.kotmpeg.audio.PcmStreamMixer
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Las utilidades de PCM ante entradas al límite: trozos que no traen ningún frame, frames
 * incompletos y ganancias que no son números. Con entradas válidas la salida no cambia ni en un bit
 * —eso lo vigilan `PcmFastPathEquivalenceTest` y `RecordingAudioLayoutsTest`—.
 */
class ApiPcmTest {

    /** Una fuente de [ms] milisegundos a 48 kHz estéreo con todas las muestras a [value]. */
    private fun stereo48k(ms: Int, value: Short): ShortArray = ShortArray(48 * ms * 2) { value }

    /**
     * Lo que entrega un mezclador al que la fuente A le llega 100 ms tarde, cuando la B ya lleva
     * 600 ms y la mezcla ha empezado sin ella. [early] es lo que A escribe antes de que la mezcla
     * empiece, y [late] su primer audio de verdad.
     */
    private fun lateSource(early: ShortArray?, late: ShortArray, rate: Int, channels: Int): Pair<ShortArray, Long> {
        val mixer = PcmStreamMixer(outputRate = 48_000, outputChannels = 2)
        val a = mixer.addSource(sampleRate = rate, channels = channels)
        val b = mixer.addSource(sampleRate = 48_000, channels = 2)
        if (early != null) mixer.write(a, early)
        mixer.write(b, stereo48k(600, 1_000))
        val first = mixer.read()
        mixer.write(a, late)
        return (first + mixer.read() + mixer.flush()) to mixer.lostFrames(a)
    }

    /**
     * Un primer `write` vacío no dice dónde empieza la fuente. Hasta la 4.0 la daba por empezada en
     * el instante 0, el tiempo que la mezcla avanzaba sin ella se le cobraba como deuda, y su primer
     * audio de verdad se descartaba entero en lugar de colocarse donde llegaba.
     */
    @Test
    fun `an empty first write does not start a source`() {
        val late = stereo48k(100, 2_000)
        val (expected, expectedLost) = lateSource(early = null, late = late, rate = 48_000, channels = 2)
        val (actual, actualLost) = lateSource(early = ShortArray(0), late = late, rate = 48_000, channels = 2)
        assertEquals(expectedLost, actualLost, "frames perdidos de la fuente tardía")
        assertContentEquals(expected, actual, "un write vacío cambió la mezcla")
        assertEquals(3_000, expected[expected.size - 1].toInt(), "el audio tardío no llegó a la mezcla")
    }

    /**
     * Un frame suelto a 44,1 kHz no da todavía ningún frame a 48 kHz: el remuestreador se lo queda
     * para interpolar con lo siguiente. Tampoco empieza la fuente, y como el remuestreo no depende de
     * cómo se trocee la entrada, escribirlo antes o junto con el resto da la misma mezcla.
     */
    @Test
    fun `a write the resampler turns into nothing does not start a source either`() {
        val late = ShortArray(4_410) { 2_000 }
        val single = shortArrayOf(2_000)
        val (expected, expectedLost) = lateSource(early = null, late = single + late, rate = 44_100, channels = 1)
        val (actual, actualLost) = lateSource(early = single, late = late, rate = 44_100, channels = 1)
        assertEquals(expectedLost, actualLost, "frames perdidos de la fuente tardía")
        assertContentEquals(expected, actual, "un frame suelto cambió la mezcla")
    }

    /** Con frecuencias iguales un frame incompleto también se rechaza, como con frecuencias distintas. */
    @Test
    fun `the resampler rejects an incomplete frame even when it has nothing to convert`() {
        for (rates in listOf(48_000 to 48_000, 44_100 to 48_000)) {
            val resampler = PcmResampler(rates.first, rates.second, channels = 2)
            assertFailsWith<IllegalArgumentException>("$rates") { resampler.resample(ShortArray(3)) }
        }
        val passthrough = PcmResampler(48_000, 48_000, channels = 2)
        val whole = ShortArray(4) { it.toShort() }
        assertEquals(true, passthrough.resample(whole) === whole, "con frames completos sigue devolviendo el mismo array")
    }

    /**
     * Un `NaN` en las ganancias silenciaba la mezcla entera y un infinito la saturaba, sin ningún
     * error. Una ganancia negativa sí es válida: invierte la fase de su fuente.
     */
    @Test
    fun `mix rejects gains that are not finite numbers`() {
        val a = shortArrayOf(1_000, -1_000)
        val b = shortArrayOf(500, 500)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("$bad") { PcmMixer.mix(listOf(a, b), listOf(1f, bad)) }
        }
        assertContentEquals(shortArrayOf(500, -1_500), PcmMixer.mix(listOf(a, b), listOf(1f, -1f)))
    }
}
