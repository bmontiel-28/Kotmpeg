package com.braymon.kotmpeg

import com.braymon.kotmpeg.audio.PcmMixer
import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.io.SeekableOutput
import com.braymon.kotmpeg.model.ColorInfo
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.HdrStaticInfo
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import com.braymon.kotmpeg.mp4.Mp4Demuxer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La cara de contenedor de lo que cubre [HdrDescriptorTest], más la E/S por descriptor.
 *
 * La caja `mdcv` es la ruta hermana del descriptor CTA-861.3 y arrastraba el mismo
 * truncamiento: los dos tienen que cuantizar idéntico o el mismo vídeo describe su HDR de dos
 * formas distintas según quién lo lea.
 *
 * Van con ello la saturación del mezclador PCM, el saneamiento de las cabeceras `largesize`, y
 * el camino de E/S por descriptor, que es lo que permite escribir a MediaStore/SAF sin
 * materializar un archivo intermedio.
 */
class HdrBoxAndDescriptorIoTest {

    @TempDir
    lateinit var dir: File

    private fun hdr() = HdrStaticInfo(
        redX = 0.708, redY = 0.292, greenX = 0.170, greenY = 0.797,
        blueX = 0.131, blueY = 0.046, whiteX = 0.3127, whiteY = 0.3290,
        maxMasteringLuminance = 1000.0, minMasteringLuminance = 0.005,
        maxContentLightLevel = 1000, maxFrameAverageLightLevel = 400,
    )

    /** avcC mínimo pero válido; el mismo que usan el resto de suites. */
    private fun avcC() = NalUnits.buildAvcC(
        listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
        listOf(byteArrayOf(0x68, 0x11, 0x22)),
    )

    /**
     * MP4 con metadata HDR10. Se escribe con H.264 y no con H.265 a propósito: `colr`,
     * `mdcv` y `clli` los emite `SampleEntries` igual para los dos códecs, y con H.264 el
     * fixture no depende de construir un SPS de HEVC completo, que es ruido para lo que
     * este test mira.
     */
    private fun writeHdrMp4(): File {
        val file = File(dir, "hdr.mp4")
        val track = TrackInfo.Video(
            codec = VideoCodec.H264, width = 3840, height = 2160,
            codecPrivate = avcC(),
            color = ColorInfo.hdr10(hdr()),
        )
        MkvKotlin.createMuxer(file).use { muxer ->
            val id = muxer.addTrack(track)
            muxer.start()
            muxer.writePacket(MediaPacket(id, ByteArray(64) { 7 }, 0, 0, true, 33_333))
        }
        return file
    }

