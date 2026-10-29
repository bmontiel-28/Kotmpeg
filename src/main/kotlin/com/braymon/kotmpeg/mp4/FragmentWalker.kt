package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.model.Timestamps

/** Los valores por defecto de un `trex` para las muestras de su pista. */
internal class TrexDefaults(val duration: Long, val size: Long, val flags: Long)

/**
 * Lo que el recorrido de fragmentos necesita de una pista de fMP4 para dar tiempo a sus muestras:
 * su escala y la lista de edición de su `trak`, que vale para toda su línea de tiempo.
 */
internal class FragmentTrack(
    val trackId: Int,
    private val mediaTimescale: Long,
    private val elstMediaTime: Long,
    private val elstEmptyDurationUs: Long,
) {
    fun ticksToUs(ticks: Long): Long = Timestamps.rescaleFloor(ticks, 1_000_000, mediaTimescale)

    /** Un instante de la línea de tiempo del medio, en tics, llevado a la de presentación en µs. */
    fun timeUs(ticks: Long): Long = ticksToUs(ticks - elstMediaTime) + elstEmptyDurationUs
}

/**
 * El estado de un recorrido de fragmentos: dónde sigue, el tiempo de decodificación acumulado de
 * cada pista —para los `traf` sin `tfdt`— y a quién se entregan sus muestras. Hay uno por
 * recorrido, la pasada al abrir y cada cursor, y [FragmentWalker], que no guarda estado propio, lo
 * recibe en cada paso: es lo que permite que todos compartan un único parser.
 *
 * Todo va en arrays indexados por la posición de la pista en [FragmentWalker.tracks]. Antes eran
 * mapas por `track_ID` en cada cursor, y con miles de pistas declaradas cada cursor llevaba miles
 * de entradas.
 */
internal class WalkState(trackCount: Int, val warn: ((String) -> Unit)?) {
    /** Inicio de la siguiente caja de primer nivel. */
    var position: Long = 0

    val ticks = LongArray(trackCount)

    /** Destino de las muestras de cada pista; null si no se recogen. */
    val sinks = arrayOfNulls<SampleSink>(trackCount)

    /**
     * Si no es null, el mayor `dts + duración` en tics de las muestras entregadas a cada pista,
     * sin la lista de edición. Es la duración de la pista, y solo la quiere la pasada al abrir.
     */
    var ends: LongArray? = null

    /**
     * Lo activa un destino que ya tiene lo que quería: el recorrido deja el `moof` en curso sin
     * terminarlo. Solo lo usan los cursores, que vuelven a empezar ese `moof` en su siguiente carga.
     */
    var halt: Boolean = false
}

/**
 * El parser de fragmentos (`moof` con sus `traf`, `tfhd`, `tfdt` y `trun`), común a la pasada al
 * abrir ([scan]) y a los cursores ([FragmentCursor]), que así recorren el archivo exactamente
 * igual. Va con un contexto explícito —las pistas, los `trex` y un [WalkState] por recorrido— en
 * vez de como clases internas del demuxer.
 *
 * Los `trun` de **todas** las pistas se recorren, también los de las que no se leen —códecs no
 * soportados o pistas por encima del límite—: sin `base_data_offset` ni `default-base-is-moof`,
 * los datos de un `traf` empiezan donde acaban los del anterior, y saltarse uno hacía que el
 * siguiente leyera bytes ajenos sin ningún aviso. De esas pistas solo se suman tamaños y
 * duraciones, sin entregar nada; con un `trun` sin campos por muestra, en una operación.
 */
