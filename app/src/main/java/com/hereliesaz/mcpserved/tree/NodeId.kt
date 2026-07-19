package com.hereliesaz.mcpserved.tree

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Derives identifiers that survive scrolling but break on genuine layout change.
 *
 * The hash covers the view's resource id, its class, its ordinal position among
 * siblings, and its depth. Screen coordinates are deliberately excluded: a node
 * that scrolls keeps its identity, which is the whole point. A node that moves
 * because the layout actually changed gets a new id, which is also the point —
 * a stale id that still resolves is worse than one that fails loudly.
 */
object NodeId {

    /**
     * @param node    the source node
     * @param ordinal index among its parent's children
     * @param depth   distance from the window root
     * @return a 12-character lowercase hex identifier
     */
    fun of(node: AccessibilityNodeInfo, ordinal: Int, depth: Int): String {
        val res = node.viewIdResourceName ?: ""
        val cls = node.className?.toString() ?: ""
        return fnv1a("$res|$cls|$ordinal|$depth")
    }

    /** FNV-1a 64-bit. Not cryptographic; collisions here cost a retry, not a breach. */
    private fun fnv1a(s: String): String {
        var h = -0x340d631b7bdddcdbL
        for (b in s.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h).padStart(16, '0').take(12)
    }
}
