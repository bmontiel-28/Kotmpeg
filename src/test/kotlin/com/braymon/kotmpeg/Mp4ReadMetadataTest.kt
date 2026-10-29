package com.braymon.kotmpeg

import com.braymon.kotmpeg.Mp4ReadFixtures.appendChild
import com.braymon.kotmpeg.Mp4ReadFixtures.box
import com.braymon.kotmpeg.Mp4ReadFixtures.children
import com.braymon.kotmpeg.Mp4ReadFixtures.find
import com.braymon.kotmpeg.Mp4ReadFixtures.fragmentedHeader
import com.braymon.kotmpeg.Mp4ReadFixtures.fullBox
import com.braymon.kotmpeg.Mp4ReadFixtures.indexOf
import com.braymon.kotmpeg.Mp4ReadFixtures.insertAfter
import com.braymon.kotmpeg.Mp4ReadFixtures.open
import com.braymon.kotmpeg.Mp4ReadFixtures.putU16
import com.braymon.kotmpeg.Mp4ReadFixtures.putU32
import com.braymon.kotmpeg.Mp4ReadFixtures.readAll
import com.braymon.kotmpeg.Mp4ReadFixtures.replace
import com.braymon.kotmpeg.Mp4ReadFixtures.trak
import com.braymon.kotmpeg.Mp4ReadFixtures.type
import com.braymon.kotmpeg.Mp4ReadFixtures.u32
import com.braymon.kotmpeg.Mp4ReadFixtures.video
import com.braymon.kotmpeg.Mp4ReadFixtures.within
import com.braymon.kotmpeg.Mp4ReadFixtures.write
import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.model.ColorInfo
import com.braymon.kotmpeg.model.HdrStaticInfo
import com.braymon.kotmpeg.model.TrackInfo
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Metadatos de un MP4 que se leían mal o que tumbaban la pista entera: color y HDR inválidos, listas
 * de edición con varias ediciones, entradas de audio de QuickTime, la duración del `mvhd`, el `pasp`
 * y el AAC con los canales en un PCE.
 */
class Mp4ReadMetadataTest {

    @TempDir
    lateinit var dir: File

    private fun file(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }

    private fun stbl(b: ByteArray, track: Int = 0): Int = within(b, trak(b, track), "mdia", "minf", "stbl")

    private fun sampleEntry(b: ByteArray, track: Int = 0): Int = children(b, within(b, stbl(b, track), "stsd")).first()

    private fun hdr10(): HdrStaticInfo = HdrStaticInfo(0.708, 0.292, 0.170, 0.797, 0.131, 0.046, 0.3127, 0.329, 1000.0, 0.005)

    /** Hallazgo 4. Unos primarios de 256 en el `colr` tumbaban la pista de vídeo por un `require`. */
    @Test
    fun `an out of range colr loses only the colour description`() {
        val bytes = write(File(dir, "hdr.mp4"), videoTrack = video { color = ColorInfo.hdr10(hdr10()) }).readBytes()
        val colr = within(bytes, sampleEntry(bytes), "colr")
        putU16(bytes, colr + 12, 256)

        val warnings = ArrayList<String>()
        val track = open(file("colr.mp4", bytes), warnings).use { it.tracks.single() as TrackInfo.Video }
        val color = assertNotNull(track.color, "el mdcv sigue siendo válido")
        assertEquals(ColorInfo.UNSPECIFIED, color.primaries)
        assertEquals(1000.0, color.hdr?.maxMasteringLuminance)
        assertTrue(warnings.any { "colr" in it }, "se avisa de lo que se pierde: $warnings")
    }

