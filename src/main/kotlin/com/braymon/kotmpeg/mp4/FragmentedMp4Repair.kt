package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.RepairResult
import com.braymon.kotmpeg.io.RepairTarget
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.Timestamps
import java.io.EOFException

/**
 * Cierra en el sitio un MP4 fragmentado que se quedó sin cerrar, como lo habría cerrado
 * [FragmentedMp4Muxer.stop]: es `Kotmpeg.repair` para fMP4.
 *
 * Un archivo así no tiene el `mfra` del final —es lo último que escribe `stop()`— y puede acabar en
 * un fragmento a medias. La reparación:
 *
 * 1. Conserva los fragmentos enteros y, del último, **las muestras que sí están completas**, igual
 *    que un lector que reempaqueta el archivo: baja el `sample_count` de cada `trun` a las que
 *    quedan, corrige el tamaño de su `mdat` y convierte en `free` los `sidx` que lo describían
 *    entero. Las entradas que sobran se quedan al final de su `trun`, donde los lectores no miran:
 *    ISO/IEC 14496-12 pide ignorar lo que haya en una caja detrás de lo que se conoce, que es como
 *    se extienden las cajas de una versión a otra. Si no queda ninguna muestra, quita el
 *    fragmento.
 * 2. Pone en `mvhd`, `tkhd`, `mehd` y `elst` la duración de lo que queda, con el criterio de
 *    `FragmentDurations`, el mismo del muxer.
 * 3. Añade el `mfra`, con una entrada por fragmento que trae un keyframe de la pista de vídeo, o de
 *    la primera si no hay vídeo, como el muxer.
 *
 * Un archivo se da por **cerrado** si acaba en un `mfra` cuyo `mfro` lo describe, y entonces no se
 * toca. Uno completo de otro programa que no escriba `mfra` se trata como cortado tras su último
 * fragmento: no se le quita nada, solo se le ponen las duraciones y el `mfra`.
 *
 * El `mfra` va lo último: es la señal de que el archivo está cerrado, así que si la reparación se
 * interrumpe antes, se puede volver a lanzar y acaba igual. Lo primero es truncar tras lo que se
 * conserva; lo demás son parches de 4 u 8 bytes sobre campos que ya existen, y ninguno deja una
 * caja a medias. Un final de ceros tras el último fragmento —lo que deja un sistema de archivos que
 * reservó el espacio sin llegar a escribirlo— cuenta como corte, igual que en MKV.
 *
 * **No modifica nada hasta haberlo leído todo.** Un MP4 plano, una cabecera incompleta, una caja
 * desconocida entre los fragmentos, un `trun` que apunta fuera de su `mdat` o un tamaño de `moof` o
 * de `mdat` que no cuadra con lo que describe se rechazan con `IllegalArgumentException` y el
 * archivo queda como estaba.
 */
internal class FragmentedMp4Repair(private val target: RepairTarget) {

    private val input = target.input
    private val length = target.length

    private class Box(val start: Long, val type: String, val headerSize: Int, val size: Long) {
        val dataStart: Long get() = start + headerSize
        val end: Long get() = start + size
    }

    /** Una caja hija dentro de un array ya leído, con posiciones relativas a ese array. */
    private class Child(val type: String, val start: Int, val headerSize: Int, val end: Int) {
        val dataStart: Int get() = start + headerSize
    }

    private class Track(
        val id: Long,
        val timescale: Long,
        val audio: Boolean,
        val video: Boolean,
        val tkhdDuration: Field?,
        val elstDuration: Field?,
        val elstMediaTime: Long,
    ) {
        var defaultDuration = 0L
        var defaultSize = 0L
        var defaultFlags = 0L
        var endTicks = 0L
    }

    /** Un campo de duración de la cabecera: posición absoluta y si es de 8 bytes o de 4. */
    private class Field(val position: Long, val wide: Boolean)

    /** Una muestra de un `trun`: su duración, dónde están sus datos y su desfase de composición (PTS − DTS). */
    private class Sample(val duration: Long, val size: Long, val flags: Long, val compositionOffset: Long, val dataStart: Long) {
        val dataEnd: Long get() = dataStart + size
    }

