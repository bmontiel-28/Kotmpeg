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
        val idMap = HashMap<Int, Int>()
        for (track in demuxer.tracks) {
            if (!trackFilter(track.id)) continue
            idMap[track.id] = muxer.addTrack(track)
        }
        require(idMap.isNotEmpty()) { "ninguna pista seleccionada" }
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
     * FFmpeg). Todas las entradas deben tener disposición compatible: mismo número de
     * pistas y, por posición, mismo códec y parámetros esenciales (dimensiones /
     * frecuencia y canales) — es decir, segmentos producidos con los mismos ajustes.
     *
     * Los tiempos de cada segmento se desplazan por la duración acumulada de los
     * anteriores. Devuelve el número de paquetes escritos. [demuxers] los cierra el llamante.
     */
    public fun concat(
        demuxers: List<Demuxer>,
        muxer: Muxer,
        onProgress: ((Long) -> Unit)? = null,
    ): Long {
        require(demuxers.isNotEmpty()) { "sin entradas" }
        val reference = demuxers.first().tracks
        require(reference.isNotEmpty()) { "la primera entrada no tiene pistas" }
        for ((index, demuxer) in demuxers.withIndex()) {
            val tracks = demuxer.tracks
            require(tracks.size == reference.size) {
                "la entrada $index tiene ${tracks.size} pistas; se esperaban ${reference.size}"
            }
            for (i in reference.indices) checkCompatible(index, reference[i], tracks[i])
        }

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

    private fun checkCompatible(inputIndex: Int, expected: TrackInfo, actual: TrackInfo) {
        val ok = when {
            expected is TrackInfo.Video && actual is TrackInfo.Video ->
                expected.codec == actual.codec &&
                    expected.width == actual.width && expected.height == actual.height
            expected is TrackInfo.Audio && actual is TrackInfo.Audio ->
                expected.codec == actual.codec &&
                    expected.sampleRate == actual.sampleRate &&
                    expected.channelCount == actual.channelCount
            else -> false
        }
        require(ok) {
            "la pista ${actual.id} de la entrada $inputIndex no es compatible con la primera " +
                "(esperado $expected, recibido $actual); concat requiere ajustes de encoder idénticos"
        }
    }
}
