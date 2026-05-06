package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.core.tvdb.ProviderLocalizedMetadataResolver
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvdbLanguageMapper
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.applyTo
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import kotlinx.coroutines.CancellationException

internal suspend fun overlayProviderLocalizedMetadataForHome(
    item: MetaPreview,
    fallbackContentId: String? = null,
    providerLocalizedMetadataResolver: ProviderLocalizedMetadataResolver,
    profileBoundary: ProfileBoundary,
    onLog: (String, String?) -> Unit = { _, _ -> }
): MetaPreview {
    return try {
        val decision = fetchProviderLocalizedMetadataDecisionForHome(
            item = item,
            fallbackContentId = fallbackContentId,
            providerLocalizedMetadataResolver = providerLocalizedMetadataResolver,
            profileBoundary = profileBoundary
        )
        logHomeProviderDecisionDiagnostics(item, decision.diagnostics, onLog)
        decision.value?.toHomeCompatibilityDisplayMetadata()?.applyToProviderLocalizedHomeItem(item) ?: item
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
    providerLocalizedMetadataResolver: ProviderLocalizedMetadataResolver,
    profileBoundary: ProfileBoundary
): TvMetadataDecision<TvMetadataEnrichment> {
    val language = TvdbLanguageMapper.normalize(profileBoundary.currentLanguageTag()).code
    val metadataRequest = MetadataRequest(
        contentId = item.id,
        contentType = item.type,
        sourceContext = item.toHomeMetadataSourceContext(
            addonMetadata = addonMetadata
        ).let { sourceContext ->
            if (fallbackContentId == null) sourceContext else sourceContext.copy(previewSourceItemId = fallbackContentId)
        },
        language = language,
        depth = MetadataDepth.DETAIL_CORE
    )
    val tvRequest = TvMetadataRequest(
        contentId = item.id,
        fallbackContentId = fallbackContentId,
        contentType = item.type,
        language = language
    )

    return providerLocalizedMetadataResolver.fetchDecision(metadataRequest, tvRequest)
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

private fun TvMetadataEnrichment.toHomeCompatibilityDisplayMetadata(): HomeDisplayMetadata =
    // Compatibility projection for first-paint MetaPreview rows. Canonical hydrated
    // home display still flows through HydratedHomeOverlay and ResolvedDisplaySurfaceRepository.
    HomeDisplayMetadata(
        title = localizedTitle,
        logo = logo,
        description = description,
        imdbRating = rating?.toFloat(),
        ratingSource = ratingSource ?: TitleRatingSource.IMDB,
        poster = poster,
        backdrop = backdrop
    )

private fun HomeDisplayMetadata.applyToProviderLocalizedHomeItem(item: MetaPreview): MetaPreview =
    applyTo(item)
