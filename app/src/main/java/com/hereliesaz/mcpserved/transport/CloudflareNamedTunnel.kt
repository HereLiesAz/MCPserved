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
                // Resolve the matching zone first: the first Cloudflare account may
                // own a completely different domain.
                val zone = findZone(apiToken, hostname)
                    ?: return@withContext Result.Failure(
                        "No Cloudflare zone matching $hostname with an identifiable owning account. " +
                            "Grant Zone:Read for the zone and use a hostname in that zone."
                    )
                val accountId = zone.accountId

                val tunnelName = "mcpserved-" + hostname.replace('.', '-')
                val tunnel = ensureTunnel(apiToken, accountId, tunnelName)
                    ?: return@withContext Result.Failure(
                        "Couldn't create or find the tunnel. Check the token has " +
                            "Account.Cloudflare Tunnel:Edit."
                    )

                putIngress(apiToken, accountId, tunnel.id, hostname, localPort)?.let {
                    return@withContext Result.Failure(it)
                }
                routeDns(apiToken, zone.id, hostname, tunnel.id)?.let {
                    return@withContext Result.Failure(it)
                }

                val runToken = tunnelToken(apiToken, accountId, tunnel.id)
                    ?: return@withContext Result.Failure("Tunnel created, but couldn't read its run token.")

                Result.Success(hostname, runToken)
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

    /**
     * Never rewrite an existing DNS name owned by something else. An A, MX,
     * TXT, or different CNAME is a conflict requiring the operator to act.
     */
    private fun routeDns(token: String, zoneId: String, hostname: String, tunnelId: String): String? {
        val target = "$tunnelId.cfargotunnel.com"
        val existing = authed("$API_BASE/zones/$zoneId/dns_records?name=$hostname&per_page=100", token).get().build()
        client.newCall(existing).execute().use { resp ->
            if (!resp.isSuccessful) return "$hostname: could not verify existing DNS records (HTTP ${resp.code})."
            val records = bodyOf(resp)?.get("result") as? JsonArray
                ?: return "$hostname: could not parse existing DNS records."
            if (records.size >= 100) return "$hostname: DNS record lookup is incomplete; refusing an overwrite."
            val exact = records.mapNotNull { it as? JsonObject }
                .filter { it["name"]?.jsonPrimitive?.content.equals(hostname, ignoreCase = true) }
            if (exact.size > 1) return "$hostname: multiple DNS records already exist; resolve them manually."
            if (exact.size == 1) {
                val record = exact.single()
                val type = record["type"]?.jsonPrimitive?.content
                val content = record["content"]?.jsonPrimitive?.content
                val proxied = record["proxied"]?.jsonPrimitive?.content == "true"
                return if (type == "CNAME" && content == target && proxied) null
                else "$hostname already has a $type record. Refusing to overwrite it; remove or update it explicitly in Cloudflare."
            }
        }
        val recordBody = buildJsonObject {
            put("type", "CNAME")
            put("name", hostname)
            put("content", target)
            put("proxied", true)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        client.newCall(authed("$API_BASE/zones/$zoneId/dns_records", token).post(recordBody).build())
            .execute().use { resp ->
                return if (resp.isSuccessful) null
                else "$hostname: Cloudflare refused to create the DNS route (HTTP ${resp.code}). Existing records were not modified."
            }
    }

    private fun tunnelToken(token: String, accountId: String, tunnelId: String): String? {
        val req = authed("$API_BASE/accounts/$accountId/cfd_tunnel/$tunnelId/token", token).get().build()
        client.newCall(req).execute().use { resp ->
            return bodyOf(resp)?.get("result")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        }
    }

    private data class Zone(val id: String, val accountId: String)

    /** Longest suffix match over every page, using that zone's owning account. */
    private fun findZone(token: String, hostname: String): Zone? {
        var best: Pair<String, Zone>? = null
        for (page in 1..MAX_ZONE_PAGES) {
            val req = authed("$API_BASE/zones?per_page=50&page=$page", token).get().build()
            val results = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Cloudflare zone lookup failed (HTTP ${resp.code})")
                bodyOf(resp)?.get("result") as? JsonArray
                    ?: throw IOException("Cloudflare returned an invalid zone list")
            }
            for (item in results) {
                val zone = item as? JsonObject ?: continue
                val name = zone["name"]?.jsonPrimitive?.content ?: continue
                val id = zone["id"]?.jsonPrimitive?.content ?: continue
                val accountId = (zone["account"] as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: continue
                if ((hostname.equals(name, ignoreCase = true) ||
                        hostname.endsWith(".$name", ignoreCase = true)) &&
                    (best == null || name.length > best.first.length)
                ) {
                    best = name to Zone(id, accountId)
                }
            }
            if (results.size < 50) return best?.second
        }
        throw IOException("Cloudflare zone list exceeded $MAX_ZONE_PAGES pages; refusing an incomplete lookup")
    }

    private companion object {
        const val API_BASE = "https://api.cloudflare.com/client/v4"
        const val MAX_ZONE_PAGES = 1000
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