    /**
     * Un `trun`: dónde empiezan sus entradas por muestra, cuánto ocupa cada una, si llevan duración
     * propia, y hasta dónde llegan los datos de **todas** las entradas de la caja ([describedEnd]),
     * también las que una reparación interrumpida dejó detrás del `sample_count`.
     */
    private class Trun(
        val child: Child,
        val entriesStart: Int,
        val entryBytes: Int,
        val ownDurations: Boolean,
        val samples: List<Sample>,
        val describedEnd: Long,
    )

    private class Traf(val child: Child, val track: Track, val decodeStart: Long, val truns: List<Trun>)

    private var movieTimescale = 1000L
    private var mvhdDuration: Field? = null
    private var declaredDuration = 0L
    private var mehdDuration: Field? = null
    private val tracks = ArrayList<Track>()

    fun run(): RepairResult = try {
        repair()
    } catch (e: EOFException) {
        reject("estructura cortada (${e.message})")
    }

    private fun repair(): RepairResult {
        var pos = 0L
        var moov: Box? = null
        while (moov == null) {
            val box = boxAt(pos)
            if (box == null || box.end > length) reject("la cabecera está incompleta")
            when (box.type) {
                "ftyp", "free", "skip" -> {}
                "moov" -> moov = box
                else -> reject("se esperaba ftyp o moov y hay ${box.type}")
            }
            pos = box.end
        }
        parseMoov(moov)
        if (isClosed()) return RepairResult(ContainerFormat.MP4, false, ticksToUs(declaredDuration, movieTimescale), 0)

        val cueTrack = tracks.firstOrNull { it.video } ?: tracks.firstOrNull() ?: reject("no tiene pistas")
        val entries = ArrayList<FragmentIndex.Entry>()
        val writes = ArrayList<Pair<Long, ByteArray>>()
        var keepEnd = pos
        val pendingSidx = ArrayList<Long>()
        while (pos < length) {
            val box = fragmentBoxAt(pos) ?: break
            if (box.type == "moof") {
                if (box.end > length) {
                    requireCutMoof(box)
                    break
                }
                val mdat = fragmentBoxAt(box.end) ?: break
                if (mdat.type != "mdat") reject("el moof de ${box.start} no va seguido de su mdat")
                val trafs = parseMoof(box, readBox(box, "moof"), mdat)
                requireInside(trafs, mdat)
                if (mdat.end <= length) {
                    addFragment(box, trafs, cueTrack, entries)
                    keepEnd = mdat.end
                    pendingSidx.clear()
                    pos = mdat.end
                    continue
                }
                requireCutMdat(trafs, mdat)
                val kept = trafs.map { traf -> keepComplete(traf) }
                val samples = kept.flatMap { traf -> traf.truns.flatMap { it.samples } }
                if (samples.isEmpty()) break
                val keptEnd = samples.maxOf { it.dataEnd }
                for ((original, trimmed) in trafs.zip(kept)) {
                    for ((trun, left) in original.truns.zip(trimmed.truns)) {
                        if (left.samples.size < trun.samples.size) {
                            writes.add(box.start + trun.child.dataStart + 4 to FragmentDurations.bigEndian(left.samples.size.toLong(), 4))
                        }
                        val last = left.samples.lastOrNull() ?: continue
                        if (last.duration != trun.samples[left.samples.size - 1].duration) {
                            val at = box.start + trun.entriesStart + (left.samples.size - 1) * trun.entryBytes
                            writes.add(at to FragmentDurations.bigEndian(last.duration, 4))
                        }
                    }
                }
                for (sidx in pendingSidx) writes.add(sidx + 4 to "free".toByteArray(Charsets.US_ASCII))
                writes.add(
                    if (mdat.headerSize == 16) mdat.start + 8 to FragmentDurations.bigEndian(keptEnd - mdat.start, 8)
                    else mdat.start to FragmentDurations.bigEndian(keptEnd - mdat.start, 4),
                )
                addFragment(box, kept, cueTrack, entries)
                keepEnd = keptEnd
                break
            }
            if (box.end > length) break
            when (box.type) {
                "sidx" -> pendingSidx.add(box.start)
                "free", "skip" -> if (pendingSidx.isEmpty()) keepEnd = box.end
                else -> reject("caja ${box.type} inesperada entre los fragmentos en ${box.start}")
            }
            pos = box.end
        }

        val declared = FragmentDurations.declared(
            LongArray(tracks.size) { tracks[it].endTicks },
            LongArray(tracks.size) { if (tracks[it].audio) tracks[it].elstMediaTime else 0L },
        ) { i, ticks -> Timestamps.rescaleRounded(ticks, movieTimescale, tracks[i].timescale) }
        mvhdDuration?.let { writes.add(it.position to FragmentDurations.durationBytes(declared.movie, it.wide)) }
        mehdDuration?.let { writes.add(it.position to FragmentDurations.durationBytes(declared.movie, it.wide)) }
        for ((i, track) in tracks.withIndex()) {
            track.tkhdDuration?.let { writes.add(it.position to FragmentDurations.durationBytes(declared.tracks[i], it.wide)) }
            track.elstDuration?.let { writes.add(it.position to FragmentDurations.durationBytes(declared.tracks[i], it.wide)) }
        }
        for ((at, bytes) in writes) {
            if (at < 0 || at + bytes.size > keepEnd) reject("un parche en $at cae fuera de lo que se conserva, que acaba en $keepEnd")
        }
        val mfra = FragmentIndex.mfra(cueTrack.id, entries)

        if (keepEnd < length) target.truncate(keepEnd)
        for ((at, bytes) in writes) target.write(at, bytes)
        target.write(keepEnd, mfra)
        target.force()
        return RepairResult(ContainerFormat.MP4, true, ticksToUs(declared.movie, movieTimescale), length - keepEnd)
    }

