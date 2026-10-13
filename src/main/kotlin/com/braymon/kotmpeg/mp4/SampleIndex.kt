package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.model.Timestamps
import java.util.BitSet

/**
 * Destino de las muestras que produce el parser: el índice al abrir, que solo las cuenta, o el
 * cursor al leer, que guarda las de su pista. Así las dos pasadas comparten el mismo código de
 * parseo y no pueden discrepar.
 */
internal fun interface SampleSink {
    fun add(offset: Long, size: Int, ptsUs: Long, dtsUs: Long, key: Boolean, durationUs: Long)
}

/**
 * Posición dentro de las muestras de una pista y los datos de la muestra en la que está. Solo se
 * mueve hacia una muestra que existe: `0 <= index < size` de su [TrackSamples].
 *
 * Los datos son propiedades calculadas y no campos que se rellenan al moverse: una búsqueda recorre
 * cientos de muestras mirando solo su DTS, y resolver en cada paso el PTS, la duración y el tamaño
 * —tres reescalados y una lectura de tabla— era la mitad del coste de buscar.
 */
internal abstract class SampleCursor {
    abstract val index: Int
    abstract val offset: Long

    /** Tamaño en bytes; -1 si no cabe en un array (más de 2 GiB), y `readPacket` la salta. */
    abstract val size: Int
    abstract val ptsUs: Long
    abstract val dtsUs: Long
    abstract val key: Boolean
    abstract val durationUs: Long

    abstract fun moveTo(target: Int)

    fun next(): Unit = moveTo(index + 1)
}

/**
 * Las muestras de una pista sin tenerlas en memoria: se recorren desde el archivo a medida que se
 * leen. Lo único que se guarda es un **punto de control** cada [CHECKPOINT_INTERVAL] muestras
 * —el estado del recorrido en ese punto—, desde el que un cursor puede retomar sin empezar por el
 * principio.
 *
 * Hasta la 3.0.0 se construía al abrir un mapa completo de 37 bytes por muestra: 28 MB para dos
 * horas a 60 fps con audio, y más de 140 MB para diez, que en un móvil es un `OutOfMemoryError`.
 * Ahora son unos 200 KB por hora en MP4 y menos en fMP4. El precio es que buscar ya no es una
 * bisección sobre arrays: se biseca entre los puntos de control y se recorren como mucho
 * [CHECKPOINT_INTERVAL] muestras.
 *
 * Las búsquedas dan **exactamente** el resultado de las de la 3.0.0; los nombres son los mismos.
 */
internal abstract class TrackSamples {
    abstract val size: Int

    /** Si los DTS nunca retroceden, que es lo que permite buscar por bisección. */
    abstract val dtsMonotonic: Boolean

    /** Cursor nuevo, colocado en la primera muestra. */
    abstract fun cursor(): SampleCursor

    protected abstract val checkpointCount: Int

    /** Índice de la primera muestra del punto de control [c]; nunca decrece y el primero es 0. */
    protected abstract fun checkpointIndex(c: Int): Int

    /** DTS de esa muestra, o `Long.MAX_VALUE` si la pista ya no tiene más. */
    protected abstract fun checkpointDtsUs(c: Int): Long

    /** Primer índice con `dts >= value` (o [size] si no hay); exige DTS monótonos. */
    fun firstIndexWithDtsAtLeast(value: Long, scan: SampleCursor): Int = firstIndex(scan) { it >= value }

    /** Primer índice con `dts > value` (o [size] si no hay); exige DTS monótonos. */
    fun firstIndexWithDtsAbove(value: Long, scan: SampleCursor): Int = firstIndex(scan) { it > value }

    private inline fun firstIndex(scan: SampleCursor, reached: (Long) -> Boolean): Int {
        var low = 0
        var high = checkpointCount
        while (low < high) {
            val mid = (low + high) ushr 1
            if (reached(checkpointDtsUs(mid))) high = mid else low = mid + 1
        }
        if (low == 0) return 0
        var index = checkpointIndex(low - 1)
        if (index >= size) return size
        scan.moveTo(index)
        while (!reached(scan.dtsUs)) {
            index++
            if (index >= size) return size
            scan.next()
        }
        return index
    }

    /** El recorrido lineal para cuando los DTS retroceden: primer índice con `dts >= value`. */
    fun firstIndexWithDtsAtLeastLinear(value: Long, scan: SampleCursor): Int {
        scan.moveTo(0)
        var index = 0
        while (scan.dtsUs < value) {
            index++
            if (index >= size) return size
            scan.next()
        }
        return index
    }

