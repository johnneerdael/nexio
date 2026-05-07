# Anime Season/Episode Projection — Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate duplicate multi-season Kitsu rail cards (MHA S1/S2/S3 → one card) and remove the repeated `fetchEpisodeEnrichment` network round-trips that `computeEpisodeProjection` currently triggers on every episode-target pair.

**Architecture:** Two independent, small tasks. Task 2.1 adds an `AnimeSeasonPresentationCache` that memoises `resolveSeasonPresentation` results inside `DefaultAnimeSeasonProjectionResolver` — eliminating the N×M Kitsu episode fetches per `computeEpisodeProjection` call. Task 2.2 adds a `KitsuRailFranchiseGrouper` that runs after `KitsuRailPreviewMapper` inside `KitsuDiscoveryService.mapCatalogResults()`, collapsing items that share a TVDB/IMDB/TMDB work identity into one enriched rail card. Tasks share no code path and can be done in either order.

**Tech Stack:** Kotlin (JDK 17), Hilt, Moshi, JUnit 4, MockK, `kotlinx-coroutines-test`. Tests mirror source at `app/src/test/java/...`. Conventions: `org.junit.Test`, `org.junit.Assert.*`, backtick test names, constructor lambda injection for unit-testability.

---

## Background

### Why Task 2.1 is needed

`DefaultAnimeSeasonProjectionResolver.computeEpisodeProjection` calls `resolveSeasonPresentation(work, sourceKitsuId, requestedSeason = null)` to detect flat-franchise status. `resolveSeasonPresentation` fetches Kitsu episodes for **every member Kitsu ID** in the work group — for MHA with 8 seasonal resources that is 8 network calls. The existing `AnimeEpisodeCoordinateStore` caches per-episode projections, but `resolveSeasonPresentation` itself is uncached. On first load of a detail screen, every unique `(episode, target)` pair triggers a full set of Kitsu episode fetches. Adding an `AnimeSeasonPresentationCache` keyed by `(AnimeWorkGroupKey, cleanSourceKitsuId)` reduces this to one fetch set per source Kitsu resource regardless of how many episodes are projected.

### Why Task 2.2 is needed

`KitsuDiscoveryService.mapCatalogResults` maps each `KitsuAnimeResource` 1:1 to a `RailItemPreview`. A Kitsu trending catalog can contain MHA S1 (`kitsu:11469`), MHA S2 (`kitsu:12268`), and MHA S3 (`kitsu:13881`) as three separate results. All three share `tvdb:305074` in `AnimeIdMapRecord`. The rail displays three cards where one would do. `KitsuRailFranchiseGrouper` uses `AnimeIdMappingService` to detect shared TVDB/IMDB/TMDB identity and collapses duplicates to the first occurrence, also enriching the representative's `stableIds` with the cross-provider IDs for downstream artwork/metadata resolution.

---

## File Structure

**Task 2.1 files:**
- **Create** `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonPresentationCache.kt` — interface + key contract
- **Create** `app/src/main/java/com/nexio/tv/core/anime/projection/InMemoryAnimeSeasonPresentationCache.kt` — `ConcurrentHashMap`-backed singleton
- **Modify** `app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt` — add `presentationCache` constructor param; wrap `resolveSeasonPresentation` with cache read/write
- **Modify** `app/src/main/java/com/nexio/tv/core/di/AnimeProjectionModule.kt` — bind `InMemoryAnimeSeasonPresentationCache` as `AnimeSeasonPresentationCache`
- **Create test** `app/src/test/java/com/nexio/tv/core/anime/projection/InMemoryAnimeSeasonPresentationCacheTest.kt`
- **Modify test** `app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverPresentationTest.kt` — add `presentationCache` arg to every resolver construction; add cache-hit and requestedSeason-override tests
- **Modify test** `app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverEpisodeTest.kt` — add `presentationCache` to `resolverFor()` helper
- **Modify test** `app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverWorkTest.kt` — add `presentationCache` to every resolver construction

**Task 2.2 files:**
- **Create** `app/src/main/java/com/nexio/tv/data/integration/railpreview/KitsuRailFranchiseGrouper.kt` — pure grouping logic, no suspend
- **Modify** `app/src/main/java/com/nexio/tv/data/repository/KitsuDiscoveryService.kt` — add `grouper: KitsuRailFranchiseGrouper` constructor param; apply in `mapCatalogResults`
- **Create test** `app/src/test/java/com/nexio/tv/data/integration/railpreview/KitsuRailFranchiseGrouperTest.kt`
- **Modify test** `app/src/test/java/com/nexio/tv/data/repository/KitsuDiscoveryServiceTest.kt` — update `createService()` to pass grouper; add one integration test

---

