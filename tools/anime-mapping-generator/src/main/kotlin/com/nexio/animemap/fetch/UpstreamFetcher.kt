package com.nexio.animemap.fetch

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.format.DateTimeFormatter

data class FetchResult(
    val url: String,
    val commit: String?,
    val fetchedAt: String,
    val payload: ByteArray
)

class UpstreamFetcher(private val userAgent: String = "Nexio anime-mapping-generator") {

    fun fetchSource(
        rawUrl: String,
        commitsApiUrl: String,
        cacheFile: File
    ): FetchResult {
        val payload = fetchBytes(rawUrl)
        cacheFile.parentFile.mkdirs()
        cacheFile.writeBytes(payload)
        val commit = fetchCommitSha(commitsApiUrl)
        return FetchResult(
            url = rawUrl,
            commit = commit,
            fetchedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            payload = payload
        )
    }

    fun useCache(rawUrl: String, cacheFile: File): FetchResult? {
        if (!cacheFile.exists()) return null
        return FetchResult(
            url = rawUrl,
            commit = null,
            fetchedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(cacheFile.lastModified())),
            payload = cacheFile.readBytes()
        )
    }

    private fun fetchBytes(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", userAgent)
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        try {
            check(connection.responseCode in 200..299) {
                "fetch failed for $url with HTTP ${connection.responseCode}"
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchCommitSha(commitsApiUrl: String): String? {
        return try {
            val bytes = fetchBytes(commitsApiUrl)
            val moshi = Moshi.Builder().build()
            val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            @Suppress("UNCHECKED_CAST")
            val parsed = moshi.adapter<Map<String, Any?>>(type).fromJson(String(bytes)) ?: return null
            parsed["sha"] as? String
        } catch (e: Exception) {
            null
        }
    }
}
