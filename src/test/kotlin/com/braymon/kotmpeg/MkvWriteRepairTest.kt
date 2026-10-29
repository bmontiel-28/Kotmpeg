package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.ebml.EbmlReader
import com.braymon.kotmpeg.ebml.EbmlWriter
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.io.RepairTarget
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * La reparación en el sitio de un MKV frente a lo que no cubre `InterruptedRecordingTest`: que la
 * propia reparación se interrumpa, y archivos que el muxer no escribe pero a `repair` le pueden
 * llegar.
 *
 * La promesa de `Kotmpeg.repair` es que, si se corta a medias, se puede volver a lanzar. Aquí se
 * comprueba en serio: cada reparación se hace sobre un [RecordingFileChannel], se corta entre cada
 * dos operaciones y dentro de las escrituras grandes, se relanza sobre lo que quedó y el resultado
 * tiene que ser el de la reparación sin cortes. Y como un lector puede abrir el archivo en
 * cualquiera de esos estados, ninguno —tampoco los de la reparación relanzada— puede tener el
 * índice roto.
 */
class MkvWriteRepairTest {

    private val video = TrackInfo.Video(VideoCodec.H264, 320, 240) {
        frameRate = 30.0
        codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22)))
    }
    private val audio = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) }

    /**
     * Cinco segundos de vídeo a 30 fps con B-frames —I P B B en orden de decodificación, un keyframe
     * por segundo— y AAC a 48 kHz, intercalados por tiempo de decodificación y con tamaños al azar.
     * Es la grabación de `InterruptedRecordingTest`.
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

    /** Una grabación registrada escritura a escritura, y cuántas de ellas hizo `start()`. */
    private class Recording(val channel: RecordingFileChannel, val startWrites: Int)

    private fun record(options: MuxerOptions.() -> Unit = {}): Recording {
        val channel = RecordingFileChannel()
        var startWrites = 0
        MkvMuxer(SeekableOutput(channel), options).use { muxer ->
            muxer.addTrack(video)
            muxer.addTrack(audio)
            muxer.start()
            startWrites = channel.writes.size
            for (packet in syntheticPackets()) muxer.writePacket(packet)
        }
        return Recording(channel, startWrites)
    }

    /**
     * Cortes de una grabación para reparar: repartidos por ella, y justo antes de la última
     * escritura de `stop()` —el tamaño del `Segment`—, con todo lo demás de `stop()` ya hecho, el
     * índice movido al final incluido.
     */
    private fun recordingCuts(recording: Recording): List<Long> {
        val total = recording.channel.totalBytes
        return listOf(total / 5, total * 2 / 5, total * 3 / 5, total * 3 / 4, total - 1)
    }

    /**
     * Puntos de corte de las operaciones de [channel]: justo antes, en y justo después del final de
     * cada una, y dentro de las que se pueden partir.
     */
    private fun cuts(channel: RecordingFileChannel): List<Long> {
        val points = sortedSetOf(0L)
        var before = 0L
        for (write in channel.writes) {
            val end = before + write.cost
            points += listOf(end - 1, end, end + 1)
            if (write.cost > RecordingFileChannel.ATOMIC_BYTES) points += listOf(before + 1, before + write.cost / 2)
            before = end
        }
        return points.filter { it in 0..channel.totalBytes }
    }

    /** Pista, tiempo, keyframe y contenido de cada paquete, en orden, y la duración que da el demuxer. */
    private fun read(bytes: ByteArray, warnings: MutableList<String> = ArrayList()): Pair<List<String>, Long> =
        Kotmpeg.openDemuxer(SeekableInput(MemoryFileChannel(bytes))) { warnings += it }.use { demuxer ->
            val packets = generateSequence { demuxer.readPacket() }
                .map { "${it.trackId}/${it.ptsUs}/${it.isKeyFrame}/${it.data.contentHashCode()}" }
                .toList()
            packets to demuxer.durationUs
        }

    /** Como [read], exigiendo que no haya ningún aviso. */
    private fun readClean(bytes: ByteArray, label: String): Pair<List<String>, Long> {
        val warnings = ArrayList<String>()
        val result = read(bytes, warnings)
        assertEquals(emptyList(), warnings, "$label: avisos al leer")
        return result
    }

    /** Abre [bytes] como lo haría un lector y exige que el índice que encuentre se pueda usar. */
    private fun assertIndexUsable(bytes: ByteArray, label: String) {
        val warnings = ArrayList<String>()
        Kotmpeg.openDemuxer(SeekableInput(MemoryFileChannel(bytes))) { warnings += it }.close()
        assertTrue(warnings.none { "índice" in it || "Cues" in it }, "$label: $warnings")
    }

    /** Un elemento de nivel 1: dónde empieza él, dónde sus datos, y cuánto miden (-1 si no se sabe). */
    private class Element(val id: Long, val start: Int, val dataStart: Int, val size: Long) {
        val end: Int get() = (dataStart + size).toInt()
    }

    /**
     * Dónde empiezan los datos del segmento de [bytes] y sus elementos de nivel 1, hasta el primero
     * de tamaño desconocido (incluido) o que se salga del archivo (excluido).
     */
    private fun topLevel(bytes: ByteArray): Pair<Int, List<Element>> {
        val input = SeekableInput(MemoryFileChannel(bytes))
        val reader = EbmlReader(input)
        reader.skip(reader.readElement())
        val segmentData = reader.readElement().dataStart.toInt()
        val elements = ArrayList<Element>()
        var pos = segmentData.toLong()
        while (pos < bytes.size) {
            input.position = pos
            val el = runCatching { reader.readElement() }.getOrNull() ?: break
            val element = Element(el.id, pos.toInt(), el.dataStart.toInt(), el.size)
            if (el.size >= 0 && element.end > bytes.size) break
            elements += element
            if (el.size < 0) break
            pos = el.dataEnd
        }
        return segmentData to elements
    }

    /** [bytes] con la disposición de la 4.0: el hueco del `SeekHead` es un `Void` de 120 bytes. */
    private fun as40(bytes: ByteArray): ByteArray {
        val (segmentData, elements) = topLevel(bytes)
        assertEquals(MatroskaIds.SEEK_HEAD, elements.first().id)
        return bytes.copyOf().also { EbmlWriter.encodeVoid(120).copyInto(it, segmentData) }
    }

    private fun idBytes(id: Long): ByteArray {
        val length = when {
            id <= 0xFF -> 1
            id <= 0xFFFF -> 2
            id <= 0xFFFFFF -> 3
            else -> 4
        }
        return ByteArray(length) { i -> (id ushr (8 * (length - 1 - i))).toByte() }
    }

    private fun element(id: Long, payload: ByteArray): ByteArray =
        idBytes(id) + EbmlWriter.encodeVintSize(payload.size.toLong(), EbmlWriter.vintSizeLength(payload.size.toLong())) + payload

    /** Repara [bytes] sobre un canal que registra las operaciones, que se devuelve también si rechaza. */
    private fun repairOn(bytes: ByteArray): Pair<Result<RepairResult>, RecordingFileChannel> {
        val channel = RecordingFileChannel(bytes)
        return runCatching { RepairTarget.open(channel).use { Kotmpeg.repair(it) } } to channel
    }

    private fun assertRejectedUntouched(bytes: ByteArray, label: String) {
        val (result, channel) = repairOn(bytes)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException, "$label: $result")
        assertTrue(channel.writes.isEmpty(), "$label: tocó el archivo al rechazarlo")
        assertContentEquals(bytes, channel.bytes(), label)
    }

    /**
     * La prueba central: una reparación cortada en cualquier punto se puede relanzar, y acaba igual
     * que si no se hubiera cortado —los mismos paquetes y la misma duración, sin avisos—. Se recorren
     * las cuatro disposiciones que puede encontrar: la reserva por defecto, en la que el índice se
     * amplía; la mínima, que se llena y lleva el índice al final; sin reserva, y la de la 4.0, con
     * el hueco del `SeekHead` como `Void`. En cada estado intermedio, de la primera reparación y de
     * la relanzada, un lector tiene que encontrar un índice utilizable o ninguno.
     */
    @Test
    fun `a repair cut anywhere and relaunched ends like the uninterrupted one`() {
        val noReserve = record { cuesReserveBytes = 0 }
        val inputs = ArrayList<Pair<String, ByteArray>>()
        for ((name, recording) in listOf("reserva por defecto" to record(), "reserva mínima" to record { cuesReserveBytes = 64 }, "sin reserva" to noReserve)) {
            for (cut in recordingCuts(recording)) inputs += "$name, grabación cortada en $cut" to recording.channel.cutAfter(cut)
        }
        for (cut in recordingCuts(noReserve)) inputs += "disposición 4.0, grabación cortada en $cut" to as40(noReserve.channel.cutAfter(cut))

        var relaunched = 0
        for ((label, bytes) in inputs) {
            val (result, channel) = RecordingFileChannel.repairRecorded(bytes)
            assertTrue(result.repaired, label)
            val reference = readClean(channel.bytes(), label)
            assertTrue(reference.first.isNotEmpty(), label)
            for (cut in cuts(channel)) {
                val partial = channel.cutAfter(cut)
                val at = "$label, reparación cortada en $cut de ${channel.totalBytes}"
                assertIndexUsable(partial, at)
                val (again, relaunch) = try {
                    RecordingFileChannel.repairRecorded(partial)
                } catch (e: IllegalArgumentException) {
                    throw AssertionError("$at: la reparación relanzada lo rechaza: ${e.message}", e)
                }
                val final = relaunch.bytes()
                assertEquals(reference, readClean(final, at), "$at: no acaba como la reparación sin cortes")
                assertTrue(abs(again.durationUs - result.durationUs) < 1_000, "$at: $again frente a $result")
                assertFalse(RecordingFileChannel.repairRecorded(final).first.repaired, "$at: queda sin cerrar")
                for (second in cuts(relaunch)) assertIndexUsable(relaunch.cutAfter(second), "$at, relanzada y cortada en $second")
                relaunched++
            }
        }
        assertTrue(relaunched > 300, "solo $relaunched relanzamientos")
    }

    /**
     * Una reparación de un MKV de la 4.0 escribe el `SeekHead` dentro de su `Void` y lo confirma
     * convirtiendo el `Void` en uno de 2 bytes. Cortada justo después, el `SeekHead` está dos bytes
     * por detrás del principio del segmento, y el relanzamiento lo tiene que reconocer ahí.
     */
    @Test
    fun `a 4_0 file whose repair stopped after committing the seek head can be repaired again`() {
        val recording = record { cuesReserveBytes = 0 }
        val bytes = as40(recording.channel.cutAfter(recording.channel.totalBytes * 2 / 3))
        val (segmentData, _) = topLevel(bytes)
        val (_, channel) = RecordingFileChannel.repairRecorded(bytes)
        val commit = channel.boundaries()[channel.writes.indexOfFirst { it.position == segmentData + 1L && it.bytes.size == 1 }]
        val (again, relaunch) = RecordingFileChannel.repairRecorded(channel.cutAfter(commit))
        assertTrue(again.repaired)
        assertEquals(readClean(channel.bytes(), "sin cortes"), readClean(relaunch.bytes(), "relanzada"))
    }

    /**
     * Dónde empieza la carga del `Void` libre de [reserve], el último de sus hijos: lo único de la
     * reserva que un lector no está usando.
     */
    private fun freePayloadStart(bytes: ByteArray, reserve: Element): Int {
        val input = SeekableInput(MemoryFileChannel(bytes))
        val reader = EbmlReader(input)
        var pos = reserve.dataStart.toLong()
        var free = reserve.end
        while (pos < reserve.end) {
            input.position = pos
            val child = reader.readElement()
            if (child.id == MatroskaIds.VOID && child.dataEnd == reserve.end.toLong()) free = child.dataStart.toInt()
            pos = child.dataEnd
        }
        return free
    }

    /**
     * Cuando lo publicado en la reserva es el principio del índice pero el resto no cabe añadiéndolo,
     * la reparación hace lo que `stop()`: escribe el índice al final, mueve la entrada del `SeekHead`
     * y anula la reserva, con parches de 8 y 9 bytes. Lo que no hace es reescribir la reserva, que es
     * el índice que un lector está usando: sobre ella solo caben esos parches y, si el índice sí
     * cabe, lo que se añade dentro de su `Void` libre.
     */
    @Test
    fun `a repair whose index outgrows the reserve does not rewrite the published one`() {
        val recording = record { cuesReserveBytes = 64 }
        var movedToEnd = 0
        for (cut in recordingCuts(recording).dropLast(1)) {
            val bytes = recording.channel.cutAfter(cut)
            val (_, elements) = topLevel(bytes)
            val reserve = elements.first { it.id == MatroskaIds.CUES }
            val free = freePayloadStart(bytes, reserve)
            val (result, channel) = RecordingFileChannel.repairRecorded(bytes)
            assertTrue(result.repaired)
            val overPublished = channel.writes.filter { it.truncatedTo < 0 && it.position < free && it.position + it.bytes.size > reserve.start }
            assertTrue(overPublished.all { it.bytes.size <= RecordingFileChannel.ATOMIC_BYTES }, "corte en $cut: escribe ${overPublished.map { it.bytes.size }} bytes sobre lo publicado")
            if (topLevel(channel.bytes()).second.last().id == MatroskaIds.CUES) movedToEnd++
        }
        assertTrue(movedToEnd >= 2, "solo $movedToEnd reparaciones llevaron el índice al final")
    }

    /**
     * Un `CuePoint` publicado con un campo de más de 8 bytes no se puede leer como entero. Es un
     * índice publicado inservible, y no un archivo irreparable: se deja de usar y se escribe otro.
     */
    @Test
    fun `an unreadable published cue point does not block the repair`() {
        val recording = record()
        val bytes = recording.channel.cutAfter(recording.channel.totalBytes * 3 / 4)
        val (_, elements) = topLevel(bytes)
        val reserve = elements.first { it.id == MatroskaIds.CUES }
        val cueTime = (reserve.dataStart until reserve.end).first { i ->
            bytes[i] == 0xBB.toByte() && bytes[i + 2] == 0xB3.toByte()
        } + 2
        bytes[cueTime + 1] = 0x89.toByte()
        val (result, channel) = repairOn(bytes)
        assertTrue(result.getOrThrow().repaired)
        val expected = RecordingFileChannel.repairRecorded(recording.channel.cutAfter(recording.channel.totalBytes * 3 / 4)).second.bytes()
        assertEquals(readClean(expected, "original").first, readClean(channel.bytes(), "con el cue dañado").first)
    }

    /**
     * Un hijo de `Seek` o de `Info` que se sale de su padre no es algo que la reparación pueda
     * parchear: la posición del índice o la duración caerían fuera del elemento, encima del
     * siguiente. Se rechaza sin tocar nada.
     */
    @Test
    fun `seek and info children that cross their parent are rejected untouched`() {
        val recording = record()
        val bytes = recording.channel.cutAfter(recording.channel.totalBytes * 3 / 4)
        val (segmentData, elements) = topLevel(bytes)

        val seek = bytes.copyOf()
        val cuesSeek = segmentData + 12 + 2 * 21
        assertEquals(0x4D.toByte(), seek[cuesSeek])
        seek[cuesSeek + 2] = (0x80 or 14).toByte()
        EbmlWriter.encodeVintSize(cuesSeek + 3 + 14 - segmentData - 12L, 8).copyInto(seek, segmentData + 4)
        byteArrayOf(0xEC.toByte(), (0x80 or (120 - (cuesSeek + 17 - segmentData) - 2)).toByte(), 0, 0).copyInto(seek, cuesSeek + 17)
        assertTrue(runCatching { read(seek) }.isSuccess, "la variante se tiene que poder abrir")
        assertRejectedUntouched(seek, "SeekPosition fuera de su Seek")

        val info = elements.first { it.id == MatroskaIds.INFO }
        val duration = bytes.copyOf()
        assertEquals(0x44.toByte(), duration[info.end - 11])
        EbmlWriter.encodeVintSize(info.size - 4, 8).copyInto(duration, info.start + 4)
        byteArrayOf(0xEC.toByte(), 0x82.toByte(), 0, 0).copyInto(duration, info.end - 4)
        assertTrue(runCatching { read(duration) }.isSuccess, "la variante se tiene que poder abrir")
        assertRejectedUntouched(duration, "Duration fuera de Info")
    }

    /**
     * Un `Void` enorme en el hueco del `SeekHead` de un MKV de la 4.0 no se convierte en un
     * `SeekHead` igual de enorme: se escribe uno del tamaño de siempre y el resto sigue siendo
     * relleno.
     */
    @Test
    fun `a huge void in the seek head slot gets a seek head of the usual size`() {
        val recording = record { cuesReserveBytes = 0 }
        val bytes = recording.channel.cutAfter(recording.channel.totalBytes / 2)
        val (segmentData, _) = topLevel(bytes)
        val huge = bytes.copyOf(segmentData) + EbmlWriter.encodeVoid(8 shl 20) + bytes.copyOfRange(segmentData + 120, bytes.size)
        val (result, channel) = repairOn(huge)
        assertTrue(result.getOrThrow().repaired)
        val slotWrites = channel.writes.filter { it.truncatedTo < 0 && it.position < segmentData + (8 shl 20) }
        assertTrue(slotWrites.all { it.bytes.size < 1024 }, "escrituras en el hueco: ${slotWrites.map { it.bytes.size }}")
        assertEquals(read(as40(bytes).let { RecordingFileChannel.repairRecorded(it).second.bytes() }).first, readClean(channel.bytes(), "Void enorme").first)
    }

    /**
     * Un `Void` entre dos clusters es relleno, no el final de la grabación: lo que viene detrás se
     * conserva. Un `Cues` entre clusters no se sabría conservar junto al índice nuevo, y se rechaza
     * en vez de tirar en silencio todo lo que hay detrás.
     */
    @Test
    fun `what lies between clusters is kept or rejected, never silently dropped`() {
        val recording = record { cuesReserveBytes = 0 }
        val bytes = recording.channel.cutAfter(recording.channel.totalBytes * 3 / 4)
        val (_, elements) = topLevel(bytes)
        val second = elements.filter { it.id == MatroskaIds.CLUSTER }[1]
        fun inserted(extra: ByteArray) = bytes.copyOf(second.start) + extra + bytes.copyOfRange(second.start, bytes.size)

        val expected = readClean(RecordingFileChannel.repairRecorded(bytes).second.bytes(), "original")
        val withVoid = RecordingFileChannel.repairRecorded(inserted(EbmlWriter.encodeVoid(10))).second.bytes()
        assertEquals(expected.first, readClean(withVoid, "con un Void entre clusters").first)

        val cues = element(MatroskaIds.CUES, element(MatroskaIds.CUE_POINT, element(MatroskaIds.CUE_TIME, byteArrayOf(0))))
        assertRejectedUntouched(inserted(cues), "con un Cues entre clusters")
    }

    /**
     * Un `Cues` entero al final, igual al que la reparación calcularía —lo deja así `stop()` cortado
     * justo antes de escribir el tamaño del `Segment`—, ya es el índice: no se trunca ni se reescribe.
     */
    @Test
    fun `a complete index at the end is kept as it is`() {
        for (reserve in listOf(64, 0)) {
            val recording = record { cuesReserveBytes = reserve }
            val bytes = recording.channel.cutAfter(recording.channel.totalBytes - 1)
            val cues = topLevel(bytes).second.last()
            assertEquals(MatroskaIds.CUES, cues.id)
            assertEquals(bytes.size, cues.end)
            val (result, channel) = RecordingFileChannel.repairRecorded(bytes)
            assertTrue(result.repaired)
            assertEquals(0L, result.discardedBytes, "reserva $reserve")
            assertTrue(channel.writes.none { it.truncatedTo >= 0 || it.position >= cues.start }, "reserva $reserve: reescribe el índice")
        }
    }

    /**
     * Un cluster de tamaño desconocido acaba donde empieza cualquier elemento de nivel 1, como dice
     * Matroska y lee el demuxer, y no solo ante otro cluster: un `Cues` o unos `Tags` detrás no son
     * parte de él.
     */
    @Test
    fun `an unknown-size cluster ends at any level 1 element`() {
        val recording = record { cuesReserveBytes = 0 }
        val (cut, repairedCut) = (60 until 90).asSequence()
            .map { recording.channel.cutAfter(recording.channel.totalBytes * it / 100) }
            .map { it to RecordingFileChannel.repairRecorded(it) }
            .first { (bytes, repair) -> topLevel(bytes.copyOf((bytes.size - repair.first.discardedBytes).toInt())).second.last().size < 0 }
        val (result, repaired) = repairedCut
        val blocksEnd = (cut.size - result.discardedBytes).toInt()
        val prefix = cut.copyOf(blocksEnd)

        val index = topLevel(repaired.bytes()).second.last()
        assertEquals(MatroskaIds.CUES, index.id)
        val withIndex = prefix + repaired.bytes().copyOfRange(index.start, index.end)
        val (_, channel) = RecordingFileChannel.repairRecorded(withIndex)
        val after = topLevel(channel.bytes()).second
        val lastCluster = after.last { it.id == MatroskaIds.CLUSTER }
        assertEquals(blocksEnd, lastCluster.end, "el cluster se tragó el índice que tenía detrás")
        assertEquals(1, after.count { it.id == MatroskaIds.CUES })
        readClean(channel.bytes(), "con el índice detrás")

        val tags = element(MatroskaIds.TAGS, element(0x7373L, ByteArray(0)))
        assertRejectedUntouched(prefix + tags, "con Tags detrás")
    }

    /**
     * Un corte durante `start()`, una vez que la cabecera con `Info` y `Tracks` está en el archivo,
     * se puede reparar: el `SeekHead` llega en la misma escritura que la cabecera, y no después,
     * sobre su `Void`, donde un corte a mitad dejaba un `SeekHead` con ceros que nada sabía leer.
     */
    @Test
    fun `a recording cut during start is repairable once its header is in the file`() {
        val recording = record()
        val channel = recording.channel
        val (_, elements) = topLevel(channel.bytes())
        val tracksEnd = elements.first { it.id == MatroskaIds.TRACKS }.end
        val startEnd = channel.boundaries()[recording.startWrites - 1]
        var repaired = 0
        for (cut in cuts(channel).filter { it <= startEnd }) {
            val bytes = channel.cutAfter(cut)
            if (bytes.size < tracksEnd) continue
            val (result, after) = repairOn(bytes)
            assertTrue(result.isSuccess, "corte en $cut de start(): $result")
            read(after.bytes())
            repaired++
        }
        assertTrue(repaired > 3, "solo $repaired cortes")
    }

    /**
     * Un corte de luz puede dejar el archivo más largo que lo escrito, con ceros al final. Esos
     * ceros son el corte: se quitan y el resto se repara igual que sin ellos. Algo distinto de cero
     * entre ellos ya no es un corte, sino un daño, y se rechaza sin tocar nada.
     */
    @Test
    fun `a zero tail is a cut and anything else after it is damage`() {
        val recording = record()
        val cut = recording.channel.cutAfter(recording.channel.totalBytes * 3 / 5)
        val clean = cut.copyOf((cut.size - RecordingFileChannel.repairRecorded(cut).first.discardedBytes).toInt())
        val (expected, reference) = RecordingFileChannel.repairRecorded(clean)
        val zeros = 100_000

        val (result, channel) = RecordingFileChannel.repairRecorded(clean + ByteArray(zeros))
        assertEquals(zeros.toLong(), result.discardedBytes)
        assertEquals(expected.durationUs, result.durationUs)
        assertEquals(readClean(reference.bytes(), "sin ceros"), readClean(channel.bytes(), "con ceros"))

        assertRejectedUntouched(clean + ByteArray(zeros).also { it[zeros - 1] = 1 }, "ceros con un byte distinto al final")
    }

    /**
     * A `repair` le puede llegar cualquier cosa. Se mutan grabaciones cortadas de las cuatro
     * disposiciones, y cada variante se repara o se rechaza sin tocarla; y si se podía leer antes de
     * repararla, se puede leer después.
     */
    @Test
    fun `mutated interrupted files that could be read still can once repaired`() {
        val noReserve = record { cuesReserveBytes = 0 }
        fun cut(recording: Recording) = recording.channel.cutAfter(recording.channel.totalBytes * 3 / 5)
        val seeds = listOf(cut(record()), cut(record { cuesReserveBytes = 64 }), cut(noReserve), as40(cut(noReserve)))
        val random = kotlin.random.Random(20_261_026)
        var repaired = 0
        for (i in 0 until 500) {
            val seed = seeds[i % seeds.size]
            val input = FuzzMutator(random, FuzzSeeds.Container.MKV).mutate(seed, seeds.filter { it !== seed })
            val label = "variante $i"
            withinDeadline(label) {
                val readable = runCatching { read(input) }.isSuccess
                val (outcome, channel) = repairOn(input)
                when (val error = outcome.exceptionOrNull()) {
                    null -> if (outcome.getOrThrow().repaired) {
                        repaired++
                        if (readable) {
                            val after = runCatching { read(channel.bytes()) }
                            assertTrue(after.isSuccess, "$label: se leía y reparada ya no: ${after.exceptionOrNull()}")
                        }
                    }
                    is IllegalArgumentException -> assertTrue(channel.writes.isEmpty(), "$label: la tocó al rechazarla")
                    else -> throw AssertionError("$label: ${error::class.simpleName}: ${error.message}", error)
                }
            }
        }
        assertTrue(repaired > 100, "solo $repaired reparadas")
    }
}
