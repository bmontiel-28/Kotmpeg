package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `MkvDemuxer.seekTo` con clusters que no empiezan en keyframe. Hasta la 3.x devolvía el tiempo
 * del cluster y leía desde su principio: sin índice, buscar el segundo 1,5 de un vídeo con un
 * keyframe cada 1,2 s caía en un cluster de 1,4 s que empieza con un fotograma que no se puede
 * decodificar. Ahora cae en el keyframe de 1,2 s, como `Mp4Demuxer`, y el audio empieza en el
 * paquete que suena en ese instante, aunque el muxer lo dejara en el cluster anterior o lo
 * escribiera detrás del keyframe, como OBS.
 */
class MkvSeekTest {

    @TempDir
    lateinit var dir: File

    private val video = TrackInfo.Video(VideoCodec.H264, 64, 48) {
        frameRate = 25.0
        codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22)))
    }
    private val audio = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) }

    /**
     * Cuatro segundos a 25 fps con un keyframe cada 30 fotogramas, audio intercalado y clusters de
     * 200 ms. Con [audioAfterVideo], el audio de cada intervalo va detrás de su fotograma, y el
     * paquete que suena en un keyframe queda escrito después de él, como en las grabaciones de OBS.
     */
    private fun write(file: File, audioAfterVideo: Boolean = false) {
        MkvMuxer(file) { maxClusterDurationMs = 200 }.use { muxer ->
            val v = muxer.addTrack(video)
            val a = muxer.addTrack(audio)
            muxer.start()
            var audioUs = 0L
            for (i in 0 until 100) {
                val pts = i * 40_000L
                val frame = MediaPacket(v, NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x41, i.toByte()))), pts, isKeyFrame = i % 30 == 0)
                if (audioAfterVideo) muxer.writePacket(frame)
                while (audioUs <= pts) {
                    muxer.writePacket(MediaPacket(a, ByteArray(8), audioUs, isKeyFrame = true))
                    audioUs += 21_333
                }
                if (!audioAfterVideo) muxer.writePacket(frame)
            }
        }
    }

    /** El mismo archivo con el índice `Cues` inutilizado ([withoutCuesIndex]), para forzar la búsqueda recorriendo clusters. */
    private fun withoutIndex(file: File): File =
        File(dir, "sin-indice.mkv").also { it.writeBytes(withoutCuesIndex(file.readBytes())) }

    private fun assertLandsOnKeyFrame(file: File, target: Long, expected: Long) {
        Kotmpeg.openDemuxer(file).use { demuxer ->
            val videoId = demuxer.tracks.first { it is TrackInfo.Video }.id
            assertEquals(expected, demuxer.seekTo(target), "${file.name}: seekTo($target)")
            val after = generateSequence { demuxer.readPacket() }.take(20).toList()
            val firstVideo = after.first { it.trackId == videoId }
            assertTrue(firstVideo.isKeyFrame, "${file.name}: el primer fotograma tras buscar no es un keyframe")
            assertEquals(expected, firstVideo.ptsUs)
            val firstAudio = after.first { it.trackId != videoId }
            assertTrue(
                firstAudio.ptsUs <= expected && expected - firstAudio.ptsUs < 22_000,
                "${file.name}: el audio empieza en ${firstAudio.ptsUs} µs, no en el paquete que suena en $expected",
            )
        }
    }

    /**
     * Una segunda pista de vídeo con sus keyframes en otros instantes (0,4 s, 1,6 s, 2,8 s). Tras
     * buscar el 1,5, lo primero que sale de ella tiene que ser un keyframe: su último anterior, el
     * de 0,4 s, como en MP4, si cae en lo que se lee; y si los clusters son tan cortos que queda
     * fuera, el siguiente, porque lo que hay entre medias no se puede decodificar.
     */
    @Test
    fun `a second video track also starts on a keyframe`() {
        assertEquals(400_000L, firstCameraPacketAfterSeek(maxClusterMs = 5_000))
        assertEquals(1_600_000L, firstCameraPacketAfterSeek(maxClusterMs = 200))
    }

    private fun firstCameraPacketAfterSeek(maxClusterMs: Long): Long {
        val file = File(dir, "dos-videos-$maxClusterMs.mkv")
        MkvMuxer(file) { maxClusterDurationMs = maxClusterMs }.use { muxer ->
            val main = muxer.addTrack(video)
            val camera = muxer.addTrack(video.copy { name = "Cámara" })
            muxer.start()
            for (i in 0 until 100) {
                val pts = i * 40_000L
                val frame = NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x41, i.toByte())))
                muxer.writePacket(MediaPacket(main, frame, pts, isKeyFrame = i % 30 == 0))
                muxer.writePacket(MediaPacket(camera, frame, pts, isKeyFrame = i % 30 == 10))
            }
        }
        return Kotmpeg.openDemuxer(file).use { demuxer ->
            val (main, camera) = demuxer.tracks.map { it.id }
            assertEquals(1_200_000L, demuxer.seekTo(1_500_000))
            val after = generateSequence { demuxer.readPacket() }.toList()
            assertTrue(after.first { it.trackId == main }.let { it.isKeyFrame && it.ptsUs == 1_200_000L })
            val firstCamera = after.first { it.trackId == camera }
            assertTrue(firstCamera.isKeyFrame, "la segunda pista empieza en un fotograma que no es keyframe (${firstCamera.ptsUs} µs)")
            firstCamera.ptsUs
        }
    }

    @Test
    fun `seeking lands on the keyframe with and without the index`() {
        val indexed = File(dir, "indice.mkv").also { write(it) }
        val plain = withoutIndex(indexed)
        for (file in listOf(indexed, plain)) {
            assertLandsOnKeyFrame(file, 1_500_000, 1_200_000)
            assertLandsOnKeyFrame(file, 3_900_000, 3_600_000)
            assertLandsOnKeyFrame(file, 0, 0)
        }
    }

    /**
     * Buscar antes del primer keyframe —o antes del primer cue— es una búsqueda más: cae en ese
     * keyframe, y el audio empieza en el paquete que suena en él aunque esté en el cluster
     * anterior. Se tomaba el primer cue tal cual y se leía desde su cluster: el audio empezaba un
     * paquete después.
     */
    @Test
    fun `seeking before the first keyframe starts the other tracks like any other seek`() {
        val file = File(dir, "primer-keyframe.mkv")
        MkvMuxer(file) { maxClusterDurationMs = 300 }.use { muxer ->
            val v = muxer.addTrack(video)
            val a = muxer.addTrack(audio)
            muxer.start()
            var audioUs = 0L
            for (i in 0 until 60) {
                val pts = 33_333L + i * 33_333L
                while (audioUs <= pts) {
                    muxer.writePacket(MediaPacket(a, ByteArray(8), audioUs, isKeyFrame = true))
                    audioUs += 21_333
                }
                muxer.writePacket(MediaPacket(v, NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x41, i.toByte()))), pts, isKeyFrame = i % 30 == 0))
            }
        }
        for (target in listOf(-1_000_000L, 0L, 20_000L)) assertLandsOnKeyFrame(file, target, 33_000)
    }

    /**
     * Un MKV con tiempos negativos —el muxer los conserva—: su primer cue lleva tiempo 0, porque
     * `CueTime` no tiene signo, aunque el keyframe esté en −100 ms. Buscar antes del principio
     * devolvía ese 0 y entregaba desde el keyframe siguiente, un segundo más tarde.
     */
    @Test
    fun `seeking before the start of an mkv with negative times lands on its first keyframe`() {
        val file = File(dir, "negativo.mkv")
        MkvMuxer(file).use { muxer ->
            val v = muxer.addTrack(video)
            muxer.start()
            for (i in 0 until 75) {
                val pts = -100_000L + i * 40_000L
                muxer.writePacket(MediaPacket(v, NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x41, i.toByte()))), pts, isKeyFrame = i % 25 == 0))
            }
        }
        Kotmpeg.openDemuxer(file).use { demuxer ->
            for (target in listOf(-1_000_000L, -100_000L, 0L)) {
                assertEquals(-100_000L, demuxer.seekTo(target), "seekTo($target)")
                val first = demuxer.readPacket()!!
                assertTrue(first.isKeyFrame && first.ptsUs == -100_000L, "tras seekTo($target) sale ${first.ptsUs} µs")
            }
        }
    }

    /**
     * El paquete de audio que suena en el keyframe está escrito detrás de él. Mirando solo lo
     * anterior al keyframe, el audio empezaría dos paquetes antes, 48 ms; hay que seguir leyendo
     * hasta pasar el keyframe en el audio para saber cuál suena.
     */
    @Test
    fun `audio written after the keyframe still starts at the packet sounding there`() {
        val file = File(dir, "audio-detras.mkv").also { write(it, audioAfterVideo = true) }
        assertLandsOnKeyFrame(file, 1_500_000, 1_200_000)
        assertLandsOnKeyFrame(file, 3_900_000, 3_600_000)
        assertLandsOnKeyFrame(withoutIndex(file), 1_500_000, 1_200_000)
    }

    /**
     * Diez segundos a 25 fps, keyframe cada segundo, con el audio escrito 3 s por detrás de su
     * vídeo, como lo intercala un muxer por tiempo de decodificación cuando el DTS del vídeo va
     * segundos por detrás del PTS. Los fotogramas de [burst] van seguidos, sin audio entre ellos,
     * como los que vacía de golpe un codificador.
     */
    private fun writeLagging(file: File, burst: IntRange = IntRange.EMPTY) {
        val lagUs = 3_000_000L
        MkvMuxer(file) { maxClusterDurationMs = 500 }.use { muxer ->
            val v = muxer.addTrack(video)
            val a = muxer.addTrack(audio)
            muxer.start()
            var audioUs = 0L
            for (i in 0 until 250) {
                val pts = i * 40_000L
                muxer.writePacket(MediaPacket(v, NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x41, i.toByte()))), pts, isKeyFrame = i % 25 == 0))
                if (i in burst) continue
                while (audioUs + lagUs <= pts) {
                    muxer.writePacket(MediaPacket(a, ByteArray(8), audioUs, isKeyFrame = true))
                    audioUs += 21_333
                }
            }
        }
    }

    /**
     * Un vídeo con B-frames cuyo DTS va segundos por detrás del PTS —hay grabaciones de Android con
     * 6,7 s— se escribe intercalado por tiempo de decodificación, así que el audio que suena en un
     * keyframe queda segundos más adelante en el archivo. La búsqueda dejaba de esperarlo en cuanto
     * lo leído pasaba un segundo del keyframe, y el audio empezaba esos segundos antes que la imagen.
     * Tampoco vale dejar de esperarlo tras un segundo de vídeo sin audio: al final de esas
     * grabaciones el codificador vacía fotogramas seguidos, y ese tramo no es un hueco del audio.
     */
    @Test
    fun `audio written seconds after its keyframe still starts at the packet sounding there`() {
        for (burst in listOf(IntRange.EMPTY, 126 until 166)) {
            val file = File(dir, "audio-retrasado-${burst.count()}.mkv").also { writeLagging(it, burst) }
            val audioPts = Kotmpeg.openDemuxer(file).use { d ->
                val audioId = d.tracks.first { it is TrackInfo.Audio }.id
                generateSequence { d.readPacket() }.filter { it.trackId == audioId }.map { it.ptsUs }.toList()
            }
            Kotmpeg.openDemuxer(file).use { d ->
                val audioId = d.tracks.first { it is TrackInfo.Audio }.id
                for ((target, keyFrame) in listOf(5_500_000L to 5_000_000L, 6_000_000L to 6_000_000L)) {
                    assertEquals(keyFrame, d.seekTo(target), "${file.name}: seekTo($target)")
                    val first = generateSequence { d.readPacket() }.first { it.trackId == audioId }.ptsUs
                    assertEquals(
                        audioPts.last { it <= keyFrame }, first,
                        "${file.name}: tras seekTo($target), el audio no empieza en el paquete que suena en el keyframe",
                    )
                }
            }
        }
    }

    /**
     * Un archivo dañado cuyos tiempos dejan de avanzar tras un keyframe, con el audio ya terminado:
     * la búsqueda esperaba a que lo leído avanzara para darlo por terminado, y guardaba en memoria
     * todo lo que quedaba del archivo antes de entregar el primer paquete. Ahora lo guardado tiene
     * un tope, y se deja de esperar al alcanzarlo.
     */
    @Test
    fun `a seek in an mkv whose times stop advancing does not hold the rest of the file`() {
        val file = File(dir, "tiempos-parados.mkv")
        val payload = NalUnits.joinLengthPrefixed(listOf(ByteArray(64 * 1024) { 0x41 }))
        MkvMuxer(file) { maxClusterDurationMs = 500 }.use { muxer ->
            val v = muxer.addTrack(video)
            val a = muxer.addTrack(audio)
            muxer.start()
            var audioUs = 0L
            for (i in 0 until 50) {
                val pts = i * 40_000L
                while (audioUs <= pts) {
                    muxer.writePacket(MediaPacket(a, ByteArray(8), audioUs, isKeyFrame = true))
                    audioUs += 21_333
                }
                muxer.writePacket(MediaPacket(v, NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x41, i.toByte()))), pts, isKeyFrame = i % 25 == 0))
            }
            for (k in 0 until 1_600) muxer.writePacket(MediaPacket(v, payload, 2_000_000L, isKeyFrame = k == 0))
        }
        SeekableInput(file).use { input ->
            Kotmpeg.openDemuxer(input).use { d ->
                assertEquals(2_000_000L, d.seekTo(2_500_000))
                assertEquals(2_000_000L, d.readPacket()?.ptsUs)
                assertTrue(
                    input.position < file.length() * 3 / 4,
                    "para entregar el primer paquete tras buscar se leyeron ${input.position} de ${file.length()} bytes",
                )
            }
        }
    }
}