    /** Si el archivo acaba en un `mfra` cuyo `mfro` lo describe: lo último que escribe el muxer. */
    private fun isClosed(): Boolean {
        if (length < 24) return false
        input.position = length - 16
        if (input.readUInt(4) != 16L || readType() != "mfro") return false
        input.skip(4)
        val mfraSize = input.readUInt(4)
        if (mfraSize < 24 || mfraSize > length) return false
        val mfra = boxAt(length - mfraSize) ?: return false
        return mfra.type == "mfra" && mfra.size == mfraSize
    }

    private fun parseMoov(moov: Box) {
        val bytes = readBox(moov, "moov")
        var hasMvex = false
        val trexDefaults = HashMap<Long, Triple<Long, Long, Long>>()
        for (child in children(bytes, moov.headerSize, bytes.size)) {
            when (child.type) {
                "mvhd" -> {
                    val wide = u8(bytes, child.dataStart) == 1
                    val timescaleAt = child.dataStart + if (wide) 20 else 12
                    movieTimescale = u32(bytes, timescaleAt).takeIf { it > 0 } ?: reject("mvhd sin escala de tiempo")
                    mvhdDuration = Field(moov.start + timescaleAt + 4, wide)
                    declaredDuration = if (wide) u64(bytes, timescaleAt + 4) else u32(bytes, timescaleAt + 4)
                }
                "trak" -> tracks.add(parseTrak(moov.start, bytes, child))
                "mvex" -> {
                    hasMvex = true
                    for (entry in children(bytes, child.dataStart, child.end)) {
                        when (entry.type) {
                            "mehd" -> mehdDuration = Field(moov.start + entry.dataStart + 4, u8(bytes, entry.dataStart) == 1)
                            "trex" -> trexDefaults[u32(bytes, entry.dataStart + 4)] = Triple(
                                u32(bytes, entry.dataStart + 12), u32(bytes, entry.dataStart + 16), u32(bytes, entry.dataStart + 20),
                            )
                        }
                    }
                }
            }
        }
        if (!hasMvex) reject("es un MP4 plano, no fragmentado: sin el moov final no hay índice que reconstruir")
        for (track in tracks) {
            trexDefaults[track.id]?.let { (duration, size, flags) ->
                track.defaultDuration = duration
                track.defaultSize = size
                track.defaultFlags = flags
            }
        }
    }

