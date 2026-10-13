package com.braymon.kotmpeg

import com.braymon.kotmpeg.ebml.MatroskaIds
import kotlin.random.Random

/**
 * Mutaciones para el fuzzing de los demuxers: a partir de un archivo válido produce variantes
 * dañadas de forma reproducible, porque todo sale del [Random] que se le pasa.
 *
 * Hay dos familias, y hacen falta las dos:
 *
 * - **Ciegas**: invertir bits, poner bytes y enteros «interesantes», borrar, duplicar, truncar y
 *   cruzar con otra semilla. Son las que encuentran lo que nadie pensó en probar, pero casi
 *   siempre caen en la carga de un fotograma, que el demuxer ni mira.
 * - **Conscientes de la estructura**: recorren el árbol de cajas MP4 o de elementos EBML y atacan
 *   justo los campos que el parser interpreta —tamaños, tipos, versiones, contadores de tablas,
 *   cabeceras de bloque y lacing—, y al duplicar o quitar un nodo corrigen el tamaño de sus
 *   ancestros para que el resto del archivo siga siendo coherente. Sin esto, la mayoría de las
 *   variantes morirían en la primera cabecera y el parser profundo no se tocaría nunca.
 *
 * Cada mutación devuelve el array nuevo y deja en [trail] una descripción legible, que es lo que
 * se guarda junto a un hallazgo para poder entenderlo sin depurar.
 */
internal class FuzzMutator(private val random: Random, private val container: FuzzSeeds.Container) {

    /** Descripción de las mutaciones aplicadas, en orden. */
    val trail: MutableList<String> = ArrayList()

    /**
     * Aplica entre una y cuatro mutaciones apiladas. [donors] son otras semillas del mismo
     * contenedor, para el cruce.
     */
    fun mutate(seed: ByteArray, donors: List<ByteArray>): ByteArray {
        var bytes = seed
        val count = when (random.nextInt(10)) {
            in 0..4 -> 1
            in 5..7 -> 2
            8 -> 3
            else -> 4
        }
        repeat(count) { bytes = mutateOnce(bytes, donors) }
        return bytes
    }

    private fun mutateOnce(bytes: ByteArray, donors: List<ByteArray>): ByteArray {
        if (bytes.isEmpty()) {
            trail += "rellenar un archivo vacío"
            return ByteArray(1 + random.nextInt(16)).also { random.nextBytes(it) }
        }
        if (bytes.size < 16) return blind(bytes, donors)
        return if (random.nextInt(100) < 65) structural(bytes) ?: blind(bytes, donors) else blind(bytes, donors)
    }

    private fun blind(bytes: ByteArray, donors: List<ByteArray>): ByteArray = when (random.nextInt(8)) {
        0 -> flipBits(bytes)
        1 -> setInterestingByte(bytes)
        2 -> setInterestingInt(bytes, random.nextInt(bytes.size))
        3 -> deleteRange(bytes)
        4 -> duplicateRange(bytes)
        5 -> truncate(bytes)
        6 -> crossover(bytes, donors)
        else -> randomRange(bytes)
    }

    private fun structural(bytes: ByteArray): ByteArray? = when (container) {
        FuzzSeeds.Container.MP4 -> mutateBox(bytes)
        FuzzSeeds.Container.MKV -> mutateElement(bytes)
    }

    private fun flipBits(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        val flips = 1 + random.nextInt(4)
        repeat(flips) {
            val at = random.nextInt(out.size)
            out[at] = (out[at].toInt() xor (1 shl random.nextInt(8))).toByte()
        }
        trail += "invertir $flips bits"
        return out
    }

    private fun setInterestingByte(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        val at = random.nextInt(out.size)
        val value = INTERESTING_BYTES[random.nextInt(INTERESTING_BYTES.size)]
        out[at] = value.toByte()
        trail += "byte $at := 0x${value.toString(16)}"
        return out
    }

