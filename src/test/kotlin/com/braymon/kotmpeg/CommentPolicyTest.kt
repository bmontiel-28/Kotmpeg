package com.braymon.kotmpeg

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hace cumplir la regla de estilo del proyecto: **la documentación del código va en KDoc**.
 *
 * ## Por qué existe
 *
 * La regla se escribió como norma del proyecto y se aplicó a todo el árbol, pero una regla que
 * solo vive en un documento se cumple a medias: la primera pasada movió el KDoc de unos archivos
 * y en otros se limitó a borrar. Este test convierte la política en algo que falla solo, igual
 * que [PublicApiTest] hace con la superficie pública.
 *
 * ## Qué comprueba, y las dos direcciones
 *
 * - **Fuera de [BINARIO], ningún comentario.** Ni de línea ni de bloque. El KDoc sí, claro.
 * - **Dentro de [BINARIO], que no bajen.** Esos ocho archivos serializan o parsean formato byte a
 *   byte y son la única excepción: el nombre del campo de la especificación va en la propia línea
 *   porque no hay declaración a la que colgarlo, y es lo único que ata el código a ISO/IEC
 *   14496-10/12 y a RFC 8794. Se perdieron una vez en una limpieza y hubo que recuperarlos del
 *   historial; el mínimo registrado impide que vuelva a pasar en silencio.
 *
 * Si de verdad desaparece un campo —porque se deja de escribir esa caja—, baja el número de
 * [BINARIO] en el mismo commit. Es una decisión deliberada, que es justo lo que se busca.
 *
 * ## Qué **no** garantiza
 *
 * Un guardián mal entendido acaba dando una falsa sensación de cobertura, así que sus tres
 * límites van escritos aquí y no en un documento aparte:
 *
 * - **Protege contra el borrado, no contra la corrupción.** Cambiar el nombre de un campo por
 *   otro equivocado, o moverlo a la línea de al lado, mantiene la cuenta y pasa en verde. Es
 *   inherente a contar, y contar era lo que resolvía el problema real —que desaparecieran—, así
 *   que el verde se lee como «no han desaparecido» y no como «son correctas».
 * - **Solo recorre este proyecto**, porque [RAICES] es relativo a él. Vigilar el código de quien
 *   consuma la librería ataría este test a cómo estén dispuestos los repositorios en el disco, y
 *   la política es de aquí: quien la quiera, se lleva el test.
 * - **Bajar un número de [BINARIO] no tiene fricción**, y tiene que no tenerla. Eso significa que
 *   en ese caso el guardián vale lo que valga la revisión del commit que lo baje.
 */
class CommentPolicyTest {

    private companion object {
        val RAICES = listOf("src/main/kotlin", "src/test/kotlin")

        /**
         * Los archivos de serialización binaria, con el número de anotaciones que tienen que
         * llevar como mínimo. La ruta es relativa a `src/main/kotlin/com/braymon/kotmpeg/`.
         */
        val BINARIO = mapOf(
            "mp4/Mp4Muxer.kt" to 24,
            "codecconfig/NalUnits.kt" to 17,
            "mp4/Mp4Demuxer.kt" to 10,
            "mp4/FragmentedMp4Muxer.kt" to 9,
            "mp4/SampleEntries.kt" to 6,
            "codecconfig/OpusConfig.kt" to 3,
            "model/ColorInfo.kt" to 1,
            "ebml/EbmlWriter.kt" to 1,
        )

        const val BASE = "src/main/kotlin/com/braymon/kotmpeg/"
    }

    /** Comentarios de un archivo, separados por forma: al final de una línea de código o solos. */
    private class Comentarios(val alFinal: Int, val sueltos: Int) {
        val total: Int get() = alFinal + sueltos
    }

