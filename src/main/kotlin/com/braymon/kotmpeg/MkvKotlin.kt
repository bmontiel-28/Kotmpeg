package com.braymon.kotmpeg

import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.mkv.MkvDemuxer
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.ContainerFormat
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
 * val muxer = MkvKotlin.createMuxer(File("out.mkv"))
 * val videoId = muxer.addTrack(TrackInfo.Video(codec = VideoCodec.H264, width = 1920, height = 1080, codecPrivate = avcC))
 * muxer.start()
 * muxer.writePacket(MediaPacket(videoId, frameBytes, ptsUs = 0, isKeyFrame = true))
 * muxer.stop()
 *
 * // Leer un archivo
 * val demuxer = MkvKotlin.openDemuxer(File("in.mp4"))
 * while (true) { val p = demuxer.readPacket() ?: break }
 *
 * // Reempaquetar sin recodificar (ffmpeg -c copy)
 * MkvKotlin.remux(File("in.mp4"), File("out.mkv"))
 * ```
 */
public object MkvKotlin {

    /**
     * Crea un muxer para [file]; el formato se infiere de la extensión salvo que se pase [format].
     *
     * Opciones MP4 (ignoradas para MKV, mutuamente excluyentes):
     *  - [mp4FastStart]: recoloca el `moov` antes de los datos al finalizar
     *    (`-movflags +faststart` de FFmpeg), para reproducción progresiva/en red.
     *  - [mp4Fragmented]: escribe MP4 fragmentado — fMP4/CMAF, el
     *    `-movflags frag_keyframe+empty_moov` de FFmpeg. Salida solo-añadir: grabación a
     *    prueba de cortes y streaming en vivo (empaquetado HLS/DASH). Un fragmento por GOP
     *    de vídeo, o por [mp4FragmentDurationUs] en archivos solo-audio.
     */
    public fun createMuxer(
        file: File,
        format: ContainerFormat? = null,
        mp4FastStart: Boolean = false,
        mp4Fragmented: Boolean = false,
        mp4FragmentDurationUs: Long = 2_000_000,
    ): Muxer {
        require(!(mp4FastStart && mp4Fragmented)) {
            "mp4FastStart y mp4Fragmented son excluyentes (el fMP4 no necesita faststart: su cabecera ya va delante)"
        }
        val fmt = format ?: ContainerFormat.fromFileName(file.name)
            ?: throw IllegalArgumentException("no se puede inferir el formato de ${file.name}")
        return when (fmt) {
            ContainerFormat.MKV -> MkvMuxer(file)
            ContainerFormat.MP4 ->
                if (mp4Fragmented) FragmentedMp4Muxer(file, mp4FragmentDurationUs)
                else Mp4Muxer(file, fastStart = mp4FastStart)
        }
    }

    /**
     * Variante de [createMuxer] sobre un [SeekableOutput] ya abierto, para escribir a un
     * destino que no es una ruta del sistema de archivos — el caso de `MediaStore`/SAF en
     * Android, donde solo se tiene un `ParcelFileDescriptor`:
     *
     * ```kotlin
     * contentResolver.openFileDescriptor(uri, "rwt")!!.use { pfd ->
     *     MkvKotlin.createMuxer(SeekableOutput(pfd.fileDescriptor), ContainerFormat.MP4)
     * }
     * ```
     *
     * [format] es obligatorio: sin nombre de archivo no hay extensión de la que inferirlo.
     * No hay opción de `faststart` porque esa reescritura necesita reabrir el archivo por
     * ruta; para un destino sin ruta el equivalente es [mp4Fragmented], cuya cabecera ya va
     * delante por construcción.
     */
    public fun createMuxer(
        output: SeekableOutput,
        format: ContainerFormat,
        mp4Fragmented: Boolean = false,
        mp4FragmentDurationUs: Long = 2_000_000,
    ): Muxer = when (format) {
        ContainerFormat.MKV -> MkvMuxer(output)
        ContainerFormat.MP4 ->
            if (mp4Fragmented) FragmentedMp4Muxer(output, mp4FragmentDurationUs)
            else Mp4Muxer(output)
    }

    /**
     * Abre un demuxer detectando el contenedor por los bytes mágicos del archivo.
     *
     * [onWarning] recibe los descartes no fatales: una pista cuyo formato no se reconoce, un
     * `trak` que no parsea, un fragmento ilegible. **Vale la pena pasarlo**: sin él, esas
     * pistas desaparecen sin dejar rastro y el síntoma que llega es "el vídeo se abre pero no
     * tiene audio", sin nada que explicarlo. Es opcional para no romper a quien ya llamaba a
     * esta función con un solo argumento.
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
    private fun detectFormat(input: SeekableInput): ContainerFormat? {
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
     * Las entradas deben compartir disposición de pistas y parámetros de códec (segmentos
     * con los mismos ajustes de grabación). Devuelve el número de paquetes escritos.
     *
     * Las entradas se abren de una en una y el `finally` cierra las que ya lo estuvieran: si
     * una falla a mitad —no es MKV ni MP4, cabecera corrupta, permisos— las anteriores no se
     * quedan con el descriptor abierto, que en Windows además impedía borrarlas o moverlas.
     */
    public fun concat(
        inputs: List<File>,
        output: File,
        format: ContainerFormat? = null,
        mp4FastStart: Boolean = false,
        mp4Fragmented: Boolean = false,
        onProgress: ((Long) -> Unit)? = null,
        /**
         * Mismos avisos que en [openDemuxer], por cada entrada: pistas descartadas al leerla.
         * Sin esto una concatenación podía perder una pista por el camino sin poder decirlo.
         */
        onWarning: (String) -> Unit = {},
    ): Long {
        require(inputs.isNotEmpty()) { "sin entradas" }
        requireDistinct(inputs, output)
        val demuxers = ArrayList<Demuxer>(inputs.size)
        try {
            for (file in inputs) demuxers.add(openDemuxer(file, onWarning))
            return createMuxer(output, format, mp4FastStart, mp4Fragmented).use { muxer ->
                Remuxer.concat(demuxers, muxer, onProgress)
            }
        } finally {
            demuxers.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Reempaqueta [input] en [output] sin recodificar, en cualquier dirección (MKV <-> MP4
     * o limpieza en el mismo formato). Devuelve el número de paquetes copiados.
     */
    public fun remux(
        input: File,
        output: File,
        format: ContainerFormat? = null,
        mp4FastStart: Boolean = false,
        mp4Fragmented: Boolean = false,
        trackFilter: (Int) -> Boolean = { true },
        onProgress: ((Long) -> Unit)? = null,
        /** Mismos avisos que en [openDemuxer]: pistas descartadas al leer la entrada. */
        onWarning: (String) -> Unit = {},
    ): Long {
        requireDistinct(listOf(input), output)
        val demuxer = openDemuxer(input, onWarning)
        try {
            return createMuxer(output, format, mp4FastStart, mp4Fragmented).use { muxer ->
                Remuxer.remux(demuxer, muxer, trackFilter, onProgress)
            }
        } finally {
            demuxer.close()
        }
    }

    /**
     * Rechaza que el destino sea también una de las entradas.
     *
     * El muxer trunca el archivo de salida a cero nada más abrirlo, así que `remux(f, f)` o
     * `concat(listOf(a, b), a)` se ejecutaban sin error aparente mientras leían un archivo
     * que se estaba reescribiendo por debajo: el resultado depende de que el escritor no
     * adelante al lector, es decir, es indefinido.
     *
     * Se compara por ruta canónica para que un enlace simbólico o una ruta relativa distinta
     * al mismo archivo tampoco se cuelen; si el sistema de archivos no puede canonizarla se
     * cae a la ruta absoluta, que es lo mejor disponible.
     */
    internal fun requireDistinct(inputs: List<File>, output: File) {
        fun key(f: File): File = runCatching { f.canonicalFile }.getOrDefault(f.absoluteFile)
        val out = key(output)
        require(inputs.none { key(it) == out }) {
            "la salida ${output.name} es también una de las entradas: se truncaría y " +
                "reescribiría mientras se lee. Escribe a un archivo temporal y renómbralo."
        }
    }
}
