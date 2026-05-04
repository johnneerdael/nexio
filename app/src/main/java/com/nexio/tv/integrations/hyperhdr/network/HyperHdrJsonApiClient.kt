package com.nexio.tv.integrations.hyperhdr.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal HyperHDR JSON-RPC client. Two operations:
 *   - [serverInfo] — used by the Settings "Test connection" button to verify reachability
 *     and surface the server's hostname + active LED instance to the user.
 *   - [setHdrVideoMode] — called once per Nexio playback session by the lifecycle wiring
 *     to tell HyperHDR what kind of frames are arriving on the FlatBuffer port.
 *
 * No subscribe/event support, no instance switching. Optional Bearer token —
 * when configured, every request body includes a "token" field. POSTs to
 * http://host:port/json-rpc. Ports a strict subset of HyperHDR-android's
 * eu.hyperhdr.android.json.HyperHdrJsonApiClient.
 */
class HyperHdrJsonApiClient(
    private val host: String,
    private val port: Int,
    private val token: String? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build(),
) {
    private val tanCounter = AtomicInteger(1)
    private val jsonMedia = "application/json".toMediaType()
    private fun url(): String = "http://$host:$port/json-rpc"

    suspend fun serverInfo(): ServerInfo = withContext(Dispatchers.IO) {
        val req = JSONObject().apply {
            put("command", "serverinfo")
            put("tan", tanCounter.getAndIncrement())
            token?.let { put("token", it) }
        }
        val body = post(req)
        if (!body.optBoolean("success", false)) {
            throw JsonApiError(
                "serverinfo failed: ${body.optString("error", "unknown")}",
                httpCode = 200,
            )
        }
        val info = body.optJSONObject("info") ?: throw JsonApiError("serverinfo missing 'info'")
        val hostname = info.optString("hostname", "")
        val instances = info.optJSONArray("instance")
        val firstInstance = (0 until (instances?.length() ?: 0))
            .asSequence()
            .map { instances!!.getJSONObject(it) }
            .firstOrNull { it.optBoolean("running", false) }
            ?: instances?.optJSONObject(0)
        ServerInfo(
            hostname = hostname,
            instanceId = firstInstance?.optInt("instance", 0) ?: 0,
            instanceName = firstInstance?.optString("friendly_name")?.takeIf { it.isNotEmpty() },
        )
    }

    suspend fun setHdrVideoMode(hdr: Boolean) = withContext(Dispatchers.IO) {
        val req = JSONObject().apply {
            put("command", "videomode")
            put("HDR", if (hdr) 1 else 0)
            put("tan", tanCounter.getAndIncrement())
            token?.let { put("token", it) }
        }
        val body = post(req)
        if (!body.optBoolean("success", false)) {
            throw JsonApiError(
                "videomode failed: ${body.optString("error", "unknown")}",
                httpCode = 200,
            )
        }
    }

    private fun post(json: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url())
            .post(json.toString().toRequestBody(jsonMedia))
            .build()
        httpClient.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw JsonApiError("HTTP ${resp.code}: $raw", httpCode = resp.code)
            }
            return runCatching { JSONObject(raw) }.getOrElse {
                throw JsonApiError("non-JSON response: $raw", httpCode = resp.code)
            }
        }
    }
}
