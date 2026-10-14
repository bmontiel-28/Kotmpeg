package com.braymon.kotmpeg.model

import kotlin.math.roundToLong

/**
 * Descripción de una pista dentro de un contenedor.
 *
 * Los datos sin los que la pista no existe van como argumentos, y el resto en un bloque de
 * configuración; [Video.copy] y [Audio.copy] usan el mismo bloque:
 *
 * ```kotlin
 * val video = TrackInfo.Video(VideoCodec.H264, 1920, 1080) {
 *     codecPrivate = avcC
 *     frameRate = 30.0
 * }
 * val audio = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = asc; codecDelayUs = 21_333 }
 * val rotado = video.copy { rotationDegrees = 90 }
 * ```
 *
 * Hasta la 3.x eran `data class` con parámetros por defecto, y cada campo nuevo cambiaba la firma
 * JVM del constructor y de `copy`: añadir uno rompía a quien ya había compilado contra la versión
 * anterior. Una propiedad nueva en el bloque de configuración no rompe a nadie.
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
    /**
     * Id de pista, único dentro del contenedor. Lo asigna el muxer al registrarla y el demuxer al
     * leerla; una pista recién construida lleva 0.
     */
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
     * `null`, el valor por defecto, deja que lo decida el muxer: es pista por defecto **la
     * primera de cada tipo**, salvo que otra del mismo tipo lleve `true`. Así una grabación con
     * mezcla, micrófono y audio de sistema tiene exactamente una pista de audio por defecto sin
     * tocar nada. Hasta la 3.x valía `true` para todas, y con varias pistas del mismo tipo cada
     * reproductor elegía una distinta: la misma grabación sonaba diferente según el programa.
     *
     * Se escribe como `FlagDefault` en Matroska y como el bit `track_enabled` del `tkhd` en MP4,
     * igual que hace FFmpeg. Los demuxers siempre lo devuelven resuelto, `true` o `false`, con lo
     * que dice el archivo.
     */
    public abstract val default: Boolean?

    /** La misma pista con otro [id]: es como el muxer y el demuxer numeran las suyas. */
    internal abstract fun withId(newId: Int): TrackInfo

    /**
     * Pista de vídeo. [codec], [width] y [height] son obligatorios; el resto se configura en el
     * bloque de [Builder].
     */
    public class Video private constructor(builder: Builder, override val id: Int) : TrackInfo() {

        public constructor(codec: VideoCodec, width: Int, height: Int, configure: Builder.() -> Unit = {}) :
            this(Builder(codec, width, height).apply(configure), 0)

        public val codec: VideoCodec = builder.codec

        /** Dimensiones codificadas, en píxeles. */
        public val width: Int = builder.width
        public val height: Int = builder.height

        /** Dimensiones de presentación; si no se configuran, las codificadas. */
        public val displayWidth: Int = builder.displayWidth ?: width
        public val displayHeight: Int = builder.displayHeight ?: height

        /** Tasa de fotogramas nominal, usada para el metadato DefaultDuration. 0 = variable/desconocida. */
        public val frameRate: Double = builder.frameRate

        /**
         * Rotación de presentación en grados (0, 90, 180, 270), aplicada por el reproductor.
         * Se guarda como matriz `tkhd` en MP4 / `ProjectionPoseRoll` en Matroska.
         */
        public val rotationDegrees: Int = builder.rotationDegrees

        /** Descripción de color y HDR estático (`colr`/`mdcv`/`clli`, `Colour` de MKV). */
        public val color: ColorInfo? = builder.color

        override val codecPrivate: ByteArray? = builder.codecPrivate
        override val language: String = builder.language
        override val name: String? = builder.name
        override val default: Boolean? = builder.default

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

        /** Una copia con lo que cambie [configure]; el [id] se conserva. */
        public fun copy(configure: Builder.() -> Unit): Video = Video(toBuilder().apply(configure), id)

        override fun withId(newId: Int): Video = Video(toBuilder(), newId)

        /**
         * Las dimensiones de presentación que coinciden con las codificadas se dejan sin fijar,
         * para que una copia que cambie [width] y [height] las arrastre consigo.
         */
        private fun toBuilder(): Builder = Builder(codec, width, height).also {
            it.displayWidth = displayWidth.takeIf { w -> w != width }
            it.displayHeight = displayHeight.takeIf { h -> h != height }
            it.frameRate = frameRate
            it.rotationDegrees = rotationDegrees
            it.color = color
            it.codecPrivate = codecPrivate
            it.language = language
            it.name = name
            it.default = default
        }

        /**
         * Se calcula desde [frameRate] y **se redondea**, no se trunca: truncar 16 666,67 daba
         * 16 666 µs para 60 fps, y de ahí salía el `DefaultDuration` que anunciaba 60,0024 fps.
         */
        override val defaultDurationNs: Long
            get() = if (frameRate > 0) (1_000_000_000.0 / frameRate).roundToLong() else 0L

        override val defaultDurationUs: Long
            get() = if (frameRate > 0) (1_000_000.0 / frameRate).roundToLong() else 0L

        /** Compara [codecPrivate] por contenido, no por referencia: dos `avcC` iguales son la misma pista. */
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
            result = 31 * result + (default?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "Video(id=$id, codec=$codec, ${width}x$height, display=${displayWidth}x$displayHeight, " +
                "frameRate=$frameRate, rotation=$rotationDegrees, color=$color, " +
                "codecPrivate=${codecPrivate?.size ?: 0} bytes, language=$language, name=$name, default=$default)"

        /** Los campos de [Video], para su constructor y para [copy]. Ver cada propiedad en [Video]. */
        public class Builder internal constructor(
            public var codec: VideoCodec,
            public var width: Int,
            public var height: Int,
        ) {
            /** `null`: las mismas que [width] y [height]. */
            public var displayWidth: Int? = null
            public var displayHeight: Int? = null
            public var frameRate: Double = 0.0
            public var rotationDegrees: Int = 0
            public var color: ColorInfo? = null
            public var codecPrivate: ByteArray? = null
            public var language: String = "und"
            public var name: String? = null
            public var default: Boolean? = null
        }
    }

    /**
     * Pista de audio. [codec], [sampleRate] y [channelCount] son obligatorios; el resto se
     * configura en el bloque de [Builder].
     */
    public class Audio private constructor(builder: Builder, override val id: Int) : TrackInfo() {

        public constructor(codec: AudioCodec, sampleRate: Int, channelCount: Int, configure: Builder.() -> Unit = {}) :
            this(Builder(codec, sampleRate, channelCount).apply(configure), 0)

        public val codec: AudioCodec = builder.codec

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
        public val sampleRate: Int = builder.sampleRate
        public val channelCount: Int = builder.channelCount

        /** Bits por muestra, solo informativo en códecs comprimidos; 0 si no se declara. */
        public val bitDepth: Int = builder.bitDepth

        /**
         * Duración del cebado que el decodificador debe descartar, **en microsegundos** (como
         * indica el sufijo `Us`), no en muestras.
         *
         * **Los tiempos de los paquetes no lo descuentan**: el primer paquete de un AAC empieza en
         * su tiempo de siempre, y lo que suena en el instante `t` es lo que el paquete de tiempo
         * `t + codecDelayUs` produce. Es lo que define Matroska para `CodecDelay` y lo que hacen
         * los dos demuxers; los muxers lo vuelven a escribir como `CodecDelay` en MKV y como lista
         * de edición en MP4. Hasta la 3.x el demuxer MP4 restaba la edición de los tiempos y
         * devolvía aquí 0, así que el mismo audio llegaba 21 ms antes desde un MP4 que desde un
         * MKV.
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
         */
        public val codecDelayUs: Long = builder.codecDelayUs

        override val codecPrivate: ByteArray? = builder.codecPrivate
        override val language: String = builder.language
        override val name: String? = builder.name
        override val default: Boolean? = builder.default

        init {
            require(sampleRate > 0) { "sampleRate inválido: $sampleRate" }
            require(channelCount in 1..64) { "channelCount inválido: $channelCount" }
            require(bitDepth >= 0) { "bitDepth inválido: $bitDepth" }
            require(codecDelayUs >= 0) { "codecDelayUs inválido: $codecDelayUs" }
        }

        /** Una copia con lo que cambie [configure]; el [id] se conserva. */
        public fun copy(configure: Builder.() -> Unit): Audio = Audio(toBuilder().apply(configure), id)

        override fun withId(newId: Int): Audio = Audio(toBuilder(), newId)

        private fun toBuilder(): Builder = Builder(codec, sampleRate, channelCount).also {
            it.bitDepth = bitDepth
            it.codecDelayUs = codecDelayUs
            it.codecPrivate = codecPrivate
            it.language = language
            it.name = name
            it.default = default
        }

        override val defaultDurationUs: Long get() = 0L

        /** Compara [codecPrivate] por contenido, no por referencia, igual que [Video]. */
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
            result = 31 * result + (default?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "Audio(id=$id, codec=$codec, sampleRate=$sampleRate, channels=$channelCount, bitDepth=$bitDepth, " +
                "codecDelayUs=$codecDelayUs, codecPrivate=${codecPrivate?.size ?: 0} bytes, language=$language, " +
                "name=$name, default=$default)"

        /** Los campos de [Audio], para su constructor y para [copy]. Ver cada propiedad en [Audio]. */
        public class Builder internal constructor(
            public var codec: AudioCodec,
            public var sampleRate: Int,
            public var channelCount: Int,
        ) {
            public var bitDepth: Int = 0
            public var codecDelayUs: Long = 0
            public var codecPrivate: ByteArray? = null
            public var language: String = "und"
            public var name: String? = null
            public var default: Boolean? = null
        }
    }
}

/** La resolución de [TrackInfo.default] que comparten los tres muxers. */
internal object TrackDefaults {
    /**
     * Lo que se escribe para cada una de [tracks]: el valor explícito si lo hay, y si es `null`,
     * `true` solo para la primera pista de su tipo, y solo si ninguna otra de ese tipo lleva `true`.
     * Así el mismo conjunto de pistas sale igual en MKV y en MP4.
     */
    fun resolve(tracks: List<TrackInfo>): BooleanArray {
        val explicit = tracks.filter { it.default == true }.map { it is TrackInfo.Video }.toSet()
        val chosen = HashSet<Boolean>()
        return BooleanArray(tracks.size) { i ->
            val track = tracks[i]
            val isVideo = track is TrackInfo.Video
            track.default ?: (isVideo !in explicit && chosen.add(isVideo))
        }
    }
}
