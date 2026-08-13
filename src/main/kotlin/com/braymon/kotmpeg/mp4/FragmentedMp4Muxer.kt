package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.Muxer
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import java.io.File

/**
 * Muxer de MP4 fragmentado (fMP4/estilo CMAF) — el equivalente de
 * `-movflags frag_keyframe+empty_moov` de FFmpeg.
 *
 * Estructura: `ftyp` + `moov` vacío (con `mvex`/`trex`) seguido de pares `moof`+`mdat`, un
 * fragmento por GOP de vídeo (o por [fragmentDurationUs] cuando no hay vídeo), y un índice
 * de acceso aleatorio `mfra` final. Cada byte se escribe estrictamente en modo añadir —
 * nunca se retrocede — que es lo que hace que la salida sea:
 *
 *  - **a prueba de cortes**: si el proceso muere a mitad de grabación, todo hasta el
 *    último fragmento completo es reproducible (un MP4 plano con el `moov` al final pierde
 *    el archivo entero);
 *  - **streameable**: los fragmentos pueden empujarse a un socket/empaquetador DASH/HLS
 *    según se van cortando.
 *
 * Los B-frames se soportan con offsets de composición firmados de `trun` versión 1; los
 * tiempos se derivan por fragmento con el mismo esquema de PTS ordenados que [Mp4Muxer],
 * con tiempos de decodificación acumulados sin deriva entre fragmentos (`tfdt`).
 */