## Task 2.1: AnimeSeasonPresentationCache

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonPresentationCache.kt`
- Create: `app/src/main/java/com/nexio/tv/core/anime/projection/InMemoryAnimeSeasonPresentationCache.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/AnimeProjectionModule.kt`
- Create test: `app/src/test/java/com/nexio/tv/core/anime/projection/InMemoryAnimeSeasonPresentationCacheTest.kt`
- Modify test: `app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverPresentationTest.kt`
- Modify test: `app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverEpisodeTest.kt`
- Modify test: `app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverWorkTest.kt`

---

- [ ] **Step 1: Write failing cache tests**

Create `app/src/test/java/com/nexio/tv/core/anime/projection/InMemoryAnimeSeasonPresentationCacheTest.kt`:

```kotlin
package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryAnimeSeasonPresentationCacheTest {

    private val groupKey = AnimeWorkGroupKey("anime-work:tvdb:305074")
    private val work = AnimeWorkIdentity(
        groupKey = groupKey,
        primaryKitsuId = "11469",
        memberKitsuIds = setOf("11469", "13881"),
        providerIds = ProviderIds(tvdb = "305074"),
        confidence = AnimeGroupingConfidence.HIGH,
        evidence = emptyList(),
    )

    @Test
    fun `get returns null when nothing cached`() {
        val cache = InMemoryAnimeSeasonPresentationCache()
        assertNull(cache.get(groupKey, "13881"))
    }

    @Test
    fun `put and get round-trip by group key and source kitsu id`() {
        val cache = InMemoryAnimeSeasonPresentationCache()
        val presentation = presentation(work, selectedSeason = 3)

        cache.put(groupKey, "13881", presentation)

        assertEquals(presentation, cache.get(groupKey, "13881"))
    }

    @Test
    fun `different source kitsu id does not hit same entry`() {
        val cache = InMemoryAnimeSeasonPresentationCache()
        cache.put(groupKey, "13881", presentation(work, selectedSeason = 3))

        assertNull(cache.get(groupKey, "11469"))
    }

    @Test
    fun `different group key does not hit same entry`() {
        val cache = InMemoryAnimeSeasonPresentationCache()
        cache.put(groupKey, "13881", presentation(work, selectedSeason = 3))
        val otherKey = AnimeWorkGroupKey("anime-work:tvdb:81797")

        assertNull(cache.get(otherKey, "13881"))
    }

    @Test
    fun `invalidate removes all entries for a group key`() {
        val cache = InMemoryAnimeSeasonPresentationCache()
        cache.put(groupKey, "11469", presentation(work, selectedSeason = 1))
        cache.put(groupKey, "13881", presentation(work, selectedSeason = 3))

        cache.invalidate(groupKey)

        assertNull(cache.get(groupKey, "11469"))
        assertNull(cache.get(groupKey, "13881"))
    }

    @Test
    fun `invalidate does not remove entries for other group keys`() {
        val cache = InMemoryAnimeSeasonPresentationCache()
        val otherKey = AnimeWorkGroupKey("anime-work:tvdb:81797")
        val otherWork = work.copy(groupKey = otherKey, providerIds = ProviderIds(tvdb = "81797"))
        cache.put(otherKey, "12", presentation(otherWork, selectedSeason = 1))

        cache.invalidate(groupKey)

        assertEquals(presentation(otherWork, selectedSeason = 1), cache.get(otherKey, "12"))
    }

    private fun presentation(work: AnimeWorkIdentity, selectedSeason: Int) = AnimeSeasonPresentation(
        work = work,
        seasons = listOf(AnimeSeasonTab(seasonNumber = selectedSeason, title = null, episodeCount = 25, episodesKitsuMemberId = "13881", isFlatFallback = false)),
        selectedSeason = selectedSeason,
        source = SeasonPresentationSource.KITSU_SEASON_NUMBERS,
        confidence = CoordinateConfidence.HIGH,
    )
}
```

- [ ] **Step 2: Run to verify tests fail**

```
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.core.anime.projection.InMemoryAnimeSeasonPresentationCacheTest" 2>&1 | grep -E "FAILED|error:|BUILD"
```

Expected: `BUILD FAILED` — `InMemoryAnimeSeasonPresentationCache` does not exist.

- [ ] **Step 3: Create `AnimeSeasonPresentationCache` interface**

Create `app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonPresentationCache.kt`:

```kotlin
package com.nexio.tv.core.anime.projection

interface AnimeSeasonPresentationCache {
    fun get(groupKey: AnimeWorkGroupKey, sourceKitsuId: String): AnimeSeasonPresentation?
    fun put(groupKey: AnimeWorkGroupKey, sourceKitsuId: String, presentation: AnimeSeasonPresentation)
    fun invalidate(groupKey: AnimeWorkGroupKey)
}
```

- [ ] **Step 4: Create `InMemoryAnimeSeasonPresentationCache`**

Create `app/src/main/java/com/nexio/tv/core/anime/projection/InMemoryAnimeSeasonPresentationCache.kt`:

```kotlin
package com.nexio.tv.core.anime.projection

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InMemoryAnimeSeasonPresentationCache @Inject constructor() : AnimeSeasonPresentationCache {

    private data class Key(val groupKey: AnimeWorkGroupKey, val sourceKitsuId: String)

    private val cache = ConcurrentHashMap<Key, AnimeSeasonPresentation>()

    override fun get(groupKey: AnimeWorkGroupKey, sourceKitsuId: String): AnimeSeasonPresentation? =
        cache[Key(groupKey, sourceKitsuId)]

    override fun put(groupKey: AnimeWorkGroupKey, sourceKitsuId: String, presentation: AnimeSeasonPresentation) {
        cache[Key(groupKey, sourceKitsuId)] = presentation
    }

    override fun invalidate(groupKey: AnimeWorkGroupKey) {
        cache.keys.removeIf { it.groupKey == groupKey }
    }
}
```

- [ ] **Step 5: Run cache tests to verify they pass**

```
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.core.anime.projection.InMemoryAnimeSeasonPresentationCacheTest" 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [ ] **Step 6: Write failing cache-integration tests in `DefaultAnimeSeasonProjectionResolverPresentationTest`**

Add three new tests at the end of the existing `DefaultAnimeSeasonProjectionResolverPresentationTest` class. Also add `presentationCache = InMemoryAnimeSeasonPresentationCache()` to the two existing resolver constructions in the file (Step 8 fixes the compile errors those cause — add the arg now to prove the test compiles after Step 8).

Add these tests. Note these will compile only after Step 8:

```kotlin
@Test
fun `resolveSeasonPresentation returns cached result without calling fetchEpisodeEnrichment again`() = runBlocking {
    val asset = AnimeIdMapAsset(
        schemaVersion = 1,
        recordsByKitsu = mapOf("13881" to series("13881", tvdb = "305074"))
    )
    val mapping = AnimeIdMappingService(assetProvider = { asset })
    val kitsu = mockk<KitsuMetadataService>()
    var callCount = 0
    coEvery { kitsu.fetchEpisodeEnrichment("kitsu:13881", ContentMediaKind.SERIES, emptyList()) } answers {
        callCount++
        (1..25).associate { (3 to it) to kitsuEp(season = 3, ep = it) }
    }
    val resolver = DefaultAnimeSeasonProjectionResolver(
        idMappingService = mapping,
        kitsuMetadataService = kitsu,
        store = InMemoryAnimeEpisodeCoordinateStore(),
        traceEvents = mockk(relaxed = true),
        presentationCache = InMemoryAnimeSeasonPresentationCache(),
    )
    val work = resolver.resolveWork(AnimeSourceIdentity(sourceKitsuId = "13881", animeStremioId = null))

    resolver.resolveSeasonPresentation(work, sourceKitsuId = "13881", requestedSeason = null)
    resolver.resolveSeasonPresentation(work, sourceKitsuId = "13881", requestedSeason = null)

    assertEquals("fetchEpisodeEnrichment must be called exactly once", 1, callCount)
}

@Test
fun `resolveSeasonPresentation cache hit with non-null requestedSeason overrides selectedSeason`() = runBlocking {
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
        store = InMemoryAnimeEpisodeCoordinateStore(),
        traceEvents = mockk(relaxed = true),
        presentationCache = InMemoryAnimeSeasonPresentationCache(),
    )
    val work = resolver.resolveWork(AnimeSourceIdentity(sourceKitsuId = "13881", animeStremioId = null))
    // Prime cache with requestedSeason = null (auto → selects 3)
    resolver.resolveSeasonPresentation(work, sourceKitsuId = "13881", requestedSeason = null)

    // Cache hit with explicit requestedSeason = 1 should return selectedSeason = 1
    val presentation = resolver.resolveSeasonPresentation(work, sourceKitsuId = "13881", requestedSeason = 1)

    assertEquals(1, presentation.selectedSeason)
    assertEquals(SeasonPresentationSource.KITSU_SEASON_NUMBERS, presentation.source)
}
```

