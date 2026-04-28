package com.nexio.tv.ui.screens.home

import android.util.Log
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.core.tvdb.ProviderMetadataRouter
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvdbLanguageMapper
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import kotlinx.coroutines.CancellationException

private const val HOME_PROVIDER_METADATA_TAG = "HomeProviderMetadata"

internal suspend fun overlayProviderLocalizedMetadataForHome(
    item: MetaPreview,
    fallbackContentId: String? = null,
    metadataRouterFacade: MetadataRouterFacade,
    providerMetadataRouter: ProviderMetadataRouter,
    profileBoundary: ProfileBoundary,
    onLog: (String, String?) -> Unit = { _, _ -> }
): MetaPreview {
    return try {
        val decision = fetchProviderLocalizedMetadataDecisionForHome(
            item = item,
            fallbackContentId = fallbackContentId,
            metadataRouterFacade = metadataRouterFacade,
            providerMetadataRouter = providerMetadataRouter,
            profileBoundary = profileBoundary
        )
        logHomeProviderDecisionDiagnostics(item, decision.diagnostics, onLog)
        val enrichment = decision.value ?: return item

        item.applyTvMetadataEnrichmentForHome(enrichment)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        item
    }
}

internal suspend fun fetchProviderLocalizedMetadataDecisionForHome(
    item: MetaPreview,
    fallbackContentId: String? = null,
    addonMetadata: HomeDisplayMetadata = item.toHomeDisplayMetadata(),
    metadataRouterFacade: MetadataRouterFacade,
    providerMetadataRouter: ProviderMetadataRouter,
    profileBoundary: ProfileBoundary
): TvMetadataDecision<TvMetadataEnrichment> {
    val language = TvdbLanguageMapper.normalize(profileBoundary.currentLanguageTag())
    val metadataRequest = MetadataRequest(
        contentId = item.id,
        contentType = item.type,
        sourceContext = item.toHomeMetadataSourceContext(
            addonMetadata = addonMetadata
        ),
        language = language,
        depth = MetadataDepth.DETAIL_CORE
    )
    val tvRequest = TvMetadataRequest(
        contentId = item.id,
        fallbackContentId = fallbackContentId,
        contentType = item.type,
        language = language
    )

    try {
        metadataRouterFacade.resolveRequest(metadataRequest)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(
            HOME_PROVIDER_METADATA_TAG,
            "Canonical home metadata routing failed for ${item.apiType}:${item.id}: ${e.message}",
            e
        )
    }
    return providerMetadataRouter.fetchEnrichment(tvRequest)
}

internal suspend fun MetadataRouterFacade.resolveHomeRequest(
    item: MetaPreview,
    depth: MetadataDepth,
    language: String? = null,
    seasonNumber: Int? = null
) {
    try {
        resolveRequest(
            MetadataRequest(
                contentId = item.id,
                contentType = item.type,
                sourceContext = item.toHomeMetadataSourceContext(),
                language = language,
                seasonNumber = seasonNumber,
                depth = depth
            )
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Facade sidecar is audit/migration-only here; legacy provider path remains authoritative.
    }
}

internal fun HomeViewModel.metadataRouterFacadeOrNull(): MetadataRouterFacade? =
    runCatching { metadataRouterFacade }.getOrNull()

internal fun HomeViewModel.providerMetadataRouterOrNull(): ProviderMetadataRouter? =
    runCatching { providerMetadataRouter }.getOrNull()

internal suspend fun HomeViewModel.resolveHomeRequestIfAvailable(
    item: MetaPreview,
    depth: MetadataDepth,
    language: String? = null,
    seasonNumber: Int? = null
) {
    metadataRouterFacadeOrNull()?.resolveHomeRequest(
        item = item,
        depth = depth,
        language = language,
        seasonNumber = seasonNumber
    )
}

internal fun MetaPreview.toHomeMetadataSourceContext(
    addonMetadata: HomeDisplayMetadata = toHomeDisplayMetadata()
): MetadataSourceContext =
    MetadataSourceContext(
        itemType = apiType,
        addonMetadata = addonMetadata,
        previewSourceRole = when (firstPaintSource) {
            FirstPaintSource.ADDON_META_PREVIEW -> SourceRole.ADDON_PREVIEW
            FirstPaintSource.RAIL_PREVIEW -> SourceRole.RAIL_PREVIEW
        },
        previewSourceProvider = firstPaintSourceProvider?.name,
        previewStableIds = firstPaintStableIds,
        previewSourceItemId = firstPaintSourceItemId,
        previewRailSource = firstPaintRailSource?.name
    )

private fun logHomeProviderDecisionDiagnostics(
    item: MetaPreview,
    diagnostics: List<com.nexio.tv.core.tvdb.TvMetadataDiagnosticEvent>,
    onLog: (String, String?) -> Unit
) {
    val itemKey = "itemKey=${item.apiType}:${item.id}"
    if (diagnostics.any { it.reason == TvMetadataDecisionReason.TMDB_TV_SKIPPED }) {
        onLog("tmdb_tv_skipped", itemKey)
    }
    if (diagnostics.any { it.reason == TvMetadataDecisionReason.TVDB_FALLBACK_TMDB }) {
        onLog("tvdb_fallback_tmdb", itemKey)
    }
}
