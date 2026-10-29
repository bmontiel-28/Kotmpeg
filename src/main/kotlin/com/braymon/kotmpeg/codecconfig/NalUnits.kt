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
        val bounds = findAnnexBNals(data)
        return List(bounds.count) { data.copyOfRange(bounds.start(it), bounds.end(it)) }
    }

    /** Límites `[inicio, fin)` de cada NAL dentro del array original, sin copiar nada. */
    private class NalBounds(private val starts: IntArray, private val ends: IntArray, val count: Int) {
        fun start(i: Int): Int = starts[i]
        fun end(i: Int): Int = ends[i]
    }

    /**
     * Localiza las NAL de un stream Annex-B quitando a cada una **todos** sus ceros finales.
     *
     * Un NAL nunca termina en 0x00: los ceros de la cola son el cero que pertenece al
     * código de inicio de 4 bytes siguiente, más los `cabac_zero_word`/`trailing_zero_8bits`
     * que algunos codificadores insertan como relleno. Quitar solo uno deja esos bytes
     * pegados al final del NAL anterior: corrupción silenciosa de la carga, sin ninguna
     * excepción que lo delate.
     */
    private fun findAnnexBNals(data: ByteArray): NalBounds {
        var starts = IntArray(8)
        var ends = IntArray(8)
        var count = 0
        fun addTrimmed(start: Int, endExclusive: Int) {
            var end = endExclusive
            while (end > start && data[end - 1].toInt() == 0) end--
            if (end <= start) return
            if (count == starts.size) {
                starts = starts.copyOf(count * 2)
                ends = ends.copyOf(count * 2)
            }
            starts[count] = start
            ends[count] = end
            count++
        }
        var i = 0
        var nalStart = -1
        val n = data.size
        while (i + 2 < n) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                if (nalStart >= 0) addTrimmed(nalStart, i)
                i += 3
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart in 0 until n) addTrimmed(nalStart, n)
        else if (nalStart == -1 && n > 0) throw IllegalArgumentException("no se encontró código de inicio Annex-B")
        return NalBounds(starts, ends, count)
    }

    /** Divide una muestra con prefijos de longitud de 4 bytes en NAL units crudas. */
    public fun splitLengthPrefixed(data: ByteArray): List<ByteArray> {
        val nals = ArrayList<ByteArray>()
        forEachLengthPrefixed(data) { start, length -> nals.add(data.copyOfRange(start, start + length)) }
        return nals
    }

    /**
     * Recorre las NAL de una muestra con prefijos de longitud de 4 bytes, comprobando que cada una
     * cabe en lo que queda, y devuelve dónde acaba la última. Lo que quede detrás, menos de 4
     * bytes, no llega a ser un prefijo y se ignora, igual en [splitLengthPrefixed] que en
     * [lengthPrefixedToAnnexB]: por eso las dos usan este recorrido y no uno cada una.
     */
    private inline fun forEachLengthPrefixed(data: ByteArray, action: (start: Int, length: Int) -> Unit): Int {
        var i = 0
        while (i + 4 <= data.size) {
            val len = readLength(data, i)
            i += 4
            require(len >= 0 && i.toLong() + len.toLong() <= data.size.toLong()) {
                "muestra con prefijos de longitud corrupta"
            }
            action(i, len)
            i += len
        }
        return i
    }

    public fun joinLengthPrefixed(nals: List<ByteArray>): ByteArray {
        val out = ByteArray(prefixedSize(nals.size) { nals[it].size })
        var at = 0
        for (nal in nals) {
            writeLength(out, at, nal.size)
            System.arraycopy(nal, 0, out, at + 4, nal.size)
            at += 4 + nal.size
        }
        return out
    }

    public fun joinAnnexB(nals: List<ByteArray>): ByteArray {
        val out = ByteArray(prefixedSize(nals.size) { nals[it].size })
        var at = 0
        for (nal in nals) {
            out[at + 3] = 1                                             // código de inicio 00 00 00 01
            System.arraycopy(nal, 0, out, at + 4, nal.size)
            at += 4 + nal.size
        }
        return out
    }

    /**
     * Tamaño de [count] NAL con un prefijo o un código de inicio de 4 bytes cada una, siendo
     * [length] la longitud de cada NAL. Se suma en `Long` y se rechaza lo que no cabe en un array:
     * sumado en `Int` daría la vuelta a un tamaño negativo o, peor, a uno pequeño y válido.
     */
    private inline fun prefixedSize(count: Int, length: (Int) -> Int): Int {
        var total = 0L
        for (k in 0 until count) total += 4L + length(k)
        require(total <= MAX_ARRAY_BYTES) { "la unidad de acceso no cabe en un array: $total bytes" }
        return total.toInt()
    }

    private fun writeLength(out: ByteArray, at: Int, length: Int) {
        out[at] = (length ushr 24).toByte()
        out[at + 1] = (length ushr 16).toByte()
        out[at + 2] = (length ushr 8).toByte()
        out[at + 3] = length.toByte()
    }

    /**
     * Convierte una unidad de acceso Annex-B al formato ISO con prefijos de longitud de 4 bytes.
     *
     * Es la conversión que se hace con **cada fotograma** que sale de un codificador por hardware,
     * así que va en una sola pasada: localiza los límites de cada NAL y copia directamente al
     * array final, del tamaño exacto. Antes se creaba un array por NAL y luego otro buffer más
     * para unirlos, el doble de memoria y de copias por fotograma.
     */
    public fun annexBToLengthPrefixed(data: ByteArray): ByteArray {
        val bounds = findAnnexBNals(data)
        val out = ByteArray(prefixedSize(bounds.count) { bounds.end(it) - bounds.start(it) })
        var at = 0
        for (k in 0 until bounds.count) {
            val length = bounds.end(k) - bounds.start(k)
            writeLength(out, at, length)
            System.arraycopy(data, bounds.start(k), out, at + 4, length)
            at += 4 + length
        }
        return out
    }

    /**
     * Convierte una muestra ISO con prefijos a Annex-B (para decodificadores que piden códigos de
     * inicio). Un prefijo de 4 bytes y un código de inicio de 4 bytes miden lo mismo, así que basta
     * una copia de la muestra con cada prefijo sustituido: no hace falta trocearla.
     */
    public fun lengthPrefixedToAnnexB(data: ByteArray): ByteArray {
        val end = forEachLengthPrefixed(data) { _, _ -> }
        val out = data.copyOf(end)
        var at = 0
        while (at + 4 <= end) {
            val len = readLength(out, at)
            out[at] = 0; out[at + 1] = 0; out[at + 2] = 0; out[at + 3] = 1 // código de inicio
            at += 4 + len
        }
        return out
    }

    private fun readLength(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)

    private const val MAX_ARRAY_BYTES = Int.MAX_VALUE - 8L

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

    /**
     * Construye un AVCDecoderConfigurationRecord (avcC) desde NAL units SPS/PPS crudas.
     *
     * Con un perfil de la familia High el registro lleva al final el formato de croma, las dos
     * profundidades de bits y el número de SPS de extensión. ISO/IEC 14496-15 lo exige con
     * `profile_idc` 100, 110, 122 y 144, y no se escribía: MediaCodec da perfil High en casi
     * cualquier móvil, así que faltaba en el caso normal. Se escribe, como FFmpeg, en todo perfil
     * que no sea Baseline, Main o Extended (66, 77 y 88), que es lo que esperan los lectores que
     * leen esos campos; los valores salen del SPS ([AvcSpsFormat]).
     *
     * Las longitudes de los parameter sets son campos de 16 bits: lo que no cabe se rechaza, porque
     * truncado dejaba el registro desalineado y el lector tomaba como SPS lo que venía detrás.
     */
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
        for (s in spsList) writeParameterSet(out, s, "avcC/SPS")
        out.write(ppsList.size)
        for (p in ppsList) writeParameterSet(out, p, "avcC/PPS")
        when (sps[1].toInt() and 0xFF) {
            66, 77, 88 -> Unit
            else -> {
                val format = AvcSpsFormat.parse(sps)
                out.write(0xFC or format.chromaFormatIdc)          // 6 bits reservados + chroma_format
                out.write(0xF8 or (format.bitDepthLuma - 8))      // 5 bits reservados + bit_depth_luma_minus8
                out.write(0xF8 or (format.bitDepthChroma - 8))    // 5 bits reservados + bit_depth_chroma_minus8
                out.write(0)                                      // numOfSequenceParameterSetExt
            }
        }
        return out.toByteArray()
    }

    /** Escribe un parameter set precedido de su longitud de 16 bits, como lo guardan avcC y hvcC. */
    private fun writeParameterSet(out: ByteArrayOutputStream, nal: ByteArray, what: String) {
        require(nal.size <= 0xFFFF) { "$what de ${nal.size} bytes: su longitud es un campo de 16 bits" }
        out.write(nal.size ushr 8); out.write(nal.size); out.write(nal)
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

    /**
     * Construye un HEVCDecoderConfigurationRecord (hvcC) desde NAL units VPS/SPS/PPS crudas.
     *
     * Exige los tres tipos, como [buildAvcC] exige SPS y PPS. Cada array se escribe con
     * `array_completeness = 1` —todos los de ese tipo están aquí— porque la entrada `hvc1` no admite
     * parameter sets dentro del stream; sin VPS salía un registro que se aceptaba sin error y un
     * archivo que ningún decodificador puede abrir. El número de NAL de cada array y la longitud
     * de cada una son campos de 16 bits, y lo que no cabe se rechaza en vez de truncarse.
     */
    public fun buildHvcC(vpsList: List<ByteArray>, spsList: List<ByteArray>, ppsList: List<ByteArray>): ByteArray {
        require(vpsList.isNotEmpty() && spsList.isNotEmpty() && ppsList.isNotEmpty()) {
            "hvcC requiere VPS, SPS y PPS: hvc1 no admite parameter sets dentro del stream"
        }
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
        )
        out.write(arrays.size)                                          // numOfArrays
        for ((type, nals) in arrays) {
            require(nals.size <= 0xFFFF) { "hvcC/tipo $type: ${nals.size} NAL y numNalus es de 16 bits" }
            out.write(0x80 or type)                                     // array_completeness=1
            out.write(nals.size ushr 8); out.write(nals.size)
            for (nal in nals) writeParameterSet(out, nal, "hvcC/tipo $type")
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
internal class HevcSpsInfo(
    val profileSpace: Int,
    val tierFlag: Boolean,
    val profileIdc: Int,
    val profileCompatFlags: Long,
    val constraintFlags: Long,
    val levelIdc: Int,
    val chromaFormatIdc: Int,
    val bitDepthLuma: Int,
    val bitDepthChroma: Int,
    val maxSubLayers: Int,
    val temporalIdNesting: Boolean,
) {
    companion object {
        /** Parsea la parte inicial de un SPS HEVC (cabecera + profile_tier_level). */
        fun parse(spsNal: ByteArray): HevcSpsInfo {
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
                repeat(8 - maxSubLayersMinus1) { r.bits(2) } // reserved_zero_2bits
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

/**
 * Formato de croma y profundidades de bits de un SPS de H.264: lo que piden los cuatro bytes finales
 * de un `avcC` de perfil High.
 *
 * El SPS solo los lleva con los perfiles que H.264 enumera en su sintaxis (7.3.2.1.1), más el 144
 * de sus primeras ediciones; en cualquier otro valen lo que dice la norma, 4:2:0 a 8 bits. Un SPS
 * que no se puede leer —truncado, o con valores fuera de rango— da también esos valores, que son
 * los de un High corriente: un `avcC` que se construía antes no puede dejar de construirse por estos
 * campos, que ningún decodificador usa para configurarse.
 */
internal class AvcSpsFormat private constructor(val chromaFormatIdc: Int, val bitDepthLuma: Int, val bitDepthChroma: Int) {
    companion object {
        private val DEFAULT = AvcSpsFormat(1, 8, 8)
        private val PROFILES_WITH_CHROMA_FORMAT = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135, 144)

        /**
         * Lee el SPS desde detrás de la cabecera NAL, quitando antes los bytes de prevención: el
         * nivel y las restricciones pueden ser ceros que formen un `00 00 03` con lo que sigue.
         */
        fun parse(spsNal: ByteArray): AvcSpsFormat {
            if (spsNal.size < 5 || (spsNal[1].toInt() and 0xFF) !in PROFILES_WITH_CHROMA_FORMAT) return DEFAULT
            return try {
                val r = BitReader(NalUnits.unescapeRbsp(spsNal.copyOfRange(1, spsNal.size)))
                r.bits(24)                                 // profile_idc, constraint_set_flags, level_idc
                r.ue()                                     // seq_parameter_set_id
                val chroma = r.ue()                        // chroma_format_idc
                if (chroma == 3) r.bits(1)                 // separate_colour_plane_flag
                val luma = r.ue() + 8                      // bit_depth_luma_minus8
                val chromaDepth = r.ue() + 8               // bit_depth_chroma_minus8
                if (chroma in 0..3 && luma in 8..14 && chromaDepth in 8..14) AvcSpsFormat(chroma, luma, chromaDepth) else DEFAULT
            } catch (_: IllegalArgumentException) {
                DEFAULT
            }
        }
    }
}

/** Lector de bits MSB-first con soporte Exp-Golomb. */
internal class BitReader(private val data: ByteArray) {
    private var bitPos = 0

    /** Bits que quedan por leer, para los campos opcionales que la norma condiciona a que los haya. */
    val bitsLeft: Long get() = data.size * 8L - bitPos

    fun bits(count: Int): Int {
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
    fun ue(): Int {
        var zeros = 0
        while (bits(1) == 0) {
            zeros++
            require(zeros < 31) { "exp-golomb corrupto" }
        }
        return (1 shl zeros) - 1 + if (zeros > 0) bits(zeros) else 0
    }
}
