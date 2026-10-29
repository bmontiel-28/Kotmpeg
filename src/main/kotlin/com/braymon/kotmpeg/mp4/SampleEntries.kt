package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.codecconfig.DefaultCodecPrivate
import com.braymon.kotmpeg.codecconfig.OpusConfig
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.HdrStaticInfo
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec

/**
 * Serialización compartida de sample entries `stsd` para los muxers MP4 plano y
 * fragmentado, incluidos los metadatos de color (`colr` nclx, `mdcv`, `clli`) y la matriz
 * de presentación del `tkhd`.
 */
internal object SampleEntries {

    /**
     * Frecuencia máxima representable en el campo `samplerate` de un SampleEntry de audio:
     * es punto fijo 16.16, así que la parte entera son 16 bits. MKV no tiene este límite —
     * su `SamplingFrequency` es un float.
     */
    internal const val MAX_SAMPLE_ENTRY_RATE: Long = 0xFFFF

    /** `und` empaquetado como lo guarda `mdhd`. */
    private const val LANGUAGE_UNDETERMINED: Int = 0x55C4

    /**
     * Código ISO 639-2/T empaquetado como lo guarda `mdhd`: tres letras minúsculas, cada una
     * menos 0x60, en 5 bits. Lo que no sea exactamente tres letras ASCII sale como `und`: un
     * código mal formado no puede viajar en ese campo sin corromper los vecinos.
     */
    internal fun packLanguage(code: String): Int {
        val normalized = code.trim().lowercase()
        if (normalized.length != 3 || normalized.any { it !in 'a'..'z' }) return LANGUAGE_UNDETERMINED
        return ((normalized[0].code - 0x60) shl 10) or
            ((normalized[1].code - 0x60) shl 5) or
            (normalized[2].code - 0x60)
    }

    /**
     * Inversa de [packLanguage]. Por debajo de 0x400 el campo lleva un código de idioma de
     * Macintosh (QuickTime), no ISO, y se trata como desconocido.
     */
    internal fun unpackLanguage(packed: Int): String {
        val value = packed and 0x7FFF
        if (value < 0x400) return "und"
        val letters = CharArray(3) { i -> (((value shr (10 - 5 * i)) and 0x1F) + 0x60).toChar() }
        return if (letters.all { it in 'a'..'z' }) String(letters) else "und"
    }

    /**
     * La entrada de muestra completa de [info], tal como va dentro del `stsd`.
     *
     * Los dos muxers MP4 la construyen al registrar la pista y no al escribir la cabecera, porque es
     * aquí donde se descubre una configuración que el MP4 no puede describir —un Opus de más de dos
     * canales sin tabla de mapeo, un AAC sin `codecPrivate` con un número de canales que el ASC no
     * expresa, un `codecPrivate` Opus que no es una `OpusHead`— y lanza [IllegalArgumentException].
     * En el plano, al cerrar, con todo el `mdat` escrito, ese error dejaba un archivo sin `moov`.
     */
    fun sampleEntry(info: TrackInfo): ByteArray {
        val b = BoxBuilder()
        when (info) {
            is TrackInfo.Video -> writeVisual(b, info)
            is TrackInfo.Audio -> writeAudio(b, info)
        }
        return b.toByteArray()
    }

    private fun writeVisual(b: BoxBuilder, info: TrackInfo.Video) {
        val config = requireNotNull(info.codecPrivate) { "la pista de vídeo requiere codecPrivate" }
        b.box(info.codec.mp4SampleEntry) {
            zeros(6); u16(1)                         // reservado + data_reference_index
            u16(0); u16(0)
            zeros(12)
            u16(info.width); u16(info.height)
            u32(0x00480000); u32(0x00480000)         // 72 dpi
            u32(0)
            u16(1)                                   // frame_count
            zeros(32)                                // compressorname
            u16(0x0018)                              // depth
            s16(-1)
            when (info.codec) {
                VideoCodec.H264 -> box("avcC") { bytes(config) }
                VideoCodec.H265 -> box("hvcC") { bytes(config) }
            }
            writePixelAspect(this, info)
            info.color?.let { color ->
                box("colr") {
                    fourcc("nclx")
                    u16(color.primaries); u16(color.transfer); u16(color.matrix)
                    u8(if (color.fullRange) 0x80 else 0)
                }
                color.hdr?.let { hdr ->
                    writeMasteringDisplay(this, hdr)
                    if (hdr.maxContentLightLevel > 0 || hdr.maxFrameAverageLightLevel > 0) {
                        box("clli") {
                            u16(hdr.maxContentLightLevel)
                            u16(hdr.maxFrameAverageLightLevel)
                        }
                    }
                }
            }
        }
    }