    private fun interestingInt(bytes: ByteArray, at: Int): Long {
        val candidates = longArrayOf(
            0, 1, 2, 7, 8, 16, 0x7F, 0x80, 0xFF, 0x100, 0xFFFF, 0x10000, 0x7FFF_FFFF, 0x8000_0000,
            0xFFFF_FFFF, 0xFFFF_FFFE, bytes.size.toLong(), (bytes.size - at).toLong(),
            (bytes.size - at + 1).toLong(), random.nextLong(0, 0x1_0000_0000),
        )
        return candidates[random.nextInt(candidates.size)]
    }

    private fun setInterestingInt(bytes: ByteArray, at: Int): ByteArray {
        if (at + 4 > bytes.size) return setInterestingByte(bytes)
        val out = bytes.copyOf()
        val value = interestingInt(bytes, at)
        writeU32(out, at, value)
        trail += "u32 en $at := $value"
        return out
    }

    private fun deleteRange(bytes: ByteArray): ByteArray {
        val at = random.nextInt(bytes.size)
        val length = 1 + random.nextInt(minOf(bytes.size - at, if (random.nextBoolean()) 16 else 512))
        trail += "borrar $length bytes en $at"
        return bytes.copyOfRange(0, at) + bytes.copyOfRange(at + length, bytes.size)
    }

    private fun duplicateRange(bytes: ByteArray): ByteArray {
        val from = random.nextInt(bytes.size)
        val length = 1 + random.nextInt(minOf(bytes.size - from, 256))
        val to = random.nextInt(bytes.size + 1)
        trail += "duplicar $length bytes de $from en $to"
        return bytes.copyOfRange(0, to) + bytes.copyOfRange(from, from + length) + bytes.copyOfRange(to, bytes.size)
    }

    private fun truncate(bytes: ByteArray): ByteArray {
        val at = random.nextInt(bytes.size)
        trail += "truncar en $at"
        return bytes.copyOf(at)
    }

    private fun randomRange(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        val at = random.nextInt(out.size)
        val length = 1 + random.nextInt(minOf(out.size - at, 32))
        for (i in at until at + length) out[i] = random.nextInt(256).toByte()
        trail += "aleatorizar $length bytes en $at"
        return out
    }

    private fun crossover(bytes: ByteArray, donors: List<ByteArray>): ByteArray {
        val donor = donors.filter { it.isNotEmpty() }.randomOrNull(random) ?: return flipBits(bytes)
        val cut = random.nextInt(bytes.size)
        val from = random.nextInt(donor.size)
        trail += "cruzar: hasta $cut propio, desde $from de otra semilla"
        return bytes.copyOf(cut) + donor.copyOfRange(from, donor.size)
    }

    /** Caja MP4 localizada en el array, con sus ancestros para poder corregir tamaños. */
    private class BoxRef(
        val start: Int,
        val headerLength: Int,
        val size: Long,
        val type: String,
        val parents: List<BoxRef>,
    ) {
        val dataStart: Int get() = start + headerLength
        val end: Int get() = (start + size).toInt()
    }

    private fun walkBoxes(bytes: ByteArray, from: Int, to: Int, parents: List<BoxRef>, out: MutableList<BoxRef>) {
        var p = from
        while (p + 8 <= to) {
            var size = readU32(bytes, p)
            val type = String(bytes, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (p + 16 > to) return
                size = readU64(bytes, p + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - p).toLong()
            }
            if (size < header || size > to - p) return
            val ref = BoxRef(p, header, size, type, parents)
            out += ref
            val skip = CHILD_OFFSET[type]
            if (skip != null && ref.dataStart + skip <= ref.end) {
                walkBoxes(bytes, ref.dataStart + skip, ref.end, parents + ref, out)
            }
            p += size.toInt()
        }
    }

