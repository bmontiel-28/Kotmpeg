package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.ebml.EbmlWriter
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Campos de MP4 que se desbordan en silencio, y la codificación de los `Void` de EBML.
 *
 * Dos casos de borde de escritura: la caja `elst` en versión 0 con duraciones que no le caben,
 * y el campo `samplerate` de punto fijo 16.16 desbordado por frecuencias altas.
 *
 * Los dos van contra los **bytes reales del archivo**, igual que los de `mdcv` en
 * [HdrBoxAndDescriptorIoTest] y por el mismo motivo: son campos que nuestro propio lector no
 * consulta —la frecuencia real viene del `esds` y `segment_duration` no se usa al demuxear—,
 * así que comprobarlos a través del modelo no probaría nada.
 */
class Mp4FieldWidthTest {

    @TempDir
    lateinit var dir: File

    /** ~57,9 días: en ticks de película (ms) supera holgadamente 0xFFFFFFFF. */
    private val farOutUs = 5_000_000_000_000L

    private fun videoTrack() = TrackInfo.Video(
        codec = VideoCodec.H264, width = 320, height = 240,
        codecPrivate = NalUnits.buildAvcC(
            listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
            listOf(byteArrayOf(0x68, 0x11, 0x22)),
        ),
    )

    private fun audioTrack(rate: Int) = TrackInfo.Audio(
        codec = AudioCodec.AAC, sampleRate = rate, channelCount = 2,
        codecPrivate = AacConfig.build(rate, 2),
    )

    private fun payload(i: Int) = ByteArray(32) { i.toByte() }

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun be64(b: ByteArray, at: Int): Long = (be32(b, at) shl 32) or be32(b, at + 4)

