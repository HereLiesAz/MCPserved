package com.hereliesaz.mcpserved.macro

import com.hereliesaz.mcpserved.transport.Request
import kotlinx.serialization.Serializable

/**
 * A named, recorded sequence of actions, scoped to the package it was
 * recorded against.
 *
 * [steps] are plain [Request]s — the same wire type a live AI action uses —
 * restricted at recording and playback time to [isRecordableStep]'s subset.
 * [MacroPlayer] runs each one through the exact same
 * [com.hereliesaz.mcpserved.service.Dispatcher] path a remote action takes,
 * so a macro can never do more than [pkg]'s grant already permits, and every
 * step still lands in the session log like any other action.
 */
@Serializable
data class Macro(
    val id: String,
    val name: String,
    val pkg: String,
    val steps: List<Request>,
    val createdAtEpochMs: Long
)

/**
 * The only [Request] shapes a macro may record or replay.
 *
 * Deliberately excludes session control, observation, launch, clipboard, and
 * shell: a macro is a recorded interaction with one screen, not a general
 * remote-control script. [com.hereliesaz.mcpserved.macro.MacroRecorder]
 * further narrows this at capture time to what the accessibility event
 * stream can actually observe (tap, long-press, type) — swipe/scroll/key
 * steps can only ever reach a stored [Macro] by some other path, never by
 * live recording.
 */
fun isRecordableStep(req: Request): Boolean = when (req) {
    is Request.Tap, is Request.LongPress, is Request.Swipe,
    is Request.Scroll, is Request.Type, is Request.Key -> true
    else -> false
}
