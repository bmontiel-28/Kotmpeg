package com.braymon.kotmpeg

import com.braymon.kotmpeg.mkv.MkvDemuxer
import com.braymon.kotmpeg.model.ContainerFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFailsWith

/**
 * Los dos demuxers se comportan igual tras `close`: `readPacket` y `seekTo` lanzan
 * `IllegalStateException`, y `close` se puede repetir. `MkvDemuxer` ya lo hacía; `Mp4Demuxer` solo
 * cerraba la entrada y dejaba que la siguiente llamada fallara —o no— según tocara el archivo.
 */
class SharedDemuxerCloseTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `both demuxers refuse to read or seek once closed`() {
        val sources = listOf(
            "plano.mp4" to Mp4ReadFixtures.write(File(dir, "plano.mp4"), audioFrames = 20),
            "fragmentado.mp4" to Mp4ReadFixtures.write(File(dir, "fragmentado.mp4"), fragmented = true, audioFrames = 20),
            "mkv" to File(dir, "video.mkv").also { mkv ->
                Kotmpeg.remux(Mp4ReadFixtures.write(File(dir, "origen.mp4"), audioFrames = 20), mkv, ContainerFormat.MKV)
            },
        )
        for ((name, file) in sources) {
            val demuxer = Kotmpeg.openDemuxer(file)
            if (name == "mkv") check(demuxer is MkvDemuxer)
            demuxer.readPacket()
            demuxer.close()
            assertFailsWith<IllegalStateException>("$name: readPacket tras close") { demuxer.readPacket() }
            assertFailsWith<IllegalStateException>("$name: seekTo tras close") { demuxer.seekTo(0) }
            demuxer.close()
        }
    }
}
