// Benchmarks JMH de la librería. Es un build aparte, como `compat/kotlin-1.9`: no forma parte del
// build principal ni del artefacto, y así JitPack no tiene que resolver el plugin de JMH para
// publicar. Se ejecuta con el wrapper de la raíz:
//
//     ./gradlew -p benchmarks verificarLimites
//
// `includeBuild("..")` hace que la dependencia `com.braymon:kotmpeg-core` se resuelva contra el
// código fuente actual y no contra un artefacto publicado: se mide lo que hay en el árbol.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    // El catálogo de la raíz, para que el Kotlin de los benchmarks sea siempre el que compila la
    // librería: con la versión escrita aquí aparte, subirla en la raíz y olvidarla aquí acabaría en
    // un compilador más viejo que los metadatos de las clases que tiene que leer.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "kotmpeg-benchmarks"

includeBuild("..")
