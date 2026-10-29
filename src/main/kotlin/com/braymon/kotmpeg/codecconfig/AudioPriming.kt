package com.braymon.kotmpeg.codecconfig

import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.TrackInfo

/**
 * El cebado que declaran los tres muxers para una pista de audio: `CodecDelay` en MKV y lista de
 * edición en MP4 y fMP4.
 *
 * El orden de preferencia es el de `TrackInfo.Audio.codecDelayUs`: en Opus manda el `preSkip` de su
 * `OpusHead`, que es el dato que de verdad describe el bitstream —la de `codecPrivate` o, sin ella,
 * la que los muxers escriben por defecto ([DefaultCodecPrivate]), que lo saca de `codecDelayUs` o
 * pone el de libopus—; en los demás códecs, y en un Opus cuya cabecera no se puede leer, manda
 * `codecDelayUs`.
 *
 * Estaba resuelto solo en `MkvMuxer`, y los dos muxers MP4 leían únicamente `codecDelayUs`. Un Opus
 * con una `OpusHead` de `preSkip` 312 y sin `codecDelayUs`, que es como lo entrega más de un
 * codificador, salía del MKV con 6,5 ms de `CodecDelay` y del MP4 sin lista de edición, así que el
 * mismo audio sonaba 6,5 ms adelantado según el contenedor.
 */
internal object AudioPriming {

    /** Cebado de [track] en µs; 0 si no declara ninguno. */
    fun us(track: TrackInfo.Audio): Long {
        if (track.codec == AudioCodec.OPUS) {
            val head = runCatching { OpusConfig.parseOpusHead(track.codecPrivate ?: DefaultCodecPrivate.forAudio(track)) }
            head.getOrNull()?.let { return it.codecDelayUs }
        }
        return track.codecDelayUs
    }
}
