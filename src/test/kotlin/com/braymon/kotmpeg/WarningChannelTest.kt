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
 * El canal de diagnóstico, desde donde de verdad se usa.
 *
 * `onWarning` existía en el demuxer pero **no se alcanzaba desde la fachada**, que es el único
 * camino que documenta el README: una pista descartada desaparecía en silencio absoluto y el
 * síntoma que llegaba era «el vídeo se abre pero no tiene audio», sin nada que lo explicase.
 *
 * Se comprueba además que una muestra ilegible no arrastre consigo a las pistas que sí se
 * pueden leer.
 */
class WarningChannelTest {

    @TempDir
    lateinit var dir: File

    private fun videoTrack() = TrackInfo.Video(
        codec = VideoCodec.H264, width = 320, height = 240, frameRate = 30.0,
        codecPrivate = NalUnits.buildAvcC(
            listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
            listOf(byteArrayOf(0x68, 0x11, 0x22)),
        ),
    )

    private fun audioTrack() = TrackInfo.Audio(
        codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2,
        codecPrivate = AacConfig.build(48000, 2),
    )

    /** Tamaños distintos por muestra, para que `stsz` use la tabla y no un tamaño constante. */
    private fun payload(i: Int): ByteArray {
        val nal = ByteArray(20 + (i % 7) * 4) { (i + it).toByte() }
        return NalUnits.joinLengthPrefixed(listOf(nal))
    }

