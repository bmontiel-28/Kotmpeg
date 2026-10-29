package com.braymon.kotmpeg.mkv

/**
 * Lo que [MkvMuxer] deduce de los bloques escritos —la duración que declara y el índice `Cues`—,
 * en un solo sitio. El muxer lo publica al cerrar cada cluster, y [MkvMuxer.stop] al cerrar el
 * último. Le da cada bloque al escribirlo y la reparación cada bloque que lee, en el mismo orden,
 * así que reparar da lo mismo que cerrar con `stop()` por construcción, y no porque dos copias del
 * mismo criterio sigan coincidiendo.
 *
 * Para la reparación, «los mismos bloques» son los paquetes que entrega el demuxer al leer el
 * archivo: solo los de las pistas que lee, uno por fotograma, y con la duración de su
 * `BlockDuration` o de la `DefaultDuration` de su pista. Es lo que recibe el muxer al
 * reempaquetarlo, y por eso una reparación da la misma duración que `Kotmpeg.remux`. Con los
 * paquetes de `MediaCodec`, que no traen duración, el muxer cuenta el último fotograma de vídeo con
 * el hueco entre los dos mayores PTS; si la cadencia real no es la nominal, la reparación puede
 * declarar unos milisegundos distintos de los que habría declarado la grabación sin cortar.
 *
 * Una instancia lleva los cues; las pistas se llevan cada una en su [Track] y se juntan con
 * [endMs].
 */
internal class MkvTimeline(private val cueTrack: Long) {

    /**
     * Un cue: [time] en ticks de `TimestampScale` —milisegundos con la escala del muxer— y
     * [clusterPosition] relativa a los datos del segmento.
     */
    data class Cue(val time: Long, val track: Long, val clusterPosition: Long) {
        fun encoded(): ByteArray = MkvLayout.cuePoint(time, track, clusterPosition)
    }

    /**
     * Dónde acaba una pista. Si sus paquetes no traen duración —lo normal con `MediaCodec`—, el
     * último fotograma se cuenta con el intervalo entre sus dos mayores PTS, o con su
     * `DefaultDuration` si no hubo dos: sin eso la duración declarada se quedaba un fotograma corta
     * respecto a la de los muxers MP4. Con B-frames el orden de llegada no es el de presentación,
     * pero los dos mayores siempre son fotogramas consecutivos, y su diferencia es la duración del
     * último. Si alguno trae duración, la pista acaba donde acabe el que más tarde lo haga, y no se
     * estima nada.
     */
    class Track(private val defaultDurationNs: Long) {
        private var maxPtsMs = Long.MIN_VALUE
        private var secondMaxPtsMs = Long.MIN_VALUE
        private var maxEndMs = Long.MIN_VALUE
        private var hasDurations = false

        /** Cuenta un paquete en [ptsMs] con [durationUs] de duración (0 si no la trae). */
        fun add(ptsMs: Long, durationUs: Long) {
            if (ptsMs > maxPtsMs) {
                secondMaxPtsMs = maxPtsMs
                maxPtsMs = ptsMs
            } else if (ptsMs < maxPtsMs && ptsMs > secondMaxPtsMs) {
                secondMaxPtsMs = ptsMs
            }
            val durationMs = if (durationUs > 0) (durationUs + 500) / 1000 else 0L
            maxEndMs = maxOf(maxEndMs, ptsMs + durationMs)
            if (durationUs > 0) hasDurations = true
        }

        /** Fin de la pista en ms, o `Long.MIN_VALUE` si no tiene ningún paquete. */
        fun endMs(): Long {
            if (maxPtsMs == Long.MIN_VALUE) return Long.MIN_VALUE
            if (hasDurations) return maxEndMs
            val lastDuration = when {
                secondMaxPtsMs != Long.MIN_VALUE -> maxPtsMs - secondMaxPtsMs
                defaultDurationNs > 0 -> (defaultDurationNs + 500_000) / 1_000_000
                else -> 0L
            }
            return maxPtsMs + lastDuration
        }
    }

    private val cueList = ArrayList<Cue>()
    private var lastCuedCluster = -1L

    /** Los cues de los bloques vistos hasta ahora, en orden. */
    val cues: List<Cue> get() = cueList

    /**
     * Cuenta un bloque de [track] en el cluster de [clusterPosition] para el índice: un cue por
     * cluster, en su primer keyframe de la pista de cues. [time] se acota a 0 porque `CueTime` es un
     * uint EBML, y el cebado de un AAC empieza en negativo: un cue negativo reventaba dentro de
     * `stop()`, con el archivo a medio cerrar.
     */
    fun block(clusterPosition: Long, track: Long, key: Boolean, time: Long) {
        if (track != cueTrack || !key || clusterPosition == lastCuedCluster) return
        cueList += Cue(time.coerceAtLeast(0), track, clusterPosition)
        lastCuedCluster = clusterPosition
    }

    companion object {
        /** La pista por la que se indexa: la primera de vídeo, o la primera si no hay vídeo. */
        fun <T> cueTrack(tracks: Collection<T>, isVideo: (T) -> Boolean): T = tracks.firstOrNull(isVideo) ?: tracks.first()

        /** El final de la línea de tiempo: el de la pista que acaba más tarde, y nunca antes de 0. */
        fun endMs(tracks: Iterable<Track>): Long = tracks.fold(0L) { end, track -> maxOf(end, track.endMs()) }
    }
}
