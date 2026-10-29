package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.ebml.EbmlReader
import com.braymon.kotmpeg.ebml.EbmlWriter
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.io.RepairTarget
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import com.braymon.kotmpeg.mp4.Mp4Muxer
import com.braymon.kotmpeg.mp4.Mp4Segment
import com.braymon.kotmpeg.pipeline.Remuxer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Grabaciones que se cortan antes de `stop()`: el proceso muere y el archivo se queda como estaba en
 * ese instante. [RecordingFileChannel] reconstruye ese estado en cualquier punto, y aquí se recorren
 * cientos de ellos —a mitad de un bloque o de una muestra, a mitad de la cabecera de un cluster,
 * justo al cerrarlo, durante la ampliación del índice, dentro de `stop()`— comprobando que:
 *
 *  - sin reparar, el MKV tiene un índice que solo apunta a clusters completos y una duración que
 *    los cubre, y el MP4 fragmentado declara en cada pista la duración de sus fragmentos completos;
 *  - `Kotmpeg.repair` deja exactamente los mismos paquetes y la misma duración que reempaquetar el
 *    archivo cortado, dice cuántos bytes quitó, no vuelve a tocar lo que ya reparó y rechaza sin
 *    modificarlo lo que no reconoce;
 *  - con FFmpeg instalado, los archivos cortados se abren sin avisos de índice y con duración, y
 *    los reparados sin avisos de índice ni de duración y con la de su remux, a un milisegundo.
 *
 * Casi todo corre en memoria: cada corte se lee con [memoryInput], se repara con
 * [RecordingFileChannel.repairRecorded] y se reempaqueta sobre otro canal en memoria. Un archivo, un
 * `fsync` y un borrado por corte eran la mayor parte de lo que tardaba esta suite, y no probaban nada
 * que no prueben los tests que sí van a disco: los de la reparación de un archivo o de un descriptor
 * de verdad, y los que pasan por FFmpeg.
 *
 * Los cortes empiezan cuando `start()` ha vuelto. Durante `start()` el archivo aún no tiene ni la
 * cabecera entera, y no hay nada que conservar.
 */
class InterruptedRecordingTest {

    private companion object {
        /** Variantes de la pasada fija del fuzzing de `repair`; `kotmpeg.fuzz.iterations` la cambia. */
        const val REPAIR_FUZZ_ITERATIONS = 600

        /** Semilla base de la pasada fija; `kotmpeg.fuzz.seed` la cambia. */
        const val REPAIR_FUZZ_SEED = 20_261_023L

        /** Cortes repartidos por la grabación que se pasan por FFmpeg, además de los de `stop()`. */
        const val FFMPEG_SPREAD_CUTS = 12

        /** Cortes que se comprueban con FFmpeg a la vez. */
        const val FFMPEG_THREADS = 4
    }

    @TempDir
    lateinit var dir: File

