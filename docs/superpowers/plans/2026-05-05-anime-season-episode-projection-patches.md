# Plan Corrections v2 — 2026-05-05-anime-season-episode-projection.md

> **For agentic workers:** Read this file BEFORE reading the main plan. Every section below overrides the corresponding section in the main plan. Where a correction exists here, use this version, not the original.

---

## Correction 1 — Task 0.1: contract test, not failing test

The original Task 0.1 says "write a failing test" but then explains the test probably already passes. That is contradictory.

**Corrected Task 0.1:**

Task 0.1 adds a **contract test expected to PASS** that pins the behaviour we rely on in Phase 0.

```kotlin
// app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceSeasonFilterTest.kt
class KitsuMetadataServiceSeasonFilterTest {

    @Test
    fun `returns all episodes when caller passes empty seasonNumbers`() = runBlocking {
        // ...same code as original Task 0.1 step 1...
    }

    @Test
    fun `still filters when caller passes specific seasonNumbers`() = runBlocking {
        // ...same code as original Task 0.1 step 1...
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.KitsuMetadataServiceSeasonFilterTest`

**Expected: PASS** — the existing KitsuMetadataService.kt:94-99 already handles this correctly. If it fails, fix the service before proceeding. If it passes (expected), commit and move to Task 0.3 immediately.

There is NO Task 0.2 step. The original "conditional fix" task is removed.

---

## Correction 2 — Task 1.11: no KitsuMetadataService call from MetaDetailsViewModel

The original Task 1.11 has `MetaDetailsViewModel` call `kitsuMetadataService.fetchEpisodeEnrichment(...)` directly. **Do not do this.** The ViewModel must not call provider-level services.

**Corrected architecture for Task 1.11:**

Introduce a facade interface:

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonDetailRepository.kt
package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.Meta

interface AnimeSeasonDetailRepository {
    /**
     * Given a Kitsu source identity, resolve which work this belongs to, which seasons
     * exist, which season was the click-source, and hydrate the episode list for the
     * selected season. Returns a hydrated Meta whose videos carry correct (season, episode)
     * keys from Kitsu's franchise-relative numbering.
     */
    suspend fun resolveAndHydrateAnimeDetail(
        sourceKitsuId: String,
        requestedSeason: Int?,
    ): AnimeDetailResult
}

sealed interface AnimeDetailResult {
    data class Success(val meta: Meta, val presentation: AnimeSeasonPresentation) : AnimeDetailResult
    data class Error(val message: String) : AnimeDetailResult
}
```

Add a `DefaultAnimeSeasonDetailRepository` that:
- Calls `AnimeSeasonProjectionResolver.resolveWork(...)`
- Calls `AnimeSeasonProjectionResolver.resolveSeasonPresentation(...)`
- Looks up which Kitsu memberId serves episodes for `presentation.selectedSeason` via `AnimeSeasonTab.episodesKitsuMemberId`
- Fetches episodes from `KitsuMetadataService.fetchEpisodeEnrichment(rawId = "kitsu:$memberId", ...)`
- Returns `Success(hydratedMeta, presentation)` or `Error(...)`

`MetaDetailsViewModel` injects `AnimeSeasonDetailRepository` (not `KitsuMetadataService`), and its `applyAnimeSeasonPresentation` method calls `repository.resolveAndHydrateAnimeDetail(...)`.

**File structure additions:**
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonDetailRepository.kt`
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonDetailRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonDetailRepositoryTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt` — inject `AnimeSeasonDetailRepository`, NOT `KitsuMetadataService`

---

## Correction 3 — Task 1.6: grouping must exclude SPECIAL/OVA/ONA/MUSIC, not only movies

The original `allSeriesRecordsSharingTvdb` filter is:

```kotlin
// WRONG — only excludes mediaType == "movie"
other.tvdb == tvdb && (other.mediaType?.lowercase() ?: "series") != "movie"
```

**Corrected filter:**

```kotlin
fun allSeriesRecordsSharingTvdb(record: AnimeIdMapRecord): List<AnimeIdMapRecord> {
    val tvdb = record.tvdb?.takeIf { it.isNotBlank() } ?: return listOf(record)
    return asset.recordsByKitsu.values.filter { other ->
        other.tvdb == tvdb && isSeriesTvEntry(other)
    }
}