internal class FragmentWalker(
    private val boxes: Mp4BoxReader,
    val tracks: List<FragmentTrack>,
    private val trex: Map<Int, TrexDefaults>,
) {
    private val input get() = boxes.input
    private val trackIds = IntArray(tracks.size) { tracks[it].trackId }

    val trackCount: Int get() = tracks.size

    /** Posición de la pista [trackId] en [tracks], o -1 si no se lee. */
    private fun slotOf(trackId: Int): Int {
        for (j in trackIds.indices) if (trackIds[j] == trackId) return j
        return -1
    }

    /**
     * Lee la caja de primer nivel que empieza en `state.position` y, si es un `moof`, la parsea;
     * deja `state.position` en la siguiente. false si el recorrido termina ahí. Un `moof` ilegible
     * se avisa, si el estado tiene a quién, y se salta.
     */
    fun step(state: WalkState): Boolean {
        val boxStart = state.position
        if (boxStart + 8 > input.length) return false
        input.position = boxStart
        val box = boxes.readBoxHeader(input.length) ?: return false
        if (box.dataEnd <= boxStart || box.dataEnd > input.length) return false
        if (box.type == "moof") {
            try {
                parseMoof(boxStart, box, state)
            } catch (e: Exception) {
                state.warn?.invoke("fragmento ilegible en el offset $boxStart (${e.message})")
            }
        }
        state.position = box.dataEnd
        return true
    }

    /**
     * La pasada al abrir: recorre los fragmentos una vez, sin guardar sus muestras —solo las cuenta
     * por pista, comprueba si sus DTS avanzan y anota dónde acaban— y deja puntos de control desde
     * los que [FragmentCursor] retoma el recorrido. Los avisos salen aquí, una vez; los cursores
     * repiten el mismo recorrido en silencio.
     */
    fun scan(budget: SampleBudget, warn: (String) -> Unit): FragmentScan {
        val checkpoints = FragmentCheckpoints(trackCount)
        val state = WalkState(trackCount, warn)
        val ends = LongArray(trackCount) { Long.MIN_VALUE }
        state.ends = ends
        val stats = Array(trackCount) { j ->
            FragmentStats(j, checkpoints, budget) { sample, reason ->
                warn("pista ${trackIds[j]}: se ignoran sus muestras a partir de la $sample: $reason")
            }
        }
        for (j in 0 until trackCount) state.sinks[j] = stats[j]
        while (true) {
            if (checkpoints.count == 0 || checkpointDue(stats)) checkpoints.record(state.position, state.ticks, stats)
            if (!step(state)) break
        }
        for (track in stats) track.closePendingCheckpoints()
        checkpoints.trim()
        return FragmentScan(stats, checkpoints, ends)
    }

    /**
     * Si toca un punto de control: cuando alguna pista ha avanzado [TrackSamples.CHECKPOINT_INTERVAL]
     * muestras desde el anterior, que es lo que acota lo que un cursor recorre al buscar, y entre
     * todas al menos [CHECKPOINT_SAMPLES_PER_TRACK] por pista. Lo segundo no cambia nada con unas
     * pocas pistas, pero un punto de control guarda el estado de todas: con 64 pistas y uno por
     * `moof`, cada muestra costaba varios bytes de puntos de control.
     */
    private fun checkpointDue(stats: Array<FragmentStats>): Boolean {
        var total = 0L
        var anyFull = false
        for (track in stats) {
            val advanced = track.count - track.countAtCheckpoint
            total += advanced
            if (advanced >= TrackSamples.CHECKPOINT_INTERVAL) anyFull = true
        }
        return anyFull && total >= CHECKPOINT_SAMPLES_PER_TRACK * stats.size
    }

    private fun parseMoof(moofStart: Long, moof: Mp4Box, state: WalkState) {
        var chainedOffset = -1L
        boxes.scanChildren(moof.dataStart, moof.dataEnd) { child ->
            if (!state.halt && child.type == "traf") chainedOffset = parseTraf(moofStart, child, state, chainedOffset)
        }
    }

    /**
     * Parsea un `traf` y devuelve dónde empiezan los datos del siguiente si va encadenado: donde
     * acaban los de este o, si no tiene ningún `trun`, el mismo [chainedOffset] que recibió.
     */
    private fun parseTraf(moofStart: Long, traf: Mp4Box, state: WalkState, chainedOffset: Long): Long {
        val run = TrunContext()
        var sawTfhd = false
        var sawTfdt = false
        var hasBaseDataOffset = false
        var baseDataOffset = 0L
        var defaultBaseIsMoof = false

        boxes.scanChildren(traf.dataStart, traf.dataEnd) { child ->
            if (state.halt) return@scanChildren
            input.position = child.dataStart
            when (child.type) {
                "tfhd" -> {
                    val flags = input.readInt32() and 0xFFFFFF
                    defaultBaseIsMoof = flags and FragmentFlags.TFHD_DEFAULT_BASE_IS_MOOF != 0
                    val trackId = input.readInt32()
                    hasBaseDataOffset = flags and FragmentFlags.TFHD_BASE_DATA_OFFSET != 0
                    if (hasBaseDataOffset) baseDataOffset = input.readInt64()
                    if (flags and FragmentFlags.TFHD_SAMPLE_DESCRIPTION_INDEX != 0) input.skip(4)
                    val defaults = trex[trackId]
                    run.defaultDuration = if (flags and FragmentFlags.TFHD_DEFAULT_SAMPLE_DURATION != 0) {
                        input.readUInt(4)
                    } else defaults?.duration ?: 0
                    run.defaultSize = if (flags and FragmentFlags.TFHD_DEFAULT_SAMPLE_SIZE != 0) {
                        input.readUInt(4)
                    } else defaults?.size ?: 0
                    run.defaultFlags = if (flags and FragmentFlags.TFHD_DEFAULT_SAMPLE_FLAGS != 0) {
                        input.readUInt(4)
                    } else defaults?.flags ?: 0
                    run.slot = slotOf(trackId)
                    if (!sawTfdt) run.dtsTicks = if (run.slot >= 0) state.ticks[run.slot] else 0L
                    sawTfhd = true
                }
                "tfdt" -> {
                    val version = input.readByte()
                    input.skip(3)
                    run.dtsTicks = if (version == 1) input.readInt64() else input.readUInt(4)
                    sawTfdt = true
                }
                "trun" -> if (sawTfhd) {
                    val trafBase = when {
                        hasBaseDataOffset -> baseDataOffset
                        defaultBaseIsMoof -> moofStart
                        chainedOffset >= 0 -> chainedOffset
                        else -> moofStart
                    }
                    parseTrun(child, trafBase, run, state)
                    if (run.slot >= 0) state.ticks[run.slot] = run.dtsTicks
                }
            }
        }
        return if (run.nextSampleOffset >= 0) run.nextSampleOffset else chainedOffset
    }

    /** Lo que un `trun` hereda de su `traf` y deja al siguiente. */
    private class TrunContext {
        var slot = -1
        var defaultDuration = 0L
        var defaultSize = 0L
        var defaultFlags = 0L
        var dtsTicks = 0L
        var nextSampleOffset = -1L
    }

    /**
     * Un `trun`. Las muestras de una pista que no se recoge no se entregan, pero sus tamaños y
     * duraciones se suman igual, para encadenar el `traf` siguiente.
     *
     * El `sample_composition_time_offset` se lee **con signo también en la versión 0**, como
     * FFmpeg: la versión 0 lo declara sin signo, pero los escritores que necesitan uno negativo
     * escriben a menudo esa versión, y leído sin signo un −1001 daba un PTS unas 13 horas por
     * delante. Ningún escritor razonable necesita un desfase positivo de más de 2^31 tics.
     *
     * De un `trun` sin campos por muestra se entregan como mucho [MAX_TRUN_SAMPLES], con aviso. Lo
     * que viene detrás —otro `trun` sin `data_offset` o el `traf` siguiente, encadenado— empieza
     * donde acaban, en datos y en tiempo, **todas** las muestras que ese `trun` ocupa en el archivo,
     * no solo las entregadas. Hasta ahora el recorte era silencioso y contaba solo las entregadas, y
     * la muestra de detrás se leía de bytes del `trun` anterior y con un tiempo que no era el suyo.
     */
    private fun parseTrun(trun: Mp4Box, trafBase: Long, run: TrunContext, state: WalkState) {
        val flags = input.readInt32() and 0xFFFFFF
        val declaredCount = input.readInt32()
        var offset = when {
            flags and FragmentFlags.TRUN_DATA_OFFSET != 0 -> trafBase + input.readInt32()
            run.nextSampleOffset >= 0 -> run.nextSampleOffset
            else -> trafBase
        }
        val hasFirstSampleFlags = flags and FragmentFlags.TRUN_FIRST_SAMPLE_FLAGS != 0
        val firstSampleFlags = if (hasFirstSampleFlags) input.readUInt(4) else run.defaultFlags
        val slot = run.slot
        val track = if (slot >= 0) tracks[slot] else null
        val entryBytes = FragmentFlags.trunEntryBytes(flags)

        if (entryBytes == 0) {
            val physical = boxes.physicalCount(declaredCount, run.defaultSize)
            val count = minOf(physical, MAX_TRUN_SAMPLES)
            val sink = if (slot >= 0) state.sinks[slot] else null
            if (sink != null && track != null) {
                if (physical > count) {
                    state.warn?.invoke(
                        "pista ${track.trackId}: un trun declara ${declaredCount.toLong() and 0xFFFFFFFFL} muestras " +
                            "sin campos propios y solo se leen las $MAX_TRUN_SAMPLES primeras",
                    )
                }
                emitImplicit(offset, count, firstSampleFlags, run, track, sink, state)
            }
            offset += physical * run.defaultSize
            run.dtsTicks += physical * run.defaultDuration
        } else {
            val count = boxes.boundedCount(declaredCount, entryBytes, trun.dataEnd)
            for (s in 0 until count) {
                if (state.halt) break
                val duration = if (flags and FragmentFlags.TRUN_SAMPLE_DURATION != 0) input.readUInt(4) else run.defaultDuration
                val size = if (flags and FragmentFlags.TRUN_SAMPLE_SIZE != 0) input.readUInt(4) else run.defaultSize
                val sampleFlags = when {
                    flags and FragmentFlags.TRUN_SAMPLE_FLAGS != 0 -> input.readUInt(4)
                    s == 0 -> firstSampleFlags
                    else -> run.defaultFlags
                }
                val ctts = if (flags and FragmentFlags.TRUN_SAMPLE_COMPOSITION_TIME_OFFSET != 0) input.readInt32().toLong() else 0L
                val sink = if (slot >= 0) state.sinks[slot] else null
                if (sink != null && track != null && offset >= 0 && offset <= input.length && size <= input.length - offset) {
                    emit(sink, slot, track, offset, size, run.dtsTicks, ctts, duration, sampleFlags, state)
                }
                offset += size
                run.dtsTicks += duration
            }
        }
        run.nextSampleOffset = offset
    }

    /**
     * Entrega las muestras de un `trun` sin campos por muestra, que comparten tamaño y duración.
     * Las que están dentro del archivo forman un tramo contiguo, que se calcula sin recorrer las
     * demás: un `trun` de 16 bytes puede declarar un millón de muestras, y uno que apunta fuera del
     * archivo costaba un millón de vueltas para no entregar ninguna. 60 000 así eran 6·10¹⁰ al abrir.
     */
    private fun emitImplicit(
        offset: Long,
        count: Int,
        firstSampleFlags: Long,
        run: TrunContext,
        track: FragmentTrack,
        sink: SampleSink,
        state: WalkState,
    ) {
        val size = run.defaultSize
        val length = input.length
        val first: Long = when {
            offset >= 0 -> 0L
            size == 0L -> count.toLong()
            else -> minOf(count.toLong(), (-(offset + 1)) / size + 1)
        }
        val firstOffset = offset + first * size
        val end: Long = when {
            first >= count -> first
            size == 0L -> if (firstOffset <= length) count.toLong() else first
            size > length || firstOffset > length - size -> first
            else -> minOf(count.toLong(), first + (length - size - firstOffset) / size + 1)
        }
        for (s in first until end) {
            if (!emit(
                    sink, run.slot, track, offset + s * size, size, run.dtsTicks + s * run.defaultDuration, 0L,
                    run.defaultDuration, if (s == 0L) firstSampleFlags else run.defaultFlags, state,
                )
            ) {
                break
            }
        }
    }

    /**
     * Entrega una muestra que está dentro del archivo. Si el destino no quiere más, deja de
     * entregarle las de esa pista en lo que queda de recorrido y devuelve false.
     */
    private fun emit(
        sink: SampleSink,
        slot: Int,
        track: FragmentTrack,
        offset: Long,
        size: Long,
        dtsTicks: Long,
        ctts: Long,
        duration: Long,
        sampleFlags: Long,
        state: WalkState,
    ): Boolean {
        val accepted = sink.add(
            offset = offset,
            size = if (size > Int.MAX_VALUE) UNREADABLE_SIZE else size.toInt(),
            ptsUs = track.timeUs(dtsTicks + ctts),
            dtsUs = track.timeUs(dtsTicks),
            key = sampleFlags and FragmentFlags.SAMPLE_IS_NON_SYNC == 0L,
            durationUs = track.ticksToUs(duration),
        )
        if (!accepted) {
            state.sinks[slot] = null
            return false
        }
        state.ends?.let { ends ->
            val end = if (dtsTicks > Long.MAX_VALUE - duration) Long.MAX_VALUE else dtsTicks + duration
            if (end > ends[slot]) ends[slot] = end
        }
        return true
    }

    companion object {
        /**
         * Muestras de un `trun` sin campos por muestra. Su cuenta no la acota el tamaño de la caja,
         * solo el del archivo, y un `trun` de 16 bytes en un archivo de 20 MB declaraba 2·10⁷
         * muestras. `FragmentedMp4Repair` lo usa como tope de muestras de un `moof` entero, porque
         * allí cada una es un objeto en memoria: ningún fragmento real se acerca —son seis horas de
         * AAC a 48 kHz—. Antes cada uno llevaba su copia del número.
         */
        const val MAX_TRUN_SAMPLES: Int = 1 shl 20

        /** Muestras por pista, entre todas, que tiene que haber entre dos puntos de control. */
        const val CHECKPOINT_SAMPLES_PER_TRACK: Int = 40

        /**
         * Tamaño que marca una muestra como ilegible: una muestra de más de 2 GiB no cabe en un
         * array, y `readPacket` la salta con aviso en vez de intentar reservarla.
         */
        private const val UNREADABLE_SIZE = -1
    }
}

