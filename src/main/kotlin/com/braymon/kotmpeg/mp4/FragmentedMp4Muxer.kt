package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.Muxer
import com.braymon.kotmpeg.MuxerOptions
import com.braymon.kotmpeg.TimelineGate
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.Timestamps
import com.braymon.kotmpeg.model.TrackDefaults
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.TrackRequirements
import java.io.File

/**
 * Muxer de MP4 fragmentado (fMP4/estilo CMAF) — el equivalente de
 * `-movflags frag_keyframe+empty_moov` de FFmpeg.
 *
 * Estructura: `ftyp` + `moov` vacío (con `mvex`/`trex`) seguido de pares `moof`+`mdat`, un
 * fragmento por GOP de vídeo (o por `fragmentDurationUs` de [MuxerOptions] cuando no hay vídeo),
 * y un índice de acceso aleatorio `mfra` final. Los datos se escriben estrictamente en modo añadir —lo único
 * que se reescribe, tras cada fragmento, son los campos de duración de la cabecera—, que es lo que
 * hace que la salida sea:
 *
 *  - **a prueba de cortes**: si el proceso muere a mitad de grabación, todo hasta el
 *    último fragmento completo es reproducible, y la cabecera declara la duración hasta él (un MP4
 *    plano con el `moov` al final pierde el archivo entero). `Kotmpeg.repair` cierra el fragmento
 *    a medias y añade el `mfra`;
 *  - **streameable**: los fragmentos pueden empujarse a un socket/empaquetador DASH/HLS
 *    según se van cortando.
 *
 * Con `segmentIndex`, cada fragmento lleva delante un índice `sidx` por pista, como
 * `-movflags dash` de FFmpeg: es un segmento indexado que un reproductor o un empaquetador DASH/HLS
 * puede direccionar por rangos de bytes, sin dejar de ser un único archivo solo-añadir. Y
 * `onSegment` avisa de cada segmento según se escribe, con su posición y su duración, para montar
 * la lista de reproducción en vivo.
 *
 * Los B-frames se soportan con offsets de composición firmados de `trun` versión 1, como en
 * CMAF: el tiempo de decodificación de cada muestra sale de los PTS ordenados de su fragmento, con
 * tiempos acumulados sin deriva entre fragmentos (`tfdt`), y un B-frame queda con un offset
 * negativo de uno o dos fotogramas. Ese adelanto del DTS sobre el PTS está acotado a la
 * profundidad de reordenación (ver [FragmentTiming]), también cuando una pausa del vídeo de
 * frecuencia variable cae entre un B-frame y su referencia. Con `nonNegativeCompositionOffsets` se
 * escriben en cambio sin signo (`trun` versión 0) con una lista de edición que compensa el desfase,
 * que es lo que necesita FFmpeg para sincronizar exacto con GOP cerrado (ver [MuxerOptions]).
 *
 * **Con GOP abierto el límite es otro.** Cada fragmento empieza a decodificarse en el keyframe que
 * lo abre, en su propio PTS, y sus imágenes iniciales —las que se muestran antes que él y se
 * decodifican después— se adelantan tantos fotogramas como imágenes iniciales haya, que suele ser
 * más que la profundidad de reordenación: con cuatro B-frames a 30 fps, unos 133 ms, y FFmpeg
 * retrasa el vídeo entero lo que mida el mayor adelanto. Lo mismo pasa, a menor escala, en un corte
 * por tamaño o por duración a mitad de GOP, con los B-frames que quedan tras el corte y se muestran
 * antes que su referencia, ya decodificada en el fragmento anterior. El adelanto es el mismo en
 * todos los fragmentos, no se acumula; hasta la 4.0 sí, y crecía con el archivo (ver
 * `FragmentTiming.absorbOverlap`).
 *
 * Sin signo es peor: el desplazamiento de la lista de edición lo fija el primer fragmento, y las
 * imágenes iniciales que se adelantan más que él quedan con desfase cero, se presentan tarde y
 * desordenadas. Con fuentes de GOP abierto conviene, por tanto, el modo con signo, que es el de por
 * defecto.
 *
 * No se hace más porque decodificar las imágenes iniciales antes que su keyframe exige que el
 * fragmento anterior acabe de decodificarse antes, y eso solo se sabe al ver las imágenes iniciales
 * del siguiente: habría que retener cada fragmento hasta entonces, lo que cambia el camino de la
 * grabación en vivo —más memoria, y un fragmento de retraso en el archivo y en `onSegment`—.
 *
 * **Memoria**: las muestras de un fragmento se retienen hasta cerrarlo, porque el `moof` que las
 * describe va delante de ellas. `maxFragmentDurationUs` y `maxFragmentBytes` acotan esa retención;
 * en un móvil con poca RAM conviene bajar el segundo a 16 o 32 MB.
 *
 * **Origen**: la línea de tiempo empieza en el paquete más temprano de todas las pistas, como en
 * [Mp4Muxer]. Como aquí se escribe en vivo, los primeros paquetes esperan a que cada pista haya
 * entregado el suyo (ver `TimelineGate`), con los mismos dos límites de memoria.
 */
