package com.braymon.kotmpeg

import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.mkv.MkvDemuxer
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import com.braymon.kotmpeg.mp4.Mp4Demuxer
import com.braymon.kotmpeg.mp4.Mp4Muxer
import com.braymon.kotmpeg.pipeline.Remuxer
import java.io.File

/**
 * Punto de entrada del motor de contenedores en Kotlin puro (MKV + MP4).
 *
 * ```kotlin
 * // Escribir un archivo
 * Kotmpeg.createMuxer(File("out.mkv")).use { muxer ->
 *     val videoId = muxer.addTrack(TrackInfo.Video(VideoCodec.H264, 1920, 1080) { codecPrivate = avcC })
 *     muxer.start()
 *     muxer.writePacket(MediaPacket(videoId, frameBytes, ptsUs = 0, isKeyFrame = true))
 * }
 *
 * // Leer un archivo
 * Kotmpeg.openDemuxer(File("in.mp4")).use { demuxer ->
 *     while (true) { val p = demuxer.readPacket() ?: break }
 * }
 *
 * // Reempaquetar sin recodificar (ffmpeg -c copy)
 * Kotmpeg.remux(File("in.mp4"), File("out.mkv"))
 * ```
 *
 * Hasta la 3.x se llamaba `MkvKotlin`, un nombre de cuando la librería solo escribía Matroska.
 */
public object Kotmpeg {

    /**
     * Crea un muxer para [file]; el formato se deduce de la extensión salvo que se pase [format].
     *
     * [options] configura el muxer (ver [MuxerOptions]); las opciones de un formato se ignoran en
     * los demás, así que el mismo bloque sirve aunque la extensión decida el contenedor. Con MP4,
     * `fragmented` elige el MP4 fragmentado.
     *
     * `.webm` no se deduce: WebM no admite los códecs que escribe esta librería, y el error lo
     * explica.
     */
    public fun createMuxer(
        file: File,
        format: ContainerFormat? = null,
        options: MuxerOptions.() -> Unit = {},
    ): Muxer {
        val fmt = format ?: ContainerFormat.fromFileName(file.name) ?: throw IllegalArgumentException(unknownExtension(file.name))
        val validated = MuxerOptions().apply(options).validated()
        return when (fmt) {
            ContainerFormat.MKV -> MkvMuxer(validated, file)
            ContainerFormat.MP4 ->
                if (validated.fragmented) FragmentedMp4Muxer(validated, file) else Mp4Muxer(validated, file)
        }
    }

    /**
     * Variante de [createMuxer] sobre un [SeekableOutput] ya abierto, para escribir a un
     * destino que no es una ruta del sistema de archivos — el caso de `MediaStore`/SAF en
     * Android, donde solo se tiene un `ParcelFileDescriptor`:
     *
     * ```kotlin
     * contentResolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
     *     Kotmpeg.createMuxer(SeekableOutput(pfd.fileDescriptor), ContainerFormat.MP4) { fastStart = true }
     * }
     * ```
     *
     * [format] es obligatorio: sin nombre de archivo no hay extensión de la que deducirlo. Con
     * `fastStart` el `moov` se recoloca desplazando los datos en el sitio, sin temporal (ver
     * `Mp4Muxer.stop`), y para eso el descriptor tiene que abrirse en `"rw"`. Si las opciones no
     * valen, [output] se cierra antes de lanzar.
     */
    public fun createMuxer(
        output: SeekableOutput,
        format: ContainerFormat,
        options: MuxerOptions.() -> Unit = {},
    ): Muxer {
        val validated = MuxerOptions().apply(options).validatedFor(output)
        return when (format) {
            ContainerFormat.MKV -> MkvMuxer(output, validated)
            ContainerFormat.MP4 ->
                if (validated.fragmented) FragmentedMp4Muxer(output, validated) else Mp4Muxer(output, null, validated)
        }
    }

    /** Por qué no se pudo deducir el formato de salida de [name], con lo que hay que hacer. */
    private fun unknownExtension(name: String): String =
        if (name.substringAfterLast('.').equals("webm", ignoreCase = true)) {
            "$name: WebM solo admite VP8, VP9 o AV1 con Vorbis u Opus, y esta librería escribe " +
                "H.264/H.265 con AAC u Opus. Usa la extensión .mkv"
        } else {
            "no se puede deducir el formato de salida de $name: usa .mkv, .mp4, .m4a, .m4v o .mov, o pasa el formato"
        }

