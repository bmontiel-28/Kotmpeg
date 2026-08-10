package com.braymon.kotmpeg.model

/**
 * Descripción de color del vídeo (la superficie de FFmpeg
 * `-color_primaries/-color_trc/-colorspace/-color_range`, más metadatos HDR estáticos).
 *
 * Los códigos enteros siguen ISO/IEC 23091-2 (los mismos valores que usan el VUI de
 * H.264/H.265, la caja `colr` nclx de MP4, los elementos `Colour` de Matroska y el
 * `MediaFormat` de Android), por lo que mapean 1:1 en todas las capas.
 */
public data class ColorInfo(
    /** Primarios de color: 1 = BT.709, 9 = BT.2020, 2 = sin especificar. */
    val primaries: Int = UNSPECIFIED,
    /** Característica de transferencia: 1 = BT.709, 16 = PQ (SMPTE 2084), 18 = HLG. */
    val transfer: Int = UNSPECIFIED,
    /** Coeficientes de matriz: 1 = BT.709, 9 = BT.2020 no constante. */
    val matrix: Int = UNSPECIFIED,
    /** True para rango completo (PC/JPEG), false para rango limitado (broadcast). */
    val fullRange: Boolean = false,
    /** Metadatos HDR10 estáticos (SMPTE ST 2086 + CTA-861.3). */
    val hdr: HdrStaticInfo? = null,
) {
    init {
        require(primaries in 0..255) { "primaries fuera de rango: $primaries" }
        require(transfer in 0..255) { "transfer fuera de rango: $transfer" }
        require(matrix in 0..255) { "matrix fuera de rango: $matrix" }
    }

    public companion object {
        public const val UNSPECIFIED: Int = 2

        public const val PRIMARIES_BT709: Int = 1
        public const val PRIMARIES_BT2020: Int = 9

        public const val TRANSFER_BT709: Int = 1
        public const val TRANSFER_PQ: Int = 16
        public const val TRANSFER_HLG: Int = 18

        public const val MATRIX_BT709: Int = 1
        public const val MATRIX_BT2020_NCL: Int = 9

        /** Preajuste: HDR10 (BT.2020 + PQ). */
        public fun hdr10(hdr: HdrStaticInfo? = null): ColorInfo =
            ColorInfo(PRIMARIES_BT2020, TRANSFER_PQ, MATRIX_BT2020_NCL, fullRange = false, hdr = hdr)

        /** Preajuste: SDR BT.709 rango broadcast. */
        public fun bt709(): ColorInfo =
            ColorInfo(PRIMARIES_BT709, TRANSFER_BT709, MATRIX_BT709, fullRange = false)
    }
}

/**
 * Metadatos HDR estáticos: volumen de color del display de masterizado (ST 2086) y niveles
 * de luz del contenido. Cromaticidades CIE 1931 xy en [0,1]; luminancia en cd/m².
 */