- [ ] **Step 7: Run to verify the new tests fail for the right reason**

```
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.core.anime.projection.DefaultAnimeSeasonProjectionResolverPresentationTest" 2>&1 | grep -E "FAILED|error:|BUILD"
```

Expected: `BUILD FAILED` — `DefaultAnimeSeasonProjectionResolver` constructor has no `presentationCache` parameter yet.

- [ ] **Step 8: Add `presentationCache` param to `DefaultAnimeSeasonProjectionResolver` and wire cache into `resolveSeasonPresentation`**

Full replacement of `DefaultAnimeSeasonProjectionResolver.kt`. The constructor gains `presentationCache`; `resolveSeasonPresentation` gets cache read before the expensive work and cache write after:

```kotlin
package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.anime.KitsuMetadataService
import com.nexio.tv.core.trace.AnimeProjectionTraceEvents
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAnimeSeasonProjectionResolver @Inject constructor(
    private val idMappingService: AnimeIdMappingService,
    private val kitsuMetadataService: KitsuMetadataService,
    private val store: AnimeEpisodeCoordinateStore,
    private val traceEvents: AnimeProjectionTraceEvents,
    private val presentationCache: AnimeSeasonPresentationCache,
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
        val result = AnimeWorkIdentity(
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
        traceEvents.emitWorkResolved(result)
        return result
    }

    override suspend fun resolveSeasonPresentation(
        work: AnimeWorkIdentity,
        sourceKitsuId: String,
        requestedSeason: Int?,
    ): AnimeSeasonPresentation {
        val cleanSourceId = sourceKitsuId.removePrefix("kitsu:")

        // Cache hit: return the memoised result, overriding selectedSeason if the caller
        // requests a specific season that exists in the cached tab list.
        presentationCache.get(work.groupKey, cleanSourceId)?.let { cached ->
            return if (requestedSeason != null && cached.seasons.any { it.seasonNumber == requestedSeason })
                cached.copy(selectedSeason = requestedSeason)
            else
                cached
        }

        // Cache miss: build from scratch (may call fetchEpisodeEnrichment for each member).
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

        val isFlatFranchise = seasonToMember.size == 1
            && (perMember[cleanSourceId]?.size ?: 0) >= FLAT_KITSU_MIN_EPISODES
        val source = if (isFlatFranchise) SeasonPresentationSource.KITSU_FLAT_FALLBACK
                     else SeasonPresentationSource.KITSU_SEASON_NUMBERS

        val sourceSeasons = perMember[cleanSourceId]?.keys?.map { it.first }?.toSet().orEmpty()
        val autoSelected = sourceSeasons.minOrNull()
            ?: seasonToMember.keys.minOrNull()
            ?: 1
        val defaultSelected = requestedSeason?.takeIf { seasonToMember.containsKey(it) } ?: autoSelected

        val tabs = seasonToMember.entries
            .sortedBy { it.key }
            .map { (season, memberId) ->
                AnimeSeasonTab(
                    seasonNumber = season,
                    title = null,
                    episodeCount = seasonToCount[season],
                    episodesKitsuMemberId = memberId,
                    isFlatFallback = isFlatFranchise,
                )
            }

        val presentation = AnimeSeasonPresentation(
            work = work,
            seasons = tabs,
            selectedSeason = defaultSelected,
            source = source,
            confidence = if (isFlatFranchise) CoordinateConfidence.LOW else CoordinateConfidence.HIGH,
        )
        traceEvents.emitSeasonProjectionBuilt(presentation)
        // Store under the auto-selected season so requestedSeason callers can override cheaply.
        presentationCache.put(work.groupKey, cleanSourceId, presentation.copy(selectedSeason = autoSelected))
        return presentation
    }

    private companion object {
        private const val FLAT_KITSU_MIN_EPISODES = 50
    }

    override suspend fun resolveEpisodeProjection(
        work: AnimeWorkIdentity,
        sourceEpisode: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
    ): AnimeEpisodeProjection {
        store.get(work.groupKey, sourceEpisode, target)?.let { return it }
        val computed = computeEpisodeProjection(work, sourceEpisode, target)
        store.put(work.groupKey, sourceEpisode, target, computed)
        val isScrobbleTarget = target == EpisodeProjectionTarget.TRAKT_SCROBBLE || target == EpisodeProjectionTarget.SIMKL_SCROBBLE
        if (computed.scrobbleCoordinate != null || !isScrobbleTarget) {
            traceEvents.emitEpisodeCoordinateResolved(computed, target)
        } else {
            traceEvents.emitEpisodeCoordinateUnresolved(
                sourceKitsuId = sourceEpisode.sourceKitsuId,
                season = sourceEpisode.season,
                episode = sourceEpisode.episode,
                target = target,
                fallbackReason = computed.fallbackReason,
            )
        }
        return computed
    }

    private suspend fun computeEpisodeProjection(
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

        val presentation = resolveSeasonPresentation(work, sourceEpisode.sourceKitsuId, requestedSeason = null)
        val isFlatFranchise = presentation.source == SeasonPresentationSource.KITSU_FLAT_FALLBACK

        val tvdbCoord = tvdbId?.let { id ->
            if (isFlatFranchise) null
            else EpisodeCoordinate(ProviderId.TVDB, id, sourceEpisode.season, sourceEpisode.episode)
        }
        val tmdbCoord = tmdbId?.let { id ->
            if (isFlatFranchise) null
            else EpisodeCoordinate(ProviderId.TMDB, id, sourceEpisode.season, sourceEpisode.episode)
        }

        val confidence = when {
            isFlatFranchise -> CoordinateConfidence.LOW
            tvdbCoord != null -> CoordinateConfidence.HIGH
            tmdbCoord != null -> CoordinateConfidence.MEDIUM
            else -> CoordinateConfidence.UNKNOWN
        }
        val fallbackReason = when {
            isFlatFranchise -> FallbackReason.LOW_CONFIDENCE_FLAT_KITSU
            tvdbCoord == null && tmdbCoord == null -> FallbackReason.NO_TVDB_MAPPING
            else -> null
        }

        val scrobbleCoord = when (target) {
            EpisodeProjectionTarget.TRAKT_SCROBBLE,
            EpisodeProjectionTarget.SIMKL_SCROBBLE ->
                if (confidence == CoordinateConfidence.HIGH) tvdbCoord else null
            else -> tvdbCoord
        }
        val artworkCoord = if (confidence != CoordinateConfidence.LOW) (tvdbCoord ?: tmdbCoord) else null
        val displayCoord = tvdbCoord ?: sourceKitsuCoord

        val targetCoordinate = when (target) {
            EpisodeProjectionTarget.UI_DISPLAY -> tvdbCoord ?: sourceKitsuCoord
            EpisodeProjectionTarget.TRAKT_SCROBBLE,
            EpisodeProjectionTarget.SIMKL_SCROBBLE -> scrobbleCoord
            EpisodeProjectionTarget.PREMIUM_THUMBNAIL -> artworkCoord
            EpisodeProjectionTarget.CONTINUE_WATCHING -> tvdbCoord ?: tmdbCoord ?: sourceKitsuCoord
            EpisodeProjectionTarget.EPISODE_RATING -> tvdbCoord ?: tmdbCoord
        }

        return AnimeEpisodeProjection(
            sourceKitsuId = sourceEpisode.sourceKitsuId,
            sourceKitsuCoordinate = sourceKitsuCoord,
            displayCoordinate = displayCoord,
            targetCoordinate = targetCoordinate,
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
                if (isFlatFranchise) "flat-franchise=true" else null,
            ),
        )
    }

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

- [ ] **Step 9: Update existing test constructors in Presentation, Episode, and Work test files**

In `DefaultAnimeSeasonProjectionResolverPresentationTest.kt`, the two inline resolver constructions now need a 5th arg. The file currently builds resolvers at two points. Find each `DefaultAnimeSeasonProjectionResolver(` call that has 4 named args and add `presentationCache = InMemoryAnimeSeasonPresentationCache()`.

The existing inline constructions at lines ~34 and ~62 become:

```kotlin
val resolver = DefaultAnimeSeasonProjectionResolver(
    idMappingService = mapping,
    kitsuMetadataService = kitsu,
    store = InMemoryAnimeEpisodeCoordinateStore(),
    traceEvents = mockk(relaxed = true),
    presentationCache = InMemoryAnimeSeasonPresentationCache(),
)
```

In `DefaultAnimeSeasonProjectionResolverEpisodeTest.kt`, the `resolverFor()` helper at line 70 becomes:

```kotlin
private fun resolverFor(
    kitsuId: String,
    tvdb: String,
    kitsuMetadataService: KitsuMetadataService,
) = DefaultAnimeSeasonProjectionResolver(
    idMappingService = AnimeIdMappingService(
        assetProvider = {
            AnimeIdMapAsset(
                schemaVersion = 1,
                recordsByKitsu = mapOf(
                    kitsuId to AnimeIdMapRecord(
                        kitsu = kitsuId, tvdb = tvdb,
                        mediaType = "series", sourceType = "TV"
                    )
                )
            )
        }
    ),
    kitsuMetadataService = kitsuMetadataService,
    store = InMemoryAnimeEpisodeCoordinateStore(),
    traceEvents = mockk(relaxed = true),
    presentationCache = InMemoryAnimeSeasonPresentationCache(),
)
```

In `DefaultAnimeSeasonProjectionResolverWorkTest.kt`, there are two inline `DefaultAnimeSeasonProjectionResolver(...)` constructions (lines ~28 and ~48). Add `presentationCache = InMemoryAnimeSeasonPresentationCache()` to each.

- [ ] **Step 10: Update `AnimeProjectionModule` to bind the new cache**

Full replacement of `AnimeProjectionModule.kt`:

```kotlin
package com.nexio.tv.core.di

import com.nexio.tv.core.anime.projection.AnimeEpisodeCoordinateStore
import com.nexio.tv.core.anime.projection.AnimeSeasonDetailRepository
import com.nexio.tv.core.anime.projection.AnimeSeasonPresentationCache
import com.nexio.tv.core.anime.projection.AnimeSeasonProjectionResolver
import com.nexio.tv.core.anime.projection.DefaultAnimeSeasonDetailRepository
import com.nexio.tv.core.anime.projection.DefaultAnimeSeasonProjectionResolver
import com.nexio.tv.core.anime.projection.InMemoryAnimeEpisodeCoordinateStore
import com.nexio.tv.core.anime.projection.InMemoryAnimeSeasonPresentationCache
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

    @Binds
    abstract fun bindPresentationCache(impl: InMemoryAnimeSeasonPresentationCache): AnimeSeasonPresentationCache

    @Binds
    abstract fun bindDetailRepository(impl: DefaultAnimeSeasonDetailRepository): AnimeSeasonDetailRepository
}
```

- [ ] **Step 11: Run all projection tests**

```
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.core.anime.projection.*" 2>&1 | grep -E "PASSED|FAILED|tests|BUILD"
```

Expected: `BUILD SUCCESSFUL`, all existing tests pass plus the 2 new cache-integration tests and 6 cache unit tests.

- [ ] **Step 12: Compile the full module**

```
./gradlew :app:compileUniversalDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 13: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/core/anime/projection/AnimeSeasonPresentationCache.kt \
  app/src/main/java/com/nexio/tv/core/anime/projection/InMemoryAnimeSeasonPresentationCache.kt \
  app/src/main/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolver.kt \
  app/src/main/java/com/nexio/tv/core/di/AnimeProjectionModule.kt \
  app/src/test/java/com/nexio/tv/core/anime/projection/InMemoryAnimeSeasonPresentationCacheTest.kt \
  app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverPresentationTest.kt \
  app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverEpisodeTest.kt \
  app/src/test/java/com/nexio/tv/core/anime/projection/DefaultAnimeSeasonProjectionResolverWorkTest.kt
