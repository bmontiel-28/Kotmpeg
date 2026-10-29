package com.braymon.kotmpeg.mp4

/**
 * Los tiempos de decodificación de cada fragmento de [FragmentedMp4Muxer], aparte del muxer porque
 * son cálculo puro, sin E/S, y así se pueden razonar y probar sueltos.
 *
 * El punto de partida es el de CMAF: con los PTS del fragmento ordenados, la muestra `i` en orden de
 * decodificación se decodifica en el `i`-ésimo PTS. Lo que hay aquí son los ajustes a esa regla:
 * la duración de la última muestra, que no sale de los PTS del propio fragmento; el límite del
 * adelanto de un B-frame retenido en una pausa ([boundReorderOffsets]); el solape con el fragmento
 * anterior ([absorbOverlap]); y, con desfases sin signo, el tope que fija la lista de edición
 * ([capCompositionLead]).
 */
internal object FragmentTiming {

    /**
     * Las duraciones de un fragmento, en µs y en orden de decodificación, y dónde empieza a
     * decodificarse ([decodeStartUs]). [typicalIntervalUs] es el intervalo con el que estimar la
     * última duración de un fragmento siguiente que no traiga nada mejor.
     */
    class Durations(val durationsUs: LongArray, val decodeStartUs: Long, val typicalIntervalUs: Long)

    /**
     * El menor paso entre dos tiempos de decodificación que sigue siendo al menos un tic de
     * [timescale], en µs: con él, dos DTS seguidos nunca caen en el mismo tic al redondear.
     */
    fun minStepUs(timescale: Long): Long = (1_000_000 + timescale - 1) / timescale

    /**
     * Las duraciones de las muestras de un fragmento cuyos PTS son [ptsUs], en orden de
     * decodificación.
     *
     * La duración de la última muestra es lo único que no se deduce de los PTS del propio
     * fragmento. Si el corte lo provoca un paquete de **esta misma pista** —el keyframe que abre el
     * GOP siguiente, que es el caso normal—, [nextPtsUs] es su PTS y la duración sale exacta. Antes
     * se estimaba siempre con el intervalo anterior, y con vídeo de frecuencia variable —una
     * captura de pantalla solo emite fotogramas cuando algo cambia— una pausa de dos segundos se
     * contaba como 16 ms: el tiempo de decodificación de los fragmentos siguientes quedaba casi dos
     * segundos por detrás del de presentación, y el desfase se acumulaba pausa a pausa.
     *
     * Ese PTS manda también sobre la duración que traiga el paquete ([lastPacketDurationUs]). Hasta
     * la 4.0 era al revés, y con `MediaCodec`, que no da duraciones, no se notaba; en un remux sí,
     * porque el paquete es el último **en orden de decodificación** y la duración que se busca es la
     * del último hueco, el que empieza en el mayor PTS. Con B-frames son muestras distintas, y si el
     * origen es un fMP4 de esta librería, la duración del paquete es la de su hueco ya ajustado. El
     * fragmento acababa unos milisegundos después de que empezara a presentarse el keyframe
     * siguiente, todo lo que venía detrás se decodificaba esos milisegundos tarde, y reempaquetar una
     * grabación real de una tablet alargaba su duración 189 ms cada vez. La duración del paquete
     * queda para cuando no hay PTS siguiente —el último fragmento, o un corte que provoca otra
     * pista—, y entonces es la del hueco del propio paquete: ver [boundReorderOffsets]. Sin ella se
     * estima con el último hueco del fragmento, con [typicalIntervalUs] o, en la primera muestra de
     * la pista, con [defaultIntervalUs].
     *
     * Sin [reorder] —en audio— no se acota nada: el DTS de cada muestra es el PTS que le toca en la
     * secuencia ordenada, como en `Mp4Muxer`, y el muxer iguala a él su PTS. AAC y Opus se
     * decodifican en el orden en que se presentan, así que un PTS que retrocede es jitter de la
     * fuente y no un B-frame, y no hay adelanto que limitar.
     *
     * Si el paquete que abre el fragmento siguiente se presenta **antes** que la última muestra de
     * este —un corte por tamaño o por duración que cae en un B-frame—, el fragmento siguiente
     * empezará a presentarse antes de donde acabe este, y todo lo que este se alargue lo tendrá que
     * recuperar aquel (ver [absorbOverlap]). Así que la última duración es la mínima, un tic.
     *
     * El fragmento empieza a decodificarse en su primer PTS, salvo que el anterior de la pista
     * ([previousEndUs], `null` en el primero) acabe después, porque el tiempo de decodificación no
     * puede retroceder; o que sea el primero y empiece antes del origen, porque `tfdt` no tiene signo
     * y empieza en cero. Si acaba antes —un micrófono que se calla, una pista que arranca tarde—, se
     * deja el hueco.
     */
    fun durations(
        ptsUs: LongArray,
        lastPacketDurationUs: Long,
        nextPtsUs: Long?,
        previousEndUs: Long?,
        typicalIntervalUs: Long,
        defaultIntervalUs: Long,
        timescale: Long,
        reorder: Boolean,
    ): Durations {
        val n = ptsUs.size
        val sorted = ptsUs.copyOf().also { it.sort() }
        val durationsUs = LongArray(n)
        for (i in 0 until n - 1) durationsUs[i] = sorted[i + 1] - sorted[i]
        val nextDelta = nextPtsUs?.let { it - sorted[n - 1] }?.takeIf { it > 0 }
        val estimate = when {
            lastPacketDurationUs > 0 -> lastPacketDurationUs
            n > 1 -> sorted[n - 1] - sorted[n - 2]
            typicalIntervalUs > 0 -> typicalIntervalUs
            else -> null
        }
        val minStepUs = minStepUs(timescale)
        durationsUs[n - 1] = when {
            nextDelta != null -> nextDelta
            nextPtsUs != null -> minStepUs
            else -> estimate ?: defaultIntervalUs
        }
        val lastFromPacket = nextPtsUs == null && lastPacketDurationUs > 0
        if (reorder) boundReorderOffsets(ptsUs, sorted, durationsUs, minStepUs, lastFromPacket)

        val decodeStartUs = if (previousEndUs == null) sorted[0].coerceAtLeast(0L) else maxOf(previousEndUs, sorted[0])
        absorbOverlap(durationsUs, sorted[0], decodeStartUs, minStepUs)
        return Durations(durationsUs, decodeStartUs, estimate ?: nextDelta ?: defaultIntervalUs)
    }

