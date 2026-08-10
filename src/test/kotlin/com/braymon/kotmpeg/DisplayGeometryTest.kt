package com.braymon.kotmpeg

import com.braymon.kotmpeg.codecconfig.NalUnits
import com.braymon.kotmpeg.ebml.MatroskaIds
import com.braymon.kotmpeg.mkv.MkvDemuxer
import com.braymon.kotmpeg.model.AudioCodec
import com.braymon.kotmpeg.model.ContainerFormat
import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import com.braymon.kotmpeg.model.VideoCodec
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **La geometría de presentación**, por sus dos caras, que fallaban por separado:
 *
 *  - Al leer un MKV se ignoraba `DisplayUnit`, que es quien dice si `DisplayWidth`/
 *    `DisplayHeight` son píxeles o una proporción, así que un 16/9 se tomaba por «16 píxeles
 *    por 9» y encima se reescribía como tal al convertir.
 *  - Al leer un MP4 se tiraba el `tkhd`, que es justo el dato equivalente, así que la geometría
 *    no sobrevivía a una ida y vuelta aunque el muxer sí supiera escribirla.
 *
 * Va con ellas la cota de `OutputSamplingFrequency`, que es de audio pero de la misma familia:
 * se aceptaba cualquier valor positivo, y uno imposible tumbaba una pista que se leía bien.
 *
 * Las cabeceras a medida se construyen aquí con [Ebml] porque ningún códec produce estos casos:
 * `DisplayUnit = 1` o una `OutputSamplingFrequency` de 0,5 solo salen de un archivo manipulado o
 * mal escrito, que es exactamente lo que hay que probar. Los casos que **sí** produce una
 * herramienta real van aparte, generados con FFmpeg, y se omiten si esta máquina no lo tiene.
 */
class DisplayGeometryTest {

    @TempDir
    lateinit var dir: File

    private companion object {
        /** Un fotograma de 640x480 al que `ffmpeg -aspect 16:9` le pone una proporción 16/9. */
        const val CODED_W = 640
        const val CODED_H = 480

        /** El 16:9 real de una imagen de 480 de alto: `round(480 * 16 / 9)`. */
        const val DISPLAY_W = 853

        fun run(vararg cmd: String): Int {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText()
            p.waitFor(120, TimeUnit.SECONDS)
            return p.exitValue()
        }
    }

    /**
     * Constructor mínimo de EBML, lo justo para fabricar una cabecera de `Tracks` a medida.
     *
     * El tamaño va siempre en la forma larga de 8 bytes: es válida, la acepta `EbmlReader` y
     * ahorra calcular la longitud mínima, que aquí no aporta nada.
     */
    private object Ebml {
        fun el(id: Long, payload: ByteArray): ByteArray = idBytes(id) + size(payload.size) + payload

        fun uint(id: Long, value: Long): ByteArray {
            var n = 1
            while (n < 8 && (value ushr (8 * n)) != 0L) n++
            return el(id, ByteArray(n) { i -> ((value ushr (8 * (n - 1 - i))) and 0xFF).toByte() })
        }

        fun float(id: Long, value: Double): ByteArray =
            el(id, ByteBuffer.allocate(8).putDouble(value).array())

        fun str(id: Long, value: String): ByteArray = el(id, value.toByteArray(Charsets.US_ASCII))

        private fun idBytes(id: Long): ByteArray {
            var n = 1
            while (n < 4 && (id ushr (8 * n)) != 0L) n++
            return ByteArray(n) { i -> ((id ushr (8 * (n - 1 - i))) and 0xFF).toByte() }
        }

        private fun size(n: Int): ByteArray = ByteArray(8) { i ->
            if (i == 0) 0x01 else ((n.toLong() ushr (8 * (7 - i))) and 0xFF).toByte()
        }
    }

    /**
     * Un MKV con una única pista y sin clusters: basta para leer [Demuxer.tracks], que es lo
     * que estos tests miran, y deja la cabecera bajo control byte a byte.
     */
    private fun headerOnlyMkv(
        name: String,
        trackType: Long,
        codecId: String,
        media: ByteArray,
    ): File {
        val entry = Ebml.el(
            MatroskaIds.TRACK_ENTRY,
            Ebml.uint(MatroskaIds.TRACK_NUMBER, 1) +
                Ebml.uint(MatroskaIds.TRACK_TYPE, trackType) +
                Ebml.str(MatroskaIds.CODEC_ID, codecId) +
                media,
        )
        val bytes = Ebml.el(MatroskaIds.EBML, Ebml.str(MatroskaIds.DOCTYPE, "matroska")) +
            Ebml.el(MatroskaIds.SEGMENT, Ebml.el(MatroskaIds.TRACKS, entry))
        return File(dir, name).also { it.writeBytes(bytes) }
    }

