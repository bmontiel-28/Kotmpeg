package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import com.braymon.kotmpeg.mp4.Mp4Demuxer
import com.braymon.kotmpeg.mp4.Mp4Segment
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Los segmentos indexados de `FragmentedMp4Muxer` (3.1.0): un `sidx` por pista delante de cada
 * fragmento, con la forma de `-movflags dash` de FFmpeg, y el aviso `onSegment` para empaquetar en
 * vivo. La validación contra un reproductor real —FFmpeg decodificando el archivo y una lista HLS
 * montada con los avisos— está en `FfmpegIntegrationTest`; aquí se comprueba campo a campo.
 */
class SegmentIndexTest {

    @TempDir
    lateinit var dir: File

    private class Box(val offset: Int, val size: Int, val type: String)

    private class Sidx(
        val offset: Int,
        val version: Int,
        val referenceId: Long,
        val timescale: Long,
        val earliestPresentationTime: Long,
        val firstOffset: Long,
        val referenceCount: Int,
        val referenceType: Int,
        val referencedSize: Long,
        val duration: Long,
        val startsWithSap: Boolean,
        val sapType: Int,
    )

    private fun sidxBoxes(bytes: ByteArray): List<Sidx> = topLevel(bytes).filter { it.type == "sidx" }.map { sidx(bytes, it) }

    private fun u32(b: ByteArray, at: Int): Long = (0 until 4).fold(0L) { v, k -> (v shl 8) or (b[at + k].toLong() and 0xFF) }

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    private fun topLevel(bytes: ByteArray): List<Box> {
        val boxes = ArrayList<Box>()
        var p = 0
        while (p + 8 <= bytes.size) {
            val size = u32(bytes, p).toInt()
            boxes += Box(p, size, String(bytes, p + 4, 4, Charsets.ISO_8859_1))
            p += size
        }
        return boxes
    }

    private fun sidx(bytes: ByteArray, box: Box): Sidx {
        val p = box.offset
        val first = u32(bytes, p + 40)
        val sap = u32(bytes, p + 48)
        return Sidx(
            offset = p,
            version = bytes[p + 8].toInt(),
            referenceId = u32(bytes, p + 12),
            timescale = u32(bytes, p + 16),
            earliestPresentationTime = u64(bytes, p + 20),
            firstOffset = u64(bytes, p + 28),
            referenceCount = (u32(bytes, p + 36) and 0xFFFF).toInt(),
            referenceType = (first ushr 31).toInt(),
            referencedSize = first and 0x7FFFFFFF,
            duration = u32(bytes, p + 44),
            startsWithSap = sap ushr 31 == 1L,
            sapType = ((sap ushr 28) and 7).toInt(),
        )
    }