    /** Lee un uint16 big-endian, que es como las cajas de MP4 guardan sus campos. */
    private fun be16(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    private fun be32(data: ByteArray, at: Int): Long =
        (be16(data, at).toLong() shl 16) or be16(data, at + 2).toLong()

    /**
     * El fallo: `mdcv` cuantizaba con `.toInt()`, que trunca.
     *
     * El descriptor CTA-861.3 ya se había arreglado, pero la caja del contenedor es otra ruta
     * de serialización y se quedó atrás. En binario
     * `0.708 / 0.00002` da 35 399,99999999999, así que truncando salían 35 399: todo MP4
     * con HDR10 que escribiera esta librería llevaba dos primarias y el punto blanco un
     * paso por debajo. Aquí se leen los bytes reales del archivo, no el modelo.
     */
    @Test
    fun `the mdcv box rounds its chromaticities instead of truncating them`() {
        val bytes = writeHdrMp4().readBytes()
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("mdcv")
        assertTrue(at > 0, "no se encontró la caja mdcv en el archivo")
        val p = at + 4

        assertEquals(8500, be16(bytes, p + 0), "greenX")
        assertEquals(39850, be16(bytes, p + 2), "greenY")
        assertEquals(6550, be16(bytes, p + 4), "blueX")
        assertEquals(2300, be16(bytes, p + 6), "blueY")
        assertEquals(35400, be16(bytes, p + 8), "redX: truncado daría 35399")
        assertEquals(14600, be16(bytes, p + 10), "redY: truncado daría 14599")
        assertEquals(15635, be16(bytes, p + 12), "whiteX: truncado daría 15634")
        assertEquals(16450, be16(bytes, p + 14), "whiteY")

        assertEquals(10_000_000L, be32(bytes, p + 16), "maxMasteringLuminance")
        assertEquals(50L, be32(bytes, p + 20), "minMasteringLuminance")
    }

    /**
     * Las dos rutas de serialización tienen que cuantizar igual.
     *
     * Que divergieran es lo que obligó a arreglar el mismo fallo dos veces, con meses de
     * diferencia. Ahora ambas pasan por `HdrStaticInfo`, y este test lo fija: compara el
     * valor escrito en la caja del contenedor con el del descriptor CTA-861.3, campo a
     * campo, sin depender de las constantes concretas.
     */
    @Test
    fun `the container box and the cta 861 3 descriptor quantize identically`() {
        val bytes = writeHdrMp4().readBytes()
        val p = String(bytes, Charsets.ISO_8859_1).indexOf("mdcv") + 4
        val descriptor = hdr().toStaticMetadataDescriptor()
        fun fromDescriptor(at: Int) =
            (descriptor[at].toInt() and 0xFF) or ((descriptor[at + 1].toInt() and 0xFF) shl 8)

        val boxOffsets = listOf(8, 10, 0, 2, 4, 6, 12, 14)
        for ((i, boxAt) in boxOffsets.withIndex()) {
            assertEquals(
                fromDescriptor(1 + i * 2), be16(bytes, p + boxAt),
                "la cromaticidad $i difiere entre la caja mdcv y el descriptor CTA-861.3",
            )
        }
    }

    /** La luminancia también se redondea: 0,005 cd/m2 son 50 unidades exactas, no 49. */
    @Test
    fun `luminance is rounded to its 0_0001 units`() {
        assertEquals(50L, HdrStaticInfo.luminanceUnits(0.005))
        assertEquals(10_000_000L, HdrStaticInfo.luminanceUnits(1000.0))
        assertEquals(299L, HdrStaticInfo.luminanceUnits(0.0299))
    }

    /**
     * `Math.round(acc)` devuelve un `Long`; estrecharlo con `.toInt()` **envuelve** (se
     * queda con los 32 bits bajos) en vez de saturar, al revés que `Double.toInt()`. El
     * `coerceIn` venía después, así que acotaba el valor ya envuelto.
     *
     * Se elige la ganancia a propósito para que el valor envuelto caiga dentro del rango de
     * 16 bits: así el resultado no es "recorte máximo" sino una muestra pequeña, plausible
     * y completamente inventada. La API pública no valida las ganancias, así que el valor
     * llega tal cual desde la app.
     */
    @Test
    fun `mixing saturates instead of wrapping with a pathological gain`() {
        val gain = (1L shl 32).toDouble() / 32767.0
        val mixed = PcmMixer.mix(listOf(shortArrayOf(32767, -32768)), listOf(gain.toFloat()))

        assertEquals(
            32767, mixed[0],
            "una ganancia enorme sobre el fondo de escala debe saturar en +32767, no dar -4",
        )
        assertNotEquals(
            (-4).toShort(), mixed[0],
            "ese -4 exacto es lo que devolvía el envolvimiento: el fallo estaría de vuelta",
        )
        assertEquals(-32768, mixed[1], "y en -32768 por el otro lado")
    }

    /** El caso normal no cambia: sin ganancias absurdas se sigue redondeando al más cercano. */
    @Test
    fun `normal mixing is unaffected by the saturation fix`() {
        val mixed = PcmMixer.mix(
            listOf(shortArrayOf(100, -100), shortArrayOf(50, 50)),
            listOf(0.5f, 0.5f),
        )
        assertEquals(75, mixed[0])
        assertEquals(-25, mixed[1])
    }

    /**
     * Una caja con `largesize` cuyo `inicio + tamaño` desborda el `Long` no puede colar un
     * `dataEnd` inventado.
     *
     * Merece la pena dejar escrito **por qué** el desbordamiento no era explotable como
     * parecía: para que `inicio + tamaño` dé la vuelta hace falta un tamaño positivo
     * enorme, y el resultado envuelto queda siempre en la zona muy negativa (como mucho
     * `longitudDelArchivo - 2 - 2^63`), nunca de vuelta dentro de la ventana válida. Los
     * tamaños negativos ya los para el `size < headerLen`. Aun así ahora se rechaza en la
     * propia cabecera con `Math.addExact`, para que la propiedad no dependa de que cada
     * llamador se acuerde de revalidar.
     */
    @Test
    fun `a largesize box whose end overflows is rejected cleanly`() {
        val file = writeHdrMp4()
        val bytes = file.readBytes()
        val moovAt = String(bytes, Charsets.ISO_8859_1).lastIndexOf("moov") - 4
        assertTrue(moovAt > 0, "no se encontró la caja moov")

        val broken = File(dir, "overflow.mp4")
        RandomAccessFile(broken, "rw").use { raf ->
            raf.write(bytes)
            raf.seek(moovAt.toLong())
            raf.writeInt(1)
            raf.seek(moovAt.toLong() + 8)
            raf.writeLong(Long.MAX_VALUE - 16)
        }

        val error = assertFailsWith<Exception> { Mp4Demuxer(broken).close() }
        assertTrue(
            error.message.orEmpty().contains("moov"),
            "se esperaba el fallo limpio de moov ausente, y llegó: ${error.message}",
        )
    }

    /**
     * `parseSampleDescription` era el único uso de `readBoxHeader` sin el saneamiento que
     * `scanChildren` aplica a toda caja hija: la entrada de `stsd` se pasaba tal cual a los
     * parsers, que la usan como límite de sus propios recorridos.
     *
     * Se declara una entrada de `stsd` mucho más larga que la caja que la contiene. La
     * pista debe quedarse sin descripción utilizable —y por tanto descartarse— en vez de
     * leer con un límite que se sale de su caja.
     */
    @Test
    fun `an stsd entry that overflows its own box is discarded`() {
        val file = writeHdrMp4()
        val bytes = file.readBytes()
        val stsdAt = String(bytes, Charsets.ISO_8859_1).lastIndexOf("stsd")
        assertTrue(stsdAt > 0, "no se encontró la caja stsd")
        val entrySizeAt = stsdAt + 4 + 4 + 4

        val broken = File(dir, "stsd.mp4")
        RandomAccessFile(broken, "rw").use { raf ->
            raf.write(bytes)
            raf.seek(entrySizeAt.toLong())
            raf.writeInt(0x0FFFFFFF)
        }

        Mp4Demuxer(broken).use { demuxer ->
            assertTrue(
                demuxer.tracks.isEmpty(),
                "una entrada de stsd que se sale de su caja no debe producir una pista",
            )
        }
    }

    /**
     * Escribir y leer por descriptor, que es lo único que expone `MediaStore`/SAF: no hay
     * ruta del sistema de archivos, solo un `ParcelFileDescriptor`. Aquí se usa el
     * `FileDescriptor` de un `RandomAccessFile` para ejercitar el mismo camino en la JVM.
     */
    @Test
    fun `a container can be written and read entirely through a file descriptor`() {
        val file = File(dir, "fd.mkv")
        val track = TrackInfo.Video(
            codec = VideoCodec.H264, width = 640, height = 480, codecPrivate = avcC(),
        )

        RandomAccessFile(file, "rw").use { raf ->
            MkvKotlin.createMuxer(SeekableOutput(raf.fd), ContainerFormat.MKV).use { muxer ->
                val id = muxer.addTrack(track)
                muxer.start()
                for (i in 0 until 5) {
                    muxer.writePacket(
                        MediaPacket(id, ByteArray(32) { i.toByte() }, i * 40_000L, i * 40_000L, true, 40_000),
                    )
                }
            }
        }

        assertTrue(file.length() > 0, "no se escribió nada por el descriptor")

        RandomAccessFile(file, "r").use { raf ->
            MkvKotlin.openDemuxer(SeekableInput(raf.fd)).use { demuxer ->
                assertEquals(1, demuxer.tracks.size)
                var count = 0
                while (demuxer.readPacket() != null) count++
                assertEquals(5, count, "se perdieron paquetes leyendo por descriptor")
            }
        }
    }

    /**
     * La detección de formato sobre un `SeekableInput` deja la posición donde estaba, o el
     * demuxer que viene detrás empezaría a parsear 12 bytes más allá de la cabecera.
     */
    @Test
    fun `detecting the format through a descriptor does not consume the header`() {
        val file = writeHdrMp4()
        RandomAccessFile(file, "r").use { raf ->
            MkvKotlin.openDemuxer(SeekableInput(raf.fd)).use { demuxer ->
                assertEquals(1, demuxer.tracks.size, "el demuxer no pudo parsear desde el principio")
            }
        }
    }

    /**
     * Una entrada que no es ni MKV ni MP4 se cierra antes de lanzar: quien pasa un
     * `SeekableInput` ya no tiene otra referencia con la que recuperar el descriptor.
     *
     * Se cuentan descriptores en `/proc/self/fd` y no se intenta leer de la entrada
     * cerrada: `SeekableInput` tiene 64 KiB de buffer, así que un archivo pequeño ya está
     * entero en memoria tras la detección y la lectura respondería sin tocar el canal.
     */
    @Test
    fun `an unrecognised descriptor input is closed before throwing`() {
        val file = File(dir, "junk.bin").also { it.writeBytes(ByteArray(64) { 0x5A }) }
        val before = openDescriptorsFor(file)

        val input = SeekableInput(file)
        assertEquals(before + 1, openDescriptorsFor(file), "no llegó a abrirse el archivo")
        assertFailsWith<IllegalArgumentException> { MkvKotlin.openDemuxer(input) }

        assertEquals(
            before, openDescriptorsFor(file),
            "openDemuxer dejó el descriptor abierto al no reconocer el contenedor",
        )
    }

    /** Cuenta descriptores abiertos sobre [file]; solo medible en Linux. */
    private fun openDescriptorsFor(file: File): Int {
        val fdDir = File("/proc/self/fd")
        assumeTrue(fdDir.isDirectory, "solo medible en Linux")
        System.gc()
        val target = file.canonicalPath
        return fdDir.listFiles().orEmpty().count { fd ->
            runCatching { fd.canonicalPath }.getOrNull() == target
        }
    }

    /** Un archivo demasiado corto para tener cabecera no se confunde con ningún formato. */
    @Test
    fun `a file too short to hold a header is not detected as a container`() {
        val file = File(dir, "tiny.bin").also { it.writeBytes(ByteArray(4)) }
        assertNull(MkvKotlin.detectFormat(file))
    }
}
