package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ColorInfo
import com.braymon.kotmpeg.model.HdrStaticInfo
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetadataAndConcatTest {

    @TempDir
    lateinit var dir: File

    private val avcC = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    private val hdr = HdrStaticInfo(
        redX = 0.708, redY = 0.292, greenX = 0.170, greenY = 0.797,
        blueX = 0.131, blueY = 0.046, whiteX = 0.3127, whiteY = 0.3290,
        maxMasteringLuminance = 1000.0, minMasteringLuminance = 0.005,
        maxContentLightLevel = 1000, maxFrameAverageLightLevel = 400,
    )

    private fun hdrVideoTrack(rotation: Int) = TrackInfo.Video(codec = VideoCodec.H264, width = 320, height = 240) {
        frameRate = 30.0
        rotationDegrees = rotation
        color = ColorInfo.hdr10(hdr)
        codecPrivate = avcC
    }

    private fun writeOneVideoTrack(file: File, track: TrackInfo.Video, packets: Int = 10, startUs: Long = 0) {
        val muxer = Kotmpeg.createMuxer(file)
        val id = muxer.addTrack(track)
        muxer.start()
        for (i in 0 until packets) {
            val data = NalUnits.joinLengthPrefixed(listOf(ByteArray(50) { i.toByte() }))
            muxer.writePacket(
                MediaPacket(id, data, startUs + i * 33_333L, startUs + i * 33_333L, i % 5 == 0, 33_333),
            )
        }
        muxer.stop()
    }

    private fun assertMetadataSurvives(file: File) {
        val demuxer = Kotmpeg.openDemuxer(file)
        val video = demuxer.tracks.single() as TrackInfo.Video
        assertEquals(90, video.rotationDegrees, "rotation in ${file.extension}")
        val color = assertNotNull(video.color, "color info in ${file.extension}")
        assertEquals(ColorInfo.PRIMARIES_BT2020, color.primaries)
        assertEquals(ColorInfo.TRANSFER_PQ, color.transfer)
        assertEquals(ColorInfo.MATRIX_BT2020_NCL, color.matrix)
        val gotHdr = assertNotNull(color.hdr, "hdr static info in ${file.extension}")
        assertTrue(abs(gotHdr.redX - hdr.redX) < 0.0001, "mastering red x")
        assertTrue(abs(gotHdr.maxMasteringLuminance - 1000.0) < 0.01, "max luminance")
        assertEquals(1000, gotHdr.maxContentLightLevel)
        assertEquals(400, gotHdr.maxFrameAverageLightLevel)
        demuxer.close()
    }

    @Test
    fun `rotation and hdr color survive mkv round trip`() {
        val file = File(dir, "meta.mkv")
        writeOneVideoTrack(file, hdrVideoTrack(rotation = 90))
        assertMetadataSurvives(file)
    }

    @Test
    fun `rotation and hdr color survive mp4 round trip`() {
        val file = File(dir, "meta.mp4")
        writeOneVideoTrack(file, hdrVideoTrack(rotation = 90))
        assertMetadataSurvives(file)
    }

    @Test
    fun `rotation and hdr color survive cross-container remux`() {
        val mkv = File(dir, "meta2.mkv")
        writeOneVideoTrack(mkv, hdrVideoTrack(rotation = 90))
        val mp4 = File(dir, "meta2.mp4")
        Kotmpeg.remux(mkv, mp4)
        assertMetadataSurvives(mp4)
        val back = File(dir, "meta3.mkv")
        Kotmpeg.remux(mp4, back)
        assertMetadataSurvives(back)
    }

    @Test
    fun `all rotations round trip in mp4`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val file = File(dir, "rot$rotation.mp4")
            writeOneVideoTrack(
                file,
                TrackInfo.Video(codec = VideoCodec.H264, width = 320, height = 240) {
                    rotationDegrees = rotation
                    codecPrivate = avcC
                },
            )
            val demuxer = Kotmpeg.openDemuxer(file)
            assertEquals(rotation, (demuxer.tracks.single() as TrackInfo.Video).rotationDegrees, "rotation $rotation")
            demuxer.close()
        }
    }

    @Test
    fun `concat joins segments with shifted timestamps`() {
        val segmentFrames = 10
        val segments = (0 until 3).map { s ->
            File(dir, "seg$s.mkv").also {
                writeOneVideoTrack(it, hdrVideoTrack(rotation = 90), packets = segmentFrames)
            }
        }
        val out = File(dir, "joined.mp4")
        val written = Kotmpeg.concat(segments, out)
        assertEquals(3L * segmentFrames, written)

        val demuxer = Kotmpeg.openDemuxer(out)
        var count = 0
        var lastPts = -1L
        var maxPts = 0L
        while (true) {
            val p = demuxer.readPacket() ?: break
            count++
            assertTrue(p.ptsUs > lastPts, "pts strictly increasing across segments")
            lastPts = p.ptsUs
            maxPts = maxOf(maxPts, p.ptsUs)
        }
        demuxer.close()
        assertEquals(3 * segmentFrames, count)
        assertTrue(maxPts > 2 * segmentFrames * 33_333L, "timestamps shifted (max=$maxPts)")
    }

    @Test
    fun `concat rejects incompatible segments`() {
        val a = File(dir, "ca.mkv").also { writeOneVideoTrack(it, hdrVideoTrack(90)) }
        val b = File(dir, "cb.mkv").also {
            writeOneVideoTrack(
                it,
                TrackInfo.Video(codec = VideoCodec.H264, width = 640, height = 480) { codecPrivate = avcC },
            )
        }
        val result = runCatching { Kotmpeg.concat(listOf(a, b), File(dir, "cc.mkv")) }
        assertTrue(result.isFailure, "dimension mismatch must be rejected")
    }

    /**
     * Dos segmentos con el mismo códec y las mismas dimensiones, pero otro SPS y otra rotación:
     * hasta la 3.x se unían sin error, y la segunda mitad salía corrupta y girada. El error tiene
     * que nombrar las dos diferencias, no solo la primera.
     */
    @Test
    fun `concat names every difference in the track header`() {
        val otherSps = NalUnits.buildAvcC(
            listOf(byteArrayOf(0x67, 0x4D, 0x00, 0x28, 0x11, 0x22, 0x33)),
            listOf(byteArrayOf(0x68, 0x11, 0x22)),
        )
        val a = File(dir, "sps-a.mkv").also { writeOneVideoTrack(it, hdrVideoTrack(90)) }
        val b = File(dir, "sps-b.mkv").also { writeOneVideoTrack(it, hdrVideoTrack(0).copy { codecPrivate = otherSps }) }
        val error = runCatching { Kotmpeg.concat(listOf(a, b), File(dir, "sps.mkv")) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "un SPS distinto tiene que rechazarse: $error")
        val message = error.message.orEmpty()
        assertTrue("codecPrivate" in message, "el error no nombra el codecPrivate: $message")
        assertTrue("rotación" in message, "el error no nombra la rotación: $message")
        assertTrue("entrada 1" in message, "el error no dice qué entrada falla: $message")
    }

    /** En audio, el cebado distinto también es una cabecera distinta. */
    @Test
    fun `concat rejects audio segments with a different priming`() {
        val asc = AacConfig.build(48000, 2)
        fun segment(name: String, delayUs: Long): File = File(dir, name).also { f ->
            Kotmpeg.createMuxer(f).use { muxer ->
                val id = muxer.addTrack(
                    TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2) { codecPrivate = asc; codecDelayUs = delayUs },
                )
                muxer.start()
                for (i in 0 until 10) muxer.writePacket(MediaPacket(id, ByteArray(32), i * 21_333L, isKeyFrame = true))
            }
        }
        val error = runCatching {
            Kotmpeg.concat(listOf(segment("p1.mkv", 21_333), segment("p2.mkv", 0)), File(dir, "p.mkv"))
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("cebado"), "el cebado distinto no se rechazó: $error")
    }

    /**
     * El cebado de la segunda entrada se queda dentro —quitarlo exigiría recodificar—, pero la
     * sincronía no se pierde: en la salida, el primer fotograma y el primer paquete de audio de la
     * segunda entrada empiezan en el mismo instante, como en su original, y el cebado declarado es
     * el de la primera.
     *
     * El mismo instante, hasta lo que deja ver la escala de cada pista: en MP4 el vídeo redondea a
     * 90 kHz y el audio a 48 kHz, así que si la unión no cae en un tic común los dos se releen a
     * menos de un tic de audio. La primera entrada acaba con el audio, en 1 002 651 µs. Hasta la 4.0
     * su `mvhd` contaba el cebado redondeado al milisegundo, 1 003 000 µs, y la unión caía siempre en
     * un tic común; ahora el `mvhd` es la suma de las ediciones, que lo descuentan, y la unión cae
     * justo donde acaba el último paquete.
     *
     * Que vídeo y audio de la segunda entrada coincidan no basta: coincidirían igual con la unión
     * desplazada cualquier cantidad. Lo que fija el silencio de la unión es que la segunda entrada
     * empiece justo donde acaba el audio de la primera —su último paquete más las 1 024 muestras que
     * dura—, a un tic de la escala de la salida: el de audio en MP4 y el milisegundo en MKV.
     */
    @Test
    fun `concat keeps each segment's audio aligned with its video`() {
        val asc = AacConfig.build(48000, 2)
        fun segment(name: String): File = File(dir, name).also { f ->
            Kotmpeg.createMuxer(f).use { muxer ->
                val v = muxer.addTrack(hdrVideoTrack(0))
                val a = muxer.addTrack(TrackInfo.Audio(AudioCodec.AAC, 48000, 2) { codecPrivate = asc; codecDelayUs = 21_333 })
                muxer.start()
                for (i in 0 until 30) {
                    val pts = i * 33_333L
                    muxer.writePacket(MediaPacket(v, NalUnits.joinLengthPrefixed(listOf(ByteArray(40) { i.toByte() })), pts, isKeyFrame = i == 0))
                    muxer.writePacket(MediaPacket(a, ByteArray(16), i * 21_333L, isKeyFrame = true))
                }
                for (i in 30 until 47) muxer.writePacket(MediaPacket(a, ByteArray(16), i * 21_333L, isKeyFrame = true))
            }
        }
        for (extension in listOf("mp4", "mkv")) {
            val out = File(dir, "sincronia.$extension")
            Kotmpeg.concat(listOf(segment("s1.$extension"), segment("s2.$extension")), out)
            Kotmpeg.openDemuxer(out).use { demuxer ->
                val (videoId, audioId) = demuxer.tracks.map { it.id }
                assertEquals(21_333L, (demuxer.tracks[1] as TrackInfo.Audio).codecDelayUs, "$extension: cebado")
                val packets = generateSequence { demuxer.readPacket() }.toList()
                val video = packets.filter { it.trackId == videoId }.map { it.ptsUs }.sorted()
                val audio = packets.filter { it.trackId == audioId }.map { it.ptsUs }
                val audioTickUs = 1_000_000L / 48_000 + 1
                val toleranceUs = if (extension == "mp4") audioTickUs else 0L
                assertTrue(
                    abs(video[30] - audio[47]) <= toleranceUs,
                    "$extension: la segunda entrada empieza desalineada: vídeo en ${video[30]} µs, audio en ${audio[47]} µs",
                )
                val firstAudioEndUs = audio[46] + 21_333
                val tickUs = if (extension == "mp4") audioTickUs else 1_000L
                assertTrue(
                    abs(video[30] - firstAudioEndUs) <= tickUs,
                    "$extension: la segunda entrada empieza en ${video[30]} µs y el audio de la primera acaba en $firstAudioEndUs µs",
                )
            }
        }
    }

    /**
     * Una llamada a la fachada que no puede salir bien se rechaza antes de abrir la salida, y lo
     * que hubiera en esa ruta sigue intacto. `concat` con entradas incompatibles y `remux` sin
     * pistas truncaban primero la salida y la dejaban vacía: se perdía un archivo que ya existía.
     */
    @Test
    fun `a rejected concat or remux leaves the existing output untouched`() {
        val first = File(dir, "uno.mp4").also { writeOneVideoTrack(it, hdrVideoTrack(0)) }
        val rotated = File(dir, "girado.mp4").also { writeOneVideoTrack(it, hdrVideoTrack(90)) }
        val previous = File(dir, "previo.mp4").also { first.copyTo(it) }
        val before = previous.readBytes()

        val incompatible = runCatching { Kotmpeg.concat(listOf(first, rotated), previous) }.exceptionOrNull()
        assertTrue(incompatible is IllegalArgumentException, "concat incompatible: $incompatible")
        assertTrue(before.contentEquals(previous.readBytes()), "concat rechazado tocó la salida")

        val noTracks = runCatching { Kotmpeg.remux(first, previous, trackFilter = { false }) }.exceptionOrNull()
        assertTrue(noTracks is IllegalArgumentException, "remux sin pistas: $noTracks")
        assertTrue(before.contentEquals(previous.readBytes()), "remux rechazado tocó la salida")
    }

    @Test
    fun `audio only concat`() {
        val asc = AacConfig.build(48000, 2)
        fun segment(name: String): File {
            val f = File(dir, name)
            val muxer = Kotmpeg.createMuxer(f)
            val id = muxer.addTrack(TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2) {
                codecPrivate = asc
            })
            muxer.start()
            for (i in 0 until 50) {
                muxer.writePacket(MediaPacket(id, ByteArray(32) { i.toByte() },
                                              i * 21_333L, i * 21_333L, true, 21_333))
            }
            muxer.stop()
            return f
        }
        val out = File(dir, "audio-joined.mkv")
        val written = Kotmpeg.concat(listOf(segment("a1.mkv"), segment("a2.mkv")), out)
        assertEquals(100L, written)
        val demuxer = Kotmpeg.openDemuxer(out)
        assertTrue(demuxer.durationUs > 2_000_000, "joined duration ${demuxer.durationUs}")
        demuxer.close()
    }
}
