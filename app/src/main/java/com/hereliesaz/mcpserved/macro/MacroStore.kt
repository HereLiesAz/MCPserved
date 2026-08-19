package com.hereliesaz.mcpserved.macro

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.macroDataStore: DataStore<Preferences> by preferencesDataStore("macros")

/**
 * Persistent macro table.
 *
 * Mirrors [com.hereliesaz.mcpserved.grant.GrantStore]'s shape exactly: one
 * JSON-encoded list behind DataStore, so a service restart never silently
 * drops what the user recorded.
 */
class MacroStore(private val ctx: Context) {

    private val key = stringPreferencesKey("table")
    private val json = Json { ignoreUnknownKeys = true }

    /** Live view of every saved macro. */
    val macros: Flow<List<Macro>> = ctx.macroDataStore.data.map { prefs -> decode(prefs[key]) }

    private fun decode(raw: String?): List<Macro> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<Macro>>(raw) }.getOrDefault(emptyList())

    suspend fun current(): List<Macro> = decode(ctx.macroDataStore.data.first()[key])

    suspend fun find(name: String): Macro? = current().firstOrNull { it.name == name }

    /** Adds a macro, replacing any earlier one with the same name. */
    suspend fun put(macro: Macro) {
        write(current().filterNot { it.name == macro.name } + macro)
    }

    /** Removes the macro named [name]. Idempotent. */
    suspend fun delete(name: String) {
        write(current().filterNot { it.name == name })
    }

    private suspend fun write(list: List<Macro>) {
        ctx.macroDataStore.edit { it[key] = json.encodeToString(list) }
    }
}
