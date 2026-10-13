package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.codecconfig.OpusConfig
import com.braymon.kotmpeg.ebml.EbmlWriter
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ColorInfo
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.HdrStaticInfo
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import com.braymon.kotmpeg.mp4.Mp4Demuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import kotlin.random.Random

/**
 * Archivos válidos de los que parte el fuzzing. Cuanto más variados, más partes del parser se
 * alcanzan con una sola mutación, así que hay de dos orígenes, y ninguno va en el repositorio:
 *
 * - **De terceros**, generados con FFmpeg en cada ejecución si está instalado, como hacen los tests
 *   de integración. Traen lo que los muxers de aquí no escriben: `ctts` por B-frames, `edts` por el
 *   retardo de AAC, fMP4 con `base_data_offset` explícito, HEVC con Opus en fragmentos, los `sidx`
 *   de `-movflags dash`, y `SeekHead`, `Tags` y `Cues` de Matroska. Sin FFmpeg el fuzzing sigue
 *   con las de la librería.
 * - **De esta librería**, también generados en cada ejecución: los tres contenedores con color HDR,
 *   rotación, idioma, nombre, Opus con retardo de códec y varios fragmentos, uno con `sidx`; y un
 *   MKV escrito a mano con los tres tipos de lacing, zlib, eliminación de cabecera y `BlockGroup`,
 *   que ningún muxer de aquí produce y que son justo los caminos más delicados del demuxer.
 *
 * Las semillas de FFmpeg dependen de la versión instalada, así que dos máquinas pueden mutar
 * entradas un poco distintas con la misma semilla base. No hace falta más: cada hallazgo se guarda
 * con la entrada exacta que lo provocó.
 */
internal object FuzzSeeds {

    enum class Container(val extension: String) { MKV("mkv"), MP4("mp4") }

    class Seed(val name: String, val container: Container, val bytes: ByteArray)

    /** Todas las semillas, escritas en [dir]: las de FFmpeg, si lo hay, y las de la librería. */
    fun all(dir: File): List<Seed> {
        dir.mkdirs()
        val thirdParty = ffmpeg(dir)
        return thirdParty + generated(dir, thirdParty)
    }

    /** Si hay un `ffmpeg` que se pueda ejecutar; sin él solo se usan las semillas de la librería. */
    val ffmpegAvailable: Boolean by lazy {
        runCatching { run(listOf("ffmpeg", "-version")) }.getOrDefault(false)
    }

    private fun run(command: List<String>): Boolean {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        return process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0
    }