    /**
     * Abre un demuxer detectando el contenedor por los bytes mágicos del archivo.
     *
     * [onWarning] recibe los descartes no fatales: una pista cuyo formato no se reconoce, un
     * `trak` que no parsea, un fragmento ilegible, un índice que no se puede usar. **Vale la pena
     * pasarlo**: sin él, esas pérdidas no dejan rastro y el síntoma que llega es "el vídeo se abre
     * pero no tiene audio", sin nada que lo explique.
     */
    public fun openDemuxer(file: File, onWarning: (String) -> Unit = {}): Demuxer {
        return when (detectFormat(file)) {
            ContainerFormat.MKV -> MkvDemuxer(SeekableInput(file), onWarning = onWarning)
            ContainerFormat.MP4 -> Mp4Demuxer(SeekableInput(file), onWarning = onWarning)
            null -> throw IllegalArgumentException("${file.name} no es un archivo MKV ni MP4")
        }
    }

    /**
     * Variante de [openDemuxer] sobre un [SeekableInput] ya abierto (`MediaStore`/SAF). El
     * formato se detecta igual, leyendo la cabecera, así que no hay que declararlo.
     *
     * Si no se reconoce el contenedor se cierra la entrada antes de lanzar: quien pasa un
     * `SeekableInput` ya no tiene otra forma de recuperar el descriptor.
     */
    public fun openDemuxer(input: SeekableInput, onWarning: (String) -> Unit = {}): Demuxer {
        val format = runCatching { detectFormat(input) }
            .getOrElse { input.close(); throw it }
        return when (format) {
            ContainerFormat.MKV -> MkvDemuxer(input, onWarning = onWarning)
            ContainerFormat.MP4 -> Mp4Demuxer(input, onWarning = onWarning)
            null -> {
                input.close()
                throw IllegalArgumentException("la entrada no es MKV ni MP4")
            }
        }
    }

    /** Detecta el formato del contenedor por los primeros bytes del archivo. */
    public fun detectFormat(file: File): ContainerFormat? =
        SeekableInput(file).use { detectFormat(it) }

    /**
     * Detecta el formato leyendo la cabecera de [input] y **deja la posición donde estaba**,
     * para que el demuxer que venga después parsee desde el principio.
     */
    public fun detectFormat(input: SeekableInput): ContainerFormat? {
        if (input.length < 12) return null
        val saved = input.position
        val head = try {
            input.position = 0
            input.readBytes(12)
        } finally {
            input.position = saved
        }
        if (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
            head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
        ) return ContainerFormat.MKV
        val type = String(head, 4, 4, Charsets.US_ASCII)
        if (type == "ftyp" || type == "moov" || type == "mdat" || type == "free" || type == "wide") {
            return ContainerFormat.MP4
        }
        return null
    }

