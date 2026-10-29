package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.ebml.EbmlElement
import com.braymon.kotmpeg.ebml.EbmlReader
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.mkv.MkvMuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dónde cae una búsqueda en un MKV: solo en keyframes que el demuxer va a entregar.
 *
 * `seekTo` recorre las cabeceras de los bloques para elegir el keyframe, y `readPacket` lo lee
 * después con otro criterio. Si los dos no coinciden, la búsqueda devuelve el tiempo de un
 * fotograma que nunca sale y el filtro tira todo el vídeo anterior: no sale ningún fotograma.
 */
class MkvFinalSeekTest {

    private val b = MkvReadBuilder

    private val video = TrackInfo.Video(VideoCodec.H264, 320, 240) {
        frameRate = 30.0
        codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22)))
    }
    private val audio = TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = AacConfig.build(48_000, 2) }

    /** Cinco segundos de vídeo a 30 fps con un keyframe por segundo y AAC a 48 kHz, por orden de tiempo. */
    private fun packets(): List<MediaPacket> {
        val random = Random(7)
        val packets = ArrayList<MediaPacket>()
        for (frame in 0 until 150) {
            val data = ByteArray(200 + random.nextInt(2800)).also { random.nextBytes(it) }
            packets += MediaPacket(1, data, frame * 33_333L, isKeyFrame = frame % 30 == 0)
        }
        var audioUs = 0L
        while (audioUs < 5_000_000) {
            val data = ByteArray(150 + random.nextInt(250)).also { random.nextBytes(it) }
            packets += MediaPacket(2, data, audioUs, isKeyFrame = true)
            audioUs += 21_333
        }
        return packets.sortedBy { it.ptsUs }
    }

    /** El `SimpleBlock` del keyframe de vídeo en [timeMs] del MKV cerrado [bytes]. */
    private fun keyFrameBlock(bytes: ByteArray, timeMs: Long): EbmlElement = memoryInput(bytes).use { input ->
        val reader = EbmlReader(input)
        reader.skip(reader.readElement())
        reader.readElement()
        while (input.position < input.length) {
            val el = reader.readElement()
            if (el.id != MatroskaIds.CLUSTER) {
                reader.skip(el)
                continue
            }
            var timestamp = 0L
            while (input.position < el.dataEnd) {
                val child = reader.readElement()
                if (child.id == MatroskaIds.CLUSTER_TIMESTAMP) {
                    timestamp = reader.readUInt(child)
                } else if (child.id == MatroskaIds.SIMPLE_BLOCK) {
                    val track = input.readByte() and 0x7F
                    val relative = input.readUInt(2).toInt().toShort()
                    val flags = input.readByte()
                    if (track == 1 && flags and 0x80 != 0 && timestamp + relative == timeMs) return child
                }
                input.position = child.dataEnd
            }
        }
        error("no hay keyframe de vídeo en $timeMs ms")
    }

    /**
     * La grabación de [channel] cortada justo cuando el archivo llega a [length] bytes: dentro de la
     * escritura que los alcanza, como un proceso que muere a mitad de ella.
     */
    private fun cutAt(channel: RecordingFileChannel, length: Long): ByteArray {
        var before = 0L
        for (write in channel.writes) {
            val splittable = write.truncatedTo < 0 && write.bytes.size > RecordingFileChannel.ATOMIC_BYTES
            if (splittable && length in write.position + 1..write.position + write.bytes.size) {
                return channel.cutAfter(before + (length - write.position)).also { assertEquals(length, it.size.toLong()) }
            }
            before += write.cost
        }
        error("ninguna escritura llega a $length")
    }

    /**
     * Una grabación cortada mientras se escribía el keyframe que abre el cluster en curso deja su
     * cabecera entera y su carga a medias. El demuxer no puede entregarlo, así que la búsqueda al
     * final tiene que caer en el anterior: si cae en él, el filtro tira todo el vídeo de antes y
     * no sale ni un fotograma.
     */
    @Test
    fun `a seek to the end of a recording cut inside a keyframe lands on the last complete one`() {
        val channel = RecordingFileChannel()
        MkvMuxer(SeekableOutput(channel)) { creationTimeMillis = null }.use { muxer ->
            muxer.addTrack(video)
            muxer.addTrack(audio)
            muxer.start()
            for (packet in packets()) muxer.writePacket(packet)
        }
        val key = keyFrameBlock(channel.bytes(), 3_000)
        val cut = cutAt(channel, key.dataStart + 16)
        Kotmpeg.openDemuxer(memoryInput(cut)).use { demuxer ->
            val target = demuxer.durationUs
            assertTrue(target >= 3_000_000, "la duración declarada ($target µs) no llega al keyframe cortado")
            val landed = demuxer.seekTo(target)
            val videoPts = generateSequence { demuxer.readPacket() }.filter { it.trackId == 1 }.map { it.ptsUs }.toList()
            assertEquals(2_000_000L, landed, "seekTo($target)")
            assertTrue(videoPts.isNotEmpty(), "seekTo($target) = $landed y no sale ningún fotograma de vídeo")
            assertEquals(2_000_000L, videoPts.first())
        }
    }

    /** Lo mismo con el keyframe en un `BlockGroup`, cuyo `Block` es lo que queda cortado. */
    @Test
    fun `a seek does not land on a block group keyframe cut by the end of the file`() {
        fun group(payload: Int) = b.element(MatroskaIds.BLOCK_GROUP, b.element(MatroskaIds.BLOCK, b.blockBody(1, 0, 0, ByteArray(payload) { 0x11 })))
        val whole = b.openFile(
            b.element(MatroskaIds.INFO, b.uint(MatroskaIds.TIMESTAMP_SCALE, 1_000_000), b.float(MatroskaIds.DURATION, 2_500.0)),
            b.tracks(b.videoTrack(1)),
            b.cluster(0, group(100)),
            b.cluster(1_000, group(100)),
            b.cluster(2_000, group(100)),
        )
        val cut = whole.copyOf(whole.size - 50)
        Kotmpeg.openDemuxer(memoryInput(cut)).use { demuxer ->
            val landed = demuxer.seekTo(demuxer.durationUs)
            val videoPts = generateSequence { demuxer.readPacket() }.filter { it.trackId == 1 }.map { it.ptsUs }.toList()
            assertEquals(1_000_000L, landed)
            assertEquals(listOf(1_000_000L), videoPts)
        }
    }

    /**
     * Un cluster de tamaño desconocido acaba en cualquier id de nivel 1, tanto al leer como al
     * buscar: un bloque suelto detrás de unos `Tags` no es de ningún cluster, `readPacket` no lo
     * entrega, y una búsqueda que cayera en él no entregaría nada.
     */
    @Test
    fun `a seek does not land on a keyframe that lies after a level 1 element`() {
        val file = b.openFile(
            b.element(MatroskaIds.INFO, b.uint(MatroskaIds.TIMESTAMP_SCALE, 1_000_000), b.float(MatroskaIds.DURATION, 1_000.0)),
            b.tracks(b.videoTrack(1)),
            b.unknownSize(MatroskaIds.CLUSTER, b.uint(MatroskaIds.CLUSTER_TIMESTAMP, 0), b.simpleBlock(1, 0, true)),
            b.element(MatroskaIds.TAGS),
            b.simpleBlock(1, 500, true),
        )
        Kotmpeg.openDemuxer(memoryInput(file)).use { demuxer ->
            val landed = demuxer.seekTo(800_000)
            val videoPts = generateSequence { demuxer.readPacket() }.filter { it.trackId == 1 }.map { it.ptsUs }.toList()
            assertEquals(0L, landed)
            assertEquals(listOf(0L), videoPts)
        }
    }

    /**
     * Ningún id de nivel 1 puede estar dentro de un cluster, así que cualquiera cierra uno de tamaño
     * desconocido, y no solo los seis que miraba el demuxer: un `Info` también. Es lo que hace la
     * reparación, y lo que dice Matroska. Lo que viene detrás sin cluster no es de ninguno.
     */
    @Test
    fun `an unknown-size cluster ends at any level 1 element when reading`() {
        val file = b.openFile(
            b.element(MatroskaIds.INFO, b.uint(MatroskaIds.TIMESTAMP_SCALE, 1_000_000)),
            b.tracks(b.videoTrack(1)),
            b.unknownSize(MatroskaIds.CLUSTER, b.uint(MatroskaIds.CLUSTER_TIMESTAMP, 0), b.simpleBlock(1, 0, true)),
            b.element(MatroskaIds.INFO),
            b.simpleBlock(1, 40, true),
            b.cluster(80, b.simpleBlock(1, 0, true)),
        )
        Kotmpeg.openDemuxer(memoryInput(file)).use { demuxer ->
            val pts = generateSequence { demuxer.readPacket() }.map { it.ptsUs }.toList()
            assertEquals(listOf(0L, 80_000L), pts)
        }
    }
}
