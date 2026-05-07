package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouteFailure
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

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
    private val traceEvents: TraceMetadataEvents
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
        val itemKey = item.homeOverlayItemKey()
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
            val overlay = result.toHydratedHomeOverlay(
                item = item,
                itemKey = itemKey,
                bundle = bundle,
                languageTag = languageTag
            ) ?: return failed(itemKey, trigger, "canonical_identity_unresolved")

            if (currentGeneration() != expectedGeneration) {
                traceEvents.emitHomeHydrationIgnored(
                    itemKey = itemKey,
                    reason = "generation_mismatch",
                    trigger = trigger.name
                )
                return null
            }

            overlayStore.upsert(
                overlay = overlay,
                aliases = overlayAliases(item = item, bundle = bundle, itemKey = itemKey)
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
                changedFields = changedFields(item.toHomeDisplayMetadata(), overlay.fields),
                displayHashBefore = item.displayHashForHomeOverlay(),
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
        languageTag: String
    ): HydratedHomeOverlay? {
        val routeProvider = route?.provider
        val canonicalIdentity = canonicalIdentity(route, resolvedDocument, bundle) ?: return null
        val fields = displayMetadata.mergeHydratedArtworkWithFirstPaintFallback(item.artwork)
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
            state = HomeItemHydrationState.CANONICAL_READY
        )
    }

    private fun HomeDisplayMetadata.mergeHydratedArtworkWithFirstPaintFallback(
        firstPaintArtwork: ArtworkBundle?
    ): HomeDisplayMetadata {
        if (firstPaintArtwork == null) return this
        val hydratedArtwork = artwork
        val merged = ArtworkBundle(
            poster = hydratedArtwork?.poster ?: firstPaintArtwork.poster.takeIf { displayPoster == null },
            backdrop = hydratedArtwork?.backdrop ?: firstPaintArtwork.backdrop.takeIf { displayBackdrop == null },
            logo = hydratedArtwork?.logo ?: firstPaintArtwork.logo.takeIf { displayLogo == null },
            thumbnail = hydratedArtwork?.thumbnail ?: firstPaintArtwork.thumbnail.takeIf { displayThumbnail == null }
        )
        val mergedOrNull = merged.takeUnless {
            it.poster == null &&
                it.backdrop == null &&
                it.logo == null &&
                it.thumbnail == null
        }
        return copy(artwork = mergedOrNull)
    }

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
        val routedIdentity = route?.canonicalIdentity(bundle)
        return routedIdentity
            ?: document.canonicalIdentity()
            ?: bundle?.canonicalIdentity()
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
        when {
            !canonical.tmdbMovieId.isNullOrBlank() -> CanonicalIdentity(ProviderId.TMDB, canonical.tmdbMovieId)
            !canonical.tvdbSeriesId.isNullOrBlank() -> CanonicalIdentity(ProviderId.TVDB, canonical.tvdbSeriesId)
            !canonical.kitsuAnimeId.isNullOrBlank() -> CanonicalIdentity(ProviderId.KITSU, canonical.kitsuAnimeId)
            else -> null
        }

    private fun overlayAliases(
        item: MetaPreview,
        bundle: StableIdBundle?,
        itemKey: String
    ): Set<String> = buildSet {
        add(itemKey)
        addStableAliases(item.apiType, item.firstPaintStableIds)
        bundle?.source?.observedIds?.let { addStableAliases(item.apiType, it) }
        addStableAliases(
            item.apiType,
            ProviderIds(
                imdb = bundle?.sidecars?.imdbId,
                tmdb = bundle?.canonical?.tmdbMovieId,
                tvdb = bundle?.canonical?.tvdbSeriesId,
                kitsu = bundle?.canonical?.kitsuAnimeId
            )
        )
    }

    private fun MutableSet<String>.addStableAliases(type: String, ids: ProviderIds) {
        ids.imdb?.takeIf { it.isNotBlank() }?.let { add("$type:imdb:$it") }
        ids.tmdb?.takeIf { it.isNotBlank() }?.let { add("$type:tmdb:$it") }
        ids.tvdb?.takeIf { it.isNotBlank() }?.let { add("$type:tvdb:$it") }
        ids.kitsu?.takeIf { it.isNotBlank() }?.let { add("$type:kitsu:$it") }
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
        const val HOME_OVERLAY_POLICY_VERSION = 1
        const val WORK_CLASS_BACKGROUND_HYDRATION = "BACKGROUND_HYDRATION"
        const val OVERLAY_STALE_MS = 24L * 60L * 60L * 1000L
        const val OVERLAY_EXPIRES_MS = 7L * 24L * 60L * 60L * 1000L
        val OBFUSCATED_CLASS_NAME = Regex("[a-z]\\d*")
    }
}