    /** El recorrido lineal del límite de búsqueda: avanza mientras `dts <= value`, sin pasar de la última. */
    fun lastIndexWithDtsAtMostLinear(value: Long, scan: SampleCursor): Int {
        scan.moveTo(0)
        var index = 0
        while (index < size - 1 && scan.dtsUs <= value) {
            index++
            scan.next()
        }
        return index
    }

    /**
     * Último keyframe con `pts <= maxPtsUs` entre las muestras `0..limit`, o 0 si no hay ninguno.
     * Recorre hacia atrás de punto de control en punto de control, que en un vídeo con un keyframe
     * cada pocos segundos es uno solo.
     */
    fun lastKeyAtOrBefore(limit: Int, maxPtsUs: Long, scan: SampleCursor): Int {
        var end = limit
        while (end >= 0) {
            var low = 0
            var high = checkpointCount
            while (low < high) {
                val mid = (low + high) ushr 1
                if (checkpointIndex(mid) <= end) low = mid + 1 else high = mid
            }
            val start = checkpointIndex(low - 1)
            scan.moveTo(start)
            var found = -1
            var index = start
            while (true) {
                if (scan.key && scan.ptsUs <= maxPtsUs) found = index
                if (index == end) break
                index++
                scan.next()
            }
            if (found >= 0) return found
            end = start - 1
        }
        return 0
    }

    companion object {
        /**
         * Muestras entre puntos de control. Con 256, cada punto cuesta menos de medio byte por
         * muestra —unos 350 KB para dos horas a 60 fps con audio— y una búsqueda recorre como mucho
         * unos cientos de muestras por pista: decenas de microsegundos. Con 1024 la memoria era la
         * cuarta parte, pero buscar costaba más de 100 µs.
         */
        const val CHECKPOINT_INTERVAL: Int = 256

        /**
         * Máximo de muestras por pista: el mismo que tenía el mapa completo de la 3.0.0, con el mismo
         * mensaje, para que una pista imposible se descarte igual que antes.
         */
        const val MAX_SAMPLES: Int = Int.MAX_VALUE - 4096
    }
}

/**
 * Tabla de entradas de tamaño fijo dentro del archivo (`stsz`, `stco`, `stts`…), leída por
 * páginas cuando se pide una entrada. Guarda una sola página, que es lo que permite recorrer una
 * tabla de millones de entradas con unos pocos KB. La página se reserva al primer uso y no más
 * grande que la tabla: muchas tienen una sola entrada, y otras ni existen en el archivo.
 */
internal class FileTable(
    private val input: SeekableInput,
    private val start: Long,
    val entries: Int,
    private val entrySize: Int,
) {
    private var page = EMPTY
    private var pageFirst = 0
    private var pageCount = 0

    /** Palabra de 32 bits sin signo número [word] de la entrada [entry]. */
    fun u32(entry: Int, word: Int): Long {
        val at = locate(entry) + word * 4
        return ((page[at].toLong() and 0xFF) shl 24) or ((page[at + 1].toLong() and 0xFF) shl 16) or
            ((page[at + 2].toLong() and 0xFF) shl 8) or (page[at + 3].toLong() and 0xFF)
    }

    /** La misma palabra, con signo. */
    fun s32(entry: Int, word: Int): Long = u32(entry, word).toInt().toLong()

    /** La entrada entera como un valor de 64 bits (`co64`). */
    fun u64(entry: Int): Long = (u32(entry, 0) shl 32) or u32(entry, 1)

    private fun locate(entry: Int): Int {
        if (entry < pageFirst || entry >= pageFirst + pageCount) {
            if (page.isEmpty()) page = ByteArray(minOf(PAGE_ENTRIES, entries) * entrySize)
            pageFirst = entry - entry % PAGE_ENTRIES
            pageCount = minOf(PAGE_ENTRIES, entries - pageFirst)
            input.readAt(start + pageFirst.toLong() * entrySize, page, 0, pageCount * entrySize)
        }
        return (entry - pageFirst) * entrySize
    }

    private companion object {
        private const val PAGE_ENTRIES = 512
        private val EMPTY = ByteArray(0)
    }
}

/**
 * Las muestras de una pista de un MP4 plano, recorriendo sus tablas `stbl` como lo hacía el mapa
 * completo de la 3.0.0, con las mismas reglas para los archivos dañados: el reparto en chunks de
 * `stsc` termina donde se acaban los chunks o las muestras, una muestra sin entrada en `stts` o
 * `ctts` toma tiempo y desfase cero, y sin `stss` todas son clave.
 */
