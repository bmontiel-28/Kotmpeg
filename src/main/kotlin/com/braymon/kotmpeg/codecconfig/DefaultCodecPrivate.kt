package com.braymon.kotmpeg.codecconfig

import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.TrackInfo

/**
 * Configuración de códec por defecto para una pista de audio que llega sin `codecPrivate`, la
 * misma para los dos contenedores.
 *
 * El MP4 ya la generaba al escribir el `esds`/`dOps`, pero el MKV escribía la pista sin
 * `CodecPrivate`. FFmpeg lo tolera, pero los lectores de Matroska de Android no: Media3/ExoPlayer
 * lanza "Missing CodecPrivate" y el extractor nativo descarta la pista AAC, así que el archivo se
 * grababa bien y luego no sonaba en el móvil.
 *
 * Para Opus, el `preSkip` sale de `codecDelayUs` si la pista lo declara, y si no se usa el valor
 * de libopus (312 muestras a 48 kHz). Un Opus de más de dos canales necesita una tabla de mapeo
 * que no se puede adivinar, y ahí `OpusConfig.buildOpusHead` lanza con un mensaje que lo explica.
 */
internal object DefaultCodecPrivate {

    private const val DEFAULT_OPUS_PRE_SKIP = 312
    private const val OPUS_RATE = 48_000L

    fun forAudio(track: TrackInfo.Audio): ByteArray = when (track.codec) {
        AudioCodec.AAC -> AacConfig.build(track.sampleRate, track.channelCount)
        AudioCodec.OPUS -> {
            val preSkip = if (track.codecDelayUs > 0) {
                ((track.codecDelayUs * OPUS_RATE + 500_000) / 1_000_000).coerceIn(0L, 0xFFFFL).toInt()
            } else {
                DEFAULT_OPUS_PRE_SKIP
            }
            OpusConfig.buildOpusHead(track.channelCount, preSkip = preSkip)
        }
    }
}
