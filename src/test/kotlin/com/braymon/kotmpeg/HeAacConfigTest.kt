package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **El ASC de HE-AAC describe el núcleo del bitstream, no la salida** — y eso vale para las dos
 * dimensiones, la frecuencia y los canales.
 *
 * Es un mismo error de fondo por partida doble. Con SBR la frecuencia del núcleo es la mitad de
 * la real; con PS el núcleo es mono aunque la salida sea estéreo. Kotmpeg resolvía la primera
 * dentro del ASC pero se quedaba con los canales del núcleo, y con la frecuencia del núcleo
 * cuando quien la declaraba era el contenedor MKV.
 *
 * Los ASC de este archivo no son inventados: se capturaron del codificador
 * `c2.android.aac.encoder` de un Xiaomi 2406APNFAG con Android 16, configurado a 48 kHz estéreo.
 * Van literales para que el test ejercite exactamente los bytes que produce un dispositivo real.
 */
class HeAacConfigTest {

    @TempDir
    lateinit var dir: File

    private companion object {
        /** HE (SBR) a 48 kHz estéreo: núcleo a 24 kHz, `channelConfiguration` = 2. */
        val ASC_HE = byteArrayOf(0x2B, 0x11, 0x88.toByte(), 0x00)

        /** HE-v2 (SBR+PS) a 48 kHz estéreo: núcleo a 24 kHz **mono**, extensión a 48 kHz. */
        val ASC_HE_V2 = byteArrayOf(0xEB.toByte(), 0x09, 0x88.toByte(), 0x00)

        fun run(vararg cmd: String): Pair<String, Int> {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(120, TimeUnit.SECONDS)
            return out to p.exitValue()
        }
    }

    /** Lo que produce `AudioEncoder.trackInfo()`: canales y tasa de la config, ASC del códec. */
    private fun heTrack(asc: ByteArray) = TrackInfo.Audio(
        codec = AudioCodec.AAC, sampleRate = 48_000, channelCount = 2, codecPrivate = asc,
    )

    private fun writeAudio(target: File, format: ContainerFormat, asc: ByteArray) {
        MkvKotlin.createMuxer(target, format).use { muxer ->
            val id = muxer.addTrack(heTrack(asc))
            muxer.start()
            for (i in 0 until 20) {
                muxer.writePacket(
                    MediaPacket(
                        id, ByteArray(64) { it.toByte() },
                        ptsUs = i * 21_333L, isKeyFrame = true,
                    ),
                )
            }
        }
    }

    private fun readTrack(file: File): TrackInfo.Audio =
        MkvKotlin.openDemuxer(file).use { it.tracks.first() as TrackInfo.Audio }

    /**
     * **El ASC declara lo que codifica el núcleo; `parse` tiene que devolver lo que sale.**
     *
     * Con PS el núcleo es mono —`channelConfiguration` = 1— y el estéreo se reconstruye al
     * decodificar con la información paramétrica lateral. Devolver ese 1 hacía que una grabación
     * estéreo en HE-v2 se releyera como mono.
     */
    @Test
    fun `the parsed asc reports the decoded channels and rate, not the core ones`() {
        val he = AacConfig.parse(ASC_HE)
        assertEquals(AacConfig.AOT_SBR, he.audioObjectType)
        assertEquals(48_000, he.sampleRate, "SBR: la frecuencia real es la de extensión")
        assertEquals(24_000, he.coreSampleRate, "y la del núcleo, la mitad")
        assertEquals(2, he.channelCount)
        assertEquals(2, he.coreChannelCount, "HE sin PS no toca los canales")

        val heV2 = AacConfig.parse(ASC_HE_V2)
        assertEquals(AacConfig.AOT_PS, heV2.audioObjectType)
        assertEquals(48_000, heV2.sampleRate)
        assertEquals(24_000, heV2.coreSampleRate)
        assertEquals(2, heV2.channelCount, "PS: la salida es estéreo aunque el núcleo sea mono")
        assertEquals(1, heV2.coreChannelCount, "y el núcleo del bitstream sigue siendo mono")

        val lc = AacConfig.parse(AacConfig.build(48_000, 2))
        assertEquals(AacConfig.AOT_AAC_LC, lc.audioObjectType)
        assertEquals(lc.sampleRate, lc.coreSampleRate)
        assertEquals(lc.channelCount, lc.coreChannelCount)
    }

