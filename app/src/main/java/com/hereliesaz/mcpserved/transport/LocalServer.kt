package com.hereliesaz.mcpserved.transport

import android.util.Base64
import com.hereliesaz.mcpserved.crypto.FrameCodec
import com.hereliesaz.mcpserved.crypto.Pairing
import com.hereliesaz.mcpserved.service.Dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Accepts the desktop server on a loopback port and pumps frames through it.
 *
 * This is the inverse of the relay the app used to dial. There is no carrier NAT
 * to defeat and no cloud in the path. The socket binds all interfaces, so it is
 * reachable two ways: through an `adb forward tcp:$PORT tcp:$PORT` tunnel onto
 * loopback (USB or adb-over-Wi-Fi), and directly on the LAN by a desktop that
 * found this device over mDNS (see [LanAdvertiser]). The wildcard bind is what
 * makes the second path possible; it still accepts the loopback path adbd dials.
 *
 * The bind is not, by itself, an authorization boundary — any process on the
 * device, and now any host on the LAN, can open the port. Authorization is the
 * pairing key: a connection that cannot produce frames sealed under the shared
 * secret gets no answer and no acknowledgement that anything is listening. The
 * grant table then decides, per package, what an authenticated peer may actually
 * do. Broadening the bind from loopback to the LAN widens who can *knock*, not
 * who can *in*: an unpaired knock is met with the same silence it always was.
 *
 * Frames are handled strictly one at a time and one connection at a time. The
 * protocol matches responses to requests by ordering, and the device dispatches
 * serially anyway, so concurrency would buy nothing and cost the ordering the
 * [FrameCodec] sequence check depends on.
 */
class LocalServer(
    private val pairing: Pairing,
    private val dispatcher: Dispatcher,
    private val scope: CoroutineScope,
    private val port: Int = DEFAULT_PORT
) {

    /** Coarse state, for the notification and the console. */
    enum class State { IDLE, LISTENING, CONNECTED, UNPAIRED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "op"
    }

    private var serverSocket: ServerSocket? = null
    private var loop: Job? = null

    /**
     * The connection currently being served, if any.
     *
     * Held so [stop] can close it directly. A blocking `readLine` inside the
     * serve loop does not observe coroutine cancellation, so without this a
     * teardown while a controller is connected would wait on the peer to hang up
     * — closing the socket here is what actually unblocks the read.
     */
    @Volatile
    private var conn: Socket? = null

    @Volatile
    private var running = false

    /** Starts listening and stays up until [stop]. Idempotent. */
    fun start() {
        if (running) return
        running = true
        loop = scope.launch { acceptLoop() }
    }

    /** Stops listening and drops any live connection. Does not touch the session. */
    suspend fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { conn?.close() }
        conn = null
        loop?.cancelAndJoin()
        _state.value = State.IDLE
    }

    // ---- accept ------------------------------------------------------------

    private suspend fun acceptLoop() {
        while (scope.isActive && running) {
            val server = runCatching {
                withContext(Dispatchers.IO) {
                    ServerSocket().apply {
                        reuseAddress = true
                        // Bind the IPv4 wildcard (0.0.0.0). This accepts the LAN
                        // path a discovered desktop dials directly *and* the
                        // 127.0.0.1 path adbd forwards onto — an IPv6-only or
                        // loopback-only bind would miss one of them. The pairing
                        // key, not the bind address, is the boundary.
                        bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
                    }
                }
            }.getOrElse {
                // The port is taken or the bind was refused. Neither is fatal —
                // a previous instance may still be tearing down — so wait and retry.
                _state.value = State.IDLE
                delay(2_000)
                continue
            }

            serverSocket = server
            _state.value = if (pairing.isPaired) State.LISTENING else State.UNPAIRED

            while (scope.isActive && running) {
                val socket = runCatching {
                    withContext(Dispatchers.IO) { server.accept() }
                }.getOrNull() ?: break

                // One controller at a time. Serve inline, then loop for the next
                // connection; a second dialler simply waits in the backlog.
                conn = socket
                runCatching { serve(socket) }
                conn = null
                runCatching { socket.close() }

                if (running) {
                    _state.value = if (pairing.isPaired) State.LISTENING else State.UNPAIRED
                }
            }

            runCatching { server.close() }
        }
    }

    // ---- one connection ----------------------------------------------------

    /**
     * Serves a single connection until it closes.
     *
     * The first line is the server's [Hello], carrying the salt this connection's
     * keys are derived under. Everything after it is a stream of newline-delimited
     * [Envelope]s. A frame that fails to open is dropped without a reply, for the
     * same reason it always was: answering would confirm to an unauthenticated
     * sender that the device is here and which device it is.
     */
    private suspend fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        val reader = socket.getInputStream().bufferedReader()
        val writer = socket.getOutputStream().bufferedWriter()

        val helloLine = withContext(Dispatchers.IO) { reader.readLine() } ?: return
        val hello = runCatching { json.decodeFromString<Hello>(helloLine) }.getOrNull() ?: return
        if (hello.v != PROTO_VERSION) return
        val salt = runCatching {
            Base64.decode(hello.salt, Base64.NO_WRAP or Base64.URL_SAFE)
        }.getOrNull() ?: return

        val keys = pairing.deriveKeys(salt) ?: run {
            _state.value = State.UNPAIRED
            return
        }
        val codec = FrameCodec(sealKey = keys.deviceToServer, openKey = keys.serverToDevice)
        val aad = pairing.deviceId.toByteArray()

        _state.value = State.CONNECTED

        while (scope.isActive && running) {
            val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break

            val env = runCatching { json.decodeFromString<Envelope>(line) }.getOrNull() ?: continue
            if (env.deviceId != pairing.deviceId) continue

            val plaintext = runCatching { codec.open(env.seq, env.payload, aad) }.getOrNull()
                ?: continue

            val request = runCatching {
                json.decodeFromString<Request>(String(plaintext))
            }.getOrNull()

            val response = if (request == null) {
                Response.Err("malformed request")
            } else {
                runCatching { dispatcher.handle(request) }
                    .getOrElse { Response.Err(it.message ?: "dispatch failed") }
            }

            val sealed = codec.seal(json.encodeToString(response).toByteArray(), aad)
            val reply = Envelope(pairing.deviceId, sealed.seq, sealed.payloadB64)
            withContext(Dispatchers.IO) {
                writer.write(json.encodeToString(reply))
                writer.write("\n")
                writer.flush()
            }
        }
    }

    companion object {
        /** Loopback port the device listens on and the desktop server forwards to. */
        const val DEFAULT_PORT = 8790
    }
}