    private fun parseTrak(base: Long, bytes: ByteArray, trak: Child): Track {
        var id = -1L
        var tkhd: Field? = null
        var elst: Field? = null
        var mediaTime = 0L
        var timescale = 0L
        var handler = ""
        for (child in children(bytes, trak.dataStart, trak.end)) {
            when (child.type) {
                "tkhd" -> {
                    val wide = u8(bytes, child.dataStart) == 1
                    val idAt = child.dataStart + if (wide) 20 else 12
                    id = u32(bytes, idAt)
                    tkhd = Field(base + idAt + 8, wide)
                }
                "edts" -> for (edit in children(bytes, child.dataStart, child.end)) {
                    if (edit.type != "elst" || u32(bytes, edit.dataStart + 4) != 1L) continue
                    val wide = u8(bytes, edit.dataStart) == 1
                    elst = Field(base + edit.dataStart + 8, wide)
                    mediaTime = (if (wide) u64(bytes, edit.dataStart + 16) else u32(bytes, edit.dataStart + 12).toInt().toLong())
                        .coerceAtLeast(0L)
                }
                "mdia" -> for (media in children(bytes, child.dataStart, child.end)) {
                    when (media.type) {
                        "mdhd" -> timescale = u32(bytes, media.dataStart + if (u8(bytes, media.dataStart) == 1) 20 else 12)
                        "hdlr" -> handler = fourcc(bytes, media.dataStart + 8)
                    }
                }
            }
        }
        if (id < 0 || timescale <= 0) reject("pista sin tkhd o sin escala de tiempo")
        return Track(id, timescale, handler == "soun", handler == "vide", tkhd, elst, mediaTime)
    }

