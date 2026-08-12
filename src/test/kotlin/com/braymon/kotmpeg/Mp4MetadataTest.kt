package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import com.braymon.kotmpeg.mp4.Mp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Los metadatos que `TrackInfo` expone, `MkvMuxer` escribe y los dos muxers MP4 descartaban.**
 *
 * Los archivos que producían eran válidos —decodificaban enteros, con DTS monótonos y marcas
 * correctas—, así que ningún test de integridad los habría delatado. Lo que faltaba era
 * información *sobre* el contenido:
 *
 *  - **El nombre de pista** se aceptaba y se tiraba. Con tres pistas de audio, es lo único que
 *    permite distinguirlas al reproducir. El texto del `hdlr` no sirve: es el nombre del manejador
 *    y sale igual en todas.
 *  - **El cebado del codificador** (`codecDelayUs`) se ignoraba, así que el audio se reproducía
 *    adelantado respecto al vídeo. El `elst` que ya se escribía resolvía otra cosa —el desfase de
 *    arranque de la pista— y es fácil confundirlas.
 *  - **Cuál es la pista predeterminada** no se podía expresar: el `tkhd` salía con flags fijos a 3,
 *    así que las tres pistas de audio se anunciaban como reproducibles y cada reproductor elegía.
 *  - **La fecha** salía a cero en las tres cabeceras que la llevan.
 *  - **El fMP4 no declaraba su duración**: ni en `mvhd` (correcto en vivo) ni en `mehd` al cerrar,
 *    así que un reproductor tenía que recorrerse todos los fragmentos para saber cuánto duraba.
 *
 * Se comprueba **sobre las cajas del archivo**, recorriendo su estructura: el fallo estaba en lo
 * que se escribía, y releerlo con nuestro propio demuxer daría por bueno cualquier par de errores
 * que se cancelen.
 */
class Mp4MetadataTest {

    @TempDir
    lateinit var dir: File

    private companion object {
        /** Origen de tiempos de MP4: 1904-01-01 UTC, no 1970. */
        const val MP4_EPOCH_OFFSET_S = 2_082_844_800L
        const val FECHA_MILLIS = 1_786_409_586_908L

        /** Cebado real de un AAC-LC a 48 kHz: 1024 muestras. */
        const val PRIMING_US = 21_333L
        const val PRIMING_MUESTRAS = 1024L
    }

    private val avcC = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    private fun video(name: String? = null, default: Boolean = true) = TrackInfo.Video(
        codec = VideoCodec.H264, width = 320, height = 240, frameRate = 30.0,
        codecPrivate = avcC, name = name, default = default,
    )

    private fun audio(name: String? = null, default: Boolean = true, delayUs: Long = 0) =
        TrackInfo.Audio(
            codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2,
            codecPrivate = AacConfig.build(48000, 2), name = name, default = default,
            codecDelayUs = delayUs,
        )

    private fun escribir(muxer: com.braymon.kotmpeg.Muxer, tracks: List<TrackInfo>) {
        val ids = tracks.map { muxer.addTrack(it) }
        muxer.start()
        for (paso in 0 until 4) {
            for (id in ids) {
                muxer.writePacket(
                    MediaPacket(
                        id, ByteArray(32) { it.toByte() },
                        ptsUs = paso * 100_000L, isKeyFrame = true, durationUs = 100_000L,
                    ),
                )
            }
        }
        muxer.stop()
    }

    private fun mp4(vararg tracks: TrackInfo, fecha: Long? = FECHA_MILLIS): File {
        val file = File(dir, "plano-${tracks.size}-$fecha.mp4")
        val muxer = Mp4Muxer(file)
        muxer.creationTimeMillis = fecha
        escribir(muxer, tracks.toList())
        return file
    }

    private fun fmp4(vararg tracks: TrackInfo, fecha: Long? = FECHA_MILLIS): File {
        val file = File(dir, "frag-${tracks.size}-$fecha.mp4")
        val muxer = FragmentedMp4Muxer(file)
        muxer.creationTimeMillis = fecha
        escribir(muxer, tracks.toList())
        return file
    }

