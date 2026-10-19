package com.braymon.kotmpeg.model

import java.math.BigInteger

/**
 * Conversión entre escalas de tiempo **sin desbordamiento silencioso**.
 *
 * `valor * multiplicador / divisor` con `Long` da la vuelta sin avisar en cuanto el producto pasa
 * de 2^63: con una escala de 1 GHz basta un valor de dos horas y media. Un resultado que no quepa
 * en `Long` se satura en vez de envolverse.
 *
 * El desbordamiento se detecta **sin lanzar nada**. Hasta la 3.x se dejaba a
 * `Math.multiplyExact` y se capturaba su `ArithmeticException`, y crear una excepción reserva su
 * traza: unos 3 KB por conversión. Con tiempos normales no pasa nunca, pero un archivo dañado con
 * tiempos enormes desbordaba en cada muestra, y el fuzzing encontró un fMP4 de 6 KB que hacía
 * reservar así 230 MiB. Lo que desborda se resuelve con la descomposición `valor = q · divisor + r`
 * (ver [scale]), y [BigInteger] queda para los extremos que ni así caben.
 *
 * No usa `Math.multiplyHigh` a propósito: en Android solo existe desde la API 31.
 */
internal object Timestamps {

    /** `floor(value * multiplier / divisor)`. */
    fun rescaleFloor(value: Long, multiplier: Long, divisor: Long): Long {
        require(divisor > 0) { "divisor de escala inválido: $divisor" }
        return scale(value, multiplier, 0L, divisor)
    }

    /** `floor((value * multiplier + divisor / 2) / divisor)`: redondeo al más cercano. */
    fun rescaleRounded(value: Long, multiplier: Long, divisor: Long): Long {
        require(divisor > 0) { "divisor de escala inválido: $divisor" }
        return scale(value, multiplier, divisor / 2, divisor)
    }

    /**
     * `floor((value * multiplier + addend) / divisor)`, saturado, con `0 <= addend < divisor`.
     *
     * Si el producto cabe, la división de siempre. Si no, con `q = floor(value / divisor)` y
     * `r = value - q * divisor` (`0 <= r < divisor`) el resultado es exactamente
     * `q * multiplier + floor((r * multiplier + addend) / divisor)`, donde los dos términos caben
     * en `Long` para cualquier escala real. El segundo es menor que `multiplier + 1`, así que si
     * ni `(q + 1) * multiplier` cabe, el resultado es menor que `Long.MIN_VALUE` y se satura. Lo
     * demás, a [slowPath].
     */
    private fun scale(value: Long, multiplier: Long, addend: Long, divisor: Long): Long {
        if (productFits(value, multiplier)) {
            val product = value * multiplier
            if (product <= Long.MAX_VALUE - addend) return Math.floorDiv(product + addend, divisor)
        }
        if (multiplier < 0) return slowPath(value, multiplier, addend, divisor)
        val q = Math.floorDiv(value, divisor)
        val r = Math.floorMod(value, divisor)
        if (!productFits(r, multiplier)) return slowPath(value, multiplier, addend, divisor)
        val partial = r * multiplier
        if (partial > Long.MAX_VALUE - addend) return slowPath(value, multiplier, addend, divisor)
        val inner = (partial + addend) / divisor
        if (!productFits(q, multiplier)) {
            return when {
                q > 0 -> Long.MAX_VALUE
                !productFits(q + 1, multiplier) -> Long.MIN_VALUE
                else -> slowPath(value, multiplier, addend, divisor)
            }
        }
        val whole = q * multiplier
        return if (whole > Long.MAX_VALUE - inner) Long.MAX_VALUE else whole + inner
    }

    /**
     * Si `a * b` cabe en `Long`. Casi siempre lo decide la longitud en bits de los dos, que no
     * cuesta nada; en el margen, una división.
     */
    private fun productFits(a: Long, b: Long): Boolean {
        if (a == 0L || b == 0L) return true
        if (a == Long.MIN_VALUE || b == Long.MIN_VALUE) return false
        val absA = Math.abs(a)
        val absB = Math.abs(b)
        if (java.lang.Long.numberOfLeadingZeros(absA) + java.lang.Long.numberOfLeadingZeros(absB) >= 65) return true
        return absA <= Long.MAX_VALUE / absB
    }

    private fun slowPath(value: Long, multiplier: Long, addend: Long, divisor: Long): Long {
        val numerator = BigInteger.valueOf(value).multiply(BigInteger.valueOf(multiplier))
            .add(BigInteger.valueOf(addend))
        val parts = numerator.divideAndRemainder(BigInteger.valueOf(divisor))
        var quotient = parts[0]
        if (parts[1].signum() < 0) quotient = quotient.subtract(BigInteger.ONE)
        return when {
            quotient > LONG_MAX -> Long.MAX_VALUE
            quotient < LONG_MIN -> Long.MIN_VALUE
            else -> quotient.toLong()
        }
    }

    private val LONG_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
    private val LONG_MIN: BigInteger = BigInteger.valueOf(Long.MIN_VALUE)
}