    /**
     * Cuenta los comentarios que **no** son KDoc.
     *
     * Es un recorrido con estado y no una expresión regular a propósito: hay `//` que no son
     * comentarios —dentro de una cadena, o en el ejemplo de uso del KDoc de `MkvKotlin`— y una
     * regexp los contaría. Reconoce cadenas normales y crudas, literales de carácter y bloques
     * anidados.
     */
    private fun contar(fuente: String): Comentarios {
        var alFinal = 0
        var sueltos = 0
        var i = 0
        while (i < fuente.length) {
            val uno = fuente[i]
            val dos = fuente.substring(i, minOf(i + 2, fuente.length))
            val tres = fuente.substring(i, minOf(i + 3, fuente.length))
            when {
                tres == "\"\"\"" -> i = saltarHasta(fuente, i + 3, "\"\"\"")
                uno == '"' -> i = saltarLiteral(fuente, i + 1, '"')
                uno == '\'' -> i = saltarLiteral(fuente, i + 1, '\'')
                tres == "/**" && fuente.getOrNull(i + 3) != '/' -> i = saltarBloque(fuente, i + 3)
                dos == "/*" -> {
                    sueltos++
                    i = saltarBloque(fuente, i + 2)
                }
                dos == "//" -> {
                    val inicioLinea = fuente.lastIndexOf('\n', i - 1) + 1
                    if (fuente.substring(inicioLinea, i).isNotBlank()) alFinal++ else sueltos++
                    while (i < fuente.length && fuente[i] != '\n') i++
                }
                else -> i++
            }
        }
        return Comentarios(alFinal, sueltos)
    }

    private fun saltarHasta(fuente: String, desde: Int, cierre: String): Int {
        val fin = fuente.indexOf(cierre, desde)
        return if (fin < 0) fuente.length else fin + cierre.length
    }

    private fun saltarLiteral(fuente: String, desde: Int, cierre: Char): Int {
        var i = desde
        while (i < fuente.length) {
            when (fuente[i]) {
                '\\' -> i += 2
                cierre -> return i + 1
                '\n' -> return i
                else -> i++
            }
        }
        return i
    }

    private fun saltarBloque(fuente: String, desde: Int): Int {
        var i = desde
        var nivel = 1
        while (i < fuente.length && nivel > 0) {
            when {
                fuente.startsWith("/*", i) -> { nivel++; i += 2 }
                fuente.startsWith("*/", i) -> { nivel--; i += 2 }
                else -> i++
            }
        }
        return i
    }

    private fun fuentes(): List<File> {
        val archivos = RAICES.map(::File).filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
        assertTrue(
            archivos.size > 40,
            "solo se encontraron ${archivos.size} archivos Kotlin; ¿ha cambiado el directorio " +
                "de trabajo de los tests? Se esperan las raíces $RAICES",
        )
        return archivos
    }

    @Test
    fun `only the binary serialization files carry comments`() {
        val permitidos = BINARIO.keys.map { File(BASE + it).invariantSeparatorsPath }.toSet()
        val infractores = fuentes().mapNotNull { archivo ->
            if (archivo.invariantSeparatorsPath in permitidos) return@mapNotNull null
            val c = contar(archivo.readText())
            if (c.total == 0) null else "${archivo.invariantSeparatorsPath}: ${c.total}"
        }
        assertEquals(
            emptyList(), infractores,
            "la documentación del código va en KDoc, no en comentarios. " +
                "Estos archivos llevan comentarios y no son de serialización binaria",
        )
    }

    @Test
    fun `the binary files keep their spec field names`() {
        for ((relativa, minimo) in BINARIO) {
            val archivo = File(BASE + relativa)
            assertTrue(archivo.isFile, "no existe $archivo")
            val c = contar(archivo.readText())
            assertTrue(
                c.alFinal >= minimo,
                "$relativa tiene ${c.alFinal} nombres de campo anotados y debería tener al menos " +
                    "$minimo. Son lo único que ata este código a la especificación: si has " +
                    "quitado un campo de verdad, baja el número en BINARIO en este mismo commit.",
            )
            assertEquals(
                0, c.sueltos,
                "$relativa: la excepción es solo para el nombre del campo **en la propia línea**; " +
                    "un comentario suelto va al KDoc de la declaración que lo contiene",
            )
        }
    }
}