    private fun indexOfLast(haystack: ByteArray, type: String): Int {
        val needle = type.toByteArray(Charsets.US_ASCII)
        var found = -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            found = i
        }
        return found
    }

    private fun putBe32(b: ByteArray, at: Int, v: Long) {
        for (k in 0 until 4) b[at + k] = ((v shr (8 * (3 - k))) and 0xFF).toByte()
    }

    /**
     * El `onWarning` del demuxer existía pero solo en el constructor primario, y el README usa
     * la fachada en sus tres ejemplos de lectura. El canal de diagnóstico era, en la práctica,
     * inalcanzable por el camino que la documentación enseña.
     */
    @Test
    fun `the facade forwards demuxer warnings for a track it cannot interpret`() {
        val file = File(dir, "roto.mp4")
        MkvKotlin.createMuxer(file, ContainerFormat.MP4).use { muxer ->
            val vid = muxer.addTrack(videoTrack())
            val aud = muxer.addTrack(audioTrack())
            muxer.start()
            repeat(12) { i ->
                muxer.writePacket(MediaPacket(vid, payload(i), i * 33_333L, i * 33_333L, true))
                muxer.writePacket(MediaPacket(aud, payload(i), i * 21_333L, i * 21_333L, true))
            }
        }
        val bytes = file.readBytes()
        val at = indexOfLast(bytes, "mp4a")
        assertTrue(at > 0, "no se encontró la sample entry de audio")
        "zzzz".toByteArray(Charsets.US_ASCII).copyInto(bytes, at)
        val broken = File(dir, "roto-patched.mp4").also { it.writeBytes(bytes) }

        val warnings = ArrayList<String>()
        MkvKotlin.openDemuxer(broken, onWarning = { warnings.add(it) }).use { demuxer ->
            assertTrue(
                demuxer.tracks.any { it is TrackInfo.Video },
                "el archivo debía degradarse a la pista de vídeo, no perderse entero",
            )
            assertTrue(demuxer.tracks.none { it is TrackInfo.Audio }, "el audio no debía sobrevivir")
        }
        assertTrue(
            warnings.isNotEmpty(),
            "la pista descartada no llegó al callback a través de MkvKotlin.openDemuxer",
        )
    }

    @Test
    fun `the facade still works without a warning callback`() {
        val file = File(dir, "sano.mp4")
        MkvKotlin.createMuxer(file, ContainerFormat.MP4).use { muxer ->
            val vid = muxer.addTrack(videoTrack())
            muxer.start()
            repeat(10) { i -> muxer.writePacket(MediaPacket(vid, payload(i), i * 33_333L, i * 33_333L, true)) }
        }
        var count = 0
        MkvKotlin.openDemuxer(file).use { demuxer ->
            assertEquals(1, demuxer.tracks.size)
            while (demuxer.readPacket() != null) count++
        }
        assertEquals(10, count)
    }

    @Test
    fun `the mkv demuxer also reports a track it had to drop`() {
        val file = File(dir, "roto.mkv")
        MkvKotlin.createMuxer(file, ContainerFormat.MKV).use { muxer ->
            val vid = muxer.addTrack(videoTrack())
            val aud = muxer.addTrack(audioTrack())
            muxer.start()
            repeat(12) { i ->
                muxer.writePacket(MediaPacket(vid, payload(i), i * 33_333L, i * 33_333L, true))
                muxer.writePacket(MediaPacket(aud, payload(i), i * 21_333L, i * 21_333L, true))
            }
        }
        val bytes = file.readBytes()
        val at = indexOfLast(bytes, "A_AAC")
        assertTrue(at > 0, "no se encontró el CodecID de audio")
        "A_ZZZ".toByteArray(Charsets.US_ASCII).copyInto(bytes, at)
        val broken = File(dir, "roto-patched.mkv").also { it.writeBytes(bytes) }

        val warnings = ArrayList<String>()
        MkvKotlin.openDemuxer(broken, onWarning = { warnings.add(it) }).use { demuxer ->
            assertTrue(demuxer.tracks.any { it is TrackInfo.Video }, "se perdió también el vídeo")
            while (demuxer.readPacket() != null) Unit
        }
        assertTrue(warnings.isNotEmpty(), "MkvDemuxer descartó la pista sin avisar")
        assertTrue(
            warnings.size <= 4,
            "el aviso se repite por bloque en vez de una vez por pista: ${warnings.size} avisos",
        )
    }

    /**
     * Una muestra cuyos bytes no están en el archivo se **salta**, en vez de terminar el stream
     * de todas las pistas. Con un archivo truncado daba igual —el daño está en la cola—, pero
     * con uno dañado en el medio se perdía todo lo que venía detrás y era legible.
     */

    /**
     * `remux` y `concat` abren demuxers por dentro, así que una conversión que pierda una pista
     * por el camino tenía el mismo problema que la lectura: no podía decirlo.
     */
    @Test
    fun `remux forwards the warnings of the input it reads`() {
        val broken = brokenAudioMp4("remux-in.mp4")
        val warnings = ArrayList<String>()
        val out = File(dir, "remux-out.mkv")
        val packets = MkvKotlin.remux(broken, out, onWarning = { warnings.add(it) })

        assertTrue(packets > 0, "el remux no copió nada")
        assertTrue(warnings.isNotEmpty(), "remux() no propagó el aviso de la pista descartada")
        MkvKotlin.openDemuxer(out).use { assertEquals(1, it.tracks.size) }
    }

    @Test
    fun `concat forwards the warnings of every input`() {
        val a = brokenAudioMp4("concat-a.mp4")
        val b = brokenAudioMp4("concat-b.mp4")
        val warnings = ArrayList<String>()
        MkvKotlin.concat(listOf(a, b), File(dir, "concat-out.mkv"), onWarning = { warnings.add(it) })
        assertTrue(warnings.size >= 2, "concat() solo avisó de ${warnings.size} de 2 entradas")
    }

    @Test
    fun `the secondary file constructor accepts a warning callback`() {
        val broken = brokenAudioMp4("directo.mp4")
        val warnings = ArrayList<String>()
        com.braymon.kotmpeg.mp4.Mp4Demuxer(broken, onWarning = { warnings.add(it) }).use { demuxer ->
            assertTrue(demuxer.tracks.any { it is TrackInfo.Video })
        }
        assertTrue(warnings.isNotEmpty(), "el constructor por File no propagó el aviso")
    }

    /** MP4 de dos pistas con el fourcc del `stsd` de audio corrompido: el audio no se puede leer. */
    private fun brokenAudioMp4(name: String): File {
        val src = File(dir, "src-$name")
        MkvKotlin.createMuxer(src, ContainerFormat.MP4).use { muxer ->
            val vid = muxer.addTrack(videoTrack())
            val aud = muxer.addTrack(audioTrack())
            muxer.start()
            repeat(12) { i ->
                muxer.writePacket(MediaPacket(vid, payload(i), i * 33_333L, i * 33_333L, true))
                muxer.writePacket(MediaPacket(aud, payload(i), i * 21_333L, i * 21_333L, true))
            }
        }
        val bytes = src.readBytes()
        val at = indexOfLast(bytes, "mp4a")
        assertTrue(at > 0, "no se encontró la sample entry de audio")
        "zzzz".toByteArray(Charsets.US_ASCII).copyInto(bytes, at)
        return File(dir, name).also { it.writeBytes(bytes) }
    }

    @Test
    fun `an unreadable video chunk does not discard the readable audio behind it`() {
        val file = File(dir, "medio.mp4")
        val perTrack = 24
        val vidId: Int
        val audId: Int
        MkvKotlin.createMuxer(file, ContainerFormat.MP4).use { muxer ->
            vidId = muxer.addTrack(videoTrack())
            audId = muxer.addTrack(audioTrack())
            muxer.start()
            repeat(perTrack) { i ->
                muxer.writePacket(MediaPacket(vidId, payload(i), i * 33_333L, i * 33_333L, i % 6 == 0))
                muxer.writePacket(MediaPacket(audId, payload(i), i * 21_333L, i * 21_333L, true))
            }
        }

        val bytes = file.readBytes()
        val stco = bytes.let { b ->
            val needle = "stco".toByteArray(Charsets.US_ASCII)
            (0..b.size - needle.size).firstOrNull { i -> needle.indices.all { b[i + it] == needle[it] } }
        }
        assertTrue(stco != null && stco > 0, "no se encontró la caja stco")
        putBe32(bytes, stco!! + 4 + 4 + 4, 0x7FFFFFF0L)
        val broken = File(dir, "medio-patched.mp4").also { it.writeBytes(bytes) }

        val read = ArrayList<MediaPacket>()
        val warnings = ArrayList<String>()
        MkvKotlin.openDemuxer(broken, onWarning = { warnings.add(it) }).use { demuxer ->
            while (true) read.add(demuxer.readPacket() ?: break)
        }

        val audio = read.count { it.trackId == audId }
        val video = read.count { it.trackId == vidId }
        assertEquals(perTrack, audio, "el audio legible se perdió por culpa de una muestra de vídeo")
        assertTrue(video < perTrack, "el chunk de vídeo dañado debería haberse saltado")
        assertTrue(warnings.isNotEmpty(), "saltarse muestras ilegibles debe avisar")
    }
}
