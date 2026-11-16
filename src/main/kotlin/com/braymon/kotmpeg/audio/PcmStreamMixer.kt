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
 * [PcmMixer.convertChannels], y la suma la hace [PcmMixer.mix]: mientras ninguna fuente pierda
 * audio, el resultado es **exactamente** el que se obtendría convirtiendo y mezclando a mano los
 * flujos enteros, sea cual sea el tamaño de los trozos y el orden en que lleguen.
 *
 * **Alineación.** El mezclador no tiene reloj: no recibe marcas de tiempo, así que mide el tiempo
 * con el propio audio. Dentro de una fuente manda la cuenta de muestras: cada trozo va a
 * continuación del anterior. Entre fuentes, las que empiezan a entregar audio antes de que el
 * mezclador haya devuelto nada se consideran **simultáneas**: la primera muestra de cada una es el
 * instante 0 de la salida, y lo que una vaya por detrás de la más adelantada en ese momento se toma
 * por su retraso de entrega habitual, no por audio perdido. Si sabes que una arrancó X ms más tarde,
 * escríbele primero X ms de silencio. Una fuente que no llega a entregar nada hasta después de que
 * la mezcla ya haya empezado se coloca donde llega: su primer trozo termina donde termina el audio
 * más avanzado de las demás.
 *
 * **Entrega.** [read] devuelve lo que ya está presente en todas las fuentes. Si una se retrasa, se
 * la espera hasta [maxLatencyMs]; pasado ese margen, su parte se mezcla como silencio, para que
 * una fuente que se cuelga o que nunca empieza no detenga la grabación ni haga crecer la memoria
 * sin límite. Lo que pasa después depende de cómo vuelva su audio:
 *
 *  - **en ráfaga**, el que retenía un hilo atascado —lo que hace un `AudioRecord`—: se descarta lo
 *    que corresponde al tiempo ya entregado y la fuente sigue en su sitio. Un tirón deja un hueco
 *    corto y nada más;
 *  - **a ritmo normal**, sin recuperar lo que faltó —una fuente a la que se dejó de escribir un
 *    rato, o una que entrega algo más despacio de lo que declara—: si en [maxLatencyMs] no recupera
 *    lo que va por detrás, ese tramo se da por perdido y se rellena con silencio, y el audio nuevo
 *    se coloca donde llega. Se tolera sin corregir una décima parte de [maxLatencyMs] (50 ms por
 *    defecto), que cubre los tirones normales de un hilo sin meter huecos.
 *
 * Hasta la 4.1.0 todo retraso se trataba como una ráfaga pendiente: una pausa menor que
 * [maxLatencyMs] dejaba la fuente desfasada esa duración el resto de la grabación, y una mayor la
 * dejaba **muda** el resto de la grabación, porque cada trozo nuevo se descartaba para pagar el
 * audio que nunca llegó. [lostFrames] cuenta por fuente los frames mezclados como silencio y los
 * descartados.
 *
 * **Para silenciar una fuente, escríbele ceros** en vez de dejar de escribirle: así sigue en su
 * sitio y no pierde nada. Dejar de escribirle también funciona, pero la mezcla espera hasta
 * [maxLatencyMs] cada vez y lo que se pierda depende de cómo vuelva.
 *
 * **Marca de tiempo.** El frame `k` de la salida está `k / outputRate` segundos después de la
 * primera muestra de las fuentes; con [emittedFrames] la app calcula el PTS de cada bloque que pasa
 * al codificador.
 *
 * **Lo que no hace**: no corrige la deriva entre los relojes de las fuentes. Las frecuencias se
 * toman por su valor nominal, y una fuente que entrega algo más despacio de lo que declara se va
 * quedando atrás —con 45 partes por millón, unos 160 ms por hora— hasta que el desfase pasa de la
 * tolerancia: entonces pierde ese tramo, que se oye como un hueco corto, y vuelve a su sitio. El
 * desfase queda acotado a esa tolerancia, sin estirar ni comprimir el audio.
 *
 * **Es seguro entre hilos**, a diferencia de [PcmResampler]: escribir cada fuente desde su hilo y
 * leer desde el del codificador es justo el caso para el que existe. Cada operación toma un único
 * cerrojo, y dentro de él [write] además remuestrea y convierte los canales de su trozo —el
 * remuestreador de cada fuente guarda estado y no admite dos hilos—, así que lo que retiene el
 * cerrojo crece con el tamaño del trozo; [read] y [flush] mezclan lo pendiente. Los arrays que se le
 * pasan se copian: se pueden reutilizar en cuanto [write] vuelve.
 */
