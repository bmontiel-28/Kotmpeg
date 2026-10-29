package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.OpusConfig
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ColorInfo
import com.braymon.kotmpeg.model.HdrStaticInfo
import com.braymon.kotmpeg.model.VideoCodec
import kotlin.math.roundToInt

/**
 * Lo que se lee de la entrada de muestra de un `stsd`: el códec, su configuración y los parámetros
 * que la acompañan. [pixelAspectH] y [pixelAspectV] son el `pasp` (separación horizontal y
 * vertical de los píxeles), 0 si no lo hay.
 */
internal class SampleDescription(
    val videoCodec: VideoCodec? = null,
    val audioCodec: AudioCodec? = null,
    val codecConfig: ByteArray? = null,
    val width: Int = 0,
    val height: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val codecDelayUs: Long = 0,
    val color: ColorInfo? = null,
    val pixelAspectH: Long = 0,
    val pixelAspectV: Long = 0,
) {
    /** La misma descripción con otro retardo del códec: el cebado que declara la lista de edición. */
    fun withCodecDelay(delayUs: Long): SampleDescription = SampleDescription(
        videoCodec, audioCodec, codecConfig, width, height, sampleRate, channels, delayUs, color,
        pixelAspectH, pixelAspectV,
    )

    /**
     * El ancho de presentación que da el `pasp` a una imagen de [codedWidth] píxeles, o null si no
     * hay `pasp`, si los píxeles son cuadrados o si el resultado no es un ancho que un `tkhd`
     * pudiera declarar. Se escala el ancho y se conserva el alto, que es como lo hacen FFmpeg y la
     * `DisplayUnit` de proporción de Matroska en `MkvDemuxer`.
     */
    fun pixelAspectWidth(codedWidth: Int): Int? {
        if (pixelAspectH <= 0 || pixelAspectV <= 0 || pixelAspectH == pixelAspectV || codedWidth <= 0) return null
        val scaled = (codedWidth.toLong() * pixelAspectH + pixelAspectV / 2) / pixelAspectV
        return scaled.toInt().takeIf { scaled in 1..MAX_PRESENTATION_SIZE }
    }

    private companion object {
        /** El mayor tamaño que cabe en la parte entera del 16.16 de un `tkhd`. */
        private const val MAX_PRESENTATION_SIZE = 0xFFFFL
    }
}

/**
 * Lector de la primera entrada de muestra de un `stsd`: `avc1`/`avc3`, `hvc1`/`hev1`, `mp4a` y
 * `Opus`. Cualquier otra es un códec no soportado y da null.
 *
 * Un metadato opcional que no se entiende —un `colr` con códigos fuera de rango, un `mdcv` con la
 * luminancia mínima por encima de la máxima— se pierde **solo él**, con aviso por [warn]. Hasta
 * ahora los `require` de [ColorInfo] y [HdrStaticInfo] lanzaban desde aquí y se llevaban por
 * delante la pista de vídeo entera, que se decodifica perfectamente sin ellos.
 */
internal class Mp4SampleEntryReader(private val boxes: Mp4BoxReader, private val warn: (String) -> Unit) {
    private val input get() = boxes.input

    /**
     * Se llama con la entrada posicionada justo tras el contador de entradas del `stsd`.
     * [stsdVersion] es la versión del `stsd`, que decide cómo se lee la de una entrada de audio
     * (ver [parseMp4a]); [trackId] solo sirve para los avisos.
     */
    fun read(end: Long, stsdVersion: Int, trackId: Int): SampleDescription? {
        val start = input.position
        val entry = boxes.readBoxHeader(end) ?: return null
        if (entry.dataEnd <= start || entry.dataEnd > end) return null
        return when (entry.type) {
            "avc1", "avc3" -> parseVisual(entry, VideoCodec.H264, "avcC", trackId)
            "hvc1", "hev1" -> parseVisual(entry, VideoCodec.H265, "hvcC", trackId)
            "mp4a" -> parseMp4a(entry, stsdVersion)
            "Opus" -> parseOpus(entry)
            else -> null
        }
    }