    /**
     * Los `traf` de un `moof`, con cada muestra y dónde están sus datos, que van en el [mdat] que lo
     * sigue.
     *
     * Dónde empiezan los datos sigue ISO/IEC 14496-12 §8.8.7, como el demuxer: el
     * `base_data_offset` del `tfhd` si lo trae; si no, con `default-base-is-moof` —lo que escriben
     * este muxer y FFmpeg—, el principio del `moof`; y si tampoco, el del `moof` para el primer
     * `traf` y el final de los datos del anterior para los siguientes. Dentro de cada `traf`, un
     * `trun` sin `data_offset` sigue donde acabó el anterior. Hasta la 4.1 la base de un `traf` sin
     * `base_data_offset` era siempre el principio del `moof`, y un fMP4 válido de otro programa que
     * encadena los `traf` se rechazaba. El encadenado cuenta todas las entradas de cada `trun`
     * ([Trun.describedEnd]), para que no cambie si una reparación interrumpida ya bajó un
     * `sample_count`.
     *
     * Los recuentos no se creen sin más: con muestras sin tamaño propio el `trun` no ocupa más por
     * declarar más, así que no pueden pasar de lo que cabe en el `mdat` contando cada muestra como un
     * byte, y entre todos los `trun` del `moof` no pueden pasar de `FragmentWalker.MAX_TRUN_SAMPLES`,
     * el mismo tope que pone el demuxer. Antes el tope era por `trun`, y diez de 16 bytes creaban diez
     * millones de muestras en memoria: un `OutOfMemoryError`, que en Android cierra la app.
     *
     * Ese tope se comprueba sumando los `trun` de todo el `moof` **antes** de crear ninguna muestra.
     * Comprobado `trun` a `trun`, uno justo en el tope se construía entero —un millón de muestras,
     * unos 70 MB— antes de que el siguiente lo rebasara y el `moof` se rechazara.
     *
     * El `sample_composition_time_offset` se lee **con signo también en la versión 0**, como el
     * demuxer (ver `FragmentWalker`): la versión 0 lo declara sin signo, pero los escritores que
     * necesitan uno negativo escriben a menudo esa versión. Leído sin signo, el desfase negativo de un
     * B-frame pasaba por uno de más de 13 horas, y la reparación alargaba la última muestra conservada
     * hasta cubrirlo: el archivo reparado declaraba esas 13 horas.
     */
    private fun parseMoof(moof: Box, bytes: ByteArray, mdat: Box): List<Traf> {
        val mdatPayload = mdat.size - mdat.headerSize
        val trafParts = children(bytes, moof.headerSize, bytes.size)
            .filter { it.type == "traf" }
            .map { it to children(bytes, it.dataStart, it.end) }
        val moofSamples = trafParts.sumOf { (_, parts) -> parts.filter { it.type == "trun" }.sumOf { u32(bytes, it.dataStart + 4) } }
        if (moofSamples > FragmentWalker.MAX_TRUN_SAMPLES) reject("el moof de ${moof.start} declara $moofSamples muestras entre sus trun")
        var chained = moof.start
        val trafs = ArrayList<Traf>()
        for ((traf, parts) in trafParts) {
            val tfhd = parts.firstOrNull { it.type == "tfhd" } ?: reject("traf sin tfhd en ${moof.start}")
            val tfhdFlags = (u32(bytes, tfhd.dataStart) and 0xFFFFFF).toInt()
            val trackId = u32(bytes, tfhd.dataStart + 4)
            val track = tracks.firstOrNull { it.id == trackId } ?: reject("traf de la pista $trackId, que no está en el moov")
            var at = tfhd.dataStart + 8
            val base = when {
                tfhdFlags and FragmentFlags.TFHD_BASE_DATA_OFFSET != 0 -> u64(bytes, at).also { at += 8 }
                tfhdFlags and FragmentFlags.TFHD_DEFAULT_BASE_IS_MOOF != 0 -> moof.start
                else -> chained
            }
            if (tfhdFlags and FragmentFlags.TFHD_SAMPLE_DESCRIPTION_INDEX != 0) at += 4
            var defaultDuration = track.defaultDuration
            var defaultSize = track.defaultSize
            var defaultFlags = track.defaultFlags
            if (tfhdFlags and FragmentFlags.TFHD_DEFAULT_SAMPLE_DURATION != 0) { defaultDuration = u32(bytes, at); at += 4 }
            if (tfhdFlags and FragmentFlags.TFHD_DEFAULT_SAMPLE_SIZE != 0) { defaultSize = u32(bytes, at); at += 4 }
            if (tfhdFlags and FragmentFlags.TFHD_DEFAULT_SAMPLE_FLAGS != 0) defaultFlags = u32(bytes, at)

            val decodeStart = parts.firstOrNull { it.type == "tfdt" }?.let {
                if (u8(bytes, it.dataStart) == 1) u64(bytes, it.dataStart + 4) else u32(bytes, it.dataStart + 4)
            } ?: track.endTicks
            var nextData = base
            val truns = ArrayList<Trun>()
            for (trun in parts) {
                if (trun.type != "trun") continue
                val flags = (u32(bytes, trun.dataStart) and 0xFFFFFF).toInt()
                val count = u32(bytes, trun.dataStart + 4)
                var p = trun.dataStart + 8
                if (flags and FragmentFlags.TRUN_DATA_OFFSET != 0) { nextData = base + u32(bytes, p).toInt().toLong(); p += 4 }
                var firstFlags: Long? = null
                if (flags and FragmentFlags.TRUN_FIRST_SAMPLE_FLAGS != 0) { firstFlags = u32(bytes, p); p += 4 }
                val entryBytes = FragmentFlags.trunEntryBytes(flags)
                val ownDurations = flags and FragmentFlags.TRUN_SAMPLE_DURATION != 0
                val ownSizes = flags and FragmentFlags.TRUN_SAMPLE_SIZE != 0
                if (p + count * entryBytes > trun.end) reject("trun de $count muestras en ${moof.start}")
                if (!ownSizes && count * maxOf(defaultSize, 1L) > mdatPayload) {
                    reject("trun de $count muestras de $defaultSize bytes en ${moof.start}, que no caben en su mdat")
                }
                val entriesStart = p
                val samples = ArrayList<Sample>()
                for (s in 0 until count.toInt()) {
                    val duration = if (ownDurations) u32(bytes, p).also { p += 4 } else defaultDuration
                    val size = if (ownSizes) u32(bytes, p).also { p += 4 } else defaultSize
                    val sampleFlags = when {
                        flags and FragmentFlags.TRUN_SAMPLE_FLAGS != 0 -> u32(bytes, p).also { p += 4 }
                        s == 0 && firstFlags != null -> firstFlags
                        else -> defaultFlags
                    }
                    val offset = if (flags and FragmentFlags.TRUN_SAMPLE_COMPOSITION_TIME_OFFSET != 0) {
                        u32(bytes, p).toInt().toLong().also { p += 4 }
                    } else {
                        0L
                    }
                    samples.add(Sample(duration, size, sampleFlags, offset, nextData))
                    nextData += size
                }
                if (entryBytes > 0) {
                    val sizeAt = if (ownDurations) 4 else 0
                    while (p + entryBytes <= trun.end) {
                        nextData += if (ownSizes) u32(bytes, p + sizeAt) else defaultSize
                        p += entryBytes
                    }
                }
                truns.add(Trun(trun, entriesStart, entryBytes, ownDurations, samples, nextData))
            }
            if (truns.isNotEmpty()) chained = nextData
            trafs.add(Traf(traf, track, decodeStart, truns))
        }
        return trafs
    }

