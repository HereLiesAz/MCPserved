package com.hereliesaz.mcpserved.transport

import android.util.Log
import com.hereliesaz.mcpserved.crypto.McpToken
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The device's own MCP server, spoken over HTTP so a model's host connects
 * straight to the phone — no desktop process in the path.
 *
 * Transport is MCP's Streamable HTTP in its simplest compliant form: the client
 * POSTs a single JSON-RPC message and receives a single JSON response. The server
 * offers no server-initiated stream, so a `GET` (which a client would use to open
 * one) is answered `405` and every exchange is one request, one reply. The MCP
 * semantics — `initialize`, `tools/list`, `tools/call`, `ping` — live in
 * [McpBridge]; this class is only HTTP, auth, and JSON-RPC framing.
 *
 * It binds to `127.0.0.1` by default, exactly like [LocalServer]'s loopback
 * path: loopback is not routable, so nothing off-device reaches it, and the
 * operator bridges to it with a single `adb forward tcp:$port tcp:$port` over
 * USB or adb-over-Wi-Fi. A caller may instead pass [bindHost] as the wildcard
 * address, opt-in and off by default (see
 * [com.hereliesaz.mcpserved.grant.RemoteAccessStore]), so the endpoint is
 * reachable directly on a private mesh (Tailscale, WireGuard) without a
 * tunnel. Either way loopback-or-not is not the authorization boundary — any
 * process that can reach the socket can dial it — so every request must carry
 * `Authorization: Bearer <token>` (either the device credential or an OAuth
 * access token issued for the active public origin); otherwise it gets `401`. Widening the bind widens who can knock, never who can walk in
 * without the token.
 */
class McpServer(
    private val token: McpToken,
    private val bridge: McpBridge,
    port: Int = DEFAULT_HTTP_PORT,
    private val bindHost: String = LOOPBACK,
) : NanoHTTPD(bindHost, port) {

    private val oauth = McpOAuth(token::value, token::oauthRegistrationKey)

    /** Publish OAuth discovery only for an explicitly started HTTPS tunnel. */
    fun enableOAuthOrigin(url: String) = oauth.enableOrigin(url)

    /** Revoke this tunnel's pending consent, codes and issued OAuth tokens. */
    fun disableOAuthOrigin(url: String) = oauth.disableOrigin(url)

    /** Starts listening. Returns false if the port could not be bound. */
    fun startServer(): Boolean = try {
        start(SOCKET_READ_TIMEOUT, true)
        true
    } catch (e: Exception) {
        Log.w(TAG, "MCP HTTP server failed to bind on $bindHost", e)
        false
    }

    /** Stops listening. Safe to call whether or not [startServer] succeeded. */
    fun stopServer() {
        oauth.clear()
        runCatching { stop() }
    }

    override fun serve(session: IHTTPSession): Response {
        // OAuth endpoints cannot reach the dispatcher. Discovery and consent
        // are only published at active, service-owned HTTPS tunnel origins.
        oauth.serve(session)?.let { return it }

        // Both the original device bearer and scoped OAuth access tokens must
        // pass this gate before a JSON-RPC message can reach the bridge.
        val header = session.headers["authorization"]
        val presented = header
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(BEARER_PREFIX_LEN)
            ?.trim()
        if (!token.matches(presented) && !oauth.accepts(session, presented)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_JSON, "").apply {
                addHeader("WWW-Authenticate", oauth.challenge(session))
            }
        }

        // Authenticated GET is not a stream: this server is stateless HTTP.
        if (session.method != Method.POST) {
            return jsonResponse(Response.Status.METHOD_NOT_ALLOWED,
                rpcError(JsonNull, -32600, "only POST is supported")).apply {
                addHeader("Allow", "POST")
            }
        }

        val body = try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (e: Exception) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                rpcError(JsonNull, -32700, "could not read request body"),
            )
        }

        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return jsonResponse(Response.Status.BAD_REQUEST, rpcError(JsonNull, -32700, "parse error"))
        }

        val idElement = root["id"]
        val id = idElement ?: JsonNull
        val method = (root["method"] as? JsonPrimitive)?.contentOrNull
        val params = root["params"] as? JsonObject

        if (method == null) {
            return jsonResponse(Response.Status.BAD_REQUEST, rpcError(id, -32600, "missing method"))
        }

        // A JSON-RPC notification carries no id; it is acknowledged with 202 and
        // no body (e.g. notifications/initialized).
        if (idElement == null || idElement is JsonNull) {
            return newFixedLengthResponse(Response.Status.ACCEPTED, MIME_JSON, "")
        }

        val result: JsonElement = when (method) {
            "initialize" -> bridge.initialize()
            "tools/list" -> bridge.toolsList()
            "tools/call" -> try {
                runBlocking { bridge.toolsCall(params) }
            } catch (e: Exception) {
                return jsonResponse(
                    Response.Status.OK,
                    rpcError(id, -32603, e.message ?: "internal error"),
                )
            }
            "ping" -> buildJsonObject {}
            else -> return jsonResponse(
                Response.Status.OK,
                rpcError(id, -32601, "method not found: $method"),
            )
        }

        return jsonResponse(Response.Status.OK, rpcResult(id, result))
    }

    private fun rpcResult(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun rpcError(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private fun jsonResponse(status: Response.Status, body: JsonObject): Response =
        newFixedLengthResponse(status, MIME_JSON, body.toString())

    companion object {
        /** Loopback port for the MCP HTTP server; forwarded from the host by adb. */
        const val DEFAULT_HTTP_PORT = 8791
        private const val LOOPBACK = "127.0.0.1"
        private const val MIME_JSON = "application/json"
        private const val BEARER_PREFIX_LEN = 7 // "Bearer ".length
        private const val TAG = "McpServer"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
