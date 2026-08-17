package com.hereliesaz.mcpserved.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The operator's own Cloudflare API token, pasted in once to deploy their own
 * relay Worker (see [com.hereliesaz.mcpserved.transport.CloudflareRelayDeployer]).
 *
 * A real secret — unlike [RelayToken], this one grants write access to
 * whatever the token's Cloudflare permissions cover — so it goes in
 * `EncryptedSharedPreferences`, same as [McpToken]. It never leaves the
 * device except as the `Authorization` header on requests the operator
 * explicitly triggers by tapping Deploy.
 */
class CloudflareApiToken(ctx: Context) {

    private val prefs by lazy {
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "cloudflare",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var value: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    fun clear() = prefs.edit().remove(KEY_TOKEN).apply()

    private companion object {
        const val KEY_TOKEN = "api_token"
    }
}
