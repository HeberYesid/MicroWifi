package com.microwifi.android

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicReference

internal fun sameWifiSubnet(local: Inet4Address, peer: Inet4Address, prefix: Int): Boolean {
    if (prefix !in 1..32) return false
    val localBits = ByteBuffer.wrap(local.address).int
    val peerBits = ByteBuffer.wrap(peer.address).int
    return (localBits xor peerBits) ushr (32 - prefix) == 0
}

internal class MjpegServer(
    private val local: Inet4Address,
    private val allowed: Inet4Address,
    private val onListening: (Int) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val port: Int = MjpegHttp.PORT,
) : Closeable {
    private val lock = Any()
    @Volatile private var closed = false
    private var listener: ServerSocketChannel? = null
    private var client: SocketChannel? = null
    private val latest = AtomicReference<ByteArray>()

    fun start(): Thread = Thread({ serve() }, "MicroWifi-MJPEG").apply { start() }

    fun publish(jpeg: ByteArray) {
        if (!closed) latest.set(jpeg)
    }

    override fun close() = synchronized(lock) {
        closed = true
        runCatching { client?.close() }
        runCatching { listener?.close() }
        latest.set(null)
    }

    private fun serve() {
        try {
            ServerSocketChannel.open().use { server ->
                synchronized(lock) {
                    if (closed) return
                    listener = server
                }
                server.socket().reuseAddress = true
                server.bind(InetSocketAddress(local, port), 4)
                if (closed) return
                onListening(server.socket().localPort)
                while (!closed) {
                    server.accept().use { channel ->
                        val socket = channel.socket()
                        // Se compara la IP real del socket, nunca cabeceras que pueda enviar el cliente.
                        if (socket.inetAddress != allowed) return@use
                        synchronized(lock) {
                            if (closed) return
                            client = channel
                        }
                        try {
                            socket.soTimeout = 500
                            socket.tcpNoDelay = true
                            val deadline = System.nanoTime() + 2_000_000_000L
                            val input = BufferedInputStream(socket.getInputStream())
                            val response = MjpegHttp.readRequest(input) { System.nanoTime() < deadline }
                            channel.configureBlocking(false)
                            write(channel, MjpegHttp.response(response))
                            if (response == 200) stream(channel)
                        } catch (_: IOException) {
                            // El cliente puede desconectarse o dejar de leer; el siguiente puede volver a conectar.
                        } finally {
                            synchronized(lock) { client = null }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!closed) onFailure(e)
        } finally {
            close()
        }
    }

    private fun stream(channel: SocketChannel) {
        var previous: ByteArray? = null
        var waitUntil = System.nanoTime() + 8_000_000_000L
        while (!closed && System.nanoTime() < waitUntil) {
            val jpeg = latest.get()
            if (jpeg != null && jpeg !== previous) {
                write(channel, MjpegHttp.partHeader(jpeg.size))
                write(channel, jpeg)
                write(channel, MjpegHttp.CRLF)
                previous = jpeg
                waitUntil = System.nanoTime() + 8_000_000_000L
            } else {
                Thread.sleep(20)
            }
        }
    }

    private fun write(channel: SocketChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        val deadline = System.nanoTime() + 2_000_000_000L
        while (buffer.hasRemaining()) {
            if (closed || System.nanoTime() >= deadline) throw IOException("Escritura MJPEG interrumpida")
            if (channel.write(buffer) == 0) Thread.sleep(10)
        }
    }
}

internal object MjpegHttp {
    const val PORT = 47832
    const val PATH = "/video"
    private const val BOUNDARY = "microwifi-frame"
    val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)

    fun readRequest(input: InputStream, withinDeadline: () -> Boolean): Int {
        val header = StringBuilder()
        while (header.length < 8192 && withinDeadline()) {
            val value = input.read()
            if (value == -1) return 400
            header.append(value.toChar())
            if (header.endsWith("\r\n\r\n")) {
                val fields = header.substring(0, header.indexOf("\r\n")).split(' ')
                if (fields.size != 3 || (fields[2] != "HTTP/1.0" && fields[2] != "HTTP/1.1")) return 400
                if (fields[0] != "GET") return 405
                return if (fields[1] == PATH) 200 else 404
            }
        }
        return 400
    }

    fun response(code: Int): ByteArray {
        val status = when (code) {
            200 -> "200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nCross-Origin-Resource-Policy: same-origin"
            404 -> "404 Not Found\r\nContent-Length: 0"
            405 -> "405 Method Not Allowed\r\nAllow: GET\r\nContent-Length: 0"
            else -> "400 Bad Request\r\nContent-Length: 0"
        }
        return "HTTP/1.1 $status\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
    }

    fun partHeader(length: Int): ByteArray =
        "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: $length\r\n\r\n".toByteArray(Charsets.US_ASCII)
}
