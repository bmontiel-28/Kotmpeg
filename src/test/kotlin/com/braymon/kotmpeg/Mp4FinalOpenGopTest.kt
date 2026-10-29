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
 * Cuánto se adelanta el tiempo de decodificación al de presentación en un fMP4 con GOP abierto y
 * pirámide de B-frames: el límite real, que es el que documenta `FragmentedMp4Muxer`.
 *
 * Cada fragmento empieza en una imagen CRA cuyas [LEADING] imágenes iniciales se muestran antes que
 * ella y se decodifican después. `FragmentTiming.absorbOverlap` decodifica la CRA en su propio PTS,
 * así que las imágenes iniciales se adelantan tantos fotogramas como imágenes iniciales haya: aquí
 * cuatro, el doble que la profundidad de reordenación a la que el KDoc decía que quedaba acotado.
 * Lo que sí garantiza es que no se acumula: el adelanto es el mismo en todos los fragmentos, y antes
 * de la 4.1.0 crecía en cada uno.
 *
 * Los tiempos caen en tics exactos de 90 kHz, y la tolerancia son los pocos tics con los que el muxer
 * mantiene el DTS creciente entre la CRA y sus imágenes iniciales.
 */
class Mp4FinalOpenGopTest {

    private companion object {
        const val FRAME_US = 20_000L
        const val TICK_US = 12L
        const val GOPS = 10

        /** Imágenes iniciales de cada GOP abierto. */
        const val LEADING = 4

        /** Lo que se adelanta un B-frame del primer GOP, que es cerrado: la profundidad de reordenación. */
        const val FIRST_GOP_LEAD = 2

        /** Pasos mínimos entre la CRA y sus imágenes iniciales, con margen. */
        const val SLACK_US = 5 * TICK_US
    }

    @TempDir
    lateinit var dir: File

