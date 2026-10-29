package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.BoxBuilder
import com.braymon.kotmpeg.mp4.Mp4Demuxer
import java.io.File

/**
 * Lo que comparten los tests `Mp4Read*`: archivos pequeños escritos por los muxers de la librería,
 * y las herramientas para **mutarlos** caja a caja —buscar una por su ruta, sustituirla o insertar
 * otra rehaciendo el tamaño de todas las que la contienen— o para añadirles fragmentos construidos
 * a mano con [BoxBuilder].
 *
 * Las entradas se construyen siempre así, sin archivos de vídeo en el repositorio: cada test parte
 * de un archivo válido y cambia exactamente lo que el hallazgo describe. Los MP4 planos que escribe
 * `Mp4Muxer` llevan el `moov` al final, así que hacerlo crecer no mueve ningún chunk; los `trun` de
 * `FragmentedMp4Muxer` direccionan relativos a su `moof`, y tampoco.
 */
internal object Mp4ReadFixtures {

    val avcC: ByteArray = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    fun video(width: Int = 640, height: Int = 480, configure: TrackInfo.Video.Builder.() -> Unit = {}): TrackInfo.Video =
        TrackInfo.Video(VideoCodec.H264, width, height) {
            frameRate = 30.0
            codecPrivate = avcC
            configure()
        }