    /**
     * `VisualSampleEntry`: 6 bytes reservados y el índice de referencia de datos, 16 de
     * `pre_defined`/reservados, el ancho y el alto, y 50 más —resolución, `frame_count`,
     * `compressorname`, `depth` y `pre_defined`— antes de las cajas hijas.
     *
     * El `pasp` se guarda para que [Mp4Demuxer] lo use de respaldo del `tkhd` (ver
     * [SampleDescription.pixelAspectWidth]).
     */
    private fun parseVisual(entry: Mp4Box, codec: VideoCodec, configBoxType: String, trackId: Int): SampleDescription {
        input.position = entry.dataStart
        input.skip(6 + 2 + 2 + 2 + 12)
        val w = input.readUInt(2).toInt()
        val h = input.readUInt(2).toInt()
        input.skip(4 + 4 + 4 + 2 + 32 + 2 + 2)
        var config: ByteArray? = null
        var primaries = ColorInfo.UNSPECIFIED
        var transfer = ColorInfo.UNSPECIFIED
        var matrix = ColorInfo.UNSPECIFIED
        var fullRange = false
        var hasColr = false
        var mastering: DoubleArray? = null
        var maxCll = 0
        var maxFall = 0
        var aspectH = 0L
        var aspectV = 0L
        boxes.scanChildren(input.position, entry.dataEnd) { box ->
            input.position = box.dataStart
            when (box.type) {
                configBoxType -> config = input.readBytes((box.dataEnd - box.dataStart).toInt())
                "colr" -> {
                    val kind = String(input.readBytes(4), Charsets.US_ASCII)
                    if (kind == "nclx" || kind == "nclc") {
                        hasColr = true
                        primaries = input.readUInt(2).toInt()
                        transfer = input.readUInt(2).toInt()
                        matrix = input.readUInt(2).toInt()
                        if (kind == "nclx") fullRange = input.readByte() and 0x80 != 0
                    }
                }
                "mdcv" -> {
                    val v = DoubleArray(8) { (input.readUInt(2)) * 0.00002 }
                    val maxLum = (input.readInt32().toLong() and 0xFFFFFFFFL) * 0.0001
                    val minLum = (input.readInt32().toLong() and 0xFFFFFFFFL) * 0.0001
                    mastering = doubleArrayOf(v[4], v[5], v[0], v[1], v[2], v[3], v[6], v[7], maxLum, minLum)
                }
                "clli" -> {
                    maxCll = input.readUInt(2).toInt()
                    maxFall = input.readUInt(2).toInt()
                }
                "pasp" -> if (box.dataEnd - box.dataStart >= 8) {
                    aspectH = input.readUInt(4)
                    aspectV = input.readUInt(4)
                }
            }
        }
        val validMastering = mastering?.let { m ->
            try {
                HdrStaticInfo(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], maxCll, maxFall)
            } catch (e: IllegalArgumentException) {
                warn("pista $trackId: se ignoran sus metadatos de masterizado HDR (mdcv inválido: ${e.message})")
                null
            }
        }
        val hdr = validMastering ?: if (maxCll > 0 || maxFall > 0) {
            HdrStaticInfo(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, maxCll, maxFall)
        } else {
            null
        }
        val colr = if (hasColr) {
            try {
                ColorInfo {
                    this.primaries = primaries
                    this.transfer = transfer
                    this.matrix = matrix
                    this.fullRange = fullRange
                }
            } catch (e: IllegalArgumentException) {
                warn("pista $trackId: se ignora su descripción de color (colr inválido: ${e.message})")
                null
            }
        } else {
            null
        }
        val color = when {
            hdr == null -> colr
            colr == null -> ColorInfo { this.hdr = hdr }
            else -> colr.copy { this.hdr = hdr }
        }
        return SampleDescription(
            videoCodec = codec, codecConfig = config, width = w, height = h, color = color,
            pixelAspectH = aspectH, pixelAspectV = aspectV,
        )
    }

    /**
     * `AudioSampleEntry`, también en sus versiones de QuickTime.
     *
     * Tras los 6 bytes reservados y el índice de referencia de datos vienen la versión de la
     * entrada (2 bytes), la revisión y el fabricante (6), los canales, el tamaño de muestra, el
     * identificador de compresión y el tamaño de paquete (2 cada uno) y la frecuencia en 16.16. En
     * ISO los tres primeros campos son cero; QuickTime usa la versión para alargar la entrada:
     *
     * - **v1**: 16 bytes más (muestras por paquete y bytes por paquete, por trama y por muestra). Es
     *   lo que escribe FFmpeg en un `.mov` con AAC, y entonces el `esds` va dentro de una caja
     *   `wave`. Sin saltarlos, las cajas hijas se buscaban 16 bytes antes de donde están y la pista
     *   se quedaba sin `codecPrivate`.
     * - **v2**: 36 bytes más, donde la frecuencia real va como `float64` y los canales como `uint32`;
     *   los campos de la v0 llevan valores fijos (la frecuencia, 1,0, que se leía como 1 Hz).
     *
     * Se toman como de QuickTime solo con un `stsd` de versión 0, igual que FFmpeg: la
     * `AudioSampleEntryV1` de ISO lleva la versión 1 en la entrada pero exige un `stsd` de versión 1,
     * y no añade nada.
     *
     * De un AudioSpecificConfig se toman la frecuencia y los canales de salida solo si los declara:
     * con `channelConfiguration` 0 los canales van en un PCE y `AacConfig` da 0, que pisaba el
     * valor del `stsd` y hacía que la pista se descartara por no tener canales.
     */
    private fun parseMp4a(entry: Mp4Box, stsdVersion: Int): SampleDescription {
        input.position = entry.dataStart
        input.skip(6 + 2)
        val version = input.readUInt(2).toInt()
        input.skip(2 + 4)
        var channels = input.readUInt(2).toInt()
        input.skip(2 + 2 + 2)
        var sampleRate = (input.readInt32() ushr 16)
        if (stsdVersion == 0 && version == QUICKTIME_SOUND_V1) {
            input.skip(QUICKTIME_SOUND_V1_EXTRA)
        } else if (stsdVersion == 0 && version == QUICKTIME_SOUND_V2) {
            input.skip(4)
            val rate = java.lang.Double.longBitsToDouble(input.readInt64())
            channels = input.readUInt(4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            input.skip(QUICKTIME_SOUND_V2_EXTRA - 16)
            sampleRate = if (rate >= 1.0 && rate <= Int.MAX_VALUE) rate.roundToInt() else 0
        }
        var asc: ByteArray? = null
        boxes.scanChildren(input.position, entry.dataEnd) { box ->
            when (box.type) {
                "esds" -> asc = readEsds(box) ?: asc
                "wave" -> boxes.scanChildren(box.dataStart, box.dataEnd) { child ->
                    if (child.type == "esds") asc = readEsds(child) ?: asc
                }
            }
        }
        asc?.let {
            runCatching { AacConfig.parse(it) }.getOrNull()?.let { parsed ->
                if (parsed.sampleRate > 0) sampleRate = parsed.sampleRate
                if (parsed.channelCount > 0) channels = parsed.channelCount
            }
        }
        return SampleDescription(
            audioCodec = AudioCodec.AAC, codecConfig = asc,
            sampleRate = sampleRate, channels = channels,
        )
    }

    /** El AudioSpecificConfig de un `esds`, saltando su versión y flags. */
    private fun readEsds(box: Mp4Box): ByteArray? {
        input.position = box.dataStart
        input.skip(4)
        return extractAscFromEsds(box.dataEnd)
    }

    /**
     * Una entrada `Opus`. Su frecuencia es la de entrada que declara el `dOps` (`InputSampleRate`), y
     * solo si no la declara —un cero, que RFC 7845 admite cuando no se conoce— la del `samplerate`
     * de la entrada, o 48 kHz. Ese `samplerate` vale 48 000 en cualquier Opus que siga su
     * encapsulado en ISOBMFF, que lo exige, así que leído de ahí un Opus a 16 kHz volvía a 48: es lo
     * que escriben los dos muxers MP4 desde que lo cumplen (ver `SampleEntries.writeAudio`).
     */
    private fun parseOpus(entry: Mp4Box): SampleDescription {
        input.position = entry.dataStart
        input.skip(6 + 2 + 8)
        val channels = input.readUInt(2).toInt()
        input.skip(2 + 2 + 2)
        val entryRate = (input.readInt32() ushr 16)
        var head: ByteArray? = null
        var delayUs = 0L
        var inputRate = 0
        boxes.scanChildren(input.position, entry.dataEnd) { box ->
            if (box.type == "dOps") {
                input.position = box.dataStart
                val dops = input.readBytes((box.dataEnd - box.dataStart).toInt())
                val opusHead = OpusConfig.dopsToOpusHead(dops)
                head = opusHead
                val parsed = OpusConfig.parseOpusHead(opusHead)
                delayUs = parsed.codecDelayUs
                inputRate = parsed.inputSampleRate
            }
        }
        val sampleRate = when {
            inputRate > 0 -> inputRate
            entryRate > 0 -> entryRate
            else -> 48000
        }
        return SampleDescription(
            audioCodec = AudioCodec.OPUS, codecConfig = head,
            sampleRate = sampleRate, channels = channels,
            codecDelayUs = delayUs,
        )
    }

    /**
     * Recorre el árbol de descriptores MPEG-4 del `esds` (ISO/IEC 14496-1 §7.2.6) hasta el
     * `DecoderSpecificInfo`. Del `ES_Descriptor` se saltan el `ES_ID` y, según sus flags, el
     * `dependsOn_ES_ID`, la URL y el `OCR_ES_Id`; del `DecoderConfigDescriptor`, sus 13 bytes fijos
     * —tipo de objeto y de stream, tamaño de búfer y tasas—, porque los descriptores hijos van a
     * continuación, dentro de él.
     */
    private fun extractAscFromEsds(end: Long): ByteArray? {
        fun readDescriptorLength(): Int {
            var length = 0
            var count = 0
            while (count < 4) {
                val b = input.readByte()
                length = (length shl 7) or (b and 0x7F)
                count++
                if (b and 0x80 == 0) break
            }
            return length
        }
        while (input.position < end) {
            val tag = input.readByte()
            val len = readDescriptorLength()
            val payloadEnd = input.position + len
            when (tag) {
                ES_DESCRIPTOR_TAG -> {
                    input.skip(2)
                    val flags = input.readByte()
                    if (flags and ES_STREAM_DEPENDENCE_FLAG != 0) input.skip(2)
                    if (flags and ES_URL_FLAG != 0) input.skip(input.readByte().toLong())
                    if (flags and ES_OCR_STREAM_FLAG != 0) input.skip(2)
                }
                DECODER_CONFIG_TAG -> input.skip(13)
                DECODER_SPECIFIC_INFO_TAG -> return if (payloadEnd <= end) input.readBytes(len) else null
                else -> input.position = payloadEnd
            }
        }
        return null
    }

    private companion object {
        /** Versión 1 de la entrada de sonido de QuickTime, y los bytes que añade. */
        private const val QUICKTIME_SOUND_V1 = 1
        private const val QUICKTIME_SOUND_V1_EXTRA = 16L

        /** Versión 2 de la entrada de sonido de QuickTime, y los bytes que añade. */
        private const val QUICKTIME_SOUND_V2 = 2
        private const val QUICKTIME_SOUND_V2_EXTRA = 36L

        private const val ES_DESCRIPTOR_TAG = 0x03
        private const val DECODER_CONFIG_TAG = 0x04
        private const val DECODER_SPECIFIC_INFO_TAG = 0x05

        /** Flags del `ES_Descriptor`: `streamDependenceFlag`, `URL_Flag` y `OCRstreamFlag`. */
        private const val ES_STREAM_DEPENDENCE_FLAG = 0x80
        private const val ES_URL_FLAG = 0x40
        private const val ES_OCR_STREAM_FLAG = 0x20
    }
}
