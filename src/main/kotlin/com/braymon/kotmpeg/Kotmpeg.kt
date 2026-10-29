package com.braymon.kotmpeg

import com.braymon.kotmpeg.io.RepairTarget
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.mkv.MkvDemuxer
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.mkv.MkvRepair
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import com.braymon.kotmpeg.mp4.FragmentedMp4Repair
import com.braymon.kotmpeg.mp4.Mp4Demuxer
import com.braymon.kotmpeg.mp4.Mp4Muxer
import com.braymon.kotmpeg.pipeline.Remuxer
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.nio.file.Files

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
        if (name.substringAfterLast('.', "").equals("webm", ignoreCase = true)) {
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
     * `SeekableInput` no recibe ningún demuxer con el que cerrarla después. Lo que se cierra es el
     * canal; en Android, el `ParcelFileDescriptor` del que salió sigue siendo de quien lo abrió y se
     * cierra aparte (con `use`).
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
        try {
            input.position = 0
            val head = input.readBytes(12)
            if (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
            ) return ContainerFormat.MKV
            return if (startsLikeMp4(input)) ContainerFormat.MP4 else null
        } finally {
            input.position = saved
        }
    }

    /**
     * Si [input] empieza como un MP4: con una caja de [MP4_OPENING_BOXES], o con cajas de
     * [MP4_LEADING_BOXES] seguidas de una de ellas.
     *
     * Las de [MP4_LEADING_BOXES] abren MP4 legítimos —un `skip` de relleno, que la reparación ya
     * aceptaba, la vista previa `pnot` de los `.mov` antiguos o un `uuid` de fabricante—, pero son
     * cuatro letras demasiado corrientes para fiarse solo de ellas: se exige además que su tamaño
     * quepa en el archivo y que lo que venga detrás sea otra caja conocida. Hasta la 4.0 un archivo
     * que empezaba así se rechazaba como «ni MKV ni MP4».
     *
     * Un `pnot` no va solo: apunta a la imagen de la vista previa, que va detrás de él, en una de
     * [MOV_PREVIEW_BOXES]. Se acepta ahí y en ningún otro sitio, con las mismas condiciones. Sin
     * aceptarla, un `.mov` con vista previa de verdad se rechazaría igual: solo pasaría uno con el
     * `pnot` y sin su imagen.
     */
    private fun startsLikeMp4(input: SeekableInput): Boolean {
        var at = 0L
        var previous = ""
        repeat(MAX_LEADING_BOXES + 1) {
            if (at > input.length - 8) return false
            input.position = at
            val size = input.readUInt(4)
            val type = String(input.readBytes(4), Charsets.US_ASCII)
            if (type in MP4_OPENING_BOXES) return true
            val preview = previous == "pnot" && type in MOV_PREVIEW_BOXES
            if (type !in MP4_LEADING_BOXES && !preview) return false
            val minimum = if (type == "uuid") 24L else 8L
            if (size < minimum || size > input.length - at) return false
            at += size
            previous = type
        }
        return false
    }

    /** Cajas que, en cabeza del archivo, bastan para tomarlo por MP4. */
    private val MP4_OPENING_BOXES = setOf("ftyp", "moov", "mdat", "free", "wide")

    /** Cajas que pueden ir delante de las de [MP4_OPENING_BOXES], pero que solas no prueban nada. */
    private val MP4_LEADING_BOXES = setOf("skip", "pnot", "uuid")

    /**
     * La imagen de la vista previa de un `.mov`, que solo se acepta detrás de su `pnot`: `PICT`, la
     * habitual, y `pict`, que FFmpeg también reconoce.
     */
    private val MOV_PREVIEW_BOXES = setOf("PICT", "pict")

    /**
     * Cuántas cajas de [MP4_LEADING_BOXES], o imágenes tras un `pnot`, se saltan como mucho antes de
     * dar el archivo por desconocido.
     */
    private const val MAX_LEADING_BOXES = 4

    /**
     * Concatena [inputs] en [output] sin recodificar (el demuxer `concat` de FFmpeg).
     * Las entradas deben compartir disposición de pistas y cabeceras de códec —segmentos
     * con los mismos ajustes de grabación—; si no, el error dice qué difiere (ver
     * `Remuxer.concat`). Devuelve el número de paquetes escritos.
     *
     * [onWarning] recibe los mismos avisos que en [openDemuxer], por cada entrada, y [options]
     * configura el muxer de salida igual que en [createMuxer].
     *
     * **Nunca hay más de una entrada abierta.** Primero se abre cada una solo para leer sus pistas y
     * se cierra; comprobado que todas encajan, se copian en orden, abriéndolas otra vez de una en
     * una. Hasta la 4.0 se tenían todas abiertas a la vez —un descriptor, un buffer de 64 KiB y el
     * índice de cada una—, y con un millar largo de segmentos se agotaban los descriptores del
     * proceso o la memoria. Los avisos que da cada entrada al abrirse llegan a [onWarning] una sola
     * vez: en la segunda apertura se descartan, porque son los mismos de la primera.
     *
     * Todo lo que se puede comprobar antes de escribir —que las entradas se abren y son
     * compatibles— se comprueba antes de abrir [output], así que una llamada rechazada deja
     * intacto lo que hubiera en esa ruta. Hasta la 4.0 la truncaba primero y la dejaba vacía.
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
        val layouts = inputs.map { file -> openDemuxer(file, onWarning).use { it.tracks } }
        Remuxer.requireConcatenable(layouts)
        return createMuxer(output, format, options).use { muxer ->
            val concatenation = Remuxer.Concatenation(layouts.first(), muxer, onProgress)
            for ((index, file) in inputs.withIndex()) {
                var opening = true
                openDemuxer(file) { if (!opening) onWarning(it) }.use { demuxer ->
                    opening = false
                    concatenation.append(index, demuxer)
                }
            }
            concatenation.finish()
        }
    }

    /**
     * Reempaqueta [input] en [output] sin recodificar, en cualquier dirección (MKV <-> MP4
     * o limpieza en el mismo formato). Devuelve el número de paquetes copiados.
     *
     * [trackFilter] recibe el id de cada pista de entrada, **una sola vez por pista**, y descarta las
     * que devuelvan `false`; [onWarning] y [options], como en [concat]. Como allí, una llamada que no
     * puede salir bien —ninguna pista seleccionada, vídeo sin `codecPrivate`— se rechaza antes de
     * abrir [output]. Hasta la 4.0 el filtro se evaluaba dos veces, al comprobar y al copiar, y uno
     * con estado podía no seleccionar nada la segunda vez: el error llegaba con [output] ya vacía.
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
            val selected = demuxer.tracks.filter { trackFilter(it.id) }
            Remuxer.requireWritable(selected)
            val selectedIds = selected.mapTo(HashSet()) { it.id }
            return createMuxer(output, format, options).use { muxer ->
                Remuxer.remux(demuxer, muxer, { it in selectedIds }, onProgress)
            }
        } finally {
            demuxer.close()
        }
    }

    /**
     * Cierra en el sitio un MKV o un MP4 fragmentado que se quedó sin cerrar —el proceso murió
     * antes de `stop()`—, como lo habría cerrado su muxer, sin copiar el archivo:
     *
     *  - **MKV**: quita lo que el corte dejó a medias tras el último bloque entero, escribe el
     *    tamaño del último cluster, reconstruye el índice `Cues` y escribe la duración, el
     *    `SeekHead` y el tamaño del `Segment`.
     *  - **MP4 fragmentado**: deja del fragmento a medias las muestras que están completas, pone la
     *    duración en la cabecera y añade el índice `mfra`.
     *
     * Con `cuesReserveBytes` el MKV ya se abre y se recorre aunque no se repare, y el fMP4 siempre;
     * reparar deja además el archivo igual de cerrado que si se hubiera llegado a `stop()`, sin
     * necesitar otra vez su tamaño en disco. Con un archivo de los muxers de esta librería tiene los
     * mismos paquetes y la misma duración que reempaquetarlo con [remux]; con uno de otro programa
     * los paquetes son los mismos, pero el remux puede intercalar las pistas en otro orden o
     * redondear la duración en un microsegundo.
     *
     * Un archivo ya cerrado no se toca (`repaired = false`). Lo que no se reconoce —otro
     * contenedor, un MP4 plano, una cabecera cortada, daños que no están al final— se rechaza con
     * `IllegalArgumentException` **sin modificar nada**: todas las comprobaciones se hacen antes de
     * escribir. Si la reparación se interrumpe a medias, el archivo sigue pareciendo sin cerrar y se
     * puede volver a lanzar.
     */
    public fun repair(file: File): RepairResult {
        if (!file.isFile) throw FileNotFoundException("${file.path} no existe o no es un archivo")
        return RepairTarget.open(file).use { repair(it) }
    }

    /**
     * Variante de [repair] sobre un descriptor ya abierto (`MediaStore`/SAF), que tiene que poder
     * leerse y escribirse: con `ContentResolver`, `openFileDescriptor(uri, "rw")`. Uno de solo
     * escritura —el modo `"w"`— se rechaza con `IllegalArgumentException` antes de leer nada; uno de
     * solo lectura falla con la `IOException` de la primera escritura, que no llega a cambiar nada.
     *
     * Cierra los canales que abre sobre el descriptor, pero el descriptor es de quien lo abrió: en
     * Android `FileInputStream`/`FileOutputStream` no se adueñan de un `FileDescriptor` ajeno, así que
     * el `ParcelFileDescriptor` hay que cerrarlo aparte (con `use`).
     */
    public fun repair(fd: FileDescriptor): RepairResult = RepairTarget.open(fd).use { repair(it) }

    /** La reparación sobre un destino ya abierto; los tests la llaman sobre un canal que registra cada operación. */
    internal fun repair(target: RepairTarget): RepairResult =
        when (detectFormat(target.input)) {
            ContainerFormat.MKV -> MkvRepair(target).run()
            ContainerFormat.MP4 -> FragmentedMp4Repair(target).run()
            null -> throw IllegalArgumentException("no se puede reparar: no es un archivo MKV ni MP4")
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
     * cae a la ruta absoluta, que es lo mejor disponible. Si [output] ya existe, se pregunta
     * además al sistema con `Files.isSameFile`, que reconoce lo que ninguna ruta delata: un enlace
     * duro, otro nombre del mismo archivo. Hasta la 4.0 `remux(f, enlaceDuro)` se aceptaba y
     * truncaba la entrada.
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
        fun sameExistingFile(f: File): Boolean =
            f.exists() && runCatching { Files.isSameFile(f.toPath(), output.toPath()) }.getOrDefault(false)
        val out = key(output)
        val outputExists = output.exists()
        require(inputs.none { key(it) == out || (outputExists && sameExistingFile(it)) }) {
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
