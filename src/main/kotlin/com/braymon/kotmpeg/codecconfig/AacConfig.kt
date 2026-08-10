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
        require(audioObjectType in 1..31) { "object type AAC fuera de rango: $audioObjectType" }

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
     */
    public class Parsed(
        public val audioObjectType: Int,
        /** Frecuencia de la señal decodificada. Con SBR es la de extensión, no la del núcleo. */
        public val sampleRate: Int,
        /** Canales de la señal decodificada. Con PS son 2 aunque el núcleo sea mono. */
        public val channelCount: Int,
        /** Frecuencia del núcleo tal cual la declara el ASC. Igual a [sampleRate] sin SBR. */
        public val coreSampleRate: Int = sampleRate,
        /**
         * Canales del núcleo del bitstream. Igual a [channelCount] salvo con PS, donde vale 1.
         *
         * Es un número de canales, no el `channelConfiguration` crudo: el valor 7 de ese campo
         * significa 7.1 y aquí ya llega convertido a 8, igual que en [channelCount].
         */
        public val coreChannelCount: Int = channelCount,
    )

    public fun parse(asc: ByteArray): Parsed {
        require(asc.size >= 2) { "AudioSpecificConfig demasiado corto" }
        val r = BitReader(asc)
        var aot = r.bits(5)
        if (aot == 31) aot = 32 + r.bits(6)
        var freqIndex = r.bits(4)
        val coreRate = readRate(r, freqIndex)
        var rate = coreRate
        var channels = r.bits(4)
        if (channels == 7) channels = 8
        val coreChannels = channels

        if (aot == AOT_SBR || aot == AOT_PS) {
            freqIndex = r.bits(4)
            rate = readRate(r, freqIndex)
        }
        if (aot == AOT_PS && channels == 1) channels = 2

        return Parsed(aot, rate, channels, coreSampleRate = coreRate, coreChannelCount = coreChannels)
    }

    /** Índice 15 = frecuencia explícita de 24 bits; el resto indexa [SAMPLE_RATES]. */
    private fun readRate(r: BitReader, freqIndex: Int): Int = when {
        freqIndex == 15 -> r.bits(24)
        freqIndex < SAMPLE_RATES.size -> SAMPLE_RATES[freqIndex]
        else -> throw IllegalArgumentException("índice de frecuencia AAC reservado: $freqIndex")
    }
}
