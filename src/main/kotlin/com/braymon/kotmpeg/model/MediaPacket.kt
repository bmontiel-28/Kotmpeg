package com.braymon.kotmpeg.model

/**
 * Una unidad de acceso codificada (un fotograma de vídeo o un paquete de audio).
 *
 * @param trackId     Id de la pista a la que pertenece (el que devuelve `Muxer.addTrack`
 *                    o el que aparece en `Demuxer.tracks`).
 * @param data        Carga codificada. Para H.264/H.265 son NALUs con prefijo de longitud
 *                    de 4 bytes.
 * @param ptsUs       Marca de tiempo de presentación en microsegundos.
 * @param dtsUs       Marca de tiempo de decodificación en microsegundos. Puede ser igual a
 *                    [ptsUs] cuando se desconoce (p. ej. desde MediaCodec o desde MKV); los
 *                    muxers que necesitan DTS real (MP4) derivan uno monótono espec-correcto
 *                    automáticamente en ese caso.
 * @param isKeyFrame  True para muestras IDR/sync y para todo paquete de audio.
 * @param durationUs  Duración del paquete en microsegundos, 0 si se desconoce.
 */
public class MediaPacket(
    public val trackId: Int,
    public val data: ByteArray,
    public val ptsUs: Long,
    public val dtsUs: Long = ptsUs,
    public val isKeyFrame: Boolean = false,
    public val durationUs: Long = 0,
) {
    public val size: Int get() = data.size

    override fun toString(): String =
        "MediaPacket(track=$trackId, pts=${ptsUs}us, dts=${dtsUs}us, key=$isKeyFrame, size=$size)"
}
