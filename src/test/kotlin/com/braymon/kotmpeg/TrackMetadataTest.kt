package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.codecconfig.OpusConfig
import com.braymon.kotmpeg.ebml.EbmlReader
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.mkv.MkvDemuxer
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **Los metadatos de `TrackEntry` e `Info` que un análisis forense encontró ausentes o
 * imprecisos** en archivos reales producidos por esta librería.
 *
 * Ninguno corrompía nada —el archivo decodificaba entero y sin un solo error—, y por eso vale la
 * pena dejar escrito qué se rompía en la práctica, que es lo que un test de "el archivo es válido"
 * jamás habría detectado:
 *
 *  - **`FlagDefault` no se escribía nunca.** Su valor por omisión en la especificación es 1, así
 *    que las tres pistas de audio se declaraban predeterminadas a la vez y cada reproductor elegía
 *    una distinta: la misma grabación sonaba a mezcla, a micrófono o a audio del sistema según el
 *    programa con que se abriera.
 *  - **`DefaultDuration` se calculaba en microsegundos.** 1/60 s son 16 666,67 µs, así que el
 *    truncamiento daba 16 666 000 ns y ffprobe leía 60,0024 fps. Unos 4 ms de deriva por minuto en
 *    la línea de tiempo de un editor.
 *  - **`DateUTC` no se escribía.** La fecha de grabación solo sobrevivía en el nombre del archivo,
 *    que es justo lo que se pierde al renombrar o reimportar.
 *  - **`CodecDelay` solo se escribía para Opus.** Compartía condicional con el `SeekPreRoll`, que
 *    sí es propio de Opus, así que un `codecDelayUs` puesto en una pista AAC se aceptaba y se
 *    descartaba en silencio: el audio quedaba por detrás del vídeo los ~21 ms que tarda en
 *    arrancar un AAC-LC a 48 kHz. El demuxer, en cambio, siempre supo leerlo para cualquier
 *    códec, así que la asimetría estaba solo en la escritura.
 *
 * La comprobación se hace **sobre los bytes del contenedor**, no sobre el modelo: el fallo estaba
 * en lo que se escribía, así que releerlo con nuestro propio demuxer y darlo por bueno sería
 * comprobar que dos errores se cancelan. Las dos direcciones importan, y por eso hay tests de las
 * dos: que el elemento esté en el archivo, y que el demuxer lo devuelva.
 */
class TrackMetadataTest {

    @TempDir
    lateinit var dir: File

    private val avcC = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    private fun video(frameRate: Double = 60.0, default: Boolean = true) = TrackInfo.Video(
        codec = VideoCodec.H264, width = 320, height = 240,
        frameRate = frameRate, codecPrivate = avcC, default = default,
    )

    private fun audio(default: Boolean = true) = TrackInfo.Audio(
        codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 1,
        codecPrivate = AacConfig.build(48000, 1), default = default,
    )

    /**
     * Escribe un archivo mínimo pero completo: sin al menos un paquete por pista el muxer no
     * llega a cerrar clusters ni índices, y lo que se quiere medir son las cabeceras de un
     * archivo terminado.
     */
    private fun mux(vararg tracks: TrackInfo, dateUtcMillis: Long? = null): File {
        val file = File(dir, "salida-${tracks.size}-$dateUtcMillis.mkv")
        MkvMuxer(com.braymon.kotmpeg.io.SeekableOutput(file), dateUtcMillis = dateUtcMillis).use { muxer ->
            val ids = tracks.map { muxer.addTrack(it) }
            muxer.start()
            for ((i, id) in ids.withIndex()) {
                muxer.writePacket(
                    MediaPacket(id, ByteArray(16) { it.toByte() }, ptsUs = i * 1_000L, isKeyFrame = true),
                )
            }
            muxer.stop()
        }
        return file
    }

    /** Todos los valores del elemento [id] que aparecen dentro de `Tracks`, en orden de pista. */
    private fun trackValues(file: File, id: Long): List<Long> = scan(file, MatroskaIds.TRACKS, id)

    /** Todos los valores del elemento [id] que aparecen dentro de `Info`. */
    private fun infoValues(file: File, id: Long, signed: Boolean = false): List<Long> =
        scan(file, MatroskaIds.INFO, id, signed)