    /**
     * Concatena [inputs] en [output] sin recodificar (el demuxer `concat` de FFmpeg).
     * Las entradas deben compartir disposición de pistas y cabeceras de códec —segmentos
     * con los mismos ajustes de grabación—; si no, el error dice qué difiere (ver
     * `Remuxer.concat`). Devuelve el número de paquetes escritos.
     *
     * [onWarning] recibe los mismos avisos que en [openDemuxer], por cada entrada, y [options]
     * configura el muxer de salida igual que en [createMuxer].
     *
     * Las entradas se abren de una en una y el `finally` cierra las que ya lo estuvieran: si
     * una falla a mitad —no es MKV ni MP4, cabecera corrupta, permisos— las anteriores no se
     * quedan con el descriptor abierto, que en Windows además impedía borrarlas o moverlas.
     *
     * Todo lo que se puede comprobar antes de escribir —que las entradas se abren y son
     * compatibles— se comprueba antes de abrir [output], así que una llamada rechazada deja
     * intacto lo que hubiera en esa ruta. Hasta ahora la truncaba primero y la dejaba vacía.
     */
    public fun concat(
        inputs: List<File>,
        output: File,
        format: ContainerFormat? = null,
        onProgress: ((Long) -> Unit)? = null,
        onWarning: (String) -> Unit = {},
        options: MuxerOptions.() -> Unit = {},
    ): Long {
        require(inputs.isNotEmpty()) { "sin entradas" }
        requireDistinct(inputs, output)
        val demuxers = ArrayList<Demuxer>(inputs.size)
        try {
            for (file in inputs) demuxers.add(openDemuxer(file, onWarning))
            Remuxer.requireConcatenable(demuxers)
            return createMuxer(output, format, options).use { muxer ->
                Remuxer.concat(demuxers, muxer, onProgress)
            }
        } finally {
            demuxers.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Reempaqueta [input] en [output] sin recodificar, en cualquier dirección (MKV <-> MP4
     * o limpieza en el mismo formato). Devuelve el número de paquetes copiados.
     *
     * [trackFilter] recibe el id de cada pista de entrada y descarta las que devuelvan `false`;
     * [onWarning] y [options], como en [concat]. Como allí, una llamada que no puede salir bien
     * —ninguna pista seleccionada, vídeo sin `codecPrivate`— se rechaza antes de abrir [output].
     */
    public fun remux(
        input: File,
        output: File,
        format: ContainerFormat? = null,
        trackFilter: (Int) -> Boolean = { true },
        onProgress: ((Long) -> Unit)? = null,
        onWarning: (String) -> Unit = {},
        options: MuxerOptions.() -> Unit = {},
    ): Long {
        requireDistinct(listOf(input), output)
        val demuxer = openDemuxer(input, onWarning)
        try {
            Remuxer.requireWritable(demuxer.tracks.filter { trackFilter(it.id) })
            return createMuxer(output, format, options).use { muxer ->
                Remuxer.remux(demuxer, muxer, trackFilter, onProgress)
            }
        } finally {
            demuxer.close()
        }
    }

    /**
     * Rechaza que el destino sea también una de las entradas, lanzando `IllegalArgumentException`
     * con un mensaje que dice qué hacer.
     *
     * El muxer trunca el archivo de salida a cero nada más abrirlo, así que `remux(f, f)` o
     * `concat(listOf(a, b), a)` se ejecutaban sin error aparente mientras leían un archivo
     * que se estaba reescribiendo por debajo: el resultado depende de que el escritor no
     * adelante al lector, es decir, es indefinido.
     *
     * Se compara por ruta canónica para que un enlace simbólico o una ruta relativa distinta
     * al mismo archivo tampoco se cuelen; si el sistema de archivos no puede canonizarla se
     * cae a la ruta absoluta, que es lo mejor disponible.
     *
     * [remux] y [concat] ya la aplican por su cuenta, así que **no hace falta llamarla** para
     * usarlas. Está expuesta para quien arme su propia canalización con [openDemuxer] y
     * [createMuxer], donde no hay ningún punto central que pueda hacer esta comprobación: es la
     * misma validación que la fachada, en vez de una versión propia que se olvide de los enlaces
     * simbólicos.
     *
     * @param inputs archivos que se van a leer.
     * @param output archivo que se va a escribir, y que se truncará al abrirlo.
     */
    public fun requireDistinct(inputs: List<File>, output: File) {
        fun key(f: File): File = runCatching { f.canonicalFile }.getOrDefault(f.absoluteFile)
        val out = key(output)
        require(inputs.none { key(it) == out }) {
            "la salida ${output.name} es también una de las entradas: se truncaría y " +
                "reescribiría mientras se lee. Escribe a un archivo temporal y renómbralo."
        }
    }

    /**
     * Envuelve [muxer] para poder llamarlo desde varios hilos a la vez.
     *
     * Los muxers son de un solo hilo a propósito, pero en una app móvil lo normal es que el
     * codificador de vídeo y el de audio entreguen sus paquetes cada uno desde su propio hilo
     * (los callbacks de `MediaCodec`). Esta envoltura serializa cada operación con un único
     * cerrojo, que es exactamente lo que habría que escribir a mano, sin el riesgo de olvidarse
     * de una de las cinco.
     *
     * ```kotlin
     * val muxer = Kotmpeg.synchronizedMuxer(Kotmpeg.createMuxer(File("salida.mp4")) { fragmented = true })
     * ```
     *
     * Envolver uno que ya lo está devuelve el mismo objeto.
     */
    public fun synchronizedMuxer(muxer: Muxer): Muxer =
        if (muxer is SynchronizedMuxer) muxer else SynchronizedMuxer(muxer)

    private class SynchronizedMuxer(private val delegate: Muxer) : Muxer {
        private val lock = Any()

        override fun addTrack(track: TrackInfo): Int = synchronized(lock) { delegate.addTrack(track) }

        override fun start(): Unit = synchronized(lock) { delegate.start() }

        override fun writePacket(packet: MediaPacket): Unit = synchronized(lock) { delegate.writePacket(packet) }

        override fun stop(): Unit = synchronized(lock) { delegate.stop() }

        override fun close(): Unit = synchronized(lock) { delegate.close() }
    }
}
