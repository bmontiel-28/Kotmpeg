package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.codecconfig.OpusConfig
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Los registros de configuración de códec que acaban dentro de un MP4: `avcC`, `hvcC`, el
 * AudioSpecificConfig de AAC y la `OpusHead`.
 *
 * Cada caso es un registro que la librería construía o leía mal **sin ningún error**: un `hvcC` sin
 * VPS que ningún decodificador abre, un `avcC` de perfil High al que le faltan los campos que la
 * norma exige, un AAC de 6.1 que se leía como 11 canales, un HE-AACv2 con señalización compatible
 * que se leía como mono a la frecuencia del núcleo. Las entradas se construyen bit a bit aquí
 * mismo, con [Bits], para que cada test diga exactamente qué campos lleva.
 */
class Mp4WriteCodecConfigTest {

    @TempDir
    lateinit var dir: File

    /** Escritor de bits MSB-first, con Exp-Golomb, para fabricar ASC y SPS campo a campo. */
    private class Bits {
        private val bits = StringBuilder()

        fun put(value: Long, count: Int): Bits {
            for (i in count - 1 downTo 0) bits.append(if ((value ushr i) and 1L == 1L) '1' else '0')
            return this
        }

        fun put(value: Int, count: Int): Bits = put(value.toLong(), count)

        fun ue(value: Int): Bits {
            val code = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(code)
            return put(0, length - 1).put(code, length)
        }

        fun bytes(): ByteArray {
            while (bits.length % 8 != 0) bits.append('0')
            return ByteArray(bits.length / 8) { i -> bits.substring(i * 8, i * 8 + 8).toInt(2).toByte() }
        }
    }

    /** ASC mínimo de un AAC-LC a 48 kHz con el `channelConfiguration` dado y nada detrás. */
    private fun lcAsc(channelConfiguration: Int): ByteArray =
        Bits().put(AacConfig.AOT_AAC_LC, 5).put(3, 4).put(channelConfiguration, 4).put(0, 3).bytes()

    /**
     * **`channelConfiguration` no es un número de canales.** ISO/IEC 14496-3 lo define como un
     * índice: 7 es 7.1, y desde la enmienda 4 los valores 11 a 14 son 6.1, 7.1 con surround
     * trasero, 22.2 y 7.1 con altura (7, 8, 24 y 8 canales). Se devolvían 11, 12, 13 y 14. El 0
     * dice que los canales van en un `program_config_element`, y como aquí no se leen se devuelve
     * 0, «desconocido», igual que en los valores reservados.
     */
    @Test
    fun `the channel configuration is mapped with the table of the standard`() {
        val expected = mapOf(
            0 to 0, 1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 5, 6 to 6, 7 to 8,
            8 to 0, 9 to 0, 10 to 0, 11 to 7, 12 to 8, 13 to 24, 14 to 8, 15 to 0,
        )
        for ((configuration, channels) in expected) {
            val parsed = AacConfig.parse(lcAsc(configuration))
            assertEquals(channels, parsed.channelCount, "channelConfiguration $configuration")
            assertEquals(channels, parsed.coreChannelCount, "channelConfiguration $configuration (núcleo)")
        }
    }

    /**
     * ASC de HE-AAC con **señalización implícita compatible hacia atrás**: el objeto es AAC-LC, para
     * que un decodificador sin SBR lo reproduzca a la frecuencia del núcleo, y detrás del
     * `GASpecificConfig` va la extensión `0x2B7` (SBR, con su frecuencia) y, opcionalmente, la
     * `0x548` (PS).
     */
    private fun implicitHeAsc(coreChannels: Int, sbr: Boolean, ps: Boolean?): ByteArray {
        val bits = Bits().put(AacConfig.AOT_AAC_LC, 5).put(6, 4).put(coreChannels, 4).put(0, 3)
        bits.put(0x2B7, 11).put(AacConfig.AOT_SBR, 5).put(if (sbr) 1 else 0, 1)
        if (sbr) bits.put(3, 4)
        if (ps != null) bits.put(0x548, 11).put(if (ps) 1 else 0, 1)
        return bits.bytes()
    }

