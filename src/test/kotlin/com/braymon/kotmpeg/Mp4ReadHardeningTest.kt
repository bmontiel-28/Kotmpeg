package com.braymon.kotmpeg

import com.braymon.kotmpeg.Mp4ReadFixtures.audio
import com.braymon.kotmpeg.Mp4ReadFixtures.box
import com.braymon.kotmpeg.Mp4ReadFixtures.children
import com.braymon.kotmpeg.Mp4ReadFixtures.find
import com.braymon.kotmpeg.Mp4ReadFixtures.fragmentedHeader
import com.braymon.kotmpeg.Mp4ReadFixtures.fullBox
import com.braymon.kotmpeg.Mp4ReadFixtures.insertAfter
import com.braymon.kotmpeg.Mp4ReadFixtures.open
import com.braymon.kotmpeg.Mp4ReadFixtures.putU32
import com.braymon.kotmpeg.Mp4ReadFixtures.putU64
import com.braymon.kotmpeg.Mp4ReadFixtures.replace
import com.braymon.kotmpeg.Mp4ReadFixtures.trak
import com.braymon.kotmpeg.Mp4ReadFixtures.type
import com.braymon.kotmpeg.Mp4ReadFixtures.u32
import com.braymon.kotmpeg.Mp4ReadFixtures.video
import com.braymon.kotmpeg.Mp4ReadFixtures.within
import com.braymon.kotmpeg.Mp4ReadFixtures.write
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.mp4.FragmentCursor
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lo que un MP4 manipulado puede **declarar** sin tenerlo: muestras que no ocupan nada, que se
 * repiten o que están fuera del archivo, y pistas por miles. Cada caso abría con un consumo de
 * memoria o de CPU sin relación con el tamaño del archivo; aquí se fija que ahora queda acotado.
 *
 * La memoria se mide como la de los demás tests de presupuesto: lo que reserva el hilo al abrir por
 * segunda vez, para no contar la carga de clases.
 */
class Mp4ReadHardeningTest {

    @TempDir
    lateinit var dir: File

    private val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

