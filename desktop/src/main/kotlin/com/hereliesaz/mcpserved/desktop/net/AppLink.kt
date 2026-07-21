package com.hereliesaz.mcpserved.desktop.net

import com.hereliesaz.mcpserved.desktop.adb.Adb
import com.hereliesaz.mcpserved.desktop.config.Config
import com.hereliesaz.mcpserved.desktop.crypto.Crypto
import com.hereliesaz.mcpserved.desktop.crypto.FrameCodec
import com.hereliesaz.mcpserved.desktop.crypto.InvalidFrame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * How to reach the on-device control server.
 *
 * Two shapes: an `adb forward` tunnel onto the device's loopback port (the USB /
 * paired-cable path), or a direct LAN socket to an address the device advertised
 * over mDNS (the auto-discovery path). Either way the sealed-frame crypto is what
 * authenticates the peer; the transport underneath is just plumbing.
 */
data class Target(
    val host: String,
    val port: Int,
    val useAdbForward: Boolean,
) {
    val describe: String
        get() = if (useAdbForward) "adb-forward → 127.0.0.1:$port" else "lan $host:$port"

    companion object {
        /** The classic path: bridge the device's loopback port through adb. */
        fun loopback(config: Config) = Target("127.0.0.1", config.port, useAdbForward = true)

        /** A device found on the LAN over mDNS — dial it straight, no adb. */
        fun lan(host: String, port: Int) = Target(host, port, useAdbForward = false)
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

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var output: OutputStream? = null
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
        runCatching { socket?.close() }
        socket = null; reader = null; output = null; codec = null
    }

    private fun ensureConnected(timeoutMs: Int = 15_000) {
        socket?.let { if (!it.isClosed && it.isConnected && codec != null) return }

        if (target.useAdbForward) {
            // Bridge the device's loopback port to ours. Harmless when already mapped.
            Adb.forward(config.port, config.port)
        }

        // Fresh per-connection salt and keys. The device folds the same salt in
        // when it reads the hello, so both sides land on the same directional keys.
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val keys = Crypto.deriveKeys(config.serverPrivateKey, config.devicePublicKey, salt)
        val newCodec = FrameCodec(sealKey = keys.serverToDevice, openKey = keys.deviceToServer)

        val sock = Socket()
        sock.connect(InetSocketAddress(target.host, target.port), timeoutMs)
        sock.tcpNoDelay = true

        val out = sock.getOutputStream()
        val hello = buildJsonObject {
            put("v", JsonPrimitive(PROTO_VERSION))
            put("salt", JsonPrimitive(Crypto.b64Url(salt)))
        }
        out.write((ProtoJson.encodeToString(JsonObject.serializer(), hello) + "\n").toByteArray(Charsets.UTF_8))
        out.flush()

        socket = sock
        reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
        output = out
        codec = newCodec
    }

    private fun exchange(request: JsonObject, timeoutMs: Long): JsonObject {
        val sock = socket ?: return err("not connected")
        val codec = codec ?: return err("not connected")
        val out = output ?: return err("not connected")
        val reader = reader ?: return err("not connected")

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
        out.write((ProtoJson.encodeToString(JsonObject.serializer(), envelope) + "\n").toByteArray(Charsets.UTF_8))
        out.flush()

        sock.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
        while (true) {
            val line = try {
                reader.readLine()
            } catch (_: SocketTimeoutException) {
                return err("device did not respond within ${timeoutMs}ms")
            } ?: return err("connection closed")
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
