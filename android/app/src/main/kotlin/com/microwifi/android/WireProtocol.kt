package com.microwifi.android

import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object WireProtocol {
    const val PORT = 47831
    const val SAMPLE_RATE = 48_000
    const val SAMPLES = 480
    const val PAYLOAD_BYTES = SAMPLES * 2
    const val PACKET_BYTES = 8 + PAYLOAD_BYTES
    private const val MAGIC = 0x4d574631 // ASCII MWF1

    fun parseIpv4(input: String): Inet4Address? {
        val parts = input.trim().split('.')
        if (parts.size != 4) return null
        val address = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.length !in 1..3 || part.any { it !in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            address[index] = value.toByte()
        }
        val parsed = InetAddress.getByAddress(address) as Inet4Address
        // El receptor debe ser un equipo, no el propio teléfono ni un grupo de difusión.
        return parsed.takeUnless {
            it.isLoopbackAddress || it.isMulticastAddress ||
                (address[0].toInt() and 0xff) in 240..255 || address[0] == 0.toByte()
        }
    }

    class Packet {
        val bytes = ByteArray(PACKET_BYTES)
        private val buffer = ByteBuffer.wrap(bytes).putInt(MAGIC)

        // Int almacena los 32 bits sin importar el signo; el contador desborda módulo 2^32.
        fun encode(sequence: Int, samples: ShortArray) {
            require(samples.size == SAMPLES)
            buffer.position(4)
            buffer.order(ByteOrder.BIG_ENDIAN).putInt(sequence)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) buffer.putShort(sample)
        }
    }
}