    /**
     * `pasp` con la proporción de píxel que lleva el tamaño codificado al de presentación,
     * `hSpacing:vSpacing = displayWidth·height : displayHeight·width` reducida; con píxeles
     * cuadrados no se escribe nada, como hace FFmpeg.
     *
     * Hasta ahora el anamórfico solo viajaba en el tamaño de presentación del `tkhd`, y Media3/
     * ExoPlayer toma la proporción del `pasp`: un MKV anamórfico pasado a MP4 se veía en Android con
     * píxeles cuadrados. Sale del mismo par que el `tkhd`, que es previo a la matriz, así que una
     * rotación no lo cambia y los dos dicen siempre lo mismo. Si la fracción reducida no cabe en 32
     * bits —dimensiones que tampoco caben en los campos de 16 bits de la entrada— se omite y queda
     * el `tkhd`.
     */
    private fun writePixelAspect(b: BoxBuilder, info: TrackInfo.Video) {
        var h = info.displayWidth.toLong() * info.height
        var v = info.displayHeight.toLong() * info.width
        var x = h
        var y = v
        while (y != 0L) { val r = x % y; x = y; y = r }
        h /= x
        v /= x
        if (h == v || h > 0xFFFFFFFFL || v > 0xFFFFFFFFL) return
        b.box("pasp") {
            u32(h)                                   // hSpacing
            u32(v)                                   // vSpacing
        }
    }

    /**
     * `mdcv` con el volumen de color del display de masterizado (SMPTE ST 2086), salvo que sus diez
     * campos cuantizados valgan cero.
     *
     * Ese caso no es un display, es la ausencia de uno: lo que el demuxer devuelve de un archivo
     * que solo trae `clli`, porque [HdrStaticInfo] lleva los dos juntos. Escribirlo convertía un
     * remux de ese archivo en uno con un `mdcv` a cero, un display sin primarias ni luminancia que
     * un reproductor puede tomar en serio.
     */
    private fun writeMasteringDisplay(b: BoxBuilder, hdr: HdrStaticInfo) {
        val chromaticities = doubleArrayOf(
            hdr.greenX, hdr.greenY, hdr.blueX, hdr.blueY, hdr.redX, hdr.redY, hdr.whiteX, hdr.whiteY,
        ).map { HdrStaticInfo.chromaticityUnits(it) }
        val maxLuminance = HdrStaticInfo.luminanceUnits(hdr.maxMasteringLuminance)
        val minLuminance = HdrStaticInfo.luminanceUnits(hdr.minMasteringLuminance)
        if (maxLuminance == 0L && minLuminance == 0L && chromaticities.all { it == 0L }) return
        b.box("mdcv") {
            for (c in chromaticities) u16(c.toInt())  // display_primaries G, B, R y white_point, x e y
            u32(maxLuminance)                        // max_display_mastering_luminance
            u32(minLuminance)                        // min_display_mastering_luminance
        }
    }

    /**
     * La entrada de muestra de audio. Su `samplerate` es la frecuencia de la pista, salvo en Opus, que
     * lleva siempre `MovieBoxes.OPUS_RATE`: su encapsulado en ISOBMFF lo exige, porque Opus se
     * decodifica a 48 kHz sea cual sea la frecuencia de entrada, y esa viaja en el `dOps`. Hasta la 4.0
     * un Opus a 16 kHz declaraba 16 000 en los dos sitios.
     */
    private fun writeAudio(b: BoxBuilder, info: TrackInfo.Audio) {
        val rate = if (info.codec == AudioCodec.OPUS) MovieBoxes.OPUS_RATE else info.sampleRate.toLong()
        b.box(info.codec.mp4SampleEntry) {
            zeros(6); u16(1)                         // reservado + data_reference_index
            u32(0); u32(0)
            u16(info.channelCount)
            u16(if (info.bitDepth > 0) info.bitDepth else 16)
            u16(0); u16(0)
            u32(rate.coerceAtMost(MAX_SAMPLE_ENTRY_RATE) shl 16)
            when (info.codec) {
                AudioCodec.AAC -> writeEsds(this, info)
                AudioCodec.OPUS -> box("dOps") {
                    val head = info.codecPrivate ?: DefaultCodecPrivate.forAudio(info)
                    bytes(OpusConfig.opusHeadToDops(head))
                }
            }
        }
    }

