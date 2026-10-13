package com.braymon.kotmpeg.benchmarks

import com.braymon.kotmpeg.MkvKotlin
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.mp4.Mp4Demuxer
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Abrir y leer el MP4 de dos horas de [TwoHourFiles]. La memoria por operación de [openPlain] es la
 * cifra que el índice bajo demanda de la 3.1.0 tiene que mantener: con el mapa completo de la 3.0.0
 * eran unos 30 MB por apertura.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class Mp4DemuxerBenchmark {

    private lateinit var plain: File
    private lateinit var fragmented: File

    @Setup(Level.Trial)
    fun prepare() {
        plain = TwoHourFiles.plain
        fragmented = TwoHourFiles.fragmented
    }

    /** Abrir un MP4 plano de dos horas: recorrer sus tablas una vez y dejar los puntos de control. */
    @Benchmark
    fun openPlain(): Int = Mp4Demuxer(plain).use { it.tracks.size }

    /** Lo mismo con fMP4, donde la apertura atraviesa todos los fragmentos. */
    @Benchmark
    fun openFragmented(): Int = Mp4Demuxer(fragmented).use { it.tracks.size }

    /** Leer el archivo entero, paquete a paquete: el coste de leer las tablas bajo demanda. */
    @Benchmark
    fun readAllPlain(blackhole: Blackhole) {
        Mp4Demuxer(plain).use { demuxer ->
            while (true) blackhole.consume(demuxer.readPacket() ?: break)
        }
    }
}

/** `seekTo` a puntos al azar del MP4 de dos horas, ya abierto. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class Mp4SeekBenchmark {

    private lateinit var plain: Mp4Demuxer
    private lateinit var fragmented: Mp4Demuxer
    private val random = Random(3)

    @Setup(Level.Trial)
    fun open() {
        plain = Mp4Demuxer(TwoHourFiles.plain)
        fragmented = Mp4Demuxer(TwoHourFiles.fragmented)
    }

    @TearDown(Level.Trial)
    fun close() {
        plain.close()
        fragmented.close()
    }

    /** Solo `seekTo`, que es la cifra del §4 de `ANALISIS.md`. */
    @Benchmark
    fun seekPlain(): Long = plain.seekTo(random.nextLong(TwoHourFiles.DURATION_US))

    /** `seekTo` y el primer paquete, que es cuando se resuelve dónde están sus bytes. */
    @Benchmark
    fun seekAndReadPlain(blackhole: Blackhole) {
        blackhole.consume(plain.seekTo(random.nextLong(TwoHourFiles.DURATION_US)))
        blackhole.consume(plain.readPacket())
    }

    @Benchmark
    fun seekFragmented(): Long = fragmented.seekTo(random.nextLong(TwoHourFiles.DURATION_US))
}

/** Escribir las dos horas con `Mp4Muxer`, incluido `stop()`, que es donde se escriben las tablas. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class Mp4MuxerBenchmark {

    private lateinit var file: File

    @Setup(Level.Trial)
    fun prepare() {
        file = File.createTempFile("escritura", ".mp4").apply { deleteOnExit() }
    }

    @Benchmark
    fun writeTwoHours() {
        TwoHourFiles.writeInto(MkvKotlin.createMuxer(file, ContainerFormat.MP4))
    }
}
