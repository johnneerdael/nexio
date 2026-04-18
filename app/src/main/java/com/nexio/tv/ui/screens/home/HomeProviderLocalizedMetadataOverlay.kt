package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvdbLanguageMapper
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import kotlinx.coroutines.flow.first

internal suspend fun overlayProviderLocalizedMetadataForHome(
    item: MetaPreview,
    fallbackContentId: String? = null,
    tvMetadataRouter: TvMetadataRouter,
    tmdbSettingsDataStore: TmdbSettingsDataStore,
    tmdbService: TmdbService,
    tmdbMetadataService: TmdbMetadataService,
    profileBoundary: ProfileBoundary,
    onLog: (String, String?) -> Unit = { _, _ -> }
): MetaPreview {
    return try {
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
