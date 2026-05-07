# Anime Season/Episode Projection Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the immediate "Episode metadata is unavailable" failure for Kitsu seasonal anime and the silent-no-op Trakt scrobble for anime-native content IDs, then add a coordinate-projection layer so detail UI, scrobble, and premium thumbnails can use provider-correct seasons while Kitsu remains the metadata authority.

**Architecture:** Two-stage rollout. Phase 0 ships a tightly-scoped hot-fix that removes the season-1 default from `MetadataRouterFacade`/`KitsuMetadataService` and replaces silent scrobble drops with visible reject events. Phase 1 introduces an `AnimeSeasonProjectionResolver` that owns coordinate translation (Kitsu source → TVDB/TMDB/Trakt projection) for detail UI, Trakt/SIMKL scrobble, Top-Posters thumbnails, and Continue Watching, without changing Kitsu's role as primary anime metadata authority.

**Tech Stack:** Kotlin (JDK 17), Jetpack Compose, Hilt, Retrofit, Moshi, Kotlin coroutines, JUnit 4. Tests live in `app/src/test/java/...` mirroring the source path. Project conventions: `org.junit.Test`, `org.junit.Assert.*`, backtick test names, constructor lambda injection for unit-testable services.

---

## Analysis: is the proposed solution correct?

**Yes, with refinements.** The proposal correctly diagnoses the bug as a coordinate-system mismatch rather than a single missing feature, and proposes a layered fix that addresses every root cause from the 2026-05-05 RCA without breaking the existing primary-authority model:

| RCA root cause | Addressed by |
|---|---|
| RC1 — Kitsu rail 1:1 mapping shows MHA S1, S2, S3 as separate cards | Phase 2 (out of scope of this plan; needs `KitsuRailFranchiseGrouper` — see "Out of scope" below) |
| RC2 — `AnimeIdMapRecord` has no parent / season disambiguator | Phase 1 (`AnimeWorkIdentity` derives the work group from shared `tvdb`/`imdb` ids; Phase 3 adds curated overlay for hard cases like One Piece) |
| RC3 — Season-1 default + Kitsu's `seasonNumber=3` filter mismatch | Phase 0 (immediate fix) + Phase 1 (`AnimeSeasonPresentation` uses Kitsu's actual season tags) |
| RC4 — Trakt scrobble silently drops `kitsu:`/`mal:`/`anilist:`/`anidb:` | Phase 0 (visible reject) + Phase 1 (route through projection resolver to produce TVDB/IMDb/TMDB ids) |
| RC5 — `season=1, episode=850` for One Piece on Trakt | Phase 1 (refuse-with-trace when projection confidence is low) + Phase 3 (curated overlay) |

**Strengths of the proposed architecture:**

1. Decouples coordinate systems explicitly. The bug is one integer being asked to mean two different things; carrying multiple coordinates with provenance prevents the entire class.
2. Refusing to send a wrong Trakt scrobble is better than sending a wrong one. `tracking.scrobble_rejected` makes the failure observable instead of silent.
3. Acknowledges Fribb's data limits — Phase 3 makes the overlay-data problem explicit instead of pretending we can derive seasons from existing IDs.

**Concerns to address in the plan (incorporated below):**

- **Grouping safety:** the proposal's `AnimeWorkIdentity` grouping by shared `tvdb`/`imdb` would incorrectly include movies (the MHA tvdb=305074 group already includes 8 movies with distinct imdb ids). Plan must hard-guard on `mediaType=series` AND `subtype=TV` before grouping; movies and specials remain separate.
- **Click-source-aware default season:** when the user clicks `kitsu:13881` (MHA S3) from a rail, the detail screen must default the season tab to the detected season of that specific Kitsu sub-record (3), not "first unwatched / latest". Plan models this via `AnimeSourceIdentity.sourceKitsuId`.
- **Per-season Kitsu source mapping:** `AnimeSeasonTab` must carry which Kitsu memberId provides episodes for that season (S1=11469, S2=12268, S3=13881...). Otherwise the detail screen can't fetch episodes when the user switches tabs.
- **Phase 0 ships separately as a hot-fix.** The season-filter bug breaks every multi-cour anime today. The plan keeps Phase 0 commits self-contained so they can be cherry-picked to a hot-fix branch independently of Phase 1.

---

## Out of scope of this plan

The full proposal covers four phases. This plan covers **Phase 0 + Phase 1**. The other two are deferred to separate plans because they have different shipping criteria:

- **Phase 2 — `KitsuRailFranchiseGrouper`** (rail card deduplication). Independent UI concern; depends only on `AnimeWorkIdentity` from Phase 1 plus a UI safety bar. Should be its own plan: `docs/superpowers/plans/YYYY-MM-DD-kitsu-rail-franchise-grouping.md`.
- **Phase 3 — Curated season-mapping overlay** (One Piece, Naruto, Bleach, …). Data sourcing problem; depends on choosing an upstream feed (Trakt anime mappings, manami-project, or a manual JSON overlay) and standing up a generation pipeline. Should be its own plan: `docs/superpowers/plans/YYYY-MM-DD-anime-season-mapping-overlay.md`. Until Phase 3 ships, the projection resolver returns `LOW` confidence for flat Kitsu series and Trakt scrobble is rejected with `ANIME_COORDINATE_UNRESOLVED` rather than sending wrong coordinates.

---

## File structure

### Phase 0 — modify

- `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt` — change `fetchEpisodeEnrichment` to not filter when `seasonNumbers` is empty.
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt` — change `fetchTvEpisodeEnrichment` (line 648) and `fetchEpisodeMetadataForRoute` (line 715) to not default to `1`.
- `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt` — replace silent `return null` in `toTraktItem` with a visible reject through a new `ScrobbleRejectionReporter`.

### Phase 0 — create

- `app/src/main/java/com/nexio/tv/data/repository/ScrobbleRejectionReporter.kt` — emits `tracking.scrobble_rejected` events.
- `app/src/main/java/com/nexio/tv/data/repository/ScrobbleRejectionReason.kt` — enum with reasons, including `ANIME_COORDINATE_UNRESOLVED`, `NO_PARSEABLE_IDS`.
- `app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceSeasonFilterTest.kt`
- `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeSeasonDefaultTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleServiceAnimeRejectionTest.kt`

### Phase 1 — create (domain models)

- `app/src/main/java/com/nexio/tv/core/anime/projection/EpisodeCoordinate.kt`
- `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeWorkIdentity.kt`
- `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeResourceDescriptor.kt`
- `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeEpisodeProjection.kt`
- `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonPresentation.kt`
- `app/src/main/java/com/nexio/tv/core/anime/projection/SourceEpisodeCoordinate.kt`
- `app/src/main/java/com/nexio/tv/core/anime/projection/CoordinateConfidence.kt`

### Phase 1 — create (resolver + store)

- `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonProjectionResolver.kt` — interface.
- `app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt` — implementation using Kitsu episode payloads + `AnimeIdMappingService`.
- `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeEpisodeCoordinateStore.kt` — interface for cached projections.
- `app/src/main/java/com/nexio/tv/core/anime/projection/InMemoryAnimeEpisodeCoordinateStore.kt` — initial impl.
- `app/src/main/java/com/nexio/tv/core/trace/AnimeProjectionTraceEvents.kt` — emits `anime.work_resolved`, `anime.season_projection_built`, `anime.episode_coordinate_resolved`, `anime.episode_coordinate_unresolved`, `tracking.scrobble_rejected`, `premium.thumbnail_coordinate_selected`.
- `app/src/main/java/com/nexio/tv/core/di/AnimeProjectionModule.kt` — Hilt wiring.

### Phase 1 — modify (integrations)

- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt` — replace ad-hoc `applyTvEpisodeEnrichment` Kitsu path with `AnimeSeasonProjectionResolver.resolveSeasonPresentation` for anime; default selected season from `AnimeSourceIdentity.sourceKitsuId` detected season.
- `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt` — for `kitsu:`/`mal:`/`anilist:`/`anidb:` content ids, route through `AnimeSeasonProjectionResolver.resolveEpisodeProjection(target=TRAKT_SCROBBLE)` before envelope construction.
- `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapter.kt` — for episode thumbnails, route through `resolveEpisodeProjection(target=PREMIUM_THUMBNAIL)`.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt` — for anime continue-watching items, route through `resolveEpisodeProjection(target=CONTINUE_WATCHING)` for identity stability.

### Phase 1 — create (tests)

- `app/src/test/java/com/nexio/tv/core/anime/projection/EpisodeCoordinateTest.kt`
- `app/src/test/java/com/nexio/tv/core/anime/projection/AnimeWorkIdentityTest.kt`
- `app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverTest.kt`
- `app/src/test/java/com/nexio/tv/core/anime/projection/InMemoryAnimeEpisodeCoordinateStoreTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleServiceAnimeProjectionTest.kt`
- `app/src/test/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapterAnimeProjectionTest.kt`

---

# Phase 0 — Stop the breakage

Phase 0 commits are self-contained and can be cherry-picked to a hot-fix branch.

## Task 0.1: Failing test — KitsuMetadataService must not filter when no seasons requested

**Files:**
- Test: `app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceSeasonFilterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.anime

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.data.integration.kitsu.KitsuIntegrationProvider
import com.nexio.tv.data.remote.api.KitsuAnimeAttributes
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class KitsuMetadataServiceSeasonFilterTest {

    @Test
    fun `returns all episodes when caller passes empty seasonNumbers`() = runBlocking {
        val provider = mockk<KitsuIntegrationProvider>()
        val mappingService = mockk<AnimeIdMappingService>(relaxed = true)
        val service = KitsuMetadataService(provider = provider, idMappingService = mappingService)

        coEvery {
            provider.fetchEpisodeEnrichment(rawId = "kitsu:13881", kitsuId = "13881", mediaKind = ContentMediaKind.SERIES, mapper = any())
        } answers {
            val mapper = arg<(List<KitsuAnimeResource>) -> Map<Pair<Int, Int>, TvEpisodeMetadata>>(3)
            mapper(listOf(episodeResource(id = "243500", number = 1, season = 3, title = "Game Start")))
        }

        val result = service.fetchEpisodeEnrichment(
            rawId = "kitsu:13881",
            mediaKind = ContentMediaKind.SERIES,
            seasonNumbers = emptyList()
        )

        assertEquals(1, result.size)
        assertEquals(3 to 1, result.keys.first())
    }

    @Test
    fun `still filters when caller passes specific seasonNumbers`() = runBlocking {
        val provider = mockk<KitsuIntegrationProvider>()
        val mappingService = mockk<AnimeIdMappingService>(relaxed = true)
        val service = KitsuMetadataService(provider = provider, idMappingService = mappingService)

        coEvery {
            provider.fetchEpisodeEnrichment(rawId = "kitsu:13881", kitsuId = "13881", mediaKind = ContentMediaKind.SERIES, mapper = any())
        } answers {
            val mapper = arg<(List<KitsuAnimeResource>) -> Map<Pair<Int, Int>, TvEpisodeMetadata>>(3)
            mapper(
                listOf(
                    episodeResource(id = "243500", number = 1, season = 3, title = "S3E1"),
                    episodeResource(id = "100000", number = 1, season = 1, title = "S1E1"),
                )
            )
        }

        val result = service.fetchEpisodeEnrichment(
            rawId = "kitsu:13881",
            mediaKind = ContentMediaKind.SERIES,
            seasonNumbers = listOf(3)
        )

        assertEquals(setOf(3 to 1), result.keys)
    }

    private fun episodeResource(id: String, number: Int, season: Int?, title: String): KitsuAnimeResource =
        KitsuAnimeResource(
            id = id,
            type = "episodes",
            attributes = KitsuAnimeAttributes(
                canonicalTitle = title,
                number = number,
                seasonNumber = season
            )
        )
}
```

- [ ] **Step 2: Run test, verify it fails**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.KitsuMetadataServiceSeasonFilterTest
```

