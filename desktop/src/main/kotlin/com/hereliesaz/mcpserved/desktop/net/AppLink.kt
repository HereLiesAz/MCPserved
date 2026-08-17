package com.hereliesaz.mcpserved.desktop.net

import com.hereliesaz.mcpserved.desktop.adb.Adb
import com.hereliesaz.mcpserved.desktop.config.Config
import com.hereliesaz.mcpserved.desktop.crypto.Crypto
import com.hereliesaz.mcpserved.desktop.crypto.FrameCodec
import com.hereliesaz.mcpserved.desktop.crypto.InvalidFrame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * How to reach the on-device control server.
 *
 * Three shapes: an `adb forward` tunnel onto the device's loopback port (the
 * USB / paired-cable path), a direct LAN socket to an address the device
 * advertised over mDNS (the auto-discovery path), or — opt-in, off the local
 * network entirely — a relay room the device dialed out to
 * ([RemoteRelayClient] on the device side; see `relay/README.md`). Either way
 * the sealed-frame crypto is what authenticates the peer; the transport
 * underneath is just plumbing, which is why only [connect] differs between
 * them and every other line of [AppLink] is shape-agnostic.
 */
sealed class Target {
    abstract val describe: String

    /** Opens the underlying [FrameStream] for this target. */
    abstract fun connect(timeoutMs: Int): FrameStream

    /** The classic path: bridge the device's loopback port through adb. */
    data class Loopback(val port: Int) : Target() {
        override val describe get() = "adb-forward → 127.0.0.1:$port"
        override fun connect(timeoutMs: Int): FrameStream {
            Adb.forward(port, port) // harmless when already mapped
            return connectSocket("127.0.0.1", port, timeoutMs)
        }
    }

    /** A device found on the LAN over mDNS — dial it straight, no adb. */
    data class Lan(val host: String, val port: Int) : Target() {
        override val describe get() = "lan $host:$port"
        override fun connect(timeoutMs: Int): FrameStream = connectSocket(host, port, timeoutMs)
    }

    /**
     * A relay room, dialed as the "host" role. Requires no local network path
     * to the device at all — the device found the same relay independently
     * (opt-in, see `app/.../grant/RemoteAccessStore.kt`) and dialed the same
     * room as the "device" role.
     */
    data class Relay(val url: String, val room: String) : Target() {
        override val describe get() = "relay $url"
        override fun connect(timeoutMs: Int): FrameStream = RelayFrameStream(url, room)
    }

    companion object {
        fun loopback(config: Config): Target = Loopback(config.port)
        fun lan(host: String, port: Int): Target = Lan(host, port)
        fun relay(url: String, room: String): Target = Relay(url, room)
    }
}

/**
 * Connection to the on-device app over a loopback tunnel or a discovered LAN
 * address.
 *
 * There is no relay and no cloud. The app listens on the phone; this link dials
 * it — either through an `adb forward` map onto its loopback port, or directly at
 * the address it broadcast over mDNS. The sealed frames never leave the pair of
 * machines.
 *
 * Requests are strictly single-flight. The wire protocol carries a sequence
 * number but no correlation id, so responses match requests by ordering, which
 * holds only while exactly one request is outstanding. A process-wide lock
 * enforces that; the device dispatches serially regardless.
 *
 * Each connection derives fresh keys from a random salt sent in the opening
 * hello, so the sequence counter starts at zero every time without any risk of
 * replaying a nonce under a reused key.
 */
class AppLink(
    private val config: Config,
    private val target: Target,
) : Link {

    private val aad: ByteArray = config.deviceId.toByteArray(Charsets.UTF_8)
    private val lock = ReentrantLock()

    private var stream: FrameStream? = null
    private var codec: FrameCodec? = null

    override val label: String get() = "app (${target.describe})"

    override fun send(request: JsonObject, timeoutMs: Long): JsonObject = lock.withLock {
        try {
            ensureConnected()
            exchange(request, timeoutMs)
        } catch (e: Exception) {
            close()
            err(e.message ?: e.toString())
        }
    }

    override fun close() = lock.withLock {
        runCatching { stream?.close() }
        stream = null; codec = null
    }

    private fun ensureConnected(timeoutMs: Int = 15_000) {
        if (stream != null && codec != null) return

        // Fresh per-connection salt and keys. The device folds the same salt in
        // when it reads the hello, so both sides land on the same directional keys.
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val keys = Crypto.deriveKeys(config.serverPrivateKey, config.devicePublicKey, salt)
        val newCodec = FrameCodec(sealKey = keys.serverToDevice, openKey = keys.deviceToServer)

        val newStream = target.connect(timeoutMs)
        val hello = buildJsonObject {
            put("v", JsonPrimitive(PROTO_VERSION))
            put("salt", JsonPrimitive(Crypto.b64Url(salt)))
        }
        newStream.writeLine(ProtoJson.encodeToString(JsonObject.serializer(), hello))

        stream = newStream
        codec = newCodec
    }

    private fun exchange(request: JsonObject, timeoutMs: Long): JsonObject {
        val stream = stream ?: return err("not connected")
        val codec = codec ?: return err("not connected")

        val sealed = codec.seal(
            ProtoJson.encodeToString(JsonObject.serializer(), request).toByteArray(Charsets.UTF_8),
            aad,
        )
        // seq as a JSON number: the device decodes it straight into a Long.
        val envelope = buildJsonObject {
            put("deviceId", JsonPrimitive(config.deviceId))
            put("seq", JsonPrimitive(sealed.seq))
            put("payload", JsonPrimitive(sealed.payloadB64))
        }
        stream.writeLine(ProtoJson.encodeToString(JsonObject.serializer(), envelope))

        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return err("device did not respond within ${timeoutMs}ms")
            val line = stream.readLine(remaining) ?: return err("connection closed")
            if (line.isEmpty()) continue

            val env = try {
                ProtoJson.parseToJsonElement(line) as? JsonObject ?: continue
            } catch (_: Exception) {
                continue
            }
            if (env.str("deviceId") != config.deviceId) continue
            val seq = env.str("seq")?.toLongOrNull() ?: continue
            val payload = env.str("payload") ?: continue

            val plaintext = try {
                codec.open(seq, payload, aad)
            } catch (_: InvalidFrame) {
                // Unopenable frames are noise or an attempt; skip them. Only the
                // paired device can produce a valid one.
                continue
            }
            return ProtoJson.parseToJsonElement(String(plaintext, Charsets.UTF_8)) as? JsonObject
                ?: err("device returned a non-object response")
        }
    }

    private companion object {
        const val PROTO_VERSION = 2
    }
}
