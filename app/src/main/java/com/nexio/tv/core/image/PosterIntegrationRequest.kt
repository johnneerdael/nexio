package com.nexio.tv.core.image

import com.nexio.tv.core.integration.IntegrationProvider
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class PosterIntegrationRequest(
    val provider: IntegrationProvider,
    val cacheKey: String,
    val apiKey: String,
    val path: String,
    val fallbackUrl: String? = null,
    val ttlMs: Long = DEFAULT_TTL_MS,
    val staleAfterExpiryMs: Long = DEFAULT_STALE_AFTER_EXPIRY_MS,
    val mimeType: String? = null
) {
    fun toModel(): String = buildString {
        append("integration-poster://fetch?")
        append("provider=")
        append(provider.name)
        append("&cacheKey=")
        append(encode(cacheKey))
        append("&apiKey=")
        append(encode(apiKey))
        append("&path=")
        append(encode(path))
        fallbackUrl?.let {
            append("&fallbackUrl=")
            append(encode(it))
        }
        append("&ttlMs=")
        append(ttlMs)
        append("&staleAfterExpiryMs=")
        append(staleAfterExpiryMs)
        mimeType?.let {
            append("&mimeType=")
            append(encode(it))
        }
    }

    fun withFallbackUrlIfAbsent(fallbackUrl: String?): PosterIntegrationRequest {
        val fallback = fallbackUrl?.takeIf { it.isNotBlank() } ?: return this
        if (!this.fallbackUrl.isNullOrBlank()) return this
        return copy(fallbackUrl = fallback)
    }

    companion object {
        fun fromModel(model: String): PosterIntegrationRequest? {
            if (!model.startsWith("integration-poster://fetch?")) return null
            val query = model.substringAfter('?', "")
            val params = query.split('&')
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) null else part.substring(0, idx) to decode(part.substring(idx + 1))
                }
                .toMap()
            val provider = params["provider"]?.let(IntegrationProvider::valueOf) ?: return null
            val cacheKey = params["cacheKey"] ?: return null
            val apiKey = params["apiKey"] ?: return null
            val path = params["path"] ?: return null
            val ttlMs = params["ttlMs"]?.toLongOrNull() ?: DEFAULT_TTL_MS
            val staleAfterExpiryMs = params["staleAfterExpiryMs"]?.toLongOrNull() ?: DEFAULT_STALE_AFTER_EXPIRY_MS
            return PosterIntegrationRequest(
                provider = provider,
                cacheKey = cacheKey,
                apiKey = apiKey,
                path = path,
                fallbackUrl = params["fallbackUrl"],
                ttlMs = ttlMs,
                staleAfterExpiryMs = staleAfterExpiryMs,
                mimeType = params["mimeType"]
            )
        }

        private const val DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L
        private const val DEFAULT_STALE_AFTER_EXPIRY_MS = 7L * 24L * 60L * 60L * 1000L

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())

        private fun decode(value: String): String =
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}