    /**
     * Que todas las muestras apunten dentro del `mdat`, hasta lo que declara: también las de tamaño
     * cero, que no tienen datos pero sí posición. Sin eso, unas muestras vacías con un `data_offset`
     * negativo dejaban el final de lo conservado dentro del `moov`, y truncar ahí destruía la
     * cabecera; sin `data_offset`, el corte caía en el principio del `moof` y la segunda reparación
     * rechazaba un archivo que la primera ya había tocado.
     */
    private fun requireInside(trafs: List<Traf>, mdat: Box) {
        for (traf in trafs) for (trun in traf.truns) for (sample in trun.samples) {
            if (sample.dataStart < mdat.dataStart || sample.dataEnd > mdat.end) {
                reject("un trun apunta fuera de su mdat en ${mdat.start}")
            }
        }
    }

    /**
     * Que un `mdat` que pasa del final del archivo sea de verdad uno cortado: que declare
     * exactamente lo que describen sus `trun`, con lo que alguna de sus muestras queda fuera del
     * archivo. Un tamaño dañado al alza en mitad del archivo pasaba por el corte, con todas sus
     * muestras enteras, y se truncaba todo lo de detrás: 95 de 100 fragmentos en la prueba.
     */
    private fun requireCutMdat(trafs: List<Traf>, mdat: Box) {
        val described = trafs.flatMap { it.truns }.maxOfOrNull { it.describedEnd } ?: mdat.dataStart
        if (described != mdat.end) {
            reject("el mdat de ${mdat.start} declara ${mdat.size} bytes y sus trun describen ${described - mdat.start}: es un tamaño dañado, no un corte")
        }
    }

    /**
     * Que un `moof` que pasa del final del archivo sea de verdad uno cortado y no uno con el tamaño
     * dañado al alza: entre sus hijos enteros no puede empezar un `moof` ni un `mdat`, que es lo que
     * hay detrás de uno de verdad. Si no, se truncaba todo lo de detrás.
     */
    private fun requireCutMoof(moof: Box) {
        var p = moof.dataStart
        while (p + 8 <= length) {
            input.position = p
            var size = input.readUInt(4)
            val type = readType()
            if (size == 0L) {
                requireZeroTail(p)
                return
            }
            if (type == "moof" || type == "mdat") {
                reject("el moof de ${moof.start} declara ${moof.size} bytes y dentro empieza un $type en $p: es un tamaño dañado, no un corte")
            }
            var header = 8
            if (size == 1L) {
                if (p + 16 > length) return
                size = input.readInt64()
                header = 16
            }
            if (size < header) reject("caja $type de tamaño $size dentro del moof de ${moof.start}")
            if (size > length - p) return
            p += size
        }
    }