    /** Hallazgo 4. Una luminancia mínima por encima de la máxima en el `mdcv`, igual. */
    @Test
    fun `an impossible mdcv loses only the mastering metadata`() {
        val bytes = write(File(dir, "hdr.mp4"), videoTrack = video { color = ColorInfo.hdr10(hdr10()) }).readBytes()
        val mdcv = within(bytes, sampleEntry(bytes), "mdcv")
        putU32(bytes, mdcv + 24, 0)
        putU32(bytes, mdcv + 28, 1_000_000)

        val warnings = ArrayList<String>()
        val track = open(file("mdcv.mp4", bytes), warnings).use { it.tracks.single() as TrackInfo.Video }
        val color = assertNotNull(track.color, "el colr sigue siendo válido")
        assertEquals(ColorInfo.PRIMARIES_BT2020, color.primaries)
        assertEquals(ColorInfo.TRANSFER_PQ, color.transfer)
        assertNull(color.hdr)
        assertTrue(warnings.any { "mdcv" in it }, "se avisa de lo que se pierde: $warnings")
    }

    /** El `elst` [entries] (duración en ms de película, `media_time` en tics del vídeo) en la primera pista. */
    private fun withEditList(vararg entries: Pair<Long, Long>): File {
        var bytes = write(File(dir, "plano.mp4"), videoFrames = 30).readBytes()
        val elst = fullBox("elst", 0, 0) {
            u32(entries.size)
            for ((duration, mediaTime) in entries) {
                u32(duration); u32(mediaTime); u16(1); u16(0)
            }
        }
        val edts = box("edts") { bytes(elst) }
        val trak = trak(bytes, 0)
        val existing = children(bytes, trak).firstOrNull { type(bytes, it) == "edts" }
        bytes = if (existing != null) replace(bytes, existing, edts) else insertAfter(bytes, within(bytes, trak, "tkhd"), edts)
        return file("elst-${entries.size}-${entries.first().second}.mp4", bytes)
    }

    private fun firstPts(file: File, warnings: MutableList<String> = ArrayList()): Long = readAll(file, warnings).first().ptsUs

    /** Hallazgo 5. `[{0}, {M}]`: la segunda edición adelantaba la pista entera M tics. */
    @Test
    fun `only the first edit with media sets the start`() {
        val warnings = ArrayList<String>()
        assertEquals(0L, firstPts(withEditList(1000L to 0L, 1000L to 9000L), warnings))
        assertTrue(warnings.any { "edición" in it }, "la edición que no se aplica se avisa: $warnings")
    }

    /** Hallazgo 5. Dos huecos seguidos antes de los medios: solo contaba el último. */
    @Test
    fun `every leading empty edit adds to the start`() {
        val warnings = ArrayList<String>()
        assertEquals(300_000L, firstPts(withEditList(100L to -1L, 200L to -1L, 1000L to 0L), warnings))
        assertEquals(emptyList(), warnings)
    }

    /** Hallazgo 5. Un hueco **después** de los medios se tomaba por un hueco inicial. */
    @Test
    fun `a trailing empty edit does not delay the track`() {
        val warnings = ArrayList<String>()
        assertEquals(0L, firstPts(withEditList(1000L to 0L, 500L to -1L), warnings))
        assertTrue(warnings.any { "edición" in it }, "la edición que no se aplica se avisa: $warnings")
    }

    /** Un MP4 de AAC con su `mp4a` sustituida por [entry], construida a partir de su `esds`. */
    private fun withAudioEntry(name: String, entry: (esds: ByteArray) -> ByteArray): File {
        val bytes = write(File(dir, "aac.mp4"), videoTrack = null, audioFrames = 20).readBytes()
        val mp4a = sampleEntry(bytes)
        val esds = within(bytes, mp4a, "esds")
        assertEquals("esds", type(bytes, esds))
        val esdsBytes = bytes.copyOfRange(esds, esds + u32(bytes, esds).toInt())
        return file(name, replace(bytes, mp4a, entry(esdsBytes)))
    }

    private fun assertReadsAac(file: File) {
        val warnings = ArrayList<String>()
        open(file, warnings).use { d ->
            val track = d.tracks.single() as TrackInfo.Audio
            assertContentEquals(AacConfig.build(48_000, 2), track.codecPrivate, "el esds no se encontró")
            assertEquals(48_000, track.sampleRate)
            assertEquals(2, track.channelCount)
            assertEquals(20, generateSequence { d.readPacket() }.count())
        }
        assertEquals(emptyList(), warnings)
    }

