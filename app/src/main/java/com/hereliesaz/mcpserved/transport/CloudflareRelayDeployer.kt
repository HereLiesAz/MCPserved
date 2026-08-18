package com.hereliesaz.mcpserved.transport

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Deploys `relay/cloudflare/worker.js` (bundled as an asset, byte-identical
 * to the copy in the repo) straight to the operator's own Cloudflare account,
 * over Cloudflare's REST API — no `wrangler`, no Node, no terminal, nothing
 * beyond an API token the operator pastes in once. This is what makes
 * deploying a relay possible from the phone alone, matching every other path
 * on this screen.
 *
 * **Unverified against a live account.** This mirrors what `wrangler deploy`
 * does under the hood, built from Cloudflare's documented API shape, but it
 * has not been exercised against a real Cloudflare account — there was none
 * available to test against while writing it. If a call here fails in a way
 * that doesn't make sense, the fallback is still `wrangler deploy` from
 * `relay/cloudflare/` (see that directory's README), which is the
 * well-established path this same Worker code already works against.
 *
 * Every request needs only `Authorization: Bearer <token>` — no OAuth flow,
 * no browser round-trip, since a Cloudflare *API token* (as opposed to the
 * OAuth-based login `wrangler login` uses) is a bearer credential by design.
 */
class CloudflareRelayDeployer(private val appContext: Context) {

    sealed class Result {
        data class Success(val url: String) : Result()
        data class Failure(val message: String) : Result()
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Runs the whole deploy: find an account, make sure it has a workers.dev
     * subdomain, upload the script with its Durable Object binding, expose
     * it at that subdomain. Returns the resulting `https://` URL, ready to
     * drop straight into the Relay URL field.
     */
    suspend fun deploy(apiToken: String): Result = withContext(Dispatchers.IO) {
        if (apiToken.isBlank()) return@withContext Result.Failure("No API token.")
        runCatching {
            val accountId = findAccountId(apiToken)
                ?: return@withContext Result.Failure(
                    "Couldn't find a Cloudflare account for this token. Check it has " +
                        "Account.Workers Scripts:Edit permission."
                )
            val subdomain = ensureSubdomain(apiToken, accountId)
                ?: return@withContext Result.Failure(
                    "Couldn't read or register a workers.dev subdomain for this account. " +
                        "Pick one once at dash.cloudflare.com → Workers & Pages → your " +
                        "account → Workers.dev, then try again."
                )
            uploadScript(apiToken, accountId)?.let { return@withContext Result.Failure(it) }
            enableSubdomainRoute(apiToken, accountId)?.let { return@withContext Result.Failure(it) }
            Result.Success("https://$SCRIPT_NAME.$subdomain.workers.dev")
        }.getOrElse { e ->
            Result.Failure(
                when (e) {
                    is IOException -> "Network error reaching Cloudflare: ${e.message}"
                    else -> "Unexpected error: ${e.message}"
                }
            )
        }
    }

    private fun authed(url: String, token: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    private fun bodyOf(response: okhttp3.Response): JsonObject? =
        response.body?.string()?.let {
            runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
        }

    private fun findAccountId(token: String): String? {
        val req = authed("$API_BASE/accounts", token).get().build()
        client.newCall(req).execute().use { resp ->
            val body = bodyOf(resp) ?: return null
            val results = (body["result"] as? JsonArray) ?: return null
            return results.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
        }
    }

    /** Returns the account's workers.dev subdomain, registering one if none exists yet. */
    private fun ensureSubdomain(token: String, accountId: String): String? {
        val getReq = authed("$API_BASE/accounts/$accountId/workers/subdomain", token).get().build()
        client.newCall(getReq).execute().use { resp ->
            // Cloudflare answers "result": null, not an empty object, for an
            // account that has never registered a workers.dev subdomain —
            // exactly the first-deploy case this falls through to handle
            // below. `.jsonObject` throws on a JsonNull rather than treating
            // it as absent, so this has to be a safe cast, not that extension.
            val existing = (bodyOf(resp)?.get("result") as? JsonObject)
                ?.get("subdomain")?.jsonPrimitive?.content
            if (!existing.isNullOrBlank()) return existing
        }

        // None registered yet — this account has never deployed a Worker
        // before. Derive a candidate from the account id rather than
        // guessing a human-friendly name, since it only needs to be valid
        // and available, not memorable; the operator never has to type it.
        val candidate = "mcpserved-${accountId.take(12).lowercase()}"
        val putBody = buildJsonObject { put("subdomain", candidate) }
            .toString().toRequestBody(JSON_MEDIA_TYPE)
        val putReq = authed("$API_BASE/accounts/$accountId/workers/subdomain", token)
            .put(putBody).build()
        client.newCall(putReq).execute().use { resp ->
            if (!resp.isSuccessful) return null
        }
        return candidate
    }

    /** Uploads the Worker module + its Durable Object binding. Null on success, else an error string. */
    private fun uploadScript(token: String, accountId: String): String? {
        val source = appContext.assets.open(WORKER_ASSET).bufferedReader().use { it.readText() }

        val metadata = buildJsonObject {
            put("main_module", "worker.js")
            put("compatibility_date", "2024-09-01")
            put("bindings", buildJsonArray {
                add(buildJsonObject {
                    put("type", "durable_object_namespace")
                    put("name", "RELAY_ROOM")
                    put("class_name", "RelayRoom")
                })
            })
            put("migrations", buildJsonObject {
                put("new_tag", "v1")
                put("new_classes", buildJsonArray { add(JsonPrimitive("RelayRoom")) })
            })
        }

        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", null, metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addFormDataPart("worker.js", "worker.js", source.toRequestBody(JS_MODULE_MEDIA_TYPE))
            .build()

        val req = authed("$API_BASE/accounts/$accountId/workers/scripts/$SCRIPT_NAME", token)
            .put(multipart).build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) return null
            return "Cloudflare rejected the script upload (HTTP ${resp.code}): ${resp.body?.string().orEmpty().take(300)}"
        }
    }

    private fun enableSubdomainRoute(token: String, accountId: String): String? {
        val body = buildJsonObject {
            put("enabled", true)
            put("previews_enabled", false)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val req = authed(
            "$API_BASE/accounts/$accountId/workers/scripts/$SCRIPT_NAME/subdomain",
            token,
        ).post(body).build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) return null
            return "Cloudflare rejected enabling the workers.dev route (HTTP ${resp.code})."
        }
    }

    private companion object {
        const val API_BASE = "https://api.cloudflare.com/client/v4"
        const val SCRIPT_NAME = "mcpserved-relay"
        const val WORKER_ASSET = "cloudflare_relay_worker.js"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val JS_MODULE_MEDIA_TYPE = "application/javascript+module".toMediaType()
    }
}
