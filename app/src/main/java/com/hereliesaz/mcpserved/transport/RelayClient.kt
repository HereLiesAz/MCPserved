package com.hereliesaz.mcpserved.transport

import com.hereliesaz.mcpserved.crypto.FrameCodec
import com.hereliesaz.mcpserved.crypto.Pairing
import com.hereliesaz.mcpserved.service.Dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import okhttp3.Request as HttpRequest
import okhttp3.Response as HttpResponse

/**
 * Maintains the outbound connection to the relay and pumps frames through it.
 *
 * Both ends dial out. Carrier-grade NAT means the device has no reachable
 * address, so nothing can connect *to* it; the relay exists solely because two
 * dialling peers need something in the middle to be dialled at.
 *
 * The socket is treated as unreliable by design rather than by accident. Doze,
 * cell handoff, Wi-Fi transitions, and OEM battery managers that disregard the
 * foreground-service contract will all drop it, and none of those are failures
 * to report — they are the normal operating environment. Reconnection is
 * therefore silent and indefinite, and a dropped socket never ends a session.
 *
 * Frames are handled strictly one at a time. The relay's Durable Object already
 * guarantees a single session per device, so concurrency here would buy nothing
 * and cost the ordering that [FrameCodec]'s monotonic sequence check depends on.
 */
class RelayClient(
    private val pairing: Pairing,
    private val dispatcher: Dispatcher,
    private val scope: CoroutineScope
) {

    /** Coarse connection state, for the notification and the console. */
    enum class State { IDLE, CONNECTING, CONNECTED, UNPAIRED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "op"
    }

    private val http = OkHttpClient.Builder()
        // The relay sends its own keepalive; this is the client half. Twenty
        // seconds is short enough that a half-open socket is noticed before the
        // caller times out waiting on a response that will never arrive.
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Codec for the current pairing.
     *
     * Held across reconnects. Rebuilding it would rewind the outbound sequence
     * counter and replay nonces under the same key — on the exact failure path
     * this transport is built to survive.
     */
    private var codec: FrameCodec? = null

    private var socket: WebSocket? = null
    private var pump: Job? = null
    private var loop: Job? = null

    /** Inbound frames, decoupled from the OkHttp callback thread. */
    private val inbound = Channel<Envelope>(Channel.BUFFERED)

    /** True while the connect loop should keep trying. */
    @Volatile
    private var running = false

    /**
     * Starts connecting and stays connected until [stop].
     *
     * Idempotent; a second call while running is ignored rather than opening a
     * second socket the relay would immediately displace.
     */
    fun start() {
        if (running) return
        running = true
        loop = scope.launch { connectLoop() }
        pump = scope.launch { pumpLoop() }
    }

    /** Stops reconnecting and closes the socket. Does not touch the session. */
    suspend fun stop() {
        running = false
        socket?.close(1000, "stopping")
        socket = null
        loop?.cancelAndJoin()
        pump?.cancelAndJoin()
        _state.value = State.IDLE
    }

    /**
     * Forces an immediate reconnect.
     *
     * Called from the FCM wake path. A socket the platform has quietly killed
     * still looks open from this side, so the existing one is closed rather than
     * checked — asking is slower and less reliable than simply redialling.
     */
    fun wake() {
        if (!running) {
            start()
            return
        }
        socket?.close(1001, "wake")
        socket = null
    }

    // ---- connection ---------------------------------------------------------

    private suspend fun connectLoop() {
        var attempt = 0
        while (scope.isActive && running) {
            val keys = pairing.deriveKeys()
            if (keys == null) {
                _state.value = State.UNPAIRED
                delay(5_000)
                continue
            }
            if (codec == null) {
                codec = FrameCodec(
                    sealKey = keys.deviceToServer,
                    openKey = keys.serverToDevice
                )
            }

            _state.value = State.CONNECTING
            val opened = openSocket()
            if (opened) {
                attempt = 0
            } else {
                attempt++
            }

            if (!running) break
            delay(backoff(attempt))
        }
    }

    /**
     * Opens one socket and suspends until it closes.
     *
     * @return true if the socket ever reached the open state, which resets the
     *   backoff. A socket that opened and then dropped is a healthy relay and a
     *   flaky network; one that never opened may be a relay that is down, and
     *   only the latter deserves escalating delay.
     */
    private suspend fun openSocket(): Boolean {
        val done = Channel<Boolean>(1)
        var everOpen = false

        val req = HttpRequest.Builder()
            .url(pairing.relayUrl)
            .header("X-Device-Id", pairing.deviceId)
            .build()

        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: HttpResponse) {
                everOpen = true
                _state.value = State.CONNECTED
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val env = runCatching { json.decodeFromString<Envelope>(text) }.getOrNull()
                    ?: return
                inbound.trySend(env)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                done.trySend(everOpen)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: HttpResponse?) {
                done.trySend(everOpen)
            }
        })

        return done.receive()
    }

    /**
     * Exponential backoff with full jitter, capped at a minute.
     *
     * Jittered because every device on a relay that has just restarted would
     * otherwise redial in lockstep and knock it over again.
     */
    private fun backoff(attempt: Int): Long {
        if (attempt == 0) return 500
        val ceiling = (1_000L shl attempt.coerceAtMost(6)).coerceAtMost(60_000)
        return Random.nextLong(500, ceiling)
    }

    // ---- frame handling -----------------------------------------------------

    /**
     * Serially decrypts, dispatches, and replies.
     *
     * A frame that fails to open is dropped without a reply. Answering would
     * confirm to an unauthenticated sender that the device is present and which
     * device it is, and there is no legitimate peer that can produce an
     * unopenable frame in the first place.
     */
    private suspend fun pumpLoop() {
        val aad = pairing.deviceId.toByteArray()

        for (env in inbound) {
            val c = codec ?: continue
            if (env.deviceId != pairing.deviceId) continue

            val plaintext = runCatching { c.open(env.seq, env.payload, aad) }.getOrNull()
                ?: continue

            val request = runCatching {
                json.decodeFromString<Request>(String(plaintext))
            }.getOrElse {
                reply(Response.Err("malformed request"), aad)
                continue
            }

            val response = runCatching { dispatcher.handle(request) }
                .getOrElse { Response.Err(it.message ?: "dispatch failed") }

            reply(response, aad)
        }
    }

    private fun reply(response: Response, aad: ByteArray) {
        val c = codec ?: return
        val ws = socket ?: return
        val sealed = c.seal(json.encodeToString(response).toByteArray(), aad)
        val env = Envelope(pairing.deviceId, sealed.seq, sealed.payloadB64)
        ws.send(json.encodeToString(env))
    }
}
