package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.metadata.router.InMemoryAnimeIdentityIndex
import com.nexio.tv.core.metadata.router.InMemoryIdMappingStore
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRequestNormalizer
import com.nexio.tv.core.metadata.router.MetadataRouter
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderPlanExecutor
import com.nexio.tv.core.metadata.router.ResolverOrchestrator
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.core.tvdb.ProviderMetadataRouter
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvdbLanguageMapper
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal suspend fun overlayProviderLocalizedMetadataForHome(
    item: MetaPreview,
    fallbackContentId: String? = null,
    tvMetadataRouter: ProviderMetadataRouter,
    metadataRouterFacade: MetadataRouterFacade = defaultMetadataRouterFacadeForManualConstruction(),
    tmdbSettingsDataStore: TmdbSettingsDataStore,
    tmdbService: TmdbService,
    tmdbMetadataService: TmdbMetadataService,
    profileBoundary: ProfileBoundary,
    onLog: (String, String?) -> Unit = { _, _ -> }
): MetaPreview {
    return try {
        metadataRouterFacade.resolveHomeRequest(item = item, depth = MetadataDepth.PREVIEW)
        val enrichment = if (item.type.isHomeProviderTvContent()) {
            val decision = tvMetadataRouter.fetchEnrichment(
                TvMetadataRequest(
                    contentId = item.id,
                    fallbackContentId = fallbackContentId,
                    contentType = item.type,
                    language = TvdbLanguageMapper.normalize(profileBoundary.currentLanguageTag())
                )
            )
            logHomeProviderDecisionDiagnostics(item, decision.diagnostics, onLog)
            decision.value
        } else {
            val apiKey = tmdbSettingsDataStore.settings.first().apiKey.trim()
            if (apiKey.isEmpty()) return item
            val tmdbId = resolveHomeProviderTmdbId(
                item = item,
                fallbackContentId = fallbackContentId,
                tmdbService = tmdbService
            ) ?: return item
            tmdbMetadataService.fetchEnrichment(
                tmdbId = tmdbId,
                contentType = item.type
            )?.toHomeTvMetadataEnrichment()
        } ?: return item

        item.applyTvMetadataEnrichmentForHome(enrichment)
    } catch (_: Throwable) {
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
        resolverOrchestrator = ResolverOrchestrator()
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
                sourceContext = MetadataSourceContext(
                    itemType = item.apiType,
                    addonMetadata = item.toHomeDisplayMetadata()
                ),
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

private suspend fun resolveHomeProviderTmdbId(
    item: MetaPreview,
    fallbackContentId: String?,
    tmdbService: TmdbService
): String? {
    val candidates = listOfNotNull(
        item.id.takeIf { it.isNotBlank() },
        fallbackContentId?.takeIf { it.isNotBlank() }
    ).distinct()
    candidates.forEach { candidate ->
        tmdbService.ensureTmdbId(candidate, item.apiType)?.let { return it }
    }
    return null
}

internal fun TmdbEnrichment.toHomeTvMetadataEnrichment(): TvMetadataEnrichment {
    return TvMetadataEnrichment(
        seriesTvdbId = null,
        localizedTitle = localizedTitle,
        description = description,
        genres = genres,
        backdrop = backdrop,
        logo = logo,
        poster = poster,
        releaseInfo = releaseInfo,
        rating = rating,
        runtimeMinutes = runtimeMinutes,
        ageRating = ageRating,
        countries = countries,
        language = language
    )
}

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

private fun ContentType.isHomeProviderTvContent(): Boolean = this == ContentType.SERIES || this == ContentType.TV
