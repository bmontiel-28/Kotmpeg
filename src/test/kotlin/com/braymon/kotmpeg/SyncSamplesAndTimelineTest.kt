package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import com.braymon.kotmpeg.mp4.Mp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dos fallos de integración que solo aparecían al alimentar los muxers como lo hace una app
 * móvil, con los paquetes recién salidos de un codificador por hardware:
 *
 *  - **`MediaPacket.isKeyFrame` vale `false` por defecto**, y los muxers copiaban el valor tal
 *    cual también para el audio. El MP4 salía con un `stss` vacío —ninguna muestra de audio era
 *    un punto de acceso—, el fMP4 con todas las muestras marcadas como dependientes y el MKV sin
 *    un solo cue en un archivo solo de audio.
 *  - **Las marcas de `MediaCodec` cuentan desde el arranque del dispositivo.** Los muxers MP4 ya
 *    movían el origen al inicio de la grabación, pero `MkvMuxer` las escribía tal cual: un archivo
 *    de un segundo declaraba una duración de días. (Qué paquete marca ese inicio cuando las pistas
 *    llegan desordenadas lo cubre `TrackStartOrderTest`.)
 *
 * Y una diferencia entre contenedores: sin duraciones en los paquetes, el MKV contaba el último
 * fotograma como si durase cero y declaraba un fotograma menos que el MP4 de la misma entrada.
 *
 * Por último, el vídeo de **frecuencia variable** de una captura de pantalla en fMP4: la duración
 * del último fotograma de cada fragmento se estimaba con el intervalo anterior, así que cada pausa
 * de la pantalla desplazaba el tiempo de decodificación respecto al de presentación.
 */
class SyncSamplesAndTimelineTest {

    private companion object {
        /** La escala de vídeo MP4 es de 90 kHz: un tic son 11,1 µs, el redondeo admisible. */
        const val ONE_VIDEO_TICK_US = 12L
    }

    @TempDir
    lateinit var dir: File

    private val avcC = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    private fun audioTrack() = TrackInfo.Audio(
        codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2, codecPrivate = AacConfig.build(48000, 2),
    )

    private fun videoTrack() = TrackInfo.Video(
        codec = VideoCodec.H264, width = 320, height = 240, frameRate = 30.0, codecPrivate = avcC,
    )

    /** Audio con `isKeyFrame` a su valor por defecto, como lo escribiría una integración ingenua. */
    private fun writeAudioOnly(muxer: Muxer, packets: Int = 200, firstPtsUs: Long = 0) {
        val id = muxer.addTrack(audioTrack())
        muxer.start()
        for (i in 0 until packets) {
            muxer.writePacket(MediaPacket(id, ByteArray(64) { i.toByte() }, ptsUs = firstPtsUs + i * 21_333L))
        }
        muxer.stop()
    }

    private fun readAll(file: File): List<MediaPacket> = MkvKotlin.openDemuxer(file).use { demuxer ->
        generateSequence { demuxer.readPacket() }.toList()
    }

    @Test
    fun `audio packets become sync samples in every container even when not flagged`() {
        val outputs = listOf(
            File(dir, "audio.mkv").also { writeAudioOnly(MkvMuxer(it)) },
            File(dir, "audio.mp4").also { writeAudioOnly(Mp4Muxer(it)) },
            File(dir, "audio-frag.mp4").also { writeAudioOnly(FragmentedMp4Muxer(it)) },
        )
        for (file in outputs) {
            val packets = readAll(file)
            assertEquals(200, packets.size, "${file.name}: paquetes perdidos")
            assertTrue(packets.all { it.isKeyFrame }, "${file.name}: hay audio marcado como no sincronizable")
        }
    }

    @Test
    fun `an audio only mkv written without key flags can still seek`() {
        val file = File(dir, "seek.mkv")
        writeAudioOnly(MkvMuxer(file, maxClusterDurationMs = 1_000), packets = 400)
        MkvKotlin.openDemuxer(file).use { demuxer ->
            val landed = demuxer.seekTo(5_000_000)
            assertTrue(landed in 4_000_000..5_000_000, "el seek cayó en $landed µs: no hay cues")
            val next = demuxer.readPacket()
            assertTrue(next != null && next.ptsUs >= landed, "tras el seek no se lee desde el cue")
        }
    }

