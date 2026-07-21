package com.hereliesaz.mcpserved.desktop.hosts

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * One-click registration of this server with the popular AI hosts that speak MCP.
 *
 * Every host needs the same three facts: a name, a command to launch, and its
 * arguments. What differs is only where that lives on disk and the exact JSON key
 * it hangs under. This file knows those differences so the operator never
 * hand-edits a config file and gets the path, the key, and the JSON shape all
 * right at once.
 *
 * It is conservative with files it did not write: it merges a single entry into a
 * host's existing config and leaves every other server untouched, and if a file
 * is present but not parseable as plain JSON it refuses to rewrite it and returns
 * the snippet to paste instead — corrupting an editor config is a worse outcome
 * than one manual step.
 */
object Hosts {

    const val SERVER_NAME = "mcpserved"

    enum class Key(val json: String) { MCP_SERVERS("mcpServers"), SERVERS("servers") }

    /** How a host should launch this server over stdio. */
    data class Launch(val command: String, val args: List<String>, val env: Map<String, String>)

    data class Target(
        val id: String,
        val label: String,
        val key: Key,
        val vscodeShape: Boolean,
        val path: () -> Path?,
        val note: String? = null,
    )

    sealed interface Outcome {
        data class Written(val label: String, val path: String, val updated: Boolean, val note: String?) : Outcome
        data class Blocked(val label: String, val path: String, val snippet: String) : Outcome
        data class Unavailable(val label: String) : Outcome
        data class External(val label: String, val message: String, val ok: Boolean) : Outcome
    }

    private val os = System.getProperty("os.name").lowercase()
    private val isMac = os.contains("mac")
    private val isWindows = os.contains("win")
    private fun home(): Path = Path.of(System.getProperty("user.home"))

    private fun appData(): Path {
        System.getenv("APPDATA")?.let { return Path.of(it) }
        return if (isMac) home().resolve("Library/Application Support") else home().resolve(".config")
    }

    private fun codeUserDir(app: String): Path = when {
        isMac -> home().resolve("Library/Application Support/$app/User")
        isWindows -> appData().resolve("$app/User")
        else -> home().resolve(".config/$app/User")
    }

    /** The popular MCP-capable AI hosts, in the order the UI lists them. */
    val targets: List<Target> = listOf(
        Target(
            id = "claude-desktop", label = "Claude Desktop", key = Key.MCP_SERVERS, vscodeShape = false,
            path = {
                when {
                    isMac -> home().resolve("Library/Application Support/Claude/claude_desktop_config.json")
                    isWindows -> appData().resolve("Claude/claude_desktop_config.json")
                    else -> home().resolve(".config/Claude/claude_desktop_config.json")
                }
            },
            note = "Restart Claude Desktop after installing.",
        ),
        Target(
            id = "cursor", label = "Cursor", key = Key.MCP_SERVERS, vscodeShape = false,
            path = { home().resolve(".cursor/mcp.json") },
            note = "Reload Cursor; check Settings → MCP for a green dot.",
        ),
        Target(
            id = "vscode", label = "VS Code", key = Key.SERVERS, vscodeShape = true,
            path = { codeUserDir("Code").resolve("mcp.json") },
            note = "Requires GitHub Copilot with MCP (Agent mode).",
        ),
        Target(
            id = "vscode-insiders", label = "VS Code Insiders", key = Key.SERVERS, vscodeShape = true,
            path = { codeUserDir("Code - Insiders").resolve("mcp.json") },
        ),
        Target(
            id = "windsurf", label = "Windsurf", key = Key.MCP_SERVERS, vscodeShape = false,
            path = { home().resolve(".codeium/windsurf/mcp_config.json") },
        ),
        Target(
            id = "cline", label = "Cline (VS Code)", key = Key.MCP_SERVERS, vscodeShape = false,
            path = { codeUserDir("Code").resolve("globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json") },
        ),
    )

