package com.braymon.kotmpeg

import com.braymon.kotmpeg.ebml.EbmlElement
import com.braymon.kotmpeg.ebml.EbmlException
import com.braymon.kotmpeg.ebml.EbmlReader
import com.braymon.kotmpeg.io.SeekableInput
import com.braymon.kotmpeg.model.HdrStaticInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * La serialización de metadatos HDR al descriptor CTA-861.3, y la cota de `readPayload`.
 *
 * Los campos del descriptor son de 16 bits, mucho más estrechos que los del contenedor: un
 * valor perfectamente legal arriba se envolvía en silencio al bajarlo aquí. La cota de EBML
 * frente a cargas de más de 2 GiB era correcta, pero no tenía nada que la respaldara.
 *
 * La cara de contenedor de lo mismo está en [HdrBoxAndDescriptorIoTest]: los dos caminos tienen
 * que cuantizar idéntico o el mismo vídeo describe su HDR de dos formas distintas.
 */
class HdrDescriptorTest {

    @TempDir
    lateinit var dir: File

    /** Lee un uint16 little-endian, que es como CTA-861.3 guarda todos sus campos. */
    private fun le16(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)

    private fun hdr(
        max: Double = 1000.0,
        min: Double = 0.005,
        maxCll: Int = 1000,
        maxFall: Int = 400,
    ) = HdrStaticInfo(
        redX = 0.708, redY = 0.292, greenX = 0.170, greenY = 0.797,
        blueX = 0.131, blueY = 0.046, whiteX = 0.3127, whiteY = 0.3290,
        maxMasteringLuminance = max, minMasteringLuminance = min,
        maxContentLightLevel = maxCll, maxFrameAverageLightLevel = maxFall,
    )

    @Test
    fun `the cta 861 3 descriptor has the layout and units the spec asks for`() {
        val d = hdr().toStaticMetadataDescriptor()

        assertEquals(25, d.size, "el descriptor son 25 bytes: id + 12 campos de 16 bits")
        assertEquals(0, d[0].toInt(), "el primer byte es el id de descriptor, siempre 0")

        assertEquals(35400, le16(d, 1), "redX")
        assertEquals(14600, le16(d, 3), "redY")
        assertEquals(8500, le16(d, 5), "greenX")
        assertEquals(39850, le16(d, 7), "greenY")
        assertEquals(6550, le16(d, 9), "blueX")
        assertEquals(2300, le16(d, 11), "blueY")
        assertEquals(15635, le16(d, 13), "whiteX")
        assertEquals(16450, le16(d, 15), "whiteY")

        assertEquals(1000, le16(d, 17), "maxMasteringLuminance en cd/m2")
        assertEquals(50, le16(d, 19), "minMasteringLuminance en pasos de 0,0001")

        assertEquals(1000, le16(d, 21), "MaxCLL")
        assertEquals(400, le16(d, 23), "MaxFALL")
    }

    /**
     * Las cromaticidades se redondean, no se truncan.
     *
     * `0.708 / 0.00002` da 35399,99999999999 en coma flotante: truncando salía 35399, un paso
     * por debajo del valor correcto, y lo mismo con el punto blanco D65. Es un error pequeño
     * pero sistemático, y se cuela en el bitstream sin que nada lo señale.
     */
    @Test
    fun `chromaticities are rounded, not truncated`() {
        val d = hdr().toStaticMetadataDescriptor()
        assertEquals(35400, le16(d, 1), "redX truncado daría 35399")
        assertEquals(15635, le16(d, 13), "whiteX truncado daría 15634")
    }

