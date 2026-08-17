package com.hereliesaz.mcpserved.transport

/**
 * Turns a [Request] into a [Response].
 *
 * The single seam between a transport ([FrameSession], [McpBridge]) and the
 * actual device dispatch ([com.hereliesaz.mcpserved.service.Dispatcher]),
 * which implements this. Existing as its own interface — rather than
 * [FrameSession] depending on the concrete `Dispatcher` directly — means
 * [FrameSession] carries no compile-time dependency on `service` (or, through
 * it, on Android `Context`), and can be driven in a plain, fast unit test
 * with a fake that returns canned responses.
 */
fun interface RequestHandler {
    suspend fun handle(req: Request): Response
}
