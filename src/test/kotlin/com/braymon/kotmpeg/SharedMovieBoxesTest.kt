package com.braymon.kotmpeg

import com.braymon.kotmpeg.Mp4ReadFixtures.trak
import com.braymon.kotmpeg.Mp4ReadFixtures.u32
import com.braymon.kotmpeg.Mp4ReadFixtures.within
import com.braymon.kotmpeg.SharedMp4Headers.creationTime
import com.braymon.kotmpeg.SharedMp4Headers.mdhd
import com.braymon.kotmpeg.SharedMp4Headers.movieOrMediaDuration
import com.braymon.kotmpeg.SharedMp4Headers.mvhd
import com.braymon.kotmpeg.SharedMp4Headers.tkhd
import com.braymon.kotmpeg.SharedMp4Headers.tkhdDuration
import com.braymon.kotmpeg.SharedMp4Headers.version
import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La cabecera de película que `Mp4Muxer` y `FragmentedMp4Muxer` escriben desde un solo sitio,
 * `MovieBoxes`. Las dos copias que había ya se habían separado, y estos tests fijan lo que tienen
 * que dar igual en los dos: la duración de una muestra sin otra de la que deducirla, la versión de
 * las cajas cuando la fecha no cabe en 32 bits y las cajas de cabecera de cada pista.
 */
class SharedMovieBoxesTest {

    @TempDir
    lateinit var dir: File

    private val containers = listOf("plano" to false, "fragmentado" to true)

    private fun write(name: String, fragmented: Boolean, created: Long?, tracks: List<TrackInfo>, packets: (List<Int>) -> List<MediaPacket>): ByteArray {
        val file = File(dir, name)
        Kotmpeg.createMuxer(file, ContainerFormat.MP4) {
            this.fragmented = fragmented
            creationTimeMillis = created
        }.use { muxer ->
            val ids = tracks.map { muxer.addTrack(it) }
            muxer.start()
            for (packet in packets(ids)) muxer.writePacket(packet)
        }
        return file.readBytes()
    }

    /**
     * Un vídeo de una sola imagen sin duración declarada dura lo que dice su `frameRate`, en los dos
     * muxers. Los dos estimaban siempre 30 fps, y el KDoc del plano prometía «los mismos valores» que el
     * fragmentado mientras los dos pasaban por alto `frameRate`: una imagen a 25 fps duraba 33 ms.
     */
    @Test
    fun `a lone frame lasts what its frame rate says in both mp4 muxers`() {
        val video = Mp4ReadFixtures.video { frameRate = 25.0 }
        for ((name, fragmented) in containers) {
            val bytes = write("imagen-$name.mp4", fragmented, null, listOf(video)) { ids ->
                listOf(MediaPacket(ids[0], Mp4ReadFixtures.nal(0), 0L, isKeyFrame = true))
            }
            val file = File(dir, "imagen-$name.mp4")
            val packet = Mp4ReadFixtures.readAll(file).single()
            assertEquals(40_000L, packet.durationUs, "$name: una imagen a 25 fps dura 40 ms")
            assertEquals(40L, movieOrMediaDuration(bytes, mvhd(bytes)), "$name: duración del mvhd")
        }
    }

    /**
     * Con una fecha de creación que no cabe en 32 bits —a partir de febrero de 2040, o antes con una
     * `creationTimeMillis` futura—, `mvhd`, `tkhd` y `mdhd` pasan a la versión 1 y la fecha se escribe
     * entera. La versión dependía solo de la duración, y la fecha se truncaba: 2050 se leía como 1913.
     * En el fragmentado, además, las duraciones que se parchean tras cada fragmento caen en los campos
     * de 64 bits de esa versión.
     */
    @Test
    fun `a creation date after 2040 is written whole in version 1 headers`() {
        val created2050 = 2_524_608_000_000L
        val expected = created2050 / 1000 + 2_082_844_800L
        val video = Mp4ReadFixtures.video()
        for ((name, fragmented) in containers) {
            val bytes = write("2050-$name.mp4", fragmented, created2050, listOf(video)) { ids ->
                List(30) { i -> MediaPacket(ids[0], Mp4ReadFixtures.nal(i), i * 33_333L, isKeyFrame = i % 10 == 0) }
            }
            for ((box, at) in listOf("mvhd" to mvhd(bytes), "tkhd" to tkhd(bytes, 0), "mdhd" to mdhd(bytes, 0))) {
                assertEquals(1, version(bytes, at), "$name: $box tiene que ser de la versión 1")
                assertEquals(expected, creationTime(bytes, at), "$name: fecha de creación del $box")
            }
            assertEquals(1000L, movieOrMediaDuration(bytes, mvhd(bytes)), "$name: duración del mvhd")
            assertEquals(1000L, tkhdDuration(bytes, 0), "$name: duración del tkhd")
            val read = Kotmpeg.openDemuxer(File(dir, "2050-$name.mp4")).use { it.durationUs }
            assertTrue(abs(read - 1_000_000L) < 1_000L, "$name: duración leída, $read µs")
        }
        val bytes2020 = write("2020.mp4", false, 1_600_000_000_000L, listOf(video)) { ids ->
            listOf(MediaPacket(ids[0], Mp4ReadFixtures.nal(0), 0L, isKeyFrame = true))
        }
        assertEquals(0, version(bytes2020, mvhd(bytes2020)), "una fecha que cabe sigue en la versión 0")
    }

    /**
     * Las cajas de cabecera de cada pista salen con los mismos bytes en los dos muxers: `tkhd` salvo su
     * duración, que el fragmentado parchea, y `hdlr`, `vmhd`/`smhd` y `dinf`.
     */
    @Test
    fun `both mp4 muxers write the same track header boxes`() {
        val tracks = listOf(
            Mp4ReadFixtures.video { name = "cámara"; language = "spa"; rotationDegrees = 90 },
            TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2); default = false },
        )
        val files = containers.map { (name, fragmented) ->
            write("cabeceras-$name.mp4", fragmented, 1_700_000_000_000L, tracks) { ids ->
                List(10) { i -> MediaPacket(ids[0], Mp4ReadFixtures.nal(i), i * 33_333L, isKeyFrame = i == 0) } +
                    List(10) { i -> MediaPacket(ids[1], ByteArray(8), i * 21_333L, isKeyFrame = true) }
            }
        }
        val (plain, fragmented) = files
        for (track in tracks.indices) {
            fun boxes(b: ByteArray): List<ByteArray> {
                val trak = trak(b, track)
                val tkhd = tkhd(b, track)
                val header = b.copyOfRange(tkhd, tkhd + u32(b, tkhd).toInt()).also { it.fill(0, 28, 32) }
                val media = if (tracks[track] is TrackInfo.Video) "vmhd" else "smhd"
                return listOf(header) + listOf(
                    within(b, trak, "mdia", "hdlr"),
                    within(b, trak, "mdia", "minf", media),
                    within(b, trak, "mdia", "minf", "dinf"),
                ).map { b.copyOfRange(it, it + u32(b, it).toInt()) }
            }
            val expected = boxes(plain)
            val actual = boxes(fragmented)
            for (i in expected.indices) assertContentEquals(expected[i], actual[i], "pista $track, caja $i")
        }
    }
}
