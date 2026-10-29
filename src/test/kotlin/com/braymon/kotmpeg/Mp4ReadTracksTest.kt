package com.braymon.kotmpeg

import com.braymon.kotmpeg.Mp4ReadFixtures.anyBox
import com.braymon.kotmpeg.Mp4ReadFixtures.audio
import com.braymon.kotmpeg.Mp4ReadFixtures.box
import com.braymon.kotmpeg.Mp4ReadFixtures.children
import com.braymon.kotmpeg.Mp4ReadFixtures.fragmentedHeader
import com.braymon.kotmpeg.Mp4ReadFixtures.indexOf
import com.braymon.kotmpeg.Mp4ReadFixtures.open
import com.braymon.kotmpeg.Mp4ReadFixtures.putU16
import com.braymon.kotmpeg.Mp4ReadFixtures.putU32
import com.braymon.kotmpeg.Mp4ReadFixtures.readAll
import com.braymon.kotmpeg.Mp4ReadFixtures.trak
import com.braymon.kotmpeg.Mp4ReadFixtures.u32
import com.braymon.kotmpeg.Mp4ReadFixtures.video
import com.braymon.kotmpeg.Mp4ReadFixtures.within
import com.braymon.kotmpeg.Mp4ReadFixtures.write
import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.model.TrackInfo
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Qué pistas salen de un MP4 y con qué muestras: el encadenado de offsets entre `traf` cuando uno
 * es de una pista que no se lee, los descartes que no avisaban, los `track_ID` repetidos, el signo
 * de los desfases de composición de `trun` v0 y el MP4 híbrido.
 */
class Mp4ReadTracksTest {

    @TempDir
    lateinit var dir: File

    private fun file(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }

    private fun tkhdTrackIdAt(b: ByteArray, track: Int): Int = within(b, trak(b, track), "tkhd") + 20

    /**
     * Hallazgo 3. Sin `base_data_offset` ni `default-base-is-moof`, los datos de cada `traf` siguen
     * a los del anterior. Con el primero de una pista no soportada (aquí un `ac-3`) su `trun` se
     * saltaba entero, y el vídeo del segundo se leía desde el principio del `moof`, sin ningún aviso.
     */
    @Test
    fun `an unsupported track fragment still chains the data offsets of the next one`() {
        val header = fragmentedHeader(dir, video(), audio())
        val entry = children(header, within(header, trak(header, 1), "mdia", "minf", "stbl", "stsd")).first()
        "ac-3".toByteArray(Charsets.US_ASCII).copyInto(header, entry + 4)
        val audioBytes = ByteArray(16) { 0x5A }
        val frames = listOf(ByteArray(30) { 0x11 }, ByteArray(30) { 0x22 })

        fun moof(dataOffset: Long) = box("moof") {
            fullBox("mfhd", 0, 0) { u32(1) }
            box("traf") {
                fullBox("tfhd", 0, 0) { u32(2) }
                fullBox("tfdt", 1, 0) { u64(0) }
                fullBox("trun", 0, 0x201) { u32(2); u32(dataOffset); u32(7); u32(9) }
            }
            box("traf") {
                fullBox("tfhd", 0, 0) { u32(1) }
                fullBox("tfdt", 1, 0) { u64(0) }
                fullBox("trun", 0, 0x700) {
                    u32(frames.size)
                    for ((i, frame) in frames.withIndex()) {
                        u32(3000); u32(frame.size); u32(if (i == 0) 0x02000000 else 0x01010000)
                    }
                }
            }
        }
        val moofSize = moof(0).size
        val mdat = box("mdat") { bytes(audioBytes); frames.forEach { bytes(it) } }
        val warnings = ArrayList<String>()
        val packets = readAll(file("encadenado.mp4", header + moof(moofSize + 8L) + mdat), warnings)

        assertEquals(listOf(1, 1), packets.map { it.trackId })
        for ((frame, packet) in frames.zip(packets)) assertContentEquals(frame, packet.data, "el vídeo leyó bytes ajenos")
        assertTrue(warnings.any { "pista 2" in it }, "la pista no soportada sí se avisa: $warnings")
    }

    /** Hallazgo 7. Con un `stco` vacío la pista desaparecía sin decir nada. */
    @Test
    fun `a track whose tables place no sample is reported`() {
        val bytes = write(File(dir, "plano.mp4"), videoFrames = 10).readBytes()
        putU32(bytes, within(bytes, trak(bytes, 0), "mdia", "minf", "stbl", "stco") + 12, 0)
        val warnings = ArrayList<String>()
        open(file("stco-vacio.mp4", bytes), warnings).use { assertTrue(it.tracks.isEmpty()) }
        assertTrue(warnings.any { "pista 1" in it }, "la pista perdida tiene que avisar: $warnings")
    }

