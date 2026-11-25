package com.hereliesaz.mcpserved.desktop.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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

    /** UI-only best-effort read; backend selection must use [tryLoadForBackend]. */
    fun tryLoad(): Config? = runCatching { tryLoadForBackend() }.getOrNull()

    /** Returns null only when absent; invalid or inaccessible pairing files fail closed. */
    fun tryLoadForBackend(path: Path = configPath): Config? {
        val message = "Pairing at $path is unreadable or invalid; repair the pairing before connecting."
        try {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(attrs.isRegularFile)
        } catch (_: NoSuchFileException) {
            return null
        } catch (_: Exception) {
            error(message)
        }
        return try {
            val stored = json.decodeFromString<StoredConfig>(Files.readString(path))
            val privateKey = com.hereliesaz.mcpserved.desktop.crypto.Crypto.unb64(stored.serverPrivateKey)
            val publicKey = com.hereliesaz.mcpserved.desktop.crypto.Crypto.unb64(stored.devicePublicKey)
            check(stored.deviceId.isNotBlank() && privateKey.size == 32 && publicKey.size == 32)
            Config(stored.deviceId, resolvePort(), privateKey, publicKey)
        } catch (_: Exception) {
            // Parsing exceptions may include the private key; never expose them.
            error(message)
        }
    }

    val isPaired: Boolean get() = tryLoad() != null

    /** Writes the pairing, creating the directory and restricting permissions. */
    fun save(stored: StoredConfig) {
        val dir = configPath.parent
        Files.createDirectories(dir)
        restrict(dir, dirPerms)
        val bytes = json.encodeToString(StoredConfig.serializer(), stored)
        // Create the file already owner-only (no window where it is world-readable
        // under a permissive umask) and swap it into place so a crash mid-write
        // cannot leave a truncated pairing.
        val tmp = dir.resolve(configPath.fileName.toString() + ".tmp")
        Files.deleteIfExists(tmp)
        try {
            Files.createFile(tmp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(filePerms))
        } catch (_: UnsupportedOperationException) {
            Files.createFile(tmp) // non-POSIX filesystem (Windows)
        }
        Files.writeString(tmp, bytes + "\n")
        restrict(tmp, filePerms)
        try {
            Files.move(tmp, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
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
