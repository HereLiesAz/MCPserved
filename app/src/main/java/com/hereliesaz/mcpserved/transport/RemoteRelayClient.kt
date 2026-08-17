package com.hereliesaz.mcpserved.transport

import com.hereliesaz.mcpserved.crypto.Pairing
import com.hereliesaz.mcpserved.service.Dispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response as OkResponse
import okio.ByteString
import kotlin.math.min

/**
 * Dials the opt-in relay as the "device" role, and feeds the connection into
 * the same [FrameSession] that [LocalServer]'s accept loop uses locally.
 *
 * This is the *only* opt-in remote-access path that leaves the device — see
 * [com.hereliesaz.mcpserved.grant.RemoteAccessStore]'s doc for the other one
 * (a wider [McpServer] bind, for a private mesh). It carries the sealed-frame
 * protocol, not [McpServer]'s bearer-token HTTP: the relay operator, whoever
 * that is, forwards ciphertext it cannot open. See `relay/README.md` and
 * `docs/guide/remote-access.md`.
 *
 * The device always plays the same protocol role here as it does when a
 * desktop dials in locally: it reads the peer's [Hello] first and never
 * writes one — the relay's "host" side (the desktop bridge or the reference
 * TS client, dialing the same room as role `host`) is the one that opens the
 * handshake, exactly as it does over `adb forward` or LAN today.
 *
 * Reconnects with exponential backoff on any drop — a relay bounce, a network
 * blip, an idle-evicted room — since this is a long-lived background dial-out,
 * not a one-shot request.
 */
class RemoteRelayClient(
    private val relayUrl: String,
    private val roomToken: String,
    pairing: Pairing,
    dispatcher: Dispatcher,
    private val scope: CoroutineScope,
) {
    private val session = FrameSession(pairing, dispatcher)
    private val client = OkHttpClient()

    @Volatile
    private var running = false
    private var job: Job? = null

    /** Starts the dial-out loop. Idempotent. */
    fun start() {
        if (running) return
        running = true
        job = scope.launch { connectLoop() }
    }

    /** Stops dialing and drops any live connection. */
    fun stop() {
        running = false
        job?.cancel()
    }

    private suspend fun connectLoop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (scope.isActive && running) {
            val connected = runCatching { dialOnce() }.getOrDefault(false)
            if (!scope.isActive || !running) return
            backoffMs = if (connected) INITIAL_BACKOFF_MS else min(backoffMs * 2, MAX_BACKOFF_MS)
            delay(backoffMs)
        }
    }

    /**
     * Dials once and serves until the connection drops.
     *
     * Returns whether the relay connection itself was established — distinct
     * from whether a peer ever showed up in the room — since that alone is
     * enough to reset the backoff: the relay is reachable, the failure (if
     * any) that follows is a room/peer matter, not a network one.
     */
    private suspend fun dialOnce(): Boolean {
        val stream = WebSocketFrameStream(client, dialUrl())
        try {
            val opened = stream.awaitOpen()
            if (!opened) return false
            session.run(stream) { scope.isActive && running }
            return true
        } finally {
            stream.close()
        }
    }

    private fun dialUrl(): String {
        val base = relayUrl.trimEnd('/')
        return "$base/connect?room=$roomToken&role=device"
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}

/**
 * [FrameStream] over an OkHttp [WebSocket], one WS text message per line —
 * the WebSocket framing already gives message boundaries, so no trailing
 * newline is needed on the wire despite [FrameSession] speaking in lines.
 */
private class WebSocketFrameStream(
    client: OkHttpClient,
    url: String,
) : FrameStream {

    private val incoming = Channel<String?>(Channel.UNLIMITED)
    private val opened = CompletableDeferred<Boolean>()
    private val socket: WebSocket

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: OkResponse) {
            opened.complete(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            incoming.trySend(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            incoming.trySend(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            opened.complete(false)
            incoming.trySend(null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
            opened.complete(false)
            incoming.trySend(null)
        }
    }

    init {
        socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    /** Suspends until the socket opens or fails; true only on a genuine open. */
    suspend fun awaitOpen(): Boolean = opened.await()

    override suspend fun readLine(): String? = incoming.receiveCatching().getOrNull()

    override suspend fun writeLine(line: String) {
        socket.send(line)
    }

    override fun close() {
        socket.close(NORMAL_CLOSURE, null)
        incoming.close()
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
