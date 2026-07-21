package com.hereliesaz.mcpserved.desktop.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * The last LAN address the background service saw the paired device at.
 *
 * The service daemon browses mDNS continuously and writes what it finds here. A
 * stdio server — spawned fresh by an AI host, with no time to run its own
 * discovery sweep — reads this first and dials the device immediately. The result
 * is that the connection is already warm the instant a model asks for it, instead
 * of paying a multi-second browse on every launch.
 *
 * The entry is timestamped and treated as advisory: a stale address is tried and,
 * if it does not answer, the caller falls back to a live browse and then adb. It
 * is never an authority — the pairing key still gates every byte.
 */
@Serializable
data class DiscoveredAddress(
    val deviceId: String,
    val host: String,
    val port: Int,
    val updatedAtEpochMs: Long,
)

object DiscoveryCache {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val path: Path =
        Path.of(System.getProperty("user.home"), ".config", "mcpserved", "discovered.json")

    /** How long a cached address is considered worth trying before a fresh browse. */
    const val FRESH_MS = 60_000L

    @Synchronized
    fun write(address: DiscoveredAddress) {
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, json.encodeToString(DiscoveredAddress.serializer(), address))
        }
    }

    @Synchronized
    fun read(): DiscoveredAddress? = runCatching {
        json.decodeFromString<DiscoveredAddress>(Files.readString(path))
    }.getOrNull()

    /** A cached address for [deviceId] that was seen within [FRESH_MS], or null. */
    fun fresh(deviceId: String, nowMs: Long): DiscoveredAddress? {
        val a = read() ?: return null
        return if (a.deviceId == deviceId && nowMs - a.updatedAtEpochMs <= FRESH_MS) a else null
    }

    @Synchronized
    fun clear() {
        runCatching { Files.deleteIfExists(path) }
    }
}
