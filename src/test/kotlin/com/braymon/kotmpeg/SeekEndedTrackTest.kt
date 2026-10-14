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
import kotlin.test.assertTrue

/**
 * `seekTo` con pistas que terminan antes que el vídeo principal: un micrófono y una cámara que se
 * cortan a los 2 s de una grabación de 8. Buscar después de ese corte no debe entregar nada de
 * ellas. Hasta ahora MP4 colocaba cada una en su último paquete o su último keyframe, y lo
 * primero que salía tras buscar el segundo 5 era sonido e imagen de tres segundos antes; MKV hacía
 * lo mismo cuando ese final caía en lo que lee al buscar.
 */
class SeekEndedTrackTest {

    @TempDir
    lateinit var dir: File

    private val video = TrackInfo.Video(VideoCodec.H264, 64, 48) {
        frameRate = 25.0
        codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22)))
    }

    /**
     * Ocho segundos de vídeo a 25 fps con un keyframe cada 1,2 s; una cámara con keyframes en 0,4 y
     * 1,6 s y AAC, las dos hasta el segundo 2.
     */
    private fun write(file: File, fragmented: Boolean = false) {
        Kotmpeg.createMuxer(file, ContainerFormat.fromFileName(file.name)!!) { this.fragmented = fragmented }.use { muxer ->
            val main = muxer.addTrack(video)
            val camera = muxer.addTrack(video.copy { name = "Cámara" })
            val audio = muxer.addTrack(TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) })
            muxer.start()
            var audioUs = 0L
            for (i in 0 until 200) {
                val pts = i * 40_000L
                val frame = NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x41, i.toByte())))
                while (audioUs <= pts && audioUs < 2_000_000) {
                    muxer.writePacket(MediaPacket(audio, ByteArray(8), audioUs, isKeyFrame = true))
                    audioUs += 21_333
                }
                muxer.writePacket(MediaPacket(main, frame, pts, isKeyFrame = i % 30 == 0))
                if (pts < 2_000_000) muxer.writePacket(MediaPacket(camera, frame, pts, isKeyFrame = i % 30 == 10))
            }
        }
    }

    @Test
    fun `a track that already ended delivers nothing after seeking past its end`() {
        val files = listOf(
            File(dir, "plano.mp4").also { write(it) },
            File(dir, "fragmentado.mp4").also { write(it, fragmented = true) },
            File(dir, "matroska.mkv").also { write(it) },
        )
        for (file in files) {
            Kotmpeg.openDemuxer(file).use { demuxer ->
                val (main, camera, audio) = demuxer.tracks.map { it.id }

                assertEquals(1_200_000L, demuxer.seekTo(1_500_000), file.name)
                val early = generateSequence { demuxer.readPacket() }.toList()
                val firstAudio = early.first { it.trackId == audio }
                assertTrue(firstAudio.ptsUs <= 1_200_000 && 1_200_000 - firstAudio.ptsUs < 22_000, "${file.name}: el audio empieza en ${firstAudio.ptsUs}")
                assertTrue(early.first { it.trackId == camera }.isKeyFrame, "${file.name}: la cámara no empieza en un keyframe")

                for ((target, landed) in listOf(5_000_000L to 4_800_000L, 7_900_000L to 7_200_000L)) {
                    assertEquals(landed, demuxer.seekTo(target), "${file.name}: seekTo($target)")
                    val after = generateSequence { demuxer.readPacket() }.toList()
                    assertEquals(landed, after.first().ptsUs, "${file.name}: lo primero tras buscar $target no es el keyframe")
                    assertTrue(after.all { it.trackId == main || it.trackId == camera }, "${file.name}: el audio, que acabó en el segundo 2, sale tras buscar $target")
                    val cameraPackets = after.filter { it.trackId == camera }
                    if (file.extension == "mkv" && target == 5_000_000L) {
                        assertTrue(cameraPackets.firstOrNull()?.isKeyFrame ?: true, "${file.name}: la cámara no empieza en un keyframe")
                    } else {
                        assertTrue(cameraPackets.isEmpty(), "${file.name}: la cámara, que acabó en el segundo 2, sale tras buscar $target (${cameraPackets.firstOrNull()?.ptsUs} µs)")
                    }
                }
            }
        }
    }
}
