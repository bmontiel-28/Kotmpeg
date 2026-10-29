package com.braymon.kotmpeg

import com.braymon.kotmpeg.io.SeekableInput
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Lo que espera [runProcess] a que termine un proceso antes de matarlo.
 *
 * Este archivo reúne lo que comparten varias suites —lanzar FFmpeg, leer un archivo desde memoria,
 * inutilizar el índice de un MKV, medir lo que reserva un hilo—, que antes llevaba cada una en su
 * copia, y las copias ya se habían separado.
 */
internal const val PROCESS_TIMEOUT_SECONDS = 120L

/**
 * Ejecuta [command] y devuelve su salida —la de error mezclada con la normal— y su código de salida.
 *
 * La salida se lee en otro hilo mientras se espera al proceso **con plazo**: leerla hasta el final
 * antes de esperar, como hacían las copias que había en cada suite, dejaba el plazo de `waitFor` sin
 * efecto, porque un proceso colgado no cierra su salida y la lectura no volvía nunca. Si no termina
 * en [timeoutSeconds] se mata y se lanza `IllegalStateException` con lo que hubiera escrito.
 */
internal fun runProcess(vararg command: String, timeoutSeconds: Long = PROCESS_TIMEOUT_SECONDS): Pair<String, Int> {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = ByteArrayOutputStream()
    val reader = Thread({ process.inputStream.use { it.copyTo(output) } }, "salida de ${command.first()}")
    reader.isDaemon = true
    reader.start()
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS)
        reader.join(1_000)
        throw IllegalStateException(
            "${command.joinToString(" ")} no terminó en $timeoutSeconds s y se mató. Salida hasta entonces:\n" +
                String(output.toByteArray(), Charsets.UTF_8),
        )
    }
    reader.join(10_000)
    return String(output.toByteArray(), Charsets.UTF_8) to process.exitValue()
}

/** Si hay un `ffmpeg` que se pueda ejecutar. Se pregunta una vez por JVM. */
internal val ffmpegInstalled: Boolean by lazy {
    runCatching { runProcess("ffmpeg", "-version").second == 0 }.getOrDefault(false)
}

/** [bytes] como entrada de un demuxer, sin pasar por el disco (ver [MemoryFileChannel]). */
internal fun memoryInput(bytes: ByteArray): SeekableInput = SeekableInput(MemoryFileChannel(bytes))

/** El id EBML de `Cues`, el índice de un MKV. */
internal val MKV_CUES_ID = byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)

/** Posiciones en [bytes] donde aparece [MKV_CUES_ID], de la primera a la última. */
internal fun cuesIdPositions(bytes: ByteArray): List<Int> =
    (0..bytes.size - MKV_CUES_ID.size).filter { at -> MKV_CUES_ID.indices.all { bytes[at + it] == MKV_CUES_ID[it] } }

/**
 * Una copia de [bytes] con el índice `Cues` inutilizado, para obligar a buscar recorriendo clusters:
 * el id cambia, en el elemento y en la entrada del `SeekHead`, por otro de 4 bytes que nadie conoce,
 * así que los dos se saltan.
 */
internal fun withoutCuesIndex(bytes: ByteArray): ByteArray {
    val out = bytes.copyOf()
    for (at in cuesIdPositions(out)) out[at + 3] = 0x6A
    return out
}

/**
 * Lo que reserva el hilo actual mientras corre [block], en bytes. Omite el test si el JDK no lo mide.
 *
 * Mide reservas, no el heap, así que no depende del recolector. Conviene medir una segunda pasada:
 * la primera carga clases, y eso también cuenta como memoria reservada por el hilo.
 *
 * Es `inline` para que medir no cambie lo medido: [block] no se convierte en un objeto ni encierra
 * en otro las variables que modifica, y puede salir de la función que lo llama con `return`, como
 * hace `FuzzHarness` al dar con una violación antes de terminar.
 */
internal inline fun allocatedBytes(block: () -> Unit): Long {
    val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    assumeTrue(threads != null, "este JDK no mide la memoria por hilo")
    val before = threads!!.currentThreadAllocatedBytes
    block()
    return threads.currentThreadAllocatedBytes - before
}

/**
 * Corre [block] en un hilo propio y lo da por fallido si no termina en [timeoutMs]. Es lo que permite
 * abandonar una variante de fuzzing atascada: un parser en un bucle no mira la interrupción, y la
 * variante colgaba el test entero sin decir cuál era. El hilo es daemon para que, si sigue girando,
 * no impida terminar al JVM.
 *
 * Ante cualquier fallo —una aserción, otra excepción o el plazo vencido— llama a [onFailure], que
 * es donde se guarda la entrada para reproducirla, y lo propaga.
 */
internal fun <T> withinDeadline(label: String, timeoutMs: Long = FuzzHarness.TIMEOUT_MS, onFailure: () -> Unit = {}, block: () -> T): T {
    val task = FutureTask(Callable { block() })
    Thread(task, "kotmpeg-deadline").apply { isDaemon = true }.start()
    val failure = try {
        return task.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        task.cancel(true)
        AssertionError("$label: no terminó en $timeoutMs ms")
    } catch (e: ExecutionException) {
        e.cause ?: e
    }
    onFailure()
    throw failure
}
