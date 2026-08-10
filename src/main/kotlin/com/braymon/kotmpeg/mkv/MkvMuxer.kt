package com.braymon.kotmpeg.mkv

import com.braymon.kotmpeg.Muxer
import com.braymon.kotmpeg.codecconfig.OpusConfig
import com.braymon.kotmpeg.ebml.EbmlWriter
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

/**
 * Muxer Matroska (.mkv).
 *
 * Produce archivos conformes a la especificación: cabecera EBML, SeekHead, Info (con la
 * duración parcheada al finalizar), Tracks, Clusters alineados a keyframe con SimpleBlocks
 * y un índice Cues para búsqueda fiable en reproductores.
 *
 * Las marcas de tiempo usan el TimestampScale estándar de 1 ms. Los bloques llevan tiempos
 * de presentación; los paquetes deben llegar en orden de decodificación por pista (Matroska
 * guarda los fotogramas en orden de decodificación con PTS, así que los B-frames no
 * necesitan señalización extra aquí).
 *
 * **No es seguro entre hilos**: toda la secuencia addTrack/start/writePacket/stop debe
 * ejecutarse desde un solo hilo, o serializarse por fuera. Es lo que hace falta en cuanto hay un
 * hilo por pista, que es el caso normal cuando los paquetes vienen de varios codificadores.
 */
