package com.microwifi.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WireProtocolTest {
    @Test
    fun serializaCabeceraYLas480Muestras() {
        val samples = ShortArray(480) { (it * 271).toShort() }
        samples[0] = 0x1234
        samples[1] = -2
        samples[479] = Short.MIN_VALUE
        val packet = WireProtocol.Packet()
        packet.encode(0xFEDCBA98.toInt(), samples)

        assertEquals(968, packet.bytes.size)
        assertEquals(47831, WireProtocol.PORT)
        assertArrayEquals(byteArrayOf(0x4d, 0x57, 0x46, 0x31, -2, -36, -70, -104, 0x34, 0x12, -2, -1), packet.bytes.copyOf(12))
        val pcm = ByteBuffer.wrap(packet.bytes, 8, 960).order(ByteOrder.LITTLE_ENDIAN)
        assertArrayEquals(samples, ShortArray(480) { pcm.short })
    }

    @Test
    fun reutilizaPaqueteSinConservarAudioAnterior() {
        val packet = WireProtocol.Packet()
        packet.encode(-1, ShortArray(480) { -1 })
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), packet.bytes.copyOfRange(4, 8))
        packet.encode(0, ShortArray(480))
        assertArrayEquals(byteArrayOf(0x4d, 0x57, 0x46, 0x31), packet.bytes.copyOf(4))
        assertArrayEquals(ByteArray(964), packet.bytes.copyOfRange(4, 968))
    }

    @Test(expected = IllegalArgumentException::class)
    fun noPermiteUnBloqueIncompleto() {
        WireProtocol.Packet().encode(0, ShortArray(479))
    }

    @Test
    fun validaIPv4SinConsultarDNS() {
        assertEquals("192.168.1.20", WireProtocol.parseIpv4(" 192.168.001.20 ")?.hostAddress)
        assertEquals("10.0.0.255", WireProtocol.parseIpv4("10.0.0.255")?.hostAddress)
        for (invalid in listOf("", "pc.local", "::1", "192.168.1.20:47831", "192.168.1", "192.168..1", "+1.0.0.1", "192.168.1.256",
            "127.0.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255")) {
            assertNull(invalid, WireProtocol.parseIpv4(invalid))
        }
    }
}
