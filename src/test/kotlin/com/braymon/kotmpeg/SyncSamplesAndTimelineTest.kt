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
 *
 * Dos más salieron al validar con grabaciones reales (3.1.0): una pausa entre un B-frame y su
 * referencia, que dejaba ese B-frame decodificándose segundos después de mostrarse, y el cebado del
 * audio en un fMP4, que el demuxer no aplicaba.
 *
 * Y uno de la 4.1.0, al reempaquetar en fMP4 una de esas grabaciones ya pasada a fMP4: la duración
 * del último paquete de cada fragmento mandaba sobre el PTS del keyframe siguiente.
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

    private fun audioTrack() = TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2) {
        codecPrivate = AacConfig.build(48000, 2)
    }

    private fun videoTrack() = TrackInfo.Video(codec = VideoCodec.H264, width = 320, height = 240) {
        frameRate = 30.0
        codecPrivate = avcC
    }

    /** Audio con `isKeyFrame` a su valor por defecto, como lo escribiría una integración ingenua. */
    private fun writeAudioOnly(muxer: Muxer, packets: Int = 200, firstPtsUs: Long = 0) {
        val id = muxer.addTrack(audioTrack())
        muxer.start()
        for (i in 0 until packets) {
            muxer.writePacket(MediaPacket(id, ByteArray(64) { i.toByte() }, ptsUs = firstPtsUs + i * 21_333L, isKeyFrame = false))
        }
        muxer.stop()
    }

    private fun readAll(file: File): List<MediaPacket> = Kotmpeg.openDemuxer(file).use { demuxer ->
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
        writeAudioOnly(MkvMuxer(file) { maxClusterDurationMs = 1_000 }, packets = 400)
        Kotmpeg.openDemuxer(file).use { demuxer ->
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
        Kotmpeg.openDemuxer(file).use { demuxer ->
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
        val mkvUs = Kotmpeg.openDemuxer(mkv).use { it.durationUs }
        val mp4Us = Kotmpeg.openDemuxer(mp4).use { it.durationUs }
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
                        muxer.writePacket(MediaPacket(audio, ByteArray(8), ptsUs = audioIndex * 21_333L, isKeyFrame = false))
                        audioIndex++
                    }
                    ptsUs += if (frame == 9) 2_000_000 + burst * 350_000L else 16_667
                }
            }
        }
        Kotmpeg.openDemuxer(file).use { demuxer ->
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

    /**
     * La pausa de una captura de pantalla cayendo entre un B-frame y su referencia, como en una
     * grabación real de una tablet Android: el codificador retiene el B-frame mostrado justo antes
     * de la pausa y lo entrega después de la referencia mostrada justo después. Con el DTS sacado
     * solo de los PTS ordenados, ese B-frame se decodificaba cinco segundos después de mostrarse, y
     * FFmpeg retrasaba todo el vídeo esos cinco segundos. Ahora se adelanta un fotograma, como
     * cualquier otro B-frame, y la pausa pasa a su duración, que es lo que se ve durante ella.
     */
    @Test
    fun `a b-frame held across a screen pause is decoded at most one frame after it is shown in fmp4`() {
        val file = File(dir, "pausa-bframe.mp4")
        val frame = 16_667L
        val pause = 5_000_000L
        fun shownAt(index: Int): Long = index * frame + if (index >= 20) pause else 0L
        val expected = ArrayList<Long>()
        FragmentedMp4Muxer(file).use { muxer ->
            val video = muxer.addTrack(videoTrack())
            muxer.start()
            for (first in listOf(0, 30)) {
                val order = arrayListOf(first)
                for (reference in first + 2 until first + 30 step 2) order += listOf(reference, reference - 1)
                order += first + 29
                for (index in order) {
                    val payload = NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x41, index.toByte())))
                    muxer.writePacket(MediaPacket(video, payload, ptsUs = shownAt(index), isKeyFrame = index == first))
                    expected += shownAt(index)
                }
            }
        }
        Kotmpeg.openDemuxer(file).use { demuxer ->
            val frames = generateSequence { demuxer.readPacket() }.toList()
            assertEquals(expected.size, frames.size)
            var previousDts = Long.MIN_VALUE
            for ((read, ptsUs) in frames.zip(expected)) {
                assertTrue(abs(read.ptsUs - ptsUs) <= ONE_VIDEO_TICK_US, "PTS ${read.ptsUs} µs, se esperaba $ptsUs")
                assertTrue(read.dtsUs > previousDts, "el DTS no avanza en el fotograma de $ptsUs µs")
                previousDts = read.dtsUs
                assertTrue(
                    read.dtsUs - read.ptsUs <= frame + ONE_VIDEO_TICK_US,
                    "el fotograma de $ptsUs µs se decodifica ${read.dtsUs - read.ptsUs} µs después de mostrarse",
                )
            }
            val held = frames.first { abs(it.ptsUs - shownAt(19)) <= ONE_VIDEO_TICK_US }
            assertTrue(held.durationUs >= pause, "la pausa tiene que quedar en el B-frame retenido, que dura ${held.durationUs} µs")
        }
    }

    /**
     * Reempaquetar en fMP4 un fMP4 de la propia librería tiene que dejar los mismos tiempos. Cada GOP
     * acaba, en orden de decodificación, en un B-frame mostrado justo antes de una pausa, así que el
     * fMP4 le da a ese B-frame la pausa como duración (el caso de arriba). Al releerlo, el paquete
     * trae esa duración, y hasta la 4.0 mandaba sobre el PTS del keyframe siguiente para el último
     * hueco del fragmento, el que empieza en la referencia mostrada tras la pausa: el fragmento
     * acababa una pausa después de que empezara a mostrarse el keyframe, y cada remux retrasaba
     * otra pausa la decodificación de todo lo que venía detrás. En el último fragmento, sin keyframe
     * siguiente, esa duración se contaba desde la referencia y no desde el B-frame, y el archivo
     * crecía una pausa en cada pasada.
     *
     * Los tiempos caen en tics exactos de 90 kHz, como los de cualquier MP4 releído, para comparar
     * sin redondeos.
     */
    @Test
    fun `remuxing an fmp4 with b-frames held across pauses into fmp4 keeps every timestamp`() {
        val frame = 20_000L
        val pause = 2_000_000L
        val gop = 29
        fun shownAt(index: Int): Long = index * frame + (index + 1) / gop * pause
        val gen1 = File(dir, "gen1.mp4")
        FragmentedMp4Muxer(gen1).use { muxer ->
            val video = muxer.addTrack(videoTrack())
            muxer.start()
            for (first in 0 until 3 * gop step gop) {
                val order = arrayListOf(first)
                for (reference in first + 2 until first + gop step 2) order += listOf(reference, reference - 1)
                for (index in order) {
                    val payload = NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x41, index.toByte())))
                    muxer.writePacket(MediaPacket(video, payload, ptsUs = shownAt(index), isKeyFrame = index == first))
                }
            }
        }
        val gen2 = File(dir, "gen2.mp4").also { Kotmpeg.remux(gen1, it) { fragmented = true } }
        val gen3 = File(dir, "gen3.mp4").also { Kotmpeg.remux(gen2, it) { fragmented = true } }

        val original = readAll(gen1)
        assertEquals(3 * gop, original.size)
        assertTrue(original.filter { it.isKeyFrame }.all { it.dtsUs == it.ptsUs }, "el fMP4 de partida ya decodifica tarde un keyframe")
        val durationUs = Kotmpeg.openDemuxer(gen1).use { it.durationUs }
        for (copy in listOf(gen2, gen3)) {
            val packets = readAll(copy)
            assertEquals(original.size, packets.size, "${copy.name}: paquetes perdidos")
            for ((before, after) in original.zip(packets)) {
                assertEquals(before.ptsUs, after.ptsUs, "${copy.name}: el PTS cambió")
                assertEquals(before.dtsUs, after.dtsUs, "${copy.name}: el fotograma de ${before.ptsUs} µs se decodifica en ${after.dtsUs} µs")
                assertEquals(before.durationUs, after.durationUs, "${copy.name}: la duración del fotograma de ${before.ptsUs} µs cambió")
            }
            assertEquals(durationUs, Kotmpeg.openDemuxer(copy).use { it.durationUs }, "${copy.name}: la duración del archivo cambió")
        }
    }

    /**
     * El cebado del AAC se escribe como lista de edición en MP4 y fMP4 y como `CodecDelay` en
     * Matroska, y los tres tienen que releerse igual: los paquetes con los tiempos con los que se
     * escribieron, desde cero, y el cebado en `codecDelayUs`.
     *
     * Hasta la 3.0.0 el fMP4 perdía la edición. Hasta la 3.x los dos MP4 la restaban de los tiempos
     * y declaraban un retardo cero, al revés que el MKV: el mismo audio llegaba 21 ms antes desde un
     * MP4 que desde un MKV, y pasarlo de un contenedor a otro arrastraba ese desfase.
     */
    @Test
    fun `encoder priming reads back the same from mp4, fmp4 and mkv`() {
        val primed = audioTrack().copy { codecDelayUs = 21_333 }
        val written = (0 until 200).map { it * 21_333L }
        for ((name, fragmented) in listOf("cebado.mp4" to false, "cebado-frag.mp4" to true, "cebado.mkv" to false)) {
            val file = File(dir, name)
            Kotmpeg.createMuxer(file) { this.fragmented = fragmented }.use { muxer ->
                val audio = muxer.addTrack(primed)
                muxer.start()
                for (pts in written) muxer.writePacket(MediaPacket(audio, ByteArray(8), ptsUs = pts, isKeyFrame = true))
            }
            Kotmpeg.openDemuxer(file).use { demuxer ->
                val delay = (demuxer.tracks.single() as TrackInfo.Audio).codecDelayUs
                assertTrue(abs(delay - 21_333) <= 21, "$name: cebado releído como $delay µs")
                val read = generateSequence { demuxer.readPacket() }.map { it.ptsUs }.toList()
                assertEquals(0L, read.first(), "$name: el primer paquete tiene que seguir en cero")
                assertEquals(written.size, read.size, "$name: paquetes releídos")
                val worst = written.zip(read).maxOf { (w, r) -> abs(w - r) }
                assertTrue(worst < 1_000, "$name: un paquete se releyó $worst µs fuera de su tiempo")
            }
        }
    }

    @Test
    fun `muxers reject parameters that would produce a broken file`() {
        assertThrows<IllegalArgumentException> { MkvMuxer(File(dir, "a.mkv")) { maxClusterDurationMs = 0 } }
        assertThrows<IllegalArgumentException> { FragmentedMp4Muxer(File(dir, "b.mp4")) { fragmentDurationUs = 0 } }
        assertThrows<IllegalArgumentException> {
            Kotmpeg.createMuxer(File(dir, "c.mp4"), ContainerFormat.MP4) { fragmented = true; fragmentDurationUs = -1 }
        }
    }
}
