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

    /**
     * The run token for a provisioned named tunnel
     * ([com.hereliesaz.mcpserved.transport.CloudflareNamedTunnel]). Also a real
     * secret — it authorizes running that tunnel — so it lives in the same
     * encrypted store, and lets the stable address come back across sessions
     * without re-provisioning.
     */
    var namedTunnelRunToken: String
        get() = prefs.getString(KEY_RUN_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RUN_TOKEN, value.trim()).apply()

    /** The hostname the named tunnel serves (the operator's own, e.g. `mcp.example.com`). Not secret, kept here for cohesion. */
    var namedTunnelHostname: String
        get() = prefs.getString(KEY_HOSTNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOSTNAME, value.trim()).apply()

    /**
     * The hostname [namedTunnelRunToken] was actually provisioned for. Kept apart
     * from [namedTunnelHostname] (which tracks whatever is typed in the field) so a
     * run token is only reused for the hostname it was created for.
     */
    var namedTunnelProvisionedHostname: String
        get() = prefs.getString(KEY_PROVISIONED_HOSTNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROVISIONED_HOSTNAME, value.trim()).apply()

    /** Forgets the API token and any provisioned tunnel's run token. */
    fun clear() = prefs.edit()
        .remove(KEY_TOKEN)
        .remove(KEY_RUN_TOKEN)
        .remove(KEY_PROVISIONED_HOSTNAME)
        .apply()

    private companion object {
        const val KEY_TOKEN = "api_token"
        const val KEY_RUN_TOKEN = "named_tunnel_run_token"
        const val KEY_HOSTNAME = "named_tunnel_hostname"
        const val KEY_PROVISIONED_HOSTNAME = "named_tunnel_provisioned_hostname"
    }
}
