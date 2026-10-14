package com.braymon.kotmpeg.mp4

/**
 * Un fragmento que [FragmentedMp4Muxer] acaba de escribir y volcar al archivo, tal como lo
 * entrega `onSegment` de `MuxerOptions`: es lo que necesita una app que empaqueta en vivo para
 * añadirlo a su lista de reproducción sin releer el archivo.
 *
 * En HLS con rangos de bytes cada segmento es una entrada `#EXTINF:` con
 * `#EXT-X-BYTERANGE:<size>@<offset>`, y el segmento de inicialización es
 * `#EXT-X-MAP:URI="…",BYTERANGE="<initSegmentSize>@0"`.
 *
 * Los tiempos son los de la pista de referencia del archivo —la de vídeo si la hay, si no la
 * primera—, en microsegundos desde el origen de la línea de tiempo del muxer. Si un fragmento no
 * trae muestras de esa pista —un vídeo que se queda sin fotogramas y el corte lo hace el límite de
 * duración—, son los de la primera pista que sí trae.
 */
public class Mp4Segment internal constructor(
    /** Número de secuencia del `moof`, desde 1, el mismo que lleva su `mfhd`. */
    public val sequenceNumber: Long,
    /**
     * Posición del primer byte del segmento: el primer `sidx` si se escriben, si no el `moof`.
     */
    public val offset: Long,
    /** Bytes del segmento, del primer `sidx` (o del `moof`) al final del `mdat`. */
    public val size: Long,
    /**
     * Tiempo de presentación más temprano de la pista de referencia en el segmento. Puede ser
     * negativo en el primero, si un fotograma se muestra antes que el que abrió la línea de tiempo.
     */
    public val startUs: Long,
    /** Duración de la pista de referencia en el segmento. */
    public val durationUs: Long,
    /** Si el segmento empieza en un keyframe de la pista de referencia, que es donde se puede entrar. */
    public val startsWithKeyFrame: Boolean,
    /**
     * Bytes de la cabecera (`ftyp` + `moov`), que es el segmento de inicialización y ocupa
     * `[0, initSegmentSize)` del archivo; el mismo en todos los segmentos. Hasta la 3.x era una
     * propiedad del muxer, que valía 0 hasta `start()`.
     */
    public val initSegmentSize: Long,
) {
    override fun toString(): String =
        "Mp4Segment(sequenceNumber=$sequenceNumber, offset=$offset, size=$size, startUs=$startUs, " +
            "durationUs=$durationUs, startsWithKeyFrame=$startsWithKeyFrame, initSegmentSize=$initSegmentSize)"
}