    private val videoTrack = TrackInfo.Video(
        codec = VideoCodec.H264, width = 64, height = 48, frameRate = 30.0,
        codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22))),
    )
    private val audioTrack = TrackInfo.Audio(
        codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2, codecPrivate = AacConfig.build(48000, 2),
    )

    /**
     * Cuatro segundos de vídeo a 30 fps con B-frames (pts reordenados) y un keyframe por segundo,
     * más AAC que empieza con el primer fotograma: cuatro fragmentos con las dos pistas. La fecha
     * de creación es fija para que dos escrituras iguales den el mismo archivo.
     */
    private fun write(
        file: File,
        segmentIndex: Boolean,
        segments: MutableList<Mp4Segment> = ArrayList(),
        onSegment: (Mp4Segment) -> Unit = { segments += it },
    ): FragmentedMp4Muxer {
        val muxer = FragmentedMp4Muxer(file)
        muxer.creationTimeMillis = 0
        muxer.segmentIndex = segmentIndex
        muxer.onSegment = onSegment
        val video = muxer.addTrack(videoTrack)
        val audio = muxer.addTrack(audioTrack)
        muxer.start()
        val frame = 33_333L
        val order = intArrayOf(0, 2, 1, 3, 5, 4)
        val packets = ArrayList<MediaPacket>()
        for (i in 0 until 120) {
            val shown = (i / 6) * 6 + order[i % 6]
            val payload = NalUnits.joinLengthPrefixed(listOf(byteArrayOf(i.toByte(), 1, 2, 3)))
            packets += MediaPacket(video, payload, shown * frame + frame, i * frame, i % 30 == 0, frame)
        }
        for (i in 0 until 188) {
            val pts = frame + i * 21_333L
            packets += MediaPacket(audio, byteArrayOf(i.toByte(), 9), pts, pts, true, 21_333)
        }
        packets.sortedBy { it.dtsUs }.forEach { muxer.writePacket(it) }
        muxer.stop()
        return muxer
    }

    @Test
    fun `every fragment is preceded by one sidx per track that indexes it`() {
        val file = File(dir, "indexado.mp4")
        val segments = ArrayList<Mp4Segment>()
        write(file, segmentIndex = true, segments)
        val bytes = file.readBytes()
        val boxes = topLevel(bytes)

        assertEquals(listOf("ftyp", "moov"), boxes.take(2).map { it.type })
        assertEquals("mfra", boxes.last().type)
        val body = boxes.drop(2).dropLast(1)
        val groups = ArrayList<List<Box>>()
        var current = ArrayList<Box>()
        for (box in body) {
            current += box
            if (box.type == "mdat") {
                groups += current
                current = ArrayList()
            }
        }
        assertTrue(current.isEmpty(), "quedaron cajas sueltas tras el último mdat: ${current.map { it.type }}")
        assertEquals(4, groups.size, "se esperaban cuatro fragmentos")

        val previousEnd = HashMap<Long, Long>()
        for (group in groups) {
            assertEquals(listOf("sidx", "sidx", "moof", "mdat"), group.map { it.type }, "estructura del segmento")
            val moof = group[2]
            val mdat = group[3]
            val indexes = group.take(2).map { sidx(bytes, it) }
            assertEquals(listOf(1L, 2L), indexes.map { it.referenceId }, "un sidx por pista, en el orden de los traf")
            assertEquals(listOf(90_000L, 48_000L), indexes.map { it.timescale })
            for ((i, index) in indexes.withIndex()) {
                assertEquals(1, index.version)
                assertEquals(1, index.referenceCount)
                assertEquals(0, index.referenceType, "la referencia es a medios, no a otro sidx")
                assertEquals((indexes.size - 1 - i) * 52L, index.firstOffset, "first_offset tiene que saltar los sidx siguientes")
                assertEquals(index.offset + 52 + index.firstOffset, moof.offset.toLong(), "la referencia no empieza en el moof")
                assertEquals((moof.size + mdat.size).toLong(), index.referencedSize)
                assertTrue(index.startsWithSap, "los fragmentos se cortan en keyframes: tienen que empezar en un SAP")
                assertEquals(0, index.sapType)
                previousEnd[index.referenceId]?.let { end ->
                    assertEquals(end, index.earliestPresentationTime, "pista ${index.referenceId}: hueco o solape entre segmentos")
                }
                previousEnd[index.referenceId] = index.earliestPresentationTime + index.duration
            }
        }

        assertEquals(groups.size, segments.size, "un aviso por fragmento")
        var expectedOffset = boxes[1].offset + boxes[1].size.toLong()
        for ((segment, group) in segments.zip(groups)) {
            assertEquals(expectedOffset, segment.offset, "los segmentos tienen que ser contiguos")
            assertEquals(group.sumOf { it.size }.toLong(), segment.size)
            assertTrue(segment.startsWithKeyFrame)
            expectedOffset = segment.offset + segment.size
        }
        assertEquals(boxes.last().offset.toLong(), expectedOffset, "tras el último segmento va el mfra")
        assertEquals(listOf(1L, 2L, 3L, 4L), segments.map { it.sequenceNumber })
        assertEquals((0 until 4).map { it * 30 * 33_333L }, segments.map { it.startUs }, "un segmento por GOP de 30 fotogramas")
        assertTrue(segments.all { it.durationUs == 999_990L }, "duraciones: ${segments.map { it.durationUs }}")
    }

    @Test
    fun `the index does not change what a reader gets, and tfra still points at each moof`() {
        val plain = File(dir, "normal.mp4")
        val indexed = File(dir, "indexado.mp4")
        val muxer = write(plain, segmentIndex = false)
        write(indexed, segmentIndex = true)

        fun read(file: File) = Mp4Demuxer(file).use { demuxer ->
            generateSequence { demuxer.readPacket() }.map { "${it.trackId} ${it.ptsUs} ${it.dtsUs} ${it.isKeyFrame} ${it.durationUs} ${it.data.toList()}" }.toList()
        }
        assertEquals(read(plain), read(indexed))
        assertEquals(0, topLevel(plain.readBytes()).count { it.type == "sidx" }, "sin la opción no se escribe ningún sidx")
        assertTrue(muxer.initSegmentSize > 0)

        val bytes = indexed.readBytes()
        val boxes = topLevel(bytes)
        val mfra = boxes.last()
        val tfra = mfra.offset + 8
        assertEquals("tfra", String(bytes, tfra + 4, 4, Charsets.ISO_8859_1))
        val entries = u32(bytes, tfra + 20).toInt()
        val moofs = boxes.filter { it.type == "moof" }.map { it.offset.toLong() }
        val pointed = (0 until entries).map { u64(bytes, tfra + 24 + it * 28 + 8) }
        assertEquals(moofs, pointed, "el tfra tiene que apuntar a los moof, no a los sidx")

        for (target in listOf(0L, 1_200_000L, 2_500_000L, 3_900_000L)) {
            val expected = Mp4Demuxer(plain).use { it.seekTo(target) }
            Mp4Demuxer(indexed).use { assertEquals(expected, it.seekTo(target), "seekTo($target)") }
        }
    }

    @Test
    fun `without the index each notified segment starts at its moof`() {
        val file = File(dir, "avisos.mp4")
        val segments = ArrayList<Mp4Segment>()
        write(file, segmentIndex = false, segments)
        val boxes = topLevel(file.readBytes())
        assertEquals(boxes.filter { it.type == "moof" }.map { it.offset.toLong() }, segments.map { it.offset })
    }

    @Test
    fun `the index is configured before start and keeps fragments within what sidx can describe`() {
        val muxer = FragmentedMp4Muxer(File(dir, "tarde.mp4"))
        muxer.addTrack(audioTrack)
        muxer.start()
        assertFailsWith<IllegalStateException> { muxer.segmentIndex = true }
        muxer.stop()

        val huge = FragmentedMp4Muxer(File(dir, "enorme.mp4"), 2_000_000, 10_000_000, 2L shl 30)
        huge.segmentIndex = true
        huge.addTrack(audioTrack)
        assertFailsWith<IllegalStateException> { huge.start() }
        huge.close()
    }

    /**
     * El aviso es código de la app: si falla, la grabación tiene que seguir y cerrarse bien, porque
     * una excepción en mitad de `stop()` dejaría el archivo sin índice final ni duraciones.
     */
    @Test
    fun `a failing segment callback does not break the recording`() {
        val reference = File(dir, "referencia.mp4")
        write(reference, segmentIndex = true)

        val file = File(dir, "callback-roto.mp4")
        write(file, segmentIndex = true) { error("la app falla al anotar el segmento ${it.sequenceNumber}") }

        assertContentEquals(reference.readBytes(), file.readBytes(), "el archivo cambió por un aviso que falló")
    }

    /**
     * Un B-frame que se muestra antes que el keyframe que abrió la línea de tiempo —lo que pasa al
     * remuxear desde un punto de búsqueda con GOP abierto— deja el primer fragmento con un PTS
     * negativo, y `earliest_presentation_time` no tiene signo: se escribe 0, como ya hacía el `tfra`.
     * Antes salía el negativo leído sin signo, un inicio a miles de millones de años.
     */
    @Test
    fun `a fragment that starts before the timeline origin is indexed from zero`() {
        val file = File(dir, "antes-del-origen.mp4")
        val segments = ArrayList<Mp4Segment>()
        val muxer = FragmentedMp4Muxer(file)
        muxer.segmentIndex = true
        muxer.onSegment = { segments += it }
        val video = muxer.addTrack(videoTrack)
        muxer.start()
        val frame = 33_333L
        for (i in 0 until 60) {
            val shown = when (i % 30) {
                0 -> i + 1
                1 -> i - 1
                else -> i
            }
            val payload = NalUnits.joinLengthPrefixed(listOf(byteArrayOf(i.toByte(), 1, 2, 3)))
            muxer.writePacket(MediaPacket(video, payload, shown * frame, i * frame, i % 30 == 0, frame))
        }
        muxer.stop()

        assertEquals(-frame, segments.first().startUs, "el segmento sí cuenta desde el origen, con signo")
        val indexes = sidxBoxes(file.readBytes())
        assertEquals(2, indexes.size)
        assertEquals(0L, indexes[0].earliestPresentationTime, "el sidx no tiene signo: un inicio negativo se escribe 0")
        assertTrue(indexes[1].earliestPresentationTime in 1L..90_000L * 2, "el segundo fragmento empieza tras el origen")
        Mp4Demuxer(file).use { demuxer ->
            var packets = 0
            while (demuxer.readPacket() != null) packets++
            assertEquals(60, packets)
        }
    }
}
