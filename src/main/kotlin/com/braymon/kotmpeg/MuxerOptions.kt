package com.braymon.kotmpeg

import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.mp4.Mp4Segment

/**
 * Opciones de escritura, compartidas por los tres muxers y por la fachada. Se configuran en un
 * bloque, y cada muxer usa las de su formato e ignora las demás:
 *
 * ```kotlin
 * val muxer = Kotmpeg.createMuxer(File("clip.mp4")) {
 *     fragmented = true
 *     maxFragmentBytes = 16L * 1024 * 1024
 *     onSegment = { segment -> playlist.add(segment) }
 * }
 * ```
 *
 * Hasta la 3.x cada opción era un parámetro con valor por defecto de un constructor o de la
 * fachada, o una propiedad mutable del muxer que había que asignar antes de `start()`. Lo primero
 * cambiaba la firma JVM con cada opción nueva —de ahí que existiera una segunda función de fábrica
 * solo para el MP4 fragmentado—, y lo segundo dejaba asignar una opción cuando ya no tenía efecto.
 * Una propiedad nueva aquí no rompe a nadie, y todo se fija al construir el muxer.
 *
 * Los valores se validan al construir el muxer, **antes** de abrir el archivo: un valor imposible
 * lanza `IllegalArgumentException` sin dejar un archivo vacío ni un descriptor abierto.
 */
public class MuxerOptions {

    /**
     * Fecha de creación en milisegundos desde la época de Unix: `mvhd`, `tkhd` y `mdhd` en MP4 y
     * `DateUTC` en Matroska. `null` no la escribe —en MP4, deja los campos a cero—, y es lo que
     * conviene para que dos ejecuciones sobre la misma entrada den el mismo archivo byte a byte.
     */
    public var creationTimeMillis: Long? = System.currentTimeMillis()

    /**
     * Nombre de la aplicación que escribe el archivo: el `WritingApp` de Matroska y la etiqueta
     * `©too` de MP4 (`udta` > `meta` > `ilst`), que es lo que `ffprobe` muestra como `encoder`.
     * Vacío no escribe la etiqueta en MP4; en Matroska el elemento es obligatorio y va vacío.
     */
    public var writingApp: String = "Kotmpeg"

    /**
     * MP4 plano: al cerrar, recoloca el `moov` delante de los datos (`-movflags +faststart` de
     * FFmpeg) para poder reproducir antes de descargar el archivo entero. Ver `Mp4Muxer`. Sobre un
     * [SeekableOutput] lo hace en el sitio y necesita leer el destino: un descriptor abierto en
     * `"w"` se rechaza al construir el muxer; ábrelo en `"rw"`.
     */
    public var fastStart: Boolean = false

    /**
     * Con un formato MP4, escribe MP4 fragmentado —fMP4/CMAF, el
     * `-movflags frag_keyframe+empty_moov` de FFmpeg— con `FragmentedMp4Muxer` en vez de MP4
     * plano. Es salida solo-añadir: grabación a prueba de cortes y streaming en vivo. Excluye
     * [fastStart], que no hace falta: la cabecera de un fMP4 ya va delante.
     *
     * La usa la fachada para elegir el muxer. `Mp4Muxer`, que solo escribe MP4 plano, la rechaza
     * en vez de ignorarla: quien la pide espera un fMP4.
     */
    public var fragmented: Boolean = false

    /** MP4 fragmentado: longitud objetivo de fragmento cuando no hay vídeo que corte por keyframes. */
    public var fragmentDurationUs: Long = 2_000_000

    /**
     * MP4 fragmentado: longitud máxima de un fragmento incluso habiendo vídeo. Con un intervalo de
     * keyframes muy disperso, o un codificador que deja de emitir IDR, un fragmento crecería sin
     * límite; al llegar aquí se corta aunque no haya keyframe.
     */
    public var maxFragmentDurationUs: Long = 10_000_000

    /**
     * MP4 fragmentado: tope de bytes retenidos por fragmento. Las muestras se guardan en memoria
     * hasta cerrar el fragmento, porque el `moof` que las describe va delante; en un móvil con poca
     * RAM conviene bajarlo a 16 o 32 MB. Con [segmentIndex] no puede pasar de 1 GiB.
     */
    public var maxFragmentBytes: Long = 64L * 1024 * 1024

