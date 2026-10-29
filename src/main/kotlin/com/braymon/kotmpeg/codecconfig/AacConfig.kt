package com.braymon.kotmpeg.codecconfig

/**
 * Construcción y parseo del AudioSpecificConfig de AAC (ISO 14496-3). El
 * AudioSpecificConfig es el [com.braymon.kotmpeg.model.TrackInfo.codecPrivate] canónico de las
 * pistas AAC tanto en MKV (CodecPrivate) como en MP4 (DecoderSpecificInfo del esds).
 */
public object AacConfig {

    /**
     * Tabla de frecuencias indexada por el campo `samplingFrequencyIndex` del ASC.
     *
     * `List` y no `IntArray`: un array público es estado global mutable, y este en concreto
     * es el que hace cumplir una validación — quien reescribiera una entrada desactivaría la
     * comprobación para todo el proceso.
     */
    public val SAMPLE_RATES: List<Int> = listOf(
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000, 7350,
    )

    public const val AOT_AAC_LC: Int = 2

    /**
     * AAC HE (SBR) y HE-v2 (SBR + PS). Los emite el codificador del dispositivo cuando se pide
     * el perfil `HE`/`HE_V2` —que se configura fuera de este módulo, ya que aquí no hay códec—,
     * así que aquí llegan por [parse] y no por [build]: el ASC de esos perfiles se toma del
     * `csd-0` del códec.
     * Se exponen para poder comprobar qué perfil declara de verdad un archivo ya escrito.
     */
    public const val AOT_SBR: Int = 5
    public const val AOT_PS: Int = 29

    /**
     * Construye un AudioSpecificConfig para AAC-LC (o el object type dado).
     *
     * Devuelve 2 bytes con las frecuencias de [SAMPLE_RATES]. Para cualquier otra (posible
     * si `AudioRecord` entrega una tasa nativa no estándar del dispositivo antes de pasar
     * por el remuestreador) usa el camino de **frecuencia explícita**: índice 15 más 24 bits
     * con el valor real, exactamente lo que [parse] sabe leer.
     *
     * **No sirve para HE/HE-v2**: la señalización explícita de SBR/PS lleva además la
     * frecuencia de extensión, que aquí no se escribe. Para esos perfiles el ASC correcto es
     * el `csd-0` que emite el propio codificador.
     */
    public fun build(sampleRate: Int, channelCount: Int, audioObjectType: Int = AOT_AAC_LC): ByteArray {
        require(sampleRate > 0) { "frecuencia AAC inválida: $sampleRate" }
        val channelConfig = when (channelCount) {
            8 -> 7
            in 1..6 -> channelCount
            else -> throw IllegalArgumentException(
                "número de canales AAC no soportado: $channelCount (válidos 1-6 y 8)",
            )
        }
        require(audioObjectType in 1..30) {
            "object type AAC fuera de rango: $audioObjectType (1-30; el 31 es el escape a 6 bits, que aquí no se escribe)"
        }

        val freqIndex = SAMPLE_RATES.indexOf(sampleRate)
        if (freqIndex >= 0) {
            val bits = (audioObjectType shl 11) or (freqIndex shl 7) or (channelConfig shl 3)
            return byteArrayOf(((bits shr 8) and 0xFF).toByte(), (bits and 0xFF).toByte())
        }

        require(sampleRate <= 0xFFFFFF) { "frecuencia AAC fuera del rango de 24 bits: $sampleRate" }
        val value = (audioObjectType.toLong() shl 32) or
            (15L shl 28) or
            (sampleRate.toLong() shl 4) or
            channelConfig.toLong()
        val padded = value shl 3
        return ByteArray(5) { i -> ((padded ushr (8 * (4 - i))) and 0xFF).toByte() }
    }

    /**
     * Lo que declara un AudioSpecificConfig, **en términos de lo que sale del decodificador**.
     *
     * Es la distinción que hay que tener presente con HE-AAC: el ASC describe el *núcleo* del
     * bitstream, no el resultado. Con SBR la frecuencia del núcleo es la mitad de la real, y con
     * PS el núcleo es mono aunque la salida sea estéreo. [sampleRate] y [channelCount] son
     * siempre los de **salida**; si necesitas los del núcleo, están en [coreSampleRate] y
     * [coreChannelCount].
     *
     * Lo mismo vale para las dos formas de declarar HE-AAC: la **explícita**, con el objeto 5 o 29
     * al principio, y la **implícita** o compatible hacia atrás, un AAC-LC con la extensión SBR
     * (y la de PS) detrás de su configuración, para que un decodificador sin SBR lo reproduzca a la
     * frecuencia del núcleo. En las dos, [audioObjectType] es el del perfil efectivo,
     * [AOT_SBR] o [AOT_PS].
     *
     * Solo lo crea [parse]: el constructor es interno para que un campo nuevo no cambie ninguna
     * firma pública.
     */
    public class Parsed internal constructor(
        public val audioObjectType: Int,
        /** Frecuencia de la señal decodificada. Con SBR es la de extensión, no la del núcleo. */
        public val sampleRate: Int,
        /**
         * Canales de la señal decodificada. Con PS son 2 aunque el núcleo sea mono.
         *
         * **0 significa desconocido**: el ASC no lo dice en su `channelConfiguration`, que vale 0
         * —los canales van en un `program_config_element`, que aquí no se lee— o un valor
         * reservado. Quien tenga otra fuente, como el `SampleEntry` o el elemento `Channels`
         * del contenedor, debe quedarse con ella y no sustituirla por este 0.
         */
        public val channelCount: Int,
        /** Frecuencia del núcleo tal cual la declara el ASC. Igual a [sampleRate] sin SBR. */
        public val coreSampleRate: Int = sampleRate,
        /**
         * Canales del núcleo del bitstream. Igual a [channelCount] salvo con PS, donde vale 1.
         *
         * Es un número de canales, no el `channelConfiguration` crudo, que es un índice de la
         * tabla de ISO/IEC 14496-3: el 7 es 7.1 y llega como 8, y del 11 al 14 son 6.1, 7.1 con
         * surround trasero, 22.2 y 7.1 con altura, que llegan como 7, 8, 24 y 8. El 0 y los
         * reservados llegan como 0, igual que en [channelCount].
         */
        public val coreChannelCount: Int = channelCount,
    )

