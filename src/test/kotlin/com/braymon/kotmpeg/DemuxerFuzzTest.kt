package com.braymon.kotmpeg

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Fuzzing de [com.braymon.kotmpeg.mkv.MkvDemuxer] y [com.braymon.kotmpeg.mp4.Mp4Demuxer] contra la
 * política de robustez de `SECURITY.md`. El motor es [FuzzMutator] y el árbitro [FuzzHarness].
 *
 * ## Por qué un fuzzer propio y no Jazzer
 *
 * Jazzer guía las mutaciones por cobertura, que es más eficaz a igualdad de tiempo, pero trae un
 * agente nativo y su modo de fuzzing solo corre bien en Linux: en la máquina de desarrollo, que es
 * Windows, se quedaría en reproducir un corpus. Este no tiene dependencias, corre igual en Windows
 * y en el CI y es **determinista**: con la misma semilla base produce las mismas variantes, así que
 * cualquier hallazgo se reproduce con dos números. Lo que pierde por no ver la cobertura lo
 * compensa en parte con las mutaciones conscientes de la estructura.
 *
 * ## Cómo se ejecuta
 *
 * Dentro de `./gradlew test` corre una pasada corta y fija —[DEFAULT_ITERATIONS] variantes con la
 * semilla [DEFAULT_SEED]—, que es una prueba de regresión más: siempre las mismas entradas. El CI
 * lanza además, una vez por semana y a mano, una campaña larga con semilla nueva:
 *
 * ```
 * ./gradlew test --tests '*DemuxerFuzzTest*' -Dkotmpeg.fuzz.seconds=600 -Dkotmpeg.fuzz.seed=1234
 * ```
 *
 * `kotmpeg.fuzz.iterations` fija el número de variantes; `kotmpeg.fuzz.seconds`, si se da, manda
 * sobre él y convierte la campaña en una de duración fija.
 *
 * ## Qué hacer con un hallazgo
 *
 * Cada fallo distinto se guarda en `build/fuzz-findings/`: la entrada exacta y un `.txt` con la
 * semilla, la iteración, las mutaciones aplicadas y la traza. `resumen.txt` cuenta cómo terminaron
 * las variantes —cuántas abrieron y leyeron, cuántas fallaron al abrir y con qué—, que es lo que
 * dice si las mutaciones llegan al parser profundo o mueren en la primera cabecera.
 *
 * Una vez corregido, el fallo se fija con un test **que construya su propia entrada**, en la suite de
 * la zona —como los de `MatroskaContentEncodingTest` para los dos primeros hallazgos—, y no
 * versionando el archivo: el repositorio no guarda archivos de vídeo, y un test con nombre explica
 * además qué fallaba.
 */
class DemuxerFuzzTest {

    @TempDir
    lateinit var dir: File

    private companion object {
        const val DEFAULT_ITERATIONS = 20_000
        const val DEFAULT_SEED = 20_261_007L

        /** Puntos de corte por semilla en el barrido de truncados. */
        const val CUTS_PER_SEED = 160
    }

    /**
     * Si una semilla no se lee limpia, el fuzzing parte de un archivo que ya falla y lo que
     * encuentre no significa nada. También fija que el demuxer entiende los archivos de terceros.
     */
    @Test
    fun `every seed is read cleanly and completely`() {
        val harness = FuzzHarness()
        try {
            val seeds = FuzzSeeds.all(File(dir, "semillas"))
            val expected = if (FuzzSeeds.ffmpegAvailable) 12 else 6
            assertTrue(seeds.size >= expected, "faltan semillas: ${seeds.map { it.name }}")
            for (seed in seeds) {
                val file = File(dir, "${seed.name}.${seed.container.extension}").apply { writeBytes(seed.bytes) }
                val warnings = ArrayList<String>()
                val packets = MkvKotlin.openDemuxer(file) { warnings += it }.use { demuxer ->
                    generateSequence { demuxer.readPacket() }.count()
                }
                assertTrue(packets > 0, "${seed.name}: no entregó ningún paquete")
                assertEquals(emptyList(), warnings, "${seed.name}: avisos en una semilla válida")
                val violation = harness.check(seed.bytes, seed.container, strict = true)
                assertEquals(null, violation?.let { "${it.kind}: ${it.detail}" }, "${seed.name} incumple la política")
            }
        } finally {
            harness.shutdown()
        }
    }