    /** Hallazgo 6. La `mp4a` v1 de QuickTime: 16 bytes más, y el `esds` dentro de `wave`. */
    @Test
    fun `a quicktime v1 sound entry with its esds inside wave is read`() {
        assertReadsAac(
            withAudioEntry("v1.mov") { esds ->
                box("mp4a") {
                    zeros(6); u16(1)
                    u16(1); u16(0); u32(0)
                    u16(2); u16(16); u16(0); u16(0)
                    u32(48_000L shl 16)
                    u32(1024); u32(0); u32(0); u32(2)
                    box("wave") {
                        box("frma") { fourcc("mp4a") }
                        box("mp4a") { u32(0) }
                        bytes(esds)
                        u32(8); u32(0)
                    }
                }
            },
        )
    }

    /** Hallazgo 6. La `mp4a` v2 de QuickTime: 36 bytes más, con la frecuencia real en `float64`. */
    @Test
    fun `a quicktime v2 sound entry is read`() {
        assertReadsAac(
            withAudioEntry("v2.mov") { esds ->
                box("mp4a") {
                    zeros(6); u16(1)
                    u16(2); u16(0); u32(0)
                    u16(3); u16(16); u16(0xFFFE); u16(0)
                    u32(0x00010000)
                    u32(72); u64(java.lang.Double.doubleToLongBits(48_000.0)); u32(2)
                    u32(0x7F000000); u32(16); u32(0); u32(0); u32(1024)
                    bytes(esds)
                }
            },
        )
    }