    /** Bytes que reserva el hilo al ejecutar [block] por segunda vez. */
    private fun allocatedBy(block: () -> Unit): Long {
        assumeTrue(threads != null, "este JDK no mide la memoria por hilo")
        block()
        val before = threads!!.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    private fun concat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun mdat(payload: Int): ByteArray = box("mdat") { zeros(payload) }

    /**
     * Hallazgo 1. Un `trun` sin campos por muestra con `count` = 0xFFFFFFFF y un `trex` de tamaño 0:
     * se acotaba por el archivo entero, una muestra por byte, y el cursor que se crea al abrir las
     * guardaba todas en su búfer, 37 bytes cada una. Con 6 MB eran más de 200 MB.
     */
    @Test
    fun `an implicit trun with a huge count does not fill the cursor buffer`() {
        val header = fragmentedHeader(dir, video())
        val moof = box("moof") {
            fullBox("mfhd", 0, 0) { u32(1) }
            box("traf") {
                fullBox("tfhd", 0, 0x020000) { u32(1) }
                fullBox("tfdt", 1, 0) { u64(0) }
                fullBox("trun", 0, 0) { u32(-1) }
            }
        }
        val file = File(dir, "trun-enorme.mp4").apply { writeBytes(concat(header, moof, mdat(6 shl 20))) }

        val allocated = allocatedBy {
            open(file).use { d ->
                assertEquals(1, d.tracks.size)
                assertEquals(5, (0 until 5).count { d.readPacket() != null })
            }
        }
        assertTrue(allocated < 48L * 1024 * 1024, "abrir reservó ${allocated / 1024 / 1024} MiB con un archivo de 6 MiB")
    }

    /**
     * Hallazgo 1. 60 000 `trun` de 16 bytes encadenados, el primero con un `data_offset` fuera del
     * archivo: ninguna muestra es legible, pero cada `trun` declaraba un millón y se recorrían
     * todas, 6·10¹⁰ vueltas al abrir.
     */
    @Test
    fun `truns that point past the end of the file are skipped without walking their samples`() {
        val header = fragmentedHeader(dir, video())
        val moof = box("moof") {
            fullBox("mfhd", 0, 0) { u32(1) }
            box("traf") {
                fullBox("tfhd", 0, 0x020000 or 0x10) { u32(1); u32(1) }
                fullBox("tfdt", 1, 0) { u64(0) }
                fullBox("trun", 0, 0x01) { u32(-1); u32(0x7FFFFFFF) }
                repeat(59_999) { fullBox("trun", 0, 0) { u32(-1) } }
            }
        }
        val file = File(dir, "truns-fuera.mp4").apply { writeBytes(concat(header, moof)) }

        val packets = assertTimeoutPreemptively<Int>(Duration.ofSeconds(10)) {
            open(file).use { d -> generateSequence { d.readPacket() }.count() }
        }
        assertEquals(0, packets, "ninguna muestra estaba dentro del archivo")
    }

    /**
     * Hallazgo 2. 3 300 pistas de audio sin muestras y 10 000 `moof` mínimos: cada punto de control
     * guardaba el estado de todas las pistas, y había uno por `moof`. Eran unos 660 MB con 2 MB de
     * archivo. Ahora se leen 64 pistas, con aviso, y los puntos de control se espacian.
     */
    @Test
    fun `thousands of declared tracks are capped and do not multiply the checkpoints`() {
        var header = fragmentedHeader(dir, audio())
        val original = trak(header, 0)
        val trakBytes = header.copyOfRange(original, original + u32(header, original).toInt())
        val copies = ByteArrayOutputStream()
        for (id in 2..3_300) {
            val copy = trakBytes.copyOf()
            putU32(copy, within(copy, -1, "trak", "tkhd") + 20, id.toLong())
            copies.write(copy)
        }
        header = insertAfter(header, original, copies.toByteArray())
        val moof = box("moof") {
            fullBox("mfhd", 0, 0) { u32(1) }
            box("traf") {
                fullBox("tfhd", 0, 0x020000) { u32(1) }
                fullBox("trun", 0, 0) { u32(256) }
            }
        }
        val file = File(dir, "pistas.mp4").apply { writeBytes(concat(header, *Array(10_000) { moof })) }

        val warnings = ArrayList<String>()
        val allocated = allocatedBy {
            warnings.clear()
            open(file, warnings).use { d -> assertTrue(d.tracks.size <= 64, "se leyeron ${d.tracks.size} pistas") }
        }
        assertTrue(allocated < 64L * 1024 * 1024, "abrir reservó ${allocated / 1024 / 1024} MiB")
        assertTrue(warnings.any { "64" in it }, "el límite de pistas tiene que avisar: $warnings")
    }

    /**
     * Hallazgo 9. 20 000 chunks que apuntan al mismo sitio con muestras de 64 KiB: cada tabla es
     * válida por separado y juntas entregaban 1,3 GB copiados de un archivo de 300 KB.
     */
    @Test
    fun `overlapping samples cannot deliver more bytes than twice the file`() {
        val plain = write(File(dir, "plano.mp4"), videoFrames = 30).readBytes()
        val stbl = find(plain, "moov", "trak", "mdia", "minf", "stbl")
        val firstChunk = u32(plain, within(plain, stbl, "stco") + 16)
        val chunks = 20_000
        val sampleSize = 64 * 1024
        var bytes = plain
        fun swap(type: String, replacement: ByteArray) {
            bytes = replace(bytes, within(bytes, find(bytes, "moov", "trak", "mdia", "minf", "stbl"), type), replacement)
        }
        swap("stsz", fullBox("stsz", 0, 0) { u32(0); u32(chunks); repeat(chunks) { u32(sampleSize) } })
        swap("stsc", fullBox("stsc", 0, 0) { u32(1); u32(1); u32(1); u32(1) })
        swap("stts", fullBox("stts", 0, 0) { u32(1); u32(chunks); u32(3000) })
        swap("stco", fullBox("stco", 0, 0) { u32(chunks); repeat(chunks) { u32(firstChunk) } })
        val file = File(dir, "solapado.mp4").apply { writeBytes(concat(bytes, box("free") { zeros(200 * 1024) })) }

        val warnings = ArrayList<String>()
        var delivered = 0L
        open(file, warnings).use { d -> generateSequence { d.readPacket() }.forEach { delivered += it.data.size } }
        assertTrue(delivered <= 2 * file.length(), "se entregaron $delivered bytes de un archivo de ${file.length()}")
        assertTrue(delivered > 0, "las primeras muestras sí se leen")
        assertTrue(warnings.any { "solapan" in it }, "y se avisa: $warnings")
    }

    /**
     * Hallazgo 10. Los keyframes iban en un `BitSet` reservado del tamaño de la pista aunque `stss`
     * tuviera una sola entrada: con 8 millones de muestras de un byte, 1 MB más solo por declarar un
     * keyframe. Se compara abrir el mismo archivo con y sin ese `stss`.
     */
    @Test
    fun `a single sync sample does not allocate a bit per sample`() {
        val plain = write(File(dir, "plano.mp4"), videoFrames = 30).readBytes()
        val stblPath = arrayOf("moov", "trak", "mdia", "minf", "stbl")
        val firstChunk = u32(plain, within(plain, find(plain, *stblPath), "stco") + 16)
        var bytes = plain
        fun swap(type: String, replacement: ByteArray) {
            bytes = replace(bytes, within(bytes, find(bytes, *stblPath), type), replacement)
        }
        swap("stsz", fullBox("stsz", 0, 0) { u32(1); u32(-1) })
        swap("stsc", fullBox("stsc", 0, 0) { u32(1); u32(1); u32(0x7FFFFFFF); u32(1) })
        swap("stts", fullBox("stts", 0, 0) { u32(1); u32(0x7FFFFFFF); u32(1) })
        swap("stco", fullBox("stco", 0, 0) { u32(1); u32(firstChunk) })
        swap("stss", fullBox("stss", 0, 0) { u32(1); u32(1) })
        val padding = box("free") { zeros(8 shl 20) }
        val withStss = File(dir, "con-stss.mp4").apply { writeBytes(concat(bytes, padding)) }
        val stss = within(bytes, find(bytes, *stblPath), "stss")
        val withoutStss = File(dir, "sin-stss.mp4").apply {
            writeBytes(concat(replace(bytes, stss, box("free") { zeros(u32(bytes, stss).toInt() - 8) }), padding))
        }

        open(withStss).use { d -> assertTrue(d.tracks.single() is TrackInfo.Video) }
        val samples = 8 shl 20
        val plainCost = allocatedBy { open(withoutStss).close() }
        val stssCost = allocatedBy { open(withStss).close() }
        assertTrue(
            stssCost - plainCost < samples / 32,
            "un stss de una entrada costó ${(stssCost - plainCost) / 1024} KiB más al abrir",
        )
    }

    /**
     * Hallazgo 11. En fMP4, buscar `Long.MAX_VALUE` con una pista de audio que termina antes que el
     * vídeo caía en un punto de control posterior a su final, con índice igual a su tamaño, y
     * `seekTo` lanzaba al colocar el cursor allí. Hace falta que el ancla tenga un PTS saturado: se
     * consigue con un `tfdt` enorme en el último fragmento de vídeo.
     */
    @Test
    fun `seeking to the maximum time with an audio track that ended early does not throw`() {
        val bytes = write(File(dir, "fmp4.mp4"), fragmented = true, videoFrames = 900, audioFrames = 50, gop = 30).readBytes()
        val videoTfdts = children(bytes).filter { type(bytes, it) == "moof" }.mapNotNull { moof ->
            children(bytes, moof).filter { type(bytes, it) == "traf" }.firstOrNull { traf ->
                u32(bytes, within(bytes, traf, "tfhd") + 12) == 1L
            }?.let { within(bytes, it, "tfdt") }
        }
        val last = videoTfdts.last()
        assertEquals(1, bytes[last + 8].toInt(), "se esperaba un tfdt de versión 1")
        putU64(bytes, last + 12, 4_000_000_000_000_000_000L)
        val file = File(dir, "tfdt-enorme.mp4").apply { writeBytes(bytes) }

        open(file).use { d ->
            assertEquals(Long.MAX_VALUE, d.seekTo(Long.MAX_VALUE), "el keyframe del último fragmento satura")
            d.readPacket()
        }
    }

    private class Sample(val pts: Long, val dts: Long, val key: Boolean, val duration: Long)

    /**
     * Dónde deja `seekTo(t)` cada pista según el algoritmo documentado aplicado a las pistas enteras,
     * para archivos solo de audio: el ancla es la primera, en su último keyframe con `pts <= t` entre
     * las que no se decodifican después de `t`; las demás, en la última muestra que no empieza después
     * de ese keyframe, o agotadas si ya no presentan nada. Es el modelo de `Mp4SampleIndexTest`.
     */
    private fun expectedSeek(tracks: List<Int>, samples: Map<Int, List<Sample>>, t: Long): Pair<Long, Map<Int, Int>> {
        val anchor = samples.getValue(tracks.first())
        val limit = anchor.indexOfFirst { it.dts > t }.let { if (it < 0) anchor.size else it }.coerceAtMost(anchor.size - 1)
        val target = (limit downTo 0).firstOrNull { anchor[it].key && anchor[it].pts <= t } ?: 0
        val targetPts = anchor[target].pts
        val positions = tracks.associateWith { id ->
            val list = samples.getValue(id)
            if (id == tracks.first()) {
                target
            } else {
                val start = list.indexOfLast { it.dts <= targetPts }.coerceAtLeast(0)
                val ended = (start until list.size).all { list[it].pts < targetPts && targetPts - list[it].pts >= list[it].duration }
                if (start == list.size - 1 && ended) list.size else start
            }
        }
        return targetPts to positions
    }

    private fun expectedNext(tracks: List<Int>, samples: Map<Int, List<Sample>>, positions: Map<Int, Int>, count: Int): List<String> {
        val cursor = positions.toMutableMap()
        val out = ArrayList<String>()
        while (out.size < count) {
            val id = tracks.filter { cursor.getValue(it) < samples.getValue(it).size }
                .minByOrNull { samples.getValue(it)[cursor.getValue(it)].dts } ?: break
            val s = samples.getValue(id)[cursor.getValue(id)]
            out += "$id ${s.pts} ${s.dts}"
            cursor[id] = cursor.getValue(id) + 1
        }
        return out
    }

    /**
     * Hallazgo 1, la otra mitad: el cursor de fMP4 ya no guarda un `moof` entero sino ventanas de
     * [FragmentCursor.WINDOW] muestras. Un fMP4 de solo audio de FFmpeg va en un único `moof`, así
     * que leerlo y buscar en él tiene que dar exactamente lo que da el modelo sobre la pista entera,
     * también justo en los bordes de las ventanas. Dos pistas —una con un `trun` con campos por
     * muestra y otra sin ellos—, para que la segunda tenga que mirar la primera muestra de la ventana
     * siguiente al buscar.
     */
    @Test
    fun `a fragment larger than a window is read and sought like the whole track`() {
        val count = 10_000
        val header = fragmentedHeader(dir, audio(), audio())
        fun moof(dataOffset: Long) = box("moof") {
            fullBox("mfhd", 0, 0) { u32(1) }
            box("traf") {
                fullBox("tfhd", 0, 0x020000) { u32(1) }
                fullBox("tfdt", 1, 0) { u64(0) }
                fullBox("trun", 0, 0x301) { u32(count); u32(dataOffset); repeat(count) { u32(1024); u32(2) } }
            }
            box("traf") {
                fullBox("tfhd", 0, 0x020000 or 0x08 or 0x10) { u32(2); u32(1024); u32(2) }
                fullBox("tfdt", 1, 0) { u64(0) }
                fullBox("trun", 0, 0x001) { u32(count); u32(dataOffset + 2L * count) }
            }
        }
        val moofSize = moof(0).size
        val file = File(dir, "un-moof.mp4").apply {
            writeBytes(concat(header, moof(moofSize + 8L), box("mdat") { for (i in 0 until 2 * count) u16(i) }))
        }
        assertTrue(count > 2 * FragmentCursor.WINDOW)

        val (tracks, samples) = open(file).use { d ->
            val read = d.tracks.associate { it.id to ArrayList<Sample>() }
            generateSequence { d.readPacket() }.forEach { read.getValue(it.trackId) += Sample(it.ptsUs, it.dtsUs, it.isKeyFrame, it.durationUs) }
            d.tracks.map { it.id } to read
        }
        assertEquals(listOf(count, count), tracks.map { samples.getValue(it).size })
        val first = samples.getValue(tracks.first())
        val targets = ArrayList<Long>()
        for (w in 1..2) for (delta in -2..2) {
            val s = first[w * FragmentCursor.WINDOW + delta]
            targets += listOf(s.pts, s.pts - 1, s.dts + 1)
        }
        targets += listOf(Long.MIN_VALUE, 0, first.last().pts, Long.MAX_VALUE)
        val random = kotlin.random.Random(11)
        repeat(100) { targets += random.nextLong(0, first.last().pts + 50_000) }

        open(file).use { d ->
            for (t in targets) {
                val (pts, positions) = expectedSeek(tracks, samples, t)
                assertEquals(pts, d.seekTo(t), "seekTo($t)")
                val actual = (0 until 12).mapNotNull { d.readPacket()?.let { p -> "${p.trackId} ${p.ptsUs} ${p.dtsUs}" } }
                assertEquals(expectedNext(tracks, samples, positions, 12), actual, "paquetes tras seekTo($t)")
            }
        }
    }
}
