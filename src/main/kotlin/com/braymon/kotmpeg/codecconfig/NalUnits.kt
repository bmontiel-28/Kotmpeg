package com.braymon.kotmpeg.codecconfig

import java.io.ByteArrayOutputStream

/**
 * Utilidades para streams de NAL units de H.264/H.265.
 *
 * Los contenedores (MP4 y MKV) guardan las muestras como NALUs con prefijo de longitud de
 * 4 bytes más un registro de configuración fuera de banda (avcC/hvcC). Los codificadores
 * (MediaCodec de Android, x264, ...) emiten streams Annex-B con códigos de inicio 00 00 01
 * y los parameter sets dentro del propio stream.
 */
public object NalUnits {
    public const val H264_IDR: Int = 5
    public const val H264_SEI: Int = 6
    public const val H264_SPS: Int = 7
    public const val H264_PPS: Int = 8
    public const val H264_AUD: Int = 9

    public const val H265_VPS: Int = 32
    public const val H265_SPS: Int = 33
    public const val H265_PPS: Int = 34
    public const val H265_AUD: Int = 35
    public const val H265_PREFIX_SEI: Int = 39

    public fun h264NalType(nal: ByteArray): Int {
        require(nal.isNotEmpty()) { "NAL vacía: no tiene byte de cabecera del que leer el tipo" }
        return nal[0].toInt() and 0x1F
    }

    public fun h265NalType(nal: ByteArray): Int {
        require(nal.isNotEmpty()) { "NAL vacía: no tiene byte de cabecera del que leer el tipo" }
        return (nal[0].toInt() shr 1) and 0x3F
    }

    /**
     * True para imágenes IRAP de HEVC. El rango IRAP del estándar es 16..23: 22 y 23 están
     * reservados para futuros tipos IRAP, pero por definición siguen siendo puntos de
     * acceso aleatorio, así que tratarlos como keyframe es lo correcto.
     */
    public fun isH265KeyFrameNal(type: Int): Boolean = type in 16..23

