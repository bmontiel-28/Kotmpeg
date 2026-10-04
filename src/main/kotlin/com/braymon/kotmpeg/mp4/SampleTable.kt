package com.braymon.kotmpeg.mp4

/**
 * Tabla de muestras en columnas de tipos primitivos, compartida por [Mp4Muxer] y [Mp4Demuxer].
 *
 * Sustituye a una lista con un objeto por muestra. Cada objeto costaba unos 64 bytes entre
 * cabecera, relleno y la referencia de la lista, más el trabajo del recolector al soltarlos;
 * aquí una muestra ocupa 37 bytes repartidos en seis columnas y no crea ningún objeto.
 *
 * Las columnas se guardan **en páginas** de tamaño fijo en vez de en un array que se duplica al
 * llenarse. Duplicar deja hasta la mitad de la capacidad sin usar y, en cada crecimiento, copia la
 * tabla entera con las dos versiones vivas a la vez: en el heap de un móvil, ese pico es justo el
 * que provoca un `OutOfMemoryError` al final de un archivo largo. Con páginas, crecer es reservar
 * una página más, sin copiar nada, y la holgura nunca pasa de una página.
 */
internal class SampleTable {
    private var offsets = arrayOfNulls<LongArray>(INITIAL_PAGES)
    private var sizes = arrayOfNulls<IntArray>(INITIAL_PAGES)
    private var ptsUs = arrayOfNulls<LongArray>(INITIAL_PAGES)
    private var dtsUs = arrayOfNulls<LongArray>(INITIAL_PAGES)
    private var durationsUs = arrayOfNulls<LongArray>(INITIAL_PAGES)
    private var keys = arrayOfNulls<BooleanArray>(INITIAL_PAGES)
    private var pages = 0

    var size: Int = 0
        private set

    fun add(offset: Long, size: Int, ptsUs: Long, dtsUs: Long, key: Boolean, durationUs: Long) {
        val i = this.size
        require(i < MAX_SAMPLES) { "demasiadas muestras en una pista: $i" }
        val page = i ushr PAGE_BITS
        if (page == pages) addPage()
        val slot = i and PAGE_MASK
        offsets[page]!![slot] = offset
        sizes[page]!![slot] = size
        this.ptsUs[page]!![slot] = ptsUs
        this.dtsUs[page]!![slot] = dtsUs
        keys[page]!![slot] = key
        durationsUs[page]!![slot] = durationUs
        this.size = i + 1
    }

    fun offset(i: Int): Long = offsets[i ushr PAGE_BITS]!![i and PAGE_MASK]
    fun size(i: Int): Int = sizes[i ushr PAGE_BITS]!![i and PAGE_MASK]
    fun ptsUs(i: Int): Long = ptsUs[i ushr PAGE_BITS]!![i and PAGE_MASK]
    fun dtsUs(i: Int): Long = dtsUs[i ushr PAGE_BITS]!![i and PAGE_MASK]
    fun isKey(i: Int): Boolean = keys[i ushr PAGE_BITS]!![i and PAGE_MASK]
    fun durationUs(i: Int): Long = durationsUs[i ushr PAGE_BITS]!![i and PAGE_MASK]

    fun isEmpty(): Boolean = size == 0

    /** Si los DTS nunca retroceden, lo que permite buscar por bisección. */
    fun dtsIsMonotonic(): Boolean {
        for (i in 1 until size) if (dtsUs(i) < dtsUs(i - 1)) return false
        return true
    }

    /** Primer índice con `dts >= value` (o [size] si no hay); exige DTS monótonos. */
    fun firstIndexWithDtsAtLeast(value: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (dtsUs(mid) < value) low = mid + 1 else high = mid
        }
        return low
    }

    /** Primer índice con `dts > value` (o [size] si no hay); exige DTS monótonos. */
    fun firstIndexWithDtsAbove(value: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (dtsUs(mid) <= value) low = mid + 1 else high = mid
        }
        return low
    }

    private fun addPage() {
        if (pages == offsets.size) {
            val capacity = pages * 2
            offsets = offsets.copyOf(capacity)
            sizes = sizes.copyOf(capacity)
            ptsUs = ptsUs.copyOf(capacity)
            dtsUs = dtsUs.copyOf(capacity)
            durationsUs = durationsUs.copyOf(capacity)
            keys = keys.copyOf(capacity)
        }
        offsets[pages] = LongArray(PAGE_SIZE)
        sizes[pages] = IntArray(PAGE_SIZE)
        ptsUs[pages] = LongArray(PAGE_SIZE)
        dtsUs[pages] = LongArray(PAGE_SIZE)
        durationsUs[pages] = LongArray(PAGE_SIZE)
        keys[pages] = BooleanArray(PAGE_SIZE)
        pages++
    }

    private companion object {
        /** 4096 muestras por página: unos 150 KB, poco más de un minuto de vídeo a 60 fps. */
        private const val PAGE_BITS = 12
        private const val PAGE_SIZE = 1 shl PAGE_BITS
        private const val PAGE_MASK = PAGE_SIZE - 1
        private const val INITIAL_PAGES = 4
        private const val MAX_SAMPLES = Int.MAX_VALUE - PAGE_SIZE
    }
}