    private fun videoMkv(name: String, vararg videoChildren: ByteArray): File = headerOnlyMkv(
        name, MatroskaIds.TRACK_TYPE_VIDEO, VideoCodec.H264.matroskaId,
        Ebml.el(
            MatroskaIds.VIDEO,
            Ebml.uint(MatroskaIds.PIXEL_WIDTH, CODED_W.toLong()) +
                Ebml.uint(MatroskaIds.PIXEL_HEIGHT, CODED_H.toLong()) +
                videoChildren.fold(ByteArray(0)) { acc, e -> acc + e },
        ),
    )

    private fun audioMkv(name: String, vararg audioChildren: ByteArray): File = headerOnlyMkv(
        name, MatroskaIds.TRACK_TYPE_AUDIO, AudioCodec.AAC.matroskaId,
        Ebml.el(
            MatroskaIds.AUDIO,
            Ebml.uint(MatroskaIds.CHANNELS, 2) +
                audioChildren.fold(ByteArray(0)) { acc, e -> acc + e },
        ),
    )

    private fun readVideo(file: File): TrackInfo.Video =
        MkvKotlin.openDemuxer(file).use { it.tracks.first() as TrackInfo.Video }

    /**
     * **`DisplayUnit` es quien dice si el par de presentación son píxeles.**
     *
     * Matroska no da por hecho que `DisplayWidth`/`DisplayHeight` sean píxeles: 0 (el valor por
     * defecto) dice que sí, 1 y 2 los expresan en centímetros y pulgadas, y 3 los convierte en
     * una **proporción**. Ignorar el elemento hacía que un 16/9 —lo que escribe
     * `ffmpeg -aspect 16:9`— se leyera como una pista que mide 16x9 en pantalla.
     *
     * Los dos primeros casos son el camino que ya funcionaba y que la corrección no puede
     * tocar; el tercero es el fallo; los tres últimos son las unidades sin ninguna medida en
     * píxeles aprovechable, donde lo correcto es caer a las dimensiones codificadas.
     */
    @Test
    fun `the display unit decides whether the display size is in pixels`() {
        val casos = listOf(
            Triple("sin-unit", emptyList<ByteArray>(), DISPLAY_W to CODED_H),
            Triple(
                "unit-0",
                listOf(Ebml.uint(MatroskaIds.DISPLAY_UNIT, 0)),
                DISPLAY_W to CODED_H,
            ),
            Triple(
                "unit-3",
                listOf(Ebml.uint(MatroskaIds.DISPLAY_UNIT, 3)),
                DISPLAY_W to CODED_H,
            ),
            Triple(
                "unit-1-cm",
                listOf(Ebml.uint(MatroskaIds.DISPLAY_UNIT, 1)),
                CODED_W to CODED_H,
            ),
            Triple(
                "unit-2-pulgadas",
                listOf(Ebml.uint(MatroskaIds.DISPLAY_UNIT, 2)),
                CODED_W to CODED_H,
            ),
        )

        for ((nombre, unit, esperado) in casos) {
            val proporcion = nombre == "unit-3" || nombre == "unit-1-cm" || nombre == "unit-2-pulgadas"
            val dw = if (proporcion) 16L else DISPLAY_W.toLong()
            val dh = if (proporcion) 9L else CODED_H.toLong()
            val file = videoMkv(
                "$nombre.mkv",
                Ebml.uint(MatroskaIds.DISPLAY_WIDTH, dw),
                Ebml.uint(MatroskaIds.DISPLAY_HEIGHT, dh),
                *unit.toTypedArray(),
            )
            val track = readVideo(file)
            assertEquals(CODED_W, track.width, "$nombre: el tamaño codificado no se toca")
            assertEquals(CODED_H, track.height, "$nombre: el tamaño codificado no se toca")
            assertEquals(
                esperado.first to esperado.second,
                track.displayWidth to track.displayHeight,
                "$nombre: dimensiones de presentación",
            )
        }
    }

    /**
     * Y una proporción degenerada no deja una pista a medio construir: sin altura codificada no
     * hay nada que derivar, así que se cae al tamaño codificado como con cualquier otra unidad
     * que no sean píxeles.
     */
    @Test
    fun `a degenerate aspect ratio falls back to the coded size`() {
        val file = videoMkv(
            "degenerada.mkv",
            Ebml.uint(MatroskaIds.DISPLAY_WIDTH, 16),
            Ebml.uint(MatroskaIds.DISPLAY_HEIGHT, 0),
            Ebml.uint(MatroskaIds.DISPLAY_UNIT, 3),
        )
        val track = readVideo(file)
        assertEquals(CODED_W to CODED_H, track.displayWidth to track.displayHeight)
    }

