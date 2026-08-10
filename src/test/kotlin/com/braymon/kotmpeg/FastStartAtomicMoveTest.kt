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
import kotlin.test.assertTrue

/**
 * El movimiento atómico que pone el `moov` en su sitio al final del inicio rápido.
 *
 * Antes había ahí un intercambio de nombres con `.bak` —tres renombrados encadenados más una
 * vuelta atrás— que resultó ser código muerto en las dos plataformas donde se prueba el
 * proyecto, porque `File.renameTo` reemplaza el destino tanto en Linux como en el JDK con el que
 * se compila. Veinticinco líneas delicadas que no ejecutaba nadie.
 *
 * `Files.move(..., ATOMIC_MOVE)` sí tiene semántica definida y uniforme: o el destino queda
 * sustituido, o lanza y el original sigue donde estaba. Estos tests fijan lo que esa sustitución
 * tiene que seguir cumpliendo; la invariante de fondo está en [FastStartSafetyTest].
 */
class FastStartAtomicMoveTest {

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

    private fun writeMp4(target: File, frames: Int, fastStart: Boolean) {
        Mp4Muxer(target, fastStart = fastStart).use { muxer ->
            val id = muxer.addTrack(videoTrack())
            muxer.start()
            for (i in 0 until frames) {
                muxer.writePacket(
                    MediaPacket(id, payload(i), ptsUs = i * 33_333L, isKeyFrame = i % 15 == 0),
                )
            }
        }
    }

    private fun readBack(file: File): List<Pair<Long, Int>> =
        MkvKotlin.openDemuxer(file).use { demuxer ->
            generateSequence { demuxer.readPacket() }.map { it.ptsUs to it.data.size }.toList()
        }

    /**
     * La sustitución del original por el archivo reordenado se completa y no deja restos.
     *
     * Con el intercambio de nombres anterior, el destino podía quedar sustituido dejando atrás un
     * `.bak`; con el movimiento atómico solo hay dos desenlaces posibles, y este es el bueno.
     */
    @Test
    fun `a successful faststart replaces the destination and leaves nothing behind`() {
        val out = File(dir, "limpio.mp4")
        writeMp4(out, frames = 30, fastStart = true)

        assertEquals(30, readBack(out).size)
        assertTrue(
            dir.listFiles()!!.none { it.name.contains(".faststart.") },
            "quedaron restos: ${dir.listFiles()!!.map { it.name }}",
        )
    }

    /**
     * Un temporal huérfano de una ejecución anterior que murió a medias no impide grabar.
     *
     * Es el escenario que motivaba parte del baile de nombres: con `renameTo` un destino ya
     * ocupado podía hacer fallar el renombrado. Aquí el temporal se trunca al abrirlo y el
     * movimiento reemplaza el destino, así que ni el temporal viejo ni el destino previo estorban.
     */
    @Test
    fun `a stale temporary from a previous run does not block a new faststart`() {
        val out = File(dir, "reintento.mp4")
        out.writeBytes(ByteArray(4096) { 0x5A })
        File(dir, "reintento.mp4.faststart.tmp").writeBytes(ByteArray(9000) { 0x33 })

        writeMp4(out, frames = 20, fastStart = true)

        assertEquals(20, readBack(out).size, "la grabación nueva tiene que quedar completa")
        assertTrue(
            dir.listFiles()!!.none { it.name.contains(".faststart.") },
            "quedaron restos: ${dir.listFiles()!!.map { it.name }}",
        )
    }

    /**
     * El contenido no depende de la ruta: `fastStart` solo mueve el índice de sitio.
     *
     * Es la red que protege la simplificación: si el movimiento atómico se cambiara alguna vez
     * por una copia, esto seguiría pasando, pero cualquier error en el recorte del `mdat` o en el
     * desplazamiento de los offsets se vería aquí de inmediato.
     */
    @Test
    fun `faststart and plain output carry exactly the same samples`() {
        val plain = File(dir, "plano.mp4")
        writeMp4(plain, frames = 45, fastStart = false)
        val fast = File(dir, "rapido.mp4")
        writeMp4(fast, frames = 45, fastStart = true)

        assertEquals(readBack(plain), readBack(fast))
        assertEquals(
            plain.length(), fast.length(),
            "reordenar no puede cambiar el tamaño del archivo",
        )
    }
}