private fun isSeriesTvEntry(record: AnimeIdMapRecord): Boolean {
    val mediaType = record.mediaType?.lowercase() ?: return true
    val sourceType = record.sourceType?.lowercase() ?: ""
    // Exclude everything that is not a main TV series broadcast
    return mediaType == "series" && sourceType in setOf("tv", "")
}
```

This excludes `mediaType=movie`, `sourceType=OVA`, `sourceType=ONA`, `sourceType=SPECIAL`, `sourceType=MUSIC`.

**Additional test to add in Task 1.6:**

```kotlin
@Test
fun `does not group OVA or special into series work even when tvdb matches`() = runBlocking {
    val asset = AnimeIdMapAsset(
        schemaVersion = 1,
        recordsByKitsu = mapOf(
            "11469" to AnimeIdMapRecord("11469", tvdb = "305074", mediaType = "series", sourceType = "TV"),
            "99999" to AnimeIdMapRecord("99999", tvdb = "305074", mediaType = "series", sourceType = "OVA"),
        )
    )
    val service = AnimeIdMappingService(assetProvider = { asset })
    val resolver = DefaultAnimeSeasonProjectionResolver(idMappingService = service)

    val work = resolver.resolveWork(AnimeSourceIdentity(sourceKitsuId = "11469", animeStremioId = null))

    assertTrue("OVA must not be grouped into series work", "99999" !in work.memberKitsuIds)
}
```

---

## Correction 4 — Task 1.12: scrobble must handle all anime-native IDs, not only kitsu:

The original Task 1.12 branches on `AnimeIdSource.KITSU` only. It must handle all anime-native prefixes by first resolving to a Kitsu id via `AnimeIdMappingService`, then projecting.

**Corrected `toTraktItem` routing logic:**

```kotlin
private suspend fun toTraktItem(item: TrackingScrobbleItem): TraktScrobbleItem? {
    val contentId = item.contentId()
    val animeId = AnimeStremioId.parse(contentId)

    // Route ALL anime-native IDs (kitsu:, mal:, anilist:, anidb:) through the projection
    if (animeId != null) {
        val resolvedKitsuId = when (animeId.source) {
            AnimeIdSource.KITSU -> animeId.value
            else -> idMappingService.resolveKitsuId(animeId, ContentMediaKind.SERIES)
        }
        if (resolvedKitsuId == null) {
            rejectionReporter.reportRejection(contentId, ScrobbleRejectionReason.NO_PARSEABLE_IDS, TrackingProvider.TRAKT)
            return null
        }
        return projectAnimeToTraktItem(item, resolvedKitsuId)
    }

    // Non-anime path (imdb, tmdb, tvdb, trakt, numeric)
    val ids = toTraktIds(parseContentIds(contentId))
    if (!ids.hasAnyId()) {
        rejectionReporter.reportRejection(contentId, ScrobbleRejectionReason.NO_PARSEABLE_IDS, TrackingProvider.TRAKT)
        return null
    }
    return when (item) {
        is TrackingScrobbleItem.Movie -> TraktScrobbleItem.Movie(item.title, item.year, ids)
        is TrackingScrobbleItem.Episode -> TraktScrobbleItem.Episode(
            item.showTitle, item.showYear, ids, item.season, item.number, item.episodeTitle
        )
    }
}
```

Inject `AnimeIdMappingService` into `DefaultTrackingScrobbleService`.

**Additional tests required:**

```kotlin
@Test
fun `mal id resolves to kitsu then projects to tvdb for scrobble`() = runBlocking { ... }

@Test
fun `anilist id resolves to kitsu then projects to tvdb for scrobble`() = runBlocking { ... }

@Test
fun `anidb id resolves to kitsu then projects to tvdb for scrobble`() = runBlocking { ... }
```

---

## Correction 5 — Task 1.8: flat-Kitsu detection must use resource role, not episode > 50

The original `isFlat` check in `computeEpisodeProjection`:

```kotlin
// WRONG — gates on episode number which misses S1E1..E50 for flat series
isFlat = work.memberKitsuIds.size == 1 && sourceEpisode.season == 1 && sourceEpisode.episode > 50
```

**Corrected: derive flat-franchise from the AnimeSeasonPresentation source type.**

`computeEpisodeProjection` should accept a `isFlatFranchise: Boolean` parameter that the resolver determines from the season presentation source. Alternatively, `AnimeEpisodeProjection` carries `confidence = LOW` and `fallbackReason = LOW_CONFIDENCE_FLAT_KITSU` for the entire resource, not per-episode.

**Corrected approach:**

1. In `resolveSeasonPresentation`, detect flat franchise:
```kotlin
val isFlatFranchise = seasonToMember.size == 1
    && (perMember[cleanSourceId]?.size ?: 0) >= FLAT_KITSU_MIN_EPISODES