    /**
     * El [traf] con solo las muestras cuyos datos están enteros en el archivo: las primeras de cada
     * `trun`, porque van seguidas.
     *
     * Con B-frames, lo que queda puede acabar en un fotograma que se presenta **después** de otros
     * que se han quedado fuera: el P que va delante de sus dos B en orden de decodificación. Su
     * presentación empieza entonces cuando ya ha acabado el tiempo de decodificación, que es la
     * duración que declara la pista, y un lector que respete esa duración lo cortaría. Así que la
     * última muestra se alarga hasta cubrirlo: la pista acaba donde empieza a presentarse ese
     * fotograma más la duración de la última muestra, que es también lo que da reempaquetar el
     * archivo, porque el muxer reparte esas duraciones a partir de los tiempos de presentación.
     *
     * Solo se alarga si ese fotograma empieza a presentarse cuando la decodificación ya ha acabado.
     * Antes bastaba con que acabara de presentarse después, y como eso se medía con la duración de
     * la última muestra, la misma que se alarga, una reparación interrumpida tras ese parche y antes
     * del del `mdat` la volvía a alargar al relanzarla. Alargada, ya no cumple la condición.
     *
     * Los tiempos de presentación se cuentan sin el `media_time` de la lista de edición: con
     * desfases sin signo todos llevan sumado ese desplazamiento, y contarlo alargaba la pista ese
     * tanto aunque no faltara ningún B-frame. En audio, cuyo `media_time` es el cebado, no se alarga
     * nunca: no tiene muestras reordenadas.
     */
    private fun keepComplete(traf: Traf): Traf {
        var truns = traf.truns.map { trun ->
            Trun(trun.child, trun.entriesStart, trun.entryBytes, trun.ownDurations, trun.samples.takeWhile { it.dataEnd <= length }, trun.describedEnd)
        }
        if (truns.all { it.samples.isEmpty() }) return Traf(traf.child, traf.track, traf.decodeStart, truns)
        var decode = traf.decodeStart
        var latestPresentation = Long.MIN_VALUE
        var lastDuration = 0L
        for (trun in truns) for (sample in trun.samples) {
            latestPresentation = maxOf(latestPresentation, decode + sample.compositionOffset - traf.track.elstMediaTime)
            lastDuration = sample.duration
            decode += sample.duration
        }
        val last = truns.indexOfLast { it.samples.isNotEmpty() }
        if (latestPresentation >= decode && truns[last].ownDurations) {
            val trun = truns[last]
            val sample = trun.samples.last()
            val longer = latestPresentation + lastDuration - decode + sample.duration
            val extended = Sample(longer, sample.size, sample.flags, sample.compositionOffset, sample.dataStart)
            truns = truns.toMutableList().also {
                it[last] = Trun(trun.child, trun.entriesStart, trun.entryBytes, true, trun.samples.dropLast(1) + extended, trun.describedEnd)
            }
        }
        return Traf(traf.child, traf.track, traf.decodeStart, truns)
    }

    /**
     * Suma lo que aportan los [trafs] de un fragmento al final de cada pista y su entrada de `tfra`.
     * Un `traf` que se ha quedado sin muestras no mueve el final de su pista.
     */
    private fun addFragment(moof: Box, trafs: List<Traf>, cueTrack: Track, entries: MutableList<FragmentIndex.Entry>) {
        var entry: FragmentIndex.Entry? = null
        for ((t, traf) in trafs.withIndex()) {
            if (traf.truns.all { it.samples.isEmpty() }) continue
            var decode = traf.decodeStart
            for ((r, trun) in traf.truns.withIndex()) {
                for ((s, sample) in trun.samples.withIndex()) {
                    if (entry == null && traf.track === cueTrack && sample.flags and FragmentFlags.SAMPLE_IS_NON_SYNC == 0L) {
                        entry = FragmentIndex.Entry((decode + sample.compositionOffset).coerceAtLeast(0), moof.start, t + 1L, r + 1L, s + 1L)
                    }
                    decode += sample.duration
                }
            }
            traf.track.endTicks = maxOf(traf.track.endTicks, decode)
        }
        entry?.let { entries.add(it) }
    }

