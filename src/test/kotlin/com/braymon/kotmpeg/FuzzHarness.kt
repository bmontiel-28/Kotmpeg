package com.braymon.kotmpeg

import com.braymon.kotmpeg.ebml.EbmlException
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.mkv.MkvDemuxer
import com.braymon.kotmpeg.mp4.Mp4Demuxer
import java.io.IOException
import java.lang.management.ManagementFactory
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Ejecuta un archivo contra su demuxer y comprueba la política de robustez declarada en
 * `SECURITY.md`: un archivo dañado produce **un error claro o un fin de stream**, nunca un cuelgue
 * ni un consumo de memoria sin control.
 *
 * Cada ejecución abre el archivo, consulta pistas y duración, lo lee entero, y busca en seis puntos
 * —incluidos más allá del final y el máximo— leyendo un poco tras cada uno. Lo que se considera
 * una violación:
 *
 * - **Un cuelgue**: no terminar en [TIMEOUT_MS]. Se ejecuta en un hilo aparte justo para poder
 *   abandonarlo.
 * - **Un `Error`** (`OutOfMemoryError`, `StackOverflowError`…) o **una excepción fuera de las
 *   tipadas**: las de E/S, [EbmlException], `IllegalArgumentException` e `IllegalStateException`,
 *   que son las que la documentación promete para un archivo corrupto. Un
 *   `IndexOutOfBoundsException` o un `ArithmeticException` es un caso que el parser no previó.
 * - **Memoria desproporcionada**: reservar más de [MAX_ALLOCATED_BYTES] para un archivo de unos KB.
 *   Se mide con lo que el hilo reserva en total, no con el heap, así que no depende del recolector.
 * - **Paquetes desproporcionados**: más de los que caben físicamente en el archivo.
 * - **Un paquete de una pista que no se anunció** en `tracks`.
 * - **El archivo abierto** después de que el constructor fallara.
 * - Con [strict], **cualquier excepción tras abrir**: es el modo de los archivos solo truncados, que
 *   según el README se leen hasta el último paquete completo y terminan limpios.
 */
internal class FuzzHarness {

    class Violation(val kind: String, val detail: String, val error: Throwable? = null) {
        /** Clave para no informar mil veces del mismo fallo: tipo y primer marco de la librería. */
        val signature: String
            get() = kind + " @ " + (
                error?.stackTrace?.firstOrNull { it.className.startsWith("com.braymon.kotmpeg") && !it.className.contains("Fuzz") }
                    ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "-"
                )
    }

    private val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    private var executor: ExecutorService = newExecutor()

    /** Reserva de la última ejecución que terminó. */
    @Volatile
    var lastAllocatedBytes: Long = 0
        private set

    /**
     * Cómo terminó cada ejecución: si abrió y leyó, o con qué excepción y en qué fase. Sirve para
     * juzgar la campaña —si casi todo muere al abrir, las mutaciones no llegan al parser profundo—.
     */
    val outcomes: MutableMap<String, Int> = java.util.TreeMap()

    private fun newExecutor(): ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "kotmpeg-fuzz").apply { isDaemon = true }
    }

    fun check(bytes: ByteArray, container: FuzzSeeds.Container, strict: Boolean = false): Violation? {
        val future = executor.submit(Callable { exercise(bytes, container, strict) })
        return try {
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            executor = newExecutor()
            Violation("cuelgue", "no terminó en $TIMEOUT_MS ms")
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun exercise(bytes: ByteArray, container: FuzzSeeds.Container, strict: Boolean): Violation? {
        val fileSize = bytes.size
        val before = threads?.currentThreadAllocatedBytes ?: 0
        val channel = MemoryFileChannel(bytes)
        var demuxer: Demuxer? = null
        var stage = "abrir"
        var packets = 0L
        val packetCap = 64L * fileSize + 10_000
        val failure: Throwable? = try {
            val input = SeekableInput(channel)
            demuxer = when (container) {
                FuzzSeeds.Container.MKV -> MkvDemuxer(input) {}
                FuzzSeeds.Container.MP4 -> Mp4Demuxer(input) {}
            }
            stage = "pistas"
            val ids = demuxer.tracks.map { it.id }.toSet()
            val duration = demuxer.durationUs
            stage = "leer"
            while (true) {
                val packet = demuxer.readPacket() ?: break
                if (packet.trackId !in ids) return Violation("pista no anunciada", "paquete de la pista ${packet.trackId}; anunciadas: $ids")
                if (++packets > packetCap) return Violation("paquetes desproporcionados", "más de $packetCap en $fileSize bytes")
            }
            for (target in listOf(0L, duration / 2, duration, duration + 1_000_000, Long.MAX_VALUE, 1L)) {
                stage = "seekTo($target)"
                demuxer.seekTo(target)
                stage = "leer tras seekTo($target)"
                for (k in 0 until 16) demuxer.readPacket() ?: break
            }
            null
        } catch (t: Throwable) {
            t
        } finally {
            demuxer?.let { opened -> runCatching { opened.close() } }
        }
        val leaked = demuxer == null && channel.isOpen
        runCatching { channel.close() }
        val allocated = (threads?.currentThreadAllocatedBytes ?: 0) - before
        lastAllocatedBytes = allocated
        val phase = when {
            demuxer == null -> "falla al abrir"
            failure != null -> "falla al leer"
            else -> "abre y lee"
        }
        val outcome = if (failure == null) phase else "$phase: ${failure.javaClass.simpleName}"
        synchronized(outcomes) { outcomes.merge(outcome, 1, Int::plus) }
        if (leaked) return Violation("archivo abierto", "el constructor falló sin cerrar la entrada", failure)
        if (failure != null) {
            val typed = failure is IOException || failure is EbmlException ||
                failure is IllegalArgumentException || failure is IllegalStateException
            when {
                failure is Error -> return Violation("Error ${failure.javaClass.simpleName}", "en «$stage»", failure)
                !typed -> return Violation("excepción no tipada ${failure.javaClass.simpleName}", "en «$stage»: ${failure.message}", failure)
                strict && demuxer != null -> return Violation("truncado que lanza", "en «$stage»: ${failure.message}", failure)
            }
        }
        if (allocated > MAX_ALLOCATED_BYTES) {
            return Violation("memoria desproporcionada", "${allocated / 1024 / 1024} MiB reservados para $fileSize bytes")
        }
        return null
    }

    companion object {
        const val TIMEOUT_MS = 5_000L

        /**
         * Margen para un archivo de unos KB. Incluye el techo de 64 MiB de descompresión zlib por
         * fotograma de Matroska —que una semilla con zlib podría rozar legítimamente— y el buffer
         * de lectura y las páginas del índice de muestras.
         */
        const val MAX_ALLOCATED_BYTES = 96L * 1024 * 1024
    }
}