    /**
     * Un archivo cortado —la grabación que se interrumpe— es el daño más común y el único con una
     * promesa más fuerte que «error claro»: si abre, se lee hasta donde llegue **sin lanzar**. Se
     * corta cada semilla en [CUTS_PER_SEED] puntos repartidos más los primeros 64 bytes uno a uno.
     */
    @Test
    fun `a seed truncated anywhere opens with a clear error or reads to a clean end`() {
        val harness = FuzzHarness()
        val failures = LinkedHashMap<String, String>()
        try {
            for (seed in FuzzSeeds.all(File(dir, "semillas"))) {
                val size = seed.bytes.size
                val cuts = ((0 until minOf(64, size)) + (0 until CUTS_PER_SEED).map { (it.toLong() * size / CUTS_PER_SEED).toInt() })
                    .toSortedSet()
                for (cut in cuts) {
                    val violation = harness.check(seed.bytes.copyOf(cut), seed.container, strict = true) ?: continue
                    failures.putIfAbsent(violation.signature, "${seed.name} cortado en $cut de $size: ${violation.kind}: ${violation.detail}")
                }
            }
        } finally {
            harness.shutdown()
        }
        if (failures.isNotEmpty()) fail("Truncados que incumplen la política:\n" + failures.values.joinToString("\n"))
    }

    @Test
    fun `mutated files fail with a clear error or a clean end, never a hang or a runaway allocation`() {
        val iterations = System.getProperty("kotmpeg.fuzz.iterations")?.toIntOrNull() ?: DEFAULT_ITERATIONS
        val seconds = System.getProperty("kotmpeg.fuzz.seconds")?.toLongOrNull() ?: 0L
        val baseSeed = System.getProperty("kotmpeg.fuzz.seed")?.toLongOrNull() ?: DEFAULT_SEED
        val findingsDir = File(System.getProperty("kotmpeg.fuzz.findings") ?: "build/fuzz-findings")

        val seeds = FuzzSeeds.all(File(dir, "semillas"))
        val harness = FuzzHarness()
        val findings = LinkedHashMap<String, String>()
        val deadline = if (seconds > 0) System.nanoTime() + seconds * 1_000_000_000 else Long.MAX_VALUE
        val started = System.nanoTime()
        var executed = 0
        var heaviest = ""
        var heaviestBytes = -1L
        try {
            var iteration = 0
            while (if (seconds > 0) System.nanoTime() < deadline else iteration < iterations) {
                val random = Random(baseSeed xor (iteration.toLong() * -0x61c8864680b583ebL))
                val seed = seeds[random.nextInt(seeds.size)]
                val mutator = FuzzMutator(random, seed.container)
                val donors = seeds.filter { it.container == seed.container && it !== seed }.map { it.bytes }
                val input = mutator.mutate(seed.bytes, donors)
                val violation = harness.check(input, seed.container)
                executed++
                if (harness.lastAllocatedBytes > heaviestBytes) {
                    heaviestBytes = harness.lastAllocatedBytes
                    heaviest = "${seed.name}, iteración $iteration: ${mutator.trail.joinToString(" | ")}"
                }
                if (violation != null && violation.signature !in findings) {
                    findings[violation.signature] = record(findingsDir, findings.size, baseSeed, iteration, seed, mutator.trail, input, violation)
                }
                iteration++
            }
        } finally {
            harness.shutdown()
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
        val summary = "Fuzzing: $executed variantes en ${"%.1f".format(elapsed)} s, semilla base $baseSeed, " +
            "${seeds.size} semillas, máxima reserva ${heaviestBytes / 1024} KiB ($heaviest), ${findings.size} fallos distintos\n" +
            harness.outcomes.entries.joinToString("\n") { (outcome, count) -> "  $count × $outcome" }
        println(summary)
        findingsDir.mkdirs()
        File(findingsDir, "resumen.txt").writeText(summary + "\n" + findings.values.joinToString("\n"))
        if (findings.isNotEmpty()) {
            fail("$summary\n" + findings.values.joinToString("\n") + "\nEntradas guardadas en ${findingsDir.absolutePath}")
        }
    }

    private fun record(
        findingsDir: File,
        index: Int,
        baseSeed: Long,
        iteration: Int,
        seed: FuzzSeeds.Seed,
        trail: List<String>,
        input: ByteArray,
        violation: FuzzHarness.Violation,
    ): String {
        findingsDir.mkdirs()
        val name = "%02d-%s".format(index, violation.kind.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-'))
        File(findingsDir, "$name.${seed.container.extension}").writeBytes(input)
        val trace = violation.error?.stackTraceToString().orEmpty()
        File(findingsDir, "$name.txt").writeText(
            "${violation.kind}: ${violation.detail}\nsemilla base $baseSeed, iteración $iteration, semilla ${seed.name}\n" +
                "mutaciones: ${trail.joinToString(" | ")}\n\n$trace",
        )
        return "${violation.signature} — ${seed.name}, iteración $iteration: ${violation.detail} [${trail.joinToString(" | ")}]"
    }
}
