// Proyecto consumidor mínimo: comprueba que una app con Kotlin 1.9 puede compilar contra la
// librería y ejecutarla. No forma parte del build principal ni del artefacto publicado.
//
// Lleva su propio wrapper, fijado en Gradle 8, a propósito: el plugin de Kotlin 1.9 no funciona
// con Gradle 9. Así el proyecto principal puede subir de Gradle sin romper esta comprobación, que
// es la que vigila el Kotlin mínimo que se promete a las apps. No lo subas a Gradle 9 mientras el
// mínimo prometido siga siendo Kotlin 1.9.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "kotmpeg-consumidor-kotlin-1.9"
