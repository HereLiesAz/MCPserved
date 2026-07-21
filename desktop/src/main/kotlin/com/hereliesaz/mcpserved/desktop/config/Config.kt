package com.hereliesaz.mcpserved.desktop.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Persisted pairing state for the desktop MCP server.
 *
 * Stored under the user's home directory rather than alongside the code, so a
 * checkout of the repository never contains key material and a stray commit
 * cannot publish it. Only the raw keys are kept; the directional frame keys are
 * derived per connection from a fresh salt, so there is nothing durable to store
 * for them.
 *
 * The file shape matches the Node reference server's `pairing.json` exactly, so a
 * machine already paired with the old CLI carries straight over.
 */
@Serializable
data class StoredConfig(
    val deviceId: String,
    val serverPrivateKey: String,
    val devicePublicKey: String,
)

/** A resolved pairing plus the loopback port `adb forward` bridges to the device. */
data class Config(
    val deviceId: String,
    val port: Int,
    val serverPrivateKey: ByteArray,
    val devicePublicKey: ByteArray,
)

object ConfigStore {
    /** Default loopback port; must match `LocalServer.DEFAULT_PORT` on the device. */
    const val DEFAULT_PORT = 8790

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    val configPath: Path =
        Path.of(System.getProperty("user.home"), ".config", "mcpserved", "pairing.json")

    private fun resolvePort(): Int {
        val raw = System.getenv("MCPSERVED_PORT") ?: return DEFAULT_PORT
        val n = raw.toIntOrNull() ?: return DEFAULT_PORT
        return if (n in 1..65535) n else DEFAULT_PORT
    }

    /** Reads the stored pairing, or null when unpaired or unreadable. */
    fun tryLoad(): Config? {
        return try {
            val stored = json.decodeFromString<StoredConfig>(Files.readString(configPath))
            Config(
                deviceId = stored.deviceId,
                port = resolvePort(),
                serverPrivateKey = com.hereliesaz.mcpserved.desktop.crypto.Crypto.unb64(stored.serverPrivateKey),
                devicePublicKey = com.hereliesaz.mcpserved.desktop.crypto.Crypto.unb64(stored.devicePublicKey),
            )
        } catch (_: Exception) {
            null
        }
    }

    val isPaired: Boolean get() = tryLoad() != null

    /** Writes the pairing, creating the directory and restricting permissions. */
    fun save(stored: StoredConfig) {
        val dir = configPath.parent
        Files.createDirectories(dir)
        restrict(dir, dirPerms)
        val bytes = json.encodeToString(StoredConfig.serializer(), stored)
        Files.writeString(configPath, bytes + "\n")
        restrict(configPath, filePerms)
    }

    fun clear() {
        runCatching { Files.deleteIfExists(configPath) }
    }

    private val dirPerms = setOf(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
    )
    private val filePerms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    /** Best-effort POSIX permission tightening; a no-op on Windows. */
    private fun restrict(path: Path, perms: Set<PosixFilePermission>) {
        runCatching { Files.setPosixFilePermissions(path, perms) }
    }
}
