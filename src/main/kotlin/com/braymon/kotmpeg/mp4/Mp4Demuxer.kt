package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.Demuxer
import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.OpusConfig
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ColorInfo
import com.braymon.kotmpeg.model.HdrStaticInfo
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import java.io.File

/**
 * Demuxer MP4 (ISO BMFF) para archivos planos y fragmentados (fMP4).
 *
 * Archivos planos: construye el mapa completo de muestras desde las tablas `stbl`
 * (stts/ctts/stss/stsc/stsz/stco/co64), respetando las edit lists para los offsets de
 * presentación. Archivos fragmentados (`moov` vacío + pares `moof`/`mdat`): recorre cada
 * fragmento leyendo tfhd/tfdt/trun — incluidos los defaults de trex, el direccionamiento
 * default-base-is-moof y los offsets de composición firmados (versión 1). Los paquetes se
 * emiten intercalados en orden de decodificación entre pistas; las no soportadas se saltan.
 */
public class Mp4Demuxer(
    private val input: SeekableInput,
    /**
     * Avisos no fatales: pistas que no se pueden parsear y fragmentos ilegibles, que se
     * descartan para que el resto del archivo siga siendo usable.
     *
     * Es la **única** forma de enterarse de que algo se perdió: sin él, una pista ilegible
     * desaparece en silencio absoluto y el síntoma que llega —"mi vídeo se abre pero sin
     * audio"— se queda sin ningún diagnóstico posible.
     */
    private val onWarning: (String) -> Unit = {},
) : Demuxer {

    /**
     * Atajo desde una ruta. Acepta [onWarning] porque el README presenta instanciar el
     * demuxer directamente como una opción válida, y sin el parámetro aquí quien lo hiciera
     * se quedaba sin diagnóstico y sin ninguna pista de que ese canal existiera.
     */
    public constructor(file: File, onWarning: (String) -> Unit = {}) :
        this(SeekableInput(file), onWarning = onWarning)

    private class Box(val type: String, val dataStart: Long, val dataEnd: Long)

    private class SampleEntry(
        val offset: Long,
        val size: Int,
        val dtsUs: Long,
        val ptsUs: Long,
        val key: Boolean,
        val durationUs: Long,
    )

    private class ParsedTrack(val info: TrackInfo, val samples: List<SampleEntry>)

    /** Pista declarada en el moov pero sin muestras stbl: candidata a fragmentos. */
    private class FragmentShell(
        val trackId: Int,
        val handlerType: String,
        val desc: SampleDescription,
        val mediaTimescale: Long,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
    )

    private class TrexDefaults(val duration: Long, val size: Long, val flags: Long)

    private val parsedTracks = ArrayList<ParsedTrack>()
    private val cursors = HashMap<Int, Int>()
    private val fragmentShells = LinkedHashMap<Int, FragmentShell>()
    private val trexDefaults = HashMap<Int, TrexDefaults>()

    /** Tiempo de decodificación acumulado por pista, para los traf que no llevan tfdt. */
    private val trackDecodeTicks = HashMap<Int, Long>()

    /** Pistas ya anunciadas como ilegibles, para no repetir el aviso por cada muestra. */
    private val unreadableWarned = HashSet<Int>()

    override var durationUs: Long = 0
        private set

    override val tracks: List<TrackInfo> get() = parsedTracks.map { it.info }

    init {
        try {
            parse()
        } catch (t: Throwable) {
            runCatching { input.close() }
            throw t
        }
        for (t in parsedTracks) cursors[t.info.id] = 0
    }

    /**
     * Lee la cabecera de la siguiente caja, o null si no cabe o es imposible.
     *
     * El fin de la caja se calcula con [Math.addExact] porque solo la vía de `largesize` puede
     * desbordar el `Long`: es el patrón crudo de 64 bits del archivo, y un desbordamiento en
     * Kotlin no lanza, da la vuelta en silencio. Un valor elegido a propósito puede hacer que el
     * resultado caiga de vuelta dentro de la ventana válida, y entonces el parser seguiría
     * leyendo desde un offset inventado como si fuera una cabecera. Se trata como fin de
     * escaneo, igual que cualquier otra cabecera imposible.
     */
    private fun readBoxHeader(end: Long): Box? {
        if (input.position + 8 > end || input.remaining < 8) return null
        val start = input.position
        var size = input.readInt32().toLong() and 0xFFFFFFFFL
        val type = String(input.readBytes(4), Charsets.US_ASCII)
        var headerLen = 8L
        if (size == 1L) {
            if (input.position + 8 > end || input.remaining < 8) return null
            size = input.readInt64()
            headerLen = 16
        } else if (size == 0L) {
            size = end - start
        }
        if (size < headerLen) return null
        val dataEnd = try {
            Math.addExact(start, size)
        } catch (_: ArithmeticException) {
            return null
        }
        return Box(type, start + headerLen, dataEnd)
    }

    /**
     * Número de entradas que caben realmente en lo que queda de la caja. Las cuentas de
     * las tablas vienen del archivo: reservar `LongArray(n)` sin validarlas permite que
     * unos pocos bytes manipulados provoquen un OutOfMemoryError (que es `Error`, no
     * `Exception`, así que ni siquiera lo atrapa el try/catch por pista).
     */
    private fun boundedCount(declared: Int, bytesPerEntry: Int, end: Long): Int {
        val declaredUnsigned = declared.toLong() and 0xFFFFFFFFL
        if (declaredUnsigned == 0L) return 0
        val available = end - input.position
        if (available <= 0) return 0
        val fits = available / bytesPerEntry
        return minOf(declaredUnsigned, fits).toInt()
    }

    /**
     * Cota para las tablas que **no** guardan una entrada por muestra —`stsz` con tamaño
     * constante y `trun` sin campos por muestra—, donde el tamaño de la caja no dice nada
     * de la cuenta. El límite lo pone la física del archivo: cada muestra ocupa al menos
     * [bytesPerSampleInFile] bytes de datos, así que no puede haber más de
     * `length / bytesPerSampleInFile`.
     *
     * Se mide contra el archivo **completo**, no contra lo que queda por leer: con el `moov`
     * al final las muestras están *detrás* de la caja que se está parseando, y acotar por
     * el remanente truncaría pistas legítimas.
     */
    private fun physicalCount(declared: Int, bytesPerSampleInFile: Long): Int {
        val declaredUnsigned = declared.toLong() and 0xFFFFFFFFL
        val physicalMax = input.length / bytesPerSampleInFile.coerceAtLeast(1)
        return minOf(declaredUnsigned, physicalMax).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private inline fun scanChildren(start: Long, end: Long, handler: (Box) -> Unit) {
        input.position = start
        while (input.position + 8 <= end) {
            val boxStart = input.position
            val box = readBoxHeader(end) ?: break
            if (box.dataEnd <= boxStart || box.dataEnd > end) break // corrupto; evita bucles
            handler(box)
            input.position = box.dataEnd
        }
    }

    private fun parse() {
        var moov: Box? = null
        runCatching {
            scanChildren(0, input.length) { box ->
                if (box.type == "moov") moov = box
            }
        }
        val moovBox = moov ?: throw IllegalStateException("no se encontró la caja moov (¿archivo truncado?)")

        var movieTimescale = 1000L
        var movieDuration = 0L
        val trakBoxes = ArrayList<Box>()
        runCatching {
            scanChildren(moovBox.dataStart, moovBox.dataEnd) { box ->
                when (box.type) {
                    "mvex" -> scanChildren(box.dataStart, box.dataEnd) { child ->
                        if (child.type == "trex") {
                            input.position = child.dataStart
                            input.skip(4)
                            val trackId = input.readInt32()
                            input.skip(4) // default_sample_description_index
                            val duration = input.readInt32().toLong() and 0xFFFFFFFFL
                            val size = input.readInt32().toLong() and 0xFFFFFFFFL
                            val flags = input.readInt32().toLong() and 0xFFFFFFFFL
                            trexDefaults[trackId] = TrexDefaults(duration, size, flags)
                        }
                    }
                    "mvhd" -> {
                        input.position = box.dataStart
                        val version = input.readByte()
                        input.skip(3)
                        if (version == 1) {
                            input.skip(16)
                            movieTimescale = input.readInt32().toLong() and 0xFFFFFFFFL
                            movieDuration = input.readInt64()
                        } else {
                            input.skip(8)
                            movieTimescale = input.readInt32().toLong() and 0xFFFFFFFFL
                            movieDuration = input.readInt32().toLong() and 0xFFFFFFFFL
                        }
                    }
                    "trak" -> trakBoxes.add(box)
                }
            }
        }
        if (movieTimescale > 0) durationUs = movieDuration * 1_000_000 / movieTimescale

        for (trak in trakBoxes) {
            try {
                parseTrak(trak, movieTimescale)
            } catch (e: Exception) {
                runCatching { onWarning("pista descartada: no se pudo parsear su trak (${e.message})") }
            }
        }

        if (fragmentShells.isNotEmpty()) parseFragments()
    }

    /** Mapea la parte de rotación de la matriz tkhd de vuelta a 0/90/180/270 grados. */
    private fun rotationFromMatrix(m: IntArray): Int {
        val a = m[0] shr 16; val b = m[1] shr 16
        val c = m[3] shr 16; val d = m[4] shr 16
        return when {
            a == 0 && b == 1 && c == -1 && d == 0 -> 90
            a == -1 && b == 0 && c == 0 && d == -1 -> 180
            a == 0 && b == -1 && c == 1 && d == 0 -> 270
            else -> 0
        }
    }

    private fun parseTrak(trak: Box, movieTimescale: Long) {
        var trackId = 0
        var mediaTimescale = 0L
        var handlerType = ""
        var width = 0
        var height = 0
        var rotationDegrees = 0
        var stblBox: Box? = null
        var elstMediaTime = 0L
        var elstEmptyDurationUs = 0L
        var sampleDescription: SampleDescription? = null

        scanChildren(trak.dataStart, trak.dataEnd) { box ->
            when (box.type) {
                "tkhd" -> {
                    input.position = box.dataStart
                    val version = input.readByte()
                    input.skip(3)
                    input.skip(if (version == 1) 16L else 8L)
                    trackId = input.readInt32()
                    input.skip(4)
                    input.skip(if (version == 1) 8L else 4L)
                    input.skip(8 + 2 + 2 + 2 + 2)
                    val matrix = IntArray(9) { input.readInt32() }
                    rotationDegrees = rotationFromMatrix(matrix)
                    width = (input.readInt32() ushr 16)
                    height = (input.readInt32() ushr 16)
                }
                "edts" -> scanChildren(box.dataStart, box.dataEnd) { elst ->
                    if (elst.type == "elst") {
                        input.position = elst.dataStart
                        val version = input.readByte()
                        input.skip(3)
                        val count = input.readInt32()
                        repeat(count) {
                            val segDuration: Long
                            val mediaTime: Long
                            if (version == 1) {
                                segDuration = input.readInt64(); mediaTime = input.readInt64()
                            } else {
                                segDuration = input.readInt32().toLong() and 0xFFFFFFFFL
                                mediaTime = input.readInt32().toLong()
                            }
                            input.skip(4) // media_rate
                            if (mediaTime == -1L) {
                                elstEmptyDurationUs = segDuration * 1_000_000 / movieTimescale
                            } else if (elstMediaTime == 0L) {
                                elstMediaTime = mediaTime
                            }
                        }
                    }
                }
                "mdia" -> scanChildren(box.dataStart, box.dataEnd) { child ->
                    when (child.type) {
                        "mdhd" -> {
                            input.position = child.dataStart
                            val version = input.readByte()
                            input.skip(3)
                            input.skip(if (version == 1) 16L else 8L)
                            mediaTimescale = input.readInt32().toLong() and 0xFFFFFFFFL
                        }
                        "hdlr" -> {
                            input.position = child.dataStart
                            input.skip(8)
                            handlerType = String(input.readBytes(4), Charsets.US_ASCII)
                        }
                        "minf" -> scanChildren(child.dataStart, child.dataEnd) { minfChild ->
                            if (minfChild.type == "stbl") stblBox = minfChild
                        }
                    }
                }
            }
        }

        val stbl = stblBox ?: return
        if (mediaTimescale <= 0) return
        if (handlerType != "vide" && handlerType != "soun") return

        var sttsCounts = LongArray(0); var sttsDeltas = LongArray(0)
        var cttsCounts = LongArray(0); var cttsOffsets = LongArray(0)
        var stss: LongArray? = null
        var stscFirstChunk = LongArray(0); var stscSamplesPerChunk = LongArray(0)
        var sampleSizes = LongArray(0)
        var chunkOffsets = LongArray(0)

        scanChildren(stbl.dataStart, stbl.dataEnd) { box ->
            input.position = box.dataStart
            when (box.type) {
                "stsd" -> {
                    input.skip(4)
                    val count = input.readInt32()
                    if (count >= 1) sampleDescription = parseSampleDescription(box.dataEnd)
                }
                "stts" -> {
                    input.skip(4)
                    val n = boundedCount(input.readInt32(), 8, box.dataEnd)
                    sttsCounts = LongArray(n); sttsDeltas = LongArray(n)
                    for (i in 0 until n) {
                        sttsCounts[i] = input.readInt32().toLong() and 0xFFFFFFFFL
                        sttsDeltas[i] = input.readInt32().toLong() and 0xFFFFFFFFL
                    }
                }
                "ctts" -> {
                    input.skip(4)
                    val n = boundedCount(input.readInt32(), 8, box.dataEnd)
                    cttsCounts = LongArray(n); cttsOffsets = LongArray(n)
                    for (i in 0 until n) {
                        cttsCounts[i] = input.readInt32().toLong() and 0xFFFFFFFFL
                        cttsOffsets[i] = input.readInt32().toLong() // may be signed (v1)
                    }
                }
                "stss" -> {
                    input.skip(4)
                    val n = boundedCount(input.readInt32(), 4, box.dataEnd)
                    stss = LongArray(n) { input.readInt32().toLong() and 0xFFFFFFFFL }
                }
                "stsc" -> {
                    input.skip(4)
                    val n = boundedCount(input.readInt32(), 12, box.dataEnd)
                    stscFirstChunk = LongArray(n); stscSamplesPerChunk = LongArray(n)
                    for (i in 0 until n) {
                        stscFirstChunk[i] = input.readInt32().toLong() and 0xFFFFFFFFL
                        stscSamplesPerChunk[i] = input.readInt32().toLong() and 0xFFFFFFFFL
                        input.skip(4) // sample_description_index
                    }
                }
                "stsz" -> {
                    input.skip(4)
                    val constant = input.readInt32().toLong() and 0xFFFFFFFFL
                    val declared = input.readInt32()
                    sampleSizes = if (constant != 0L) {
                        LongArray(physicalCount(declared, constant)) { constant }
                    } else {
                        LongArray(boundedCount(declared, 4, box.dataEnd)) {
                            input.readInt32().toLong() and 0xFFFFFFFFL
                        }
                    }
                }
                "stco" -> {
                    input.skip(4)
                    val n = boundedCount(input.readInt32(), 4, box.dataEnd)
                    chunkOffsets = LongArray(n) { input.readInt32().toLong() and 0xFFFFFFFFL }
                }
                "co64" -> {
                    input.skip(4)
                    val n = boundedCount(input.readInt32(), 8, box.dataEnd)
                    chunkOffsets = LongArray(n) { input.readInt64() }
                }
            }
        }

        val desc = sampleDescription ?: run {
            runCatching {
                onWarning(
                    "pista $trackId ($handlerType) descartada: su formato no se pudo " +
                        "interpretar (códec no soportado o stsd ilegible)",
                )
            }
            return
        }
        val sampleCount = sampleSizes.size
        if (sampleCount == 0) {
            fragmentShells[trackId] =
                FragmentShell(trackId, handlerType, desc, mediaTimescale, width, height, rotationDegrees)
            return
        }

        val offsets = LongArray(sampleCount)
        var sampleIndex = 0
        for (chunk in chunkOffsets.indices) {
            var samplesInChunk = 0L
            for (e in stscFirstChunk.indices) {
                if (stscFirstChunk[e] <= chunk + 1) samplesInChunk = stscSamplesPerChunk[e] else break
            }
            var offset = chunkOffsets[chunk]
            var s = 0L
            while (s < samplesInChunk && sampleIndex < sampleCount) {
                offsets[sampleIndex] = offset
                offset += sampleSizes[sampleIndex]
                sampleIndex++
                s++
            }
            if (sampleIndex >= sampleCount) break
        }
        val usableSamples = sampleIndex
        if (usableSamples == 0) return

        val dtsTicks = LongArray(sampleCount)
        var tick = 0L
        var i = 0
        val lastDeltas = LongArray(sampleCount)
        for (e in sttsCounts.indices) {
            if (i >= sampleCount) break
            val runLength = sttsCounts[e].coerceIn(0L, (sampleCount - i).toLong()).toInt()
            repeat(runLength) {
                dtsTicks[i] = tick
                lastDeltas[i] = sttsDeltas[e]
                tick += sttsDeltas[e]
                i++
            }
        }
        val cttsPerSample = LongArray(sampleCount)
        if (cttsCounts.isNotEmpty()) {
            var idx = 0
            for (e in cttsCounts.indices) {
                if (idx >= sampleCount) break
                val runLength = cttsCounts[e].coerceIn(0L, (sampleCount - idx).toLong()).toInt()
                repeat(runLength) { cttsPerSample[idx++] = cttsOffsets[e] }
            }
        }

        val keySet = stss?.map { (it - 1).toInt() }?.toHashSet()

        fun ticksToUs(t: Long): Long = Math.floorDiv(t * 1_000_000, mediaTimescale)

        val samples = ArrayList<SampleEntry>(usableSamples)
        for (s in 0 until usableSamples) {
            val cts = dtsTicks[s] + cttsPerSample[s] - elstMediaTime
            samples.add(
                SampleEntry(
                    offset = offsets[s],
                    size = sampleSizes[s].toInt(),
                    dtsUs = ticksToUs(dtsTicks[s] - elstMediaTime) + elstEmptyDurationUs,
                    ptsUs = ticksToUs(cts) + elstEmptyDurationUs,
                    key = keySet?.contains(s) ?: true,
                    durationUs = ticksToUs(lastDeltas[s]),
                ),
            )
        }

        val frameRateHint = run {
            val delta = lastDeltas.firstOrNull { it > 0 }
            if (delta != null && delta > 0) mediaTimescale.toDouble() / delta else 0.0
        }
        val info = buildTrackInfo(handlerType, desc, trackId, width, height, frameRateHint, rotationDegrees)
        if (info != null) parsedTracks.add(ParsedTrack(info, samples))
    }

    /**
     * Convierte una pista ya parseada en [TrackInfo].
     *
     * El tamaño **codificado** lo manda el `SampleEntry`; el `tkhd` hace de respaldo suyo y es,
     * por definición (ISO/IEC 14496-12), el tamaño de **presentación**: aquel al que se escalan
     * las imágenes *antes* de aplicar la matriz. Sin propagarlo, un MP4 con píxeles no cuadrados
     * perdía su geometría al releerlo aunque `Mp4Muxer` sí la escriba.
     *
     * Al ser previo a la matriz no viene intercambiado por una rotación —así lo escriben FFmpeg
     * y el propio `Mp4Muxer`—, pero hay escritores que meten ahí la imagen ya rotada. Ese caso se
     * reconoce porque el par es exactamente la transpuesta del codificado: entonces no aporta
     * ninguna información y se ignora, que es lo que evita darle la vuelta a las dimensiones de
     * todo vídeo vertical.
     */
    private fun buildTrackInfo(
        handlerType: String,
        desc: SampleDescription,
        trackId: Int,
        tkhdWidth: Int,
        tkhdHeight: Int,
        frameRateHint: Double,
        rotationDegrees: Int = 0,
    ): TrackInfo? = when {
        handlerType == "vide" && desc.videoCodec != null -> {
            val width = if (desc.width > 0) desc.width else tkhdWidth
            val height = if (desc.height > 0) desc.height else tkhdHeight

            val rotatedQuarter = rotationDegrees == 90 || rotationDegrees == 270
            val transposed = rotatedQuarter && tkhdWidth == height && tkhdHeight == width
            val usable = tkhdWidth > 0 && tkhdHeight > 0 && !transposed

            TrackInfo.Video(
                id = trackId,
                codec = desc.videoCodec,
                width = width,
                height = height,
                displayWidth = if (usable) tkhdWidth else width,
                displayHeight = if (usable) tkhdHeight else height,
                frameRate = frameRateHint,
                rotationDegrees = rotationDegrees,
                color = desc.color,
                codecPrivate = desc.codecConfig,
            )
        }
        handlerType == "soun" && desc.audioCodec != null -> TrackInfo.Audio(
            id = trackId,
            codec = desc.audioCodec,
            sampleRate = desc.sampleRate,
            channelCount = desc.channels,
            codecDelayUs = desc.codecDelayUs,
            codecPrivate = desc.codecConfig,
        )
        else -> null
    }

    private fun parseFragments() {
        val samplesPerTrack = HashMap<Int, ArrayList<SampleEntry>>()
        for (id in fragmentShells.keys) samplesPerTrack[id] = ArrayList()

        input.position = 0
        while (input.position + 8 <= input.length) {
            val boxStart = input.position
            val box = readBoxHeader(input.length) ?: break
            if (box.dataEnd <= boxStart || box.dataEnd > input.length) break
            if (box.type == "moof") {
                try {
                    parseMoof(boxStart, box, samplesPerTrack)
                } catch (e: Exception) {
                    runCatching { onWarning("fragmento ilegible en el offset $boxStart (${e.message})") }
                }
            }
            input.position = box.dataEnd
        }

        for (shell in fragmentShells.values) {
            val samples = samplesPerTrack[shell.trackId] ?: continue
            if (samples.isEmpty()) continue
            val frameRateHint = samples.firstOrNull { it.durationUs > 0 }
                ?.let { 1_000_000.0 / it.durationUs } ?: 0.0
            val info = runCatching {
                buildTrackInfo(
                    shell.handlerType, shell.desc, shell.trackId, shell.width, shell.height,
                    frameRateHint, shell.rotationDegrees,
                )
            }.getOrNull() ?: continue
            parsedTracks.add(ParsedTrack(info, samples))
            val end = samples.last().let { it.dtsUs + it.durationUs }
            if (end > durationUs) durationUs = end
        }
    }

    private fun parseMoof(moofStart: Long, moof: Box, out: HashMap<Int, ArrayList<SampleEntry>>) {
        var chainedOffset = -1L
        scanChildren(moof.dataStart, moof.dataEnd) { child ->
            if (child.type == "traf") chainedOffset = parseTraf(moofStart, child, out, chainedOffset)
        }
    }

    /** Devuelve el offset de archivo donde terminan los datos de este traf. */
    private fun parseTraf(
        moofStart: Long,
        traf: Box,
        out: HashMap<Int, ArrayList<SampleEntry>>,
        chainedOffset: Long,
    ): Long {
        var trackId = 0
        var baseDataOffset: Long? = null
        var defaultDuration = 0L
        var defaultSize = 0L
        var defaultFlags = 0L
        var baseDecodeTicks = 0L
        var nextSampleOffset = -1L
        var dtsTicks = 0L
        var sawTfdt = false
        var defaultBaseIsMoof = false
        var shell: FragmentShell? = null
        var collected: ArrayList<SampleEntry>? = null

        scanChildren(traf.dataStart, traf.dataEnd) { child ->
            input.position = child.dataStart
            when (child.type) {
                "tfhd" -> {
                    val versionAndFlags = input.readInt32()
                    val flags = versionAndFlags and 0xFFFFFF
                    defaultBaseIsMoof = flags and 0x020000 != 0
                    trackId = input.readInt32()
                    if (flags and 0x01 != 0) baseDataOffset = input.readInt64()
                    if (flags and 0x02 != 0) input.skip(4)
                    val trex = trexDefaults[trackId]
                    defaultDuration = if (flags and 0x08 != 0) {
                        input.readInt32().toLong() and 0xFFFFFFFFL
                    } else trex?.duration ?: 0
                    defaultSize = if (flags and 0x10 != 0) {
                        input.readInt32().toLong() and 0xFFFFFFFFL
                    } else trex?.size ?: 0
                    defaultFlags = if (flags and 0x20 != 0) {
                        input.readInt32().toLong() and 0xFFFFFFFFL
                    } else trex?.flags ?: 0
                    shell = fragmentShells[trackId]
                    collected = out[trackId]
                    if (!sawTfdt) dtsTicks = trackDecodeTicks[trackId] ?: 0L
                }
                "tfdt" -> {
                    val version = input.readByte()
                    input.skip(3)
                    baseDecodeTicks = if (version == 1) input.readInt64()
                    else input.readInt32().toLong() and 0xFFFFFFFFL
                    dtsTicks = baseDecodeTicks
                    sawTfdt = true
                }
                "trun" -> {
                    val currentShell = shell
                    val sink = collected
                    if (currentShell != null && sink != null) {
                        val versionAndFlags = input.readInt32()
                        val version = versionAndFlags ushr 24
                        val flags = versionAndFlags and 0xFFFFFF
                        val declaredCount = input.readInt32()
                        val trafBase = baseDataOffset ?: when {
                            defaultBaseIsMoof -> moofStart
                            chainedOffset >= 0 -> chainedOffset
                            else -> moofStart
                        }
                        var offset = when {
                            flags and 0x01 != 0 -> trafBase + input.readInt32()
                            nextSampleOffset >= 0 -> nextSampleOffset
                            else -> trafBase
                        }
                        val firstSampleFlags: Long? = if (flags and 0x04 != 0) {
                            input.readInt32().toLong() and 0xFFFFFFFFL
                        } else null
                        val ts = currentShell.mediaTimescale
                        fun ticksToUs(t: Long): Long = Math.floorDiv(t * 1_000_000, ts)

                        var bytesPerSample = 0
                        if (flags and 0x100 != 0) bytesPerSample += 4
                        if (flags and 0x200 != 0) bytesPerSample += 4
                        if (flags and 0x400 != 0) bytesPerSample += 4
                        if (flags and 0x800 != 0) bytesPerSample += 4
                        val count = if (bytesPerSample > 0) {
                            boundedCount(declaredCount, bytesPerSample, child.dataEnd)
                        } else {
                            physicalCount(declaredCount, defaultSize)
                        }

                        repeat(count) { s ->
                            val duration = if (flags and 0x100 != 0) {
                                input.readInt32().toLong() and 0xFFFFFFFFL
                            } else defaultDuration
                            val size = if (flags and 0x200 != 0) {
                                input.readInt32().toLong() and 0xFFFFFFFFL
                            } else defaultSize
                            val sampleFlags = when {
                                flags and 0x400 != 0 -> input.readInt32().toLong() and 0xFFFFFFFFL
                                s == 0 && firstSampleFlags != null -> firstSampleFlags
                                else -> defaultFlags
                            }
                            val ctts = if (flags and 0x800 != 0) {
                                val raw = input.readInt32()
                                if (version == 0) raw.toLong() and 0xFFFFFFFFL else raw.toLong()
                            } else 0L
                            if (offset >= 0 && size >= 0 &&
                                offset <= input.length && size <= input.length - offset
                            ) {
                                sink.add(
                                    SampleEntry(
                                        offset = offset,
                                        size = size.toInt(),
                                        dtsUs = ticksToUs(dtsTicks),
                                        ptsUs = ticksToUs(dtsTicks + ctts),
                                        key = sampleFlags and 0x10000L == 0L, // !non_sync
                                        durationUs = ticksToUs(duration),
                                    ),
                                )
                            }
                            offset += size
                            dtsTicks += duration
                        }
                        nextSampleOffset = offset
                        trackDecodeTicks[trackId] = dtsTicks
                    }
                }
            }
        }
        return if (nextSampleOffset >= 0) nextSampleOffset else chainedOffset
    }

    private class SampleDescription(
        val videoCodec: VideoCodec? = null,
        val audioCodec: AudioCodec? = null,
        val codecConfig: ByteArray? = null,
        val width: Int = 0,
        val height: Int = 0,
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val codecDelayUs: Long = 0,
        val color: ColorInfo? = null,
    )

    /** Se llama con la entrada posicionada justo tras el contador de entradas del stsd. */
    private fun parseSampleDescription(end: Long): SampleDescription? {
        val start = input.position
        val entry = readBoxHeader(end) ?: return null
        if (entry.dataEnd <= start || entry.dataEnd > end) return null
        return when (entry.type) {
            "avc1", "avc3" -> parseVisual(entry, VideoCodec.H264, "avcC")
            "hvc1", "hev1" -> parseVisual(entry, VideoCodec.H265, "hvcC")
            "mp4a" -> parseMp4a(entry)
            "Opus" -> parseOpus(entry)
            else -> null
        }
    }

    private fun parseVisual(entry: Box, codec: VideoCodec, configBoxType: String): SampleDescription {
        input.position = entry.dataStart
        input.skip(6 + 2 + 2 + 2 + 12)
        val w = input.readBits(2).toInt()
        val h = input.readBits(2).toInt()
        input.skip(4 + 4 + 4 + 2 + 32 + 2 + 2)
        var config: ByteArray? = null
        var primaries = ColorInfo.UNSPECIFIED
        var transfer = ColorInfo.UNSPECIFIED
        var matrix = ColorInfo.UNSPECIFIED
        var fullRange = false
        var hasColr = false
        var mastering: DoubleArray? = null
        var maxCll = 0
        var maxFall = 0
        scanChildren(input.position, entry.dataEnd) { box ->
            input.position = box.dataStart
            when (box.type) {
                configBoxType -> config = input.readBytes((box.dataEnd - box.dataStart).toInt())
                "colr" -> {
                    val kind = String(input.readBytes(4), Charsets.US_ASCII)
                    if (kind == "nclx" || kind == "nclc") {
                        hasColr = true
                        primaries = input.readBits(2).toInt()
                        transfer = input.readBits(2).toInt()
                        matrix = input.readBits(2).toInt()
                        if (kind == "nclx") fullRange = input.readByte() and 0x80 != 0
                    }
                }
                "mdcv" -> {
                    val v = DoubleArray(8) { (input.readBits(2)) * 0.00002 }
                    val maxLum = (input.readInt32().toLong() and 0xFFFFFFFFL) * 0.0001
                    val minLum = (input.readInt32().toLong() and 0xFFFFFFFFL) * 0.0001
                    mastering = doubleArrayOf(v[4], v[5], v[0], v[1], v[2], v[3], v[6], v[7], maxLum, minLum)
                }
                "clli" -> {
                    maxCll = input.readBits(2).toInt()
                    maxFall = input.readBits(2).toInt()
                }
            }
        }
        val hdr = mastering?.let {
            HdrStaticInfo(
                it[0],
                it[1],
                it[2],
                it[3],
                it[4],
                it[5],
                it[6],
                it[7],
                it[8],
                it[9],
                maxCll,
                maxFall
            )
        } ?: if (maxCll > 0 || maxFall > 0) {
            HdrStaticInfo(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, maxCll, maxFall)
        } else null
        val color = if (hasColr || hdr != null) ColorInfo(
            primaries,
            transfer,
            matrix,
            fullRange,
            hdr
        ) else null
        return SampleDescription(videoCodec = codec, codecConfig = config, width = w, height = h, color = color)
    }

    private fun parseMp4a(entry: Box): SampleDescription {
        input.position = entry.dataStart
        input.skip(6 + 2 + 8)
        var channels = input.readBits(2).toInt()
        input.skip(2 + 2 + 2)
        var sampleRate = (input.readInt32() ushr 16)
        var asc: ByteArray? = null
        scanChildren(input.position, entry.dataEnd) { box ->
            if (box.type == "esds") {
                input.position = box.dataStart
                input.skip(4)
                asc = extractAscFromEsds(box.dataEnd)
            }
        }
        asc?.let {
            runCatching { AacConfig.parse(it) }.getOrNull()?.let { parsed ->
                sampleRate = parsed.sampleRate
                channels = parsed.channelCount
            }
        }
        return SampleDescription(
            audioCodec = AudioCodec.AAC, codecConfig = asc,
            sampleRate = sampleRate, channels = channels,
        )
    }

    private fun parseOpus(entry: Box): SampleDescription {
        input.position = entry.dataStart
        input.skip(6 + 2 + 8)
        val channels = input.readBits(2).toInt()
        input.skip(2 + 2 + 2)
        val sampleRate = (input.readInt32() ushr 16)
        var head: ByteArray? = null
        var delayUs = 0L
        scanChildren(input.position, entry.dataEnd) { box ->
            if (box.type == "dOps") {
                input.position = box.dataStart
                val dops = input.readBytes((box.dataEnd - box.dataStart).toInt())
                head = OpusConfig.dopsToOpusHead(dops)
                delayUs = OpusConfig.parseOpusHead(head!!).codecDelayUs
            }
        }
        return SampleDescription(
            audioCodec = AudioCodec.OPUS, codecConfig = head,
            sampleRate = if (sampleRate > 0) sampleRate else 48000, channels = channels,
            codecDelayUs = delayUs,
        )
    }

    /** Recorre el árbol de descriptores MPEG-4 del esds hasta el DecoderSpecificInfo. */
    private fun extractAscFromEsds(end: Long): ByteArray? {
        fun readDescriptorLength(): Int {
            var length = 0
            var count = 0
            while (count < 4) {
                val b = input.readByte()
                length = (length shl 7) or (b and 0x7F)
                count++
                if (b and 0x80 == 0) break
            }
            return length
        }
        while (input.position < end) {
            val tag = input.readByte()
            val len = readDescriptorLength()
            val payloadEnd = input.position + len
            when (tag) {
                0x03 -> {
                    input.skip(2)
                    val flags = input.readByte()
                    if (flags and 0x80 != 0) input.skip(2)             // streamDependenceFlag
                    if (flags and 0x40 != 0) input.skip(input.readByte().toLong()) // URL_Flag
                    if (flags and 0x20 != 0) input.skip(2)             // OCRstreamFlag
                }
                0x04 -> input.skip(13)                                  // luego hijos en línea
                0x05 -> return input.readBytes(len)
                else -> input.position = payloadEnd
            }
        }
        return null
    }

    override fun readPacket(): MediaPacket? {
        while (true) {
            var best: ParsedTrack? = null
            var bestIndex = -1
            var bestDts = Long.MAX_VALUE
            for (t in parsedTracks) {
                val cursor = cursors[t.info.id] ?: 0
                if (cursor < t.samples.size) {
                    val dts = t.samples[cursor].dtsUs
                    if (dts < bestDts) {
                        bestDts = dts
                        best = t
                        bestIndex = cursor
                    }
                }
            }
            val track = best ?: return null
            val s = track.samples[bestIndex]
            cursors[track.info.id] = bestIndex + 1

            if (s.offset < 0 || s.size < 0 ||
                s.offset > input.length || s.size > input.length - s.offset
            ) {
                if (unreadableWarned.add(track.info.id)) {
                    runCatching {
                        onWarning(
                            "pista ${track.info.id}: se saltan muestras cuyos bytes no están " +
                                "en el archivo (¿grabación cortada o archivo dañado?)",
                        )
                    }
                }
                continue
            }
            input.position = s.offset
            val data = input.readBytes(s.size)
            return MediaPacket(
                trackId = track.info.id,
                data = data,
                ptsUs = s.ptsUs,
                dtsUs = s.dtsUs,
                isKeyFrame = s.key,
                durationUs = s.durationUs,
            )
        }
    }

    override fun seekTo(timestampUs: Long): Long {
        val videoTrack = parsedTracks.firstOrNull { it.info is TrackInfo.Video }
        val anchor = videoTrack ?: parsedTracks.firstOrNull() ?: return 0
        var target = 0
        for ((idx, s) in anchor.samples.withIndex()) {
            if (s.ptsUs <= timestampUs && s.key) target = idx
            if (s.dtsUs > timestampUs) break
        }
        val targetDts = anchor.samples[target].dtsUs
        for (t in parsedTracks) {
            cursors[t.info.id] = if (t === anchor) target
            else t.samples.indexOfFirst { it.dtsUs >= targetDts }.let { if (it < 0) t.samples.size else it }
        }
        return anchor.samples[target].ptsUs
    }

    override fun close(): Unit = input.close()
}