git commit -m "perf(projection): cache resolveSeasonPresentation to avoid repeat episode enrichment fetches"
```

---

## Task 2.2: KitsuRailFranchiseGrouper

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/railpreview/KitsuRailFranchiseGrouper.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/KitsuDiscoveryService.kt`
- Create test: `app/src/test/java/com/nexio/tv/data/integration/railpreview/KitsuRailFranchiseGrouperTest.kt`
- Modify test: `app/src/test/java/com/nexio/tv/data/repository/KitsuDiscoveryServiceTest.kt`

---

- [ ] **Step 1: Write failing grouper tests**

Create `app/src/test/java/com/nexio/tv/data/integration/railpreview/KitsuRailFranchiseGrouperTest.kt`:

```kotlin
package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailDisplaySeed
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailRankingMetadata
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.SourcePayloadQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KitsuRailFranchiseGrouperTest {

    @Test
    fun `three MHA seasonal records sharing tvdb are collapsed to first occurrence`() {
        val grouper = grouperWith(
            series("11469", tvdb = "305074"),
            series("12268", tvdb = "305074"),
            series("13881", tvdb = "305074"),
        )
        val items = listOf(
            preview("kitsu:11469", kitsuId = "11469"),
            preview("kitsu:12268", kitsuId = "12268"),
            preview("kitsu:13881", kitsuId = "13881"),
        )

        val result = grouper.group(items)

        assertEquals(1, result.size)
        assertEquals("kitsu:11469", result.single().sourceItemId)
    }

    @Test
    fun `grouped representative stableIds are enriched with shared tvdb and imdb`() {
        val grouper = grouperWith(
            series("11469", tvdb = "305074", imdb = "tt5626028"),
            series("12268", tvdb = "305074", imdb = "tt5626028"),
        )
        val items = listOf(
            preview("kitsu:11469", kitsuId = "11469"),
            preview("kitsu:12268", kitsuId = "12268"),
        )

        val result = grouper.group(items)

        assertEquals("305074", result.single().stableIds.tvdb)
        assertEquals("tt5626028", result.single().stableIds.imdb)
        assertEquals("11469", result.single().stableIds.kitsu)
    }

    @Test
    fun `movies sharing tvdb with series are NOT grouped into the series card`() {
        val grouper = grouperWith(
            series("11469", tvdb = "305074"),
            AnimeIdMapRecord(kitsu = "14084", tvdb = "305074", imdb = "tt7745068", mediaType = "movie", sourceType = "MOVIE"),
        )
        val items = listOf(
            preview("kitsu:11469", kitsuId = "11469", type = ContentType.SERIES),
            preview("kitsu:14084", kitsuId = "14084", type = ContentType.MOVIE),
        )

        val result = grouper.group(items)

        assertEquals(2, result.size)
        assertEquals(listOf("kitsu:11469", "kitsu:14084"), result.map { it.sourceItemId })
    }

    @Test
    fun `OVA sharing tvdb is not grouped into series card`() {
        val grouper = grouperWith(
            series("11469", tvdb = "305074"),
            AnimeIdMapRecord(kitsu = "99999", tvdb = "305074", mediaType = "series", sourceType = "OVA"),
        )
        val items = listOf(
            preview("kitsu:11469", kitsuId = "11469"),
            preview("kitsu:99999", kitsuId = "99999"),
        )

        val result = grouper.group(items)

        assertEquals(2, result.size)
    }

    @Test
    fun `item with no mapping record passes through unchanged`() {
        val grouper = grouperWith()
        val item = preview("kitsu:99999", kitsuId = "99999")

        val result = grouper.group(listOf(item))

        assertEquals(1, result.size)
        assertEquals(item, result.single())
    }

    @Test
    fun `item with kitsu-only record (no tvdb imdb tmdb) passes through unchanged`() {
        val grouper = grouperWith(
            AnimeIdMapRecord(kitsu = "99999", mediaType = "series")
        )
        val item = preview("kitsu:99999", kitsuId = "99999")

        val result = grouper.group(listOf(item))

        assertEquals(1, result.size)
    }

    @Test
    fun `unique series preserves order when no grouping occurs`() {
        val grouper = grouperWith(
            series("1", tvdb = "11111"),
            series("2", tvdb = "22222"),
            series("3", tvdb = "33333"),
        )
        val items = listOf(
            preview("kitsu:1", kitsuId = "1"),
            preview("kitsu:2", kitsuId = "2"),
            preview("kitsu:3", kitsuId = "3"),
        )

        val result = grouper.group(items)

        assertEquals(listOf("kitsu:1", "kitsu:2", "kitsu:3"), result.map { it.sourceItemId })
    }

    @Test
    fun `mixed list of grouped and ungrouped preserves position of first grouped occurrence`() {
        val grouper = grouperWith(
            series("1", tvdb = "11111"),
            series("2", tvdb = "22222"),
            series("3", tvdb = "22222"),
        )
        val items = listOf(
            preview("kitsu:1", kitsuId = "1"),
            preview("kitsu:2", kitsuId = "2"),
            preview("kitsu:3", kitsuId = "3"),
        )

        val result = grouper.group(items)

        // item 1 passes through, item 2 is the representative of its group, item 3 is dropped
        assertEquals(2, result.size)
        assertEquals(listOf("kitsu:1", "kitsu:2"), result.map { it.sourceItemId })
    }

    // --- helpers ---

    private fun grouperWith(vararg records: AnimeIdMapRecord): KitsuRailFranchiseGrouper {
        val asset = AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = records.associateBy { it.kitsu }
        )
        return KitsuRailFranchiseGrouper(AnimeIdMappingService(assetProvider = { asset }))
    }

    private fun series(kitsu: String, tvdb: String, imdb: String = "") =
        AnimeIdMapRecord(kitsu = kitsu, tvdb = tvdb, imdb = imdb.takeIf { it.isNotEmpty() }, mediaType = "series", sourceType = "TV")

    private fun preview(
        sourceItemId: String,
        kitsuId: String,
        type: ContentType = ContentType.SERIES,
    ) = RailItemPreview(
        railId = "kitsu_trending_anime",
        railSource = RailSource.BUILT_IN_KITSU,
        sourceProvider = ProviderId.KITSU,
        sourceItemId = sourceItemId,
        itemType = type,
        stableIds = ProviderIds(kitsu = kitsuId),
        display = RailDisplaySeed(title = "Anime $kitsuId"),
        ranking = RailRankingMetadata(rank = 1),
        sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
        sourcePayloadHash = "hash-$kitsuId",
        generatedAtMs = 1000L,
    )
}
```

