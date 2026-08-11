package com.braymon.kotmpeg.model

import kotlin.math.roundToLong

/**
 * Descripción de una pista dentro de un contenedor.
 *
 * La configuración específica del códec ([codecPrivate]) usa un formato binario canónico
 * por códec, independiente del contenedor:
 *  - H.264: `AVCDecoderConfigurationRecord` (avcC) — los mismos bytes que guardan el `avcC`
 *    de MP4 y el `CodecPrivate` de MKV.
 *  - H.265: `HEVCDecoderConfigurationRecord` (hvcC).
 *  - AAC:   `AudioSpecificConfig` (la carga del DecoderSpecificInfo del `esds` de MP4).
 *  - Opus:  cabecera de identificación `OpusHead` (el formato del `CodecPrivate` de MKV;
 *    se convierte a/desde el `dOps` de MP4).
 *
 * Las cargas de vídeo H.264/H.265 se guardan como NALUs con prefijo de longitud de 4 bytes
 * (formato ISO, usado tal cual por MKV y MP4). Usa [com.braymon.kotmpeg.codecconfig.NalUnits]
 * para convertir desde streams Annex-B (p. ej. la salida de MediaCodec de Android).
 */
public sealed class TrackInfo {
    /** Id de pista, único dentro del contenedor. Lo asigna el muxer/demuxer. */
    public abstract val id: Int
    public abstract val codecPrivate: ByteArray?
    /** Código de idioma ISO 639-2, p. ej. "und", "spa", "eng". */
    public abstract val language: String
    /** Nombre legible de la pista, opcional. */
    public abstract val name: String?
    /** Duración por defecto de fotograma/paquete en microsegundos, 0 si es variable/desconocida. */
    public abstract val defaultDurationUs: Long

    /**
     * Duración nominal por muestra en **nanosegundos**, que es la unidad en la que Matroska
     * define `DefaultDuration`.
     *
     * Existe aparte de [defaultDurationUs] porque el microsegundo no alcanza para las cadencias
     * más comunes: 1/60 s son 16 666,67 µs, y cualquier redondeo a µs deja el valor a cientos de
     * nanosegundos del real. Un reproductor que se fíe de ahí deriva unos 4 ms por minuto de
     * línea de tiempo, y un archivo a 60 fps se anuncia como 60,0024.
     *
     * La implementación por defecto deriva de [defaultDurationUs]; quien conozca la cadencia
     * exacta debería sobrescribirla, como hace [Video].
     */
    public open val defaultDurationNs: Long get() = defaultDurationUs * 1_000

    /**
     * Si el reproductor debe elegir esta pista cuando el usuario no ha elegido ninguna.
     *
     * **Con varias pistas del mismo tipo, exactamente una debería llevarlo a `true`.** Si todas
     * lo llevan —que es lo que ocurre si no se toca— cada reproductor elige una distinta, así que
     * una grabación con mezcla, micrófono y audio de sistema suena diferente según el programa
     * con que se abra.
     *
     * Se escribe como `FlagDefault` en Matroska. **MP4 no tiene equivalente**, así que este dato
     * no sobrevive a una conversión a MP4 y vuelta.
     */
    public abstract val default: Boolean

