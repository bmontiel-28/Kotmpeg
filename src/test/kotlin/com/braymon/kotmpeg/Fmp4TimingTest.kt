package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El tiempo de decodificación de un fMP4 cuando un fragmento empieza a presentarse **antes** de
 * donde acabó de decodificarse el anterior.
 *
 * El muxer saca el DTS de los PTS ordenados de cada fragmento y encadena los fragmentos: cada uno
 * se decodifica, como pronto, donde acabó el anterior. Si su primer PTS cae antes —los fotogramas
 * iniciales de un GOP abierto, que se muestran antes que el keyframe que los abre; un corte por
 * tamaño o por duración a mitad de un GOP con B-frames; o un primer PTS negativo—, ese solape se
 * quedaba como retraso de todo el fragmento y pasaba entero al siguiente, que añadía el suyo: con
 * x265 por defecto, dos fotogramas más por GOP. Los desfases de composición eran cada vez más
 * negativos —FFmpeg retrasa el vídeo lo que mida el mayor—, los keyframes se decodificaban tarde y
 * la duración declarada crecía con el archivo.
 *
 * Los tiempos caen en tics exactos de 90 kHz para comparar sin redondeos.
 */
class Fmp4TimingTest {

    private companion object {
        const val FRAME_US = 20_000L

        /** Un tic de 90 kHz son 11,1 µs. */
        const val TICK_US = 12L
    }

    @TempDir
    lateinit var dir: File

