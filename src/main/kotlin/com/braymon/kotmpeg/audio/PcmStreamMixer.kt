package com.braymon.kotmpeg.audio

/**
 * Mezclador en streaming para el audio de una grabación: recibe el PCM de cada fuente según
 * llega —en trozos de cualquier tamaño, a su propia frecuencia y con sus propios canales— y
 * entrega la mezcla ya alineada, lista para el codificador.
 *
 * Es la forma cómoda de cumplir el montaje «Mezcla» de la regla de audio (micrófono y sistema en
 * una sola pista). [PcmMixer.mix] exige bloques que cubran exactamente el mismo tiempo, y en
 * Android casi nunca llegan así: el micrófono suele ir mono a 44,1 kHz y el audio del sistema
 * estéreo a 48 kHz, cada `AudioRecord` se lee en su propio hilo y cada uno entrega trozos de un
 * tamaño distinto. Cuadrarlos le tocaba a la app, y un bloque mal cuadrado se oía como un hueco.
 *
 * ```kotlin
 * val mezclador = PcmStreamMixer(outputRate = 48_000, outputChannels = 2)
 * val micro = mezclador.addSource(sampleRate = 44_100, channels = 1)
 * val sistema = mezclador.addSource(sampleRate = 48_000, channels = 2)
 *
 * mezclador.write(micro, pcmMicro)           // desde el hilo del micrófono
 * mezclador.write(sistema, pcmSistema)       // desde el hilo de la captura del sistema
 * val pcm = mezclador.read()                 // desde el hilo del codificador; puede venir vacío
 * val cola = mezclador.flush()               // al terminar la grabación
 * ```
 *
 * **Formato.** Cada fuente se lleva al formato de salida con [PcmResampler] y
 * [PcmMixer.convertChannels], y la suma la hace [PcmMixer.mix]: el resultado es **exactamente** el
 * que se obtendría convirtiendo y mezclando a mano los flujos enteros, sea cual sea el tamaño de los
 * trozos y el orden en que lleguen.
 *
 * **Alineación.** Dentro de una fuente manda la cuenta de muestras: cada trozo va a continuación del
 * anterior. Entre fuentes, las que empiezan a entregar audio antes de que el mezclador haya devuelto
 * nada se consideran **simultáneas**: la primera muestra de cada una es el instante 0 de la salida.
 * Si sabes que una arrancó X ms más tarde, escríbele primero X ms de silencio. Una fuente que no
 * llega a entregar nada hasta después de que la mezcla ya haya empezado se coloca donde llega: su
 * primer trozo termina donde termina el audio más avanzado de las demás.
 *
 * **Entrega.** [read] devuelve lo que ya está presente en todas las fuentes. Si una se retrasa, se
 * la espera hasta [maxLatencyMs]; pasado ese margen, su parte se da por perdida y se mezcla como
 * silencio, para que una fuente que se cuelga o que nunca empieza no detenga la grabación ni haga
 * crecer la memoria sin límite. Cuando el audio retrasado llega por fin, se descarta lo que
 * corresponde al tiempo ya entregado: un tirón de un hilo deja un hueco corto, pero la fuente sigue
 * sincronizada el resto de la grabación. [lostFrames] cuenta esos frames por fuente.
 *
 * **Marca de tiempo.** El frame `k` de la salida está `k / outputRate` segundos después de la
 * primera muestra de las fuentes; con [emittedFrames] la app calcula el PTS de cada bloque que pasa
 * al codificador.
 *
 * **Lo que no hace**: no corrige la deriva entre los relojes de las fuentes. Las frecuencias se
 * toman por su valor nominal; si un dispositivo entregara una fuente algo más despacio de lo que
 * declara, se notaría como pérdidas puntuales en [lostFrames], nunca como un desfase creciente.
 *
 * **Es seguro entre hilos**, a diferencia de [PcmResampler]: escribir cada fuente desde su hilo y
 * leer desde el del codificador es justo el caso para el que existe. Cada operación toma un único
 * cerrojo y solo copia arrays. Los arrays que se le pasan se copian: se pueden reutilizar en cuanto
 * [write] vuelve.
 */