/** Lo que deja la pasada al abrir: la cuenta de cada pista, los puntos de control y dónde acaba cada una. */
internal class FragmentScan(
    val stats: Array<FragmentStats>,
    val checkpoints: FragmentCheckpoints,
    private val ends: LongArray,
) {
    /** Fin de la pista [slot] en tics de su medio, sin la lista de edición. */
    fun endTicks(slot: Int): Long = ends[slot]
}

/**
 * Cuenta de una pista de fMP4 durante la pasada al abrir. Además rellena, en los puntos de
 * control que la esperan, el DTS de la primera muestra de esta pista que llega tras ellos, y
 * descuenta cada muestra de [budget]: la primera que no cabe termina la pista, con aviso por
 * [onRefused].
 */
internal class FragmentStats(
    private val slot: Int,
    private val checkpoints: FragmentCheckpoints,
    private val budget: SampleBudget,
    private val onRefused: (Int, String) -> Unit,
) : SampleSink {
    var count = 0
        private set
    var countAtCheckpoint = 0
    var monotonic = true
        private set
    var firstPositiveDurationUs = 0L
        private set
    private var lastDtsUs = 0L

    /**
     * Primer punto de control que aún espera el DTS de esta pista. Se sigue por posición y no
     * con un valor centinela, porque cualquier `Long` puede ser un DTS real: un `elst` dañado da
     * `Long.MIN_VALUE`, y con ese centinela la búsqueda empezaba en la muestra equivocada.
     */
    private var pendingCheckpoint = 0

    override fun add(offset: Long, size: Int, ptsUs: Long, dtsUs: Long, key: Boolean, durationUs: Long): Boolean {
        val refusal = budget.take(offset, size.toLong())
        if (refusal != null) {
            onRefused(count, refusal)
            return false
        }
        while (pendingCheckpoint < checkpoints.count && checkpoints.index(pendingCheckpoint, slot) == count) {
            checkpoints.setDts(pendingCheckpoint, slot, dtsUs)
            pendingCheckpoint++
        }
        if (count > 0 && dtsUs < lastDtsUs) monotonic = false
        if (firstPositiveDurationUs == 0L && durationUs > 0) firstPositiveDurationUs = durationUs
        lastDtsUs = dtsUs
        count++
        return true
    }

    /** Los puntos tras los que esta pista ya no tiene muestras se quedan con el DTS máximo. */
    fun closePendingCheckpoints() {
        for (c in pendingCheckpoint until checkpoints.count) checkpoints.setDts(c, slot, Long.MAX_VALUE)
        pendingCheckpoint = checkpoints.count
    }
}

