package com.braymon.kotmpeg

import com.braymon.kotmpeg.model.MediaPacket

/**
 * Retiene los primeros paquetes de un muxer que escribe en vivo hasta que todas las pistas han
 * entregado el suyo, para fijar el origen de la línea de tiempo en el paquete **más temprano** y no
 * en el primero que llega.
 *
 * Con dos codificadores no son el mismo: el de vídeo suele tardar más que el de audio, así que el
 * primer trozo de audio llega al muxer antes que el primer fotograma aunque se capturara después.
 * Con el origen en el primero que llegaba, los fotogramas anteriores quedaban con marcas negativas:
 * en MKV los lectores los dejaban sin tiempo —incluido el keyframe que abre la grabación— y en fMP4
 * el audio acababa adelantado respecto al vídeo. `Mp4Muxer` no lo necesita: lo tiene todo al cerrar
 * y toma el menor PTS de todas las pistas.
 *
 * La espera está acotada por [maxWaitUs] —la distancia entre el menor y el mayor PTS retenidos— y
 * por [maxHeldBytes], porque una pista declarada que nunca produce nada no puede hacer crecer la
 * memoria sin límite: pasado cualquiera de los dos topes se fija el origen con lo que haya. Un
 * paquete que llegue después con un PTS anterior a ese origen queda con marca negativa, que es lo
 * que pasaba con todos antes de existir esto.
 *
 * Los paquetes se guardan **copiados**: el contrato de `MediaPacket` permite reutilizar el array en
 * cuanto `writePacket` vuelve, y aquí se retienen más allá de esa llamada.
 */
internal class TimelineGate(
    trackCount: Int,
    private val maxWaitUs: Long,
    private val maxHeldBytes: Long,
) {
    private val queues = List(trackCount) { ArrayList<MediaPacket>() }
    private var tracksWithoutPackets = trackCount
    private var heldBytes = 0L
    private var minPtsUs = Long.MAX_VALUE
    private var maxPtsUs = Long.MIN_VALUE

    /** Menor PTS retenido, que es el origen que debe usar el muxer; `null` si no se retuvo nada. */
    val earliestPtsUs: Long? get() = if (minPtsUs == Long.MAX_VALUE) null else minPtsUs

    /**
     * Retiene una copia de [packet], que pertenece a la pista de índice [trackIndex] (base 0).
     * Devuelve `true` cuando ya se puede fijar el origen: todas las pistas han entregado algo o se
     * ha llegado a uno de los topes.
     */
    fun hold(packet: MediaPacket, trackIndex: Int): Boolean {
        val queue = queues[trackIndex]
        if (queue.isEmpty()) tracksWithoutPackets--
        queue += MediaPacket(
            packet.trackId, packet.data.copyOf(), packet.ptsUs, packet.dtsUs, packet.isKeyFrame, packet.durationUs,
        )
        heldBytes += packet.data.size
        minPtsUs = minOf(minPtsUs, packet.ptsUs)
        maxPtsUs = maxOf(maxPtsUs, packet.ptsUs)
        return tracksWithoutPackets == 0 || heldBytes >= maxHeldBytes || waitedTooLong()
    }

    /** Una distancia que no cabe en `Long` viene de marcas absurdas: se da la espera por agotada. */
    private fun waitedTooLong(): Boolean = try {
        Math.subtractExact(maxPtsUs, minPtsUs) >= maxWaitUs
    } catch (_: ArithmeticException) {
        true
    }

    /**
     * Entrega lo retenido intercalando las pistas por PTS, como si hubiera llegado en orden de
     * captura, **sin alterar el orden dentro de cada pista**: con B-frames ese es el orden de
     * decodificación y no se puede tocar. Deja la retención vacía.
     */
    fun release(): List<MediaPacket> {
        val total = queues.sumOf { it.size }
        val out = ArrayList<MediaPacket>(total)
        val heads = IntArray(queues.size)
        repeat(total) {
            var best = -1
            for (q in queues.indices) {
                if (heads[q] >= queues[q].size) continue
                if (best < 0 || queues[q][heads[q]].ptsUs < queues[best][heads[best]].ptsUs) best = q
            }
            out += queues[best][heads[best]++]
        }
        for (queue in queues) queue.clear()
        heldBytes = 0
        return out
    }
}