public class MkvMuxer(
    private val out: SeekableOutput,
    /** Duración máxima de cluster antes de forzar uno nuevo, en ms. */
    private val maxClusterDurationMs: Long = 5_000,
    private val writingApp: String = "Kotmpeg",
) : Muxer {

    public constructor(file: File, maxClusterDurationMs: Long = 5_000) :
        this(SeekableOutput(file), maxClusterDurationMs)

    private val ebml = EbmlWriter(out)
    private val tracks = ArrayList<TrackInfo>()
    private val random = Random()

    private var started = false
    private var stopped = false

    private var segmentSizePos = 0L
    private var segmentDataStart = 0L
    private var seekHeadPos = 0L
    private var infoPos = 0L
    private var tracksPos = 0L
    private var cuesPos = 0L
    private var durationValuePos = 0L

    private var clusterSizePos = -1L
    private var clusterStartPos = 0L
    private var clusterTimestampMs = 0L
    private var maxEndTimestampMs = 0L

    private class CueEntry(val timeMs: Long, val trackNumber: Int, val clusterPosition: Long)

    private val cues = ArrayList<CueEntry>()
    private var cueTrackNumber = 0
    private var lastCuedClusterPos = -1L

    private companion object {
        /** Espacio fijo reservado al inicio del segmento para el SeekHead final. */
        const val SEEK_HEAD_RESERVED = 120
        /** TimestampScale de Matroska: 1_000_000 ns = ticks de 1 ms. */
        const val TIMESTAMP_SCALE_NS = 1_000_000L
    }

    override fun addTrack(track: TrackInfo): Int {
        check(!started) { "no se pueden añadir pistas después de start()" }
        val trackNumber = tracks.size + 1
        require(trackNumber in 1..126) { "Matroska admite como máximo 126 pistas en este muxer" }
        tracks.add(track.withId(trackNumber))
        return trackNumber
    }

    override fun start() {
        check(!started) { "ya iniciado" }
        check(tracks.isNotEmpty()) { "sin pistas" }
        started = true

        cueTrackNumber = (tracks.firstOrNull { it is TrackInfo.Video } ?: tracks.first()).id

        writeEbmlHeader()

        ebml.writeId(MatroskaIds.SEGMENT)
        segmentSizePos = out.position
        ebml.writeUnknownSize(8)
        segmentDataStart = out.position

        seekHeadPos = out.position
        ebml.writeVoid(SEEK_HEAD_RESERVED)

        writeInfo()
        writeTracks()
    }

    private fun writeEbmlHeader() {
        val sizePos = ebml.beginMaster(MatroskaIds.EBML)
        ebml.writeUInt(MatroskaIds.EBML_VERSION, 1)
        ebml.writeUInt(MatroskaIds.EBML_READ_VERSION, 1)
        ebml.writeUInt(MatroskaIds.EBML_MAX_ID_LENGTH, 4)
        ebml.writeUInt(MatroskaIds.EBML_MAX_SIZE_LENGTH, 8)
        ebml.writeString(MatroskaIds.DOCTYPE, "matroska")
        ebml.writeUInt(MatroskaIds.DOCTYPE_VERSION, 4)
        ebml.writeUInt(MatroskaIds.DOCTYPE_READ_VERSION, 2)
        ebml.endMaster(sizePos)
    }

    private fun writeInfo() {
        infoPos = out.position
        val sizePos = ebml.beginMaster(MatroskaIds.INFO)
        val uid = ByteArray(16).also { random.nextBytes(it) }
        ebml.writeElement(MatroskaIds.SEGMENT_UID, uid)
        ebml.writeUInt(MatroskaIds.TIMESTAMP_SCALE, TIMESTAMP_SCALE_NS)
        ebml.writeString(MatroskaIds.MUXING_APP, writingApp)
        ebml.writeString(MatroskaIds.WRITING_APP, writingApp)
        ebml.writeId(MatroskaIds.DURATION)
        ebml.writeVintSize(8)
        durationValuePos = out.position
        out.writeInt64(0.0.toRawBits())
        ebml.endMaster(sizePos)
    }

    private fun writeTracks() {
        tracksPos = out.position
        val sizePos = ebml.beginMaster(MatroskaIds.TRACKS)
        for (track in tracks) writeTrackEntry(track)
        ebml.endMaster(sizePos)
    }

    private fun writeTrackEntry(track: TrackInfo) {
        val entryPos = ebml.beginMaster(MatroskaIds.TRACK_ENTRY)
        ebml.writeUInt(MatroskaIds.TRACK_NUMBER, track.id.toLong())
        ebml.writeUInt(MatroskaIds.TRACK_UID, (random.nextLong().and(Long.MAX_VALUE)).coerceAtLeast(1))
        ebml.writeUInt(MatroskaIds.FLAG_LACING, 0)
        ebml.writeString(MatroskaIds.LANGUAGE, track.language)
        track.name?.let { ebml.writeString(MatroskaIds.NAME, it) }
        when (track) {
            is TrackInfo.Video -> {
                ebml.writeUInt(MatroskaIds.TRACK_TYPE, MatroskaIds.TRACK_TYPE_VIDEO)
                ebml.writeString(MatroskaIds.CODEC_ID, track.codec.matroskaId)
                track.codecPrivate?.let { ebml.writeElement(MatroskaIds.CODEC_PRIVATE, it) }
                if (track.defaultDurationUs > 0) {
                    ebml.writeUInt(MatroskaIds.DEFAULT_DURATION, track.defaultDurationUs * 1000)
                }
                val vPos = ebml.beginMaster(MatroskaIds.VIDEO)
                ebml.writeUInt(MatroskaIds.PIXEL_WIDTH, track.width.toLong())
                ebml.writeUInt(MatroskaIds.PIXEL_HEIGHT, track.height.toLong())
                if (track.displayWidth != track.width || track.displayHeight != track.height) {
                    ebml.writeUInt(MatroskaIds.DISPLAY_WIDTH, track.displayWidth.toLong())
                    ebml.writeUInt(MatroskaIds.DISPLAY_HEIGHT, track.displayHeight.toLong())
                }
                track.color?.let { color ->
                    val cPos = ebml.beginMaster(MatroskaIds.COLOUR)
                    ebml.writeUInt(MatroskaIds.MATRIX_COEFFICIENTS, color.matrix.toLong())
                    ebml.writeUInt(MatroskaIds.COLOUR_RANGE, if (color.fullRange) 2 else 1)
                    ebml.writeUInt(MatroskaIds.TRANSFER_CHARACTERISTICS, color.transfer.toLong())
                    ebml.writeUInt(MatroskaIds.COLOUR_PRIMARIES, color.primaries.toLong())
                    color.hdr?.let { hdr ->
                        if (hdr.maxContentLightLevel > 0) ebml.writeUInt(MatroskaIds.MAX_CLL, hdr.maxContentLightLevel.toLong())
                        if (hdr.maxFrameAverageLightLevel > 0) ebml.writeUInt(MatroskaIds.MAX_FALL, hdr.maxFrameAverageLightLevel.toLong())
                        val mPos = ebml.beginMaster(MatroskaIds.MASTERING_METADATA)
                        ebml.writeFloat(MatroskaIds.PRIMARY_R_X, hdr.redX)
                        ebml.writeFloat(MatroskaIds.PRIMARY_R_Y, hdr.redY)
                        ebml.writeFloat(MatroskaIds.PRIMARY_G_X, hdr.greenX)
                        ebml.writeFloat(MatroskaIds.PRIMARY_G_Y, hdr.greenY)
                        ebml.writeFloat(MatroskaIds.PRIMARY_B_X, hdr.blueX)
                        ebml.writeFloat(MatroskaIds.PRIMARY_B_Y, hdr.blueY)
                        ebml.writeFloat(MatroskaIds.WHITE_POINT_X, hdr.whiteX)
                        ebml.writeFloat(MatroskaIds.WHITE_POINT_Y, hdr.whiteY)
                        ebml.writeFloat(MatroskaIds.LUMINANCE_MAX, hdr.maxMasteringLuminance)
                        ebml.writeFloat(MatroskaIds.LUMINANCE_MIN, hdr.minMasteringLuminance)
                        ebml.endMaster(mPos)
                    }
                    ebml.endMaster(cPos)
                }
                if (track.rotationDegrees != 0) {
                    val pPos = ebml.beginMaster(MatroskaIds.PROJECTION)
                    ebml.writeUInt(MatroskaIds.PROJECTION_TYPE, 0)
                    val roll = when (track.rotationDegrees) {
                        90 -> -90.0
                        180 -> 180.0
                        270 -> 90.0
                        else -> 0.0
                    }
                    ebml.writeFloat(MatroskaIds.PROJECTION_POSE_ROLL, roll)
                    ebml.endMaster(pPos)
                }
                ebml.endMaster(vPos)
            }
            is TrackInfo.Audio -> {
                ebml.writeUInt(MatroskaIds.TRACK_TYPE, MatroskaIds.TRACK_TYPE_AUDIO)
                ebml.writeString(MatroskaIds.CODEC_ID, track.codec.matroskaId)
                track.codecPrivate?.let { ebml.writeElement(MatroskaIds.CODEC_PRIVATE, it) }
                if (track.codec == AudioCodec.OPUS) {
                    val delayNs = track.codecPrivate
                        ?.let { runCatching { OpusConfig.parseOpusHead(it).codecDelayUs * 1000 }.getOrNull() }
                        ?: (track.codecDelayUs * 1000)
                    if (delayNs > 0) ebml.writeUInt(MatroskaIds.CODEC_DELAY, delayNs)
                    ebml.writeUInt(MatroskaIds.SEEK_PRE_ROLL, 80_000_000)
                }
                val aPos = ebml.beginMaster(MatroskaIds.AUDIO)
                ebml.writeFloat(MatroskaIds.SAMPLING_FREQUENCY, track.sampleRate.toDouble())
                ebml.writeUInt(MatroskaIds.CHANNELS, track.channelCount.toLong())
                if (track.bitDepth > 0) ebml.writeUInt(MatroskaIds.BIT_DEPTH, track.bitDepth.toLong())
                ebml.endMaster(aPos)
            }
        }
        ebml.endMaster(entryPos)
    }

    /**
     * Escribe un paquete como `SimpleBlock` dentro del cluster que le corresponda.
     *
     * Tres detalles que parecen de más y no lo son, los tres por la misma razón: **una marca de
     * tiempo negativa no es un error**, es como se expresa el `priming` de un codificador AAC y
     * así llega desde cualquier MP4 escrito por FFmpeg.
     *
     *  - `Math.floorDiv` y no `/`: la división de `Long` trunca hacia cero, así que con un pts
     *    negativo el redondeo se iba al lado contrario que en el resto de la librería y los dos
     *    muxers redondeaban distinto.
     *  - El tiempo del cue se acota a 0 porque `CueTime` es un uint EBML. Un cue negativo
     *    reventaba dentro de `stop()`, con el archivo ya sin SeekHead, sin duración parcheada y
     *    sin tamaño de segmento: ilegible por una muestra que sí se había escrito bien.
     *  - El desfase respecto al cluster va en 16 bits **con signo**, y ahí está el límite real de
     *    lo que este contenedor puede expresar (unos ±32,7 s). Sin la comprobación se truncaba
     *    en silencio y salía un archivo que se abre pero suena descolocado.
     */
    override fun writePacket(packet: MediaPacket) {
        check(started) { "start() no llamado" }
        check(!stopped) { "muxer ya detenido" }
        val track = tracks.getOrNull(packet.trackId - 1)
            ?: throw IllegalArgumentException("pista desconocida ${packet.trackId}")
        val ptsMs = Math.floorDiv(packet.ptsUs + 500, 1000)
        val isVideo = track is TrackInfo.Video
        val isCueTrack = track.id == cueTrackNumber

        val needNewCluster = clusterSizePos < 0 ||
            (isVideo && packet.isKeyFrame) ||
            ptsMs - clusterTimestampMs > maxClusterDurationMs ||
            ptsMs - clusterTimestampMs > Short.MAX_VALUE ||
            ptsMs < clusterTimestampMs + Short.MIN_VALUE

        if (needNewCluster) startCluster(ptsMs)

        if (isCueTrack && packet.isKeyFrame && clusterStartPos != lastCuedClusterPos) {
            cues.add(CueEntry(ptsMs.coerceAtLeast(0), track.id, clusterStartPos - segmentDataStart))
            lastCuedClusterPos = clusterStartPos
        }

        val relative = ptsMs - clusterTimestampMs
        require(relative >= Short.MIN_VALUE && relative <= Short.MAX_VALUE) {
            "el paquete de la pista ${packet.trackId} queda a $relative ms de su cluster, " +
                "fuera del rango [${Short.MIN_VALUE}, ${Short.MAX_VALUE}] de un SimpleBlock " +
                "(pts ${packet.ptsUs} µs); rebasa la línea de tiempo antes de muxear"
        }
        val header = trackNumberVint(track.id)
        ebml.writeId(MatroskaIds.SIMPLE_BLOCK)
        ebml.writeVintSize(header.size.toLong() + 3L + packet.data.size.toLong())
        out.write(header)
        out.writeBits(relative and 0xFFFF, 2)
        out.writeByte(if (packet.isKeyFrame) 0x80 else 0x00)
        out.write(packet.data)

        val durMs = if (packet.durationUs > 0) (packet.durationUs + 500) / 1000 else 0
        maxEndTimestampMs = maxOf(maxEndTimestampMs, ptsMs + durMs)
    }

    private fun trackNumberVint(number: Int): ByteArray {
        require(number in 1..126) { "número de pista $number fuera de rango para vint de 1 byte" }
        return byteArrayOf((0x80 or number).toByte())
    }

    private fun startCluster(timestampMs: Long) {
        closeCluster()
        clusterStartPos = out.position
        clusterTimestampMs = maxOf(0, timestampMs)
        ebml.writeId(MatroskaIds.CLUSTER)
        clusterSizePos = out.position
        ebml.writeUnknownSize(8)
        ebml.writeUInt(MatroskaIds.CLUSTER_TIMESTAMP, clusterTimestampMs)
    }

    private fun closeCluster() {
        if (clusterSizePos >= 0) {
            ebml.endMaster(clusterSizePos)
            clusterSizePos = -1
        }
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        if (!started) {
            out.close()
            return
        }
        try {
            closeCluster()
            writeCues()

            out.patch(
                durationValuePos,
                longToBytes(maxEndTimestampMs.toDouble().toRawBits()),
            )
            writeSeekHead()

            out.patch(segmentSizePos, EbmlWriter.encodeVintSize(out.position - segmentDataStart, 8))
        } finally {
            out.close()
        }
    }

    private fun writeCues() {
        cuesPos = out.position
        val sizePos = ebml.beginMaster(MatroskaIds.CUES)
        for (cue in cues) {
            val pointPos = ebml.beginMaster(MatroskaIds.CUE_POINT)
            ebml.writeUInt(MatroskaIds.CUE_TIME, cue.timeMs)
            val posPos = ebml.beginMaster(MatroskaIds.CUE_TRACK_POSITIONS)
            ebml.writeUInt(MatroskaIds.CUE_TRACK, cue.trackNumber.toLong())
            ebml.writeUInt(MatroskaIds.CUE_CLUSTER_POSITION, cue.clusterPosition)
            ebml.endMaster(posPos)
            ebml.endMaster(pointPos)
        }
        ebml.endMaster(sizePos)
    }

    private fun writeSeekHead() {
        val bytes = ByteArrayOutputStream()

        fun seekEntry(targetId: Long, position: Long): ByteArray {
            val idBytes = when {
                targetId <= 0xFFFF -> 2
                targetId <= 0xFFFFFF -> 3
                else -> 4
            }
            val body = ByteArrayOutputStream()
            body.write(0x53); body.write(0xAB)
            body.write(0x80 or idBytes)
            for (i in idBytes - 1 downTo 0) body.write(((targetId ushr (8 * i)) and 0xFF).toInt())
            body.write(0x53); body.write(0xAC)
            body.write(0x88)
            for (i in 7 downTo 0) body.write(((position ushr (8 * i)) and 0xFF).toInt())
            val entry = ByteArrayOutputStream()
            entry.write(0x4D); entry.write(0xBB)
            entry.write(0x80 or body.size())
            body.writeTo(entry)
            return entry.toByteArray()
        }

        val entries = listOf(
            seekEntry(MatroskaIds.INFO, infoPos - segmentDataStart),
            seekEntry(MatroskaIds.TRACKS, tracksPos - segmentDataStart),
            seekEntry(MatroskaIds.CUES, cuesPos - segmentDataStart),
        )
        val contentSize = entries.sumOf { it.size }
        bytes.write(0x11); bytes.write(0x4D); bytes.write(0x9B); bytes.write(0x74)
        bytes.write(EbmlWriter.encodeVintSize(contentSize.toLong(), 8))
        for (e in entries) bytes.write(e)
        val seekHead = bytes.toByteArray()
        require(seekHead.size <= SEEK_HEAD_RESERVED - 2) { "el SeekHead excede el espacio reservado" }

        val voidSize = SEEK_HEAD_RESERVED - seekHead.size
        out.patch(seekHeadPos, seekHead + EbmlWriter.encodeVoid(voidSize))
    }

    private fun longToBytes(v: Long): ByteArray {
        val b = ByteArray(8)
        for (i in 0 until 8) b[i] = ((v ushr (8 * (7 - i))) and 0xFF).toByte()
        return b
    }

    override fun close() {
        stop()
    }
}