- [ ] **Step 2: Run to verify tests fail**

```
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.integration.railpreview.KitsuRailFranchiseGrouperTest" 2>&1 | grep -E "FAILED|error:|BUILD"
```

Expected: `BUILD FAILED` — `KitsuRailFranchiseGrouper` does not exist.

- [ ] **Step 3: Create `KitsuRailFranchiseGrouper`**

Create `app/src/main/java/com/nexio/tv/data/integration/railpreview/KitsuRailFranchiseGrouper.kt`:

```kotlin
package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.projection.AnimeWorkGroupKey
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.RailItemPreview
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KitsuRailFranchiseGrouper @Inject constructor(
    private val idMappingService: AnimeIdMappingService
) {

    /**
     * Collapses rail items that share a TVDB/IMDB/TMDB work identity into one card (first
     * occurrence wins). Non-series items (movies) and items with no cross-provider anchor
     * pass through unchanged. The representative's stableIds are enriched with the shared
     * TVDB/IMDB/TMDB/MAL/AniList/AniDB identifiers for downstream metadata resolution.
     */
    fun group(items: List<RailItemPreview>): List<RailItemPreview> {
        val keys: List<AnimeWorkGroupKey?> = items.map { groupKeyFor(it) }
        val keyCount = keys.filterNotNull().groupingBy { it }.eachCount()

        val emitted = mutableSetOf<AnimeWorkGroupKey>()
        val result = mutableListOf<RailItemPreview>()

        for ((index, item) in items.withIndex()) {
            val key = keys[index]
            if (key == null || keyCount[key] == 1) {
                val record = item.stableIds.kitsu?.let { idMappingService.recordForKitsuId(it) }
                result += if (record != null) item.withEnrichedStableIds(record) else item
            } else {
                if (emitted.add(key)) {
                    val record = item.stableIds.kitsu?.let { idMappingService.recordForKitsuId(it) }
                    result += if (record != null) item.withEnrichedStableIds(record) else item
                }
                // Subsequent items from the same franchise group are dropped.
            }
        }
        return result
    }

    private fun groupKeyFor(item: RailItemPreview): AnimeWorkGroupKey? {
        if (item.itemType != ContentType.SERIES) return null
        val kitsuId = item.stableIds.kitsu?.takeIf { it.isNotBlank() } ?: return null
        val record = idMappingService.recordForKitsuId(kitsuId) ?: return null
        if (!isSeriesTvEntry(record)) return null
        if (record.tvdb.isNullOrBlank() && record.imdb.isNullOrBlank() && record.tmdb.isNullOrBlank()) return null
        return AnimeWorkGroupKey.preferred(record.tvdb, record.imdb, record.tmdb, kitsuId)
    }

    private fun isSeriesTvEntry(record: AnimeIdMapRecord): Boolean {
        val mediaType = record.mediaType?.lowercase() ?: return true
        val sourceType = record.sourceType?.lowercase() ?: ""
        return mediaType == "series" && sourceType in setOf("tv", "")
    }

    private fun RailItemPreview.withEnrichedStableIds(record: AnimeIdMapRecord): RailItemPreview =
        copy(
            stableIds = stableIds.copy(
                tvdb = record.tvdb ?: stableIds.tvdb,
                imdb = record.imdb ?: stableIds.imdb,
                tmdb = record.tmdb ?: stableIds.tmdb,
                mal = record.mal ?: stableIds.mal,
                anilist = record.anilist ?: stableIds.anilist,
                anidb = record.anidb ?: stableIds.anidb,
            )
        )
}
```