    /**
     * MP4 fragmentado: escribe delante de cada fragmento un índice `sidx` por pista, como
     * `-movflags dash` de FFmpeg, para que cada fragmento sea un segmento indexado de DASH. Ver
     * `FragmentedMp4Muxer`.
     */
    public var segmentIndex: Boolean = false

    /**
     * MP4 fragmentado: se llama con cada fragmento ya escrito y volcado al archivo, con su
     * posición, su tamaño y su duración, para empaquetar en vivo sin releer el archivo. Si lanza,
     * la excepción se ignora: un fallo al anotar un segmento no puede dejar la grabación a medias.
     */
    public var onSegment: ((Mp4Segment) -> Unit)? = null

    /**
     * MP4 fragmentado: escribe los desfases de composición sin signo (`trun` versión 0) y una
     * lista de edición que alinea el inicio, en vez de desfases firmados (`trun` versión 1, como
     * en CMAF), que es el valor por defecto.
     *
     * Las dos formas son válidas según ISO/IEC 14496-12, y la firmada es la que pide CMAF. La
     * diferencia está en el demuxer de FFmpeg —y en lo que se apoya en él—, que con desfases
     * negativos retrasa la pista de vídeo entera lo que mida el mayor de ellos: uno o dos
     * fotogramas con B-frames, vídeo ligeramente detrás del audio. Con esta opción FFmpeg lo
     * sincroniza exacto. A cambio, la cabecera no se escribe al llamar
     * a `start()` sino con el primer fragmento, que es cuando se conoce el desfase que hay que
     * compensar.
     */
    public var nonNegativeCompositionOffsets: Boolean = false

    /** Matroska: duración máxima de un cluster antes de abrir otro, en ms. */
    public var maxClusterDurationMs: Long = 5_000

    /**
     * Comprueba que los valores tienen sentido y devuelve estas mismas opciones. Es lo primero que
     * hace cada muxer, antes de abrir nada.
     *
     * El tope de [maxFragmentBytes] con [segmentIndex] es 1 GiB porque el `sidx` describe el tamaño
     * de cada fragmento en 31 bits, y un fragmento puede pasar del límite en lo que ocupe la muestra
     * que lo hace cortarse.
     */
    internal fun validated(): MuxerOptions {
        require(fragmentDurationUs > 0) { "fragmentDurationUs debe ser positivo: $fragmentDurationUs" }
        require(maxFragmentDurationUs > 0) { "maxFragmentDurationUs debe ser positivo: $maxFragmentDurationUs" }
        require(maxFragmentBytes > 0) { "maxFragmentBytes debe ser positivo: $maxFragmentBytes" }
        require(!segmentIndex || maxFragmentBytes <= 1L shl 30) {
            "con segmentIndex, maxFragmentBytes no puede pasar de 1 GiB: $maxFragmentBytes"
        }
        require(maxClusterDurationMs > 0) { "maxClusterDurationMs debe ser positivo: $maxClusterDurationMs" }
        require(!(fastStart && fragmented)) {
            "fastStart y fragmented son excluyentes (el fMP4 no necesita faststart: su cabecera ya va delante)"
        }
        return this
    }

    /** [validated], y además que no pidan MP4 fragmentado: es lo que comprueba `Mp4Muxer`. */
    internal fun validatedForPlainMp4(): MuxerOptions {
        validated()
        require(!fragmented) {
            "Mp4Muxer escribe MP4 plano y fragmented = true pide fMP4: usa FragmentedMp4Muxer, o " +
                "Kotmpeg.createMuxer, que elige el muxer según esta opción"
        }
        return this
    }

    /**
     * [check] —[validated] u otra de las comprobaciones— para un muxer que recibe un destino ya
     * abierto: si los valores no valen, cierra [out] antes de lanzar, porque el muxer no llega a
     * existir para cerrarlo y quien lo creó en la misma llamada no se quedó con otra referencia.
     */
    internal fun validatedFor(out: SeekableOutput, check: MuxerOptions.() -> MuxerOptions = { validated() }): MuxerOptions =
        try {
            check()
        } catch (t: Throwable) {
            runCatching { out.close() }
            throw t
        }
}