    private val video = TrackInfo.Video(VideoCodec.H264, 64, 48) {
        frameRate = 50.0
        codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22)))
    }

    private fun frame(shown: Int, key: Boolean): MediaPacket {
        val payload = NalUnits.joinLengthPrefixed(listOf(ByteArray(8) { if (it == 0) 0x41 else shown.toByte() }))
        return MediaPacket(1, payload, shown * FRAME_US, isKeyFrame = key)
    }

    /**
     * Un primer GOP cerrado, `I0 P5 B3 B1 B2 B4`, y detrás GOP abiertos de diez fotogramas con
     * pirámide: la CRA, sus cuatro imágenes iniciales `-2 -4 -3 -1` y sus cinco finales
     * `+5 +3 +1 +2 +4`. Las presentaciones son contiguas, sin huecos entre un GOP y el siguiente.
     */
    private fun openGopPyramid(): List<MediaPacket> {
        val frames = ArrayList<MediaPacket>()
        frames += frame(0, key = true)
        for (shown in listOf(5, 3, 1, 2, 4)) frames += frame(shown, key = false)
        for (g in 1 until GOPS) {
            val cra = 10 * g
            frames += frame(cra, key = true)
            for (offset in listOf(-2, -4, -3, -1, 5, 3, 1, 2, 4)) frames += frame(cra + offset, key = false)
        }
        return frames
    }

    /** El GOP de un fotograma, por su presentación: el GOP `g` muestra de `10g - 4` a `10g + 5`. */
    private fun gopOf(packet: MediaPacket): Int = ((packet.ptsUs / FRAME_US + LEADING) / 10).toInt()

    private fun writeAndRead(name: String, frames: List<MediaPacket>, unsigned: Boolean): List<MediaPacket> {
        val file = File(dir, name)
        FragmentedMp4Muxer(file) { nonNegativeCompositionOffsets = unsigned }.use { muxer ->
            muxer.addTrack(video)
            muxer.start()
            for (packet in frames) muxer.writePacket(packet)
        }
        return Kotmpeg.openDemuxer(file).use { demuxer ->
            val packets = generateSequence { demuxer.readPacket() }.toList()
            val end = frames.maxOf { it.ptsUs } + FRAME_US
            assertTrue(abs(demuxer.durationUs - end) <= TICK_US, "$name: dura ${demuxer.durationUs} µs y el último fotograma acaba en $end")
            packets
        }
    }

    private fun assertNear(expected: Long, actual: Long, label: String) {
        assertTrue(actual in expected - TICK_US..expected + SLACK_US, "$label: $actual µs, se esperaban unos $expected")
    }

    /**
     * Con desfases firmados, el modo por defecto, cada fotograma se presenta donde se escribió, cada
     * CRA se decodifica cuando se muestra, y el mayor adelanto de cada GOP abierto es el de su primera
     * imagen inicial: cuatro fotogramas, en todos los GOP por igual. El primer GOP, cerrado, se adelanta
     * solo su profundidad de reordenación.
     */
    @Test
    fun `with signed offsets an open gop leads by its leading pictures and does not accumulate`() {
        val frames = openGopPyramid()
        val read = writeAndRead("firmado.mp4", frames, unsigned = false)
        assertEquals(frames.size, read.size, "fotogramas perdidos")
        val maxLead = LongArray(GOPS) { Long.MIN_VALUE }
        var previousDts = Long.MIN_VALUE
        for ((expected, packet) in frames.zip(read)) {
            assertTrue(packet.dtsUs > previousDts, "el DTS no avanza en el fotograma de ${expected.ptsUs} µs")
            previousDts = packet.dtsUs
            assertTrue(abs(packet.ptsUs - expected.ptsUs) <= TICK_US, "el fotograma de ${expected.ptsUs} µs se presenta en ${packet.ptsUs}")
            val lead = packet.dtsUs - packet.ptsUs
            if (expected.isKeyFrame) assertTrue(abs(lead) <= TICK_US, "la CRA de ${expected.ptsUs} µs se decodifica en ${packet.dtsUs}")
            val gop = gopOf(expected)
            maxLead[gop] = maxOf(maxLead[gop], lead)
        }
        assertNear(FIRST_GOP_LEAD * FRAME_US, maxLead[0], "adelanto del GOP cerrado")
        for (g in 1 until GOPS) assertNear(LEADING * FRAME_US, maxLead[g], "adelanto del GOP abierto $g")
    }

    /**
     * Con desfases sin signo, el desplazamiento de la lista de edición lo fija el primer fragmento:
     * los dos fotogramas que se adelanta el GOP cerrado. Las imágenes iniciales que se adelantan más
     * quedan con desfase cero y se presentan tarde —la primera, dos fotogramas—, y como todas acaban
     * pegadas a la CRA, en el orden en que se decodifican y no en el que se muestran. Las CRA no se
     * mueven, nada se presenta antes de tiempo y el retraso es el mismo en todos los GOP.
     */
    @Test
    fun `with unsigned offsets the leading pictures beyond the first lead are clipped and do not accumulate`() {
        val frames = openGopPyramid()
        val read = writeAndRead("sin-signo.mp4", frames, unsigned = true)
        assertEquals(frames.size, read.size, "fotogramas perdidos")
        val maxLate = LongArray(GOPS) { Long.MIN_VALUE }
        var keyLead: Long? = null
        for ((expected, packet) in frames.zip(read)) {
            val late = packet.ptsUs - expected.ptsUs
            assertTrue(late >= -TICK_US, "el fotograma de ${expected.ptsUs} µs se presenta antes, en ${packet.ptsUs}")
            if (expected.isKeyFrame) {
                assertTrue(abs(late) <= TICK_US, "la CRA de ${expected.ptsUs} µs se presenta en ${packet.ptsUs}")
                val lead = packet.dtsUs - packet.ptsUs
                val first = keyLead ?: lead.also { keyLead = it }
                assertTrue(abs(lead - first) <= TICK_US, "la CRA de ${expected.ptsUs} µs se decodifica a $lead µs y la primera a $first")
            }
            val gop = gopOf(expected)
            maxLate[gop] = maxOf(maxLate[gop], late)
        }
        assertTrue(abs(maxLate[0]) <= TICK_US, "el primer GOP se presenta tarde: ${maxLate[0]} µs")
        for (g in 1 until GOPS) assertNear((LEADING - FIRST_GOP_LEAD) * FRAME_US, maxLate[g], "retraso del GOP abierto $g")
    }
}
