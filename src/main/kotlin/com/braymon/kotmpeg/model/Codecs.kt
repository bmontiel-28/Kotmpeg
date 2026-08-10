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
        public fun fromFileName(name: String): ContainerFormat? = when (name.substringAfterLast('.').lowercase()) {
            "mkv", "webm" -> MKV
            "mp4", "m4a", "m4v", "mov" -> MP4
            else -> null
        }
    }
}
