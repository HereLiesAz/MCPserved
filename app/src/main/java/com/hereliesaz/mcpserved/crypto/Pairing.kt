package com.hereliesaz.mcpserved.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import java.util.UUID

/**
 * Establishes and stores the long-term keys shared with the desktop MCP server.
 *
 * Pairing is a one-time out-of-band exchange: the device generates an X25519
 * keypair and renders its public half and its device id as a QR code. The MCP
 * server scans it, generates its own keypair, and shows its public half back for
 * the device to scan. Both sides then derive the same shared secret and never
 * transmit it.
 *
 * There is no relay and no network peer. The two endpoints meet on the local
 * machine: the desktop server reaches the device's loopback control port through
 * an `adb forward` tunnel (see [com.hereliesaz.mcpserved.transport.LocalServer]).
 * The pairing secret still matters even so — any app on the device can dial a
 * loopback port, and the shared key is what stops one that is not the paired
 * server from driving the service.
 *
 * BouncyCastle rather than the platform providers: `XDH` via `java.security`
 * only arrived in API 33, and `ChaCha20-Poly1305` via `javax.crypto` in API 28.
 * Bundling one implementation avoids two independent API-level branches in the
 * one part of the system where a silent fallback would be worst.
 */
class Pairing(ctx: Context) {

    private val prefs by lazy {
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "pairing",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Everything the MCP server needs to address and authenticate this device. */
    data class QrPayload(
        val deviceId: String,
        val devicePublicKey: ByteArray
    ) {
        /**
         * Compact single-line encoding for the QR bitmap.
         *
         * Format: `mcpserved:2:<deviceId>:<b64url pubkey>`. Versioned in the
         * second field so that a future format change fails to parse loudly
         * rather than being misread as the current one. Version 2 dropped the
         * relay URL that version 1 carried: there is no relay anymore.
         */
        fun encode(): String = listOf(
            "mcpserved",
            "2",
            deviceId,
            Base64.encodeToString(devicePublicKey, Base64.NO_WRAP or Base64.URL_SAFE)
        ).joinToString(":")

        override fun equals(other: Any?): Boolean =
            other is QrPayload && deviceId == other.deviceId &&
                devicePublicKey.contentEquals(other.devicePublicKey)

        override fun hashCode(): Int =
            deviceId.hashCode() * 31 + devicePublicKey.contentHashCode()

        companion object {
            fun decode(s: String): QrPayload? {
                val parts = s.split(":")
                if (parts.size != 4 || parts[0] != "mcpserved" || parts[1] != "2") return null
                return runCatching {
                    QrPayload(
                        deviceId = parts[2],
                        devicePublicKey = Base64.decode(parts[3], Base64.NO_WRAP or Base64.URL_SAFE)
                    )
                }.getOrNull()
            }
        }
    }

    /** True once a peer public key has been recorded. */
    val isPaired: Boolean get() = prefs.getString(KEY_PEER_PUB, null) != null

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: rotateIdentity().deviceId

    /**
     * Generates a fresh identity, discarding any existing pairing.
     *
     * Called on first run and on explicit re-pair. Rotating the identity is the
     * only complete revocation available: the grant table can be emptied and the
     * session ended, but a peer holding a valid shared secret can still reach the
     * loopback port and be told no. Rotation means it cannot even do that.
     *
     * @return the payload to render as a QR code
     */
    fun rotateIdentity(): QrPayload {
        val priv = X25519PrivateKeyParameters(SecureRandom())
        val pub = priv.generatePublicKey()
        val id = UUID.randomUUID().toString()

        prefs.edit()
            .putString(KEY_DEVICE_ID, id)
            .putString(KEY_PRIV, Base64.encodeToString(priv.encoded, Base64.NO_WRAP))
            .putString(KEY_PUB, Base64.encodeToString(pub.encoded, Base64.NO_WRAP))
            .remove(KEY_PEER_PUB)
            .apply()

        return QrPayload(id, pub.encoded)
    }

    /** The payload for the current identity, generating one if none exists. */
    fun currentPayload(): QrPayload {
        val pub = prefs.getString(KEY_PUB, null) ?: return rotateIdentity()
        return QrPayload(deviceId, Base64.decode(pub, Base64.NO_WRAP))
    }

    /**
     * Records the MCP server's public key, completing the pairing.
     *
     * @param peerPublicKey 32 raw X25519 bytes
     * @return false when the key is malformed; a wrong-length key is rejected
     *   rather than padded, since a key that silently becomes a different key
     *   produces a shared secret that differs only on one side.
     */
    fun completePairing(peerPublicKey: ByteArray): Boolean {
        if (peerPublicKey.size != X25519PublicKeyParameters.KEY_SIZE) return false
        prefs.edit()
            .putString(KEY_PEER_PUB, Base64.encodeToString(peerPublicKey, Base64.NO_WRAP))
            .apply()
        return true
    }

    /** Discards the peer key. The device stays addressable but nothing can talk to it. */
    fun unpair() {
        prefs.edit().remove(KEY_PEER_PUB).apply()
    }

    /**
     * Derives the directional frame keys from the X25519 shared secret and a
     * per-connection [salt].
     *
     * Two keys, not one. With a single key both sides would draw nonces from the
     * same space, and a device frame and a server frame carrying the same
     * sequence number would reuse a nonce — which for ChaCha20-Poly1305 leaks the
     * keystream and forges the authenticator. Separate keys let each direction
     * use its own counter with no coordination at all.
     *
     * The salt is fresh random bytes the server picks per connection and sends in
     * its opening hello. Folding it into the KDF means every connection derives
     * distinct keys, so each connection's sequence counter may safely start at
     * zero: a counter reset under a *new* key cannot replay a nonce. Without this,
     * a desktop server the MCP host relaunches — which resets its counter — would
     * have every frame rejected as a replay by a device still holding the old one.
     *
     * @param salt per-connection random bytes shared by both endpoints
     * @return device-to-server and server-to-device keys, or null when unpaired
     */
    fun deriveKeys(salt: ByteArray): FrameKeys? {
        val privB64 = prefs.getString(KEY_PRIV, null) ?: return null
        val peerB64 = prefs.getString(KEY_PEER_PUB, null) ?: return null

        val priv = X25519PrivateKeyParameters(Base64.decode(privB64, Base64.NO_WRAP), 0)
        val peer = X25519PublicKeyParameters(Base64.decode(peerB64, Base64.NO_WRAP), 0)

        val shared = ByteArray(X25519PrivateKeyParameters.SECRET_SIZE)
        X25519Agreement().apply {
            init(priv)
            calculateAgreement(peer, shared, 0)
        }

        return FrameKeys(
            deviceToServer = hkdf(shared, salt, "mcpserved d2s v1"),
            serverToDevice = hkdf(shared, salt, "mcpserved s2d v1")
        )
    }

    private fun hkdf(secret: ByteArray, salt: ByteArray, info: String): ByteArray {
        val out = ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(secret, salt, info.toByteArray()))
            generateBytes(out, 0, out.size)
        }
        return out
    }

    /** Directional 256-bit ChaCha20-Poly1305 keys. */
    data class FrameKeys(val deviceToServer: ByteArray, val serverToDevice: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is FrameKeys &&
                deviceToServer.contentEquals(other.deviceToServer) &&
                serverToDevice.contentEquals(other.serverToDevice)

        override fun hashCode(): Int =
            deviceToServer.contentHashCode() * 31 + serverToDevice.contentHashCode()
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PRIV = "priv"
        const val KEY_PUB = "pub"
        const val KEY_PEER_PUB = "peer_pub"
    }
}