    /** Divide un stream Annex-B (delimitado por 00 00 01 / 00 00 00 01) en NAL units crudas. */
    public fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val nals = ArrayList<ByteArray>()
        var i = 0
        var nalStart = -1
        val n = data.size
        while (i + 2 < n) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                if (nalStart >= 0) nals.addTrimmed(data, nalStart, i)
                i += 3
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart in 0 until n) nals.addTrimmed(data, nalStart, n)
        else if (nalStart == -1 && n > 0) throw IllegalArgumentException("no se encontró código de inicio Annex-B")
        return nals
    }

    /**
     * Añade data[start, end) como NAL quitando TODOS los ceros finales.
     *
     * Un NAL nunca termina en 0x00: los ceros de la cola son el cero que pertenece al
     * código de inicio de 4 bytes siguiente, más los `cabac_zero_word`/`trailing_zero_8bits`
     * que algunos codificadores insertan como relleno. Quitar solo uno deja esos bytes
     * pegados al final del NAL anterior: corrupción silenciosa de la carga, sin ninguna
     * excepción que lo delate.
     */
    private fun MutableList<ByteArray>.addTrimmed(data: ByteArray, start: Int, endExclusive: Int) {
        var end = endExclusive
        while (end > start && data[end - 1].toInt() == 0) end--
        if (end > start) add(data.copyOfRange(start, end))
    }

    /** Divide una muestra con prefijos de longitud de 4 bytes en NAL units crudas. */
    public fun splitLengthPrefixed(data: ByteArray): List<ByteArray> {
        val nals = ArrayList<ByteArray>()
        var i = 0
        while (i + 4 <= data.size) {
            val len = ((data[i].toInt() and 0xFF) shl 24) or ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            i += 4
            require(len >= 0 && i.toLong() + len.toLong() <= data.size.toLong()) {
                "muestra con prefijos de longitud corrupta"
            }
            nals.add(data.copyOfRange(i, i + len))
            i += len
        }
        return nals
    }

    public fun joinLengthPrefixed(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            out.write(nal.size ushr 24); out.write(nal.size ushr 16)
            out.write(nal.size ushr 8); out.write(nal.size)
            out.write(nal)
        }
        return out.toByteArray()
    }

    public fun joinAnnexB(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            out.write(0); out.write(0); out.write(0); out.write(1)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /** Convierte una unidad de acceso Annex-B al formato ISO con prefijos de longitud de 4 bytes. */
    public fun annexBToLengthPrefixed(data: ByteArray): ByteArray = joinLengthPrefixed(splitAnnexB(data))

    /** Convierte una muestra ISO con prefijos a Annex-B (para decodificadores que piden códigos de inicio). */
    public fun lengthPrefixedToAnnexB(data: ByteArray): ByteArray = joinAnnexB(splitLengthPrefixed(data))

    /** Elimina los bytes de prevención de emulación (00 00 03 -> 00 00) de una carga NAL. */
    public fun unescapeRbsp(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        var i = 0
        while (i < nal.size) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                i++
                continue
            }
            zeros = if (b == 0) zeros + 1 else 0
            out.write(b)
            i++
        }
        return out.toByteArray()
    }

    /** Construye un AVCDecoderConfigurationRecord (avcC) desde NAL units SPS/PPS crudas. */
    public fun buildAvcC(spsList: List<ByteArray>, ppsList: List<ByteArray>): ByteArray {
        require(spsList.isNotEmpty() && ppsList.isNotEmpty()) { "avcC requiere SPS y PPS" }
        require(spsList.size <= 31) { "avcC admite como máximo 31 SPS, recibidos ${spsList.size}" }
        require(ppsList.size <= 255) { "avcC admite como máximo 255 PPS, recibidos ${ppsList.size}" }
        require(spsList[0].size >= 4) { "SPS demasiado corto para construir el avcC" }
        val sps = spsList[0]
        val out = ByteArrayOutputStream()
        out.write(1)                       // configurationVersion
        out.write(sps[1].toInt())          // AVCProfileIndication
        out.write(sps[2].toInt())          // profile_compatibility
        out.write(sps[3].toInt())          // AVCLevelIndication
        out.write(0xFF)                    // 6 bits reservados + lengthSizeMinusOne = 3
        out.write(0xE0 or spsList.size)    // 3 bits reservados + numOfSequenceParameterSets
        for (s in spsList) { out.write(s.size ushr 8); out.write(s.size); out.write(s) }
        out.write(ppsList.size)
        for (p in ppsList) { out.write(p.size ushr 8); out.write(p.size); out.write(p) }
        return out.toByteArray()
    }

    /**
     * Extrae las NAL units SPS/PPS de un registro avcC. Devuelve (spsList, ppsList).
     *
     * Valida longitudes en cada paso: un `codecPrivate` truncado o de otro codec debe dar
     * un error claro y no un ArrayIndexOutOfBounds a medio configurar un decodificador.
     */
    public fun parseAvcC(avcC: ByteArray): Pair<List<ByteArray>, List<ByteArray>> {
        require(avcC.size >= 7) { "avcC demasiado corto (${avcC.size} bytes)" }
        var i = 5
        val spsCount = avcC[i++].toInt() and 0x1F
        val sps = ArrayList<ByteArray>(spsCount)
        repeat(spsCount) {
            i = readParameterSet(avcC, i, sps, "avcC/SPS")
        }
        require(i < avcC.size) { "avcC truncado: falta el número de PPS" }
        val ppsCount = avcC[i++].toInt() and 0xFF
        val pps = ArrayList<ByteArray>(ppsCount)
        repeat(ppsCount) {
            i = readParameterSet(avcC, i, pps, "avcC/PPS")
        }
        return sps to pps
    }

    /** Lee un parameter set con prefijo de longitud de 16 bits; devuelve el nuevo offset. */
    private fun readParameterSet(data: ByteArray, offset: Int, sink: MutableList<ByteArray>, what: String): Int {
        require(offset + 2 <= data.size) { "$what truncado en el offset $offset" }
        val len = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        val start = offset + 2
        require(start + len <= data.size) { "$what declara $len bytes y solo quedan ${data.size - start}" }
        sink.add(data.copyOfRange(start, start + len))
        return start + len
    }

    /** Construye un HEVCDecoderConfigurationRecord (hvcC) desde NAL units VPS/SPS/PPS crudas. */
    public fun buildHvcC(vpsList: List<ByteArray>, spsList: List<ByteArray>, ppsList: List<ByteArray>): ByteArray {
        require(spsList.isNotEmpty() && ppsList.isNotEmpty()) { "hvcC requiere SPS y PPS" }
        val sps = HevcSpsInfo.parse(spsList[0])
        val out = ByteArrayOutputStream()
        out.write(1)                                                    // configurationVersion
        out.write((sps.profileSpace shl 6) or (if (sps.tierFlag) 0x20 else 0) or sps.profileIdc)
        for (i in 3 downTo 0) out.write((sps.profileCompatFlags ushr (8 * i)).toInt() and 0xFF)
        for (i in 5 downTo 0) out.write(((sps.constraintFlags ushr (8 * i)) and 0xFF).toInt())
        out.write(sps.levelIdc)
        out.write(0xF0); out.write(0)                                   // min_spatial_segmentation_idc = 0
        out.write(0xFC)                                                 // parallelismType = 0
        out.write(0xFC or sps.chromaFormatIdc)
        out.write(0xF8 or (sps.bitDepthLuma - 8))
        out.write(0xF8 or (sps.bitDepthChroma - 8))
        out.write(0); out.write(0)                                      // avgFrameRate = 0 (sin especificar)
        out.write((sps.maxSubLayers shl 3) or (if (sps.temporalIdNesting) 4 else 0) or 3)
        val arrays = listOf(
            H265_VPS to vpsList,
            H265_SPS to spsList,
            H265_PPS to ppsList,
        ).filter { it.second.isNotEmpty() }
        out.write(arrays.size)
        for ((type, nals) in arrays) {
            out.write(0x80 or type)                                     // array_completeness=1
            out.write(nals.size ushr 8); out.write(nals.size)
            for (nal in nals) { out.write(nal.size ushr 8); out.write(nal.size); out.write(nal) }
        }
        return out.toByteArray()
    }

    /** Extrae todas las NAL units de parameter sets de un hvcC, por tipo de NAL. */
    public fun parseHvcC(hvcC: ByteArray): Map<Int, List<ByteArray>> {
        require(hvcC.size >= 23) { "hvcC demasiado corto (${hvcC.size} bytes)" }
        var i = 22
        val numArrays = hvcC[i++].toInt() and 0xFF
        val result = HashMap<Int, MutableList<ByteArray>>()
        repeat(numArrays) {
            require(i + 3 <= hvcC.size) { "hvcC truncado en la cabecera de array" }
            val type = hvcC[i++].toInt() and 0x3F
            val count = ((hvcC[i].toInt() and 0xFF) shl 8) or (hvcC[i + 1].toInt() and 0xFF); i += 2
            val list = result.getOrPut(type) { ArrayList() }
            repeat(count) {
                i = readParameterSet(hvcC, i, list, "hvcC/tipo $type")
            }
        }
        return result
    }
}

