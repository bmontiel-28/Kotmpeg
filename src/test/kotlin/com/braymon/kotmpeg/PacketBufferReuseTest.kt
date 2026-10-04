package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Una app puede reutilizar el mismo `ByteArray` para todos sus paquetes.
 *
 * Es lo habitual al copiar la salida de `MediaCodec` sin reservar memoria por fotograma, y
 * `FragmentedMp4Muxer` no lo soportaba: retenía una referencia al array hasta cerrar el fragmento,
 * así que todas las muestras acababan con el contenido del último paquete. En la prueba original,
 * 49 de 50 paquetes salieron dañados sin ningún error. Los otros dos muxers escriben en el momento
 * y nunca tuvieron el problema; se comprueban igual para que nadie lo introduzca.
 *
 * Van aquí también los límites de retención del fMP4, que son los que deciden cuánta memoria cuesta
 * esa copia y que ahora se pueden fijar desde la fachada.
 */
class PacketBufferReuseTest {

    @TempDir
    lateinit var dir: File

    private val avcC = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    private fun writeWithOneBuffer(muxer: Muxer) {
        val buffer = ByteArray(32)
        muxer.use { m ->
            val video = m.addTrack(TrackInfo.Video(codec = VideoCodec.H264, width = 64, height = 64, codecPrivate = avcC))
            val audio = m.addTrack(
                TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48_000, channelCount = 2, codecPrivate = AacConfig.build(48_000, 2)),
            )
            m.start()
            for (i in 0 until 60) {
                buffer.fill((i * 2).toByte())
                m.writePacket(MediaPacket(video, buffer, ptsUs = i * 33_333L, isKeyFrame = i % 15 == 0))
                buffer.fill((i * 2 + 1).toByte())
                m.writePacket(MediaPacket(audio, buffer, ptsUs = i * 33_333L))
            }
            buffer.fill(-1)
        }
    }

    @Test
    fun `reusing one buffer for every packet keeps every container intact`() {
        val outputs = listOf(
            File(dir, "reuso.mkv").also { writeWithOneBuffer(MkvKotlin.createMuxer(it)) },
            File(dir, "reuso.mp4").also { writeWithOneBuffer(MkvKotlin.createMuxer(it)) },
            File(dir, "reuso-frag.mp4").also { writeWithOneBuffer(MkvKotlin.createMuxer(it, mp4Fragmented = true)) },
            File(dir, "reuso-frag-limites.mp4").also {
                writeWithOneBuffer(MkvKotlin.createFragmentedMp4Muxer(it, maxFragmentBytes = 256))
            },
        )
        for (file in outputs) {
            MkvKotlin.openDemuxer(file).use { demuxer ->
                val videoId = demuxer.tracks.first { it is TrackInfo.Video }.id
                val packets = generateSequence { demuxer.readPacket() }.toList()
                assertEquals(120, packets.size, "${file.name}: paquetes perdidos")
                for (packet in packets) {
                    val index = Math.round(packet.ptsUs / 33_333.0).toInt()
                    val expected = (index * 2 + if (packet.trackId == videoId) 0 else 1).toByte()
                    assertTrue(packet.data.all { it == expected }, "${file.name}: paquete en ${packet.ptsUs} µs dañado")
                }
            }
        }
    }

    /** Cuántas cajas `moof` hay en el nivel superior del archivo. */
    private fun countFragments(file: File): Int {
        val bytes = file.readBytes()
        var position = 0
        var fragments = 0
        while (position + 8 <= bytes.size) {
            var size = (0 until 4).fold(0L) { acc, k -> (acc shl 8) or (bytes[position + k].toLong() and 0xFF) }
            if (size == 1L) size = (0 until 8).fold(0L) { acc, k -> (acc shl 8) or (bytes[position + 8 + k].toLong() and 0xFF) }
            if (String(bytes, position + 4, 4, Charsets.US_ASCII) == "moof") fragments++
            if (size < 8) break
            position += size.toInt()
        }
        return fragments
    }

    @Test
    fun `the facade exposes the fragment retention limits`() {
        val roomy = File(dir, "holgado.mp4").also { writeWithOneBuffer(MkvKotlin.createFragmentedMp4Muxer(it)) }
        val tight = File(dir, "ajustado.mp4").also { writeWithOneBuffer(MkvKotlin.createFragmentedMp4Muxer(it, maxFragmentBytes = 256)) }
        assertEquals(4, countFragments(roomy), "con los valores por defecto se corta un fragmento por GOP")
        assertTrue(countFragments(tight) > 4, "un límite de 256 bytes tiene que cortar más fragmentos que los GOP")
    }

    @Test
    fun `invalid fragment limits are rejected before the file is created`() {
        val target = File(dir, "nunca.mp4")
        assertFailsWith<IllegalArgumentException> { MkvKotlin.createFragmentedMp4Muxer(target, maxFragmentBytes = 0) }
        assertFailsWith<IllegalArgumentException> { MkvKotlin.createFragmentedMp4Muxer(target, maxFragmentDurationUs = -1) }
        assertFalse(target.exists(), "se creó el archivo aunque los parámetros eran inválidos")
    }
}
