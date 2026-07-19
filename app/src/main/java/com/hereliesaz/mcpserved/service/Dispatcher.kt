package com.hereliesaz.mcpserved.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import com.hereliesaz.mcpserved.backend.Resolver
import com.hereliesaz.mcpserved.grant.Enforcer
import com.hereliesaz.mcpserved.grant.GrantStore
import com.hereliesaz.mcpserved.transport.AppEntry
import com.hereliesaz.mcpserved.transport.GrantEntry
import com.hereliesaz.mcpserved.transport.Request
import com.hereliesaz.mcpserved.transport.Response
import com.hereliesaz.mcpserved.transport.Scope

/**
 * Turns a decrypted [Request] into a [Response].
 *
 * The single place where the wire protocol meets the device. Everything upstream
 * — loopback socket, crypto, framing — is transport and knows nothing about what
 * a request means; everything downstream is mechanism and knows nothing about who
 * asked.
 *
 * Three invariants hold for every branch:
 *
 *  1. **Session gating.** Only [Request.Capabilities] and [Request.SessionBegin]
 *     are answerable without a live session. Everything else fails closed. A
 *     capability query is exempt because the caller must be able to discover
 *     what a session would even offer before requesting one.
 *
 *  2. **Grant bracketing.** Every package-scoped operation passes through
 *     [Enforcer.guard], which reads the foreground package before and after.
 *     Nothing calls [Resolver] directly.
 *
 *  3. **Extension on success only.** A failed action does not push the session
 *     deadline back. A caller stuck in a retry loop against a screen that will
 *     never accept input should hit the TTL, not outlive it.
 */
