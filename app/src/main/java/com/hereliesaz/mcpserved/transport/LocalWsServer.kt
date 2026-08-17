package com.hereliesaz.mcpserved.transport

import android.util.Log
import com.hereliesaz.mcpserved.crypto.KeyPairing
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * A local, loopback-only WebSocket endpoint for [FrameSession] — the shape a
 * self-hosted tunnel needs, since Cloudflare's anonymous Quick Tunnels
 * (`cloudflared tunnel --url`, see [CloudflareTunnel]) proxy HTTP/WebSocket
 * traffic only, not [LocalServer]'s raw TCP.
 *
 * Loopback (`127.0.0.1`), never wildcard: this is meant to be reached
 * exclusively through a tunnel pointed at it, not directly on the LAN — a
 * device already has [LocalServer]'s wildcard TCP bind and, opt-in,
 * [LocalServer.setIpv6Enabled] for that. Widening this one too would just be
 * a second, redundant way in.
 *
 * Every accepted WebSocket becomes its own [FrameStream] running the same
 * [FrameSession] handshake and dispatch as every other transport — the
 * protocol does not know or care that its "socket" here is a WebSocket frame
 * queue instead of a real [java.net.Socket].
 */
class LocalWsServer(
    pairing: KeyPairing,
    dispatcher: RequestHandler,
    private val scope: CoroutineScope,
    port: Int = DEFAULT_PORT,
) : NanoWSD(LOOPBACK, port) {

    private val session = FrameSession(pairing, dispatcher)

    /** Starts listening. Returns false if the port could not be bound. */
    fun startServer(): Boolean = try {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        true
    } catch (e: Exception) {
        Log.w(TAG, "LocalWsServer failed to bind on $LOOPBACK", e)
        false
    }

    /** Stops listening. Safe to call whether or not [startServer] succeeded. */
    fun stopServer() {
        runCatching { stop() }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = ServerWebSocket(handshake)

    private inner class ServerWebSocket(handshakeRequest: IHTTPSession) : WebSocket(handshakeRequest) {

        private val incoming = Channel<String?>(Channel.UNLIMITED)

        private val stream = object : FrameStream {
            override suspend fun readLine(): String? = incoming.receiveCatching().getOrNull()

            override suspend fun writeLine(line: String) = withContext(Dispatchers.IO) {
                send(line)
            }

            override fun close() {
                runCatching {
                    this@ServerWebSocket.close(WebSocketFrame.CloseCode.NormalClosure, "done", false)
                }
                incoming.close()
            }
        }

        override fun onOpen() {
            scope.launch {
                try {
                    session.run(stream, isActive = { scope.isActive })
                } finally {
                    stream.close()
                }
            }
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            incoming.trySend(null)
        }

        override fun onMessage(message: WebSocketFrame) {
            if (message.opCode == WebSocketFrame.OpCode.Text) {
                incoming.trySend(message.textPayload)
            }
        }

        override fun onPong(pong: WebSocketFrame) {}

        override fun onException(exception: IOException) {
            incoming.trySend(null)
        }
    }

    companion object {
        private const val TAG = "LocalWsServer"

        /** Distinct from [LocalServer.DEFAULT_PORT] and [McpServer.DEFAULT_HTTP_PORT] — a third, WS-only listener. */
        const val DEFAULT_PORT = 8795
        private const val LOOPBACK = "127.0.0.1"
    }
}