/** Campos de un SPS HEVC necesarios para rellenar un registro hvcC. */
public class HevcSpsInfo(
    public val profileSpace: Int,
    public val tierFlag: Boolean,
    public val profileIdc: Int,
    public val profileCompatFlags: Long,
    public val constraintFlags: Long,
    public val levelIdc: Int,
    public val chromaFormatIdc: Int,
    public val bitDepthLuma: Int,
    public val bitDepthChroma: Int,
    public val maxSubLayers: Int,
    public val temporalIdNesting: Boolean,
) {
    public companion object {
        /** Parsea la parte inicial de un SPS HEVC (cabecera + profile_tier_level). */
        public fun parse(spsNal: ByteArray): HevcSpsInfo {
            val rbsp = NalUnits.unescapeRbsp(spsNal.copyOfRange(2, spsNal.size)) // salta la cabecera NAL de 2 bytes
            val r = BitReader(rbsp)
            r.bits(4)                                  // sps_video_parameter_set_id
            val maxSubLayersMinus1 = r.bits(3)
            val temporalIdNesting = r.bits(1) == 1
            val profileSpace = r.bits(2)
            val tier = r.bits(1) == 1
            val profileIdc = r.bits(5)
            var compat = 0L
            repeat(32) { compat = (compat shl 1) or r.bits(1).toLong() }
            var constraints = 0L
            repeat(48) { constraints = (constraints shl 1) or r.bits(1).toLong() }
            val levelIdc = r.bits(8)
            if (maxSubLayersMinus1 > 0) {
                val profilePresent = BooleanArray(maxSubLayersMinus1)
                val levelPresent = BooleanArray(maxSubLayersMinus1)
                for (i in 0 until maxSubLayersMinus1) {
                    profilePresent[i] = r.bits(1) == 1
                    levelPresent[i] = r.bits(1) == 1
                }
                if (maxSubLayersMinus1 > 0) repeat(8 - maxSubLayersMinus1) { r.bits(2) }
                for (i in 0 until maxSubLayersMinus1) {
                    if (profilePresent[i]) repeat(88) { r.bits(1) }
                    if (levelPresent[i]) r.bits(8)
                }
            }
            r.ue()                                     // sps_seq_parameter_set_id
            val chromaFormatIdc = r.ue()
            if (chromaFormatIdc == 3) r.bits(1)        // separate_colour_plane_flag
            r.ue(); r.ue()                             // pic_width/height_in_luma_samples
            if (r.bits(1) == 1) { r.ue(); r.ue(); r.ue(); r.ue() } // ventana de conformidad
            val bitDepthLuma = r.ue() + 8
            val bitDepthChroma = r.ue() + 8
            return HevcSpsInfo(
                profileSpace, tier, profileIdc, compat, constraints, levelIdc,
                chromaFormatIdc, bitDepthLuma, bitDepthChroma,
                maxSubLayers = maxSubLayersMinus1 + 1,
                temporalIdNesting = temporalIdNesting,
            )
        }
    }
}

/** Lector de bits MSB-first con soporte Exp-Golomb. */
public class BitReader(private val data: ByteArray) {
    private var bitPos = 0

    public fun bits(count: Int): Int {
        var v = 0
        repeat(count) {
            val index = bitPos ushr 3
            if (index >= data.size) throw IllegalArgumentException("fin de datos leyendo bits")
            val byte = data[index].toInt() and 0xFF
            v = (v shl 1) or ((byte shr (7 - (bitPos and 7))) and 1)
            bitPos++
        }
        return v
    }

    /** Exp-Golomb sin signo. */
    public fun ue(): Int {
        var zeros = 0
        while (bits(1) == 0) {
            zeros++
            require(zeros < 31) { "exp-golomb corrupto" }
        }
        return (1 shl zeros) - 1 + if (zeros > 0) bits(zeros) else 0
    }
}