    /**
     * **El caso real, de extremo a extremo.** No se fabrica: lo escribe FFmpeg, que es de donde
     * viene. `-aspect 16:9` sobre un fotograma de 640x480 emite `DisplayUnit = 3` con el par
     * `16`/`9`, que es el archivo que describe el hallazgo.
     *
     * Lo que convertía el fallo en permanente era el remux: `MkvMuxer` no emite `DisplayUnit`,
     * así que el `16`/`9` mal leído se reescribía con el valor por defecto —píxeles— y la
     * proporción original desaparecía. Por eso la comprobación no acaba en la lectura.
     */
    @Test
    fun `a foreign mkv with a declared aspect ratio survives a remux in pixels`() {
        val source = File(dir, "anamorfico.mkv")
        assumeTrue(makeAnamorphic(source, "mkv"), "sin FFmpeg")
        assumeTrue(
            headerUInt(source, MatroskaIds.DISPLAY_UNIT) == 3,
            "esta compilación de FFmpeg no emitió DisplayUnit = 3",
        )
        assumeTrue(headerUInt(source, MatroskaIds.DISPLAY_WIDTH) == 16, "el DAR declarado no es 16/9")

        val leido = readVideo(source)
        assertEquals(CODED_W to CODED_H, leido.width to leido.height)
        assertEquals(
            DISPLAY_W to CODED_H, leido.displayWidth to leido.displayHeight,
            "la proporción 16/9 se leyó como si fueran píxeles",
        )

        val aMkv = File(dir, "convertido.mkv")
        MkvKotlin.remux(source, aMkv)
        assertEquals(
            DISPLAY_W to CODED_H, readVideo(aMkv).let { it.displayWidth to it.displayHeight },
            "el MKV de salida heredó el 16x9, y ahí ya no hay vuelta atrás",
        )

        val aMp4 = File(dir, "convertido.mp4")
        MkvKotlin.remux(source, aMp4)
        assertEquals(
            DISPLAY_W to CODED_H, readVideo(aMp4).let { it.displayWidth to it.displayHeight },
            "el tkhd del MP4 de salida declara el tamaño de presentación equivocado",
        )
    }

    /**
     * **Una `OutputSamplingFrequency` imposible no puede llevarse por delante la pista.**
     *
     * La lectura de ese elemento aceptaba cualquier valor positivo. Con `0,5` el valor pisaba
     * una `SamplingFrequency` de 24 000 perfectamente buena, `toInt()` lo dejaba en 0, el
     * `require` de `TrackInfo.Audio` lanzaba y la pista entera se descartaba: una regresión
     * frente a la versión anterior, que ese archivo lo leía sin problema.
     *
     * La cota es que la tasa de salida solo puede **subir** —SBR duplica la del núcleo— y tiene
     * que caber en el rango de una frecuencia real.
     */
    @Test
    fun `a malformed output sampling frequency falls back to the core rate`() {
        val casos = listOf(
            "legitimo-sbr" to (48_000.0 to 48_000),
            "igual-al-nucleo" to (24_000.0 to 24_000),
            "medio-hercio" to (0.5 to 24_000),
            "negativo" to (-1.0 to 24_000),
            "nan" to (Double.NaN to 24_000),
            "astronomico" to (1e30 to 24_000),
        )
        for ((nombre, caso) in casos) {
            val (declarada, esperada) = caso
            val file = audioMkv(
                "$nombre.mkv",
                Ebml.float(MatroskaIds.SAMPLING_FREQUENCY, 24_000.0),
                Ebml.float(MatroskaIds.OUTPUT_SAMPLING_FREQUENCY, declarada),
            )
            val tracks = MkvKotlin.openDemuxer(file).use { it.tracks }
            assertEquals(1, tracks.size, "$nombre: la pista desapareció")
            assertEquals(esperada, (tracks.first() as TrackInfo.Audio).sampleRate, nombre)
        }
    }

