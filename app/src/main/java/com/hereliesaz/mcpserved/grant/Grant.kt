package com.hereliesaz.mcpserved.grant

import com.hereliesaz.mcpserved.transport.Scope
import kotlinx.serialization.Serializable

/**
 * Permission for the remote caller to act on a single package.
 *
 * Grants are additive and default to absent. There is no denylist of sensitive
 * applications, because a denylist enumerates badness and will always be
 * incomplete — banking apps rebrand, authenticators get installed, and a list
 * written today is wrong by next quarter. An empty grant table renders the
 * entire service inert, which is the correct resting state.
 *
 * @property pkg       target application id
 * @property scopes    permitted operation classes; absent scope means refusal
 * @property expiresAtEpochMs hard expiry, or null for a grant that persists
 *   until revoked. The UI defaults to a bounded value; unbounded grants are
 *   available but require deliberate selection.
 * @property grantedAtEpochMs audit timestamp, never used for enforcement
 */
@Serializable
data class Grant(
    val pkg: String,
    val scopes: Set<Scope>,
    val expiresAtEpochMs: Long? = null,
    val grantedAtEpochMs: Long = System.currentTimeMillis()
) {
    /** True when [now] falls past [expiresAtEpochMs]. Unbounded grants never expire. */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs != null && now >= expiresAtEpochMs

    /** True when this grant is live and confers [scope]. */
    fun permits(scope: Scope, now: Long = System.currentTimeMillis()): Boolean =
        !isExpired(now) && scope in scopes
}
