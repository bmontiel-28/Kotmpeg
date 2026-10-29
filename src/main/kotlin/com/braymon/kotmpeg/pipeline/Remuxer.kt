package com.braymon.kotmpeg.pipeline

import com.braymon.kotmpeg.Demuxer
import com.braymon.kotmpeg.Muxer
import com.braymon.kotmpeg.TimelineGate
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.TrackRequirements

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
     * Cada entrada se coloca donde acaba la anterior: a sus tiempos se les resta su propio
     * principio —el menor PTS con el que arranca, como el `start_time` que resta FFmpeg— y se les
     * suma la duración acumulada de las anteriores. Así una entrada que no empieza en cero, como
     * los trozos de `ffmpeg -f segment` sin `-reset_timestamps`, empalma sin hueco. Hasta la 4.0 no
     * se restaba ese principio, y cada segmento dejaba delante un hueco tan largo como el tiempo en
     * que empezaba: diez segundos, luego veinte… Devuelve el número de paquetes escritos.
     * [demuxers] los cierra el llamante.
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
        val layouts = demuxers.map { it.tracks }
        requireConcatenable(layouts)
        val concatenation = Concatenation(layouts.first(), muxer, onProgress)
        for ((index, demuxer) in demuxers.withIndex()) concatenation.append(index, demuxer)
        return concatenation.finish()
    }

    /**
     * La copia de [concat], entrada a entrada, separada de quién abre las entradas: `Kotmpeg.concat`
     * abre cada archivo justo antes de [append] y lo cierra justo después, para no tener nunca más de
     * uno abierto. Al construirla registra las pistas de [reference] en [muxer] y lo arranca.
     */
    internal class Concatenation(
        private val reference: List<TrackInfo>,
        private val muxer: Muxer,
        private val onProgress: ((Long) -> Unit)?,
    ) {
        private val outIds = reference.map { muxer.addTrack(it) }
        private var written = 0L
        private var offsetUs = 0L

        init {
            muxer.start()
        }

        /**
         * Copia la entrada [index], que tiene que encajar con [reference] —se vuelve a comprobar
         * aquí: entre la validación y la copia el archivo pudo cambiar—, desplazada para empezar
         * donde acabó la anterior.
         *
         * Su principio es el menor PTS de lo primero que entrega cada pista, y para saberlo antes de
         * escribir nada se retienen los primeros paquetes con [TimelineGate], el mismo mecanismo con
         * el que los muxers fijan el origen de una grabación: hasta que todas las pistas han dado
         * alguno, o hasta [LOOKAHEAD_US] de diferencia o [LOOKAHEAD_BYTES] retenidos, para que una
         * pista que no arranca nunca no obligue a leer la entrada entera en memoria. Se cuentan los
         * tiempos tal cual, **sin descontar el cebado**: es la misma convención con la que se mide su
         * final, así que una entrada que ya empieza en cero se copia exactamente igual que antes y el
         * silencio de la unión sigue siendo el de su cebado.
         *
         * Su final, en cada pista, es su mayor PTS más la duración de ese paquete. Si no la declara
         * —un MKV escrito sin `frameRate`, los paquetes de `MediaCodec`—, se le suma la diferencia
         * entre sus dos mayores PTS, que es como estima `MkvTimeline` el final de un MKV: con
         * B-frames el orden de llegada no es el de presentación, pero los dos mayores son siempre
         * fotogramas consecutivos. Hasta la 4.0 se tomaba el mayor entre la duración y el último
         * salto hacia delante en orden de decodificación, y ese salto no es un fotograma: en
         * I P B B, un P llega tres por delante del anterior. La unión quedaba con un fotograma de
         * más por cada B-frame seguido, 66 ms a 30 fps con dos.
         */
        fun append(index: Int, demuxer: Demuxer) {
            val tracks = demuxer.tracks
            requireSameLayout(index, reference, tracks)
            val positions = HashMap<Int, Int>(tracks.size * 2)
            tracks.forEachIndexed { position, track -> positions[track.id] = position }

            val gate = TimelineGate(tracks.size, LOOKAHEAD_US, LOOKAHEAD_BYTES)
            while (true) {
                val packet = demuxer.readPacket() ?: break
                val position = positions[packet.trackId] ?: continue
                if (gate.hold(packet, position)) break
            }
            val startUs = gate.earliestPtsUs ?: 0L

            val maxPts = LongArray(tracks.size) { Long.MIN_VALUE }
            val secondMaxPts = LongArray(tracks.size) { Long.MIN_VALUE }
            val maxPtsDuration = LongArray(tracks.size)
            fun copy(packet: MediaPacket, position: Int) {
                val shiftedPts = packet.ptsUs - startUs + offsetUs
                muxer.writePacket(
                    MediaPacket(
                        trackId = outIds[position],
                        data = packet.data,
                        ptsUs = shiftedPts,
                        dtsUs = packet.dtsUs - startUs + offsetUs,
                        isKeyFrame = packet.isKeyFrame,
                        durationUs = packet.durationUs,
                    ),
                )
                written++
                val pts = packet.ptsUs
                when {
                    pts > maxPts[position] -> {
                        secondMaxPts[position] = maxPts[position]
                        maxPts[position] = pts
                        maxPtsDuration[position] = packet.durationUs
                    }
                    pts == maxPts[position] -> maxPtsDuration[position] = packet.durationUs
                    pts > secondMaxPts[position] -> secondMaxPts[position] = pts
                }
                onProgress?.invoke(shiftedPts)
            }

            for (packet in gate.release()) copy(packet, positions.getValue(packet.trackId))
            while (true) {
                val packet = demuxer.readPacket() ?: break
                copy(packet, positions[packet.trackId] ?: continue)
            }
            val maxEndUs = tracks.indices.filter { maxPts[it] != Long.MIN_VALUE }.maxOfOrNull {
                maxPts[it] + when {
                    maxPtsDuration[it] > 0 -> maxPtsDuration[it]
                    secondMaxPts[it] != Long.MIN_VALUE -> maxPts[it] - secondMaxPts[it]
                    else -> 0L
                }
            } ?: 0L
            offsetUs += maxOf(demuxer.durationUs, maxEndUs) - startUs
        }

        /** Cierra la salida y devuelve los paquetes escritos en total. */
        fun finish(): Long {
            muxer.stop()
            return written
        }
    }

    /**
     * Lo que [remux] exige de las pistas que va a copiar, antes de tocar el muxer: que haya alguna,
     * y que las de vídeo traigan `codecPrivate`, que los tres muxers rechazan sin él. La fachada lo
     * comprueba **antes de abrir la salida**: si no, una llamada que no podía salir bien truncaba un
     * archivo que ya existía y lo dejaba vacío.
     */
    internal fun requireWritable(tracks: List<TrackInfo>) {
        require(tracks.isNotEmpty()) { "ninguna pista seleccionada" }
        for (track in tracks) {
            TrackRequirements.requireVideoCodecPrivate(track) {
                "la pista ${track.id} es de vídeo y no trae codecPrivate (avcC/hvcC), que los muxers exigen"
            }
        }
    }

    /**
     * Lo que [concat] exige de sus entradas, dadas por sus pistas ([layouts], una lista por entrada),
     * antes de tocar el muxer: la misma disposición de pistas y la misma cabecera en cada una (ver
     * [checkCompatible]). Como [requireWritable], la fachada lo comprueba antes de abrir la salida, y
     * con las pistas basta: no hace falta tener las entradas abiertas.
     */
    internal fun requireConcatenable(layouts: List<List<TrackInfo>>) {
        require(layouts.isNotEmpty()) { "sin entradas" }
        val reference = layouts.first()
        require(reference.isNotEmpty()) { "la primera entrada no tiene pistas" }
        requireWritable(reference)
        for ((index, tracks) in layouts.withIndex()) requireSameLayout(index, reference, tracks)
    }

    /** Que la entrada [index], con estas [tracks], tenga las mismas pistas que [reference], posición a posición. */
    private fun requireSameLayout(index: Int, reference: List<TrackInfo>, tracks: List<TrackInfo>) {
        require(tracks.size == reference.size) {
            "la entrada $index tiene ${tracks.size} pistas; se esperaban ${reference.size}"
        }
        for (i in reference.indices) checkCompatible(index, i, reference[i], tracks[i])
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

    /**
     * Cuánto se lee como mucho de cada entrada de [concat] antes de fijar su principio: la misma
     * espera que da `MkvMuxer` a las pistas de una grabación para entregar su primer paquete.
     */
    private const val LOOKAHEAD_US = 5_000_000L

    /** Bytes que se retienen como mucho para fijar el principio de una entrada, como en `MkvMuxer`. */
    private const val LOOKAHEAD_BYTES = 16L * 1024 * 1024
}
