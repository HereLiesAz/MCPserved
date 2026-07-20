package com.hereliesaz.mcpserved.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The bearer token that authorizes a direct MCP-over-HTTP connection to the
 * device's own server.
 *
 * The QR [Pairing] authenticates the desktop bridge's sealed-frame transport. The
 * on-device MCP server speaks ordinary HTTP instead, so it authenticates the way
 * every other MCP HTTP server does: a bearer token the operator copies into the
 * host's config once. It is generated on the device, kept in encrypted
 * preferences, and never leaves except when the operator pastes it.
 *
 * Loopback binding is not the authorization boundary — any process on the device
 * can reach `127.0.0.1`. The token is: a request that cannot present it gets 401
 * and learns nothing else about what is listening.
 */
class McpToken(ctx: Context) {

    private val prefs by lazy {
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "mcp",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** The current token, minting one on first read. */
    fun value(): String = prefs.getString(KEY_TOKEN, null) ?: rotate()

    /**
     * Mints a fresh token, invalidating any host still configured with the old one.
     *
     * This is the HTTP path's revocation: a rotated token means a previously
     * configured client presents a secret the device no longer accepts, and every
     * request it makes gets 401 until the operator re-copies the new one.
     */
    fun rotate(): String {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val tok = Base64.encodeToString(raw, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        prefs.edit().putString(KEY_TOKEN, tok).apply()
        return tok
    }

    /**
     * Constant-time comparison against the stored token.
     *
     * A byte-by-byte compare that returned early on the first mismatch would leak
     * the shared prefix length through timing. [MessageDigest.isEqual] runs in time
     * independent of where the inputs differ; the token length itself is not secret.
     */
    fun matches(presented: String?): Boolean {
        if (presented.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            value().toByteArray(Charsets.US_ASCII),
            presented.toByteArray(Charsets.US_ASCII)
        )
    }

    private companion object {
        const val KEY_TOKEN = "http_bearer"
    }
}
