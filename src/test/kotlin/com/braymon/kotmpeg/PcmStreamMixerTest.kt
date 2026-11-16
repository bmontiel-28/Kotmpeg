package com.braymon.kotmpeg

import com.braymon.kotmpeg.audio.PcmMixer
import com.braymon.kotmpeg.audio.PcmResampler
import com.braymon.kotmpeg.audio.PcmStreamMixer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **`PcmStreamMixer`: la mezcla de micrófono y sistema cuando llegan como llegan en un móvil.**
 *
 * La promesa central es que el resultado es **exactamente** el de convertir y mezclar a mano los
 * flujos enteros con `PcmResampler`, `PcmMixer.convertChannels` y `PcmMixer.mix`, sea cual sea el
 * tamaño de los trozos y el orden en que lleguen. Alrededor de esa promesa, lo que hace distinto a
 * un mezclador en vivo: qué pasa cuando una fuente se retrasa, cuando empieza tarde, cuando no
 * empieza nunca, y que se pueda usar desde varios hilos a la vez.
 */
class PcmStreamMixerTest {

    private fun noise(frames: Int, channels: Int, seed: Int): ShortArray {
        val random = Random(seed)
        return ShortArray(frames * channels) { random.nextInt(-32768, 32768).toShort() }
    }

    /** Un flujo partido en trozos de tamaño aleatorio entre [minFrames] y [maxFrames]. */
    private fun chunks(stream: ShortArray, channels: Int, minFrames: Int, maxFrames: Int, seed: Int): List<ShortArray> {
        val random = Random(seed)
        val out = ArrayList<ShortArray>()
        var at = 0
        while (at < stream.size) {
            val frames = random.nextInt(minFrames, maxFrames + 1)
            val end = minOf(stream.size, at + frames * channels)
            out += stream.copyOfRange(at, end)
            at = end
        }
        return out
    }

    /**
     * Escribe los trozos de cada fuente en orden de captura —como llegarían desde sus hilos— y lee
     * después de cada escritura. Devuelve la mezcla completa, incluido el [PcmStreamMixer.flush].
     */
    private fun stream(mixer: PcmStreamMixer, perSource: List<Pair<Int, List<ShortArray>>>, rates: List<Int>): ShortArray {
        class Event(val endUs: Double, val source: Int, val samples: ShortArray)
        val events = ArrayList<Event>()
        for ((index, entry) in perSource.withIndex()) {
            val (channels, list) = entry
            var frames = 0L
            for (chunk in list) {
                frames += chunk.size / channels
                events += Event(frames * 1_000_000.0 / rates[index], index, chunk)
            }
        }
        val out = ArrayList<ShortArray>()
        for (event in events.sortedBy { it.endUs }) {
            mixer.write(event.source, event.samples)
            out += mixer.read()
        }
        out += mixer.flush()
        return concat(out)
    }