    /**
     * Acota cuánto se adelanta el tiempo de decodificación de una muestra a su tiempo de
     * presentación, reescribiendo [durationsUs] si hace falta. [ptsUs] va en orden de decodificación
     * y [sorted] son los mismos PTS ordenados.
     *
     * Con los PTS ordenados, la muestra `i` en orden de decodificación se decodifica en el `i`-ésimo
     * PTS del fragmento. Un B-frame se adelanta así uno o dos fotogramas, lo normal en CMAF. Pero si
     * entre un B-frame y la referencia que se decodifica antes que él hay una pausa —una captura de
     * pantalla deja de emitir fotogramas mientras nada cambia, y el codificador retiene el B-frame
     * hasta el siguiente—, el adelanto es **la pausa entera**. Pasó con una grabación real de una
     * tablet Android: un B-frame mostrado a los 4,6 s quedó decodificándose a los 11,3 s, y FFmpeg,
     * que retrasa la pista entera lo que mida el mayor adelanto, ponía todo el vídeo 6,7 s por
     * detrás del audio.
     *
     * El tiempo de decodificación de cada muestra se limita al menor PTS de las que quedan por
     * decodificar más la profundidad de reordenación del fragmento —cuántas posiciones se adelanta
     * como mucho una muestra respecto a su orden de presentación— por el intervalo típico entre
     * fotogramas, la mediana. Así el adelanto queda en lo que tendría sin la pausa, y la pausa pasa a
     * la duración del B-frame, que es lo que se muestra durante ella. Sin B-frames, o cuando ninguna
     * muestra pasa del límite, no cambia nada: el archivo es idéntico. El tramo total del fragmento
     * tampoco cambia, así que los fragmentos siguientes no se mueven.
     *
     * Salvo si la duración de la última muestra la trae su paquete ([lastFromPacket]). Esa es la de
     * **su** hueco de decodificación, el que empieza en su DTS: así la escribe este muxer y así la
     * devuelve el demuxer al releer el archivo. Si aquí se adelanta ese DTS, el tramo acaba donde
     * acababa ese hueco, siempre que sea después de que empiece a mostrarse el último fotograma; si
     * no, la duración cuenta desde el mayor PTS, como cuando se estima. Antes contaba siempre desde
     * ahí, y reempaquetar un fMP4 de la librería cuyo último fragmento acabara en un B-frame retenido
     * en una pausa alargaba el archivo esa pausa en cada pasada.
     */
    fun boundReorderOffsets(
        ptsUs: LongArray,
        sorted: LongArray,
        durationsUs: LongArray,
        minStepUs: Long,
        lastFromPacket: Boolean,
    ) {
        val n = ptsUs.size
        var reordered = false
        for (i in 1 until n) if (ptsUs[i] < ptsUs[i - 1]) reordered = true
        if (!reordered) return

        var depth = 0
        for (i in 0 until n) depth = maxOf(depth, i - lowerBound(sorted, ptsUs[i]))
        var positive = 0
        for (i in 0 until n - 1) if (sorted[i + 1] > sorted[i]) positive++
        if (depth == 0 || positive == 0) return
        val gaps = LongArray(positive)
        var g = 0
        for (i in 0 until n - 1) if (sorted[i + 1] > sorted[i]) gaps[g++] = sorted[i + 1] - sorted[i]
        gaps.sort()
        val allowance = depth * gaps[positive / 2]

        val pendingMin = LongArray(n)
        pendingMin[n - 1] = ptsUs[n - 1]
        for (i in n - 2 downTo 0) pendingMin[i] = minOf(ptsUs[i], pendingMin[i + 1])
        val decode = LongArray(n)
        var changed = false
        for (i in 0 until n) {
            var d = minOf(sorted[i], pendingMin[i] + allowance)
            if (i > 0) d = maxOf(d, minOf(sorted[i], decode[i - 1] + minStepUs))
            if (d != sorted[i]) changed = true
            decode[i] = d
        }
        if (!changed) return
        val packetEnd = decode[n - 1] + durationsUs[n - 1]
        val end = when {
            lastFromPacket && packetEnd > sorted[n - 1] -> packetEnd
            else -> sorted[n - 1] + durationsUs[n - 1]
        }
        for (i in 0 until n - 1) durationsUs[i] = decode[i + 1] - decode[i]
        durationsUs[n - 1] = end - decode[n - 1]
    }

