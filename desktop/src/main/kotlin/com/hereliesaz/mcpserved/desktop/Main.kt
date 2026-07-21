package com.hereliesaz.mcpserved.desktop

import com.hereliesaz.mcpserved.desktop.hosts.Hosts
import com.hereliesaz.mcpserved.desktop.mcp.McpServer
import com.hereliesaz.mcpserved.desktop.pair.PairingFlow
import com.hereliesaz.mcpserved.desktop.service.ServiceDaemon
import com.hereliesaz.mcpserved.desktop.service.ServiceInstaller
import com.hereliesaz.mcpserved.desktop.ui.launchGui

/**
 * One binary, two personalities.
 *
 * Launched with no arguments — the way a user double-clicks the installed app —
 * it opens the Compose desktop window for pairing, discovery, and one-click host
 * setup. Launched with `stdio`, it becomes the headless MCP server an AI host
 * spawns as a subprocess and speaks to over stdin/stdout. The `pair` and
 * `install` verbs cover the same ground from a terminal for the scriptable case.
 *
 * The stdio branch must return before anything touches Compose or AWT, so an MCP
 * host that spawns this process never accidentally brings up a window.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()?.lowercase()) {
        "stdio", "serve" -> McpServer.run()
        "service" -> cliService(args.drop(1))
        "pair" -> cliPair()
        "install" -> cliInstall(args.drop(1))
        "--help", "-h", "help" -> printUsage()
        null -> launchGui()
        else -> {
            System.err.println("unknown command: ${args.first()}")
            printUsage()
        }
    }
}

/**
 * `service` with no argument runs the always-on discovery daemon in the
 * foreground (this is what the OS service manager launches). The sub-verbs
 * register or remove that daemon as a per-user OS service.
 */
private fun cliService(rest: List<String>) {
    when (rest.firstOrNull()?.lowercase()) {
        null, "run" -> ServiceDaemon.run()
        "install", "enable" -> println(ServiceInstaller.install())
        "uninstall", "remove", "disable" -> println(ServiceInstaller.uninstall())
        "status" -> {
            val s = ServiceInstaller.status()
            println(if (s.installed) "installed — ${s.detail}" else "not installed")
        }
        else -> System.err.println("unknown service command: ${rest.first()}")
    }
}

private fun printUsage() {
    println(
        """
        MCPserved desktop — control an authorized Android device from an AI host.

        Usage:
          mcpserved                  open the desktop app (pairing, discovery, host setup)
          mcpserved stdio            run as an MCP server over stdio (what AI hosts launch)
          mcpserved service          run the always-on discovery daemon in the foreground
          mcpserved service install  register the daemon as a per-user OS service
          mcpserved service status   report whether the service is installed
          mcpserved pair             pair with a device from the terminal
          mcpserved install [ids]    register with AI hosts (default: all detected)

        Hosts: ${Hosts.targets.joinToString(", ") { it.id }}, claude-code
        """.trimIndent(),
    )
}

/** Terminal pairing: paste the device payload, get the reply string back. */
private fun cliPair() {
    println("Open MCPserved on the device, go to Pair, and paste the string under its QR code:")
    val payload = readlnOrNull()?.trim().orEmpty()
    try {
        val result = PairingFlow.pairFromPayload(payload)
        println("\nPaired with ${result.deviceId}.")
        println("Now scan this back on the device to complete the exchange:\n")
        println(result.reply)
    } catch (e: Exception) {
        System.err.println("pairing failed: ${e.message}")
    }
}

/** Terminal host registration. */
private fun cliInstall(ids: List<String>) {
    val chosen = if (ids.isEmpty() || ids.contains("--all")) {
        listOf("claude-code") + Hosts.targets.map { it.id }
    } else {
        ids
    }
    for (id in chosen) {
        if (id == "claude-code") {
            println("  ${describe(Hosts.installClaudeCode())}")
            continue
        }
        val target = Hosts.targets.firstOrNull { it.id == id }
        if (target == null) {
            println("  $id — unknown host")
            continue
        }
        println("  ${describe(Hosts.install(target))}")
    }
}

private fun describe(o: Hosts.Outcome): String = when (o) {
    is Hosts.Outcome.Written -> "${o.label}: ${if (o.updated) "updated" else "added"} ${o.path}"
    is Hosts.Outcome.Blocked -> "${o.label}: ${o.path} is not plain JSON — paste manually:\n${o.snippet}"
    is Hosts.Outcome.Unavailable -> "${o.label}: not available on this OS"
    is Hosts.Outcome.External -> "${o.label}: ${o.message}"
}
