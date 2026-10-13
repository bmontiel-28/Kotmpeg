import com.braymon.kotmpeg.MkvKotlin
import com.braymon.kotmpeg.audio.PcmMixer
import com.braymon.kotmpeg.audio.PcmResampler
import com.braymon.kotmpeg.audio.PcmStreamMixer
import com.braymon.kotmpeg.codecconfig.AacConfig
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import com.braymon.kotmpeg.mp4.Mp4Segment
import java.io.File
import kotlin.system.exitProcess

/**
 * Graba pantalla, micrófono y sistema en los tres contenedores, con la mezcla y las dos pistas por
 * separado, más un fMP4 con índice `sidx` y aviso de segmentos, y lo relee. Sale con código distinto
 * de cero si algo no cuadra.
 */
fun main() {
    val directorio = File("build/salidas").apply { mkdirs() }
    val avcC = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )
    val remuestreador = PcmResampler(inputRate = 44_100, outputRate = 48_000, channels = 1)
    val microfono = PcmMixer.convertChannels(remuestreador.resample(ShortArray(441) { 1_000 }), from = 1, to = 2)
    val mezcla = PcmMixer.mix(listOf(ShortArray(960) { 2_000 }, microfono))
    if (mezcla.first().toInt() != 3_000) {
        println("FALLO: la mezcla dio ${mezcla.first()}")
        exitProcess(1)
    }

    val enVivo = PcmStreamMixer(outputRate = 48_000, outputChannels = 2)
    val micEnVivo = enVivo.addSource(sampleRate = 44_100, channels = 1)
    val sistemaEnVivo = enVivo.addSource(sampleRate = 48_000, channels = 2)
    enVivo.write(micEnVivo, ShortArray(441) { 1_000 })
    enVivo.write(sistemaEnVivo, ShortArray(960) { 2_000 })
    val mezclaEnVivo = enVivo.read() + enVivo.flush()
    if (mezclaEnVivo.size != 960 || mezclaEnVivo.any { it.toInt() != 3_000 }) {
        println("FALLO: la mezcla en streaming dio ${mezclaEnVivo.size} muestras, primera ${mezclaEnVivo.firstOrNull()}")
        exitProcess(1)
    }

    val segmentos = ArrayList<Mp4Segment>()
    val segmentado = FragmentedMp4Muxer(File(directorio, "consumidor-segmentado.mp4")).apply {
        segmentIndex = true
        onSegment = { segmentos += it }
    }
    val salidas = listOf(
        File(directorio, "consumidor.mkv") to MkvKotlin.createMuxer(File(directorio, "consumidor.mkv")),
        File(directorio, "consumidor.mp4") to MkvKotlin.createMuxer(File(directorio, "consumidor.mp4")),
        File(directorio, "consumidor-frag.mp4") to MkvKotlin.createFragmentedMp4Muxer(File(directorio, "consumidor-frag.mp4")),
        File(directorio, "consumidor-segmentado.mp4") to segmentado,
    )
    for ((archivo, creado) in salidas) {
        val muxer = MkvKotlin.synchronizedMuxer(creado)
        val pantalla = muxer.addTrack(TrackInfo.Video(codec = VideoCodec.H264, width = 1080, height = 2400, codecPrivate = avcC))
        val pistas = listOf("Mezcla", "Micrófono", "Audio del sistema").mapIndexed { indice, nombre ->
            muxer.addTrack(
                TrackInfo.Audio(
                    codec = AudioCodec.AAC, sampleRate = 48_000, channelCount = 2,
                    codecPrivate = AacConfig.build(48_000, 2), name = nombre, default = indice == 0,
                ),
            )
        }
        muxer.start()
        for (fotograma in 0 until 30) {
            val pts = fotograma * 33_333L
            muxer.writePacket(MediaPacket(pantalla, NalUnits.joinLengthPrefixed(listOf(byteArrayOf(0x65, 1))), pts, isKeyFrame = fotograma == 0))
            for (pista in pistas) muxer.writePacket(MediaPacket(pista, ByteArray(8), pts))
        }
        muxer.stop()

        MkvKotlin.openDemuxer(archivo).use { lector ->
            val nombres = lector.tracks.filterIsInstance<TrackInfo.Audio>().map { it.name }
            var paquetes = 0
            while (lector.readPacket() != null) paquetes++
            if (nombres != listOf("Mezcla", "Micrófono", "Audio del sistema") || paquetes != 120) {
                println("FALLO en ${archivo.name}: pistas $nombres, paquetes $paquetes")
                exitProcess(1)
            }
        }
        println("OK ${archivo.name}")
    }
    if (segmentos.size != 1 || segmentos[0].offset != segmentado.initSegmentSize) {
        println("FALLO: segmentos avisados $segmentos, cabecera de ${segmentado.initSegmentSize} bytes")
        exitProcess(1)
    }
    println("OK: compilado con Kotlin 1.9; biblioteca estándar en ejecución ${KotlinVersion.CURRENT}")
}
