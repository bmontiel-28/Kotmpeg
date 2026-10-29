package com.braymon.kotmpeg.mp4

/**
 * Cuándo [FragmentedMp4Muxer] cierra un fragmento por lo que ocupan sus datos.
 *
 * Además de `maxFragmentBytes`, que fija quien usa el muxer, hay un tope interno de
 * [MAX_DATA_BYTES]: el `data_offset` de cada `trun` es un entero de 32 bits **con signo** relativo al
 * `moof`, y con varias pistas el de la segunda apunta detrás de todos los datos de la primera. Sin
 * `segmentIndex`, `maxFragmentBytes` no tiene tope, y un fragmento con más de 2 GiB de vídeo dejaba
 * el `data_offset` del audio truncado: el audio de ese fragmento se leía de cualquier parte. Con el
 * tope, los datos de un fragmento con más de una muestra nunca pasan de 1 GiB, así que todos los
 * `data_offset` caben y el `mdat` cabe en un tamaño de 32 bits. Lo único que lo supera es una sola
 * muestra mayor, que va sola en su fragmento: un `ByteArray` no llega a 2 GiB.
 */
internal object FragmentLimits {

    /** Tope interno de datos de un fragmento con más de una muestra. */
    const val MAX_DATA_BYTES: Long = 1L shl 30

    /**
     * Si el fragmento en curso, que retiene [heldBytes] de datos, tiene que cerrarse antes de
     * añadirle una muestra de [nextBytes]: porque ya llegó a [maxFragmentBytes], que como hasta
     * ahora se puede pasar en lo que ocupe la muestra que lo hace cortarse, o porque con ella se
     * pasaría de [MAX_DATA_BYTES].
     */
    fun isFull(heldBytes: Long, nextBytes: Int, maxFragmentBytes: Long): Boolean =
        heldBytes >= maxFragmentBytes || heldBytes + nextBytes > MAX_DATA_BYTES
}
