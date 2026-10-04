package com.braymon.kotmpeg

import com.braymon.kotmpeg.audio.PcmMixer
import com.braymon.kotmpeg.audio.PcmResampler
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
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Regla inmutable del proyecto: el audio del sistema y el del micrófono se pueden grabar
 * combinados en una pista, cada uno en la suya, o cualquiera de los dos por separado.**
 *
 * Es lo que distingue a esta librería para móviles, y por eso tiene su propio test de política,
 * como `PublicApiTest` o `CommentPolicyTest`: si un cambio rompe uno solo de estos casos, el
 * cambio está mal, no el test. Cubre las dos mitades de la regla:
 *
 *  - **La mezcla**: micrófono y sistema llegan con frecuencias y canales distintos (lo normal en
 *    Android es micrófono mono a 44,1 kHz y sistema estéreo a 48 kHz). `PcmResampler` los lleva
 *    a la misma tasa, `PcmMixer.convertChannels` al mismo número de canales y `PcmMixer.mix` los
 *    suma con saturación. Se comprueba muestra a muestra.
 *  - **La disposición de pistas**: los cinco montajes posibles, con vídeo de pantalla, escritos
 *    en los tres contenedores y releídos —y también tras pasar de MKV a MP4 y de vuelta—, con
 *    cada pista entera, su nombre y exactamente una pista de audio predeterminada.
 */
class RecordingAudioLayoutsTest {

    @TempDir
    lateinit var dir: File

    private enum class Layout(val audioTracks: List<String>) {
        MIXED(listOf("Mezcla")),
        MICROPHONE_ONLY(listOf("Micrófono")),
        SYSTEM_ONLY(listOf("Audio del sistema")),
        SEPARATE(listOf("Micrófono", "Audio del sistema")),
        MIXED_AND_SEPARATE(listOf("Mezcla", "Micrófono", "Audio del sistema")),
    }

    private val avcC = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    private fun sine(frames: Int, rate: Int, channels: Int, hz: Double, amplitude: Double): ShortArray =
        ShortArray(frames * channels) { i -> (sin(2 * PI * hz * (i / channels) / rate) * amplitude).toInt().toShort() }

    @Test
    fun `microphone and system audio at different rates and channels mix into one track`() {
        val systemRate = 48_000
        val microphoneRate = 44_100
        val toSystemRate = PcmResampler(microphoneRate, systemRate, channels = 1)
        val mixedStream = ArrayList<ShortArray>()
        var systemFramesTotal = 0
        var microphoneFramesTotal = 0
        repeat(100) { block ->
            val system = sine(480, systemRate, channels = 2, hz = 440.0, amplitude = 12_000.0)
            val microphone = sine(441, microphoneRate, channels = 1, hz = 1_000.0, amplitude = 9_000.0)
            val microphoneAtSystemRate = toSystemRate.resample(microphone)
            val microphoneStereo = PcmMixer.convertChannels(microphoneAtSystemRate, from = 1, to = 2)
            val mixed = PcmMixer.mix(listOf(system, microphoneStereo))

            assertEquals(system.size, mixed.size, "bloque $block: la mezcla no conserva la duración del sistema")
            for (i in mixed.indices) {
                val micSample = if (i < microphoneStereo.size) microphoneStereo[i].toInt() else 0
                val expected = (system[i] + micSample).coerceIn(-32768, 32767)
                assertEquals(expected, mixed[i].toInt(), "bloque $block, muestra $i")
            }
            systemFramesTotal += system.size / 2
            microphoneFramesTotal += microphoneStereo.size / 2
            mixedStream += mixed
        }
        microphoneFramesTotal += toSystemRate.flush().size
        assertEquals(systemFramesTotal, microphoneFramesTotal, "el micrófono remuestreado no dura lo mismo que el sistema")
        assertTrue(mixedStream.sumOf { it.size } == systemFramesTotal * 2)
    }

    @Test
    fun `a loud mix saturates instead of wrapping around`() {
        val system = shortArrayOf(30_000, -30_000, 20_000, -20_000)
        val microphone = shortArrayOf(10_000, -10_000, 20_000, -20_000)
        assertContentEquals(shortArrayOf(32_767, -32_768, 32_767, -32_768), PcmMixer.mix(listOf(system, microphone)))
        val withGains = PcmMixer.mix(listOf(system, microphone), gains = listOf(0.5f, 0.5f))
        assertContentEquals(shortArrayOf(20_000, -20_000, 20_000, -20_000), withGains)
    }

