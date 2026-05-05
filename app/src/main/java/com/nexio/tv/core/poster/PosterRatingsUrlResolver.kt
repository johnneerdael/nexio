package com.nexio.tv.core.poster

import com.nexio.tv.core.image.PosterIntegrationRequest
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterRatingsProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PosterRatingsUrlResolver @Inject constructor(
    private val settingsDataStore: PosterRatingsSettingsDataStore
) {
    data class ActiveProvider(
        val provider: PosterRatingsProvider,
        val apiKey: String
    )

    suspend fun getActiveProvider(): ActiveProvider? {
        val settings = settingsDataStore.settings.first()
        return resolveProvider(settings)
    }

    fun apply(meta: Meta, activeProvider: ActiveProvider?): Meta {
        if (activeProvider == null) return meta
        val providerTag = activeProvider.provider.name.lowercase()
        return meta.copy(
            poster = resolvePosterUrl(
                originalPosterUrl = meta.poster,
                contentId = meta.id,
                contentType = meta.type,
                activeProvider = activeProvider
            ),
            posterProviderTag = providerTag
        )
    }

    fun apply(metaPreview: MetaPreview, activeProvider: ActiveProvider?): MetaPreview {
        if (activeProvider == null) return metaPreview
        val providerTag = activeProvider.provider.name.lowercase()
        return metaPreview.copy(
            poster = resolvePosterUrl(
                originalPosterUrl = metaPreview.poster,
                contentId = metaPreview.id,
                contentType = metaPreview.type,
                activeProvider = activeProvider
            ),
            posterProviderTag = providerTag
        )
    }

    private fun resolveProvider(settings: ArtworkProviderSettings): ActiveProvider? {
        return when (settings.selection.posterProvider) {
            ArtworkProviderChoiceKey.RPDB -> settings.rpdbApiKey.trim()
                .takeIf { it.isNotBlank() }
                ?.let { apiKey ->
                    ActiveProvider(
                        provider = PosterRatingsProvider.RPDB,
                        apiKey = apiKey
                    )
                }
            ArtworkProviderChoiceKey.TOP_POSTERS -> settings.topPostersApiKey.trim()
                .takeIf { it.isNotBlank() }
                ?.let { apiKey ->
                    ActiveProvider(
                        provider = PosterRatingsProvider.TOP_POSTERS,
                        apiKey = apiKey
                    )
                }
            else -> null
        }
    }

    fun resolvePosterUrl(
        originalPosterUrl: String?,
        contentId: String,
        contentType: ContentType,
        activeProvider: ActiveProvider?
    ): String? {
        val provider = activeProvider ?: return originalPosterUrl
        val id = parseContentId(contentId, contentType) ?: return originalPosterUrl

        // Idempotent: if the poster is already from the active provider, return as-is.
        if (originalPosterUrl != null && isAlreadyProviderUrl(originalPosterUrl, provider)) {
            return originalPosterUrl
        }

        return when (provider.provider) {
            PosterRatingsProvider.RPDB -> buildRpdbPosterUrl(
                apiKey = provider.apiKey,
                id = id
            ) ?: originalPosterUrl
            PosterRatingsProvider.TOP_POSTERS -> buildTopPostersUrl(
                apiKey = provider.apiKey,
                id = id,
                fallbackUrl = originalPosterUrl?.takeIf { it.isNotBlank() }
            )
            PosterRatingsProvider.NONE -> originalPosterUrl
        }
    }

    private fun isAlreadyProviderUrl(url: String, provider: ActiveProvider): Boolean {
        val request = PosterIntegrationRequest.fromModel(url)
        if (request != null) {
            return when (provider.provider) {
                PosterRatingsProvider.RPDB -> request.provider == IntegrationProvider.RPDB
                PosterRatingsProvider.TOP_POSTERS -> request.provider == IntegrationProvider.TOP_POSTERS
                PosterRatingsProvider.NONE -> false
            }
        }
        return when (provider.provider) {
            PosterRatingsProvider.RPDB -> url.startsWith(providerUrlPrefix("ratingposterdb"))
            PosterRatingsProvider.TOP_POSTERS -> url.startsWith(providerUrlPrefix("top-posters"))
            PosterRatingsProvider.NONE -> false
        }
    }

    private fun providerUrlPrefix(hostToken: String): String =
        "https://api.$hostToken.com/"

    private fun buildRpdbPosterUrl(apiKey: String, id: ProviderId): String? {
        val idType = when (id.type) {
            IdType.IMDB -> "imdb"
            IdType.TMDB -> "tmdb"
            IdType.TVDB -> "tvdb"
            else -> return null
        }
        return PosterIntegrationRequest(
            provider = IntegrationProvider.RPDB,
            cacheKey = "rpdb:$idType:${id.value}:poster-default:${stableHashHex8(apiKey)}",
            apiKey = apiKey,
            path = "$idType/poster-default/${id.value}.jpg",
            mimeType = "image/jpeg"
        ).toModel()
    }

    private fun buildTopPostersUrl(
        apiKey: String,
        id: ProviderId,
        fallbackUrl: String?
    ): String {
        val path = when (id.type) {
            IdType.IMDB -> "imdb/poster/${id.value}.jpg"
            IdType.TMDB -> "tmdb/poster/${id.value}.jpg"
            IdType.TVDB -> "tvdb/poster/${id.value}.jpg"
            IdType.TRAKT -> "trakt/poster/${id.value}.jpg"
            IdType.MAL -> "mal/poster/${id.value}.jpg"
            IdType.KITSU -> "kitsu/poster/${id.value}.jpg"
            IdType.ANILIST -> "anilist/poster/${id.value}.jpg"
            IdType.ANIDB -> "anidb/poster/${id.value}.jpg"
        }
        return PosterIntegrationRequest(
            provider = IntegrationProvider.TOP_POSTERS,
            cacheKey = "topposters:${id.type.name.lowercase()}:${id.value}:${stableHashHex8(apiKey)}",
            apiKey = apiKey,
            path = path,
            fallbackUrl = fallbackUrl,
            mimeType = "image/jpeg"
        ).toModel()
    }

    private fun parseContentId(contentId: String, contentType: ContentType): ProviderId? {
        val trimmed = contentId.trim()
        if (trimmed.isBlank()) return null

        if (trimmed.startsWith("tt", ignoreCase = true)) {
            return ProviderId(IdType.IMDB, trimmed)
        }

        val normalized = trimmed.lowercase()
        return when {
            normalized.startsWith("imdb:") -> ProviderId(IdType.IMDB, trimmed.substringAfter(':'))
            normalized.startsWith("tmdb:") -> {
                val tmdbRaw = trimmed.substringAfter(':').trim()
                if (tmdbRaw.isBlank()) null else ProviderId(
                    IdType.TMDB,
                    if (tmdbRaw.startsWith("movie-", ignoreCase = true) || tmdbRaw.startsWith("series-", ignoreCase = true)) {
                        tmdbRaw
                    } else if (contentType == ContentType.SERIES || contentType == ContentType.TV) {
                        "series-$tmdbRaw"
                    } else {
                        "movie-$tmdbRaw"
                    }
                )
            }
            normalized.startsWith("tvdb:") -> ProviderId(IdType.TVDB, trimmed.substringAfter(':'))
            normalized.startsWith("trakt:") -> ProviderId(IdType.TRAKT, trimmed.substringAfter(':'))
            normalized.startsWith("mal:") -> ProviderId(IdType.MAL, trimmed.substringAfter(':'))
            normalized.startsWith("kitsu:") -> ProviderId(IdType.KITSU, trimmed.substringAfter(':'))
            normalized.startsWith("anilist:") -> ProviderId(IdType.ANILIST, trimmed.substringAfter(':'))
            normalized.startsWith("anidb:") -> ProviderId(IdType.ANIDB, trimmed.substringAfter(':'))
            else -> null
        }?.takeIf { it.value.isNotBlank() }
    }

    private data class ProviderId(
        val type: IdType,
        val value: String
    )

    private enum class IdType {
        IMDB,
        TMDB,
        TVDB,
        TRAKT,
        MAL,
        KITSU,
        ANILIST,
        ANIDB
    }

    private fun stableHashHex8(s: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
        return buildString(8) {
            for (i in 0 until 4) {
                val b = bytes[i].toInt() and 0xFF
                append(b.toString(16).padStart(2, '0'))
            }
        }
    }
}
