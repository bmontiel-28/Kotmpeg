package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.Mp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * La invariante del inicio rápido: **pase lo que pase, en disco queda una copia completa**.
 *
 * Hubo una versión en la que el `finally` de `rewriteFastStart` borraba el temporal y la copia
 * de respaldo también cuando el intercambio de nombres se había quedado a medias — y en ese
 * estado eran las dos únicas copias completas de la grabación.
 *
 * El mecanismo cambió después: aquel intercambio se sustituyó por `Files.move(..., ATOMIC_MOVE)`
 * y lo cubre [FastStartAtomicMoveTest]. Lo que estos tests fijan no es el mecanismo, es la
 * invariante, y por eso siguen valiendo tal cual.
 */
class FastStartSafetyTest {

    @TempDir
    lateinit var dir: File

    private fun videoTrack() = TrackInfo.Video(
        codec = VideoCodec.H264, width = 320, height = 240, frameRate = 30.0,
        codecPrivate = NalUnits.buildAvcC(
            listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
            listOf(byteArrayOf(0x68, 0x11, 0x22)),
        ),
    )

    private fun payload(i: Int): ByteArray =
        NalUnits.joinLengthPrefixed(listOf(ByteArray(24 + (i % 5) * 4) { (i + it).toByte() }))

    private fun writeMp4(target: File, frames: Int, fastStart: Boolean): Mp4Muxer {
        val muxer = Mp4Muxer(target, fastStart = fastStart)
        val id = muxer.addTrack(videoTrack())
        muxer.start()
        for (i in 0 until frames) {
            muxer.writePacket(
                MediaPacket(id, payload(i), ptsUs = i * 33_333L, isKeyFrame = i % 15 == 0),
            )
        }
        return muxer
    }

    /** Paquetes releídos como (pts, tamaño), para comparar dos archivos muestra a muestra. */
    private fun readBack(file: File): List<Pair<Long, Int>> =
        MkvKotlin.openDemuxer(file).use { demuxer ->
            generateSequence { demuxer.readPacket() }.map { it.ptsUs to it.data.size }.toList()
        }

    /** Ocupa [name] con un directorio no vacío: no se puede borrar ni renombrar encima. */
    private fun blockName(name: String): File {
        val blocker = File(dir, name)
        assertTrue(blocker.mkdirs())
        File(blocker, "ocupado").writeBytes(byteArrayOf(1))
        return blocker
    }

    /**
     * La invariante que rompía el `finally`: **pase lo que pase, en disco queda al menos una
     * copia completa de la grabación**.
     *
     * Se mira el directorio entero y no solo el destino, porque si el intercambio se quedara a
     * medias la superviviente estaría bajo otro nombre. La ruta que se fuerza aquí es la de la
     * copia (el temporal no se puede ni crear), que es la que sí se alcanza en cualquier
     * plataforma.
     */
    @Test
    fun `no failure of the faststart rewrite leaves the directory without a complete copy`() {
        val out = File(dir, "captura.mp4")
        blockName("captura.mp4.faststart.tmp")

        assertFailsWith<Throwable> { writeMp4(out, frames = 25, fastStart = true).stop() }

        val survivors = dir.listFiles()!!
            .filter { it.isFile && it.name.startsWith("captura.mp4") }
            .filter { runCatching { readBack(it).size }.getOrDefault(0) == 25 }
        assertTrue(
            survivors.isNotEmpty(),
            "no quedó ninguna copia legible: ${dir.listFiles()!!.map { it.name }}",
        )
        assertTrue(out.exists(), "y la que queda tiene que ser el propio destino")
    }

    /** El error de `fastStart` dice siempre que el archivo sigue siendo reproducible. */
    @Test
    fun `a failed faststart always reports that the recording is still playable`() {
        val out = File(dir, "aviso.mp4")
        blockName("aviso.mp4.faststart.tmp")

        val error = assertFailsWith<Throwable> { writeMp4(out, frames = 10, fastStart = true).stop() }
        val message = error.message.orEmpty()
        assertTrue(message.contains("inicio rápido"), "debe decir qué se perdió: $message")
        assertTrue(message.contains("se reproduce"), "y que el archivo sirve igual: $message")
    }

    /**
     * Reutilizar el `moov` de cola en vez de reconstruirlo no puede cambiar el resultado.
     *
     * Se compara la salida con `fastStart` contra la de siempre: mismos paquetes, mismos
     * tiempos, mismos tamaños, y el `moov` delante del `mdat` en una y detrás en la otra.
     */
    @Test
    fun `reusing the tail moov does not change what faststart writes`() {
        val plain = File(dir, "plano.mp4")
        writeMp4(plain, frames = 50, fastStart = false).stop()
        val fast = File(dir, "rapido.mp4")
        writeMp4(fast, frames = 50, fastStart = true).stop()

        assertEquals(readBack(plain), readBack(fast), "el contenido debe ser idéntico")

        fun order(f: File): Pair<Int, Int> {
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            return text.indexOf("moov") to text.indexOf("mdat")
        }
        val (plainMoov, plainMdat) = order(plain)
        assertTrue(plainMdat in 1 until plainMoov, "en el plano el moov va al final")
        val (fastMoov, fastMdat) = order(fast)
        assertTrue(fastMoov in 1 until fastMdat, "con fastStart el moov va delante")
    }
}
