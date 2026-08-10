package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.Mp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Endurecimiento del motor de contenedores: lo que tiene que seguir siendo cierto pase lo que
 * pase con el archivo, el disco o el orden de las llamadas.
 *
 * Es la tanda más variada del proyecto —inicio rápido que falla a media reescritura, descriptores
 * que se quedan abiertos, salida que coincide con la entrada, marcas de tiempo negativas del
 * `priming` de AAC, desfases que no caben en un bloque de Matroska— y toda ella salió de fallos
 * reales, no de casos imaginados.
 *
 * Dos cosas relacionadas **no** se cubren aquí, y conviene tenerlo escrito para que nadie las dé
 * por cubiertas:
 *
 *  - **Que un codificador real emita de verdad la configuración de HE-AAC** que aquí se parsea.
 *    Eso exige un códec de hardware, que es justo lo que este proyecto no tiene: lo que sí se
 *    puede medir en la JVM —que el ASC de SBR y PS se lea y se escriba bien en los dos
 *    contenedores— está en [HeAacConfigTest].
 *  - **El ciclo de vida de quien alimenta al muxer.** Arrancar y detener a la vez, liberar un
 *    códec con una lectura en vuelo, unir los hilos productores antes de cerrar: son fallos
 *    reales, pero de la capa que integra la librería, no del contenedor. Aquí se cubre que el
 *    muxer responda bien a lo que le llegue, incluido detenerse dos veces.
 */
class ContainerHardeningTest {

    @TempDir
    lateinit var dir: File

    private fun avcC() = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    private fun videoTrack() = TrackInfo.Video(
        codec = VideoCodec.H264, width = 320, height = 240, frameRate = 30.0,
        codecPrivate = avcC(),
    )

    private fun audioTrack() = TrackInfo.Audio(
        codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2,
        codecPrivate = AacConfig.build(48000, 2),
    )

    private fun payload(i: Int): ByteArray =
        NalUnits.joinLengthPrefixed(listOf(ByteArray(24 + (i % 5) * 4) { (i + it).toByte() }))

    /** Escribe un MP4 plano de [frames] fotogramas a 30 fps. */
    private fun writeMp4(target: File, frames: Int, fastStart: Boolean = false) {
        Mp4Muxer(target, fastStart = fastStart).use { muxer ->
            val id = muxer.addTrack(videoTrack())
            muxer.start()
            for (i in 0 until frames) {
                muxer.writePacket(
                    MediaPacket(id, payload(i), ptsUs = i * 33_333L, isKeyFrame = i % 15 == 0),
                )
            }
        }
    }

    /**
     * En la ruta `fastStart` el `moov` no se escribía nunca sobre el archivo original: se
     * construía en memoria y solo llegaba a disco a través de la reescritura. Si esa
     * reescritura fallaba —lo más probable, quedarse sin espacio, porque el modo necesita una
     * segunda copia completa del archivo—, quedaba un `ftyp + mdat`: todos los datos dentro y
     * ningún índice con el que leerlos.
     *
     * Aquí se provoca el fallo ocupando el nombre del temporal con un **directorio**, que no se
     * puede abrir para escribir ni borrar con `delete()`.
     */
    @Test
    fun `a failed faststart rewrite leaves a file that still opens and returns every packet`() {
        val out = File(dir, "grabacion.mp4")
        val blocker = File(dir, "grabacion.mp4.faststart.tmp")
        assertTrue(blocker.mkdirs(), "no se pudo preparar el bloqueo del temporal")

        val muxer = Mp4Muxer(out, fastStart = true)
        val id = muxer.addTrack(videoTrack())
        muxer.start()
        for (i in 0 until 40) {
            muxer.writePacket(MediaPacket(id, payload(i), ptsUs = i * 33_333L, isKeyFrame = i % 15 == 0))
        }
        val error = assertFailsWith<Throwable> { muxer.stop() }
        assertTrue(
            error.message.orEmpty().contains("inicio rápido"),
            "el error debería explicar que el archivo sigue siendo válido: ${error.message}",
        )

        var packets = 0
        MkvKotlin.openDemuxer(out).use { demuxer ->
            assertEquals(1, demuxer.tracks.size)
            while (demuxer.readPacket() != null) packets++
        }
        assertEquals(40, packets, "el archivo tiene que conservar todos los paquetes")
    }