public class PcmStreamMixer(
    /** Frecuencia de la mezcla, en Hz. */
    public val outputRate: Int,
    /** Canales de la mezcla, intercalados. */
    public val outputChannels: Int,
    /**
     * Cuánto se espera a una fuente que va por detrás antes de mezclar su parte como silencio.
     * Medio segundo cubre de sobra los tirones normales de un hilo de captura; en una grabación a
     * archivo, la espera no se nota en el resultado, solo retrasa un poco la entrega.
     */
    public val maxLatencyMs: Int = 500,
) {
    init {
        require(outputRate > 0) { "frecuencia de salida inválida: $outputRate" }
        require(outputChannels in 1..8) { "número de canales de salida inválido: $outputChannels" }
        require(maxLatencyMs > 0) { "maxLatencyMs debe ser positivo: $maxLatencyMs" }
    }

    private val lock = Any()
    private val sources = ArrayList<Source>()
    private val maxLatencyFrames = (outputRate.toLong() * maxLatencyMs / 1000).coerceAtLeast(1)
    private var sealed = false
    private var finished = false
    private var emitted = 0L
    private var gains: List<Float>? = null

    /** Frames de salida entregados hasta ahora por [read] y [flush]. */
    public val emittedFrames: Long get() = synchronized(lock) { emitted }

    /**
     * Registra una fuente de PCM de 16 bits intercalado y devuelve su índice, que es el que se pasa
     * a [write]. Todas las fuentes se registran antes del primer [write] o [read].
     *
     * Los canales tienen que coincidir con los de salida, o ser una conversión mono↔estéreo. [gain]
     * se aplica como en [PcmMixer.mix]; con todas a 1,0 la suma va por la ruta entera.
     */
    public fun addSource(sampleRate: Int, channels: Int, gain: Float = 1f): Int = synchronized(lock) {
        check(!sealed) { "las fuentes se registran antes de empezar a escribir o leer" }
        require(sampleRate > 0) { "frecuencia de la fuente inválida: $sampleRate" }
        require(channels in 1..8) { "número de canales de la fuente inválido: $channels" }
        require(channels == outputChannels || (channels in 1..2 && outputChannels in 1..2)) {
            "conversión de canales no soportada: $channels -> $outputChannels"
        }
        require(gain.isFinite() && gain >= 0f) { "ganancia inválida: $gain" }
        sources += Source(sampleRate, channels, gain, outputRate, outputChannels)
        sources.lastIndex
    }

    /**
     * Entrega [samples] (intercalado, frames completos) de la fuente [source]. El array se copia.
     *
     * @throws IllegalStateException si el mezclador ya se cerró con [flush].
     */
    public fun write(source: Int, samples: ShortArray) {
        synchronized(lock) {
            check(!finished) { "el mezclador ya se cerró con flush()" }
            val s = sources.getOrNull(source) ?: throw IllegalArgumentException("fuente desconocida: $source")
            require(samples.size % s.channels == 0) {
                "el buffer de la fuente $source (${samples.size}) no es múltiplo de ${s.channels} canales: " +
                    "hay un frame incompleto que se perdería en silencio"
            }
            seal()
            place(s, s.toOutputFormat(samples))
        }
    }

    /**
     * Devuelve la mezcla que ya se puede entregar, intercalada en el formato de salida. Puede venir
     * vacía: mientras una fuente va por detrás dentro de [maxLatencyMs], se la espera.
     */
    public fun read(): ShortArray = synchronized(lock) {
        if (finished) {
            ShortArray(0)
        } else {
            seal()
            var fewest = Int.MAX_VALUE
            var most = 0
            for (s in sources) {
                fewest = minOf(fewest, s.frames)
                most = maxOf(most, s.frames)
            }
            val frames = maxOf(fewest.toLong(), most - maxLatencyFrames).coerceAtLeast(0L).toInt()
            emit(frames, countLosses = true)
        }
    }

    /**
     * Termina la mezcla: suelta el último frame que retiene cada remuestreador, entrega todo lo
     * pendiente y completa con silencio las fuentes que acaben antes. Después solo queda leer
     * arrays vacíos; para otra grabación, crea otro mezclador.
     */
    public fun flush(): ShortArray = synchronized(lock) {
        check(!finished) { "el mezclador ya se cerró con flush()" }
        seal()
        finished = true
        for (s in sources) if (s.started) place(s, s.tailInOutputFormat())
        emit(sources.maxOf { it.frames }, countLosses = false)
    }

    /**
     * Frames de la fuente [source] que se mezclaron como silencio porque su audio no llegó a tiempo,
     * o que se descartaron por llegar tarde. Cero en una grabación sin tirones; si crece, esa fuente
     * se está quedando atrás.
     */
    public fun lostFrames(source: Int): Long = synchronized(lock) {
        (sources.getOrNull(source) ?: throw IllegalArgumentException("fuente desconocida: $source")).lost
    }

    /** A partir del primer [write] o [read] ya no se admiten fuentes nuevas. */
    private fun seal() {
        if (sealed) return
        check(sources.isNotEmpty()) { "no hay fuentes: registra al menos una con addSource()" }
        sealed = true
        gains = if (sources.all { it.gain == 1f }) null else sources.map { it.gain }
    }

    /**
     * Coloca en su fuente audio ya convertido al formato de salida.
     *
     * El primer trozo de una fuente decide dónde empieza: en el instante 0 si la mezcla aún no ha
     * entregado nada, o terminando donde termina el audio más avanzado si ya empezó. En ese último
     * caso, lo que caería antes de lo ya entregado se descarta y cuenta como perdido. Los trozos
     * siguientes pagan primero la deuda: el tiempo que se mezcló como silencio mientras la fuente
     * iba por detrás.
     */
    private fun place(s: Source, audio: ShortArray) {
        val frames = audio.size / outputChannels
        if (!s.started) {
            s.started = true
            if (emitted > 0) {
                val leaderEnd = sources.maxOf { it.frames }
                val skip = (frames - leaderEnd).coerceAtLeast(0)
                s.lost += skip
                s.appendSilence(leaderEnd - (frames - skip))
                s.append(audio, skip, frames - skip)
                return
            }
        }
        val skip = minOf(s.debt, frames.toLong()).toInt()
        s.debt -= skip
        s.append(audio, skip, frames - skip)
    }

    /**
     * Saca [frames] frames de cada fuente y los mezcla. Una fuente que no tiene tantos se completa
     * con silencio; si ya había empezado, ese tiempo pasa a su deuda y a [lostFrames].
     */
    private fun emit(frames: Int, countLosses: Boolean): ShortArray {
        if (frames == 0) return ShortArray(0)
        val parts = ArrayList<ShortArray>(sources.size)
        for (s in sources) {
            val part = ShortArray(frames * outputChannels)
            val missing = s.take(frames, part)
            if (missing > 0 && countLosses && s.started) {
                s.debt += missing
                s.lost += missing
            }
            parts += part
        }
        emitted += frames
        return PcmMixer.mix(parts, gains)
    }

    /** Estado de una fuente: su conversión al formato de salida y el audio aún sin entregar. */
    private class Source(
        sampleRate: Int,
        val channels: Int,
        val gain: Float,
        outputRate: Int,
        private val outputChannels: Int,
    ) {
        private val resampler = PcmResampler(sampleRate, outputRate, channels)
        private var buffer = ShortArray(0)
        private var used = 0

        var started = false
        var debt = 0L
        var lost = 0L

        val frames: Int get() = used / outputChannels

        fun toOutputFormat(samples: ShortArray): ShortArray =
            PcmMixer.convertChannels(resampler.resample(samples), channels, outputChannels)

        fun tailInOutputFormat(): ShortArray =
            PcmMixer.convertChannels(resampler.flush(), channels, outputChannels)

        fun append(audio: ShortArray, fromFrame: Int, frameCount: Int) {
            if (frameCount <= 0) return
            val count = frameCount * outputChannels
            ensureCapacity(count)
            System.arraycopy(audio, fromFrame * outputChannels, buffer, used, count)
            used += count
        }

        fun appendSilence(frameCount: Int) {
            if (frameCount <= 0) return
            val count = frameCount * outputChannels
            ensureCapacity(count)
            buffer.fill(0, used, used + count)
            used += count
        }

        /** Copia hasta [frameCount] frames al principio de [into] y devuelve cuántos faltaron. */
        fun take(frameCount: Int, into: ShortArray): Int {
            val available = minOf(frames, frameCount)
            val count = available * outputChannels
            System.arraycopy(buffer, 0, into, 0, count)
            System.arraycopy(buffer, count, buffer, 0, used - count)
            used -= count
            return frameCount - available
        }

        private fun ensureCapacity(extra: Int) {
            val needed = used.toLong() + extra
            if (needed <= buffer.size) return
            require(needed <= Int.MAX_VALUE - 8) { "demasiado audio retenido en una fuente: $needed muestras" }
            buffer = buffer.copyOf(maxOf(needed, buffer.size * 2L, MIN_CAPACITY.toLong()).toInt())
        }

        private companion object {
            private const val MIN_CAPACITY = 4096
        }
    }
}
