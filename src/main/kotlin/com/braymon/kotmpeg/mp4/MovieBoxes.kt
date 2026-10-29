package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.codecconfig.AudioPriming
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.Timestamps
import com.braymon.kotmpeg.model.TrackInfo

/**
 * La cabecera de película que comparten [Mp4Muxer] y [FragmentedMp4Muxer]: las escalas de tiempo, la
 * fecha, el cebado, la duración por defecto de una muestra y las cajas `mvhd`, `tkhd`, `mdhd`,
 * `hdlr`, `minf` y `dinf`, que los dos escriben con los mismos bytes. Lo que cambia entre ellos es lo
 * que va dentro: el MP4 plano conoce las duraciones y la tabla de muestras al escribir el `moov`, y
 * el fragmentado escribe ceros que luego parchea y un `stbl` vacío.
 *
 * Estaba copiado en los dos muxers, y las copias ya se habían separado: el MP4 plano escribía la
 * matriz identidad con [SampleEntries.writeIdentityMatrix] y el fragmentado con una copia propia, y
 * la duración por defecto de una muestra de vídeo prometía en uno «los mismos valores» que en el
 * otro mientras los dos pasaban por alto `frameRate`.
 */
internal object MovieBoxes {

    /**
     * Escala de la película, en milésimas, como la escribe FFmpeg. En ella van las duraciones de
     * `mvhd`, `tkhd` y `mehd` y las de la lista de edición, así que el hueco inicial de una pista que
     * arranca después que las demás se expresa al milisegundo: su primera muestra puede leerse hasta
     * medio milisegundo antes o después de donde se escribió.
     */
    const val MOVIE_TIMESCALE: Long = 1000L

    /** Escala de las pistas de vídeo: los 90 kHz habituales, que dan tics exactos a 24, 25, 30 y 60 fps. */
    const val VIDEO_TIMESCALE: Long = 90000L

    /**
     * La frecuencia de Opus en MP4. Opus se decodifica siempre a 48 kHz, y su encapsulado en ISOBMFF
     * exige ese valor en el `samplerate` de la entrada de muestra y recomienda esa escala en el
     * `mdhd`, sea cual sea la frecuencia de entrada, que viaja en el `dOps` (ver
     * [SampleEntries.writeAudio]).
     */
    const val OPUS_RATE: Long = 48000L

    /**
     * El origen de tiempos de MP4 es 1904-01-01 UTC y no 1970, así que hay que sumar estos segundos a
     * una marca de Unix. Con `version = 0` el campo es de 32 bits sin signo, que alcanza hasta 2040.
     */
    private const val MP4_EPOCH_OFFSET_S = 2_082_844_800L

    /** Lo que cabe en un campo de 32 bits sin signo, el de las cajas de versión 0. */
    private const val UINT32_MAX = 0xFFFFFFFFL

    /** Flags de `tkhd`: `track_enabled` (0x1) y `track_in_movie` (0x2). */
    private const val TKHD_ENABLED_IN_MOVIE = 3
    private const val TKHD_IN_MOVIE_ONLY = 2

    /** Duración por defecto de un fotograma de vídeo sin `frameRate`: 30 fps. */
    private const val DEFAULT_FRAME_US = 33_333L

    /** Muestras de un fotograma de AAC, la duración por defecto de un paquete de audio. */
    private const val AAC_FRAME_SAMPLES = 1024L

    /** La fecha [creationTimeMillis] en la escala de MP4, o 0 si no hay ninguna que escribir. */
    fun mp4Time(creationTimeMillis: Long?): Long =
        creationTimeMillis?.let { (Math.floorDiv(it, 1000L) + MP4_EPOCH_OFFSET_S).coerceAtLeast(0L) } ?: 0L

    /** Microsegundos a tics de la escala de la película, con el mismo redondeo que el resto. */
    fun toMovieTicks(us: Long): Long = Timestamps.rescaleRounded(us, MOVIE_TIMESCALE, 1_000_000)

    /** La escala del `mdhd` de [info]: 90 kHz en vídeo, [OPUS_RATE] en Opus y su frecuencia en el resto del audio. */
    fun mediaTimescale(info: TrackInfo): Long = when (info) {
        is TrackInfo.Video -> VIDEO_TIMESCALE
        is TrackInfo.Audio -> if (info.codec == AudioCodec.OPUS) OPUS_RATE else info.sampleRate.toLong()
    }