    /**
     * Medio segundo de vídeo de 64x48 y un tono, codificados con un solo hilo y sin metadatos de
     * versión para que el resultado no cambie de una ejecución a otra en la misma máquina.
     */
    private fun ffmpeg(dir: File): List<Seed> {
        if (!ffmpegAvailable) return emptyList()
        val input = listOf(
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=15:duration=0.6",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=0.6",
        )
        val stable = listOf("-threads", "1", "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-flags:a", "+bitexact")
        val x264 = listOf("-c:v", "libx264", "-preset", "ultrafast", "-b:v", "20k")
        val x265 = listOf("-c:v", "libx265", "-preset", "ultrafast", "-b:v", "20k")
        val recipes = listOf(
            "ffmpeg-h264-aac-bframes.mp4" to x264 + listOf("-bf", "2", "-g", "5", "-c:a", "aac", "-b:a", "16k"),
            "ffmpeg-fragmented-default-base-moof.mp4" to x264 + listOf(
                "-g", "4", "-c:a", "aac", "-b:a", "16k", "-movflags", "frag_keyframe+empty_moov+default_base_moof",
            ),
            "ffmpeg-fragmented-hevc-opus.mp4" to x265 + listOf(
                "-x265-params", "log-level=none:keyint=5:bframes=2:pools=1:frame-threads=1",
                "-c:a", "libopus", "-b:a", "16k", "-movflags", "frag_keyframe+empty_moov",
            ),
            "ffmpeg-dash-sidx.mp4" to x264 + listOf(
                "-g", "4", "-c:a", "aac", "-b:a", "16k", "-movflags", "dash+frag_keyframe+empty_moov+default_base_moof",
            ),
            "ffmpeg-h264-opus.mkv" to x264 + listOf(
                "-bf", "2", "-g", "5", "-c:a", "libopus", "-b:a", "16k", "-cluster_time_limit", "200", "-metadata:s:a", "language=spa",
            ),
            "ffmpeg-hevc-aac-hdr.mkv" to x265 + listOf(
                "-x265-params", "log-level=none:keyint=5:pools=1:frame-threads=1", "-c:a", "aac", "-b:a", "16k",
                "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc", "-cluster_time_limit", "300",
            ),
        )
        return recipes.mapNotNull { (name, codecs) ->
            val file = File(dir, name)
            val ok = runCatching { run(listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-y") + input + codecs + stable + file.absolutePath) }
                .getOrDefault(false)
            if (!ok || !file.isFile) return@mapNotNull null
            Seed(name, if (file.extension == "mkv") Container.MKV else Container.MP4, file.readBytes())
        }
    }

    /**
     * Las semillas de la librería. El vídeo es H.265 con HDR cuando FFmpeg ha dado un `hvcC` real
     * del que sacarlo, y H.264 si no.
     */
    private fun generated(dir: File, thirdParty: List<Seed>): List<Seed> {
        val hevcConfig = thirdParty.firstOrNull { it.name.contains("hevc") && it.container == Container.MP4 }?.let { seed ->
            val file = File(dir, "hevc-config.mp4").apply { writeBytes(seed.bytes) }
            Mp4Demuxer(file).use { demuxer -> demuxer.tracks.filterIsInstance<TrackInfo.Video>().firstOrNull()?.codecPrivate }
        }
        return listOf(
            Seed("lib-mkv", Container.MKV, muxed(File(dir, "lib.mkv"), ContainerFormat.MKV, hevcConfig)),
            Seed("lib-mp4", Container.MP4, muxed(File(dir, "lib.mp4"), ContainerFormat.MP4, hevcConfig)),
            Seed("lib-mp4-faststart", Container.MP4, muxed(File(dir, "lib-fs.mp4"), ContainerFormat.MP4, null, fastStart = true)),
            Seed("lib-fmp4", Container.MP4, muxed(File(dir, "lib-f.mp4"), ContainerFormat.MP4, hevcConfig, fragmented = true)),
            Seed(
                "lib-fmp4-sidx", Container.MP4,
                muxed(File(dir, "lib-sidx.mp4"), ContainerFormat.MP4, null, fragmented = true, segmentIndex = true),
            ),
            Seed("lib-mkv-lacing-encodings", Container.MKV, handMadeMkv(File(dir, "hand.mkv"))),
        )
    }

    /**
     * Vídeo con B-frames simulados (pts reordenados), audio AAC y Opus con retardo de códec, 1,2 s.
     * Con [hevcConfig] el vídeo es H.265 con HDR; sin él, H.264 rotado.
     */
    private fun muxed(
        file: File,
        format: ContainerFormat,
        hevcConfig: ByteArray?,
        fastStart: Boolean = false,
        fragmented: Boolean = false,
        segmentIndex: Boolean = false,
    ): ByteArray {
        val muxer = if (segmentIndex) {
            FragmentedMp4Muxer(file, 300_000).apply { this.segmentIndex = true }
        } else {
            MkvKotlin.createMuxer(file, format, mp4FastStart = fastStart, mp4Fragmented = fragmented, mp4FragmentDurationUs = 300_000)
        }
        val video = if (hevcConfig != null) {
            TrackInfo.Video(
                codec = VideoCodec.H265, width = 64, height = 48, frameRate = 15.0, codecPrivate = hevcConfig,
                color = ColorInfo(
                    primaries = 9, transfer = 16, matrix = 9, fullRange = false,
                    hdr = HdrStaticInfo(0.708, 0.292, 0.170, 0.797, 0.131, 0.046, 0.3127, 0.3290, 1000.0, 0.005, 1000, 400),
                ),
                language = "spa", name = "Pantalla",
            )
        } else {
            TrackInfo.Video(
                codec = VideoCodec.H264, width = 64, height = 48, frameRate = 15.0, rotationDegrees = 90,
                codecPrivate = NalUnits.buildAvcC(listOf(byteArrayOf(0x67, 0x64, 0, 0x1F, 0x11)), listOf(byteArrayOf(0x68, 0x22))),
            )
        }
        val videoId = muxer.addTrack(video)
        val aacId = muxer.addTrack(
            TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48000, channelCount = 2, codecPrivate = AacConfig.build(48000, 2), name = "Micrófono"),
        )
        val opusId = muxer.addTrack(
            TrackInfo.Audio(
                codec = AudioCodec.OPUS, sampleRate = 48000, channelCount = 2, codecDelayUs = 6_500,
                codecPrivate = OpusConfig.buildOpusHead(channelCount = 2), language = "eng", default = false,
            ),
        )
        muxer.start()
        val random = Random(7)
        val packets = ArrayList<MediaPacket>()
        val frame = 66_666L
        val order = intArrayOf(0, 2, 1, 3, 5, 4)
        for (i in 0 until 18) {
            val shown = (i / 6) * 6 + order[i % 6]
            packets += MediaPacket(
                videoId, NalUnits.joinLengthPrefixed(listOf(ByteArray(12 + random.nextInt(20)).also { random.nextBytes(it) })),
                ptsUs = shown * frame + frame, dtsUs = i * frame, isKeyFrame = i % 6 == 0, durationUs = frame,
            )
        }
        for (i in 0 until 56) packets += MediaPacket(aacId, ByteArray(6 + random.nextInt(6)), i * 21_333L, i * 21_333L, true, 21_333)
        for (i in 0 until 60) packets += MediaPacket(opusId, ByteArray(5 + random.nextInt(5)), i * 20_000L, i * 20_000L, true, 20_000)
        packets.sortedBy { it.dtsUs }.forEach { muxer.writePacket(it) }
        muxer.stop()
        return file.readBytes()
    }

    /**
     * MKV escrito a mano: pista 1 de audio con zlib y bloques con lacing Xiph, fijo y EBML; pista 2
     * con eliminación de cabecera dentro de un `BlockGroup` con `BlockDuration` y `ReferenceBlock`;
     * escala de tiempo no estándar y un `Cues` que apunta a los dos clusters.
     */
    private fun handMadeMkv(file: File): ByteArray {
        val frames = List(4) { i -> ByteArray(9 + i) { k -> (0x30 + i * 7 + k).toByte() } }
        val clusterPositions = ArrayList<Long>()
        SeekableOutput(file).use { out ->
            val ebml = EbmlWriter(out)
            val header = ebml.beginMaster(MatroskaIds.EBML)
            ebml.writeString(MatroskaIds.DOCTYPE, "matroska")
            ebml.endMaster(header)
            val segment = ebml.beginMaster(MatroskaIds.SEGMENT)
            val segmentData = segment + 8
            val info = ebml.beginMaster(MatroskaIds.INFO)
            ebml.writeUInt(MatroskaIds.TIMESTAMP_SCALE, 500_000)
            ebml.writeFloat(MatroskaIds.DURATION, 400.0)
            ebml.endMaster(info)
            val tracks = ebml.beginMaster(MatroskaIds.TRACKS)
            audioEntry(ebml, number = 1, algorithm = 0, settings = null)
            audioEntry(ebml, number = 2, algorithm = 3, settings = byteArrayOf(0x21, 0x10))
            ebml.endMaster(tracks)
            for (c in 0 until 2) {
                clusterPositions += out.position - segmentData
                val cluster = ebml.beginMaster(MatroskaIds.CLUSTER)
                ebml.writeUInt(MatroskaIds.CLUSTER_TIMESTAMP, c * 200L)
                ebml.writeElement(MatroskaIds.SIMPLE_BLOCK, laced(1, 0, 0x80 or (1 shl 1), xiph(frames.map(::zlib))))
                ebml.writeElement(MatroskaIds.SIMPLE_BLOCK, laced(1, 40, 0x80 or (2 shl 1), fixed(frames.map { zlib(ByteArray(8) { k -> (k * 3).toByte() }) })))
                ebml.writeElement(MatroskaIds.SIMPLE_BLOCK, laced(1, 80, 0x80 or (3 shl 1), ebmlLaces(frames.map(::zlib))))
                val group = ebml.beginMaster(MatroskaIds.BLOCK_GROUP)
                ebml.writeElement(MatroskaIds.BLOCK, laced(2, 120, 0, frames[c]))
                ebml.writeUInt(MatroskaIds.BLOCK_DURATION, 40)
                ebml.writeSInt(MatroskaIds.REFERENCE_BLOCK, -40)
                ebml.endMaster(group)
                ebml.endMaster(cluster)
            }
            val cues = ebml.beginMaster(MatroskaIds.CUES)
            for ((c, position) in clusterPositions.withIndex()) {
                val point = ebml.beginMaster(MatroskaIds.CUE_POINT)
                ebml.writeUInt(MatroskaIds.CUE_TIME, c * 200L)
                val positions = ebml.beginMaster(MatroskaIds.CUE_TRACK_POSITIONS)
                ebml.writeUInt(MatroskaIds.CUE_TRACK, 1)
                ebml.writeUInt(MatroskaIds.CUE_CLUSTER_POSITION, position)
                ebml.endMaster(positions)
                ebml.endMaster(point)
            }
            ebml.endMaster(cues)
            ebml.endMaster(segment)
        }
        return file.readBytes()
    }

    private fun audioEntry(ebml: EbmlWriter, number: Long, algorithm: Long, settings: ByteArray?) {
        val entry = ebml.beginMaster(MatroskaIds.TRACK_ENTRY)
        ebml.writeUInt(MatroskaIds.TRACK_NUMBER, number)
        ebml.writeUInt(MatroskaIds.TRACK_TYPE, MatroskaIds.TRACK_TYPE_AUDIO)
        ebml.writeString(MatroskaIds.CODEC_ID, AudioCodec.AAC.matroskaId)
        ebml.writeElement(MatroskaIds.CODEC_PRIVATE, AacConfig.build(44100, 1))
        val encodings = ebml.beginMaster(MatroskaIds.CONTENT_ENCODINGS)
        val encoding = ebml.beginMaster(MatroskaIds.CONTENT_ENCODING)
        ebml.writeUInt(MatroskaIds.CONTENT_ENCODING_SCOPE, 1)
        val compression = ebml.beginMaster(MatroskaIds.CONTENT_COMPRESSION)
        ebml.writeUInt(MatroskaIds.CONTENT_COMP_ALGO, algorithm)
        settings?.let { ebml.writeElement(MatroskaIds.CONTENT_COMP_SETTINGS, it) }
        ebml.endMaster(compression)
        ebml.endMaster(encoding)
        ebml.endMaster(encodings)
        val audio = ebml.beginMaster(MatroskaIds.AUDIO)
        ebml.writeFloat(MatroskaIds.SAMPLING_FREQUENCY, 44100.0)
        ebml.writeUInt(MatroskaIds.CHANNELS, 1)
        ebml.endMaster(audio)
        ebml.endMaster(entry)
    }

    private fun laced(track: Int, time: Int, flags: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x80 or track)
        out.write(time ushr 8)
        out.write(time)
        out.write(flags)
        out.write(payload)
        return out.toByteArray()
    }

    private fun xiph(frames: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(frames.size - 1)
        for (frame in frames.dropLast(1)) {
            var size = frame.size
            while (size >= 255) { out.write(255); size -= 255 }
            out.write(size)
        }
        frames.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun fixed(frames: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(frames.size - 1)
        frames.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** Lacing EBML: el primer tamaño como vint sin signo y el resto como diferencias con signo. */
    private fun ebmlLaces(frames: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(frames.size - 1)
        out.write(0x40 or (frames[0].size ushr 8))
        out.write(frames[0].size)
        for (k in 1 until frames.size - 1) {
            val diff = frames[k].size - frames[k - 1].size
            out.write(0x80 or (diff + 63))
        }
        frames.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun zlib(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val buffer = ByteArray(data.size + 64)
        val length = deflater.deflate(buffer)
        deflater.end()
        return buffer.copyOf(length)
    }
}