    /**
     * **Un HE-AACv2 con señalización implícita se lee como lo que suena: 48 kHz estéreo.** Se
     * ignoraba la extensión y salía con la frecuencia y los canales del núcleo, 24 kHz mono, igual
     * que el HE-AAC explícito antes de [HeAacConfigTest]. Se trata como ese: el objeto que se
     * devuelve es el del perfil efectivo, y el núcleo queda en `coreSampleRate`/`coreChannelCount`.
     */
    @Test
    fun `backward compatible sbr and ps signalling is honoured`() {
        val heV2 = AacConfig.parse(implicitHeAsc(coreChannels = 1, sbr = true, ps = true))
        assertEquals(AacConfig.AOT_PS, heV2.audioObjectType)
        assertEquals(48_000, heV2.sampleRate)
        assertEquals(2, heV2.channelCount)
        assertEquals(24_000, heV2.coreSampleRate)
        assertEquals(1, heV2.coreChannelCount)

        val he = AacConfig.parse(implicitHeAsc(coreChannels = 2, sbr = true, ps = null))
        assertEquals(AacConfig.AOT_SBR, he.audioObjectType)
        assertEquals(48_000, he.sampleRate)
        assertEquals(2, he.channelCount)
        assertEquals(24_000, he.coreSampleRate)

        val noSbr = AacConfig.parse(implicitHeAsc(coreChannels = 2, sbr = false, ps = null))
        assertEquals(AacConfig.AOT_AAC_LC, noSbr.audioObjectType, "sbrPresentFlag = 0 no es HE-AAC")
        assertEquals(24_000, noSbr.sampleRate)
        assertEquals(24_000, noSbr.coreSampleRate)
    }