internal class StblSamples(
    private val sampleCount: Int,
    private val constantSize: Long,
    private val sizes: FileTable?,
    private val chunkOffsets: FileTable,
    private val chunkOffsets64: Boolean,
    private val stsc: FileTable,
    private val stts: FileTable,
    private val ctts: FileTable,
    stss: FileTable?,
    private val mediaTimescale: Long,
    private val elstMediaTime: Long,
    private val elstEmptyDurationUs: Long,
) : TrackSamples() {

    override var size: Int = 0
        private set
    override var dtsMonotonic: Boolean = true
        private set

    private var checkpoints = LongArray(16 * FIELDS)
    override var checkpointCount: Int = 0
        private set

    private val keys: BitSet?

    init {
        val walker = Cursor()
        if (walker.start()) {
            var previousDts = Long.MIN_VALUE
            do {
                require(walker.index < MAX_SAMPLES) { "demasiadas muestras en una pista: ${walker.index}" }
                if (walker.index % CHECKPOINT_INTERVAL == 0) walker.saveCheckpoint()
                if (walker.dtsUs < previousDts) dtsMonotonic = false
                previousDts = walker.dtsUs
                size = walker.index + 1
            } while (walker.advance())
        }
        checkpoints = checkpoints.copyOf(checkpointCount * FIELDS)
        keys = stss?.let { table ->
            val flags = BitSet(size)
            for (k in 0 until table.entries) {
                val index = table.u32(k, 0) - 1
                if (index in 0 until size) flags.set(index.toInt())
            }
            flags
        }
    }

    /**
     * El primer `stts` con duración positiva, contando solo las muestras que declara `stsz`: es la
     * pista que se da como tasa de fotogramas, igual que en la 3.0.0.
     */
    fun firstPositiveDelta(): Long? {
        var assigned = 0L
        for (e in 0 until stts.entries) {
            if (assigned >= sampleCount) break
            val run = stts.u32(e, 0).coerceIn(0L, sampleCount - assigned)
            if (run > 0 && stts.u32(e, 1) > 0) return stts.u32(e, 1)
            assigned += run
        }
        return null
    }

    override fun cursor(): SampleCursor = Cursor().also { it.moveTo(0) }

    override fun checkpointIndex(c: Int): Int = c * CHECKPOINT_INTERVAL

    override fun checkpointDtsUs(c: Int): Long = dtsUs(checkpoints[c * FIELDS + DTS_TICK])

    private fun ticksToUs(t: Long): Long = Timestamps.rescaleFloor(t, 1_000_000, mediaTimescale)

    private fun dtsUs(dtsTick: Long): Long = ticksToUs(dtsTick - elstMediaTime) + elstEmptyDurationUs

    private fun sizeOf(index: Int): Long = sizes?.u32(index, 0) ?: constantSize

    private fun chunkOffset(chunk: Int): Long = if (chunkOffsets64) chunkOffsets.u64(chunk) else chunkOffsets.u32(chunk, 0)

    /**
     * El recorrido en sí: dos máquinas de estado sobre las tablas, con el estado completo en campos
     * para poder guardarlo en un punto de control y restaurarlo.
     *
     * - La de **tiempos** (`stts`, `ctts`) avanza en cada paso, porque buscar solo mira el DTS y el
     *   PTS de las muestras que recorre.
     * - La de **posiciones** (`stsc`, `stco`, `stsz`) solo se pone al día cuando alguien pide
     *   [offset], que es al leer el paquete. Así una búsqueda no toca esas tablas: leerlas del disco
     *   era la mayor parte de su coste, porque una búsqueda al azar no encuentra su página en caché.
     *
     * En la pasada al abrir avanzan juntas ([start], [advance]), porque es la de posiciones la que
     * decide cuántas muestras tienen sitio en algún chunk.
     */
    private inner class Cursor : SampleCursor() {
        override var index: Int = -1
            private set
        override val offset: Long
            get() {
                syncPosition()
                return position
            }
        override val size: Int
            get() = sizeOf(index).let { if (it > Int.MAX_VALUE) -1 else it.toInt() }
        override val ptsUs: Long
            get() = ticksToUs(dtsTick + sampleCtts - elstMediaTime) + elstEmptyDurationUs
        override val dtsUs: Long
            get() = dtsUs(dtsTick)
        override val key: Boolean
            get() = keys?.get(index) ?: true
        override val durationUs: Long
            get() = ticksToUs(sampleDelta)

        private var sttsNext = 0
        private var sttsLeft = 0L
        private var sttsDelta = 0L
        private var tick = 0L
        private var dtsTick = 0L
        private var sampleDelta = 0L
        private var cttsNext = 0
        private var cttsLeft = 0L
        private var cttsValue = 0L
        private var sampleCtts = 0L

        /** Muestra a la que corresponde el estado de posiciones; -1 si todavía no hay ninguno. */
        private var positionIndex = -1
        private var chunk = 0
        private var stscEntry = 0
        private var samplesInChunk = 0L
        private var inChunk = 0L
        private var position = 0L

        override fun moveTo(target: Int) {
            val samples = this@StblSamples.size
            require(target in 0 until samples) { "muestra $target fuera de 0..${samples - 1}" }
            if (index < 0 || target < index || target - index >= CHECKPOINT_INTERVAL) {
                restoreTimes(target / CHECKPOINT_INTERVAL)
            }
            while (index < target) {
                index++
                assignTimes()
            }
        }

        /** Coloca el recorrido en la primera muestra; false si la pista no tiene ninguna usable. */
        fun start(): Boolean {
            index = 0
            positionIndex = 0
            chunk = -1
            stscEntry = 0
            samplesInChunk = 0
            inChunk = 0
            if (sampleCount <= 0 || !nextChunkWithSamples()) return false
            assignTimes()
            return true
        }

        /** Pasa a la muestra siguiente; false si ya no hay más con sitio en algún chunk. */
        fun advance(): Boolean {
            position += sizeOf(positionIndex)
            inChunk++
            positionIndex++
            index++
            if (index >= sampleCount) return false
            if (inChunk >= samplesInChunk && !nextChunkWithSamples()) return false
            assignTimes()
            return true
        }

        /**
         * Lleva el estado de posiciones hasta [index]: desde donde esté si va por detrás en el mismo
         * tramo, o desde el punto de control si no. La muestra existe, así que siempre hay chunk.
         */
        private fun syncPosition() {
            if (positionIndex < 0 || positionIndex > index || index - positionIndex >= CHECKPOINT_INTERVAL) {
                restorePosition(index / CHECKPOINT_INTERVAL)
            }
            while (positionIndex < index) {
                position += sizeOf(positionIndex)
                inChunk++
                positionIndex++
                if (inChunk >= samplesInChunk) nextChunkWithSamples()
            }
        }

        private fun nextChunkWithSamples(): Boolean {
            do {
                chunk++
                if (chunk >= chunkOffsets.entries) return false
                while (stscEntry + 1 < stsc.entries && stsc.u32(stscEntry + 1, 0) <= chunk + 1) stscEntry++
                samplesInChunk = if (stsc.entries > 0 && stsc.u32(stscEntry, 0) <= chunk + 1) stsc.u32(stscEntry, 1) else 0L
                position = chunkOffset(chunk)
                inChunk = 0
            } while (inChunk >= samplesInChunk)
            return true
        }

        private fun assignTimes() {
            while (sttsLeft == 0L && sttsNext < stts.entries) {
                sttsLeft = stts.u32(sttsNext, 0)
                sttsDelta = stts.u32(sttsNext, 1)
                sttsNext++
            }
            if (sttsLeft > 0) {
                dtsTick = tick
                sampleDelta = sttsDelta
                tick += sttsDelta
                sttsLeft--
            } else {
                dtsTick = 0
                sampleDelta = 0
            }
            while (cttsLeft == 0L && cttsNext < ctts.entries) {
                cttsLeft = ctts.u32(cttsNext, 0)
                cttsValue = ctts.s32(cttsNext, 1)
                cttsNext++
            }
            if (cttsLeft > 0) {
                sampleCtts = cttsValue
                cttsLeft--
            } else {
                sampleCtts = 0
            }
        }

        /** Guarda los dos estados; en la pasada al abrir van juntos, en la misma muestra. */
        fun saveCheckpoint() {
            val at = checkpointCount * FIELDS
            if (at + FIELDS > checkpoints.size) checkpoints = checkpoints.copyOf(checkpoints.size * 2)
            checkpoints[at + CHUNK] = chunk.toLong()
            checkpoints[at + STSC_ENTRY] = stscEntry.toLong()
            checkpoints[at + SAMPLES_IN_CHUNK] = samplesInChunk
            checkpoints[at + IN_CHUNK] = inChunk
            checkpoints[at + OFFSET] = position
            checkpoints[at + STTS_NEXT] = sttsNext.toLong()
            checkpoints[at + STTS_LEFT] = sttsLeft
            checkpoints[at + STTS_DELTA] = sttsDelta
            checkpoints[at + TICK] = tick
            checkpoints[at + DTS_TICK] = dtsTick
            checkpoints[at + SAMPLE_DELTA] = sampleDelta
            checkpoints[at + CTTS_NEXT] = cttsNext.toLong()
            checkpoints[at + CTTS_LEFT] = cttsLeft
            checkpoints[at + CTTS_VALUE] = cttsValue
            checkpoints[at + SAMPLE_CTTS] = sampleCtts
            checkpointCount++
        }

        private fun restoreTimes(c: Int) {
            val at = c * FIELDS
            index = c * CHECKPOINT_INTERVAL
            sttsNext = checkpoints[at + STTS_NEXT].toInt()
            sttsLeft = checkpoints[at + STTS_LEFT]
            sttsDelta = checkpoints[at + STTS_DELTA]
            tick = checkpoints[at + TICK]
            dtsTick = checkpoints[at + DTS_TICK]
            sampleDelta = checkpoints[at + SAMPLE_DELTA]
            cttsNext = checkpoints[at + CTTS_NEXT].toInt()
            cttsLeft = checkpoints[at + CTTS_LEFT]
            cttsValue = checkpoints[at + CTTS_VALUE]
            sampleCtts = checkpoints[at + SAMPLE_CTTS]
        }

        private fun restorePosition(c: Int) {
            val at = c * FIELDS
            positionIndex = c * CHECKPOINT_INTERVAL
            chunk = checkpoints[at + CHUNK].toInt()
            stscEntry = checkpoints[at + STSC_ENTRY].toInt()
            samplesInChunk = checkpoints[at + SAMPLES_IN_CHUNK]
            inChunk = checkpoints[at + IN_CHUNK]
            position = checkpoints[at + OFFSET]
        }
    }

    private companion object {
        private const val CHUNK = 0
        private const val STSC_ENTRY = 1
        private const val SAMPLES_IN_CHUNK = 2
        private const val IN_CHUNK = 3
        private const val OFFSET = 4
        private const val STTS_NEXT = 5
        private const val STTS_LEFT = 6
        private const val STTS_DELTA = 7
        private const val TICK = 8
        private const val DTS_TICK = 9
        private const val SAMPLE_DELTA = 10
        private const val CTTS_NEXT = 11
        private const val CTTS_LEFT = 12
        private const val CTTS_VALUE = 13
        private const val SAMPLE_CTTS = 14

        /** Campos de un punto de control. */
        private const val FIELDS = 15
    }
}

