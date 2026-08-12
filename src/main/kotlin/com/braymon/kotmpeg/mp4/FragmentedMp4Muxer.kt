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

    /**
     * Cebado de la pista en ticks de su propia escala, redondeando: a 48 kHz los 21 333 µs de un
     * AAC-LC son 1023,98 muestras.
     */
    private fun primingTicks(t: TrackState): Long {
        val info = t.info
        if (info !is TrackInfo.Audio || info.codecDelayUs <= 0) return 0L
        return Math.floorDiv(info.codecDelayUs * t.timescale + 500_000, 1_000_000)
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
    private var mehdDurationPos: Long? = null

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
        mehdDurationPos = findMehdDurationPos(bytes, out.position)
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
            patchMehd()
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
     * El `mehd` es la excepción: se emite con un hueco de 8 bytes que [stop] rellena con la
     * duración total. Es el **único** sitio en el que este muxer vuelve atrás a escribir, y se
     * puede permitir porque el archivo es válido con o sin ese parche — si el proceso muere antes,
     * el `mehd` se queda a cero, que es exactamente la información que había antes de existir.
     * Sin él, un reproductor tiene que recorrerse todos los fragmentos para saber cuánto dura.
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
     * Rellena el `mehd` con la duración total, ya conocida al cerrar.
     *
     * Se toma el máximo de lo volcado por cada pista, que es el final real de la película, y se
     * pasa a la escala del `mvhd`. Si por lo que sea no se localizó el hueco, no se escribe nada:
     * un `mehd` a cero es lo mismo que no tenerlo, y desde luego mejor que parchear a ciegas.
     */
    private fun patchMehd() {
        val pos = mehdDurationPos ?: return
        val totalUs = tracks.maxOfOrNull { it.decodeCumUs } ?: 0L
        val ticks = Math.floorDiv(totalUs * MOVIE_TIMESCALE + 500_000, 1_000_000)
        val bytes = ByteArray(8) { i -> (ticks shr (56 - 8 * i)).toByte() }
        out.patch(pos, bytes)
    }

    /**
     * Posición absoluta del `fragment_duration` del `mehd` dentro de [header], recorriendo las
     * cajas en vez de buscar la firma por el array: `mehd` son cuatro bytes que podrían aparecer
     * dentro de un `codecPrivate` cualquiera, y una coincidencia haría que [stop] parcheara datos
     * de vídeo.
     */
    private fun findMehdDurationPos(header: ByteArray, base: Long): Long? {
        fun buscar(desde: Int, hasta: Int, ruta: List<String>): Int? {
            var p = desde
            while (p + 8 <= hasta) {
                val size = ((header[p].toInt() and 0xFF) shl 24) or
                    ((header[p + 1].toInt() and 0xFF) shl 16) or
                    ((header[p + 2].toInt() and 0xFF) shl 8) or
                    (header[p + 3].toInt() and 0xFF)
                if (size < 8 || p + size > hasta) return null
                val tipo = String(header, p + 4, 4, Charsets.US_ASCII)
                if (tipo == ruta.first()) {
                    return if (ruta.size == 1) p else buscar(p + 8, p + size, ruta.drop(1))
                }
                p += size
            }
            return null
        }
        val pos = buscar(0, header.size, listOf("moov", "mvex", "mehd")) ?: return null
        return base + pos + 12
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
     * `segment_duration` va a cero porque en vivo la duración no se conoce al escribir el `moov`;
     * es la misma convención que el `mvhd` de este muxer, y quien quiera la duración total la tiene
     * en el `mehd` que [stop] rellena.
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
