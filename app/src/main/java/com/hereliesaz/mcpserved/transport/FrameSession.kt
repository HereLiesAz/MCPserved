package com.hereliesaz.mcpserved.transport

import android.util.Base64
import com.hereliesaz.mcpserved.crypto.FrameCodec
import com.hereliesaz.mcpserved.crypto.KeyPairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Socket

/**
 * A duplex, line-delimited byte stream [FrameSession] can run the sealed-frame
 * protocol over.
 *
 * The wire format is already line-delimited — one JSON object per line — so this
 * is the entire seam between "the protocol" and "how bytes actually move."
 * [SocketFrameStream] is a live [Socket]'s streams, [LocalServer]'s accept path.
 * [RemoteRelayClient] implements it over a WebSocket message stream for the
 * relay dial-out path. Both drive the identical [FrameSession].
 */
interface FrameStream {
    /** Reads one line, suspending until one arrives, or null at end-of-stream. */
    suspend fun readLine(): String?

    /** Writes one line and flushes it. */
    suspend fun writeLine(line: String)

    fun close()
}

/** [FrameStream] over a plain [Socket]'s streams — used by [LocalServer]'s accept loop. */
class SocketFrameStream(socket: Socket) : FrameStream {
    private val reader = socket.getInputStream().bufferedReader()
    private val writer = socket.getOutputStream().bufferedWriter()

    override suspend fun readLine(): String? =
        withContext(Dispatchers.IO) { reader.readLine() }

    override suspend fun writeLine(line: String) = withContext(Dispatchers.IO) {
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }

    override fun close() {
        runCatching { reader.close() }
        runCatching { writer.close() }
    }
}

/**
 * Runs one sealed-frame connection to completion over a [FrameStream].
 *
 * Extracted from [LocalServer], which used to inline this directly over a raw
 * [Socket]. The device always plays the same role regardless of transport: it
 * reads the peer's [Hello] and replies, never sends [Hello] first — so a
 * session dialed out to a relay by [RemoteRelayClient] behaves identically to
 * one [LocalServer.acceptLoop] accepted locally, and both share every line of
 * handshake and dispatch logic below.
 *
 * A frame that fails to open is dropped without a reply, for the same reason it
 * always was: answering would confirm to an unauthenticated sender that the
 * device is here and which device it is.
 */
class FrameSession(
    private val pairing: KeyPairing,
    private val dispatcher: RequestHandler,
) {
    enum class EndReason { STREAM_CLOSED, UNPAIRED, STOPPED }

    /**
     * Salts already consumed by a handshake, newest last.
     *
     * The device contributes no entropy to the key derivation: the keys are a
     * function of the static X25519 secret and the peer-chosen salt alone. A
     * recorded session could therefore be replayed verbatim on a new connection
     * (same salt -> same keys -> the frame counter restarts at zero and the old
     * frames open again, and the device would seal fresh replies under the old
     * key+nonce pairs). Refusing a salt that was already used closes that without
     * changing the wire format; an honest peer picks a fresh random salt per
     * connection, so it never collides.
     */
    private val usedSalts = LinkedHashSet<String>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "op"
    }

    /**
     * Serves [stream] until it closes or [isActive] returns false.
     *
     * [isActive] is polled between frames so a caller with its own lifecycle
     * (a coroutine scope's cancellation, a "still connected" flag) can stop the
     * loop without needing to close the stream itself. [onConnected] fires once,
     * right after the peer's keys are derived successfully — a caller tracking
     * connection state (e.g. [LocalServer]'s notification-facing [LocalServer.State])
     * should flip to "connected" there, not before, so a handshake that turns
     * out to be unpaired never reports a connection that did not happen.
     */
    suspend fun run(
        stream: FrameStream,
        isActive: () -> Boolean = { true },
        onConnected: () -> Unit = {},
    ): EndReason {
        // Bound the wait for the opening Hello. Without it a peer that connects
        // and says nothing pins this session (and, for [LocalServer], the single
        // serving slot) forever. Returning closes the stream via the caller.
        val helloLine = withTimeoutOrNull(HELLO_TIMEOUT_MS) { stream.readLine() }
            ?: return EndReason.STREAM_CLOSED
        val hello = runCatching { json.decodeFromString<Hello>(helloLine) }.getOrNull()
            ?: return EndReason.STREAM_CLOSED
        if (hello.v != PROTO_VERSION) return EndReason.STREAM_CLOSED
        val salt = runCatching {
            Base64.decode(hello.salt, Base64.NO_WRAP or Base64.URL_SAFE)
        }.getOrNull() ?: return EndReason.STREAM_CLOSED

        if (!claimSalt(salt) || !pairing.claimSalt(salt)) return EndReason.STREAM_CLOSED

        val keys = pairing.deriveKeys(salt) ?: return EndReason.UNPAIRED
        val codec = FrameCodec(sealKey = keys.deviceToServer, openKey = keys.serverToDevice)
        val aad = pairing.deviceId.toByteArray()
        onConnected()

        while (isActive()) {
            val line = stream.readLine() ?: return EndReason.STREAM_CLOSED

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
            stream.writeLine(json.encodeToString(reply))
        }
        return EndReason.STOPPED
    }

    /** Records [salt] as used; false if it was already seen (a replayed handshake). */
    private fun claimSalt(salt: ByteArray): Boolean = synchronized(usedSalts) {
        val key = Base64.encodeToString(salt, Base64.NO_WRAP)
        if (!usedSalts.add(key)) return false
        if (usedSalts.size > MAX_REMEMBERED_SALTS) usedSalts.remove(usedSalts.first())
        true
    }

    companion object {
        /** How long a connection may sit without sending its Hello. */
        const val HELLO_TIMEOUT_MS = 10_000L

        private const val MAX_REMEMBERED_SALTS = 1024
    }
}
