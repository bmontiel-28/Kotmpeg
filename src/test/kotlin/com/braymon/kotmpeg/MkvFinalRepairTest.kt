package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.ebml.EbmlWriter
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.io.RepairTarget
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.pipeline.Remuxer
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * La reparación de un MKV frente a archivos que no escribe `MkvMuxer` pero que le pueden llegar: la
 * duración que declara tiene que ser la de los paquetes como los entrega el demuxer, que es lo que
 * daría reempaquetarlo, y un archivo que el demuxer abre no se rechaza por cómo acaba.
 */
class MkvFinalRepairTest {

    private val b = MkvReadBuilder

    private val avcC = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22)))

    /** `Info` con la escala de 1 ms y una `Duration` de 8 bytes, que es lo que la reparación parchea. */
    private fun info(durationTicks: Double = 0.0): ByteArray =
        b.element(MatroskaIds.INFO, b.uint(MatroskaIds.TIMESTAMP_SCALE, 1_000_000), b.float(MatroskaIds.DURATION, durationTicks))

    private fun videoTrack(number: Long, defaultDurationNs: Long): ByteArray = b.element(
        MatroskaIds.TRACK_ENTRY,
        b.uint(MatroskaIds.TRACK_NUMBER, number),
        b.uint(MatroskaIds.TRACK_TYPE, MatroskaIds.TRACK_TYPE_VIDEO),
        b.string(MatroskaIds.CODEC_ID, "V_MPEG4/ISO/AVC"),
        b.element(MatroskaIds.CODEC_PRIVATE, avcC),
        b.uint(MatroskaIds.DEFAULT_DURATION, defaultDurationNs),
        b.element(MatroskaIds.VIDEO, b.uint(MatroskaIds.PIXEL_WIDTH, 64), b.uint(MatroskaIds.PIXEL_HEIGHT, 48)),
    )

    private fun subtitleTrack(number: Long): ByteArray = b.element(
        MatroskaIds.TRACK_ENTRY,
        b.uint(MatroskaIds.TRACK_NUMBER, number),
        b.uint(MatroskaIds.TRACK_TYPE, MatroskaIds.TRACK_TYPE_SUBTITLE),
        b.string(MatroskaIds.CODEC_ID, "S_TEXT/UTF8"),
    )

    /**
     * Un MKV sin cerrar —el `Segment` de tamaño desconocido— con [children] detrás del hueco del
     * `SeekHead` de la 4.0, que es donde la reparación apunta al índice que escribe al final.
     */
    private fun unclosed(vararg children: ByteArray): ByteArray = b.openFile(EbmlWriter.encodeVoid(120), *children)

    /** La duración que declara [bytes] reempaquetado con `MkvMuxer`, en µs. */
    private fun remuxDurationUs(bytes: ByteArray): Long {
        val channel = RecordingFileChannel()
        Kotmpeg.openDemuxer(memoryInput(bytes)).use { demuxer ->
            Kotmpeg.createMuxer(SeekableOutput(channel), ContainerFormat.MKV) { creationTimeMillis = null }.use { muxer ->
                Remuxer.remux(demuxer, muxer)
            }
        }
        return Kotmpeg.openDemuxer(memoryInput(channel.bytes())).use { it.durationUs }
    }

    /** Repara [bytes] y exige la duración del remux, que tiene que ser [expectedUs]. */
    private fun assertRepairedLikeRemux(bytes: ByteArray, expectedUs: Long, label: String) {
        val remuxed = remuxDurationUs(bytes)
        assertEquals(expectedUs, remuxed, "$label: el remux")
        val (result, channel) = RecordingFileChannel.repairRecorded(bytes)
        assertTrue(result.repaired, label)
        assertEquals(remuxed, result.durationUs, "$label: lo que dice la reparación")
        assertEquals(remuxed, Kotmpeg.openDemuxer(memoryInput(channel.bytes())).use { it.durationUs }, "$label: lo que declara el archivo reparado")
    }

    /**
     * Una pista de subtítulos no la entrega el demuxer, así que no cuenta: con bloques en 10 y 50 s
     * y sin duración, se estimaba que el último duraba 40 s y la reparación declaraba 90 s.
     */
    @Test
    fun `tracks the demuxer discards do not count for the duration`() {
        val bytes = unclosed(
            info(),
            b.tracks(videoTrack(1, 33_000_000), subtitleTrack(2)),
            b.cluster(0, b.simpleBlock(1, 0, true), b.simpleBlock(2, 10_000, true)),
            b.cluster(30_000, b.simpleBlock(1, 0, true)),
            b.cluster(50_000, b.simpleBlock(1, 0, true), b.simpleBlock(2, 0, true), b.simpleBlock(1, 2_000, false)),
        )
        assertRepairedLikeRemux(bytes, 52_033_000, "con subtítulos")
    }

    /** La `BlockDuration` de un bloque manda sobre la `DefaultDuration` de su pista, como en el demuxer. */
    @Test
    fun `a block duration counts instead of the default duration`() {
        val bytes = unclosed(
            info(),
            b.tracks(videoTrack(1, 33_000_000)),
            b.cluster(0, b.simpleBlock(1, 0, true)),
            b.cluster(
                1_000,
                b.element(MatroskaIds.BLOCK_GROUP, b.element(MatroskaIds.BLOCK, b.blockBody(1, 0, 0, byteArrayOf(0x11))), b.uint(MatroskaIds.BLOCK_DURATION, 500)),
            ),
        )
        assertRepairedLikeRemux(bytes, 1_500_000, "con BlockDuration")
    }

    /**
     * Un bloque con lacing son varios fotogramas, uno detrás de otro: cinco de 20 ms acaban 100 ms
     * después de su tiempo, y no 20. Con `BlockDuration`, esta se reparte entre ellos.
     */
    @Test
    fun `a laced block counts each of its frames`() {
        val opus = b.audioTrack(2, b.uint(MatroskaIds.DEFAULT_DURATION, 20_000_000))
        val xiph = unclosed(
            info(),
            b.tracks(videoTrack(1, 33_000_000), opus),
            b.cluster(0, b.simpleBlock(1, 0, true)),
            b.cluster(1_000, b.simpleBlock(1, 0, true), b.lacedBlock(2, 0, 1, List(5) { ByteArray(3 + it) { 0x22 } })),
        )
        assertRepairedLikeRemux(xiph, 1_100_000, "lacing Xiph")

        val fixedInGroup = b.element(
            MatroskaIds.BLOCK_GROUP,
            b.element(MatroskaIds.BLOCK, b.blockBody(2, 100, 2 shl 1, byteArrayOf(3) + ByteArray(8) { 0x22 })),
            b.uint(MatroskaIds.BLOCK_DURATION, 400),
        )
        val grouped = unclosed(
            info(),
            b.tracks(videoTrack(1, 33_000_000), opus),
            b.cluster(0, b.simpleBlock(1, 0, true)),
            b.cluster(1_000, b.simpleBlock(1, 0, true), fixedInGroup),
        )
        assertRepairedLikeRemux(grouped, 1_500_000, "lacing fijo con BlockDuration")
    }

    /**
     * Un final de ceros justo detrás de la cabecera es un corte —una grabación que reservó espacio
     * y murió antes de su primer cluster—, como lo es detrás de los clusters. El demuxer abre ese
     * archivo, y la reparación lo cierra igual que sin los ceros. Algo distinto de cero entre ellos
     * es un daño, y se sigue rechazando sin tocar nada.
     */
    @Test
    fun `a zero tail right after the header is a cut`() {
        val channel = RecordingFileChannel()
        var startWrites = 0
        MkvMuxer(SeekableOutput(channel)) { creationTimeMillis = null }.use { muxer ->
            muxer.addTrack(TrackInfo.Video(VideoCodec.H264, 320, 240) { frameRate = 30.0; codecPrivate = avcC })
            muxer.addTrack(TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) })
            muxer.start()
            startWrites = channel.writes.size
        }
        val header = channel.cutAfter(channel.boundaries()[startWrites - 1])
        val zeros = 4_096
        val withZeros = header + ByteArray(zeros)
        Kotmpeg.openDemuxer(memoryInput(withZeros)).use { assertEquals(null, it.readPacket()) }

        val (expected, reference) = RecordingFileChannel.repairRecorded(header)
        val (result, repaired) = RecordingFileChannel.repairRecorded(withZeros)
        assertTrue(result.repaired)
        assertEquals(zeros.toLong(), result.discardedBytes)
        assertEquals(expected.durationUs, result.durationUs)
        assertContentEquals(reference.bytes(), repaired.bytes())

        val damaged = withZeros.copyOf().also { it[it.size - 1] = 1 }
        val target = RecordingFileChannel(damaged)
        val outcome = runCatching { RepairTarget.open(target).use { Kotmpeg.repair(it) } }
        assertTrue(outcome.exceptionOrNull() is IllegalArgumentException, "$outcome")
        assertTrue(target.writes.isEmpty(), "tocó el archivo al rechazarlo")
    }

    /**
     * Con un archivo ya cerrado, la duración del resultado es la que lee el demuxer, con la
     * fracción de tic: mkvmerge escribe `Duration` con decimales, y 1234,567 ms se quedaban en
     * 1 234 000 µs.
     */
    @Test
    fun `a closed file reports the duration the demuxer reads`() {
        val bytes = b.file(info(1_234.567), b.tracks(b.videoTrack(1)), b.cluster(0, b.simpleBlock(1, 0, true)))
        val declared = Kotmpeg.openDemuxer(memoryInput(bytes)).use { it.durationUs }
        assertEquals(1_234_567L, declared)
        val (result, channel) = RecordingFileChannel.repairRecorded(bytes)
        assertFalse(result.repaired)
        assertTrue(channel.writes.isEmpty())
        assertEquals(declared, result.durationUs)
    }
}
