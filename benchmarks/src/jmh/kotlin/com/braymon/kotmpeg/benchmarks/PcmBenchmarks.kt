package com.braymon.kotmpeg.benchmarks

import com.braymon.kotmpeg.audio.PcmMixer
import com.braymon.kotmpeg.audio.PcmResampler
import com.braymon.kotmpeg.audio.PcmStreamMixer
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * El audio de grabación en bloques de 10 ms, que es como llega desde `AudioRecord`: el §4 de
 * `ANALISIS.md` da las cifras de una hora, que son 360 000 de estas operaciones.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class PcmBenchmark {

    private lateinit var mic: ShortArray
    private lateinit var system: ShortArray
    private lateinit var mic441: ShortArray
    private lateinit var resampler: PcmResampler
    private lateinit var streamMixer: PcmStreamMixer
    private var micSource = 0
    private var systemSource = 0
    private val gains = listOf(0.8f, 1.2f)

    @Setup
    fun prepare() {
        val random = Random(9)
        mic = ShortArray(960) { random.nextInt(-8000, 8000).toShort() }
        system = ShortArray(960) { random.nextInt(-8000, 8000).toShort() }
        mic441 = ShortArray(441) { random.nextInt(-8000, 8000).toShort() }
        resampler = PcmResampler(inputRate = 44100, outputRate = 48000, channels = 1)
        streamMixer = PcmStreamMixer(outputRate = 48000, outputChannels = 2)
        micSource = streamMixer.addSource(sampleRate = 44100, channels = 1)
        systemSource = streamMixer.addSource(sampleRate = 48000, channels = 2)
    }

    /** Micrófono y sistema, estéreo a 48 kHz, sin ganancias: el camino rápido. */
    @Benchmark
    fun mixWithoutGains(): ShortArray = PcmMixer.mix(listOf(mic, system))

    @Benchmark
    fun mixWithGains(): ShortArray = PcmMixer.mix(listOf(mic, system), gains)

    /** 10 ms de micrófono a 44,1 kHz llevados a 48 kHz. */
    @Benchmark
    fun resample441To48(): ShortArray = resampler.resample(mic441)

    /**
     * Un bloque de cada fuente en `PcmStreamMixer` y la lectura de lo que ya está alineado:
     * conversión de frecuencia y canales del micrófono, mezcla y espera incluidas.
     */
    @Benchmark
    fun streamMixer(): ShortArray {
        streamMixer.write(micSource, mic441)
        streamMixer.write(systemSource, system)
        return streamMixer.read()
    }
}
