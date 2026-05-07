package com.nexio.tv.core.poster

import com.nexio.tv.core.artwork.ArtworkCacheKeys
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkCandidate
import com.nexio.tv.core.artwork.ArtworkCredentialHash
import com.nexio.tv.core.artwork.ArtworkDecision
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDecisionPolicy
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.EpisodeArtworkContext
import com.nexio.tv.core.artwork.ArtworkExternalIdSelector
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkProviderRegistry
import com.nexio.tv.core.artwork.ArtworkRemoteSourceStore
import com.nexio.tv.core.artwork.ArtworkRoutingPolicy
import com.nexio.tv.core.artwork.ArtworkRouter
import com.nexio.tv.core.artwork.ArtworkSource
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.NoopArtworkRemoteSourceStore
import com.nexio.tv.core.artwork.RejectedArtworkCandidate
import com.nexio.tv.core.artwork.SensitiveArtworkUrl
import com.nexio.tv.core.artwork.toPersistedCandidate
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.core.image.IntegrationPosterRequest
import com.nexio.tv.core.image.TopPostersThumbnailRequest
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
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
    private val artworkDecisionCache: ArtworkDecisionCache,
    private val remoteSourceStore: ArtworkRemoteSourceStore = NoopArtworkRemoteSourceStore
) {
    private val artworkRouter = ArtworkRouter(remoteSourceStore = remoteSourceStore)
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
        val resolved = resolvePosterArtworkRef(
            originalPosterUrl = meta.poster,
            contentId = meta.id,
            contentType = meta.type,
            activeProvider = activeProvider
        ) as? ArtworkDisplayRef.RuntimeAsset
        val poster = resolved.toLegacyArtworkString()
            ?: meta.poster?.takeIf { it.isSafeRemoteArtworkFallback() || it.isInternalArtworkRef() }
        return meta.copy(
            poster = poster,
            posterProviderTag = resolved.premiumProviderTag()
        )
    }

    fun apply(metaPreview: MetaPreview, activeProvider: ActiveProvider?): MetaPreview {
        if (metaPreview.poster?.isInternalArtworkRef() == true) return metaPreview

        val settings = activeProvider.toArtworkProviderSettings() ?: return metaPreview.copy(
            poster = metaPreview.poster?.takeIf { it.isSafeRemoteArtworkFallback() || it.isInternalArtworkRef() },
            posterProviderTag = null
        )
        val fallbackPosterUrl = metaPreview.poster?.takeIf { it.isSafeRemoteArtworkFallback() }
        val resolved = resolvePosterArtworkRef(
            settings = settings,
            providerIds = metaPreview.posterArtworkProviderIds(),
            mediaKind = metaPreview.type.toMetadataMediaKind(),
            ownerKey = metaPreview.posterArtworkOwnerKey(),
            fallbackPosterUrl = fallbackPosterUrl
        ) as? ArtworkDisplayRef.RuntimeAsset
        val poster = resolved.toLegacyArtworkString()
            ?: fallbackPosterUrl
        return metaPreview.copy(
            poster = poster,
            posterProviderTag = resolved.premiumProviderTag()
        )
    }

    fun applyArtworkRef(metaPreview: MetaPreview, settings: ArtworkProviderSettings): MetaPreview {
        val currentPoster = metaPreview.poster
        if (currentPoster != null && currentPoster.isInternalArtworkRef()) {
            return metaPreview
        }

        val providerIds = metaPreview.posterArtworkProviderIds()
        val ownerKey = metaPreview.posterArtworkOwnerKey()
        val fallbackPosterUrl = currentPoster?.takeIf { it.isSafeRemoteArtworkFallback() }
        val resolved = resolvePosterArtworkRef(
            settings = settings,
            providerIds = providerIds,
            mediaKind = metaPreview.type.toMetadataMediaKind(),
            ownerKey = ownerKey,
            fallbackPosterUrl = fallbackPosterUrl
        ) as? ArtworkDisplayRef.RuntimeAsset

        val poster = resolved.toLegacyArtworkString()
            ?: fallbackPosterUrl
        val providerTag = resolved
            ?.takeIf { it.sourceRole == ArtworkSourceRole.PREMIUM }
            ?.selectedProvider
            ?.key
            ?.lowercase()

        return metaPreview.copy(
            poster = poster,
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
        val selected = selection.selectedCandidateOrNull ?: return null
        val settingsHash = ArtworkDecisionPolicy.settingsHash(settings, ArtworkType.POSTER)
        val credentialHash = ArtworkDecisionPolicy.credentialHash(settings, ArtworkType.POSTER)
        val now = System.currentTimeMillis()
        val decisionKey = ArtworkCacheKeys.decisionKey(
            ownerKey = ownerKey,
            imageType = ArtworkType.POSTER,
            provider = selected.provider,
            premiumEnabled = ArtworkDecisionPolicy.premiumEnabled(settings, ArtworkType.POSTER),
            settingsHash = settingsHash,
            credentialHash = credentialHash,
            policyVersion = policy.policyVersion
        )
        val decision = ArtworkDecision(
            decisionKey = decisionKey,
            ownerKey = ownerKey,
            canonicalContentId = (ownerKey as? ArtworkOwnerKey.CanonicalContent)?.contentId,
            imageType = ArtworkType.POSTER,
            selectedCandidate = selected.toPersistedCandidate(
                policyVersion = policy.policyVersion,
                remoteSourceStore = remoteSourceStore
            ),
            rejectedCandidates = selection.rejectedCandidates,
            policyVersion = policy.policyVersion,
            imageLanguage = selected.imageLanguage,
            settingsHash = settingsHash,
            credentialHash = credentialHash,
            createdAtMs = now,
            expiresAtMs = now + ArtworkDecisionPolicy.DECISION_TTL_MS,
            staleUntilMs = now + ArtworkDecisionPolicy.DECISION_STALE_TTL_MS
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
            fallbackPosterUrl = fallbackPosterUrl?.takeIf { it.isSafeRemoteArtworkFallback() }
        ).toLegacyArtworkString()
            ?: fallbackPosterUrl?.takeIf { it.isSafeRemoteArtworkFallback() }

    fun resolvePosterArtworkRef(
        originalPosterUrl: String?,
        contentId: String,
        contentType: ContentType,
        activeProvider: ActiveProvider?
    ): ArtworkDisplayRef? {
        val settings = activeProvider.toArtworkProviderSettings() ?: return null
        val parsedProviderIds = parseContentId(contentId, contentType)?.toProviderIds() ?: ProviderIds()
        val fallbackPosterUrl = originalPosterUrl?.takeIf { it.isSafeRemoteArtworkFallback() }
        return resolvePosterArtworkRef(
            settings = settings,
            providerIds = parsedProviderIds,
            mediaKind = contentType.toMetadataMediaKind(),
            ownerKey = parsedProviderIds.posterArtworkOwnerKey(
                contentId = contentId,
                contentType = contentType,
                fallbackPosterUrl = fallbackPosterUrl
            ),
            fallbackPosterUrl = fallbackPosterUrl
        )
    }

    fun resolvePosterArtworkString(
        originalPosterUrl: String?,
        contentId: String,
        contentType: ContentType,
        activeProvider: ActiveProvider?
    ): String? =
        resolvePosterArtworkRef(
            originalPosterUrl = originalPosterUrl,
            contentId = contentId,
            contentType = contentType,
            activeProvider = activeProvider
        ).toLegacyArtworkString()
            ?: originalPosterUrl?.takeIf { it.isSafeRemoteArtworkFallback() || it.isInternalArtworkRef() }

    fun resolveEpisodeThumbnailArtworkRef(
        settings: ArtworkProviderSettings,
        providerIds: ProviderIds,
        mediaKind: MetadataMediaKind,
        ownerKey: ArtworkOwnerKey,
        episodeContext: EpisodeArtworkContext,
        fallbackThumbnailUrl: String?,
        primaryProvider: ArtworkProviderId
    ): ArtworkDisplayRef? {
        val policy = ArtworkRoutingPolicy(settings = settings)
        val missingRejections = mutableListOf<RejectedArtworkCandidate>()
        val candidates = buildEpisodeThumbnailArtworkCandidates(
            settings = settings,
            providerIds = providerIds,
            mediaKind = mediaKind,
            ownerKey = ownerKey,
            episodeContext = episodeContext,
            fallbackThumbnailUrl = fallbackThumbnailUrl,
            primaryProvider = primaryProvider,
            missingRejections = missingRejections
        )
        if (candidates.isEmpty()) return null

        val selection = artworkRouter.select(candidates, policy)
        val selected = selection.selectedCandidateOrNull ?: return null
        val rejectedCandidates = missingRejections + selection.rejectedCandidates
        val persistedSelected = selected.toPersistedCandidate(
            policyVersion = policy.policyVersion,
            remoteSourceStore = remoteSourceStore
        )
        val selectedAssetKey = selected.assetKeyForRuntimeRef(policy.policyVersion)
        val settingsHash = ArtworkDecisionPolicy.settingsHash(settings, ArtworkType.THUMBNAIL)
        val credentialHash = ArtworkDecisionPolicy.credentialHash(settings, ArtworkType.THUMBNAIL)
        val now = System.currentTimeMillis()
        val decisionKey = ArtworkCacheKeys.decisionKey(
            ownerKey = ownerKey,
            imageType = ArtworkType.THUMBNAIL,
            provider = selected.provider,
            premiumEnabled = ArtworkDecisionPolicy.premiumEnabled(settings, ArtworkType.THUMBNAIL),
            settingsHash = settingsHash,
            credentialHash = credentialHash,
            policyVersion = policy.policyVersion
        )
        val decision = ArtworkDecision(
            decisionKey = decisionKey,
            ownerKey = ownerKey,
            canonicalContentId = (ownerKey as? ArtworkOwnerKey.CanonicalContent)?.contentId,
            imageType = ArtworkType.THUMBNAIL,
            selectedCandidate = persistedSelected,
            rejectedCandidates = rejectedCandidates,
            policyVersion = policy.policyVersion,
            imageLanguage = selected.imageLanguage,
            settingsHash = settingsHash,
            credentialHash = credentialHash,
            createdAtMs = now,
            expiresAtMs = now + ArtworkDecisionPolicy.DECISION_TTL_MS,
            staleUntilMs = now + ArtworkDecisionPolicy.DECISION_STALE_TTL_MS
        )
        artworkDecisionCache.put(decision)

        return ArtworkDisplayRef.RuntimeAsset(
            decisionKey = decisionKey,
            assetKey = selectedAssetKey,
            imageType = ArtworkType.THUMBNAIL,
            selectedProvider = selected.provider,
            sourceRole = selected.sourceRole,
            trace = ArtworkTrace(
                selectedProvider = selected.provider?.key,
                sourceRole = selected.sourceRole.name,
                reason = "thumbnail_artwork_provider_selection",
                rejectedCandidates = rejectedCandidates
            ),
            displayHints = selected.displayHints()
        )
    }

    /**
     * Legacy raw compat path retained for tests and older callers. It no longer builds premium
     * provider models; UI-facing metadata adapters must use resolvePosterArtworkRef/string.
     */
    fun resolvePosterUrl(
        originalPosterUrl: String?,
        contentId: String,
        contentType: ContentType,
        activeProvider: ActiveProvider?
    ): String? =
        originalPosterUrl?.takeIf { it.isSafeRemoteArtworkFallback() || it.isInternalArtworkRef() }

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
                ?.takeIf { it.isSafeRemoteArtworkFallback() }
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

    private fun buildEpisodeThumbnailArtworkCandidates(
        settings: ArtworkProviderSettings,
        providerIds: ProviderIds,
        mediaKind: MetadataMediaKind,
        ownerKey: ArtworkOwnerKey,
        episodeContext: EpisodeArtworkContext,
        fallbackThumbnailUrl: String?,
        primaryProvider: ArtworkProviderId,
        missingRejections: MutableList<RejectedArtworkCandidate>
    ): List<ArtworkCandidate> =
        buildList {
            val selectedThumbnailProvider = settings.selection.thumbnailProvider
            val premiumProvider = providerRegistry.providerIdFor(selectedThumbnailProvider)
            val runtimeProvider = (premiumProvider as? ArtworkProviderId.RuntimeProvider)?.providerId
            if (selectedThumbnailProvider == ArtworkProviderChoiceKey.TOP_POSTERS &&
                runtimeProvider == IntegrationProvider.TOP_POSTERS
            ) {
                val id = if (episodeContext.isValid) {
                    externalIdSelector
                        .selectIds(
                            provider = runtimeProvider,
                            imageType = ArtworkType.THUMBNAIL,
                            mediaKind = mediaKind,
                            providerIds = providerIds,
                            episodeContext = episodeContext
                        )
                        .firstOrNull()
                } else {
                    missingRejections += RejectedArtworkCandidate(
                        provider = premiumProvider,
                        sourceRole = ArtworkSourceRole.PREMIUM,
                        reason = "topposters_invalid_episode_context"
                    )
                    null
                }
                if (id == null) {
                    if (episodeContext.isValid) {
                        missingRejections += RejectedArtworkCandidate(
                            provider = premiumProvider,
                            sourceRole = ArtworkSourceRole.PREMIUM,
                            reason = "missing_supported_provider_id"
                        )
                    }
                } else {
                    val pathParams = topPostersThumbnailPathParams(episodeContext)
                    val providerPathHash = ArtworkCacheKeys.providerTemplatePathHash(
                        provider = premiumProvider,
                        imageType = ArtworkType.THUMBNAIL,
                        idType = id.idType,
                        mediaId = id.mediaId,
                        pathParams = pathParams
                    )
                    add(
                        ArtworkCandidate(
                            ownerKey = ownerKey,
                            canonicalContentId = (ownerKey as? ArtworkOwnerKey.CanonicalContent)?.contentId,
                            providerIds = providerIds,
                            mediaKind = mediaKind,
                            imageType = ArtworkType.THUMBNAIL,
                            provider = premiumProvider,
                            sourceRole = ArtworkSourceRole.PREMIUM,
                            source = ArtworkSource.ProviderTemplate(
                                provider = premiumProvider,
                                idType = id.idType,
                                mediaId = id.mediaId,
                                providerPathHash = providerPathHash,
                                settingsHash = settings.stableSettingsHash(ArtworkType.THUMBNAIL),
                                credentialHash = settings.credentialHash(selectedThumbnailProvider),
                                pathParams = pathParams
                            ),
                            priority = 10,
                            requiresRuntimeFetch = true,
                            trace = ArtworkTrace(
                                selectedProvider = premiumProvider.key,
                                sourceRole = ArtworkSourceRole.PREMIUM.name,
                                reason = "premium_thumbnail_template_candidate"
                            )
                        )
                    )
                }
            }

            fallbackThumbnailUrl
                ?.takeIf { it.isNotBlank() }
                ?.takeIf { it.isSafeRemoteArtworkFallback() }
                ?.let { rawUrl ->
                    val normalizedUrlHash = ArtworkCacheKeys.normalizedUrlHash(rawUrl)
                    add(
                        ArtworkCandidate(
                            ownerKey = ownerKey,
                            canonicalContentId = (ownerKey as? ArtworkOwnerKey.CanonicalContent)?.contentId,
                            providerIds = providerIds,
                            mediaKind = mediaKind,
                            imageType = ArtworkType.THUMBNAIL,
                            provider = primaryProvider,
                            sourceRole = ArtworkSourceRole.PRIMARY,
                            source = ArtworkSource.RemoteUrl.of(
                                rawUrl = SensitiveArtworkUrl.of(rawUrl),
                                normalizedUrlHash = normalizedUrlHash
                            ),
                            priority = 20,
                            requiresRuntimeFetch = true,
                            trace = ArtworkTrace(
                                selectedProvider = primaryProvider.key,
                                sourceRole = ArtworkSourceRole.PRIMARY.name,
                                reason = "primary_thumbnail_url_candidate"
                            )
                        )
                    )
                }
        }

    private fun ArtworkCandidate.assetKeyForRuntimeRef(policyVersion: Int): ArtworkAssetKey? =
        when (val candidateSource = source) {
            is ArtworkSource.ProviderTemplate ->
                toPersistedCandidate(
                    policyVersion = policyVersion,
                    remoteSourceStore = remoteSourceStore
                ).providerTemplate
                    ?.let(ArtworkCacheKeys::assetKeyForProviderTemplate)
            is ArtworkSource.RemoteUrl ->
                provider?.let { selectedProvider ->
                    ArtworkCacheKeys.assetKeyForRemoteUrl(
                        provider = selectedProvider,
                        imageType = imageType,
                        normalizedUrlHash = candidateSource.normalizedUrlHash,
                        variant = null,
                        policyVersion = policyVersion
                    )
                }
            is ArtworkSource.LocalAsset -> candidateSource.assetKey
            else -> null
        }

    private fun ArtworkCandidate.displayHints(): ArtworkDisplayHints =
        ArtworkDisplayHints(
            embedsRatingOverlay = imageType == ArtworkType.THUMBNAIL &&
                selectedTopPostersThumbnailProvider()
        )

    private fun ArtworkCandidate.selectedTopPostersThumbnailProvider(): Boolean =
        (provider as? ArtworkProviderId.RuntimeProvider)?.providerId == IntegrationProvider.TOP_POSTERS &&
            sourceRole == ArtworkSourceRole.PREMIUM

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

    private fun ArtworkProviderSettings.stableSettingsHash(imageType: ArtworkType): String =
        if (imageType == ArtworkType.THUMBNAIL) {
            ArtworkCacheKeys.providerTemplateSettingsHash(
                imageType = imageType,
                settingsParts = listOf(
                    "thumbnailProvider=${selection.thumbnailProvider.value}",
                    "hasTopPostersKey=$hasTopPostersKey",
                    "topPostersCanProvideThumbnails=$topPostersCanProvideThumbnails"
                )
            )
        } else {
            stableSettingsHash()
        }

    private fun ArtworkProviderSettings.credentialHash(): String? =
        credentialHash(selection.posterProvider)

    private fun ArtworkProviderSettings.credentialHash(providerChoice: ArtworkProviderChoiceKey): String? =
        when (providerChoice) {
            ArtworkProviderChoiceKey.RPDB -> ArtworkCredentialHash.hashCredential(rpdbApiKey)
            ArtworkProviderChoiceKey.TOP_POSTERS -> ArtworkCredentialHash.hashCredential(topPostersApiKey)
            else -> null
        }

    private fun topPostersThumbnailPathParams(episodeContext: EpisodeArtworkContext): Map<String, String> =
        mapOf(
            "season" to episodeContext.season.toString(),
            "episode" to episodeContext.episode.toString(),
            "badgeSize" to TopPostersThumbnailRequest.BADGE_SIZE,
            "badgePosition" to TopPostersThumbnailRequest.BADGE_POSITION,
            "blur" to TopPostersThumbnailRequest.BLUR.toString()
        )

    private fun stableHashHex(s: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun providerUrlPrefix(hostToken: String): String =
        "https://api.$hostToken.com/"

    private fun String.isPremiumProviderRawUrl(): Boolean =
        startsWith(providerUrlPrefix("ratingposterdb"), ignoreCase = true) ||
            startsWith(providerUrlPrefix("top-posters"), ignoreCase = true)

    private fun String.isInternalArtworkRef(): Boolean =
        startsWith("nexio-artwork://", ignoreCase = true) ||
            startsWith("nexio-placeholder://", ignoreCase = true)

    private fun String.isSafeRemoteArtworkFallback(): Boolean {
        val value = trim()
        if (value.isBlank()) return false
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        if (value.isPremiumProviderRawUrl()) return false
        if (value.isInternalArtworkRef()) return false
        if (value.isLegacyIntegrationPosterModel()) return false
        return true
    }

    private fun String.isLegacyIntegrationPosterModel(): Boolean =
        startsWith("integration-poster://", ignoreCase = true) ||
            IntegrationPosterRequest.fromModel(this) != null

    private fun ActiveProvider?.toArtworkProviderSettings(): ArtworkProviderSettings? =
        when (this?.provider) {
            PosterRatingsProvider.RPDB -> ArtworkProviderSettings(
                rpdbApiKey = apiKey,
                selection = ArtworkProviderSelectionSettings(
                    posterProvider = ArtworkProviderChoiceKey.RPDB
                )
            )
            PosterRatingsProvider.TOP_POSTERS -> ArtworkProviderSettings(
                topPostersApiKey = apiKey,
                selection = ArtworkProviderSelectionSettings(
                    posterProvider = ArtworkProviderChoiceKey.TOP_POSTERS
                )
            )
            PosterRatingsProvider.NONE,
            null -> null
        }

    private fun ArtworkDisplayRef.RuntimeAsset?.premiumProviderTag(): String? =
        this
            ?.takeIf { it.sourceRole == ArtworkSourceRole.PREMIUM }
            ?.selectedProvider
            ?.key
            ?.lowercase()

    private fun ProviderIds.posterArtworkOwnerKey(
        contentId: String,
        contentType: ContentType,
        fallbackPosterUrl: String?
    ): ArtworkOwnerKey =
        canonicalArtworkContentId(contentType)?.let(ArtworkOwnerKey::CanonicalContent)
            ?: ArtworkOwnerKey.PreviewItem(
                itemKey = "${contentType.toApiString()}:${contentId.trim()}",
                sourcePayloadHash = stableHashHex(
                    listOf(
                        "id=${contentId.trim()}",
                        "type=${contentType.toApiString()}",
                        "poster=${fallbackPosterUrl.orEmpty()}"
                    ).joinToString("|")
                )
            )

    private fun MetaPreview.posterArtworkOwnerKey(): ArtworkOwnerKey {
        val stableIds = (firstPaintStableIds as ProviderIds?) ?: ProviderIds()
        return stableIds.canonicalArtworkContentId(type)?.let(ArtworkOwnerKey::CanonicalContent)
            ?: ArtworkOwnerKey.PreviewItem(
                itemKey = "${apiType}:${id.trim()}",
                sourcePayloadHash = stableHashHex(
                    listOf(
                        "source=${(firstPaintSource as FirstPaintSource?) ?: FirstPaintSource.ADDON_META_PREVIEW}",
                        "sourceProvider=${firstPaintSourceProvider?.name.orEmpty()}",
                        "sourceItemId=${firstPaintSourceItemId.orEmpty()}",
                        "railSource=${firstPaintRailSource?.name.orEmpty()}",
                        "id=${id.trim()}",
                        "type=$apiType",
                        "poster=${poster.orEmpty()}"
                    ).joinToString("|")
                )
            )
    }

    private fun MetaPreview.posterArtworkProviderIds(): ProviderIds {
        val stableIds = (firstPaintStableIds as ProviderIds?) ?: ProviderIds()
        val derived = parseContentId(id, type)?.toProviderIds() ?: ProviderIds()
        return stableIds.withFallback(derived)
    }

    private fun ProviderIds.canonicalArtworkContentId(contentType: ContentType): String? {
        return when {
            !imdb.isNullOrBlank() -> "imdb:${imdb.trim()}"
            !tmdb.isNullOrBlank() -> "tmdb:${tmdb.trim().withTmdbMediaPrefix(contentType)}"
            !tvdb.isNullOrBlank() -> "tvdb:${tvdb.trim()}"
            !kitsu.isNullOrBlank() -> "kitsu:${kitsu.trim()}"
            !trakt.isNullOrBlank() -> "trakt:${trakt.trim()}"
            !simkl.isNullOrBlank() -> "simkl:${simkl.trim()}"
            !mal.isNullOrBlank() -> "mal:${mal.trim()}"
            !anilist.isNullOrBlank() -> "anilist:${anilist.trim()}"
            !anidb.isNullOrBlank() -> "anidb:${anidb.trim()}"
            else -> null
        }
    }

    private fun ProviderIds.withFallback(fallback: ProviderIds): ProviderIds =
        ProviderIds(
            imdb = imdb.nonBlankOr(fallback.imdb),
            tmdb = tmdb.nonBlankOr(fallback.tmdb),
            tvdb = tvdb.nonBlankOr(fallback.tvdb),
            trakt = trakt.nonBlankOr(fallback.trakt),
            simkl = simkl.nonBlankOr(fallback.simkl),
            kitsu = kitsu.nonBlankOr(fallback.kitsu),
            slug = slug.nonBlankOr(fallback.slug),
            mal = mal.nonBlankOr(fallback.mal),
            anilist = anilist.nonBlankOr(fallback.anilist),
            anidb = anidb.nonBlankOr(fallback.anidb)
        )

    private fun String?.nonBlankOr(fallback: String?): String? =
        this?.trim()?.takeIf { it.isNotBlank() } ?: fallback

    private fun ProviderId.toProviderIds(): ProviderIds =
        when (type) {
            IdType.IMDB -> ProviderIds(imdb = value)
            IdType.TMDB -> ProviderIds(tmdb = value)
            IdType.TVDB -> ProviderIds(tvdb = value)
            IdType.TRAKT -> ProviderIds(trakt = value)
            IdType.MAL -> ProviderIds(mal = value)
            IdType.KITSU -> ProviderIds(kitsu = value)
            IdType.ANILIST -> ProviderIds(anilist = value)
            IdType.ANIDB -> ProviderIds(anidb = value)
        }

    private fun ContentType.toMetadataMediaKind(): MetadataMediaKind =
        when (this) {
            ContentType.MOVIE -> MetadataMediaKind.MOVIE
            ContentType.SERIES,
            ContentType.TV -> MetadataMediaKind.SERIES
            ContentType.CHANNEL,
            ContentType.PERSON,
            ContentType.UNKNOWN -> MetadataMediaKind.UNKNOWN
        }

    private fun String.withTmdbMediaPrefix(contentType: ContentType): String =
        when {
            startsWith("movie-", ignoreCase = true) || startsWith("series-", ignoreCase = true) -> this
            contentType == ContentType.MOVIE -> "movie-$this"
            else -> "series-$this"
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
}
