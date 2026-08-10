package com.braymon.kotmpeg.codecconfig

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Configuración de Opus. El formato canónico de codec-private es la cabecera de
 * identificación `OpusHead` (RFC 7845), que MKV guarda literal; la caja `dOps` de MP4
 * lleva los mismos campos en big-endian y sin la firma.
 */
public object OpusConfig {

    private val MAGIC = "OpusHead".toByteArray(Charsets.US_ASCII)

    public class Parsed(
        public val channelCount: Int,
        public val preSkip: Int,
        public val inputSampleRate: Int,
        public val outputGain: Int,
        public val mappingFamily: Int,
        /**
         * Tabla de mapeo de canales de las familias distintas de 0 (Opus 5.1/7.1):
         * `[streamCount, coupledCount, mapping...]`. Null en la familia 0 (mono/estéreo),
         * que no lleva tabla.
         */
        public val channelMapping: ByteArray? = null,
    ) {
        /** Duración de cebado implicada por preSkip (preSkip va en muestras a 48 kHz). */
        public val codecDelayUs: Long get() = preSkip * 1_000_000L / 48000L
    }

    public fun buildOpusHead(
        channelCount: Int,
        preSkip: Int = 312,
        inputSampleRate: Int = 48000,
        outputGain: Int = 0,
        /** Familia de mapeo de canales: 0 para mono/estéreo, 1 para 5.1/7.1 (Vorbis). */
        mappingFamily: Int = 0,
        /** `[streamCount, coupledCount, mapping...]`, obligatorio si [mappingFamily] != 0. */
        channelMapping: ByteArray? = null,
    ): ByteArray {
        require(channelCount in 1..255) { "channelCount inválido: $channelCount" }
        require(!(mappingFamily == 0 && channelCount > 2)) {
            "la familia de mapeo 0 solo admite 1-2 canales"
        }
        if (mappingFamily != 0) {
            requireNotNull(channelMapping) { "la familia de mapeo $mappingFamily requiere tabla de canales" }
            require(channelMapping.size == 2 + channelCount) {
                "la tabla de mapeo debe tener 2 + $channelCount bytes"
            }
        }
        val extra = channelMapping?.size ?: 0
        val buf = ByteBuffer.allocate(19 + extra).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.put(1)                        // versión
        buf.put(channelCount.toByte())
        buf.putShort(preSkip.toShort())
        buf.putInt(inputSampleRate)
        buf.putShort(outputGain.toShort())
        buf.put(mappingFamily.toByte())
        channelMapping?.let { buf.put(it) }
        return buf.array()
    }

    public fun parseOpusHead(head: ByteArray): Parsed {
        require(head.size >= 19 && head.copyOfRange(0, 8).contentEquals(MAGIC)) { "no es una cabecera OpusHead" }
        val buf = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(9)
        val channels = buf.get().toInt() and 0xFF
        require(channels in 1..255) { "OpusHead con 0 canales" }
        val preSkip = buf.short.toInt() and 0xFFFF
        val rate = buf.int
        val gain = buf.short.toInt()
        val family = buf.get().toInt() and 0xFF
        require(!(family == 0 && channels > 2)) {
            "OpusHead inválida: familia de mapeo 0 solo admite 1-2 canales, declara $channels"
        }
        val mapping = if (family != 0) {
            val needed = 2 + channels
            require(head.size >= 19 + needed) {
                "OpusHead de familia $family sin tabla de mapeo completa"
            }
            head.copyOfRange(19, 19 + needed)
        } else {
            null
        }
        return Parsed(channels, preSkip, rate, gain, family, mapping)
    }

    /** Convierte una cabecera OpusHead a la carga de una caja `dOps` de MP4. */
    public fun opusHeadToDops(head: ByteArray): ByteArray {
        val p = parseOpusHead(head)
        val mapping = p.channelMapping
        val buf = ByteBuffer.allocate(11 + (mapping?.size ?: 0)).order(ByteOrder.BIG_ENDIAN)
        buf.put(0)                        // Version
        buf.put(p.channelCount.toByte())
        buf.putShort(p.preSkip.toShort())
        buf.putInt(p.inputSampleRate)
        buf.putShort(p.outputGain.toShort())
        buf.put(p.mappingFamily.toByte())
        mapping?.let { buf.put(it) }
        return buf.array()
    }

    /** Convierte la carga de una caja `dOps` de MP4 a una cabecera OpusHead. */
    public fun dopsToOpusHead(dops: ByteArray): ByteArray {
        require(dops.size >= 11) { "dOps demasiado corto" }
        val buf = ByteBuffer.wrap(dops).order(ByteOrder.BIG_ENDIAN)
        buf.get()                         // Version
        val channels = buf.get().toInt() and 0xFF
        val preSkip = buf.short.toInt() and 0xFFFF
        val rate = buf.int
        val gain = buf.short.toInt()
        val family = buf.get().toInt() and 0xFF
        val mapping = if (family != 0) {
            require(dops.size >= 11 + 2 + channels) { "dOps de familia $family sin tabla de mapeo" }
            dops.copyOfRange(11, 11 + 2 + channels)
        } else {
            null
        }
        return buildOpusHead(channels, preSkip, rate, gain, family, mapping)
    }
}