    /**
     * Cuando el fragmento tiene que empezar a decodificarse ([decodeStartUs]) después de su primer
     * PTS ([firstPtsUs]), recupera esa diferencia dentro del propio fragmento, acortando sus primeros
     * huecos de decodificación —sin bajar de [minStepUs], para que el DTS siga avanzando—, y deja el
     * final donde estaba: en el PTS del keyframe siguiente, o en el mayor PTS más la última duración.
     * [durationsUs] parte de que el fragmento empieza en [firstPtsUs].
     *
     * Pasa cuando el fragmento empieza a presentarse antes de donde acabó el anterior: con un GOP
     * abierto, cuyos primeros fotogramas se muestran antes que el keyframe que los abre y que cerró
     * el fragmento anterior; en un corte por tamaño o por duración a mitad de un GOP con B-frames; o
     * con un primer PTS negativo, porque `tfdt` no tiene signo. Antes el fragmento entero se
     * decodificaba esa diferencia tarde y acababa esa diferencia después, así que el siguiente
     * heredaba el retraso y le sumaba el suyo: con x265 por defecto, dos fotogramas más por GOP. Los
     * desfases de composición eran cada vez más negativos, FFmpeg retrasaba el vídeo lo que midiera
     * el mayor, y la duración declarada crecía con el archivo.
     *
     * Solo cambian las muestras hasta que se alcanza su DTS de siempre; las demás, y todo lo que no
     * tiene solape, quedan exactamente igual. Si el fragmento no da para tanto —pocas muestras muy
     * juntas—, recupera lo que puede y el resto pasa al siguiente, que hará lo mismo.
     *
     * Lo que evita es que el solape se acumule, no que haya adelanto. La primera muestra se decodifica
     * en [decodeStartUs] y las que se presentan antes que ella van detrás, a [minStepUs] una de otra.
     * Con un GOP abierto, la primera es el keyframe y [decodeStartUs] su propio PTS, así que sus
     * imágenes iniciales se adelantan tantos fotogramas como imágenes iniciales haya, que puede ser
     * más que la profundidad de reordenación que acota [boundReorderOffsets]. Qué supone eso y por
     * qué no se hace más está en el KDoc de [FragmentedMp4Muxer].
     */
    fun absorbOverlap(durationsUs: LongArray, firstPtsUs: Long, decodeStartUs: Long, minStepUs: Long) {
        if (decodeStartUs <= firstPtsUs) return
        val n = durationsUs.size
        val endUs = firstPtsUs + durationsUs.sum()
        var natural = firstPtsUs
        var shifted = decodeStartUs
        for (i in 0 until n - 1) {
            natural += durationsUs[i]
            val next = maxOf(natural, shifted + minStepUs)
            durationsUs[i] = next - shifted
            shifted = next
            if (shifted == natural) return
        }
        durationsUs[n - 1] = maxOf(endUs - shifted, minStepUs)
    }