```

2. Store on `AnimeSeasonPresentation.source == SeasonPresentationSource.KITSU_FLAT_FALLBACK` (already exists).

3. In `resolveEpisodeProjection`, check presentation source from the resolver's own cache:
```kotlin
// Fetch the presentation to determine if the source is flat-franchise
val presentation = resolveSeasonPresentation(work, sourceEpisode.sourceKitsuId, requestedSeason = null)
val isFlatFranchise = presentation.source == SeasonPresentationSource.KITSU_FLAT_FALLBACK
```

4. If `isFlatFranchise`, set `scrobbleCoordinate = null` and `fallbackReason = LOW_CONFIDENCE_FLAT_KITSU` for **all** episodes in the resource, not only those with `episode > 50`.

**Test correction for Task 1.8:**

```kotlin
@Test
fun `One Piece S1E1 is also rejected for scrobble when resource is flat franchise`() = runBlocking {
    // ... One Piece setup ...
    val projection = resolver.resolveEpisodeProjection(work, SourceEpisodeCoordinate("12", 1, 1), EpisodeProjectionTarget.TRAKT_SCROBBLE)
    assertNull("S1E1 must also be rejected for flat franchise", projection.scrobbleCoordinate)
    assertEquals(FallbackReason.LOW_CONFIDENCE_FLAT_KITSU, projection.fallbackReason)
}
```

---

## Correction 6 — AnimeEpisodeProjection: add targetCoordinate for CONTINUE_WATCHING dedupe

The original `AnimeEpisodeProjection` in Task 1.4 sets `displayCoordinate` based on `target == UI_DISPLAY` only.

For `CONTINUE_WATCHING`, the existing code leaves `displayCoordinate = sourceKitsuCoord` which means CW cannot dedupe against TVDB/Trakt entries.

**Add `targetCoordinate` field to `AnimeEpisodeProjection`:**

```kotlin
data class AnimeEpisodeProjection(
    val sourceKitsuId: String,
    val sourceKitsuCoordinate: EpisodeCoordinate,
    val displayCoordinate: EpisodeCoordinate,
    val targetCoordinate: EpisodeCoordinate?,   // NEW — the canonical coord for the requested target
    val scrobbleCoordinate: EpisodeCoordinate?,
    val premiumArtworkCoordinate: EpisodeCoordinate?,
    val tvdbCoordinate: EpisodeCoordinate?,
    val tmdbCoordinate: EpisodeCoordinate?,
    val confidence: CoordinateConfidence,
    val fallbackReason: FallbackReason?,
    val evidence: List<String>,
)
```

In `computeEpisodeProjection`, set `targetCoordinate`:

```kotlin
val targetCoordinate = when (target) {
    EpisodeProjectionTarget.UI_DISPLAY -> tvdbCoord ?: sourceKitsuCoord
    EpisodeProjectionTarget.TRAKT_SCROBBLE,
    EpisodeProjectionTarget.SIMKL_SCROBBLE -> scrobbleCoord
    EpisodeProjectionTarget.PREMIUM_THUMBNAIL -> artworkCoord
    EpisodeProjectionTarget.CONTINUE_WATCHING -> tvdbCoord ?: tmdbCoord ?: sourceKitsuCoord
    EpisodeProjectionTarget.EPISODE_RATING -> tvdbCoord ?: tmdbCoord
}
```

Task 1.14 uses `projection.targetCoordinate?.identityKey ?: projection.sourceKitsuCoordinate.identityKey` for CW identity.

---

## Correction 7 — Task 1.13: Top-Posters must use anime-route awareness, not string prefix

The original says "when source contentId is a Kitsu id". That misses `mal:`, `anilist:`, `anidb:`, and content that arrived from Kitsu rail but was re-resolved to an `imdb:` id upstream.

**Corrected trigger condition for Top-Posters episode thumbnail projection:**

Use the route provider as the signal, not the content ID string:

```kotlin
val shouldUseAnimeProjection = route?.provider == MetadataPrimaryProvider.KITSU
    || AnimeStremioId.parse(sourceContentId) != null