/**
 * Muestras de una pista recogidas de un solo `moof`, el buffer de los cursores de fMP4. Crece
 * duplicando, porque un fragmento normal trae unas decenas o cientos de muestras por pista.
 */
internal class SampleBuffer : SampleSink {
    var size: Int = 0
        private set
    private var offsets = LongArray(64)
    private var sizes = IntArray(64)
    private var pts = LongArray(64)
    private var dts = LongArray(64)
    private var durations = LongArray(64)
    private var keys = BooleanArray(64)

    override fun add(offset: Long, size: Int, ptsUs: Long, dtsUs: Long, key: Boolean, durationUs: Long) {
        val i = this.size
        if (i == offsets.size) {
            val capacity = i * 2
            offsets = offsets.copyOf(capacity)
            sizes = sizes.copyOf(capacity)
            pts = pts.copyOf(capacity)
            dts = dts.copyOf(capacity)
            durations = durations.copyOf(capacity)
            keys = keys.copyOf(capacity)
        }
        offsets[i] = offset
        sizes[i] = size
        pts[i] = ptsUs
        dts[i] = dtsUs
        durations[i] = durationUs
        keys[i] = key
        this.size = i + 1
    }

    fun clear() {
        size = 0
    }

    fun offset(i: Int): Long = offsets[i]
    fun size(i: Int): Int = sizes[i]
    fun ptsUs(i: Int): Long = pts[i]
    fun dtsUs(i: Int): Long = dts[i]
    fun isKey(i: Int): Boolean = keys[i]
    fun durationUs(i: Int): Long = durations[i]
}