    private fun mutateBox(bytes: ByteArray): ByteArray? {
        val boxes = ArrayList<BoxRef>()
        walkBoxes(bytes, 0, bytes.size, emptyList(), boxes)
        if (boxes.isEmpty()) return null
        val box = pickBox(boxes)
        return when (random.nextInt(7)) {
            0 -> boxSize(bytes, box)
            1 -> boxType(bytes, box)
            2 -> boxVersionAndFlags(bytes, box)
            3, 4 -> boxField(bytes, box)
            5 -> if (random.nextBoolean()) duplicateBox(bytes, box) else removeBox(bytes, box)
            else -> swapSiblings(bytes, boxes, box)
        }
    }

    /** Prefiere las cajas que el parser interpreta campo a campo sobre `mdat` y `free`. */
    private fun pickBox(boxes: List<BoxRef>): BoxRef {
        val parsed = boxes.filter { it.type in FIELD_BOXES }
        return if (parsed.isNotEmpty() && random.nextInt(4) != 0) parsed.random(random) else boxes.random(random)
    }

    private fun boxSize(bytes: ByteArray, box: BoxRef): ByteArray {
        val out = bytes.copyOf()
        val choices = longArrayOf(
            0, 1, 7, 8, box.size - 1, box.size + 1, box.size + 8, box.size * 2, 0xFFFF_FFFF,
            (bytes.size - box.start).toLong(), (bytes.size - box.start + 1).toLong(),
        )
        val value = choices[random.nextInt(choices.size)]
        if (box.headerLength == 16) writeU64(out, box.start + 8, value) else writeU32(out, box.start, value)
        trail += "tamaño de '${box.type}' en ${box.start}: ${box.size} -> $value"
        return out
    }

