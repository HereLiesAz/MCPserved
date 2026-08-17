package com.hereliesaz.mcpserved.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * The room token a [com.hereliesaz.mcpserved.transport.RemoteRelayClient]
 * presents to a relay so it can find and pair with the host dialing the same
 * room.
 *
 * This is reachability config, not identity: it is independent of the X25519
 * pairing secret in [Pairing], travels over the relay connection in the clear
 * (the relay must read it to route the room), and confers no authority by
 * itself. A leaked token lets a third party occupy the room's device slot
 * (denying the real device a connection) or observe the sealed frames passing
 * through — it cannot decrypt them, and it cannot produce a valid one, since
 * that still requires the pairing secret this class knows nothing about.
 *
 * Kept in `EncryptedSharedPreferences` anyway, mirroring [McpToken], because
 * that costs nothing and there is no reason to make the token easier to lift
 * off the device than it needs to be.
 */
class RelayToken(ctx: Context) {

    private val prefs by lazy {
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "relay",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** The current room token, minting one on first read. */
    fun value(): String = synchronized(LOCK) { prefs.getString(KEY_TOKEN, null) ?: mint() }

    /** Mints a fresh token. Any relay room paired under the old one goes cold. */
    fun rotate(): String = synchronized(LOCK) { mint() }

    private fun mint(): String {
        val raw = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val tok = Base64.encodeToString(raw, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        prefs.edit().putString(KEY_TOKEN, tok).apply()
        return tok
    }

    private companion object {
        const val KEY_TOKEN = "room_token"
        val LOCK = Any()
    }
}