    /**
     * Recorre el nivel superior del `Segment` hasta [seccion] y devuelve los valores de [id] que
     * encuentre dentro, entrando un nivel en los `TrackEntry`.
     */
    private fun scan(file: File, seccion: Long, id: Long, signed: Boolean = false): List<Long> {
        val encontrados = ArrayList<Long>()
        SeekableInput(file).use { input ->
            val reader = EbmlReader(input)
            reader.skip(reader.readElement())
            val segment = reader.readElement()
            val fin = if (segment.size < 0) input.length else segment.dataEnd
            while (input.position < fin) {
                val el = reader.readElement()
                if (el.id != seccion) {
                    if (el.size < 0) break
                    reader.skip(el)
                    continue
                }
                while (input.position < el.dataEnd) {
                    val hijo = reader.readElement()
                    if (hijo.id == MatroskaIds.TRACK_ENTRY) {
                        while (input.position < hijo.dataEnd) {
                            val nieto = reader.readElement()
                            if (nieto.id == id) {
                                encontrados += if (signed) reader.readSInt(nieto) else reader.readUInt(nieto)
                            }
                            input.position = nieto.dataEnd
                        }
                    } else if (hijo.id == id) {
                        encontrados += if (signed) reader.readSInt(hijo) else reader.readUInt(hijo)
                    }
                    input.position = hijo.dataEnd
                }
                break
            }
        }
        return encontrados
    }

    @Test
    fun `FlagDefault is written only for the tracks that are not default`() {
        val file = mux(video(), audio(default = true), audio(default = false))
        assertEquals(
            listOf(0L), trackValues(file, MatroskaIds.FLAG_DEFAULT),
            "solo la pista marcada como no predeterminada debe llevar FlagDefault, y con valor 0",
        )
    }

    /**
     * El otro lado de la moneda, y el que mantiene los archivos existentes byte a byte iguales:
     * cuando todas las pistas son predeterminadas no se escribe nada, porque 1 es el valor que la
     * especificación asume cuando el elemento falta.
     */
    @Test
    fun `no FlagDefault is written when every track is default`() {
        val file = mux(video(), audio(), audio())
        assertTrue(
            trackValues(file, MatroskaIds.FLAG_DEFAULT).isEmpty(),
            "con todas las pistas predeterminadas el archivo no debe contener ningún FlagDefault",
        )
    }

    @Test
    fun `the demuxer reads FlagDefault back`() {
        val file = mux(video(), audio(default = true), audio(default = false))
        MkvDemuxer(SeekableInput(file)).use { demuxer ->
            val audios = demuxer.tracks.filterIsInstance<TrackInfo.Audio>()
            assertEquals(2, audios.size)
            assertTrue(audios[0].default, "la primera pista de audio es la predeterminada")
            assertFalse(audios[1].default, "la segunda no lo es, y el remux no debe perderlo")
        }
    }

    /**
     * Las cadencias que el microsegundo no puede representar. 25 fps entra exacto y sirve de
     * control: si cambiara, el fallo estaría en el redondeo y no en la unidad.
     */
    @Test
    fun `defaultDurationNs rounds instead of truncating`() {
        assertEquals(16_666_667L, video(frameRate = 60.0).defaultDurationNs)
        assertEquals(33_333_333L, video(frameRate = 30.0).defaultDurationNs)
        assertEquals(40_000_000L, video(frameRate = 25.0).defaultDurationNs)
        assertEquals(0L, video(frameRate = 0.0).defaultDurationNs)
    }

    @Test
    fun `DefaultDuration is written in nanoseconds with full precision`() {
        val file = mux(video(frameRate = 60.0))
        assertEquals(
            listOf(16_666_667L), trackValues(file, MatroskaIds.DEFAULT_DURATION),
            "16666000 anunciaba 60,0024 fps; el valor exacto de 1/60 s son 16666667 ns",
        )
    }

    /**
     * Y que ese valor sobreviva a la ida y vuelta: el demuxer deriva `frameRate` dividiendo
     * `1e9 / DefaultDuration`, así que un valor impreciso reaparece como una cadencia rara.
     */
    @Test
    fun `the frame rate survives a round trip at 60 fps`() {
        val file = mux(video(frameRate = 60.0))
        MkvDemuxer(SeekableInput(file)).use { demuxer ->
            val leido = demuxer.tracks.filterIsInstance<TrackInfo.Video>().single().frameRate
            assertTrue(
                kotlin.math.abs(leido - 60.0) < 0.001,
                "se releyó $leido fps en vez de 60",
            )
        }
    }