/**
 * Puntos de control del recorrido de fragmentos, compartidos por todas las pistas: dónde
 * retomar, el tiempo de decodificación acumulado de cada pista (para los `traf` sin `tfdt`), y
 * cuántas muestras de cada una van ya, con el DTS de la siguiente.
 */
internal class FragmentCheckpoints(private val trackCount: Int) {
    var count = 0
        private set
    private var positions = LongArray(16)
    private var ticks = LongArray(16 * trackCount)
    private var indices = IntArray(16 * trackCount)
    private var dts = LongArray(16 * trackCount)

    fun record(position: Long, decodeTicks: LongArray, stats: Array<FragmentStats>) {
        if (count == positions.size) {
            positions = positions.copyOf(count * 2)
            ticks = ticks.copyOf(count * 2 * trackCount)
            indices = indices.copyOf(count * 2 * trackCount)
            dts = dts.copyOf(count * 2 * trackCount)
        }
        positions[count] = position
        for (j in 0 until trackCount) {
            ticks[count * trackCount + j] = decodeTicks[j]
            indices[count * trackCount + j] = stats[j].count
            stats[j].countAtCheckpoint = stats[j].count
        }
        count++
    }

    fun position(c: Int): Long = positions[c]
    fun ticks(c: Int, slot: Int): Long = ticks[c * trackCount + slot]
    fun index(c: Int, slot: Int): Int = indices[c * trackCount + slot]
    fun dts(c: Int, slot: Int): Long = dts[c * trackCount + slot]