    /**
     * La versión de `mvhd`, `tkhd` y `mdhd`: la 1, de campos de 64 bits, si la duración **o la fecha**
     * no caben en 32 bits sin signo. Hasta ahora solo contaba la duración, y la fecha se truncaba en
     * silencio a partir de febrero de 2040, o antes con una `creationTimeMillis` futura: un archivo
     * creado en 2050 decía haberse creado en 1913.
     */
    fun headerVersion(duration: Long, created: Long): Int =
        if (duration > UINT32_MAX || created > UINT32_MAX) 1 else 0

    /**
     * El cebado de [info] en µs, tal como lo declara la lista de edición: el de `AudioPriming`, o 0
     * si no es audio o no llega a un tic de [timescale]. Una pista con un cebado menor que un tic no
     * lleva edición que lo salte, y descontarlo de su duración la dejaría más corta que sus ediciones.
     */
    fun primingUs(info: TrackInfo, timescale: Long): Long {
        if (info !is TrackInfo.Audio) return 0L
        val us = AudioPriming.us(info)
        return if (us > 0 && toTicks(us, timescale) > 0) us else 0L
    }

    /**
     * El cebado de [info] en tics de [timescale], redondeando: a 48 kHz los 21 333 µs de un AAC-LC son
     * 1023,98 muestras, y truncar dejaría el `initial_padding` una muestra corto.
     */
    fun primingTicks(info: TrackInfo, timescale: Long): Long = toTicks(primingUs(info, timescale), timescale)

    /**
     * Duración estimada de una muestra cuando no hay ninguna otra de la que deducirla: la de un
     * fotograma a `frameRate` en vídeo, o 30 fps si no la declara, y un fotograma de AAC (1024
     * muestras) en audio. Es la misma en los dos muxers: hasta ahora el vídeo usaba siempre 30 fps, y
     * un vídeo de una sola imagen a 25 fps duraba 33 ms en vez de 40.
     */
    fun defaultSampleDurationUs(info: TrackInfo): Long = when (info) {
        is TrackInfo.Audio -> AAC_FRAME_SAMPLES * 1_000_000 / info.sampleRate
        is TrackInfo.Video -> if (info.defaultDurationUs > 0) info.defaultDurationUs else DEFAULT_FRAME_US
    }

    private fun toTicks(us: Long, timescale: Long): Long = Timestamps.rescaleRounded(us, timescale, 1_000_000)

    /**
     * `mvhd`, con [duration] en la escala de la película y [nextTrackId] el siguiente id libre. La
     * versión la decide [headerVersion]; el muxer fragmentado, que escribe la duración a cero y la
     * parchea, localiza el campo leyendo la versión que quedó escrita.
     */
    fun writeMvhd(parent: BoxBuilder, created: Long, duration: Long, nextTrackId: Int) {
        val version = headerVersion(duration, created)
        parent.fullBox("mvhd", version, 0) {
            if (version == 1) {
                u64(created); u64(created)           // creation_time, modification_time
                u32(MOVIE_TIMESCALE)                 // timescale
                u64(duration)                        // duration
            } else {
                u32(created); u32(created)           // creation_time, modification_time
                u32(MOVIE_TIMESCALE)                 // timescale
                u32(duration)                        // duration
            }
            u32(0x00010000)                          // rate 1.0
            u16(0x0100)                              // volume 1.0
            u16(0); u32(0); u32(0)                   // reserved
            SampleEntries.writeIdentityMatrix(this)
            zeros(24)                                // pre_defined
            u32(nextTrackId)                         // next_track_ID
        }
    }

