package com.braymon.kotmpeg.mp4

/**
 * Las duraciones que declara la cabecera de un fMP4 —`mvhd`, `mehd`, cada `tkhd` y el
 * `segment_duration` de cada lista de edición—, con un solo criterio para [FragmentedMp4Muxer], que
 * las parchea tras cada fragmento, y para [FragmentedMp4Repair], que las pone al cerrar un archivo
 * cortado. Antes estaba escrito en los dos sitios.
 *
 * Todas van en la escala del `mvhd`, también las de las pistas: `tkhd.duration` no usa la escala del
 * medio. La de cada pista es la suma de sus ediciones, como pide ISO/IEC 14496-12 §8.3.2.3, y el
 * `mvhd` y el `mehd` llevan la mayor, igual que en [Mp4Muxer]. Una pista lleva como mucho una
 * edición, así que su duración es la de esa edición: hasta donde acaba de decodificarse, menos el
 * cebado del codificador, que es lo que la edición salta al principio. En audio es lo que no se
 * presenta; en vídeo nada, porque su `media_time` solo compensa el desplazamiento de los desfases
 * sin signo, que mueve igual el principio y el final de la presentación. Una pista sin edición no
 * tiene cebado que descontar y dura hasta donde acaba de decodificarse.
 *
 * Hasta la 4.0 el `tkhd` contaba el cebado y la edición no: con el audio como pista más larga, el
 * archivo declaraba en el `mvhd` y el `mehd` unos 21 ms de más con AAC-LC.
 */
internal object FragmentDurations {

    /**
     * Las duraciones de la cabecera en la escala del `mvhd`: la del archivo y la de cada pista, que es
     * a la vez la de su `tkhd` y la de su edición.
     */
    class Declared(val movie: Long, val tracks: List<Long>)

    /**
     * Las duraciones para unas pistas que se decodifican hasta [decodeEnds] y cuyo cebado es
     * [priming], las dos en la unidad de cada pista. [toMovie] pasa un valor de la pista `i` a la
     * escala del `mvhd`: el muxer lleva los tiempos en µs y la reparación en la escala de cada pista.
     */
    fun declared(decodeEnds: LongArray, priming: LongArray, toMovie: (track: Int, value: Long) -> Long): Declared {
        val tracks = decodeEnds.indices.map { toMovie(it, (decodeEnds[it] - priming[it]).coerceAtLeast(0L)) }
        return Declared(tracks.maxOrNull() ?: 0L, tracks)
    }

    /**
     * Un campo de duración tal como se escribe: de 8 bytes si es [wide], y si no de 4, saturado.
     *
     * `mvhd` y `tkhd` se emiten en versión 0, con un hueco de 32 bits, salvo que la fecha de creación
     * no quepa en él (ver `MovieBoxes.headerVersion`), y a estas alturas ya están en disco: no se les
     * puede cambiar la versión. El tope son 49,7 días de grabación continua; pasado eso vale más un
     * campo saturado que uno truncado por arriba, que daría una duración absurdamente corta.
     */
    fun durationBytes(value: Long, wide: Boolean): ByteArray =
        if (wide) bigEndian(value, 8) else bigEndian(clampToU32(value), 4)

    /** [value] dentro de lo que cabe en un campo de 32 bits sin signo. */
    fun clampToU32(value: Long): Long = value.coerceIn(0L, 0xFFFFFFFFL)

    /** Los [count] bytes bajos de [value], el más significativo primero. */
    fun bigEndian(value: Long, count: Int): ByteArray =
        ByteArray(count) { i -> (value ushr (8 * (count - 1 - i))).toByte() }
}
