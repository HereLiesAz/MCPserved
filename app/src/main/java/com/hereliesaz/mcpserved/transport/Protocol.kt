package com.hereliesaz.mcpserved.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire protocol shared by the device, the relay, and the MCP server.
 *
 * Frames are serialized to JSON, then sealed with ChaCha20-Poly1305 before
 * they reach the relay. The relay routes on [Envelope.deviceId] alone and
 * never observes plaintext.
 *
 * Every [Response] carries [Response.ok]. Mutating responses additionally
 * carry [Response.foregroundChanged], which is the caller's only reliable
 * signal that an action landed somewhere other than where it was aimed.
 */
@Serializable
data class Envelope(
    /** Stable device identifier established at pairing. */
    val deviceId: String,
    /** Monotonic per-session counter; also the AEAD nonce source. */
    val seq: Long,
    /** Base64 ChaCha20-Poly1305 ciphertext of a [Request] or [Response]. */
    val payload: String
)

/** Capabilities advertised by the device so the caller never probes blindly. */
@Serializable
enum class Cap {
    /** Accessibility service connected; tree and gesture dispatch available. */
    TREE,
    GESTURE,
    TEXT_INPUT,
    GLOBAL_KEYS,
    /** Screen capture without a per-session MediaProjection dialog. */
    CAPTURE_SILENT,
    CAPTURE_PROJECTION,
    /** Arbitrary shell via root. */
    SHELL_ROOT,
    /** Arbitrary shell via Shizuku (ADB-level, no root). */
    SHELL_SHIZUKU,
    NOTIFICATIONS,
    CLIPBOARD
}

/** Scopes a [com.hereliesaz.mcpserved.grant.Grant] may confer on a package. */
@Serializable
enum class Scope { OBSERVE, INTERACT, TYPE, LAUNCH, SHELL }

@Serializable
enum class GlobalKey { BACK, HOME, RECENTS, ENTER, DELETE, NOTIFICATIONS }

@Serializable
enum class ScrollDir { UP, DOWN, LEFT, RIGHT }

/** Screen-space rectangle in device pixels. */
@Serializable
data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int) {
    val centerX: Int get() = (l + r) / 2
    val centerY: Int get() = (t + b) / 2
    val width: Int get() = r - l
    val height: Int get() = b - t
    /** Zero-area nodes are unhittable and are pruned before transmission. */
    val isDegenerate: Boolean get() = width <= 0 || height <= 0
}

/**
 * A single interactive or text-bearing node.
 *
 * Pure layout containers are dropped by [com.hereliesaz.mcpserved.tree.Pruner];
 * the transmitted form is a flat list, with [depth] retained so the caller
 * can reconstruct rough hierarchy without paying for it in tokens.
 */
@Serializable
data class UiNode(
    /** Stable across dumps for an unchanged layout. See NodeId. */
    val id: String,
    /** Simple class name, e.g. `Button`, not the fully-qualified form. */
    val cls: String,
    val bounds: Bounds,
    val depth: Int,
    val text: String? = null,
    val desc: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val enabled: Boolean = true
)

@Serializable
sealed interface Request {
    @Serializable @SerialName("capabilities")
    data object Capabilities : Request

    @Serializable @SerialName("session_begin")
    data class SessionBegin(val ttlSec: Int = 300) : Request

    @Serializable @SerialName("session_end")
    data object SessionEnd : Request

    @Serializable @SerialName("grants_list")
    data object GrantsList : Request

    @Serializable @SerialName("ui_tree")
    data class UiTreeReq(val maxDepth: Int = 40) : Request

    @Serializable @SerialName("screenshot")
    data class Screenshot(val maxPx: Int = 768) : Request

    @Serializable @SerialName("apps_list")
    data class AppsList(val grantedOnly: Boolean = true) : Request

    @Serializable @SerialName("notifications")
    data object Notifications : Request

    @Serializable @SerialName("tap")
    data class Tap(val nodeId: String? = null, val x: Int? = null, val y: Int? = null) : Request

