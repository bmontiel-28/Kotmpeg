package com.braymon.kotmpeg.mkv

import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo

/**
 * Lo que vuelve a la cola cuando se decide una búsqueda: [packets] en el orden del archivo, y las
 * pistas de vídeo que tienen que descartar lo suyo hasta su siguiente keyframe.
 */
internal class SeekLanding(val packets: List<MediaPacket>, val awaitingKeyFrame: Set<Int>)

/**
 * Lo que queda por decidir tras `MkvDemuxer.seekTo`. Hasta el keyframe de [anchor] con `pts`
 * [landedUs], los paquetes de las demás pistas se guardan en [held], y los de [anchor] anteriores
 * a él se descartan sin leer su carga (ver [drops]). Llegado el keyframe ([keyFrame]), lo que
 * sigue se guarda en [after] hasta que cada una de las demás pistas ha dado un paquete posterior a
 * él ([past]). Hasta entonces no se sabe por dónde empieza: un muxer puede escribir el paquete de
 * audio que suena en ese instante detrás del keyframe, incluso segundos detrás, y una pista que no
 * da ninguno posterior puede haber terminado ya (ver [waitedEnough]).
 *
 * Cada paquete entra por [offer], y el final del archivo por [finish]; cuando se decide, lo que
 * devuelven ([SeekLanding]) vuelve a la cola de `MkvDemuxer` y pasa por su filtro de keyframes
 * como cualquier otro paquete. Vive aparte del demuxer porque no lee nada: solo decide sobre lo
 * que le llega.
 */
internal class MkvSeekFilter(val anchor: Int, val landedUs: Long, private val tracks: Map<Int, TrackInfo>) {
    private val held = ArrayList<MediaPacket>()
    private var keyFrame: MediaPacket? = null
    private val after = ArrayList<MediaPacket>()
    private val past = HashSet<Int>()

    /** Hasta dónde ha llegado la lectura tras el keyframe: el mayor `pts` leído desde él. */
    private var progressUs = 0L

    /** Por pista, [progressUs] cuando dio su último paquete tras el keyframe. */
    private val heardAtUs = HashMap<Int, Long>()

    /** Por pista, cuánto iba por detrás de [progressUs] su último paquete tras el keyframe. */
    private val behindUs = HashMap<Int, Long>()

    /** Lo que ocupa en memoria lo guardado en [held] y [after] (ver [SEEK_MAX_RETAINED_BYTES]). */
    private var retainedBytes = 0L

    private fun retain(packet: MediaPacket) {
        retainedBytes += packet.data.size + RETAINED_PACKET_OVERHEAD
    }

    /**
     * Si el bloque de [track] con `pts` [ptsUs] se va a descartar de todos modos —es de la pista
     * ancla y anterior al keyframe que se espera—, para no leer su carga. La lectura tras buscar
     * empieza un cluster antes que el del keyframe, y sin esto se leerían sus fotogramas para
     * tirarlos. Lo que llega después del keyframe se entrega entero, como sin búsqueda.
     */
    fun drops(track: Int, ptsUs: Long): Boolean = keyFrame == null && track == anchor && ptsUs < landedUs

    /** Pasa [packet] por el filtro: `null` mientras no se sepa por dónde empieza cada pista. */
    fun offer(packet: MediaPacket): SeekLanding? {
        val key = keyFrame
        if (key != null) {
            after += packet
            retain(packet)
            if (packet.ptsUs > key.ptsUs) past += packet.trackId
            if (packet.ptsUs > progressUs) progressUs = packet.ptsUs
            heardAtUs[packet.trackId] = progressUs
            behindUs[packet.trackId] = progressUs - packet.ptsUs
            return if (settled() || waitedEnough(key)) land(key, atEnd = false) else null
        }
        if (packet.ptsUs - landedUs > SEEK_LOOKAHEAD_US || retainedBytes > SEEK_MAX_RETAINED_BYTES) return giveUp(packet)
        if (packet.trackId != anchor) {
            held += packet
            retain(packet)
        } else if (packet.isKeyFrame && packet.ptsUs >= landedUs) {
            keyFrame = packet
            progressUs = packet.ptsUs
            for (h in held) if (h.ptsUs > packet.ptsUs) past += h.trackId
            if (settled()) return land(packet, atEnd = false)
        }
        return null
    }

    /**
     * El archivo se acabó con la búsqueda por decidir. Si el keyframe llegó, se recorta como
     * siempre con lo que haya ([land]); si no, es lo mismo que [giveUp].
     */
    fun finish(): SeekLanding {
        val key = keyFrame
        return if (key != null) land(key, atEnd = true) else giveUp(null)
    }

    /**
     * Si ya se sabe por dónde empieza cada pista: todas, salvo la ancla, han dado un paquete
     * posterior al keyframe. Siguen, y como el audio va en orden, ninguno de los que faltan por
     * leer suena en él.
     *
     * Se cuenta en vez de recorrer las pistas: [past] solo tiene pistas declaradas, porque los
     * bloques de las demás no llegan a ser paquetes, así que basta con que estén todas menos la
     * ancla. Recorrerlas costaba, con miles de pistas declaradas, miles de consultas por paquete.
     */
    private fun settled(): Boolean = past.size - (if (anchor in past) 1 else 0) == tracks.size - 1

