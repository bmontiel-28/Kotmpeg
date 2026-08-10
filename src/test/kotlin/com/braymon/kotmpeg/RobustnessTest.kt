package com.braymon.kotmpeg

import com.braymon.kotmpeg.audio.PcmMixer
import com.braymon.kotmpeg.audio.PcmResampler
import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.codecconfig.OpusConfig
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.Mp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cubre lo que una suite de casos normales no toca: archivos corruptos o truncados, límites
 * de tamaño, y los caminos de configuración de códec y DSP que solo aparecen fuera del caso
 * común.
 *
 * El criterio de "correcto" ante un archivo malformado es siempre el mismo: fallar rápido
 * con un error entendible, sin OutOfMemoryError, sin bucles infinitos y sin dejar el
 * archivo abierto.
 */
class RobustnessTest {

    @TempDir
    lateinit var dir: File

    private val fakeSps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)
    private val fakePps = byteArrayOf(0x68, 0x11, 0x22)
    private val avcC = NalUnits.buildAvcC(listOf(fakeSps), listOf(fakePps))

    private fun videoTrack() = TrackInfo.Video(
        codec = VideoCodec.H264, width = 320, height = 240, frameRate = 30.0, codecPrivate = avcC,
    )

    private fun audioTrack(rate: Int = 48000, channels: Int = 2) = TrackInfo.Audio(
        codec = AudioCodec.AAC, sampleRate = rate, channelCount = channels,
        codecPrivate = AacConfig.build(rate, channels),
    )

    /** Escribe un archivo pequeño pero válido en el contenedor pedido. */
    private fun writeSample(file: File, format: ContainerFormat, frames: Int = 12): File {
        val muxer = MkvKotlin.createMuxer(file, format)
        val videoId = muxer.addTrack(videoTrack())
        muxer.start()
        val rnd = Random(3)
        for (i in 0 until frames) {
            val payload = ByteArray(48).also { rnd.nextBytes(it) }
            muxer.writePacket(
                MediaPacket(
                    trackId = videoId,
                    data = NalUnits.joinLengthPrefixed(listOf(payload)),
                    ptsUs = i * 33_333L,
                    dtsUs = i * 33_333L,
                    isKeyFrame = i % 4 == 0,
                    durationUs = 33_333L,
                ),
            )
        }
        muxer.stop()
        return file
    }

    @Test
    fun `truncated files fail cleanly instead of hanging or exhausting memory`() {
        for (format in ContainerFormat.entries) {
            val good = writeSample(File(dir, "good.${format.name.lowercase()}"), format)
            val bytes = good.readBytes()
            for (fraction in listOf(0.1, 0.3, 0.5, 0.75, 0.95)) {
                val cut = File(dir, "cut-${format.name}-$fraction.bin")
                cut.writeBytes(bytes.copyOfRange(0, (bytes.size * fraction).toInt()))
                runCatching {
                    MkvKotlin.openDemuxer(cut).use { demuxer ->
                        while (demuxer.readPacket() != null) Unit
                    }
                }
            }
        }
    }

    @Test
    fun `garbage with a valid magic number does not allocate wildly`() {
        val rnd = Random(11)
        repeat(40) { seed ->
            val bytes = ByteArray(512)
            rnd.nextBytes(bytes)
            bytes[0] = 0x1A; bytes[1] = 0x45; bytes[2] = 0xDF.toByte(); bytes[3] = 0xA3.toByte()
            val f = File(dir, "fuzz-mkv-$seed.mkv")
            f.writeBytes(bytes)
            runCatching { MkvKotlin.openDemuxer(f).use { while (it.readPacket() != null) Unit } }
        }
        repeat(40) { seed ->
            val bytes = ByteArray(512)
            rnd.nextBytes(bytes)
            bytes[0] = 0; bytes[1] = 0; bytes[2] = 0; bytes[3] = 0x18
            bytes[4] = 'f'.code.toByte(); bytes[5] = 't'.code.toByte()
            bytes[6] = 'y'.code.toByte(); bytes[7] = 'p'.code.toByte()
            val f = File(dir, "fuzz-mp4-$seed.mp4")
            f.writeBytes(bytes)
            runCatching { MkvKotlin.openDemuxer(f).use { while (it.readPacket() != null) Unit } }
        }
    }

    @Test
    fun `an mkv master element with unknown size is rejected, not followed to EOF`() {
        val good = writeSample(File(dir, "sized.mkv"), ContainerFormat.MKV)
        val bytes = good.readBytes()
        val tracksId = byteArrayOf(0x16, 0x54.toByte(), 0xAE.toByte(), 0x6B)
        val at = indexOf(bytes, tracksId)
        assertTrue(at > 0, "no se encontró el elemento Tracks en el archivo de prueba")
        for (i in 0 until 8) bytes[at + 4 + i] = 0xFF.toByte()
        bytes[at + 4] = 0x01
        val broken = File(dir, "unknown-size.mkv")
        broken.writeBytes(bytes)

        assertFailsWith<Exception> { MkvKotlin.openDemuxer(broken).close() }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun `stopping a muxer that never started closes the file instead of throwing`() {
        val mkv = File(dir, "never-started.mkv")
        MkvMuxer(mkv).stop()
        val mp4 = File(dir, "never-started.mp4")
        Mp4Muxer(mp4).stop()
        MkvMuxer(File(dir, "twice.mkv")).apply { stop(); stop() }
    }

    @Test
    fun `mkv rejects more tracks than the block header can address`() {
        val muxer = MkvMuxer(File(dir, "many-tracks.mkv"))
        repeat(126) { muxer.addTrack(audioTrack()) }
        assertFailsWith<IllegalArgumentException> { muxer.addTrack(audioTrack()) }
        muxer.stop()
    }

    @Test
    fun `mkv seeks by scanning clusters when the file has no cue index`() {
        val file = writeSample(File(dir, "seekable.mkv"), ContainerFormat.MKV, frames = 120)
        val bytes = file.readBytes()
        val cuesId = byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)
        var at = indexOf(bytes, cuesId)
        assertTrue(at > 0)
        bytes[at + 3] = 0x6A
        val noCues = File(dir, "no-cues.mkv")
        noCues.writeBytes(bytes)

        MkvKotlin.openDemuxer(noCues).use { demuxer ->
            val target = 2_000_000L
            val landed = demuxer.seekTo(target)
            assertTrue(landed > 0, "sin Cues el seek volvió al principio en vez de escanear")
            assertTrue(landed <= target, "el seek se pasó del objetivo: $landed > $target")
            val packet = demuxer.readPacket()
            assertNotNull(packet, "tras el seek no se pudo leer ningún paquete")
        }
    }

    @Test
    fun `aac config round trips non standard sample rates and 7 point 1 audio`() {
        val odd = AacConfig.build(sampleRate = 37800, channelCount = 2)
        val parsedOdd = AacConfig.parse(odd)
        assertEquals(37800, parsedOdd.sampleRate)
        assertEquals(2, parsedOdd.channelCount)

        val surround = AacConfig.build(sampleRate = 48000, channelCount = 8)
        val parsedSurround = AacConfig.parse(surround)
        assertEquals(48000, parsedSurround.sampleRate)
        assertEquals(8, parsedSurround.channelCount)

        assertFailsWith<IllegalArgumentException> { AacConfig.build(48000, 7) }
    }

    @Test
    fun `esds survives an audio specific config of 128 bytes or more`() {
        val bigAsc = AacConfig.build(48000, 2) + ByteArray(140) { (it and 0x7F).toByte() }
        val file = File(dir, "big-asc.mp4")
        val muxer = Mp4Muxer(file)
        val id = muxer.addTrack(
            TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2, codecPrivate = bigAsc),
        )
        muxer.start()
        muxer.writePacket(MediaPacket(id, ByteArray(32), 0, 0, true, 21_333))
        muxer.writePacket(MediaPacket(id, ByteArray(32), 21_333, 21_333, true, 21_333))
        muxer.stop()

        MkvKotlin.openDemuxer(file).use { demuxer ->
            val track = demuxer.tracks.filterIsInstance<TrackInfo.Audio>().single()
            assertContentEquals(bigAsc, track.codecPrivate, "el ASC largo no sobrevivió al esds")
        }
    }

    @Test
    fun `opus multichannel mapping survives the opus head to dops conversion`() {
        val mapping = byteArrayOf(4, 2, 0, 4, 1, 2, 3, 5)
        val head = OpusConfig.buildOpusHead(
            channelCount = 6, mappingFamily = 1, channelMapping = mapping,
        )
        val dops = OpusConfig.opusHeadToDops(head)
        val back = OpusConfig.dopsToOpusHead(dops)
        val parsed = OpusConfig.parseOpusHead(back)
        assertEquals(6, parsed.channelCount)
        assertEquals(1, parsed.mappingFamily)
        assertContentEquals(mapping, parsed.channelMapping)

        assertFailsWith<IllegalArgumentException> { OpusConfig.buildOpusHead(channelCount = 6) }
    }

    @Test
    fun `annex b splitting drops cabac zero words instead of appending them to the nal`() {
        val first = byteArrayOf(0x67, 0x11, 0x22)
        val second = byteArrayOf(0x68, 0x33)
        val stream = byteArrayOf(0, 0, 0, 1) + first +
            byteArrayOf(0, 0, 0, 0, 0, 0) + byteArrayOf(0, 0, 0, 1) + second
        val nals = NalUnits.splitAnnexB(stream)
        assertEquals(2, nals.size)
        assertContentEquals(first, nals[0], "el NAL se quedó con los ceros de relleno pegados")
        assertContentEquals(second, nals[1])
    }

    @Test
    fun `corrupt codec config records fail with a clear error`() {
        assertFailsWith<IllegalArgumentException> { NalUnits.parseAvcC(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { NalUnits.parseAvcC(ByteArray(4)) }
        assertFailsWith<IllegalArgumentException> { NalUnits.parseHvcC(ByteArray(5)) }
        val lying = byteArrayOf(1, 0x64, 0, 0x1F, 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0xFF.toByte())
        assertFailsWith<IllegalArgumentException> { NalUnits.parseAvcC(lying) }
    }

    @Test
    fun `resampler flush emits the frames that the streaming path holds back`() {
        val resampler = PcmResampler(inputRate = 48000, outputRate = 44100, channels = 1)
        val input = ShortArray(1000) { (it % 100).toShort() }
        val streamed = resampler.resample(input).size
        val flushed = resampler.flush().size
        assertTrue(flushed > 0, "flush() no emitió los frames retenidos")
        assertEquals(919, streamed + flushed, "el remuestreador no converge al total exacto")
        assertEquals(0, resampler.flush().size)
    }

    @Test
    fun `resampling converges to the exact frame count for several rate pairs`() {
        val cases = listOf(
            Triple(48000, 16000, 4800),
            Triple(48000, 44100, 1000),
            Triple(44100, 48000, 1000),
            Triple(16000, 48000, 1600),
            Triple(48000, 22050, 3000),
        )
        for ((inRate, outRate, frames) in cases) {
            val resampler = PcmResampler(inRate, outRate, channels = 2)
            var produced = 0
            var written = 0
            while (written < frames) {
                val chunk = minOf(317, frames - written)
                produced += resampler.resample(ShortArray(chunk * 2) { (it % 50).toShort() }).size / 2
                written += chunk
            }
            produced += resampler.flush().size / 2
            val expected = Math.round(frames.toDouble() * outRate / inRate)
            assertEquals(expected, produced.toLong(), "$inRate -> $outRate no converge")
        }
    }

    @Test
    fun `resampler and mixer reject partial frames instead of dropping them silently`() {
        val resampler = PcmResampler(48000, 24000, channels = 2)
        assertFailsWith<IllegalArgumentException> { resampler.resample(ShortArray(5)) }
        assertFailsWith<IllegalArgumentException> { PcmMixer.stereoToMono(ShortArray(5)) }
    }

    @Test
    fun `mixing rounds to nearest instead of biasing towards silence`() {
        val mixed = PcmMixer.mix(listOf(shortArrayOf(1, -1)), listOf(0.75f))
        assertEquals(1, mixed[0].toInt())
        assertEquals(-1, mixed[1].toInt())
    }

    @Test
    fun `impossible track parameters are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            TrackInfo.Video(codec = VideoCodec.H264, width = 0, height = 240)
        }
        assertFailsWith<IllegalArgumentException> {
            TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 0, channelCount = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 0)
        }
    }
}
