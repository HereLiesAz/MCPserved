package com.hereliesaz.mcpserved.tree

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.hereliesaz.mcpserved.transport.Bounds
import com.hereliesaz.mcpserved.transport.UiNode

/**
 * Flattens an [AccessibilityNodeInfo] hierarchy into the minimal node list that
 * still permits interaction.
 *
 * A raw window dump routinely exceeds two thousand nodes, most of them layout
 * scaffolding with no text, no affordance, and no reason to cross a cellular
 * link. Pruning is therefore not an optimization — an unpruned tree costs more
 * than the screenshot it was meant to replace.
 *
 * A node survives if it is visible, non-degenerate, and either interactive or
 * carrying text. Everything else is counted and discarded.
 */
class Pruner(private val maxDepth: Int = 40) {

    /** Result of a flatten pass. */
    data class Result(val nodes: List<UiNode>, val pruned: Int)

    fun flatten(root: AccessibilityNodeInfo?): Result {
        val out = ArrayList<UiNode>(128)
        var dropped = 0
        val rect = Rect()

        fun walk(node: AccessibilityNodeInfo?, ordinal: Int, depth: Int) {
            if (node == null || depth > maxDepth) return

            node.getBoundsInScreen(rect)
            val bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            val interactive = node.isClickable || node.isLongClickable ||
                node.isEditable || node.isScrollable || node.isCheckable

            val keep = node.isVisibleToUser &&
                !bounds.isDegenerate &&
                (interactive || text != null || desc != null)

            if (keep) {
                out += UiNode(
                    id = NodeId.of(node, ordinal, depth),
                    cls = node.className?.toString()?.substringAfterLast('.') ?: "View",
                    bounds = bounds,
                    depth = depth,
                    text = text,
                    desc = desc,
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    checked = if (node.isCheckable) node.isChecked else null,
                    enabled = node.isEnabled
                )
            } else {
                dropped++
            }

            for (i in 0 until node.childCount) {
                walk(node.getChild(i), i, depth + 1)
            }
        }

        walk(root, 0, 0)
        return Result(out, dropped)
    }
}
