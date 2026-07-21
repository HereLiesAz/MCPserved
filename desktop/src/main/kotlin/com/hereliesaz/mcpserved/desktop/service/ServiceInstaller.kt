package com.hereliesaz.mcpserved.desktop.service

import java.nio.file.Files
import java.nio.file.Path

/**
 * Installs the [ServiceDaemon] as an OS-managed background service, so the
 * desktop is always looking for the phone without anyone leaving the app open.
 *
 * Each platform has its own idiom and this uses the least-privilege, per-user one
 * on each: a systemd **user** unit on Linux, a launchd **LaunchAgent** on macOS,
 * and the current-user **Run** key on Windows. None of them needs root, and all
 * of them survive a reboot and restart the daemon if it dies.
 */
object ServiceInstaller {

    private const val LABEL = "com.hereliesaz.mcpserved"
    private const val UNIT = "mcpserved.service"

    private val os = System.getProperty("os.name").lowercase()
    private val isMac = os.contains("mac")
    private val isWindows = os.contains("win")
    private fun home(): Path = Path.of(System.getProperty("user.home"))

    data class Status(val installed: Boolean, val detail: String)

    /** This running executable — the jpackage launcher once installed. */
    private fun self(): String = ProcessHandle.current().info().command().orElse("mcpserved")

    private data class Exec(val code: Int, val output: String)

    private fun run(vararg cmd: String): Exec = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        Exec(p.waitFor(), out)
    } catch (e: Exception) {
        Exec(-1, e.message ?: e.toString())
    }

    // ---- install ----------------------------------------------------------

    fun install(): String = when {
        isWindows -> installWindows()
        isMac -> installMac()
        else -> installLinux()
    }

    fun uninstall(): String = when {
        isWindows -> uninstallWindows()
        isMac -> uninstallMac()
        else -> uninstallLinux()
    }

    fun status(): Status = when {
        isWindows -> statusWindows()
        isMac -> statusMac()
        else -> statusLinux()
    }

    // ---- Linux: systemd user unit -----------------------------------------

    private fun linuxUnitPath(): Path = home().resolve(".config/systemd/user/$UNIT")

    private fun installLinux(): String {
        val unit = """
            [Unit]
            Description=MCPserved — keep looking for the paired device
            After=network-online.target

            [Service]
            ExecStart=${self()} service
            Restart=on-failure
            RestartSec=5

            [Install]
            WantedBy=default.target
        """.trimIndent() + "\n"

        val path = linuxUnitPath()
        Files.createDirectories(path.parent)
        Files.writeString(path, unit)

        run("systemctl", "--user", "daemon-reload")
        val enable = run("systemctl", "--user", "enable", "--now", UNIT)
        return if (enable.code == 0) {
            "Installed and started (systemd user unit at $path)."
        } else {
            "Wrote $path, but `systemctl --user enable --now` failed: ${enable.output}. " +
                "Enable it manually, or start the daemon from the app meanwhile."
        }
    }

    private fun uninstallLinux(): String {
        run("systemctl", "--user", "disable", "--now", UNIT)
        val path = linuxUnitPath()
        val removed = runCatching { Files.deleteIfExists(path) }.getOrDefault(false)
        run("systemctl", "--user", "daemon-reload")
        return if (removed) "Removed the systemd user unit." else "No systemd user unit was installed."
    }

    private fun statusLinux(): Status {
        val active = run("systemctl", "--user", "is-active", UNIT).output
        val installed = Files.exists(linuxUnitPath())
        return Status(installed, if (installed) "systemd user unit: $active" else "not installed")
    }

    // ---- macOS: launchd LaunchAgent ---------------------------------------

    private fun macPlistPath(): Path = home().resolve("Library/LaunchAgents/$LABEL.plist")

    private fun installMac(): String {
        val logDir = home().resolve("Library/Logs")
        runCatching { Files.createDirectories(logDir) }
        val plist = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key><string>$LABEL</string>
                <key>ProgramArguments</key>
                <array>
                    <string>${self()}</string>
                    <string>service</string>
                </array>
                <key>RunAtLoad</key><true/>
                <key>KeepAlive</key><true/>
                <key>StandardOutPath</key><string>${logDir.resolve("mcpserved.log")}</string>
                <key>StandardErrorPath</key><string>${logDir.resolve("mcpserved.log")}</string>
            </dict>
            </plist>
        """.trimIndent() + "\n"

        val path = macPlistPath()
        Files.createDirectories(path.parent)
        Files.writeString(path, plist)

        run("launchctl", "unload", path.toString())
        val load = run("launchctl", "load", "-w", path.toString())
        return if (load.code == 0) {
            "Installed and started (LaunchAgent at $path)."
        } else {
            "Wrote $path, but `launchctl load` failed: ${load.output}."
        }
    }

    private fun uninstallMac(): String {
        val path = macPlistPath()
        run("launchctl", "unload", "-w", path.toString())
        val removed = runCatching { Files.deleteIfExists(path) }.getOrDefault(false)
        return if (removed) "Removed the LaunchAgent." else "No LaunchAgent was installed."
    }

    private fun statusMac(): Status {
        val installed = Files.exists(macPlistPath())
        val listed = run("launchctl", "list").output.contains(LABEL)
        return Status(installed, if (installed) "LaunchAgent ${if (listed) "loaded" else "installed"}" else "not installed")
    }

    // ---- Windows: current-user Run key ------------------------------------

    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val RUN_VALUE = "MCPserved"

    private fun installWindows(): String {
        val cmd = "\"${self()}\" service"
        val add = run("reg", "add", RUN_KEY, "/v", RUN_VALUE, "/t", "REG_SZ", "/d", cmd, "/f")
        return if (add.code == 0) {
            "Installed. The service starts automatically at your next sign-in."
        } else {
            "Could not write the Run key: ${add.output}."
        }
    }

    private fun uninstallWindows(): String {
        val del = run("reg", "delete", RUN_KEY, "/v", RUN_VALUE, "/f")
        return if (del.code == 0) "Removed the autostart entry." else "No autostart entry was installed."
    }

    private fun statusWindows(): Status {
        val q = run("reg", "query", RUN_KEY, "/v", RUN_VALUE)
        val installed = q.code == 0
        return Status(installed, if (installed) "autostart registered (starts at sign-in)" else "not installed")
    }
}
