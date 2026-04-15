---
phase: 07-tvdb-provider-replacement
reviewed: 2026-04-15T04:36:20Z
depth: standard
files_reviewed: 26
files_reviewed_list:
  - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataDiagnostics.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt
  - app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt
  - app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt
  - app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt
  - app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt
  - app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt
  - app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt
  - app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt
  - app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt
  - app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsScreen.kt
  - app/src/main/res/values/strings.xml
  - app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt
  - app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataModelsTest.kt
  - app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt
  - app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt
  - app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTvdbTest.kt
  - app/src/test/java/com/nexio/tv/ui/screens/detail/MarkSeasonWatchedTest.kt
  - app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbProviderRoutingTest.kt
  - app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelTestFactory.kt
  - app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTvdbTest.kt
  - app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingTest.kt
  - app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt
  - app/src/test/java/com/nexio/tv/ui/screens/settings/ProviderPrecedenceCopyTest.kt
findings:
  critical: 0
  warning: 3
  info: 0
  total: 3
status: issues_found
---

# Phase 07: Code Review Report

**Reviewed:** 2026-04-15T04:36:20Z
**Depth:** standard
**Files Reviewed:** 26
**Status:** issues_found

## Summary

Reviewed the TVDB provider replacement paths across router, service, home/detail call sites, disk cache, settings UI, and tests. The main risks are provider-precedence regressions: continue-watching enrichment is still gated by TMDB settings, while the shared router can still use TMDB when the TMDB toggle is disabled. There is also a TVDB season episode cache bug that can persist transient request failures as empty seasons.

## Warnings

### WR-01: TVDB-only continue-watching enrichment never runs

**File:** `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:64`
**Issue:** `loadContinueWatchingPipeline` only enriches continue-watching rows when `currentTmdbSettings.isActive && useBasicInfo` is true. The same gate is repeated in `enrichContinueWatchingWithCurrentSettings` at line 519. With TVDB enabled and TMDB disabled or missing an API key, the new TVDB router is never called for continue-watching items, so TVDB titles, artwork, release info, runtime, and localized episode descriptions do not apply on that rail. Existing tests cover the "TVDB succeeds and TMDB is active" path, but not the TVDB-only configuration.
**Fix:**
```kotlin
// Track TVDB settings separately from TMDB settings, then allow TVDB to drive TV rows.
val hasTvRows = items.any { isSeriesType(it.contentType()) } ||
    traktUpNextItems.any { isSeriesType(it.contentType()) }
val shouldEnrichProviderMetadata =
    (settings.isActive && settings.useBasicInfo) ||
        (currentTvdbSettings.isActive && hasTvRows)

if (shouldEnrichProviderMetadata) {
    continueWatchingEnrichmentJob?.cancel()
    continueWatchingEnrichmentJob = viewModelScope.launch {
        val enrichedItems = enrichContinueWatchingItems(items, settings)
        val enrichedTraktItems = enrichContinueWatchingNextUpItems(traktUpNextItems, settings)
        _uiState.update { it.copy(continueWatchingItems = enrichedItems, traktUpNextItems = enrichedTraktItems) }
    }
}
```
Add a regression test where `TmdbSettings(enabled = false, apiKey = "")`, TVDB returns `TVDB_SUCCESS`, and `enrichContinueWatchingItemWithProvider` or the snapshot pipeline still applies TVDB metadata without calling TMDB.

### WR-02: TMDB fallback ignores the TMDB enabled toggle

**File:** `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt:154`
**Issue:** The router falls back to TMDB whenever TVDB is inactive, identity is missing, or the TVDB record is missing, but it never checks whether TMDB enrichment is enabled. `resolveTmdbId` then calls `tmdbService.ensureTmdbId` and `fetchTmdbEnrichment` calls `tmdbMetadataService.fetchEnrichment`; that service only requires a stored API key, not `TmdbSettings.enabled`. A user who disabled TMDB but still has an API key saved can still get TMDB TV metadata through the TVDB router from detail, home focus, catalog overlay, runtime warmup, and season-marking flows. That creates the accidental duplicate TMDB TV metadata path this replacement is trying to remove.
**Fix:**
```kotlin
private suspend fun canUseTmdbFallback(): Boolean {
    return tmdbSettingsDataStore.settings.first().isActive
}

private suspend fun fetchTmdbEnrichment(
    request: TvMetadataRequest,
    diagnostics: List<TvMetadataDiagnosticEvent>,
    reason: TvMetadataDecisionReason
): TvMetadataDecision<TvMetadataEnrichment> {
    if (!canUseTmdbFallback()) {
        return TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = reason,
            value = null,
            diagnostics = diagnostics
        )
    }
    val tmdbId = resolveTmdbId(request) ?: return TvMetadataDecision(TvProvider.TMDB, reason, null, diagnostics)
    val tmdb = tmdbMetadataService.fetchEnrichment(tmdbId, request.contentType, request.language)
    return TvMetadataDecision(TvProvider.TMDB, reason, tmdb?.toTvMetadataEnrichment(), diagnostics)
}
```
Apply the same guard to episode and season fallback helpers, then add router tests for `TVDB_INACTIVE + TMDB disabled` and `TVDB_RECORD_MISSING + TMDB disabled` that verify no TMDB service calls happen.

### WR-03: Failed TVDB season requests are cached as empty seasons

**File:** `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt:122`
**Issue:** `fetchSeasonEpisodes` collapses exceptions and non-success HTTP responses into `emptyList()` via `.getOrNull()?.takeIf { response.isSuccessful }?.body()?.data.orEmpty()`, then unconditionally writes that mapped list to `writeTvdbSeasonEpisodes` at lines 143-149. A transient network failure, 401, 429, or 5xx response therefore caches an empty TVDB season. Future calls read the empty list from disk, skip the TVDB network request, and the router falls back to TMDB indefinitely for that season until cache invalidation.
**Fix:**
```kotlin
val response = runCatching {
    tvdbApi.getSeriesEpisodes(
        authorization = authorization,
        id = identity.tvdbId,
        seasonType = DEFAULT_SEASON_TYPE,
        page = 0,
        season = seasonNumber
    )
}.onFailure { error ->
    Log.w(TAG, "TVDB season metadata request failed reason=${error.javaClass.simpleName}")
}.getOrNull() ?: return@withContext emptyList()

if (!response.isSuccessful) {
    Log.w(TAG, "TVDB season metadata request failed status=${response.code()}")
    return@withContext emptyList()
}

val records = response.body()?.data.orEmpty()
val mapped = records
    .map { record -> record.toEpisodeMetadata() }
    .filter { metadata -> metadata.seasonNumber == seasonNumber }
    .sortedWith(compareBy<TvEpisodeMetadata> { it.episodeNumber ?: Int.MAX_VALUE }.thenBy { it.providerEpisodeId })

metadataDiskCacheStore.writeTvdbSeasonEpisodes(
    seriesId = identity.tvdbId,
    seasonType = DEFAULT_SEASON_TYPE,
    seasonNumber = seasonNumber,
    languageTag = normalizedLanguage,
    episodes = mapped
)
```
Add tests that a thrown exception and a non-2xx response return an empty result without writing `writeTvdbSeasonEpisodes`, while a successful 200 with an empty body can still cache an intentionally empty season if that behavior is desired.

---

_Reviewed: 2026-04-15T04:36:20Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