    /**
     * Y el síntoma en un archivo: el demuxer MP4 toma del ASC la frecuencia y los canales, así que
     * una grabación HE-AACv2 de 48 kHz estéreo se releía como 24 kHz mono, y un remux lo dejaba
     * escrito así en el `SampleEntry` del destino.
     */
    @Test
    fun `an implicitly signalled he aac v2 track reads back at its output rate and channels`() {
        val asc = implicitHeAsc(coreChannels = 1, sbr = true, ps = true)
        val file = File(dir, "he-v2-implicito.mp4")
        Kotmpeg.createMuxer(file, ContainerFormat.MP4).use { muxer ->
            val id = muxer.addTrack(TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = asc })
            muxer.start()
            for (i in 0 until 10) muxer.writePacket(MediaPacket(id, ByteArray(32), i * 42_666L, isKeyFrame = true))
        }
        val track = Kotmpeg.openDemuxer(file).use { it.tracks.single() as TrackInfo.Audio }
        assertEquals(48_000 to 2, track.sampleRate to track.channelCount)
    }

    /** Lo que no cabe en el campo de 5 bits del objeto: 31 es el escape a 6 bits más, no un objeto. */
    @Test
    fun `building an asc rejects the escape object type`() {
        assertFailsWith<IllegalArgumentException> { AacConfig.build(48_000, 2, audioObjectType = 31) }
        assertEquals(30, AacConfig.parse(AacConfig.build(48_000, 2, audioObjectType = 30)).audioObjectType)
    }

    /**
     * RFC 7845: con la familia de mapeo 0 la tabla **debe** omitirse. Se escribía detrás de la
     * cabecera, y salía una `OpusHead` más larga de lo que su familia permite.
     */
    @Test
    fun `an opus head of mapping family 0 does not take a mapping table`() {
        assertFailsWith<IllegalArgumentException> {
            OpusConfig.buildOpusHead(2, mappingFamily = 0, channelMapping = byteArrayOf(1, 1, 0, 1))
        }
        assertEquals(19, OpusConfig.buildOpusHead(2).size)
    }

    /** SPS de H.264 con los campos hasta las profundidades de bits; el resto da igual al `avcC`. */
    private fun h264Sps(profileIdc: Int, chromaFormatIdc: Int, bitDepthLuma: Int, bitDepthChroma: Int): ByteArray {
        val bits = Bits().put(0x67, 8).put(profileIdc, 8).put(0, 8).put(0x28, 8).ue(0)
        bits.ue(chromaFormatIdc)
        if (chromaFormatIdc == 3) bits.put(0, 1)
        bits.ue(bitDepthLuma - 8).ue(bitDepthChroma - 8).put(0, 1).put(0, 1).ue(0).ue(0).put(1, 1)
        return bits.bytes()
    }

    private val pps = byteArrayOf(0x68, 0xEB.toByte(), 0xE3.toByte(), 0xCB.toByte(), 0x22, 0xC0.toByte())

    /** Los cuatro bytes del final de un `avcC` de perfil High, como los define ISO/IEC 14496-15. */
    private fun highExtension(chroma: Int, luma: Int, chromaDepth: Int) = byteArrayOf(
        (0xFC or chroma).toByte(), (0xF8 or (luma - 8)).toByte(), (0xF8 or (chromaDepth - 8)).toByte(), 0,
    )

    /**
     * **Un `avcC` de perfil High lleva cuatro bytes más.** ISO/IEC 14496-15 exige con
     * `profile_idc` 100, 110, 122 y 144 el formato de croma, las dos profundidades de bits y el
     * número de SPS de extensión, y no se escribían. MediaCodec da perfil High en casi cualquier
     * móvil, así que era el caso normal. Los valores salen del propio SPS; si no se puede leer,
     * los de un High corriente, 4:2:0 a 8 bits.
     */
    @Test
    fun `a high profile avcC carries the chroma format and bit depths of its sps`() {
        val high = NalUnits.buildAvcC(listOf(h264Sps(100, 1, 8, 8)), listOf(pps))
        assertContentEquals(highExtension(1, 8, 8), high.copyOfRange(high.size - 4, high.size))

        val high422 = NalUnits.buildAvcC(listOf(h264Sps(122, 2, 10, 10)), listOf(pps))
        assertContentEquals(highExtension(2, 10, 10), high422.copyOfRange(high422.size - 4, high422.size))

        val high444 = NalUnits.buildAvcC(listOf(h264Sps(244, 3, 12, 12)), listOf(pps))
        assertContentEquals(highExtension(3, 12, 12), high444.copyOfRange(high444.size - 4, high444.size))

        val truncated = byteArrayOf(0x67, 100, 0, 0x1F)
        val fallback = NalUnits.buildAvcC(listOf(truncated), listOf(pps))
        assertContentEquals(highExtension(1, 8, 8), fallback.copyOfRange(fallback.size - 4, fallback.size))

        val mainSps = h264Sps(77, 1, 8, 8)
        val main = NalUnits.buildAvcC(listOf(mainSps), listOf(pps))
        assertEquals(6 + 2 + mainSps.size + 1 + 2 + pps.size, main.size, "Main no lleva la extensión")

        val (sps, ppsBack) = NalUnits.parseAvcC(high)
        assertContentEquals(h264Sps(100, 1, 8, 8), sps.single())
        assertContentEquals(pps, ppsBack.single())
    }

    /**
     * La prueba de fuego del registro: con el SPS y el PPS que da x264 en perfil High, el `avcC`
     * que se construye aquí es byte a byte el que escribe FFmpeg en su propio MP4. Se omite sin
     * FFmpeg con libx264.
     */
    @Test
    fun `the avcC of a real high profile stream matches the one ffmpeg writes`() {
        val mp4 = File(dir, "x264-high.mp4")
        val code = runCatching {
            val p = ProcessBuilder(
                "ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:duration=1:rate=10",
                "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", mp4.absolutePath,
            ).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor(120, TimeUnit.SECONDS)
            p.exitValue()
        }.getOrDefault(1)
        assumeTrue(code == 0 && mp4.length() > 0, "sin FFmpeg con libx264")

        val bytes = mp4.readBytes()
        val at = (0 until bytes.size - 4).first { String(bytes, it, 4, Charsets.US_ASCII) == "avcC" }
        val size = ((bytes[at - 4].toInt() and 0xFF) shl 24) or ((bytes[at - 3].toInt() and 0xFF) shl 16) or
            ((bytes[at - 2].toInt() and 0xFF) shl 8) or (bytes[at - 1].toInt() and 0xFF)
        val ffmpegAvcC = bytes.copyOfRange(at + 4, at - 4 + size)
        assertEquals(100, ffmpegAvcC[1].toInt() and 0xFF, "x264 no dio perfil High")
        val (sps, ppsList) = NalUnits.parseAvcC(ffmpegAvcC)
        assertContentEquals(ffmpegAvcC, NalUnits.buildAvcC(sps, ppsList))
    }

    /**
     * SPS de HEVC mínimo pero parseable: sin subcapas, Main 4:2:0 a 8 bits, 64x48. Los ceros del
     * `profile_tier_level` no llevan bytes de prevención: aquí solo se construye el `hvcC`, no se
     * decodifica nada.
     */
    private fun hevcSps(): ByteArray = Bits()
        .put(0x42, 8).put(0x01, 8)
        .put(0, 4).put(0, 3).put(1, 1)
        .put(0, 2).put(0, 1).put(1, 5).put(0x60000000L, 32).put(0x900000000000L, 48).put(93, 8)
        .ue(0).ue(1).ue(64).ue(48).put(0, 1).ue(0).ue(0).put(1, 1)
        .bytes()

    private val hevcVps = byteArrayOf(0x40, 0x01, 0x0C, 0x01, 0xFF.toByte(), 0xFF.toByte())
    private val hevcPps = byteArrayOf(0x44, 0x01, 0xC1.toByte(), 0x72, 0xB4.toByte(), 0x62, 0x40)

    /**
     * **Un `hvcC` sin VPS no se construye.** Se escribían los arrays con `array_completeness = 1`
     * —todos los parameter sets están aquí— y faltaba el VPS: `hvc1` no admite parameter sets en
     * banda, así que el archivo salía indecodificable. `buildAvcC` ya exigía SPS y PPS.
     */
    @Test
    fun `an hvcC without a vps is rejected`() {
        val sps = hevcSps()
        val error = assertFailsWith<IllegalArgumentException> {
            NalUnits.buildHvcC(emptyList(), listOf(sps), listOf(hevcPps))
        }
        assertEquals(true, error.message?.contains("VPS"), error.message)
        assertFailsWith<IllegalArgumentException> { NalUnits.buildHvcC(listOf(hevcVps), emptyList(), listOf(hevcPps)) }
        assertFailsWith<IllegalArgumentException> { NalUnits.buildHvcC(listOf(hevcVps), listOf(sps), emptyList()) }

        val hvcC = NalUnits.buildHvcC(listOf(hevcVps), listOf(sps), listOf(hevcPps))
        val sets = NalUnits.parseHvcC(hvcC)
        assertContentEquals(hevcVps, sets.getValue(NalUnits.H265_VPS).single())
        assertContentEquals(sps, sets.getValue(NalUnits.H265_SPS).single())
        assertContentEquals(hevcPps, sets.getValue(NalUnits.H265_PPS).single())
    }

    /**
     * Las longitudes de los parameter sets y el número de NAL de cada array son campos de 16 bits.
     * Lo que no cabía se truncaba en silencio y el registro quedaba desalineado: el lector tomaba
     * una longitud equivocada y leía como parameter set lo que venía detrás.
     */
    @Test
    fun `parameter sets that do not fit their 16 bit fields are rejected`() {
        val huge = ByteArray(0x10000).also { it[0] = 0x67; it[1] = 77 }
        assertFailsWith<IllegalArgumentException> { NalUnits.buildAvcC(listOf(huge), listOf(pps)) }
        assertFailsWith<IllegalArgumentException> { NalUnits.buildAvcC(listOf(h264Sps(77, 1, 8, 8)), listOf(huge)) }

        val hugePps = ByteArray(0x10000).also { it[0] = 0x44; it[1] = 0x01 }
        assertFailsWith<IllegalArgumentException> {
            NalUnits.buildHvcC(listOf(hevcVps), listOf(hevcSps()), listOf(hugePps))
        }
        val tooMany = List(0x10000) { hevcPps }
        assertFailsWith<IllegalArgumentException> {
            NalUnits.buildHvcC(listOf(hevcVps), listOf(hevcSps()), tooMany)
        }
    }

    /** La limpieza de `splitLengthPrefixed` no cambia lo que devuelve, ni lo que rechaza. */
    @Test
    fun `splitting a length prefixed sample keeps its behaviour`() {
        val nals = listOf(byteArrayOf(0x65, 1, 2, 3), byteArrayOf(0x41), ByteArray(0))
        val joined = NalUnits.joinLengthPrefixed(nals)
        val split = NalUnits.splitLengthPrefixed(joined + byteArrayOf(0, 0))
        assertEquals(nals.size, split.size)
        for (i in nals.indices) assertContentEquals(nals[i], split[i])
        assertContentEquals(NalUnits.joinAnnexB(nals), NalUnits.lengthPrefixedToAnnexB(joined))
        assertFailsWith<IllegalArgumentException> { NalUnits.splitLengthPrefixed(byteArrayOf(0, 0, 0, 9, 1)) }
        assertFailsWith<IllegalArgumentException> { NalUnits.lengthPrefixedToAnnexB(byteArrayOf(0, 0, 0, 9, 1)) }
    }
}
