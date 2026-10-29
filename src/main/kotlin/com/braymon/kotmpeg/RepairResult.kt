package com.braymon.kotmpeg

import com.braymon.kotmpeg.model.ContainerFormat

/**
 * Lo que hizo [Kotmpeg.repair] con un archivo.
 */
public class RepairResult internal constructor(
    /** El contenedor: MKV, o MP4 fragmentado. */
    public val format: ContainerFormat,
    /**
     * Si se modificó el archivo. `false` cuando ya estaba cerrado —el muxer llegó a `stop()`, o se
     * reparó antes— y se dejó tal cual.
     */
    public val repaired: Boolean,
    /** La duración que declara el archivo al terminar, en µs. */
    public val durationUs: Long,
    /**
     * Bytes que se quitaron del final: lo que el corte dejó a medias, sea un bloque, la cabecera de
     * un cluster, un fragmento entero o un índice al final que hubo que volver a escribir. 0 si el
     * corte cayó justo entre dos, o si lo que había al final ya era el índice entero.
     */
    public val discardedBytes: Long,
) {
    override fun toString(): String =
        "RepairResult(format=$format, repaired=$repaired, durationUs=$durationUs, discardedBytes=$discardedBytes)"
}
