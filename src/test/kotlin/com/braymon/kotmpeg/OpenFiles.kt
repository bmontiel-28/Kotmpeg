package com.braymon.kotmpeg

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.RandomAccessFile

/**
 * Si el proceso tiene abierto un archivo, para los tests que comprueban que una operación fallida
 * no deja descriptores huérfanos. En un móvil eso no es un detalle: cada proceso tiene un límite de
 * descriptores, y una app que graba muchas veces y deja uno abierto en cada fallo acaba con
 * «Too many open files».
 *
 * Se mide de una forma distinta en cada sistema, porque no hay una que valga para todos:
 *
 *  - **Linux**: se cuentan los descriptores de `/proc/self/fd` que apuntan al archivo.
 *  - **Windows**: no hay `/proc`, pero la librería abre con `RandomAccessFile`, que en Windows no
 *    comparte el permiso de borrado: mientras quede un descriptor abierto, el archivo no se deja
 *    renombrar. Se renombra y se deshace. Antes de fiarse, el método se calibra con un archivo
 *    abierto a propósito; si un JDK futuro cambiara ese comportamiento, el test se omite en vez de
 *    dar por cerrado lo que no se ha medido.
 *  - **Resto (macOS)**: renombrar un archivo abierto está permitido y no hay `/proc`, así que se
 *    omite.
 *
 * Hasta la 3.0.0 solo existía la medida de Linux y en Windows estos tests se omitían siempre.
 */
internal object OpenFiles {

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private val windowsDetectsOpenFiles: Boolean by lazy {
        val probe = File.createTempFile("kotmpeg-abierto", ".bin")
        try {
            RandomAccessFile(probe, "rw").use { lockedAgainstRename(probe) } && !lockedAgainstRename(probe)
        } finally {
            probe.delete()
        }
    }

    /** `true` si el proceso tiene abierto [file]. Omite el test donde no se puede medir. */
    fun isOpen(file: File): Boolean {
        val fdDir = File("/proc/self/fd")
        if (fdDir.isDirectory) {
            System.gc()
            val target = file.canonicalPath
            return fdDir.listFiles().orEmpty().any { fd -> runCatching { fd.canonicalPath }.getOrNull() == target }
        }
        assumeTrue(isWindows, "solo medible en Linux (/proc) y en Windows (bloqueo de renombrado)")
        assumeTrue(windowsDetectsOpenFiles, "este Windows/JDK deja renombrar archivos abiertos: no se puede medir")
        return file.exists() && lockedAgainstRename(file)
    }

    /** Intenta renombrar [file] y lo devuelve a su nombre; `true` si el sistema no lo dejó. */
    private fun lockedAgainstRename(file: File): Boolean {
        val moved = File(file.parentFile, file.name + ".comprobando")
        if (!file.renameTo(moved)) return true
        check(moved.renameTo(file)) { "no se pudo devolver ${file.name} a su nombre" }
        return false
    }
}
