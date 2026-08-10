// Este proyecto es la raíz: un único build de Gradle, sin módulos hijos y sin depender de ningún
// otro repositorio. Quien lo use lo consume como artefacto publicado, nunca como `project(":...")`,
// que es lo que permite versionarlo y publicarlo sin arrastrar a nadie.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kotmpeg-core"
