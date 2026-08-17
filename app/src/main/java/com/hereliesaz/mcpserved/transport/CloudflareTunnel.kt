package com.hereliesaz.mcpserved.transport

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs Cloudflare's `cloudflared` as a subprocess in "Quick Tunnel" mode
 * (`cloudflared tunnel --url http://127.0.0.1:<port>`), exposing
 * [LocalWsServer] at a random public `https://…trycloudflare.com` address —
 * anonymous, no Cloudflare account, no login, alive only as long as this
 * process runs. This is what makes a relay possible with nothing pre-
 * deployed: the phone creates the tunnel itself, hands the resulting address
 * to the AI host (the existing "send connect instructions" share action),
 * and the tunnel — and the address — die the moment the app stops it or the
 * process ends. Nothing persists between sessions; nothing needs to.
 *
 * **The binary itself is not part of this class.** `cloudflared` has to ship
 * inside the app — Android (and Play policy) do not allow fetching and
 * executing a native binary after install — placed as `libcloudflared.so`
 * under the appropriate `jniLibs/<abi>/` so the platform extracts it
 * somewhere it is actually permitted to execute from (a plain downloaded-
 * then-chmod'd file in app-writable storage is blocked from executing on
 * modern Android; a native library packaged the normal way is not). If it
 * is not present for the running device's ABI, [start] fails cleanly rather
 * than crashing, and the caller should say so and point at the manual
 * `wrangler`/Cloudflare-dashboard paths in `relay/cloudflare/README.md`.
 *
 * **Unverified on real hardware.** Written against `cloudflared`'s
 * documented `tunnel --url` output shape (a line containing a
 * `https://….trycloudflare.com` URL once the tunnel is up), not exercised
 * against a running binary — there was no device available while writing
 * this. If the URL never appears, [start] times out and reports failure
 * rather than hanging.
 */
class CloudflareTunnel(private val appContext: Context) {

    sealed class Result {
        data class Success(val url: String) : Result()
        data class Failure(val message: String) : Result()
    }

    @Volatile
    private var process: Process? = null

    /** Starts a tunnel to `127.0.0.1:localPort`. Call [stop] to tear it down. */
    suspend fun start(localPort: Int): Result = withContext(Dispatchers.IO) {
        val binary = File(appContext.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) {
            return@withContext Result.Failure(
                "cloudflared isn't bundled for this device's CPU architecture in this build. " +
                    "Use relay/cloudflare/README.md's wrangler or dashboard path instead.",
            )
        }

        runCatching {
            val proc = ProcessBuilder(binary.absolutePath, "tunnel", "--url", "http://127.0.0.1:$localPort")
                .redirectErrorStream(true)
                .start()
            process = proc
            val url = awaitUrl(proc)
            if (url != null) {
                Result.Success(url)
            } else {
                proc.destroy()
                process = null
                Result.Failure("cloudflared didn't report a tunnel URL within ${TIMEOUT_MS / 1000}s.")
            }
        }.getOrElse { e ->
            Log.w(TAG, "cloudflared failed to start", e)
            Result.Failure("Couldn't start cloudflared: ${e.message}")
        }
    }

    /** Kills the tunnel process, if one is running. The public URL stops working immediately. */
    fun stop() {
        process?.destroy()
        process = null
    }

    /**
     * Reads `cloudflared`'s combined stdout/stderr line by line for its
     * announced tunnel URL. `readLine()` itself can't be interrupted
     * cleanly mid-block on the JVM, so the bound here is a wall-clock
     * deadline checked between lines, backstopped by destroying the process
     * (which closes the pipe and unblocks the read) if it's ever exceeded.
     */
    private fun awaitUrl(proc: Process): String? {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        val watchdog = Thread {
            while (System.currentTimeMillis() < deadline) {
                if (process !== proc) return@Thread
                Thread.sleep(500)
            }
            if (process === proc) proc.destroy()
        }.apply { isDaemon = true; start() }

        try {
            proc.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: return null
                    URL_PATTERN.find(line)?.let { return it.value }
                }
            }
        } finally {
            watchdog.interrupt()
        }
    }

    private companion object {
        const val TAG = "CloudflareTunnel"
        const val BINARY_NAME = "libcloudflared.so"
        const val TIMEOUT_MS = 30_000L
        val URL_PATTERN = Regex("""https://[a-zA-Z0-9-]+\.trycloudflare\.com""")
    }
}