    /** Posiciones de todas las apariciones del fourcc [type]. */
    private fun findAll(bytes: ByteArray, type: String): List<Int> {
        val needle = type.toByteArray(Charsets.US_ASCII)
        val out = ArrayList<Int>()
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            out.add(i)
        }
        return out
    }

    /** Una entrada de `elst` ya decodificada, con independencia de la versión. */
    private data class Edit(val segmentDuration: Long, val mediaTime: Long)

    /** Decodifica la caja `elst` que empieza (por su fourcc) en [at]. */
    private fun readElst(bytes: ByteArray, at: Int): Pair<Int, List<Edit>> {
        val version = bytes[at + 4].toInt() and 0xFF
        val count = be32(bytes, at + 8).toInt()
        val edits = ArrayList<Edit>()
        var p = at + 12
        repeat(count) {
            if (version == 1) {
                edits.add(Edit(be64(bytes, p), be64(bytes, p + 8)))
                p += 20
            } else {
                edits.add(Edit(be32(bytes, p), be32(bytes, p + 4).toInt().toLong()))
                p += 12
            }
        }
        return version to edits
    }

    /**
     * Una grabación de más de 49,7 días con desfase entre pistas necesita `elst` en versión
     * 1, igual que `mvhd`/`tkhd`/`mdhd`.
     *
     * El desfase entre pistas no es rebuscado: es lo normal en cualquier grabación
     * multipista, porque el codificador de audio y el de vídeo no entregan su primer paquete
     * en el mismo microsegundo. Basta con eso para que la caja exista, y en versión 0 su
     * `segment_duration` de 32 bits se truncaba en silencio.
     */
    @Test
    fun `an edit list longer than 32 bits is written as version 1`() {
        val file = File(dir, "long-edts.mp4")
        MkvKotlin.createMuxer(file, ContainerFormat.MP4).use { muxer ->
            val v = muxer.addTrack(videoTrack())
            val a = muxer.addTrack(audioTrack(48_000))
            muxer.start()
            muxer.writePacket(MediaPacket(v, payload(0), 0, 0, true, 33_333L))
            muxer.writePacket(MediaPacket(a, payload(1), 1_000_000L, 1_000_000L, true, 21_333L))
            muxer.writePacket(MediaPacket(v, payload(2), farOutUs, farOutUs, true, 33_333L))
            muxer.writePacket(MediaPacket(a, payload(3), farOutUs, farOutUs, true, 21_333L))
        }

        val bytes = file.readBytes()
        val positions = findAll(bytes, "elst")
        assertTrue(positions.isNotEmpty(), "no se escribió ninguna caja elst")

        val delayed = positions.map { readElst(bytes, it) }.firstOrNull { (_, edits) ->
            edits.any { it.mediaTime == -1L }
        }
        assertTrue(delayed != null, "ninguna elst tiene el edit vacío del desfase de arranque")
        val (version, edits) = delayed!!

        assertEquals(1, version, "elst debería ir en versión 1 con una duración de 57 días")

        val empty = edits.first { it.mediaTime == -1L }
        assertEquals(1_000L, empty.segmentDuration, "el edit vacío dura 1 s = 1000 ticks de ms")

        val main = edits.first { it.mediaTime != -1L }
        val expected = (farOutUs + 21_333L - 1_000_000L) / 1_000L
        assertEquals(
            expected, main.segmentDuration,
            "segment_duration se truncó: no cabe en 32 bits y la caja fue en versión 0",
        )
        assertTrue(
            main.segmentDuration > 0xFFFFFFFFL,
            "el test no está ejercitando el desbordamiento que dice cubrir",
        )
    }

    /**
     * Un desfase de arranque que por sí solo pasa de 32 bits también fuerza la versión 1, y
     * el `media_time = -1` del edit vacío tiene que seguir leyéndose como -1 con 64 bits.
     */
    @Test
    fun `an empty edit longer than 32 bits keeps its media time at minus one`() {
        val file = File(dir, "long-delay.mp4")
        MkvKotlin.createMuxer(file, ContainerFormat.MP4).use { muxer ->
            val v = muxer.addTrack(videoTrack())
            val a = muxer.addTrack(audioTrack(48_000))
            muxer.start()
            muxer.writePacket(MediaPacket(v, payload(0), 0, 0, true, 33_333L))
            muxer.writePacket(MediaPacket(a, payload(1), farOutUs, farOutUs, true, 21_333L))
        }

        val bytes = file.readBytes()
        val delayed = findAll(bytes, "elst").map { readElst(bytes, it) }
            .firstOrNull { (_, edits) -> edits.any { it.mediaTime == -1L } }
        assertTrue(delayed != null, "no se escribió el edit vacío del desfase")
        val (version, edits) = delayed!!

        assertEquals(1, version, "un desfase de 57 días no cabe en la versión 0")
        val empty = edits.first { it.mediaTime == -1L }
        assertEquals(
            farOutUs / 1_000L, empty.segmentDuration,
            "el desfase se truncó a 32 bits",
        )
        assertTrue(empty.segmentDuration > 0xFFFFFFFFL, "el test no ejercita el desbordamiento")
    }

    /** Un archivo normal sigue usando la versión 0: no se sube por costumbre. */
    @Test
    fun `a normal edit list stays on version 0`() {
        val file = File(dir, "normal-edts.mp4")
        MkvKotlin.createMuxer(file, ContainerFormat.MP4).use { muxer ->
            val v = muxer.addTrack(videoTrack())
            val a = muxer.addTrack(audioTrack(48_000))
            muxer.start()
            muxer.writePacket(MediaPacket(v, payload(0), 0, 0, true, 33_333L))
            muxer.writePacket(MediaPacket(a, payload(1), 1_000_000L, 1_000_000L, true, 21_333L))
            muxer.writePacket(MediaPacket(v, payload(2), 2_000_000L, 2_000_000L, true, 33_333L))
        }

        val bytes = file.readBytes()
        val positions = findAll(bytes, "elst")
        assertTrue(positions.isNotEmpty(), "no se escribió ninguna caja elst")
        for (at in positions) {
            assertEquals(
                0, readElst(bytes, at).first,
                "una duración normal no necesita la versión 1, que ocupa el doble",
            )
        }
    }

    /**
     * El campo `samplerate` del SampleEntry es punto fijo 16.16, así que su parte entera
     * solo llega a 65 535 Hz.
     *
     * Con 96 000 Hz —una frecuencia válida de AAC— `96000 shl 16` ocupa 33 bits y escribirlo
     * con `u32` dejaba los de abajo: el campo salía como 0x77000000, cuya parte entera es
     * 30 464 Hz. No un valor recortado, sino otro número. Ahora se satura al máximo
     * representable.
     */
    @Test
    fun `a sample rate above the 16 bit field saturates instead of wrapping`() {
        val file = File(dir, "96k.mp4")
        MkvKotlin.createMuxer(file, ContainerFormat.MP4).use { muxer ->
            val a = muxer.addTrack(audioTrack(96_000))
            muxer.start()
            muxer.writePacket(MediaPacket(a, payload(0), 0, 0, true, 10_666L))
            muxer.writePacket(MediaPacket(a, payload(1), 10_666L, 10_666L, true, 10_666L))
        }

        val bytes = file.readBytes()
        val at = findAll(bytes, "mp4a").firstOrNull()
        assertTrue(at != null, "no se encontró el SampleEntry mp4a")

        val field = be32(bytes, at!! + 4 + 24)
        assertEquals(
            0xFFFF0000L, field,
            "el campo 16.16 debería saturar en 65535 Hz; envolviendo salía 0x77000000 (30464 Hz)",
        )
        assertEquals(0xFFFFL, field shr 16, "la parte entera es la que se satura")
    }

    /**
     * Y la frecuencia real sobrevive igualmente, porque viaja en el `esds` y es de ahí de
     * donde la lee el demuxer. Es lo que hace que saturar el campo del contenedor sea
     * aceptable en vez de una pérdida de datos.
     */
    @Test
    fun `the real sample rate survives the round trip through esds`() {
        val file = File(dir, "96k-roundtrip.mp4")
        MkvKotlin.createMuxer(file, ContainerFormat.MP4).use { muxer ->
            val a = muxer.addTrack(audioTrack(96_000))
            muxer.start()
            muxer.writePacket(MediaPacket(a, payload(0), 0, 0, true, 10_666L))
            muxer.writePacket(MediaPacket(a, payload(1), 10_666L, 10_666L, true, 10_666L))
        }

        MkvKotlin.openDemuxer(file).use { demuxer ->
            val track = demuxer.tracks.single()
            assertTrue(track is TrackInfo.Audio)
            assertEquals(
                96_000, (track as TrackInfo.Audio).sampleRate,
                "la frecuencia real se perdió: debería venir del esds, no del campo 16.16",
            )
        }
    }

    /** Una frecuencia normal no se toca: la saturación solo actúa por encima del límite. */
    @Test
    fun `a normal sample rate is written unchanged`() {
        val file = File(dir, "48k.mp4")
        MkvKotlin.createMuxer(file, ContainerFormat.MP4).use { muxer ->
            val a = muxer.addTrack(audioTrack(48_000))
            muxer.start()
            muxer.writePacket(MediaPacket(a, payload(0), 0, 0, true, 21_333L))
        }
        val bytes = file.readBytes()
        val at = findAll(bytes, "mp4a").first()
        assertEquals(48_000L shl 16, be32(bytes, at + 4 + 24))
    }

    /**
     * `EbmlWriter.encodeVoid` produce exactamente el tamaño pedido a ambos lados de la
     * frontera del campo de tamaño.
     *
     * Con un campo de 1 byte solo caben 126 de carga: el 127 (todo unos) está reservado para
     * "tamaño desconocido", así que a partir de ahí hace falta un campo de 8 bytes. Esa
     * frontera es la que `MkvMuxer.writeSeekHead` reimplementaba a mano dando por hecho que
     * siempre cabía en 1 byte — cierto con las 3 entradas de SeekHead actuales, y roto en
     * silencio en cuanto se añadiera una cuarta.
     */
    @Test
    fun `void elements encode to the exact size on both sides of the vint boundary`() {
        for (size in listOf(2, 3, 64, 127, 128, 129, 200, 1000)) {
            val encoded = EbmlWriter.encodeVoid(size)
            assertEquals(size, encoded.size, "un Void de $size bytes salió de ${encoded.size}")
            assertEquals(0xEC, encoded[0].toInt() and 0xFF, "el id de Void es 0xEC")
        }
    }

    /**
     * El tamaño declarado dentro del Void tiene que cuadrar con lo que ocupa de verdad, o el
     * parser se desincroniza. Se comprueba releyéndolo con nuestro propio lector de VINT.
     */
    @Test
    fun `the size declared inside a void matches the bytes it occupies`() {
        for (size in listOf(2, 126, 127, 128, 500)) {
            val encoded = EbmlWriter.encodeVoid(size)
            val first = encoded[1].toInt() and 0xFF
            var lengthBytes = 1
            while (lengthBytes <= 8 && (first and (0x80 shr (lengthBytes - 1))) == 0) lengthBytes++
            assertTrue(lengthBytes <= 8, "campo de tamaño ilegible en un Void de $size")

            var declared = (first and (0xFF shr lengthBytes)).toLong()
            for (i in 1 until lengthBytes) {
                declared = (declared shl 8) or (encoded[1 + i].toLong() and 0xFF)
            }
            assertEquals(
                (size - 1 - lengthBytes).toLong(), declared,
                "el Void de $size bytes declara un tamaño que no cuadra con su relleno",
            )
        }
    }
}
