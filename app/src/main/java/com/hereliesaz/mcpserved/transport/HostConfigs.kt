package com.hereliesaz.mcpserved.transport

/**
 * Per-host connection snippets for wiring an AI client straight to this device's
 * MCP-over-HTTP endpoint.
 *
 * The device is the server; a host authenticates with the bearer token. What
 * differs between hosts is only the JSON shape and whether the host speaks HTTP
 * with custom headers natively (a direct `url` + `Authorization` entry) or has to
 * be bridged through the `mcp-remote` stdio shim. This mirrors the desktop
 * `connect` command so the phone and the desktop offer the same set of clients.
 *
 * These are copy-to-clipboard snippets, not one-click writes — the phone cannot
 * reach into a config file on another machine. The user pastes them, or copies
 * them across after scanning the endpoint.
 */
object HostConfigs {

    /** One supported AI client. [config] renders its snippet for a given endpoint/token. */
    data class Host(
        val id: String,
        val label: String,
        val hint: String,
        val config: (endpoint: String, token: String) -> String,
    )

    private fun nativeUrl(key: String, vscode: Boolean, endpoint: String, token: String): String {
        // Insert the optional type line via a placeholder replaced AFTER trimIndent.
        // Interpolating a value that contains a newline before trimIndent would make
        // its short indent the common minimum and mis-strip every other line.
        val typeLine = if (vscode) "\n      \"type\": \"http\"," else ""
        val template = """
            {
              "$key": {
                "mcpserved": {<TYPE_LINE>
                  "url": "$endpoint",
                  "headers": { "Authorization": "Bearer $token" }
                }
              }
            }
        """.trimIndent()
        return template.replace("<TYPE_LINE>", typeLine)
    }

    private fun shim(endpoint: String, token: String): String = """
        {
          "mcpServers": {
            "mcpserved": {
              "command": "npx",
              "args": [
                "-y", "mcp-remote", "$endpoint",
                "--header", "Authorization: Bearer $token"
              ]
            }
          }
        }
    """.trimIndent()

    val hosts: List<Host> = listOf(
        Host(
            "claude-code", "Claude Code",
            "Run this in a terminal (uses the mcp-remote bridge).",
        ) { endpoint, token ->
            "claude mcp add mcpserved -s user -- npx -y mcp-remote $endpoint " +
                "--header \"Authorization: Bearer $token\""
        },
        Host(
            "claude-desktop", "Claude Desktop",
            "Paste into claude_desktop_config.json, then restart.",
        ) { e, t -> shim(e, t) },
        Host(
            "cursor", "Cursor",
            "Paste into ~/.cursor/mcp.json (native URL + header).",
        ) { e, t -> nativeUrl("mcpServers", vscode = false, endpoint = e, token = t) },
        Host(
            "vscode", "VS Code",
            "Paste into the user mcp.json (Copilot Agent, native HTTP).",
        ) { e, t -> nativeUrl("servers", vscode = true, endpoint = e, token = t) },
        Host(
            "windsurf", "Windsurf",
            "Paste into ~/.codeium/windsurf/mcp_config.json (mcp-remote).",
        ) { e, t -> shim(e, t) },
        Host(
            "cline", "Cline",
            "Paste into Cline's MCP settings (mcp-remote).",
        ) { e, t -> shim(e, t) },
    )
}
