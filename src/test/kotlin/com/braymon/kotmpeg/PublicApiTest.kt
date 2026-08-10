package com.braymon.kotmpeg

import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Congela la **API pública** de la librería en un archivo versionado y falla si cambia.
 *
 * ## Por qué existe, y por qué no es `binary-compatibility-validator`
 *
 * Con `explicitApi()` activo la visibilidad es explícita, pero nada avisaba de que una firma
 * cambiara. Y eso importa más de lo que parece: en Kotlin, **añadir un parámetro con valor por
 * defecto es compatible en código fuente pero no a nivel binario** —cambia la firma JVM del
 * método—, así que quien haya compilado contra una versión anterior recibe un `NoSuchMethodError`
 * en tiempo de ejecución. Un parámetro opcional añadido a `remux` o a `concat` sale gratis mientras
 * no hay nada publicado, y es una rotura silenciosa en cuanto exista un tag.
 *
 * La herramienta habitual para esto es `binary-compatibility-validator`. Este test hace el mismo
 * trabajo sin dependencias y con el volcado en un archivo legible, que es lo que permite juzgar un
 * cambio de API leyendo el diff en la revisión en vez de un formato que solo entiende una
 * herramienta. Si la API cambia, el test falla y el diff de [SNAPSHOT] queda a la vista.
 *
 * ## Qué cubre exactamente, y qué no
 *
 * Lo que se congela es la **superficie visible desde el bytecode**, que no es idéntica a la API
 * pública de Kotlin. Tres diferencias que conviene conocer para no sorprenderse con el diff:
 *
 * - **Las clases `internal` aparecen.** Kotlin las emite como públicas en el bytecode y la
 *   reflexión no puede distinguirlas de las de verdad públicas, así que figuran en el volcado.
 *   Consecuencia práctica: un refactor interno también mueve el volcado, y regenerarlo entonces
 *   es legítimo. Se prefiere esto a mantener una lista de exclusiones, que podría **ocultar** una
 *   clase el día que pase a ser pública de verdad — un falso negativo es mucho peor que un poco
 *   de ruido.
 * - **Los miembros `internal` no aparecen**, porque su nombre sí lleva marca: el compilador les
 *   añade el sufijo del módulo, y [isMangledMember] los descarta.
 * - **Los puentes `$default` sí aparecen**, aunque el compilador los marque como sintéticos. Son
 *   API binaria de verdad; el porqué está en [publicApi].
 *
 * ## Lo que este volcado **no** detecta
 *
 * Vale más tenerlo escrito que descubrirlo tarde, porque el riesgo de un guardián no es que falle:
 * es que alguien lea el verde como "la API no se movió". Tres cambios rompen a un consumidor y
 * dejan el volcado idéntico:
 *
 * - **Los supertipos no se registran.** La línea es `class com.braymon.kotmpeg.mkv.MkvMuxer`, sin
 *   su `: Muxer`. Si un tipo público dejara de implementar una interfaz pública, sus métodos
 *   seguirían declarados donde están y el volcado no se movería, pero quien lo asigne a `Muxer`
 *   deja de compilar.
 * - **Los modificadores tampoco.** Pasar una clase o un método de `open` a `final` rompe a quien
 *   herede y aquí es invisible.
 * - **La nulabilidad no existe a este nivel.** `String` y `String?` son el mismo
 *   `java.lang.String`. Cambiar un parámetro de `String?` a `String` es compatible en binario pero
 *   rompe en fuente a quien pasara `null`.
 *
 * Los tres son bastante menos probables que el caso de los valores por defecto —que ya se
 * materializó una vez, con `onWarning`— y cubrirlos sería reimplementar a mano
 * `binary-compatibility-validator`, que es justo lo que aquí no se puede usar. Se documentan en
 * lugar de implementarse; si algún día se tocan, que sea con la lista delante.
 *
 * ## Cómo actualizarlo cuando el cambio es intencionado
 *
 * Ejecuta el test con `-Dkotmpeg.api.update=true`, que reescribe el archivo, y **revisa el diff**:
 *
 * ```
 * ./gradlew test --tests "*PublicApiTest*" -Dkotmpeg.api.update=true
 * ```
 *
 * Un cambio que solo añade miembros es compatible hacia atrás. Uno que quita o modifica una firma
 * existente **no** lo es, y va declarado como `Cambios incompatibles` en el CHANGELOG.
 */