public class FragmentedMp4Muxer(
    private val out: SeekableOutput,
    /** Longitud objetivo de fragmento cuando no hay vídeo que corte por keyframes. */
    private val fragmentDurationUs: Long = 2_000_000,
    /**
     * Red de seguridad: longitud máxima de un fragmento incluso habiendo vídeo. Con un
     * intervalo de keyframes muy disperso (o un codificador que deja de emitir IDR) un
     * fragmento crecería sin límite; al llegar aquí se corta aunque no haya keyframe.
     */
    private val maxFragmentDurationUs: Long = 10_000_000,
    /** Red de seguridad equivalente por tamaño de las muestras retenidas del fragmento. */
    private val maxFragmentBytes: Long = 64L * 1024 * 1024,
) : Muxer {

    public constructor(file: File, fragmentDurationUs: Long = 2_000_000) :
        this(SeekableOutput(file), fragmentDurationUs)

    /**
     * Fecha de creación que se escribe en `mvhd`, `tkhd` y `mdhd`, en milisegundos desde la época
     * de Unix. `null` deja los campos a cero.
     *
     * **Hay que asignarla antes de [start]**, que es cuando se escribe el `moov`. Es una propiedad
     * y no un parámetro del constructor para no cambiar la firma de uno público por un metadato.
     */
    public var creationTimeMillis: Long? = System.currentTimeMillis()

    private companion object {
        const val MOVIE_TIMESCALE = 1000L
        const val VIDEO_TIMESCALE = 90000L
        const val SYNC_SAMPLE_FLAGS = 0x02000000L      // depends_on = 2 (I-frame)
        const val NON_SYNC_SAMPLE_FLAGS = 0x01010000L  // depends_on = 1, no-sync

        /** El origen de tiempos de MP4 es 1904-01-01 UTC y no 1970. */
        const val MP4_EPOCH_OFFSET_S = 2_082_844_800L

        /** Flags de `tkhd`: `track_enabled` (0x1) y `track_in_movie` (0x2). */
        const val TKHD_ENABLED_IN_MOVIE = 3
        const val TKHD_IN_MOVIE_ONLY = 2
    }

    /** La fecha en la escala de MP4, o 0 si no hay ninguna que escribir. */
    private fun mp4Time(): Long =
        creationTimeMillis?.let { (Math.floorDiv(it, 1000L) + MP4_EPOCH_OFFSET_S).coerceAtLeast(0L) } ?: 0L

    /** Microsegundos a ticks de la escala del `mvhd`, redondeando. */
    private fun toMovieTicks(us: Long): Long = Math.floorDiv(us * MOVIE_TIMESCALE + 500_000, 1_000_000)

    /** Cebado declarado por la pista, en µs; 0 si no es audio o no lo declara. */
    private fun primingUs(t: TrackState): Long {
        val info = t.info
        return if (info is TrackInfo.Audio && info.codecDelayUs > 0) info.codecDelayUs else 0L
    }

    /**
     * Cebado de la pista en ticks de su propia escala, redondeando: a 48 kHz los 21 333 µs de un
     * AAC-LC son 1023,98 muestras.
     */
    private fun primingTicks(t: TrackState): Long {
        val us = primingUs(t)
        if (us <= 0) return 0L
        return Math.floorDiv(us * t.timescale + 500_000, 1_000_000)
    }

    private class PendingSample(
        val data: ByteArray,
        val ptsUs: Long,
        val key: Boolean,
        val durationUs: Long,
    )

    private class TrackState(val info: TrackInfo) {
        val timescale: Long = when (info) {
            is TrackInfo.Video -> VIDEO_TIMESCALE
            is TrackInfo.Audio -> info.sampleRate.toLong()
        }
        val pending = ArrayList<PendingSample>()

        /** Tiempo de decodificación acumulado ya volcado, en µs (a ticks por fragmento). */
        var decodeCumUs = 0L
        var lastDurationUs = 0L

        fun toTicks(us: Long): Long = Math.floorDiv(us * timescale + 500_000, 1_000_000)
    }

    /**
     * Entrada del índice `tfra`: dónde está el `moof` y, dentro de él, la posición exacta de
     * la muestra clave — el `traf` de la pista de cues, el `trun` dentro de ese `traf` y el
     * ordinal de la muestra dentro del `trun`, los tres en base 1 como pide la
     * especificación.
     */
    private class TfraEntry(
        val timeTicks: Long,
        val moofOffset: Long,
        val trafNumber: Long,
        val trunNumber: Long,
        val sampleNumber: Long,
    )

    private val tracks = ArrayList<TrackState>()
    private val tfraEntries = ArrayList<TfraEntry>()
    private var cueTrackIndex = 0
    private var hasVideo = false

    private var started = false
    private var stopped = false
    private var sequenceNumber = 1L
    private var baseUs = Long.MIN_VALUE
    private var fragmentStartUs = 0L
    private var fragmentHasSamples = false
    private var fragmentBytes = 0L

    /**
     * Posiciones absolutas de los tres campos de duración reservados en la cabecera, que solo se
     * pueden rellenar al cerrar. `null` (o la lista vacía) significa que no se localizaron y que se
     * quedarán a cero, que es lo que había antes de existir este parche.
     */
    private var mehdDurationPos: Long? = null
    private var mvhdDurationPos: Long? = null
    private var tkhdDurationPos: List<Long> = emptyList()

    /** Paralela a [tracks]: `null` en las pistas que no llevan lista de edición. */
    private var elstDurationPos: List<Long?> = emptyList()

    override fun addTrack(track: TrackInfo): Int {
        check(!started) { "no se pueden añadir pistas después de start()" }
        if (track is TrackInfo.Video) {
            requireNotNull(track.codecPrivate) { "las pistas de vídeo MP4 requieren codecPrivate (avcC/hvcC)" }
        }
        val id = tracks.size + 1
        tracks.add(TrackState(track.withId(id)))
        return id
    }

    override fun start() {
        check(!started) { "ya iniciado" }
        check(tracks.isNotEmpty()) { "sin pistas" }
        started = true
        hasVideo = tracks.any { it.info is TrackInfo.Video }
        cueTrackIndex = tracks.indexOfFirst { it.info is TrackInfo.Video }.let { if (it < 0) 0 else it }

        val header = BoxBuilder()
        header.box("ftyp") {
            fourcc("iso5")
            u32(0x200)
            fourcc("iso5"); fourcc("iso6"); fourcc("mp41")
        }
        writeEmptyMoov(header)
        val bytes = header.toByteArray()
        locateDurationFields(bytes, out.position)
        out.write(bytes)
        out.flush()
    }

    override fun writePacket(packet: MediaPacket) {
        check(started) { "start() no llamado" }
        check(!stopped) { "muxer ya detenido" }
        val track = tracks.getOrNull(packet.trackId - 1)
            ?: throw IllegalArgumentException("pista desconocida ${packet.trackId}")

        if (baseUs == Long.MIN_VALUE) {
            baseUs = packet.ptsUs
            fragmentStartUs = 0
        }
        val pts = packet.ptsUs - baseUs

        val cutOnKeyFrame = hasVideo && track.info is TrackInfo.Video && packet.isKeyFrame
        val cutOnDuration = !hasVideo && pts - fragmentStartUs >= fragmentDurationUs
        val cutOnLimit = pts - fragmentStartUs >= maxFragmentDurationUs || fragmentBytes >= maxFragmentBytes
        if (fragmentHasSamples && (cutOnKeyFrame || cutOnDuration || cutOnLimit)) {
            flushFragment()
            fragmentStartUs = pts
        }

        track.pending.add(PendingSample(packet.data, pts, packet.isKeyFrame, packet.durationUs))
        fragmentHasSamples = true
        fragmentBytes += packet.data.size
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        if (!started) {
            out.close()
            return
        }
        try {
            if (fragmentHasSamples) flushFragment()
            writeMfra()
            patchDurations()
        } finally {
            out.close()
        }
    }

    override fun close(): Unit = stop()

    private class TrackFragment(
        val track: TrackState,
        val durationsTicks: LongArray,
        val cttsTicks: LongArray,
        val sampleFlags: LongArray,
        val sizes: IntArray,
        val baseDecodeTicks: Long,
        val data: List<ByteArray>,
        val firstKeyPtsTicks: Long?,
        /** Ordinal (base 1) de esa muestra clave dentro del `trun`, para el índice `tfra`. */
        val firstKeySampleNumber: Long?,
    )

    private fun prepareTrackFragment(track: TrackState): TrackFragment? {
        val samples = track.pending
        if (samples.isEmpty()) return null
        val n = samples.size

        val sorted = LongArray(n) { samples[it].ptsUs }.also { it.sort() }
        val durationsUs = LongArray(n)
        for (i in 0 until n - 1) durationsUs[i] = sorted[i + 1] - sorted[i]
        val last = samples[n - 1]
        durationsUs[n - 1] = when {
            last.durationUs > 0 -> last.durationUs
            n > 1 -> sorted[n - 1] - sorted[n - 2]
            track.lastDurationUs > 0 -> track.lastDurationUs
            track.info is TrackInfo.Audio -> 1024L * 1_000_000 / (track.info as TrackInfo.Audio).sampleRate
            else -> 33_333L
        }
        track.lastDurationUs = durationsUs[n - 1]

        val baseDecodeTicks = track.toTicks(track.decodeCumUs)
        val durationsTicks = LongArray(n)
        val ctts = LongArray(n)
        val flags = LongArray(n)
        val sizes = IntArray(n)
        var cumUs = track.decodeCumUs
        var dtsTicks = baseDecodeTicks
        var firstKeyPtsTicks: Long? = null
        var firstKeySampleNumber: Long? = null
        for (i in 0 until n) {
            val nextCumUs = cumUs + durationsUs[i]
            durationsTicks[i] = track.toTicks(nextCumUs) - track.toTicks(cumUs)
            ctts[i] = track.toTicks(samples[i].ptsUs) - dtsTicks
            flags[i] = if (samples[i].key) SYNC_SAMPLE_FLAGS else NON_SYNC_SAMPLE_FLAGS
            sizes[i] = samples[i].data.size
            if (samples[i].key && firstKeyPtsTicks == null) {
                firstKeyPtsTicks = track.toTicks(samples[i].ptsUs)
                firstKeySampleNumber = i + 1L        // base 1, como pide el tfra
            }
            dtsTicks += durationsTicks[i]
            cumUs = nextCumUs
        }
        track.decodeCumUs = cumUs

        val fragment = TrackFragment(
            track, durationsTicks, ctts, flags, sizes, baseDecodeTicks,
            samples.map { it.data }, firstKeyPtsTicks, firstKeySampleNumber,
        )
        track.pending.clear()
        return fragment
    }

    private fun flushFragment() {
        val fragments = tracks.mapNotNull { prepareTrackFragment(it) }
        if (fragments.isEmpty()) {
            fragmentHasSamples = false
            return
        }
        val moofOffset = out.position

        val totalData = fragments.sumOf { f -> f.data.sumOf { it.size.toLong() } }
        val useLargeSize = totalData + 8 > 0xFFFFFFFFL
        val mdatHeaderSize = if (useLargeSize) 16 else 8

        val moofSize = buildMoof(fragments, dataOffsetsKnown = false, moofSize = 0, mdatHeaderSize).size
        val moof = buildMoof(fragments, dataOffsetsKnown = true, moofSize = moofSize, mdatHeaderSize)
        out.write(moof)

        if (useLargeSize) {
            out.writeInt32(1)
            out.write("mdat".toByteArray(Charsets.US_ASCII))
            out.writeInt64(totalData + 16)
        } else {
            out.writeInt32((totalData + 8).toInt())
            out.write("mdat".toByteArray(Charsets.US_ASCII))
        }
        for (fragment in fragments) {
            for (data in fragment.data) out.write(data)
        }
        out.flush()

        val cueIndex = fragments.indexOfFirst { it.track === tracks[cueTrackIndex] }
        if (cueIndex >= 0) {
            val cueFragment = fragments[cueIndex]
            val timeTicks = cueFragment.firstKeyPtsTicks
            val sampleNumber = cueFragment.firstKeySampleNumber
            if (timeTicks != null && sampleNumber != null) {
                tfraEntries.add(
                    TfraEntry(
                        timeTicks.coerceAtLeast(0), moofOffset,
                        cueIndex + 1L, 1L, sampleNumber,
                    ),
                )
            }
        }

        sequenceNumber++
        fragmentHasSamples = false
        fragmentBytes = 0
    }

    private fun buildMoof(
        fragments: List<TrackFragment>,
        dataOffsetsKnown: Boolean,
        moofSize: Int,
        mdatHeaderSize: Int,
    ): ByteArray {
        val dataStart = LongArray(fragments.size)
        var running = 0L
        for ((i, fragment) in fragments.withIndex()) {
            dataStart[i] = running
            running += fragment.data.sumOf { it.size.toLong() }
        }

        val builder = BoxBuilder()
        builder.box("moof") {
            fullBox("mfhd", 0, 0) { u32(sequenceNumber) }
            for ((i, fragment) in fragments.withIndex()) {
                box("traf") {
                    fullBox("tfhd", 0, 0x020000) { u32(fragment.track.info.id) }
                    fullBox("tfdt", 1, 0) { u64(fragment.baseDecodeTicks) }
                    fullBox("trun", 1, 0x000F01) {
                        val n = fragment.sizes.size
                        u32(n)
                        val offset = if (dataOffsetsKnown) moofSize + mdatHeaderSize + dataStart[i] else 0L
                        u32(offset)
                        for (s in 0 until n) {
                            u32(fragment.durationsTicks[s])
                            u32(fragment.sizes[s])
                            u32(fragment.sampleFlags[s])
                            u32(fragment.cttsTicks[s].toInt()) // firmado (trun v1)
                        }
                    }
                }
            }
        }
        return builder.toByteArray()
    }

    /**
     * `moov` de cabecera, con las duraciones a cero porque en vivo todavía no se conocen.
     *
     * Los campos de duración de `mvhd`, `tkhd` y `mehd` son la excepción: se emiten a cero pero
     * [stop] los rellena con el total, ya conocido al cerrar. Es el **único** sitio en el que este
     * muxer vuelve atrás a escribir, y se puede permitir porque el archivo es válido con o sin ese
     * parche — si el proceso muere antes, se quedan a cero, que es exactamente la información que
     * había antes de existir esto.
     */
    private fun writeEmptyMoov(builder: BoxBuilder) {
        val created = mp4Time()
        builder.box("moov") {
            fullBox("mvhd", 0, 0) {
                u32(created); u32(created)           // tiempos de creación/modificación
                u32(MOVIE_TIMESCALE)
                u32(0)
                u32(0x00010000); u16(0x0100)
                u16(0); u32(0); u32(0)
                identityMatrix()
                zeros(24)
                u32(tracks.size + 1)
            }
            for (track in tracks) writeEmptyTrak(this, track)
            box("mvex") {
                fullBox("mehd", 1, 0) {
                    u64(0)                           // fragment_duration, parcheado en stop()
                }
                for (track in tracks) {
                    fullBox("trex", 0, 0) {
                        u32(track.info.id)
                        u32(1)                       // default_sample_description_index
                        u32(0); u32(0); u32(0)       // duración/tamaño/flags por defecto
                    }
                }
            }
        }
    }

    /**
     * Rellena con la duración total los tres campos que se reservaron en la cabecera.
     *
     * `mehd` es el que corresponde a un archivo fragmentado, y basta para quien lea la
     * especificación entera. Pero hay consumidores muy extendidos —el extractor MP4 de Android
     * entre ellos, que es el que alimenta el índice de medios del sistema— que solo miran
     * `tkhd.duration` y ni leen el `mehd` ni recorren los fragmentos: para ellos un archivo con ese
     * campo a cero **dura cero**, no muestran duración y la barra de reproducción sale vacía. El
     * `mvhd` va por coherencia, que es donde otros la buscan.
     *
     * Cada `tkhd` lleva la duración de su pista y el `mvhd` el máximo, igual que en [Mp4Muxer]. Van
     * en la escala del `mvhd`, también los de las pistas: `tkhd.duration` no usa la escala del
     * medio (ISO/IEC 14496-12 §8.3.2.3). El `segment_duration` de la lista de edición va detrás por
     * el mismo motivo, descontándole el cebado igual que hace el muxer plano.
     *
     * Si algún hueco no se localizó, ese campo se queda a cero en lugar de parchearse a ciegas.
     */
    private fun patchDurations() {
        val trackTicks = tracks.map { toMovieTicks(it.decodeCumUs) }
        val movieTicks = trackTicks.maxOrNull() ?: 0L

        mehdDurationPos?.let { out.patch(it, bigEndian(movieTicks, 8)) }
        mvhdDurationPos?.let { out.patch(it, bigEndian(clampToU32(movieTicks), 4)) }
        for ((i, pos) in tkhdDurationPos.withIndex()) {
            out.patch(pos, bigEndian(clampToU32(trackTicks[i]), 4))
        }
        for ((i, pos) in elstDurationPos.withIndex()) {
            if (pos == null) continue
            val presentation = toMovieTicks((tracks[i].decodeCumUs - primingUs(tracks[i])).coerceAtLeast(0L))
            out.patch(pos, bigEndian(clampToU32(presentation), 4))
        }
    }

    /**
     * `mvhd` y `tkhd` se emitieron en versión 0, con un hueco de 32 bits, y a estas alturas ya
     * están en disco: no se les puede cambiar la versión al cerrar. El tope son 49,7 días de
     * grabación continua; pasado eso vale más un campo saturado que uno truncado por arriba, que
     * daría una duración absurdamente corta.
     */
    private fun clampToU32(ticks: Long): Long = ticks.coerceIn(0L, 0xFFFFFFFFL)

    private fun bigEndian(value: Long, count: Int): ByteArray =
        ByteArray(count) { i -> (value shr (8 * (count - 1 - i))).toByte() }

    /**
     * Localiza dentro de [header] los campos de duración que [patchDurations] rellenará al cerrar,
     * en posiciones absolutas del archivo ([base] es dónde empieza la cabecera).
     *
     * Los `tkhd` salen en el mismo orden en que se escribieron los `trak`, que es el de [tracks];
     * si por lo que sea no cuadra el número, se descartan todos en vez de emparejar duraciones con
     * la pista equivocada.
     */
    private fun locateDurationFields(header: ByteArray, base: Long) {
        mehdDurationPos = boxPositions(header, listOf("moov", "mvex", "mehd"))
            .singleOrNull()?.let { base + it + 12 }
        mvhdDurationPos = boxPositions(header, listOf("moov", "mvhd"))
            .singleOrNull()?.let { base + it + 24 }
        val tkhds = boxPositions(header, listOf("moov", "trak", "tkhd"))
        tkhdDurationPos = if (tkhds.size == tracks.size) tkhds.map { base + it + 28 } else emptyList()

        val elsts = boxPositions(header, listOf("moov", "trak", "edts", "elst"))
        val hasEdit = tracks.map { primingTicks(it) > 0 }
        elstDurationPos = if (elsts.size == hasEdit.count { it }) {
            var next = 0
            hasEdit.map { if (it) base + elsts[next++] + 16 else null }
        } else {
            List(tracks.size) { null }
        }
    }

    /**
     * Desplazamientos dentro de [header] de todas las cajas que encajan en [path], recorriendo el
     * árbol en vez de buscar la firma de cuatro letras por el array: `mehd` o `tkhd` son cuatro
     * bytes que pueden aparecer dentro de un `codecPrivate` cualquiera, y una coincidencia haría
     * que [stop] parcheara datos de vídeo.
     *
     * No contempla la forma `largesize` porque no hace falta: aquí solo entra el `ftyp` + `moov`
     * que construye [BoxBuilder], que rechaza cualquier caja de más de 4 GiB.
     */
    private fun boxPositions(header: ByteArray, path: List<String>): List<Int> {
        val found = ArrayList<Int>()

        fun walk(from: Int, to: Int, level: Int) {
            var p = from
            while (p + 8 <= to) {
                val size = ((header[p].toInt() and 0xFF) shl 24) or
                    ((header[p + 1].toInt() and 0xFF) shl 16) or
                    ((header[p + 2].toInt() and 0xFF) shl 8) or
                    (header[p + 3].toInt() and 0xFF)
                if (size < 8 || p + size > to) return
                if (String(header, p + 4, 4, Charsets.US_ASCII) == path[level]) {
                    if (level == path.lastIndex) found += p else walk(p + 8, p + size, level + 1)
                }
                p += size
            }
        }
        walk(0, header.size, 0)
        return found
    }

    private fun BoxBuilder.identityMatrix() {
        u32(0x00010000); u32(0); u32(0)
        u32(0); u32(0x00010000); u32(0)
        u32(0); u32(0); u32(0x40000000)
    }

    private fun writeEmptyTrak(parent: BoxBuilder, t: TrackState) {
        val info = t.info
        val created = mp4Time()
        val tkhdFlags = if (info.default) TKHD_ENABLED_IN_MOVIE else TKHD_IN_MOVIE_ONLY
        parent.box("trak") {
            fullBox("tkhd", 0, tkhdFlags) {
                u32(created); u32(created)           // tiempos de creación/modificación
                u32(info.id)
                u32(0)
                u32(0)                               // duración desconocida
                u32(0); u32(0)
                u16(0); u16(0)
                u16(if (info is TrackInfo.Audio) 0x0100 else 0)
                u16(0)
                if (info is TrackInfo.Video) {
                    SampleEntries.writeDisplayMatrix(this, info.rotationDegrees, info.displayWidth, info.displayHeight)
                    u32(info.displayWidth.toLong() shl 16)
                    u32(info.displayHeight.toLong() shl 16)
                } else {
                    identityMatrix()
                    u32(0); u32(0)
                }
            }
            writeEdts(this, t)
            box("mdia") {
                fullBox("mdhd", 0, 0) {
                    u32(created); u32(created)       // tiempos de creación/modificación
                    u32(t.timescale)
                    u32(0)                           // duración desconocida (en vivo)
                    u16(0x55C4); u16(0)
                }
                fullBox("hdlr", 0, 0) {
                    u32(0)
                    fourcc(if (info is TrackInfo.Video) "vide" else "soun")
                    u32(0); u32(0); u32(0)
                    bytes("Kotmpeg".toByteArray(Charsets.US_ASCII)); u8(0) // null-terminated name
                }
                box("minf") {
                    if (info is TrackInfo.Video) {
                        fullBox("vmhd", 0, 1) { u16(0); u16(0); u16(0); u16(0) }
                    } else {
                        fullBox("smhd", 0, 0) { u16(0); u16(0) }
                    }
                    box("dinf") {
                        fullBox("dref", 0, 0) {
                            u32(1)
                            fullBox("url ", 0, 1) {}
                        }
                    }
                    box("stbl") {
                        fullBox("stsd", 0, 0) {
                            u32(1)
                            when (info) {
                                is TrackInfo.Video -> SampleEntries.writeVisual(this, info)
                                is TrackInfo.Audio -> SampleEntries.writeAudio(this, info)
                            }
                        }
                        fullBox("stts", 0, 0) { u32(0) }
                        fullBox("stsc", 0, 0) { u32(0) }
                        fullBox("stsz", 0, 0) { u32(0); u32(0) }
                        fullBox("stco", 0, 0) { u32(0) }
                    }
                }
            }
            writeTrackName(this, info)
        }
    }

    /**
     * Lista de edición que compensa el **cebado del codificador**: un códec con solapamiento de
     * ventanas no emite su primer paquete hasta haber consumido más muestras de las que ese
     * paquete representa, y sin saltarlas el audio se reproduce adelantado respecto al vídeo —
     * unos 21 ms con AAC-LC a 48 kHz.
     *
     * `segment_duration` sale a cero porque en vivo la duración no se conoce al escribir el `moov`,
     * y lo rellena [patchDurations] al cerrar. Dejarlo a cero contradiría al `tkhd`: la duración de
     * una pista es la suma de sus ediciones (ISO/IEC 14496-12 §8.6.6), así que un lector que
     * hiciera esa suma volvería a ver una pista de duración cero por mucho que el `tkhd` diga otra
     * cosa.
     */
    private fun writeEdts(parent: BoxBuilder, t: TrackState) {
        val priming = primingTicks(t)
        if (priming <= 0) return
        parent.box("edts") {
            fullBox("elst", 0, 0) {
                u32(1)                               // entry_count
                u32(0)                               // segment_duration: desconocida en vivo
                u32(priming)                         // media_time: el cebado que hay que saltar
                u16(1); u16(0)                       // media_rate 1.0
            }
        }
    }

    /**
     * Nombre legible de la pista, en `udta` > `name`. El texto del `hdlr` no vale: es el nombre
     * del manejador, sale igual en todas las pistas y ningún reproductor lo usa para distinguirlas.
     */
    private fun writeTrackName(parent: BoxBuilder, info: TrackInfo) {
        val name = info.name ?: return
        parent.box("udta") {
            box("name") {
                bytes(name.toByteArray(Charsets.UTF_8))
            }
        }
    }

    /** Índice de acceso aleatorio: permite buscar sin escanear todos los moof. */
    /**
     * Escribe el índice de acceso aleatorio (`mfra`/`tfra`/`mfro`).
     *
     * El `0x3F` declara los tres ordinales —fragmento, grupo y muestra— en campos de **4
     * bytes**. Antes iban de 1 byte y con el valor fijo `1`, así que el ordinal de muestra se
     * salía de rango en cuanto un fragmento pasaba de 255 muestras y el índice no apuntaba a
     * ninguna parte útil.
     */
    private fun writeMfra() {
        val builder = BoxBuilder()
        builder.box("mfra") {
            if (tfraEntries.isNotEmpty()) {
                fullBox("tfra", 1, 0) {
                    u32(tracks[cueTrackIndex].info.id)
                    u32(0x3F)
                    u32(tfraEntries.size)
                    for (entry in tfraEntries) {
                        u64(entry.timeTicks)
                        u64(entry.moofOffset)
                        u32(entry.trafNumber); u32(entry.trunNumber); u32(entry.sampleNumber)
                    }
                }
            }
            fullBox("mfro", 0, 0) {
                val tfraSize = if (tfraEntries.isEmpty()) 0 else 24 + tfraEntries.size * 28
                u32(8 + tfraSize + 16)
            }
        }
        out.write(builder.toByteArray())
    }
}
