package com.braymon.kotmpeg

import com.braymon.kotmpeg.MkvReadBuilder.audioTrack
import com.braymon.kotmpeg.MkvReadBuilder.cluster
import com.braymon.kotmpeg.MkvReadBuilder.clusterOf
import com.braymon.kotmpeg.MkvReadBuilder.compression
import com.braymon.kotmpeg.MkvReadBuilder.cuePoint
import com.braymon.kotmpeg.MkvReadBuilder.element
import com.braymon.kotmpeg.MkvReadBuilder.elementOf
import com.braymon.kotmpeg.MkvReadBuilder.file
import com.braymon.kotmpeg.MkvReadBuilder.fileOf
import com.braymon.kotmpeg.MkvReadBuilder.info
import com.braymon.kotmpeg.MkvReadBuilder.lacedBlock
import com.braymon.kotmpeg.MkvReadBuilder.simpleBlock
import com.braymon.kotmpeg.MkvReadBuilder.tracks
import com.braymon.kotmpeg.MkvReadBuilder.uint
import com.braymon.kotmpeg.MkvReadBuilder.videoTrack
import com.braymon.kotmpeg.MkvReadBuilder.zlib
import com.braymon.kotmpeg.MkvReadBuilder.zlibOfZeros
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.mkv.MkvDemuxer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Lo que un MKV manipulado podía hacer reservar o calcular a `MkvDemuxer`, encontrado en una
 * revisión del demuxer. `SECURITY.md` promete que un archivo no confiable no reserva memoria
 * desproporcionada ni cuelga la lectura; cada caso es un archivo de pocos KB o MB que lo
 * incumplía:
 *
 * - **La eliminación de cabecera no tenía límite**: unos `ContentCompSettings` de 1 MiB se
 *   copiaban delante de cada uno de los 256 fotogramas vacíos de un bloque de 7 bytes, 256 MiB.
 * - **Ni la cadena de `ContentEncoding`**: 100 000 eliminaciones de cabecera de 14 bytes
 *   concatenaban en cadena, unos 5 GB copiados por fotograma.
 * - **El techo de zlib era por fotograma y se comprobaba después de escribir**: 256 fotogramas que
 *   inflan 1 MiB cada uno eran 256 MiB de un solo bloque, y el que pasaba de 64 MiB duplicaba su
 *   buffer a 128 MiB antes de descartarse.
 * - **Sin topes de cardinalidad**: 50 000 pistas declaradas hacían recorrerlas todas por cada
 *   paquete leído tras una búsqueda, cada pista sin declarar añadía un aviso y una entrada en
 *   memoria, y el índice crecía sin límite.
 *
 * La memoria se mide con lo que el hilo reserva y el cálculo con su tiempo de CPU, como en
 * `FuzzHarness`: no dependen del recolector ni de lo cargada que esté la máquina.
 */
class MkvReadHardeningTest {

    private val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

    private fun open(bytes: ByteArray, warnings: MutableList<String> = ArrayList()): MkvDemuxer =
        MkvDemuxer(SeekableInput(MemoryFileChannel(bytes)), onWarning = { warnings += it })

    /** Lee el archivo entero sin guardar los paquetes: devuelve cuántos y cuántos bytes suman. */
    private fun drain(bytes: ByteArray, warnings: MutableList<String> = ArrayList()): Pair<Int, Long> =
        open(bytes, warnings).use { demuxer ->
            var count = 0
            var total = 0L
            while (true) {
                val packet = demuxer.readPacket() ?: break
                count++
                total += packet.data.size
            }
            count to total
        }

    /** Lo que reserva el hilo al ejecutar [block]. */
    private fun allocatedBy(block: () -> Unit): Long {
        val mx = threads
        assumeTrue(mx != null && mx.isThreadAllocatedMemorySupported, "este JDK no mide la memoria por hilo")
        val before = mx!!.currentThreadAllocatedBytes
        block()
        return mx.currentThreadAllocatedBytes - before
    }