    /**
     * Cargas de todas las cajas que encajan en [ruta] (`"moov/trak/tkhd"`), sin la cabecera de
     * tamaño y tipo. Recorre el árbol de verdad en vez de buscar la firma de cuatro letras por el
     * archivo: esos cuatro bytes pueden aparecer dentro de cualquier carga binaria.
     *
     * Contempla la forma **largesize**, en la que el campo de tamaño vale 1 y el real va en 64
     * bits detrás del tipo. No es un caso raro que convenga cubrir por si acaso: es la que usa el
     * `mdat` del muxer plano, así que sin ella el recorrido se detiene en la primera caja y todas
     * las comprobaciones sobre un MP4 no fragmentado saldrían vacías.
     */
    private fun cajas(file: File, ruta: String): List<ByteArray> {
        val datos = file.readBytes()
        val partes = ruta.split("/")
        val salida = ArrayList<ByteArray>()

        fun u32en(at: Int): Long = ((datos[at].toLong() and 0xFF) shl 24) or
            ((datos[at + 1].toLong() and 0xFF) shl 16) or
            ((datos[at + 2].toLong() and 0xFF) shl 8) or (datos[at + 3].toLong() and 0xFF)

        fun recorrer(desde: Int, hasta: Int, nivel: Int) {
            var p = desde
            while (p + 8 <= hasta) {
                var size = u32en(p)
                var cabecera = 8
                if (size == 1L) {
                    size = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (datos[p + 8 + i].toLong() and 0xFF) }
                    cabecera = 16
                }
                if (size < cabecera || p + size > hasta) return
                val tipo = String(datos, p + 4, 4, Charsets.US_ASCII)
                if (tipo == partes[nivel]) {
                    if (nivel == partes.lastIndex) {
                        salida += datos.copyOfRange(p + cabecera, (p + size).toInt())
                    } else {
                        recorrer(p + cabecera, (p + size).toInt(), nivel + 1)
                    }
                }
                p += size.toInt()
            }
        }
        recorrer(0, datos.size, 0)
        return salida
    }

    private fun ByteArray.u32(at: Int): Long =
        ((this[at].toLong() and 0xFF) shl 24) or ((this[at + 1].toLong() and 0xFF) shl 16) or
            ((this[at + 2].toLong() and 0xFF) shl 8) or (this[at + 3].toLong() and 0xFF)

    private fun ByteArray.u64(at: Int): Long = (0 until 8).fold(0L) { acc, i ->
        (acc shl 8) or (this[at + i].toLong() and 0xFF)
    }

    /** Los 24 bits bajos de la primera palabra de una caja completa. */
    private fun ByteArray.flags(): Long = u32(0) and 0xFFFFFF

    @Test
    fun `the track name reaches the plain MP4`() {
        val file = mp4(video(name = "pantalla"), audio(name = "microfono"))
        val nombres = cajas(file, "moov/trak/udta/name").map { String(it, Charsets.UTF_8) }
        assertEquals(listOf("pantalla", "microfono"), nombres)
    }

    @Test
    fun `the track name reaches the fragmented MP4`() {
        val file = fmp4(video(name = "pantalla"), audio(name = "sistema"))
        val nombres = cajas(file, "moov/trak/udta/name").map { String(it, Charsets.UTF_8) }
        assertEquals(listOf("pantalla", "sistema"), nombres)
    }

    @Test
    fun `no name box is written when the track has no name`() {
        assertTrue(cajas(mp4(video()), "moov/trak/udta/name").isEmpty())
        assertTrue(cajas(fmp4(video()), "moov/trak/udta/name").isEmpty())
    }

    /**
     * El `media_time` va en ticks **del medio**, así que a 48 kHz los 21 333 µs son 1024 muestras.
     * Es lo que un reproductor lee como `initial_padding`.
     */
    @Test
    fun `the encoder priming becomes the media_time of the edit list`() {
        val file = mp4(audio(delayUs = PRIMING_US))
        val elst = cajas(file, "moov/trak/edts/elst").single()
        assertEquals(1L, elst.u32(4), "una sola entrada: la pista arranca en cero")
        assertEquals(PRIMING_MUESTRAS, elst.u32(12), "media_time debe saltar el cebado")
    }

    @Test
    fun `the fragmented MP4 also declares the priming`() {
        val file = fmp4(audio(delayUs = PRIMING_US))
        val elst = cajas(file, "moov/trak/edts/elst").single()
        assertEquals(PRIMING_MUESTRAS, elst.u32(12))
    }

    /** Sin cebado declarado, el comportamiento anterior no cambia: no aparece ninguna edición. */
    @Test
    fun `a track without priming keeps its edit list untouched`() {
        assertTrue(cajas(mp4(audio(delayUs = 0)), "moov/trak/edts/elst").isEmpty())
        assertTrue(cajas(fmp4(audio(delayUs = 0)), "moov/trak/edts/elst").isEmpty())
    }

    /**
     * MP4 no tiene un `FlagDefault` como Matroska; su equivalente es el bit `track_enabled` (0x1)
     * de los flags del `tkhd`. Se conserva `track_in_movie` (0x2) en los dos casos.
     */
    @Test
    fun `the tkhd flags express which track is default`() {
        val file = mp4(audio(default = true), audio(default = false))
        assertEquals(listOf(3L, 2L), cajas(file, "moov/trak/tkhd").map { it.flags() })
    }

    @Test
    fun `the fragmented MP4 expresses it too`() {
        val file = fmp4(audio(default = true), audio(default = false))
        assertEquals(listOf(3L, 2L), cajas(file, "moov/trak/tkhd").map { it.flags() })
    }

    @Test
    fun `the creation date reaches mvhd tkhd and mdhd`() {
        val esperado = FECHA_MILLIS / 1000 + MP4_EPOCH_OFFSET_S
        val file = mp4(video(), audio())
        assertEquals(esperado, cajas(file, "moov/mvhd").single().u32(4), "mvhd")
        assertTrue(cajas(file, "moov/trak/tkhd").all { it.u32(4) == esperado }, "tkhd")
        assertTrue(cajas(file, "moov/trak/mdia/mdhd").all { it.u32(4) == esperado }, "mdhd")
    }

    @Test
    fun `the fragmented MP4 carries the date as well`() {
        val esperado = FECHA_MILLIS / 1000 + MP4_EPOCH_OFFSET_S
        val file = fmp4(video(), audio())
        assertEquals(esperado, cajas(file, "moov/mvhd").single().u32(4))
        assertTrue(cajas(file, "moov/trak/tkhd").all { it.u32(4) == esperado })
    }

    /** Sin fecha, los campos vuelven a cero, que es lo que había antes de existir esto. */
    @Test
    fun `no date leaves the header fields at zero`() {
        val file = mp4(video(), fecha = null)
        assertEquals(0L, cajas(file, "moov/mvhd").single().u32(4))
        assertEquals(0L, cajas(file, "moov/trak/tkhd").single().u32(4))
    }

    /**
     * El `mehd` se reserva al abrir y se rellena al cerrar, cuando la duración ya se conoce. Es el
     * único punto en el que este muxer vuelve atrás a escribir, y el archivo es válido con o sin
     * ese parche.
     */
    @Test
    fun `the fragmented MP4 declares its total duration in mehd`() {
        val file = fmp4(video(), audio())
        val mehd = cajas(file, "moov/mvex/mehd").single()
        assertEquals(1L, mehd.u32(0) ushr 24, "version 1: hueco fijo de 8 bytes, siempre parcheable")
        assertEquals(400L, mehd.u64(4), "cuatro paquetes de 100 ms en la escala de 1000 del mvhd")
    }
}
