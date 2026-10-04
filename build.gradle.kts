plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

// La versión vive aquí y no como texto suelto en el README: es la que acaba en el POM y en el
// nombre del jar, así que cualquier otra copia acabaría desincronizándose.
version = "3.0.0"
group = "com.braymon"

// El nombre del jar sigue al del artefacto publicado, no al de la carpeta del repositorio.
base {
    archivesName.set("kotmpeg-core")
}

kotlin {
    // JVM 17 es el suelo y el techo a la vez: por debajo no compilan las construcciones que usa el
    // código, y por encima se quedaría fuera quien todavía compile su app contra 17 — entre ellos
    // cualquier proyecto de Android, que es donde 17 es el nivel habitual.
    jvmToolchain(17)

    // Todo el código ya marca `public` a mano, pero sin esto la disciplina era solo una
    // convención: un archivo nuevo sin modificadores compila igual y filtra API pública por
    // accidente. En una librería, la superficie pública es el contrato.
    explicitApi()

    // El compilador de la app que consume la librería es el techo: solo sabe leer la metadata de su
    // versión y de la siguiente. Compilado en nivel 2.2, la librería exigía Kotlin 2.1 o superior a
    // la app —el mismo tipo de rotura que obligó a publicar la 1.0.1—. En nivel 2.0, y declarando
    // la stdlib 2.0.21 en el POM, la acepta cualquier app con Kotlin 1.9 o superior. El compilador
    // que se usa aquí puede ser más nuevo; lo que importa es el nivel que escribe en el artefacto.
    // `compat/kotlin-1.9` lo comprueba en el CI con un proyecto consumidor real.
    coreLibrariesVersion = "2.0.21"
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

// La firma de la API 26 de Android, como archivo suelto. Es una configuración aparte y no una
// dependencia de test porque no es un jar: `AndroidApiCompatibilityTest` la lee por su ruta.
val androidApiSignature: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.animal.sniffer)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidApiSignature(variantOf(libs.android.api.signature) { artifactType("signature") })
}

tasks.test {
    useJUnitPlatform()

    // Gradle no reenvía las `-D` de la línea de comandos al JVM de test. `PublicApiTest` la
    // necesita para poder regenerar el volcado de API pública a petición.
    systemProperty("kotmpeg.api.update", providers.systemProperty("kotmpeg.api.update").orNull ?: "false")

    // `AndroidApiCompatibilityTest` necesita la firma de Android y las clases compiladas de la
    // librería; se le pasan como rutas para que no dependa del directorio de trabajo.
    val firma: FileCollection = androidApiSignature
    val clases: FileCollection = sourceSets.main.get().output.classesDirs
    inputs.files(firma).withPropertyName("androidApiSignature")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkotmpeg.android.signature=${firma.singleFile.absolutePath}",
                "-Dkotmpeg.main.classes=${clases.files.joinToString(File.pathSeparator) { it.absolutePath }}",
            )
        },
    )

    testLogging {
        // Sin esto, un `skipped` masivo por falta de ffmpeg en la máquina pasa desapercibido y el
        // verde se lee como cobertura que no se ejecutó.
        events("skipped", "failed")
    }
}

java {
    // El jar de fuentes es lo que permite navegar y leer el KDoc desde el IDE de quien consuma la
    // librería.
    withSourcesJar()
}

publishing {
    publications {
        register<MavenPublication>("release") {
            from(components["java"])
            artifactId = "kotmpeg-core"
            pom {
                name.set("Kotmpeg Core")
                description.set(
                    "Muxing y demuxing de MKV y MP4/fMP4 en Kotlin puro sobre cualquier JVM 17, " +
                        "sin dependencias de plataforma ni binarios externos.",
                )
                url.set("https://github.com/bmontiel-28/Kotmpeg")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("bmontiel-28")
                        name.set("bmontiel-28")
                        url.set("https://github.com/bmontiel-28")
                    }
                }
                scm {
                    url.set("https://github.com/bmontiel-28/Kotmpeg")
                    connection.set("scm:git:https://github.com/bmontiel-28/Kotmpeg.git")
                    developerConnection.set("scm:git:ssh://git@github.com/bmontiel-28/Kotmpeg.git")
                }
            }
        }
    }
}