    /** El camino feliz sigue produciendo un archivo con inicio rápido y sin dejar restos. */
    @Test
    fun `a successful faststart rewrite leaves no temporary files behind`() {
        val out = File(dir, "rapido.mp4")
        writeMp4(out, frames = 30, fastStart = true)

        val head = out.readBytes()
        val ftyp = String(head, 4, 4, Charsets.US_ASCII)
        assertEquals("ftyp", ftyp)
        val moovAt = String(head, 0, minOf(head.size, 4096), Charsets.ISO_8859_1).indexOf("moov")
        val mdatAt = String(head, 0, minOf(head.size, 4096), Charsets.ISO_8859_1).indexOf("mdat")
        assertTrue(moovAt in 1 until mdatAt, "el moov debe ir delante del mdat: moov=$moovAt mdat=$mdatAt")
        assertTrue(
            dir.listFiles()!!.none { it.name.contains(".faststart.") },
            "quedaron restos: ${dir.listFiles()!!.map { it.name }}",
        )
    }

    /**
     * `concat` abría **todas** las entradas antes del `try` que las cierra, así que si una
     * posterior lanzaba, las anteriores quedaban abiertas hasta la siguiente recolección.
     *
     * En Windows un descriptor abierto impide borrar el archivo, y eso es lo que se mide aquí
     * sin llamar a `System.gc()`: si `delete()` devuelve false, la fuga es real.
     */
    @Test
    fun `a concat that fails on a later input does not leak the ones already opened`() {
        val good = File(dir, "bueno.mp4")
        writeMp4(good, frames = 10)
        val notAContainer = File(dir, "basura.mp4").also { it.writeBytes(ByteArray(4096) { 0x7A }) }

        assertFailsWith<IllegalArgumentException> {
            MkvKotlin.concat(listOf(good, notAContainer), File(dir, "unido.mkv"))
        }
        assertTrue(good.delete(), "la primera entrada quedó abierta tras fallar la segunda")
    }

    /**
     * El muxer trunca el destino en su propio constructor, antes de `addTrack` y de `start()`.
     * Con la salida apuntando a una de las entradas se leía un archivo que se estaba
     * reescribiendo por debajo, y el resultado dependía de que el escritor no adelantara al
     * lector.
     */
    @Test
    fun `remux and concat refuse to write over one of their own inputs`() {
        val source = File(dir, "origen.mp4")
        writeMp4(source, frames = 12)
        val sizeBefore = source.length()

        assertFailsWith<IllegalArgumentException> { MkvKotlin.remux(source, source) }
        assertEquals(sizeBefore, source.length(), "la entrada no puede haberse tocado")

        val indirect = File(dir, "." + File.separator + "origen.mp4")
        assertFailsWith<IllegalArgumentException> { MkvKotlin.remux(source, indirect) }
        assertEquals(sizeBefore, source.length())

        val other = File(dir, "otro.mp4")
        writeMp4(other, frames = 12)
        assertFailsWith<IllegalArgumentException> { MkvKotlin.concat(listOf(other, source), source) }
        assertEquals(sizeBefore, source.length())
    }