    @Test
    fun `an mkv fed with an uptime clock starts at zero and declares its real duration`() {
        val thirtyDaysUs = 30L * 24 * 3600 * 1_000_000
        val file = File(dir, "uptime.mkv")
        writeAudioOnly(MkvMuxer(file), packets = 47, firstPtsUs = thirtyDaysUs)
        MkvKotlin.openDemuxer(file).use { demuxer ->
            assertTrue(demuxer.durationUs in 950_000..1_100_000, "duración declarada ${demuxer.durationUs} µs")
            assertEquals(0L, demuxer.readPacket()?.ptsUs, "la línea de tiempo no empieza en cero")
        }
    }

    @Test
    fun `a negative first timestamp is kept and not shifted`() {
        val file = File(dir, "priming.mkv")
        writeAudioOnly(MkvMuxer(file), packets = 10, firstPtsUs = -21_333)
        assertEquals(-21_000L, readAll(file).first().ptsUs)
    }

    @Test
    fun `mkv and mp4 agree on the duration when packets carry none`() {
        fun write(muxer: Muxer) {
            val id = muxer.addTrack(videoTrack())
            muxer.start()
            for (i in 0 until 30) {
                val payload = NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x65, i.toByte())))
                muxer.writePacket(MediaPacket(id, payload, ptsUs = i * 33_333L, isKeyFrame = i == 0))
            }
            muxer.stop()
        }
        val mkv = File(dir, "dur.mkv").also { write(MkvMuxer(it)) }
        val mp4 = File(dir, "dur.mp4").also { write(Mp4Muxer(it)) }
        val mkvUs = MkvKotlin.openDemuxer(mkv).use { it.durationUs }
        val mp4Us = MkvKotlin.openDemuxer(mp4).use { it.durationUs }
        assertTrue(abs(mkvUs - 1_000_000) <= 1_000, "MKV declara $mkvUs µs para 30 fotogramas a 30 fps")
        assertTrue(abs(mkvUs - mp4Us) <= 1_000, "MKV $mkvUs µs y MP4 $mp4Us µs no coinciden")
    }

    @Test
    fun `a variable frame rate screen capture keeps decode and presentation time aligned in fmp4`() {
        val file = File(dir, "pantalla-vfr.mp4")
        val expected = ArrayList<Long>()
        FragmentedMp4Muxer(file).use { muxer ->
            val video = muxer.addTrack(videoTrack())
            val audio = muxer.addTrack(audioTrack())
            muxer.start()
            var ptsUs = 0L
            var audioIndex = 0
            for (burst in 0 until 5) {
                for (frame in 0 until 10) {
                    val payload = NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x41, frame.toByte())))
                    muxer.writePacket(MediaPacket(video, payload, ptsUs = ptsUs, isKeyFrame = frame == 0))
                    expected += ptsUs
                    while (audioIndex * 21_333L <= ptsUs) {
                        muxer.writePacket(MediaPacket(audio, ByteArray(8), ptsUs = audioIndex * 21_333L))
                        audioIndex++
                    }
                    ptsUs += if (frame == 9) 2_000_000 + burst * 350_000L else 16_667
                }
            }
        }
        MkvKotlin.openDemuxer(file).use { demuxer ->
            val videoId = demuxer.tracks.first { it is TrackInfo.Video }.id
            val frames = generateSequence { demuxer.readPacket() }.filter { it.trackId == videoId }.toList()
            assertEquals(expected.size, frames.size)
            for ((frame, ptsUs) in frames.zip(expected)) {
                assertTrue(abs(frame.ptsUs - ptsUs) <= ONE_VIDEO_TICK_US, "PTS ${frame.ptsUs} µs, se esperaba $ptsUs")
                assertEquals(frame.ptsUs, frame.dtsUs, "el DTS se separó del PTS en ${frame.ptsUs} µs")
            }
            for (burst in 0 until 4) {
                val beforePause = frames[burst * 10 + 9]
                val pause = 2_000_000L + burst * 350_000L
                assertTrue(
                    abs(beforePause.durationUs - pause) <= ONE_VIDEO_TICK_US,
                    "el fotograma previo a la pausa $burst dura ${beforePause.durationUs} µs y la pausa fue de $pause",
                )
            }
        }
    }

    @Test
    fun `muxers reject parameters that would produce a broken file`() {
        assertThrows<IllegalArgumentException> { MkvMuxer(File(dir, "a.mkv"), maxClusterDurationMs = 0) }
        assertThrows<IllegalArgumentException> { FragmentedMp4Muxer(File(dir, "b.mp4"), fragmentDurationUs = 0) }
        assertThrows<IllegalArgumentException> {
            MkvKotlin.createMuxer(File(dir, "c.mp4"), ContainerFormat.MP4, mp4Fragmented = true, mp4FragmentDurationUs = -1)
        }
    }
}
