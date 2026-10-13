package com.braymon.kotmpeg.benchmarks

import com.braymon.kotmpeg.MkvKotlin
import com.braymon.kotmpeg.Muxer
import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import java.io.File

/**
 * El archivo de referencia de las mediciones del §4 de `ANALISIS.md`: dos horas de vídeo a 60 fps
 * con AAC, unas 770 000 muestras. Las cargas son de unos pocos bytes porque lo que se mide es el
 * coste de las tablas, no el de copiar datos; así el archivo ocupa unos MB y se escribe en un par de
 * segundos al preparar cada fork.
 */
internal object TwoHourFiles {

    const val DURATION_US: Long = 2L * 60 * 60 * 1_000_000
    const val VIDEO_FRAME_US: Long = 16_667
    const val AUDIO_FRAME_US: Long = 21_333

    val plain: File by lazy { write("dos-horas", ContainerFormat.MP4, fragmented = false) }
    val fragmented: File by lazy { write("dos-horas-fragmentado", ContainerFormat.MP4, fragmented = true) }

    private val videoTrack = TrackInfo.Video(
        codec = VideoCodec.H264, width = 1920, height = 1080, frameRate = 60.0,
        codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x28, 0x11)), listOf(byteArrayOf(0x68, 0x22))),
    )
    private val audioTrack = TrackInfo.Audio(
        codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2, codecPrivate = AacConfig.build(48000, 2),
    )

    /** Escribe las dos horas en [muxer], intercaladas por tiempo como las entregaría un grabador. */
    fun writeInto(muxer: Muxer) {
        val video = muxer.addTrack(videoTrack)
        val audio = muxer.addTrack(audioTrack)
        muxer.start()
        val frame = NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x65, 1, 2)))
        val sound = byteArrayOf(0x21, 0x10)
        var v = 0L
        var a = 0L
        while (true) {
            val videoUs = v * VIDEO_FRAME_US
            val audioUs = a * AUDIO_FRAME_US
            if (videoUs >= DURATION_US && audioUs >= DURATION_US) break
            if (videoUs <= audioUs && videoUs < DURATION_US) {
                muxer.writePacket(MediaPacket(video, frame, videoUs, videoUs, v % 120 == 0L, VIDEO_FRAME_US))
                v++
            } else {
                muxer.writePacket(MediaPacket(audio, sound, audioUs, audioUs, true, AUDIO_FRAME_US))
                a++
            }
        }
        muxer.stop()
    }

    private fun write(name: String, format: ContainerFormat, fragmented: Boolean): File {
        val file = File.createTempFile(name, ".mp4").apply { deleteOnExit() }
        writeInto(MkvKotlin.createMuxer(file, format, mp4Fragmented = fragmented))
        return file
    }
}
