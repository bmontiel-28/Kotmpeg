package com.braymon.kotmpeg

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El plazo por defecto que `build.gradle.kts` da a cada test tiene que poder cortar un cuelgue de
 * CPU, que es el que deja un parser atascado en un bucle.
 *
 * En el modo de hilo por defecto de JUnit, `SAME_THREAD`, vencer el plazo solo interrumpe el hilo
 * del test, y un bucle que no mira la interrupción no se entera: el test no terminaba nunca y el
 * job agotaba su tiempo sin decir cuál era. En `SEPARATE_THREAD` el test corre en un hilo aparte, y
 * al vencer JUnit lo da por fallido y sigue, aunque ese hilo siga girando.
 */
class ApiFinalTestTimeoutTest {

    /**
     * Comprueba la configuración que llega al JVM de test, y que medir lo que reserva el hilo actual
     * —lo que hacen `allocatedBytes` y los tests de memoria— sigue valiendo en ese hilo aparte: el
     * código medido corre en el mismo hilo que el test, sea cual sea.
     *
     * Sin plazo por defecto, que es como corre la campaña larga de fuzzing, no hay nada que comprobar.
     */
    @Test
    fun `the default timeout runs each test in a thread it can abandon`() {
        assumeTrue(System.getProperty("junit.jupiter.execution.timeout.default") != null, "sin plazo por defecto")
        assertEquals("SEPARATE_THREAD", System.getProperty("junit.jupiter.execution.timeout.thread.mode.default"))

        val size = 4 shl 20
        var kept: ByteArray? = null
        allocatedBytes { kept = ByteArray(size) }
        val allocated = allocatedBytes { kept = ByteArray(size) }
        assertEquals(size, kept?.size)
        assertTrue(allocated in size.toLong()..size + (1L shl 20), "se midieron $allocated bytes al reservar $size")
    }
}