    fun setDts(c: Int, slot: Int, value: Long) {
        dts[c * trackCount + slot] = value
    }

    /** Suelta la holgura de los arrays, que crecen duplicando, cuando ya no se añaden puntos. */
    fun trim() {
        positions = positions.copyOf(count)
        ticks = ticks.copyOf(count * trackCount)
        indices = indices.copyOf(count * trackCount)
        dts = dts.copyOf(count * trackCount)
    }
}

/** Las muestras de una pista de fMP4, la [slot]-ésima de [FragmentWalker.tracks]. */
internal class FragmentSamples(
    private val walker: FragmentWalker,
    val slot: Int,
    stats: FragmentStats,
    val checkpoints: FragmentCheckpoints,
) : TrackSamples() {
    override val size: Int = stats.count
    override val dtsMonotonic: Boolean = stats.monotonic
    override val checkpointCount: Int get() = checkpoints.count
    override fun checkpointIndex(c: Int): Int = checkpoints.index(c, slot)
    override fun checkpointDtsUs(c: Int): Long = checkpoints.dts(c, slot)
    override fun cursor(): SampleCursor = FragmentCursor(walker, this).also { it.moveTo(0) }

    /** El último punto de control que no ha pasado de la muestra [target]. */
    fun checkpointFor(target: Int): Int {
        var low = 0
        var high = checkpoints.count
        while (low < high) {
            val mid = (low + high) ushr 1
            if (checkpoints.index(mid, slot) <= target) low = mid + 1 else high = mid
        }
        return low - 1
    }
}