- [ ] **Step 4: Run grouper tests to verify they pass**

```
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.integration.railpreview.KitsuRailFranchiseGrouperTest" 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`, 8 tests pass.

- [ ] **Step 5: Wire grouper into `KitsuDiscoveryService`**

Replace `app/src/main/java/com/nexio/tv/data/repository/KitsuDiscoveryService.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.railpreview.KitsuRailFranchiseGrouper
import com.nexio.tv.data.integration.railpreview.KitsuRailPreviewMapper
import com.nexio.tv.data.integration.kitsu.KitsuDiscoveryIntegrationProvider
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailPreviewCatalogRowRecord
import com.nexio.tv.domain.model.toMetaPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

typealias RetrofitKitsuDiscoveryClient = KitsuDiscoveryIntegrationProvider

@Singleton
class KitsuDiscoveryService @Inject constructor(
    private val client: KitsuDiscoveryClient,
    private val grouper: KitsuRailFranchiseGrouper,
) {
    private val snapshot = MutableStateFlow(KitsuDiscoverySnapshot())
    private val railPreviewMapper = KitsuRailPreviewMapper()

    fun observeSnapshot(): Flow<KitsuDiscoverySnapshot> = snapshot

    suspend fun refreshCatalogs(
        preferences: KitsuCatalogPreferences,
        force: Boolean,
        catalogIds: Set<String>? = null
    ): KitsuDiscoverySnapshot {
        val sanitized = preferences.sanitized()
        val requestedCatalogIds = catalogIds
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
        val previous = snapshot.value
        val previousCurrentRows = previous.currentRowsFor(sanitized)
        val previousCurrentRowRecords = previous.rowRecordsByCatalog.filterKeys { key ->
            key in previousCurrentRows
        }
        if (!force && requestedCatalogIds != null && requestedCatalogIds.all { it in previousCurrentRows }) {
            return previous
        }

        val enabledCatalogs = sanitized.catalogOrder
            .filter { it in sanitized.enabledCatalogs }
            .filter { requestedCatalogIds == null || it in requestedCatalogIds }

        val refreshedRows = enabledCatalogs.associateWith { catalogId ->
            fetchCatalogRow(catalogId, sanitized)
        }.filterValues { it != null }
            .mapValues { it.value!! }

        val rows = if (catalogIds == null) {
            refreshedRows
        } else {
            previousCurrentRowRecords - requestedCatalogIds.orEmpty() + refreshedRows
        }
        val currentPreferenceCatalogIds = sanitized.enabledCatalogIds()
        val catalogIdsWithCurrentPreferences = if (catalogIds == null) {
            currentPreferenceCatalogIds
        } else {
            (previous.catalogIdsWithCurrentPreferences.intersect(currentPreferenceCatalogIds) - requestedCatalogIds.orEmpty()) +
                enabledCatalogs.toSet()
        }

        return KitsuDiscoverySnapshot(
            rowRecordsByCatalog = rows,
            updatedAtMs = System.currentTimeMillis(),
            catalogIdsWithCurrentPreferences = catalogIdsWithCurrentPreferences
        ).also { snapshot.value = it }
    }

    private suspend fun fetchCatalogRow(
        catalogId: String,
        preferences: KitsuCatalogPreferences
    ): RailPreviewCatalogRowRecord? {
        val title = kitsuCatalogTitle(catalogId) ?: return null
        val results = runCatching { client.fetchCatalog(catalogId, preferences) }
            .getOrDefault(emptyList())
        val items = mapCatalogResults(
            railId = catalogId,
            results = results,
            generatedAtMs = System.currentTimeMillis()
        )
        if (items.isEmpty()) return null
        return RailPreviewCatalogRowRecord(
            addonId = ADDON_ID,
            addonName = ADDON_NAME,
            addonBaseUrl = ADDON_BASE_URL,
            catalogId = catalogId,
            catalogName = title,
            type = ContentType.SERIES,
            rawType = ContentType.SERIES.toApiString("catalog"),
            previews = items
        )
    }

    private fun mapCatalogResults(
        railId: String,
        results: List<KitsuAnimeResource>,
        generatedAtMs: Long
    ): List<RailItemPreview> {
        val mapped = results.take(MAX_ITEMS_PER_SOURCE)
            .mapIndexedNotNull { index, result ->
                railPreviewMapper.mapAnime(
                    railId = railId,
                    anime = result,
                    position = index,
                    generatedAtMs = generatedAtMs
                )
            }
        return grouper.group(mapped)
    }

    companion object {
        private const val ADDON_ID = "kitsu"
        private const val ADDON_NAME = "Kitsu"
        private const val ADDON_BASE_URL = "https://kitsu.io/api/edge"
        private const val MAX_ITEMS_PER_SOURCE = 20
    }
}
```

