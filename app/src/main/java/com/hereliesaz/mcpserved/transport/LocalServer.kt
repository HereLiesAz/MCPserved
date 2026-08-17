package com.hereliesaz.mcpserved.transport

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
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
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
 * A third, opt-in path — [setIpv6Enabled] — binds the device's own global IPv6
 * address directly: no NAT exists for IPv6 to begin with, so this is the
 * closest thing to the phone hosting itself with nothing else in the way,
 * limited only by whether the network's firewall allows unsolicited inbound.
 *
 * The bind is not, by itself, an authorization boundary — any process on the
 * device, and now any host on the LAN or (opt-in) the wider internet, can open
 * the port. Authorization is the pairing key: a connection that cannot produce
 * frames sealed under the shared secret gets no answer and no acknowledgement
 * that anything is listening. The grant table then decides, per package, what
 * an authenticated peer may actually do. Broadening the bind widens who can
 * *knock*, not who can *in*: an unpaired knock is met with the same silence it
 * always was.
 *
 * Each accept loop serves one connection at a time on its own socket — the
 * protocol matches responses to requests by ordering, which holds only within
 * a single connection. Running the IPv4 and IPv6 loops concurrently can put
 * two connections in flight across the two sockets at once; [Dispatcher]
 * guards its own state with a mutex specifically so that stays safe, rather
 * than requiring a single global accept loop to make it true by construction.
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

    private val session = FrameSession(pairing, dispatcher)

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

    private var ipv6Job: Job? = null

    @Volatile
    private var ipv6Socket: ServerSocket? = null

    /** Starts listening and stays up until [stop]. Idempotent. */
    fun start() {
        if (running) return
        running = true
        loop = scope.launch { acceptLoop() }
    }

    /** Stops listening and drops any live connection. Does not touch the session. */
    suspend fun stop() {
        running = false
        ipv6Job?.cancelAndJoin()
        ipv6Job = null
        runCatching { ipv6Socket?.close() }
        ipv6Socket = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { conn?.close() }
        conn = null
        loop?.cancelAndJoin()
        _state.value = State.IDLE
    }

    /**
     * Turns the opt-in IPv6 listener on or off. Off by default, off again once
     * `stop()` runs.
     *
     * Unlike [start]'s IPv4 wildcard bind, this binds a specific global
     * unicast IPv6 address — not the IPv6 wildcard `::`, which on most stacks
     * would silently steal the port from the IPv4 socket already bound to
     * `0.0.0.0` above. Binding a concrete address instead means the two never
     * contend, at the cost of tracking that address across the renewals and
     * privacy-address rotations IPv6 hosts do on their own; see
     * [ipv6SuperviseLoop].
     */
    fun setIpv6Enabled(enabled: Boolean) {
        if (enabled == (ipv6Job != null)) return
        if (enabled) {
            ipv6Job = scope.launch { ipv6SuperviseLoop() }
        } else {
            ipv6Job?.cancel()
            ipv6Job = null
            runCatching { ipv6Socket?.close() }
            ipv6Socket = null
        }
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

    // ---- IPv6, opt-in --------------------------------------------------------

    /**
     * Rebinds to the device's current global IPv6 address whenever it changes,
     * and runs an accept loop on it meanwhile.
     *
     * There is no NAT to defeat here — a global IPv6 address is routable as-is,
     * which is what makes this worth doing at all: on a network that does not
     * firewall unsolicited inbound (not guaranteed, but common, and unlike
     * IPv4 CGNAT it is not architecturally *impossible* the way IPv4 on
     * cellular is), a host with IPv6 connectivity can dial straight in with no
     * relay, no router cooperation, and no UPnP. The address itself is not
     * stable, though: Android rotates privacy addresses (RFC 4941) and a
     * network change replaces it outright, so this polls rather than binding
     * once and assuming it stays valid.
     */
    private suspend fun ipv6SuperviseLoop() {
        var bound: InetAddress? = null
        while (scope.isActive) {
            val current = withContext(Dispatchers.IO) { findGlobalIpv6Address() }
            if (current != bound) {
                runCatching { ipv6Socket?.close() }
                ipv6Socket = null
                bound = current
                if (current != null) {
                    val socket = runCatching {
                        withContext(Dispatchers.IO) {
                            ServerSocket().apply {
                                reuseAddress = true
                                bind(InetSocketAddress(current, port))
                            }
                        }
                    }.getOrNull()
                    if (socket != null) {
                        ipv6Socket = socket
                        scope.launch { ipv6AcceptLoop(socket) }
                    }
                }
            }
            delay(IPV6_RECHECK_INTERVAL_MS)
        }
    }

    /** One connection at a time, same shape as [acceptLoop]'s inner loop. */
    private suspend fun ipv6AcceptLoop(server: ServerSocket) {
        while (scope.isActive && server === ipv6Socket) {
            val socket = runCatching {
                withContext(Dispatchers.IO) { server.accept() }
            }.getOrNull() ?: break
            runCatching { serve(socket) }
            runCatching { socket.close() }
        }
    }

    /**
     * The device's own global-scope IPv6 address, if it has one — not
     * link-local (`fe80::/10`), not a unique-local address (`fc00::/7`, the
     * IPv6 analogue of RFC 1918 private ranges), not loopback or multicast.
     * Picks the first candidate found; a device with more than one (a stable
     * SLAAC address alongside a rotating privacy one, say) exposes only that
     * one at a time, which is an acceptable limitation for a best-effort path.
     */
    private fun findGlobalIpv6Address(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet6Address>()
            .firstOrNull(::isGlobalUnicast)
    }.getOrNull()

    private fun isGlobalUnicast(addr: Inet6Address): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isMulticastAddress) return false
        val firstByte = addr.address[0].toInt() and 0xFF
        return firstByte and 0xFE != 0xFC // exclude fc00::/7 (unique local)
    }

    // ---- one connection ----------------------------------------------------

    /**
     * Serves a single connection until it closes.
     *
     * The handshake and dispatch logic live in [FrameSession] — shared, byte for
     * byte, with [RemoteRelayClient]'s dial-out path. This method's only job is
     * to wrap the accepted [Socket] as a [FrameStream] and reflect the
     * connected/unpaired state this class exposes.
     */
    private suspend fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        val stream = SocketFrameStream(socket)
        try {
            val ended = session.run(
                stream,
                isActive = { scope.isActive && running },
                onConnected = { _state.value = State.CONNECTED },
            )
            if (ended == FrameSession.EndReason.UNPAIRED) _state.value = State.UNPAIRED
        } finally {
            stream.close()
        }
    }

    companion object {
        /** Loopback port the device listens on and the desktop server forwards to. */
        const val DEFAULT_PORT = 8790

        /** How often the opt-in IPv6 listener rechecks its bound address. */
        private const val IPV6_RECHECK_INTERVAL_MS = 5 * 60_000L
    }
}
