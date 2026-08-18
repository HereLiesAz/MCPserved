package com.hereliesaz.mcpserved.transport

import android.util.Base64
import org.json.JSONObject
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Delivers this device's public key to the desktop that issued a pairing QR,
 * over the LAN, the instant the phone finishes scanning it — the return half
 * of the exchange, completed automatically instead of asked of the user.
 *
 * The desktop's QR carries a one-time token that never crosses the network
 * until this call sends it back as an HMAC over the message (mirrored in
 * `PairingResponder` on the desktop). Only whatever actually scanned the QR —
 * camera only, never the network — can produce a valid tag, so an on-path LAN
 * attacker cannot forge a submission or swap in a different public key
 * without invalidating it.
 */
object PairingPush {

    /** @throws Exception on any connection, timeout, or protocol failure. */
    fun push(host: String, port: Int, token: ByteArray, deviceId: String, publicKey: ByteArray, timeoutMs: Int = 5_000) {
        val pubKeyB64 = Base64.encodeToString(publicKey, Base64.NO_WRAP or Base64.URL_SAFE)
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(token, "HmacSHA256"))
        }.doFinal("$deviceId:$pubKeyB64".toByteArray(Charsets.UTF_8))

        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("devicePublicKey", pubKeyB64)
            .put("mac", Base64.encodeToString(mac, Base64.NO_WRAP or Base64.URL_SAFE))
            .toString()

        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            PrintWriter(socket.getOutputStream(), true).apply {
                print(body)
                print("\n")
                flush()
            }
        }
    }
}
