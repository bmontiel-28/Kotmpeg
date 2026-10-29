package com.braymon.kotmpeg.mp4

import com.braymon.kotmpeg.io.SeekableInput

/** Una caja ISO BMFF ya localizada: su tipo y dónde empiezan y acaban sus datos. */
internal class Mp4Box(val type: String, val dataStart: Long, val dataEnd: Long)

/**
 * Las primitivas de lectura de cajas que comparten [Mp4Demuxer], el lector de entradas de muestra
 * ([Mp4SampleEntryReader]) y el recorrido de fragmentos ([FragmentWalker]). Están juntas porque
 * las tres piezas tienen que tratar igual una cabecera imposible o una cuenta declarada que no
 * cabe: si una la aceptara y otra no, la pasada al abrir y los cursores verían archivos distintos.
 */
internal class Mp4BoxReader(val input: SeekableInput) {

    /**
     * Lee la cabecera de la siguiente caja, o null si no cabe o es imposible.
     *
     * El fin de la caja se calcula con [Math.addExact] porque solo la vía de `largesize` puede
     * desbordar el `Long`: es el patrón crudo de 64 bits del archivo, y un desbordamiento en
     * Kotlin no lanza, da la vuelta en silencio. Un valor elegido a propósito puede hacer que el
     * resultado caiga de vuelta dentro de la ventana válida, y entonces el parser seguiría
     * leyendo desde un offset inventado como si fuera una cabecera. Se trata como fin de
     * escaneo, igual que cualquier otra cabecera imposible.
     */
    fun readBoxHeader(end: Long): Mp4Box? {
        if (input.position + 8 > end || input.remaining < 8) return null
        val start = input.position
        var size = input.readInt32().toLong() and 0xFFFFFFFFL
        val type = String(input.readBytes(4), Charsets.US_ASCII)
        var headerLen = 8L
        if (size == 1L) {
            if (input.position + 8 > end || input.remaining < 8) return null
            size = input.readInt64()
            headerLen = 16
        } else if (size == 0L) {
            size = end - start
        }
        if (size < headerLen) return null
        val dataEnd = try {
            Math.addExact(start, size)
        } catch (_: ArithmeticException) {
            return null
        }
        return Mp4Box(type, start + headerLen, dataEnd)
    }

    /**
     * Recorre las cajas hijas entre [start] y [end]. Una que no avanza o que se sale de su padre
     * termina el recorrido: es un archivo corrupto, y seguir sería arriesgarse a un bucle.
     */
    inline fun scanChildren(start: Long, end: Long, handler: (Mp4Box) -> Unit) {
        input.position = start
        while (input.position + 8 <= end) {
            val boxStart = input.position
            val box = readBoxHeader(end) ?: break
            if (box.dataEnd <= boxStart || box.dataEnd > end) break
            handler(box)
            input.position = box.dataEnd
        }
    }

    /**
     * Número de entradas que caben realmente en lo que queda de la caja. Las cuentas de
     * las tablas vienen del archivo: reservar `LongArray(n)` sin validarlas permite que
     * unos pocos bytes manipulados provoquen un OutOfMemoryError (que es `Error`, no
     * `Exception`, así que ni siquiera lo atrapa el try/catch por pista).
     */
    fun boundedCount(declared: Int, bytesPerEntry: Int, end: Long): Int {
        val declaredUnsigned = declared.toLong() and 0xFFFFFFFFL
        if (declaredUnsigned == 0L) return 0
        val available = end - input.position
        if (available <= 0) return 0
        val fits = available / bytesPerEntry
        return minOf(declaredUnsigned, fits).toInt()
    }

    /**
     * Cota para las tablas que **no** guardan una entrada por muestra —`stsz` con tamaño
     * constante y `trun` sin campos por muestra—, donde el tamaño de la caja no dice nada
     * de la cuenta. El límite lo pone la física del archivo: cada muestra ocupa al menos
     * [bytesPerSampleInFile] bytes de datos, así que no puede haber más de
     * `length / bytesPerSampleInFile`.
     *
     * Se mide contra el archivo **completo**, no contra lo que queda por leer: con el `moov`
     * al final las muestras están *detrás* de la caja que se está parseando, y acotar por
     * el remanente truncaría pistas legítimas. Por eso solo acota cada tabla por separado: lo
     * que impide que muchas tablas sumen más muestras de las que caben es [SampleBudget].
     */
    fun physicalCount(declared: Int, bytesPerSampleInFile: Long): Int {
        val declaredUnsigned = declared.toLong() and 0xFFFFFFFFL
        val physicalMax = input.length / bytesPerSampleInFile.coerceAtLeast(1)
        return minOf(declaredUnsigned, physicalMax).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
