package com.nexio.tv.core.poster

import com.nexio.tv.core.artwork.ArtworkCacheKeys
import com.nexio.tv.core.artwork.ArtworkCandidate
import com.nexio.tv.core.artwork.ArtworkDecision
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkExternalIdSelector
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkProviderRegistry
import com.nexio.tv.core.artwork.ArtworkRoutingPolicy
import com.nexio.tv.core.artwork.ArtworkRouter
import com.nexio.tv.core.artwork.ArtworkSource
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.PersistedArtworkCandidate
import com.nexio.tv.core.artwork.PersistedProviderTemplate
import com.nexio.tv.core.artwork.SensitiveArtworkUrl
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.core.image.PosterIntegrationRequest
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterRatingsProvider
import com.nexio.tv.domain.model.ProviderIds
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PosterRatingsUrlResolver @Inject constructor(
    private val settingsDataStore: PosterRatingsSettingsDataStore,
    private val artworkDecisionCache: ArtworkDecisionCache
) {
    private val artworkRouter = ArtworkRouter()
    private val providerRegistry = ArtworkProviderRegistry()
    private val externalIdSelector = ArtworkExternalIdSelector()

    data class ActiveProvider(
        val provider: PosterRatingsProvider,
        val apiKey: String
    )

    suspend fun currentSettings(): ArtworkProviderSettings =
        settingsDataStore.settings.first()

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

    fun resolvePosterArtworkRef(
        settings: ArtworkProviderSettings,
        providerIds: ProviderIds,
        mediaKind: MetadataMediaKind,
        ownerKey: ArtworkOwnerKey,
        fallbackPosterUrl: String? = null
    ): ArtworkDisplayRef? {
        val policy = ArtworkRoutingPolicy(settings = settings)
        val candidates = buildPosterArtworkCandidates(
            settings = settings,
            providerIds = providerIds,
            mediaKind = mediaKind,
            ownerKey = ownerKey,
            fallbackPosterUrl = fallbackPosterUrl
        )
        if (candidates.isEmpty()) return null

        val selection = artworkRouter.select(candidates, policy)
        val selected = selection.selectedCandidate
        val settingsHash = settings.stableSettingsHash()
        val credentialHash = settings.credentialHash()
        val now = System.currentTimeMillis()
        val decisionKey = ArtworkCacheKeys.decisionKey(
            ownerKey = ownerKey,
            imageType = ArtworkType.POSTER,
            provider = selected.provider,
            premiumEnabled = settings.selection.posterProvider != ArtworkProviderChoiceKey.DEFAULT,
            settingsHash = settingsHash,
            credentialHash = credentialHash,
            policyVersion = policy.policyVersion
        )
        val decision = ArtworkDecision(
            decisionKey = decisionKey,
            ownerKey = ownerKey,
            canonicalContentId = (ownerKey as? ArtworkOwnerKey.CanonicalContent)?.contentId,
            imageType = ArtworkType.POSTER,
            selectedCandidate = selected.toPersistedCandidate(policy.policyVersion),
            rejectedCandidates = selection.rejectedCandidates,
            policyVersion = policy.policyVersion,
            imageLanguage = selected.imageLanguage,
            settingsHash = settingsHash,
            credentialHash = credentialHash,
            createdAtMs = now,
            expiresAtMs = now + DECISION_TTL_MS,
            staleUntilMs = now + DECISION_STALE_TTL_MS
        )
        artworkDecisionCache.put(decision)

        return ArtworkDisplayRef.RuntimeAsset(
            decisionKey = decisionKey,
            assetKey = null,
            imageType = ArtworkType.POSTER,
            selectedProvider = selected.provider,
            sourceRole = selected.sourceRole,
            trace = ArtworkTrace(
                selectedProvider = selected.provider?.key,
                sourceRole = selected.sourceRole.name,
                reason = "poster_artwork_provider_selection",
                rejectedCandidates = selection.rejectedCandidates
            )
        )
    }

    fun resolvePosterArtworkString(
        settings: ArtworkProviderSettings,
        providerIds: ProviderIds,
        mediaKind: MetadataMediaKind,
        ownerKey: ArtworkOwnerKey,
        fallbackPosterUrl: String? = null
    ): String? =
        resolvePosterArtworkRef(
            settings = settings,
            providerIds = providerIds,
            mediaKind = mediaKind,
            ownerKey = ownerKey,
            fallbackPosterUrl = fallbackPosterUrl
        ).toLegacyArtworkString()
            ?: fallbackPosterUrl?.takeIf { it.isNotBlank() && !it.isPremiumProviderRawUrl() }

    /**
     * Legacy raw compat path for older metadata services. UI-facing metadata adapters must use
     * resolvePosterArtworkRef/resolvePosterArtworkString so premium providers remain internal refs.
     */
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

    private fun buildPosterArtworkCandidates(
        settings: ArtworkProviderSettings,
        providerIds: ProviderIds,
        mediaKind: MetadataMediaKind,
        ownerKey: ArtworkOwnerKey,
        fallbackPosterUrl: String?
    ): List<ArtworkCandidate> =
        buildList {
            val premiumProvider = providerRegistry.providerIdFor(settings.selection.posterProvider)
            val runtimeProvider = (premiumProvider as? ArtworkProviderId.RuntimeProvider)?.providerId
            if (runtimeProvider != null) {
                externalIdSelector
                    .selectIds(
                        provider = runtimeProvider,
                        imageType = ArtworkType.POSTER,
                        mediaKind = mediaKind,
                        providerIds = providerIds
                    )
                    .firstOrNull()
                    ?.let { id ->
                        val providerPathHash = stableHashHex(
                            "${runtimeProvider.name.lowercase()}:poster:${id.idType}:${id.mediaId}"
                        )
                        add(
                            ArtworkCandidate(
                                ownerKey = ownerKey,
                                canonicalContentId = (ownerKey as? ArtworkOwnerKey.CanonicalContent)?.contentId,
                                providerIds = providerIds,
                                mediaKind = mediaKind,
                                imageType = ArtworkType.POSTER,
                                provider = premiumProvider,
                                sourceRole = ArtworkSourceRole.PREMIUM,
                                source = ArtworkSource.ProviderTemplate(
                                    provider = premiumProvider,
                                    idType = id.idType,
                                    mediaId = id.mediaId,
                                    providerPathHash = providerPathHash,
                                    settingsHash = settings.stableSettingsHash(),
                                    credentialHash = settings.credentialHash()
                                ),
                                priority = 10,
                                requiresRuntimeFetch = true,
                                trace = ArtworkTrace(
                                    selectedProvider = premiumProvider.key,
                                    sourceRole = ArtworkSourceRole.PREMIUM.name,
                                    reason = "premium_poster_template_candidate"
                                )
                            )
                        )
                    }
            }

            fallbackPosterUrl
                ?.takeIf { it.isNotBlank() }
                ?.let { rawUrl ->
                    val normalizedUrlHash = ArtworkCacheKeys.normalizedUrlHash(rawUrl)
                    val fallbackProvider = fallbackProviderFor(rawUrl)
                    add(
                        ArtworkCandidate(
                            ownerKey = ownerKey,
                            canonicalContentId = (ownerKey as? ArtworkOwnerKey.CanonicalContent)?.contentId,
                            providerIds = providerIds,
                            mediaKind = mediaKind,
                            imageType = ArtworkType.POSTER,
                            provider = fallbackProvider,
                            sourceRole = ArtworkSourceRole.PRIMARY,
                            source = ArtworkSource.RemoteUrl.of(
                                rawUrl = SensitiveArtworkUrl.of(rawUrl),
                                normalizedUrlHash = normalizedUrlHash
                            ),
                            priority = 20,
                            requiresRuntimeFetch = true,
                            trace = ArtworkTrace(
                                selectedProvider = fallbackProvider.key,
                                sourceRole = ArtworkSourceRole.PRIMARY.name,
                                reason = "fallback_poster_url_candidate"
                            )
                        )
                    )
                }
        }

    private fun ArtworkCandidate.toPersistedCandidate(policyVersion: Int): PersistedArtworkCandidate {
        val sourceHash = when (val candidateSource = source) {
            is ArtworkSource.RemoteUrl -> candidateSource.normalizedUrlHash
            is ArtworkSource.ProviderTemplate -> candidateSource.providerPathHash
            is ArtworkSource.LocalAsset -> candidateSource.assetKey.value
            is ArtworkSource.Placeholder -> null
            else -> null
        }
        val providerTemplate = (source as? ArtworkSource.ProviderTemplate)?.let { template ->
            PersistedProviderTemplate(
                provider = template.provider,
                imageType = imageType,
                idType = template.idType,
                mediaId = template.mediaId,
                providerPathHash = template.providerPathHash,
                settingsHash = template.settingsHash,
                credentialHash = template.credentialHash,
                imageLanguage = imageLanguage,
                policyVersion = policyVersion,
                pathParams = template.pathParams
            )
        }
        return PersistedArtworkCandidate(
            provider = provider,
            sourceRole = sourceRole,
            sourceHash = sourceHash,
            redactedSourceForTrace = (source as? ArtworkSource.RemoteUrl)?.redactedUrlForTrace,
            providerTemplate = providerTemplate,
            priority = priority
        )
    }

    private fun fallbackProviderFor(url: String): ArtworkProviderId =
        when {
            url.contains("image.tmdb.org", ignoreCase = true) ->
                ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB)
            url.contains("thetvdb.com", ignoreCase = true) ||
                url.contains("tvdb", ignoreCase = true) ->
                ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB)
            url.contains("kitsu", ignoreCase = true) ->
                ArtworkProviderId.RuntimeProvider(IntegrationProvider.KITSU)
            else -> ArtworkProviderId.AddonPreview
        }

    private fun ArtworkProviderSettings.stableSettingsHash(): String =
        stableHashHex(
            listOf(
                "posterProvider=${selection.posterProvider.value}",
                "hasRpdbKey=$hasRpdbKey",
                "hasTopPostersKey=$hasTopPostersKey",
                "topPostersCanProvideThumbnails=$topPostersCanProvideThumbnails"
            ).joinToString("|")
        )

    private fun ArtworkProviderSettings.credentialHash(): String? =
        when (selection.posterProvider) {
            ArtworkProviderChoiceKey.RPDB -> rpdbApiKey.trim()
            ArtworkProviderChoiceKey.TOP_POSTERS -> topPostersApiKey.trim()
            else -> ""
        }.takeIf { it.isNotBlank() }?.let { stableHashHex(it) }

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

    private fun String.isPremiumProviderRawUrl(): Boolean =
        startsWith(providerUrlPrefix("ratingposterdb"), ignoreCase = true) ||
            startsWith(providerUrlPrefix("top-posters"), ignoreCase = true)

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
        return stableHashHex(s).take(8)
    }

    private fun stableHashHex(s: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val DECISION_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L
        const val DECISION_STALE_TTL_MS: Long = 30L * 24L * 60L * 60L * 1000L
    }
}