- [ ] **Step 6: Update `KitsuDiscoveryServiceTest` to supply grouper + add grouping integration test**

Replace `KitsuDiscoveryServiceTest.kt` with the following (all existing tests preserved, `createService()` updated, one new test added):

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.data.integration.railpreview.KitsuRailFranchiseGrouper
import com.nexio.tv.data.local.KitsuCatalogIds
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.remote.api.KitsuAnimeAttributes
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.data.remote.api.KitsuImage
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.PosterShape
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KitsuDiscoveryServiceTest {
    @Test
    fun `trending anime rail maps to kitsu discovery row`() = runTest {
        val service = FakeKitsuDiscoveryClient(
            catalogResults = mapOf(
                KitsuCatalogIds.TRENDING_ANIME to listOf(
                    animeResult(
                        id = "1",
                        canonicalTitle = "Cowboy Bebop",
                        subtype = "TV",
                        synopsis = "Space bounty hunters.",
                        startDate = "1998-04-03",
                        averageRating = "85.2",
                        poster = "https://media.kitsu.io/poster.jpg",
                        cover = "https://media.kitsu.io/cover.jpg"
                    )
                )
            )
        ).createService()

        val snapshot = service.refreshCatalogs(
            KitsuCatalogPreferences(enabledCatalogs = setOf(KitsuCatalogIds.TRENDING_ANIME)),
            force = true
        )

        val row = snapshot.rowsByCatalog.getValue(KitsuCatalogIds.TRENDING_ANIME)
        assertEquals("Kitsu Trending Anime", row.catalogName)
        assertEquals("kitsu:1", row.items.single().id)
        assertEquals(ContentType.SERIES, row.items.single().type)
        assertEquals(PosterShape.POSTER, row.items.single().posterShape)
        assertEquals("1998", row.items.single().releaseInfo)
        assertEquals(8.5f, row.items.single().imdbRating)
        assertEquals(1, snapshot.rowRecordsByCatalog.getValue(KitsuCatalogIds.TRENDING_ANIME).previews.size)
    }

    @Test
    fun `movie subtype maps to movie preview`() = runTest {
        val service = FakeKitsuDiscoveryClient(
            catalogResults = mapOf(
                KitsuCatalogIds.POPULAR_ANIME to listOf(
                    animeResult(
                        id = "2",
                        canonicalTitle = "Akira",
                        subtype = "movie"
                    )
                )
            )
        ).createService()

        val snapshot = service.refreshCatalogs(
            KitsuCatalogPreferences(enabledCatalogs = setOf(KitsuCatalogIds.POPULAR_ANIME)),
            force = true
        )

        val item = snapshot.rowsByCatalog.getValue(KitsuCatalogIds.POPULAR_ANIME).items.single()
        val preview = snapshot.rowRecordsByCatalog.getValue(KitsuCatalogIds.POPULAR_ANIME).previews.single()
        assertEquals(ContentType.MOVIE, preview.itemType)
        assertEquals(ContentType.MOVIE, item.type)
        assertEquals("movie", item.apiType)
    }

    @Test
    fun `missing results keep rail empty but present in expected preferences`() = runTest {
        val service = FakeKitsuDiscoveryClient().createService()
        val prefs = KitsuCatalogPreferences(enabledCatalogs = setOf(KitsuCatalogIds.POPULAR_COMEDY_ANIME))

        val snapshot = service.refreshCatalogs(prefs, force = true)

        assertTrue(snapshot.rowsByCatalog.isEmpty())
        assertEquals(setOf(KitsuCatalogIds.POPULAR_COMEDY_ANIME), snapshot.catalogIdsWithCurrentPreferences)
    }

    @Test
    fun `observeSnapshot emits refreshed catalog snapshot after refreshCatalogs`() = runTest {
        val service = FakeKitsuDiscoveryClient(
            catalogResults = mapOf(
                KitsuCatalogIds.POPULAR_ACTION_ANIME to listOf(animeResult(id = "3", canonicalTitle = "Trigun"))
            )
        ).createService()
        val emission = async(start = CoroutineStart.UNDISPATCHED) { service.observeSnapshot().drop(1).first() }

        val refreshed = service.refreshCatalogs(
            KitsuCatalogPreferences(enabledCatalogs = setOf(KitsuCatalogIds.POPULAR_ACTION_ANIME)),
            force = true
        )

        assertEquals(refreshed, emission.await())
    }

    @Test
    fun `franchise grouping collapses MHA S1 and S3 sharing tvdb into one card`() = runTest {
        val mhaAsset = AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = mapOf(
                "11469" to AnimeIdMapRecord(kitsu = "11469", tvdb = "305074", mediaType = "series", sourceType = "TV"),
                "13881" to AnimeIdMapRecord(kitsu = "13881", tvdb = "305074", mediaType = "series", sourceType = "TV"),
            )
        )
        val grouper = KitsuRailFranchiseGrouper(AnimeIdMappingService(assetProvider = { mhaAsset }))
        val service = FakeKitsuDiscoveryClient(
            catalogResults = mapOf(
                KitsuCatalogIds.TRENDING_ANIME to listOf(
                    animeResult(id = "11469", canonicalTitle = "My Hero Academia"),
                    animeResult(id = "13881", canonicalTitle = "My Hero Academia Season 3"),
                )
            )
        ).createService(grouper = grouper)

        val snapshot = service.refreshCatalogs(
            KitsuCatalogPreferences(enabledCatalogs = setOf(KitsuCatalogIds.TRENDING_ANIME)),
            force = true
        )

        val previews = snapshot.rowRecordsByCatalog.getValue(KitsuCatalogIds.TRENDING_ANIME).previews
        assertEquals("MHA S1 and S3 should collapse to one card", 1, previews.size)
        assertEquals("kitsu:11469", previews.single().sourceItemId)
        assertEquals("305074", previews.single().stableIds.tvdb)
    }

    private class FakeKitsuDiscoveryClient(
        private val catalogResults: Map<String, List<KitsuAnimeResource>> = emptyMap()
    ) : KitsuDiscoveryClient {
        override suspend fun fetchCatalog(
            catalogId: String,
            preferences: KitsuCatalogPreferences
        ): List<KitsuAnimeResource> = catalogResults[catalogId].orEmpty()

        fun createService(
            grouper: KitsuRailFranchiseGrouper = noOpGrouper()
        ): KitsuDiscoveryService = KitsuDiscoveryService(this, grouper)

        private fun noOpGrouper() = KitsuRailFranchiseGrouper(
            AnimeIdMappingService(assetProvider = { AnimeIdMapAsset(schemaVersion = 0) })
        )
    }

    private fun animeResult(
        id: String,
        canonicalTitle: String,
        subtype: String = "TV",
        synopsis: String = "",
        startDate: String? = null,
        averageRating: String? = null,
        poster: String? = null,
        cover: String? = null
    ): KitsuAnimeResource {
        return KitsuAnimeResource(
            id = id,
            type = "anime",
            attributes = KitsuAnimeAttributes(
                canonicalTitle = canonicalTitle,
                synopsis = synopsis.takeIf { it.isNotBlank() },
                subtype = subtype,
                startDate = startDate,
                averageRating = averageRating,
                posterImage = poster?.let { KitsuImage(original = it) },
                coverImage = cover?.let { KitsuImage(original = it) }
            )
        )
    }
}
```

- [ ] **Step 7: Run grouper and discovery tests**

```
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.integration.railpreview.KitsuRailFranchiseGrouperTest" \
  --tests "com.nexio.tv.data.repository.KitsuDiscoveryServiceTest" \
  2>&1 | grep -E "PASSED|FAILED|tests|BUILD"
