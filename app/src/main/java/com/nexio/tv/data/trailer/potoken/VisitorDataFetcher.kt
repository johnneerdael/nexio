package com.nexio.tv.data.trailer.potoken

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal suspend fun fetchVisitorData(
    webClientName: String,
    webClientId: String,
    webClientVersion: String,
    webClientUserAgent: String,
    webClientScreen: String? = null,
    embedUrl: String? = null
): String = withContext(Dispatchers.IO) {
    val body = JSONObject().apply {
        put(
            "context",
            JSONObject().apply {
                put(
                    "client",
                    JSONObject().apply {
                        put("clientName", webClientName)
                        put("clientVersion", webClientVersion)
                        if (!webClientScreen.isNullOrBlank()) {
                            put("clientScreen", webClientScreen)
                        }
                        put("platform", "DESKTOP")
                        put("hl", "en")
                        put("gl", "US")
                        put("utcOffsetMinutes", 0)
                    }
                )
                if (!embedUrl.isNullOrBlank()) {
                    put("thirdParty", JSONObject().put("embedUrl", embedUrl))
                }
                put(
                    "request",
                    JSONObject().apply {
                        put("internalExperimentFlags", org.json.JSONArray())
                        put("useSsl", true)
                    }
                )
                put("user", JSONObject().put("lockedSafetyMode", false))
            }
        )
    }.toString().toByteArray()

    val conn = (URL("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
        .openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 5_000
        readTimeout = 5_000
        doOutput = true
        setRequestProperty("User-Agent", webClientUserAgent)
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Origin", "https://www.youtube.com")
        setRequestProperty("Referer", "https://www.youtube.com/")
        setRequestProperty("X-YouTube-Client-Name", webClientId)
        setRequestProperty("X-YouTube-Client-Version", webClientVersion)
    }
    try {
        conn.outputStream.use { it.write(body) }
        if (conn.responseCode != 200) {
            throw PoTokenException("visitor_id endpoint returned HTTP ${conn.responseCode}")
        }
        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val responseContext = JSONObject(responseText).optJSONObject("responseContext")
            ?: throw PoTokenException("visitor_id response missing responseContext")
        responseContext.optString("visitorData").takeIf { it.isNotBlank() }
            ?: throw PoTokenException("visitor_id response missing visitorData")
    } catch (e: IOException) {
        throw PoTokenException("visitor_id network error: ${e.message}")
    } finally {
        conn.disconnect()
    }
}
