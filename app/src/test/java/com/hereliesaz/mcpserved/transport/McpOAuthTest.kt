package com.hereliesaz.mcpserved.transport

import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

/**
 * Exercise NanoHTTPD's actual request parsing, status codes, headers and response
 * bodies over a loopback socket. No Android crypto/storage mocks and no live
 * public tunnel. The separate fixture credential and RFC 7636 test vector are
 * independent of the authorization implementation.
 */
class McpOAuthTest {
    private var credential = "fixture-device-bearer-not-an-oauth-token"
    private var now = 1000L
    private lateinit var oauth: McpOAuth
    private lateinit var server: NanoHTTPD
    private val http = OkHttpClient.Builder().followRedirects(false).build()

    @Before
    fun start() {
        oauth = McpOAuth({ credential }, { "fixture-registration-signing-key" }, { now })
        oauth.enableOrigin(ORIGIN)
        oauth.enableOrigin(OTHER)
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                oauth.serve(session)?.let { return it }
                val presented = session.headers["authorization"]?.removePrefix("Bearer ")
                return if (oauth.accepts(session, presented)) {
                    newFixedLengthResponse(Response.Status.OK, "text/plain", "protected")
                } else {
                    newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "").apply {
                        addHeader("WWW-Authenticate", oauth.challenge(session))
                    }
                }
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
    }

    @After
    fun stop() { server.stop(); http.connectionPool.evictAll() }

    private data class Reply(val status: Int, val body: String, val location: String?, val challenge: String?, val cache: String?) {
        fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject
        fun value(key: String) = json().getValue(key).jsonPrimitive.content
        fun redirect(key: String): String? = location?.substringAfter('?')?.split('&')
            ?.map { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            ?.firstOrNull { it.first == key }?.second
    }

    private fun request(
        path: String, origin: String = ORIGIN, body: RequestBody? = null,
        bearer: String? = null, browserOrigin: String? = null,
    ): Reply {
        val builder = Request.Builder().url("http://127.0.0.1:${server.listeningPort}$path")
            .header("Host", origin.removePrefix("https://"))
        if (body != null) builder.post(body)
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        if (browserOrigin != null) builder.header("Origin", browserOrigin)
        return http.newCall(builder.build()).execute().use {
            Reply(it.code, it.body?.string().orEmpty(), it.header("Location"),
                it.header("WWW-Authenticate"), it.header("Cache-Control"))
        }
    }

    private fun form(vararg pairs: Pair<String, String>) =
        FormBody.Builder().apply { pairs.forEach { add(it.first, it.second) } }.build()

    private fun register(origin: String = ORIGIN, callback: String = CALLBACK): Reply =
        request("/oauth/register", origin,
            """{"redirect_uris":["$callback"],"token_endpoint_auth_method":"none","grant_types":["authorization_code","refresh_token"]}"""
                .toRequestBody("application/json".toMediaType()))

    private fun client(): String = register().also { assertEquals(201, it.status) }.value("client_id")

    private fun begin(client: String, origin: String = ORIGIN, resource: String = "$origin/mcp",
                      challenge: String = CHALLENGE, callback: String = CALLBACK): Reply {
        val query = listOf("response_type" to "code", "client_id" to client, "redirect_uri" to callback,
            "resource" to resource, "state" to "state/with+symbols", "scope" to "mcp:control",
            "code_challenge" to challenge, "code_challenge_method" to "S256")
            .joinToString("&") { "${it.first}=${java.net.URLEncoder.encode(it.second, "UTF-8")}" }
        return request("/oauth/authorize?$query", origin)
    }

    private fun nonce(reply: Reply): String {
        assertEquals("Authorization response: ${reply.body}", 200, reply.status)
        assertEquals("no-store", reply.cache)
        assertFalse(reply.body.contains(credential))
        return Regex("name=\"request\" value=\"([^\"]+)\"").find(reply.body)!!.groupValues[1]
    }

    private fun approve(nonce: String, origin: String = ORIGIN, secret: String = credential,
                        browserOrigin: String = origin, decision: String = "approve"): Reply =
        request("/oauth/authorize", origin, form("request" to nonce,
            "decision" to decision, "device_token" to secret), browserOrigin = browserOrigin)

    private fun code(client: String): String {
        val approved = approve(nonce(begin(client)))
        assertEquals("Consent response: ${approved.body}", 303, approved.status)
        assertTrue(approved.location!!.startsWith("$CALLBACK?"))
        assertEquals(ORIGIN, approved.redirect("iss"))
        assertEquals("state/with+symbols", approved.redirect("state"))
        return approved.redirect("code")!!
    }

    private fun redeem(client: String, code: String, origin: String = ORIGIN,
                       verifier: String = VERIFIER, resource: String = "$origin/mcp",
                       callback: String = CALLBACK): Reply =
        request("/oauth/token", origin, form("grant_type" to "authorization_code",
            "client_id" to client, "code" to code, "redirect_uri" to callback,
            "code_verifier" to verifier, "resource" to resource))

    private fun renew(client: String, refresh: String, origin: String = ORIGIN): Reply =
        request("/oauth/token", origin, form("grant_type" to "refresh_token",
            "client_id" to client, "refresh_token" to refresh, "resource" to "$origin/mcp"))

    @Test
    fun discoveryOnlyForActiveConfiguredOrigins() {
        val challenge = request("/mcp")
        assertEquals(401, challenge.status)
        assertEquals("Bearer resource_metadata=\"$ORIGIN/.well-known/oauth-protected-resource/mcp\", scope=\"mcp:control\"", challenge.challenge)
        val resource = request("/.well-known/oauth-protected-resource/mcp").json()
        assertEquals("$ORIGIN/mcp", resource.getValue("resource").jsonPrimitive.content)
        assertEquals("""["$ORIGIN"]""", resource.getValue("authorization_servers").toString())
        val metadata = request("/.well-known/oauth-authorization-server").json()
        assertEquals(ORIGIN, metadata.getValue("issuer").jsonPrimitive.content)
        assertEquals("""["S256"]""", metadata.getValue("code_challenge_methods_supported").toString())
        assertEquals("true", metadata.getValue("authorization_response_iss_parameter_supported").toString())
        assertEquals(404, request("/.well-known/oauth-authorization-server", "https://attacker.example").status)
        assertEquals("Bearer", request("/mcp", "https://attacker.example").challenge)
    }

    @Test
    fun registrationAndConsentDoNotAllowArbitraryRedirectOrCredentiallessApproval() {
        assertEquals(400, register(callback = "https://attacker.example/redirect").status)
        val client = client()
        assertEquals(400, begin(client, callback = "$CALLBACK/extra").status)
        assertEquals(400, begin("forged-client").status)
        assertEquals("invalid_request", begin(client, resource = "$OTHER/mcp").redirect("error"))
        val csrfNonce = nonce(begin(client))
        assertEquals(400, approve(csrfNonce, browserOrigin = OTHER).status)
        val denied = approve(csrfNonce, secret = "wrong")
        assertEquals("access_denied", denied.redirect("error"))
        assertNull(denied.redirect("code"))
        assertEquals(ORIGIN, denied.redirect("iss"))
        assertEquals(400, approve(csrfNonce).status)
        val explicitDeny = approve(nonce(begin(client)), decision = "deny")
        assertEquals("access_denied", explicitDeny.redirect("error"))
    }

    @Test
    fun codeExchangeRequiresExactClientResourceCallbackAndRfc7636Verifier() {
        val client = client()
        val otherClient = client()
        val code = code(client)
        assertEquals(400, redeem(otherClient, code).status)
        assertEquals(400, redeem(client, code, origin = OTHER).status)
        assertEquals(400, redeem(client, code, verifier = "x".repeat(43)).status)
        assertEquals(400, redeem(client, code, callback = "https://attacker.example").status)
        val issued = redeem(client, code)
        assertEquals(200, issued.status)
        assertEquals("no-store", issued.cache)
        assertEquals("3600", issued.value("expires_in"))
        val access = issued.value("access_token")
        assertNotEquals(credential, access)
        assertEquals(200, request("/mcp", bearer = access).status)
        assertEquals(401, request("/mcp", OTHER, bearer = access).status)
        assertEquals(401, request("/wrong", bearer = access).status)
        assertEquals(401, request("/mcp", bearer = issued.value("refresh_token")).status)
        assertEquals(400, redeem(client, code).status)
    }

    @Test
    fun rotationExpiryAndTunnelShutdownRevokeGrantsWithoutDestroyingRegistration() {
        val client = client()
        val initial = redeem(client, code(client))
        now += 3_600_001L
        assertEquals(401, request("/mcp", bearer = initial.value("access_token")).status)
        val refreshed = renew(client, initial.value("refresh_token"))
        assertEquals(200, refreshed.status)
        assertNotEquals(initial.value("refresh_token"), refreshed.value("refresh_token"))
        assertEquals(400, renew(client, refreshed.value("refresh_token"), OTHER).status)
        assertEquals(200, request("/mcp", bearer = refreshed.value("access_token")).status)
        assertEquals(400, renew(client, initial.value("refresh_token")).status)
        assertEquals(401, request("/mcp", bearer = refreshed.value("access_token")).status)
        assertEquals(400, renew(client, refreshed.value("refresh_token")).status)
        val beforeRotation = redeem(client, code(client))
        assertEquals(200, beforeRotation.status)
        credential = "rotated-device-bearer"
        assertEquals(401, request("/mcp", bearer = beforeRotation.value("access_token")).status)
        assertEquals(400, renew(client, beforeRotation.value("refresh_token")).status)
        val fresh = redeem(client, code(client))
        assertEquals(200, fresh.status)
        oauth.disableOrigin(ORIGIN)
        assertEquals(401, request("/mcp", bearer = fresh.value("access_token")).status)
        assertEquals(404, request("/.well-known/oauth-authorization-server").status)
        assertEquals(200, request("/.well-known/oauth-authorization-server", OTHER).status)
        oauth.enableOrigin(ORIGIN)
        assertEquals(401, request("/mcp", bearer = fresh.value("access_token")).status)
        assertEquals(200, begin(client).status)
        oauth.clear()
        assertEquals(404, request("/.well-known/oauth-authorization-server", OTHER).status)
    }

    @Test
    fun pendingConsentAndCodesExpireAndRegistrationSurvivesServiceRestart() {
        val client = client()
        val pending = nonce(begin(client))
        now += 300_001L
        assertEquals(400, approve(pending).status)
        val expiredCode = code(client)
        now += 120_001L
        assertEquals(400, redeem(client, expiredCode).status)
        val old = redeem(client, code(client))
        oauth.clear()
        oauth = McpOAuth({ credential }, { "fixture-registration-signing-key" }, { now })
        oauth.enableOrigin(ORIGIN)
        assertEquals(401, request("/mcp", bearer = old.value("access_token")).status)
        assertEquals(200, redeem(client, code(client)).status)
    }

    @Test
    fun postAfterAuthorizationGetOnSameConnectionUsesOnlyCurrentRequestParameters() {
        val client = client()
        val pending = nonce(begin(client))
        val approved = approve(pending)
        assertEquals("Consent POST after query-bearing GET", 303, approved.status)
        assertEquals(200, redeem(client, approved.redirect("code")!!).status)
        // URL parameters must not be merged with security-critical form fields.
        val next = nonce(begin(client))
        val rejected = request("/oauth/authorize?decision=deny", body = form(
            "request" to next, "decision" to "approve", "device_token" to credential
        ), browserOrigin = ORIGIN)
        assertEquals(400, rejected.status)
        assertEquals("invalid_request", rejected.value("error"))
        assertEquals(303, approve(next).status)
    }

    @Test
    fun refreshCannotExtendConsentPastTwentyFourHours() {
        val client = client()
        val issued = redeem(client, code(client))
        now += 86_399_000L
        val last = renew(client, issued.value("refresh_token"))
        assertEquals(200, last.status)
        assertEquals("1", last.value("expires_in"))
        now += 1000
        assertEquals(401, request("/mcp", bearer = last.value("access_token")).status)
        assertEquals(400, renew(client, last.value("refresh_token")).status)
    }

    private companion object {
        const val ORIGIN = "https://phone.example"
        const val OTHER = "https://other.example"
        const val CALLBACK = "https://chatgpt.com/connector_platform_oauth_redirect"
        // RFC 7636 Appendix B; expected challenge is published, not generated by test code.
        const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        const val CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    }
}
