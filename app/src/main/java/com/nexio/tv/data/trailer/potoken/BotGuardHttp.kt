package com.nexio.tv.data.trailer.potoken

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal suspend fun botGuardPost(
    url: String,
    data: List<String>,
    userAgent: String
): String = withContext(Dispatchers.IO) {
    val body = JSONArray(data).toString().toByteArray()
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 5_000
        readTimeout = 5_000
        doOutput = true
        setRequestProperty("User-Agent", userAgent)
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Content-Type", "application/json+protobuf")
        setRequestProperty("x-goog-api-key", BOTGUARD_GOOGLE_API_KEY)
        setRequestProperty("x-user-agent", "grpc-web-javascript/0.1")
    }
    try {
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        if (code != 200) {
            throw PoTokenException("BotGuard $url returned HTTP $code")
        }
        conn.inputStream.bufferedReader().use { it.readText() }
    } catch (e: IOException) {
        throw PoTokenException("BotGuard $url network error: ${e.message}")
    } finally {
        conn.disconnect()
    }
}

internal const val BOTGUARD_GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
internal const val BOTGUARD_REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
