package com.nexio.tv.core.image

import com.nexio.tv.core.integration.IntegrationProvider
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed interface IntegrationPosterRequest {
    val provider: IntegrationProvider
    val cacheKey: String
    val apiKey: String
    val ttlMs: Long
    val staleAfterExpiryMs: Long
    val mimeType: String?

    fun toModel(): String

    companion object {
        fun fromModel(model: String): IntegrationPosterRequest? =
            PosterIntegrationRequest.fromModel(model) ?: TopPostersThumbnailRequest.fromModel(model)
    }
}

data class PosterIntegrationRequest(
    override val provider: IntegrationProvider,
    override val cacheKey: String,
    override val apiKey: String,
    val path: String,
    val fallbackUrl: String? = null,
    override val ttlMs: Long = DEFAULT_TTL_MS,
    override val staleAfterExpiryMs: Long = DEFAULT_STALE_AFTER_EXPIRY_MS,
    override val mimeType: String? = null
) : IntegrationPosterRequest {
    override fun toModel(): String = buildString {
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

data class TopPostersThumbnailRequest(
    override val apiKey: String,
    val idType: String,
    val mediaId: String,
    val season: Int,
    val episode: Int,
    val credentialHash: String,
    val badgePosition: String = "top-right",
    val badgeSize: String = "small",
    val blur: Boolean = false,
    override val ttlMs: Long = DEFAULT_TTL_MS,
    override val staleAfterExpiryMs: Long = DEFAULT_STALE_AFTER_EXPIRY_MS,
    override val mimeType: String? = "image/jpeg"
) : IntegrationPosterRequest {
    init {
        require(idType.isNotBlank()) { "idType must not be blank" }
        require(mediaId.isNotBlank()) { "mediaId must not be blank" }
        require(season > 0) { "season must be positive" }
        require(episode > 0) { "episode must be positive" }
        require(credentialHash.isNotBlank()) { "credentialHash must not be blank" }
    }

    override val provider: IntegrationProvider = IntegrationProvider.TOP_POSTERS
    val episodePath: String = "S${season}E${episode}"
    override val cacheKey: String =
        "artwork-asset:TOP_POSTERS:thumbnail:$idType:$mediaId:$episodePath:badgeSize:$badgeSize:badgePos:$badgePosition:blur:$blur:credential:$credentialHash:imageLang:en:policy:1"

    override fun toModel(): String = buildString {
        append("integration-poster://fetch?")
        append("type=topposters-thumbnail")
        append("&apiKey=")
        append(encode(apiKey))
        append("&idType=")
        append(encode(idType))
        append("&mediaId=")
        append(encode(mediaId))
        append("&season=")
        append(season)
        append("&episode=")
        append(episode)
        append("&credentialHash=")
        append(encode(credentialHash))
        append("&badgePosition=")
        append(encode(badgePosition))
        append("&badgeSize=")
        append(encode(badgeSize))
        append("&blur=")
        append(blur)
        append("&ttlMs=")
        append(ttlMs)
        append("&staleAfterExpiryMs=")
        append(staleAfterExpiryMs)
        mimeType?.let {
            append("&mimeType=")
            append(encode(it))
        }
    }

    companion object {
        fun fromModel(model: String): TopPostersThumbnailRequest? {
            if (!model.startsWith("integration-poster://fetch?")) return null
            val params = parseQuery(model)
            if (params["type"] != "topposters-thumbnail") return null
            return TopPostersThumbnailRequest(
                apiKey = params["apiKey"] ?: return null,
                idType = params["idType"] ?: return null,
                mediaId = params["mediaId"] ?: return null,
                season = params["season"]?.toIntOrNull() ?: return null,
                episode = params["episode"]?.toIntOrNull() ?: return null,
                credentialHash = params["credentialHash"] ?: return null,
                badgePosition = params["badgePosition"] ?: "top-right",
                badgeSize = params["badgeSize"] ?: "small",
                blur = params["blur"]?.toBooleanStrictOrNull() ?: false,
                ttlMs = params["ttlMs"]?.toLongOrNull() ?: DEFAULT_TTL_MS,
                staleAfterExpiryMs = params["staleAfterExpiryMs"]?.toLongOrNull() ?: DEFAULT_STALE_AFTER_EXPIRY_MS,
                mimeType = params["mimeType"] ?: "image/jpeg"
            )
        }
    }
}

private const val DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L
private const val DEFAULT_STALE_AFTER_EXPIRY_MS = 7L * 24L * 60L * 60L * 1000L

private fun parseQuery(model: String): Map<String, String> {
    val query = model.substringAfter('?', "")
    return query.split('&')
        .mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx) to decode(part.substring(idx + 1))
        }
        .toMap()
}

private fun encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

private fun decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
