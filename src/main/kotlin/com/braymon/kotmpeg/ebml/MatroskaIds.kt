package com.braymon.kotmpeg.ebml

/**
 * Ids de elementos EBML/Matroska (RFC 8794 + especificación Matroska). Los ids se guardan
 * con su bit marcador de longitud incluido, exactamente como aparecen en el archivo.
 */
internal object MatroskaIds {
    const val EBML: Long = 0x1A45DFA3L
    const val EBML_VERSION: Long = 0x4286L
    const val EBML_READ_VERSION: Long = 0x42F7L
    const val EBML_MAX_ID_LENGTH: Long = 0x42F2L
    const val EBML_MAX_SIZE_LENGTH: Long = 0x42F3L
    const val DOCTYPE: Long = 0x4282L
    const val DOCTYPE_VERSION: Long = 0x4287L
    const val DOCTYPE_READ_VERSION: Long = 0x4285L

    const val SEGMENT: Long = 0x18538067L
    const val SEEK_HEAD: Long = 0x114D9B74L
    const val SEEK: Long = 0x4DBBL
    const val SEEK_ID: Long = 0x53ABL
    const val SEEK_POSITION: Long = 0x53ACL
    const val VOID: Long = 0xECL
    const val CRC32: Long = 0xBFL

    const val INFO: Long = 0x1549A966L
    const val SEGMENT_UID: Long = 0x73A4L
    const val TIMESTAMP_SCALE: Long = 0x2AD7B1L
    const val DURATION: Long = 0x4489L
    const val MUXING_APP: Long = 0x4D80L
    const val WRITING_APP: Long = 0x5741L
    const val DATE_UTC: Long = 0x4461L
    const val TITLE: Long = 0x7BA9L

    const val TRACKS: Long = 0x1654AE6BL
    const val TRACK_ENTRY: Long = 0xAEL
    const val TRACK_NUMBER: Long = 0xD7L
    const val TRACK_UID: Long = 0x73C5L
    const val TRACK_TYPE: Long = 0x83L
    const val FLAG_ENABLED: Long = 0xB9L
    const val FLAG_DEFAULT: Long = 0x88L
    const val FLAG_FORCED: Long = 0x55AAL
    const val FLAG_LACING: Long = 0x9CL
    const val DEFAULT_DURATION: Long = 0x23E383L
    const val TRACK_TIMESTAMP_SCALE: Long = 0x23314FL
    const val NAME: Long = 0x536EL
    const val LANGUAGE: Long = 0x22B59CL
    const val CODEC_ID: Long = 0x86L
    const val CODEC_PRIVATE: Long = 0x63A2L
    const val CODEC_NAME: Long = 0x258688L
    const val CODEC_DELAY: Long = 0x56AAL
    const val SEEK_PRE_ROLL: Long = 0x56BBL

    const val CONTENT_ENCODINGS: Long = 0x6D80L
    const val CONTENT_ENCODING: Long = 0x6240L
    const val CONTENT_ENCODING_ORDER: Long = 0x5031L
    const val CONTENT_ENCODING_SCOPE: Long = 0x5032L
    const val CONTENT_ENCODING_TYPE: Long = 0x5033L
    const val CONTENT_COMPRESSION: Long = 0x5034L
    const val CONTENT_COMP_ALGO: Long = 0x4254L
    const val CONTENT_COMP_SETTINGS: Long = 0x4255L
    const val CONTENT_ENCRYPTION: Long = 0x5035L

    const val VIDEO: Long = 0xE0L
    const val PIXEL_WIDTH: Long = 0xB0L
    const val PIXEL_HEIGHT: Long = 0xBAL
    const val DISPLAY_WIDTH: Long = 0x54B0L
    const val DISPLAY_HEIGHT: Long = 0x54BAL
    const val DISPLAY_UNIT: Long = 0x54B2L
    const val FLAG_INTERLACED: Long = 0x9AL

    const val COLOUR: Long = 0x55B0L
    const val MATRIX_COEFFICIENTS: Long = 0x55B1L
    const val COLOUR_RANGE: Long = 0x55B9L
    const val TRANSFER_CHARACTERISTICS: Long = 0x55BAL
    const val COLOUR_PRIMARIES: Long = 0x55BBL
    const val MAX_CLL: Long = 0x55BCL
    const val MAX_FALL: Long = 0x55BDL
    const val MASTERING_METADATA: Long = 0x55D0L
    const val PRIMARY_R_X: Long = 0x55D1L
    const val PRIMARY_R_Y: Long = 0x55D2L
    const val PRIMARY_G_X: Long = 0x55D3L
    const val PRIMARY_G_Y: Long = 0x55D4L
    const val PRIMARY_B_X: Long = 0x55D5L
    const val PRIMARY_B_Y: Long = 0x55D6L
    const val WHITE_POINT_X: Long = 0x55D7L
    const val WHITE_POINT_Y: Long = 0x55D8L
    const val LUMINANCE_MAX: Long = 0x55D9L
    const val LUMINANCE_MIN: Long = 0x55DAL

    const val PROJECTION: Long = 0x7670L
    const val PROJECTION_TYPE: Long = 0x7671L
    const val PROJECTION_POSE_YAW: Long = 0x7673L
    const val PROJECTION_POSE_PITCH: Long = 0x7674L
    const val PROJECTION_POSE_ROLL: Long = 0x7675L

    const val AUDIO: Long = 0xE1L
    const val SAMPLING_FREQUENCY: Long = 0xB5L
    const val OUTPUT_SAMPLING_FREQUENCY: Long = 0x78B5L
    const val CHANNELS: Long = 0x9FL
    const val BIT_DEPTH: Long = 0x6264L

    const val CLUSTER: Long = 0x1F43B675L
    const val CLUSTER_TIMESTAMP: Long = 0xE7L
    const val SIMPLE_BLOCK: Long = 0xA3L
    const val BLOCK_GROUP: Long = 0xA0L
    const val BLOCK: Long = 0xA1L
    const val BLOCK_DURATION: Long = 0x9BL
    const val REFERENCE_BLOCK: Long = 0xFBL

    const val CUES: Long = 0x1C53BB6BL
    const val CUE_POINT: Long = 0xBBL
    const val CUE_TIME: Long = 0xB3L
    const val CUE_TRACK_POSITIONS: Long = 0xB7L
    const val CUE_TRACK: Long = 0xF7L
    const val CUE_CLUSTER_POSITION: Long = 0xF1L
    const val CUE_RELATIVE_POSITION: Long = 0xF0L

    const val TAGS: Long = 0x1254C367L
    const val CHAPTERS: Long = 0x1043A770L
    const val ATTACHMENTS: Long = 0x1941A469L

    const val TRACK_TYPE_VIDEO: Long = 1L
    const val TRACK_TYPE_AUDIO: Long = 2L
    const val TRACK_TYPE_SUBTITLE: Long = 17L
}