    /**
     * Canales de cada `channelConfiguration`, según la tabla de ISO/IEC 14496-3 con su enmienda 4.
     * Se devolvía el índice como si fuera el número de canales, así que un 6.1 se leía como 11
     * canales y un 22.2 como 13. El 0 y los reservados (8 a 10 y 15) son 0, «desconocido».
     */
    private val CHANNELS_BY_CONFIGURATION = intArrayOf(0, 1, 2, 3, 4, 5, 6, 8, 0, 0, 0, 7, 8, 24, 8, 0)

    /**
     * Objetos que llevan un `GASpecificConfig` sin campos de protección de errores: los únicos
     * detrás de los cuales [parse] busca la señalización implícita de SBR y PS.
     */
    private val GA_OBJECT_TYPES = setOf(1, 2, 3, 4, 6, 7)

    /** `syncExtensionType` que anuncian la extensión SBR y, detrás de ella, la de PS. */
    private const val SYNC_EXTENSION_SBR = 0x2B7
    private const val SYNC_EXTENSION_PS = 0x548

    /**
     * Lee lo que declara un AudioSpecificConfig, con la señalización de HE-AAC explícita o
     * implícita (ver [Parsed]). La implícita es opcional en la norma y va al final: si el ASC acaba
     * antes, si su configuración no se puede recorrer —canales en un `program_config_element`,
     * objetos con protección de errores— o si lo que sigue no es la extensión, el ASC se lee como
     * el AAC sin SBR que es para un decodificador que no la conozca, como hasta ahora.
     */
    public fun parse(asc: ByteArray): Parsed {
        require(asc.size >= 2) { "AudioSpecificConfig demasiado corto" }
        val r = BitReader(asc)
        var aot = r.bits(5)
        if (aot == 31) aot = 32 + r.bits(6)
        val coreRate = readRate(r, r.bits(4))
        var rate = coreRate
        val channelConfiguration = r.bits(4)
        val coreChannels = CHANNELS_BY_CONFIGURATION[channelConfiguration]
        var channels = coreChannels

        if (aot == AOT_SBR || aot == AOT_PS) {
            rate = readRate(r, r.bits(4))
        } else if (skipGaSpecificConfig(r, aot, channelConfiguration) &&
            r.bitsLeft >= 16 && r.bits(11) == SYNC_EXTENSION_SBR &&
            r.bits(5) == AOT_SBR && r.bitsLeft >= 1 && r.bits(1) == 1
        ) {
            val extensionRate = readExtensionRate(r)
            if (extensionRate != null) {
                rate = extensionRate
                aot = AOT_SBR
                if (r.bitsLeft >= 12 && r.bits(11) == SYNC_EXTENSION_PS && r.bits(1) == 1) aot = AOT_PS
            }
        }
        if (aot == AOT_PS && channels == 1) channels = 2

        return Parsed(aot, rate, channels, coreSampleRate = coreRate, coreChannelCount = coreChannels)
    }

    /**
     * Salta el `GASpecificConfig` (ISO/IEC 14496-3, 4.4.1) y dice si lo consiguió. Solo sabe
     * hacerlo con los objetos de [GA_OBJECT_TYPES] y con los canales en `channelConfiguration`:
     * un `program_config_element` tiene longitud variable y no se recorre. Los campos que salta son
     * `frameLengthFlag`, `dependsOnCoreCoder` con su `coreCoderDelay`, `extensionFlag`, `layerNr`
     * del objeto 6 y `extensionFlag3`.
     */
    private fun skipGaSpecificConfig(r: BitReader, aot: Int, channelConfiguration: Int): Boolean {
        if (aot !in GA_OBJECT_TYPES || channelConfiguration == 0 || r.bitsLeft < 2) return false
        r.bits(1)
        if (r.bits(1) == 1) {
            if (r.bitsLeft < 14) return false
            r.bits(14)
        }
        if (r.bitsLeft < 1) return false
        val extensionFlag = r.bits(1)
        val tail = (if (aot == 6) 3 else 0) + extensionFlag
        if (r.bitsLeft < tail) return false
        r.bits(tail)
        return true
    }

    /**
     * Frecuencia de la extensión SBR implícita, o null si el ASC se acaba o el índice está
     * reservado: en esta parte opcional, un dato que no se puede leer se ignora en vez de rechazar
     * un ASC que, sin él, es un AAC-LC válido.
     */
    private fun readExtensionRate(r: BitReader): Int? {
        if (r.bitsLeft < 4) return null
        val index = r.bits(4)
        return when {
            index == 15 -> if (r.bitsLeft >= 24) r.bits(24) else null
            index < SAMPLE_RATES.size -> SAMPLE_RATES[index]
            else -> null
        }
    }

    /** Índice 15 = frecuencia explícita de 24 bits; el resto indexa [SAMPLE_RATES]. */
    private fun readRate(r: BitReader, freqIndex: Int): Int = when {
        freqIndex == 15 -> r.bits(24)
        freqIndex < SAMPLE_RATES.size -> SAMPLE_RATES[freqIndex]
        else -> throw IllegalArgumentException("índice de frecuencia AAC reservado: $freqIndex")
    }
}
