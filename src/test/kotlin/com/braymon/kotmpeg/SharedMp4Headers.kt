package com.braymon.kotmpeg

import com.braymon.kotmpeg.Mp4ReadFixtures.children
import com.braymon.kotmpeg.Mp4ReadFixtures.find
import com.braymon.kotmpeg.Mp4ReadFixtures.trak
import com.braymon.kotmpeg.Mp4ReadFixtures.type
import com.braymon.kotmpeg.Mp4ReadFixtures.u32
import com.braymon.kotmpeg.Mp4ReadFixtures.within

/**
 * Lectura de los campos de cabecera que comprueban los tests `Shared*`: duraciones, versiones y
 * fechas de `mvhd`, `tkhd`, `mdhd` y `mehd`, las entradas de la lista de edición y los desfases de
 * composición de cada `trun`. Se apoya en [Mp4ReadFixtures] para recorrer las cajas.
 *
 * Cada campo se lee según la versión de su caja, y no en una posición fija, porque lo que comprueban
 * esos tests es justo que la versión cambie cuando un valor no cabe en 32 bits.
 */
internal object SharedMp4Headers {

    /** Una entrada de la lista de edición: su duración en la escala de la película y su `media_time`. */
    data class Edit(val duration: Long, val mediaTime: Long)

    fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    fun version(b: ByteArray, box: Int): Int = b[box + 8].toInt() and 0xFF

    fun mvhd(b: ByteArray): Int = find(b, "moov", "mvhd")

    fun tkhd(b: ByteArray, track: Int): Int = within(b, trak(b, track), "tkhd")

    fun mdhd(b: ByteArray, track: Int): Int = within(b, trak(b, track), "mdia", "mdhd")

    /** La fecha de creación de un `mvhd`, `tkhd` o `mdhd`. */
    fun creationTime(b: ByteArray, box: Int): Long = if (version(b, box) == 1) u64(b, box + 12) else u32(b, box + 12)

    /** La duración de un `mvhd` o un `mdhd`, que tienen la misma forma hasta ella. */
    fun movieOrMediaDuration(b: ByteArray, box: Int): Long =
        if (version(b, box) == 1) u64(b, box + 32) else u32(b, box + 24)

    /** La escala de un `mvhd` o un `mdhd`. */
    fun timescale(b: ByteArray, box: Int): Long = if (version(b, box) == 1) u32(b, box + 28) else u32(b, box + 20)

    fun tkhdDuration(b: ByteArray, track: Int): Long {
        val box = tkhd(b, track)
        return if (version(b, box) == 1) u64(b, box + 36) else u32(b, box + 28)
    }

    fun mehdDuration(b: ByteArray): Long {
        val box = find(b, "moov", "mvex", "mehd")
        return if (version(b, box) == 1) u64(b, box + 12) else u32(b, box + 12)
    }

    /** Las ediciones de la pista [track], o una lista vacía si no lleva `edts`. */
    fun edits(b: ByteArray, track: Int): List<Edit> {
        val edts = children(b, trak(b, track)).firstOrNull { type(b, it) == "edts" } ?: return emptyList()
        val elst = within(b, edts, "elst")
        val wide = version(b, elst) == 1
        val count = u32(b, elst + 12).toInt()
        val entry = if (wide) 20 else 12
        return (0 until count).map { i ->
            val at = elst + 16 + i * entry
            if (wide) Edit(u64(b, at), u64(b, at + 8)) else Edit(u32(b, at), u32(b, at + 4).toInt().toLong())
        }
    }

    /** La entrada de muestra de la pista [track]: la primera hija de su `stsd`. */
    fun sampleEntry(b: ByteArray, track: Int): Int =
        children(b, within(b, trak(b, track), "mdia", "minf", "stbl", "stsd")).first()

    /** La parte entera del `samplerate` 16.16 de una entrada de muestra de audio. */
    fun audioEntryRate(b: ByteArray, track: Int): Long = u32(b, sampleEntry(b, track) + 32) ushr 16

    /**
     * Los desfases de composición de todas las muestras de la pista [trackId] en los `trun` de un
     * fMP4, en orden, leídos con signo como hace el demuxer.
     */
    fun compositionOffsets(b: ByteArray, trackId: Long): List<Long> {
        val out = ArrayList<Long>()
        for (moof in children(b).filter { type(b, it) == "moof" }) {
            for (traf in children(b, moof).filter { type(b, it) == "traf" }) {
                val parts = children(b, traf)
                val tfhd = parts.first { type(b, it) == "tfhd" }
                if (u32(b, tfhd + 12) != trackId) continue
                for (trun in parts.filter { type(b, it) == "trun" }) {
                    val flags = u32(b, trun + 8).toInt() and 0xFFFFFF
                    val count = u32(b, trun + 12).toInt()
                    var at = trun + 16
                    if (flags and 0x1 != 0) at += 4
                    if (flags and 0x4 != 0) at += 4
                    val fields = listOf(0x100, 0x200, 0x400, 0x800).filter { flags and it != 0 }
                    require(0x800 in fields) { "el trun no lleva desfases de composición" }
                    val offsetAt = fields.indexOf(0x800) * 4
                    repeat(count) {
                        out += u32(b, at + offsetAt).toInt().toLong()
                        at += fields.size * 4
                    }
                }
            }
        }
        return out
    }
}
