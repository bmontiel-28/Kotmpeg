package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import com.braymon.kotmpeg.mp4.Mp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **El origen de la línea de tiempo cuando las pistas no llegan en orden de captura.**
 *
 * Con dos codificadores, el primer paquete que llega al muxer no tiene por qué ser el más
 * temprano: el de vídeo suele tardar más que el de audio, así que el primer trozo de audio llega
 * antes que el primer fotograma aunque se capturara después. `MkvMuxer` y `FragmentedMp4Muxer`
 * fijaban el origen con el primero que llegaba:
 *
 *  - en **MKV** los fotogramas anteriores quedaban con marcas negativas, y los lectores los dejaban
 *    sin tiempo —incluido el keyframe que abre la grabación—;
 *  - en **fMP4** el vídeo arrancaba en negativo y el audio quedaba adelantado al reproducir.
 *
 * `Mp4Muxer` ya lo hacía bien, porque lo tiene todo al cerrar y toma el menor PTS de todas las
 * pistas, y aquí sirve de referencia. Las marcas son de reloj absoluto, como las de `MediaCodec`.
 */
class TrackStartOrderTest {

    @TempDir
    lateinit var dir: File

    private companion object {
        /** Tres días de reloj de arranque del dispositivo. */
        const val UPTIME_US = 3L * 24 * 3600 * 1_000_000

        /** El micrófono empieza a capturar 64 ms después que la pantalla... */
        const val MIC_START_US = 64_000L

        /** ...pero su codificador entrega con 20 ms de latencia y el de vídeo con 150 ms. */
        const val AUDIO_LATENCY_US = 20_000L
        const val VIDEO_LATENCY_US = 150_000L

        const val FRAMES = 30
        const val AUDIO_PACKETS = 45
    }

    private val avcC = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    private fun video() = TrackInfo.Video(
        codec = VideoCodec.H264, width = 320, height = 240, frameRate = 30.0, codecPrivate = avcC,
    )

    private fun audio() = TrackInfo.Audio(
        codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2, codecPrivate = AacConfig.build(48000, 2),
    )

    private class Arrival(val atUs: Long, val packet: MediaPacket)

    /** Un segundo de captura en el orden en que llega al muxer, no en el que se capturó. */
    private fun arrivals(videoId: Int, audioId: Int): List<MediaPacket> {
        val list = ArrayList<Arrival>()
        for (f in 0 until FRAMES) {
            val pts = UPTIME_US + f * 33_333L
            val nal = byteArrayOf(if (f == 0) 0x65 else 0x41, f.toByte())
            list += Arrival(pts + VIDEO_LATENCY_US, MediaPacket(videoId, NalUnits.joinLengthPrefixed(listOf(nal)), pts, isKeyFrame = f == 0))
        }
        for (a in 0 until AUDIO_PACKETS) {
            val pts = UPTIME_US + MIC_START_US + a * 21_333L
            list += Arrival(pts + AUDIO_LATENCY_US, MediaPacket(audioId, ByteArray(16) { a.toByte() }, pts))
        }
        return list.sortedBy { it.atUs }.map { it.packet }
    }

    private fun record(muxer: Muxer): Pair<Int, Int> {
        val videoId = muxer.addTrack(video())
        val audioId = muxer.addTrack(audio())
        muxer.start()
        val packets = arrivals(videoId, audioId)
        check(packets.first().trackId == audioId) { "el escenario tiene que empezar por el audio" }
        for (packet in packets) muxer.writePacket(packet)
        muxer.stop()
        return videoId to audioId
    }

    private fun readAll(file: File): List<MediaPacket> = MkvKotlin.openDemuxer(file).use { demuxer ->
        generateSequence { demuxer.readPacket() }.toList()
    }

    private fun containers(): List<Pair<File, (File) -> Muxer>> = listOf(
        File(dir, "plano.mp4") to { f: File -> Mp4Muxer(f) },
        File(dir, "fragmentado.mp4") to { f: File -> FragmentedMp4Muxer(f) },
        File(dir, "grabacion.mkv") to { f: File -> MkvMuxer(f) },
    )

    @Test
    fun `every container keeps the capture offset when the earliest track reaches the muxer second`() {
        for ((file, create) in containers()) {
            val (videoId, audioId) = record(create(file))
            val packets = readAll(file)
            val videoPts = packets.filter { it.trackId == videoId }.map { it.ptsUs }
            val audioPts = packets.filter { it.trackId == audioId }.map { it.ptsUs }

            assertEquals(FRAMES, videoPts.size, "${file.name}: fotogramas perdidos")
            assertEquals(AUDIO_PACKETS, audioPts.size, "${file.name}: audio perdido")
            assertTrue(packets.all { it.ptsUs >= 0 }, "${file.name}: hay marcas negativas: ${packets.minOf { it.ptsUs }} µs")
            assertEquals(0L, videoPts.min(), "${file.name}: el vídeo, que se capturó primero, no abre la línea de tiempo")
            assertEquals(MIC_START_US, audioPts.min(), "${file.name}: el audio no conserva su desfase respecto al vídeo")
        }
    }

