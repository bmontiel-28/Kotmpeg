package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.FragmentWalker
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * La reparación de fMP4 frente a lo que `InterruptedRecordingTest` no cubre: una reparación que a
 * su vez se interrumpe y se relanza, los desfases sin signo, un final de ceros, tamaños dañados en
 * mitad del archivo, recuentos de muestras absurdos, muestras vacías que apuntan fuera de su `mdat`
 * y fMP4 válidos de otros programas que encadenan los `traf`.
 *
 * Las grabaciones son las de `InterruptedRecordingTest` —cinco segundos de vídeo con B-frames y AAC
 * sobre un canal que registra cada escritura—; los fragmentos raros se construyen a mano detrás de
 * una cabecera de verdad.
 */
class Fmp4RepairTest {

    @TempDir
    lateinit var dir: File

    private val video = TrackInfo.Video(VideoCodec.H264, 320, 240) {
        frameRate = 30.0
        codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22)))
    }
    private val audio = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) }

    /**
     * Cinco segundos de vídeo a 30 fps con B-frames —I P B B en orden de decodificación, un keyframe
     * por segundo— y AAC a 48 kHz, intercalados por tiempo de decodificación y con tamaños al azar.
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

    /** Una grabación registrada escritura a escritura, y desde qué escritura empiezan los cortes. */
    private class Recording(val channel: RecordingFileChannel, val startWrites: Int) {

        /**
         * Justo antes, en y justo después del final de cada escritura, y dentro de las que se pueden
         * partir. Con desfases sin signo, `start()` no escribe nada: la cabecera espera al primer
         * fragmento.
         */
        fun cuts(): List<Long> {
            val start = if (startWrites == 0) 0L else channel.boundaries()[startWrites - 1]
            return cutPoints(channel).filter { it >= start }
        }
    }

    private fun record(options: MuxerOptions.() -> Unit = {}): Recording {
        val channel = RecordingFileChannel()
        var startWrites = 0
        FragmentedMp4Muxer(SeekableOutput(channel), options).use { muxer ->
            muxer.addTrack(video)
            muxer.addTrack(audio)
            muxer.start()
            startWrites = channel.writes.size
            for (packet in syntheticPackets()) muxer.writePacket(packet)
        }
        return Recording(channel, startWrites)
    }

    /** Pista, tiempo, keyframe y contenido de cada paquete, en orden, y la duración que da el demuxer. */
    private fun read(bytes: ByteArray, warnings: MutableList<String> = ArrayList()): Pair<List<String>, Long> =
        Kotmpeg.openDemuxer(memoryInput(bytes)) { warnings += it }.use { demuxer ->
            val packets = generateSequence { demuxer.readPacket() }
                .map { "${it.trackId}/${it.ptsUs}/${it.isKeyFrame}/${it.data.contentHashCode()}" }
                .toList()
            packets to demuxer.durationUs
        }

    private fun file(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }

    /**
     * Repara [bytes] y devuelve lo que se lee del resultado, comprobando que no da avisos y que una
     * segunda reparación no lo toca. En memoria: aquí no se prueba la E/S de archivos, y con disco y
     * `force()` en cada uno de los cientos de puntos la clase tardaba más de un minuto.
     */
    private fun repairAndRead(label: String, bytes: ByteArray): Pair<List<String>, Long> {
        val after = RecordingFileChannel.repairRecorded(bytes).second.bytes()
        val (second, again) = RecordingFileChannel.repairRecorded(after)
        assertFalse(second.repaired, "$label: la segunda reparación volvió a cambiar el archivo")
        assertContentEquals(after, again.bytes(), "$label: la segunda reparación tocó el archivo")
        val warnings = ArrayList<String>()
        val result = read(after, warnings)
        assertEquals(emptyList(), warnings, "$label: el archivo reparado da avisos")
        return result
    }

    /** Si la reparación acepta [bytes], probado sobre una copia en memoria. */
    private fun repairable(bytes: ByteArray): Boolean = runCatching { RecordingFileChannel.repairRecorded(bytes) }.isSuccess

    private fun assertRejectedUntouched(label: String, bytes: ByteArray) {
        val target = file("rechazado.mp4", bytes)
        assertFailsWith<IllegalArgumentException>(label) { Kotmpeg.repair(target) }
        assertContentEquals(bytes, target.readBytes(), "$label: se modificó al rechazarlo")
    }

    /**
     * La reparación también puede morir a medias. Para cada corte de una grabación, se repara sobre
     * un canal que registra cada operación y se reconstruye el archivo como quedaría si la reparación
     * muriera en cada punto —antes y después de cada parche, y dentro del `mfra`—; relanzada sobre
     * él, tiene que terminar, leerse sin avisos y dar los mismos paquetes y la misma duración que la
     * reparación sin cortes. En particular, cortada entre el parche que alarga la última muestra y el
     * del tamaño del `mdat`, la segunda pasada la volvía a alargar.
     */
    private fun interruptedRepairResumes(options: MuxerOptions.() -> Unit) {
        val recording = record(options)
        val cuts = recording.cuts()
        var resumed = 0
        for (cut in cuts.filterIndexed { i, _ -> i % 6 == 0 }) {
            val bytes = recording.channel.cutAfter(cut)
            val label = "grabación cortada en $cut"
            val (result, channel) = runCatching { RecordingFileChannel.repairRecorded(bytes) }.getOrNull() ?: continue
            if (!result.repaired) continue
            val expected = read(channel.bytes())
            for (point in cutPoints(channel)) {
                val partial = channel.cutAfter(point)
                assertEquals(expected, repairAndRead("$label, reparación cortada en $point", partial), "$label, reparación cortada en $point")
                resumed++
            }
        }
        assertTrue(resumed > 100, "solo $resumed reparaciones relanzadas")
    }

    @Test
    fun `an interrupted repair resumes to the same file`() {
        interruptedRepairResumes {}
    }

    @Test
    fun `an interrupted repair with unsigned offsets resumes to the same file`() {
        interruptedRepairResumes { nonNegativeCompositionOffsets = true }
    }

    @Test
    fun `an interrupted repair with segment indexes resumes to the same file`() {
        interruptedRepairResumes { segmentIndex = true }
    }

    /**
     * Con desfases sin signo, todos los tiempos de presentación llevan sumado el `media_time` de la
     * lista de edición. La reparación los comparaba con el tiempo de decodificación sin descontarlo,
     * y alargaba la última muestra ese desplazamiento de más, faltaran B-frames o no: la duración no
     * era la del remux. El remux se hace también sin signo, porque el demuxer descuenta el
     * `media_time` del DTS y la duración que da sale entonces del `mvhd`, en milisegundos.
     */
    @Test
    fun `a cut fmp4 with unsigned offsets is repaired like its remux`() {
        val recording = record { nonNegativeCompositionOffsets = true }
        var compared = 0
        for (cut in recording.cuts()) {
            val bytes = recording.channel.cutAfter(cut)
            val label = "corte en $cut"
            if (!repairable(bytes)) continue
            val (packets, duration) = repairAndRead(label, bytes)
            if (packets.isEmpty()) continue
            val remuxed = File(dir, "remux.mp4")
            Kotmpeg.remux(file("cortado.mp4", bytes), remuxed) { fragmented = true; nonNegativeCompositionOffsets = true }
            val (expected, expectedDuration) = read(remuxed.readBytes())
            assertEquals(expected, packets, "$label: los paquetes no son los del remux")
            assertEquals(expectedDuration, duration, "$label: la duración no es la del remux")
            compared++
        }
        assertTrue(compared > 50, "solo $compared cortes comparados")
    }

    /** El archivo completo de [recording] sin el `mfra` del final: todos sus fragmentos enteros, como si se cortara justo antes de cerrarlo. */
    private fun withoutMfra(recording: Recording): ByteArray {
        val bytes = recording.channel.bytes()
        return bytes.copyOf(topLevel(bytes).first { it.first == "mfra" }.second)
    }

    /**
     * Un final de ceros —lo que deja un sistema de archivos que reservó el espacio sin llegar a
     * escribirlo, en un corte de luz— cuenta como corte, como en MKV: tras un fragmento entero, o
     * tras un `moof` sin su `mdat`, da lo mismo que el archivo sin ellos. Unos ceros seguidos de otra
     * cosa no son un final, y se rechazan. (Unos ceros que caen dentro de un `mdat` no se distinguen
     * de datos, igual que en MKV.)
     */
    @Test
    fun `a zero tail after the last fragment is treated as the cut`() {
        for (options in listOf<MuxerOptions.() -> Unit>({}, { segmentIndex = true })) {
            val bytes = withoutMfra(record(options))
            val ends = topLevel(bytes).filter { it.first == "moof" || it.first == "mdat" }.map { it.second + it.third }
            for (end in ends) {
                val cut = bytes.copyOf(end)
                val expected = repairAndRead("corte en $end", cut)
                assertEquals(expected, repairAndRead("corte en $end con ceros", cut + ByteArray(5_000)), "corte en $end con ceros")
            }
            assertTrue(ends.size >= 10, "solo ${ends.size} cortes")
            assertRejectedUntouched("ceros y luego datos", bytes + ByteArray(5_000) + byteArrayOf(1, 2, 3))
        }
    }

    /** Las cajas de primer nivel enteras de [bytes]: tipo, posición y tamaño. */
    private fun topLevel(bytes: ByteArray): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var p = 0
        while (p + 8 <= bytes.size) {
            val size = u32(bytes, p).toInt()
            if (size < 8 || p + size > bytes.size) break
            out += Triple(String(bytes, p + 4, 4, Charsets.US_ASCII), p, size)
            p += size
        }
        return out
    }

    private fun u32(b: ByteArray, at: Int): Long = (0 until 4).fold(0L) { v, k -> (v shl 8) or (b[at + k].toLong() and 0xFF) }

    private fun putU32(b: ByteArray, at: Int, value: Long) {
        for (k in 0 until 4) b[at + k] = (value ushr (24 - 8 * k)).toByte()
    }

    /**
     * Un tamaño de `mdat` o de `moof` dañado al alza en mitad del archivo parecía un corte: todo lo
     * de detrás se truncaba. Ahora se rechaza sin tocar nada, porque lo que ese `mdat` declara no es
     * lo que describen sus `trun`, o porque dentro de ese `moof` empieza un `mdat`.
     */
    @Test
    fun `a size damaged upward in the middle is rejected instead of cutting everything after it`() {
        val bytes = withoutMfra(record())
        assertTrue(repairable(bytes), "sin dañar, se repara")
        val boxes = topLevel(bytes)
        assertTrue(boxes.count { it.first == "moof" } >= 4)
        val mdat = boxes.filter { it.first == "mdat" }[1]
        val damagedMdat = bytes.copyOf().also { putU32(it, mdat.second, mdat.third + (1L shl 28)) }
        assertRejectedUntouched("mdat dañado", damagedMdat)
        val moof = boxes.filter { it.first == "moof" }[1]
        val damagedMoof = bytes.copyOf().also { putU32(it, moof.second, moof.third + (1L shl 28)) }
        assertRejectedUntouched("moof dañado", damagedMoof)
    }

    private fun u32Bytes(value: Long): ByteArray = ByteArray(4) { (value ushr (24 - 8 * it)).toByte() }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { for (part in parts) write(part) }.toByteArray()
        return u32Bytes(8L + body.size) + type.toByteArray(Charsets.US_ASCII) + body
    }

    private fun fullBox(type: String, flags: Int, vararg parts: ByteArray): ByteArray =
        box(type, u32Bytes(flags.toLong() and 0xFFFFFF), *parts)

    /** Una muestra de un `trun` hecho a mano: duración, tamaño y flags. */
    private class Entry(val duration: Long, val size: Long, val flags: Long)

    /** Un `trun` con duración, tamaño y flags por muestra, y `data_offset` si no es `null`. */
    private fun trun(dataOffset: Long?, entries: List<Entry>): ByteArray {
        val fields = ByteArrayOutputStream()
        fields.write(u32Bytes(entries.size.toLong()))
        if (dataOffset != null) fields.write(u32Bytes(dataOffset))
        for (e in entries) {
            fields.write(u32Bytes(e.duration))
            fields.write(u32Bytes(e.size))
            fields.write(u32Bytes(e.flags))
        }
        return fullBox("trun", (if (dataOffset != null) 0x001 else 0) or 0x700, fields.toByteArray())
    }

    private fun traf(trackId: Long, tfhdFlags: Int, baseDataOffset: Long?, decodeTime: Long, vararg truns: ByteArray): ByteArray {
        val tfhd = if (baseDataOffset != null) {
            fullBox("tfhd", tfhdFlags or 0x01, u32Bytes(trackId), u32Bytes(baseDataOffset ushr 32), u32Bytes(baseDataOffset))
        } else {
            fullBox("tfhd", tfhdFlags, u32Bytes(trackId))
        }
        return box("traf", tfhd, fullBox("tfdt", 0, u32Bytes(decodeTime)), *truns)
    }

    /** La cabecera (`ftyp` + `moov`) de una grabación de vídeo y audio: pista 1 a 90 kHz y pista 2 a 48 kHz. */
    private fun header(): ByteArray {
        val bytes = record().channel.bytes()
        return bytes.copyOf(topLevel(bytes).first { it.first == "moof" }.second)
    }

    private val key = 0x02000000L
    private val nonKey = 0x01010000L

    /**
     * Un fMP4 de otro programa puede no declarar `default-base-is-moof`: entonces los datos del
     * primer `traf` cuentan desde el `moof` y los de cada uno de los siguientes desde el final de los
     * del anterior, y un `trun` sin `data_offset` sigue donde acabó el anterior. La reparación
     * contaba siempre desde el `moof`, y rechazaba un archivo válido que el demuxer lee bien.
     */
    @Test
    fun `fragments that chain their track data are repaired as the demuxer reads them`() {
        val head = header()
        val videoEntries = listOf(Entry(3000, 100, key), Entry(3000, 50, nonKey), Entry(3000, 60, nonKey))
        val audioEntries = listOf(Entry(1024, 20, key), Entry(1024, 21, key), Entry(1024, 22, key))
        val dataSize = (videoEntries + audioEntries).sumOf { it.size }.toInt()

        fun moof(sequence: Int, start: Long, chainedOffset: Long?, absolute: Boolean, moofSize: Long): ByteArray {
            val time = sequence - 1L
            val dataStart = start + moofSize + 8
            val videoTraf = if (absolute) {
                traf(1, 0, dataStart, time * 9000, trun(0, videoEntries))
            } else {
                traf(1, 0, null, time * 9000, trun(moofSize + 8, videoEntries))
            }
            val audioTraf = when {
                absolute -> traf(2, 0, dataStart + 210, time * 3072, trun(0, audioEntries))
                chainedOffset != null -> traf(2, 0, null, time * 3072, trun(chainedOffset, audioEntries))
                else -> traf(2, 0, null, time * 3072, trun(null, audioEntries))
            }
            return box("moof", fullBox("mfhd", 0, u32Bytes(sequence.toLong())), videoTraf, audioTraf)
        }

        for ((name, chained, absolute) in listOf(Triple("sin data_offset", null, false), Triple("data_offset 0", 0L, false), Triple("base absoluta", null, true))) {
            val out = ByteArrayOutputStream().apply { write(head) }
            val random = Random(3)
            for (sequence in 1..3) {
                val start = out.size().toLong()
                val size = moof(sequence, start, chained, absolute, 0).size.toLong()
                out.write(moof(sequence, start, chained, absolute, size))
                out.write(box("mdat", ByteArray(dataSize).also { random.nextBytes(it) }))
            }
            val complete = out.toByteArray()
            val cut = complete.copyOf(complete.size - 30)
            val (expected, _) = read(complete)
            val (packets, _) = repairAndRead(name, cut)
            assertEquals(18, expected.size, name)
            assertEquals(expected.size - 2, packets.size, "$name: se esperaban todas las muestras menos las dos de audio cortadas")
            assertTrue(expected.containsAll(packets), "$name: la reparación leyó otras muestras")
        }
    }

    /**
     * Muestras de tamaño cero en el fragmento cortado. No tienen datos, pero su posición decide hasta
     * dónde se conserva el archivo: con un `data_offset` negativo caía dentro del `moov` y truncar ahí
     * destruía la cabecera; sin `data_offset`, en el principio del `moof`, y los parches posteriores
     * se escribían más allá del final. Ahora se rechazan sin tocar nada.
     */
    @Test
    fun `empty samples outside their mdat are rejected before touching the file`() {
        val head = header()
        val empty = listOf(Entry(3000, 0, key), Entry(3000, 0, nonKey))
        for ((name, dataOffset) in listOf("data_offset negativo" to -(head.size - 50L), "sin data_offset" to null)) {
            val moof = box("moof", fullBox("mfhd", 0, u32Bytes(1)), traf(1, 0x020000, null, 0, trun(dataOffset?.let { it and 0xFFFFFFFFL }, empty)))
            val mdat = u32Bytes(8 + 1000) + "mdat".toByteArray(Charsets.US_ASCII) + ByteArray(100) { 7 }
            assertRejectedUntouched(name, head + moof + mdat)
        }
    }

    /**
     * Un `trun` sin campos por muestra no ocupa más por declarar más muestras. El tope era de un
     * millón por `trun`, y diez de 16 bytes creaban diez millones de objetos: un `OutOfMemoryError`.
     * Ahora el tope es por `moof`, y sin tamaño por muestra el recuento no puede pasar de los bytes
     * del `mdat`.
     *
     * Lo que se reserva se mide, porque rechazar no basta: el `OutOfMemoryError` llegaba antes que
     * el rechazo. Se mide la segunda pasada, ya con las clases cargadas. Los diez `trun` no caben en
     * los 16 bytes de su `mdat` y se rechazan sin leer ninguna muestra: el tope es de pocos MiB,
     * cuando un millón de muestras en memoria son unos 70. En el otro caso el primer `trun` cabe en
     * el tope del `moof` y se lee entero antes de que el segundo lo rebase, así que su tope es lo que
     * cuestan [FragmentWalker.MAX_TRUN_SAMPLES] muestras, holgado a 96 bytes cada una, y no el doble.
     */
    @Test
    fun `absurd sample counts are rejected without allocating them`() {
        val head = header()
        fun countOnly(count: Long, dataOffset: Long): ByteArray =
            fullBox("trun", 0x001, u32Bytes(count), u32Bytes(dataOffset))

        fun fragment(truns: Int, count: Long, payload: Int): ByteArray {
            fun moof(offset: Long) = box("moof", fullBox("mfhd", 0, u32Bytes(1)), traf(1, 0x020000, null, 0, *Array(truns) { countOnly(count, offset) }))
            val size = moof(0).size.toLong()
            return moof(size + 8) + box("mdat", ByteArray(payload))
        }
        val cases = listOf(
            Triple("diez trun de un millón de muestras vacías", head + fragment(10, 0x100000, 16), 4L shl 20),
            Triple("dos millones de muestras en un moof", head + fragment(2, 0x100000, 3 shl 20), FragmentWalker.MAX_TRUN_SAMPLES * 96L),
        )
        for ((label, bytes, cap) in cases) {
            assertRejectedUntouched(label, bytes)
            val allocated = allocatedBytes { assertRejectedUntouched(label, bytes) }
            assertTrue(allocated < cap, "$label: ${allocated / 1024} KiB reservados; el tope es ${cap / 1024} KiB")
        }
    }
}

/**
 * Puntos de corte de las operaciones de [channel]: justo antes, en y justo después del final de
 * cada una, y dentro de las escrituras que se pueden partir.
 */
private fun cutPoints(channel: RecordingFileChannel): List<Long> {
    val points = sortedSetOf<Long>()
    var before = 0L
    for (write in channel.writes) {
        val end = before + write.cost
        points += listOf(end - 1, end, end + 1)
        if (write.cost > RecordingFileChannel.ATOMIC_BYTES) points += listOf(before + 1, before + write.cost / 2)
        before = end
    }
    return points.filter { it in 0..channel.totalBytes }
}