class PublicApiTest {

    private companion object {
        /** Volcado versionado. Vive en `src/test/resources` para que viaje con el test. */
        const val SNAPSHOT = "public-api.txt"
        const val UPDATE_FLAG = "kotmpeg.api.update"

        /**
         * Para **miembros**, un `$` en el nombre significa mangling de `internal`
         * (`algo$Kotmpeg_debug`) o algo que generó el compilador. Nada de eso es superficie que
         * nadie escriba a mano.
         *
         * Con una excepción, la de [DEFAULT_BRIDGE], que se resuelve en [publicApi] quitando el
         * sufijo antes de preguntar aquí: así `openDemuxer$default` entra y
         * `algo$Kotmpeg_debug$default` —el puente de una función `internal`— sigue fuera, sin
         * tener que escribir el nombre del módulo en ningún sitio.
         */
        fun isMangledMember(name: String) = '$' in name

        /**
         * Sufijo del puente que el compilador emite por cada función con valores por defecto.
         */
        const val DEFAULT_BRIDGE = "\$default"

        /**
         * Para **clases** el `$` significa otra cosa: es el separador de anidamiento, y
         * `TrackInfo$Video` sí es API pública —de hecho es la más usada de la librería—. Aplicar
         * aquí el mismo filtro que a los miembros dejaba fuera del volcado, **en silencio**, todo
         * el modelo anidado: `TrackInfo.Video/Audio` y cada `Companion` con sus factorías
         * (`ColorInfo.bt709()`, `VideoCodec.fromMatroskaId()`...).
         * Un volcado en verde daba a entender que la API entera estaba congelada cuando faltaba
         * buena parte.
         *
         * Lo que sí hay que descartar es lo que genera el compilador: la tabla de saltos de un
         * `when` sobre enum, los cuerpos por defecto de una interfaz, y las clases anónimas, que
         * se numeran.
         */
        fun isGeneratedClass(binaryName: String): Boolean {
            val last = binaryName.substringAfterLast('$')
            return last == "WhenMappings" ||
                last == "DefaultImpls" ||
                last.toIntOrNull() != null ||
                "$$" in binaryName
        }

        /**
         * Quita los prefijos de paquete de un nombre de tipo dejando los argumentos genéricos.
         *
         * `parameterTypes` da el tipo **borrado**, así que `(String) -> Unit` y
         * `(Throwable) -> Unit` eran los dos `Function1` y un cambio entre ellos no movía el
         * volcado —pese a romper a quien llamara—. Con `genericParameterTypes` el nombre llega
         * completo (`kotlin.jvm.functions.Function1<? super java.lang.String, kotlin.Unit>`) y solo
         * hay que acortarlo para que siga siendo legible.
         */
        fun shortTypeName(typeName: String): String =
            typeName.replace(Regex("""\w+(\.\w+)*\."""), "")
    }