    fun audio(): TrackInfo.Audio = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) }

    /** Una NAL con prefijo de longitud y contenido reconocible por [i]. */
    fun nal(i: Int, size: Int = 24): ByteArray = NalUnits.joinLengthPrefixed(listOf(ByteArray(size) { (i + it).toByte() }))

    /**
     * Escribe [videoFrames] fotogramas a 30 fps, un keyframe cada [gop], y [audioFrames] paquetes de
     * AAC de 21,333 ms, en el formato que se pida.
     */
    fun write(
        file: File,
        fragmented: Boolean = false,
        videoTrack: TrackInfo.Video? = video(),
        videoFrames: Int = 30,
        audioFrames: Int = 0,
        gop: Int = 10,
    ): File {
        Kotmpeg.createMuxer(file, ContainerFormat.MP4) { this.fragmented = fragmented; fragmentDurationUs = 1_000_000 }.use { muxer ->
            val v = videoTrack?.let { muxer.addTrack(it) }
            val a = if (audioFrames > 0) muxer.addTrack(audio()) else null
            muxer.start()
            val packets = ArrayList<MediaPacket>()
            if (v != null) {
                for (i in 0 until videoFrames) packets += MediaPacket(v, nal(i), i * 33_333L, i * 33_333L, i % gop == 0, 33_333L)
            }
            if (a != null) {
                for (i in 0 until audioFrames) packets += MediaPacket(a, ByteArray(12) { (i + it).toByte() }, i * 21_333L, i * 21_333L, true, 21_333L)
            }
            packets.sortedBy { it.dtsUs }.forEach { muxer.writePacket(it) }
        }
        return file
    }

    /** `ftyp` + `moov` de un fMP4 con estas pistas y sin muestras: la cabecera a la que añadir fragmentos. */
    fun fragmentedHeader(dir: File, vararg tracks: TrackInfo): ByteArray {
        val file = File(dir, "cabecera-${System.nanoTime()}.mp4")
        Kotmpeg.createMuxer(file, ContainerFormat.MP4) { fragmented = true }.use { muxer ->
            for (t in tracks) muxer.addTrack(t)
            muxer.start()
        }
        val bytes = file.readBytes()
        val moov = topLevel(bytes, "moov")
        return bytes.copyOf(moov + u32(bytes, moov).toInt())
    }

    /** Abre [file] y devuelve el demuxer con los avisos que vaya dando en [warnings]. */
    fun open(file: File, warnings: MutableList<String> = ArrayList()): Mp4Demuxer =
        Mp4Demuxer(SeekableInput(file)) { warnings += it }

    /** Todos los paquetes de [file], y los avisos en [warnings]. */
    fun readAll(file: File, warnings: MutableList<String> = ArrayList()): List<MediaPacket> =
        open(file, warnings).use { d -> generateSequence { d.readPacket() }.toList() }

    fun u32(b: ByteArray, at: Int): Long = (0 until 4).fold(0L) { v, k -> (v shl 8) or (b[at + k].toLong() and 0xFF) }

    fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    fun putU32(b: ByteArray, at: Int, value: Long) {
        for (k in 0 until 4) b[at + k] = (value ushr (8 * (3 - k))).toByte()
    }

    fun putU16(b: ByteArray, at: Int, value: Int) {
        b[at] = (value ushr 8).toByte()
        b[at + 1] = value.toByte()
    }

    fun putU64(b: ByteArray, at: Int, value: Long) {
        putU32(b, at, value ushr 32)
        putU32(b, at + 4, value and 0xFFFFFFFFL)
    }

    fun type(b: ByteArray, at: Int): String = String(b, at + 4, 4, Charsets.ISO_8859_1)

    /** Cajas que contienen otras, con lo que hay que saltar desde su inicio hasta la primera hija. */
    private val CONTAINERS = mapOf(
        "moov" to 8, "trak" to 8, "mdia" to 8, "minf" to 8, "stbl" to 8, "edts" to 8, "dinf" to 8,
        "udta" to 8, "mvex" to 8, "moof" to 8, "traf" to 8, "stsd" to 16, "avc1" to 86, "mp4a" to 36,
    )

    /** Las cajas hijas de la que empieza en [parent] (o las de primer nivel si es -1). */
    fun children(b: ByteArray, parent: Int = -1): List<Int> {
        val start = if (parent < 0) 0 else parent + (CONTAINERS[type(b, parent)] ?: error("${type(b, parent)} no es contenedor"))
        val end = if (parent < 0) b.size else parent + u32(b, parent).toInt()
        val out = ArrayList<Int>()
        var p = start
        while (p + 8 <= end) {
            out += p
            val size = sizeOf(b, p, end)
            if (size < 8) break
            p += size
        }
        return out
    }

    /**
     * Tamaño de la caja que empieza en [at], también con `largesize` (el `mdat` de `Mp4Muxer`) o
     * hasta [end] si declara cero.
     */
    fun sizeOf(b: ByteArray, at: Int, end: Int = b.size): Int = when (val size = u32(b, at)) {
        1L -> ((u32(b, at + 8) shl 32) or u32(b, at + 12)).toInt()
        0L -> end - at
        else -> size.toInt()
    }

    fun topLevel(b: ByteArray, type: String): Int = children(b).first { type(b, it) == type }

    /** Posición de la caja a la que lleva [path] desde el primer nivel, por la primera de cada tipo. */
    fun find(b: ByteArray, vararg path: String): Int = within(b, -1, *path)

    /** Lo mismo que [find], pero bajando desde la caja que empieza en [from]. */
    fun within(b: ByteArray, from: Int, vararg path: String): Int {
        var at = from
        for (t in path) at = children(b, at).firstOrNull { type(b, it) == t } ?: error("no hay caja $t bajo $at")
        return at
    }

    /** El [i]-ésimo `trak` del `moov`. */
    fun trak(b: ByteArray, i: Int): Int = children(b, find(b, "moov")).filter { type(b, it) == "trak" }[i]

    /** Las cajas que contienen la que empieza en [target], de fuera a dentro. */
    private fun ancestors(b: ByteArray, target: Int): List<Int> {
        val out = ArrayList<Int>()
        var level = children(b)
        while (true) {
            val holder = level.firstOrNull { it < target && target < it + sizeOf(b, it) } ?: return out
            out += holder
            if (type(b, holder) !in CONTAINERS) return out
            level = children(b, holder)
        }
    }

    /**
     * Sustituye [removed] bytes desde [at] por [inserted] y corrige el tamaño de [resize], las cajas
     * que contienen ese tramo.
     */
    private fun splice(b: ByteArray, at: Int, removed: Int, inserted: ByteArray, resize: List<Int>): ByteArray {
        val delta = inserted.size - removed
        val out = ByteArray(b.size + delta)
        b.copyInto(out, 0, 0, at)
        inserted.copyInto(out, at)
        b.copyInto(out, at + inserted.size, at + removed, b.size)
        for (box in resize) putU32(out, box, u32(b, box) + delta)
        return out
    }

    /** Sustituye la caja que empieza en [box] por [replacement], una caja completa. */
    fun replace(b: ByteArray, box: Int, replacement: ByteArray): ByteArray =
        splice(b, box, u32(b, box).toInt(), replacement, ancestors(b, box))

    /** Añade [child], una caja completa, al final de la caja que empieza en [box]. */
    fun appendChild(b: ByteArray, box: Int, child: ByteArray): ByteArray =
        splice(b, box + u32(b, box).toInt(), 0, child, ancestors(b, box) + box)

    /** Inserta [child], una caja completa, justo después de la caja que empieza en [sibling]. */
    fun insertAfter(b: ByteArray, sibling: Int, child: ByteArray): ByteArray =
        splice(b, sibling + u32(b, sibling).toInt(), 0, child, ancestors(b, sibling))

    /** Una caja construida con [BoxBuilder]. */
    fun box(type: String, content: BoxBuilder.() -> Unit): ByteArray = BoxBuilder().apply { box(type, content) }.toByteArray()

    fun fullBox(type: String, version: Int, flags: Int, content: BoxBuilder.() -> Unit): ByteArray =
        BoxBuilder().apply { fullBox(type, version, flags, content) }.toByteArray()

    /** Primera aparición de [pattern] en [b] desde [from], o -1. */
    fun indexOf(b: ByteArray, pattern: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..b.size - pattern.size) {
            for (j in pattern.indices) if (b[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    /** Inicio de la primera caja de tipo [type] en cualquier nivel, buscando su fourcc. */
    fun anyBox(b: ByteArray, type: String, from: Int = 0): Int {
        val at = indexOf(b, type.toByteArray(Charsets.ISO_8859_1), from)
        check(at >= 4) { "no hay caja $type" }
        return at - 4
    }
}