    @Test
    fun `DateUTC is written as nanoseconds from the 2001 epoch`() {
        val file = mux(video(), dateUtcMillis = 1_786_409_586_908L)
        val esperado = (1_786_409_586_908L - 978_307_200_000L) * 1_000_000L
        assertEquals(listOf(esperado), infoValues(file, MatroskaIds.DATE_UTC, signed = true))
    }

    /**
     * Una fecha anterior a 2001 es negativa, que es el único motivo por el que este elemento no
     * se puede escribir con `writeUInt`. Si el signo se codificara mal, este caso saldría como un
     * número enorme y positivo.
     */
    @Test
    fun `DateUTC encodes dates before its epoch as negative`() {
        val file = mux(video(), dateUtcMillis = 0L)
        val valor = infoValues(file, MatroskaIds.DATE_UTC, signed = true).single()
        assertEquals(-978_307_200_000L * 1_000_000L, valor)
        assertTrue(valor < 0, "1970 es anterior a la época de Matroska, así que debe ser negativo")
    }

    @Test
    fun `DateUTC is omitted when no date is given`() {
        val file = mux(video(), dateUtcMillis = null)
        assertTrue(
            infoValues(file, MatroskaIds.DATE_UTC, signed = true).isEmpty(),
            "sin fecha no debe escribirse el elemento",
        )
    }

    /** El cebado real de un AAC-LC a 48 kHz: 1024 muestras, que son 21 333 µs. */
    private fun aacWithDelay(delayUs: Long) = TrackInfo.Audio(
        codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2,
        codecDelayUs = delayUs, codecPrivate = AacConfig.build(48000, 2),
    )

    @Test
    fun `CodecDelay is written for AAC and not only for Opus`() {
        val file = mux(aacWithDelay(21_333))
        assertEquals(
            listOf(21_333_000L), trackValues(file, MatroskaIds.CODEC_DELAY),
            "el retardo de arranque de un AAC se aceptaba y se descartaba en silencio",
        )
    }

    /**
     * El `SeekPreRoll` de 80 ms **sí** es propio de Opus, y compartía condicional con el retardo.
     * Que no se cuele en una pista AAC es la otra mitad del arreglo.
     */
    @Test
    fun `SeekPreRoll stays out of non Opus tracks`() {
        val file = mux(aacWithDelay(21_333))
        assertTrue(
            trackValues(file, MatroskaIds.SEEK_PRE_ROLL).isEmpty(),
            "SeekPreRoll es de Opus y no debe aparecer en una pista AAC",
        )
    }

    @Test
    fun `no CodecDelay is written when the track declares no delay`() {
        val file = mux(aacWithDelay(0))
        assertTrue(
            trackValues(file, MatroskaIds.CODEC_DELAY).isEmpty(),
            "sin retardo declarado no debe escribirse el elemento",
        )
    }

    /**
     * Opus no cambia: su `OpusHead` describe el bitstream de verdad, así que su `preSkip` sigue
     * ganando al campo del modelo. Con el `preSkip` por defecto de 312 muestras a 48 kHz el
     * retardo son 6500 µs, y el 99 999 que se pasa aquí debe quedar ignorado.
     */
    @Test
    fun `Opus still takes its delay from the OpusHead and keeps SeekPreRoll`() {
        val opus = TrackInfo.Audio(
            codec = AudioCodec.OPUS, sampleRate = 48000, channelCount = 2,
            codecDelayUs = 99_999, codecPrivate = OpusConfig.buildOpusHead(channelCount = 2),
        )
        val file = mux(opus)
        assertEquals(listOf(6_500_000L), trackValues(file, MatroskaIds.CODEC_DELAY))
        assertEquals(listOf(80_000_000L), trackValues(file, MatroskaIds.SEEK_PRE_ROLL))
    }

    /** Y que el valor sobreviva a la relectura, que es lo que usa un `remux()`. */
    @Test
    fun `the demuxer reads back the AAC delay`() {
        val file = mux(aacWithDelay(21_333))
        MkvDemuxer(SeekableInput(file)).use { demuxer ->
            val audio = demuxer.tracks.filterIsInstance<TrackInfo.Audio>().single()
            assertEquals(21_333L, audio.codecDelayUs)
        }
    }
}