public class FragmentedMp4Muxer internal constructor(
    private val out: SeekableOutput,
    options: MuxerOptions,
) : Muxer {

    /** Escribe en [out], que el muxer cierra al terminar. */
    public constructor(out: SeekableOutput, options: MuxerOptions.() -> Unit = {}) :
        this(out, MuxerOptions().apply(options).validatedFor(out))

    /** Escribe en [file], truncándolo. Las opciones se validan antes de abrirlo. */
    public constructor(file: File, options: MuxerOptions.() -> Unit = {}) :
        this(MuxerOptions().apply(options).validated(), file)

    internal constructor(options: MuxerOptions, file: File) : this(SeekableOutput(file), options)

    private val fragmentDurationUs = options.fragmentDurationUs
    private val maxFragmentDurationUs = options.maxFragmentDurationUs
    private val maxFragmentBytes = options.maxFragmentBytes
    private val writingApp = options.writingApp

    /** La fecha de creación en la escala de MP4, o 0 si no hay ninguna que escribir. */
    private val created = MovieBoxes.mp4Time(options.creationTimeMillis)

    /**
     * Un `sidx` (Segment Index Box, ISO/IEC 14496-12 §8.16.3) delante de cada fragmento por cada
     * pista que tiene muestras en él, con la misma forma que `-movflags dash` de FFmpeg: versión 1,
     * una sola referencia al `moof`+`mdat` que sigue, su tiempo de presentación más temprano y su
     * duración en la escala de la pista, y si empieza en un punto de acceso. El `first_offset` de
     * cada uno salta los `sidx` que van detrás de él.
     *
     * Así cada fragmento es un **segmento indexado** de DASH, y un reproductor puede saltar de uno a
     * otro sin recorrer los `moof`. Todo se escribe hacia delante, porque el fragmento entero está
     * en memoria antes de escribirse: el archivo sigue siendo solo-añadir y a prueba de cortes.
     * Cuesta 52 bytes por pista y fragmento.
     *
     * No se declara la marca CMAF (`cmfc`): CMAF exige una sola pista por archivo, y este muxer
     * intercala todas en el mismo `moof`. Quien necesite pistas CMAF separadas puede escribir cada
     * pista con su propio muxer.
     */
    private val segmentIndex = options.segmentIndex

    /**
     * Se llama desde el hilo que escribe el paquete que cierra el fragmento, o desde [stop] para el
     * último. Si lanza, la excepción se ignora: un fallo de la app al anotar un segmento no puede
     * dejar la grabación a medias, que es lo que pasaría si cortara el cierre del archivo.
     */
    private val onSegment = options.onSegment

    /** Ver `MuxerOptions.nonNegativeCompositionOffsets`. */
    private val nonNegative = options.nonNegativeCompositionOffsets

    /**
     * Bytes de la cabecera (`ftyp` + `moov`), que es el segmento de inicialización y ocupa
     * `[0, initSegmentSize)` del archivo. Se entrega con cada `Mp4Segment`.
     */
    private var initSegmentSize = 0L

    /**
     * Si la cabecera ya está en el archivo. Se escribe en [start], salvo con [nonNegative], que
     * espera al primer fragmento para conocer el desfase de cada pista (ver [flushFragment]).
     */
    private var headerWritten = false

    /** [TrackInfo.default] resuelto por pista, en el orden de [tracks]; se fija en [start]. */
    private var defaultFlags = BooleanArray(0)

    private companion object {
        private const val SYNC_SAMPLE_FLAGS = 0x02000000L      // depends_on = 2 (I-frame)
        private const val NON_SYNC_SAMPLE_FLAGS = 0x01010000L  // depends_on = 1, no-sync

        /**
         * Lo que lleva cada `trun`: su `data_offset` y, por muestra, duración, tamaño, flags y desfase
         * de composición. El desfase va también en audio, a cero, para que todos los `trun` tengan la
         * misma forma.
         */
        private const val TRUN_FLAGS = FragmentFlags.TRUN_DATA_OFFSET or FragmentFlags.TRUN_SAMPLE_DURATION or
            FragmentFlags.TRUN_SAMPLE_SIZE or FragmentFlags.TRUN_SAMPLE_FLAGS or FragmentFlags.TRUN_SAMPLE_COMPOSITION_TIME_OFFSET

        /** Tamaño de un `sidx` de versión 1 con una sola referencia. */
        private const val SIDX_BYTES = 52L

        /**
         * Cabecera del `mdat` de cada fragmento, siempre con el tamaño de 32 bits: `FragmentLimits`
         * lo mantiene por debajo de 2 GiB, así que la forma `largesize` no hace falta.
         */
        private const val MDAT_HEADER_BYTES = 8

        /** Última palabra de la referencia del `sidx` con `starts_with_SAP` puesto y `SAP_type` 0. */
        private const val STARTS_WITH_SAP = 0x80000000L

        /**
         * Dónde va la duración desde el principio de un `mvhd` y de un `tkhd`, en las versiones 0 y 1:
         * detrás de la cabecera, la versión y los flags, las dos fechas —de 4 u 8 bytes— y la escala
         * del `mvhd` o el `track_ID` y el reservado del `tkhd`.
         */
        private const val MVHD_DURATION_V0 = 24
        private const val MVHD_DURATION_V1 = 32
        private const val TKHD_DURATION_V0 = 28
        private const val TKHD_DURATION_V1 = 36
    }

    private class PendingSample(
        val data: ByteArray,
        val ptsUs: Long,
        val key: Boolean,
        val durationUs: Long,
    )

    /**
     * Una pista registrada, con su entrada de `stsd` ya construida (ver [addTrack]) y su cebado en µs
     * y en tics de su escala, el de `MovieBoxes.primingUs`.
     */
    private class TrackState(val info: TrackInfo, val sampleEntry: ByteArray) {
        val timescale: Long = MovieBoxes.mediaTimescale(info)
        val primingUs: Long = MovieBoxes.primingUs(info, timescale)
        val primingTicks: Long = MovieBoxes.primingTicks(info, timescale)
        val audio: Boolean = info is TrackInfo.Audio
        val pending = ArrayList<PendingSample>()

        /**
         * Tiempo de decodificación acumulado ya volcado, en µs (a ticks por fragmento). Cada
         * fragmento lo empieza, como mínimo, en su primera muestra presentada: ver [decodeStarted].
         */
        var decodeCumUs = 0L

        /**
         * Si ya se preparó algún fragmento de la pista. El primero fija dónde empieza su tiempo de
         * decodificación: en su primera muestra presentada, que en una pista que arranca después
         * que las demás —un micrófono que entra medio segundo tarde— no es el origen. Los
         * siguientes siguen donde acabó el anterior, salvo que su primera muestra presentada llegue
         * después: la duración de la última muestra de un fragmento se estima si aún no ha llegado
         * la siguiente, y tras una pausa —un micrófono que se calla— se queda corta. Si llega
         * antes, el fragmento recupera la diferencia (ver `FragmentTiming.absorbOverlap`).
         *
         * Hasta ahora el tiempo empezaba en cero para todas las pistas y cada fragmento seguía
         * donde acabó el anterior, y lo que faltaba iba en el desfase de composición: el audio
         * quedaba con el DTS por detrás de su PTS, la búsqueda —que en audio va por DTS— lo
         * colocaba ese tiempo tarde, y la duración de la pista y de su lista de edición no lo
         * contaban.
         */
        var decodeStarted = false

        /**
         * Intervalo típico entre muestras de la pista, en µs: con él se estima la duración de la
         * última muestra de un fragmento que no trae nada mejor (ver `FragmentTiming.durations`).
         * 0 hasta el primer fragmento.
         */
        var typicalIntervalUs = 0L

        /**
         * Con desfases sin signo, lo que se suma a cada desfase de composición para que ninguno
         * sea negativo, en ticks: el mayor adelanto del primer fragmento. La lista de edición lo
         * descuenta al presentar. Se fija al escribir la cabecera; 0 en el modo firmado.
         */
        var compositionShiftTicks = 0L

        fun toTicks(us: Long): Long = Timestamps.rescaleRounded(us, timescale, 1_000_000)
    }

    private val tracks = ArrayList<TrackState>()
    private val tfraEntries = ArrayList<FragmentIndex.Entry>()
    private var cueTrackIndex = 0
    private var hasVideo = false

    private var started = false
    private var stopped = false
    private var sequenceNumber = 1L

    /**
     * Origen de la línea de tiempo en µs: el paquete más temprano de todas las pistas. Se fija
     * cuando [gate] lo permite, con el primer paquete de cada pista entregado o al agotar la espera.
     */
    private var baseUs = 0L

    /** Retención de arranque; `null` en cuanto el origen está fijado. */
    private var gate: TimelineGate? = null
    private var fragmentStartUs = 0L
    private var fragmentHasSamples = false
    private var fragmentBytes = 0L

    /**
     * Un campo de duración reservado en la cabecera: su posición absoluta en el archivo y si es de 8
     * bytes, que es como queda en una caja de versión 1.
     */
    private class DurationField(val position: Long, val wide: Boolean)

    /**
     * Los campos de duración reservados en la cabecera, que se rellenan tras cada fragmento. `null`
     * (o la lista vacía) significa que no se localizaron y que se quedarán a cero, que es lo que había
     * antes de existir este parche.
     */
    private var mehdDuration: DurationField? = null
    private var mvhdDuration: DurationField? = null
    private var tkhdDuration: List<DurationField> = emptyList()

    /** Paralela a [tracks]: `null` en las pistas que no llevan lista de edición. */
    private var elstDuration: List<DurationField?> = emptyList()

    /**
     * Registra una pista y construye ya su entrada de `stsd`, que la cabecera copia tal cual.
     *
     * Construirla aquí es lo que valida la pista, como en [Mp4Muxer]: un Opus de más de dos canales
     * sin tabla de mapeo, un AAC sin `codecPrivate` con un número de canales que el ASC no expresa o
     * un `codecPrivate` Opus que no es una `OpusHead` se rechazan con [IllegalArgumentException] antes
     * de grabar nada. Hasta ahora la entrada se construía al escribir la cabecera, y el error salía en
     * [start] o, con `nonNegativeCompositionOffsets`, al cerrar el primer fragmento, con la grabación
     * ya en marcha. Una pista rechazada no se registra y no consume id.
     */
    override fun addTrack(track: TrackInfo): Int {
        check(!started) { "no se pueden añadir pistas después de start()" }
        TrackRequirements.requireVideoCodecPrivate(track) { "las pistas de vídeo MP4 requieren codecPrivate (avcC/hvcC)" }
        val id = tracks.size + 1
        val info = track.withId(id)
        tracks.add(TrackState(info, SampleEntries.sampleEntry(info)))
        return id
    }

    override fun start() {
        check(!started) { "ya iniciado" }
        check(tracks.isNotEmpty()) { "sin pistas" }
        started = true
        hasVideo = tracks.any { it.info is TrackInfo.Video }
        cueTrackIndex = tracks.indexOfFirst { it.info is TrackInfo.Video }.let { if (it < 0) 0 else it }
        gate = TimelineGate(tracks.size, maxFragmentDurationUs, maxFragmentBytes)
        defaultFlags = TrackDefaults.resolve(tracks.map { it.info })
        if (!nonNegative) writeHeader()
    }

    /** Escribe `ftyp` + `moov`, el segmento de inicialización, y apunta dónde quedaron las duraciones. */
    private fun writeHeader() {
        headerWritten = true
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
        initSegmentSize = bytes.size.toLong()
    }

    /**
     * El audio se marca siempre como muestra de sincronización, igual que en [Mp4Muxer]: con
     * `isKeyFrame = false`, que es como los entrega más de un codificador, cada paquete de AAC u Opus salía
     * con el bit `sample_is_non_sync_sample` puesto y el fragmento entero quedaba sin un solo
     * punto de acceso para el reproductor.
     *
     * **Los datos del paquete se copian al recibirlos.** Este es el único muxer que no los escribe
     * en el momento —los retiene hasta cerrar el fragmento—, y antes guardaba una referencia al
     * array de quien llama. Una app que reutiliza su buffer entre paquetes, algo habitual al copiar
     * la salida de `MediaCodec`, acababa con todas las muestras del fragmento iguales a la última:
     * 49 de 50 paquetes dañados en la prueba, sin ningún error. La copia cuesta una por paquete y
     * su memoria la acota [maxFragmentBytes].
     *
     * Hasta que todas las pistas han entregado su primer paquete, los paquetes esperan en una
     * retención de arranque acotada por los mismos dos límites: el origen de la línea de tiempo
     * tiene que ser el paquete más temprano, y el primero que llega no siempre lo es. Antes se
     * tomaba el primero, y cuando el vídeo llegaba más tarde que el audio aunque se hubiera
     * capturado antes, el audio quedaba adelantado en el archivo.
     */
    override fun writePacket(packet: MediaPacket) {
        check(started) { "start() no llamado" }
        check(!stopped) { "muxer ya detenido" }
        val track = tracks.getOrNull(packet.trackId - 1)
            ?: throw IllegalArgumentException("pista desconocida ${packet.trackId}")
        val waiting = gate
        if (waiting != null) {
            if (waiting.hold(packet, packet.trackId - 1)) openTimeline(waiting)
            return
        }
        append(track, packet, packet.data.copyOf())
    }

    /** Fija el origen en el paquete retenido más temprano y pasa lo retenido a los fragmentos. */
    private fun openTimeline(waiting: TimelineGate) {
        gate = null
        baseUs = waiting.earliestPtsUs ?: 0L
        for (held in waiting.release()) append(tracks[held.trackId - 1], held, held.data)
    }

    /** Añade una muestra al fragmento en curso. [data] ya es propiedad del muxer: no se copia aquí. */
    private fun append(track: TrackState, packet: MediaPacket, data: ByteArray) {
        val pts = packet.ptsUs - baseUs

        val cutOnKeyFrame = hasVideo && track.info is TrackInfo.Video && packet.isKeyFrame
        val cutOnDuration = !hasVideo && pts - fragmentStartUs >= fragmentDurationUs
        val cutOnLimit = pts - fragmentStartUs >= maxFragmentDurationUs ||
            FragmentLimits.isFull(fragmentBytes, data.size, maxFragmentBytes)
        if (fragmentHasSamples && (cutOnKeyFrame || cutOnDuration || cutOnLimit)) {
            flushFragment(nextTrack = track, nextPtsUs = pts)
            fragmentStartUs = pts
        }

        val key = packet.isKeyFrame || track.info is TrackInfo.Audio
        track.pending.add(PendingSample(data, pts, key, packet.durationUs))
        fragmentHasSamples = true
        fragmentBytes += data.size
    }

    /**
     * Vuelca lo pendiente y escribe el `mfra`. Las duraciones de la cabecera ya están al día: las
     * parchea [flushFragment] tras cada fragmento, también el último, y sin ningún fragmento siguen
     * a cero, que es lo que se escribió.
     */
    override fun stop() {
        if (stopped) return
        stopped = true
        if (!started) {
            out.close()
            return
        }
        try {
            gate?.let { openTimeline(it) }
            if (fragmentHasSamples) flushFragment()
            if (!headerWritten) writeHeader()
            writeMfra()
        } finally {
            out.close()
        }
    }

    override fun close(): Unit = stop()

    private class TrackFragment(
        val track: TrackState,
        val durationsTicks: LongArray,
        /** Desfase de composición de cada muestra (PTS − DTS), con signo y sin el desplazamiento del modo sin signo. */
        val offsetsTicks: LongArray,
        val sampleFlags: LongArray,
        val sizes: IntArray,
        val baseDecodeTicks: Long,
        val data: List<ByteArray>,
        val firstKeyPtsTicks: Long?,
        /** Ordinal (base 1) de esa muestra clave dentro del `trun`, para el índice `tfra`. */
        val firstKeySampleNumber: Long?,
        /**
         * Lo que describe el `sidx` de la pista: su PTS más temprano y el tramo de decodificación que
         * cubre el fragmento, en ticks y en µs.
         */
        val earliestPtsTicks: Long,
        val spanTicks: Long,
        val earliestPtsUs: Long,
        val spanUs: Long,
        /** Si la primera muestra en orden de decodificación es un punto de acceso. */
        val startsWithSync: Boolean,
    )

    /**
     * Prepara las muestras retenidas de [track] para el fragmento que se cierra: sus duraciones y
     * dónde empieza a decodificarse salen de `FragmentTiming.durations`, a partir de los PTS y de
     * [nextPtsUs], el PTS del paquete de esta misma pista que provoca el corte (`null` si lo provoca
     * otra pista o es el último fragmento).
     *
     * **En audio no hay desfases de composición**, con la misma regla que `Mp4Muxer` (ver su
     * `TrackTiming`): el DTS es la secuencia de PTS, ordenada si alguno retrocede, y el PTS se iguala
     * a él. AAC y Opus se decodifican en el orden en que se presentan, así que un PTS que retrocede es
     * jitter de la fuente y no reordenación. Antes se trataba como vídeo, y ese paquete —o el primero
     * de un fragmento que empezaba antes de donde acabó el anterior, cuya última duración se había
     * estimado— salía con un desfase negativo, que el MP4 plano no escribe nunca. Con PTS monótonos y
     * sin ese solape, el resultado es el de siempre.
     */
    private fun prepareTrackFragment(track: TrackState, nextPtsUs: Long?): TrackFragment? {
        val samples = track.pending
        if (samples.isEmpty()) return null
        val n = samples.size

        val ptsUs = LongArray(n) { samples[it].ptsUs }
        val audio = track.audio
        val timing = FragmentTiming.durations(
            ptsUs,
            lastPacketDurationUs = samples[n - 1].durationUs,
            nextPtsUs = nextPtsUs,
            previousEndUs = if (track.decodeStarted) track.decodeCumUs else null,
            typicalIntervalUs = track.typicalIntervalUs,
            defaultIntervalUs = MovieBoxes.defaultSampleDurationUs(track.info),
            timescale = track.timescale,
            reorder = !audio,
        )
        val durationsUs = timing.durationsUs
        track.typicalIntervalUs = timing.typicalIntervalUs
        track.decodeStarted = true
        track.decodeCumUs = timing.decodeStartUs

        val baseDecodeTicks = track.toTicks(track.decodeCumUs)
        val durationsTicks = LongArray(n)
        val offsets = LongArray(n)
        val flags = LongArray(n)
        val sizes = IntArray(n)
        var cumUs = track.decodeCumUs
        var dtsTicks = baseDecodeTicks
        var firstKeyPtsTicks: Long? = null
        var firstKeySampleNumber: Long? = null
        for (i in 0 until n) {
            val nextCumUs = cumUs + durationsUs[i]
            durationsTicks[i] = track.toTicks(nextCumUs) - track.toTicks(cumUs)
            val ptsTicks = if (audio) dtsTicks else track.toTicks(samples[i].ptsUs)
            offsets[i] = ptsTicks - dtsTicks
            flags[i] = if (samples[i].key) SYNC_SAMPLE_FLAGS else NON_SYNC_SAMPLE_FLAGS
            sizes[i] = samples[i].data.size
            if (samples[i].key && firstKeyPtsTicks == null) {
                firstKeyPtsTicks = ptsTicks
                firstKeySampleNumber = i + 1L        // base 1, como pide el tfra
            }
            dtsTicks += durationsTicks[i]
            cumUs = nextCumUs
        }
        if (nonNegative && headerWritten) FragmentTiming.capCompositionLead(durationsTicks, offsets, track.compositionShiftTicks)
        val startUs = track.decodeCumUs
        track.decodeCumUs = cumUs

        val earliestPtsUs = if (audio) startUs else ptsUs.min()
        val fragment = TrackFragment(
            track, durationsTicks, offsets, flags, sizes, baseDecodeTicks,
            samples.map { it.data }, firstKeyPtsTicks, firstKeySampleNumber,
            earliestPtsTicks = if (audio) baseDecodeTicks else track.toTicks(earliestPtsUs),
            spanTicks = dtsTicks - baseDecodeTicks,
            earliestPtsUs = earliestPtsUs,
            spanUs = cumUs - startUs,
            startsWithSync = samples[0].key,
        )
        track.pending.clear()
        return fragment
    }

    /**
     * Escribe el fragmento en curso.
     *
     * Con [nonNegative], el primero escribe además la cabecera, que hasta ahora no podía: la lista
     * de edición de cada pista compensa el mayor adelanto del tiempo de decodificación sobre el de
     * presentación, y ese adelanto sale de las muestras de este fragmento. Es lo mismo que hace
     * FFmpeg con `-movflags delay_moov`.
     */
    private fun flushFragment(nextTrack: TrackState? = null, nextPtsUs: Long = 0) {
        val fragments = tracks.mapNotNull { prepareTrackFragment(it, if (it === nextTrack) nextPtsUs else null) }
        if (fragments.isEmpty()) {
            fragmentHasSamples = false
            return
        }
        if (!headerWritten) {
            for (fragment in fragments) {
                fragment.track.compositionShiftTicks = (-(fragment.offsetsTicks.minOrNull() ?: 0L)).coerceAtLeast(0L)
            }
            writeHeader()
        }
        val totalData = fragments.sumOf { f -> f.data.sumOf { it.size.toLong() } }

        val moof = buildMoof(fragments)
        val segmentOffset = out.position
        if (segmentIndex) out.write(buildSegmentIndexes(fragments, moof.size + MDAT_HEADER_BYTES + totalData))
        val moofOffset = out.position
        out.write(moof)

        out.writeInt32((totalData + MDAT_HEADER_BYTES).toInt())
        out.write("mdat".toByteArray(Charsets.US_ASCII))
        for (fragment in fragments) {
            for (data in fragment.data) out.write(data)
        }
        out.flush()
        patchDurations()

        val cueIndex = fragments.indexOfFirst { it.track === tracks[cueTrackIndex] }
        if (cueIndex >= 0) {
            val cueFragment = fragments[cueIndex]
            val timeTicks = cueFragment.firstKeyPtsTicks?.plus(cueFragment.track.compositionShiftTicks)
            val sampleNumber = cueFragment.firstKeySampleNumber
            if (timeTicks != null && sampleNumber != null) {
                tfraEntries.add(
                    FragmentIndex.Entry(
                        timeTicks.coerceAtLeast(0), moofOffset,
                        cueIndex + 1L, 1L, sampleNumber,
                    ),
                )
            }
        }

        val reference = fragments.firstOrNull { it.track === tracks[cueTrackIndex] } ?: fragments.first()
        val segment = Mp4Segment(
            sequenceNumber, segmentOffset, out.position - segmentOffset,
            reference.earliestPtsUs, reference.spanUs, reference.startsWithSync, initSegmentSize,
        )

        sequenceNumber++
        fragmentHasSamples = false
        fragmentBytes = 0
        onSegment?.let { notify -> runCatching { notify(segment) } }
    }

    /**
     * Los `sidx` de un fragmento, uno por pista con muestras en él y en el orden de sus `traf`.
     * Todos apuntan al mismo `moof`+`mdat` ([referencedSize] bytes): el `first_offset` de cada uno
     * es lo que ocupan los que van detrás, para que su referencia empiece justo en el `moof`.
     *
     * `earliest_presentation_time` no tiene signo, y un fragmento puede empezar antes del origen: un
     * B-frame que se muestra antes que el keyframe que abrió la línea de tiempo, como los fotogramas
     * iniciales de un GOP abierto al remuxear desde un punto de búsqueda. Ahí se escribe 0, igual
     * que el tiempo del `tfra`; escrito tal cual, el negativo se leía como un inicio a miles de
     * millones de años.
     *
     * Es un tiempo de composición, el mismo que dan el `tfdt` y el `trun`, así que con
     * `nonNegativeCompositionOffsets` lleva el mismo desplazamiento que ellos y que el `tfra`
     * ([TrackState.compositionShiftTicks]). Sin él, cada segmento se anunciaba uno o dos
     * fotogramas antes de lo que de verdad empieza.
     */
    private fun buildSegmentIndexes(fragments: List<TrackFragment>, referencedSize: Long): ByteArray {
        check(referencedSize <= 0x7FFFFFFFL) { "fragmento de $referencedSize bytes: el sidx solo describe hasta 2 GiB" }
        val builder = BoxBuilder()
        for ((i, fragment) in fragments.withIndex()) {
            builder.fullBox("sidx", 1, 0) {
                u32(fragment.track.info.id)                  // reference_ID
                u32(fragment.track.timescale)                // timescale
                u64((fragment.earliestPtsTicks + fragment.track.compositionShiftTicks).coerceAtLeast(0)) // earliest_presentation_time
                u64((fragments.size - 1 - i) * SIDX_BYTES)   // first_offset
                u16(0); u16(1)                               // reserved, reference_count
                u32(referencedSize)                          // reference_type 0 | referenced_size
                u32(FragmentDurations.clampToU32(fragment.spanTicks)) // subsegment_duration
                u32(if (fragment.startsWithSync) STARTS_WITH_SAP else 0L) // SAP: tipo 0, delta 0
            }
        }
        return builder.toByteArray()
    }

    /**
     * Construye el `moof` en una sola pasada. El `data_offset` de cada `trun` depende del tamaño
     * del propio `moof`, que no se conoce hasta terminarlo: se reserva a cero, se apunta dónde
     * quedó y se rellena al final, en vez de construir la caja entera dos veces.
     *
     * Cada `data_offset` cuenta desde el `moof` y es de 32 bits con signo; `FragmentLimits` corta los
     * fragmentos antes de que el de la última pista pueda pasar de ahí.
     */
    private fun buildMoof(fragments: List<TrackFragment>): ByteArray {
        val dataStart = LongArray(fragments.size)
        var running = 0L
        for ((i, fragment) in fragments.withIndex()) {
            dataStart[i] = running
            running += fragment.data.sumOf { it.size.toLong() }
        }
        val dataOffsetPositions = IntArray(fragments.size)

        val builder = BoxBuilder()
        builder.box("moof") {
            fullBox("mfhd", 0, 0) { u32(sequenceNumber) }
            for ((i, fragment) in fragments.withIndex()) {
                box("traf") {
                    fullBox("tfhd", 0, FragmentFlags.TFHD_DEFAULT_BASE_IS_MOOF) { u32(fragment.track.info.id) }
                    fullBox("tfdt", 1, 0) { u64(fragment.baseDecodeTicks) }
                    fullBox("trun", if (nonNegative) 0 else 1, TRUN_FLAGS) {
                        val n = fragment.sizes.size
                        u32(n)
                        dataOffsetPositions[i] = size
                        u32(0)                       // data_offset, se rellena al cerrar el moof
                        val shift = fragment.track.compositionShiftTicks
                        for (s in 0 until n) {
                            u32(fragment.durationsTicks[s])
                            u32(fragment.sizes[s])
                            u32(fragment.sampleFlags[s])
                            if (nonNegative) {
                                u32((fragment.offsetsTicks[s] + shift).coerceAtLeast(0L)) // sin signo (trun v0)
                            } else {
                                u32(fragment.offsetsTicks[s].toInt()) // firmado (trun v1)
                            }
                        }
                    }
                }
            }
        }
        val moofSize = builder.size.toLong()
        for (i in fragments.indices) {
            builder.patchU32(dataOffsetPositions[i], moofSize + MDAT_HEADER_BYTES + dataStart[i])
        }
        return builder.toByteArray()
    }

    /**
     * `moov` de cabecera, con las duraciones a cero porque en vivo todavía no se conocen.
     *
     * Los campos de duración de `mvhd`, `tkhd`, `mehd` y, en las pistas que la llevan, el
     * `segment_duration` de la lista de edición ([writeEdts]) son la excepción: se emiten a cero y
     * [patchDurations] los pone al día tras cada fragmento. Es el **único** sitio en el que este
     * muxer vuelve atrás a escribir, y se puede permitir porque el archivo es válido antes y
     * después de cada parche: si el proceso muere, declaran la duración hasta el último fragmento
     * completo.
     */
    private fun writeEmptyMoov(builder: BoxBuilder) {
        builder.box("moov") {
            MovieBoxes.writeMvhd(this, created, 0, tracks.size + 1)
            for ((i, track) in tracks.withIndex()) {
                MovieBoxes.writeTrak(
                    this, track.info, defaultFlags[i], created, 0, track.timescale, 0,
                    edts = { writeEdts(this, track) },
                    stbl = { writeEmptyStbl(this, track) },
                )
            }
            box("mvex") {
                fullBox("mehd", 1, 0) {
                    u64(0)                           // fragment_duration, parcheado tras cada fragmento
                }
                for (track in tracks) {
                    fullBox("trex", 0, 0) {
                        u32(track.info.id)
                        u32(1)                       // default_sample_description_index
                        u32(0); u32(0); u32(0)       // duración/tamaño/flags por defecto
                    }
                }
            }
            SampleEntries.writeEncoderTag(this, writingApp)
        }
    }

    /**
     * Rellena con la duración de lo ya escrito los campos que se reservaron en la cabecera. Se
     * llama tras volcar cada fragmento, no solo al cerrar: un archivo cortado declaraba duración
     * cero, y los extractores de Android no lo podían ni mostrar ni recorrer. Al cerrar se llama
     * antes de escribir el `mfra`, que así es lo último que se escribe —lo que `Kotmpeg.repair`
     * toma como señal de que el archivo se cerró—.
     *
     * `mehd` es el que corresponde a un archivo fragmentado, y basta para quien lea la
     * especificación entera. Pero hay consumidores muy extendidos —el extractor MP4 de Android
     * entre ellos, que es el que alimenta el índice de medios del sistema— que solo miran
     * `tkhd.duration` y ni leen el `mehd` ni recorren los fragmentos: para ellos un archivo con ese
     * campo a cero **dura cero**, no muestran duración y la barra de reproducción sale vacía. El
     * `mvhd` va por coherencia, que es donde otros la buscan.
     *
     * Qué lleva cada campo lo decide `FragmentDurations`, el mismo criterio con el que la reparación
     * cierra un archivo cortado.
     *
     * Si algún hueco no se localizó, ese campo se queda a cero en lugar de parchearse a ciegas.
     */
    private fun patchDurations() {
        val declared = FragmentDurations.declared(
            LongArray(tracks.size) { tracks[it].decodeCumUs },
            LongArray(tracks.size) { tracks[it].primingUs },
        ) { _, us -> MovieBoxes.toMovieTicks(us) }

        mehdDuration?.let { patch(it, declared.movie) }
        mvhdDuration?.let { patch(it, declared.movie) }
        for ((i, field) in tkhdDuration.withIndex()) patch(field, declared.tracks[i])
        for ((i, field) in elstDuration.withIndex()) field?.let { patch(it, declared.tracks[i]) }
    }

    private fun patch(field: DurationField, value: Long) {
        out.patch(field.position, FragmentDurations.durationBytes(value, field.wide))
    }

    /**
     * Localiza dentro de [header] los campos de duración que [patchDurations] rellenará tras cada
     * fragmento, en posiciones absolutas del archivo ([base] es dónde empieza la cabecera).
     *
     * Dónde está la duración de `mvhd` y `tkhd`, y si es de 4 bytes o de 8, depende de la versión de la
     * caja, que se lee de la propia cabecera: es la 1 cuando la fecha de creación no cabe en 32 bits
     * (ver `MovieBoxes.headerVersion`). El `mehd` es siempre de la versión 1 y el `elst`, de la 0.
     *
     * Los `tkhd` salen en el mismo orden en que se escribieron los `trak`, que es el de [tracks];
     * si por lo que sea no cuadra el número, se descartan todos en vez de emparejar duraciones con
     * la pista equivocada.
     */
    private fun locateDurationFields(header: ByteArray, base: Long) {
        fun field(at: Int, v0Offset: Int, v1Offset: Int): DurationField {
            val wide = header[at + 8].toInt() == 1
            return DurationField(base + at + if (wide) v1Offset else v0Offset, wide)
        }
        mehdDuration = boxPositions(header, listOf("moov", "mvex", "mehd"))
            .singleOrNull()?.let { DurationField(base + it + 12, wide = true) }
        mvhdDuration = boxPositions(header, listOf("moov", "mvhd"))
            .singleOrNull()?.let { field(it, MVHD_DURATION_V0, MVHD_DURATION_V1) }
        val tkhds = boxPositions(header, listOf("moov", "trak", "tkhd"))
        tkhdDuration = if (tkhds.size == tracks.size) tkhds.map { field(it, TKHD_DURATION_V0, TKHD_DURATION_V1) } else emptyList()

        val elsts = boxPositions(header, listOf("moov", "trak", "edts", "elst"))
        val hasEdit = tracks.map { editMediaTime(it) > 0 }
        elstDuration = if (elsts.size == hasEdit.count { it }) {
            var next = 0
            hasEdit.map { if (it) DurationField(base + elsts[next++] + 16, wide = false) else null }
        } else {
            List(tracks.size) { null }
        }
    }

    /**
     * Desplazamientos dentro de [header] de todas las cajas que encajan en [path], recorriendo el
     * árbol en vez de buscar la firma de cuatro letras por el array: `mehd` o `tkhd` son cuatro
     * bytes que pueden aparecer dentro de un `codecPrivate` cualquiera, y una coincidencia haría
     * que [patchDurations] parcheara datos de vídeo.
     *
     * No contempla la forma `largesize` porque no hace falta: aquí solo entra el `ftyp` + `moov`
     * que construye [BoxBuilder], que no admite nada que no quepa en un array, por debajo de 2 GiB.
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

    /**
     * La tabla de muestras del `moov` de cabecera: la entrada de muestra de [addTrack] y las tablas
     * vacías, porque las muestras van en los fragmentos.
     */
    private fun writeEmptyStbl(parent: BoxBuilder, t: TrackState) {
        parent.box("stbl") {
            fullBox("stsd", 0, 0) {
                u32(1)                               // entry_count
                bytes(t.sampleEntry)
            }
            fullBox("stts", 0, 0) { u32(0) }         // entry_count
            fullBox("stsc", 0, 0) { u32(0) }         // entry_count
            fullBox("stsz", 0, 0) { u32(0); u32(0) } // sample_size, sample_count
            fullBox("stco", 0, 0) { u32(0) }         // entry_count
        }
    }

    /**
     * Dónde empieza la presentación en la línea de tiempo del medio, en ticks: el cebado del
     * codificador más, con desfases sin signo, lo que se sumó a los desfases de composición.
     */
    private fun editMediaTime(t: TrackState): Long = t.primingTicks + t.compositionShiftTicks

    /**
     * Lista de edición que compensa el **cebado del codificador**: un códec con solapamiento de
     * ventanas no emite su primer paquete hasta haber consumido más muestras de las que ese
     * paquete representa, y sin saltarlas el audio se reproduce adelantado respecto al vídeo —
     * unos 21 ms con AAC-LC a 48 kHz. Con desfases sin signo compensa además el desfase que se
     * les sumó (ver [TrackState.compositionShiftTicks]).
     *
     * `segment_duration` sale a cero porque en vivo la duración no se conoce al escribir el `moov`,
     * y lo rellena [patchDurations] tras cada fragmento. Dejarlo a cero contradiría al `tkhd`: la
     * duración de una pista es la suma de sus ediciones (ISO/IEC 14496-12 §8.6.6), así que un lector
     * que hiciera esa suma volvería a ver una pista de duración cero por mucho que el `tkhd` diga
     * otra cosa.
     */
    private fun writeEdts(parent: BoxBuilder, t: TrackState) {
        val mediaTime = editMediaTime(t)
        if (mediaTime <= 0) return
        parent.box("edts") {
            fullBox("elst", 0, 0) {
                u32(1)                               // entry_count
                u32(0)                               // segment_duration: desconocida en vivo
                u32(mediaTime)                       // media_time: lo que hay que saltar
                u16(1); u16(0)                       // media_rate 1.0
            }
        }
    }

    /** Escribe el índice de acceso aleatorio (ver [FragmentIndex]). */
    private fun writeMfra() {
        out.write(FragmentIndex.mfra(tracks[cueTrackIndex].info.id.toLong(), tfraEntries))
    }
}