    /**
     * Con desfases sin signo, garantiza que ningún desfase de composición del fragmento quede por
     * debajo de `-maxLeadTicks`, que es lo que la lista de edición —fijada con el primer
     * fragmento— puede compensar. Adelanta lo justo el tiempo de decodificación de las muestras que
     * se pasarían, con el mismo criterio que [boundReorderOffsets]: ninguna se decodifica más tarde
     * que el menor PTS de las que quedan más ese margen. La primera muestra y el tramo total del
     * fragmento no se mueven, así que los fragmentos vecinos tampoco.
     *
     * Solo actúa si el adelanto crece respecto al del primer fragmento, lo que pasa con vídeo de
     * frecuencia variable cuyos fotogramas se espacian más que al principio. Si aun así queda algún
     * desfase negativo —la primera muestra no se puede mover—, el `trun` lo escribe como cero. Es lo
     * que les pasa a las imágenes iniciales de un GOP abierto que se adelantan más que el primer
     * fragmento: van detrás de su keyframe, que es la primera muestra (ver [absorbOverlap]).
     */
    fun capCompositionLead(durationsTicks: LongArray, offsetsTicks: LongArray, maxLeadTicks: Long) {
        val n = offsetsTicks.size
        if (offsetsTicks.all { it >= -maxLeadTicks }) return
        val dts = LongArray(n)
        for (i in 1 until n) dts[i] = dts[i - 1] + durationsTicks[i - 1]
        val end = dts[n - 1] + durationsTicks[n - 1]
        val pts = LongArray(n) { dts[it] + offsetsTicks[it] }
        val pendingMin = LongArray(n)
        pendingMin[n - 1] = pts[n - 1]
        for (i in n - 2 downTo 0) pendingMin[i] = minOf(pts[i], pendingMin[i + 1])
        val decode = LongArray(n)
        for (i in 1 until n) {
            val capped = minOf(dts[i], pendingMin[i] + maxLeadTicks)
            decode[i] = maxOf(capped, minOf(dts[i], decode[i - 1] + 1))
        }
        for (i in 0 until n - 1) durationsTicks[i] = decode[i + 1] - decode[i]
        durationsTicks[n - 1] = end - decode[n - 1]
        for (i in 0 until n) offsetsTicks[i] = pts[i] - decode[i]
    }

    /** Primera posición de [sorted] con un valor `>= value`: el rango de [value] contando empates a su favor. */
    fun lowerBound(sorted: LongArray, value: Long): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid] < value) low = mid + 1 else high = mid
        }
        return low
    }
}
