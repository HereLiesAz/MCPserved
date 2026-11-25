package com.hereliesaz.mcpserved.desktop.hosts

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HostsTest {
    @Test
    fun windowsCliUsesNativeOrManifestEntryWithoutShell() {
        val dir = Files.createTempDirectory("mcpserved-claude-")
        try {
            val native = dir.resolve("claude.exe")
            val node = dir.resolve("node.exe")
            assertNull(Hosts.resolveWindowsClaudeCommand(listOf(dir)))
            Files.createFile(native)
            assertEquals(listOf(native.toString()), Hosts.resolveWindowsClaudeCommand(listOf(dir)))
            Files.delete(native)
            Files.createFile(node)
            Files.writeString(dir.resolve("claude.cmd"), "not executed")
            val root = Files.createDirectories(dir.resolve("node_modules/@anthropic-ai/claude-code"))
            val entry = Files.writeString(root.resolve("cli.js"), "// not executed")
            val manifest = root.resolve("package.json")
            Files.writeString(manifest, """{"bin":{"claude":"cli.js"}}""")
            assertEquals(listOf(node.toString(), entry.toString()), Hosts.resolveWindowsClaudeCommand(listOf(dir)))
            Files.writeString(manifest, """{"bin":{"claude":"../../outside.js"}}""")
            Files.createFile(dir.resolve("node_modules/outside.js"))
            assertNull(Hosts.resolveWindowsClaudeCommand(listOf(dir)))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