class Dispatcher(
    private val ctx: Context,
    private val service: ControlService
) {

    private val resolver: Resolver get() = service.resolver
    private val grants: GrantStore get() = service.grants
    private val enforcer: Enforcer get() = service.enforcer
    private val session: Session get() = service.session

    suspend fun handle(req: Request): Response {
        // Session gate. Fails closed for everything that touches the device.
        if (req !is Request.Capabilities && req !is Request.SessionBegin) {
            if (!session.isActive) return Response.Err("no active session")
        }

        return when (req) {
            is Request.Capabilities -> capabilities()
            is Request.SessionBegin -> sessionBegin(req)
            is Request.SessionEnd -> sessionEnd()
            is Request.GrantsList -> grantsList()
            is Request.AppsList -> appsList(req)
            is Request.UiTreeReq -> uiTree(req)
            is Request.Screenshot -> screenshot(req)
            is Request.Notifications -> notifications()
            is Request.Tap -> tap(req)
            is Request.LongPress -> longPress(req)
            is Request.Swipe -> swipe(req)
            is Request.Scroll -> scroll(req)
            is Request.Type -> type(req)
            is Request.Key -> key(req)
            is Request.Launch -> launch(req)
            is Request.ClipboardGet -> clipboardGet()
            is Request.ClipboardSet -> clipboardSet(req)
            is Request.Shell -> shell(req)
        }
    }

    // ---- lifecycle and inventory -------------------------------------------

    private fun capabilities() = Response.Capabilities(
        caps = resolver.caps,
        root = resolver.hasRoot,
        shizuku = resolver.hasShizuku,
        a11y = resolver.hasA11y
    )

    private fun sessionBegin(req: Request.SessionBegin): Response {
        val s = service.beginSession(req.ttlSec)
        return Response.Session(s.id, s.expiresAtEpochMs)
    }

    private fun sessionEnd(): Response {
        service.endSession()
        return Response.Ack()
    }

    private suspend fun grantsList() = Response.Grants(
        grants.current().map { GrantEntry(it.pkg, it.scopes, it.expiresAtEpochMs) }
    )

    /**
     * Lists installed launchable applications.
     *
     * Defaults to granted packages only. The ungranted listing is available but
     * opt-in, because a full inventory of installed applications is itself
     * disclosure — what someone has installed says a great deal about them, and
     * none of it is needed to operate the two apps they actually authorized.
     */
    private suspend fun appsList(req: Request.AppsList): Response {
        val granted = grants.current().map { it.pkg }.toSet()
        val pm = ctx.packageManager
        val entries = pm.getInstalledApplications(0)
            .mapNotNull { info ->
                val pkg = info.packageName
                if (req.grantedOnly && pkg !in granted) return@mapNotNull null
                if (!req.grantedOnly && pm.getLaunchIntentForPackage(pkg) == null) {
                    return@mapNotNull null
                }
                AppEntry(pkg, pm.getApplicationLabel(info).toString(), pkg in granted)
            }
            .sortedBy { it.label.lowercase() }
        return Response.Apps(entries)
    }

    // ---- observation --------------------------------------------------------

    private suspend fun uiTree(req: Request.UiTreeReq): Response {
        val out = enforcer.guard(Scope.OBSERVE, "ui_tree") {
            resolver.tree(req.maxDepth)
        }
        val r = out.result.getOrElse { return errFor(it, out.foregroundChanged) }
        onSuccess()
        return Response.Tree(
            pkg = out.pkgBefore,
            activity = resolver.foregroundActivity().getOrNull(),
            nodes = r.nodes,
            pruned = r.pruned,
            foregroundChanged = out.foregroundChanged
        )
    }

    private suspend fun screenshot(req: Request.Screenshot): Response {
        val out = enforcer.guard(Scope.OBSERVE, "screenshot") {
            resolver.capture(req.maxPx)
        }
        val img = out.result.getOrElse { return errFor(it, out.foregroundChanged) }
        onSuccess()
        return Response.Image(
            mime = img.mime,
            b64 = Base64.encodeToString(img.bytes, Base64.NO_WRAP),
            w = img.w,
            h = img.h,
            foregroundChanged = out.foregroundChanged
        )
    }

    private suspend fun notifications(): Response {
        val mirror = NotificationMirror.instance
            ?: return Response.Err("notification access not granted")
        val allowed = grants.current()
            .filter { it.permits(Scope.OBSERVE) }
            .map { it.pkg }
            .toSet()
        onSuccess()
        return Response.Notifications(mirror.snapshot(allowed))
    }

    // ---- interaction --------------------------------------------------------

    /**
     * Resolves a node id to a screen point.
     *
     * Coordinate resolution happens here rather than at the caller so that a node
     * id is the primary way to address anything. Raw coordinates remain available,
     * but a caller working from a tree it fetched three actions ago and computing
     * its own centre points is a caller that will eventually tap the wrong thing
     * after a scroll it did not account for.
     */
    private fun pointFor(nodeId: String?, x: Int?, y: Int?): Pair<Int, Int>? {
        if (nodeId == null) return if (x != null && y != null) x to y else null
        val svc = McpAccessibilityService.instance ?: return null
        val node = svc.findById(nodeId) ?: return null
        val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        if (r.width() <= 0 || r.height() <= 0) return null
        return r.centerX() to r.centerY()
    }

    private suspend fun tap(req: Request.Tap): Response {
        val p = pointFor(req.nodeId, req.x, req.y)
            ?: return Response.Err("unresolvable target")
        val out = enforcer.guard(Scope.INTERACT, "tap") { resolver.tap(p.first, p.second) }
        return ackFor(out)
    }

    private suspend fun longPress(req: Request.LongPress): Response {
        val p = pointFor(req.nodeId, req.x, req.y)
            ?: return Response.Err("unresolvable target")
        val out = enforcer.guard(Scope.INTERACT, "long_press") {
            resolver.longPress(p.first, p.second, req.ms)
        }
        return ackFor(out)
    }

    private suspend fun swipe(req: Request.Swipe): Response {
        val out = enforcer.guard(Scope.INTERACT, "swipe") {
            resolver.swipe(req.x1, req.y1, req.x2, req.y2, req.ms)
        }
        return ackFor(out)
    }

    private suspend fun scroll(req: Request.Scroll): Response {
        val out = enforcer.guard(Scope.INTERACT, "scroll") {
            resolver.scroll(req.nodeId, req.dir)
        }
        return ackFor(out)
    }

    private suspend fun type(req: Request.Type): Response {
        val out = enforcer.guard(Scope.TYPE, "type") {
            resolver.type(req.text, req.nodeId)
        }
        return ackFor(out)
    }

    /**
     * Presses a global key.
     *
     * Scoped to INTERACT and bracketed like any other action, even though HOME
     * and RECENTS deliberately leave the granted package. The check is against
     * the package being left, which is the one that consented to be acted upon.
     */
    private suspend fun key(req: Request.Key): Response {
        val out = enforcer.guard(Scope.INTERACT, "key ${req.key}") { resolver.key(req.key) }
        return ackFor(out)
    }

    /**
     * Launches an application.
     *
     * The grant checked is the target's, not the current foreground's — launching
     * is the one operation whose subject is where you are going rather than where
     * you are. [Enforcer.guard] cannot express that, so the check is explicit.
     */
    private suspend fun launch(req: Request.Launch): Response {
        val g = grants.find(req.pkg)
        if (g == null || !g.permits(Scope.LAUNCH)) {
            service.log.record("launch", req.pkg, denied = true, ok = false)
            return Response.Err("no LAUNCH grant for ${req.pkg}")
        }
        val r = resolver.launch(req.pkg)
        service.log.record("launch", req.pkg, denied = false, ok = r.isSuccess)
        if (r.isFailure) {
            return Response.Err(r.exceptionOrNull()?.message ?: "launch failed")
        }
        onSuccess()
        return Response.Ack()
    }

    // ---- clipboard ----------------------------------------------------------

    /**
     * Reads the clipboard.
     *
     * Since Android 10 an application may only read the clipboard while it holds
     * focus or is the active input method, neither of which applies to a
     * background service. A privileged backend is therefore required; without one
     * this fails rather than returning the empty string the platform would hand
     * back, which would read as an empty clipboard rather than a refusal.
     */
    private suspend fun clipboardGet(): Response {
        val out = enforcer.guard(Scope.OBSERVE, "clipboard_get") {
            resolver.shell("cmd clipboard get-primary-clip 2>/dev/null || service call clipboard 1")
        }
        val text = out.result.getOrElse { return errFor(it, out.foregroundChanged) }
        onSuccess()
        return Response.Text(text.trim(), foregroundChanged = out.foregroundChanged)
    }

    /**
     * Writes the clipboard.
     *
     * Writing is not restricted the way reading is — [ClipboardManager.setPrimaryClip]
     * still works from a background service on most builds, so the unprivileged
     * path is tried first and a privileged backend is only the fallback. Routing
     * both directions through the shell, as an earlier revision did, made an
     * unrooted device fail at an operation it was perfectly capable of.
     *
     * Some OEM builds do silently reject background writes. The result is
     * verified by reading the clip description back rather than trusting the call
     * to have taken effect; a write that was dropped falls through to the shell.
     */
    private suspend fun clipboardSet(req: Request.ClipboardSet): Response {
        val out = enforcer.guard(Scope.TYPE, "clipboard_set") {
            val native = runCatching {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("mcpserved", req.text))
                cm.hasPrimaryClip()
            }.getOrDefault(false)

            if (native) Result.success(Unit)
            else {
                val escaped = req.text.replace("\\", "\\\\").replace("'", "'\\''")
                resolver.shell("cmd clipboard set-primary-clip --text '$escaped'").map { }
            }
        }
        return ackFor(out)
    }

    // ---- shell --------------------------------------------------------------

    /**
     * Runs an arbitrary shell command.
     *
     * Bracketed and logged like every other operation despite reaching well past
     * any single package. A command that can touch everything is precisely the one
     * whose audit line matters most; exempting it because the scoping model does
     * not fit would leave the widest capability as the least recorded.
     */
    private suspend fun shell(req: Request.Shell): Response {
        val out = enforcer.guard(Scope.SHELL, "shell") { resolver.shell(req.cmd) }
        val text = out.result.getOrElse { return errFor(it, out.foregroundChanged) }
        onSuccess()
        return Response.Text(text, foregroundChanged = out.foregroundChanged)
    }

    // ---- shared -------------------------------------------------------------

    private fun onSuccess() = service.touchSession()

    private fun ackFor(out: Enforcer.Outcome<Unit>): Response {
        out.result.getOrElse { return errFor(it, out.foregroundChanged) }
        onSuccess()
        return Response.Ack(foregroundChanged = out.foregroundChanged)
    }

    /**
     * Maps a failure to a wire error.
     *
     * A denial names the package and the missing scope. Telling the caller only
     * that something was refused would leave it guessing between an absent grant,
     * a lapsed one, and a genuine device failure — and a caller that cannot tell
     * those apart retries the one case where retrying is useless.
     */
    private fun errFor(t: Throwable, foregroundChanged: Boolean): Response.Err = when (t) {
        is Enforcer.Denied -> Response.Err("denied: ${t.scope} not granted for ${t.pkg}", foregroundChanged)
        else -> Response.Err(t.message ?: t::class.simpleName ?: "unknown failure", foregroundChanged)
    }
}
