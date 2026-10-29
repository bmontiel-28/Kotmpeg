package com.braymon.kotmpeg

import com.braymon.kotmpeg.mp4.FragmentLimits
import com.braymon.kotmpeg.mp4.FragmentedMp4Muxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El tope interno de datos de un fragmento de fMP4. El `data_offset` de cada `trun` es de 32 bits con
 * signo y cuenta desde el `moof`; con varias pistas, el de la segunda salta todos los datos de la
 * primera. Sin `segmentIndex`, `maxFragmentBytes` no tiene tope, y un fragmento con más de 2 GiB de
 * vídeo dejaba truncado el `data_offset` del audio.
 *
 * Reproducirlo de punta a punta exige retener más de 2 GiB en memoria, más de lo que tiene el JVM de
 * los tests, así que se prueba la regla de corte que lo impide.
 */
class Fmp4FragmentLimitsTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `fragments are cut before their data can overflow a signed data offset`() {
        FragmentedMp4Muxer(File(dir, "sin-tope.mp4")) { maxFragmentBytes = 4L shl 30 }.close()

        val limit = 4L shl 30
        assertTrue(FragmentLimits.isFull((3L shl 29), 1 shl 20, limit), "1,5 GiB retenidos y la muestra siguiente")
        assertTrue(FragmentLimits.isFull(FragmentLimits.MAX_DATA_BYTES - 10, 11, limit), "un byte más que el tope")
        assertFalse(FragmentLimits.isFull(FragmentLimits.MAX_DATA_BYTES - 10, 10, limit), "justo el tope")
        assertTrue(FragmentLimits.isFull(64L shl 20, 100, 64L shl 20), "maxFragmentBytes sigue mandando")
        assertFalse(FragmentLimits.isFull((64L shl 20) - 1, 100, 64L shl 20), "y como hasta ahora se puede pasar en una muestra")

        for (held in listOf(0L, 1L, 1L shl 20, (1L shl 30) - 1, 1L shl 30)) {
            for (next in listOf(0, 1, 1 shl 20, 1 shl 29, Int.MAX_VALUE - 8)) {
                if (!FragmentLimits.isFull(held, next, limit) && held > 0) {
                    assertTrue(held + next <= FragmentLimits.MAX_DATA_BYTES, "$held + $next pasa del tope")
                    assertTrue(held + next + 8 + 64 * 1024 <= Int.MAX_VALUE, "$held + $next no cabe en un data_offset")
                }
            }
        }
    }
}
