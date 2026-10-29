package com.braymon.kotmpeg

import com.braymon.kotmpeg.MkvReadBuilder.audioTrack
import com.braymon.kotmpeg.MkvReadBuilder.blockBody
import com.braymon.kotmpeg.MkvReadBuilder.cluster
import com.braymon.kotmpeg.MkvReadBuilder.clusterOf
import com.braymon.kotmpeg.MkvReadBuilder.compression
import com.braymon.kotmpeg.MkvReadBuilder.cuePoint
import com.braymon.kotmpeg.MkvReadBuilder.cues
import com.braymon.kotmpeg.MkvReadBuilder.element
import com.braymon.kotmpeg.MkvReadBuilder.file
import com.braymon.kotmpeg.MkvReadBuilder.fileOf
import com.braymon.kotmpeg.MkvReadBuilder.float
import com.braymon.kotmpeg.MkvReadBuilder.info
import com.braymon.kotmpeg.MkvReadBuilder.lacedBlock
import com.braymon.kotmpeg.MkvReadBuilder.openFile
import com.braymon.kotmpeg.MkvReadBuilder.simpleBlock
import com.braymon.kotmpeg.MkvReadBuilder.string
import com.braymon.kotmpeg.MkvReadBuilder.tracks
import com.braymon.kotmpeg.MkvReadBuilder.uint
import com.braymon.kotmpeg.MkvReadBuilder.unknownSize
import com.braymon.kotmpeg.MkvReadBuilder.videoTrack
import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.mkv.MkvDemuxer
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ColorInfo
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lecturas de Matroska que, encontradas en una revisión del demuxer, rechazaban un archivo
 * legible, entregaban algo distinto de lo que dice el archivo o lo hacían sin decir nada:
 *
 * - **Cabeceras que tumbaban la apertura** por un dato de una sola pista: un `TrackNumber` 0, un
 *   `MasteringMetadata` incoherente o unos ceros antes del primer cluster.
 * - **Tiempos mal resueltos**: `DefaultDuration` ganaba a `BlockDuration`, y un `Cues` anterior a
 *   `Info` —orden legal— se convertía con la escala por defecto.
 * - **Búsquedas** que entregaban primero un fotograma que no se puede decodificar, o que lanzaban
 *   o terminaban en silencio por un `CueClusterPosition` imposible.
 * - **Finales y descartes silenciosos**, incluido un `null` de `readPacket` tras el que seguían
 *   llegando paquetes.
 */
class MkvReadRobustnessTest {

    @TempDir
    lateinit var dir: File

    private fun open(bytes: ByteArray, warnings: MutableList<String> = ArrayList()): MkvDemuxer =
        MkvDemuxer(SeekableInput(MemoryFileChannel(bytes)), onWarning = { warnings += it })

    private fun readAll(bytes: ByteArray, warnings: MutableList<String> = ArrayList()): List<MediaPacket> =
        open(bytes, warnings).use { demuxer -> generateSequence { demuxer.readPacket() }.toList() }