/**
 * Cursor de una pista de fMP4: retoma el recorrido de fragmentos desde un punto de control y
 * guarda en [buffer] una **ventana** de las muestras de su pista —las de un `moof`, o
 * [WINDOW] de ellas si trae más— y en [previous] la anterior. Parsea los fragmentos completos
 * —todas las pistas— porque el tiempo de un `traf` sin `tfdt` depende de lo anterior, pero solo
 * se queda con lo suyo.
 *
 * Guardar también la ventana anterior es lo que deja volver una muestra atrás sin repetir el
 * recorrido. Los fragmentos se cortan en los keyframes, así que el paquete de audio que suena en
 * un keyframe suele ser el último del fragmento anterior: para encontrarlo, la búsqueda llega al
 * final de ese fragmento, carga el siguiente para ver que se ha pasado y retrocede una. Con un
 * solo fragmento, retroceder obligaba a volver al punto de control y parsear otra vez hasta tres
 * fragmentos, y cada búsqueda reservaba el doble de memoria que en la 3.1.0.
 *
 * La ventana existe porque un `moof` no tiene por qué ser pequeño: FFmpeg escribe un fMP4 de
 * solo audio en un único `moof`, y uno manipulado puede declarar millones de muestras en él. El
 * búfer guardaba el fragmento entero —37 bytes por muestra, unos 740 MB con un `trun` de 16 bytes
 * en un archivo de 20 MB—, y ahora no pasa de [WINDOW] muestras. Un `moof` más grande se lee por
 * ventanas: cada carga vuelve a parsearlo desde su inicio, con el tiempo acumulado que tenía
 * entonces, y salta las muestras de la pista que ya entregaron las anteriores; para ir lejos dentro
 * de él, salta directamente a la ventana del destino.
 */