    /**
     * Una grabación estéreo en HE-v2 se relee como estéreo **desde los dos contenedores**.
     *
     * Contra el árbol anterior el MP4 devolvía `channelCount = 1`: `parseMp4a` pisaba con el dato
     * del ASC el del `SampleEntry`, que sí llevaba los canales de salida. El MKV acertaba porque
     * su elemento `Channels` no se pisa, así que los dos contenedores discrepaban sobre
     * exactamente el mismo contenido.
     */
    @Test
    fun `a stereo he aac recording reads back as stereo from both containers`() {
        for ((name, asc) in listOf("he" to ASC_HE, "he-v2" to ASC_HE_V2)) {
            val mp4 = File(dir, "$name.mp4")
            writeAudio(mp4, ContainerFormat.MP4, asc)
            val fromMp4 = readTrack(mp4)
            assertEquals(2, fromMp4.channelCount, "$name: el MP4 no devuelve estéreo")
            assertEquals(48_000, fromMp4.sampleRate, "$name: el MP4 devuelve la tasa del núcleo")

            val mkv = File(dir, "$name.mkv")
            writeAudio(mkv, ContainerFormat.MKV, asc)
            val fromMkv = readTrack(mkv)
            assertEquals(2, fromMkv.channelCount, "$name: el MKV no devuelve estéreo")
            assertEquals(48_000, fromMkv.sampleRate, "$name: el MKV devuelve la tasa del núcleo")
        }
    }

    /**
     * Y el dato no se degrada al convertir, que es donde el error se hacía **permanente**: se
     * graba a MP4, se remuxea a MKV para editar, y el MKV heredaba el `Channels = 1`. A partir de
     * ahí ya no hay forma de recuperarlo, porque el ASC sigue diciendo 1 y el contenedor ha
     * perdido el 2 que sí tenía.
     */
    @Test
    fun `remuxing a he v2 recording does not lose the channel count`() {
        val mp4 = File(dir, "origen.mp4")
        writeAudio(mp4, ContainerFormat.MP4, ASC_HE_V2)
        val mkv = File(dir, "convertido.mkv")
        MkvKotlin.remux(mp4, mkv)

        val track = readTrack(mkv)
        assertEquals(2, track.channelCount, "el remux perdió el estéreo")
        assertEquals(48_000, track.sampleRate)
        assertTrue(track.codecPrivate.contentEquals(ASC_HE_V2), "el ASC debe viajar intacto")
    }

    /**
     * **Un MKV de HE-AAC ajeno se lee a su frecuencia real, no a la del núcleo.**
     *
     * No se fabrica a mano: lo escribe FFmpeg, que es de donde viene el caso. Para una pista
     * HE-AAC emite `SamplingFrequency = 24000` (el núcleo) y `OutputSamplingFrequency = 48000`
     * (la real). Antes el segundo caía en el `else` del bucle de `Audio` y se saltaba en
     * silencio, así que la pista se leía a la mitad de su frecuencia — y ese valor acaba en
     * `Demuxer.tracks`, en `MediaFormat.createAudioFormat` y en el `SampleEntry` de un remux.
     *
     * Se omite si FFmpeg no está instalado o si su compilación no puede producir HE-AAC (el
     * codificador `aac` nativo no lo hace; hace falta `aac_mf` o `libfdk_aac`).
     */
    @Test
    fun `a foreign mkv that declares an output sampling frequency is read at that rate`() {
        val source = File(dir, "he-ajeno.mkv")
        assumeTrue(makeHeAacMkv(source), "sin FFmpeg capaz de producir HE-AAC")
        assumeTrue(
            declaresOutputSamplingFrequency(source),
            "esta compilación de FFmpeg no emitió OutputSamplingFrequency",
        )

        val track = readTrack(source)
        assertEquals(
            48_000, track.sampleRate,
            "se leyó la tasa del núcleo (24 000) en vez de la de salida",
        )
        assertEquals(2, track.channelCount)
    }