    private val h264 = TrackInfo.Video(VideoCodec.H264, 64, 48) {
        frameRate = 25.0
        codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22)))
    }

    private val aac = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) }

    private fun frame(i: Int, size: Int = 2): ByteArray =
        NalUnits.joinLengthPrefixed(listOf(ByteArray(size) { k -> if (k == 0) 0x41 else (i + k).toByte() }))

    @Test
    fun `block duration wins over the track default duration`() {
        val group = element(
            MatroskaIds.BLOCK_GROUP,
            element(MatroskaIds.BLOCK, blockBody(1, 0, 0, byteArrayOf(1))),
            uint(MatroskaIds.BLOCK_DURATION, 100),
        )
        val bytes = file(info(), tracks(audioTrack(1, uint(MatroskaIds.DEFAULT_DURATION, 20_000_000))), cluster(0, group))
        assertEquals(listOf(100_000L), readAll(bytes).map { it.durationUs })
    }

    @Test
    fun `an impossible or missing track number drops that entry with a warning`() {
        for (bad in listOf(videoTrack(0), videoTrack(1L shl 31), element(MatroskaIds.TRACK_ENTRY, uint(MatroskaIds.TRACK_TYPE, 1)))) {
            val warnings = ArrayList<String>()
            val bytes = file(info(), tracks(bad, videoTrack(1)), cluster(0, simpleBlock(1, 0, true)))
            open(bytes, warnings).use { demuxer ->
                assertEquals(listOf(1), demuxer.tracks.map { it.id })
                assertEquals(1, generateSequence { demuxer.readPacket() }.count())
            }
            assertTrue(warnings.any { "TrackNumber" in it }, "la entrada descartada no se avisó: $warnings")
        }
    }

    /** La segunda entrada con el mismo número sustituye a la primera, con su compresión y no con la ajena. */
    @Test
    fun `a repeated track number does not inherit the content encodings of the first entry`() {
        val bytes = file(
            info(),
            tracks(audioTrack(1, compression(3, byteArrayOf(0xAA.toByte(), 0xBB.toByte()))), audioTrack(1)),
            cluster(0, simpleBlock(1, 0, true, byteArrayOf(1, 2, 3))),
        )
        val warnings = ArrayList<String>()
        assertContentEquals(byteArrayOf(1, 2, 3), readAll(bytes, warnings).single().data)
        assertTrue(warnings.any { "pista 1" in it }, "el número repetido no se avisó: $warnings")
    }

    @Test
    fun `an unsupported track is reported once and with the right reason`() {
        val subtitles = element(
            MatroskaIds.TRACK_ENTRY,
            uint(MatroskaIds.TRACK_NUMBER, 2),
            uint(MatroskaIds.TRACK_TYPE, MatroskaIds.TRACK_TYPE_SUBTITLE),
            string(MatroskaIds.CODEC_ID, "S_TEXT/UTF8"),
        )
        for (other in listOf(subtitles, videoTrack(2, codecId = "V_VP9"))) {
            val warnings = ArrayList<String>()
            val blocks = (0 until 5).flatMap { listOf(simpleBlock(1, it * 40, it == 0), simpleBlock(2, it * 40, true)) }
            val packets = readAll(fileOf(listOf(info(), tracks(videoTrack(1), other), clusterOf(0, blocks))), warnings)
            assertEquals(5, packets.size)
            assertEquals(1, warnings.size, "avisos: $warnings")
            assertTrue("pista 2" in warnings.single() && "no se pudo construir" !in warnings.single(), warnings.single())
        }
    }

    /**
     * Un `Tags` de tamaño desconocido tras los clusters terminaba la lectura sin aviso y sin
     * marcarla terminada: la llamada siguiente leía lo que había dentro como si fueran clusters, y
     * llegaban paquetes después de un `null`.
     */
    @Test
    fun `no packet arrives after readPacket returned null`() {
        val bytes = openFile(
            info(),
            tracks(videoTrack(1)),
            cluster(0, simpleBlock(1, 0, true), simpleBlock(1, 40, false)),
            unknownSize(MatroskaIds.TAGS),
            cluster(1000, simpleBlock(1, 0, true)),
        )
        val warnings = ArrayList<String>()
        open(bytes, warnings).use { demuxer ->
            assertEquals(2, generateSequence { demuxer.readPacket() }.count())
            assertNull(demuxer.readPacket(), "llegó un paquete después de un null")
        }
        assertTrue(warnings.isNotEmpty(), "el final por un elemento de tamaño desconocido no se avisó")
    }

    @Test
    fun `impossible blocks and unreadable endings are reported`() {
        val cases = mapOf(
            "bloque de 3 bytes" to element(MatroskaIds.SIMPLE_BLOCK, byteArrayOf(0x81.toByte(), 0, 0)),
            "pista con VINT nulo" to element(MatroskaIds.SIMPLE_BLOCK, byteArrayOf(0, 0, 0, 0x80.toByte(), 1)),
            "lacing que no cuadra" to element(MatroskaIds.SIMPLE_BLOCK, blockBody(1, 0, 0x82, byteArrayOf(0xFF.toByte(), 1, 2))),
            "hijo de tamaño desconocido" to unknownSize(MatroskaIds.VOID),
        )
        for ((name, broken) in cases) {
            val warnings = ArrayList<String>()
            val packets = readAll(file(info(), tracks(videoTrack(1)), cluster(0, simpleBlock(1, 0, true), broken)), warnings)
            assertEquals(1, packets.size, name)
            assertTrue(warnings.isNotEmpty(), "$name: se descartó sin aviso")
        }
    }

    @Test
    fun `a closed demuxer refuses to keep reading or seeking`() {
        val bytes = file(info(), tracks(audioTrack(1)), cluster(0, lacedBlock(1, 0, 2, List(4) { ByteArray(2) })))
        val demuxer = open(bytes)
        assertNotNull(demuxer.readPacket())
        demuxer.close()
        assertFailsWith<IllegalStateException> { demuxer.readPacket() }
        assertFailsWith<IllegalStateException> { demuxer.seekTo(0) }
        demuxer.close()
    }

    /**
     * Un error de E/S de verdad durante la búsqueda —aquí, el canal cerrado por debajo— se tragaba,
     * y `seekTo` devolvía 0 como si el archivo no tuviera keyframes.
     */
    @Test
    fun `an I-O error while seeking is not swallowed`() {
        val path = File(dir, "grande.mkv")
        MkvMuxer(path) { maxClusterDurationMs = 200 }.use { muxer ->
            val v = muxer.addTrack(h264)
            muxer.start()
            for (i in 0 until 100) muxer.writePacket(MediaPacket(v, frame(i, 10_000), i * 40_000L, isKeyFrame = i % 25 == 0))
        }
        val raf = RandomAccessFile(path, "r")
        MkvDemuxer(SeekableInput(raf)).use { demuxer ->
            raf.close()
            assertFailsWith<IOException> { demuxer.seekTo(3_500_000) }
        }
    }

    /**
     * Un `CueClusterPosition` cerca de `Long.MAX_VALUE` desbordaba al sumarle el principio del
     * `Segment` y `seekTo` lanzaba; uno más allá del final colocaba la lectura fuera del archivo, y
     * tras buscar no llegaba nada. Ahora esos puntos se descartan al leer el índice.
     */
    @Test
    fun `cue positions outside the segment are ignored`() {
        val c0 = cluster(0, simpleBlock(1, 0, false), simpleBlock(1, 500, true), simpleBlock(1, 900, false))
        val c1 = cluster(1000, simpleBlock(1, 0, false), simpleBlock(1, 500, false))
        for (position in listOf(0x7FFF_FFFF_FFFF_FFF0L, 1L shl 30)) {
            open(file(info(), tracks(videoTrack(1)), cues(cuePoint(0, 1L to position)), c0, c1)).use { demuxer ->
                assertEquals(500_000L, demuxer.seekTo(0), "posición $position")
                assertEquals(500_000L, demuxer.readPacket()?.ptsUs, "posición $position")
            }
        }
    }

    /**
     * `Cues` antes de `Info` es un orden legal, y sus tiempos se convertían con la escala por
     * defecto (1 ms) en vez de con la del archivo (aquí 10 ms): buscar el medio segundo caía en
     * el cue del segundo 3, leído como 300 ms, y devolvía un tiempo que no era de ningún keyframe.
     */
    @Test
    fun `cues before info use the timestamp scale of the file`() {
        val clusters = (0 until 4).map { k -> cluster(k * 100L, simpleBlock(1, 0, true), simpleBlock(1, 50, false)) }
        val head = listOf(tracks(videoTrack(1)))
        val cuesSize = cues(*Array(4) { cuePoint(it * 100L, 1L to 0L) }).size
        val infoSize = info(10_000_000).size
        var at = (cuesSize + infoSize + head.sumOf { it.size }).toLong()
        val positions = clusters.map { c -> at.also { at += c.size } }
        val index = cues(*Array(4) { cuePoint(it * 100L, 1L to positions[it]) })
        val bytes = fileOf(listOf(index, info(10_000_000)) + head + clusters)
        open(bytes).use { demuxer ->
            assertEquals(0L, demuxer.seekTo(500_000))
            assertEquals(2_000_000L, demuxer.seekTo(2_500_000))
        }
    }

    /**
     * De cada `CuePoint` solo se guardaba el último `CueTrackPositions`: si era el del audio, el
     * punto dejaba de contar para el vídeo. Aquí un cluster que no se puede recorrer separa los
     * puntos, así que sin los del vídeo la búsqueda se queda en el primero.
     */
    @Test
    fun `every cue track position of a cue point is kept`() {
        val clusters = (0 until 5).map { k ->
            if (k == 1) {
                cluster(1000, unknownSize(MatroskaIds.VOID))
            } else {
                cluster(k * 1000L, simpleBlock(1, 0, true), simpleBlock(2, 0, true))
            }
        }
        val head = listOf(info(), tracks(videoTrack(1), audioTrack(2)))
        fun index(positions: List<Long>) = cues(
            cuePoint(0, 1L to positions[0]),
            cuePoint(2000, 1L to positions[2], 2L to positions[2]),
            cuePoint(3000, 1L to positions[3], 2L to positions[3]),
        )
        var at = head.sumOf { it.size }.toLong() + index(List(5) { 0L }).size
        val positions = clusters.map { c -> at.also { at += c.size } }
        open(fileOf(head + index(positions) + clusters)).use { demuxer ->
            assertEquals(3_000_000L, demuxer.seekTo(3_500_000))
        }
    }

    /**
     * Una grabación que reservó espacio con ceros y se cortó antes del primer cluster: hasta ahora
     * el constructor lanzaba por el «id» de byte 0, mientras que un corte a mitad de una cabecera
     * se abría con aviso. Son el mismo archivo y se tratan igual.
     */
    @Test
    fun `zeros before the first cluster are a cut, not a corrupt file`() {
        val path = File(dir, "ceros.mkv")
        MkvMuxer(path).use { muxer ->
            val v = muxer.addTrack(h264)
            muxer.start()
            for (i in 0 until 10) muxer.writePacket(MediaPacket(v, frame(i), i * 40_000L, isKeyFrame = i == 0))
        }
        val bytes = path.readBytes()
        val firstCluster = MkvReadBuilder.indexOf(bytes, MkvReadBuilder.id(MatroskaIds.CLUSTER))
        assertTrue(firstCluster > 0)
        val warnings = ArrayList<String>()
        open(bytes.copyOf(firstCluster) + ByteArray(4096), warnings).use { demuxer ->
            assertEquals(1, demuxer.tracks.size)
            assertNull(demuxer.readPacket())
        }
        assertEquals(1, warnings.size, "avisos: $warnings")
    }

    /**
     * El keyframe de una búsqueda no llega —aquí su bloque tiene un lacing roto—, la búsqueda se
     * abandona a los diez segundos y se entrega todo lo leído. Las pistas de vídeo salían desde un
     * fotograma que no se puede decodificar; ahora cada una espera a su siguiente keyframe.
     */
    @Test
    fun `giving up on a seek still starts every video track on a keyframe`() {
        val clusters = (0 until 20).map { second ->
            val blocks = ArrayList<ByteArray>()
            for (f in 0 until 10) {
                val t = second * 1000 + f * 100
                blocks += if (t == 4000) {
                    element(MatroskaIds.SIMPLE_BLOCK, blockBody(1, f * 100, 0x82, byteArrayOf(0xFF.toByte(), 1, 2)))
                } else {
                    simpleBlock(1, f * 100, key = t == 0 || t == 16_000)
                }
                blocks += simpleBlock(2, f * 100, key = t == 0 || t == 7000 || t == 16_000)
            }
            clusterOf(second * 1000L, blocks)
        }
        open(fileOf(listOf(info(), tracks(videoTrack(1), videoTrack(2))) + clusters)).use { demuxer ->
            assertEquals(4_000_000L, demuxer.seekTo(4_500_000))
            val after = generateSequence { demuxer.readPacket() }.toList()
            for (track in listOf(1, 2)) {
                val first = after.first { it.trackId == track }
                assertTrue(first.isKeyFrame, "la pista $track empieza en ${first.ptsUs} µs, que no es keyframe")
            }
        }
    }

    /**
     * Dos pistas de vídeo y un audio que terminó: una búsqueda en el último segundo se decide al
     * llegar al final del archivo, y el primer paquete se entregaba sin pasar por el filtro de
     * keyframes, así que salía un P de la segunda pista.
     */
    @Test
    fun `a seek decided at the end of the file still filters the second video track`() {
        val path = File(dir, "final.mkv")
        MkvMuxer(path) { maxClusterDurationMs = 200 }.use { muxer ->
            val main = muxer.addTrack(h264)
            val camera = muxer.addTrack(h264.copy { name = "Cámara" })
            val audio = muxer.addTrack(aac)
            muxer.start()
            var audioUs = 0L
            for (i in 0 until 100) {
                val pts = i * 40_000L
                while (audioUs <= pts && audioUs < 1_000_000) {
                    muxer.writePacket(MediaPacket(audio, ByteArray(8), audioUs, isKeyFrame = true))
                    audioUs += 21_333
                }
                muxer.writePacket(MediaPacket(main, frame(i), pts, isKeyFrame = i % 30 == 0))
                muxer.writePacket(MediaPacket(camera, frame(i), pts, isKeyFrame = i % 30 == 10))
            }
        }
        MkvDemuxer(path).use { demuxer ->
            val (main, camera) = demuxer.tracks.map { it.id }
            assertEquals(3_600_000L, demuxer.seekTo(3_700_000))
            val after = generateSequence { demuxer.readPacket() }.toList()
            assertTrue(after.first().let { it.trackId == main && it.isKeyFrame }, "el primer paquete es ${after.first().trackId}@${after.first().ptsUs}")
            val firstCamera = after.firstOrNull { it.trackId == camera }
            assertTrue(firstCamera == null || firstCamera.isKeyFrame, "la segunda pista empieza en un fotograma que no es keyframe")
        }
    }

    private fun colourTrack(vararg colour: ByteArray): ByteArray =
        videoTrack(1, video = listOf(element(MatroskaIds.COLOUR, *colour)))

    private fun mastering(lumMax: Double, lumMin: Double, redX: Double = 0.68): ByteArray = element(
        MatroskaIds.MASTERING_METADATA,
        float(MatroskaIds.PRIMARY_R_X, redX), float(MatroskaIds.PRIMARY_R_Y, 0.32),
        float(MatroskaIds.PRIMARY_G_X, 0.265), float(MatroskaIds.PRIMARY_G_Y, 0.69),
        float(MatroskaIds.PRIMARY_B_X, 0.15), float(MatroskaIds.PRIMARY_B_Y, 0.06),
        float(MatroskaIds.WHITE_POINT_X, 0.3127), float(MatroskaIds.WHITE_POINT_Y, 0.329),
        float(MatroskaIds.LUMINANCE_MAX, lumMax), float(MatroskaIds.LUMINANCE_MIN, lumMin),
    )

    /** Metadatos de color imposibles se ignoran con aviso, como hace FFmpeg; la pista se queda. */
    @Test
    fun `impossible colour metadata is dropped and the track kept`() {
        val primaries = uint(MatroskaIds.COLOUR_PRIMARIES, 9)
        val cases = mapOf(
            "luminancia mínima mayor que la máxima" to colourTrack(primaries, mastering(lumMax = 100.0, lumMin = 1000.0)),
            "cromaticidad NaN" to colourTrack(primaries, mastering(lumMax = 1000.0, lumMin = 0.01, redX = Double.NaN)),
            "primarios fuera de rango" to colourTrack(uint(MatroskaIds.COLOUR_PRIMARIES, 300)),
        )
        for ((name, track) in cases) {
            val warnings = ArrayList<String>()
            open(file(info(), tracks(track), cluster(0, simpleBlock(1, 0, true))), warnings).use { demuxer ->
                val video = demuxer.tracks.single() as TrackInfo.Video
                assertNull(video.color?.hdr, name)
                if (name == "primarios fuera de rango") assertNull(video.color, name) else assertEquals(ColorInfo.PRIMARIES_BT2020, video.color?.primaries, name)
                assertNotNull(demuxer.readPacket(), name)
            }
            assertEquals(1, warnings.size, "$name: $warnings")
        }
    }

    /** Un `PixelWidth` que no cabe en `Int` se truncaba en silencio: 2³² + 64 se leía como 64. */
    @Test
    fun `a dimension wider than an int drops the track instead of wrapping`() {
        val warnings = ArrayList<String>()
        open(file(info(), tracks(videoTrack(1, width = (1L shl 32) + 64)), cluster(0, simpleBlock(1, 0, true))), warnings).use { demuxer ->
            assertTrue(demuxer.tracks.isEmpty(), "la pista se abrió con ${(demuxer.tracks.firstOrNull() as? TrackInfo.Video)?.width} de ancho")
        }
        assertTrue(warnings.any { "pista 1" in it }, "avisos: $warnings")
    }

    /** Un `ClusterTimestamp` de 8 bytes con el bit alto daba tiempos negativos sin ningún aviso. */
    @Test
    fun `a cluster timestamp past the signed range ends the stream with a warning`() {
        val badCluster = element(
            MatroskaIds.CLUSTER,
            element(MatroskaIds.CLUSTER_TIMESTAMP, ByteArray(8) { -1 }),
            simpleBlock(1, 0, true),
        )
        val warnings = ArrayList<String>()
        val packets = readAll(file(info(), tracks(videoTrack(1)), cluster(0, simpleBlock(1, 0, true)), badCluster), warnings)
        assertEquals(listOf(0L), packets.map { it.ptsUs })
        assertTrue(warnings.isNotEmpty())
    }

    /** Un `ReferenceBlock` de más de 8 bytes no se usa para nada y terminaba el stream. */
    @Test
    fun `an oversized reference block is skipped, not fatal`() {
        val group = element(
            MatroskaIds.BLOCK_GROUP,
            element(MatroskaIds.BLOCK, blockBody(1, 40, 0, byteArrayOf(1))),
            element(MatroskaIds.REFERENCE_BLOCK, ByteArray(9)),
        )
        val packets = readAll(file(info(), tracks(videoTrack(1)), cluster(0, simpleBlock(1, 0, true), group, simpleBlock(1, 80, false))))
        assertEquals(listOf(0L, 40_000L, 80_000L), packets.map { it.ptsUs })
        assertEquals(listOf(true, false, false), packets.map { it.isKeyFrame })
    }
}