    /**
     * Y una `SamplingFrequency` fuera de rango sí descarta la pista, que es la política
     * correcta cuando no queda ningún valor bueno al que caer: al truncar a `Int` esos `1e30`
     * se convierten en `Int.MAX_VALUE`, que envenenaría el `MediaFormat` y el `SampleEntry` de
     * cualquier remux. Se descarta con aviso, no tumbando la lectura del archivo.
     */
    @Test
    fun `an impossible core sample rate discards the track with a warning`() {
        val file = audioMkv(
            "frecuencia-imposible.mkv",
            Ebml.float(MatroskaIds.SAMPLING_FREQUENCY, 1e30),
        )
        val avisos = ArrayList<String>()
        val tracks = MkvDemuxer(file) { avisos += it }.use { it.tracks }
        assertTrue(tracks.isEmpty(), "una frecuencia de 1e30 no puede dar una pista usable")
        assertTrue(avisos.any { it.contains("descartada") }, "y tiene que avisar: $avisos")
    }

    /**
     * **El `tkhd` de un MP4 es su tamaño de presentación, y tiene que volver.**
     *
     * `Mp4Muxer` lo escribe desde `displayWidth`/`displayHeight`, pero `Mp4Demuxer` solo usaba
     * ese par como respaldo del tamaño **codificado** y nunca lo asignaba, así que el dato se
     * perdía en la relectura. Un MP4 con píxeles no cuadrados salía de un `remux()` con su
     * geometría convertida en la codificada, y hacia MKV igual.
     */
    @Test
    fun `the presentation size of an mp4 survives the round trip`() {
        val origen = File(dir, "presentacion.mp4")
        writeVideo(origen, ContainerFormat.MP4, displayWidth = DISPLAY_W, displayHeight = CODED_H)

        val leido = readVideo(origen)
        assertEquals(CODED_W to CODED_H, leido.width to leido.height, "el tamaño codificado")
        assertEquals(
            DISPLAY_W to CODED_H, leido.displayWidth to leido.displayHeight,
            "el tkhd se leyó y se tiró",
        )

        for (destino in listOf("ida-y-vuelta.mp4", "ida-y-vuelta.mkv")) {
            val out = File(dir, destino)
            MkvKotlin.remux(origen, out)
            assertEquals(
                DISPLAY_W to CODED_H, readVideo(out).let { it.displayWidth to it.displayHeight },
                "$destino: la geometría no sobrevivió a la conversión",
            )
        }
    }

    /**
     * **Y una rotación de 90° no da la vuelta a las dimensiones.**
     *
     * Es el riesgo que hay que descartar antes de propagar el `tkhd`: si ese par describiera la
     * imagen ya rotada, tomarlo tal cual invertiría el tamaño de todo vídeo vertical de móvil,
     * que es el caso más común que existe. No lo describe —la especificación dice que es el
     * tamaño previo a la matriz, y así lo escriben FFmpeg y el propio `Mp4Muxer`—, pero el
     * segundo caso comprueba además que un escritor que sí lo hiciera no nos arrastra: cuando
     * el par es exactamente la transpuesta del codificado no aporta nada y se ignora.
     */
    @Test
    fun `a rotated track does not come back with its dimensions swapped`() {
        val vertical = File(dir, "vertical.mp4")
        writeVideo(vertical, ContainerFormat.MP4, rotation = 90)

        val leido = readVideo(vertical)
        assertEquals(90, leido.rotationDegrees)
        assertEquals(
            CODED_W to CODED_H, leido.displayWidth to leido.displayHeight,
            "las dimensiones de presentación salieron invertidas",
        )

        patchTkhdDisplaySize(vertical, CODED_H, CODED_W)
        val parcheado = readVideo(vertical)
        assertEquals(
            CODED_W to CODED_H, parcheado.displayWidth to parcheado.displayHeight,
            "un tkhd transpuesto no aporta nada y no puede invertir la pista",
        )
    }

    /**
     * El caso real de B3, otra vez con un archivo de FFmpeg y no fabricado: `-aspect 16:9` sobre
     * un MP4 escribe `tkhd = 853x480` con un `SampleEntry` de 640x480. Y con una rotación de 90°
     * encima el `tkhd` **no** cambia de orientación, que es la medida que respalda la decisión
     * de propagarlo tal cual.
     */
    @Test
    fun `a foreign mp4 with non square pixels keeps its presentation size`() {
        val plano = File(dir, "anamorfico.mp4")
        assumeTrue(makeAnamorphic(plano, "mp4"), "sin FFmpeg")

        val leido = readVideo(plano)
        assertEquals(CODED_W to CODED_H, leido.width to leido.height)
        assertEquals(DISPLAY_W to CODED_H, leido.displayWidth to leido.displayHeight)

        val rotado = File(dir, "anamorfico-rotado.mp4")
        assumeTrue(
            run(
                "ffmpeg", "-y", "-v", "error", "-display_rotation:v", "90",
                "-i", plano.absolutePath, "-c", "copy", rotado.absolutePath,
            ) == 0 && rotado.length() > 0,
            "esta compilación de FFmpeg no admite -display_rotation",
        )

        val conRotacion = readVideo(rotado)
        assertTrue(
            conRotacion.rotationDegrees == 90 || conRotacion.rotationDegrees == 270,
            "se esperaba un cuarto de vuelta, salió ${conRotacion.rotationDegrees}",
        )
        assertEquals(CODED_W to CODED_H, conRotacion.width to conRotacion.height)
        assertEquals(
            DISPLAY_W to CODED_H, conRotacion.displayWidth to conRotacion.displayHeight,
            "el tkhd es previo a la matriz: una rotación no lo transpone",
        )
    }

