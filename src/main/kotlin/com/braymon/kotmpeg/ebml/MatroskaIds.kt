package com.braymon.kotmpeg.ebml

/**
 * Ids de elementos EBML/Matroska (RFC 8794 + especificación Matroska). Los ids se guardan
 * con su bit marcador de longitud incluido, exactamente como aparecen en el archivo.
 */
public object MatroskaIds {
    public const val EBML: Long = 0x1A45DFA3L
    public const val EBML_VERSION: Long = 0x4286L
    public const val EBML_READ_VERSION: Long = 0x42F7L
    public const val EBML_MAX_ID_LENGTH: Long = 0x42F2L
    public const val EBML_MAX_SIZE_LENGTH: Long = 0x42F3L
    public const val DOCTYPE: Long = 0x4282L
    public const val DOCTYPE_VERSION: Long = 0x4287L
    public const val DOCTYPE_READ_VERSION: Long = 0x4285L

    public const val SEGMENT: Long = 0x18538067L
    public const val SEEK_HEAD: Long = 0x114D9B74L
    public const val SEEK: Long = 0x4DBBL
    public const val SEEK_ID: Long = 0x53ABL
    public const val SEEK_POSITION: Long = 0x53ACL
    public const val VOID: Long = 0xECL
    public const val CRC32: Long = 0xBFL

    public const val INFO: Long = 0x1549A966L
    public const val SEGMENT_UID: Long = 0x73A4L
    public const val TIMESTAMP_SCALE: Long = 0x2AD7B1L
    public const val DURATION: Long = 0x4489L
    public const val MUXING_APP: Long = 0x4D80L
    public const val WRITING_APP: Long = 0x5741L
    public const val DATE_UTC: Long = 0x4461L
    public const val TITLE: Long = 0x7BA9L

    public const val TRACKS: Long = 0x1654AE6BL
    public const val TRACK_ENTRY: Long = 0xAEL
    public const val TRACK_NUMBER: Long = 0xD7L
    public const val TRACK_UID: Long = 0x73C5L
    public const val TRACK_TYPE: Long = 0x83L
    public const val FLAG_ENABLED: Long = 0xB9L
    public const val FLAG_DEFAULT: Long = 0x88L
    public const val FLAG_FORCED: Long = 0x55AAL
    public const val FLAG_LACING: Long = 0x9CL
    public const val DEFAULT_DURATION: Long = 0x23E383L
    public const val TRACK_TIMESTAMP_SCALE: Long = 0x23314FL
    public const val NAME: Long = 0x536EL
    public const val LANGUAGE: Long = 0x22B59CL
    public const val CODEC_ID: Long = 0x86L
    public const val CODEC_PRIVATE: Long = 0x63A2L
    public const val CODEC_NAME: Long = 0x258688L
    public const val CODEC_DELAY: Long = 0x56AAL
    public const val SEEK_PRE_ROLL: Long = 0x56BBL

    public const val VIDEO: Long = 0xE0L
    public const val PIXEL_WIDTH: Long = 0xB0L
    public const val PIXEL_HEIGHT: Long = 0xBAL
    public const val DISPLAY_WIDTH: Long = 0x54B0L
    public const val DISPLAY_HEIGHT: Long = 0x54BAL
    public const val DISPLAY_UNIT: Long = 0x54B2L
    public const val FLAG_INTERLACED: Long = 0x9AL

    public const val COLOUR: Long = 0x55B0L
    public const val MATRIX_COEFFICIENTS: Long = 0x55B1L
    public const val COLOUR_RANGE: Long = 0x55B9L
    public const val TRANSFER_CHARACTERISTICS: Long = 0x55BAL
    public const val COLOUR_PRIMARIES: Long = 0x55BBL
    public const val MAX_CLL: Long = 0x55BCL
    public const val MAX_FALL: Long = 0x55BDL
    public const val MASTERING_METADATA: Long = 0x55D0L
    public const val PRIMARY_R_X: Long = 0x55D1L
    public const val PRIMARY_R_Y: Long = 0x55D2L
    public const val PRIMARY_G_X: Long = 0x55D3L
    public const val PRIMARY_G_Y: Long = 0x55D4L
    public const val PRIMARY_B_X: Long = 0x55D5L
    public const val PRIMARY_B_Y: Long = 0x55D6L
    public const val WHITE_POINT_X: Long = 0x55D7L
    public const val WHITE_POINT_Y: Long = 0x55D8L
    public const val LUMINANCE_MAX: Long = 0x55D9L
    public const val LUMINANCE_MIN: Long = 0x55DAL

    public const val PROJECTION: Long = 0x7670L
    public const val PROJECTION_TYPE: Long = 0x7671L
    public const val PROJECTION_POSE_YAW: Long = 0x7673L
    public const val PROJECTION_POSE_PITCH: Long = 0x7674L
    public const val PROJECTION_POSE_ROLL: Long = 0x7675L

    public const val AUDIO: Long = 0xE1L
    public const val SAMPLING_FREQUENCY: Long = 0xB5L
    public const val OUTPUT_SAMPLING_FREQUENCY: Long = 0x78B5L
    public const val CHANNELS: Long = 0x9FL
    public const val BIT_DEPTH: Long = 0x6264L

    public const val CLUSTER: Long = 0x1F43B675L
    public const val CLUSTER_TIMESTAMP: Long = 0xE7L
    public const val SIMPLE_BLOCK: Long = 0xA3L
    public const val BLOCK_GROUP: Long = 0xA0L
    public const val BLOCK: Long = 0xA1L
    public const val BLOCK_DURATION: Long = 0x9BL
    public const val REFERENCE_BLOCK: Long = 0xFBL

    public const val CUES: Long = 0x1C53BB6BL
    public const val CUE_POINT: Long = 0xBBL
    public const val CUE_TIME: Long = 0xB3L
    public const val CUE_TRACK_POSITIONS: Long = 0xB7L
    public const val CUE_TRACK: Long = 0xF7L
    public const val CUE_CLUSTER_POSITION: Long = 0xF1L
    public const val CUE_RELATIVE_POSITION: Long = 0xF0L

    public const val TAGS: Long = 0x1254C367L
    public const val CHAPTERS: Long = 0x1043A770L
    public const val ATTACHMENTS: Long = 0x1941A469L

    public const val TRACK_TYPE_VIDEO: Long = 1L
    public const val TRACK_TYPE_AUDIO: Long = 2L
    public const val TRACK_TYPE_SUBTITLE: Long = 17L
}