    private fun boxType(bytes: ByteArray, box: BoxRef): ByteArray {
        val out = bytes.copyOf()
        val type = if (random.nextInt(4) == 0) {
            String(CharArray(4) { 'a' + random.nextInt(26) })
        } else {
            KNOWN_BOXES.random(random)
        }
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, box.start + 4)
        trail += "tipo de '${box.type}' en ${box.start} -> '$type'"
        return out
    }

    private fun boxVersionAndFlags(bytes: ByteArray, box: BoxRef): ByteArray? {
        if (box.type !in FULL_BOXES || box.dataStart + 4 > box.end) return null
        val out = bytes.copyOf()
        if (random.nextBoolean()) {
            val version = intArrayOf(0, 1, 2, 0xFF).random(random)
            out[box.dataStart] = version.toByte()
            trail += "versión de '${box.type}' en ${box.start} -> $version"
        } else {
            val flags = if (random.nextBoolean()) 0xFFFFFF else random.nextInt(0x1000000)
            out[box.dataStart + 1] = (flags ushr 16).toByte()
            out[box.dataStart + 2] = (flags ushr 8).toByte()
            out[box.dataStart + 3] = flags.toByte()
            trail += "flags de '${box.type}' en ${box.start} -> 0x${flags.toString(16)}"
        }
        return out
    }

    /**
     * Un entero de la carga. En las tablas, la mitad de las veces el contador de entradas, que es
     * el campo con el que se reserva memoria; si no, uno cualquiera alineado.
     */
    private fun boxField(bytes: ByteArray, box: BoxRef): ByteArray? {
        val payload = box.end - box.dataStart
        if (payload < 4) return null
        val countOffset = COUNT_OFFSET[box.type]
        val at = if (countOffset != null && countOffset + 4 <= payload && random.nextBoolean()) {
            box.dataStart + countOffset
        } else {
            box.dataStart + 4 * random.nextInt(payload / 4)
        }
        trail += "campo de '${box.type}' (+${at - box.dataStart})"
        return setInterestingInt(bytes, at)
    }

    private fun duplicateBox(bytes: ByteArray, box: BoxRef): ByteArray? {
        val length = box.size.toInt()
        val out = bytes.copyOfRange(0, box.end) + bytes.copyOfRange(box.start, box.end) +
            bytes.copyOfRange(box.end, bytes.size)
        if (!resizeBoxParents(out, box.parents, length.toLong())) return null
        trail += "duplicar '${box.type}' en ${box.start}"
        return out
    }

    private fun removeBox(bytes: ByteArray, box: BoxRef): ByteArray? {
        val out = bytes.copyOfRange(0, box.start) + bytes.copyOfRange(box.end, bytes.size)
        if (!resizeBoxParents(out, box.parents, -box.size)) return null
        trail += "quitar '${box.type}' en ${box.start}"
        return out
    }

    /** Ajusta el tamaño de los ancestros, que están todos antes del punto cambiado. */
    private fun resizeBoxParents(bytes: ByteArray, parents: List<BoxRef>, delta: Long): Boolean {
        for (parent in parents) {
            val size = parent.size + delta
            if (parent.headerLength == 16) {
                writeU64(bytes, parent.start + 8, size)
            } else {
                if (size !in 8..0xFFFF_FFFFL) return false
                writeU32(bytes, parent.start, size)
            }
        }
        return true
    }

    private fun swapSiblings(bytes: ByteArray, boxes: List<BoxRef>, box: BoxRef): ByteArray? {
        val siblings = boxes.filter { it !== box && it.parents == box.parents }
        if (siblings.isEmpty()) return null
        val other = siblings.random(random)
        val (a, b) = if (box.start < other.start) box to other else other to box
        trail += "intercambiar '${a.type}' (${a.start}) con '${b.type}' (${b.start})"
        return bytes.copyOfRange(0, a.start) + bytes.copyOfRange(b.start, b.end) +
            bytes.copyOfRange(a.end, b.start) + bytes.copyOfRange(a.start, a.end) + bytes.copyOfRange(b.end, bytes.size)
    }

    /** Elemento EBML localizado en el array; [size] es -1 si es de tamaño desconocido. */
    private class ElementRef(
        val start: Int,
        val id: Long,
        val idLength: Int,
        val sizeLength: Int,
        val size: Long,
        val end: Int,
        val parents: List<ElementRef>,
    ) {
        val dataStart: Int get() = start + idLength + sizeLength
    }

    private fun vintLength(first: Int, max: Int): Int {
        for (length in 1..max) if (first and (0x80 ushr (length - 1)) != 0) return length
        return 0
    }

    private fun walkElements(bytes: ByteArray, from: Int, to: Int, parents: List<ElementRef>, out: MutableList<ElementRef>) {
        var p = from
        while (p < to) {
            val idLength = vintLength(bytes[p].toInt() and 0xFF, 4)
            if (idLength == 0 || p + idLength >= to) return
            var id = 0L
            for (k in 0 until idLength) id = (id shl 8) or (bytes[p + k].toLong() and 0xFF)
            val sizeStart = p + idLength
            val sizeLength = vintLength(bytes[sizeStart].toInt() and 0xFF, 8)
            if (sizeLength == 0 || sizeStart + sizeLength > to) return
            var size = (bytes[sizeStart].toLong() and 0xFF) and (0xFFL ushr sizeLength)
            var allOnes = size == (0xFFL ushr sizeLength)
            for (k in 1 until sizeLength) {
                val b = bytes[sizeStart + k].toLong() and 0xFF
                size = (size shl 8) or b
                if (b != 0xFFL) allOnes = false
            }
            val dataStart = sizeStart + sizeLength
            val unknown = allOnes
            if (!unknown && size > to - dataStart) return
            val end = if (unknown) to else dataStart + size.toInt()
            val ref = ElementRef(p, id, idLength, sizeLength, if (unknown) -1 else size, end, parents)
            out += ref
            if (id in MASTERS) walkElements(bytes, dataStart, end, parents + ref, out)
            p = end
        }
    }

    private fun mutateElement(bytes: ByteArray): ByteArray? {
        val elements = ArrayList<ElementRef>()
        walkElements(bytes, 0, bytes.size, emptyList(), elements)
        if (elements.isEmpty()) return null
        val element = elements.random(random)
        val name = "0x${element.id.toString(16)}"
        return when (random.nextInt(8)) {
            0 -> elementSize(bytes, element, name)
            1 -> elementId(bytes, element, name)
            2, 3 -> elementData(bytes, element, name)
            4 -> if (random.nextBoolean()) duplicateElement(bytes, element, name) else removeElement(bytes, element, name)
            5 -> swapElements(bytes, elements, element)
            else -> blockHeader(bytes, elements)
        }
    }

    private fun elementSize(bytes: ByteArray, element: ElementRef, name: String): ByteArray {
        val out = bytes.copyOf()
        val max = (1L shl (7 * element.sizeLength)) - 1
        val choices = longArrayOf(0, 1, element.size - 1, element.size + 1, element.size * 2, max - 1, max)
        val value = choices[random.nextInt(choices.size)].coerceIn(0, max)
        writeVint(out, element.start + element.idLength, element.sizeLength, value)
        trail += "tamaño de $name en ${element.start}: ${element.size} -> " + if (value == max) "desconocido" else "$value"
        return out
    }

    private fun elementId(bytes: ByteArray, element: ElementRef, name: String): ByteArray? {
        val same = KNOWN_IDS.filter { idLength(it) == element.idLength && it != element.id }
        if (same.isEmpty()) return null
        val id = same.random(random)
        val out = bytes.copyOf()
        for (k in 0 until element.idLength) {
            out[element.start + k] = (id ushr (8 * (element.idLength - 1 - k))).toByte()
        }
        trail += "id de $name en ${element.start} -> 0x${id.toString(16)}"
        return out
    }

    private fun elementData(bytes: ByteArray, element: ElementRef, name: String): ByteArray? {
        if (element.size <= 0 || element.id in MASTERS) return null
        val out = bytes.copyOf()
        val length = minOf(element.size.toInt(), 8)
        val pattern = random.nextInt(4)
        for (k in 0 until length) {
            out[element.dataStart + k] = when (pattern) {
                0 -> 0
                1 -> 0xFF.toByte()
                2 -> if (k == 0) 0x80.toByte() else 0
                else -> random.nextInt(256).toByte()
            }
        }
        trail += "datos de $name en ${element.start} (patrón $pattern)"
        return out
    }

    private fun duplicateElement(bytes: ByteArray, element: ElementRef, name: String): ByteArray? {
        val length = element.end - element.start
        val out = bytes.copyOfRange(0, element.end) + bytes.copyOfRange(element.start, element.end) +
            bytes.copyOfRange(element.end, bytes.size)
        if (!resizeElementParents(out, element.parents, length.toLong())) return null
        trail += "duplicar $name en ${element.start}"
        return out
    }

    private fun removeElement(bytes: ByteArray, element: ElementRef, name: String): ByteArray? {
        val length = element.end - element.start
        val out = bytes.copyOfRange(0, element.start) + bytes.copyOfRange(element.end, bytes.size)
        if (!resizeElementParents(out, element.parents, -length.toLong())) return null
        trail += "quitar $name en ${element.start}"
        return out
    }

    /** Reescribe el tamaño de los ancestros sin cambiar la longitud de su vint. */
    private fun resizeElementParents(bytes: ByteArray, parents: List<ElementRef>, delta: Long): Boolean {
        for (parent in parents) {
            if (parent.size < 0) continue
            val size = parent.size + delta
            val max = (1L shl (7 * parent.sizeLength)) - 1
            if (size !in 0 until max) return false
            writeVint(bytes, parent.start + parent.idLength, parent.sizeLength, size)
        }
        return true
    }

    private fun swapElements(bytes: ByteArray, elements: List<ElementRef>, element: ElementRef): ByteArray? {
        val siblings = elements.filter { it !== element && it.parents == element.parents }
        if (siblings.isEmpty()) return null
        val other = siblings.random(random)
        val (a, b) = if (element.start < other.start) element to other else other to element
        if (a.end > b.start) return null
        trail += "intercambiar 0x${a.id.toString(16)} (${a.start}) con 0x${b.id.toString(16)} (${b.start})"
        return bytes.copyOfRange(0, a.start) + bytes.copyOfRange(b.start, b.end) +
            bytes.copyOfRange(a.end, b.start) + bytes.copyOfRange(a.start, a.end) + bytes.copyOfRange(b.end, bytes.size)
    }

    /**
     * La cabecera de un `SimpleBlock` o `Block`: número de pista, tiempo relativo, flags —con los
     * dos bits de lacing— y, si hay lacing, el número de fotogramas y sus tamaños.
     */
    private fun blockHeader(bytes: ByteArray, elements: List<ElementRef>): ByteArray? {
        val blocks = elements.filter { (it.id == MatroskaIds.SIMPLE_BLOCK || it.id == MatroskaIds.BLOCK) && it.size >= 4 }
        if (blocks.isEmpty()) return null
        val block = blocks.random(random)
        val out = bytes.copyOf()
        val limit = minOf(block.size.toInt(), 12)
        when (random.nextInt(4)) {
            0 -> {
                val flags = out[block.dataStart + 3].toInt() and 0xF9
                val lacing = random.nextInt(4)
                out[block.dataStart + 3] = (flags or (lacing shl 1)).toByte()
                trail += "lacing del bloque en ${block.start} -> $lacing"
            }
            1 -> {
                out[block.dataStart] = intArrayOf(0x80, 0x81, 0x82, 0x83, 0xFF, 0x40, 0x00).random(random).toByte()
                trail += "pista del bloque en ${block.start}"
            }
            2 -> {
                out[block.dataStart + 1] = intArrayOf(0x7F, 0x80, 0xFF, 0).random(random).toByte()
                out[block.dataStart + 2] = intArrayOf(0xFF, 0, 0x01).random(random).toByte()
                trail += "tiempo relativo del bloque en ${block.start}"
            }
            else -> {
                val at = block.dataStart + 4 + random.nextInt(maxOf(1, limit - 4))
                if (at < block.end) out[at] = INTERESTING_BYTES.random(random).toByte()
                trail += "lacing/carga del bloque en ${block.start} (+${at - block.dataStart})"
            }
        }
        return out
    }

    private companion object {
        val INTERESTING_BYTES = intArrayOf(0x00, 0x01, 0x7F, 0x80, 0xFF, 0x40, 0x10, 0xFE)

        /** Desplazamiento de los hijos dentro de la carga de las cajas que los tienen. */
        val CHILD_OFFSET: Map<String, Int> = buildMap {
            for (type in listOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "moof", "traf", "udta", "mfra")) {
                put(type, 0)
            }
            put("stsd", 8)
            put("dref", 8)
            put("meta", 4)
            for (type in listOf("avc1", "avc3", "hvc1", "hev1")) put(type, 78)
            for (type in listOf("mp4a", "Opus")) put(type, 28)
        }

        val FULL_BOXES = setOf(
            "mvhd", "tkhd", "mdhd", "hdlr", "elst", "stsd", "stts", "ctts", "stss", "stsc", "stsz",
            "stco", "co64", "tfhd", "tfdt", "trun", "trex", "mehd", "esds", "dref",
        )

        /** Dónde está el contador de entradas (o el campo que dimensiona la tabla). */
        val COUNT_OFFSET: Map<String, Int> = mapOf(
            "stsd" to 4, "stts" to 4, "ctts" to 4, "stss" to 4, "stsc" to 4, "stco" to 4, "co64" to 4,
            "elst" to 4, "trun" to 4, "stsz" to 8, "tfhd" to 4, "trex" to 4, "dref" to 4,
        )

        val FIELD_BOXES: Set<String> = FULL_BOXES + setOf("avcC", "hvcC", "dOps", "colr", "mdcv", "clli", "name", "moof", "traf", "trak")

        val KNOWN_BOXES = listOf(
            "moov", "trak", "mdia", "minf", "stbl", "stsd", "stts", "ctts", "stss", "stsc", "stsz",
            "stco", "co64", "moof", "traf", "tfhd", "tfdt", "trun", "mdat", "free", "mvex", "trex",
            "edts", "elst", "avc1", "hvc1", "mp4a", "Opus", "esds", "dOps", "avcC", "hvcC",
        )

        val MASTERS: Set<Long> = setOf(
            MatroskaIds.EBML, MatroskaIds.SEGMENT, MatroskaIds.SEEK_HEAD, MatroskaIds.SEEK, MatroskaIds.INFO,
            MatroskaIds.TRACKS, MatroskaIds.TRACK_ENTRY, MatroskaIds.VIDEO, MatroskaIds.AUDIO,
            MatroskaIds.COLOUR, MatroskaIds.MASTERING_METADATA, MatroskaIds.PROJECTION,
            MatroskaIds.CONTENT_ENCODINGS, MatroskaIds.CONTENT_ENCODING, MatroskaIds.CONTENT_COMPRESSION,
            MatroskaIds.CONTENT_ENCRYPTION, MatroskaIds.CLUSTER, MatroskaIds.BLOCK_GROUP, MatroskaIds.CUES,
            MatroskaIds.CUE_POINT, MatroskaIds.CUE_TRACK_POSITIONS, MatroskaIds.TAGS,
        )

        val KNOWN_IDS: List<Long> = (
            MASTERS + setOf(
                MatroskaIds.TRACK_NUMBER, MatroskaIds.TRACK_TYPE, MatroskaIds.CODEC_ID, MatroskaIds.CODEC_PRIVATE,
                MatroskaIds.SIMPLE_BLOCK, MatroskaIds.BLOCK, MatroskaIds.BLOCK_DURATION, MatroskaIds.REFERENCE_BLOCK,
                MatroskaIds.CLUSTER_TIMESTAMP, MatroskaIds.TIMESTAMP_SCALE, MatroskaIds.DURATION,
                MatroskaIds.PIXEL_WIDTH, MatroskaIds.PIXEL_HEIGHT, MatroskaIds.SAMPLING_FREQUENCY,
                MatroskaIds.CHANNELS, MatroskaIds.CUE_TIME, MatroskaIds.CUE_TRACK, MatroskaIds.CUE_CLUSTER_POSITION,
                MatroskaIds.CODEC_DELAY, MatroskaIds.DEFAULT_DURATION, MatroskaIds.CONTENT_COMP_ALGO,
                MatroskaIds.CONTENT_COMP_SETTINGS, MatroskaIds.CONTENT_ENCODING_SCOPE, MatroskaIds.VOID,
                MatroskaIds.SEEK_ID, MatroskaIds.SEEK_POSITION, MatroskaIds.DOCTYPE, MatroskaIds.LANGUAGE,
            )
            ).toList()

        fun idLength(id: Long): Int = when {
            id >= 0x1000000L -> 4
            id >= 0x10000L -> 3
            id >= 0x100L -> 2
            else -> 1
        }

        fun readU32(bytes: ByteArray, at: Int): Long {
            var v = 0L
            for (k in 0 until 4) v = (v shl 8) or (bytes[at + k].toLong() and 0xFF)
            return v
        }

        fun readU64(bytes: ByteArray, at: Int): Long {
            var v = 0L
            for (k in 0 until 8) v = (v shl 8) or (bytes[at + k].toLong() and 0xFF)
            return v
        }

        fun writeU32(bytes: ByteArray, at: Int, value: Long) {
            for (k in 0 until 4) bytes[at + k] = (value ushr (8 * (3 - k))).toByte()
        }

        fun writeU64(bytes: ByteArray, at: Int, value: Long) {
            for (k in 0 until 8) bytes[at + k] = (value ushr (8 * (7 - k))).toByte()
        }

        /** Escribe [value] como vint de exactamente [length] octetos, con su marcador. */
        fun writeVint(bytes: ByteArray, at: Int, length: Int, value: Long) {
            val marked = value or (1L shl (7 * length))
            for (k in 0 until length) bytes[at + k] = (marked ushr (8 * (length - 1 - k))).toByte()
        }
    }
}
