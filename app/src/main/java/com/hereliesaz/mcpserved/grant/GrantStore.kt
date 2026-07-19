package com.hereliesaz.mcpserved.grant

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hereliesaz.mcpserved.transport.Scope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.grantDataStore: DataStore<Preferences> by preferencesDataStore("grants")

/**
 * Persistent grant table.
 *
 * Backed by DataStore rather than an in-memory map so that a service restart —
 * which Android performs freely, and which OEM battery managers perform
 * enthusiastically — does not silently widen or narrow what the caller may
 * reach. A grant table that resets to empty on restart would be safe but
 * unusable; one that resets to permissive would be neither.
 *
 * Expired grants are pruned lazily on read rather than by a scheduled job. A
 * timer that fires while the device dozes is a timer that does not fire.
 */
class GrantStore(private val ctx: Context) {

    private val key = stringPreferencesKey("table")
    private val json = Json { ignoreUnknownKeys = true }

    /** Live view of unexpired grants. */
    val grants: Flow<List<Grant>> = ctx.grantDataStore.data.map { prefs ->
        decode(prefs[key]).filterNot { it.isExpired() }
    }

    private fun decode(raw: String?): List<Grant> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<Grant>>(raw) }.getOrDefault(emptyList())

    /** Current unexpired grants, pruning any that have lapsed. */
    suspend fun current(): List<Grant> {
        val now = System.currentTimeMillis()
        val all = decode(ctx.grantDataStore.data.first()[key])
        val live = all.filterNot { it.isExpired(now) }
        if (live.size != all.size) write(live)
        return live
    }

    /** Live grant for [pkg], or null when none exists or it has lapsed. */
    suspend fun find(pkg: String): Grant? = current().firstOrNull { it.pkg == pkg }

    /**
     * Adds or replaces the grant for [grant]'s package.
     *
     * Replacement is total, not a merge. Re-granting with a narrower scope set
     * must narrow; a union would make revocation-by-regrant silently fail.
     */
    suspend fun put(grant: Grant) {
        write(current().filterNot { it.pkg == grant.pkg } + grant)
    }

    /** Removes the grant for [pkg]. Idempotent. */
    suspend fun revoke(pkg: String) {
        write(current().filterNot { it.pkg == pkg })
    }

    /** Removes every grant. Backs the panic control in the session notification. */
    suspend fun revokeAll() = write(emptyList())

    private suspend fun write(list: List<Grant>) {
        val encoded = json.encodeToString(list)
        ctx.grantDataStore.edit { it[key] = encoded }
    }

    /** Convenience for the UI: scope sets offered as presets. */
    companion object {
        /** Observation only — tree and screenshots, no input. */
        val READ_ONLY = setOf(Scope.OBSERVE)

        /** Everything short of shell. The common case. */
        val INTERACT = setOf(Scope.OBSERVE, Scope.INTERACT, Scope.TYPE, Scope.LAUNCH)

        /** Full surface, including arbitrary shell when a privileged backend exists. */
        val FULL = setOf(Scope.OBSERVE, Scope.INTERACT, Scope.TYPE, Scope.LAUNCH, Scope.SHELL)
    }
}