```

Expected: `BUILD SUCCESSFUL`, all 9 grouper tests + 5 discovery tests pass.

- [ ] **Step 8: Compile full module to confirm no Hilt wiring regressions**

```
./gradlew :app:compileUniversalDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/data/integration/railpreview/KitsuRailFranchiseGrouper.kt \
  app/src/main/java/com/nexio/tv/data/repository/KitsuDiscoveryService.kt \
  app/src/test/java/com/nexio/tv/data/integration/railpreview/KitsuRailFranchiseGrouperTest.kt \
  app/src/test/java/com/nexio/tv/data/repository/KitsuDiscoveryServiceTest.kt
git commit -m "feat(home): deduplicate multi-season Kitsu rail cards with KitsuRailFranchiseGrouper"
```

---

## Final verification

- [ ] **Run the full Phase 1 + Phase 2 test suite**

```
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.core.anime.*" \
  --tests "com.nexio.tv.data.integration.railpreview.*" \
  --tests "com.nexio.tv.data.repository.KitsuDiscoveryServiceTest" \
  --tests "com.nexio.tv.core.metadata.router.MetadataRouterFacadeStableIdBundleTest" \
  --tests "com.nexio.tv.data.integration.posters.PremiumPoster*" \
  2>&1 | grep -E "PASSED|FAILED|tests|BUILD"
```

Expected: `BUILD SUCCESSFUL`, no failures.

- [ ] **Full compile**

```
./gradlew :app:compileUniversalDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

---

## Self-Review

**Spec coverage:**

| Phase 2 requirement | Task |
|---|---|
| `resolveSeasonPresentation` session cache to avoid repeat fetches | Task 2.1 — `AnimeSeasonPresentationCache` |
| Cache invalidation by group key | Task 2.1 — `InMemoryAnimeSeasonPresentationCache.invalidate()` |
| `requestedSeason` override on cache hit | Task 2.1 — `copy(selectedSeason = requestedSeason)` in resolver |
| `KitsuRailFranchiseGrouper` collapses multi-season cards | Task 2.2 — `KitsuRailFranchiseGrouper.group()` |
| Movies not grouped with series | Task 2.2 — `item.itemType != ContentType.SERIES` guard |
| OVAs/specials not grouped with series | Task 2.2 — `isSeriesTvEntry()` guard |
| Representative stableIds enriched with TVDB/IMDB/TMDB | Task 2.2 — `withEnrichedStableIds()` |
| Kitsu-only items (no cross-provider anchor) pass through | Task 2.2 — null TVDB/IMDB/TMDB guard |
| Hilt bindings for new cache | Task 2.1 — `AnimeProjectionModule` |
| No regressions in Phase 1 tests | Final verification step |

**Placeholder scan:** No TBDs, TODOs, or "similar to Task N" references. All code blocks are complete.

**Type consistency:** `AnimeWorkGroupKey`, `AnimeSeasonPresentation`, `RailItemPreview`, `ProviderIds` types are used consistently with the definitions in Phase 1. `KitsuRailFranchiseGrouper` uses `AnimeWorkGroupKey.preferred()` with the same 4-arg signature defined in `AnimeWorkIdentity.kt`.

---

## Out of scope

- **Phase 3 — curated season-mapping overlay** for flat-franchise anime (One Piece TVDB season mapping). Depends only on Phase 1 `FallbackReason.OVERLAY_MISSING` path; should be its own plan when the data source is chosen.
- **Profile-isolation gap in `KitsuDiscoveryService`** — the global `MutableStateFlow<KitsuDiscoverySnapshot>` is shared across profiles. Documented in `KitsuCatalogRailSource`; orthogonal to this plan.
