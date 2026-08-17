package com.hereliesaz.mcpserved.desktop.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A duplex, line-delimited byte stream [AppLink] can run the sealed-frame
 * handshake and exchange over — a live [Socket] for the adb-forward/LAN
 * paths, or [RelayFrameStream] for the opt-in relay path. The wire format is
 * already line-delimited (one JSON object per line), so this is the entire
 * seam between "the protocol" and "how bytes actually move." Mirrors the
 * `FrameStream` abstraction on the Android side (`transport/FrameSession.kt`).
 */
interface FrameStream {
    /** Reads one line, blocking, or null at end-of-stream / timeout. */
    fun readLine(timeoutMs: Long): String?

    /** Writes one line. */
    fun writeLine(line: String)

    fun close()
}

/** [FrameStream] over a plain [Socket] — the adb-forward and LAN paths. */
class SocketFrameStream(private val socket: Socket) : FrameStream {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val output = socket.getOutputStream()

    override fun readLine(timeoutMs: Long): String? {
        socket.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
        return try {
            reader.readLine()
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override fun writeLine(line: String) {
        output.write((line + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * [FrameStream] over a relay's WebSocket, dialed as the "host" role — the
 * counterpart to the device's `RemoteRelayClient` (role "device") on the
 * other end of the same room. See `relay/README.md`: the relay forwards
 * bytes verbatim and never decrypts anything, since what travels here is
 * already the sealed-frame protocol, unchanged.
 *
 * `java.net.http.WebSocket`'s listener is asynchronous; this class bridges it
 * to the blocking `readLine`/`writeLine` shape [AppLink] already speaks with
 * a simple queue, so the rest of [AppLink] needs no async-awareness at all.
 */
class RelayFrameStream(relayUrl: String, roomToken: String) : FrameStream {
    private val incoming = LinkedBlockingQueue<String?>()
    private val client = HttpClient.newHttpClient()

    private val socket: WebSocket = client.newWebSocketBuilder()
        .buildAsync(URI.create(dialUri(relayUrl, roomToken)), Listener())
        .get(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)

    private inner class Listener : WebSocket.Listener {
        private val textBuffer = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            textBuffer.append(data)
            if (last) {
                incoming.put(textBuffer.toString())
                textBuffer.setLength(0)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            incoming.put(null)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            incoming.put(null)
        }
    }

    override fun readLine(timeoutMs: Long): String? = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun writeLine(line: String) {
        socket.sendText(line, true).get(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    override fun close() {
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS) }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SEC = 15L

        /** `wss://host/connect?room=<token>&role=host` from a bare relay URL. */
        fun dialUri(relayUrl: String, roomToken: String): String {
            val base = relayUrl.trimEnd('/')
            return "$base/connect?room=$roomToken&role=host"
        }
    }
}

/** Dials the device's loopback port via an already-established `adb forward`. */
fun connectSocket(host: String, port: Int, timeoutMs: Int): SocketFrameStream {
    val sock = Socket()
    sock.connect(InetSocketAddress(host, port), timeoutMs)
    sock.tcpNoDelay = true
    return SocketFrameStream(sock)
}
