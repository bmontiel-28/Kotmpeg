package com.braymon.kotmpeg.mp4

/**
 * El índice de acceso aleatorio de un MP4 fragmentado (`mfra`/`tfra`/`mfro`), que escriben igual
 * [FragmentedMp4Muxer] al cerrar y la reparación en el sitio.
 */
internal object FragmentIndex {

    /**
     * Entrada del índice `tfra`: dónde está el `moof` y, dentro de él, la posición exacta de
     * la muestra clave — el `traf` de la pista de cues, el `trun` dentro de ese `traf` y el
     * ordinal de la muestra dentro del `trun`, los tres en base 1 como pide la
     * especificación.
     */
    class Entry(
        val timeTicks: Long,
        val moofOffset: Long,
        val trafNumber: Long,
        val trunNumber: Long,
        val sampleNumber: Long,
    )

    /**
     * El `mfra` completo para la pista [trackId]. Sin entradas lleva solo el `mfro`.
     *
     * El `0x3F` declara los tres ordinales —fragmento, grupo y muestra— en campos de **4
     * bytes**. Antes iban de 1 byte y con el valor fijo `1`, así que el ordinal de muestra se
     * salía de rango en cuanto un fragmento pasaba de 255 muestras y el índice no apuntaba a
     * ninguna parte útil.
     */
    fun mfra(trackId: Long, entries: List<Entry>): ByteArray {
        val builder = BoxBuilder()
        builder.box("mfra") {
            if (entries.isNotEmpty()) {
                fullBox("tfra", 1, 0) {
                    u32(trackId)
                    u32(0x3F)
                    u32(entries.size)
                    for (entry in entries) {
                        u64(entry.timeTicks)
                        u64(entry.moofOffset)
                        u32(entry.trafNumber); u32(entry.trunNumber); u32(entry.sampleNumber)
                    }
                }
            }
            fullBox("mfro", 0, 0) {
                val tfraSize = if (entries.isEmpty()) 0 else 24 + entries.size * 28
                u32(8 + tfraSize + 16)
            }
        }
        return builder.toByteArray()
    }
}