internal class FragmentCursor(private val walker: FragmentWalker, private val owner: FragmentSamples) : SampleCursor() {
    private val state = WalkState(walker.trackCount, warn = null)

    /** El tiempo acumulado al empezar el `moof` en curso, para volver a parsearlo. */
    private val moofTicks = LongArray(walker.trackCount)
    private var buffer = SampleBuffer()
    private var previous = SampleBuffer()
    private var current = buffer
    private var bufferStart = 0
    private var previousStart = 0
    private var restored = false
    private var at = 0

    /** Si la siguiente carga vuelve al `moof` de la anterior, que no cupo en una ventana. */
    private var resuming = false

    /** Dónde empieza ese `moof`. */
    private var moofPosition = 0L

    /** Índice en la pista de su primera muestra en ese `moof`, o en el siguiente si no se vuelve. */
    private var moofBase = 0

    /** Muestras de la pista en ese `moof` que la siguiente carga se salta. */
    private var windowSkip = 0

    /** Muestras de la pista vistas en la carga en curso, saltadas o no. */
    private var seen = 0

    /** Si la carga en curso llenó la ventana y el `moof` trae más muestras de la pista. */
    private var more = false

    private val window = SampleSink { offset, size, ptsUs, dtsUs, key, durationUs ->
        val ordinal = seen++
        when {
            ordinal < windowSkip -> true
            buffer.size < WINDOW -> buffer.add(offset, size, ptsUs, dtsUs, key, durationUs)
            else -> {
                more = true
                state.halt = true
                false
            }
        }
    }