    /**
     * Rutas de los `.class` del módulo. Con `kotlin("jvm")` llegan como directorio, pero se
     * soportan también dentro de un jar: hay plugins que entregan las clases al classpath de test
     * ya empaquetadas, y sostener las dos formas cuesta un `when`.
     */
    private fun classFiles(): List<String> {
        val source = MkvKotlin::class.java.protectionDomain?.codeSource
            ?: error("no se pudo localizar las clases compiladas del módulo")
        val location = File(source.location.toURI())
        val prefix = "com/braymon/kotmpeg/"
        val entries = when {
            location.isDirectory -> location.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(location).invariantSeparatorsPath }
                .toList()
            location.isFile -> java.util.jar.JarFile(location).use { jar ->
                jar.entries().asSequence().filter { it.name.endsWith(".class") }.map { it.name }.toList()
            }
            else -> error("no se encontraron las clases del módulo en $location")
        }
        val own = entries.filter { it.startsWith(prefix) }.sorted()
        assertTrue(own.isNotEmpty(), "no se encontró ninguna clase de la librería en $location")
        return own
    }

    /**
     * Vuelca la superficie pública leyendo el bytecode compilado, una línea por miembro.
     *
     * Dos decisiones sostienen que el volcado sea **estable** y que sirva de guardián:
     *
     *  - Se recorren los miembros `declared` y no todos: lo heredado ya se declara en su propio
     *    tipo, y repetirlo haría que el volcado cambiara solo por reordenar la jerarquía. Y se
     *    ordena por la misma cadena que se imprime, no por el tipo borrado: dos sobrecargas que
     *    solo difieran en sus argumentos genéricos compartirían clave y el desempate quedaría en
     *    manos de `getDeclaredMethods()`, que la JVM no garantiza — un volcado que cambia entre
     *    compilaciones es un test intermitente, que es la peor forma de fallar porque enseña a
     *    ignorarlo.
     *  - Los puentes [DEFAULT_BRIDGE] **se incluyen** pese a estar marcados como sintéticos,
     *    exceptuándolos de los dos filtros. Son API binaria de verdad: quien omite un argumento
     *    por defecto desde otro módulo invoca el puente y no el método real, así que quitarle el
     *    valor por defecto a un parámetro no mueve la firma real ni un milímetro y sin esta
     *    excepción el guardián pasaba en verde ante un cambio que da `NoSuchMethodError` a todo
     *    consumidor. Se pregunta por el nombre base, sin el sufijo, para que el puente de una
     *    función `internal` siga fuera (ver [isMangledMember]).
     */
    private fun publicApi(): List<String> {
        val lines = ArrayList<String>()

        classFiles()
            .forEach { relative ->
                val binaryName = relative.removeSuffix(".class").replace('/', '.')
                if (isGeneratedClass(binaryName)) return@forEach
                val loader = javaClass.classLoader
                val type = runCatching { Class.forName(binaryName, false, loader) }
                    .getOrNull() ?: return@forEach
                if (type.isSynthetic || type.isAnonymousClass || type.isLocalClass) return@forEach
                if (!Modifier.isPublic(type.modifiers)) return@forEach

                val kind = when {
                    type.isInterface -> "interface"
                    type.isEnum -> "enum"
                    type.isAnnotation -> "annotation"
                    else -> "class"
                }
                lines.add("$kind $binaryName")

                for (c in type.declaredConstructors.sortedBy { c -> c.genericParameterTypes.joinToString { it.typeName } }) {
                    if (c.isSynthetic || !isVisible(c.modifiers)) continue
                    val params = c.genericParameterTypes.joinToString(", ") { shortTypeName(it.typeName) }
                    lines.add("  constructor($params)")
                }
                for (m in type.declaredMethods.sortedBy { m -> m.name + m.genericParameterTypes.joinToString { it.typeName } }) {
                    val defaultBridge = m.name.endsWith(DEFAULT_BRIDGE)
                    if (m.isBridge || !isVisible(m.modifiers)) continue
                    if (!defaultBridge && m.isSynthetic) continue
                    if (isMangledMember(m.name.removeSuffix(DEFAULT_BRIDGE))) continue
                    val params = m.genericParameterTypes.joinToString(", ") { shortTypeName(it.typeName) }
                    lines.add("  fun ${m.name}($params): ${shortTypeName(m.genericReturnType.typeName)}")
                }
                for (f in type.declaredFields.sortedBy { it.name }) {
                    if (f.isSynthetic || !isVisible(f.modifiers) || isMangledMember(f.name)) continue
                    lines.add("  field ${f.name}: ${shortTypeName(f.genericType.typeName)}")
                }
            }
        return lines
    }

    private fun isVisible(modifiers: Int) =
        Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)

    /**
     * El volcado tiene que **cubrir** lo que dice cubrir.
     *
     * El fallo que motiva este test no fue que el volcado estuviera mal, sino que estaba
     * incompleto **sin decirlo**: un heurístico demasiado amplio dejaba fuera todas las clases
     * anidadas, así que el guardián pasaba en verde mientras `TrackInfo.Video` y todo
     * `TrackAction` podían cambiar libremente. Un guardián que se encoge en silencio es peor que
     * no tenerlo, porque da una tranquilidad que no corresponde.
     *
     * Estos anclajes son la superficie más usada de la librería. Si un cambio en los filtros
     * vuelve a excluirlos, este test lo dice en vez de dejar que el otro pase de largo.
     */
    @Test
    fun `the snapshot covers the nested types that make up most of the api`() {
        val api = publicApi().joinToString("\n")
        val required = listOf(
            "class com.braymon.kotmpeg.model.TrackInfo\$Video",
            "class com.braymon.kotmpeg.model.TrackInfo\$Audio",
            "com.braymon.kotmpeg.model.ColorInfo\$Companion",
            "com.braymon.kotmpeg.model.VideoCodec",
            "com.braymon.kotmpeg.model.AudioCodec",
        )
        for (entry in required) {
            assertTrue(entry in api, "el volcado de API no cubre `$entry`")
        }
        for (member in listOf("bt709", "hdr10", "fromMatroskaId", "fromMimeType")) {
            assertTrue(member in api, "el volcado de API no cubre el miembro `$member`")
        }
    }

    /**
     * Y el volcado tiene que cubrir también los puentes de los valores por defecto.
     *
     * Mismo espíritu que el test de arriba, y por el mismo motivo: el filtro que los deja entrar
     * depende del **orden** de dos `if` —el puente está marcado como sintético, así que basta con
     * comprobar `isSynthetic` antes de tiempo para que la excepción no llegue a aplicarse— y eso
     * se rompe sin darse cuenta al reordenar. Un guardián cuyo alcance encoge en silencio es peor
     * que no tenerlo.
     *
     * Los anclajes son la superficie principal de la librería: si `MkvKotlin.remux` pierde un
     * valor por defecto, esto lo dice.
     */
    @Test
    fun `the snapshot covers the default argument bridges`() {
        val lines = publicApi()
        val api = lines.joinToString("\n")
        for (bridge in listOf(
            "fun openDemuxer\$default(",
            "fun remux\$default(",
            "fun concat\$default(",
            "fun createMuxer\$default(",
            "fun copy\$default(",
        )) {
            assertTrue(bridge in api, "el volcado de API no cubre el puente `$bridge`")
        }

        val leaked = lines.filter { line ->
            val member = line.substringAfter("fun ", "").substringBefore('(')
            member.isNotEmpty() && isMangledMember(member.removeSuffix(DEFAULT_BRIDGE))
        }
        assertTrue(leaked.isEmpty(), "se colaron miembros con mangling en el volcado: $leaked")
    }

    @Test
    fun `the public api matches the versioned snapshot`() {
        val current = publicApi().joinToString("\n") + "\n"

        if (System.getProperty(UPDATE_FLAG) == "true") {
            val target = File("src/test/resources/$SNAPSHOT")
            target.parentFile?.mkdirs()
            target.writeText(current)
            println("API actualizada en ${target.absolutePath} (${current.lines().size - 1} líneas)")
            return
        }

        val expected = javaClass.classLoader?.getResourceAsStream(SNAPSHOT)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        if (expected == null) {
            error(
                "no existe el volcado $SNAPSHOT. Genéralo con:\n" +
                    "  ./gradlew test --tests \"*PublicApiTest*\" " +
                    "-D$UPDATE_FLAG=true",
            )
        }

        val before = expected.trim().lines().toSet()
        val after = current.trim().lines().toSet()
        val removed = (before - after).sorted()
        val added = (after - before).sorted()
        if (removed.isNotEmpty() || added.isNotEmpty()) {
            val detail = buildString {
                if (removed.isNotEmpty()) {
                    appendLine("QUITADO de la API pública (rompe compatibilidad):")
                    removed.forEach { appendLine("  - $it") }
                }
                if (added.isNotEmpty()) {
                    appendLine("AÑADIDO a la API pública:")
                    added.forEach { appendLine("  + $it") }
                }
                appendLine()
                appendLine("Si el cambio es intencionado, regenera el volcado y revisa el diff:")
                appendLine(
                    "  ./gradlew test --tests \"*PublicApiTest*\" " +
                        "-D$UPDATE_FLAG=true",
                )
                append("Si quita o cambia una firma existente, declárala en el CHANGELOG.")
            }
            assertEquals(expected.trim(), current.trim(), "la API pública cambió.\n$detail")
        }
    }
}