    /**
     * Bytes que ocupa una longitud de descriptor MPEG-4 (7 bits útiles por byte, con bit
     * de continuación en el más significativo).
     */
    private fun descriptorLengthSize(value: Int): Int {
        var size = 1
        var rest = value ushr 7
        while (rest > 0) { size++; rest = rest ushr 7 }
        return size
    }

    /** Escribe una longitud de descriptor MPEG-4 con el esquema de continuación de 7 bits. */
    private fun BoxBuilder.descriptorLength(value: Int) {
        val size = descriptorLengthSize(value)
        for (i in size - 1 downTo 0) {
            val chunk = (value ushr (7 * i)) and 0x7F
            u8(if (i > 0) chunk or 0x80 else chunk)
        }
    }

    private fun writeEsds(b: BoxBuilder, info: TrackInfo.Audio) {
        val asc = info.codecPrivate ?: DefaultCodecPrivate.forAudio(info)
        val dsiSize = 1 + descriptorLengthSize(asc.size) + asc.size
        val decoderConfigContent = 13 + dsiSize
        val decoderConfigSize = 1 + descriptorLengthSize(decoderConfigContent) + decoderConfigContent
        val slDescriptorSize = 3
        val esContent = 3 + decoderConfigSize + slDescriptorSize
        b.fullBox("esds", 0, 0) {
            u8(0x03); descriptorLength(esContent)
            u16(info.id); u8(0)
            u8(0x04); descriptorLength(decoderConfigContent)
            u8(0x40); u8(0x15)
            u24(0); u32(0); u32(0)
            u8(0x05); descriptorLength(asc.size); bytes(asc)
            u8(0x06); descriptorLength(1); u8(0x02)
        }
    }

    /**
     * La aplicación que escribió el archivo, en `udta` > `meta` > `ilst` > `©too`: lo que FFmpeg
     * escribe con su propio nombre y lo que `ffprobe` muestra como `encoder`. Va en el `moov` de los
     * dos muxers MP4; con [app] vacío no se escribe nada.
     *
     * El `meta` es una full box, como en ISO/IEC 14496-12, y su `hdlr` declara `mdir`, el
     * manejador de metadatos de iTunes que define esta estructura de `ilst`.
     */
    fun writeEncoderTag(parent: BoxBuilder, app: String) {
        if (app.isEmpty()) return
        parent.box("udta") {
            fullBox("meta", 0, 0) {
                fullBox("hdlr", 0, 0) {
                    u32(0)                               // pre_defined
                    fourcc("mdir")                       // handler_type
                    fourcc("appl"); u32(0); u32(0)       // reserved
                    u8(0)                                // name, vacío
                }
                box("ilst") {
                    box("\u00A9too") {
                        box("data") {
                            u32(1)                       // type: texto UTF-8
                            u32(0)                       // locale
                            bytes(app.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }
        }
    }

    /**
     * Escribe la matriz de presentación de 9 entradas de tkhd/mvhd (punto fijo 16.16 / 2.30). Sin
     * rotación es la identidad, la misma que escribe [writeIdentityMatrix].
     */
    fun writeDisplayMatrix(b: BoxBuilder, rotationDegrees: Int, width: Int, height: Int) {
        val one = 0x00010000L
        val negOne = 0xFFFF0000L
        when (rotationDegrees) {
            90 -> {
                b.u32(0); b.u32(one); b.u32(0)
                b.u32(negOne); b.u32(0); b.u32(0)
                b.u32(height.toLong() shl 16); b.u32(0); b.u32(0x40000000)
            }
            180 -> {
                b.u32(negOne); b.u32(0); b.u32(0)
                b.u32(0); b.u32(negOne); b.u32(0)
                b.u32(width.toLong() shl 16); b.u32(height.toLong() shl 16); b.u32(0x40000000)
            }
            270 -> {
                b.u32(0); b.u32(negOne); b.u32(0)
                b.u32(one); b.u32(0); b.u32(0)
                b.u32(0); b.u32(width.toLong() shl 16); b.u32(0x40000000)
            }
            else -> writeIdentityMatrix(b)
        }
    }

    /**
     * La matriz identidad, la de `mvhd` y la del `tkhd` de una pista sin rotación o de audio. Vive
     * aquí, junto a [writeDisplayMatrix], para que los dos muxers no lleven cada uno su copia.
     */
    fun writeIdentityMatrix(b: BoxBuilder) {
        b.u32(0x00010000); b.u32(0); b.u32(0)
        b.u32(0); b.u32(0x00010000); b.u32(0)
        b.u32(0); b.u32(0); b.u32(0x40000000)
    }
}
