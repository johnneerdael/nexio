package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.metadata.router.FieldResolver
import com.nexio.tv.core.metadata.router.InMemoryAnimeIdentityIndex
import com.nexio.tv.core.metadata.router.InMemoryIdMappingStore
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataIdentityResolver
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRequestNormalizer
import com.nexio.tv.core.metadata.router.MetadataRouter
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderPlanExecutor
import com.nexio.tv.core.metadata.router.ProviderPlanRunner
import com.nexio.tv.core.metadata.router.ResolverOrchestrator
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvdbLanguageMapper
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import kotlinx.coroutines.CancellationException

internal suspend fun overlayProviderLocalizedMetadataForHome(
    item: MetaPreview,
    fallbackContentId: String? = null,
    metadataRouterFacade: MetadataRouterFacade = defaultMetadataRouterFacadeForManualConstruction(),
    profileBoundary: ProfileBoundary,
    onLog: (String, String?) -> Unit = { _, _ -> }
): MetaPreview {
    return try {
        val decision = metadataRouterFacade.fetchTvEnrichment(
            metadataRequest = MetadataRequest(
                contentId = item.id,
                contentType = item.type,
                sourceContext = item.toHomeMetadataSourceContext(),
                language = TvdbLanguageMapper.normalize(profileBoundary.currentLanguageTag()),
                depth = MetadataDepth.DETAIL_CORE
            ),
            tvRequest = TvMetadataRequest(
                contentId = item.id,
                fallbackContentId = fallbackContentId,
                contentType = item.type,
                language = TvdbLanguageMapper.normalize(profileBoundary.currentLanguageTag())
            )
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

fun defaultMetadataRouterFacadeForManualConstruction(): MetadataRouterFacade =
    MetadataRouterFacade(
        router = MetadataRouter(
            normalizer = MetadataRequestNormalizer(),
            animeIdentityIndex = InMemoryAnimeIdentityIndex(),
            idMappingStore = InMemoryIdMappingStore()
        ),
        providerPlanExecutor = ProviderPlanExecutor(),
        resolverOrchestrator = ResolverOrchestrator(),
        identityResolver = MetadataIdentityResolver(object : MetadataIdentityResolver.Lookup {
            override suspend fun tmdbToTvdb(tmdbId: String): String? = null
            override suspend fun tvdbToTmdb(tvdbId: String): String? = null
        }),
        providerPlanRunner = ProviderPlanRunner(emptySet()),
        fieldResolver = FieldResolver()
    )

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
