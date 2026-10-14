package com.braymon.kotmpeg.pipeline

import com.braymon.kotmpeg.Demuxer
import com.braymon.kotmpeg.Muxer
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo

/**
 * Pipeline de copia de streams: reescribe paquetes de un demuxer en un muxer sin
 * recodificar (el equivalente de `ffmpeg -c copy`). Funciona MKV <-> MP4 en ambas
 * direcciones porque ambos contenedores comparten aquí los mismos formatos canónicos de
 * paquete y codec-private.
 */
public object Remuxer {

    /**
     * Copia todas las pistas soportadas de [demuxer] a [muxer].
     *
     * @param trackFilter  devuelve false para descartar una pista (por id de entrada).
     * @param onProgress   se llama con el último pts escrito (us).
     * @return número de paquetes escritos.
     */
    public fun remux(
        demuxer: Demuxer,
        muxer: Muxer,
        trackFilter: (Int) -> Boolean = { true },
        onProgress: ((Long) -> Unit)? = null,
    ): Long {
        val selected = demuxer.tracks.filter { trackFilter(it.id) }
        requireWritable(selected)
        val idMap = HashMap<Int, Int>()
        for (track in selected) idMap[track.id] = muxer.addTrack(track)
        muxer.start()
        var written = 0L
        while (true) {
            val packet = demuxer.readPacket() ?: break
            val outId = idMap[packet.trackId] ?: continue
            muxer.writePacket(
                MediaPacket(
                    trackId = outId,
                    data = packet.data,
                    ptsUs = packet.ptsUs,
                    dtsUs = packet.dtsUs,
                    isKeyFrame = packet.isKeyFrame,
                    durationUs = packet.durationUs,
                ),
            )
            written++
            onProgress?.invoke(packet.ptsUs)
        }
        muxer.stop()
        return written
    }

    /**
     * Concatena varias entradas en una salida sin recodificar (el demuxer `concat` de
     * FFmpeg). Todas las entradas deben tener la misma disposición: mismo número de pistas y,
     * por posición, todo lo que va en la cabecera de la pista (ver [checkCompatible]) —es
     * decir, segmentos producidos por el mismo codificador con los mismos ajustes—.
     *
     * Los tiempos de cada segmento se desplazan por la duración acumulada de los
     * anteriores. Devuelve el número de paquetes escritos. [demuxers] los cierra el llamante.
     *
     * El cebado del audio (`codecDelayUs`) se declara una sola vez, para el principio de la
     * salida. El de las entradas siguientes no se puede descartar sin recodificar —ningún
     * contenedor permite saltar muestras a mitad de pista de forma que lo respeten los
     * reproductores—, así que se queda dentro, igual que en el demuxer `concat` de FFmpeg. **La
     * sincronía no se pierde**: cada entrada empieza donde acaba el audio de la anterior, contado con
     * su cebado, así que su vídeo y su audio arrancan en el mismo instante que en el original. Lo
     * único que se nota es el empalme, unos 20 ms en AAC en los que suena el arranque del
     * decodificador y se mantiene el último fotograma de la entrada anterior.
     */
    public fun concat(
        demuxers: List<Demuxer>,
        muxer: Muxer,
        onProgress: ((Long) -> Unit)? = null,
    ): Long {
        requireConcatenable(demuxers)
        val reference = demuxers.first().tracks
        val outIds = reference.map { muxer.addTrack(it) }
        muxer.start()

        var written = 0L
        var offsetUs = 0L
        for (demuxer in demuxers) {
            val idMap = demuxer.tracks.mapIndexed { i, t -> t.id to outIds[i] }.toMap()
            val lastPts = HashMap<Int, Long>()
            val lastDelta = HashMap<Int, Long>()
            val lastDuration = HashMap<Int, Long>()
            while (true) {
                val packet = demuxer.readPacket() ?: break
                val outId = idMap[packet.trackId] ?: continue
                muxer.writePacket(
                    MediaPacket(
                        trackId = outId,
                        data = packet.data,
                        ptsUs = packet.ptsUs + offsetUs,
                        dtsUs = packet.dtsUs + offsetUs,
                        isKeyFrame = packet.isKeyFrame,
                        durationUs = packet.durationUs,
                    ),
                )
                written++
                lastPts[packet.trackId]?.let { prev ->
                    if (packet.ptsUs > prev) lastDelta[packet.trackId] = packet.ptsUs - prev
                }
                lastPts[packet.trackId] = maxOf(packet.ptsUs, lastPts[packet.trackId] ?: Long.MIN_VALUE)
                lastDuration[packet.trackId] = packet.durationUs
                onProgress?.invoke(packet.ptsUs + offsetUs)
            }
            val maxEndUs = lastPts.entries.maxOfOrNull { (trackId, pts) ->
                pts + maxOf(lastDuration[trackId] ?: 0, lastDelta[trackId] ?: 0, 0)
            } ?: 0L
            offsetUs += maxOf(demuxer.durationUs, maxEndUs)
        }
        muxer.stop()
        return written
    }

