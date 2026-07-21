package com.hereliesaz.mcpserved.desktop.adb

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper over the `adb` binary.
 *
 * Everything here is stateless: each call spawns adb afresh. That is slower than
 * holding a shell open, but robust against the device dropping off Wi-Fi
 * mid-session, and it keeps the desktop server from owning any long-lived handle
 * to the phone — which matters for a tool whose whole pitch is that the user is
 * in control of the connection.
 *
 * Target selection: `MCPSERVED_ADB_SERIAL` picks a specific device (a USB serial,
 * or an `ip:port` for adb-over-Wi-Fi). Unset, adb's own default applies — fine
 * when exactly one device is attached. `MCPSERVED_ADB` overrides the binary path.
 */
object Adb {
    private val binary: String = System.getenv("MCPSERVED_ADB") ?: "adb"
    private val serial: String? = System.getenv("MCPSERVED_ADB_SERIAL")

    private fun baseArgs(): List<String> = if (serial != null) listOf("-s", serial) else emptyList()

    class AdbException(message: String) : Exception(message)

    data class Result(val stdout: ByteArray, val stderr: String, val code: Int)

    /** Runs `adb <args>` and collects its output. Throws only if adb cannot start or times out. */
    fun exec(args: List<String>, timeoutMs: Long = 30_000): Result {
        val process = try {
            ProcessBuilder(listOf(binary) + baseArgs() + args)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            // Most commonly the binary is missing.
            throw AdbException("ENOENT: ${e.message}")
        }

        val out = ByteArrayOutputStream()
        val errBuf = StringBuilder()
        val outThread = Thread { process.inputStream.copyTo(out) }.apply { isDaemon = true; start() }
        val errThread = Thread {
            process.errorStream.bufferedReader().forEachLine { errBuf.appendLine(it) }
        }.apply { isDaemon = true; start() }

        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw AdbException("adb ${args.joinToString(" ")} timed out")
        }
        outThread.join(2_000)
        errThread.join(2_000)
        return Result(out.toByteArray(), errBuf.toString().trim(), process.exitValue())
    }

    /**
     * Runs a shell command on the device.
     *
     * Text commands go through `shell`; binary output (screencap) goes through
     * `exec-out`, which does not translate `\n` to `\r\n` and so leaves a PNG
     * byte-exact. The command is passed as a single argument, so callers that
     * interpolate untrusted text must quote it with [shellQuote].
     */
    fun shell(cmd: String, binary: Boolean = false, timeoutMs: Long = 30_000): Result {
        val verb = if (binary) "exec-out" else "shell"
        return exec(listOf(verb, cmd), timeoutMs)
    }

    /** Runs a shell command, returning trimmed stdout as text, throwing on nonzero exit. */
    fun shellText(cmd: String, timeoutMs: Long = 30_000): String {
        val r = shell(cmd, binary = false, timeoutMs = timeoutMs)
        if (r.code != 0) {
            val msg = (r.stderr.ifEmpty { String(r.stdout) }).trim()
            throw AdbException(msg.ifEmpty { "adb shell exited ${r.code}" })
        }
        return String(r.stdout)
    }

    /** Bridges a local TCP port to the device. Idempotent; safe to call every connect. */
    fun forward(local: Int, remote: Int) {
        val r = exec(listOf("forward", "tcp:$local", "tcp:$remote"), timeoutMs = 10_000)
        if (r.code != 0) throw AdbException(r.stderr.ifEmpty { "adb forward failed (${r.code})" })
    }

    /** True when adb reports a single reachable device in state `device`. */
    fun ready(): Boolean = try {
        val r = exec(listOf("get-state"), timeoutMs = 8_000)
        r.code == 0 && String(r.stdout).trim() == "device"
    } catch (_: Exception) {
        false
    }

    /**
     * Quotes a string for a single-quoted position in the device's shell.
     *
     * Wraps in single quotes and rewrites embedded single quotes as the usual
     * `'\''` dance. Everything else is literal inside single quotes.
     */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
