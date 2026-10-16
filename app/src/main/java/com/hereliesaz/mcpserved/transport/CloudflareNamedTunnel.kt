package com.hereliesaz.mcpserved.transport

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Provisions a **named** Cloudflare Tunnel from the phone to its own
 * [McpServer], over Cloudflare's REST API — no `cloudflared login`, no
 * terminal, just the same API token the relay deploy uses. Unlike the
 * session-scoped Quick Tunnel ([CloudflareTunnel.start]), a named tunnel has a
 * **stable hostname** ([hostname], e.g. `mcp.example.com`): the public URL
 * stays the same across sessions, so a cloud host can register it once as a
 * remote MCP connector and keep it.
 *
 * What it sets up, idempotently:
 *  1. a Cloudflare-managed tunnel (`config_src: cloudflare`), creating one or
 *     reusing the same-named one from a previous run;
 *  2. the tunnel's ingress — [hostname] → `http://127.0.0.1:<port>` (McpServer),
 *     with a catch-all 404;
 *  3. a proxied `CNAME` from [hostname] to `<tunnel-id>.cfargotunnel.com`.
 *
 * It returns the run token the phone feeds to `cloudflared tunnel run`
 * ([CloudflareTunnel.startNamed]).
 *
 * **Unverified against a live account**, same caveat as
 * [CloudflareRelayDeployer]: built to Cloudflare's documented API shape but not
 * exercised against a real account here. On failure the fallback is the
 * Cloudflare Zero Trust dashboard (Networks → Tunnels), which does the same by
 * hand.
 *
 * **Prerequisite:** [hostname] must be free to become a tunnel CNAME — if it is
 * still attached to a Worker as a custom domain (e.g. the relay from
 * `relay/cloudflare/wrangler.toml`), detach it there first, or the CNAME step
 * is reported as a note.
 */
class CloudflareNamedTunnel(private val appContext: Context) {

    sealed class Result {
        /** [runToken] feeds `cloudflared tunnel run --token`; [notes] flags any step that needs attention. */
        data class Success(val hostname: String, val runToken: String, val notes: List<String> = emptyList()) : Result()
        data class Failure(val message: String) : Result()
    }

    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun provision(apiToken: String, hostname: String, localPort: Int = McpServer.DEFAULT_HTTP_PORT): Result =
        withContext(Dispatchers.IO) {
            if (apiToken.isBlank()) return@withContext Result.Failure("No API token.")
            if (hostname.isBlank()) return@withContext Result.Failure("No hostname.")
            runCatching {
                val notes = mutableListOf<String>()
                val accountId = findAccountId(apiToken)
                    ?: return@withContext Result.Failure(
                        "Couldn't find a Cloudflare account for this token. It needs " +
                            "Account.Cloudflare Tunnel:Edit, Zone.DNS:Edit and Zone:Read."
                    )

                val tunnelName = "mcpserved-" + hostname.replace('.', '-')
                val tunnel = ensureTunnel(apiToken, accountId, tunnelName)
                    ?: return@withContext Result.Failure(
                        "Couldn't create or find the tunnel. Check the token has " +
                            "Account.Cloudflare Tunnel:Edit."
                    )

                putIngress(apiToken, accountId, tunnel.id, hostname, localPort)?.let { notes += it }

                val zoneId = findZoneId(apiToken, hostname)
                if (zoneId == null) {
                    notes += "$hostname: no matching zone on this account (add the zone, or grant Zone:Read), " +
                        "so the DNS route wasn't created — add a proxied CNAME to ${tunnel.id}.cfargotunnel.com by hand."
                } else {
                    routeDns(apiToken, zoneId, hostname, tunnel.id)?.let { notes += it }
                }

                val runToken = tunnelToken(apiToken, accountId, tunnel.id)
                    ?: return@withContext Result.Failure("Tunnel created, but couldn't read its run token.")

                Result.Success(hostname, runToken, notes)
            }.getOrElse { e ->
                Result.Failure(
                    when (e) {
                        is IOException -> "Network error reaching Cloudflare: ${e.message}"
                        else -> "Unexpected error: ${e.message}"
                    }
                )
            }
        }

    private data class Tunnel(val id: String)

