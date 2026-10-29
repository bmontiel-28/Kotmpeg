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

    /**
     * Escribe [packet] en la pista [MediaPacket.trackId], después de [start]. Dentro de cada pista
     * los paquetes llegan en orden de decodificación; entre pistas, en el orden que se quiera.
     *
     * El array de [MediaPacket.data] se puede reutilizar en cuanto vuelve: ningún muxer guarda una
     * referencia a él (ver [MediaPacket]).
     */
    public fun writePacket(packet: MediaPacket)

    /** Finaliza índices/cabeceras y cierra el archivo. Idempotente. */
    public fun stop()
}

/**
 * Lee paquetes codificados de un archivo contenedor.
 *
 * **Ante una entrada dañada lanza siempre una excepción tipada**, al abrir o al leer: en Matroska,
 * `EbmlException`; en MP4, `IllegalStateException`; un campo fuera de rango en cualquiera de los
 * dos, `IllegalArgumentException`; un archivo que se acaba antes de lo que declara, `EOFException`,
 * y cualquier otro fallo de lectura, `IOException`. Nunca otra cosa, ni un cuelgue ni una reserva de
 * memoria desproporcionada: es la política de `SECURITY.md`, y el fuzzing de `DemuxerFuzzTest` la
 * comprueba. Un archivo solo truncado, si abre, no lanza al leer: entrega hasta su último paquete
 * completo y termina con `null`. `Kotmpeg.repair`, que sí escribe, es más estricto: lo que no
 * reconoce lo rechaza con `IllegalArgumentException` sin tocarlo.
 */
public interface Demuxer : Closeable {
    /**
     * Las pistas que se pueden leer, en el orden del archivo, cada una con el id que llevan sus
     * paquetes en [MediaPacket.trackId]. Las de un códec que no se soporta, o cuya cabecera no se
     * puede leer, no están: se descartan al abrir y se avisa por `onWarning`.
     */
    public val tracks: List<TrackInfo>

    /** Duración total en microsegundos, 0 si se desconoce. */
    public val durationUs: Long

    /** Devuelve el siguiente paquete en orden de decodificación entre todas las pistas, o null al final. */
    public fun readPacket(): MediaPacket?

    /**
     * Reposiciona el stream en el último keyframe con `pts <= `[timestampUs] de la pista ancla
     * —la primera de vídeo, o la primera si no hay vídeo— y devuelve su `pts`, en
     * microsegundos. Si no hay ninguno antes, en el primero.
     *
     * Lo siguiente que entrega [readPacket] de esa pista es ese keyframe. Las demás siguen desde
     * el mismo instante **de presentación**, para que un recorte que empiece en el valor devuelto
     * tenga imagen y sonido desde el primer momento:
     * - una pista de audio empieza en el paquete que suena en ese instante. En Matroska, si el
     *   muxer lo escribió muy por detrás del keyframe —más de diez segundos de lectura, o más de
     *   64 MiB—, se deja de esperarlo y la pista empieza antes;
     * - otra pista de vídeo, en su propio último keyframe no posterior. En Matroska, que intercala
     *   todas las pistas en los mismos clusters, la lectura empieza un cluster antes que el del
     *   keyframe; si el de esa otra pista queda más atrás, empieza en el siguiente suyo, porque lo
     *   que hay entre medias no se puede decodificar;
     * - una pista que ya ha terminado en ese instante —nada suyo se presenta en él ni después— no
     *   entrega nada. En Matroska, donde un fotograma se muestra hasta el siguiente diga lo que
     *   diga su duración, de una pista de vídeo eso solo se sabe cerca del final del archivo; antes,
     *   se trata como si siguiera.
     *
     * Hasta la 3.x las demás pistas de un MP4 se colocaban por tiempo de decodificación, que con
     * B-frames adelantaba el audio, y un MKV cuyos clusters no empezaban en keyframe entregaba
     * primero fotogramas que no se pueden decodificar.
     */
    public fun seekTo(timestampUs: Long): Long
}