    /**
     * Si se deja de esperar a las pistas que aún no han pasado el keyframe ([settled] no se
     * cumple): todas ellas llevan un rato sin dar nada —han terminado o tienen un hueco—, o la
     * lectura va ya [SEEK_LOOKAHEAD_US] por delante del keyframe, o lo guardado llega a
     * [SEEK_MAX_RETAINED_BYTES].
     *
     * Una pista que sigue dando paquetes, aunque sean anteriores al keyframe, no ha terminado: va
     * por detrás en el archivo. Es lo que pasa cuando el muxer intercala por tiempo de
     * decodificación un vídeo con B-frames cuyo DTS va segundos por detrás del PTS, como en algunas
     * grabaciones de Android: el audio que suena en un keyframe queda escrito esos segundos más
     * adelante. Hasta la 4.0 se dejaba de esperar en cuanto lo leído pasaba un segundo del
     * keyframe, y el audio empezaba esos segundos antes que la imagen.
     *
     * Por eso «un rato» es [SEEK_SETTLE_US] más lo que la pista iba por detrás en su último
     * paquete ([behindUs]). Una pista intercalada a milisegundos, como casi todas, se da por
     * terminada tras un segundo sin noticias. Una que va segundos por detrás puede pasar un tramo
     * sin paquetes sin haber terminado: al final de esas mismas grabaciones el codificador vacía de
     * golpe más de un segundo de fotogramas seguidos, sin audio entre ellos.
     *
     * Solo se miran las pistas que han dado algo tras el keyframe ([heardAtUs]): una que no ha dado
     * nada lleva en silencio todo lo leído, que ya pasa de [SEEK_SETTLE_US], y no iba por detrás de
     * nada, así que cumple siempre. Recorrer todas las declaradas daba el mismo resultado a un coste
     * por paquete proporcional a su número.
     */
    private fun waitedEnough(keyFrame: MediaPacket): Boolean {
        if (retainedBytes > SEEK_MAX_RETAINED_BYTES) return true
        val read = progressUs - keyFrame.ptsUs
        if (read > SEEK_LOOKAHEAD_US) return true
        if (read <= SEEK_SETTLE_US) return false
        return heardAtUs.all { (number, heardAt) ->
            number == anchor || number in past || progressUs - heardAt - SEEK_SETTLE_US > (behindUs[number] ?: 0L)
        }
    }

    /**
     * Se sabe ya por dónde empieza cada pista tras una búsqueda: lo leído de las demás pistas, antes
     * y después del [keyFrame], se recorta con el criterio de `Mp4Demuxer`, en el orden del archivo.
     *
     * - Una pista de audio empieza en su último paquete que no empieza después del keyframe: el
     *   que suena en ese instante. Como la lectura empezó un cluster antes del suyo y siguió hasta
     *   pasar el keyframe en todas las demás pistas, ese paquete está entre lo leído tanto si el
     *   muxer lo dejó en el cluster anterior como si lo escribió detrás del keyframe.
     * - Otra pista de vídeo empieza en su último keyframe no posterior. Si no hay ninguno entre lo
     *   leído —el suyo está más atrás—, se descarta lo suyo hasta el siguiente
     *   ([SeekLanding.awaitingKeyFrame]): nunca sale primero un fotograma que no se puede
     *   decodificar.
     * - Una pista que ya ha terminado en ese instante no entrega nada de lo leído (ver
     *   [endedBefore]; [atEnd] dice si la lectura llegó al final del archivo). Si luego vuelve
     *   —tenía un hueco—, sigue desde ahí, y si es de vídeo, desde su siguiente keyframe.
     */
    private fun land(keyFrame: MediaPacket, atEnd: Boolean): SeekLanding {
        val read = ArrayList<MediaPacket>(held.size + 1 + after.size)
        read.addAll(held)
        read.add(keyFrame)
        read.addAll(after)
        val startAt = HashMap<Int, Int>()
        for ((i, packet) in read.withIndex()) {
            if (packet.trackId == anchor) continue
            if (tracks[packet.trackId] is TrackInfo.Video) {
                if (packet.isKeyFrame && packet.ptsUs <= keyFrame.ptsUs) startAt[packet.trackId] = i
            } else if (packet.ptsUs <= keyFrame.ptsUs || packet.trackId !in startAt) {
                startAt[packet.trackId] = i
            }
        }
        val awaiting = HashSet<Int>()
        for ((number, track) in tracks) {
            if (number == anchor) continue
            if (number !in past && endedBefore(number, track, read, keyFrame.ptsUs, atEnd)) {
                startAt[number] = read.size
                if (track is TrackInfo.Video) awaiting += number
            } else if (track is TrackInfo.Video && number !in startAt) {
                awaiting += number
            }
        }
        return SeekLanding(read.filterIndexed { i, packet -> i >= (startAt[packet.trackId] ?: 0) }, awaiting)
    }