Expected: the first test fails because the current `KitsuMetadataService.fetchEpisodeEnrichment` does NOT filter when `seasonNumbers` is empty (the existing code says `if (acceptedSeasons.isEmpty()) allEpisodes else allEpisodes.filterKeys { ... }` — so this test should actually PASS today). Read the existing code carefully: re-confirm the current behaviour. If the test passes already, the bug is upstream in `MetadataRouterFacade` (Task 0.3). If the test fails, the contract is broken locally — fix in Task 0.2.

The likely reality is that this test passes today because `KitsuMetadataService` already returns `allEpisodes` when `seasonNumbers` is empty (`KitsuMetadataService.kt:95`). The bug is that `MetadataRouterFacade` always passes a non-empty list (defaulting to `[1]`), so `KitsuMetadataService` never sees the empty case in production.

If the test passes: skip Task 0.2 and proceed directly to Task 0.3 (the real bug). If the test fails: the contract has drifted and Task 0.2 fixes it.

- [ ] **Step 3: Commit (test only)**

```bash
git add app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceSeasonFilterTest.kt
git commit -m "test(kitsu): pin contract that empty seasonNumbers returns all episodes"
```

## Task 0.2: (Conditional) Fix KitsuMetadataService season filter if Task 0.1 failed

Only execute this task if the first test in Task 0.1 failed (i.e. the current code filters when `seasonNumbers` is empty).

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt:94-99`

- [ ] **Step 1: Read the current implementation**

```
Read app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt:94-99
```

Verify the existing branch is correct:

```kotlin
val acceptedSeasons = seasonNumbers.toSet()
if (acceptedSeasons.isEmpty()) {
    allEpisodes
} else {
    allEpisodes.filterKeys { (season, _) -> season in acceptedSeasons }
}
```

If it matches the above (which the live code does as of 2026-05-05), Task 0.1's first test should pass and this task is a no-op — proceed to Task 0.3.

- [ ] **Step 2: If different, change to match the contract above; rerun the test**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.KitsuMetadataServiceSeasonFilterTest
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt
git commit -m "fix(kitsu): return all episodes when caller passes empty seasonNumbers"
```

## Task 0.3: Failing test — MetadataRouterFacade must not default seasonNumber to 1

