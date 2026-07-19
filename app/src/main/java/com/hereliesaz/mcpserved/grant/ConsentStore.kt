package com.hereliesaz.mcpserved.grant

import android.content.Context

/**
 * Whether the user has accepted the prominent disclosure.
 *
 * One durable bit, kept behind its own type for the same reason [GrantStore] and
 * [com.hereliesaz.mcpserved.crypto.Pairing] are: the persistence detail stays in
 * one place, and the view model asks a question rather than reaching into
 * SharedPreferences. Plain (unencrypted) storage is deliberate — this records a
 * decision, not a secret, and nothing here is worth protecting from the device's
 * owner.
 *
 * Read synchronously because it gates the very first frame drawn: the disclosure
 * must be shown before anything else, and an async load would flash the main UI
 * for a frame before the gate resolved.
 */
class ConsentStore(ctx: Context) {

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True once the disclosure has been accepted. */
    val isAccepted: Boolean
        get() = prefs.getBoolean(KEY_ACCEPTED, false)

    /** Records acceptance. Never reversed here — revoking consent is uninstalling. */
    fun accept() {
        prefs.edit().putBoolean(KEY_ACCEPTED, true).apply()
    }

    private companion object {
        const val PREFS = "consent"
        const val KEY_ACCEPTED = "disclosure_accepted"
    }
}