    /**
     * Y esa frecuencia sobrevive al remux **a MKV**, que es donde el error se hacía permanente.
     *
     * La dirección importa. Al convertir a MP4 el dato se recupera solo, porque `parseMp4a`
     * vuelve a sacar la frecuencia del ASC y ahí la extensión sí está: el error quedaba tapado.
     * Nuestro MKV, en cambio, escribe un único `SamplingFrequency` con lo que se leyó, y no
     * emite `OutputSamplingFrequency` —limitación documentada—, así que una tasa mal leída se
     * escribía como buena y ya no había de dónde recuperarla.
     */
    @Test
    fun `remuxing a foreign he aac mkv to mkv keeps its real sample rate`() {
        val source = File(dir, "he-ajeno-remux.mkv")
        assumeTrue(makeHeAacMkv(source), "sin FFmpeg capaz de producir HE-AAC")
        assumeTrue(
            declaresOutputSamplingFrequency(source),
            "esta compilación de FFmpeg no emitió OutputSamplingFrequency",
        )

        val out = File(dir, "he-convertido.mkv")
        MkvKotlin.remux(source, out)
        assertEquals(48_000, readTrack(out).sampleRate, "el remux heredó la tasa del núcleo")
        assertTrue(
            !declaresOutputSamplingFrequency(out),
            "nuestro muxer no emite OutputSamplingFrequency: por eso el valor escrito " +
                "tiene que ser ya el de salida",
        )
    }

    /**
     * Genera un MKV con una pista HE-AAC estéreo a 48 kHz; false si esta máquina no puede.
     *
     * Diez segundos y no uno: con un solo segundo la pareja de bytes `78 B5` no llega a salir
     * por casualidad dentro del audio comprimido, y [declaresOutputSamplingFrequency] parecía
     * correcta aunque barriera el archivo entero. Con diez sí sale, así que la duración es
     * parte de lo que estos tests comprueban.
     */
    private fun makeHeAacMkv(target: File): Boolean {
        val (_, code) = runCatching {
            run(
                "ffmpeg", "-y", "-v", "error",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=10",
                "-ac", "2", "-c:a", "aac_mf", "-profile:a", "28", "-b:a", "48k",
                target.absolutePath,
            )
        }.getOrDefault("" to 1)
        return code == 0 && target.length() > 0
    }

    /**
     * ¿El archivo lleva de verdad el elemento que este test necesita? (id EBML `0x78B5`).
     *
     * Solo se busca en la cabecera, es decir en el tramo anterior al primer `Cluster`, que es
     * donde vive `Tracks` y el único sitio donde el elemento puede estar. Dentro de un cluster
     * la pareja `78 B5` aparece por casualidad en los datos comprimidos —con un segundo de
     * audio no llega a salir, pero con diez ya hay dos apariciones falsas—, y la aserción
     * negativa del test de remux, que afirma que el archivo de salida **no** lleva el elemento,
     * se convertiría en un fallo que no significa nada.
     */
    private fun declaresOutputSamplingFrequency(file: File): Boolean {
        val bytes = file.readBytes()
        var end = bytes.size
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == 0x1F.toByte() && bytes[i + 1] == 0x43.toByte() &&
                bytes[i + 2] == 0xB6.toByte() && bytes[i + 3] == 0x75.toByte()
            ) {
                end = i
                break
            }
        }
        for (i in 0 until end - 1) {
            if (bytes[i] == 0x78.toByte() && bytes[i + 1] == 0xB5.toByte()) return true
        }
        return false
    }
}