    override var index: Int = -1
        private set
    override val offset: Long get() = current.offset(at)
    override val size: Int get() = current.size(at)
    override val ptsUs: Long get() = current.ptsUs(at)
    override val dtsUs: Long get() = current.dtsUs(at)
    override val key: Boolean get() = current.isKey(at)
    override val durationUs: Long get() = current.durationUs(at)

    /**
     * Se conoce la siguiente dentro de la ventana en que está y, al final de la anterior, la
     * primera de la última cargada; al final de esta, no.
     */
    override val knowsNextDts: Boolean
        get() = at + 1 < current.size || (current === previous && buffer.size > 0)

    override val nextDtsUs: Long
        get() = if (at + 1 < current.size) current.dtsUs(at + 1) else buffer.dtsUs(0)

    override fun moveTo(target: Int) {
        require(target in 0 until owner.size) { "muestra $target fuera de 0..${owner.size - 1}" }
        val bufferEnd = bufferStart + buffer.size
        if (!restored || target < previousStart || target - bufferEnd >= TrackSamples.CHECKPOINT_INTERVAL) {
            restore(owner.checkpointFor(target))
        }
        while (bufferStart + buffer.size <= target) {
            check(loadNext(target)) { "el recorrido de fragmentos terminó antes de la muestra $target" }
        }
        if (target >= bufferStart) {
            current = buffer
            at = target - bufferStart
        } else {
            current = previous
            at = target - previousStart
        }
        index = target
    }

    private fun restore(c: Int) {
        val checkpoints = owner.checkpoints
        state.position = checkpoints.position(c)
        for (j in 0 until walker.trackCount) state.ticks[j] = checkpoints.ticks(c, j)
        moofBase = checkpoints.index(c, owner.slot)
        resuming = false
        bufferStart = moofBase
        buffer.clear()
        previous.clear()
        previousStart = bufferStart
        restored = true
    }

    /**
     * Carga la siguiente ventana con muestras de la pista, o la que contiene [target] si está más
     * allá dentro del mismo `moof`. La última cargada pasa a ser la anterior, y su búfer se
     * reutiliza para la nueva; si la nueva no va justo a continuación, la anterior se vacía.
     */
    private fun loadNext(target: Int): Boolean {
        val expectedStart = bufferStart + buffer.size
        val reused = previous
        previous = buffer
        previousStart = bufferStart
        buffer = reused
        buffer.clear()
        while (true) {
            if (resuming) {
                state.position = moofPosition
                System.arraycopy(moofTicks, 0, state.ticks, 0, moofTicks.size)
            } else {
                moofPosition = state.position
                System.arraycopy(state.ticks, 0, moofTicks, 0, moofTicks.size)
                windowSkip = 0
            }
            if (target - moofBase >= windowSkip + WINDOW) windowSkip = (target - moofBase) / WINDOW * WINDOW
            seen = 0
            more = false
            state.halt = false
            state.sinks[owner.slot] = window
            if (!walker.step(state)) return false
            val start = moofBase + windowSkip
            if (more) {
                resuming = true
                windowSkip += WINDOW
            } else {
                resuming = false
                moofBase += seen
            }
            if (buffer.size > 0) {
                if (start != expectedStart) {
                    previous.clear()
                    previousStart = start
                }
                bufferStart = start
                return true
            }
        }
    }

    companion object {
        /**
         * Muestras de una ventana: unos 150 KB de búfer. Un fragmento normal, de uno o dos
         * segundos, cabe entero de sobra, y entonces nada cambia respecto a guardarlo completo.
         */
        const val WINDOW: Int = 4096
    }
}
