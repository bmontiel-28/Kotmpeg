package com.braymon.kotmpeg

import org.codehaus.mojo.animal_sniffer.SignatureChecker
import org.codehaus.mojo.animal_sniffer.logging.Logger
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comprueba que el bytecode de la librería solo usa API del JDK que existe en **Android 8.0
 * (API 26)**, el mínimo que se documenta.
 *
 * ## Por qué existe
 *
 * Compilar contra JDK 17 no avisa de nada: el código compila igual aunque llame a algo que en un
 * móvil no está. El caso que lo motivó fue `ByteBuffer.position(int)`: desde JDK 9 enlaza con una
 * sobrecarga covariante que Android no tenía, y `OpusConfig` fallaba con `NoSuchMethodError` en
 * cuanto un móvil abría una pista Opus. Ni la suite ni el CI lo veían, porque corren en una JVM.
 *
 * Se recorre cada clase compilada con animal-sniffer contra la firma oficial de la API 26 y se
 * informa de toda referencia a `java.*`/`javax.*` que no exista allí. Las referencias a la propia
 * librería y a la biblioteca estándar de Kotlin no cuentan: las primeras viajan en el jar y la
 * segunda es una dependencia que Android ya soporta.
 *
 * ## Qué no cubre
 *
 * Solo mira **firmas**, no comportamiento: un método que existe pero se comporta distinto en
 * Android no se detecta. Y no ve las llamadas por reflexión, que esta librería no usa.
 */
class AndroidApiCompatibilityTest {

    private class Collector : Logger {
        val messages = ArrayList<String>()
        override fun info(message: String) = Unit
        override fun info(message: String, t: Throwable) = Unit
        override fun debug(message: String) = Unit
        override fun debug(message: String, t: Throwable) = Unit
        override fun warn(message: String) { messages += message }
        override fun warn(message: String, t: Throwable) { messages += message }
        override fun error(message: String) { messages += message }
        override fun error(message: String, t: Throwable) { messages += message }
    }

    /**
     * Clase dueña de la referencia: lo que va antes del nombre del miembro, o la propia clase si
     * la referencia es a un tipo.
     */
    private fun ownerOf(reference: String): String {
        val withoutArgs = reference.substringBefore('(')
        val name = withoutArgs.substringAfterLast(' ')
        val isMember = '(' in reference || ' ' in reference
        return if (isMember) name.substringBeforeLast('.') else name
    }

    @Test
    fun `the library only uses jdk api available on android 8`() {
        val signature = File(requireNotNull(System.getProperty("kotmpeg.android.signature")) {
            "falta -Dkotmpeg.android.signature; el test se lanza desde Gradle"
        })
        val classDirs = requireNotNull(System.getProperty("kotmpeg.main.classes")) {
            "falta -Dkotmpeg.main.classes; el test se lanza desde Gradle"
        }.split(File.pathSeparator).map(::File).filter { it.isDirectory }
        val classCount = classDirs.sumOf { dir -> dir.walkTopDown().count { it.extension == "class" } }
        assertTrue(classCount > 30, "solo se encontraron $classCount clases compiladas en $classDirs")

        val collector = Collector()
        val checker = signature.inputStream().use { SignatureChecker(it, emptySet(), collector) }
        checker.setSourcePath(emptyList())
        checker.setAnnotationTypes(emptyList())
        classDirs.forEach { checker.process(it) }
        assertTrue(collector.messages.isNotEmpty(), "el comprobador no analizó nada")

        val missing = collector.messages
            .filter { "Undefined reference:" in it }
            .map { message ->
                val reference = message.substringAfter("Undefined reference:").trim()
                val origin = message.substringBefore(':').substringAfterLast("/kotmpeg/")
                reference to origin
            }
            .filter { (reference, _) ->
                ownerOf(reference).let { it.startsWith("java.") || it.startsWith("javax.") }
            }
            .groupBy({ it.first }, { it.second })
            .map { (reference, origins) -> "$reference  <-  ${origins.distinct().joinToString()}" }
            .sorted()

        assertEquals(
            emptyList(), missing,
            "la librería usa API del JDK que no existe en Android 8.0 (API 26). Cada una de estas " +
                "llamadas lanza NoSuchMethodError o NoClassDefFoundError en esos móviles",
        )
    }
}
