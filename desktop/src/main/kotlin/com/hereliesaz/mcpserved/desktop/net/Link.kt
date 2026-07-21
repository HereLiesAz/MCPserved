package com.hereliesaz.mcpserved.desktop.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The one thing a backend must be: something that answers a protocol request.
 *
 * The tool surface is written entirely in terms of [send], so a backend is free
 * to satisfy it however it likes. Two do: [AppLink] carries sealed frames to the
 * on-device app over a loopback tunnel or a discovered LAN address, and [AdbLink]
 * synthesizes the same responses out of raw `adb` commands. The tools cannot tell
 * them apart, which is the point — the model gets one consistent device whether
 * or not the app is installed.
 *
 * [send] never throws for an ordinary device-level refusal; those come back as
 * `{ ok: false, error }` so the model can reason about them. It throws only for a
 * genuine transport failure the host should surface.
 */
interface Link {
    fun send(request: JsonObject, timeoutMs: Long = 30_000): JsonObject
    fun close()

    /** Human-readable description of how this link reaches the device. */
    val label: String
}

/** Shared JSON parser/serializer for protocol messages. */
val ProtoJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

// ---- Small typed accessors over JsonObject, to keep the ports readable -------

fun ok(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject {
    put("ok", JsonPrimitive(true))
    pairs.forEach { (k, v) -> put(k, v) }
}

fun err(message: String): JsonObject = buildJsonObject {
    put("ok", JsonPrimitive(false))
    put("error", JsonPrimitive(message))
}

fun JsonObject.opOr(default: String? = null): String? = str("op") ?: default

fun JsonObject.isOk(): Boolean = (this["ok"] as? JsonPrimitive)?.booleanOrNull == true
fun JsonObject.errorText(): String = str("error") ?: "unknown error"

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.intOr(key: String, default: Int): Int = str(key)?.toIntOrNull() ?: default
fun JsonObject.longOr(key: String, default: Long): Long = str(key)?.toLongOrNull() ?: default
fun JsonObject.boolOr(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

/** Convenience for reading a string field that may be absent from a nested object. */
fun JsonElement.string(): String? = (this as? JsonPrimitive)?.contentOrNull

@Suppress("unused")
private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key]?.jsonPrimitive
