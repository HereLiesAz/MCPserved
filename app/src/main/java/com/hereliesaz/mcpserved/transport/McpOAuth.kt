package com.hereliesaz.mcpserved.transport

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OAuth authorization server for the device's opt-in HTTPS MCP tunnels.
 * Public client registration is limited to the stable ChatGPT callback. Consent
 * proves possession of the existing device credential; OAuth never mints or
 * discloses that credential. Tokens are opaque, short-lived, origin-bound,
 * revoked with the credential generation, and cannot bypass the MCP dispatcher.
 * Registration identifiers are authenticated with a separate persistent key.
 *
 * All mutable grant state is bounded, in-memory and protected by [lock].
 * Request bodies are parsed before acquiring it; no network I/O runs under it.
 * [clock] is monotonic so wall-clock changes cannot extend grants.
 */
internal class McpOAuth(
    private val bearer: () -> String,
    private val registrationKey: () -> String,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()
    private val random = SecureRandom()
    private val origins = mutableSetOf<String>()
    private val consents = mutableMapOf<String, Consent>()
    private val codes = mutableMapOf<String, Code>()
    private val access = mutableMapOf<String, Grant>()
    private val refresh = mutableMapOf<String, Grant>()

    private data class Consent(
        val origin: String, val client: String, val challenge: String,
        val state: String, val expires: Long,
    )
    private data class Code(val consent: Consent, val generation: String, val expires: Long)
    private data class Grant(
        val origin: String, val client: String, val generation: String,
        val expires: Long, val consentExpires: Long,
    )

    /** Only a successful service-owned tunnel may add an origin. */
    fun enableOrigin(url: String) = synchronized(lock) {
        val parsed = URI(url)
        require(parsed.scheme == "https" && parsed.host != null &&
            parsed.rawAuthority == parsed.host && parsed.rawPath.isNullOrEmpty() &&
            parsed.rawQuery == null && parsed.rawFragment == null)
        origins.add("https://${parsed.host.lowercase()}")
        Unit
    }

    /** Stop only this origin; credentials for other active tunnels are unaffected. */
    fun disableOrigin(url: String) = synchronized(lock) {
        val origin = url.lowercase()
        origins.remove(origin)
        consents.entries.removeAll { it.value.origin == origin }
        codes.entries.removeAll { it.value.consent.origin == origin }
        access.entries.removeAll { it.value.origin == origin }
        refresh.entries.removeAll { it.value.origin == origin }
        Unit
    }

    /** Disarming destroys every live OAuth grant. Public registration survives. */
    fun clear() = synchronized(lock) {
        origins.clear(); consents.clear(); codes.clear(); access.clear(); refresh.clear()
    }

    private fun origin(session: IHTTPSession): String? {
        // Never derive metadata from X-Forwarded-Host or arbitrary Host input.
        // Cloudflare preserves the public Host; it must match a running tunnel.
        val host = session.headers["host"]?.lowercase()?.removeSuffix(":443") ?: return null
        return "https://$host".takeIf { it in origins }
    }

    /** Unauthenticated MCP requests advertise discovery only on active tunnels. */
    fun challenge(session: IHTTPSession): String = synchronized(lock) {
        origin(session)?.let { "Bearer resource_metadata=\"$it$RESOURCE_METADATA\", scope=\"$SCOPE\"" }
            ?: "Bearer"
    }

    /** Validate an opaque access token against the exact resource and current bearer. */
    fun accepts(session: IHTTPSession, presented: String?): Boolean = synchronized(lock) {
        if (session.uri != "/mcp" || presented == null || presented.length != SECRET_LENGTH) return@synchronized false
        val origin = origin(session) ?: return@synchronized false
        val grant = access[hash(presented)] ?: return@synchronized false
        grant.origin == origin && clock() < grant.expires && same(grant.generation, hash(bearer()))
    }

    /** Public metadata and narrowly scoped OAuth endpoints; null means ordinary MCP. */
    fun serve(session: IHTTPSession): Response? {
        val path = session.uri
        if (path !in ROUTES) return null
        val origin = synchronized(lock) { origin(session) } ?: return error("invalid_request", Response.Status.NOT_FOUND)
        if (session.method == Method.GET) {
            return synchronized(lock) {
                if (origin !in origins) return@synchronized error("invalid_request", Response.Status.NOT_FOUND)
                prune()
                when (path) {
                    RESOURCE_METADATA, RESOURCE_ROOT -> resourceMetadata(origin)
                    AUTH_METADATA -> authorizationMetadata(origin)
                    AUTHORIZE -> begin(origin, fields(session))
                    else -> error("invalid_request", Response.Status.METHOD_NOT_ALLOWED)
                }
            }
        }
        if (session.method != Method.POST || path !in setOf(REGISTER, AUTHORIZE, TOKEN))
            return error("invalid_request", Response.Status.METHOD_NOT_ALLOWED)
        // Cap unauthenticated bodies before NanoHTTPD allocates or spools them.
        val length = session.headers["content-length"]?.toLongOrNull()
        if (length == null || length !in 1L..MAX_BODY || session.headers.containsKey("transfer-encoding") ||
            !session.queryParameterString.isNullOrEmpty()) return error("invalid_request")
        val contentType = session.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()
        if (contentType != if (path == REGISTER) "application/json" else "application/x-www-form-urlencoded")
            return error("invalid_request")
        val body = HashMap<String, String>()
        try { session.parseBody(body) } catch (_: Exception) { return error("invalid_request") }
        return synchronized(lock) {
            if (origin !in origins) return@synchronized error("invalid_request", Response.Status.NOT_FOUND)
            prune()
            when (path) {
                REGISTER -> register(body["postData"] ?: "")
                AUTHORIZE -> {
                    // A consent form may be submitted only from its own origin.
                    if (session.headers["origin"] != origin) error("invalid_request")
                    else approve(origin, fields(session))
                }
                TOKEN -> exchange(origin, fields(session))
                else -> error("invalid_request")
            }
        }
    }

    private fun fields(session: IHTTPSession): Map<String, String>? {
        val p = session.parameters
        if (p.values.any { it.size != 1 } || p.any { it.key.length > 128 || it.value.single().length > 4096 }) return null
        return p.mapValues { it.value.single() }
    }

    private fun resourceMetadata(origin: String) = jsonResponse(buildJsonObject {
        put("resource", "$origin/mcp")
        put("authorization_servers", strings(origin))
        put("scopes_supported", strings(SCOPE))
        put("bearer_methods_supported", strings("header"))
    })

    private fun authorizationMetadata(origin: String) = jsonResponse(buildJsonObject {
        put("issuer", origin)
        put("authorization_endpoint", "$origin$AUTHORIZE")
        put("token_endpoint", "$origin$TOKEN")
        put("registration_endpoint", "$origin$REGISTER")
        put("response_types_supported", strings("code"))
        put("grant_types_supported", strings("authorization_code", "refresh_token"))
        put("token_endpoint_auth_methods_supported", strings("none"))
        put("code_challenge_methods_supported", strings("S256"))
        put("scopes_supported", strings(SCOPE))
        put("authorization_response_iss_parameter_supported", true)
    })

    private fun register(body: String): Response {
        val request = try { Json.parseToJsonElement(body).jsonObject } catch (_: Exception) {
            return error("invalid_client_metadata")
        }
        val redirects = request["redirect_uris"] as? JsonArray ?: return error("invalid_redirect_uri")
        if (redirects.size != 1 || (redirects[0] as? JsonPrimitive)?.contentOrNull != CALLBACK)
            return error("invalid_redirect_uri")
        if (request["token_endpoint_auth_method"] != null && string(request, "token_endpoint_auth_method") != "none")
            return error("invalid_client_metadata")
        val id = secret()
        // Self-authenticating IDs avoid storing unbounded attacker-controlled
        // registrations. The callback, grant types and scope are fixed server-side.
        val client = "$id.${signature(id)}"
        return jsonResponse(buildJsonObject {
            put("client_id", client)
            put("redirect_uris", strings(CALLBACK))
            put("token_endpoint_auth_method", "none")
            put("grant_types", strings("authorization_code", "refresh_token"))
            put("response_types", strings("code"))
            put("scope", SCOPE)
        }, Response.Status.CREATED)
    }

    private fun validClient(client: String?): Boolean {
        if (client == null) return false
        val parts = client.split('.')
        if (parts.size != 2 || !SECRET.matches(parts[0]) || !SECRET.matches(parts[1])) return false
        return same(signature(parts[0]), parts[1])
    }

    private fun begin(origin: String, p: Map<String, String>?): Response {
        if (p == null || !validClient(p["client_id"]) || p["redirect_uri"] != CALLBACK) return error("invalid_request")
        if (p["response_type"] != "code" || p["code_challenge_method"] != "S256" ||
            p["resource"] != "$origin/mcp" || !SECRET.matches(p["code_challenge"] ?: "") ||
            p["state"].isNullOrEmpty() || (p["scope"] ?: SCOPE) != SCOPE)
            return redirect(origin, "error" to "invalid_request", "state" to (p["state"] ?: ""))
        if (consents.size >= LIMIT) return error("temporarily_unavailable", Response.Status.SERVICE_UNAVAILABLE)
        val nonce = secret()
        consents[nonce] = Consent(origin, p.getValue("client_id"), p.getValue("code_challenge"),
            p.getValue("state"), clock() + CONSENT_MS)
        // No client-controlled markup, script, external resource, or secret in a URL.
        return response(Response.Status.OK, "text/html; charset=utf-8", """
            <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>MCPserved authorization</title></head><body>
            <h1>Connect ChatGPT to MCPserved</h1>
            <p>Approve only if you started this connection and this HTTPS hostname matches the phone.
            ChatGPT can request device actions subject to your existing session and package grants.
            Cloudflare can inspect this connection.</p>
            <form method="post" action="$AUTHORIZE" autocomplete="off">
            <input type="hidden" name="request" value="$nonce">
            <label>Device token <input type="password" name="device_token" required autocomplete="off"></label>
            <button type="submit" name="decision" value="approve">Approve</button>
            <button type="submit" name="decision" value="deny" formnovalidate>Deny</button>
            </form></body></html>
        """.trimIndent()).apply {
            addHeader("Content-Security-Policy", "default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
            addHeader("X-Frame-Options", "DENY")
        }
    }

    private fun approve(origin: String, p: Map<String, String>?): Response {
        if (p == null) return error("invalid_request")
        val nonce = p["request"] ?: return error("invalid_request")
        val consent = consents[nonce] ?: return error("invalid_request")
        if (consent.origin != origin) return error("invalid_request")
        consents.remove(nonce) // single use, including denial and failed login
        if (p["decision"] == "deny") return redirect(origin, "error" to "access_denied", "state" to consent.state)
        val current = bearer()
        if (p["decision"] != "approve" || !same(current, p["device_token"] ?: ""))
            return redirect(origin, "error" to "access_denied", "state" to consent.state)
        if (codes.size >= LIMIT) return redirect(origin, "error" to "temporarily_unavailable", "state" to consent.state)
        val code = secret()
        codes[hash(code)] = Code(consent, hash(current), clock() + CODE_MS)
        return redirect(origin, "code" to code, "state" to consent.state)
    }

    private fun exchange(origin: String, p: Map<String, String>?): Response {
        if (p == null || p["resource"] != "$origin/mcp") return error("invalid_target")
        val client = p["client_id"]
        if (!validClient(client)) return error("invalid_client")
        if (p["scope"] != null && p["scope"] != SCOPE) return error("invalid_scope")
        val current = hash(bearer())
        return when (p["grant_type"]) {
            "authorization_code" -> {
                val code = codes[hash(p["code"] ?: "")] ?: return error("invalid_grant")
                val verifier = p["code_verifier"] ?: ""
                if (code.consent.origin != origin || code.consent.client != client ||
                    p["redirect_uri"] != CALLBACK || !VERIFIER.matches(verifier) ||
                    !same(code.consent.challenge, hash(verifier)) || !same(code.generation, current))
                    return error("invalid_grant")
                if (access.size >= LIMIT || refresh.size >= LIMIT) return error("temporarily_unavailable")
                codes.remove(hash(p.getValue("code")))
                issue(origin, client!!, current, clock() + REFRESH_MS)
            }
            "refresh_token" -> {
                val key = hash(p["refresh_token"] ?: "")
                val grant = refresh[key] ?: return error("invalid_grant")
                if (grant.origin != origin || grant.client != client || !same(grant.generation, current))
                    return error("invalid_grant")
                if (access.size >= LIMIT) return error("temporarily_unavailable")
                refresh.remove(key)
                issue(origin, grant.client, current, grant.consentExpires)
            }
            else -> error("unsupported_grant_type")
        }
    }

    private fun issue(origin: String, client: String, generation: String, consentExpires: Long): Response {
        val now = clock()
        val expires = minOf(now + ACCESS_MS, consentExpires)
        val accessToken = secret()
        val refreshToken = secret()
        access[hash(accessToken)] = Grant(origin, client, generation, expires, consentExpires)
        refresh[hash(refreshToken)] = Grant(origin, client, generation, consentExpires, consentExpires)
        return jsonResponse(buildJsonObject {
            put("access_token", accessToken)
            put("token_type", "Bearer")
            put("expires_in", (expires - now) / 1000)
            put("refresh_token", refreshToken)
            put("scope", SCOPE)
        })
    }

    private fun prune() {
        val now = clock()
        consents.entries.removeAll { it.value.expires <= now }
        codes.entries.removeAll { it.value.expires <= now }
        access.entries.removeAll { it.value.expires <= now }
        refresh.entries.removeAll { it.value.expires <= now }
    }

    private fun redirect(origin: String, vararg params: Pair<String, String>): Response =
        response(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "").apply {
            val query = (params.toList() + ("iss" to origin)).joinToString("&") {
                "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}"
            }
            addHeader("Location", "$CALLBACK?$query")
        }

    private fun error(code: String, status: Response.Status = Response.Status.BAD_REQUEST) =
        jsonResponse(buildJsonObject { put("error", code) }, status)

    private fun jsonResponse(body: JsonObject, status: Response.Status = Response.Status.OK) =
        response(status, "application/json", body.toString())

    private fun response(status: Response.Status, type: String, body: String) =
        NanoHTTPD.newFixedLengthResponse(status, type, body).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Pragma", "no-cache")
            addHeader("Referrer-Policy", "no-referrer")
            addHeader("X-Content-Type-Options", "nosniff")
        }

    private fun strings(vararg values: String) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
    private fun string(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
    private fun secret() = encode(ByteArray(32).also(random::nextBytes))
    private fun hash(value: String) = encode(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    private fun signature(value: String): String = encode(Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(registrationKey().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        doFinal(value.toByteArray(Charsets.UTF_8))
    })
    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private companion object {
        const val RESOURCE_METADATA = "/.well-known/oauth-protected-resource/mcp"
        const val RESOURCE_ROOT = "/.well-known/oauth-protected-resource"
        const val AUTH_METADATA = "/.well-known/oauth-authorization-server"
        const val AUTHORIZE = "/oauth/authorize"
        const val REGISTER = "/oauth/register"
        const val TOKEN = "/oauth/token"
        const val CALLBACK = "https://chatgpt.com/connector_platform_oauth_redirect"
        const val SCOPE = "mcp:control"
        const val SECRET_LENGTH = 43
        val SECRET = Regex("[A-Za-z0-9_-]{43}")
        val VERIFIER = Regex("[A-Za-z0-9._~-]{43,128}")
        val ROUTES = setOf(RESOURCE_METADATA, RESOURCE_ROOT, AUTH_METADATA, AUTHORIZE, REGISTER, TOKEN)
        const val MAX_BODY = 16_384L
        const val LIMIT = 128
        const val CONSENT_MS = 300_000L
        const val CODE_MS = 120_000L
        const val ACCESS_MS = 3_600_000L
        const val REFRESH_MS = 86_400_000L
    }
}