```

If `shouldUseAnimeProjection`:
1. Resolve `AnimeSourceIdentity` from the content ID
2. Call `animeSeasonProjectionResolver.resolveEpisodeProjection(target = PREMIUM_THUMBNAIL)`
3. Use `projection.premiumArtworkCoordinate` for the thumbnail request
4. If `premiumArtworkCoordinate == null` (low confidence), return `null` from adapter → PosterRatingsUrlResolver falls back to primary thumbnail (preserving rating overlay)

If NOT `shouldUseAnimeProjection`, use existing path (pass season/episode through unchanged).

---

## Correction 8 — Task 1.15: trace events must have concrete payload shapes

The original Task 1.15 leaves emit bodies as `/* sink.emit(...) */`. Every event must be concrete.

**Required event payloads:**

```kotlin
// anime.work_resolved
data class AnimeWorkResolvedEvent(
    val sourceKitsuId: String,
    val groupKey: String,           // e.g. "anime-work:tvdb:305074"
    val memberCount: Int,
    val confidence: String,         // "HIGH" | "MEDIUM" | "LOW"
    val evidence: List<String>,
)

// anime.season_projection_built
data class AnimeSeasonProjectionBuiltEvent(
    val groupKey: String,
    val selectedSeason: Int,
    val seasonCount: Int,
    val source: String,             // "KITSU_SEASON_NUMBERS" | "KITSU_FLAT_FALLBACK" etc.
    val confidence: String,
)

// anime.episode_coordinate_resolved
data class AnimeEpisodeCoordinateResolvedEvent(
    val sourceKitsuId: String,
    val sourceSeason: Int,
    val sourceEpisode: Int,
    val targetProvider: String,     // "TVDB" | "TMDB"
    val targetSeriesId: String,
    val targetSeason: Int,
    val targetEpisode: Int,
    val confidence: String,
    val uses: List<String>,         // e.g. ["TRAKT_SCROBBLE", "PREMIUM_THUMBNAIL"]
    val evidence: List<String>,
)

// anime.episode_coordinate_unresolved
data class AnimeEpisodeCoordinateUnresolvedEvent(
    val sourceKitsuId: String,
    val sourceSeason: Int,
    val sourceEpisode: Int,
    val target: String,
    val fallbackReason: String,
    val confidence: String,
)

// tracking.scrobble_rejected
data class ScrobbleRejectedEvent(
    val contentId: String,
    val reason: String,             // ScrobbleRejectionReason.name
    val provider: String,           // TrackingProvider.name
)

// premium.thumbnail_coordinate_selected
data class PremiumThumbnailCoordinateSelectedEvent(
    val sourceKitsuId: String,
    val selectedProvider: String?,  // null when falling back to primary
    val selectedSeriesId: String?,
    val selectedSeason: Int?,
    val selectedEpisode: Int?,
    val confidence: String,
    val fallbackToPrimary: Boolean,
)
```

Each `emit*` method in `AnimeProjectionTraceEvents` must call through to `TraceMetadataEvents.sink.emit(eventType, payload)` using the project's existing trace emission pattern (grep `traceEvents.emit` in MetadataRouterFacade.kt or TraceMetadataEvents.kt for the exact call signature).

---

## Execution sequencing reminder

1. Phase 0 (Tasks 0.1, 0.3+0.4, 0.6+0.7+0.8, 0.9) — execute now on `feature/anime-season-projection-phase0`
2. Phase 1 foundation (Tasks 1.1–1.5 models + 1.6 resolveWork + 1.9 store + 1.10 Hilt) — implement after Phase 0 merges
3. Phase 1 integrations (Tasks 1.7 resolveSeasonPresentation + 1.8 resolveEpisodeProjection + 1.11 detail + 1.12 scrobble + 1.13 posters + 1.14 CW + 1.15 trace) — implement in parallel by integration surface per original plan subagent assignment
4. Phase 0 must NOT call `AnimeSeasonDetailRepository` or `AnimeSeasonProjectionResolver` — those belong to Phase 1