public data class HdrStaticInfo(
    val redX: Double, val redY: Double,
    val greenX: Double, val greenY: Double,
    val blueX: Double, val blueY: Double,
    val whiteX: Double, val whiteY: Double,
    val maxMasteringLuminance: Double,
    val minMasteringLuminance: Double,
    /** Nivel máximo de luz del contenido (MaxCLL), 0 si se desconoce. */
    val maxContentLightLevel: Int = 0,
    /** Nivel máximo de luz media por fotograma (MaxFALL), 0 si se desconoce. */
    val maxFrameAverageLightLevel: Int = 0,
) {
    init {
        for ((name, value) in listOf(
            "redX" to redX, "redY" to redY, "greenX" to greenX, "greenY" to greenY,
            "blueX" to blueX, "blueY" to blueY, "whiteX" to whiteX, "whiteY" to whiteY,
        )) {
            require(value in 0.0..1.0) { "cromaticidad $name fuera de [0,1]: $value" }
        }
        for ((name, value) in listOf(
            "maxMasteringLuminance" to maxMasteringLuminance,
            "minMasteringLuminance" to minMasteringLuminance,
        )) {
            require(value >= 0) { "$name negativa: $value" }
            require(value <= MAX_MASTERING_LUMINANCE) {
                "$name fuera del rango representable en mdcv (0..$MAX_MASTERING_LUMINANCE cd/m2): $value"
            }
        }
        require(minMasteringLuminance <= maxMasteringLuminance) {
            "minMasteringLuminance ($minMasteringLuminance) supera a maxMasteringLuminance ($maxMasteringLuminance)"
        }
        require(maxContentLightLevel in 0..0xFFFF) { "MaxCLL fuera de rango de 16 bits" }
        require(maxFrameAverageLightLevel in 0..0xFFFF) { "MaxFALL fuera de rango de 16 bits" }
    }

    /**
     * Serializa estos metadatos como «Static Metadata Descriptor ID 0» de CTA-861.3: 25
     * bytes en little-endian, el formato exacto que espera `KEY_HDR_STATIC_INFO` de Android
     * y que acaba en el SEI del bitstream H.265.
     *
     * **Los campos de CTA-861.3 son de 16 bits**, mucho más estrechos que el uint32 de
     * `mdcv` que valida el constructor: la luminancia máxima llega a
     * [CTA861_MAX_MASTERING_LUMINANCE] y la mínima a [CTA861_MIN_MASTERING_LUMINANCE]. Un
     * valor legal para el contenedor pero no representable aquí se **satura** al máximo del
     * campo. Un `toShort()` a secas lo envolvería —100 000 cd/m2 saldrían como 34 464, es
     * decir metadatos HDR incorrectos sin ningún aviso—; saturar también pierde información,
     * pero de forma monótona y predecible en vez de dar un valor arbitrario.
     *
     * Ojo con las unidades, que no son las mismas en los dos campos de luminancia: el máximo
     * va en cd/m2 y el mínimo en pasos de 0,0001 cd/m2. Es lo que dice CTA-861.3, aunque
     * algunos extractores conocidos escriben los dos en cd/m2.
     */
    public fun toStaticMetadataDescriptor(): ByteArray {
        val out = ByteArray(25)
        var i = 0
        fun le16(value: Long) {
            val v = value.coerceIn(0, 0xFFFF)
            out[i++] = (v and 0xFF).toByte()
            out[i++] = ((v shr 8) and 0xFF).toByte()
        }
        out[i++] = 0 // id del descriptor
        le16(chromaticityUnits(redX)); le16(chromaticityUnits(redY))
        le16(chromaticityUnits(greenX)); le16(chromaticityUnits(greenY))
        le16(chromaticityUnits(blueX)); le16(chromaticityUnits(blueY))
        le16(chromaticityUnits(whiteX)); le16(chromaticityUnits(whiteY))
        le16(Math.round(maxMasteringLuminance))
        le16(luminanceUnits(minMasteringLuminance))
        le16(maxContentLightLevel.toLong())
        le16(maxFrameAverageLightLevel.toLong())
        return out
    }

    public companion object {
        /**
         * Cuantiza una cromaticidad CIE 1931 a las unidades de 0,00002 que usan **todas**
         * las rutas de serialización (la caja `mdcv` de MP4 y el descriptor CTA-861.3).
         *
         * Redondea, no trunca. En binario `0.708 / 0.00002` no da 35 400 exacto sino
         * 35 399,99999999999, así que truncar deja dos de las coordenadas de un master
         * BT.2020/D65 —el caso más común de HDR10— un paso por debajo, en silencio y siempre
         * en la misma dirección. Vive aquí y no en cada escritor para que las dos rutas de
         * serialización no puedan divergir.
         */
        internal fun chromaticityUnits(value: Double): Long = Math.round(value / CHROMATICITY_UNIT)

        /** Cuantiza una luminancia a las unidades de 0,0001 cd/m2 de `mdcv`. Redondea. */
        internal fun luminanceUnits(value: Double): Long = Math.round(value / LUMINANCE_UNIT)

        /** Luminancia máxima representable en `mdcv`: `0xFFFFFFFF` unidades de 0.0001 cd/m2. */
        public const val MAX_MASTERING_LUMINANCE: Double = 0xFFFFFFFFL * 0.0001

        /** Luminancia máxima representable en CTA-861.3: uint16 en unidades de 1 cd/m2. */
        public const val CTA861_MAX_MASTERING_LUMINANCE: Double = 65_535.0

        /** Luminancia mínima representable en CTA-861.3: uint16 en unidades de 0.0001 cd/m2. */
        public const val CTA861_MIN_MASTERING_LUMINANCE: Double = 6.5535

        private const val CHROMATICITY_UNIT = 0.00002
        private const val LUMINANCE_UNIT = 0.0001
    }
}