    private fun authed(url: String, token: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    private fun bodyOf(response: okhttp3.Response): JsonObject? =
        response.body?.string()?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun findAccountId(token: String): String? {
        val req = authed("$API_BASE/accounts", token).get().build()
        client.newCall(req).execute().use { resp ->
            val results = (bodyOf(resp)?.get("result") as? JsonArray) ?: return null
            return results.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
        }
    }

    /** Creates the Cloudflare-managed tunnel, or reuses the existing same-named one. */
    private fun ensureTunnel(token: String, accountId: String, name: String): Tunnel? {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val createBody = buildJsonObject {
            put("name", name)
            put("config_src", "cloudflare")
            put("tunnel_secret", android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP))
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        client.newCall(authed("$API_BASE/accounts/$accountId/cfd_tunnel", token).post(createBody).build())
            .execute().use { resp ->
                bodyOf(resp)?.get("result")?.let { it as? JsonObject }
                    ?.get("id")?.jsonPrimitive?.content?.let { return Tunnel(it) }
            }
        // Create failed — most likely a tunnel with this name already exists. Reuse it.
        val listReq = authed("$API_BASE/accounts/$accountId/cfd_tunnel?name=$name&is_deleted=false", token).get().build()
        client.newCall(listReq).execute().use { resp ->
            val results = (bodyOf(resp)?.get("result") as? JsonArray) ?: return null
            return results.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content?.let { Tunnel(it) }
        }
    }

    /** Points the tunnel's ingress at the local McpServer. Null on success, else a note. */
    private fun putIngress(token: String, accountId: String, tunnelId: String, hostname: String, localPort: Int): String? {
        val body = buildJsonObject {
            put("config", buildJsonObject {
                put("ingress", buildJsonArray {
                    add(buildJsonObject {
                        put("hostname", hostname)
                        put("service", "http://127.0.0.1:$localPort")
                    })
                    add(buildJsonObject { put("service", "http_status:404") })
                })
            })
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val req = authed("$API_BASE/accounts/$accountId/cfd_tunnel/$tunnelId/configurations", token).put(body).build()
        client.newCall(req).execute().use { resp ->
            return if (resp.isSuccessful) null
            else "ingress: Cloudflare rejected the tunnel config (HTTP ${resp.code})"
        }
    }

    /** Creates (or updates) the proxied CNAME to the tunnel. Null on success, else a note. */
    private fun routeDns(token: String, zoneId: String, hostname: String, tunnelId: String): String? {
        val target = "$tunnelId.cfargotunnel.com"
        val recordBody = buildJsonObject {
            put("type", "CNAME")
            put("name", hostname)
            put("content", target)
            put("proxied", true)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        client.newCall(authed("$API_BASE/zones/$zoneId/dns_records", token).post(recordBody).build())
            .execute().use { resp -> if (resp.isSuccessful) return null }
        // Create failed — a record for this name probably exists (e.g. a Worker
        // custom domain). Repoint it only if it is a CNAME: overwriting an A, MX or
        // TXT record with a CNAME would silently destroy unrelated DNS.
        val existing = authed("$API_BASE/zones/$zoneId/dns_records?name=$hostname", token).get().build()
        val records = client.newCall(existing).execute().use { resp ->
            (bodyOf(resp)?.get("result") as? JsonArray)?.mapNotNull { it as? JsonObject }
        } ?: return "$hostname: couldn't create the DNS route and couldn't list the existing records."
        val cname = records.firstOrNull { it["type"]?.jsonPrimitive?.content == "CNAME" }
        val recordId = cname?.get("id")?.jsonPrimitive?.content
            ?: return if (records.isEmpty()) {
                "$hostname: couldn't create the DNS route and found no existing record to update."
            } else {
                "$hostname: a non-CNAME DNS record already exists for this name; it was left untouched — " +
                    "remove it or pick another hostname."
            }
        val putReq = authed("$API_BASE/zones/$zoneId/dns_records/$recordId", token).put(recordBody).build()
        client.newCall(putReq).execute().use { resp ->
            return if (resp.isSuccessful) null
            else "$hostname: an existing DNS record is in the way and couldn't be repointed (HTTP ${resp.code}) — " +
                "if it's a Worker custom domain, detach it there first."
        }
    }

    private fun tunnelToken(token: String, accountId: String, tunnelId: String): String? {
        val req = authed("$API_BASE/accounts/$accountId/cfd_tunnel/$tunnelId/token", token).get().build()
        client.newCall(req).execute().use { resp ->
            return bodyOf(resp)?.get("result")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * The owning zone's id for [hostname] — the longest zone name the hostname ends
     * with. Looked up by exact name, longest candidate first, so it works on
     * accounts with more zones than one list page returns.
     */
    private fun findZoneId(token: String, hostname: String): String? {
        val labels = hostname.split('.')
        for (i in 0 until labels.size - 1) {
            val candidate = labels.drop(i).joinToString(".")
            val req = authed("$API_BASE/zones?name=$candidate", token).get().build()
            val id = client.newCall(req).execute().use { resp ->
                (bodyOf(resp)?.get("result") as? JsonArray)?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { it["name"]?.jsonPrimitive?.content == candidate }
                    ?.get("id")?.jsonPrimitive?.content
            }
            if (id != null) return id
        }
        return null
    }

    private companion object {
        const val API_BASE = "https://api.cloudflare.com/client/v4"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
