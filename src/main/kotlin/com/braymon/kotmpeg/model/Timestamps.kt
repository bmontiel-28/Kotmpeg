package com.braymon.kotmpeg.model

import java.math.BigInteger

/**
 * Conversión entre escalas de tiempo **sin desbordamiento silencioso**.
 *
 * `valor * multiplicador / divisor` con `Long` da la vuelta sin avisar en cuanto el producto pasa
 * de 2^63: con una escala de 1 GHz basta un valor de dos horas y media. El camino rápido es el de
 * siempre; solo si [Math.multiplyExact] detecta el desbordamiento se recurre a [BigInteger], y un
 * resultado que no quepa en `Long` se satura en vez de envolverse.
 *
 * No usa `Math.multiplyHigh` a propósito: en Android solo existe desde la API 31.
 */
internal object Timestamps {

    /** `floor(value * multiplier / divisor)`. */
    fun rescaleFloor(value: Long, multiplier: Long, divisor: Long): Long {
        require(divisor > 0) { "divisor de escala inválido: $divisor" }
        return try {
            Math.floorDiv(Math.multiplyExact(value, multiplier), divisor)
        } catch (_: ArithmeticException) {
            slowPath(value, multiplier, 0L, divisor)
        }
    }

    /** `floor((value * multiplier + divisor / 2) / divisor)`: redondeo al más cercano. */
    fun rescaleRounded(value: Long, multiplier: Long, divisor: Long): Long {
        require(divisor > 0) { "divisor de escala inválido: $divisor" }
        val half = divisor / 2
        return try {
            Math.floorDiv(Math.addExact(Math.multiplyExact(value, multiplier), half), divisor)
        } catch (_: ArithmeticException) {
            slowPath(value, multiplier, half, divisor)
        }
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