    /**
     * How a host should launch this build in stdio mode.
     *
     * The command is this running executable — the jpackage launcher when
     * installed, so it works with no PATH assumptions and survives the host
     * running from a different working directory — plus the `stdio` argument that
     * puts it into headless server mode. Any MCPSERVED_* variables set in this
     * environment are carried through.
     */
    fun stdioLaunch(): Launch {
        val self = ProcessHandle.current().info().command().orElse("mcpserved")
        val env = buildMap {
            for (k in listOf("MCPSERVED_MODE", "MCPSERVED_ADB_SERIAL", "MCPSERVED_ADB", "MCPSERVED_PORT")) {
                System.getenv(k)?.let { put(k, it) }
            }
        }
        return Launch(command = self, args = listOf("stdio"), env = env)
    }

    private fun entry(target: Target, launch: Launch): JsonObject = buildJsonObject {
        if (target.vscodeShape) put("type", JsonPrimitive("stdio"))
        put("command", JsonPrimitive(launch.command))
        put("args", kotlinx.serialization.json.buildJsonArray { launch.args.forEach { add(JsonPrimitive(it)) } })
        if (launch.env.isNotEmpty()) {
            put("env", buildJsonObject { launch.env.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
        }
    }

    private fun snippet(target: Target, entry: JsonObject): String {
        val root = buildJsonObject { put(target.key.json, buildJsonObject { put(SERVER_NAME, entry) }) }
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    /** Registers this server with one host, returning what happened. */
    fun install(target: Target, launch: Launch = stdioLaunch()): Outcome {
        val path = target.path() ?: return Outcome.Unavailable(target.label)
        val entry = entry(target, launch)
        return when (val res = writeConfig(path, target.key, entry)) {
            WriteResult.BLOCKED -> Outcome.Blocked(target.label, path.toString(), snippet(target, entry))
            else -> Outcome.Written(target.label, path.toString(), res == WriteResult.UPDATED, target.note)
        }
    }

    /**
     * Claude Code has a first-class CLI for this; use it rather than guessing at
     * its project-scoped config file. Falls back to returning the command when the
     * `claude` binary is not on PATH.
     */
    fun installClaudeCode(launch: Launch = stdioLaunch()): Outcome {
        val cmd = mutableListOf("mcp", "add", SERVER_NAME, "-s", "user")
        launch.env.forEach { (k, v) -> cmd += listOf("-e", "$k=$v") }
        cmd += "--"
        cmd += launch.command
        cmd += launch.args

        val binary = if (isWindows) "claude.cmd" else "claude"
        return try {
            val p = ProcessBuilder(listOf(binary) + cmd).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            val code = p.waitFor()
            if (code == 0) Outcome.External("Claude Code", "added via `claude mcp add` (user scope)", ok = true)
            else Outcome.External("Claude Code", "`claude mcp add` exited $code. Run it yourself: claude ${cmd.joinToString(" ")}", ok = false)
        } catch (_: Exception) {
            Outcome.External("Claude Code", "`claude` not found on PATH. Run it yourself: claude ${cmd.joinToString(" ")}", ok = false)
        }
    }

    private enum class WriteResult { CREATED, UPDATED, BLOCKED }

    private fun writeConfig(path: Path, key: Key, entry: JsonObject): WriteResult {
        var root: JsonObject = buildJsonObject { }
        var replacing = false

        if (Files.exists(path)) {
            val parsed = try {
                prettyJson.parseToJsonElement(Files.readString(path))
            } catch (_: Exception) {
                return WriteResult.BLOCKED
            }
            if (parsed !is JsonObject) return WriteResult.BLOCKED
            root = parsed
            val bucket = root[key.json] as? JsonObject
            replacing = bucket?.containsKey(SERVER_NAME) == true
        }

        val existingBucket = (root[key.json] as? JsonObject) ?: buildJsonObject { }
        val newBucket = buildJsonObject {
            existingBucket.forEach { (k, v) -> if (k != SERVER_NAME) put(k, v) }
            put(SERVER_NAME, entry)
        }
        val newRoot = buildJsonObject {
            root.forEach { (k, v) -> if (k != key.json) put(k, v) }
            put(key.json, newBucket)
        }

        Files.createDirectories(path.parent)
        Files.writeString(path, prettyJson.encodeToString(JsonObject.serializer(), newRoot) + "\n")
        return if (replacing) WriteResult.UPDATED else WriteResult.CREATED
    }

    private val prettyJson = kotlinx.serialization.json.Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
}
