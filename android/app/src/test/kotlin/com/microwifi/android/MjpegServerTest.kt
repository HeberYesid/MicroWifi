package com.microwifi.android

import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MjpegServerTest {
    @Test
    fun comparaLaSubredIncluyendoPrefijosNoAlineados() {
        val local = ip(128.toByte())
        val peer = ip(200.toByte())
        assertTrue(sameWifiSubnet(local, peer, 25))
        assertFalse(sameWifiSubnet(local, peer, 26))
        assertFalse(sameWifiSubnet(local, peer, 32))
        assertFalse(sameWifiSubnet(local, peer, 0))
        assertFalse(sameWifiSubnet(local, ip(127), 25))
    }

    @Test
    fun soloAceptaGetVideoConCabeceraAcotada() {
        fun status(request: String, withinDeadline: Boolean = true) =
            MjpegHttp.readRequest(ByteArrayInputStream(request.toByteArray())) { withinDeadline }
        assertEquals(200, status("GET /video HTTP/1.1\r\nHost: pc\r\n\r\n"))
        assertEquals(200, status("GET /video HTTP/1.0\r\n\r\n"))
        assertEquals(404, status("GET / HTTP/1.1\r\n\r\n"))
        assertEquals(405, status("POST /video HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET /video HTTP/2\r\n\r\n"))
        assertEquals(400, status("GET /video HTTP/1.1\r\n"))
        assertEquals(400, status("GET /video HTTP/1.1\r\nX-Test: ${"a".repeat(8192)}\r\n\r\n"))
        assertEquals(400, status("GET /video HTTP/1.1\r\n\r\n", false))
    }

    @Test
    fun entregaMjpegYAlCerrarTerminaElClienteYElListener() {
        val loopback = ip(1)
        val frame = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        withServer(loopback, loopback) { server, port ->
            server.publish(frame)
            connect(loopback, port).use { socket ->
                socket.getOutputStream().write("GET /video HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                val input = socket.getInputStream()
                val expected = "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=microwifi-frame\r\n" +
                    "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nCross-Origin-Resource-Policy: same-origin\r\nConnection: close\r\n\r\n" +
                    "--microwifi-frame\r\nContent-Type: image/jpeg\r\nContent-Length: 4\r\n\r\n"
                assertArrayEquals(expected.toByteArray() + frame + byteArrayOf(13, 10), input.readNBytes(expected.length + frame.size + 2))
                server.close()
                assertEquals(-1, input.read())
            }
            try {
                connect(loopback, port).close()
                throw AssertionError("El puerto sigue escuchando")
            } catch (_: SocketException) {
                // Conexión rechazada tras el cierre.
            }
        }
    }

    @Test
    fun rechazaOtraIpv4SinConfiarEnCabeceras() {
        withServer(ip(1), ip(2)) { server, port ->
            server.publish(byteArrayOf(1, 2, 3))
            connect(ip(1), port).use { socket ->
                try {
                    socket.getOutputStream().write("GET /video HTTP/1.1\r\nX-Forwarded-For: 127.0.0.2\r\n\r\n".toByteArray())
                    assertEquals(-1, socket.getInputStream().read())
                } catch (e: SocketException) {
                    assertTrue(e.message.orEmpty().isNotEmpty()) // El cierre puede enviar un reset TCP.
                }
            }
        }
    }

    private fun withServer(local: Inet4Address, allowed: Inet4Address, test: (MjpegServer, Int) -> Unit) {
        val listening = CompletableFuture<Int>()
        val server = MjpegServer(local, allowed, { listening.complete(it) }, { listening.completeExceptionally(it) }, port = 0)
        val thread = server.start()
        try {
            test(server, listening.get(5, TimeUnit.SECONDS))
        } finally {
            server.close()
            thread.join(3_000)
            assertFalse("El hilo MJPEG sigue activo", thread.isAlive)
        }
    }

    private fun connect(address: Inet4Address, port: Int) = Socket().apply {
        try {
            soTimeout = 2_000
            connect(InetSocketAddress(address, port), 2_000)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun ip(last: Byte) = InetAddress.getByAddress(byteArrayOf(127, 0, 0, last)) as Inet4Address
}
