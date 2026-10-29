package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.ColorInfo
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.HdrStaticInfo
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Las cajas opcionales de la entrada de muestra de vídeo que comparten el MP4 plano y el
 * fragmentado: `pasp` para los píxeles no cuadrados y `mdcv` para el HDR estático. Cada caso se
 * comprueba en los dos, porque los dos escriben la entrada con el mismo código.
 */
class Mp4WriteSampleEntryTest {

    @TempDir
    lateinit var dir: File

    private val containers = listOf("plano.mp4" to false, "fragmentado.mp4" to true)

    private fun write(target: File, fragmented: Boolean, track: TrackInfo.Video) {
        Kotmpeg.createMuxer(target, ContainerFormat.MP4) { this.fragmented = fragmented }.use { muxer ->
            val id = muxer.addTrack(track)
            muxer.start()
            for (i in 0 until 10) {
                muxer.writePacket(MediaPacket(id, ByteArray(16), i * 33_333L, isKeyFrame = i == 0))
            }
        }
    }

    private fun video(configure: TrackInfo.Video.Builder.() -> Unit) =
        TrackInfo.Video(VideoCodec.H264, 640, 480) {
            codecPrivate = NalUnits.buildAvcC(
                listOf(byteArrayOf(0x67, 0x4D, 0x00, 0x1F, 0x11, 0x22, 0x33)),
                listOf(byteArrayOf(0x68, 0x11, 0x22)),
            )
            configure()
        }

    /** Carga de la primera caja de tipo [type] del archivo, o null si no hay ninguna. */
    private fun boxPayload(file: File, type: String): ByteArray? {
        val bytes = file.readBytes()
        val marker = type.toByteArray(Charsets.US_ASCII)
        val at = (4 until bytes.size - 4).firstOrNull { i -> (0..3).all { bytes[i + it] == marker[it] } } ?: return null
        val size = ((bytes[at - 4].toInt() and 0xFF) shl 24) or ((bytes[at - 3].toInt() and 0xFF) shl 16) or
            ((bytes[at - 2].toInt() and 0xFF) shl 8) or (bytes[at - 1].toInt() and 0xFF)
        return bytes.copyOfRange(at + 4, at - 4 + size)
    }

    private fun u32(data: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { acc, k -> (acc shl 8) or (data[at + k].toLong() and 0xFF) }

    private fun ffprobe(vararg args: String): String? = runCatching {
        val p = ProcessBuilder("ffprobe", *args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(60, TimeUnit.SECONDS)
        if (p.exitValue() == 0) out else null
    }.getOrNull()

    private val hasFfprobe: Boolean by lazy { ffprobe("-version") != null }

    /** La proporción de píxel que lee FFmpeg, solo la línea del dato: con `-v error` puede avisar antes. */
    private fun ffprobeSar(file: File): String? = ffprobe(
        "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=sample_aspect_ratio",
        "-of", "default=nw=1:nk=1", file.absolutePath,
    )?.lines()?.last()

    /**
     * **Un vídeo anamórfico lleva su `pasp`.** La proporción de píxel solo viajaba en el tamaño de
     * presentación del `tkhd`, y Media3/ExoPlayer la toma del `pasp`: un MKV anamórfico pasado a
     * MP4 se veía en Android con píxeles cuadrados. 640x480 presentado a 853x480 es un píxel de
     * 853:640, coherente con el `tkhd`, que no cambia; el demuxer, que manda el `tkhd`, relee lo
     * mismo que antes. Con FFmpeg instalado se comprueba además que lo lee igual.
     */
    @Test
    fun `an anamorphic track writes a pasp box consistent with its tkhd`() {
        for ((name, fragmented) in containers) {
            for (rotation in listOf(0, 90)) {
                val file = File(dir, "anamorfico-$rotation-$name")
                write(file, fragmented, video { displayWidth = 853; displayHeight = 480; rotationDegrees = rotation })
                val pasp = assertNotNull(boxPayload(file, "pasp"), "$name/$rotation: sin pasp")
                assertEquals(853L to 640L, u32(pasp, 0) to u32(pasp, 4), "$name/$rotation")

                val read = Kotmpeg.openDemuxer(file).use { it.tracks.single() as TrackInfo.Video }
                assertEquals(640 to 480, read.width to read.height, name)
                assertEquals(853 to 480, read.displayWidth to read.displayHeight, name)
                assertEquals(rotation, read.rotationDegrees, name)

                if (hasFfprobe) assertEquals("853:640", ffprobeSar(file), "$name/$rotation: FFmpeg lee otra proporción")
            }
        }
    }

    /** Píxeles cuadrados, aunque el tamaño de presentación sea otro múltiplo, no llevan `pasp`. */
    @Test
    fun `square pixels do not write a pasp box`() {
        for ((name, fragmented) in containers) {
            val same = File(dir, "cuadrado-$name")
            write(same, fragmented, video {})
            assertNull(boxPayload(same, "pasp"), name)

            val scaled = File(dir, "escalado-$name")
            write(scaled, fragmented, video { displayWidth = 1280; displayHeight = 960 })
            assertNull(boxPayload(scaled, "pasp"), "$name: 2x en las dos direcciones es 1:1")
        }
    }

    /**
     * **Un HDR que solo trae niveles de luz no gana un `mdcv` a cero.** Es justo lo que el demuxer
     * devuelve de un archivo con `clli` y sin `mdcv`: un [HdrStaticInfo] con los diez campos del
     * display de masterizado a 0. Al escribirlo de nuevo salía un `mdcv` todo ceros, un display de
     * masterizado sin primarias ni luminancia que un reproductor puede tomar en serio.
     */
    @Test
    fun `content light levels alone do not write a zeroed mdcv`() {
        val lightOnly = HdrStaticInfo(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1000, 400)
        val color = ColorInfo.hdr10(lightOnly)
        for ((name, fragmented) in containers) {
            val file = File(dir, "solo-clli-$name")
            write(file, fragmented, video { this.color = color })
            assertNull(boxPayload(file, "mdcv"), "$name: mdcv a cero")
            val clli = assertNotNull(boxPayload(file, "clli"), name)
            assertEquals(1000 to 400, ((clli[0].toInt() and 0xFF) shl 8 or (clli[1].toInt() and 0xFF)) to
                ((clli[2].toInt() and 0xFF) shl 8 or (clli[3].toInt() and 0xFF)), name)

            val read = Kotmpeg.openDemuxer(file).use { it.tracks.single() as TrackInfo.Video }
            assertEquals(color, read.color, "$name: el color no vuelve igual")

            val remuxed = File(dir, "remux-$name")
            Kotmpeg.remux(file, remuxed) { this.fragmented = fragmented }
            assertNull(boxPayload(remuxed, "mdcv"), "$name: el remux añadió un mdcv a cero")
        }

        val mastering = File(dir, "con-mdcv.mp4")
        write(mastering, false, video { this.color = ColorInfo.hdr10(lightOnly.copy(whiteX = 0.3127, whiteY = 0.329)) })
        assertNotNull(boxPayload(mastering, "mdcv"), "un display de masterizado con datos sí se escribe")
    }
}