    private fun writeVideo(
        target: File,
        format: ContainerFormat,
        displayWidth: Int = CODED_W,
        displayHeight: Int = CODED_H,
        rotation: Int = 0,
    ) {
        val track = TrackInfo.Video(
            codec = VideoCodec.H264, width = CODED_W, height = CODED_H,
            displayWidth = displayWidth, displayHeight = displayHeight,
            frameRate = 30.0, rotationDegrees = rotation,
            codecPrivate = NalUnits.buildAvcC(
                listOf(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22, 0x33)),
                listOf(byteArrayOf(0x68, 0x11, 0x22)),
            ),
        )
        MkvKotlin.createMuxer(target, format).use { muxer ->
            val id = muxer.addTrack(track)
            muxer.start()
            for (i in 0 until 10) {
                muxer.writePacket(
                    MediaPacket(
                        id,
                        NalUnits.joinLengthPrefixed(listOf(ByteArray(24) { (i + it).toByte() })),
                        ptsUs = i * 33_333L, isKeyFrame = i == 0,
                    ),
                )
            }
        }
    }

    /** 640x480 con una proporción 16:9 declarada, en el contenedor que se pida. */
    private fun makeAnamorphic(target: File, ext: String): Boolean {
        val code = runCatching {
            run(
                "ffmpeg", "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=${CODED_W}x$CODED_H:duration=1:rate=10",
                "-c:v", "libx264", "-aspect", "16:9", "-f", if (ext == "mkv") "matroska" else "mp4",
                target.absolutePath,
            )
        }.getOrDefault(1)
        return code == 0 && target.length() > 0
    }

    /**
     * Lee un `uint` de la cabecera de un MKV por su id de dos bytes, o null si no está.
     *
     * Es un barrido, no un parser, así que se acota al tramo anterior al primer `Cluster`: es
     * donde vive `Tracks` y donde estos elementos pueden estar de verdad. Solo se usa para las
     * guardas de `assumeTrue` —comprobar que FFmpeg escribió lo que este test necesita—; las
     * aserciones van siempre contra lo que devuelve el demuxer.
     */
    private fun headerUInt(file: File, id: Long): Int? {
        val b = file.readBytes()
        var end = b.size
        for (i in 0 until b.size - 3) {
            if (b[i] == 0x1F.toByte() && b[i + 1] == 0x43.toByte() &&
                b[i + 2] == 0xB6.toByte() && b[i + 3] == 0x75.toByte()
            ) {
                end = i
                break
            }
        }
        val hi = ((id shr 8) and 0xFF).toByte()
        val lo = (id and 0xFF).toByte()
        for (i in 0 until end - 3) {
            if (b[i] != hi || b[i + 1] != lo) continue
            val marker = b[i + 2].toInt() and 0xFF
            if (marker !in 0x81..0x88) continue
            var v = 0L
            for (k in 0 until marker - 0x80) v = (v shl 8) or (b[i + 3 + k].toLong() and 0xFF)
            return v.toInt()
        }
        return null
    }

    /** Reescribe el par de presentación del `tkhd` (16.16 con signo, al final de la caja). */
    private fun patchTkhdDisplaySize(file: File, width: Int, height: Int) {
        val b = file.readBytes()
        val marca = "tkhd".toByteArray(Charsets.US_ASCII)
        val i = (0 until b.size - 4).first { j -> (0..3).all { b[j + it] == marca[it] } }
        val version = b[i + 4].toInt() and 0xFF
        val off = i + 4 + 4 + (if (version == 1) 16 else 8) + 4 + 4 +
            (if (version == 1) 8 else 4) + 8 + 2 + 2 + 2 + 2 + 36
        ByteBuffer.wrap(b, off, 8).putInt(width shl 16).putInt(height shl 16)
        file.writeBytes(b)
    }
}
