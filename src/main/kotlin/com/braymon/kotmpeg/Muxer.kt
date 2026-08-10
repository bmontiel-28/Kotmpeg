package com.braymon.kotmpeg

import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import java.io.Closeable

/**
 * Escribe paquetes codificados en un archivo contenedor.
 *
 * Ciclo de vida: [addTrack]* -> [start] -> [writePacket]* -> [stop].
 * Los paquetes deben llegar en orden de decodificación por pista; las pistas pueden
 * intercalarse libremente, pero conviene mantenerlas alineadas en el tiempo para un buen
 * comportamiento en reproductores y streaming.
 */
public interface Muxer : Closeable {
    /** Registra una pista antes de [start]. Devuelve el id a usar en [MediaPacket.trackId]. */
    public fun addTrack(track: TrackInfo): Int

    /** Escribe las cabeceras. Después no se pueden añadir más pistas. */
    public fun start()

    public fun writePacket(packet: MediaPacket)

    /** Finaliza índices/cabeceras y cierra el archivo. Idempotente. */
    public fun stop()
}

/**
 * Lee paquetes codificados de un archivo contenedor.
 */
public interface Demuxer : Closeable {
    public val tracks: List<TrackInfo>

    /** Duración total en microsegundos, 0 si se desconoce. */
    public val durationUs: Long

    /** Devuelve el siguiente paquete en orden de decodificación entre todas las pistas, o null al final. */
    public fun readPacket(): MediaPacket?

    /**
     * Reposiciona el stream en el último keyframe anterior o igual a [timestampUs]
     * (sobre la pista de vídeo principal si existe). Devuelve la posición real en microsegundos.
     */
    public fun seekTo(timestampUs: Long): Long
}