    /**
     * `TrackInfo` es `data class` con un `ByteArray` entre sus propiedades, así que el `equals`
     * generado comparaba la **referencia** del array: dos pistas idénticas no salían iguales.
     * Es un tipo del modelo público, usado como clave lógica en `Remuxer.concat` y expuesto en
     * `Demuxer.tracks`.
     */
    @Test
    fun `two identical tracks compare equal even with distinct codecPrivate arrays`() {
        val a = TrackInfo.Video(codec = VideoCodec.H264, width = 1920, height = 1080, codecPrivate = avcC())
        val b = TrackInfo.Video(codec = VideoCodec.H264, width = 1920, height = 1080, codecPrivate = avcC())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(setOf(a), setOf(a, b), "deberían deduplicarse en un Set")

        val audioA = audioTrack()
        val audioB = audioTrack()
        assertEquals(audioA, audioB)
        assertEquals(audioA.hashCode(), audioB.hashCode())

        val other = TrackInfo.Video(
            codec = VideoCodec.H264, width = 1920, height = 1080,
            codecPrivate = avcC().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() },
        )
        assertNotEquals(a, other)
        assertNotEquals(a, a.withId(7))
    }

    /**
     * Un pts anterior a cero es legítimo y frecuente: es como se expresa el `priming` de un
     * codificador AAC, y así lo entrega el lector de cualquier MP4 escrito por FFmpeg. Lo que
     * fallaba era el redondeo —la división de Long trunca hacia cero, no hacia abajo— y el
     * índice de cues, que es un entero **sin signo** y reventaba dentro de `stop()` con el
     * archivo ya sin SeekHead, sin duración y sin tamaño de segmento.
     */
    @Test
    fun `an audio only mkv survives the negative pts of aac priming`() {
        val out = File(dir, "priming.mkv")
        MkvMuxer(out).use { muxer ->
            val id = muxer.addTrack(audioTrack())
            muxer.start()
            muxer.writePacket(MediaPacket(id, payload(0), ptsUs = -21_333L, isKeyFrame = true))
            for (i in 1..40) {
                muxer.writePacket(
                    MediaPacket(id, payload(i), ptsUs = -21_333L + i * 21_333L, isKeyFrame = true),
                )
            }
        }

        val first = MkvKotlin.openDemuxer(out).use { it.readPacket() }
        assertEquals(-21_000L, first!!.ptsUs, "el redondeo del pts negativo tiene que ir hacia abajo")
    }

    /**
     * El desfase de un bloque respecto a su cluster va en 16 bits **con signo**. Pasarse de ahí
     * se truncaba en silencio y daba un archivo que se abre pero suena descolocado.
     */
    @Test
    fun `a timestamp too far from its cluster fails with a message instead of truncating`() {
        val out = File(dir, "lejos.mkv")
        val muxer = MkvMuxer(out)
        val id = muxer.addTrack(audioTrack())
        muxer.start()
        val error = assertFailsWith<IllegalArgumentException> {
            muxer.writePacket(MediaPacket(id, payload(0), ptsUs = -60_000_000L, isKeyFrame = true))
        }
        assertTrue(
            error.message.orEmpty().contains("SimpleBlock"),
            "el mensaje debería nombrar el campo que se desborda: ${error.message}",
        )
        muxer.stop()
    }

    /**
     * Una pista de audio con un solo paquete sin duración declarada recibía **1 segundo**. La
     * duración real de un frame AAC a 48 kHz es de ~21 ms, y es lo que ya calculaba bien el
     * muxer fragmentado para el caso equivalente.
     */
    @Test
    fun `a single sample audio track gets a frame of duration and not one second`() {
        val out = File(dir, "una-muestra.mp4")
        Mp4Muxer(out).use { muxer ->
            val id = muxer.addTrack(audioTrack())
            muxer.start()
            muxer.writePacket(MediaPacket(id, payload(0), ptsUs = 0, isKeyFrame = true))
        }

        val durationUs = MkvKotlin.openDemuxer(out).use { it.durationUs }
        assertTrue(
            durationUs in 20_000..23_000,
            "la pista debería durar un frame de AAC, no $durationUs us",
        )
    }

    /**
     * Las tablas de frecuencias eran el estado global que hacía cumplir una validación.
     *
     * Que el tipo ya no sea un array lo vigila `PublicApiTest` contra `public-api.txt`, que es
     * donde corresponde: una comprobación de tipo aquí sería siempre cierta —el tipo declarado
     * es el que es— y volver a `IntArray` no la haría fallar, haría que no compilase.
     */
    @Test
    fun `the public sample rate tables keep their documented contents`() {
        assertEquals(13, AacConfig.SAMPLE_RATES.size)
        assertEquals(48000, AacConfig.SAMPLE_RATES[3])
        assertEquals(3, AacConfig.SAMPLE_RATES.indexOf(48000))
    }

    /** El índice del fMP4 declara ordinales de 4 bytes, no de 1: uno por fragmento no cabía. */
    @Test
    fun `the fragmented index declares wide entry fields`() {
        val out = File(dir, "frag.mp4")
        MkvKotlin.createMuxer(out, ContainerFormat.MP4, mp4Fragmented = true).use { muxer ->
            val id = muxer.addTrack(videoTrack())
            muxer.start()
            for (i in 0 until 60) {
                muxer.writePacket(
                    MediaPacket(id, payload(i), ptsUs = i * 33_333L, isKeyFrame = i % 15 == 0),
                )
            }
        }
        val bytes = out.readBytes()
        val at = String(bytes, Charsets.ISO_8859_1).lastIndexOf("tfra")
        assertTrue(at > 0, "no se escribió el tfra")
        val lengths = (0 until 4).fold(0L) { acc, k -> (acc shl 8) or (bytes[at + 12 + k].toLong() and 0xFF) }
        assertEquals(0x3FL, lengths, "los tres ordinales deben declararse de 4 bytes")

        var packets = 0
        MkvKotlin.openDemuxer(out).use { demuxer -> while (demuxer.readPacket() != null) packets++ }
        assertEquals(60, packets)
    }
}