public class PcmStreamMixer(
    /** Frecuencia de la mezcla, en Hz. */
    public val outputRate: Int,
    /** Canales de la mezcla, intercalados. */
    public val outputChannels: Int,
    /**
     * Cuánto se espera a una fuente que va por detrás antes de mezclar su parte como silencio, y
     * cuánto tiene para recuperar lo que le falta antes de que se dé por perdido.
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

    /**
     * Cuánto puede ir una fuente más por detrás de lo que iba sin que se tome por audio perdido:
     * una décima parte de [maxLatencyMs]. Por debajo, el retraso es un tirón de su hilo o el tamaño
     * de los trozos, y se espera; por encima, si no lo recupera, se corrige (ver [place]).
     */
    private val toleranceFrames = (maxLatencyFrames / 10).coerceAtLeast(1)
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
     *
     * Antes de entregar silencio donde iría el audio nuevo de una fuente que no se ha puesto al día
     * (ver [place]), lo da por perdido y coloca ese audio en su sitio: es el último momento en que
     * todavía se puede hacer sin descartarlo.
     */
    public fun read(): ShortArray = synchronized(lock) {
        if (finished) {
            ShortArray(0)
        } else {
            seal()
            var frames = deliverable()
            while (true) {
                val overrun = sources.firstOrNull { it.pendingFrames > 0 && emitted + frames > committedEnd(it) + it.correction } ?: break
                resolveLost(overrun)
                frames = deliverable()
            }
            emit(frames, countLosses = true)
        }
    }

    /**
     * Termina la mezcla: suelta el último frame que retiene cada remuestreador, entrega todo lo
     * pendiente y completa con silencio las fuentes que acaben antes. Una fuente que todavía no se
     * había puesto al día se coloca como si se hubiera agotado su espera. Después solo queda leer
     * arrays vacíos; para otra grabación, crea otro mezclador.
     */
    public fun flush(): ShortArray = synchronized(lock) {
        check(!finished) { "el mezclador ya se cerró con flush()" }
        seal()
        finished = true
        for (s in sources) place(s, s.tailInOutputFormat())
        for (s in sources) if (s.pendingFrames > 0) resolveLost(s)
        emit(sources.maxOf { it.frames }, countLosses = false)
    }

    /**
     * Frames de la fuente [source] que se mezclaron como silencio porque su audio no llegó a tiempo
     * o no llegó nunca, o que se descartaron por llegar tarde. Cero en una grabación sin tirones; si
     * crece, esa fuente se está quedando atrás.
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

    /** Lo que [read] puede entregar ya: lo que tienen todas, o lo que pasa de [maxLatencyMs]. */
    private fun deliverable(): Int {
        var fewest = Int.MAX_VALUE
        var most = 0
        for (s in sources) {
            fewest = minOf(fewest, s.frames)
            most = maxOf(most, s.frames)
        }
        return maxOf(fewest.toLong(), most - maxLatencyFrames).coerceAtLeast(0L).toInt()
    }

    /**
     * Frame de la salida en el que acaba lo ya colocado de [s]: lo entregado, más lo que espera en
     * su buffer, menos su deuda —el silencio que se entregó en su lugar y que su audio, si llega,
     * tiene que pagar—.
     */
    private fun committedEnd(s: Source): Long = emitted + s.frames - s.debt

    /**
     * Cuánto va [s] por detrás de la fuente más adelantada si su próximo audio ([extraFrames] más
     * lo que retiene sin colocar) se coloca a continuación del anterior. Es el reloj del mezclador:
     * la más adelantada es la que más tiempo real ha entregado.
     */
    private fun lag(s: Source, extraFrames: Int): Long {
        val end = committedEnd(s) + s.pendingFrames + extraFrames
        var leader = end
        for (o in sources) if (o !== s && o.started) leader = maxOf(leader, committedEnd(o) + o.pendingFrames)
        return leader - end
    }

    /**
     * Coloca en su fuente audio ya convertido al formato de salida.
     *
     * El primer trozo de una fuente decide dónde empieza: en el instante 0 si la mezcla aún no ha
     * entregado nada, o terminando donde termina el audio más avanzado si ya empezó. En ese último
     * caso, lo que caería antes de lo ya entregado se descarta y cuenta como perdido.
     *
     * Los trozos siguientes van a continuación, salvo que con ellos la fuente quede más por detrás
     * de la más adelantada que de costumbre —su [Source.baseline] más [toleranceFrames]—. Puede ser
     * el audio atrasado de un hilo que se atascó, que llega en ráfaga y recupera el retraso, o audio
     * nuevo tras un tramo que no llegará nunca, que llega a ritmo normal y no lo recupera. No se sabe
     * hasta ver los siguientes, así que se retiene sin colocar: si la fuente se pone al día, va a
     * continuación como siempre —pagando antes la deuda, el tiempo que se mezcló como silencio
     * mientras iba por detrás—; si no, [read] lo coloca donde llegó cuando ya no puede esperar más
     * ([resolveLost]).
     *
     * El retraso de costumbre se mide al entregar la primera mezcla y después solo baja: es lo que
     * conserva la regla de las fuentes simultáneas, aunque una entregue siempre algo más tarde que
     * otra. Sin marcas de tiempo, un retraso que aparece a mitad de la grabación y no se recupera es
     * audio perdido, y uno que estaba desde el principio no se puede distinguir de un retraso de
     * entrega.
     *
     * Un trozo sin frames no es un primer trozo: un `write` vacío, o uno tan corto que el
     * remuestreador aún no saca nada de él, no dice dónde empieza la fuente. Hasta la 4.0 la daba por
     * empezada, y si la mezcla arrancaba antes de que llegara su audio, ese tiempo se le cobraba como
     * deuda y su primer trozo de verdad se descartaba en vez de colocarse donde llegaba. Por lo mismo
     * [flush] puede colocar aquí la cola del remuestreador de una fuente que no llegó a empezar.
     */
    private fun place(s: Source, audio: ShortArray) {
        val frames = audio.size / outputChannels
        if (frames == 0) return
        if (!s.started) {
            s.started = true
            if (emitted > 0) {
                var leaderEnd = 0L
                for (o in sources) if (o !== s && o.started) leaderEnd = maxOf(leaderEnd, committedEnd(o) + o.pendingFrames - emitted)
                val leader = leaderEnd.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val skip = (frames - leader).coerceAtLeast(0)
                s.lost += skip
                s.appendSilence(leader - (frames - skip))
                s.append(audio, skip, frames - skip)
                s.baseline = 0
                return
            }
            s.append(audio, 0, frames)
            return
        }
        val lag = lag(s, frames)
        if (s.pendingFrames > 0) {
            s.hold(audio, frames)
            s.pendingMinLag = minOf(s.pendingMinLag, lag)
            if (lag <= s.baseline + toleranceFrames) {
                commitPending(s)
                s.baseline = minOf(s.baseline, lag)
            }
            return
        }
        if (s.baseline != Source.UNTRACKED && lag > s.baseline + toleranceFrames) {
            s.hold(audio, frames)
            s.pendingMinLag = lag
            return
        }
        payDebtAndAppend(s, audio, frames)
        if (s.baseline != Source.UNTRACKED) s.baseline = minOf(s.baseline, lag)
    }

    /** Coloca [audio] a continuación de lo que ya tiene [s], descartando antes lo que debe. */
    private fun payDebtAndAppend(s: Source, audio: ShortArray, frames: Int) {
        val skip = minOf(s.debt, frames.toLong()).toInt()
        s.debt -= skip
        s.append(audio, skip, frames - skip)
    }

    /** Coloca a continuación lo que [s] retenía sin colocar: la fuente se puso al día. */
    private fun commitPending(s: Source) {
        for (chunk in s.pending) payDebtAndAppend(s, chunk, chunk.size / outputChannels)
        s.clearPending()
    }

    /**
     * Da por perdido el tramo que [s] no recuperó y coloca su audio retenido donde llegó:
     * [Source.correction] frames de silencio detrás de lo ya colocado —lo que se entregó como
     * silencio en ese tramo deja de ser deuda—, y después el audio. Si la entrega ya pasó de donde
     * empieza ese audio, lo que se solapa se descarta como cualquier deuda.
     */
    private fun resolveLost(s: Source) {
        val correction = s.correction.coerceAtLeast(0L)
        val forgiven = minOf(s.debt, correction)
        s.debt -= forgiven
        val silence = correction - forgiven
        s.appendSilence(silence.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        s.lost += silence
        commitPending(s)
    }

    /**
     * Saca [frames] frames de cada fuente y los mezcla. Una fuente que no tiene tantos se completa
     * con silencio; si ya había empezado, ese tiempo pasa a su deuda y a [lostFrames].
     *
     * Con la primera mezcla se fija el retraso de costumbre de cada fuente que ya ha empezado (ver
     * [place]): hasta entonces son simultáneas y ninguna puede ir «más por detrás de lo normal».
     */
    private fun emit(frames: Int, countLosses: Boolean): ShortArray {
        if (frames == 0) return ShortArray(0)
        if (emitted == 0L) {
            for (s in sources) if (s.started && s.baseline == Source.UNTRACKED) s.baseline = lag(s, 0)
        }
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

        /**
         * Lo menos que ha ido por detrás de la fuente más adelantada mientras estaba al día, en
         * frames: su retraso de entrega habitual. [UNTRACKED] hasta la primera mezcla.
         */
        var baseline = UNTRACKED

        /** Audio ya convertido que llegó con la fuente más por detrás de lo normal, sin colocar. */
        val pending = ArrayList<ShortArray>()
        var pendingFrames = 0
            private set

        /** Lo menos que ha ido por detrás mientras retenía [pending]: el tramo que no recuperó. */
        var pendingMinLag = Long.MAX_VALUE

        /** Frames de silencio que pondría [PcmStreamMixer.resolveLost] delante de [pending]. */
        val correction: Long get() = pendingMinLag - baseline

        val frames: Int get() = used / outputChannels

        fun toOutputFormat(samples: ShortArray): ShortArray =
            PcmMixer.convertChannels(resampler.resample(samples), channels, outputChannels)

        fun tailInOutputFormat(): ShortArray =
            PcmMixer.convertChannels(resampler.flush(), channels, outputChannels)

        /**
         * Retiene [audio] sin colocar. Se guarda una copia: con frecuencias iguales y sin conversión
         * de canales, lo convertido es el mismo array que pasó quien llama.
         */
        fun hold(audio: ShortArray, frameCount: Int) {
            require(pendingFrames.toLong() + frameCount <= Int.MAX_VALUE / outputChannels) {
                "demasiado audio retenido en una fuente: ${pendingFrames + frameCount.toLong()} frames"
            }
            pending += audio.copyOf()
            pendingFrames += frameCount
        }

        fun clearPending() {
            pending.clear()
            pendingFrames = 0
            pendingMinLag = Long.MAX_VALUE
        }

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

        companion object {
            private const val MIN_CAPACITY = 4096

            /** [baseline] de una fuente que aún no ha entrado en la primera mezcla. */
            const val UNTRACKED: Long = Long.MAX_VALUE / 4
        }
    }
}