    /**
     * Un archivo con las mismas piezas que los casos medidos, pero inofensivo: carga las clases de
     * la lectura y la descompresión antes de medir, que también cuentan como memoria reservada.
     */
    private fun warmUp() {
        drain(file(info(), tracks(audioTrack(1, compression(3, byteArrayOf(1)))), cluster(0, lacedBlock(1, 0, 2, List(4) { ByteArray(2) }))))
        drain(file(info(), tracks(audioTrack(1, compression(0))), cluster(0, lacedBlock(1, 0, 1, List(4) { zlib(ByteArray(10)) }))))
    }

    @Test
    fun `a megabyte of header stripping is not copied into 256 empty laced frames`() {
        warmUp()
        val settings = ByteArray(1 shl 20) { 0x47 }
        val bytes = file(
            info(),
            tracks(audioTrack(1, compression(3, settings))),
            cluster(0, lacedBlock(1, 0, lacing = 2, frames = List(256) { ByteArray(0) })),
        )
        val warnings = ArrayList<String>()
        val allocated = allocatedBy { drain(bytes, warnings) }

        assertTrue(allocated < 16L * 1024 * 1024, "un bloque de 7 bytes reservó ${allocated / 1024 / 1024} MiB")
        assertTrue(warnings.any { "pista 1" in it }, "la pista con un prefijo imposible no se avisó: $warnings")
    }

    @Test
    fun `a chain of 100 000 content encodings is not concatenated per frame`() {
        warmUp()
        val one = element(
            MatroskaIds.CONTENT_ENCODING,
            element(
                MatroskaIds.CONTENT_COMPRESSION,
                uint(MatroskaIds.CONTENT_COMP_ALGO, 3),
                element(MatroskaIds.CONTENT_COMP_SETTINGS, byteArrayOf(0x47)),
            ),
        )
        assertEquals(14, one.size, "cada ContentEncoding debería ocupar 14 bytes")
        val bytes = file(
            info(),
            tracks(audioTrack(1, elementOf(MatroskaIds.CONTENT_ENCODINGS, List(100_000) { one }))),
            cluster(0, simpleBlock(1, 0, true)),
        )
        val warnings = ArrayList<String>()
        val allocated = allocatedBy { drain(bytes, warnings) }

        assertTrue(allocated < 32L * 1024 * 1024, "leer un fotograma de un byte reservó ${allocated / 1024 / 1024} MiB")
        assertTrue(warnings.any { "pista 1" in it }, "la cadena imposible no se avisó: $warnings")
    }

    /**
     * El techo de 64 MiB vale también para el bloque entero: los fotogramas que caben se entregan
     * y el resto se descarta con un único aviso, en vez de dejar 256 MiB en la cola de un bloque de
     * 256 KB.
     */
    @Test
    fun `the inflate ceiling applies to the whole laced block`() {
        val frame = zlib(ByteArray(1 shl 20))
        assertTrue(frame.size < 2048, "1 MiB de ceros debería comprimir a unos pocos KB: ${frame.size}")
        val bytes = file(info(), tracks(audioTrack(1, compression(0))), cluster(0, lacedBlock(1, 0, 1, List(256) { frame })))
        val warnings = ArrayList<String>()
        val (count, total) = drain(bytes, warnings)

        assertTrue(total <= 64L * 1024 * 1024, "un bloque entregó ${total / 1024 / 1024} MiB descomprimidos")
        assertEquals(64, count, "deberían llegar los fotogramas que caben en el techo")
        assertEquals(1, warnings.size, "un bloque que pasa del techo debe avisar una vez: $warnings")
    }

    /**
     * Un fotograma que pasa del techo se descarta **antes** de escribir lo que sobra: crecer el
     * buffer a 128 MiB para descubrir después que no cabe dejaba un pico de casi 200 MiB, más del
     * doble del presupuesto de memoria de `FuzzHarness`.
     */
    @Test
    fun `a frame over the ceiling is dropped before its buffer doubles`() {
        warmUp()
        val bomb = zlibOfZeros(64L * 1024 * 1024 + 64 * 1024)
        val bytes = file(info(), tracks(audioTrack(1, compression(0))), cluster(0, simpleBlock(1, 0, true, bomb)))
        val warnings = ArrayList<String>()
        var count = 0
        val allocated = allocatedBy { count = drain(bytes, warnings).first }

        assertEquals(0, count)
        assertEquals(1, warnings.size, "avisos: $warnings")
        assertTrue(allocated < 160L * 1024 * 1024, "descartar el fotograma reservó ${allocated / 1024 / 1024} MiB")
    }