    public data class Video(
        override val id: Int = 0,
        val codec: VideoCodec,
        val width: Int,
        val height: Int,
        /** Dimensiones de presentación; por defecto las codificadas. */
        val displayWidth: Int = width,
        val displayHeight: Int = height,
        /** Tasa de fotogramas nominal, usada para el metadato DefaultDuration. 0 = variable/desconocida. */
        val frameRate: Double = 0.0,
        /**
         * Rotación de presentación en grados (0, 90, 180, 270), aplicada por el reproductor.
         * Se guarda como matriz `tkhd` en MP4 / `ProjectionPoseRoll` en Matroska.
         */
        val rotationDegrees: Int = 0,
        /** Descripción de color y HDR estático (`colr`/`mdcv`/`clli`, `Colour` de MKV). */
        val color: ColorInfo? = null,
        override val codecPrivate: ByteArray? = null,
        override val language: String = "und",
        override val name: String? = null,
        override val default: Boolean = true,
    ) : TrackInfo() {
        init {
            require(rotationDegrees in intArrayOf(0, 90, 180, 270)) {
                "rotationDegrees debe ser 0, 90, 180 o 270"
            }
            require(width > 0 && height > 0) { "dimensiones de vídeo inválidas: ${width}x$height" }
            require(displayWidth > 0 && displayHeight > 0) {
                "dimensiones de presentación inválidas: ${displayWidth}x$displayHeight"
            }
            require(frameRate >= 0 && frameRate.isFinite()) { "frameRate inválido: $frameRate" }
        }

        /**
         * Se calcula desde [frameRate] y **se redondea**, no se trunca: truncar 16 666,67 daba
         * 16 666 µs para 60 fps, y de ahí salía el `DefaultDuration` que anunciaba 60,0024 fps.
         */
        override val defaultDurationNs: Long
            get() = if (frameRate > 0) (1_000_000_000.0 / frameRate).roundToLong() else 0L

        override val defaultDurationUs: Long
            get() = if (frameRate > 0) (1_000_000.0 / frameRate).roundToLong() else 0L

        /**
         * A mano y no los generados por `data class`: esos comparan [codecPrivate] por
         * **referencia**, así que dos pistas con el mismo códec, dimensiones y `avcC` no salían
         * iguales. Afecta a quien deduplique pistas, cachee por pista o escriba un test de ida
         * y vuelta. [hashCode] va emparejado por el mismo motivo.
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Video) return false
            return id == other.id &&
                codec == other.codec &&
                width == other.width &&
                height == other.height &&
                displayWidth == other.displayWidth &&
                displayHeight == other.displayHeight &&
                frameRate == other.frameRate &&
                rotationDegrees == other.rotationDegrees &&
                color == other.color &&
                codecPrivate.contentEquals(other.codecPrivate) &&
                language == other.language &&
                name == other.name &&
                default == other.default
        }

        override fun hashCode(): Int {
            var result = id
            result = 31 * result + codec.hashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + displayWidth
            result = 31 * result + displayHeight
            result = 31 * result + frameRate.hashCode()
            result = 31 * result + rotationDegrees
            result = 31 * result + (color?.hashCode() ?: 0)
            result = 31 * result + (codecPrivate?.contentHashCode() ?: 0)
            result = 31 * result + language.hashCode()
            result = 31 * result + (name?.hashCode() ?: 0)
            result = 31 * result + default.hashCode()
            return result
        }
    }

    public data class Audio(
        override val id: Int = 0,
        val codec: AudioCodec,
        /**
         * Frecuencia de muestreo en Hz.
         *
         * Se escribe y se relee sin pérdida en ambos contenedores. La única salvedad es
         * cosmética: el campo `samplerate` del SampleEntry de MP4 es punto fijo 16.16, así
         * que **por encima de 65 535 Hz ese campo concreto se satura**. No afecta a la
         * decodificación ni al round-trip —la frecuencia real viaja en `esds`/`dOps`, que es
         * de donde la lee cualquier decodificador y también nuestro demuxer—, pero una
         * herramienta que inspeccione esa cabecera del contenedor verá 65 535. MKV no tiene
         * este límite: su `SamplingFrequency` es un float.
         */
        val sampleRate: Int,
        val channelCount: Int,
        val bitDepth: Int = 0,
        /**
         * Duración del cebado que el decodificador debe descartar, **en microsegundos** (como
         * indica el sufijo `Us`), no en muestras.
         *
         * Vale para **cualquier códec con retardo de arranque**, no solo para Opus: un AAC-LC a
         * 48 kHz ronda los 21 ms, y sin declararlo el audio queda por detrás del vídeo. En Opus,
         * si hay `codecPrivate`, el `preSkip` de su `OpusHead` tiene preferencia sobre este campo.
         *
         * Si partes del `preSkip` crudo de una cabecera OpusHead (que sí va en muestras a
         * 48 kHz), conviértelo antes: `codecDelayUs = preSkip * 1_000_000L / 48_000`. Con
         * el valor por defecto de Opus, `preSkip = 312` equivale a `codecDelayUs = 6500`.
         * Pasar 312 aquí daría un retardo ~20 veces menor del real: clic audible al inicio
         * y desfase de audio. [com.braymon.kotmpeg.codecconfig.OpusConfig.Parsed.codecDelayUs]
         * hace la conversión.
         *
         * Se mapea a CodecDelay en MKV y a edit lists en MP4.
         */
        val codecDelayUs: Long = 0,
        override val codecPrivate: ByteArray? = null,
        override val language: String = "und",
        override val name: String? = null,
        override val default: Boolean = true,
    ) : TrackInfo() {
        init {
            require(sampleRate > 0) { "sampleRate inválido: $sampleRate" }
            require(channelCount in 1..64) { "channelCount inválido: $channelCount" }
            require(bitDepth >= 0) { "bitDepth inválido: $bitDepth" }
            require(codecDelayUs >= 0) { "codecDelayUs inválido: $codecDelayUs" }
        }

        override val defaultDurationUs: Long get() = 0L

        /** Mismo motivo que en [Video]: `codecPrivate` es un array y hay que comparar contenido. */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Audio) return false
            return id == other.id &&
                codec == other.codec &&
                sampleRate == other.sampleRate &&
                channelCount == other.channelCount &&
                bitDepth == other.bitDepth &&
                codecDelayUs == other.codecDelayUs &&
                codecPrivate.contentEquals(other.codecPrivate) &&
                language == other.language &&
                name == other.name &&
                default == other.default
        }

        override fun hashCode(): Int {
            var result = id
            result = 31 * result + codec.hashCode()
            result = 31 * result + sampleRate
            result = 31 * result + channelCount
            result = 31 * result + bitDepth
            result = 31 * result + codecDelayUs.hashCode()
            result = 31 * result + (codecPrivate?.contentHashCode() ?: 0)
            result = 31 * result + language.hashCode()
            result = 31 * result + (name?.hashCode() ?: 0)
            result = 31 * result + default.hashCode()
            return result
        }
    }

    public fun withId(newId: Int): TrackInfo = when (this) {
        is Video -> copy(id = newId)
        is Audio -> copy(id = newId)
    }
}
