package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.codecconfig.AacConfig
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

    fun writeVisual(b: BoxBuilder, info: TrackInfo.Video) {
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
            info.color?.let { color ->
                box("colr") {
                    fourcc("nclx")
                    u16(color.primaries); u16(color.transfer); u16(color.matrix)
                    u8(if (color.fullRange) 0x80 else 0)
                }
                color.hdr?.let { hdr ->
                    box("mdcv") {
                        fun chroma(v: Double) = u16(HdrStaticInfo.chromaticityUnits(v).toInt())
                        chroma(hdr.greenX); chroma(hdr.greenY)
                        chroma(hdr.blueX); chroma(hdr.blueY)
                        chroma(hdr.redX); chroma(hdr.redY)
                        chroma(hdr.whiteX); chroma(hdr.whiteY)
                        u32(HdrStaticInfo.luminanceUnits(hdr.maxMasteringLuminance))
                        u32(HdrStaticInfo.luminanceUnits(hdr.minMasteringLuminance))
                    }
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

    fun writeAudio(b: BoxBuilder, info: TrackInfo.Audio) {
        b.box(info.codec.mp4SampleEntry) {
            zeros(6); u16(1)                         // reservado + data_reference_index
            u32(0); u32(0)
            u16(info.channelCount)
            u16(if (info.bitDepth > 0) info.bitDepth else 16)
            u16(0); u16(0)
            u32(info.sampleRate.toLong().coerceAtMost(MAX_SAMPLE_ENTRY_RATE) shl 16)
            when (info.codec) {
                AudioCodec.AAC -> writeEsds(this, info)
                AudioCodec.OPUS -> box("dOps") {
                    val head = info.codecPrivate
                    val dops = if (head != null) OpusConfig.opusHeadToDops(head)
                    else OpusConfig.opusHeadToDops(OpusConfig.buildOpusHead(info.channelCount))
                    bytes(dops)
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
        val asc = info.codecPrivate ?: AacConfig.build(info.sampleRate, info.channelCount)
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

    /** Escribe la matriz de presentación de 9 entradas de tkhd/mvhd (punto fijo 16.16 / 2.30). */
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
            else -> {
                b.u32(one); b.u32(0); b.u32(0)
                b.u32(0); b.u32(one); b.u32(0)
                b.u32(0); b.u32(0); b.u32(0x40000000)
            }
        }
    }
}