    /**
     * Mientras se espera a la pista que falta, los paquetes se retienen más allá de `writePacket`,
     * así que tienen que copiarse: el contrato de `MediaPacket` permite reutilizar el array.
     */
    @Test
    fun `packets held while waiting for a track survive the caller reusing its buffer`() {
        for ((file, create) in containers().drop(1)) {
            val muxer = create(file)
            val videoId = muxer.addTrack(video())
            val audioId = muxer.addTrack(audio())
            muxer.start()
            val shared = ByteArray(16)
            for (a in 0 until 5) {
                shared.fill(a.toByte())
                muxer.writePacket(MediaPacket(audioId, shared, UPTIME_US + MIC_START_US + a * 21_333L))
            }
            val key = NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x65, 0)))
            muxer.writePacket(MediaPacket(videoId, key, UPTIME_US, isKeyFrame = true))
            muxer.stop()

            val audioData = readAll(file).filter { it.trackId == audioId }.map { it.data }
            assertEquals(5, audioData.size, "${file.name}: audio perdido")
            for ((a, data) in audioData.withIndex()) {
                assertContentEquals(ByteArray(16) { a.toByte() }, data, "${file.name}: el paquete $a se pisó")
            }
        }
    }

    /** Una grabación que termina sin que una pista declarada haya llegado a producir nada. */
    @Test
    fun `stop writes what was held when a declared track never delivered`() {
        for ((file, create) in containers().drop(1)) {
            val muxer = create(file)
            muxer.addTrack(video())
            val audioId = muxer.addTrack(audio())
            muxer.start()
            for (a in 0 until 10) muxer.writePacket(MediaPacket(audioId, ByteArray(16), UPTIME_US + a * 21_333L))
            muxer.stop()

            val packets = readAll(file)
            assertEquals(10, packets.size, "${file.name}: lo retenido no llegó al archivo")
            assertEquals(0L, packets.minOf { it.ptsUs }, "${file.name}: la línea de tiempo no empieza en cero")
        }
    }

    /**
     * La espera tiene dos topes, porque una pista declarada que no produce nada no puede hacer
     * crecer la memoria sin límite: la distancia de tiempo retenida y los bytes retenidos.
     */
    @Test
    fun `the wait for a missing track is bounded by time and by memory`() {
        val byTime = TimelineGate(trackCount = 2, maxWaitUs = 1_000_000, maxHeldBytes = Long.MAX_VALUE)
        assertFalse(byTime.hold(MediaPacket(2, ByteArray(10), 0), trackIndex = 1))
        assertFalse(byTime.hold(MediaPacket(2, ByteArray(10), 999_999), trackIndex = 1))
        assertTrue(byTime.hold(MediaPacket(2, ByteArray(10), 1_000_000), trackIndex = 1), "no se agotó la espera")

        val byMemory = TimelineGate(trackCount = 2, maxWaitUs = Long.MAX_VALUE, maxHeldBytes = 100)
        assertFalse(byMemory.hold(MediaPacket(2, ByteArray(60), 0), trackIndex = 1))
        assertTrue(byMemory.hold(MediaPacket(2, ByteArray(60), 1), trackIndex = 1), "no se respetó el tope de memoria")
    }

    /**
     * Lo retenido se entrega intercalado por tiempo, pero **sin reordenar dentro de una pista**:
     * con B-frames el orden de llegada de una pista es el de decodificación.
     */
    @Test
    fun `release interleaves tracks by time without reordering inside a track`() {
        val gate = TimelineGate(trackCount = 2, maxWaitUs = Long.MAX_VALUE, maxHeldBytes = Long.MAX_VALUE)
        gate.hold(MediaPacket(2, ByteArray(1), 64_000), trackIndex = 1)
        gate.hold(MediaPacket(2, ByteArray(1), 85_000), trackIndex = 1)
        gate.hold(MediaPacket(1, ByteArray(1), 0, isKeyFrame = true), trackIndex = 0)
        gate.hold(MediaPacket(1, ByteArray(1), 100_000), trackIndex = 0)
        gate.hold(MediaPacket(1, ByteArray(1), 33_000), trackIndex = 0)

        assertEquals(0L, gate.earliestPtsUs)
        val order = gate.release().map { it.trackId to it.ptsUs }
        assertEquals(listOf(1 to 0L, 2 to 64_000L, 2 to 85_000L, 1 to 100_000L, 1 to 33_000L), order)
    }
}
