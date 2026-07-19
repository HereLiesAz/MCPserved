package com.hereliesaz.mcpserved.grant

import com.hereliesaz.mcpserved.backend.Resolver
import com.hereliesaz.mcpserved.transport.Scope

/**
 * Gate between an incoming request and the device.
 *
 * Enforcement lives here, on the device, and not in the MCP server. The server
 * is downstream of a language model's output and is therefore not a trust
 * boundary — it can be persuaded, and a permission check that can be talked out
 * of is decoration.
 *
 * Every mutating operation is bracketed:
 *
 *  1. read the foreground package
 *  2. refuse unless a live grant confers the required scope
 *  3. perform the action
 *  4. read the foreground package again
 *
 * Step four exists because the window can change between check and act. An
 * application may raise a dialog, a notification may take focus, a different
 * app may come forward on its own schedule. Without the second read, a tap
 * aimed at a granted screen can land on the confirmation button of something
 * that was never granted at all — and return success while doing it.
 *
 * A detected change does not roll anything back; nothing here is reversible.
 * It marks the response so the caller discards its stale node ids and
 * re-observes rather than firing the next queued action blind.
 */
class Enforcer(
    private val resolver: Resolver,
    private val store: GrantStore,
    private val log: SessionLog
) {

    /** Outcome of a bracketed operation. */
    data class Outcome<T>(
        val result: Result<T>,
        val foregroundChanged: Boolean,
        val pkgBefore: String,
        val pkgAfter: String
    )

    /** Raised when no live grant covers the foreground package for a scope. */
    class Denied(val pkg: String, val scope: Scope) :
        SecurityException("no $scope grant for $pkg")

    /**
     * Runs [block] under grant enforcement.
     *
     * @param scope permission the operation requires
     * @param label human-readable operation name, recorded in the session log
     * @param block the action; receives the foreground package it was authorized against
     */
    suspend fun <T> guard(
        scope: Scope,
        label: String,
        block: suspend (pkg: String) -> Result<T>
    ): Outcome<T> {
        val before = resolver.foregroundPackage().getOrElse { e ->
            val f = Result.failure<T>(IllegalStateException("foreground unreadable: ${e.message}"))
            log.record(label, "?", denied = false, ok = false, note = "foreground unreadable")
            return Outcome(f, false, "?", "?")
        }

        val grant = store.find(before)
        if (grant == null || !grant.permits(scope)) {
            log.record(label, before, denied = true, ok = false)
            return Outcome(Result.failure(Denied(before, scope)), false, before, before)
        }

        val result = block(before)

        val after = resolver.foregroundPackage().getOrDefault(before)
        val changed = after != before
        if (changed) {
            log.record(
                label, before, denied = false, ok = result.isSuccess,
                note = "foreground changed to $after during action"
            )
        } else {
            log.record(label, before, denied = false, ok = result.isSuccess)
        }

        return Outcome(result, changed, before, after)
    }

    /**
     * Runs [block] without a foreground check.
     *
     * Reserved for operations that are not scoped to a package — capability
     * reporting, session lifecycle, listing grants. Shell is deliberately not in
     * that set: it is bracketed like any other action, because a shell command
     * can reach every package at once and the audit trail matters more there,
     * not less.
     */
    suspend fun <T> unscoped(label: String, block: suspend () -> Result<T>): Outcome<T> {
        val r = block()
        log.record(label, "-", denied = false, ok = r.isSuccess)
        return Outcome(r, false, "-", "-")
    }
}