    private val video = TrackInfo.Video(VideoCodec.H264, 64, 48) {
        frameRate = 50.0
        codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22)))
    }

    /** Un fotograma de [size] bytes que lleva dentro su posición de presentación, sin DTS ni duración, como los da `MediaCodec`. */
    private fun frame(shown: Int, key: Boolean, size: Int = 8): MediaPacket {
        val payload = NalUnits.joinLengthPrefixed(listOf(ByteArray(size) { if (it == 0) 0x41 else shown.toByte() }))
        return MediaPacket(1, payload, shown * FRAME_US, isKeyFrame = key)
    }

    private fun write(name: String, frames: List<MediaPacket>, options: MuxerOptions.() -> Unit = {}): File {
        val file = File(dir, name)
        FragmentedMp4Muxer(file, options).use { muxer ->
            muxer.addTrack(video)
            muxer.start()
            for (packet in frames) muxer.writePacket(packet)
        }
        return file
    }

    private class Read(val packets: List<MediaPacket>, val durationUs: Long)

    private fun read(file: File): Read = Kotmpeg.openDemuxer(file).use { demuxer ->
        Read(generateSequence { demuxer.readPacket() }.toList(), demuxer.durationUs)
    }

    /**
     * Lo que tiene que cumplir el vídeo releído: el DTS siempre avanzando, cada keyframe donde se
     * escribió y decodificándose a la misma distancia de su presentación en todo el archivo, y la
     * duración del archivo hasta el final del último fotograma. Con desfases firmados ([signed]),
     * además, cada fotograma donde se escribió, los keyframes decodificándose cuando se muestran y
     * ningún fotograma decodificándose más de [maxLeadFrames] después de mostrarse.
     */
    private fun assertTimeline(label: String, written: List<MediaPacket>, read: Read, signed: Boolean, maxLeadFrames: Int) {
        assertEquals(written.size, read.packets.size, "$label: fotogramas perdidos")
        var previousDts = Long.MIN_VALUE
        var keyLead: Long? = null
        for ((expected, packet) in written.zip(read.packets)) {
            assertTrue(packet.dtsUs > previousDts, "$label: el DTS no avanza en el fotograma de ${expected.ptsUs} µs")
            previousDts = packet.dtsUs
            val lead = packet.dtsUs - packet.ptsUs
            val misplaced = abs(packet.ptsUs - expected.ptsUs) > TICK_US
            if (expected.isKeyFrame) {
                assertTrue(!misplaced, "$label: el keyframe de ${expected.ptsUs} µs se presenta en ${packet.ptsUs}")
                val first = keyLead ?: lead.also { keyLead = it }
                assertTrue(abs(lead - first) <= TICK_US, "$label: el keyframe de ${expected.ptsUs} µs se decodifica $lead µs tras mostrarse y el primero $first")
                if (signed) assertTrue(abs(lead) <= TICK_US, "$label: el keyframe de ${expected.ptsUs} µs se decodifica en ${packet.dtsUs}")
            }
            if (!signed) continue
            assertTrue(!misplaced, "$label: el fotograma de ${expected.ptsUs} µs se presenta en ${packet.ptsUs}")
            assertTrue(
                lead <= maxLeadFrames * FRAME_US + 3 * TICK_US,
                "$label: el fotograma de ${expected.ptsUs} µs se decodifica $lead µs después de mostrarse",
            )
        }
        val expectedDuration = written.maxOf { it.ptsUs } + FRAME_US
        assertTrue(abs(read.durationUs - expectedDuration) <= TICK_US, "$label: dura ${read.durationUs} µs y el último fotograma acaba en $expectedDuration")
    }

    /**
     * GOP abierto, como x265 por defecto: cada GOP empieza en una imagen CRA cuyos dos B-frames
     * iniciales se muestran antes que ella. En orden de decodificación,
     * `I0 P3 B1 B2 P6 B4 B5 | I9 B7 B8 P12 B10 B11 | I15 B13 B14 …`. El corte en `I9` cierra el
     * primer fragmento en el 9, y el segundo empieza a presentarse en el 7.
     *
     * Con desfases sin signo, la lista de edición la fija el primer fragmento, que aquí no tiene
     * imágenes iniciales: compensa un fotograma, y las imágenes iniciales de los GOP siguientes, que
     * se adelantan dos, no caben y se presentan un fotograma tarde. Lo que no puede pasar es lo de
     * antes: que el retraso acumulado moviera los keyframes y alargara el archivo.
     */
    @Test
    fun `an open gop does not push decode time further behind on every fragment`() {
        val frames = ArrayList<MediaPacket>()
        for (shown in listOf(0, 3, 1, 2, 6, 4, 5)) frames += frame(shown, key = shown == 0)
        for (g in 1 until 10) {
            for (offset in listOf(3, 1, 2, 6, 4, 5)) frames += frame(6 * g + offset, key = offset == 3)
        }
        for (signed in listOf(true, false)) {
            val file = write("gop-abierto-$signed.mp4", frames) { nonNegativeCompositionOffsets = !signed }
            assertTimeline(if (signed) "con signo" else "sin signo", frames, read(file), signed, maxLeadFrames = 2)
        }
    }

    /**
     * GOP cerrado con B-frames (`I0 P3 B1 B2 P6 B4 B5 P9 …`) cortado por `maxFragmentBytes` cada
     * cinco fotogramas, en cualquier punto del GOP. Cuando el paquete que abre el fragmento siguiente
     * se muestra antes que el último del fragmento —un B-frame—, el fragmento no puede acabar más
     * tarde de lo imprescindible, porque lo que se pase lo tiene que recuperar el siguiente.
     */
    @Test
    fun `fragments cut mid gop by size absorb the overlap instead of passing it on`() {
        val frames = ArrayList<MediaPacket>()
        for (gop in 0 until 4) {
            val first = gop * 30
            frames += frame(first, key = true, size = 1000)
            var k = 1
            while (k < 30) {
                val order = if (k + 2 < 30) listOf(k + 2, k, k + 1) else (k until 30).toList()
                for (offset in order) frames += frame(first + offset, key = false, size = 1000)
                k += 3
            }
        }
        for (signed in listOf(true, false)) {
            val file = write("cortes-por-tamano-$signed.mp4", frames) {
                maxFragmentBytes = 4_500
                nonNegativeCompositionOffsets = !signed
            }
            assertTimeline(if (signed) "con signo" else "sin signo", frames, read(file), signed, maxLeadFrames = 2)
        }
    }

    /**
     * Un primer PTS negativo: un B-frame que se muestra antes que el keyframe que abrió la línea de
     * tiempo, como al reempaquetar desde un punto de búsqueda con GOP abierto. `tfdt` no tiene signo
     * y el primer fragmento se decodifica desde cero; antes, ese fotograma de diferencia retrasaba el
     * resto del archivo.
     */
    @Test
    fun `a negative first timestamp does not delay the rest of the file`() {
        val frames = (0 until 90).map { i ->
            val shown = when (i) {
                0 -> 1
                1 -> 0
                else -> i
            }
            frame(shown, key = i % 30 == 0)
        }
        val origin = frames.first().ptsUs
        val shifted = frames.map { MediaPacket(it.trackId, it.data, it.ptsUs - origin, isKeyFrame = it.isKeyFrame) }
        for (signed in listOf(true, false)) {
            val file = write("antes-del-origen-$signed.mp4", frames) { nonNegativeCompositionOffsets = !signed }
            assertTimeline(if (signed) "con signo" else "sin signo", shifted, read(file), signed, maxLeadFrames = 1)
        }
    }
}