    /**
     * Si la pista [number], que no dio ningún paquete posterior al keyframe en lo leído, ya ha
     * terminado en [keyFrameUs]: lo último suyo que se leyó deja de presentarse antes. Es el
     * criterio de `Mp4Demuxer`, con lo que Matroska permite saber:
     *
     * - En audio, un hueco es silencio, así que no dar nada en el segundo que se lee tras el
     *   keyframe ([SEEK_SETTLE_US]) basta. La duración de un bloque es opcional; si no la declara,
     *   se toma la distancia a su paquete anterior, porque los paquetes de un códec de audio duran
     *   lo mismo.
     * - En vídeo, un fotograma se muestra hasta el siguiente aunque su duración diga otra cosa —una
     *   captura de pantalla se queda quieta lo que haga falta—, y la de un MP4 lo refleja porque es
     *   la distancia al siguiente. Aquí solo se sabe que no hay siguiente al llegar al final del
     *   archivo ([atEnd]), y solo entonces, y con la duración declarada, se da por terminada. Una
     *   pista de vídeo que termina antes que el archivo, lejos de su final, se sigue entregando
     *   desde su último keyframe.
     */
    private fun endedBefore(number: Int, track: TrackInfo, read: List<MediaPacket>, keyFrameUs: Long, atEnd: Boolean): Boolean {
        if (track is TrackInfo.Video && !atEnd) return false
        var latest: MediaPacket? = null
        var previous: MediaPacket? = null
        for (packet in read) {
            if (packet.trackId != number) continue
            if (latest == null || packet.ptsUs >= latest.ptsUs) {
                previous = latest
                latest = packet
            }
        }
        if (latest == null || latest.ptsUs >= keyFrameUs) return false
        val durationUs = when {
            latest.durationUs > 0 -> latest.durationUs
            track is TrackInfo.Audio && previous != null -> latest.ptsUs - previous.ptsUs
            else -> return false
        }
        return keyFrameUs - latest.ptsUs >= durationUs
    }

    /**
     * El keyframe de la búsqueda no llega —un cue que apunta mal, un bloque dañado— y los paquetes
     * ya van [SEEK_LOOKAHEAD_US] por delante del destino, o el archivo se acabó sin él: se deja de
     * filtrar y se entrega todo desde aquí, guardado incluido. Sin este tope, un keyframe que no
     * existe haría guardar en memoria todo lo que queda del archivo.
     *
     * Todas las pistas de vídeo, la ancla incluida, esperan a su siguiente keyframe: lo guardado y
     * lo que sigue empiezan donde caiga, y hasta ahora salían desde un fotograma que no se puede
     * decodificar.
     */
    private fun giveUp(packet: MediaPacket?): SeekLanding {
        val packets = ArrayList<MediaPacket>(held.size + 1)
        packets.addAll(held)
        if (packet != null) packets.add(packet)
        return SeekLanding(packets, tracks.filterValues { it is TrackInfo.Video }.keys)
    }

    /**
     * Constantes internas, `private` una a una por lo mismo que las de `MkvDemuxer`: un
     * `const val` de un objeto compañero se compila como campo estático con su propia visibilidad.
     */
    private companion object {
        /**
         * Cuánto puede adelantarse lo leído tras la búsqueda al keyframe esperado antes de dejar de
         * esperarlo, y, llegado el keyframe, a él antes de dejar de esperar a una pista que va por
         * detrás en el archivo (ver [waitedEnough]). Ese keyframe está en el cluster en el que se
         * cae, así que en un archivo sano llega mucho antes; y una pista va como mucho unos pocos
         * segundos por detrás. Si va más, empieza antes del keyframe.
         */
        private const val SEEK_LOOKAHEAD_US = 10_000_000L

        /**
         * Cuánto se sigue leyendo tras el keyframe de una búsqueda sin que una pista que no lo ha
         * pasado dé ningún paquete, antes de darla por terminada o en un hueco. Un muxer intercala
         * las pistas a pocos milisegundos unas de otras, así que en un archivo normal se decide en
         * unos pocos paquetes.
         */
        private const val SEEK_SETTLE_US = 1_000_000L

        /**
         * Lo más que se guarda en memoria mientras se decide por dónde empieza cada pista tras la
         * búsqueda, contando cada paquete como su carga más [RETAINED_PACKET_OVERHEAD]: 64 MiB,
         * diez segundos de vídeo a unos 50 Mbit/s. En un archivo sano mandan los topes de tiempo;
         * este cubre uno dañado cuyos tiempos dejan de avanzar, que sin él haría guardar todo lo
         * que queda del archivo antes de entregar el primer paquete.
         */
        private const val SEEK_MAX_RETAINED_BYTES = 64L * 1024 * 1024

        /** Lo que ocupa un `MediaPacket` guardado además de su carga, redondeado por arriba. */
        private const val RETAINED_PACKET_OVERHEAD = 64L
    }
}
