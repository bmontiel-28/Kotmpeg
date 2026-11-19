package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import com.braymon.kotmpeg.mp4.Mp4Muxer
import com.braymon.kotmpeg.mp4.SampleEntries
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Idioma, nombre y pista predeterminada sobreviven a MP4 en los dos sentidos.
 *
 * Los muxers MP4 escribían el `mdhd` siempre con `und` y el demuxer no leía ni el idioma, ni el
 * `udta`/`name` ni el bit `track_enabled` del `tkhd`. El resultado era que un MKV con audio en
 * dos idiomas pasado a MP4 y vuelta salía con las dos pistas en `und`, sin nombre y ambas
 * predeterminadas: cada reproductor elegía la que quisiera.
 */
class TrackLanguageRoundTripTest {

    @TempDir
    lateinit var dir: File

    private val avcC = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    private val tracks = listOf(
        TrackInfo.Video(codec = VideoCodec.H264, width = 320, height = 240) { codecPrivate = avcC; language = "jpn" },
        TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2) {
            codecPrivate = AacConfig.build(48000, 2)
            language = "spa"
            name = "Español"
            default = true
        },
        TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2) {
            codecPrivate = AacConfig.build(48000, 2)
            language = "eng"
            name = "Commentary"
            default = false
        },
    )

    private fun write(muxer: Muxer) {
        val ids = tracks.map { muxer.addTrack(it) }
        muxer.start()
        for (step in 0 until 5) {
            for (id in ids) {
                muxer.writePacket(MediaPacket(id, ByteArray(16) { step.toByte() }, ptsUs = step * 40_000L, isKeyFrame = true))
            }
        }
        muxer.stop()
    }

    private fun metadataOf(file: File): List<Triple<String, String?, Boolean?>> =
        Kotmpeg.openDemuxer(file).use { demuxer -> demuxer.tracks.map { Triple(it.language, it.name, it.default) } }

    /** La de vídeo no fija `default`: es la única de su tipo, así que el muxer la marca. */
    private val expected = tracks.map { Triple(it.language, it.name, it.default ?: true) }

    @Test
    fun `plain and fragmented mp4 keep language name and default flag`() {
        val plain = File(dir, "plano.mp4").also { write(Mp4Muxer(it)) }
        val fragmented = File(dir, "frag.mp4").also { write(FragmentedMp4Muxer(it)) }
        assertEquals(expected, metadataOf(plain))
        assertEquals(expected, metadataOf(fragmented))
    }

    @Test
    fun `an mkv to mp4 to mkv remux keeps the track metadata`() {
        val source = File(dir, "origen.mkv").also { write(MkvMuxer(it)) }
        val middle = File(dir, "medio.mp4")
        val back = File(dir, "vuelta.mkv")
        Kotmpeg.remux(source, middle)
        Kotmpeg.remux(middle, back)
        assertEquals(expected, metadataOf(back))
    }

    @Test
    fun `language codes are packed as iso 639-2 and malformed ones fall back to und`() {
        assertEquals(0x55C4, SampleEntries.packLanguage("und"))
        assertEquals("spa", SampleEntries.unpackLanguage(SampleEntries.packLanguage("SPA")))
        for (malformed in listOf("", "es", "español", "e1g", "en-US")) {
            assertEquals(0x55C4, SampleEntries.packLanguage(malformed), malformed)
        }
        assertEquals("und", SampleEntries.unpackLanguage(0), "un código de idioma Macintosh no es ISO")
    }

    /**
     * El idioma se normaliza al crear la pista, igual en los tres muxers: minúsculas si es un código
     * de tres letras, y `und` si no. Hasta la 4.1.0 MKV escribía `es` o `es-MX` tal cual en su
     * elemento `Language`, que es el de los códigos ISO 639-2, MP4 los escribía como `und`, y un
     * remux de MKV a MP4 los perdía sin aviso.
     */
    @Test
    fun `a language that is not a three letter code is und in every container`() {
        val cases = listOf("es" to "und", "es-MX" to "und", "" to "und", "zz9" to "und", "SPA" to "spa", " eng " to "eng")
        for ((given, normalized) in cases) {
            val track = TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2) {
                codecPrivate = AacConfig.build(48000, 2)
                language = given
            }
            assertEquals(normalized, track.language, "«$given» en el TrackInfo")
            assertEquals(normalized, track.copy { }.language, "«$given» tras copy")
            val outputs = listOf(
                File(dir, "idioma.mkv").let { it to MkvMuxer(it) },
                File(dir, "idioma.mp4").let { it to Mp4Muxer(it) },
                File(dir, "idioma-frag.mp4").let { it to FragmentedMp4Muxer(it) },
            )
            for ((file, muxer) in outputs) {
                muxer.use { m ->
                    val id = m.addTrack(track)
                    m.start()
                    m.writePacket(MediaPacket(id, ByteArray(16), ptsUs = 0, isKeyFrame = true))
                }
                val read = Kotmpeg.openDemuxer(file).use { it.tracks.single().language }
                assertEquals(normalized, read, "«$given» en ${file.name}")
            }
        }
    }

    /** Los tres contenedores, con el archivo que escribe cada uno en las pruebas de nombres. */
    private val nameOutputs: List<Pair<String, (File) -> Muxer>> = listOf(
        "nombre.mkv" to { file -> MkvMuxer(file) },
        "nombre.mp4" to { file -> Mp4Muxer(file) },
        "nombre-frag.mp4" to { file -> FragmentedMp4Muxer(file) },
    )

    /** Escribe en [file], con [muxer], una sola pista de audio llamada [given]. */
    private fun writeNamed(file: File, muxer: Muxer, given: String) {
        muxer.use { m ->
            val id = m.addTrack(
                TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2) {
                    codecPrivate = AacConfig.build(48000, 2)
                    name = given
                },
            )
            m.start()
            m.writePacket(MediaPacket(id, ByteArray(16), ptsUs = 0, isKeyFrame = true))
        }
    }

    /** El nombre que se lee de la única pista de [file] y los avisos que da el demuxer al abrirlo. */
    private fun readName(file: File): Pair<String?, List<String>> {
        val warnings = mutableListOf<String>()
        val name = Kotmpeg.openDemuxer(file) { warnings += it }.use { it.tracks.single().name }
        return name to warnings
    }

    /**
     * En MP4 y fMP4, de un nombre de más de 4096 bytes se leen los primeros, cortados en un carácter
     * UTF-8 completo, con un aviso que dice de qué pista es y cuánto ocupa. Hasta la 4.1.2 se
     * descartaba entero y sin aviso. Uno de 4096 justos se lee entero y sin aviso, y MKV los
     * conserva todos.
     */
    @Test
    fun `a track name over 4096 bytes is cut with a warning in mp4 and kept whole in mkv`() {
        val cases = listOf(
            "a".repeat(4096) to "a".repeat(4096),
            "a".repeat(4097) to "a".repeat(4096),
            "a".repeat(4095) + "é" to "a".repeat(4095),
            "a".repeat(4094) + "😀" to "a".repeat(4094),
            "ñ".repeat(3000) to "ñ".repeat(2048),
        )
        for ((given, cut) in cases) {
            val bytes = given.toByteArray(Charsets.UTF_8).size
            for ((fileName, muxer) in nameOutputs) {
                val file = File(dir, fileName)
                writeNamed(file, muxer(file), given)
                val (read, warnings) = readName(file)
                val label = "$fileName, nombre de $bytes bytes"
                if (file.extension == "mkv" || bytes <= 4096) {
                    assertEquals(given, read, label)
                    assertEquals(emptyList(), warnings, label)
                } else {
                    assertEquals(cut, read, label)
                    val warning = warnings.single()
                    assertTrue("pista 1" in warning && "$bytes bytes" in warning, "$label: $warning")
                }
            }
        }
    }

    /** El nombre largo tampoco se pierde al pasar de MKV a MP4: llega entero al archivo y se lee cortado. */
    @Test
    fun `a long track name survives an mkv to mp4 remux`() {
        val source = File(dir, "largo.mkv")
        val target = File(dir, "largo.mp4")
        writeNamed(source, MkvMuxer(source), "b".repeat(5000))
        Kotmpeg.remux(source, target)
        val (read, warnings) = readName(target)
        assertEquals("b".repeat(4096), read)
        assertTrue("5000 bytes" in warnings.single(), warnings.single())
    }

    /**
     * Los demás nombres que no vuelven igual son los que dice el KDoc de `TrackInfo.name`: MP4 y fMP4
     * leen hasta el primer carácter nulo y devuelven `null` para uno vacío o en blanco, y MKV quita
     * los espacios y nulos del final. Ninguno de esos casos avisa.
     */
    @Test
    fun `the other names that do not come back the same are the documented ones`() {
        val cases = listOf(
            Triple("a\u0000b", "a\u0000b", "a"),
            Triple("", "", null),
            Triple("   ", "", null),
            Triple("pista ", "pista", "pista "),
        )
        for ((given, inMkv, inMp4) in cases) {
            for ((fileName, muxer) in nameOutputs) {
                val file = File(dir, fileName)
                writeNamed(file, muxer(file), given)
                val (read, warnings) = readName(file)
                val label = "$fileName, «$given»"
                assertEquals(if (file.extension == "mkv") inMkv else inMp4, read, label)
                assertEquals(emptyList(), warnings, label)
            }
        }
    }
}
