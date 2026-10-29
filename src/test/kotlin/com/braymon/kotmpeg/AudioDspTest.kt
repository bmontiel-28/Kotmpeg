package com.braymon.kotmpeg

import com.braymon.kotmpeg.audio.PcmMixer
import com.braymon.kotmpeg.audio.PcmResampler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioDspTest {

    @Test
    fun `resampler converges to expected length and preserves a sine`() {
        val inRate = 44100
        val outRate = 48000
        val resampler = PcmResampler(inRate, outRate, channels = 1)
        val freq = 440.0
        var produced = 0
        var maxError = 0.0
        var inputFrames = 0
        var t = 0
        while (t < inRate) {
            val chunk = minOf(997, inRate - t)
            val input = ShortArray(chunk) { i ->
                (Math.sin(2 * Math.PI * freq * (t + i) / inRate) * 16000).toInt().toShort()
            }
            val out = resampler.resample(input)
            for ((k, sample) in out.withIndex()) {
                val outIndex = produced + k
                val expected = Math.sin(2 * Math.PI * freq * outIndex / outRate) * 16000
                maxError = maxOf(maxError, Math.abs(sample - expected))
            }
            produced += out.size
            t += chunk
            inputFrames += chunk
        }
        val expectedFrames = inputFrames.toLong() * outRate / inRate
        assertTrue(Math.abs(produced - expectedFrames) <= 2, "length $produced vs $expectedFrames")
        assertTrue(maxError < 320, "max error $maxError")
    }

    @Test
    fun `passthrough resampler returns input`() {
        val resampler = PcmResampler(48000, 48000, 2)
        val input = ShortArray(96) { it.toShort() }
        assertTrue(resampler.resample(input) === input)
    }

    @Test
    fun `mixer saturates and applies gains`() {
        val a = shortArrayOf(30000, -30000, 100)
        val b = shortArrayOf(10000, -10000, 50)
        val mixed = PcmMixer.mix(listOf(a, b))
        assertEquals(32767, mixed[0].toInt())
        assertEquals(-32768, mixed[1].toInt())
        assertEquals(150, mixed[2].toInt())
        val gained = PcmMixer.mix(listOf(a, b), gains = listOf(0.5f, 0.5f))
        assertEquals(20000, gained[0].toInt())
        val uneven = PcmMixer.mix(listOf(shortArrayOf(10), shortArrayOf(1, 2, 3)))
        assertEquals(11, uneven[0].toInt())
        assertEquals(2, uneven[1].toInt())
    }

    @Test
    fun `channel conversion`() {
        val stereo = shortArrayOf(100, 200, -50, 50)
        assertTrue(PcmMixer.stereoToMono(stereo).contentEquals(shortArrayOf(150, 0)))
        assertTrue(PcmMixer.monoToStereo(shortArrayOf(7)).contentEquals(shortArrayOf(7, 7)))
        val same = shortArrayOf(1, 2)
        assertTrue(PcmMixer.convertChannels(same, 2, 2) === same)
    }
}