    /** Los hijos directos de la caja que ocupa `[from, to)` de [bytes]. */
    private fun children(bytes: ByteArray, from: Int, to: Int): List<Child> {
        val out = ArrayList<Child>()
        var p = from
        while (p + 8 <= to) {
            val size = u32(bytes, p)
            val type = fourcc(bytes, p + 4)
            val header = if (size == 1L) 16 else 8
            val boxSize = if (size == 1L) u64(bytes, p + 8) else size
            if (boxSize < header || p + boxSize > to) reject("caja $type mal formada")
            out.add(Child(type, p, header, (p + boxSize).toInt()))
            p += boxSize.toInt()
        }
        return out
    }

    private fun readBox(box: Box, what: String): ByteArray {
        if (box.size > MAX_HEADER_BOX) reject("$what de ${box.size} bytes")
        input.position = box.start
        return input.readBytes(box.size.toInt())
    }

    /** La cabecera de la caja en [at], o `null` si el archivo se acaba antes de terminarla. */
    private fun boxAt(at: Long): Box? {
        if (at + 8 > length) return null
        input.position = at
        var size = input.readUInt(4)
        val type = readType()
        var header = 8
        if (size == 1L) {
            if (at + 16 > length) return null
            size = input.readInt64()
            header = 16
        }
        if (size < header) reject("caja $type de tamaño $size en $at")
        return Box(at, type, header, size)
    }

    /**
     * Como [boxAt], para donde va un fragmento o su `mdat`: si ahí empieza un final de ceros, es un
     * corte y también devuelve `null`. Un tamaño cero seguido de otra cosa —una caja que dice llegar
     * hasta el final del archivo— se sigue rechazando.
     */
    private fun fragmentBoxAt(at: Long): Box? {
        if (at + 8 > length) return null
        input.position = at
        if (input.readUInt(4) == 0L) {
            requireZeroTail(at)
            return null
        }
        return boxAt(at)
    }

    /** Si lo que queda desde [at] no es todo ceros, la caja de tamaño cero es un daño y no un corte. */
    private fun requireZeroTail(at: Long) {
        input.position = at
        val chunk = ByteArray(1 shl 16)
        var remaining = length - at
        while (remaining > 0) {
            val n = minOf(remaining, chunk.size.toLong()).toInt()
            input.readFully(chunk, 0, n)
            for (i in 0 until n) if (chunk[i].toInt() != 0) reject("caja de tamaño 0 en $at, y lo que sigue no es un final de ceros")
            remaining -= n
        }
    }

    private fun readType(): String = String(input.readBytes(4), Charsets.US_ASCII)

    private fun ticksToUs(ticks: Long, timescale: Long): Long = Timestamps.rescaleFloor(ticks, 1_000_000, timescale)

    private fun u32(bytes: ByteArray, at: Int): Long {
        if (at < 0 || at + 4 > bytes.size) reject("caja cortada")
        return ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)
    }

    private fun u64(bytes: ByteArray, at: Int): Long = (u32(bytes, at) shl 32) or u32(bytes, at + 4)

    private fun u8(bytes: ByteArray, at: Int): Int {
        if (at < 0 || at >= bytes.size) reject("caja cortada")
        return bytes[at].toInt() and 0xFF
    }

    private fun fourcc(bytes: ByteArray, at: Int): String {
        if (at < 0 || at + 4 > bytes.size) reject("caja cortada")
        return String(bytes, at, 4, Charsets.US_ASCII)
    }

    private companion object {
        /** Tope para leer en memoria un `moov` o un `moof`; uno mayor no es de este muxer. */
        const val MAX_HEADER_BOX = 64L shl 20

        fun reject(reason: String): Nothing = throw IllegalArgumentException("no se puede reparar el MP4: $reason")
    }
}
