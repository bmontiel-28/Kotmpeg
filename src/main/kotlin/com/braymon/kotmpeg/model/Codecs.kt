package com.braymon.kotmpeg.model

/** Códecs de vídeo soportados por el motor de contenedores. */
public enum class VideoCodec(
    /** CodecID de Matroska. */
    public val matroskaId: String,
    /** Código de cuatro caracteres del sample entry ISO BMFF. */
    public val mp4SampleEntry: String,
    /** Tipo MIME de MediaFormat en Android. */
    public val mimeType: String,
) {
    H264("V_MPEG4/ISO/AVC", "avc1", "video/avc"),
    H265("V_MPEGH/ISO/HEVC", "hvc1", "video/hevc");

    public companion object {
        public fun fromMatroskaId(id: String): VideoCodec? = entries.firstOrNull { it.matroskaId == id }
        public fun fromMimeType(mime: String): VideoCodec? = entries.firstOrNull { it.mimeType.equals(mime, ignoreCase = true) }
    }
}

/** Códecs de audio soportados por el motor de contenedores. */
public enum class AudioCodec(
    public val matroskaId: String,
    public val mp4SampleEntry: String,
    public val mimeType: String,
) {
    AAC("A_AAC", "mp4a", "audio/mp4a-latm"),
    OPUS("A_OPUS", "Opus", "audio/opus");

    public companion object {
        public fun fromMatroskaId(id: String): AudioCodec? = entries.firstOrNull { id == it.matroskaId || id.startsWith(it.matroskaId + "/") }
        public fun fromMimeType(mime: String): AudioCodec? = entries.firstOrNull { it.mimeType.equals(mime, ignoreCase = true) }
    }
}

/** Formatos de contenedor de salida soportados por la librería. */
public enum class ContainerFormat {
    MKV,
    MP4;

    public companion object {
        /**
         * El formato de salida que corresponde a la extensión de [name], o `null` si no hay
         * ninguno.
         *
         * `.webm` devuelve `null` aunque WebM sea un subconjunto de Matroska: solo admite VP8,
         * VP9 o AV1 con Vorbis u Opus, y esta librería escribe H.264/H.265 con AAC u Opus.
         * Hasta la 3.x se trataba como MKV y salía un archivo con extensión `.webm`, cabecera
         * `matroska` y códecs que un navegador no reproduce. Leer un WebM sí se puede: el
         * demuxer detecta el contenedor por su contenido, no por la extensión.
         *
         * Un nombre sin punto no tiene extensión y devuelve `null`. Hasta la 4.0 un archivo llamado
         * `mp4` o `mkv` a secas se tomaba por ese formato: sin punto, el nombre entero hacía de
         * extensión.
         */
        public fun fromFileName(name: String): ContainerFormat? = when (name.substringAfterLast('.', "").lowercase()) {
            "mkv" -> MKV
            "mp4", "m4a", "m4v", "mov" -> MP4
            else -> null
        }
    }
}
