package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolver
import com.nexio.tv.core.artwork.ArtworkProviderResolver
import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.ContentTypeDefaults
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
import com.nexio.tv.core.artwork.takeIfImageType
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.core.metadata.router.CanonicalStableIds
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouteFailure
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HOME_OVERLAY_POLICY_VERSION
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.RatingValueValidator
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import com.nexio.tv.domain.model.toSettingsSignature
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

enum class HomeHydrationPriority {
    FOCUSED,
    VISIBLE,
    ADJACENT,
    HERO
}

@Singleton
class HomeHydrationCoordinator @Inject constructor(
    private val metadataRouterFacade: MetadataRouterFacade,
    private val overlayStore: HydratedHomeOverlayStore,
    private val traceEvents: TraceMetadataEvents,
    private val settingsSource: ArtworkProviderSettingsSource,
    private val artworkProviderResolver: ArtworkProviderResolver = ArtworkProviderResolver(
        ArtworkProviderCapabilityResolver()
    )
) {
    suspend fun hydrate(
        item: MetaPreview,
        trigger: StableIdResolutionTrigger,
        priority: HomeHydrationPriority,
        languageTag: String,
        expectedGeneration: Long,
        currentGeneration: () -> Long,
        onOverlayApplied: (HydratedHomeOverlay) -> Boolean
    ): HydratedHomeOverlay? {
        val itemKey = homeDisplayItemKey(item.apiType, item.id)
        traceEvents.emitHomeHydrationStarted(
            railId = item.firstPaintRailSource?.name,
            itemKey = itemKey,
            firstPaintSource = item.firstPaintSource.name,
            trigger = trigger.name,
            priority = priority.name,
            workClass = WORK_CLASS_BACKGROUND_HYDRATION
        )

        return try {
            if (currentGeneration() != expectedGeneration) {
                traceEvents.emitHomeHydrationIgnored(
                    itemKey = itemKey,
                    reason = "generation_mismatch",
                    trigger = trigger.name
                )
                return null
            }

            val request = item.toMetadataRequest(languageTag)
            val result = metadataRouterFacade.resolveRequest(request)
            if (currentGeneration() != expectedGeneration) {
                traceEvents.emitHomeHydrationIgnored(
                    itemKey = itemKey,
                    reason = "generation_mismatch",
                    trigger = trigger.name
                )
                return null
            }

            val stableIds = resolveStableIdBundle(result.route, request, trigger, itemKey)
            if (currentGeneration() != expectedGeneration) {
                traceEvents.emitHomeHydrationIgnored(
                    itemKey = itemKey,
                    reason = "generation_mismatch",
                    trigger = trigger.name
                )
                return null
            }

            val bundle = stableIds.bundle
            val currentSettings = settingsSource.settings.first()
            val settingsSignature = currentSettings.toSettingsSignature()
            val stableIdsSnapshot = bundle?.let { b ->
                ProviderIds(
                    imdb = b.sidecars.imdbId ?: item.firstPaintStableIds.imdb,
                    tmdb = b.canonical.tmdbIdFor(b.itemType) ?: item.firstPaintStableIds.tmdb,
                    tvdb = b.canonical.tvdbSeriesId ?: item.firstPaintStableIds.tvdb,
                    kitsu = b.canonical.kitsuAnimeId ?: item.firstPaintStableIds.kitsu,
                    trakt = item.firstPaintStableIds.trakt,
                    simkl = item.firstPaintStableIds.simkl
                )
            } ?: item.firstPaintStableIds
            val overlay = result.toHydratedHomeOverlay(
                item = item,
                itemKey = itemKey,
                bundle = bundle,
                languageTag = languageTag,
                stableIdsSnapshot = stableIdsSnapshot,
                settingsSignature = settingsSignature,
                currentSettings = currentSettings
            ) ?: return failed(itemKey, trigger, "canonical_identity_unresolved")

            if (currentGeneration() != expectedGeneration) {
                traceEvents.emitHomeHydrationIgnored(
                    itemKey = itemKey,
                    reason = "generation_mismatch",
                    trigger = trigger.name
                )
                return null
            }

            // Content-equality gate. Prevents a feedback loop where unconditional upsert
                // bumps the version flow, which re-triggers downstream apply-seams and
                // re-spawns hero/visible hydration -> upsert again. The 73/sec
                // home.hydration_applied flood + cascading SharedPreferences writes were
                // the dominant ANR source. We compare displayHash (canonical content
                // identity) and fields; if both match a stored overlay we skip upsert and
                // return the existing instance so callers see a stable identity.
            val existingOverlay = overlayStore.readByCanonicalIdentity(
                canonicalProvider = overlay.canonicalProvider,
                canonicalId = overlay.canonicalId,
                contentType = overlay.contentType,
                languageTag = overlay.languageTag,
                policyVersion = overlay.policyVersion
            )
            if (existingOverlay != null &&
                existingOverlay.state != HomeItemHydrationState.STALE_READY &&
                existingOverlay.displayHash == overlay.displayHash &&
                existingOverlay.fields == overlay.fields
            ) {
                traceEvents.emitHomeHydrationIgnored(
                    itemKey = itemKey,
                    reason = "overlay_content_unchanged",
                    trigger = trigger.name
                )
                return existingOverlay
            }

            if (existingOverlay?.state == HomeItemHydrationState.STALE_READY) {
                traceEvents.emitOverlayRehydrationTriggered(
                    itemKey = itemKey,
                    source = priority.name,
                    priorState = HomeItemHydrationState.STALE_READY.name
                )
            }

            overlayStore.upsert(
                overlay = overlay,
                aliases = overlayAliases(
                    item = item,
                    bundle = bundle,
                    overlay = overlay,
                    itemKey = itemKey
                )
            )
            if (currentGeneration() != expectedGeneration) {
                traceEvents.emitHomeHydrationIgnored(
                    itemKey = itemKey,
                    reason = "generation_mismatch",
                    trigger = trigger.name
                )
                return null
            }
            traceEvents.emitHomeHydrationOverlayWritten(
                itemKey = itemKey,
                canonicalProvider = overlay.canonicalProvider.name,
                canonicalId = overlay.canonicalId,
                imdbId = overlay.imdbId,
                displayHash = overlay.displayHash
            )
            val overlayAccepted = onOverlayApplied(overlay)
            val firstPaintMetadata = item.toHomeDisplayMetadata()
            val previewRating = firstPaintMetadata.imdbRating
            val acceptedPreviewRating = RatingValueValidator.validTitleRating(previewRating)
            val sanitizedPreviewRating = RatingValueValidator.sanitizeTitleRating(previewRating)
            val sanitizedHydratedRating = RatingValueValidator.sanitizeTitleRating(overlay.fields.imdbRating)
            val hydratedTvdbId = overlay.canonicalId.takeIf { overlay.canonicalProvider == ProviderId.TVDB }
                ?: bundle?.canonical?.tvdbSeriesId
            traceEvents.emitHomeRatingAndArtworkSurface(
                surface = "HOME",
                itemKeyHash = traceEvents.hashForActiveSession(HOME_RATING_ARTWORK_TRACE_HASH_PURPOSE, itemKey),
                firstPaintRatingValue = sanitizedPreviewRating,
                firstPaintRatingAccepted = acceptedPreviewRating,
                firstPaintRatingRejectReason = if (previewRating != null && !acceptedPreviewRating) {
                    "OUT_OF_RANGE_TITLE_RATING"
                } else {
                    null
                },
                firstPaintLogoPresent = firstPaintMetadata.displayLogo != null,
                firstPaintTmdbIdHash = item.firstPaintStableIds.tmdbOrPreviewId(item.id)
                    ?.let { traceEvents.hashForActiveSession(HOME_RATING_ARTWORK_TRACE_HASH_PURPOSE, it) },
                firstPaintTvdbIdHash = item.firstPaintStableIds.tvdb
                    ?.let { traceEvents.hashForActiveSession(HOME_RATING_ARTWORK_TRACE_HASH_PURPOSE, it) },
                firstPaintImdbIdHash = item.firstPaintStableIds.imdb
                    ?.let { traceEvents.hashForActiveSession(HOME_RATING_ARTWORK_TRACE_HASH_PURPOSE, it) },
                hydrationStarted = true,
                routeProvider = result.route?.provider?.name,
                tvdbIdHash = hydratedTvdbId?.let {
                    traceEvents.hashForActiveSession(HOME_RATING_ARTWORK_TRACE_HASH_PURPOSE, it)
                },
                overlayApplied = overlayAccepted,
                hydratedRatingValue = sanitizedHydratedRating,
                hydratedRatingSource = overlay.fields.ratingSource?.name.takeIf { sanitizedHydratedRating != null },
                hydratedLogoPresent = overlay.fields.displayLogo != null,
                hydratedLogoSource = overlay.fields.artwork?.logo?.trace?.selectedProvider
            )
            if (!overlayAccepted) {
                return null
            }
            traceEvents.emitHomeHydrationApplied(
                railId = item.firstPaintRailSource?.name,
                itemKey = itemKey,
                firstPaintSource = item.firstPaintSource.name,
                canonicalProvider = overlay.canonicalProvider.name,
                canonicalId = overlay.canonicalId,
                imdbId = overlay.imdbId,
                trigger = trigger.name,
                priority = priority.name,
                workClass = WORK_CLASS_BACKGROUND_HYDRATION,
                changedFields = changedFields(firstPaintMetadata, overlay.fields),
                displayHashBefore = item.toHomeDisplayMetadata().hydratedHomeDisplayHash(),
                displayHashAfter = overlay.displayHash,
                rowOrderChanged = false,
                focusChanged = false,
                networkExecuted = stableIds.networkExecuted,
                cacheDecision = stableIds.cacheDecision
            )
            overlay
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failed(itemKey, trigger, error.toHomeHydrationFailureReason())
        }
    }

    private suspend fun MetadataResolutionResult.toHydratedHomeOverlay(
        item: MetaPreview,
        itemKey: String,
        bundle: StableIdBundle?,
        languageTag: String,
        stableIdsSnapshot: ProviderIds,
        settingsSignature: String,
        currentSettings: ArtworkProviderSettings
    ): HydratedHomeOverlay? {
        val routeProvider = route?.provider
        val canonicalIdentity = canonicalIdentity(route, resolvedDocument, bundle) ?: return null
        val fields = displayMetadata
            .sanitizeHydratedTitleRating()
            .withHydratedOnlyArtwork(
                item = item,
                document = resolvedDocument,
                stableIds = stableIdsSnapshot,
                settings = currentSettings
            )
        val nowMs = System.currentTimeMillis()

        return HydratedHomeOverlay(
            overlayKey = hydratedHomeOverlayKey(
                canonicalProvider = canonicalIdentity.provider,
                canonicalId = canonicalIdentity.id,
                contentType = item.type,
                languageTag = languageTag,
                policyVersion = HOME_OVERLAY_POLICY_VERSION
            ),
            itemKey = itemKey,
            canonicalProvider = canonicalIdentity.provider,
            canonicalId = canonicalIdentity.id,
            imdbId = bundle?.sidecars?.imdbId ?: item.firstPaintStableIds.imdb,
            contentType = item.type,
            languageTag = languageTag,
            policyVersion = HOME_OVERLAY_POLICY_VERSION,
            fields = fields,
            fieldTrace = resolvedDocument.homeFieldTrace(routeProvider),
            displayHash = fields.hydratedHomeDisplayHash(),
            updatedAtMs = nowMs,
            staleAtMs = nowMs + OVERLAY_STALE_MS,
            expiresAtMs = nowMs + OVERLAY_EXPIRES_MS,
            state = HomeItemHydrationState.CANONICAL_READY,
            stableIdsSnapshot = stableIdsSnapshot,
            settingsSignature = settingsSignature
        )
    }

    private fun HomeDisplayMetadata.sanitizeHydratedTitleRating(): HomeDisplayMetadata {
        val cleanRating = RatingValueValidator.sanitizeTitleRating(imdbRating)
        return copy(
            imdbRating = cleanRating,
            ratingSource = ratingSource.takeIf { cleanRating != null }
        )
    }

    private fun HomeDisplayMetadata.withHydratedOnlyArtwork(
        item: MetaPreview,
        document: ResolvedMetadataDocument,
        stableIds: ProviderIds,
        settings: ArtworkProviderSettings
    ): HomeDisplayMetadata {
        val typed = artwork?.enforceArtworkTypeBoundaries()
        val isAnime = item.isAnimeForArtworkRouting()
        val posterFromPreview = document.isPreviewArtwork(ResolvedField.POSTER) ||
            (poster != null && document.poster == null && poster == item.poster) ||
            (document.poster == null && typed?.poster != null && typed.poster == item.artwork?.poster)
        val backdropFromPreview = document.isPreviewArtwork(ResolvedField.BACKDROP) ||
            (backdrop != null && document.backdrop == null && backdrop == item.background) ||
            (document.backdrop == null && typed?.backdrop != null && typed.backdrop == item.artwork?.backdrop)
        val logoFromPreview = document.isPreviewArtwork(ResolvedField.LOGO) ||
            (logo != null && document.logo == null && logo == item.logo) ||
            (document.logo == null && typed?.logo != null && typed.logo == item.artwork?.logo)
        val thumbnailFromPreview = thumbnail != null &&
            typed?.thumbnail == item.artwork?.thumbnail
        val hydratedTyped = ArtworkBundle(
            poster = typed?.poster.takeIf {
                !posterFromPreview && document.acceptsArtworkProvider(
                    type = ArtworkType.POSTER,
                    ref = it,
                    contentType = item.type,
                    isAnime = isAnime,
                    stableIds = stableIds,
                    settings = settings
                )
            },
            backdrop = typed?.backdrop.takeIf {
                !backdropFromPreview && document.acceptsArtworkProvider(
                    type = ArtworkType.BACKDROP,
                    ref = it,
                    contentType = item.type,
                    isAnime = isAnime,
                    stableIds = stableIds,
                    settings = settings
                )
            },
            logo = typed?.logo.takeIf {
                !logoFromPreview && document.acceptsArtworkProvider(
                    type = ArtworkType.LOGO,
                    ref = it,
                    contentType = item.type,
                    isAnime = isAnime,
                    stableIds = stableIds,
                    settings = settings
                )
            },
            thumbnail = typed?.thumbnail.takeIf {
                !thumbnailFromPreview && document.acceptsArtworkProvider(
                    type = ArtworkType.THUMBNAIL,
                    ref = it,
                    contentType = item.type,
                    isAnime = isAnime,
                    stableIds = stableIds,
                    settings = settings
                )
            }
        ).enforceArtworkTypeBoundaries()
        return copy(
            poster = hydratedTyped.poster.toLegacyArtworkString() ?: poster.takeIf {
                !posterFromPreview && document.acceptsArtworkProvider(
                    type = ArtworkType.POSTER,
                    ref = null,
                    contentType = item.type,
                    isAnime = isAnime,
                    stableIds = stableIds,
                    settings = settings
                )
            },
            backdrop = hydratedTyped.backdrop.toLegacyArtworkString() ?: backdrop.takeIf {
                !backdropFromPreview && document.acceptsArtworkProvider(
                    type = ArtworkType.BACKDROP,
                    ref = null,
                    contentType = item.type,
                    isAnime = isAnime,
                    stableIds = stableIds,
                    settings = settings
                )
            },
            logo = hydratedTyped.logo.toLegacyArtworkString() ?: logo.takeIf {
                !logoFromPreview && document.acceptsArtworkProvider(
                    type = ArtworkType.LOGO,
                    ref = null,
                    contentType = item.type,
                    isAnime = isAnime,
                    stableIds = stableIds,
                    settings = settings
                )
            },
            thumbnail = hydratedTyped.thumbnail.toLegacyArtworkString() ?: thumbnail.takeIf {
                !thumbnailFromPreview && document.acceptsArtworkProvider(
                    type = ArtworkType.THUMBNAIL,
                    ref = null,
                    contentType = item.type,
                    isAnime = isAnime,
                    stableIds = stableIds,
                    settings = settings
                )
            },
            artwork = hydratedTyped.takeIf {
                it.poster.takeIfImageType(ArtworkType.POSTER) != null ||
                    it.backdrop.takeIfImageType(ArtworkType.BACKDROP) != null ||
                    it.logo.takeIfImageType(ArtworkType.LOGO) != null ||
                    it.thumbnail.takeIfImageType(ArtworkType.THUMBNAIL) != null
            }
        )
    }

    private fun ResolvedMetadataDocument.isPreviewArtwork(field: ResolvedField): Boolean =
        sourceRoles[field] == SourceRole.RAIL_PREVIEW ||
            sourceRoles[field] == SourceRole.ADDON_PREVIEW

    private fun ResolvedMetadataDocument.acceptsArtworkProvider(
        type: ArtworkType,
        ref: ArtworkDisplayRef?,
        contentType: ContentType,
        isAnime: Boolean,
        stableIds: ProviderIds,
        settings: ArtworkProviderSettings
    ): Boolean {
        val field = type.toResolvedField()
        val actualProvider = ref.providerKey()
            ?: field?.let { sourceProviders[it]?.trim()?.takeIf { value -> value.isNotBlank() } }
            ?: return false
        val preferred = artworkProviderResolver.resolve(
            artworkType = type,
            contentType = contentType,
            isAnime = isAnime,
            availableIds = stableIds,
            settings = settings
        ).key
        if (actualProvider.equals(preferred, ignoreCase = true)) return true
        val stock = ContentTypeDefaults.resolve(type, isAnime).key
        if (actualProvider.equals(stock, ignoreCase = true)) return true
        return field != null &&
            actualProvider.equals("TVDB", ignoreCase = true) &&
            preferred.equals(stock, ignoreCase = true) &&
            type != ArtworkType.POSTER &&
            !isPreviewArtwork(field)
    }

    private fun ArtworkDisplayRef?.providerKey(): String? =
        when (this) {
            is ArtworkDisplayRef.RuntimeAsset -> selectedProvider?.key
                ?: trace.selectedProvider?.trim()?.takeIf { it.isNotBlank() }
            is ArtworkDisplayRef.LegacyString -> trace.selectedProvider?.trim()?.takeIf { it.isNotBlank() }
            is ArtworkDisplayRef.Placeholder -> null
            null -> null
        }

    private fun ArtworkType.toResolvedField(): ResolvedField? =
        when (this) {
            ArtworkType.POSTER -> ResolvedField.POSTER
            ArtworkType.BACKDROP -> ResolvedField.BACKDROP
            ArtworkType.LOGO -> ResolvedField.LOGO
            ArtworkType.THUMBNAIL -> null
        }

    private fun MetaPreview.isAnimeForArtworkRouting(): Boolean =
        apiType.equals("anime", ignoreCase = true) ||
            firstPaintRailSource == RailSource.BUILT_IN_KITSU

    private fun failed(
        itemKey: String,
        trigger: StableIdResolutionTrigger,
        reason: String
    ): HydratedHomeOverlay? {
        traceEvents.emitHomeHydrationFailedUsingPreview(
            itemKey = itemKey,
            reason = reason,
            trigger = trigger.name
        )
        return null
    }

    private fun Exception.toHomeHydrationFailureReason(): String =
        when (this) {
            is MetadataRouteFailure.IdentityResolutionFailed -> "identity_resolution_failed"
            is MetadataRouteFailure.MissingPlanStepAdapter -> "missing_provider_plan_step_adapter"
            is IllegalStateException -> "illegal_state"
            else -> this::class.java.name
                .substringAfterLast('.')
                .takeIf { it.isNotBlank() && !it.matches(OBFUSCATED_CLASS_NAME) }
                ?: "unexpected_exception"
        }

    private suspend fun resolveStableIdBundle(
        route: MetadataRoute?,
        request: MetadataRequest,
        trigger: StableIdResolutionTrigger,
        itemKey: String
    ): StableIdBundleResolution {
        route ?: return StableIdBundleResolution.unavailable()
        return try {
            val bundle = metadataRouterFacade.resolveStableIdBundle(
                route = route,
                request = request,
                trigger = trigger,
                itemKey = itemKey
            )
            StableIdBundleResolution.available(bundle)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            StableIdBundleResolution.unavailable()
        }
    }

    private fun MetaPreview.toMetadataRequest(languageTag: String): MetadataRequest =
        MetadataRequest(
            contentId = id,
            contentType = type,
            sourceContext = MetadataSourceContext(
                itemType = apiType,
                addonMetadata = toHomeDisplayMetadata(),
                previewSourceRole = firstPaintSource.toSourceRole(),
                previewSourceProvider = firstPaintSourceProvider?.name,
                previewStableIds = firstPaintStableIds,
                previewSourceItemId = firstPaintSourceItemId ?: id,
                previewRailSource = firstPaintRailSource?.name
            ),
            language = languageTag,
            depth = MetadataDepth.DETAIL_CORE
        )

    private fun FirstPaintSource.toSourceRole(): SourceRole =
        when (this) {
            FirstPaintSource.ADDON_META_PREVIEW -> SourceRole.ADDON_PREVIEW
            FirstPaintSource.RAIL_PREVIEW -> SourceRole.RAIL_PREVIEW
        }

    private fun canonicalIdentity(
        route: MetadataRoute?,
        document: ResolvedMetadataDocument,
        bundle: StableIdBundle?
    ): CanonicalIdentity? {
        val stableCanonical = bundle?.canonicalIdentity()
        if (stableCanonical?.provider == ProviderId.KITSU || stableCanonical?.provider == ProviderId.TMDB) {
            return stableCanonical
        }
        val routedIdentity = route?.canonicalIdentity(bundle)
        return routedIdentity
            ?: document.canonicalIdentity()
            ?: stableCanonical
    }

    private fun MetadataRoute.canonicalIdentity(bundle: StableIdBundle?): CanonicalIdentity? {
        val canonicalProvider = provider.toProviderId() ?: return null
        val providerNativeId = bundle?.canonical?.providerNativeIdFor(provider)
            ?: targetIds[provider].providerNativeId()
            ?: parentId.providerNativeId()
            ?: return null
        return CanonicalIdentity(canonicalProvider, providerNativeId)
    }

    private fun String?.providerNativeId(): String? =
        this
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.substringAfter(':')
            ?.takeIf { it.isNotBlank() }

    private fun ProviderIds.tmdbOrPreviewId(previewId: String): String? =
        tmdb ?: previewId.takeIf { it.startsWith("tmdb:") }?.providerNativeId()

    private fun ResolvedMetadataDocument.homeFieldTrace(
        fallbackProvider: MetadataPrimaryProvider?
    ): List<HydratedHomeFieldTrace> =
        sourceRoles.map { (field, role) ->
            HydratedHomeFieldTrace(
                field = field.name,
                selectedProvider = sourceProviders[field] ?: fallbackProvider?.name.orEmpty(),
                sourceRole = role.name
            )
        }

    private fun ResolvedMetadataDocument.canonicalIdentity(): CanonicalIdentity? {
        val provider = canonicalId?.substringBefore(':', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
            ?.let { provider ->
                ProviderId.entries.firstOrNull { it.name.equals(provider, ignoreCase = true) }
            } ?: return null
        val id = canonicalId.substringAfter(':', missingDelimiterValue = canonicalId)
            .takeIf { it.isNotBlank() } ?: return null
        return CanonicalIdentity(provider, id)
    }

    private fun StableIdBundle.canonicalIdentity(): CanonicalIdentity? =
        when (itemType) {
            ContentType.SERIES,
            ContentType.TV -> when {
                !canonical.kitsuAnimeId.isNullOrBlank() -> CanonicalIdentity(ProviderId.KITSU, canonical.kitsuAnimeId)
                !canonical.tmdbTvId.isNullOrBlank() -> CanonicalIdentity(ProviderId.TMDB, canonical.tmdbTvId)
                !canonical.tmdbMovieId.isNullOrBlank() -> CanonicalIdentity(ProviderId.TMDB, canonical.tmdbMovieId)
                !canonical.tvdbSeriesId.isNullOrBlank() -> CanonicalIdentity(ProviderId.TVDB, canonical.tvdbSeriesId)
                else -> null
            }
            ContentType.MOVIE -> when {
                !canonical.tmdbMovieId.isNullOrBlank() -> CanonicalIdentity(ProviderId.TMDB, canonical.tmdbMovieId)
                !canonical.tmdbTvId.isNullOrBlank() -> CanonicalIdentity(ProviderId.TMDB, canonical.tmdbTvId)
                !canonical.tvdbSeriesId.isNullOrBlank() -> CanonicalIdentity(ProviderId.TVDB, canonical.tvdbSeriesId)
                !canonical.kitsuAnimeId.isNullOrBlank() -> CanonicalIdentity(ProviderId.KITSU, canonical.kitsuAnimeId)
                else -> null
            }
            else -> when {
                !canonical.kitsuAnimeId.isNullOrBlank() -> CanonicalIdentity(ProviderId.KITSU, canonical.kitsuAnimeId)
                !canonical.tmdbMovieId.isNullOrBlank() && canonical.tmdbTvId.isNullOrBlank() ->
                    CanonicalIdentity(ProviderId.TMDB, canonical.tmdbMovieId)
                !canonical.tmdbTvId.isNullOrBlank() && canonical.tmdbMovieId.isNullOrBlank() ->
                    CanonicalIdentity(ProviderId.TMDB, canonical.tmdbTvId)
                !canonical.tvdbSeriesId.isNullOrBlank() -> CanonicalIdentity(ProviderId.TVDB, canonical.tvdbSeriesId)
                else -> null
            }
        }

    private fun overlayAliases(
        item: MetaPreview,
        bundle: StableIdBundle?,
        overlay: HydratedHomeOverlay,
        itemKey: String
    ): Set<String> {
        val firstPaintIds = item.firstPaintStableIds
        val observedIds = bundle?.source?.observedIds
        val mergedProviderIds = ProviderIds(
            imdb = firstPaintIds.imdb
                ?: observedIds?.imdb
                ?: bundle?.sidecars?.imdbId,
            tmdb = firstPaintIds.tmdb
                ?: observedIds?.tmdb
                ?: bundle?.canonical?.tmdbIdFor(bundle.itemType),
            tvdb = firstPaintIds.tvdb
                ?: observedIds?.tvdb
                ?: bundle?.canonical?.tvdbSeriesId,
            trakt = firstPaintIds.trakt ?: observedIds?.trakt,
            kitsu = firstPaintIds.kitsu
                ?: observedIds?.kitsu
                ?: bundle?.canonical?.kitsuAnimeId
        )
        return HomeArtworkOverlayKeys.aliasesFor(
            rowItemKey = itemKey,
            contentId = item.id,
            itemType = item.apiType,
            providerIds = mergedProviderIds,
            canonicalProvider = overlay.canonicalProvider,
            canonicalId = overlay.canonicalId
        )
    }

    private fun CanonicalStableIds.tmdbIdFor(itemType: ContentType): String? =
        when (itemType) {
            ContentType.MOVIE -> tmdbMovieId ?: tmdbTvId
            ContentType.SERIES,
            ContentType.TV -> tmdbTvId ?: tmdbMovieId
            ContentType.CHANNEL,
            ContentType.PERSON,
            ContentType.UNKNOWN -> singleTypedTmdbIdOrNull()
        }

    private fun CanonicalStableIds.singleTypedTmdbIdOrNull(): String? {
        val movieId = tmdbMovieId?.takeIf { it.isNotBlank() }
        val tvId = tmdbTvId?.takeIf { it.isNotBlank() }
        return when {
            movieId != null && tvId == null -> movieId
            tvId != null && movieId == null -> tvId
            else -> null
        }
    }

    private fun MetadataPrimaryProvider.toProviderId(): ProviderId? =
        when (this) {
            MetadataPrimaryProvider.TMDB -> ProviderId.TMDB
            MetadataPrimaryProvider.TVDB -> ProviderId.TVDB
            MetadataPrimaryProvider.KITSU -> ProviderId.KITSU
            MetadataPrimaryProvider.IMDB -> ProviderId.IMDB
            MetadataPrimaryProvider.TRAKT -> ProviderId.TRAKT
            MetadataPrimaryProvider.SIMKL -> ProviderId.SIMKL
            MetadataPrimaryProvider.RPDB,
            MetadataPrimaryProvider.TOP_POSTERS -> null
        }

    private fun changedFields(
        before: HomeDisplayMetadata,
        after: HomeDisplayMetadata
    ): List<String> = buildList {
        val displayLogoChanged = before.displayLogo != after.displayLogo
        val displayPosterChanged = before.displayPoster != after.displayPoster
        val displayBackdropChanged = before.displayBackdrop != after.displayBackdrop
        if (before.title != after.title) add("title")
        if (displayLogoChanged) add("logo")
        if (before.description != after.description) add("description")
        if (before.genres != after.genres) add("genres")
        if (before.releaseInfo != after.releaseInfo) add("releaseInfo")
        if (before.runtime != after.runtime) add("runtime")
        if (before.imdbRating != after.imdbRating || before.ratingSource != after.ratingSource) add("rating")
        if (before.tomatoesRating != after.tomatoesRating) add("tomatoesRating")
        if (displayPosterChanged) add("poster")
        if (before.posterProviderTag != after.posterProviderTag) add("posterProviderTag")
        if (displayBackdropChanged) add("backdrop")
        if (
            before.artwork != after.artwork &&
            !displayLogoChanged &&
            !displayPosterChanged &&
            !displayBackdropChanged
        ) {
            add("artwork")
        }
    }

    private data class CanonicalIdentity(
        val provider: ProviderId,
        val id: String
    )

    private data class StableIdBundleResolution(
        val bundle: StableIdBundle?,
        val networkExecuted: Boolean,
        val cacheDecision: String
    ) {
        companion object {
            fun available(bundle: StableIdBundle): StableIdBundleResolution {
                val networkExecuted = bundle.evidence.any { it.networkExecuted }
                return StableIdBundleResolution(
                    bundle = bundle,
                    networkExecuted = networkExecuted,
                    cacheDecision = if (networkExecuted) {
                        "MISS_THEN_WRITE"
                    } else {
                        "HIT_OR_LOCAL"
                    }
                )
            }

            fun unavailable(): StableIdBundleResolution =
                StableIdBundleResolution(
                    bundle = null,
                    networkExecuted = false,
                    cacheDecision = "STABLE_ID_UNAVAILABLE"
                )
        }
    }

    private companion object {
        const val HOME_RATING_ARTWORK_TRACE_HASH_PURPOSE = "home-rating-artwork"
        const val WORK_CLASS_BACKGROUND_HYDRATION = "BACKGROUND_HYDRATION"
        const val OVERLAY_STALE_MS = 24L * 60L * 60L * 1000L
        const val OVERLAY_EXPIRES_MS = 7L * 24L * 60L * 60L * 1000L
        val OBFUSCATED_CLASS_NAME = Regex("[a-z]\\d*")
    }
}