    /** Hallazgo 7. Una pista que no es de audio ni de vídeo, igual. */
    @Test
    fun `a track that is neither audio nor video is reported`() {
        val bytes = write(File(dir, "plano.mp4"), videoFrames = 10, audioFrames = 10).readBytes()
        val hdlr = within(bytes, trak(bytes, 1), "mdia", "hdlr")
        "text".toByteArray(Charsets.US_ASCII).copyInto(bytes, hdlr + 16)
        val warnings = ArrayList<String>()
        open(file("texto.mp4", bytes), warnings).use { assertEquals(1, it.tracks.size) }
        assertTrue(warnings.any { "pista 2" in it && "text" in it }, "la pista ignorada tiene que avisar: $warnings")
    }

    /**
     * Hallazgo 7. En fMP4 la construcción de la pista iba en un `runCatching` que se tragaba la
     * excepción: aquí, una pista de audio sin canales ni en el `stsd` ni en el ASC.
     */
    @Test
    fun `a fragmented track that cannot be built is reported`() {
        val bytes = write(File(dir, "fmp4.mp4"), fragmented = true, videoTrack = null, audioFrames = 60).readBytes()
        val mp4a = children(bytes, within(bytes, trak(bytes, 0), "mdia", "minf", "stbl", "stsd")).first()
        putU16(bytes, mp4a + 24, 0)
        val asc = AacConfig.build(48_000, 2)
        val at = indexOf(bytes, byteArrayOf(0x05, asc.size.toByte()) + asc)
        bytes[at + 3] = (bytes[at + 3].toInt() and 0x87).toByte()
        val warnings = ArrayList<String>()
        open(file("sin-canales.mp4", bytes), warnings).use { assertTrue(it.tracks.isEmpty()) }
        assertTrue(warnings.any { "pista 1" in it }, "la pista descartada tiene que avisar: $warnings")
    }

    /** Hallazgo 8. Dos pistas con el mismo `track_ID` en un MP4 plano salían las dos, indistinguibles. */
    @Test
    fun `a repeated track id in a plain mp4 keeps only the first track`() {
        val bytes = write(File(dir, "plano.mp4"), videoFrames = 10, audioFrames = 10).readBytes()
        putU32(bytes, tkhdTrackIdAt(bytes, 1), 1)
        val warnings = ArrayList<String>()
        open(file("repetido.mp4", bytes), warnings).use { d ->
            assertEquals(listOf(1), d.tracks.map { it.id })
            assertTrue(d.tracks.single() is TrackInfo.Video)
        }
        assertTrue(warnings.any { "track_ID" in it }, "el duplicado tiene que avisar: $warnings")
    }

    /** Hallazgo 8. En fMP4 la segunda pisaba a la primera: el vídeo desaparecía. */
    @Test
    fun `a repeated track id in a fragmented mp4 keeps only the first track`() {
        val bytes = write(File(dir, "fmp4.mp4"), fragmented = true, videoFrames = 10, audioFrames = 10).readBytes()
        putU32(bytes, tkhdTrackIdAt(bytes, 1), 1)
        val warnings = ArrayList<String>()
        open(file("repetido-f.mp4", bytes), warnings).use { d ->
            assertTrue(d.tracks.single() is TrackInfo.Video, "se esperaba el vídeo: ${d.tracks}")
            assertEquals(10, generateSequence { d.readPacket() }.count())
        }
        assertTrue(warnings.any { "track_ID" in it }, "el duplicado tiene que avisar: $warnings")
    }

    /**
     * Hallazgo 12. Un `trun` v0 con un desfase de −1001 tics: leído sin signo daba un PTS unas 13
     * horas por delante. FFmpeg lo lee con signo, igual que el `ctts` v0.
     */
    @Test
    fun `a negative composition offset in a version 0 trun is read as negative`() {
        val bytes = write(File(dir, "fmp4.mp4"), fragmented = true, videoFrames = 10).readBytes()
        val trun = anyBox(bytes, "trun")
        assertEquals(0x000F01L, u32(bytes, trun + 8) and 0xFFFFFF)
        bytes[trun + 8] = 0
        putU32(bytes, trun + 32, 0xFFFFFC17L)

        val first = readAll(file("ctts-negativo.mp4", bytes)).first()
        assertTrue(first.dtsUs - first.ptsUs in 11_122L..11_123L, "−1001 tics a 90 kHz: pts ${first.ptsUs}, dts ${first.dtsUs}")
    }

    /**
     * Hallazgo 16. El MP4 híbrido de `ffmpeg -movflags frag_keyframe` —muestras en el `moov` y
     * fragmentos detrás— no se lee entero, pero tiene que abrirse, leerse sin errores y avisar.
     */
    @Test
    fun `a hybrid mp4 opens, reads its moov samples and warns about the fragments`() {
        val hybrid = File(dir, "hibrido.mp4")
        val ok = runCatching {
            val p = ProcessBuilder(
                "ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=15:duration=2",
                "-c:v", "libx264", "-preset", "ultrafast", "-g", "5", "-movflags", "frag_keyframe", hybrid.absolutePath,
            ).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
        assumeTrue(ok && hybrid.length() > 0, "sin FFmpeg")

        val warnings = ArrayList<String>()
        val packets = readAll(hybrid, warnings)
        assertTrue(packets.isNotEmpty(), "las muestras del moov se leen")
        assertTrue(warnings.any { "híbrido" in it }, "se avisa de lo que no se lee: $warnings")
    }
}