    @Serializable @SerialName("long_press")
    data class LongPress(
        val nodeId: String? = null, val x: Int? = null, val y: Int? = null, val ms: Int = 500
    ) : Request

    @Serializable @SerialName("swipe")
    data class Swipe(
        val x1: Int, val y1: Int, val x2: Int, val y2: Int, val ms: Int = 300
    ) : Request

    @Serializable @SerialName("scroll")
    data class Scroll(val nodeId: String, val dir: ScrollDir) : Request

    @Serializable @SerialName("type")
    data class Type(val text: String, val nodeId: String? = null) : Request

    @Serializable @SerialName("key")
    data class Key(val key: GlobalKey) : Request

    @Serializable @SerialName("launch")
    data class Launch(val pkg: String) : Request

    @Serializable @SerialName("clipboard_get")
    data object ClipboardGet : Request

    @Serializable @SerialName("clipboard_set")
    data class ClipboardSet(val text: String) : Request

    @Serializable @SerialName("shell")
    data class Shell(val cmd: String) : Request
}

@Serializable
sealed interface Response {
    val ok: Boolean
    val error: String?
    /**
     * True when the foreground package differed before and after the action.
     * A `true` here invalidates every node id the caller is holding.
     */
    val foregroundChanged: Boolean

    @Serializable @SerialName("err")
    data class Err(
        override val error: String,
        override val foregroundChanged: Boolean = false
    ) : Response { override val ok: Boolean get() = false }

    @Serializable @SerialName("ack")
    data class Ack(
        override val foregroundChanged: Boolean = false,
        override val ok: Boolean = true,
        override val error: String? = null
    ) : Response

    @Serializable @SerialName("capabilities")
    data class Capabilities(
        val caps: Set<Cap>,
        val root: Boolean,
        val shizuku: Boolean,
        val a11y: Boolean,
        override val ok: Boolean = true,
        override val error: String? = null,
        override val foregroundChanged: Boolean = false
    ) : Response

    @Serializable @SerialName("session")
    data class Session(
        val sessionId: String,
        val expiresAtEpochMs: Long,
        override val ok: Boolean = true,
        override val error: String? = null,
        override val foregroundChanged: Boolean = false
    ) : Response

    @Serializable @SerialName("tree")
    data class Tree(
        val pkg: String,
        val activity: String?,
        val nodes: List<UiNode>,
        /** Nodes dropped by pruning; a large value hints the screen is canvas-drawn. */
        val pruned: Int,
        override val ok: Boolean = true,
        override val error: String? = null,
        override val foregroundChanged: Boolean = false
    ) : Response

    @Serializable @SerialName("image")
    data class Image(
        val mime: String,
        val b64: String,
        val w: Int,
        val h: Int,
        override val ok: Boolean = true,
        override val error: String? = null,
        override val foregroundChanged: Boolean = false
    ) : Response

    @Serializable @SerialName("apps")
    data class Apps(
        val apps: List<AppEntry>,
        override val ok: Boolean = true,
        override val error: String? = null,
        override val foregroundChanged: Boolean = false
    ) : Response

    @Serializable @SerialName("grants")
    data class Grants(
        val grants: List<GrantEntry>,
        override val ok: Boolean = true,
        override val error: String? = null,
        override val foregroundChanged: Boolean = false
    ) : Response

    @Serializable @SerialName("notifications")
    data class Notifications(
        val items: List<NotificationEntry>,
        override val ok: Boolean = true,
        override val error: String? = null,
        override val foregroundChanged: Boolean = false
    ) : Response

    @Serializable @SerialName("text")
    data class Text(
        val text: String,
        override val ok: Boolean = true,
        override val error: String? = null,
        override val foregroundChanged: Boolean = false
    ) : Response
}

@Serializable
data class AppEntry(val pkg: String, val label: String, val granted: Boolean)

@Serializable
data class GrantEntry(val pkg: String, val scopes: Set<Scope>, val expiresAtEpochMs: Long?)

@Serializable
data class NotificationEntry(
    val pkg: String, val key: String, val title: String?, val text: String?, val postedAtEpochMs: Long
)