    private val video = TrackInfo.Video(VideoCodec.H264, 320, 240) {
        frameRate = 30.0
        codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22)))
    }
    private val audio = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) }

    /**
     * Cinco segundos de vídeo a 30 fps con B-frames —I P B B en orden de decodificación, un keyframe
     * por segundo— y AAC a 48 kHz, intercalados por tiempo de decodificación y con tamaños al azar
     * para que los cortes caigan en sitios distintos de cada bloque.
     */
    private fun syntheticPackets(): List<MediaPacket> {
        val random = Random(42)
        val timed = ArrayList<Pair<Long, MediaPacket>>()
        val frameUs = 33_333L
        var decodeIndex = 0
        for (gop in 0 until 5) {
            val first = gop * 30
            val order = arrayListOf(first)
            var k = 1
            while (k < 30) {
                if (k + 2 < 30) order += listOf(first + k + 2, first + k, first + k + 1) else order += (first + k until first + 30)
                k += 3
            }
            for (frame in order) {
                val dts = (decodeIndex++ - 2) * frameUs
                val data = ByteArray(200 + random.nextInt(2800)).also { random.nextBytes(it) }
                timed += dts to MediaPacket(1, data, frame * frameUs, dts, isKeyFrame = frame == first)
            }
        }
        var audioUs = 0L
        while (audioUs < 5_000_000) {
            val data = ByteArray(150 + random.nextInt(250)).also { random.nextBytes(it) }
            timed += audioUs to MediaPacket(2, data, audioUs, isKeyFrame = true)
            audioUs += 21_333
        }
        return timed.sortedBy { it.first }.map { it.second }
    }

    /**
     * Una grabación entera registrada escritura a escritura, cuántas escrituras había al volver
     * `start()` y al empezar `stop()`, y lo que avisó `onSegment`.
     */
    private class Recording(
        val channel: RecordingFileChannel,
        val startWrites: Int,
        val stopWrites: Int,
        val segments: List<Mp4Segment>,
    ) {

        /**
         * Puntos de corte desde que `start()` vuelve: justo antes, en y justo después del final de
         * cada escritura, y dentro de las que se pueden partir.
         */
        fun cuts(): List<Long> {
            val start = channel.boundaries()[startWrites - 1]
            val points = sortedSetOf<Long>()
            var before = 0L
            for (write in channel.writes) {
                val end = before + write.bytes.size
                points += listOf(end - 1, end, end + 1)
                if (write.bytes.size > RecordingFileChannel.ATOMIC_BYTES) points += listOf(before + 1, before + write.bytes.size / 2)
                before = end
            }
            return points.filter { it in start..channel.totalBytes }
        }

        /** Un corte justo antes de `stop()` y otro tras cada una de sus escrituras: el cierre, paso a paso. */
        fun stopCuts(): List<Long> = channel.boundaries().drop(stopWrites - 1)
    }

    private fun record(
        packets: List<MediaPacket>,
        tracks: List<TrackInfo>,
        fragmented: Boolean,
        options: MuxerOptions.() -> Unit = {},
    ): Recording {
        val channel = RecordingFileChannel()
        val segments = ArrayList<Mp4Segment>()
        val out = SeekableOutput(channel)
        val muxer = if (fragmented) {
            FragmentedMp4Muxer(out) {
                creationTimeMillis = null
                options()
                onSegment = { segments += it }
            }
        } else {
            MkvMuxer(out) {
                creationTimeMillis = null
                options()
            }
        }
        var startWrites = 0
        var stopWrites = 0
        muxer.use {
            for (track in tracks) it.addTrack(track)
            it.start()
            startWrites = channel.writes.size
            for (packet in packets) it.writePacket(packet)
            stopWrites = channel.writes.size
        }
        return Recording(channel, startWrites, stopWrites, segments)
    }

    /** Pista, tiempo, keyframe y contenido de cada paquete, en orden, y la duración que da el demuxer. */
    private fun read(bytes: ByteArray, warnings: MutableList<String> = ArrayList()): Pair<List<String>, Long> =
        Kotmpeg.openDemuxer(memoryInput(bytes)) { warnings += it }.use { demuxer ->
            val packets = generateSequence { demuxer.readPacket() }
                .map { "${it.trackId}/${it.ptsUs}/${it.isKeyFrame}/${it.data.contentHashCode()}" }
                .toList()
            packets to demuxer.durationUs
        }

    /** Si [bytes] se abre y se lee hasta el final sin lanzar nada. */
    private fun readsCleanly(bytes: ByteArray): Boolean = runCatching {
        Kotmpeg.openDemuxer(memoryInput(bytes)).use { demuxer -> while (demuxer.readPacket() != null) Unit }
    }.isSuccess

    /** [bytes] reempaquetado con [Remuxer.remux] en el mismo contenedor, sin pasar por el disco. */
    private fun remux(bytes: ByteArray, fragmented: Boolean): ByteArray {
        val channel = RecordingFileChannel()
        Kotmpeg.openDemuxer(memoryInput(bytes)).use { demuxer ->
            Kotmpeg.createMuxer(SeekableOutput(channel), if (fragmented) ContainerFormat.MP4 else ContainerFormat.MKV) {
                creationTimeMillis = null
                this.fragmented = fragmented
            }.use { muxer -> Remuxer.remux(demuxer, muxer) }
        }
        return channel.bytes()
    }

    /** Lo que el corte deja en un MKV: la duración declarada y cada cue con el cluster al que apunta. */
    private class LiveMkv(val durationMs: Double, val cues: List<Pair<Long, Long>>, val incompleteCues: List<Long>)

    private fun inspectMkv(bytes: ByteArray): LiveMkv = memoryInput(bytes).use { input ->
        val reader = EbmlReader(input)
        reader.skip(reader.readElement())
        val segment = reader.readElement()
        var duration = 0.0
        val cues = ArrayList<Pair<Long, Long>>()
        top@ while (input.position < input.length) {
            val el = try {
                reader.readElement()
            } catch (_: EOFException) {
                break@top
            }
            when (el.id) {
                MatroskaIds.INFO -> while (input.position < el.dataEnd) {
                    val child = reader.readElement()
                    if (child.id == MatroskaIds.DURATION) duration = reader.readFloat(child) else reader.skip(child)
                }
                MatroskaIds.CUES -> while (input.position < el.dataEnd) {
                    val point = reader.readElement()
                    if (point.id != MatroskaIds.CUE_POINT) { reader.skip(point); continue }
                    var time = -1L
                    var position = -1L
                    while (input.position < point.dataEnd) {
                        val child = reader.readElement()
                        when (child.id) {
                            MatroskaIds.CUE_TIME -> time = reader.readUInt(child)
                            MatroskaIds.CUE_TRACK_POSITIONS -> while (input.position < child.dataEnd) {
                                val p = reader.readElement()
                                if (p.id == MatroskaIds.CUE_CLUSTER_POSITION) position = reader.readUInt(p) else reader.skip(p)
                            }
                            else -> reader.skip(child)
                        }
                    }
                    cues += time to position
                }
                MatroskaIds.CLUSTER -> break@top
                else -> reader.skip(el)
            }
        }
        val incomplete = cues.filter { (_, position) ->
            input.position = segment.dataStart + position
            val cluster = runCatching { reader.readElement() }.getOrNull()
            cluster == null || cluster.id != MatroskaIds.CLUSTER || cluster.size < 0 || cluster.dataEnd > input.length
        }.map { it.first }
        LiveMkv(duration, cues, incomplete)
    }

    private fun u32(bytes: ByteArray, at: Int): Long = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (bytes[at + i].toLong() and 0xFF) }

    /** `tkhd.duration` de la pista [index] de un MP4 fragmentado, en ms (la escala del `mvhd`). */
    private fun declaredTrackDurationMs(bytes: ByteArray, index: Int): Long {
        var found = -1
        for (at in 4 until bytes.size - 4) {
            if (bytes[at] == 't'.code.toByte() && String(bytes, at, 4, Charsets.US_ASCII) == "tkhd" && ++found == index) {
                return u32(bytes, at + 24)
            }
        }
        error("no hay tkhd $index")
    }

    /**
     * La duración de lo que lleva cada pista de [bytes] —la suma de las de sus muestras, en el orden
     * de `tracks`—, en ms: lo que su `tkhd` tiene que declarar si ese es el archivo entero.
     */
    private fun decodedDurationsMs(bytes: ByteArray): List<Long> = Kotmpeg.openDemuxer(memoryInput(bytes)).use { demuxer ->
        val sums = HashMap<Int, Long>()
        for (packet in generateSequence { demuxer.readPacket() }) sums[packet.trackId] = (sums[packet.trackId] ?: 0L) + packet.durationUs
        demuxer.tracks.map { (sums[it.id] ?: 0L) / 1_000 }
    }

    /**
     * Hasta dónde llega lo que la reparación conservó de [repaired]: todo menos lo que añade detrás
     * —el `mfra` de un fMP4, que acaba con su propio tamaño en el `mfro`, o el `Cues` de un MKV cuyo
     * índice no cabía en la reserva—. Es la cuenta contra la que se comprueba `discardedBytes`.
     *
     * Un `Cues` antes del primer cluster es la reserva, no algo añadido, salvo que la grabación no
     * tuviera reserva ([withReserve]): entonces es el índice que la reparación escribió al final de
     * un archivo cortado antes de su primer cluster. Y un `Cues` final que ya estaba entero en el
     * archivo cortado ([original]) tampoco es añadido: un corte dentro de `stop()`, después de
     * escribir el índice al final, lo deja ahí, y la reparación lo conserva si es el que calcularía.
     */
    private fun keptBytes(repaired: ByteArray, original: ByteArray, fragmented: Boolean, withReserve: Boolean): Long {
        if (fragmented) {
            val mfraSize = u32(repaired, repaired.size - 4)
            assertEquals("mfra", String(repaired, (repaired.size - mfraSize).toInt() + 4, 4, Charsets.US_ASCII))
            return repaired.size - mfraSize
        }
        return memoryInput(repaired).use { input ->
            val reader = EbmlReader(input)
            reader.skip(reader.readElement())
            val segment = reader.readElement()
            var sawCluster = false
            var trailingCues = -1L
            while (input.position < minOf(segment.dataEnd, input.length)) {
                val start = input.position
                val el = reader.readElement()
                trailingCues = if (el.id == MatroskaIds.CUES && (sawCluster || !withReserve)) start else -1L
                if (el.id == MatroskaIds.CLUSTER) sawCluster = true
                reader.skip(el)
            }
            val keptCues = trailingCues >= 0 && original.size >= repaired.size &&
                repaired.copyOfRange(trailingCues.toInt(), repaired.size)
                    .contentEquals(original.copyOfRange(trailingCues.toInt(), repaired.size))
            if (trailingCues >= 0 && !keptCues) trailingCues else repaired.size.toLong()
        }
    }

    private fun mkvCutsStayReadable(options: MuxerOptions.() -> Unit) {
        val full = record(syntheticPackets(), listOf(video, audio), fragmented = false, options)
        val (allPackets, _) = read(full.channel.bytes())
        val cuts = full.cuts()
        var withIndex = 0
        for (cut in cuts) {
            val bytes = full.channel.cutAfter(cut)
            val label = "corte en $cut de ${full.channel.totalBytes}"
            val live = inspectMkv(bytes)
            assertEquals(emptyList(), live.incompleteCues, "$label: cues que apuntan a clusters sin cerrar")
            for ((time, _) in live.cues) assertTrue(live.durationMs >= time, "$label: Duration ${live.durationMs} por detrás del cue de $time ms")
            if (live.cues.isNotEmpty()) withIndex++

            val warnings = ArrayList<String>()
            val (packets, _) = read(bytes, warnings)
            assertEquals(allPackets.subList(0, packets.size), packets, "$label: lo leído no es el principio de la grabación")
            assertTrue(warnings.none { "índice" in it || "Cues" in it }, "$label: $warnings")
        }
        assertTrue(cuts.size > 50, "solo ${cuts.size} cortes")
        if (MuxerOptions().apply(options).cuesReserveBytes > 0) assertTrue(withIndex > cuts.size / 2, "solo $withIndex cortes con índice")
    }

    @Test
    fun `an mkv cut anywhere keeps an index to complete clusters and a duration that covers them`() {
        mkvCutsStayReadable {}
    }

    @Test
    fun `an mkv cut while its index outgrows the reserve keeps what was already indexed`() {
        mkvCutsStayReadable { cuesReserveBytes = 64 }
    }

    @Test
    fun `an mkv without reserve still declares its duration when cut`() {
        val full = record(syntheticPackets(), listOf(video, audio), fragmented = false) { cuesReserveBytes = 0 }
        val late = full.cuts().last { it < full.channel.totalBytes / 2 }
        val live = inspectMkv(full.channel.cutAfter(late))
        assertEquals(emptyList(), live.cues)
        assertTrue(live.durationMs > 1_000, "duración ${live.durationMs} ms a mitad de grabación")
    }

    /**
     * Tras cada fragmento, el `tkhd` de cada pista declara la duración de lo escrito de esa pista.
     * Entre el volcado de un fragmento y el parche de las duraciones hay escrituras, así que la
     * declarada es la de los fragmentos completos o la de todos menos el último.
     *
     * Lo que se lee del corte es, pista a pista, el principio de lo grabado. Entre pistas no tiene
     * por qué serlo: dentro de un fragmento las muestras de cada pista van juntas, y un corte a mitad
     * de las de audio deja leer todo el vídeo de ese fragmento y solo parte de su audio.
     */
    @Test
    fun `a fragmented mp4 cut anywhere declares the duration of its complete fragments`() {
        val full = record(syntheticPackets(), listOf(video, audio), fragmented = true)
        assertTrue(full.segments.size >= 5)
        val complete = full.channel.bytes()
        val wholeTracks = read(complete).first.groupBy { it.substringBefore('/') }
        val ends = full.segments.map { it.offset + it.size }
        val durationsAt = ends.map { decodedDurationsMs(complete.copyOf(it.toInt())) }
        for (cut in full.cuts()) {
            val bytes = full.channel.cutAfter(cut)
            val label = "corte en $cut de ${full.channel.totalBytes}"
            val done = ends.indices.filter { ends[it] <= bytes.size }
            for (track in 0 until 2) {
                val declared = declaredTrackDurationMs(bytes, track)
                val allowed = listOf(done.getOrNull(done.size - 2), done.lastOrNull()).map { k -> k?.let { durationsAt[it][track] } ?: 0L }
                assertTrue(
                    allowed.any { abs(it - declared) <= 2 },
                    "$label: la pista $track declara $declared ms y sus fragmentos completos acaban en $allowed",
                )
            }
            for ((track, packets) in read(bytes).first.groupBy { it.substringBefore('/') }) {
                val whole = wholeTracks.getValue(track)
                assertEquals(whole.subList(0, packets.size), packets, "$label: lo leído de la pista $track no es el principio de lo grabado")
            }
        }
    }

    /**
     * Repara cada corte y lo compara con el remux del mismo corte. Comprueba también que
     * `discardedBytes` es lo que de verdad desapareció del final, que una segunda reparación no
     * cambia nada y que lo que ya estaba cerrado tampoco se toca.
     */
    private fun repairMatchesRemux(fragmented: Boolean, options: MuxerOptions.() -> Unit = {}) {
        val full = record(syntheticPackets(), listOf(video, audio), fragmented, options)
        val withReserve = MuxerOptions().apply(options).cuesReserveBytes > 0
        var repaired = 0
        for (cut in full.cuts()) {
            val bytes = full.channel.cutAfter(cut)
            val label = "corte en $cut de ${full.channel.totalBytes}"
            val (result, channel) = RecordingFileChannel.repairRecorded(bytes)
            val after = channel.bytes()
            val (again, second) = RecordingFileChannel.repairRecorded(after)
            assertFalse(again.repaired, "$label: la segunda reparación volvió a cambiar el archivo")
            assertEquals(0, second.writes.size, "$label: la segunda reparación escribió en el archivo")
            if (!result.repaired) {
                assertContentEquals(bytes, after, "$label: un archivo cerrado no se toca")
                continue
            }
            assertEquals(bytes.size - keptBytes(after, bytes, fragmented, withReserve), result.discardedBytes, "$label: bytes descartados en $result")
            val warnings = ArrayList<String>()
            val (packets, duration) = read(after, warnings)
            assertEquals(emptyList(), warnings, "$label: el archivo reparado da avisos")
            if (packets.isEmpty()) continue
            val (expected, expectedDuration) = read(remux(bytes, fragmented))
            assertEquals(expected, packets, "$label: los paquetes no son los del remux")
            assertEquals(expectedDuration, duration, "$label: la duración no es la del remux")
            assertTrue(abs(result.durationUs - duration) < 1_000, "$label: el resultado dice ${result.durationUs} µs y el archivo $duration")
            repaired++
        }
        assertTrue(repaired > 50, "solo $repaired cortes reparados")
    }

    @Test
    fun `a cut mkv repaired in place has the packets and duration of its remux`() {
        repairMatchesRemux(fragmented = false)
    }

    @Test
    fun `a cut mkv whose index does not fit the reserve is repaired with the index at the end`() {
        repairMatchesRemux(fragmented = false) { cuesReserveBytes = 64 }
    }

    @Test
    fun `a cut mkv without reserve is repaired with the index at the end`() {
        repairMatchesRemux(fragmented = false) { cuesReserveBytes = 0 }
    }

    @Test
    fun `a cut fragmented mp4 repaired in place has the packets and duration of its remux`() {
        repairMatchesRemux(fragmented = true)
    }

    @Test
    fun `a cut fragmented mp4 with segment indexes is repaired like its remux`() {
        repairMatchesRemux(fragmented = true) { segmentIndex = true }
    }

    /**
     * Un MKV de la 4.0 cortado tenía el hueco del `SeekHead` como un `Void`, la duración a cero y
     * ningún índice. La reparación escribe las tres cosas.
     */
    @Test
    fun `an mkv cut with the 4_0 layout gets a seek head and an index`() {
        val full = record(syntheticPackets(), listOf(video, audio), fragmented = false) { cuesReserveBytes = 0 }
        val bytes = full.channel.cutAfter(full.channel.totalBytes * 2 / 3)
        val segmentData = memoryInput(bytes).use { input ->
            val reader = EbmlReader(input)
            reader.skip(reader.readElement())
            reader.readElement().dataStart.toInt()
        }
        EbmlWriter.encodeVoid(120).copyInto(bytes, segmentData)
        val file = File(dir, "antiguo.mkv").apply { writeBytes(bytes) }
        assertTrue(Kotmpeg.repair(file).repaired)
        val repaired = file.readBytes()
        assertEquals(MatroskaIds.VOID, repaired[segmentData].toLong() and 0xFF, "el Void del hueco se reduce a su cabecera")
        assertEquals(MatroskaIds.SEEK_HEAD, u32(repaired, segmentData + 2))
        assertEquals(read(remux(bytes, fragmented = false)), read(repaired))
    }

    @Test
    fun `an mkv whose index outgrows the reserve closes with a single index at the end`() {
        val full = record(syntheticPackets(), listOf(video, audio), fragmented = false) { cuesReserveBytes = 64 }
        val bytes = full.channel.bytes()
        val cuesElements = memoryInput(bytes).use { input ->
            val reader = EbmlReader(input)
            reader.skip(reader.readElement())
            reader.readElement()
            var count = 0
            while (input.position < input.length) {
                val el = reader.readElement()
                if (el.id == MatroskaIds.CUES) count++
                reader.skip(el)
            }
            count
        }
        assertEquals(1, cuesElements, "un segmento solo puede tener un Cues")
        Kotmpeg.openDemuxer(memoryInput(bytes)).use { demuxer ->
            assertEquals(3_000_000L, demuxer.seekTo(3_500_000))
        }
    }

    @Test
    fun `repair leaves a closed file untouched`() {
        for (fragmented in listOf(false, true)) {
            val full = record(syntheticPackets(), listOf(video, audio), fragmented)
            val bytes = full.channel.bytes()
            val file = File(dir, if (fragmented) "cerrado.mp4" else "cerrado.mkv").apply { writeBytes(bytes) }
            val result = Kotmpeg.repair(file)
            assertFalse(result.repaired)
            assertEquals(if (fragmented) ContainerFormat.MP4 else ContainerFormat.MKV, result.format)
            assertTrue(result.durationUs > 4_900_000, "$result")
            assertContentEquals(bytes, file.readBytes())
        }
    }

    @Test
    fun `repair rejects what it does not recognize without modifying it`() {
        val mkv = record(syntheticPackets(), listOf(video, audio), fragmented = false).channel
        val fmp4 = record(syntheticPackets(), listOf(video, audio), fragmented = true).channel
        val plain = File(dir, "plano.mp4").also { file ->
            Mp4Muxer(file).use { muxer ->
                muxer.addTrack(video)
                muxer.addTrack(audio)
                muxer.start()
                for (packet in syntheticPackets()) muxer.writePacket(packet)
            }
        }.readBytes()
        val damaged = mkv.cutAfter(mkv.totalBytes / 2).also { bytes ->
            memoryInput(bytes.copyOf()).use { input ->
                val reader = EbmlReader(input)
                reader.skip(reader.readElement())
                reader.readElement()
                var el = reader.readElement()
                while (el.id != MatroskaIds.CLUSTER) { reader.skip(el); el = reader.readElement() }
                reader.skip(reader.readElement())
                bytes[input.position.toInt()] = 0
            }
        }
        val cases = mapOf(
            "basura.mkv" to ByteArray(5_000).also { Random(7).nextBytes(it) },
            "plano.mp4" to plain,
            "plano-cortado.mp4" to plain.copyOf(plain.size / 2),
            "cabecera-cortada.mkv" to mkv.cutAfter(100),
            "moov-cortado.mp4" to fmp4.cutAfter(300),
            "danado-en-medio.mkv" to damaged,
        )
        for ((name, bytes) in cases) {
            val file = File(dir, name).apply { writeBytes(bytes) }
            assertFailsWith<IllegalArgumentException>(name) { Kotmpeg.repair(file) }
            assertContentEquals(bytes, file.readBytes(), "$name: se modificó al rechazarlo")
        }
        val missing = File(dir, "no-existe.mkv")
        assertFailsWith<FileNotFoundException> { Kotmpeg.repair(missing) }
        assertFalse(missing.exists(), "reparar un archivo que no existe lo creó")
    }

    /**
     * A `repair` le puede llegar cualquier cosa. Se mutan grabaciones cortadas —MKV con reserva y sin
     * ella, y fMP4— con [FuzzMutator], y cada variante tiene que repararse, o rechazarse con
     * `IllegalArgumentException` sin que el archivo cambie. Reparada, una segunda pasada no la toca,
     * y si se leía antes de repararla —abría y llegaba al final sin lanzar— se sigue leyendo, que es
     * lo que promete `SECURITY.md`. Cualquier otra excepción es un fallo.
     *
     * Como `DemuxerFuzzTest`, corre una pasada fija de [REPAIR_FUZZ_ITERATIONS] variantes, y acepta
     * `kotmpeg.fuzz.iterations`, `kotmpeg.fuzz.seconds` y `kotmpeg.fuzz.seed` para una campaña larga.
     * Cada variante sale de su propia semilla —la base mezclada con su número—, así que un fallo se
     * reproduce con esos dos números, y la entrada que lo provocó queda en `kotmpeg.fuzz.findings`.
     * Cada una tiene además su plazo (ver [withinFuzzDeadline]).
     */
    @Test
    fun `mutated interrupted files are repaired or rejected untouched`() {
        val iterations = System.getProperty("kotmpeg.fuzz.iterations")?.toIntOrNull() ?: REPAIR_FUZZ_ITERATIONS
        val seconds = System.getProperty("kotmpeg.fuzz.seconds")?.toLongOrNull() ?: 0L
        val baseSeed = System.getProperty("kotmpeg.fuzz.seed")?.toLongOrNull() ?: REPAIR_FUZZ_SEED
        val findings = File(System.getProperty("kotmpeg.fuzz.findings") ?: "build/fuzz-findings")
        val packets = syntheticPackets()
        fun cut(recording: Recording) = recording.channel.cutAfter(recording.channel.totalBytes * 3 / 5)
        val seeds = listOf(
            FuzzSeeds.Container.MKV to cut(record(packets, listOf(video, audio), fragmented = false)),
            FuzzSeeds.Container.MKV to cut(record(packets, listOf(video, audio), fragmented = false) { cuesReserveBytes = 0 }),
            FuzzSeeds.Container.MP4 to cut(record(packets, listOf(video, audio), fragmented = true)),
        )
        val deadline = if (seconds > 0) System.nanoTime() + seconds * 1_000_000_000 else Long.MAX_VALUE
        var executed = 0
        var repaired = 0
        var rejected = 0
        while (if (seconds > 0) System.nanoTime() < deadline else executed < iterations) {
            val iteration = executed++
            val random = kotlin.random.Random(baseSeed xor (iteration.toLong() * -0x61c8864680b583ebL))
            val (container, seed) = seeds[iteration % seeds.size]
            val donors = seeds.filter { it.first == container && it.second !== seed }.map { it.second }
            val input = FuzzMutator(random, container).mutate(seed, donors)
            val label = "semilla base $baseSeed, variante $iteration (${container.extension})"
            val kept = File(findings, "reparar-$baseSeed-$iteration.${container.extension}")
            when (withinFuzzDeadline(input, kept, label) { repairMutated(input, label) }) {
                true -> repaired++
                false -> Unit
                null -> rejected++
            }
        }
        println("Fuzzing de repair: $executed variantes, semilla base $baseSeed: $repaired reparadas, $rejected rechazadas")
        assertTrue(repaired > executed / 6 && rejected > executed / 12, "reparadas $repaired, rechazadas $rejected de $executed")
    }

    /**
     * Corre [block], una variante del fuzzing de `repair`, con [timeoutMs] de plazo —el mismo que
     * `FuzzHarness` da a las del demuxer— ([withinDeadline]), y devuelve lo que devuelva.
     *
     * Si la variante falla —una aserción, cualquier otra excepción o no terminar a tiempo—, [input]
     * se guarda en [kept] antes de propagar el fallo, para poder reproducirla. Antes solo se guardaba
     * ante un `AssertionError`, y una variante colgada o que lanzaba otra cosa se perdía.
     */
    private fun <T> withinFuzzDeadline(
        input: ByteArray,
        kept: File,
        label: String,
        timeoutMs: Long = FuzzHarness.TIMEOUT_MS,
        block: () -> T,
    ): T = withinDeadline(label, timeoutMs, onFailure = { kept.parentFile?.mkdirs(); kept.writeBytes(input) }, block = block)

    /**
     * Repara [input] en memoria y comprueba lo que promete `repair` con él. Devuelve `true` si lo
     * reparó, `false` si ya estaba cerrado y `null` si lo rechazó.
     */
    private fun repairMutated(input: ByteArray, label: String): Boolean? {
        val readableBefore = readsCleanly(input)
        val channel = RecordingFileChannel(input)
        val outcome = runCatching { RepairTarget.open(channel).use { Kotmpeg.repair(it) } }
        val after = channel.bytes()
        when (val error = outcome.exceptionOrNull()) {
            null -> Unit
            is IllegalArgumentException -> {
                assertContentEquals(input, after, "$label: se modificó al rechazarla")
                return null
            }
            else -> throw AssertionError("$label: ${error::class.simpleName}: ${error.message}", error)
        }
        val result = outcome.getOrThrow()
        if (!result.repaired) assertContentEquals(input, after, "$label: cerrada y aun así tocada")
        val (again, second) = runCatching { RecordingFileChannel.repairRecorded(after) }
            .getOrElse { throw AssertionError("$label: la segunda reparación lanzó ${it::class.simpleName}: ${it.message}", it) }
        assertFalse(again.repaired, "$label: la segunda reparación volvió a cambiarla")
        assertEquals(0, second.writes.size, "$label: la segunda reparación la tocó")
        if (readableBefore) assertTrue(readsCleanly(after), "$label: se leía antes de repararla y después ya no")
        return result.repaired
    }

    /**
     * El plazo de cada variante del fuzzing de `repair`. Una que no termina —aquí un bucle que no
     * mira la interrupción, como un parser atascado— falla a su plazo en vez de colgar el test, y una
     * que lanza algo que no es una aserción lo propaga tal cual; en los dos casos su entrada queda
     * guardada para reproducirla.
     */
    @Test
    fun `a repair fuzzing variant that hangs or throws fails in time and is kept`() {
        val input = byteArrayOf(1, 2, 3)
        val hung = File(dir, "hallazgos/colgada.mkv")
        val release = AtomicBoolean(false)
        val started = System.nanoTime()
        val timeout = try {
            assertFailsWith<AssertionError> { withinFuzzDeadline(input, hung, "colgada", timeoutMs = 200) { while (!release.get()) Unit } }
        } finally {
            release.set(true)
        }
        assertTrue(System.nanoTime() - started < 5_000_000_000L, "el plazo de 200 ms no cortó la variante")
        assertTrue("200 ms" in timeout.message.orEmpty(), "el fallo no dice que venció el plazo: ${timeout.message}")
        assertContentEquals(input, hung.readBytes())

        val thrown = File(dir, "hallazgos/lanza.mkv")
        assertFailsWith<IllegalStateException> { withinFuzzDeadline(input, thrown, "lanza") { error("no tipada") } }
        assertContentEquals(input, thrown.readBytes())
    }

    @Test
    fun `repair works on a descriptor and refuses one it cannot use`() {
        val full = record(syntheticPackets(), listOf(video, audio), fragmented = false)
        val bytes = full.channel.cutAfter(full.channel.totalBytes / 2)

        val file = File(dir, "descriptor.mkv").apply { writeBytes(bytes) }
        val raf = RandomAccessFile(file, "rw")
        val result = try {
            Kotmpeg.repair(raf.fd)
        } finally {
            runCatching { raf.close() }
        }
        assertTrue(result.repaired)
        assertEquals(read(RecordingFileChannel.repairRecorded(bytes).second.bytes()), read(file.readBytes()))

        val writeOnly = File(dir, "solo-escritura.mkv").apply { writeBytes(bytes) }
        FileOutputStream(writeOnly, true).use { stream ->
            assertFailsWith<IllegalArgumentException> { Kotmpeg.repair(stream.fd) }
        }
        assertContentEquals(bytes, writeOnly.readBytes())

        val readOnly = File(dir, "solo-lectura.mkv").apply { writeBytes(bytes) }
        FileInputStream(readOnly).use { stream ->
            assertFailsWith<IOException>("un descriptor de solo lectura falla en la primera escritura") { Kotmpeg.repair(stream.fd) }
        }
        assertContentEquals(bytes, readOnly.readBytes())
    }

    @Test
    fun `the cues reserve is validated before opening the file`() {
        for (bad in listOf(-1, 1, 63, (16 shl 20) + 1)) {
            val file = File(dir, "reserva-$bad.mkv")
            assertFailsWith<IllegalArgumentException> { MkvMuxer(file) { cuesReserveBytes = bad } }
            assertFalse(file.exists())
        }
        MkvMuxer(File(dir, "sin-reserva.mkv")) { cuesReserveBytes = 0 }.close()
        MkvMuxer(File(dir, "minima.mkv")) { cuesReserveBytes = 64 }.close()
    }

    /**
     * Los cortes que se pasan por FFmpeg: [FFMPEG_SPREAD_CUTS] repartidos por la grabación y, además,
     * los de [Recording.stopCuts], porque el cierre va por pasos y es donde un lector puede
     * encontrarse el archivo a medio cerrar. Cada corte cuesta hasta tres procesos de FFmpeg, así que
     * pasarlos todos no cabe en la suite.
     */
    private fun ffmpegCuts(recording: Recording): List<Long> {
        val stopCuts = recording.stopCuts()
        val beforeStop = recording.cuts().filter { it < stopCuts.first() }
        val step = maxOf(1, beforeStop.size / FFMPEG_SPREAD_CUTS)
        return (beforeStop.filterIndexed { i, _ -> i % step == 0 } + stopCuts).distinct()
    }

    /** Los avisos de `ffprobe -v warning` sobre [file] y la duración que le da, en segundos. */
    private fun ffprobe(file: File): Pair<List<String>, Double?> {
        val (out, _) = runProcess("ffprobe", "-v", "warning", "-show_entries", "format=duration", "-of", "csv=p=0", file.absolutePath)
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val duration = lines.lastOrNull()?.toDoubleOrNull()
        return lines.filter { it.toDoubleOrNull() == null && it != "N/A" } to duration
    }

    private val indexProblems = Regex("index|cue|seek|duplicate|unknown entry|invalid as first byte", RegexOption.IGNORE_CASE)

    /** Lo que no puede salir de un archivo reparado: los mismos avisos de índice, y además de duración. */
    private val repairProblems = Regex(indexProblems.pattern + "|duration", RegexOption.IGNORE_CASE)

    /**
     * Con FFmpeg y vídeo de verdad (H.264 con B-frames y AAC): cada corte que ya declara duración
     * —desde que se cierra el primer cluster o fragmento— se abre sin avisos de índice y con
     * duración, y una vez reparado, sin avisos de índice ni de duración y con la duración de su
     * remux, a un milisegundo. Antes de eso el archivo no tiene nada que reproducir: FFmpeg llega al
     * final sin encontrar un cluster y, al buscar por dónde seguir, confunde los ids que nombra el
     * `SeekHead` con elementos.
     *
     * Lo que se exige depende lo menos posible de la versión de FFmpeg —el CI usa la de Ubuntu, y en
     * desarrollo hay otra—: otros avisos de un archivo reparado no hacen fallar el test, pero se
     * imprimen, y la duración se compara con tolerancia y no con igualdad exacta de `Double`.
     *
     * Casi todo el tiempo se va en arrancar procesos de FFmpeg, así que los cortes se comprueban de
     * [FFMPEG_THREADS] en [FFMPEG_THREADS], cada uno con sus propios archivos.
     */
    @Test
    fun `ffmpeg opens every cut and every repaired file`() {
        assumeTrue(ffmpegInstalled, "ffmpeg no está instalado")
        val source = File(dir, "fuente.mp4")
        val (out, code) = runProcess(
            "ffmpeg", "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30:duration=6",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=6",
            "-c:v", "libx264", "-bf", "2", "-g", "30", "-c:a", "aac", source.absolutePath,
        )
        assertEquals(0, code, out)
        val (tracks, packets) = Kotmpeg.openDemuxer(source).use { demuxer ->
            demuxer.tracks to generateSequence { demuxer.readPacket() }.toList()
        }
        val pool = Executors.newFixedThreadPool(FFMPEG_THREADS)
        try {
            for (fragmented in listOf(false, true)) {
                val recording = record(packets, tracks, fragmented)
                val checks = ffmpegCuts(recording).map { cut -> pool.submit(Callable { checkWithFfmpeg(recording, cut, fragmented) }) }
                for (check in checks) {
                    try {
                        check.get()
                    } catch (e: ExecutionException) {
                        throw e.cause ?: e
                    }
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Lo que comprueba el test de FFmpeg con el corte [cut] de [recording], en archivos que son solo suyos. */
    private fun checkWithFfmpeg(recording: Recording, cut: Long, fragmented: Boolean) {
        val bytes = recording.channel.cutAfter(cut)
        val ext = if (fragmented) "mp4" else "mkv"
        val label = "$ext cortado en $cut de ${recording.channel.totalBytes}"
        val declared = if (fragmented) declaredTrackDurationMs(bytes, 0).toDouble() else inspectMkv(bytes).durationMs
        if (declared > 0) {
            val (problems, duration) = ffprobe(File(dir, "ff-$cut-cortado.$ext").apply { writeBytes(bytes) })
            assertTrue(problems.none { indexProblems.containsMatchIn(it) }, "$label: $problems")
            assertTrue((duration ?: 0.0) > 0, "$label: sin duración")
        }

        val (result, channel) = RecordingFileChannel.repairRecorded(bytes)
        val repaired = channel.bytes()
        if (!result.repaired || read(repaired).first.isEmpty()) return
        val (warnings, repairedDuration) = ffprobe(File(dir, "ff-$cut-reparado.$ext").apply { writeBytes(repaired) })
        assertEquals(emptyList(), warnings.filter { repairProblems.containsMatchIn(it) }, "$label, reparado")
        if (warnings.isNotEmpty()) println("$label, reparado: avisos de FFmpeg que no son de índice ni de duración: $warnings")
        val remuxDuration = ffprobe(File(dir, "ff-$cut-remux.$ext").apply { writeBytes(remux(bytes, fragmented)) }).second
        assertNotNull(repairedDuration, "$label: el reparado no tiene duración")
        assertNotNull(remuxDuration, "$label: el remux no tiene duración")
        assertTrue(
            Math.round(abs(remuxDuration - repairedDuration) * 1_000_000) <= 1_000,
            "$label: el reparado dura $repairedDuration s y su remux $remuxDuration s",
        )
    }
}