    /** Escribe el montaje con vídeo de pantalla y devuelve los paquetes escritos por nombre de pista. */
    private fun record(file: File, format: ContainerFormat, fragmented: Boolean, layout: Layout): Map<String, List<ByteArray>> {
        val written = LinkedHashMap<String, MutableList<ByteArray>>()
        MkvKotlin.createMuxer(file, format, mp4Fragmented = fragmented).use { muxer ->
            val video = muxer.addTrack(
                TrackInfo.Video(codec = VideoCodec.H264, width = 1080, height = 2400, frameRate = 30.0, codecPrivate = avcC, name = "Pantalla"),
            )
            val audioIds = layout.audioTracks.mapIndexed { index, name ->
                name to muxer.addTrack(
                    TrackInfo.Audio(
                        codec = AudioCodec.AAC, sampleRate = 48_000, channelCount = 2,
                        codecPrivate = AacConfig.build(48_000, 2), name = name, default = index == 0,
                    ),
                )
            }
            muxer.start()
            written["Pantalla"] = ArrayList()
            for ((name, _) in audioIds) written[name] = ArrayList()
            var audioIndex = 0
            for (frame in 0 until 90) {
                val ptsUs = frame * 33_333L
                val payload = NalUnits.joinLengthPrefixed(listOf(ByteArray(40) { (frame + it).toByte() }))
                muxer.writePacket(MediaPacket(video, payload, ptsUs = ptsUs, isKeyFrame = frame % 30 == 0))
                written.getValue("Pantalla") += payload
                while (audioIndex * 21_333L <= ptsUs) {
                    for ((trackNumber, entry) in audioIds.withIndex()) {
                        val (name, id) = entry
                        val aac = ByteArray(24) { (audioIndex * 7 + trackNumber * 31 + it).toByte() }
                        muxer.writePacket(MediaPacket(id, aac, ptsUs = audioIndex * 21_333L))
                        written.getValue(name) += aac
                    }
                    audioIndex++
                }
            }
        }
        return written
    }

    private fun assertLayout(file: File, layout: Layout, expected: Map<String, List<ByteArray>>) {
        MkvKotlin.openDemuxer(file).use { demuxer ->
            val audio = demuxer.tracks.filterIsInstance<TrackInfo.Audio>()
            assertEquals(layout.audioTracks, audio.map { it.name }, "${file.name}: pistas de audio")
            assertEquals(1, audio.count { it.default }, "${file.name}: tiene que haber exactamente una pista de audio predeterminada")
            assertEquals(layout.audioTracks.first(), audio.first { it.default }.name, "${file.name}: predeterminada equivocada")
            val nameById = demuxer.tracks.associate { it.id to it.name }
            val read = LinkedHashMap<String, MutableList<ByteArray>>()
            while (true) {
                val packet = demuxer.readPacket() ?: break
                read.getOrPut(nameById.getValue(packet.trackId)!!) { ArrayList() } += packet.data
            }
            for ((name, packets) in expected) {
                val got = read[name].orEmpty()
                assertEquals(packets.size, got.size, "${file.name}: $name perdió paquetes")
                for ((k, data) in packets.withIndex()) assertContentEquals(data, got[k], "${file.name}: $name, paquete $k")
            }
        }
    }

    @Test
    fun `every audio layout survives every container`() {
        for (layout in Layout.entries) {
            for ((suffix, format, fragmented) in listOf(
                Triple("mkv", ContainerFormat.MKV, false),
                Triple("mp4", ContainerFormat.MP4, false),
                Triple("frag.mp4", ContainerFormat.MP4, true),
            )) {
                val file = File(dir, "${layout.name.lowercase()}.$suffix")
                val written = record(file, format, fragmented, layout)
                assertLayout(file, layout, written)
            }
        }
    }

    @Test
    fun `every audio layout survives a round trip between containers`() {
        for (layout in Layout.entries) {
            val source = File(dir, "${layout.name.lowercase()}-origen.mkv")
            val written = record(source, ContainerFormat.MKV, fragmented = false, layout)
            val asMp4 = File(dir, "${layout.name.lowercase()}-medio.mp4")
            val back = File(dir, "${layout.name.lowercase()}-vuelta.mkv")
            MkvKotlin.remux(source, asMp4)
            MkvKotlin.remux(asMp4, back)
            assertLayout(asMp4, layout, written)
            assertLayout(back, layout, written)
        }
    }
}
