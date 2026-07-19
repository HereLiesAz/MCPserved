package com.hereliesaz.mcpserved.grant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

/**
 * In-memory record of every action attempted during a session.
 *
 * Deliberately not persisted. A durable log of everything an automated agent did
 * to your phone is a durable log of everything on your phone, and the failure
 * mode of keeping it is worse than the failure mode of losing it. The record
 * exists so the operator can watch a session and stop it; forensics after the
 * fact is not the goal.
 *
 * @param capacity entries retained before the oldest are dropped
 */
class SessionLog(private val capacity: Int = 500) {

    /** One attempted operation. */
    data class Entry(
        val atEpochMs: Long,
        val label: String,
        val pkg: String,
        val denied: Boolean,
        val ok: Boolean,
        val note: String? = null
    )

    private val ring = ArrayDeque<Entry>(capacity)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest-first view, for the log screen. */
    val entries: StateFlow<List<Entry>> = _entries

    @Synchronized
    fun record(label: String, pkg: String, denied: Boolean, ok: Boolean, note: String? = null) {
        if (ring.size >= capacity) ring.removeLast()
        ring.addFirst(Entry(System.currentTimeMillis(), label, pkg, denied, ok, note))
        _entries.value = ring.toList()
    }

    @Synchronized
    fun clear() {
        ring.clear()
        _entries.value = emptyList()
    }
}
