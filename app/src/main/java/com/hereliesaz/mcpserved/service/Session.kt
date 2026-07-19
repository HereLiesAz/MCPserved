package com.hereliesaz.mcpserved.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * The window during which the remote caller may act.
 *
 * A session is not a connection. The socket may drop and re-establish freely —
 * doze, cell handoff, an OEM battery manager with opinions — without ending the
 * session, and a session may expire while the socket is perfectly healthy.
 * Conflating the two would mean either that a network hiccup silently revokes
 * authority, or that a forgotten connection holds it indefinitely.
 *
 * Expiry is checked on read rather than fired by a timer, for the same reason
 * grants are: a timer scheduled during doze fires late or not at all, and an
 * authority window that outlives its stated end because the CPU was asleep is
 * not a window at all.
 */
class Session(private val defaultTtlSec: Int = 300) {

    /** Live session state, or null when no session is open. */
    data class State(val id: String, val expiresAtEpochMs: Long) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAtEpochMs
        fun remainingSec(now: Long = System.currentTimeMillis()): Int =
            ((expiresAtEpochMs - now) / 1000).coerceAtLeast(0).toInt()
    }

    private val _state = MutableStateFlow<State?>(null)

    /** Observable session state, for the notification and the log screen. */
    val state: StateFlow<State?> = _state

    /** True when a session is open and unexpired. */
    val isActive: Boolean
        get() = _state.value?.isExpired() == false

    /**
     * Opens a session, replacing any existing one.
     *
     * @param ttlSec lifetime in seconds, clamped to [MAX_TTL_SEC]. An unbounded
     *   session is not offered: the screen is held awake for the duration, and a
     *   phone that never sleeps is a phone that is dead by morning.
     */
    fun begin(ttlSec: Int = defaultTtlSec): State {
        val ttl = ttlSec.coerceIn(MIN_TTL_SEC, MAX_TTL_SEC)
        val s = State(
            id = UUID.randomUUID().toString(),
            expiresAtEpochMs = System.currentTimeMillis() + ttl * 1000L
        )
        _state.value = s
        return s
    }

    /**
     * Pushes expiry back by [ttlSec] from now, if a session is live.
     *
     * Called on every successful action so that an active session does not
     * expire mid-task, while an abandoned one still lapses on schedule.
     *
     * @return the extended state, or null when no live session exists
     */
    fun touch(ttlSec: Int = defaultTtlSec): State? {
        val cur = _state.value ?: return null
        if (cur.isExpired()) {
            _state.value = null
            return null
        }
        val ttl = ttlSec.coerceIn(MIN_TTL_SEC, MAX_TTL_SEC)
        val next = cur.copy(expiresAtEpochMs = System.currentTimeMillis() + ttl * 1000L)
        _state.value = next
        return next
    }

    /** Closes the session. Idempotent. */
    fun end() {
        _state.value = null
    }

    /** Clears state if the session has lapsed. Returns whether one is still live. */
    fun reap(): Boolean {
        val cur = _state.value ?: return false
        if (cur.isExpired()) {
            _state.value = null
            return false
        }
        return true
    }

    companion object {
        const val MIN_TTL_SEC = 30
        const val MAX_TTL_SEC = 1800
    }
}