    /** Hallazgo 6, con un `.mov` de verdad: FFmpeg escribe la `mp4a` v1 con `wave`. */
    @Test
    fun `an aac mov written by ffmpeg keeps its codec configuration`() {
        val mov = File(dir, "ffmpeg.mov")
        val ok = runCatching {
            val p = ProcessBuilder(
                "ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "sine=duration=1", "-c:a", "aac", mov.absolutePath,
            ).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
        assumeTrue(ok && mov.length() > 0, "sin FFmpeg")

        val track = open(mov).use { it.tracks.single() as TrackInfo.Audio }
        assertNotNull(track.codecPrivate, "el esds de la caja wave se perdió")
        assertEquals(44_100, track.sampleRate)
    }

    /** Hallazgo 13. Un `mvhd` con duración cero no tenía respaldo en MP4 plano. */
    @Test
    fun `a plain mp4 without a movie duration takes it from its tracks`() {
        val bytes = write(File(dir, "plano.mp4"), videoFrames = 30).readBytes()
        val mvhd = find(bytes, "moov", "mvhd")
        assertEquals(0, bytes[mvhd + 8].toInt(), "se esperaba un mvhd de versión 0")
        for ((name, value) in listOf("cero" to 0L, "todo-unos" to 0xFFFFFFFFL)) {
            putU32(bytes, mvhd + 24, value)
            val duration = open(file("mvhd-$name.mp4", bytes)).use { it.durationUs }
            assertTrue(duration in 990_000L..1_010_000L, "mvhd $name: duración $duration")
        }
    }

    /** Hallazgo 13. En fMP4, un `mvhd` «todo unos» se conservaba como 49 días. */
    @Test
    fun `an unknown movie duration does not win over the fragments`() {
        val bytes = write(File(dir, "fmp4.mp4"), fragmented = true, videoFrames = 30).readBytes()
        val mvhd = find(bytes, "moov", "mvhd")
        assertEquals(0, bytes[mvhd + 8].toInt(), "se esperaba un mvhd de versión 0")
        putU32(bytes, mvhd + 24, 0xFFFFFFFFL)
        val duration = open(file("fmp4-todo-unos.mp4", bytes)).use { it.durationUs }
        assertTrue(duration in 990_000L..1_010_000L, "duración $duration")
    }

    /**
     * Hallazgo 13. El final de un fMP4 se sumaba ya redondeado: 10 tics de 90 kHz en una muestra
     * daban 111 µs y en dos de 5, 110.
     */
    @Test
    fun `fragments that end on the same tick have the same duration`() {
        val header = fragmentedHeader(dir, video())
        fun fragmented(durations: List<Long>): File {
            fun moof(dataOffset: Long) = box("moof") {
                fullBox("mfhd", 0, 0) { u32(1) }
                box("traf") {
                    fullBox("tfhd", 0, 0x020000 or 0x10) { u32(1); u32(4) }
                    fullBox("tfdt", 1, 0) { u64(0) }
                    fullBox("trun", 0, 0x101) {
                        u32(durations.size); u32(dataOffset)
                        for (d in durations) u32(d)
                    }
                }
            }
            val size = moof(0).size
            val mdat = box("mdat") { zeros(4 * durations.size) }
            return file("ticks-${durations.size}.mp4", header + moof(size + 8L) + mdat)
        }
        assertEquals(111L, open(fragmented(listOf(10))).use { it.durationUs })
        assertEquals(111L, open(fragmented(listOf(5, 5))).use { it.durationUs })
    }

    private fun withPasp(name: String, track: TrackInfo.Video, h: Long, v: Long): TrackInfo.Video {
        val bytes = write(File(dir, "$name-src.mp4"), videoTrack = track).readBytes()
        val patched = appendChild(bytes, sampleEntry(bytes), box("pasp") { u32(h); u32(v) })
        return open(file("$name.mp4", patched)).use { it.tracks.single() as TrackInfo.Video }
    }

    /** Hallazgo 14. Sin un `tkhd` que diga otra cosa, el `pasp` da el tamaño de presentación. */
    @Test
    fun `pasp gives the presentation size when the tkhd does not`() {
        val read = withPasp("pasp", video(640, 480), 4, 3)
        assertEquals(640 to 480, read.width to read.height)
        assertEquals(853 to 480, read.displayWidth to read.displayHeight)
    }

    /** Hallazgo 14. Con un `tkhd` distinto del codificado, manda el `tkhd`, como hasta ahora. */
    @Test
    fun `a tkhd with its own presentation size still wins over pasp`() {
        val coherent = withPasp("coherente", video(640, 480) { displayWidth = 853 }, 4, 3)
        assertEquals(853 to 480, coherent.displayWidth to coherent.displayHeight)
        val contradicting = withPasp("contradice", video(640, 480) { displayWidth = 800 }, 4, 3)
        assertEquals(800 to 480, contradicting.displayWidth to contradicting.displayHeight)
        val square = withPasp("cuadrado", video(640, 480), 1, 1)
        assertEquals(640 to 480, square.displayWidth to square.displayHeight)
    }

    /**
     * Hallazgo 15. Un AAC con `channelConfiguration` 0 (los canales van en un PCE): el 0 del ASC
     * pisaba los canales del `stsd` y la pista se descartaba.
     */
    @Test
    fun `an aac config without a channel configuration keeps the stsd channel count`() {
        val bytes = write(File(dir, "aac.mp4"), videoTrack = null, audioFrames = 20).readBytes()
        val asc = AacConfig.build(48_000, 2)
        val at = indexOf(bytes, byteArrayOf(0x05, asc.size.toByte()) + asc)
        assertTrue(at > 0, "no se encontró el AudioSpecificConfig")
        bytes[at + 3] = (bytes[at + 3].toInt() and 0x87).toByte()

        val warnings = ArrayList<String>()
        val track = open(file("pce.mp4", bytes), warnings).use { it.tracks.singleOrNull() as? TrackInfo.Audio }
        assertNotNull(track, "la pista se descartó: $warnings")
        assertEquals(2, track.channelCount)
        assertEquals(48_000, track.sampleRate)
        assertEquals(0, (track.codecPrivate!![1].toInt() shr 3) and 0x0F, "el ASC se entrega tal cual")
    }

    @Test
    fun `the aac entry written by the library is still read`() {
        assertReadsAac(write(File(dir, "aac-normal.mp4"), videoTrack = null, audioFrames = 20))
    }
}
