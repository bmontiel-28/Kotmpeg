package com.braymon.kotmpeg.mp4

/**
 * Los bits de los flags de `tfhd` y `trun` y el bit de muestra no sincronizada de los
 * `sample_flags` (ISO/IEC 14496-12 §8.8.7, §8.8.8 y §8.8.3.1), con el nombre de la
 * especificación.
 *
 * Están en un solo sitio porque los leen y escriben tres piezas —el demuxer, el muxer
 * fragmentado y la reparación— y un número mágico copiado en cada una es justo lo que acaba
 * discrepando: el `0x01` de `tfhd` (base de 64 bits) y el de `trun` (desplazamiento de 32) se
 * escriben igual y no significan lo mismo, y confundirlos no da ningún error, solo un offset mal
 * leído.
 */
internal object FragmentFlags {
    /** `tfhd`: lleva `base_data_offset` de 64 bits. */
    const val TFHD_BASE_DATA_OFFSET: Int = 0x000001

    /** `tfhd`: lleva `sample_description_index`. */
    const val TFHD_SAMPLE_DESCRIPTION_INDEX: Int = 0x000002

    /** `tfhd`: lleva `default_sample_duration`. */
    const val TFHD_DEFAULT_SAMPLE_DURATION: Int = 0x000008

    /** `tfhd`: lleva `default_sample_size`. */
    const val TFHD_DEFAULT_SAMPLE_SIZE: Int = 0x000010

    /** `tfhd`: lleva `default_sample_flags`. */
    const val TFHD_DEFAULT_SAMPLE_FLAGS: Int = 0x000020

    /** `tfhd`: sin `base_data_offset`, la base es el inicio del `moof` y no el final del `traf` anterior. */
    const val TFHD_DEFAULT_BASE_IS_MOOF: Int = 0x020000

    /** `trun`: lleva `data_offset`, relativo a la base del `traf`. */
    const val TRUN_DATA_OFFSET: Int = 0x000001

    /** `trun`: lleva `first_sample_flags`, que sustituye a los de la primera muestra. */
    const val TRUN_FIRST_SAMPLE_FLAGS: Int = 0x000004

    /** `trun`: cada muestra lleva su `sample_duration`. */
    const val TRUN_SAMPLE_DURATION: Int = 0x000100

    /** `trun`: cada muestra lleva su `sample_size`. */
    const val TRUN_SAMPLE_SIZE: Int = 0x000200

    /** `trun`: cada muestra lleva sus `sample_flags`. */
    const val TRUN_SAMPLE_FLAGS: Int = 0x000400

    /** `trun`: cada muestra lleva su `sample_composition_time_offset`. */
    const val TRUN_SAMPLE_COMPOSITION_TIME_OFFSET: Int = 0x000800

    /** `sample_flags`: `sample_is_non_sync_sample`; sin él, la muestra es un punto de acceso. */
    const val SAMPLE_IS_NON_SYNC: Long = 0x10000L

    /**
     * Bytes por muestra de un `trun` con estos [flags]: cuatro por cada campo por muestra que
     * declare. Cero significa que todas toman los valores por defecto.
     */
    fun trunEntryBytes(flags: Int): Int {
        var bytes = 0
        if (flags and TRUN_SAMPLE_DURATION != 0) bytes += 4
        if (flags and TRUN_SAMPLE_SIZE != 0) bytes += 4
        if (flags and TRUN_SAMPLE_FLAGS != 0) bytes += 4
        if (flags and TRUN_SAMPLE_COMPOSITION_TIME_OFFSET != 0) bytes += 4
        return bytes
    }
}
