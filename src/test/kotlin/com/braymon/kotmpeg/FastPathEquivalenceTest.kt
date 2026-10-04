package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.Timestamps
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.BoxBuilder
import com.braymon.kotmpeg.mp4.Mp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Las optimizaciones de la 3.0 cambian **cómo** se calcula algo, nunca **qué** sale. Cada test
 * compara la ruta rápida contra la implementación de referencia —la anterior, o la definición
 * directa— sobre entradas aleatorias o extremas:
 *
 *  - la lectura directa del canal para bloques grandes de `SeekableInput`;
 *  - la conversión Annex-B ↔ ISO en una pasada de `NalUnits`;
 *  - `BoxBuilder` sobre un único array con tamaños rellenados al cerrar cada caja;
 *  - el seek por bisección de `Mp4Demuxer`;
 *  - la conversión de escalas de tiempo sin desbordamiento.
 */
class FastPathEquivalenceTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `direct channel reads return the same bytes as buffered ones`() {
        val random = Random(7)
        val content = ByteArray(600_000).also { random.nextBytes(it) }
        val file = File(dir, "datos.bin").apply { writeBytes(content) }
        SeekableInput(file).use { input ->
            repeat(300) {
                val length = if (random.nextBoolean()) random.nextInt(1, 200) else random.nextInt(60_000, 200_000)
                val at = random.nextLong(0, (content.size - length).toLong())
                input.position = at
                if (random.nextInt(4) == 0) input.readByte().also { input.position = at }
                val read = input.readBytes(length)
                assertContentEquals(content.copyOfRange(at.toInt(), at.toInt() + length), read, "en $at, $length bytes")
            }
        }
    }

    @Test
    fun `single pass annex b conversion matches split and join`() {
        val random = Random(21)
        repeat(500) {
            val nals = List(random.nextInt(1, 6)) {
                ByteArray(random.nextInt(1, 300)) { (random.nextInt(1, 256)).toByte() }
            }
            val annexB = buildList {
                for (nal in nals) {
                    if (random.nextBoolean()) add(0.toByte())
                    addAll(listOf(0, 0, 1).map { it.toByte() })
                    addAll(nal.toList())
                    repeat(random.nextInt(0, 3)) { add(0.toByte()) }
                }
            }.toByteArray()
            val expected = NalUnits.joinLengthPrefixed(NalUnits.splitAnnexB(annexB))
            val converted = NalUnits.annexBToLengthPrefixed(annexB)
            assertContentEquals(expected, converted)

            val trailing = converted + ByteArray(random.nextInt(0, 4)) { 9 }
            assertContentEquals(
                NalUnits.joinAnnexB(NalUnits.splitLengthPrefixed(trailing)),
                NalUnits.lengthPrefixedToAnnexB(trailing),
            )
        }
        assertFailsWith<IllegalArgumentException> { NalUnits.annexBToLengthPrefixed(byteArrayOf(1, 2, 3, 4)) }
        assertFailsWith<IllegalArgumentException> { NalUnits.lengthPrefixedToAnnexB(byteArrayOf(0, 0, 0, 9, 1)) }
    }

    @Test
    fun `nested boxes get their exact sizes and a patched field keeps the rest intact`() {
        val builder = BoxBuilder()
        builder.box("moov") {
            fullBox("mvhd", 1, 0x000003) { u32(7) }
            box("trak") {
                box("tkhd") { zeros(5) }
                u64(-1L)
            }
        }
        val bytes = builder.toByteArray()
        fun u32(at: Int) = ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)
        assertEquals(bytes.size.toLong(), u32(0))
        assertEquals(16L, u32(8))
        assertEquals(1, bytes[16].toInt())
        assertEquals(7L, u32(20))
        assertEquals(29L, u32(24))
        assertEquals(13L, u32(32))
        assertEquals(bytes.size, 8 + 16 + 29)
    }

    @Test
    fun `the bisection seek lands on the same sample as a linear scan`() {
        val avcC = NalUnits.buildAvcC(
            listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
            listOf(byteArrayOf(0x68, 0x11, 0x22)),
        )
        val file = File(dir, "seek.mp4")
        Mp4Muxer(file).use { muxer ->
            val video = muxer.addTrack(TrackInfo.Video(codec = VideoCodec.H264, width = 64, height = 64, codecPrivate = avcC))
            val audio = muxer.addTrack(
                TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2, codecPrivate = AacConfig.build(48000, 2)),
            )
            muxer.start()
            val order = intArrayOf(0, 3, 1, 2)
            for (gop in 0 until 40) {
                for (k in 0 until 4) {
                    val frame = gop * 4 + order[k]
                    muxer.writePacket(
                        MediaPacket(video, byteArrayOf(0, 0, 0, 1, 0x41), ptsUs = frame * 40_000L + 80_000, isKeyFrame = k == 0),
                    )
                }
                for (a in 0 until 7) {
                    muxer.writePacket(MediaPacket(audio, ByteArray(8), ptsUs = (gop * 7 + a) * 22_857L))
                }
            }
        }
        val (videoId, all) = MkvKotlin.openDemuxer(file).use { demuxer ->
            demuxer.tracks.first { it is TrackInfo.Video }.id to generateSequence { demuxer.readPacket() }.toList()
        }
        val videoPackets = all.filter { it.trackId == videoId }
        MkvKotlin.openDemuxer(file).use { demuxer ->
            for (target in listOf(-5_000L, 0L, 79_999L, 80_000L, 1_234_567L, 3_000_000L, 6_399_999L, 99_000_000L)) {
                var expected = 0
                for ((index, packet) in videoPackets.withIndex()) {
                    if (packet.ptsUs <= target && packet.isKeyFrame) expected = index
                    if (packet.dtsUs > target) break
                }
                val landed = demuxer.seekTo(target)
                assertEquals(videoPackets[expected].ptsUs, landed, "seek a $target")
                val next = generateSequence { demuxer.readPacket() }.first { it.trackId == videoId }
                assertEquals(videoPackets[expected].ptsUs, next.ptsUs, "primer paquete tras el seek a $target")
            }
        }
    }

    @Test
    fun `timescale conversion never wraps around`() {
        val cases = listOf(
            Triple(Long.MAX_VALUE / 3, 1_000_000L, 90_000L),
            Triple(-(Long.MAX_VALUE / 7), 1_000_000L, 48_000L),
            Triple(123_456_789L, 90_000L, 1_000_000L),
            Triple(-1L, 1_000_000L, 1_000_000_007L),
        )
        for ((value, multiplier, divisor) in cases) {
            val exact = BigInteger.valueOf(value).multiply(BigInteger.valueOf(multiplier))
            val floor = exact.subtract(exact.mod(BigInteger.valueOf(divisor))).divide(BigInteger.valueOf(divisor))
            val expected = floor.max(BigInteger.valueOf(Long.MIN_VALUE)).min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
            assertEquals(expected, Timestamps.rescaleFloor(value, multiplier, divisor), "$value * $multiplier / $divisor")
        }
        assertEquals(Long.MAX_VALUE, Timestamps.rescaleRounded(Long.MAX_VALUE, 1_000_000, 1))
        assertEquals(1L, Timestamps.rescaleRounded(500_000, 1, 1_000_000))
        assertEquals(0L, Timestamps.rescaleRounded(499_999, 1, 1_000_000))
    }

    @Test
    fun `closing an output releases the descriptor even when the last flush fails`() {
        val file = File(dir, "solo-lectura.bin").apply { writeBytes(ByteArray(10)) }
        val readOnly = RandomAccessFile(file, "r")
        val output = SeekableOutput(readOnly)
        output.write(ByteArray(100) { 1 })
        runCatching { output.close() }
        assertFalse(readOnly.channel.isOpen, "el canal quedó abierto tras un flush fallido")
    }
}