    private fun concat(parts: List<ShortArray>): ShortArray {
        val result = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            System.arraycopy(part, 0, result, at, part.size)
            at += part.size
        }
        return result
    }

    private fun resampleWhole(input: ShortArray, from: Int, to: Int, channels: Int): ShortArray {
        val resampler = PcmResampler(from, to, channels)
        return resampler.resample(input) + resampler.flush()
    }

    @Test
    fun `mixing in chunks of any size gives exactly the same samples as mixing the whole streams`() {
        val a = noise(48_000, 2, seed = 1)
        val b = noise(47_000, 2, seed = 2)
        val mixer = PcmStreamMixer(outputRate = 48_000, outputChannels = 2)
        mixer.addSource(48_000, 2)
        mixer.addSource(48_000, 2)

        val mixed = stream(mixer, listOf(2 to chunks(a, 2, 1, 1_200, 3), 2 to chunks(b, 2, 300, 900, 4)), listOf(48_000, 48_000))

        assertContentEquals(PcmMixer.mix(listOf(a, b)), mixed)
        assertEquals(0L, mixer.lostFrames(0))
        assertEquals(0L, mixer.lostFrames(1))
    }

    /** El caso real de Android: micrófono mono a 44,1 kHz y sistema estéreo a 48 kHz. */
    @Test
    fun `microphone and system audio in different formats match converting them by hand`() {
        val mic = noise(44_100 * 2, 1, seed = 5)
        val system = noise(48_000 * 2, 2, seed = 6)
        val mixer = PcmStreamMixer(outputRate = 48_000, outputChannels = 2)
        val micId = mixer.addSource(44_100, 1)
        val systemId = mixer.addSource(48_000, 2)
        assertEquals(listOf(0, 1), listOf(micId, systemId))

        val mixed = stream(mixer, listOf(1 to chunks(mic, 1, 441, 441, 7), 2 to chunks(system, 2, 960, 960, 8)), listOf(44_100, 48_000))

        val micByHand = PcmMixer.convertChannels(resampleWhole(mic, 44_100, 48_000, 1), from = 1, to = 2)
        assertContentEquals(PcmMixer.mix(listOf(micByHand, system)), mixed)
    }

    @Test
    fun `gains are applied exactly like PcmMixer`() {
        val a = noise(9_600, 2, seed = 9)
        val b = noise(9_600, 2, seed = 10)
        val mixer = PcmStreamMixer(outputRate = 48_000, outputChannels = 2)
        mixer.addSource(48_000, 2, gain = 1f)
        mixer.addSource(48_000, 2, gain = 0.35f)

        val mixed = stream(mixer, listOf(2 to chunks(a, 2, 480, 480, 11), 2 to chunks(b, 2, 960, 960, 12)), listOf(48_000, 48_000))

        assertContentEquals(PcmMixer.mix(listOf(a, b), listOf(1f, 0.35f)), mixed)
    }

    /**
     * El hilo de la captura del sistema se para 0,8 s y después entrega de golpe lo que retenía,
     * como hace un `AudioRecord`. Pasada la espera de 0,5 s, el mezclador entrega el micrófono con el
     * sistema en silencio; cuando el atraso llega, descarta lo que ya se entregó. El resultado: un
     * hueco corto y el sistema **en su sitio** el resto de la grabación, no desplazado.
     */
    @Test
    fun `a source that stalls loses only the stalled time and stays in sync afterwards`() {
        val rate = 48_000
        val total = 2 * rate
        val system = ShortArray(total) { ((it % 20_000) + 1).toShort() }
        val mixer = PcmStreamMixer(outputRate = rate, outputChannels = 1, maxLatencyMs = 500)
        val micId = mixer.addSource(rate, 1)
        val systemId = mixer.addSource(rate, 1)
        val out = ArrayList<ShortArray>()
        val chunk = 480

        val stallStart = rate / 5
        mixer.write(systemId, system.copyOfRange(0, stallStart))
        for (f in 0 until rate step chunk) {
            mixer.write(micId, ShortArray(chunk))
            out += mixer.read()
        }
        mixer.write(systemId, system.copyOfRange(stallStart, rate))
        out += mixer.read()
        for (f in rate until total step chunk) {
            mixer.write(micId, ShortArray(chunk))
            mixer.write(systemId, system.copyOfRange(f, f + chunk))
            out += mixer.read()
        }
        out += mixer.flush()
        val mixed = concat(out)

        val gapStart = stallStart
        val gapEnd = rate / 2
        assertEquals((gapEnd - gapStart).toLong(), mixer.lostFrames(systemId), "frames perdidos del sistema")
        assertEquals(0L, mixer.lostFrames(micId))
        assertEquals(total, mixed.size)
        for (k in mixed.indices) {
            val expected = if (k in gapStart until gapEnd) 0 else system[k].toInt()
            if (mixed[k].toInt() != expected) {
                throw AssertionError("frame $k: ${mixed[k]} en vez de $expected (el sistema se desincronizó)")
            }
        }
    }

    /**
     * El sistema deja de entregar audio [pauseMs] y vuelve **sin ráfaga**: lo de la pausa no llega
     * nunca —como una app que silencia una fuente dejando de llamar a `write`— y lo nuevo llega a
     * ritmo normal. Lo que se exige es lo mismo que con ráfaga: el hueco en silencio, contado en
     * `lostFrames`, y el sistema **en su sitio** el resto de la grabación. Hasta la 4.1.0, con una
     * pausa menor que `maxLatencyMs` quedaba desfasado lo que durara la pausa y, con una mayor,
     * mudo hasta el final: cada trozo nuevo pagaba la deuda del audio que no llegó.
     */
    private fun pauseWithoutBurst(pauseMs: Int) {
        val rate = 48_000
        val chunk = 480
        val steps = 400
        val system = ShortArray(steps * chunk) { ((it % 20_000) + 1).toShort() }
        val mixer = PcmStreamMixer(outputRate = rate, outputChannels = 1, maxLatencyMs = 500)
        val micId = mixer.addSource(rate, 1)
        val systemId = mixer.addSource(rate, 1)
        val pause = 100 until 100 + pauseMs / 10
        val out = ArrayList<ShortArray>()
        for (step in 0 until steps) {
            mixer.write(micId, ShortArray(chunk))
            if (step !in pause) mixer.write(systemId, system.copyOfRange(step * chunk, (step + 1) * chunk))
            out += mixer.read()
        }
        out += mixer.flush()
        val mixed = concat(out)

        val gap = pause.first * chunk until (pause.last + 1) * chunk
        assertEquals(gap.count().toLong(), mixer.lostFrames(systemId), "frames perdidos del sistema con una pausa de $pauseMs ms")
        assertEquals(0L, mixer.lostFrames(micId))
        assertEquals(system.size, mixed.size)
        for (k in mixed.indices) {
            val expected = if (k in gap) 0 else system[k].toInt()
            if (mixed[k].toInt() != expected) {
                throw AssertionError("pausa de $pauseMs ms, frame $k: ${mixed[k]} en vez de $expected (el sistema se desincronizó)")
            }
        }
    }

    @Test
    fun `a source paused for less than the wait without a burst stays in sync afterwards`() = pauseWithoutBurst(300)

    @Test
    fun `a source paused for longer than the wait without a burst is not muted afterwards`() = pauseWithoutBurst(1_500)

    /**
     * Deriva sostenida: el sistema declara 8 kHz pero entrega un 0,5 % menos, como un dispositivo
     * cuyo reloj va algo más despacio (la deriva real ronda las decenas de partes por millón; aquí se
     * exagera para que en 150 s pase lo que pasaría en horas). Hasta la 4.1.0 el desfase crecía
     * hasta `maxLatencyMs` y desde ahí el sistema quedaba mudo. Ahora, cada vez que el desfase pasa
     * de la tolerancia —una décima parte de la espera—, el sistema pierde ese tramo y vuelve a su
     * sitio: suena hasta el final, nunca va más desfasado que esa tolerancia, y lo perdido es lo que
     * no entregó, salvo lo que le faltaba al acabar, que `flush` completa con silencio sin contarlo.
     */
    @Test
    fun `a source whose clock drifts keeps sounding and stays within the tolerance`() {
        val rate = 8_000
        val chunk = 80
        val steps = 15_000
        val drift = 0.005
        val tolerance = rate * 500L / 1000 / 10
        val mixer = PcmStreamMixer(outputRate = rate, outputChannels = 1, maxLatencyMs = 500)
        val micId = mixer.addSource(rate, 1)
        val systemId = mixer.addSource(rate, 1)
        val out = ArrayList<ShortArray>()
        var delivered = 0
        for (step in 0 until steps) {
            mixer.write(micId, ShortArray(chunk))
            val due = ((step + 1) * chunk * (1 - drift)).toInt()
            mixer.write(systemId, ShortArray(due - delivered) { (((delivered + it) % 30_000) + 1).toShort() })
            delivered = due
            out += mixer.read()
        }
        out += mixer.flush()
        val mixed = concat(out)

        var worst = 0.0
        var sounding = 0
        val tail = mixed.size - 10 * rate
        for (k in mixed.indices) {
            val value = mixed[k].toInt()
            if (value == 0) continue
            if (k >= tail) sounding++
            val wrapped = value - 1
            val expectedFrame = k * (1 - drift)
            val frame = wrapped + 30_000 * Math.round((expectedFrame - wrapped) / 30_000)
            worst = maxOf(worst, abs(k - frame / (1 - drift)))
        }
        assertTrue(sounding >= 9 * rate, "el sistema quedó mudo al final: suenan $sounding de ${10 * rate} frames")
        assertTrue(worst <= tolerance + 2 * chunk, "el sistema llegó a ir $worst frames desfasado (tolerancia $tolerance)")
        val undelivered = mixed.size - delivered.toLong()
        val lost = mixer.lostFrames(systemId)
        assertTrue(lost <= undelivered && undelivered - lost <= tolerance + 2 * chunk, "perdidos $lost, sin entregar $undelivered")
        assertEquals(0L, mixer.lostFrames(micId))
    }

    /** Una fuente que nunca entrega nada no detiene la mezcla ni hace crecer la memoria. */
    @Test
    fun `a source that never starts does not hold the others back`() {
        val rate = 48_000
        val mixer = PcmStreamMixer(outputRate = rate, outputChannels = 1, maxLatencyMs = 500)
        val micId = mixer.addSource(rate, 1)
        mixer.addSource(rate, 1)
        var delivered = 0
        repeat(200) {
            mixer.write(micId, ShortArray(480) { 7 })
            delivered += mixer.read().size
        }
        assertTrue(delivered >= 2 * rate - rate / 2 - 480, "la mezcla se quedó esperando: $delivered frames")
        assertEquals(0L, mixer.lostFrames(1), "una fuente que no ha empezado no tiene nada que perder")
    }

    /**
     * Una fuente que llega cuando la mezcla ya empezó se coloca donde llega: su primer trozo termina
     * donde termina el audio más avanzado.
     */
    @Test
    fun `a source that starts after mixing began is placed where it arrived`() {
        val rate = 48_000
        val mixer = PcmStreamMixer(outputRate = rate, outputChannels = 1, maxLatencyMs = 500)
        val micId = mixer.addSource(rate, 1)
        val systemId = mixer.addSource(rate, 1)
        val out = ArrayList<ShortArray>()
        repeat(100) {
            mixer.write(micId, ShortArray(480))
            out += mixer.read()
        }
        val arrivalEnd = 100 * 480
        mixer.write(systemId, ShortArray(480) { 9 })
        out += mixer.flush()
        val mixed = concat(out)

        assertEquals(arrivalEnd, mixed.size)
        assertEquals(arrivalEnd - 480, mixed.indexOfFirst { it.toInt() == 9 }, "el sistema no empieza donde llegó")
        assertEquals(0L, mixer.lostFrames(systemId))
    }

    /** El array que se pasa a `write` se copia: la app puede reutilizar su buffer. */
    @Test
    fun `the caller can reuse its buffer between writes`() {
        val mixer = PcmStreamMixer(outputRate = 48_000, outputChannels = 1)
        val id = mixer.addSource(48_000, 1)
        val shared = ShortArray(100)
        for (i in 1..5) {
            shared.fill(i.toShort())
            mixer.write(id, shared)
        }
        val mixed = mixer.read() + mixer.flush()
        assertEquals((1..5).flatMap { i -> List(100) { i } }, mixed.map { it.toInt() })
    }

    /**
     * Cada fuente desde su hilo y la lectura desde un tercero, que es el uso normal. Con una espera
     * amplia no se pierde nada, así que el resultado tiene que ser idéntico a la mezcla a mano.
     */
    @Test
    fun `two writer threads and a reader thread mix without losing or corrupting anything`() {
        val a = noise(48_000 * 3, 2, seed = 21)
        val b = noise(48_000 * 3, 2, seed = 22)
        val mixer = PcmStreamMixer(outputRate = 48_000, outputChannels = 2, maxLatencyMs = 60_000)
        mixer.addSource(48_000, 2)
        mixer.addSource(48_000, 2)
        val out = ArrayList<ShortArray>()
        val writers = listOf(a, b).mapIndexed { index, stream ->
            Thread {
                for (chunk in chunks(stream, 2, 200, 1_000, 30 + index)) mixer.write(index, chunk)
            }
        }
        val reader = Thread {
            while (writers.any { it.isAlive }) out += mixer.read()
        }
        writers.forEach { it.start() }
        reader.start()
        writers.forEach { it.join() }
        reader.join()
        out += mixer.read()
        out += mixer.flush()

        assertContentEquals(PcmMixer.mix(listOf(a, b)), concat(out))
        assertEquals(a.size / 2L, mixer.emittedFrames)
    }

    @Test
    fun `invalid configurations and misuse are rejected with a clear error`() {
        assertThrows<IllegalArgumentException> { PcmStreamMixer(outputRate = 0, outputChannels = 2) }
        assertThrows<IllegalArgumentException> { PcmStreamMixer(outputRate = 48_000, outputChannels = 2, maxLatencyMs = 0) }

        val mixer = PcmStreamMixer(outputRate = 48_000, outputChannels = 2)
        assertThrows<IllegalArgumentException> { mixer.addSource(48_000, 6) }
        assertThrows<IllegalArgumentException> { mixer.addSource(48_000, 2, gain = Float.NaN) }
        assertThrows<IllegalStateException> { mixer.read() }

        val id = mixer.addSource(48_000, 2)
        assertThrows<IllegalArgumentException> { mixer.write(id, ShortArray(3)) }
        assertThrows<IllegalArgumentException> { mixer.write(id + 1, ShortArray(2)) }
        mixer.write(id, ShortArray(4))
        assertThrows<IllegalStateException> { mixer.addSource(48_000, 2) }
        mixer.flush()
        assertThrows<IllegalStateException> { mixer.write(id, ShortArray(2)) }
        assertEquals(0, mixer.read().size)
    }
}
