// El Kotlin más antiguo que la librería promete soportar en la app que la consume. Si este proyecto
// deja de compilar, la librería se publicó con un nivel de Kotlin demasiado nuevo.
plugins {
    kotlin("jvm") version "1.9.25"
    application
}

kotlin {
    jvmToolchain(17)
}

// La versión a probar llega desde el CI (`-PkotmpegVersion=...`), que la lee de build.gradle.kts y
// publica antes el artefacto en el Maven local.
val kotmpegVersion: String = providers.gradleProperty("kotmpegVersion").get()

dependencies {
    implementation("com.braymon:kotmpeg-core:$kotmpegVersion")
}

application {
    mainClass.set("ConsumidorKt")
}
