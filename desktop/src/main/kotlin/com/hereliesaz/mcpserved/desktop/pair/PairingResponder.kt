package com.hereliesaz.mcpserved.desktop.pair

import com.hereliesaz.mcpserved.desktop.crypto.Crypto
import com.hereliesaz.mcpserved.desktop.net.LanAddress
import com.hereliesaz.mcpserved.desktop.net.ProtoJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * Waits, on the LAN, for the phone to scan this machine's pairing QR and send
 * its public key back — the second half of the exchange, completed the
 * instant the camera reads the code instead of asking the operator to copy
 * anything by hand.
 *
 * [PairingFlow.startPairing] hands out a fresh token alongside the QR. That
 * token never crosses the network until the phone echoes it back as an HMAC
 * over the submission, so an attacker elsewhere on the same LAN — who never
 * saw the QR, camera-only by design — cannot forge a submission or silently
 * swap in a different public key: any tampering invalidates the tag.
 */
class PairingResponder {

    data class Submission(val deviceId: String, val devicePublicKey: ByteArray)

    private var server: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "pairing-responder").apply { isDaemon = true }
    }

    /**
     * Listens for one authenticated submission matching [token], then stops.
     * [onResult] runs on the listener's own thread — callers marshal back to
     * their own thread before touching UI state.
     */
    fun listen(token: ByteArray, onResult: (Result<Submission>) -> Unit) {
        stop()
        val address = LanAddress.resolve()
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, PORT))
                soTimeout = LISTEN_TIMEOUT_MS
            }
        } catch (e: Exception) {
            onResult(Result.failure(e))
            return
        }
        server = socket
        executor.execute {
            try {
                socket.accept().use { client ->
                    client.soTimeout = READ_TIMEOUT_MS
                    val line = BufferedReader(InputStreamReader(client.getInputStream())).readLine()
                        ?: throw IllegalStateException("connection closed with no data")
                    onResult(Result.success(parse(line, token)))
                }
            } catch (_: SocketTimeoutException) {
                onResult(Result.failure(IllegalStateException("no phone connected within the pairing window")))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun parse(line: String, token: ByteArray): Submission {
        val obj = ProtoJson.parseToJsonElement(line) as JsonObject
        val deviceId = obj["deviceId"]?.jsonPrimitive?.content ?: error("missing deviceId")
        val pubKeyB64 = obj["devicePublicKey"]?.jsonPrimitive?.content ?: error("missing devicePublicKey")
        val macB64 = obj["mac"]?.jsonPrimitive?.content ?: error("missing mac")

        val expected = Crypto.hmacSha256(token, "$deviceId:$pubKeyB64")
        require(Crypto.constantTimeEquals(Crypto.unb64Url(macB64), expected)) { "bad pairing token" }

        val devicePublicKey = Crypto.unb64Url(pubKeyB64)
        require(devicePublicKey.size == 32) { "device public key is not 32 bytes" }
        return Submission(deviceId, devicePublicKey)
    }

    companion object {
        /** Fixed, not ephemeral: the port has to be in the QR before the socket exists. */
        const val PORT = 8792
        private const val LISTEN_TIMEOUT_MS = 120_000
        private const val READ_TIMEOUT_MS = 10_000
    }
}