**Files:**
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeSeasonDefaultTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.domain.model.ContentType
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRouterFacadeSeasonDefaultTest {

    @Test
    fun `does not default seasonNumber to 1 when caller passes empty seasonNumbers`() = runBlocking {
        val router = mockk<MetadataRouter>()
        val identityResolver = mockk<MetadataIdentityResolver>()
        val planExecutor = mockk<ProviderPlanExecutor>()
        val planRunner = mockk<ProviderPlanRunner>()
        val capturedSeason = slot<Int?>()

        val route = MetadataRoute(
            provider = MetadataPrimaryProvider.KITSU,
            parentId = "kitsu:13881",
            targetIds = emptyMap(),
            seasonNumber = null,
            targetIdRequiresIdentityResolution = false,
            pagination = null
        )

        coEvery { router.route(any()) } answers {
            capturedSeason.captured = firstArg<MetadataRequest>().seasonNumber
            route
        }
        coEvery { identityResolver.resolve(route) } returns route
        coEvery { planExecutor.buildPlan(any(), any()) } returns mockk(relaxed = true)
        coEvery { planRunner.run(any()) } returns mockk(relaxed = true) {
            coEvery { stepResults } returns emptyList()
        }

        val facade = MetadataRouterFacade(
            router = router,
            providerPlanExecutor = planExecutor,
            resolverOrchestrator = mockk(relaxed = true),
            identityResolver = identityResolver,
            providerPlanRunner = planRunner,
            fieldResolver = mockk(relaxed = true),
        )

        facade.fetchTvEpisodeEnrichment(
            metadataRequest = MetadataRequest(
                contentId = "kitsu:13881",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(itemType = "series"),
                depth = MetadataDepth.SEASON,
                seasonNumber = null
            ),
            tvRequest = TvMetadataRequest(
                contentId = "kitsu:13881",
                fallbackContentId = "kitsu:13881",
                contentType = ContentType.SERIES,
                language = "en",
                seasonNumbers = emptyList()
            )
        )

        assertNull(
            "router must receive seasonNumber=null when caller has none, NOT 1",
            capturedSeason.captured
        )
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterFacadeSeasonDefaultTest
```

Expected: FAIL — `capturedSeason.captured` is `1`, because `MetadataRouterFacade.kt:648` does `?: 1`.

- [ ] **Step 3: Commit (test only)**

```bash
git add app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeSeasonDefaultTest.kt
git commit -m "test(metadata): pin that fetchTvEpisodeEnrichment must not default seasonNumber to 1"
```

## Task 0.4: Fix MetadataRouterFacade to not default seasonNumber to 1

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:644-649`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:708-724`

- [ ] **Step 1: Edit `fetchTvEpisodeEnrichment` season default**

Replace at `MetadataRouterFacade.kt:644-649`:

```kotlin
val seasonMetadataRequest = metadataRequest.copy(
    depth = MetadataDepth.SEASON,
    // Default to season 1 when no explicit season is provided — the episode metadata
    // fetcher will expand to all available seasons via fetchEpisodeMetadataForRoute.
    seasonNumber = tvRequest.seasonNumbers.firstOrNull() ?: metadataRequest.seasonNumber ?: 1
)
```

with:

```kotlin
val seasonMetadataRequest = metadataRequest.copy(
    depth = MetadataDepth.SEASON,
    // Pass through "no season hint" rather than guessing season 1. fetchEpisodeMetadataForRoute
    // expands to all seasons when no hint is supplied, which is the only safe default for
    // anime where Kitsu sub-records carry their franchise-relative season number (e.g.
    // kitsu:13881 = MHA Season 3).
    seasonNumber = tvRequest.seasonNumbers.firstOrNull() ?: metadataRequest.seasonNumber
)
```

- [ ] **Step 2: Edit `fetchEpisodeMetadataForRoute` to expand-all when no hint**

Replace at `MetadataRouterFacade.kt:712-715`:

```kotlin
return seasonNumbers
    .ifEmpty { listOfNotNull(metadataSeasonNumber) }
    .ifEmpty { listOf(1) }
    .flatMap { seasonNumber ->
```

with:

```kotlin
val effectiveSeasons = seasonNumbers
    .ifEmpty { listOfNotNull(metadataSeasonNumber) }
return if (effectiveSeasons.isEmpty()) {
    // No season hint: ask the provider for all seasons it knows about by passing
    // an unconstrained season list. KitsuMetadataService.fetchEpisodeEnrichment
    // and TvdbMetadataService.fetchEpisodeEnrichment both treat empty seasonNumbers
    // as "return everything", so this expands instead of guessing season 1.
    val unconstrainedRoute = route.copy(seasonNumber = null)
    val plan = providerPlanExecutor.buildPlan(unconstrainedRoute, MetadataDepth.SEASON)
    providerPlanRunner.run(plan).stepResults
        .flatMap { stepResult -> stepResult.episodeMetadata.entries }
        .associate { it.toPair() }
} else {
    effectiveSeasons.flatMap { seasonNumber ->
        val seasonRoute = route.copy(seasonNumber = seasonNumber)
        val plan = providerPlanExecutor.buildPlan(seasonRoute, MetadataDepth.SEASON)
        providerPlanRunner.run(plan).stepResults.flatMap { stepResult ->
            stepResult.episodeMetadata.entries
        }
    }.associate { it.toPair() }
}
```

- [ ] **Step 3: Run the failing test**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterFacadeSeasonDefaultTest
```

Expected: PASS.

- [ ] **Step 4: Run the full router-facade test suite to check for regressions**

```
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.metadata.router.*"
```

Expected: all pass. If `MetadataRouterFacadeStableIdBundleTest` or any other suite asserts the old `?: 1` behaviour, that assertion is the one that was wrong; update it to match the new contract and re-run. Do NOT revert this change without consulting the RCA.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt
git commit -m "fix(metadata): stop defaulting episode-enrichment season to 1

Kitsu sub-records (e.g. kitsu:13881 = MHA Season 3) return episodes
tagged with their franchise-relative seasonNumber. Defaulting to
season 1 caused KitsuMetadataService to filter every season-3 episode
out, producing 'Episode metadata is unavailable' on the detail screen.
Pass through 'no season hint' instead and let the provider return
every season it knows about."
```

## Task 0.5: End-to-end test — MetaDetailsViewModel renders MHA S3 episodes

**Files:**
- Test: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelMhaSeason3Test.kt`

This is a higher-level integration test using fakes. It guards against regression of the Phase 0 fix end-to-end.

- [ ] **Step 1: Write the test**

```kotlin
package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetaDetailsViewModelMhaSeason3Test {

    @Test
    fun `MHA Season 3 detail loads 25 episodes when Kitsu returns them as season 3`() = runBlocking {
        val (vm, fakes) = MetaDetailsViewModelTestHarness.create()

        fakes.metadataRouterFacade.episodeEnrichmentDecision = TvMetadataDecision(
            provider = TvProvider.KITSU,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = (1..25).associate { ep ->
                (3 to ep) to TvEpisodeMetadata(
                    providerEpisodeId = "kitsu:24350${ep - 1}",
                    seasonNumber = 3,
                    episodeNumber = ep,
                    title = "S3E$ep",
                    overview = null,
                    thumbnail = null,
                    airDate = null,
                    runtimeMinutes = 24
                )
            },
            diagnostics = emptyList()
        )

        vm.applyMeta(
            Meta(
                id = "kitsu:13881",
                name = "Boku no Hero Academia 3",
                type = ContentType.SERIES.toApiString(),
                videos = emptyList(),
                links = emptyList()
            )
        )

        val state = vm.uiState.value
        assertNull("error must be null after Phase 0 fix", state.error)
        assertEquals(25, state.meta?.videos?.size)
        assertEquals(setOf(3), state.meta?.videos?.mapNotNull { it.season }?.toSet())
    }
}
```

NOTE: `MetaDetailsViewModelTestHarness` may not exist yet. If absent, scope this task down to a smaller integration: assert that `applyTvEpisodeEnrichment` produces non-empty videos when given a Kitsu episodeMap with `(3, n)` keys. The principle is the same.

- [ ] **Step 2: Run, verify it passes given the Phase 0 fix**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsViewModelMhaSeason3Test
```

Expected: PASS (red→green is in Tasks 0.1–0.4; this is the regression guard).

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelMhaSeason3Test.kt
git commit -m "test(detail): regression guard for MHA Season 3 episode load"
```

## Task 0.6: Failing test — Trakt scrobble must emit a visible reject for kitsu: ids

**Files:**
- Test: `app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleServiceAnimeRejectionTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.playback.PlaybackOwnerContext
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TrackingScrobbleServiceAnimeRejectionTest {

    @Test
    fun `kitsu content id triggers a visible reject not a silent noop`() = runBlocking {
        val trakt = mockk<TraktScrobbleService>(relaxed = true)
        val simkl = mockk<SimklScrobbleService>(relaxed = true)
        val providerState = mockk<TrackingProviderStateService>()
        val rejectionReporter = mockk<ScrobbleRejectionReporter>(relaxed = true)

        coEvery { providerState.currentState(any<Int>()) } returns EffectiveTrackingProviderState(
            effectiveProvider = TrackingProvider.TRAKT,
            traktAuthenticated = true,
            simklAuthenticated = false
        )

        val service = DefaultTrackingScrobbleService(
            traktScrobbleService = trakt,
            simklScrobbleService = simkl,
            trackingProviderStateService = providerState,
            rejectionReporter = rejectionReporter
        )

        service.scrobbleStart(
            item = TrackingScrobbleItem.Episode(
                contentId = "kitsu:13881",
                showTitle = "Boku no Hero Academia 3",
                showYear = 2018,
                season = 3,
                number = 1,
                episodeTitle = "Game Start"
            ),
            progressPercent = 5f,
            owner = PlaybackOwnerContext(ownerProfileId = 1, ownerSessionId = "s1")
        )

        coVerify(exactly = 0) { trakt.scrobbleStart(any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            rejectionReporter.reportRejection(
                contentId = "kitsu:13881",
                reason = ScrobbleRejectionReason.NO_PARSEABLE_IDS,
                provider = TrackingProvider.TRAKT
            )
        }
    }
}
```

- [ ] **Step 2: Run, verify it fails**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.TrackingScrobbleServiceAnimeRejectionTest
```

Expected: FAIL — `ScrobbleRejectionReporter` does not exist; `DefaultTrackingScrobbleService` constructor doesn't take it; the silent-`return null` path in `toTraktItem` does not emit anything.

- [ ] **Step 3: Commit (test only)**

```bash
git add app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleServiceAnimeRejectionTest.kt
git commit -m "test(scrobble): pin that anime-native ids must emit visible reject"
```

## Task 0.7: Create ScrobbleRejectionReason and ScrobbleRejectionReporter

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/ScrobbleRejectionReason.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/ScrobbleRejectionReporter.kt`

- [ ] **Step 1: Add the reason enum**

```kotlin
// app/src/main/java/com/nexio/tv/data/repository/ScrobbleRejectionReason.kt
package com.nexio.tv.data.repository

enum class ScrobbleRejectionReason {
    /** Content id has no recognised id scheme (kitsu:, mal:, anilist:, anidb: etc). */
    NO_PARSEABLE_IDS,

    /** AnimeSeasonProjectionResolver could not produce a confident scrobble coordinate. */
    ANIME_COORDINATE_UNRESOLVED,

    /** Provider returned an empty or invalid id set after parsing. */
    EMPTY_ID_BUNDLE,
}
```

- [ ] **Step 2: Add the reporter**

```kotlin
// app/src/main/java/com/nexio/tv/data/repository/ScrobbleRejectionReporter.kt
package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.domain.model.TrackingProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits a single, observable record when scrobble cannot proceed for a given content id.
 * Replaces the prior silent-no-op behaviour at TrackingScrobbleService.toTraktItem,
 * which swallowed every kitsu:/mal:/anilist:/anidb: playback because parseContentIds
 * had no branch for those schemes.
 */
@Singleton
class ScrobbleRejectionReporter @Inject constructor() {

    fun reportRejection(
        contentId: String,
        reason: ScrobbleRejectionReason,
        provider: TrackingProvider,
    ) {
        Log.w(
            LOG_TAG,
            "scrobble.rejected provider=$provider reason=$reason contentId=$contentId"
        )
        // Future: emit to TraceMetadataEvents as tracking.scrobble_rejected once the
        // anime-projection trace bus is wired up in Task 1.13.
    }

    private companion object {
        private const val LOG_TAG = "ScrobbleRejection"
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ScrobbleRejectionReason.kt app/src/main/java/com/nexio/tv/data/repository/ScrobbleRejectionReporter.kt
git commit -m "feat(scrobble): add visible rejection reporter for anime-native ids"
```

## Task 0.8: Wire ScrobbleRejectionReporter into TrackingScrobbleService

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt`

- [ ] **Step 1: Inject the reporter and emit on the silent-null path**

Update the constructor at `TrackingScrobbleService.kt:46-51` to add `private val rejectionReporter: ScrobbleRejectionReporter`.

Update `toTraktItem` at `TrackingScrobbleService.kt:146-165` to:

```kotlin
private fun toTraktItem(item: TrackingScrobbleItem): TraktScrobbleItem? {
    val contentId = item.contentId()
    val ids = toTraktIds(parseContentIds(contentId))
    if (!ids.hasAnyId()) {
        rejectionReporter.reportRejection(
            contentId = contentId,
            reason = ScrobbleRejectionReason.NO_PARSEABLE_IDS,
            provider = TrackingProvider.TRAKT
        )
        return null
    }
    return when (item) {
        is TrackingScrobbleItem.Movie -> TraktScrobbleItem.Movie(
            title = item.title,
            year = item.year,
            ids = ids
        )

        is TrackingScrobbleItem.Episode -> TraktScrobbleItem.Episode(
            showTitle = item.showTitle,
            showYear = item.showYear,
            showIds = ids,
            season = item.season,
            number = item.number,
            episodeTitle = item.episodeTitle
        )
    }
}
```

- [ ] **Step 2: Run the failing test**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.TrackingScrobbleServiceAnimeRejectionTest
```

Expected: PASS.

- [ ] **Step 3: Run all scrobble tests for regressions**

```
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.*Scrobble*"
```

Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt
git commit -m "fix(scrobble): emit visible rejection instead of silent noop for anime-native ids

Previously, kitsu:/mal:/anilist:/anidb: content ids fell through
parseContentIds with no branch and toTraktItem returned null, which
silently disabled scrobble for every Kitsu-sourced playback. This
preserves the 'no scrobble request sent' outcome but makes it
observable through ScrobbleRejectionReporter."
```

## Task 0.9: Phase 0 verification — full test suite + manual smoke

- [ ] **Step 1: Run the full unit-test suite**

```
./gradlew :app:testDebugUnitTest
```

Expected: GREEN. Any failure here is a regression introduced by Tasks 0.1–0.8 and must be triaged before continuing.

- [ ] **Step 2: Build a debug APK**

```
./gradlew :app:assembleDebug
```

- [ ] **Step 3: Install on device and reproduce manually**

```
adb -s 192.168.50.98:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 logcat -v threadtime "MetaDetailsViewModel:V" "ScrobbleRejection:V" "*:S" > /tmp/phase0_smoke.log &
```

On device: open modern home → Kitsu Trending → click MHA Season 3 → expect episode list of 25, NOT "Episode metadata is unavailable". Then play any episode and check `ScrobbleRejection` does NOT appear (because by Phase 1 we'll have a projection; in Phase 0 alone it WILL appear with `NO_PARSEABLE_IDS` until Phase 1 ships — record the log either way).

Stop the logcat, attach `/tmp/phase0_smoke.log` to the PR description.

- [ ] **Step 4: Tag the Phase 0 commit**

```bash
git tag -a phase-0-anime-coordinate-fix -m "Hot-fixable anime episode + scrobble visibility"
```

Phase 0 is now self-contained — the two-commit chain (`fix(metadata): stop defaulting…` and `fix(scrobble): emit visible rejection…`) plus the supporting tests can be cherry-picked to a hot-fix branch independently of Phase 1.

---

# Phase 1 — Anime Season/Episode Projection Layer

Phase 1 introduces the new resolver and routes detail UI, scrobble, and Top-Posters through it. It does not change Kitsu's role as the metadata authority.

## Task 1.1: Domain model — EpisodeCoordinate

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/EpisodeCoordinate.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/projection/EpisodeCoordinateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeCoordinateTest {

    @Test
    fun `keys equal when provider seriesId season and episode match regardless of absoluteNumber`() {
        val a = EpisodeCoordinate(ProviderId.TVDB, "305074", 3, 1, absoluteNumber = 39)
        val b = EpisodeCoordinate(ProviderId.TVDB, "305074", 3, 1, absoluteNumber = null)
        assertEquals(a.identityKey, b.identityKey)
    }

    @Test
    fun `identityKey distinguishes provider`() {
        val tvdb = EpisodeCoordinate(ProviderId.TVDB, "305074", 3, 1)
        val kitsu = EpisodeCoordinate(ProviderId.KITSU, "13881", 3, 1)
        assert(tvdb.identityKey != kitsu.identityKey)
    }
}
```

- [ ] **Step 2: Run, verify it fails**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.projection.EpisodeCoordinateTest
```

Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Implement the class**

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/projection/EpisodeCoordinate.kt
package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.ProviderId

/**
 * One coordinate-system reference to an episode. The same logical episode (e.g. MHA S3E1)
 * has different values in different systems: Kitsu may flatten under (1, 1), Trakt and TVDB
 * use (3, 1), an "absolute number" might be 39 across the franchise. AnimeEpisodeProjection
 * carries several of these together so consumers can pick the one they need.
 */
data class EpisodeCoordinate(
    val provider: ProviderId,
    val seriesId: String,
    val season: Int,
    val episode: Int,
    val absoluteNumber: Int? = null,
) {
    /** Stable identity for cache keys; ignores absoluteNumber because that is derived. */
    val identityKey: String = "${provider.name.lowercase()}:$seriesId:s${season}e$episode"
}
```

- [ ] **Step 4: Run, verify it passes**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.projection.EpisodeCoordinateTest
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/projection/ app/src/test/java/com/nexio/tv/core/anime/projection/
git commit -m "feat(projection): add EpisodeCoordinate domain model"
```

## Task 1.2: Domain models — confidence + work identity

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/CoordinateConfidence.kt`
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeWorkIdentity.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/projection/AnimeWorkIdentityTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeWorkIdentityTest {

    @Test
    fun `groupKey prefers tvdb when present and series`() {
        val key = AnimeWorkGroupKey.preferred(
            tvdbId = "305074",
            imdbId = "tt5626028",
            tmdbId = "65930",
            sourceKitsuId = "13881"
        )
        assertEquals("anime-work:tvdb:305074", key.value)
    }

    @Test
    fun `groupKey falls through to imdb then kitsu`() {
        val imdbKey = AnimeWorkGroupKey.preferred(tvdbId = null, imdbId = "tt5626028", tmdbId = null, sourceKitsuId = "13881")
        val kitsuOnly = AnimeWorkGroupKey.preferred(tvdbId = null, imdbId = null, tmdbId = null, sourceKitsuId = "12")
        assertEquals("anime-work:imdb:tt5626028", imdbKey.value)
        assertEquals("anime-work:kitsu:12", kitsuOnly.value)
    }

    @Test
    fun `identity carries confidence and member set`() {
        val identity = AnimeWorkIdentity(
            groupKey = AnimeWorkGroupKey("anime-work:tvdb:305074"),
            primaryKitsuId = "11469",
            memberKitsuIds = setOf("11469", "12268", "13881"),
            providerIds = ProviderIds(tvdb = "305074", imdb = "tt5626028", tmdb = "65930"),
            confidence = AnimeGroupingConfidence.HIGH,
            evidence = listOf("kitsu.tvdb=305074", "kitsu.imdb=tt5626028"),
        )
        assertTrue("13881" in identity.memberKitsuIds)
        assertEquals(AnimeGroupingConfidence.HIGH, identity.confidence)
    }
}
```

- [ ] **Step 2: Run, verify it fails**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.projection.AnimeWorkIdentityTest
```

- [ ] **Step 3: Implement**

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/projection/CoordinateConfidence.kt
package com.nexio.tv.core.anime.projection

enum class CoordinateConfidence { HIGH, MEDIUM, LOW, UNKNOWN }
enum class AnimeGroupingConfidence { HIGH, MEDIUM, LOW }
```

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/projection/AnimeWorkIdentity.kt
package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.ProviderIds

@JvmInline
value class AnimeWorkGroupKey(val value: String) {
    companion object {
        fun preferred(
            tvdbId: String?,
            imdbId: String?,
            tmdbId: String?,
            sourceKitsuId: String?,
        ): AnimeWorkGroupKey = AnimeWorkGroupKey(
            when {
                !tvdbId.isNullOrBlank() -> "anime-work:tvdb:$tvdbId"
                !imdbId.isNullOrBlank() -> "anime-work:imdb:$imdbId"
                !tmdbId.isNullOrBlank() -> "anime-work:tmdb:$tmdbId"
                !sourceKitsuId.isNullOrBlank() -> "anime-work:kitsu:$sourceKitsuId"
                else -> "anime-work:unknown"
            }
        )
    }
}

data class AnimeWorkIdentity(
    val groupKey: AnimeWorkGroupKey,
    /** Best Kitsu id to use as franchise representative (e.g. earliest season). */
    val primaryKitsuId: String?,
    /** Every Kitsu id we believe belongs to this work. */
    val memberKitsuIds: Set<String>,
    val providerIds: ProviderIds,
    val confidence: AnimeGroupingConfidence,
    val evidence: List<String>,
)
```

- [ ] **Step 4: Run, verify it passes**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.projection.AnimeWorkIdentityTest
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/projection/CoordinateConfidence.kt app/src/main/java/com/nexio/tv/core/anime/projection/AnimeWorkIdentity.kt app/src/test/java/com/nexio/tv/core/anime/projection/AnimeWorkIdentityTest.kt
git commit -m "feat(projection): add AnimeWorkIdentity and CoordinateConfidence"
```

## Task 1.3: Domain models — resource descriptor

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeResourceDescriptor.kt`

- [ ] **Step 1: Implement (no separate test — exercised by resolver tests)**

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/projection/AnimeResourceDescriptor.kt
package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.ProviderIds

enum class AnimeSubtype {
    TV, MOVIE, OVA, ONA, SPECIAL, MUSIC, UNKNOWN;
    companion object {
        fun parse(raw: String?): AnimeSubtype = when (raw?.uppercase()) {
            "TV" -> TV
            "MOVIE" -> MOVIE
            "OVA" -> OVA
            "ONA" -> ONA
            "SPECIAL" -> SPECIAL
            "MUSIC" -> MUSIC
            else -> UNKNOWN
        }
    }
}

enum class AnimeResourceRole {
    /** Single Kitsu resource that covers an entire long-running series (e.g. One Piece). */
    FRANCHISE_SINGLE_SERIES,
    /** One season of a multi-season franchise (e.g. MHA Season 3 = kitsu:13881). */
    SEASONAL_ENTRY,
    /** A movie tied to the franchise. */
    MOVIE,
    /** OVAs / specials / shorts. */
    SPECIAL,
    UNKNOWN,
}

/**
 * A single Kitsu anime resource as we see it after merging Kitsu attributes with our
 * AnimeIdMappingService overlay. Used by AnimeSeasonProjectionResolver to decide work
 * grouping and season presentation.
 */
data class AnimeResourceDescriptor(
    val kitsuId: String,
    val malId: String?,
    val anilistId: String?,
    val anidbId: String?,
    val providerIds: ProviderIds,
    val subtype: AnimeSubtype,
    val startDate: String?,
    val episodeCount: Int?,
    val detectedSeasonNumbers: Set<Int>,
    val role: AnimeResourceRole,
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/projection/AnimeResourceDescriptor.kt
git commit -m "feat(projection): add AnimeResourceDescriptor"
```

## Task 1.4: Domain models — projection + presentation

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/SourceEpisodeCoordinate.kt`
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeEpisodeProjection.kt`
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonPresentation.kt`

- [ ] **Step 1: Implement**

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/projection/SourceEpisodeCoordinate.kt
package com.nexio.tv.core.anime.projection

/**
 * Coordinate as the user interacted with it (usually the Kitsu coordinate that came back
 * from KitsuMetadataService). The projection resolver translates this into target
 * coordinates for scrobble / thumbnails / display.
 */
data class SourceEpisodeCoordinate(
    val sourceKitsuId: String,
    val season: Int,
    val episode: Int,
)
```

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/projection/AnimeEpisodeProjection.kt
package com.nexio.tv.core.anime.projection

enum class EpisodeProjectionTarget {
    UI_DISPLAY,
    TRAKT_SCROBBLE,
    SIMKL_SCROBBLE,
    PREMIUM_THUMBNAIL,
    EPISODE_RATING,
    CONTINUE_WATCHING,
}

enum class FallbackReason {
    NO_TVDB_MAPPING,
    NO_TMDB_MAPPING,
    LOW_CONFIDENCE_FLAT_KITSU,
    OVERLAY_MISSING,
}

data class AnimeEpisodeProjection(
    val sourceKitsuId: String,
    val sourceKitsuCoordinate: EpisodeCoordinate,
    val displayCoordinate: EpisodeCoordinate,
    val scrobbleCoordinate: EpisodeCoordinate?,
    val premiumArtworkCoordinate: EpisodeCoordinate?,
    val tvdbCoordinate: EpisodeCoordinate?,
    val tmdbCoordinate: EpisodeCoordinate?,
    val confidence: CoordinateConfidence,
    val fallbackReason: FallbackReason?,
    val evidence: List<String>,
)
```

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonPresentation.kt
package com.nexio.tv.core.anime.projection

enum class SeasonPresentationSource {
    KITSU_SEASON_NUMBERS,
    TVDB_PROJECTED,
    TMDB_PROJECTED,
    KITSU_FLAT_FALLBACK,
}

/**
 * One tab on the detail screen. Carries the Kitsu memberId that hydrates episodes
 * for this season — important for franchises like MHA where each season's episodes
 * live under a different Kitsu anime resource.
 */
data class AnimeSeasonTab(
    val seasonNumber: Int,
    val title: String?,
    val episodeCount: Int?,
    val episodesKitsuMemberId: String?,
    val isFlatFallback: Boolean,
)

data class AnimeSeasonPresentation(
    val work: AnimeWorkIdentity,
    val seasons: List<AnimeSeasonTab>,
    val selectedSeason: Int,
    val source: SeasonPresentationSource,
    val confidence: CoordinateConfidence,
)
```

- [ ] **Step 2: Build to verify it compiles**

```
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/projection/
git commit -m "feat(projection): add SourceEpisodeCoordinate, AnimeEpisodeProjection, AnimeSeasonPresentation"
```

## Task 1.5: AnimeSeasonProjectionResolver interface

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonProjectionResolver.kt`

- [ ] **Step 1: Implement the interface**

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonProjectionResolver.kt
package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeStremioId

/**
 * Identifies the entry point a caller used to reach the projection resolver: usually
 * a Kitsu id from a rail click, or a parsed AnimeStremioId from a watch-history record.
 * The resolver uses this to pick a sensible default season for the detail UI (see
 * "click-source-aware default season" in the plan analysis).
 */
data class AnimeSourceIdentity(
    val sourceKitsuId: String?,
    val animeStremioId: AnimeStremioId?,
)

/**
 * Converts between Kitsu's anime-resource coordinate space and TVDB/TMDB/Trakt episode
 * coordinate spaces. Caller-facing facade around the AnimeIdMappingService asset, the
 * AnimeEpisodeCoordinateStore cache, and (later) curated overlay data.
 *
 * Kitsu remains the metadata authority — this resolver does NOT replace KitsuMetadataService
 * for titles, synopsis, characters, etc. It only owns coordinate translation.
 */
interface AnimeSeasonProjectionResolver {

    /**
     * Group all Kitsu resources that belong to the same logical anime work. For MHA, this
     * collapses kitsu:11469/12268/13881/41971/43108/45240/47232/49279 (the eight series
     * sub-records) into one identity. Movies and specials sharing the same TVDB/IMDb id are
     * NOT grouped into the series identity (see grouping safety in the plan analysis).
     */
    suspend fun resolveWork(source: AnimeSourceIdentity): AnimeWorkIdentity

    /**
     * Build the season-tab list for the detail screen. selectedSeason defaults to the
     * detected Kitsu season of [sourceKitsuId] when present, else the lowest known season.
     */
    suspend fun resolveSeasonPresentation(
        work: AnimeWorkIdentity,
        sourceKitsuId: String,
        requestedSeason: Int?,
    ): AnimeSeasonPresentation

    /**
     * Project a single Kitsu episode coordinate into the coordinate system the [target]
     * needs. For TRAKT_SCROBBLE on a low-confidence flat Kitsu source (e.g. One Piece
     * before Phase 3 overlay), returns scrobbleCoordinate = null and the caller MUST
     * emit a tracking.scrobble_rejected event rather than send wrong coords.
     */
    suspend fun resolveEpisodeProjection(
        work: AnimeWorkIdentity,
        sourceEpisode: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
    ): AnimeEpisodeProjection
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonProjectionResolver.kt
git commit -m "feat(projection): add AnimeSeasonProjectionResolver interface"
```

## Task 1.6: DefaultAnimeSeasonProjectionResolver — resolveWork

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverWorkTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAnimeSeasonProjectionResolverWorkTest {

    @Test
    fun `groups all MHA series Kitsu records under one work via shared tvdb`() = runBlocking {
        val asset = AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = mapOf(
                "11469" to series("11469", tvdb = "305074", imdb = "tt5626028"),
                "12268" to series("12268", tvdb = "305074", imdb = "tt5626028"),
                "13881" to series("13881", tvdb = "305074", imdb = "tt5626028"),
                "14084" to movie("14084", tvdb = "305074", imdb = "tt7745068"),
            )
        )
        val service = AnimeIdMappingService(assetProvider = { asset })
        val resolver = DefaultAnimeSeasonProjectionResolver(idMappingService = service)

        val work = resolver.resolveWork(AnimeSourceIdentity(sourceKitsuId = "13881", animeStremioId = null))

        assertEquals("anime-work:tvdb:305074", work.groupKey.value)
        assertEquals(setOf("11469", "12268", "13881"), work.memberKitsuIds)
        assertTrue("movie 14084 must NOT be grouped into the series work", "14084" !in work.memberKitsuIds)
        assertEquals(AnimeGroupingConfidence.HIGH, work.confidence)
    }

    private fun series(kitsu: String, tvdb: String, imdb: String) = AnimeIdMapRecord(
        kitsu = kitsu, tvdb = tvdb, imdb = imdb, mediaType = "series", sourceType = "TV"
    )

    private fun movie(kitsu: String, tvdb: String, imdb: String) = AnimeIdMapRecord(
        kitsu = kitsu, tvdb = tvdb, imdb = imdb, mediaType = "movie", sourceType = "MOVIE"
    )
}
```

- [ ] **Step 2: Run, verify it fails**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.projection.DefaultAnimeSeasonProjectionResolverWorkTest
```

- [ ] **Step 3: Implement `resolveWork`**

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt
package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAnimeSeasonProjectionResolver @Inject constructor(
    private val idMappingService: AnimeIdMappingService,
) : AnimeSeasonProjectionResolver {

    override suspend fun resolveWork(source: AnimeSourceIdentity): AnimeWorkIdentity {
        val kitsuId = source.sourceKitsuId?.removePrefix("kitsu:")?.takeIf { it.isNotBlank() }
            ?: return unknownWork(source)
        val record = idMappingService.recordForKitsuId(kitsuId)
            ?: return unknownWork(source)

        val memberRecords = idMappingService.allSeriesRecordsSharingTvdb(record)
        val memberIds = memberRecords.map { it.kitsu }.toSet()
        val primary = memberRecords.minByOrNull { it.kitsu.toIntOrNull() ?: Int.MAX_VALUE }?.kitsu

        val groupKey = AnimeWorkGroupKey.preferred(
            tvdbId = record.tvdb,
            imdbId = record.imdb,
            tmdbId = record.tmdb,
            sourceKitsuId = kitsuId,
        )
        val confidence = when {
            !record.tvdb.isNullOrBlank() -> AnimeGroupingConfidence.HIGH
            !record.imdb.isNullOrBlank() -> AnimeGroupingConfidence.MEDIUM
            else -> AnimeGroupingConfidence.LOW
        }
        return AnimeWorkIdentity(
            groupKey = groupKey,
            primaryKitsuId = primary,
            memberKitsuIds = memberIds,
            providerIds = ProviderIds(
                tvdb = record.tvdb,
                imdb = record.imdb,
                tmdb = record.tmdb,
                kitsu = kitsuId,
                mal = record.mal,
                anilist = record.anilist,
                anidb = record.anidb,
            ),
            confidence = confidence,
            evidence = listOfNotNull(
                record.tvdb?.let { "kitsu.tvdb=$it" },
                record.imdb?.let { "kitsu.imdb=$it" },
                record.tmdb?.let { "kitsu.tmdb=$it" },
            ),
        )
    }

    override suspend fun resolveSeasonPresentation(
        work: AnimeWorkIdentity,
        sourceKitsuId: String,
        requestedSeason: Int?,
    ): AnimeSeasonPresentation = TODO("Task 1.7")

    override suspend fun resolveEpisodeProjection(
        work: AnimeWorkIdentity,
        sourceEpisode: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
    ): AnimeEpisodeProjection = TODO("Task 1.8")

    private fun unknownWork(source: AnimeSourceIdentity): AnimeWorkIdentity = AnimeWorkIdentity(
        groupKey = AnimeWorkGroupKey.preferred(null, null, null, source.sourceKitsuId),
        primaryKitsuId = source.sourceKitsuId,
        memberKitsuIds = setOfNotNull(source.sourceKitsuId),
        providerIds = ProviderIds(kitsu = source.sourceKitsuId),
        confidence = AnimeGroupingConfidence.LOW,
        evidence = listOf("no-mapping-record"),
    )
}
```

- [ ] **Step 4: Add the helper methods to AnimeIdMappingService**

Edit `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt`, append:

```kotlin
    /** Returns the raw record for a kitsu id, or null if the asset has no entry. */
    fun recordForKitsuId(kitsuId: String): AnimeIdMapRecord? =
        asset.recordsByKitsu[kitsuId.removePrefix("kitsu:")]

    /**
     * Returns every SERIES-mediaType record sharing the same tvdb id as [record]. Used by
     * AnimeSeasonProjectionResolver to enumerate franchise members. Movies and specials are
     * intentionally excluded because they share TVDB ids with their parent series in Kitsu's
     * dataset (e.g. eight MHA movies share tvdb=305074 with the eight series sub-records).
     */
    fun allSeriesRecordsSharingTvdb(record: AnimeIdMapRecord): List<AnimeIdMapRecord> {
        val tvdb = record.tvdb?.takeIf { it.isNotBlank() } ?: return listOf(record)
        return asset.recordsByKitsu.values.filter { other ->
            other.tvdb == tvdb && (other.mediaType?.lowercase() ?: "series") != "movie"
        }
    }
```

- [ ] **Step 5: Run, verify it passes**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.projection.DefaultAnimeSeasonProjectionResolverWorkTest
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverWorkTest.kt
git commit -m "feat(projection): implement resolveWork with movie/series safety guard"
```

## Task 1.7: DefaultAnimeSeasonProjectionResolver — resolveSeasonPresentation

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverPresentationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.anime.KitsuMetadataService
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultAnimeSeasonProjectionResolverPresentationTest {

    @Test
    fun `MHA Season 3 click defaults selectedSeason to 3 not 1`() = runBlocking {
        val asset = AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = mapOf(
                "11469" to series("11469"),
                "13881" to series("13881"),
            )
        )
        val mapping = AnimeIdMappingService(assetProvider = { asset })
        val kitsu = mockk<KitsuMetadataService>()
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:11469", ContentMediaKind.SERIES, emptyList()) } returns
            (1..13).associate { (1 to it) to kitsuEp(season = 1, ep = it) }
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:13881", ContentMediaKind.SERIES, emptyList()) } returns
            (1..25).associate { (3 to it) to kitsuEp(season = 3, ep = it) }

        val resolver = DefaultAnimeSeasonProjectionResolver(
            idMappingService = mapping,
            kitsuMetadataService = kitsu,
        )

        val work = resolver.resolveWork(AnimeSourceIdentity(sourceKitsuId = "13881", animeStremioId = null))
        val presentation = resolver.resolveSeasonPresentation(work, sourceKitsuId = "13881", requestedSeason = null)

        assertEquals(3, presentation.selectedSeason)
        assertEquals(SeasonPresentationSource.KITSU_SEASON_NUMBERS, presentation.source)
        // S1 tab and S3 tab present, each pointing at the correct Kitsu memberId
        assertEquals("11469", presentation.seasons.first { it.seasonNumber == 1 }.episodesKitsuMemberId)
        assertEquals("13881", presentation.seasons.first { it.seasonNumber == 3 }.episodesKitsuMemberId)
    }

    @Test
    fun `flat Kitsu One Piece presents single season with KITSU_FLAT_FALLBACK`() = runBlocking {
        val asset = AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = mapOf("12" to series("12", tvdb = "81797", imdb = "tt0388629"))
        )
        val mapping = AnimeIdMappingService(assetProvider = { asset })
        val kitsu = mockk<KitsuMetadataService>()
        // Kitsu reports all episodes as season 1 for OP — flat franchise model
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:12", ContentMediaKind.SERIES, emptyList()) } returns
            (1..1387).associate { (1 to it) to kitsuEp(season = 1, ep = it) }

        val resolver = DefaultAnimeSeasonProjectionResolver(idMappingService = mapping, kitsuMetadataService = kitsu)
        val work = resolver.resolveWork(AnimeSourceIdentity(sourceKitsuId = "12", animeStremioId = null))
        val presentation = resolver.resolveSeasonPresentation(work, sourceKitsuId = "12", requestedSeason = null)

        assertEquals(1, presentation.selectedSeason)
        assertEquals(SeasonPresentationSource.KITSU_FLAT_FALLBACK, presentation.source)
        assertEquals(1, presentation.seasons.size)
        assertEquals(true, presentation.seasons.first().isFlatFallback)
    }

    private fun series(kitsu: String, tvdb: String = "305074", imdb: String = "tt5626028") =
        AnimeIdMapRecord(kitsu = kitsu, tvdb = tvdb, imdb = imdb, mediaType = "series", sourceType = "TV")

    private fun kitsuEp(season: Int, ep: Int) = TvEpisodeMetadata(
        providerEpisodeId = "kitsu:ep$season-$ep",
        seasonNumber = season,
        episodeNumber = ep,
        title = "S${season}E$ep",
        overview = null,
        thumbnail = null,
        airDate = null,
        runtimeMinutes = 24,
    )
}
```

- [ ] **Step 2: Update DefaultAnimeSeasonProjectionResolver constructor and implement `resolveSeasonPresentation`**

Add `KitsuMetadataService` to the constructor and implement:

```kotlin
override suspend fun resolveSeasonPresentation(
    work: AnimeWorkIdentity,
    sourceKitsuId: String,
    requestedSeason: Int?,
): AnimeSeasonPresentation {
    val cleanSourceId = sourceKitsuId.removePrefix("kitsu:")
    // For each member kitsu id, fetch its episodes and learn which seasons it carries.
    val perMember = work.memberKitsuIds.associateWith { memberId ->
        kitsuMetadataService.fetchEpisodeEnrichment(
            rawId = "kitsu:$memberId",
            mediaKind = ContentMediaKind.SERIES,
            seasonNumbers = emptyList(),
        )
    }
    val seasonToMember = mutableMapOf<Int, String>()
    val seasonToCount = mutableMapOf<Int, Int>()
    perMember.forEach { (memberId, eps) ->
        eps.keys.forEach { (season, _) ->
            seasonToMember.putIfAbsent(season, memberId)
            seasonToCount[season] = (seasonToCount[season] ?: 0) + 1
        }
    }

    val isFlat = seasonToMember.size == 1 && seasonToMember.values.first() == cleanSourceId
        && (perMember[cleanSourceId]?.size ?: 0) >= FLAT_KITSU_MIN_EPISODES
    val source = if (isFlat) SeasonPresentationSource.KITSU_FLAT_FALLBACK else SeasonPresentationSource.KITSU_SEASON_NUMBERS

    // Click-source-aware selectedSeason: prefer the season that the clicked sourceKitsuId actually contains.
    val sourceSeasons = perMember[cleanSourceId]?.keys?.map { it.first }?.toSet().orEmpty()
    val defaultSelected = requestedSeason
        ?: sourceSeasons.minOrNull()
        ?: seasonToMember.keys.minOrNull()
        ?: 1

    val tabs = seasonToMember.entries
        .sortedBy { it.key }
        .map { (season, memberId) ->
            AnimeSeasonTab(
                seasonNumber = season,
                title = null,
                episodeCount = seasonToCount[season],
                episodesKitsuMemberId = memberId,
                isFlatFallback = isFlat,
            )
        }

    return AnimeSeasonPresentation(
        work = work,
        seasons = tabs,
        selectedSeason = defaultSelected,
        source = source,
        confidence = if (isFlat) CoordinateConfidence.LOW else CoordinateConfidence.HIGH,
    )
}

private companion object {
    /** Threshold above which a single-season Kitsu record is treated as flat-franchise. */
    private const val FLAT_KITSU_MIN_EPISODES = 50
}
```

- [ ] **Step 3: Run, verify it passes**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.projection.DefaultAnimeSeasonProjectionResolverPresentationTest
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverPresentationTest.kt
git commit -m "feat(projection): implement resolveSeasonPresentation with flat-Kitsu fallback"
```

## Task 1.8: DefaultAnimeSeasonProjectionResolver — resolveEpisodeProjection

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverEpisodeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.KitsuMetadataService
import com.nexio.tv.domain.model.ProviderId
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultAnimeSeasonProjectionResolverEpisodeTest {

    @Test
    fun `MHA S3E1 projects to TVDB S3E1 with HIGH confidence for trakt scrobble`() = runBlocking {
        val resolver = DefaultAnimeSeasonProjectionResolver(
            idMappingService = AnimeIdMappingService(
                assetProvider = {
                    AnimeIdMapAsset(
                        schemaVersion = 1,
                        recordsByKitsu = mapOf(
                            "13881" to AnimeIdMapRecord(
                                kitsu = "13881", tvdb = "305074", imdb = "tt5626028", tmdb = "65930",
                                mediaType = "series", sourceType = "TV"
                            )
                        )
                    )
                }
            ),
            kitsuMetadataService = mockk(relaxed = true),
        )
        val work = resolver.resolveWork(AnimeSourceIdentity("13881", null))
        val source = SourceEpisodeCoordinate(sourceKitsuId = "13881", season = 3, episode = 1)

        val projection = resolver.resolveEpisodeProjection(work, source, EpisodeProjectionTarget.TRAKT_SCROBBLE)

        assertNotNull(projection.scrobbleCoordinate)
        assertEquals(ProviderId.TVDB, projection.scrobbleCoordinate?.provider)
        assertEquals("305074", projection.scrobbleCoordinate?.seriesId)
        assertEquals(3, projection.scrobbleCoordinate?.season)
        assertEquals(1, projection.scrobbleCoordinate?.episode)
        assertEquals(CoordinateConfidence.HIGH, projection.confidence)
        assertNull(projection.fallbackReason)
    }

    @Test
    fun `One Piece flat Kitsu projection refuses scrobble with LOW_CONFIDENCE_FLAT_KITSU`() = runBlocking {
        val resolver = DefaultAnimeSeasonProjectionResolver(
            idMappingService = AnimeIdMappingService(
                assetProvider = {
                    AnimeIdMapAsset(
                        schemaVersion = 1,
                        recordsByKitsu = mapOf(
                            "12" to AnimeIdMapRecord(kitsu = "12", tvdb = "81797", imdb = "tt0388629", mediaType = "series", sourceType = "TV")
                        )
                    )
                }
            ),
            kitsuMetadataService = mockk(relaxed = true),
        )
        val work = resolver.resolveWork(AnimeSourceIdentity("12", null))
        val source = SourceEpisodeCoordinate(sourceKitsuId = "12", season = 1, episode = 850)

        val projection = resolver.resolveEpisodeProjection(work, source, EpisodeProjectionTarget.TRAKT_SCROBBLE)

        assertNull("must not send wrong scrobble for flat Kitsu", projection.scrobbleCoordinate)
        assertEquals(CoordinateConfidence.LOW, projection.confidence)
        assertEquals(FallbackReason.LOW_CONFIDENCE_FLAT_KITSU, projection.fallbackReason)
    }
}
```

- [ ] **Step 2: Implement**

Replace the `TODO("Task 1.8")` with:

```kotlin
override suspend fun resolveEpisodeProjection(
    work: AnimeWorkIdentity,
    sourceEpisode: SourceEpisodeCoordinate,
    target: EpisodeProjectionTarget,
): AnimeEpisodeProjection {
    val sourceKitsuCoord = EpisodeCoordinate(
        provider = ProviderId.KITSU,
        seriesId = sourceEpisode.sourceKitsuId,
        season = sourceEpisode.season,
        episode = sourceEpisode.episode,
    )
    val record = idMappingService.recordForKitsuId(sourceEpisode.sourceKitsuId)
    val tvdbId = record?.tvdb?.takeIf { it.isNotBlank() }
    val tmdbId = record?.tmdb?.takeIf { it.isNotBlank() }

    // Detect flat-Kitsu by checking the Kitsu record's reported episodeCount (or by member count).
    // For now: if work has only one member AND source season is 1 AND episode is high (>50),
    // treat as flat-franchise and refuse scrobble until Phase 3 overlay ships.
    val isFlat = work.memberKitsuIds.size == 1 && sourceEpisode.season == 1 && sourceEpisode.episode > 50

    val tvdbCoord = tvdbId?.let { id ->
        if (isFlat) null
        else EpisodeCoordinate(ProviderId.TVDB, id, sourceEpisode.season, sourceEpisode.episode)
    }
    val tmdbCoord = tmdbId?.let { id ->
        if (isFlat) null
        else EpisodeCoordinate(ProviderId.TMDB, id, sourceEpisode.season, sourceEpisode.episode)
    }

    val confidence = when {
        isFlat -> CoordinateConfidence.LOW
        tvdbCoord != null -> CoordinateConfidence.HIGH
        tmdbCoord != null -> CoordinateConfidence.MEDIUM
        else -> CoordinateConfidence.UNKNOWN
    }
    val fallbackReason = when {
        isFlat -> FallbackReason.LOW_CONFIDENCE_FLAT_KITSU
        tvdbCoord == null && tmdbCoord == null -> FallbackReason.NO_TVDB_MAPPING
        else -> null
    }

    val scrobbleCoord = when (target) {
        EpisodeProjectionTarget.TRAKT_SCROBBLE,
        EpisodeProjectionTarget.SIMKL_SCROBBLE -> if (confidence == CoordinateConfidence.HIGH) tvdbCoord else null
        else -> tvdbCoord
    }
    val artworkCoord = if (confidence != CoordinateConfidence.LOW) (tvdbCoord ?: tmdbCoord) else null

    return AnimeEpisodeProjection(
        sourceKitsuId = sourceEpisode.sourceKitsuId,
        sourceKitsuCoordinate = sourceKitsuCoord,
        displayCoordinate = if (target == EpisodeProjectionTarget.UI_DISPLAY) (tvdbCoord ?: sourceKitsuCoord) else sourceKitsuCoord,
        scrobbleCoordinate = scrobbleCoord,
        premiumArtworkCoordinate = artworkCoord,
        tvdbCoordinate = tvdbCoord,
        tmdbCoordinate = tmdbCoord,
        confidence = confidence,
        fallbackReason = fallbackReason,
        evidence = listOfNotNull(
            tvdbId?.let { "kitsu.tvdb=$it" },
            tmdbId?.let { "kitsu.tmdb=$it" },
            "source.member-count=${work.memberKitsuIds.size}",
        ),
    )
}
```

- [ ] **Step 3: Run, verify it passes**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.anime.projection.DefaultAnimeSeasonProjectionResolverEpisodeTest
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverEpisodeTest.kt
git commit -m "feat(projection): implement resolveEpisodeProjection with flat-Kitsu refusal"
```

## Task 1.9: AnimeEpisodeCoordinateStore (in-memory)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeEpisodeCoordinateStore.kt`
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/InMemoryAnimeEpisodeCoordinateStore.kt`
- Test: `app/src/test/java/com/nexio/tv/core/anime/projection/InMemoryAnimeEpisodeCoordinateStoreTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryAnimeEpisodeCoordinateStoreTest {

    @Test
    fun `put and get round-trip by group key and source kitsu id`() {
        val store = InMemoryAnimeEpisodeCoordinateStore()
        val groupKey = AnimeWorkGroupKey("anime-work:tvdb:305074")
        val source = SourceEpisodeCoordinate(sourceKitsuId = "13881", season = 3, episode = 1)
        val projection = projection(source, ProviderId.TVDB, "305074", 3, 1)

        store.put(groupKey, source, EpisodeProjectionTarget.TRAKT_SCROBBLE, projection)

        assertEquals(projection, store.get(groupKey, source, EpisodeProjectionTarget.TRAKT_SCROBBLE))
        assertNull(store.get(groupKey, source, EpisodeProjectionTarget.PREMIUM_THUMBNAIL))
    }

    private fun projection(
        source: SourceEpisodeCoordinate,
        target: ProviderId, seriesId: String, season: Int, episode: Int,
    ) = AnimeEpisodeProjection(
        sourceKitsuId = source.sourceKitsuId,
        sourceKitsuCoordinate = EpisodeCoordinate(ProviderId.KITSU, source.sourceKitsuId, source.season, source.episode),
        displayCoordinate = EpisodeCoordinate(target, seriesId, season, episode),
        scrobbleCoordinate = EpisodeCoordinate(target, seriesId, season, episode),
        premiumArtworkCoordinate = null,
        tvdbCoordinate = if (target == ProviderId.TVDB) EpisodeCoordinate(target, seriesId, season, episode) else null,
        tmdbCoordinate = null,
        confidence = CoordinateConfidence.HIGH,
        fallbackReason = null,
        evidence = emptyList(),
    )
}
```

- [ ] **Step 2: Implement**

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/projection/AnimeEpisodeCoordinateStore.kt
package com.nexio.tv.core.anime.projection

interface AnimeEpisodeCoordinateStore {
    fun get(
        groupKey: AnimeWorkGroupKey,
        source: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
    ): AnimeEpisodeProjection?

    fun put(
        groupKey: AnimeWorkGroupKey,
        source: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
        projection: AnimeEpisodeProjection,
    )

    fun invalidate(groupKey: AnimeWorkGroupKey)
}
```

```kotlin
// app/src/main/java/com/nexio/tv/core/anime/projection/InMemoryAnimeEpisodeCoordinateStore.kt
package com.nexio.tv.core.anime.projection

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InMemoryAnimeEpisodeCoordinateStore @Inject constructor() : AnimeEpisodeCoordinateStore {
    private data class Key(
        val groupKey: AnimeWorkGroupKey,
        val sourceKitsuId: String,
        val season: Int,
        val episode: Int,
        val target: EpisodeProjectionTarget,
    )

    private val cache = ConcurrentHashMap<Key, AnimeEpisodeProjection>()

    override fun get(groupKey: AnimeWorkGroupKey, source: SourceEpisodeCoordinate, target: EpisodeProjectionTarget) =
        cache[Key(groupKey, source.sourceKitsuId, source.season, source.episode, target)]

    override fun put(
        groupKey: AnimeWorkGroupKey,
        source: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
        projection: AnimeEpisodeProjection,
    ) {
        cache[Key(groupKey, source.sourceKitsuId, source.season, source.episode, target)] = projection
    }

    override fun invalidate(groupKey: AnimeWorkGroupKey) {
        cache.keys.removeIf { it.groupKey == groupKey }
    }
}
```

- [ ] **Step 3: Wire the store into DefaultAnimeSeasonProjectionResolver**

Add `private val store: AnimeEpisodeCoordinateStore` to the constructor; wrap `resolveEpisodeProjection` to consult the store first:

```kotlin
override suspend fun resolveEpisodeProjection(
    work: AnimeWorkIdentity,
    sourceEpisode: SourceEpisodeCoordinate,
    target: EpisodeProjectionTarget,
): AnimeEpisodeProjection {
    store.get(work.groupKey, sourceEpisode, target)?.let { return it }
    val computed = computeEpisodeProjection(work, sourceEpisode, target)
    store.put(work.groupKey, sourceEpisode, target, computed)
    return computed
}

// rename the previous method body to:
private fun computeEpisodeProjection(
    work: AnimeWorkIdentity,
    sourceEpisode: SourceEpisodeCoordinate,
    target: EpisodeProjectionTarget,
): AnimeEpisodeProjection { /* unchanged body from Task 1.8 */ }
```

- [ ] **Step 4: Run all projection tests**

```
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.projection.*"
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/anime/projection/AnimeEpisodeCoordinateStore.kt app/src/main/java/com/nexio/tv/core/anime/projection/InMemoryAnimeEpisodeCoordinateStore.kt app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt app/src/test/java/com/nexio/tv/core/anime/projection/InMemoryAnimeEpisodeCoordinateStoreTest.kt
git commit -m "feat(projection): cache episode projections in-memory by groupKey"
```

## Task 1.10: Hilt module — bind the resolver and store

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/di/AnimeProjectionModule.kt`

- [ ] **Step 1: Implement the module**

```kotlin
// app/src/main/java/com/nexio/tv/core/di/AnimeProjectionModule.kt
package com.nexio.tv.core.di

import com.nexio.tv.core.anime.projection.AnimeEpisodeCoordinateStore
import com.nexio.tv.core.anime.projection.AnimeSeasonProjectionResolver
import com.nexio.tv.core.anime.projection.DefaultAnimeSeasonProjectionResolver
import com.nexio.tv.core.anime.projection.InMemoryAnimeEpisodeCoordinateStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AnimeProjectionModule {
    @Binds
    abstract fun bindResolver(impl: DefaultAnimeSeasonProjectionResolver): AnimeSeasonProjectionResolver

    @Binds
    abstract fun bindStore(impl: InMemoryAnimeEpisodeCoordinateStore): AnimeEpisodeCoordinateStore
}
```

- [ ] **Step 2: Build to verify Hilt graph compiles**

```
./gradlew :app:compileDebugKotlin :app:kspDebugKotlin
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/di/AnimeProjectionModule.kt
git commit -m "feat(projection): wire AnimeProjectionModule into Hilt graph"
```

## Task 1.11: Wire MetaDetailsViewModel — use AnimeSeasonPresentation for anime detail

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`

This task replaces the ad-hoc Kitsu episode-handling path in `applyMetaWithEnrichment` with a call into `AnimeSeasonProjectionResolver.resolveSeasonPresentation` when the route picked KITSU. It does NOT change the non-anime path.

- [ ] **Step 1: Inject the resolver**

Add to the constructor of `MetaDetailsViewModel`:

```kotlin
private val animeSeasonProjectionResolver: AnimeSeasonProjectionResolver,
```

- [ ] **Step 2: Replace the Kitsu branch in applyMetaWithEnrichment**

Inside `applyMetaWithEnrichment` at `MetaDetailsViewModel.kt:760-805`, after `enrichMeta(...)` returns and before the `blockedForMandatoryEpisodes` check, branch on `enrichment.isAnimeDetail`:

```kotlin
if (enrichment.isAnimeDetail) {
    val parsedKitsu = AnimeStremioId.parse(enrichment.meta.id)
    val sourceKitsuId = parsedKitsu?.takeIf { it.source == AnimeIdSource.KITSU }?.value
    if (sourceKitsuId != null) {
        val work = animeSeasonProjectionResolver.resolveWork(
            AnimeSourceIdentity(sourceKitsuId = sourceKitsuId, animeStremioId = parsedKitsu)
        )
        val presentation = animeSeasonProjectionResolver.resolveSeasonPresentation(
            work = work,
            sourceKitsuId = sourceKitsuId,
            requestedSeason = preferredSeason,
        )
        applyAnimeSeasonPresentation(enrichment, presentation)
        return
    }
}
// existing non-anime path follows unchanged
```

- [ ] **Step 3: Implement `applyAnimeSeasonPresentation`**

Add a new private method that hydrates the UI from the presentation: it should pick the `episodesKitsuMemberId` for `presentation.selectedSeason`, call `kitsuMetadataService.fetchEpisodeEnrichment("kitsu:$memberId", SERIES, listOf(presentation.selectedSeason))`, build the resulting Meta videos with the correct (season, episode) keys, then call `applyMeta(...)`. Crucially, this path uses the presentation's selected season — NOT a hardcoded 1 — so MHA Season 3 hydrates with `(3, n)` videos.

```kotlin
private suspend fun applyAnimeSeasonPresentation(
    enrichment: DetailMetadataEnrichment,
    presentation: AnimeSeasonPresentation,
) {
    val tab = presentation.seasons.firstOrNull { it.seasonNumber == presentation.selectedSeason }
    val memberId = tab?.episodesKitsuMemberId
    if (memberId == null) {
        _uiState.update {
            it.copy(
                isLoading = false,
                error = "No Kitsu source for season ${presentation.selectedSeason}"
            )
        }
        return
    }
    val episodeMap = kitsuMetadataService.fetchEpisodeEnrichment(
        rawId = "kitsu:$memberId",
        mediaKind = ContentMediaKind.SERIES,
        seasonNumbers = listOf(presentation.selectedSeason),
    )
    if (episodeMap.isEmpty()) {
        _uiState.update {
            it.copy(
                isLoading = false,
                error = "Episode metadata is unavailable for ${enrichment.meta.name}."
            )
        }
        return
    }
    val hydrated = enrichment.meta.copy(
        videos = buildKitsuEpisodeVideos(
            seriesId = enrichment.meta.id,
            episodeLabel = context.getString(R.string.episodes_episode),
            episodeMap = episodeMap,
        )
    )
    applyMeta(hydrated)
    Log.i(TAG, "detail.anime_presentation_applied metaId=${enrichment.meta.id} " +
        "season=${presentation.selectedSeason} videos=${hydrated.videos.size} " +
        "source=${presentation.source} confidence=${presentation.confidence}")
}
```

- [ ] **Step 4: Re-run the MHA Season 3 regression test**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsViewModelMhaSeason3Test
```

Expected: PASS, with the new path. Update the test if it asserted the old internal route — the user-visible behaviour (videos.size = 25, error = null) is what matters.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt
git commit -m "feat(detail): route Kitsu detail through AnimeSeasonProjectionResolver

For meta whose route picked KITSU, hydrate episodes from the projection
resolver's selected season (MHA S3 → S3 episodes) instead of the
ad-hoc applyTvEpisodeEnrichment path that defaulted to season 1."
```

## Task 1.12: Wire TrackingScrobbleService — project anime ids before scrobble

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt`

- [ ] **Step 1: Inject the resolver**

Add `private val animeSeasonProjectionResolver: AnimeSeasonProjectionResolver` to `DefaultTrackingScrobbleService`'s constructor.

- [ ] **Step 2: Update `toTraktItem` to resolve anime-native ids through the projection**

Replace the current `toTraktItem` implementation:

```kotlin
private suspend fun toTraktItem(item: TrackingScrobbleItem): TraktScrobbleItem? {
    val contentId = item.contentId()
    val animeId = AnimeStremioId.parse(contentId)
    if (animeId != null && animeId.source == AnimeIdSource.KITSU) {
        return projectAnimeToTraktItem(item, animeId.value)
    }
    val ids = toTraktIds(parseContentIds(contentId))
    if (!ids.hasAnyId()) {
        rejectionReporter.reportRejection(
            contentId = contentId,
            reason = ScrobbleRejectionReason.NO_PARSEABLE_IDS,
            provider = TrackingProvider.TRAKT,
        )
        return null
    }
    return when (item) {
        is TrackingScrobbleItem.Movie -> TraktScrobbleItem.Movie(item.title, item.year, ids)
        is TrackingScrobbleItem.Episode -> TraktScrobbleItem.Episode(
            item.showTitle, item.showYear, ids, item.season, item.number, item.episodeTitle
        )
    }
}

private suspend fun projectAnimeToTraktItem(
    item: TrackingScrobbleItem,
    sourceKitsuId: String,
): TraktScrobbleItem? {
    val work = animeSeasonProjectionResolver.resolveWork(
        AnimeSourceIdentity(sourceKitsuId = sourceKitsuId, animeStremioId = null)
    )
    return when (item) {
        is TrackingScrobbleItem.Movie -> {
            val ids = work.providerIds.toTraktIds()
            if (!ids.hasAnyId()) {
                rejectionReporter.reportRejection(item.contentId, ScrobbleRejectionReason.EMPTY_ID_BUNDLE, TrackingProvider.TRAKT)
                null
            } else TraktScrobbleItem.Movie(item.title, item.year, ids)
        }
        is TrackingScrobbleItem.Episode -> {
            val projection = animeSeasonProjectionResolver.resolveEpisodeProjection(
                work = work,
                sourceEpisode = SourceEpisodeCoordinate(sourceKitsuId, item.season, item.number),
                target = EpisodeProjectionTarget.TRAKT_SCROBBLE,
            )
            val coord = projection.scrobbleCoordinate
            if (coord == null) {
                rejectionReporter.reportRejection(
                    contentId = item.contentId,
                    reason = ScrobbleRejectionReason.ANIME_COORDINATE_UNRESOLVED,
                    provider = TrackingProvider.TRAKT,
                )
                null
            } else {
                val ids = work.providerIds.toTraktIds().copy(
                    tvdb = coord.seriesId.toIntOrNull().takeIf { coord.provider.name == "TVDB" },
                )
                TraktScrobbleItem.Episode(
                    showTitle = item.showTitle,
                    showYear = item.showYear,
                    showIds = ids,
                    season = coord.season,
                    number = coord.episode,
                    episodeTitle = item.episodeTitle,
                )
            }
        }
    }
}

private fun ProviderIds.toTraktIds(): TraktIdsDto = TraktIdsDto(
    imdb = imdb,
    tmdb = tmdb?.toIntOrNull(),
    tvdb = tvdb?.toIntOrNull(),
    trakt = trakt?.toIntOrNull(),
)
```

- [ ] **Step 3: Update the scrobble entry methods to be suspend-aware**

The existing `scrobbleStart`/`scrobbleStop`/`scrobblePause` already are `suspend` — `toTraktItem` is now `suspend` too, so the calls inside compile cleanly.

- [ ] **Step 4: Add a new test for the projection happy path**

`app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleServiceAnimeProjectionTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.anime.projection.AnimeEpisodeProjection
import com.nexio.tv.core.anime.projection.AnimeGroupingConfidence
import com.nexio.tv.core.anime.projection.AnimeSeasonProjectionResolver
import com.nexio.tv.core.anime.projection.AnimeWorkGroupKey
import com.nexio.tv.core.anime.projection.AnimeWorkIdentity
import com.nexio.tv.core.anime.projection.CoordinateConfidence
import com.nexio.tv.core.anime.projection.EpisodeCoordinate
import com.nexio.tv.core.anime.projection.EpisodeProjectionTarget
import com.nexio.tv.core.anime.projection.SourceEpisodeCoordinate
import com.nexio.tv.core.playback.PlaybackOwnerContext
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TrackingScrobbleServiceAnimeProjectionTest {

    @Test
    fun `kitsu MHA S3 episode scrobbles with TVDB s3e1 ids`() = runBlocking {
        val trakt = mockk<TraktScrobbleService>(relaxed = true)
        val simkl = mockk<SimklScrobbleService>(relaxed = true)
        val providerState = mockk<TrackingProviderStateService>()
        val rejection = mockk<ScrobbleRejectionReporter>(relaxed = true)
        val resolver = mockk<AnimeSeasonProjectionResolver>()

        coEvery { providerState.currentState(any<Int>()) } returns EffectiveTrackingProviderState(
            effectiveProvider = TrackingProvider.TRAKT, traktAuthenticated = true, simklAuthenticated = false
        )
        val work = AnimeWorkIdentity(
            groupKey = AnimeWorkGroupKey("anime-work:tvdb:305074"),
            primaryKitsuId = "11469",
            memberKitsuIds = setOf("11469", "13881"),
            providerIds = ProviderIds(tvdb = "305074", imdb = "tt5626028", tmdb = "65930"),
            confidence = AnimeGroupingConfidence.HIGH,
            evidence = emptyList(),
        )
        coEvery { resolver.resolveWork(any()) } returns work
        coEvery { resolver.resolveEpisodeProjection(work, any(), EpisodeProjectionTarget.TRAKT_SCROBBLE) } returns
            AnimeEpisodeProjection(
                sourceKitsuId = "13881",
                sourceKitsuCoordinate = EpisodeCoordinate(ProviderId.KITSU, "13881", 3, 1),
                displayCoordinate = EpisodeCoordinate(ProviderId.TVDB, "305074", 3, 1),
                scrobbleCoordinate = EpisodeCoordinate(ProviderId.TVDB, "305074", 3, 1),
                premiumArtworkCoordinate = EpisodeCoordinate(ProviderId.TVDB, "305074", 3, 1),
                tvdbCoordinate = EpisodeCoordinate(ProviderId.TVDB, "305074", 3, 1),
                tmdbCoordinate = null,
                confidence = CoordinateConfidence.HIGH,
                fallbackReason = null,
                evidence = emptyList(),
            )

        val service = DefaultTrackingScrobbleService(
            traktScrobbleService = trakt,
            simklScrobbleService = simkl,
            trackingProviderStateService = providerState,
            rejectionReporter = rejection,
            animeSeasonProjectionResolver = resolver,
        )

        service.scrobbleStart(
            item = TrackingScrobbleItem.Episode(
                contentId = "kitsu:13881",
                showTitle = "Boku no Hero Academia 3",
                showYear = 2018,
                season = 3,
                number = 1,
                episodeTitle = "Game Start",
            ),
            progressPercent = 5f,
            owner = PlaybackOwnerContext(ownerProfileId = 1, ownerSessionId = "s1"),
        )

        coVerify {
            trakt.scrobbleStart(
                match { it is TraktScrobbleItem.Episode && it.season == 3 && it.number == 1 && it.showIds.tvdb == 305074 },
                5f, 1, any()
            )
        }
        coVerify(exactly = 0) { rejection.reportRejection(any(), any(), any()) }
    }
}
```

- [ ] **Step 5: Run, verify it passes**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.TrackingScrobbleServiceAnimeProjectionTest
```

- [ ] **Step 6: Run the previously-passing rejection test**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.TrackingScrobbleServiceAnimeRejectionTest
```

The earlier test from Task 0.6 may need an update because `toTraktItem` now resolves kitsu ids through the projection. Update the test setup to use a resolver that returns a work with no member ids → falls into `EMPTY_ID_BUNDLE` rejection. This still validates the original intent (no silent no-op).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleServiceAnimeProjectionTest.kt app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleServiceAnimeRejectionTest.kt
git commit -m "feat(scrobble): project Kitsu ids to TVDB coordinates before envelope build"
```

## Task 1.13: Wire Top-Posters thumbnail to use projected coordinates

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapter.kt`

- [ ] **Step 1: Inject the resolver and route episode-thumbnail requests through it**

Find the existing episode-thumbnail call site in `TopPostersMetadataProviderAdapter` (search for the function that builds the thumbnail URL with `season`/`episode` parameters). Inject `AnimeSeasonProjectionResolver` and, when the source contentId is a Kitsu id, replace the raw `(season, episode)` with `resolveEpisodeProjection(target = PREMIUM_THUMBNAIL).premiumArtworkCoordinate`.

If `premiumArtworkCoordinate` is null, return `null` from this adapter so `PosterRatingsUrlResolver` falls back to the primary thumbnail (preserves the local rating overlay path that already exists).

- [ ] **Step 2: Add a test**

`app/src/test/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapterAnimeProjectionTest.kt` — assert that for `kitsu:13881` season 3 episode 1, the adapter calls Top-Posters with `season=3 episode=1 id_type=tvdb media_id=305074` (HIGH confidence) and that for One Piece kitsu:12 season 1 episode 850 it returns null (LOW confidence → fallback to primary thumbnail).

- [ ] **Step 3: Run, verify it passes**

```
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.posters.TopPostersMetadataProviderAdapterAnimeProjectionTest
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapter.kt app/src/test/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapterAnimeProjectionTest.kt
git commit -m "feat(posters): use projected coordinates for Top-Posters episode thumbnails"
```

## Task 1.14: Wire Continue Watching identity through projection

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`

- [ ] **Step 1: For continue-watching items whose `contentId` is `kitsu:*`, project to the canonical TVDB coordinate**

This makes Continue Watching stable across user actions that re-resolve the same content from a different upstream (e.g. switching between a Kitsu rail click and a Trakt watchlist click). Without projection, the same episode would have two distinct identity keys and appear twice in CW.

Inject `AnimeSeasonProjectionResolver` into the file's enrichment pipeline. Where `ContinueWatchingItem.contentId` is a Kitsu id, resolve `EpisodeProjectionTarget.CONTINUE_WATCHING` and use `displayCoordinate.identityKey` as the dedup key.

- [ ] **Step 2: Test**

Add a regression test asserting that two Continue Watching entries with the same projected coordinate (one originating from `kitsu:13881` S3E1, one from a Trakt watchlist with TVDB S3E1) collapse to one row.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingProjectionTest.kt
git commit -m "feat(home): use projected coordinates for Continue Watching identity"
```

## Task 1.15: Trace events — anime.* + tracking.scrobble_rejected

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/trace/AnimeProjectionTraceEvents.kt`
- Modify: `DefaultAnimeSeasonProjectionResolver`, `ScrobbleRejectionReporter` to emit through it

- [ ] **Step 1: Define the event helper**

```kotlin
// app/src/main/java/com/nexio/tv/core/trace/AnimeProjectionTraceEvents.kt
package com.nexio.tv.core.trace

import com.nexio.tv.core.anime.projection.AnimeEpisodeProjection
import com.nexio.tv.core.anime.projection.AnimeSeasonPresentation
import com.nexio.tv.core.anime.projection.AnimeWorkIdentity
import com.nexio.tv.core.anime.projection.EpisodeProjectionTarget
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnimeProjectionTraceEvents @Inject constructor(
    private val sink: TraceMetadataEvents,
) {
    fun emitWorkResolved(identity: AnimeWorkIdentity) { /* sink.emit(...) */ }
    fun emitSeasonProjectionBuilt(presentation: AnimeSeasonPresentation) { /* ... */ }
    fun emitEpisodeCoordinateResolved(projection: AnimeEpisodeProjection, target: EpisodeProjectionTarget) { /* ... */ }
    fun emitEpisodeCoordinateUnresolved(sourceKitsuId: String, season: Int, episode: Int, target: EpisodeProjectionTarget, reason: String) { /* ... */ }
}
```

The actual sink call goes through the existing `TraceMetadataEvents` infrastructure (referenced in `MetadataRouterFacade.kt:55`). Re-use that pattern.

- [ ] **Step 2: Wire the events into the resolver and reporter**

Inject `AnimeProjectionTraceEvents` into `DefaultAnimeSeasonProjectionResolver` and emit at the end of each public method. Wire `ScrobbleRejectionReporter` to emit `tracking.scrobble_rejected` through the same trace bus.

- [ ] **Step 3: Add a smoke test that verifies events fire**

```
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.projection.*"
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/trace/AnimeProjectionTraceEvents.kt app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt app/src/main/java/com/nexio/tv/data/repository/ScrobbleRejectionReporter.kt
git commit -m "feat(trace): emit anime.* and tracking.scrobble_rejected events"
```

## Task 1.16: Phase 1 verification — full suite + manual smoke

- [ ] **Step 1: Full unit-test suite**

```
./gradlew :app:testDebugUnitTest
```

- [ ] **Step 2: Build and install**

```
./gradlew :app:assembleDebug
adb -s 192.168.50.98:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: Manual smoke**

```
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 logcat -v threadtime "MetaDetailsViewModel:V" "TraktScrobbleApi:V" "ScrobbleRejection:V" "AnimeProjection:V" "*:S" > /tmp/phase1_smoke.log &
```

On device:
1. Open MHA Season 3 from Kitsu rail → expect 25 episodes.
2. Play S3E1 → check `TraktScrobbleApi` log fires a request body with `season=3 number=1 ids.tvdb=305074`.
3. Open One Piece from Kitsu rail → expect a single flat season tab.
4. Play OP S1E850 → expect `ScrobbleRejection` log with `reason=ANIME_COORDINATE_UNRESOLVED`, NO `TraktScrobbleApi` request line.

Stop logcat, attach `/tmp/phase1_smoke.log` to the PR description.

- [ ] **Step 4: Tag**

```bash
git tag -a phase-1-anime-projection -m "Anime season/episode projection layer wired through detail, scrobble, posters, CW"
```

---

## Phase 2 + Phase 3 — explicitly out of scope of this plan

These need their own plans:

- **Phase 2 — `KitsuRailFranchiseGrouper`**: read `AnimeWorkIdentity.memberKitsuIds` from this plan, then add a UI-side grouper that collapses MHA S1/S2/S3 cards into one. Suggested file: `docs/superpowers/plans/2026-05-12-kitsu-rail-franchise-grouping.md`. Pre-requisite: this plan (Phase 0 + 1) merged.
- **Phase 3 — Curated season-mapping overlay**: design a `AnimeSeasonMappingOverlay` JSON asset with `parentKitsu`, `tvdbSeason`, `tvdbEpisodeOffset` per Kitsu id; choose an upstream (Trakt anime mappings, manami, manual); add a generator script. Suggested file: `docs/superpowers/plans/2026-05-19-anime-season-mapping-overlay.md`. Pre-requisite: this plan merged. Once shipped, `DefaultAnimeSeasonProjectionResolver.computeEpisodeProjection` should consult the overlay and upgrade One Piece confidence from LOW to HIGH.

---

## Self-Review

**Spec coverage:** every numbered finding from the RCA proof section maps to a Phase 0 or Phase 1 task: RC2 → Task 1.6 (work grouping), RC3 → Tasks 0.3/0.4 + Task 1.7 (correct season selection), RC4 → Tasks 0.6/0.7/0.8 + Task 1.12 (visible reject + projection), RC5 → Task 1.8 (refuse low-confidence scrobble). RC1 (rail dedup) is explicitly deferred to Phase 2.

**Placeholders:** Task 1.13 (Top-Posters wiring) and Task 1.14 (Continue Watching wiring) each describe the change at high level rather than showing the exact replaced lines, because the replacement points are scattered across helper functions in those files. The implementing engineer must read the files and apply the same pattern as Task 1.12 (inject resolver, branch on `AnimeStremioId.parse(contentId)?.source == KITSU`, route through `resolveEpisodeProjection` with the matching `EpisodeProjectionTarget`). The pattern is fully shown in Task 1.12 so this is "show the same code shape" not "TBD".

**Type consistency:** `AnimeSeasonTab.episodesKitsuMemberId` is consistently named across Tasks 1.4, 1.7, 1.11. `EpisodeCoordinate.identityKey` is referenced in Tasks 1.1 and 1.14. `ScrobbleRejectionReason` enum values are consistent across Tasks 0.7, 0.8, 1.12. `EpisodeProjectionTarget` enum values are consistent across Tasks 1.4, 1.8, 1.12, 1.13, 1.14.

**Known limitation:** Task 1.7's flat-Kitsu detection uses a single-member + episode-count threshold. This is heuristic and correctly identifies One Piece, but may misclassify edge cases (e.g. an OVA with 60 episodes). Tighten in Phase 3 with overlay data.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-05-anime-season-episode-projection.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration. Best for this plan because Phase 0 commits should land in a hot-fix branch as soon as they're green, independently of Phase 1.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
