import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("me.champeau.jmh") version "0.7.3"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    jmh("com.braymon:kotmpeg-core")
}

// Por defecto, una pasada corta que cabe en el CI: un fork, dos calentamientos y tres medidas de un
// segundo. Para medir en serio en local se sube con propiedades, p. ej.
// `-Pjmh.iteraciones=10 -Pjmh.forks=3`.
jmh {
    jmhVersion.set("1.37")
    fork.set(providers.gradleProperty("jmh.forks").orElse("1").map { it.toInt() })
    warmupIterations.set(providers.gradleProperty("jmh.calentamientos").orElse("2").map { it.toInt() })
    warmup.set("1s")
    iterations.set(providers.gradleProperty("jmh.iteraciones").orElse("3").map { it.toInt() })
    timeOnIteration.set("1s")
    // El perfilador de GC añade a cada benchmark los bytes reservados por operación, que es la
    // medida que se vigila: a diferencia del tiempo, no depende de la máquina.
    profilers.add("gc")
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json"))
    providers.gradleProperty("jmh.incluir").orNull?.let { includes.add(it) }
}

// Compara la memoria reservada por operación de cada benchmark con su límite en
// `limites.properties` y falla si alguno se pasa. Los tiempos se informan pero no se vigilan: en un
// runner compartido varían de una ejecución a otra más de lo que cualquier regresión que importe,
// así que un límite de tiempo o sería inestable o tan holgado que no detectaría nada. Los bytes por
// operación, en cambio, son los mismos en cualquier máquina, y son justo lo que retrocede cuando
// alguien vuelve a cargar en memoria lo que se leía bajo demanda.
val verificarLimites by tasks.registering {
    dependsOn("jmh")
    val resultados = layout.buildDirectory.file("results/jmh/results.json")
    val limites = layout.projectDirectory.file("limites.properties")
    val resumen = layout.buildDirectory.file("results/jmh/resumen.md")
    inputs.file(resultados)
    inputs.file(limites)
    outputs.file(resumen)
    doLast {
        val tope = Properties().apply { limites.asFile.inputStream().use { load(it) } }
        @Suppress("UNCHECKED_CAST")
        val filas = JsonSlurper().parse(resultados.get().asFile) as List<Map<String, Any?>>
        val fallos = ArrayList<String>()
        val tabla = StringBuilder("| Benchmark | Tiempo | Memoria por operación | Límite | |\n|---|---|---|---|---|\n")
        for (fila in filas) {
            val nombre = (fila["benchmark"] as String).substringAfterLast(".benchmarks.")
            @Suppress("UNCHECKED_CAST")
            val principal = fila["primaryMetric"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val secundarias = fila["secondaryMetrics"] as Map<String, Map<String, Any?>>
            val bytes = secundarias.entries.firstOrNull { it.key.endsWith("gc.alloc.rate.norm") }
                ?.value?.get("score")?.toString()?.toDouble()
            val limite = tope.getProperty(nombre)?.toDouble()
            val estado = when {
                limite == null -> { fallos += "$nombre: sin límite en limites.properties"; "sin límite" }
                bytes == null -> { fallos += "$nombre: JMH no midió la memoria"; "sin medida" }
                bytes > limite -> { fallos += "$nombre: %.0f B/op, el límite es %.0f".format(bytes, limite); "❌" }
                else -> "✅"
            }
            tabla.append("| `$nombre` | %.3f %s | %s | %s | %s |\n".format(
                (principal["score"] as Number).toDouble(), principal["scoreUnit"],
                bytes?.let { "%,.0f B".format(it) } ?: "—", limite?.let { "%,.0f B".format(it) } ?: "—", estado,
            ))
        }
        resumen.get().asFile.writeText(tabla.toString())
        println(tabla)
        if (fallos.isNotEmpty()) throw GradleException("Benchmarks por encima de su límite de memoria:\n" + fallos.joinToString("\n"))
    }
}