    /**
     * 50 000 pistas declaradas y una que habla sin parar por detrás del keyframe: tras la búsqueda,
     * cada paquete recorría las 50 000 para decidir si ya se sabía por dónde empezaba cada una.
     */
    @Test
    fun `fifty thousand declared tracks do not make a seek walk all of them per packet`() {
        val mx = threads
        assumeTrue(mx != null && mx.isCurrentThreadCpuTimeSupported, "este JDK no mide el tiempo de CPU por hilo")
        val entries = ArrayList<ByteArray>()
        entries += videoTrack(1)
        for (number in 2L..50_000L) entries += audioTrack(number)
        val clusters = (0 until 12).map { second ->
            val blocks = ArrayList<ByteArray>()
            for (ms in 0 until 1000 step 2) {
                blocks += simpleBlock(1, ms, key = ms == 0 && second <= 1)
                blocks += simpleBlock(50_000, 500 - second * 1000, key = true)
            }
            clusterOf(second * 1000L, blocks)
        }
        val bytes = fileOf(listOf(info(), elementOf(MatroskaIds.TRACKS, entries)) + clusters)
        val warnings = ArrayList<String>()
        open(bytes, warnings).use { demuxer ->
            val before = mx!!.currentThreadCpuTime
            assertEquals(1_000_000L, demuxer.seekTo(1_500_000))
            val first = demuxer.readPacket()
            val cpuMs = (mx.currentThreadCpuTime - before) / 1_000_000
            assertEquals(1_000_000L, first?.ptsUs)
            assertTrue(cpuMs < 2_000, "buscar en un archivo con 50 000 pistas tardó $cpuMs ms de CPU")
        }
    }

    @Test
    fun `blocks of thousands of undeclared tracks do not warn once each`() {
        val blocks = listOf(simpleBlock(1, 0, true)) + (2L..10_001L).map { simpleBlock(it, 0, true) }
        val warnings = ArrayList<String>()
        val (count, _) = drain(file(info(), tracks(videoTrack(1)), clusterOf(0, blocks)), warnings)

        assertEquals(1, count)
        assertTrue(warnings.isNotEmpty(), "los bloques de pistas sin declarar deben avisarse")
        assertTrue(warnings.size <= 100, "${warnings.size} avisos por bloques de pistas sin declarar")
    }

    /**
     * Un índice de cientos de miles de puntos ocupa en memoria unas cinco veces lo que en el
     * archivo. Pasado el tope se ignora el resto con aviso, y lo leído sigue sirviendo para buscar.
     */
    @Test
    fun `an index with too many points is capped with a warning`() {
        val head = listOf(info(), tracks(videoTrack(1)))
        val point = cuePoint(0, 1L to 0L)
        val index = elementOf(MatroskaIds.CUES, List(600_000) { point })
        val clusterAt = head.sumOf { it.size }.toLong() + index.size
        val body = cluster(0, simpleBlock(1, 0, true), simpleBlock(1, 40, false))
        val bytes = fileOf(head + elementOf(MatroskaIds.CUES, List(600_000) { cuePoint(0, 1L to clusterAt) }) + body)
        val warnings = ArrayList<String>()
        open(bytes, warnings).use { demuxer ->
            assertEquals(0L, demuxer.seekTo(20_000))
            assertEquals(0L, demuxer.readPacket()?.ptsUs)
        }
        assertTrue(warnings.any { "Cues" in it }, "el índice recortado no se avisó: $warnings")
    }

    /** `skip` se saltaba la comprobación de la posición: con un salto enorme quedaba negativa. */
    @Test
    fun `skipping past the largest position fails instead of wrapping around`() {
        val input = SeekableInput(MemoryFileChannel(ByteArray(16)))
        input.position = 10
        assertFailsWith<IllegalArgumentException> { input.skip(Long.MAX_VALUE) }
        assertEquals(10L, input.position)
    }
}