    /**
     * El fallo: un valor legal para el contenedor puede no serlo para CTA-861.3, y ahí se
     * saturaba mal.
     *
     * `mdcv` guarda la luminancia en un uint32 de pasos de 0,0001 cd/m2 (hasta ~429 496
     * cd/m2), pero CTA-861.3 solo tiene 16 bits. Con `toShort()` a secas, 100 000 cd/m2
     * salían como 34 464: no un valor recortado, sino uno **arbitrario y más bajo que el
     * original**, que es lo peor que puede pasar en unos metadatos de masterización.
     */
    @Test
    fun `luminance beyond the 16 bit fields saturates instead of wrapping`() {
        val tooBright = hdr(max = 100_000.0)
        assertTrue(
            tooBright.maxMasteringLuminance <= HdrStaticInfo.MAX_MASTERING_LUMINANCE,
            "el valor debe ser legal para el contenedor: si no, el test no prueba nada",
        )
        assertEquals(
            0xFFFF, le16(tooBright.toStaticMetadataDescriptor(), 17),
            "debería saturar al máximo del campo, no envolverse a 34 464",
        )

        val highFloor = hdr(max = 1000.0, min = 10.0)
        assertTrue(highFloor.minMasteringLuminance > HdrStaticInfo.CTA861_MIN_MASTERING_LUMINANCE)
        assertEquals(
            0xFFFF, le16(highFloor.toStaticMetadataDescriptor(), 19),
            "el mínimo también debe saturar",
        )

        val atLimit = hdr(max = HdrStaticInfo.CTA861_MAX_MASTERING_LUMINANCE, min = 0.005)
        assertEquals(0xFFFF, le16(atLimit.toStaticMetadataDescriptor(), 17))
        val justBelow = hdr(max = 65_534.0, min = 0.005)
        assertEquals(65_534, le16(justBelow.toStaticMetadataDescriptor(), 17))
    }

    /**
     * Los campos de cromaticidad usan los 16 bits completos como valor sin signo: 39 850 y
     * 35 400 no caben en un `Short` con signo, así que lo que se comprueba aquí es que el
     * patrón de bits escrito se relea como el número correcto y no como un negativo.
     */
    @Test
    fun `chromaticity fields use the full unsigned 16 bit range`() {
        val d = hdr().toStaticMetadataDescriptor()
        for (offset in listOf(1, 7)) {
            assertTrue(le16(d, offset) > Short.MAX_VALUE, "el campo en $offset debe pasar de 32 767")
        }
    }

    /**
     * Guard de `readPayload`: un tamaño declarado por encima de `Int.MAX_VALUE` se rechaza
     * **antes** de intentar la lectura.
     *
     * El bug histórico era truncar ese `Long` a `Int`: el tamaño salía negativo o pequeño, se
     * leían muchos menos bytes de los declarados y el parser seguía adelante desincronizado,
     * sin ningún error inmediato que apuntara a la causa. Se prueba contra el lector directo
     * porque construir un MKV que llegue a esa rama por el camino normal exigiría un archivo
     * de 2 GiB.
     */
    @Test
    fun `an ebml payload larger than two gigabytes is rejected before reading it`() {
        val file = File(dir, "payload.bin").also { it.writeBytes(ByteArray(256)) }
        SeekableInput(file).use { input ->
            val reader = EbmlReader(input)

            val tooBig = EbmlElement(id = 0x63A2, size = Int.MAX_VALUE.toLong() + 1, dataStart = 0)
            val error = assertFailsWith<EbmlException> { reader.readBinary(tooBig) }
            assertTrue(
                error.message.orEmpty().contains("demasiado grande"),
                "se esperaba el guard de tamaño, y llegó: ${error.message}",
            )

            val beyondEof = EbmlElement(id = 0x63A2, size = 1024, dataStart = 0)
            val eofError = assertFailsWith<EbmlException> { reader.readBinary(beyondEof) }
            assertTrue(
                eofError.message.orEmpty().contains("excede el final"),
                "se esperaba el guard de fin de archivo, y llegó: ${eofError.message}",
            )

            val fine = EbmlElement(id = 0x63A2, size = 128, dataStart = 0)
            assertEquals(128, reader.readBinary(fine).size)
        }
    }

    /** Un tamaño desconocido (-1) no tiene carga legible; debe decirlo, no devolver vacío. */
    @Test
    fun `an unknown size element has no readable payload`() {
        val file = File(dir, "unknown.bin").also { it.writeBytes(ByteArray(64)) }
        SeekableInput(file).use { input ->
            val reader = EbmlReader(input)
            val unknown = EbmlElement(id = 0x1F43B675, size = -1, dataStart = 0)
            assertFailsWith<EbmlException> { reader.readBinary(unknown) }
        }
    }
}