    /**
     * Comprueba que la pista [actual] de la entrada [inputIndex] puede ir a la misma pista de
     * salida que [expected], y si no, lanza `IllegalArgumentException` con **todas** las
     * diferencias.
     *
     * La salida lleva una sola cabecera por pista, la de la primera entrada, así que tiene que
     * valer también para los paquetes de las demás. Hasta la 3.x solo se comparaban el códec, las
     * dimensiones y la frecuencia y los canales, y el resto de diferencias llegaba al archivo sin
     * error:
     * - otro `codecPrivate` (SPS/PPS de otro perfil o nivel, otro `AudioSpecificConfig`): el
     *   decodificador, configurado con la primera cabecera, no entiende los paquetes de la
     *   segunda, y el resultado es imagen corrupta o un error a mitad de reproducción;
     * - otra rotación o descripción de color: la segunda parte se ve girada o con otros colores;
     * - otro cebado: se descartan las muestras que dice la primera entrada, no las de la suya.
     *
     * El idioma, el nombre y la pista por defecto no se comparan: son de la salida, no de los
     * paquetes, y se toman de la primera entrada.
     */
    /**
     * Lo que [remux] exige de las pistas que va a copiar, antes de tocar el muxer: que haya alguna,
     * y que las de vídeo traigan `codecPrivate`, que los tres muxers rechazan sin él. La fachada lo
     * comprueba **antes de abrir la salida**: si no, una llamada que no podía salir bien truncaba un
     * archivo que ya existía y lo dejaba vacío.
     */
    internal fun requireWritable(tracks: List<TrackInfo>) {
        require(tracks.isNotEmpty()) { "ninguna pista seleccionada" }
        for (track in tracks) {
            require(track !is TrackInfo.Video || track.codecPrivate != null) {
                "la pista ${track.id} es de vídeo y no trae codecPrivate (avcC/hvcC), que los muxers exigen"
            }
        }
    }

    /**
     * Lo que [concat] exige de sus entradas antes de tocar el muxer: la misma disposición de pistas
     * y la misma cabecera en cada una (ver [checkCompatible]). Como [requireWritable], la fachada lo
     * comprueba antes de abrir la salida.
     */
    internal fun requireConcatenable(demuxers: List<Demuxer>) {
        require(demuxers.isNotEmpty()) { "sin entradas" }
        val reference = demuxers.first().tracks
        require(reference.isNotEmpty()) { "la primera entrada no tiene pistas" }
        requireWritable(reference)
        for ((index, demuxer) in demuxers.withIndex()) {
            val tracks = demuxer.tracks
            require(tracks.size == reference.size) {
                "la entrada $index tiene ${tracks.size} pistas; se esperaban ${reference.size}"
            }
            for (i in reference.indices) checkCompatible(index, i, reference[i], tracks[i])
        }
    }

    private fun checkCompatible(inputIndex: Int, position: Int, expected: TrackInfo, actual: TrackInfo) {
        val differences = ArrayList<String>()
        fun compare(field: String, first: Any?, other: Any?) {
            if (first != other) differences += "$field $first en la primera y $other en esta"
        }
        when {
            expected is TrackInfo.Video && actual is TrackInfo.Video -> {
                compare("códec", expected.codec, actual.codec)
                compare("dimensiones", "${expected.width}x${expected.height}", "${actual.width}x${actual.height}")
                compare("rotación", expected.rotationDegrees, actual.rotationDegrees)
                compare("color", expected.color, actual.color)
            }
            expected is TrackInfo.Audio && actual is TrackInfo.Audio -> {
                compare("códec", expected.codec, actual.codec)
                compare("frecuencia", expected.sampleRate, actual.sampleRate)
                compare("canales", expected.channelCount, actual.channelCount)
                compare("cebado (µs)", expected.codecDelayUs, actual.codecDelayUs)
            }
            else -> compare("tipo de pista", kind(expected), kind(actual))
        }
        if (!expected.codecPrivate.contentEquals(actual.codecPrivate)) {
            differences += "codecPrivate distinto (${expected.codecPrivate?.size ?: 0} bytes en la primera, " +
                "${actual.codecPrivate?.size ?: 0} en esta)"
        }
        require(differences.isEmpty()) {
            "la pista $position de la entrada $inputIndex no es compatible con la de la primera entrada: " +
                differences.joinToString("; ") + ". concat copia los paquetes sin recodificar bajo una sola " +
                "cabecera por pista, así que las entradas tienen que salir del mismo codificador con los mismos ajustes"
        }
    }

    private fun kind(track: TrackInfo): String = when (track) {
        is TrackInfo.Video -> "vídeo"
        is TrackInfo.Audio -> "audio"
    }
}