    /**
     * Un `trak` entero salvo lo que es de cada muxer: la lista de edición, que escribe [edts] detrás del
     * `tkhd`, y la tabla de muestras, que escribe [stbl] dentro del `minf`.
     *
     * [duration] va en la escala de la película y es la del `tkhd`; [mediaDuration], en [timescale], la
     * del `mdhd`. [isDefault] es [TrackInfo.default] resuelto, que va en el bit `track_enabled`.
     */
    fun writeTrak(
        parent: BoxBuilder,
        info: TrackInfo,
        isDefault: Boolean,
        created: Long,
        duration: Long,
        timescale: Long,
        mediaDuration: Long,
        edts: BoxBuilder.() -> Unit,
        stbl: BoxBuilder.() -> Unit,
    ) {
        parent.box("trak") {
            writeTkhd(this, info, isDefault, created, duration)
            edts()
            box("mdia") {
                writeMdhd(this, info, created, timescale, mediaDuration)
                fullBox("hdlr", 0, 0) {
                    u32(0)                           // pre_defined
                    fourcc(if (info is TrackInfo.Video) "vide" else "soun") // handler_type
                    u32(0); u32(0); u32(0)           // reserved
                    bytes("Kotmpeg".toByteArray(Charsets.US_ASCII)); u8(0) // name, terminado en nulo
                }
                box("minf") {
                    if (info is TrackInfo.Video) {
                        fullBox("vmhd", 0, 1) { u16(0); u16(0); u16(0); u16(0) } // graphicsmode, opcolor
                    } else {
                        fullBox("smhd", 0, 0) { u16(0); u16(0) } // balance, reserved
                    }
                    box("dinf") {
                        fullBox("dref", 0, 0) {
                            u32(1)                   // entry_count
                            fullBox("url ", 0, 1) {} // autocontenido
                        }
                    }
                    stbl()
                }
            }
            writeTrackName(this, info)
        }
    }

    private fun writeTkhd(parent: BoxBuilder, info: TrackInfo, isDefault: Boolean, created: Long, duration: Long) {
        val version = headerVersion(duration, created)
        parent.fullBox("tkhd", version, if (isDefault) TKHD_ENABLED_IN_MOVIE else TKHD_IN_MOVIE_ONLY) {
            if (version == 1) {
                u64(created); u64(created)           // creation_time, modification_time
                u32(info.id)                         // track_ID
                u32(0)                               // reserved
                u64(duration)                        // duration
            } else {
                u32(created); u32(created)           // creation_time, modification_time
                u32(info.id)                         // track_ID
                u32(0)                               // reserved
                u32(duration)                        // duration
            }
            u32(0); u32(0)                           // reserved
            u16(0)                                   // layer
            u16(0)                                   // alternate_group
            u16(if (info is TrackInfo.Audio) 0x0100 else 0) // volume
            u16(0)                                   // reserved
            if (info is TrackInfo.Video) {
                SampleEntries.writeDisplayMatrix(this, info.rotationDegrees, info.displayWidth, info.displayHeight)
                u32(info.displayWidth.toLong() shl 16)  // width
                u32(info.displayHeight.toLong() shl 16) // height
            } else {
                SampleEntries.writeIdentityMatrix(this)
                u32(0); u32(0)                       // width, height
            }
        }
    }

    private fun writeMdhd(parent: BoxBuilder, info: TrackInfo, created: Long, timescale: Long, duration: Long) {
        val version = headerVersion(duration, created)
        parent.fullBox("mdhd", version, 0) {
            if (version == 1) {
                u64(created); u64(created)           // creation_time, modification_time
                u32(timescale)                       // timescale
                u64(duration)                        // duration
            } else {
                u32(created); u32(created)           // creation_time, modification_time
                u32(timescale)                       // timescale
                u32(duration)                        // duration
            }
            u16(SampleEntries.packLanguage(info.language)) // language, ISO 639-2/T
            u16(0)                                   // pre_defined
        }
    }

    /**
     * Nombre legible de la pista, en `udta` > `name` dentro del `trak`.
     *
     * No vale el texto del `hdlr`: ese es el nombre del *manejador* —sale «Kotmpeg» en todas las
     * pistas— y ningún reproductor lo usa para distinguirlas. Con varias pistas del mismo tipo, esto
     * es lo único que permite saber cuál es cuál.
     */
    private fun writeTrackName(parent: BoxBuilder, info: TrackInfo) {
        val name = info.name ?: return
        parent.box("udta") {
            box("name") {
                bytes(name.toByteArray(Charsets.UTF_8))
            }
        }
    }
}
