# Scrobble + Continue Watching Dual-Provider Overhaul — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewire Nexio's scrobble + Continue Watching pipeline so Trakt and Simkl both receive scrobbles simultaneously (CrossWatch-style), Simkl serves as a Continue Watching source, scrobbles carry the fully-hydrated `ProviderIds` bundle (IMDB + TMDB + TVDB + Trakt + Simkl + Kitsu + MAL + AniList + AniDB) instead of the single parsed contentId, CW dedup matches across the multi-ID bundle, and anime correctly routes to Simkl's native `anime` endpoint even when Trakt anime projection fails.

**Architecture:** Six sequential phases, each ending in a green commit on `main`. Phase 0 dissolves the Trakt-OR-Simkl exclusivity gate. Phase 1 hydrates the full ID bundle at scrobble-emit time. Phase 2 replaces opaque `identityKey()` with multi-ID intersection matching. Phase 3 adds Simkl as a CW source with timestamp-aware merge. Phase 4 routes anime to Simkl natively. Phase 5 adds the Trakt pause-above-80% guard. Phases 1+2 are paired (ship as one branch). Phases 0, 3, 4, 5 each ship independently. All work is TDD: red test → minimal impl → green → commit.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Coroutines/Flow, JUnit 4 + MockK, Retrofit2, Jetpack DataStore, Room. Reference patterns from `~/Scripts/NuvioMedia/CrossWatch` (Python) and `~/Scripts/NuvioMedia/jellyfin-plugin-trakt` (C#) — read but never copy verbatim; translate idioms to Kotlin.

---

## File Structure

### Phase 0 — Remove provider exclusivity (dual-write)

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/data/repository/TrackingProviderStateService.kt` | Modify — add `activeProviders: Set<TrackingProvider>` field; keep `effectiveProvider` for legacy reads but stop using it for scrobble routing |
| `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt` | Modify lines 67-145 — fan out to both providers instead of `when` switch |
| `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt` | Modify — `trackingProvider` becomes a tie-breaker preference, no longer exclusive |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/tracking/TrackingSettingsScreen.kt` | Modify — remove single-select radio, replace with two independent connect/disconnect cards |
| `app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleServiceTest.kt` | Create — tests dual-write fan-out, per-provider auth gating |
| `app/src/test/java/com/nexio/tv/data/repository/TrackingProviderStateServiceTest.kt` | Modify — assert `activeProviders` covers both when both authed |

### Phase 1 — Hydrate IDs at scrobble-emit time

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/data/repository/ScrobbleIdBundleHydrator.kt` | Create — new `@Singleton` service wrapping `StableIdBundleResolver` for the scrobble-emit path |
| `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt` | Modify — `toTraktItem` and new `toSimklItem` consume hydrated `ProviderIds` instead of `parseContentIds(rawContentId)` |
| `app/src/main/java/com/nexio/tv/data/repository/simkl/SimklScrobbleMutationAdapter.kt` | Modify lines 166-188 — accept `ProviderIds` directly instead of re-parsing contentId |
| `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt` | Modify lines 269-292 — accept `ProviderIds` directly |
| `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleItem.kt` (or inline in TrackingScrobbleService) | Modify — `TrackingScrobbleItem` gains `hydratedIds: ProviderIds?` field |
| `app/src/test/java/com/nexio/tv/data/repository/ScrobbleIdBundleHydratorTest.kt` | Create |
| `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapterTest.kt` | Modify — add IMDB-hydration cases |

### Phase 2 — Multi-ID CW dedup

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingRecord.kt` | Modify — add `idBundle: ContinueWatchingIdBundle` field; keep `identityKey()` for back-compat but mark deprecated |
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdBundle.kt` | Create — `data class ContinueWatchingIdBundle(imdbId, tmdbId, tvdbId, kitsuId, malId, anilistId, anidbId, simklId, traktId, season, episode)` |
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt` | Modify — replace opaque-key groupBy with multi-ID intersection (Union-Find) |
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt` | Modify lines 80-260 — populate `idBundle` from `MetadataRouterFacade.resolveStableIdBundle()` |
| `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMergerTest.kt` | Modify — add multi-ID intersection cases |
| `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdBundleTest.kt` | Create |

### Phase 3 — Simkl as CW source

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/domain/model/WatchProgress.kt` | Modify lines 31-35 — add `SOURCE_SIMKL_PLAYBACK`, `SOURCE_SIMKL_HISTORY` |
| `app/src/main/java/com/nexio/tv/data/repository/simkl/SimklContinueWatchingPuller.kt` | Create — mirror Trakt CW pull path |
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt` | Modify lines 84 and 164 — remove `provider = TrackingProvider.TRAKT` hardcode |
| `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingProgressDiffPlanner.kt` | Create — port CrossWatch's `_planner.py:207-354` algorithm |
| `app/src/test/java/com/nexio/tv/data/repository/simkl/SimklContinueWatchingPullerTest.kt` | Create |
| `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingProgressDiffPlannerTest.kt` | Create |

### Phase 4 — Anime SIMKL-first routing

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt` | Modify lines 160-233 — add `projectAnimeToSimklItem`, don't drop scrobble when Trakt projection fails |
| `app/src/main/java/com/nexio/tv/data/repository/simkl/SimklScrobbleMutationAdapter.kt` | Modify — add `anime` parent payload branch |
| `app/src/main/java/com/nexio/tv/data/remote/dto/simkl/SimklScrobbleDtos.kt` | Modify — add `anime` field on request DTO (alongside `show` / `movie`) |
| `app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleServiceAnimeTest.kt` | Create |

### Phase 5 — Trakt pause-above-80% guard

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt` | Modify — convert action to `"stop"` if `pause && progress >= 80f` |
| `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapterTest.kt` | Modify — add 79/80/81% boundary cases |

---

## Phase 0 — Remove provider exclusivity

**Goal:** Both Trakt and Simkl receive scrobbles simultaneously when both are authenticated. `trackingProvider` setting becomes a tie-breaker only (e.g. for surfaces that need to pick one for display); it no longer gates scrobble routing.

### Task 0.1: Add `activeProviders` to `EffectiveTrackingProviderState`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TrackingProviderStateService.kt:21-39`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TrackingProviderStateServiceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `activeProviders contains both when both authed`() = runTest {
    val service = trackingProviderStateService(
        trakt = true,
        simkl = true,
        storedPreference = TrackingProvider.TRAKT,
    )
    val state = service.state.first()
    assertEquals(setOf(TrackingProvider.TRAKT, TrackingProvider.SIMKL), state.activeProviders)
}

@Test
fun `activeProviders contains only authed provider`() = runTest {
    val service = trackingProviderStateService(trakt = true, simkl = false)
    val state = service.state.first()
    assertEquals(setOf(TrackingProvider.TRAKT), state.activeProviders)
}

@Test
fun `activeProviders is empty when none authed`() = runTest {
    val service = trackingProviderStateService(trakt = false, simkl = false)
    val state = service.state.first()
    assertEquals(emptySet<TrackingProvider>(), state.activeProviders)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.TrackingProviderStateServiceTest"`
Expected: FAIL with "unresolved reference: activeProviders"

- [ ] **Step 3: Add `activeProviders` field**

In `TrackingProviderStateService.kt`, modify the data class (lines 21-39):

```kotlin
data class EffectiveTrackingProviderState(
    val storedProvider: TrackingProvider = TrackingProvider.TRAKT,
    val effectiveProvider: TrackingProvider = TrackingProvider.TRAKT,
    val traktAuthenticated: Boolean = false,
    val simklAuthenticated: Boolean = false,
) {
    val hasAuthenticatedProvider: Boolean
        get() = traktAuthenticated || simklAuthenticated

    val activeProviders: Set<TrackingProvider>
        get() = buildSet {
            if (traktAuthenticated) add(TrackingProvider.TRAKT)
            if (simklAuthenticated) add(TrackingProvider.SIMKL)
        }

    val canReadEffectiveProvider: Boolean
        get() = hasAuthenticatedProvider

    fun isProviderAuthenticated(provider: TrackingProvider): Boolean = when (provider) {
        TrackingProvider.TRAKT -> traktAuthenticated
        TrackingProvider.SIMKL -> simklAuthenticated
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.TrackingProviderStateServiceTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TrackingProviderStateService.kt \
        app/src/test/java/com/nexio/tv/data/repository/TrackingProviderStateServiceTest.kt
git commit -m "feat(tracking): expose activeProviders for dual-write fan-out

Adds a Set<TrackingProvider> field that lists every authenticated
provider. Replaces the single 'effectiveProvider' as the source of
truth for scrobble routing. effectiveProvider is retained for legacy
surfaces (CW provider tag, future tie-breakers)."
```

### Task 0.2: Fan-out scrobble dispatch to all `activeProviders`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt:67-145`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleServiceTest.kt` (create)

- [ ] **Step 1: Write the failing test**

```kotlin
class TrackingScrobbleServiceFanOutTest {

    private val traktService = mockk<TraktScrobbleService>(relaxed = true)
    private val simklService = mockk<SimklScrobbleService>(relaxed = true)
    private val providerState = MutableStateFlow(
        EffectiveTrackingProviderState(
            traktAuthenticated = true,
            simklAuthenticated = true,
        )
    )

    @Test
    fun `scrobbleStart dispatches to both providers when both authed`() = runTest {
        val service = newService()
        service.scrobbleStart(movieItem(), 10f, ownerContext())
        coVerify(exactly = 1) { traktService.scrobbleStart(any(), 10f, any()) }
        coVerify(exactly = 1) { simklService.scrobbleStart(any(), 10f, any(), any()) }
    }

    @Test
    fun `scrobbleStart dispatches only to authed provider`() = runTest {
        providerState.value = EffectiveTrackingProviderState(
            traktAuthenticated = false,
            simklAuthenticated = true,
        )
        val service = newService()
        service.scrobbleStart(movieItem(), 10f, ownerContext())
        coVerify(exactly = 0) { traktService.scrobbleStart(any(), any(), any()) }
        coVerify(exactly = 1) { simklService.scrobbleStart(any(), any(), any(), any()) }
    }

    @Test
    fun `scrobbleStart no-op when no provider authed`() = runTest {
        providerState.value = EffectiveTrackingProviderState()
        val service = newService()
        service.scrobbleStart(movieItem(), 10f, ownerContext())
        coVerify(exactly = 0) { traktService.scrobbleStart(any(), any(), any()) }
        coVerify(exactly = 0) { simklService.scrobbleStart(any(), any(), any(), any()) }
    }

    // helpers omitted for brevity in this snippet — include them in the actual file:
    // newService(), movieItem(), ownerContext()
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.TrackingScrobbleServiceFanOutTest"`
Expected: FAIL — both providers receive the call only when `effectiveProvider` matches (current code only calls the chosen one).

- [ ] **Step 3: Rewrite dispatch to fan out**

In `TrackingScrobbleService.kt`, replace `scrobbleStart` (lines 67-92) and the equivalent `scrobbleStop`, `scrobblePause`, `checkin` blocks with:

```kotlin
override suspend fun scrobbleStart(
    item: TrackingScrobbleItem,
    progressPercent: Float,
    owner: PlaybackOwnerContext,
) {
    val state = providerState(owner)
    coroutineScope {
        if (state.traktAuthenticated) {
            launch {
                val traktItem = toTraktItem(item) ?: return@launch
                traktScrobbleService.scrobbleStart(traktItem, progressPercent, owner.ownerProfileId)
            }
        }
        if (state.simklAuthenticated) {
            launch {
                val simklItem = toSimklItem(item) ?: return@launch
                simklScrobbleService.scrobbleStart(
                    item = simklItem,
                    progressPercent = progressPercent,
                    ownerProfileId = owner.ownerProfileId,
                    ownerSessionId = owner.ownerSessionId,
                )
            }
        }
    }
}
```

Repeat the same fan-out shape for `scrobbleStop`, `scrobblePause`. For `checkin`, return `true` if EITHER provider returns true:

```kotlin
override suspend fun checkin(
    item: TrackingScrobbleItem,
    message: String?,
    owner: PlaybackOwnerContext,
): Boolean {
    val state = providerState(owner)
    val results = coroutineScope {
        listOfNotNull(
            if (state.traktAuthenticated) async {
                toTraktItem(item)?.let { traktScrobbleService.checkin(it, message, owner.ownerProfileId) } ?: false
            } else null,
            if (state.simklAuthenticated) async {
                toSimklItem(item)?.let {
                    simklScrobbleService.checkin(it, message, owner.ownerProfileId, owner.ownerSessionId)
                } ?: false
            } else null,
        ).awaitAll()
    }
    return results.any { it }
}
```

Add a `toSimklItem` helper alongside the existing `toTraktItem` — for now, pass the `TrackingScrobbleItem` through unchanged (Phase 1 hydrates it):

```kotlin
private suspend fun toSimklItem(item: TrackingScrobbleItem): TrackingScrobbleItem? {
    // Simkl mutation adapter currently parses IDs from contentId at queue time.
    // Phase 1 will replace this with hydrated ProviderIds.
    return item
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.TrackingScrobbleServiceFanOutTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt \
        app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleServiceFanOutTest.kt
git commit -m "feat(tracking): fan scrobble out to every authenticated provider

Replaces the when(effectiveProvider) gate in DefaultTrackingScrobbleService
with a coroutineScope fan-out across activeProviders. Trakt and Simkl now
both receive start/stop/pause/checkin when authed; effectiveProvider is no
longer consulted for routing."
```

### Task 0.3: Update TrackingSettings UI — remove exclusive radio

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/tracking/TrackingSettingsScreen.kt`

- [ ] **Step 1: Read the current screen to identify the radio**

Run: `grep -n "TrackingProvider" app/src/main/java/com/nexio/tv/ui/screens/settings/tracking/TrackingSettingsScreen.kt`

You're looking for a RadioGroup, SegmentedButton, or `selectableGroup()` that binds to `playerSettings.trackingProvider`. Confirm one exists before continuing.

- [ ] **Step 2: Replace the exclusive selector with two independent connect cards**

The screen should expose two side-by-side `ProviderConnectionCard` composables — one per provider — each with its own connect/disconnect button. Remove any "active provider" radio. The `trackingProvider` setting is no longer surfaced in UI; it stays in DataStore as a hidden tie-breaker.

If no such composable exists, create it inline. The exact composable shape depends on the project's design system — match adjacent settings screens (e.g. debrid settings under `ui/screens/settings/debrid/`). Concretely: each card shows the provider name, the authenticated email/username when connected, a "Connect" or "Disconnect" button, and a help line ("Both can be enabled at the same time"). No mutual-exclusion logic.

- [ ] **Step 3: Manually verify on emulator or device**

Run the app, navigate to Settings → Tracking. Confirm:
- Trakt and Simkl can both be authenticated simultaneously
- No radio button gates which one is "active"
- Disconnecting one does not affect the other

If a UI test exists for this screen, update it. Otherwise document the smoke check in the commit message.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/tracking/TrackingSettingsScreen.kt
git commit -m "ui(settings): make Trakt and Simkl independently toggleable

Removes the exclusive provider radio. Each provider gets its own
ProviderConnectionCard. Backend dual-write is wired in 0.2; this
screen now reflects that both can be enabled at once."
```

### Task 0.4: Mark `effectiveProvider` deprecated for routing decisions

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TrackingProviderStateService.kt`

- [ ] **Step 1: Add a `KDoc` warning on `effectiveProvider`**

In the data class, annotate the field:

```kotlin
@Deprecated(
    message = "effectiveProvider is no longer used for scrobble routing. " +
        "Use activeProviders for fan-out. effectiveProvider is retained only for " +
        "surfaces that must pick one provider for display (e.g. ContinueWatchingRecord.provider).",
    level = DeprecationLevel.WARNING,
)
val effectiveProvider: TrackingProvider = TrackingProvider.TRAKT,
```

- [ ] **Step 2: Build and silence any callers that should migrate**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | grep -i "effectiveProvider"`

For each warning, either migrate to `activeProviders` or annotate with `@Suppress("DEPRECATION")` and a comment explaining why a single-provider display is correct (e.g. ContinueWatching needs ONE provider tag per record for legacy DB column).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TrackingProviderStateService.kt
git commit -m "chore(tracking): deprecate effectiveProvider for routing decisions

activeProviders is the new source of truth for fan-out. effectiveProvider
remains available for display-only surfaces (ContinueWatchingRecord.provider,
settings UI tie-breakers). All scrobble routing paths now ignore it."
```

---

## Phase 1 — Hydrate IDs at scrobble-emit time

**Goal:** Every scrobble payload carries the fully-hydrated `ProviderIds` bundle. When playback starts from a TMDB-keyed item, the scrobble call still includes IMDB, TVDB, Kitsu (if known), MAL, AniList — whatever `StableIdBundleResolver` can produce.

### Task 1.1: Create `ScrobbleIdBundleHydrator`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/ScrobbleIdBundleHydrator.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ScrobbleIdBundleHydratorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
class ScrobbleIdBundleHydratorTest {

    private val resolver = mockk<StableIdBundleResolver>()
    private val hydrator = ScrobbleIdBundleHydrator(resolver)

    @Test
    fun `hydrate returns full ProviderIds from canonical and sidecar`() = runTest {
        coEvery { resolver.resolve(any()) } returns StableIdBundle(
            itemKey = "series:tmdb:1396",
            itemType = ContentType.SERIES,
            canonical = CanonicalStableIds(tmdbMovieId = "1396", tvdbSeriesId = "81189"),
            sidecars = SidecarStableIds(imdbId = "tt0903747"),
            source = SourceStableIds(
                sourceProvider = ProviderId.TMDB,
                sourceItemId = "1396",
                railId = null,
                observedIds = ProviderIds(tmdb = "1396"),
            ),
            evidence = emptyList(),
            resolvedAtMs = 0L,
        )

        val ids = hydrator.hydrate(
            rawContentId = "tmdb:1396",
            contentType = "series",
        )

        assertEquals("tt0903747", ids.imdb)
        assertEquals("1396", ids.tmdb)
        assertEquals("81189", ids.tvdb)
    }

    @Test
    fun `hydrate preserves raw IDs from observed when resolver fails`() = runTest {
        coEvery { resolver.resolve(any()) } throws IllegalStateException("network")

        val ids = hydrator.hydrate(
            rawContentId = "tt0903747",
            contentType = "series",
        )

        assertEquals("tt0903747", ids.imdb)
        assertNull(ids.tmdb)
    }

    @Test
    fun `hydrate fills anime sidecars when present`() = runTest {
        coEvery { resolver.resolve(any()) } returns StableIdBundle(
            itemKey = "series:kitsu:1",
            itemType = ContentType.SERIES,
            canonical = CanonicalStableIds(kitsuAnimeId = "1"),
            sidecars = SidecarStableIds(
                imdbId = "tt0388629",
                malId = "21",
                anilistId = "21",
                anidbId = "69",
            ),
            source = SourceStableIds(
                sourceProvider = ProviderId.KITSU,
                sourceItemId = "1",
                railId = null,
                observedIds = ProviderIds(kitsu = "1"),
            ),
            evidence = emptyList(),
            resolvedAtMs = 0L,
        )

        val ids = hydrator.hydrate(rawContentId = "kitsu:1", contentType = "series")

        assertEquals("1", ids.kitsu)
        assertEquals("21", ids.mal)
        assertEquals("21", ids.anilist)
        assertEquals("69", ids.anidb)
        assertEquals("tt0388629", ids.imdb)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ScrobbleIdBundleHydratorTest"`
Expected: FAIL — `Unresolved reference: ScrobbleIdBundleHydrator`

- [ ] **Step 3: Write the hydrator**

Create `ScrobbleIdBundleHydrator.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.CanonicalStableIds
import com.nexio.tv.core.metadata.router.SidecarStableIds
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdBundleRequest
import com.nexio.tv.core.metadata.router.StableIdBundleResolver
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScrobbleIdBundleHydrator @Inject constructor(
    private val resolver: StableIdBundleResolver,
) {
    suspend fun hydrate(rawContentId: String, contentType: String?): ProviderIds {
        val type = when (contentType?.lowercase()) {
            "series", "tv", "show" -> ContentType.SERIES
            "movie" -> ContentType.MOVIE
            else -> ContentType.SERIES
        }
        val request = StableIdBundleRequest(
            sourceContentId = rawContentId,
            contentType = type,
        )
        return runCatching { resolver.resolve(request) }
            .map { bundle -> bundle.toProviderIds() }
            .getOrElse { fallbackFromRaw(rawContentId) }
    }

    private fun StableIdBundle.toProviderIds(): ProviderIds = ProviderIds(
        imdb = sidecars.imdbId,
        tmdb = canonical.tmdbMovieId ?: source.observedIds.tmdb,
        tvdb = canonical.tvdbSeriesId ?: source.observedIds.tvdb,
        trakt = source.observedIds.trakt,
        simkl = source.observedIds.simkl,
        kitsu = canonical.kitsuAnimeId ?: source.observedIds.kitsu,
        slug = source.observedIds.slug,
        mal = sidecars.malId,
        anilist = sidecars.anilistId,
        anidb = sidecars.anidbId,
    )

    private fun fallbackFromRaw(rawContentId: String): ProviderIds {
        val trimmed = rawContentId.trim()
        return when {
            trimmed.startsWith("tt") -> ProviderIds(imdb = trimmed)
            trimmed.startsWith("tmdb:") -> ProviderIds(tmdb = trimmed.removePrefix("tmdb:"))
            trimmed.startsWith("tvdb:") -> ProviderIds(tvdb = trimmed.removePrefix("tvdb:"))
            trimmed.startsWith("kitsu:") -> ProviderIds(kitsu = trimmed.removePrefix("kitsu:"))
            trimmed.startsWith("trakt:") -> ProviderIds(trakt = trimmed.removePrefix("trakt:"))
            trimmed.toIntOrNull() != null -> ProviderIds(trakt = trimmed)
            else -> ProviderIds()
        }
    }
}
```

Note: confirm the actual `StableIdBundleRequest` constructor params via `Read app/src/main/java/com/nexio/tv/core/metadata/router/StableIdBundleModels.kt` if compilation fails — the audit only enumerated the response shape, not the request.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ScrobbleIdBundleHydratorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ScrobbleIdBundleHydrator.kt \
        app/src/test/java/com/nexio/tv/data/repository/ScrobbleIdBundleHydratorTest.kt
git commit -m "feat(scrobble): introduce ScrobbleIdBundleHydrator

Wraps StableIdBundleResolver to produce a fully-populated ProviderIds
from a raw contentId. Canonical IDs (tmdb/tvdb/kitsu) come from the
bundle's canonical block, IMDB and anime sidecars from sidecars, and
trakt/simkl/slug from observed source facts. Falls back to a naive
prefix parse if the resolver throws."
```

### Task 1.2: Thread `hydratedIds` through `TrackingScrobbleItem`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt:23-38`

- [ ] **Step 1: Write the failing test**

In `app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleItemTest.kt`:

```kotlin
class TrackingScrobbleItemTest {
    @Test
    fun `movie carries hydratedIds`() {
        val ids = ProviderIds(imdb = "tt1", tmdb = "1")
        val movie = TrackingScrobbleItem.Movie(
            contentId = "tmdb:1",
            title = "x",
            year = 2000,
            hydratedIds = ids,
        )
        assertEquals(ids, movie.hydratedIds)
    }

    @Test
    fun `episode carries hydratedIds`() {
        val ids = ProviderIds(imdb = "tt1", tmdb = "1", tvdb = "10")
        val ep = TrackingScrobbleItem.Episode(
            contentId = "tmdb:1",
            showTitle = "x", showYear = 2000,
            season = 1, number = 1, episodeTitle = "p",
            hydratedIds = ids,
        )
        assertEquals(ids, ep.hydratedIds)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.TrackingScrobbleItemTest"`
Expected: FAIL — `No value passed for parameter 'hydratedIds'`

- [ ] **Step 3: Add `hydratedIds` to both subclasses**

In `TrackingScrobbleService.kt` lines 23-38, modify:

```kotlin
sealed interface TrackingScrobbleItem {
    val contentId: String
    val hydratedIds: ProviderIds?

    data class Movie(
        override val contentId: String,
        val title: String?,
        val year: Int?,
        override val hydratedIds: ProviderIds? = null,
    ) : TrackingScrobbleItem

    data class Episode(
        override val contentId: String,
        val showTitle: String?,
        val showYear: Int?,
        val season: Int,
        val number: Int,
        val episodeTitle: String?,
        override val hydratedIds: ProviderIds? = null,
    ) : TrackingScrobbleItem
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.TrackingScrobbleItemTest"`
Expected: PASS

- [ ] **Step 5: Build to confirm no other callers break**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
Expected: clean — defaults to `null` so existing call sites compile.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt \
        app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleItemTest.kt
git commit -m "feat(scrobble): add hydratedIds to TrackingScrobbleItem

Nullable to keep existing call sites compiling; will be required in
practice once PlayerRuntimeController.buildScrobbleItem populates it
from ScrobbleIdBundleHydrator."
```

### Task 1.3: Populate `hydratedIds` in `buildScrobbleItem`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt:299-335`

- [ ] **Step 1: Write the failing test**

This function is `internal` and called from a Compose runtime. Test it via the same fixture used by adjacent player tests. If none exists, create:

`app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerScrobbleItemTest.kt`:

```kotlin
class PlayerRuntimeControllerScrobbleItemTest {

    @Test
    fun `buildScrobbleItem populates hydratedIds via hydrator`() = runTest {
        val hydrator = mockk<ScrobbleIdBundleHydrator>()
        coEvery { hydrator.hydrate("tmdb:1396", "series") } returns
            ProviderIds(imdb = "tt0903747", tmdb = "1396", tvdb = "81189")

        val controller = playerRuntimeControllerWith(
            contentId = "tmdb:1396",
            contentType = "series",
            currentSeason = 5,
            currentEpisode = 14,
            scrobbleIdBundleHydrator = hydrator,
        )

        val item = controller.buildScrobbleItem() as TrackingScrobbleItem.Episode
        assertEquals("tt0903747", item.hydratedIds?.imdb)
        assertEquals("1396", item.hydratedIds?.tmdb)
        assertEquals("81189", item.hydratedIds?.tvdb)
    }
}
```

(Helper `playerRuntimeControllerWith(...)` mirrors whatever fixture adjacent tests in `app/src/test/java/com/nexio/tv/ui/screens/player/` already use; if the controller can't be constructed in test, extract the hydration into a small testable function and test that directly.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerScrobbleItemTest"`
Expected: FAIL — `hydratedIds` is null because `buildScrobbleItem` doesn't call the hydrator yet.

- [ ] **Step 3: Inject the hydrator and call it**

`PlayerRuntimeController` needs a `scrobbleIdBundleHydrator: ScrobbleIdBundleHydrator` dependency. Add it via the same DI mechanism used for `trackingScrobbleService` (likely an `@Inject` constructor param on the controller's host or passed via a Compose `LocalProvider`).

Then modify `buildScrobbleItem` (lines 299-335). The current signature is non-suspend; this needs to change. Two options:

**Option A (recommended):** Make `buildScrobbleItem` suspend.
**Option B:** Pre-hydrate once on playback start, cache `currentHydratedIds: ProviderIds?` on the controller, read it synchronously in `buildScrobbleItem`.

Option B is safer because `buildScrobbleItem` is called from heartbeat tick paths. Implement Option B:

```kotlin
// Add to PlayerRuntimeController state (the file holding state for these events):
internal var currentHydratedIds: ProviderIds? = null
internal var hydratedIdsForContentId: String? = null

// New helper, called from playback-start path before any emitScrobbleStart:
internal suspend fun PlayerRuntimeController.prehydrateScrobbleIds() {
    val raw = contentId ?: run {
        currentHydratedIds = null
        hydratedIdsForContentId = null
        return
    }
    if (hydratedIdsForContentId == raw && currentHydratedIds != null) return
    currentHydratedIds = scrobbleIdBundleHydrator.hydrate(raw, contentType)
    hydratedIdsForContentId = raw
}
```

Then in `buildScrobbleItem` (line 299-335), populate `hydratedIds`:

```kotlin
val item = if (isEpisode) {
    TrackingScrobbleItem.Episode(
        contentId = rawContentId,
        showTitle = contentName ?: title,
        showYear = parsedYear,
        season = effectiveSeason ?: return null,
        number = effectiveEpisode ?: return null,
        episodeTitle = currentEpisodeTitle,
        hydratedIds = currentHydratedIds,
    )
} else {
    TrackingScrobbleItem.Movie(
        contentId = rawContentId,
        title = contentName ?: title,
        year = parsedYear,
        hydratedIds = currentHydratedIds,
    )
}
return item
```

Wire `prehydrateScrobbleIds()` to be called from the existing playback-start callback (the same place `buildScrobbleItem` is first invoked — search the file for `currentScrobbleItem = it` near line 339; call `scope.launch { prehydrateScrobbleIds() }` just before that). Also call it when `contentId` changes.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerRuntimeControllerScrobbleItemTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt \
        app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerScrobbleItemTest.kt
git commit -m "feat(player): pre-hydrate ProviderIds for every scrobble emit

Calls ScrobbleIdBundleHydrator once on content load, caches the result
keyed by contentId, and populates TrackingScrobbleItem.hydratedIds on
every buildScrobbleItem call. Heartbeat ticks stay synchronous."
```

### Task 1.4: Consume `hydratedIds` in `TraktScrobbleMutationAdapter`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt:269-292`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt:160-241` (the `toTraktItem` / `toTraktIds` path)
- Test: `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `scrobble envelope uses hydratedIds when present`() = runTest {
    val item = TrackingScrobbleItem.Movie(
        contentId = "tmdb:1396",
        title = "Breaking Bad",
        year = 2008,
        hydratedIds = ProviderIds(imdb = "tt0903747", tmdb = "1396"),
    )
    val service = newTrackingScrobbleService()
    val traktItem = service.toTraktItemForTest(item)!!
    assertEquals("tt0903747", traktItem.ids.imdb)
    assertEquals(1396, traktItem.ids.tmdb)
}

@Test
fun `scrobble envelope falls back to contentId parse when hydratedIds null`() = runTest {
    val item = TrackingScrobbleItem.Movie(
        contentId = "tmdb:1396",
        title = "x",
        year = null,
        hydratedIds = null,
    )
    val service = newTrackingScrobbleService()
    val traktItem = service.toTraktItemForTest(item)!!
    assertEquals(1396, traktItem.ids.tmdb)
    assertNull(traktItem.ids.imdb)
}
```

Expose `toTraktItem` via an `@VisibleForTesting` wrapper named `toTraktItemForTest`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.trakt.TraktScrobbleMutationAdapterTest"`
Expected: FAIL — current code uses `parseContentIds(rawContentId)` and ignores `hydratedIds`.

- [ ] **Step 3: Prefer `hydratedIds` in `toTraktItem`**

In `TrackingScrobbleService.kt:160-233`, modify `toTraktItem`:

```kotlin
private suspend fun toTraktItem(item: TrackingScrobbleItem): TraktScrobbleItem? {
    val contentId = item.contentId
    val animeId = AnimeStremioId.parse(contentId)?.takeIf { it.source in ANIME_NATIVE_SOURCES }
    if (animeId != null) {
        // unchanged — anime projection still flows through projectAnimeToTraktItem
        val resolvedKitsuId = when (animeId.source) {
            AnimeIdSource.KITSU -> animeId.value
            else -> idMappingService.resolveKitsuId(animeId, ContentMediaKind.SERIES)
        }
        if (resolvedKitsuId == null) {
            rejectionReporter.reportRejection(
                contentId, ScrobbleRejectionReason.NO_PARSEABLE_IDS, TrackingProvider.TRAKT,
            )
            return null
        }
        return projectAnimeToTraktItem(item, resolvedKitsuId)
    }

    val ids = item.hydratedIds?.toTraktIds()
        ?: toTraktIds(parseContentIds(contentId))
    if (!ids.hasAnyId()) {
        rejectionReporter.reportRejection(
            contentId, ScrobbleRejectionReason.NO_PARSEABLE_IDS, TrackingProvider.TRAKT,
        )
        return null
    }
    return when (item) {
        is TrackingScrobbleItem.Movie -> TraktScrobbleItem.Movie(item.title, item.year, ids)
        is TrackingScrobbleItem.Episode -> TraktScrobbleItem.Episode(
            item.showTitle, item.showYear, ids, item.season, item.number, item.episodeTitle,
        )
    }
}

@VisibleForTesting
internal suspend fun toTraktItemForTest(item: TrackingScrobbleItem): TraktScrobbleItem? = toTraktItem(item)
```

The existing `ProviderIds.toTraktIds()` extension at lines 236-241 stays unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.trakt.TraktScrobbleMutationAdapterTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt \
        app/src/test/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapterTest.kt
git commit -m "feat(trakt): prefer hydratedIds over contentId parse for scrobble payload

When the player attaches a hydrated ProviderIds bundle, the Trakt
TraktIdsDto is built from it directly — IMDB, TMDB, TVDB, Trakt all
ship when known. Falls back to parseContentIds() only when the
hydrator returned nothing."
```

### Task 1.5: Consume `hydratedIds` in `SimklScrobbleMutationAdapter`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/simkl/SimklScrobbleMutationAdapter.kt:166-188, 260-270`
- Test: existing `SimklScrobbleMutationAdapterTest.kt` if present, else create

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `simkl envelope uses hydratedIds when present`() = runTest {
    val item = TrackingScrobbleItem.Movie(
        contentId = "tmdb:1396",
        title = "x",
        year = null,
        hydratedIds = ProviderIds(imdb = "tt0903747", tmdb = "1396", simkl = "5045"),
    )
    val adapter = newSimklScrobbleMutationAdapter()
    val envelope = adapter.buildScrobbleEnvelope(
        item = item,
        action = "start",
        progressPercent = 10f,
        ownerProfileId = 1,
        ownerSessionId = "s",
    )
    val payload = envelope.payload.asJsonObject
    assertEquals(5045L, payload["simkl"].asLong)
    assertEquals("tt0903747", payload["imdb"].asString)
    assertEquals("1396", payload["tmdb"].asString)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.simkl.SimklScrobbleMutationAdapterTest"`
Expected: FAIL — current `parseSimklIds()` only parses contentId.

- [ ] **Step 3: Prefer `hydratedIds` in `populateItem`**

In `SimklScrobbleMutationAdapter.kt` modify `populateItem` (lines 166-188) to prefer `hydratedIds` over `parseSimklIds(contentId)`:

```kotlin
private fun populateItem(payload: JsonObject, item: TrackingScrobbleItem) {
    val ids = item.hydratedIds?.toSimklIds() ?: parseSimklIds(item.contentId)
    payload.addPropertyIfNotNull("imdb", ids.imdb)
    payload.addPropertyIfNotNull("tmdb", ids.tmdb)
    ids.simkl?.let { payload.addProperty("simkl", it) }
    // ... existing title/year/season/episode population unchanged ...
}

private fun ProviderIds.toSimklIds(): ParsedSimklIds = ParsedSimklIds(
    simkl = simkl?.toLongOrNull(),
    imdb = imdb,
    tmdb = tmdb,
)
```

If `populateItem` previously inlined the JSON construction differently, match its style. The point is: every `ProviderIds` field Simkl accepts (`imdb, tmdb, simkl, tvdb` for non-anime; +`mal/anilist/kitsu/anidb` for anime) becomes a JSON field on the payload.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.simkl.SimklScrobbleMutationAdapterTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/simkl/SimklScrobbleMutationAdapter.kt \
        app/src/test/java/com/nexio/tv/data/repository/simkl/SimklScrobbleMutationAdapterTest.kt
git commit -m "feat(simkl): prefer hydratedIds over contentId parse

Mirrors TraktScrobbleMutationAdapter: when the player hydrates a
ProviderIds bundle, Simkl envelopes ship every supported ID
(simkl/imdb/tmdb plus tvdb/mal/anilist/kitsu/anidb in Phase 4).
Falls back to parseSimklIds() when no bundle is attached."
```

---

## Phase 2 — Multi-ID CW dedup

**Goal:** Continue Watching merges items by shared multi-ID intersection (IMDB → TMDB → TVDB → Kitsu priority), so an item arriving from Trakt CW as `tt0903747` and from local resume as `tmdb:1396` collapses to a single entry.

### Task 2.1: Create `ContinueWatchingIdBundle`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdBundle.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdBundleTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
class ContinueWatchingIdBundleTest {

    @Test
    fun `matches when imdb ids match`() {
        val a = ContinueWatchingIdBundle(imdb = "tt1", tmdb = null)
        val b = ContinueWatchingIdBundle(imdb = "tt1", tmdb = "999")
        assertTrue(a.matches(b))
    }

    @Test
    fun `matches when tmdb ids match`() {
        val a = ContinueWatchingIdBundle(tmdb = "1396")
        val b = ContinueWatchingIdBundle(tmdb = "1396")
        assertTrue(a.matches(b))
    }

    @Test
    fun `no match when ids disjoint`() {
        val a = ContinueWatchingIdBundle(imdb = "tt1")
        val b = ContinueWatchingIdBundle(imdb = "tt2")
        assertFalse(a.matches(b))
    }

    @Test
    fun `no match when bundles empty`() {
        val a = ContinueWatchingIdBundle()
        val b = ContinueWatchingIdBundle()
        assertFalse(a.matches(b))
    }

    @Test
    fun `episode bundles only match when season and episode match`() {
        val a = ContinueWatchingIdBundle(imdb = "tt1", season = 1, episode = 5)
        val b = ContinueWatchingIdBundle(imdb = "tt1", season = 1, episode = 6)
        assertFalse(a.matches(b))
    }

    @Test
    fun `priority key prefers imdb then tmdb then tvdb then kitsu`() {
        assertEquals("imdb:tt1",
            ContinueWatchingIdBundle(imdb = "tt1", tmdb = "1", tvdb = "10", kitsu = "x").priorityKey())
        assertEquals("tmdb:1",
            ContinueWatchingIdBundle(tmdb = "1", tvdb = "10").priorityKey())
        assertEquals("tvdb:10",
            ContinueWatchingIdBundle(tvdb = "10", kitsu = "x").priorityKey())
        assertEquals("kitsu:x",
            ContinueWatchingIdBundle(kitsu = "x").priorityKey())
        assertEquals(null, ContinueWatchingIdBundle().priorityKey())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingIdBundleTest"`
Expected: FAIL — unresolved class.

- [ ] **Step 3: Implement the bundle**

```kotlin
package com.nexio.tv.data.repository

data class ContinueWatchingIdBundle(
    val imdb: String? = null,
    val tmdb: String? = null,
    val tvdb: String? = null,
    val kitsu: String? = null,
    val mal: String? = null,
    val anilist: String? = null,
    val anidb: String? = null,
    val trakt: String? = null,
    val simkl: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    fun matches(other: ContinueWatchingIdBundle): Boolean {
        if (season != other.season || episode != other.episode) return false
        val a = idMap()
        val b = other.idMap()
        if (a.isEmpty() || b.isEmpty()) return false
        for ((k, v) in a) {
            val ov = b[k] ?: continue
            if (v == ov) return true
        }
        return false
    }

    fun priorityKey(): String? {
        imdb?.let { return "imdb:$it" }
        tmdb?.let { return "tmdb:$it" }
        tvdb?.let { return "tvdb:$it" }
        kitsu?.let { return "kitsu:$it" }
        mal?.let { return "mal:$it" }
        anilist?.let { return "anilist:$it" }
        anidb?.let { return "anidb:$it" }
        trakt?.let { return "trakt:$it" }
        simkl?.let { return "simkl:$it" }
        return null
    }

    private fun idMap(): Map<String, String> = buildMap {
        imdb?.let { put("imdb", it) }
        tmdb?.let { put("tmdb", it) }
        tvdb?.let { put("tvdb", it) }
        kitsu?.let { put("kitsu", it) }
        mal?.let { put("mal", it) }
        anilist?.let { put("anilist", it) }
        anidb?.let { put("anidb", it) }
        trakt?.let { put("trakt", it) }
        simkl?.let { put("simkl", it) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingIdBundleTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdBundle.kt \
        app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdBundleTest.kt
git commit -m "feat(cw): add ContinueWatchingIdBundle with multi-ID matches()

Encapsulates the full provider-ID bundle for a CW record. matches()
returns true when any non-null ID overlaps with a peer; priorityKey()
picks the canonical key in IMDB→TMDB→TVDB→Kitsu order, falling back
to anime/tracking IDs. Episode bundles require season+episode match
in addition to ID overlap."
```

### Task 2.2: Attach `idBundle` to `ContinueWatchingRecord`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingRecord.kt:5-68`

- [ ] **Step 1: Write the failing test**

In `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingRecordTest.kt`:

```kotlin
@Test
fun `idBundle default is empty bundle (back-compat)`() {
    val r = continueWatchingRecord(profileId = 1, parentId = "tt1")
    assertEquals(ContinueWatchingIdBundle(), r.idBundle)
}

@Test
fun `record retains explicit idBundle`() {
    val bundle = ContinueWatchingIdBundle(imdb = "tt1", tmdb = "1")
    val r = continueWatchingRecord(profileId = 1, parentId = "tt1", idBundle = bundle)
    assertEquals(bundle, r.idBundle)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingRecordTest"`
Expected: FAIL — `idBundle` field doesn't exist.

- [ ] **Step 3: Add the field**

In `ContinueWatchingRecord.kt`, add to the data class (around line 25, after `resumeIdentities`):

```kotlin
val idBundle: ContinueWatchingIdBundle = ContinueWatchingIdBundle(),
```

Default empty so all existing call sites compile.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingRecordTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingRecord.kt \
        app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingRecordTest.kt
git commit -m "feat(cw): add idBundle field to ContinueWatchingRecord

Default empty so existing producers compile. Phase 2.3 will populate
it in ContinueWatchingIdentityResolver from the StableIdBundle."
```

### Task 2.3: Populate `idBundle` in `ContinueWatchingIdentityResolver`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt:80-260`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `resolver populates idBundle from StableIdBundle`() = runTest {
    val facade = mockk<MetadataRouterFacade>()
    coEvery { facade.resolveStableIdBundle(any()) } returns StableIdBundle(
        itemKey = "series:tmdb:1396",
        itemType = ContentType.SERIES,
        canonical = CanonicalStableIds(tmdbMovieId = "1396", tvdbSeriesId = "81189"),
        sidecars = SidecarStableIds(imdbId = "tt0903747"),
        source = SourceStableIds(
            sourceProvider = ProviderId.TMDB,
            sourceItemId = "1396",
            railId = null,
            observedIds = ProviderIds(tmdb = "1396"),
        ),
        evidence = emptyList(),
        resolvedAtMs = 0L,
    )
    val resolver = ContinueWatchingIdentityResolver(facade, StreamFetchIdentityResolver())
    val record = resolver.resolveLocalResume(/* fixture args */)
    assertEquals("tt0903747", record.idBundle.imdb)
    assertEquals("1396", record.idBundle.tmdb)
    assertEquals("81189", record.idBundle.tvdb)
}
```

(Adjust to whatever the actual resolver entry method is named — `resolveOrFallback`, `resolveLocalResume`, etc. Read the file to confirm before writing the test.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingIdentityResolverTest"`
Expected: FAIL — `idBundle` is empty default.

- [ ] **Step 3: Populate `idBundle` from `MetadataRouterFacade.resolveStableIdBundle`**

Find every site in `ContinueWatchingIdentityResolver.kt` where a `ContinueWatchingRecord(...)` is constructed (the audit identified two key sites at lines 84 and 164 where `provider = TrackingProvider.TRAKT` is hardcoded). At each construction site, pass:

```kotlin
idBundle = stableIdBundle?.toContinueWatchingIdBundle(
    season = episodeContext?.season,
    episode = episodeContext?.number,
) ?: ContinueWatchingIdBundle(),
```

Add the extension at the bottom of the file:

```kotlin
private fun StableIdBundle.toContinueWatchingIdBundle(
    season: Int?,
    episode: Int?,
): ContinueWatchingIdBundle = ContinueWatchingIdBundle(
    imdb = sidecars.imdbId,
    tmdb = canonical.tmdbMovieId ?: source.observedIds.tmdb,
    tvdb = canonical.tvdbSeriesId ?: source.observedIds.tvdb,
    kitsu = canonical.kitsuAnimeId ?: source.observedIds.kitsu,
    mal = sidecars.malId,
    anilist = sidecars.anilistId,
    anidb = sidecars.anidbId,
    trakt = source.observedIds.trakt,
    simkl = source.observedIds.simkl,
    season = season,
    episode = episode,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingIdentityResolverTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt \
        app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolverTest.kt
git commit -m "feat(cw): populate idBundle from StableIdBundle on every record

ContinueWatchingIdentityResolver now writes a full ContinueWatchingIdBundle
into every record it produces. Phase 2.4 will use it for cross-source
merge; existing identityKey() path stays in place for back-compat."
```

### Task 2.4: Replace opaque merge with multi-ID intersection

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt:4-11`

- [ ] **Step 1: Write the failing test**

In `ContinueWatchingMergerTest.kt` add:

```kotlin
@Test
fun `merges records sharing imdb across different parentIds`() {
    val a = record(
        parentId = "tmdb:1396",
        idBundle = ContinueWatchingIdBundle(imdb = "tt0903747", tmdb = "1396"),
        updatedAt = 1000L,
        positionMs = 5_000L, durationMs = 60_000L,
    )
    val b = record(
        parentId = "tt0903747",
        idBundle = ContinueWatchingIdBundle(imdb = "tt0903747"),
        updatedAt = 2000L,
        positionMs = 10_000L, durationMs = 60_000L,
    )
    val merged = ContinueWatchingMerger.merge(listOf(a, b))
    assertEquals(1, merged.size)
    assertEquals(2000L, merged.first().updatedAt)
}

@Test
fun `merges records sharing tmdb when imdb absent on one side`() {
    val a = record(
        parentId = "x",
        idBundle = ContinueWatchingIdBundle(tmdb = "1396"),
        updatedAt = 1000L,
    )
    val b = record(
        parentId = "y",
        idBundle = ContinueWatchingIdBundle(tmdb = "1396", imdb = "tt0903747"),
        updatedAt = 2000L,
    )
    val merged = ContinueWatchingMerger.merge(listOf(a, b))
    assertEquals(1, merged.size)
}

@Test
fun `episodes only merge when season and episode also match`() {
    val s1e1 = record(
        idBundle = ContinueWatchingIdBundle(imdb = "tt1", season = 1, episode = 1),
        updatedAt = 1000L,
    )
    val s1e2 = record(
        idBundle = ContinueWatchingIdBundle(imdb = "tt1", season = 1, episode = 2),
        updatedAt = 2000L,
    )
    val merged = ContinueWatchingMerger.merge(listOf(s1e1, s1e2))
    assertEquals(2, merged.size)
}

@Test
fun `falls back to identityKey when idBundle empty`() {
    // Two records, no idBundle population, same parentId+season+episode collapse via identityKey().
    val a = record(parentId = "tt1", updatedAt = 1000L)
    val b = record(parentId = "tt1", updatedAt = 2000L)
    val merged = ContinueWatchingMerger.merge(listOf(a, b))
    assertEquals(1, merged.size)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingMergerTest"`
Expected: FAIL — current merge groups by opaque `identityKey()`, so `parentId="tmdb:1396"` and `parentId="tt0903747"` produce different keys → not merged.

- [ ] **Step 3: Rewrite merge to use union-find on multi-ID overlap**

Replace `ContinueWatchingMerger.merge` (lines 4-11) with:

```kotlin
object ContinueWatchingMerger {

    fun merge(records: List<ContinueWatchingRecord>): List<ContinueWatchingRecord> {
        if (records.isEmpty()) return emptyList()
        val sorted = records.sortedByDescending { it.updatedAt }

        // Union-find over records: index i merges with index j if their idBundles match()
        // OR if their identityKey() collides (back-compat for records with empty idBundle).
        val parent = IntArray(sorted.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun union(i: Int, j: Int) {
            val ri = find(i); val rj = find(j)
            if (ri != rj) parent[ri] = rj
        }

        // Bucket by every non-null ID for O(n) merging instead of O(n^2).
        val byId = HashMap<String, MutableList<Int>>()
        sorted.forEachIndexed { idx, r ->
            r.idBundle.toBucketKeys().forEach { key ->
                byId.getOrPut(key) { mutableListOf() }.add(idx)
            }
            // Back-compat: bucket by identityKey when no idBundle entries exist.
            if (r.idBundle.toBucketKeys().isEmpty()) {
                val key = "legacy:${r.identityKey()}"
                byId.getOrPut(key) { mutableListOf() }.add(idx)
            }
        }
        byId.values.forEach { indices ->
            for (i in 1 until indices.size) union(indices[0], indices[i])
        }

        // Reduce each group via the existing pairwise mergeRecords() helper, preserving its
        // "prefer most recent / most progress" semantics.
        val groups = LinkedHashMap<Int, ContinueWatchingRecord>()
        sorted.indices.forEach { idx ->
            val root = find(idx)
            val cur = groups[root]
            groups[root] = if (cur == null) sorted[idx] else mergeRecords(cur, sorted[idx])
        }
        return groups.values.sortedByDescending { it.updatedAt }
    }

    private fun ContinueWatchingIdBundle.toBucketKeys(): List<String> {
        val seasonSuffix = if (season != null && episode != null) ":s${season}e${episode}" else ""
        return buildList {
            imdb?.let { add("imdb:$it$seasonSuffix") }
            tmdb?.let { add("tmdb:$it$seasonSuffix") }
            tvdb?.let { add("tvdb:$it$seasonSuffix") }
            kitsu?.let { add("kitsu:$it$seasonSuffix") }
            mal?.let { add("mal:$it$seasonSuffix") }
            anilist?.let { add("anilist:$it$seasonSuffix") }
            anidb?.let { add("anidb:$it$seasonSuffix") }
            trakt?.let { add("trakt:$it$seasonSuffix") }
            simkl?.let { add("simkl:$it$seasonSuffix") }
        }
    }

    // mergeRecords() — keep the existing helper from the original file (it handles which
    // positionMs/durationMs/clickTimeDisplayMetadata to keep). If the original file had it
    // private, expose it here unchanged.
}
```

If `mergeRecords` was previously a top-level private function in the same file, keep it where it is and call into it. Do not reimplement it.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingMergerTest"`
Expected: PASS — all four new cases plus all pre-existing merger tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt \
        app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMergerTest.kt
git commit -m "feat(cw): merge by multi-ID intersection instead of opaque key

Records sharing any non-null ID (imdb/tmdb/tvdb/kitsu/mal/anilist/
anidb/trakt/simkl) collapse to one entry, gated on season+episode for
episodes. Union-find keeps it O(n). Records without an idBundle fall
back to the previous identityKey() grouping for back-compat with any
producer still emitting empty bundles."
```

---

## Phase 3 — Simkl as a Continue Watching source

**Goal:** Simkl's playback / history feeds populate CW alongside Trakt's. When the same item exists on both providers with different progress / timestamps, conflict resolution mirrors CrossWatch's `diff_progress` planner.

### Task 3.0: Scope check — confirm Simkl pull infrastructure shape

**Files:**
- Read: `app/src/main/java/com/nexio/tv/data/repository/simkl/` (whole directory)
- Read: `app/src/main/java/com/nexio/tv/data/remote/SimklTrackingRemoteDataSource.kt` (or wherever the Simkl API client lives)

- [ ] **Step 1: Confirm what exists**

Run:
```bash
find app/src/main/java/com/nexio/tv -name "Simkl*" -type f | sort
grep -rn "fun.*continueWatching\|fun.*playback\|fun.*progress" app/src/main/java/com/nexio/tv/data/repository/simkl/
```

You need to know whether `SimklProgressService` (mentioned in the audit) already exposes:
- Observable in-progress items via Simkl's `/sync/all-items?status=watching` (or equivalent)
- Episode-level progress data

If yes, Task 3.2 wires it. If no, Task 3.2 also adds the endpoint client + DTO. Adjust per actual code shape — do NOT create duplicate endpoints if they exist.

- [ ] **Step 2: Write a short note in the commit if scope shifts**

No code change in this task; it's a context-gathering step. Do not commit empty changes.

### Task 3.1: Add `SOURCE_SIMKL_PLAYBACK` and `SOURCE_SIMKL_HISTORY`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/WatchProgress.kt:31-35`

- [ ] **Step 1: Write the failing test**

In `app/src/test/java/com/nexio/tv/domain/model/WatchProgressTest.kt`:

```kotlin
@Test
fun `SOURCE_SIMKL_PLAYBACK and SOURCE_SIMKL_HISTORY are defined`() {
    assertEquals("simkl_playback", WatchProgress.SOURCE_SIMKL_PLAYBACK)
    assertEquals("simkl_history", WatchProgress.SOURCE_SIMKL_HISTORY)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.domain.model.WatchProgressTest"`
Expected: FAIL — constants missing.

- [ ] **Step 3: Add the constants**

In `WatchProgress.kt:31-35`, modify the companion object:

```kotlin
companion object {
    const val SOURCE_LOCAL = "local"
    const val SOURCE_TRAKT_PLAYBACK = "trakt_playback"
    const val SOURCE_TRAKT_HISTORY = "trakt_history"
    const val SOURCE_TRAKT_SHOW_PROGRESS = "trakt_show_progress"
    const val SOURCE_SIMKL_PLAYBACK = "simkl_playback"
    const val SOURCE_SIMKL_HISTORY = "simkl_history"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.domain.model.WatchProgressTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/WatchProgress.kt \
        app/src/test/java/com/nexio/tv/domain/model/WatchProgressTest.kt
git commit -m "feat(cw): add SOURCE_SIMKL_PLAYBACK and SOURCE_SIMKL_HISTORY

Mirrors the SOURCE_TRAKT_* constants so SimklContinueWatchingPuller can
tag the WatchProgress entries it produces. ContinueWatchingIdentityResolver
will use these to differentiate provider origin per record in 3.3."
```

### Task 3.2: Implement `SimklContinueWatchingPuller`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/simkl/SimklContinueWatchingPuller.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/simkl/SimklContinueWatchingPullerTest.kt`

- [ ] **Step 1: Find Trakt's equivalent for reference**

Run: `grep -rn "class.*ContinueWatchingPuller\|class.*ProgressPuller\|fun.*pullCw" app/src/main/java/com/nexio/tv/data/repository/trakt/`

There should be a class that fetches `/sync/playback` + `/sync/history/episodes` and emits `WatchProgress`. Read it. Note its constructor dependencies, output type (`Flow<List<WatchProgress>>` or one-shot `suspend fun pull()`), and how it tags `source = SOURCE_TRAKT_PLAYBACK`.

- [ ] **Step 2: Write the failing test**

```kotlin
class SimklContinueWatchingPullerTest {

    private val remote = mockk<SimklTrackingRemoteDataSource>()
    private val puller = SimklContinueWatchingPuller(remote)

    @Test
    fun `pull returns WatchProgress entries tagged SOURCE_SIMKL_PLAYBACK`() = runTest {
        coEvery { remote.getInProgressItems(any()) } returns listOf(
            simklInProgressMovie(imdb = "tt1", tmdb = "1", progressPercent = 35f, lastWatchedMs = 1000L),
        )
        val entries = puller.pullPlayback(profileId = 1)
        assertEquals(1, entries.size)
        assertEquals(WatchProgress.SOURCE_SIMKL_PLAYBACK, entries[0].source)
        assertEquals("tt1", entries[0].contentId)
    }

    @Test
    fun `episodes carry season and number`() = runTest {
        coEvery { remote.getInProgressItems(any()) } returns listOf(
            simklInProgressEpisode(imdb = "tt2", season = 3, episode = 7, progressPercent = 12f),
        )
        val entries = puller.pullPlayback(profileId = 1)
        assertEquals(3, entries[0].episodeSeason)
        assertEquals(7, entries[0].episodeNumber)
    }
}
```

(Replace `simklInProgressMovie / simklInProgressEpisode` helpers with whatever DTO shape exists. Read `SimklTrackingRemoteDataSource` first.)

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.simkl.SimklContinueWatchingPullerTest"`
Expected: FAIL — class doesn't exist.

- [ ] **Step 4: Implement the puller**

```kotlin
package com.nexio.tv.data.repository.simkl

import com.nexio.tv.data.remote.SimklTrackingRemoteDataSource
import com.nexio.tv.domain.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklContinueWatchingPuller @Inject constructor(
    private val remote: SimklTrackingRemoteDataSource,
) {
    suspend fun pullPlayback(profileId: Int): List<WatchProgress> {
        val items = runCatching { remote.getInProgressItems(profileId) }.getOrElse { return emptyList() }
        return items.mapNotNull { it.toWatchProgress(profileId, WatchProgress.SOURCE_SIMKL_PLAYBACK) }
    }

    suspend fun pullHistory(profileId: Int): List<WatchProgress> {
        val items = runCatching { remote.getHistoryItems(profileId) }.getOrElse { return emptyList() }
        return items.mapNotNull { it.toWatchProgress(profileId, WatchProgress.SOURCE_SIMKL_HISTORY) }
    }
}
```

Mapping `SimklInProgressItem.toWatchProgress(profileId, source)` lives in the same file or as a sibling. The mapping should set `contentId = imdb ?: "tmdb:$tmdb" ?: "simkl:$simkl"`, `episodeSeason`, `episodeNumber`, `positionMs`, `durationMs`, `progressPercent`, `lastUpdated`. Match field names to the existing `WatchProgress` data class — read `WatchProgress.kt` before writing.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.simkl.SimklContinueWatchingPullerTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/simkl/SimklContinueWatchingPuller.kt \
        app/src/test/java/com/nexio/tv/data/repository/simkl/SimklContinueWatchingPullerTest.kt
git commit -m "feat(simkl): pull Simkl playback + history as WatchProgress

SimklContinueWatchingPuller fetches in-progress and history items from
SimklTrackingRemoteDataSource and emits WatchProgress entries tagged
SOURCE_SIMKL_PLAYBACK / SOURCE_SIMKL_HISTORY. Network failures return
an empty list (parity with Trakt puller behavior)."
```

### Task 3.3: Remove `provider = TRAKT` hardcode and source-tag per record

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt:84, 164`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `resolver tags Simkl-sourced WatchProgress with provider SIMKL`() = runTest {
    val resolver = newIdentityResolver()
    val record = resolver.resolveForWatchProgress(
        watchProgress = watchProgress(source = WatchProgress.SOURCE_SIMKL_PLAYBACK, contentId = "tt1"),
    )
    assertEquals(TrackingProvider.SIMKL, record.provider)
}

@Test
fun `resolver tags Trakt-sourced WatchProgress with provider TRAKT`() = runTest {
    val resolver = newIdentityResolver()
    val record = resolver.resolveForWatchProgress(
        watchProgress = watchProgress(source = WatchProgress.SOURCE_TRAKT_PLAYBACK, contentId = "tt1"),
    )
    assertEquals(TrackingProvider.TRAKT, record.provider)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingIdentityResolverTest"`
Expected: FAIL — both records currently get `provider = TRAKT`.

- [ ] **Step 3: Replace hardcode with source-driven mapping**

In `ContinueWatchingIdentityResolver.kt`, at lines 84 and 164 (and any other site where `provider = TrackingProvider.TRAKT` appears), replace with:

```kotlin
provider = providerForSource(watchProgress.source),
```

Add helper at the bottom of the file:

```kotlin
private fun providerForSource(source: String): TrackingProvider = when (source) {
    WatchProgress.SOURCE_SIMKL_PLAYBACK,
    WatchProgress.SOURCE_SIMKL_HISTORY -> TrackingProvider.SIMKL
    WatchProgress.SOURCE_TRAKT_PLAYBACK,
    WatchProgress.SOURCE_TRAKT_HISTORY,
    WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS -> TrackingProvider.TRAKT
    else -> TrackingProvider.TRAKT  // SOURCE_LOCAL falls through to the legacy default
}
```

If a record is constructed with no `watchProgress` reference in scope (e.g. synthetic next-up), keep `TrackingProvider.TRAKT` as the legacy default — flag with a `@Suppress("DEPRECATION")` comment referencing Task 0.4.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingIdentityResolverTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt \
        app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolverTest.kt
git commit -m "fix(cw): tag CW records with the provider that produced them

Removes the 'provider = TRAKT' hardcode at lines 84 and 164. WatchProgress
records sourced from SOURCE_SIMKL_* now get provider = SIMKL on the
ContinueWatchingRecord, so downstream UI can show the right badge and
SimklContinueWatchingPuller output flows through correctly."
```

### Task 3.4: Implement `ContinueWatchingProgressDiffPlanner`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingProgressDiffPlanner.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingProgressDiffPlannerTest.kt`

This ports CrossWatch's `_planner.py:207-354` algorithm. Reference (CrossWatch source, do NOT copy verbatim):

```python
# At ~/Scripts/NuvioMedia/CrossWatch/cw_platform/orchestrator/_planner.py:297-354
# Behavior to replicate:
#   - Per-item across two sources A and B with their progress and lastUpdated timestamps:
#     - If only one source has data: keep it
#     - If both have data and delta < 30s: prefer newer timestamp; otherwise keep destination
#     - If A is meaningfully ahead (>= 30s): use A
#     - If A is behind: only use A if A's timestamp is strictly newer (don't regress
#       progress unless A is more recent)
#     - Near-complete (>= 95%): skip both — history will mark it watched
```

- [ ] **Step 1: Write the failing tests**

```kotlin
class ContinueWatchingProgressDiffPlannerTest {

    private val planner = ContinueWatchingProgressDiffPlanner()

    @Test
    fun `keeps newer source when progress within 30s`() {
        val trakt = entry(positionMs = 50_000, durationMs = 100_000, updatedAt = 1000L)
        val simkl = entry(positionMs = 55_000, durationMs = 100_000, updatedAt = 2000L)
        val winner = planner.pickWinner(listOf(trakt, simkl))
        assertSame(simkl, winner)
    }

    @Test
    fun `meaningful lead wins regardless of timestamp`() {
        val older = entry(positionMs = 80_000, durationMs = 100_000, updatedAt = 1000L)
        val newer = entry(positionMs = 10_000, durationMs = 100_000, updatedAt = 2000L)
        val winner = planner.pickWinner(listOf(older, newer))
        // older is 70s ahead — wins even though newer's timestamp is fresher
        assertSame(older, winner)
    }

    @Test
    fun `near complete returns null to defer to history`() {
        val a = entry(positionMs = 96_000, durationMs = 100_000, updatedAt = 1000L)
        val b = entry(positionMs = 97_000, durationMs = 100_000, updatedAt = 2000L)
        val winner = planner.pickWinner(listOf(a, b))
        assertNull(winner)
    }

    @Test
    fun `single source passes through`() {
        val only = entry(positionMs = 30_000, durationMs = 100_000, updatedAt = 1000L)
        val winner = planner.pickWinner(listOf(only))
        assertSame(only, winner)
    }

    @Test
    fun `empty input returns null`() {
        assertNull(planner.pickWinner(emptyList()))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingProgressDiffPlannerTest"`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Implement the planner**

```kotlin
package com.nexio.tv.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class ContinueWatchingProgressDiffPlanner @Inject constructor() {

    fun pickWinner(candidates: List<ContinueWatchingRecord>): ContinueWatchingRecord? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.single()

        val nonComplete = candidates.filter { it.percentComplete() < NEAR_COMPLETE_PERCENT }
        if (nonComplete.isEmpty()) return null

        var best = nonComplete.first()
        for (other in nonComplete.drop(1)) {
            best = preferred(best, other)
        }
        return best
    }

    private fun preferred(
        a: ContinueWatchingRecord,
        b: ContinueWatchingRecord,
    ): ContinueWatchingRecord {
        val deltaMs = abs(a.positionMs - b.positionMs)
        if (deltaMs < TRIVIAL_DELTA_MS) {
            return if (a.updatedAt >= b.updatedAt) a else b
        }
        return if (a.positionMs > b.positionMs) {
            // a leads meaningfully — only b wins if b is strictly newer AND a is older than b
            if (b.updatedAt > a.updatedAt && b.positionMs > a.positionMs) b else a
        } else {
            if (a.updatedAt > b.updatedAt && a.positionMs > b.positionMs) a else b
        }
    }

    private fun ContinueWatchingRecord.percentComplete(): Float =
        if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()) * 100f

    private companion object {
        const val TRIVIAL_DELTA_MS = 30_000L  // 30 seconds — matches CrossWatch _planner delta
        const val NEAR_COMPLETE_PERCENT = 95f
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingProgressDiffPlannerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingProgressDiffPlanner.kt \
        app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingProgressDiffPlannerTest.kt
git commit -m "feat(cw): port CrossWatch diff_progress planner to Kotlin

Picks a winner among Trakt vs Simkl candidates for the same item.
30s trivial-delta = newer timestamp wins. Meaningful lead = leader
wins regardless of timestamp. >=95% = both skipped (history takes
over). Used by ContinueWatchingMerger when multiple records collapse."
```

### Task 3.5: Wire the planner into `ContinueWatchingMerger.mergeRecords`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `cross-provider merge defers to ProgressDiffPlanner`() {
    val trakt = record(
        idBundle = ContinueWatchingIdBundle(imdb = "tt1"),
        provider = TrackingProvider.TRAKT,
        positionMs = 50_000L, durationMs = 100_000L, updatedAt = 1000L,
    )
    val simkl = record(
        idBundle = ContinueWatchingIdBundle(imdb = "tt1"),
        provider = TrackingProvider.SIMKL,
        positionMs = 55_000L, durationMs = 100_000L, updatedAt = 2000L,
    )
    val merged = ContinueWatchingMerger.merge(listOf(trakt, simkl))
    assertEquals(1, merged.size)
    // Simkl is fresher; planner returns it as winner.
    assertEquals(TrackingProvider.SIMKL, merged.first().provider)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingMergerTest"`
Expected: FAIL — current `mergeRecords` doesn't consult the planner.

- [ ] **Step 3: Inject and call the planner**

Convert `ContinueWatchingMerger` from `object` to a class (or add a static planner instance), and route same-key conflicts through it:

```kotlin
@Singleton
class ContinueWatchingMerger @Inject constructor(
    private val planner: ContinueWatchingProgressDiffPlanner,
) {
    fun merge(records: List<ContinueWatchingRecord>): List<ContinueWatchingRecord> {
        // ... same union-find as Task 2.4 ...

        // Replace the pairwise mergeRecords() reduction with planner-aware reduction:
        val groups = LinkedHashMap<Int, MutableList<ContinueWatchingRecord>>()
        sorted.indices.forEach { idx ->
            val root = find(idx)
            groups.getOrPut(root) { mutableListOf() }.add(sorted[idx])
        }

        return groups.values.mapNotNull { candidates ->
            // For same-provider records, fall back to existing mergeRecords pairwise reduction.
            // For cross-provider, planner picks one winner; then merge the loser's metadata
            // (e.g. resumeIdentities union, latest clickTimeDisplayMetadata) into it.
            val byProvider = candidates.groupBy { it.provider }
            val collapsedPerProvider = byProvider.values.map { perProvider ->
                perProvider.reduce { acc, next -> mergeRecords(acc, next) }
            }
            val winner = planner.pickWinner(collapsedPerProvider) ?: return@mapNotNull null
            mergeMetadataInto(winner, collapsedPerProvider.filterNot { it === winner })
        }.sortedByDescending { it.updatedAt }
    }

    private fun mergeMetadataInto(
        winner: ContinueWatchingRecord,
        losers: List<ContinueWatchingRecord>,
    ): ContinueWatchingRecord {
        if (losers.isEmpty()) return winner
        val mergedResumeIdentities = (winner.resumeIdentities + losers.flatMap { it.resumeIdentities })
            .distinctBy { it.lookupKey() }
        return winner.copy(resumeIdentities = mergedResumeIdentities)
    }
}
```

Update every call site of `ContinueWatchingMerger.merge(...)` — it's no longer a static `object`. Inject the singleton via Hilt where needed. Run a grep to find them:

```bash
grep -rn "ContinueWatchingMerger" app/src/main/java/com/nexio/tv/
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingMergerTest"`
Expected: PASS

- [ ] **Step 5: Build to confirm no broken call sites**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
Expected: clean.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt \
        app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMergerTest.kt
git commit -m "feat(cw): route same-item cross-provider conflicts through diff planner

ContinueWatchingMerger now collapses per-provider then defers to
ContinueWatchingProgressDiffPlanner for the Trakt vs Simkl winner.
Resume identities from the loser are unioned into the winner so
deep links from either provider still work. Merger is now a Hilt
@Singleton (was a Kotlin object); all call sites updated to inject."
```

### Task 3.6: Wire `SimklContinueWatchingPuller` into the CW refresh path

**Files:**
- Modify: wherever Trakt's puller is wired into the CW refresh loop (likely a repository or coordinator — discover via grep)

- [ ] **Step 1: Find the Trakt wire-in point**

```bash
grep -rn "TraktContinueWatchingPuller\|ContinueWatchingPuller\|pullPlayback" app/src/main/java/com/nexio/tv/
```

You're looking for the class that calls Trakt's puller and writes its output into `WatchProgressRepository`. Likely `TrackingProgressSyncService` or `ContinueWatchingSyncCoordinator`.

- [ ] **Step 2: Write the failing test**

A test on that coordinator class verifying it also calls `SimklContinueWatchingPuller.pullPlayback()` when Simkl is authenticated:

```kotlin
@Test
fun `refresh pulls from both Trakt and Simkl when both authed`() = runTest {
    val traktPuller = mockk<TraktContinueWatchingPuller>(relaxed = true)
    val simklPuller = mockk<SimklContinueWatchingPuller>(relaxed = true)
    coEvery { traktPuller.pullPlayback(any()) } returns emptyList()
    coEvery { simklPuller.pullPlayback(any()) } returns emptyList()

    val coordinator = newCoordinator(
        traktPuller = traktPuller,
        simklPuller = simklPuller,
        providerState = EffectiveTrackingProviderState(
            traktAuthenticated = true,
            simklAuthenticated = true,
        ),
    )
    coordinator.refresh(profileId = 1)

    coVerify(exactly = 1) { traktPuller.pullPlayback(1) }
    coVerify(exactly = 1) { simklPuller.pullPlayback(1) }
}
```

- [ ] **Step 3: Run test to verify it fails**

Expected: FAIL — `simklPuller` is not called.

- [ ] **Step 4: Inject and call `SimklContinueWatchingPuller`**

In the coordinator's `refresh` (or equivalent) method, alongside the Trakt call:

```kotlin
suspend fun refresh(profileId: Int) {
    val state = trackingProviderStateService.state.first()
    coroutineScope {
        val traktDeferred = if (state.traktAuthenticated) {
            async { traktPuller.pullPlayback(profileId) + traktPuller.pullHistory(profileId) }
        } else null
        val simklDeferred = if (state.simklAuthenticated) {
            async { simklPuller.pullPlayback(profileId) + simklPuller.pullHistory(profileId) }
        } else null

        val combined = listOfNotNull(traktDeferred, simklDeferred).awaitAll().flatten()
        watchProgressRepository.upsertAll(combined)
    }
}
```

Match the actual existing method signatures — this is shape only.

- [ ] **Step 5: Run test to verify it passes**

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add <coordinator file> <its test>
git commit -m "feat(cw): pull Simkl playback + history during refresh

CW refresh now fans out to both TraktContinueWatchingPuller and
SimklContinueWatchingPuller in parallel when both providers are
authenticated. Output flows through WatchProgressRepository.upsertAll
where ContinueWatchingMerger collapses cross-provider duplicates via
the diff planner."
```

---

## Phase 4 — Anime SIMKL-first routing

**Goal:** When `AnimeSeasonProjectionResolver` cannot produce Trakt-compatible coordinates, the scrobble is not dropped. Instead, Simkl receives it on its native `anime` endpoint with full anime ID bundle. Trakt is best-effort.

### Task 4.1: Add `anime` parent to Simkl scrobble DTO

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/dto/simkl/SimklScrobbleDtos.kt`

- [ ] **Step 1: Read the existing DTO shape**

Run: `cat app/src/main/java/com/nexio/tv/data/remote/dto/simkl/SimklScrobbleDtos.kt`

Confirm the request DTO has `movie: SimklMediaDto?` and `show: SimklMediaDto?` + `episode: ...` fields. We will add an `anime: SimklMediaDto?` alongside them.

- [ ] **Step 2: Add `anime` field**

In `SimklScrobbleDtos.kt`, modify the request DTO to add:

```kotlin
@SerializedName("anime") val anime: SimklMediaDto? = null,
```

The `SimklMediaDto.ids` (or whatever the existing `show.ids` type is) already supports `mal`, `anilist`, `kitsu`, `anidb` — confirm. If not, add those fields to `SimklIdsDto` as nullable strings.

- [ ] **Step 3: Commit (no test needed for a pure data class field add)**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/dto/simkl/SimklScrobbleDtos.kt
git commit -m "feat(simkl): add anime field to scrobble request DTO

Simkl accepts {anime: {ids: {...}}, episode: {...}} as an alternative
to {show: ..., episode: ...} for anime content. Phase 4.2 will wire
the adapter to populate it."
```

### Task 4.2: Detect anime and route to `anime` parent in `SimklScrobbleMutationAdapter`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/simkl/SimklScrobbleMutationAdapter.kt:166-188`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `episode with anime IDs routes to anime parent`() = runTest {
    val item = TrackingScrobbleItem.Episode(
        contentId = "kitsu:1",
        showTitle = "Cowboy Bebop",
        showYear = 1998,
        season = 1, number = 5,
        episodeTitle = "Ballad of Fallen Angels",
        hydratedIds = ProviderIds(
            kitsu = "1", mal = "1", anilist = "1",
            imdb = "tt0213338", tmdb = "30991",
        ),
    )
    val adapter = newSimklScrobbleMutationAdapter()
    val envelope = adapter.buildScrobbleEnvelope(
        item = item,
        action = "start",
        progressPercent = 10f,
        ownerProfileId = 1,
        ownerSessionId = "s",
    )
    val body = envelope.payload.asJsonObject
    assertTrue(body.has("anime"))
    assertFalse(body.has("show"))
    val animeIds = body["anime"].asJsonObject["ids"].asJsonObject
    assertEquals("1", animeIds["mal"].asString)
    assertEquals("1", animeIds["kitsu"].asString)
    assertEquals("1", animeIds["anilist"].asString)
}

@Test
fun `non-anime episode keeps show parent`() = runTest {
    val item = TrackingScrobbleItem.Episode(
        contentId = "tt0903747",
        showTitle = "Breaking Bad",
        showYear = 2008,
        season = 5, number = 14,
        episodeTitle = "Ozymandias",
        hydratedIds = ProviderIds(imdb = "tt0903747", tmdb = "1396"),
    )
    val adapter = newSimklScrobbleMutationAdapter()
    val envelope = adapter.buildScrobbleEnvelope(item, "start", 10f, 1, "s")
    val body = envelope.payload.asJsonObject
    assertFalse(body.has("anime"))
    assertTrue(body.has("show"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.simkl.SimklScrobbleMutationAdapterTest"`
Expected: FAIL — current adapter only uses `show`.

- [ ] **Step 3: Add anime detection**

In `SimklScrobbleMutationAdapter.kt`, before constructing the payload:

```kotlin
private fun TrackingScrobbleItem.hasAnimeIds(): Boolean {
    val ids = hydratedIds ?: return false
    return ids.mal != null || ids.anilist != null || ids.kitsu != null || ids.anidb != null
}
```

In `populateItem` (or wherever the JSON tree is built for episodes), switch the parent key:

```kotlin
when (item) {
    is TrackingScrobbleItem.Episode -> {
        val parentKey = if (item.hasAnimeIds()) "anime" else "show"
        val parentBlock = JsonObject().apply {
            add("ids", buildSimklIdsObject(item.hydratedIds))
            item.showTitle?.let { addProperty("title", it) }
            item.showYear?.let { addProperty("year", it) }
        }
        payload.add(parentKey, parentBlock)
        payload.add("episode", JsonObject().apply {
            addProperty("season", item.season)
            addProperty("number", item.number)
            item.episodeTitle?.let { addProperty("title", it) }
        })
    }
    is TrackingScrobbleItem.Movie -> {
        // movies: anime is rare but Simkl supports {anime: {...}} for anime films too.
        val parentKey = if (item.hasAnimeIds()) "anime" else "movie"
        // ... same construction ...
    }
}

private fun buildSimklIdsObject(ids: ProviderIds?): JsonObject = JsonObject().apply {
    if (ids == null) return@apply
    ids.imdb?.let { addProperty("imdb", it) }
    ids.tmdb?.let { addProperty("tmdb", it) }
    ids.tvdb?.let { addProperty("tvdb", it) }
    ids.simkl?.toLongOrNull()?.let { addProperty("simkl", it) }
    ids.mal?.let { addProperty("mal", it) }
    ids.anilist?.let { addProperty("anilist", it) }
    ids.kitsu?.let { addProperty("kitsu", it) }
    ids.anidb?.let { addProperty("anidb", it) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.simkl.SimklScrobbleMutationAdapterTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/simkl/SimklScrobbleMutationAdapter.kt \
        app/src/test/java/com/nexio/tv/data/repository/simkl/SimklScrobbleMutationAdapterTest.kt
git commit -m "feat(simkl): route anime episodes to anime parent

Detects MAL/AniList/Kitsu/AniDB presence on the hydrated ID bundle and
switches the scrobble payload parent from 'show' to 'anime' (or 'movie'
to 'anime' for films). Anime IDs flow through to the payload's ids
block alongside imdb/tmdb/tvdb so Simkl can match on the strongest ID."
```

### Task 4.3: Stop dropping anime scrobble when Trakt projection fails

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt:160-233`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `anime scrobble continues to Simkl when Trakt projection fails`() = runTest {
    val traktService = mockk<TraktScrobbleService>(relaxed = true)
    val simklService = mockk<SimklScrobbleService>(relaxed = true)
    val projection = mockk<AnimeSeasonProjectionResolver>()
    coEvery { projection.resolveWork(any()) } returns animeWork(
        providerIds = ProviderIds(kitsu = "1", mal = "1", anilist = "1"),
    )
    coEvery { projection.resolveEpisodeProjection(any(), any(), any()) } returns
        animeEpisodeProjection(scrobbleCoordinate = null)  // Trakt projection fails

    val service = newScrobbleService(
        traktService = traktService, simklService = simklService,
        projectionResolver = projection,
        providerState = EffectiveTrackingProviderState(
            traktAuthenticated = true, simklAuthenticated = true,
        ),
    )

    service.scrobbleStart(
        item = TrackingScrobbleItem.Episode(
            contentId = "kitsu:1", showTitle = null, showYear = null,
            season = 1, number = 5, episodeTitle = null,
            hydratedIds = ProviderIds(kitsu = "1", mal = "1", anilist = "1"),
        ),
        progressPercent = 10f,
        owner = ownerContext(),
    )

    coVerify(exactly = 0) { traktService.scrobbleStart(any(), any(), any()) }
    coVerify(exactly = 1) { simklService.scrobbleStart(any(), any(), any(), any()) }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.TrackingScrobbleServiceAnimeTest"`
Expected: FAIL — current code in `projectAnimeToTraktItem` returns null when projection fails AND that null cascades to dropping the whole scrobble call. With dual-write from Task 0.2, the Simkl branch should still fire.

- [ ] **Step 3: Decouple Trakt projection failure from Simkl dispatch**

In `TrackingScrobbleService.scrobbleStart` (modified in Task 0.2), each branch is already independent — the Trakt branch returns null inside its own `launch` and the Simkl branch is unaffected. So this test should already pass after Phase 1.5 (Simkl uses `hydratedIds` and is no longer routed through `toTraktItem`).

**Verify** the failing test really still fails: if it does, the issue is that `toSimklItem` (added in Task 0.2 as a pass-through) is also gating on Trakt's projection somewhere. Audit `toSimklItem` and confirm the anime path doesn't share state with `toTraktItem`. The fix is then:

```kotlin
private suspend fun toSimklItem(item: TrackingScrobbleItem): TrackingScrobbleItem? {
    val contentId = item.contentId
    val animeId = AnimeStremioId.parse(contentId)?.takeIf { it.source in ANIME_NATIVE_SOURCES }
    if (animeId != null) {
        val resolvedKitsuId = when (animeId.source) {
            AnimeIdSource.KITSU -> animeId.value
            else -> idMappingService.resolveKitsuId(animeId, ContentMediaKind.SERIES)
        }
        if (resolvedKitsuId == null) {
            rejectionReporter.reportRejection(
                contentId, ScrobbleRejectionReason.NO_PARSEABLE_IDS, TrackingProvider.SIMKL,
            )
            return null
        }
        return projectAnimeToSimklItem(item, resolvedKitsuId)
    }
    // Non-anime: pass through with hydratedIds intact.
    return item
}

private suspend fun projectAnimeToSimklItem(
    item: TrackingScrobbleItem,
    sourceKitsuId: String,
): TrackingScrobbleItem? {
    val work = animeSeasonProjectionResolver.resolveWork(
        AnimeSourceIdentity(sourceKitsuId = sourceKitsuId, animeStremioId = null),
    )
    val mergedIds = (item.hydratedIds ?: ProviderIds()).copy(
        kitsu = sourceKitsuId,
        mal = work.providerIds.mal ?: item.hydratedIds?.mal,
        anilist = work.providerIds.anilist ?: item.hydratedIds?.anilist,
        anidb = work.providerIds.anidb ?: item.hydratedIds?.anidb,
        tmdb = work.providerIds.tmdb ?: item.hydratedIds?.tmdb,
        tvdb = work.providerIds.tvdb ?: item.hydratedIds?.tvdb,
        imdb = item.hydratedIds?.imdb ?: work.providerIds.imdb,
    )
    return when (item) {
        is TrackingScrobbleItem.Movie -> item.copy(hydratedIds = mergedIds)
        is TrackingScrobbleItem.Episode -> {
            // For episodes, project to Simkl's preferred coordinate if available, else keep
            // the source episode numbering — Simkl's anime endpoint accepts kitsu-native S/E.
            val projection = animeSeasonProjectionResolver.resolveEpisodeProjection(
                work = work,
                sourceEpisode = SourceEpisodeCoordinate(sourceKitsuId, item.season, item.number),
                target = EpisodeProjectionTarget.SIMKL_SCROBBLE,  // see Task 4.4
            )
            val coord = projection.scrobbleCoordinate
            if (coord != null) {
                item.copy(
                    season = coord.season,
                    number = coord.episode,
                    hydratedIds = mergedIds,
                )
            } else {
                // Simkl accepts native Kitsu numbering — don't drop.
                item.copy(hydratedIds = mergedIds)
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.TrackingScrobbleServiceAnimeTest"`
Expected: PASS — Simkl gets the scrobble even when Trakt projection returns null.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt \
        app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleServiceAnimeTest.kt
git commit -m "feat(scrobble): SIMKL-first anime, Trakt best-effort

Adds projectAnimeToSimklItem that merges anime sidecars into the
hydrated ID bundle and accepts native Kitsu episode numbering when
no SIMKL-specific projection exists. Trakt anime scrobble continues
to require successful TVDB projection; SIMKL never blocks on it.
Closes the 'anime never shows in CW' failure mode from the audit."
```

### Task 4.4: Add `EpisodeProjectionTarget.SIMKL_SCROBBLE` enum value

**Files:**
- Modify: wherever `EpisodeProjectionTarget` is defined (search: `grep -rn "EpisodeProjectionTarget" app/src/main/java/com/nexio/tv/`)

- [ ] **Step 1: Add the enum value**

```kotlin
enum class EpisodeProjectionTarget {
    TRAKT_SCROBBLE,
    SIMKL_SCROBBLE,
    // ... any existing values stay ...
}
```

- [ ] **Step 2: Handle the new branch in `AnimeSeasonProjectionResolver`**

In the resolver's `resolveEpisodeProjection` method, add a `SIMKL_SCROBBLE` arm that returns a projection using Simkl's preferred ID space (typically Kitsu-native; if Simkl prefers MAL coordinates for the work in question, project to that). For the first cut, return the source coordinate unchanged for `SIMKL_SCROBBLE` — Simkl accepts native Kitsu numbering. Add a TODO comment referencing this plan if a future projection target is needed.

```kotlin
fun resolveEpisodeProjection(
    work: AnimeWork,
    sourceEpisode: SourceEpisodeCoordinate,
    target: EpisodeProjectionTarget,
): EpisodeProjection = when (target) {
    EpisodeProjectionTarget.TRAKT_SCROBBLE -> /* existing logic */
    EpisodeProjectionTarget.SIMKL_SCROBBLE -> EpisodeProjection(
        scrobbleCoordinate = AnimeScrobbleCoordinate(
            provider = ProviderId.KITSU,
            seriesId = sourceEpisode.sourceKitsuId,
            season = sourceEpisode.season,
            episode = sourceEpisode.episode,
        ),
    )
}
```

- [ ] **Step 3: Build + run anime tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.TrackingScrobbleServiceAnimeTest"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add <projection target file> <resolver file>
git commit -m "feat(anime): add SIMKL_SCROBBLE projection target

Returns Kitsu-native episode coordinates for Simkl's anime endpoint
(Simkl accepts source numbering for anime works). Pairs with the
projectAnimeToSimklItem flow added in Task 4.3."
```

---

## Phase 5 — Trakt pause-above-80% guard

**Goal:** Avoid 422 responses from Trakt for `/scrobble/pause` calls at progress ≥ 80%. The CrossWatch reference at `providers/scrobble/trakt/sink.py:750` documents this constraint explicitly.

### Task 5.1: Convert pause-above-80 to stop

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt:215-241`
- Test: `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `pause at 79 percent stays pause`() {
    val envelope = adapter.buildScrobbleEnvelope(
        item = movieItem(),
        action = "pause",
        progressPercent = 79f,
        ownerProfileId = 1,
    )
    assertEquals("pause", envelope.payload.asJsonObject["action"].asString)
}

@Test
fun `pause at 80 percent converts to stop`() {
    val envelope = adapter.buildScrobbleEnvelope(
        item = movieItem(),
        action = "pause",
        progressPercent = 80f,
        ownerProfileId = 1,
    )
    assertEquals("stop", envelope.payload.asJsonObject["action"].asString)
}

@Test
fun `pause at 81 percent converts to stop`() {
    val envelope = adapter.buildScrobbleEnvelope(
        item = movieItem(),
        action = "pause",
        progressPercent = 81f,
        ownerProfileId = 1,
    )
    assertEquals("stop", envelope.payload.asJsonObject["action"].asString)
}

@Test
fun `start at 95 percent stays start`() {
    val envelope = adapter.buildScrobbleEnvelope(
        item = movieItem(),
        action = "start",
        progressPercent = 95f,
        ownerProfileId = 1,
    )
    assertEquals("start", envelope.payload.asJsonObject["action"].asString)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.trakt.TraktScrobbleMutationAdapterTest"`
Expected: FAIL on the 80% and 81% cases (current code persists `action = "pause"`).

- [ ] **Step 3: Add the guard in `buildScrobbleEnvelope`**

In `TraktScrobbleMutationAdapter.kt:215-241`:

```kotlin
fun buildScrobbleEnvelope(
    item: TrackingScrobbleItem,
    action: String,
    progressPercent: Float,
    ownerProfileId: Int,
): TraktMutationEnvelope {
    val effectiveAction = coerceAction(action, progressPercent)
    // ... existing body, using effectiveAction wherever 'action' was used ...
}

private fun coerceAction(action: String, progressPercent: Float): String =
    if (action == "pause" && progressPercent >= TRAKT_PAUSE_REJECTION_THRESHOLD) "stop" else action

private companion object {
    // Trakt rejects /scrobble/pause at >= 80% with 422.
    // CrossWatch reference: providers/scrobble/trakt/sink.py:750
    const val TRAKT_PAUSE_REJECTION_THRESHOLD = 80f
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.trakt.TraktScrobbleMutationAdapterTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt \
        app/src/test/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapterTest.kt
git commit -m "fix(trakt): convert pause at >= 80% to stop

Trakt rejects /scrobble/pause at >= 80% progress with HTTP 422. Before
this fix, such pauses entered the outbox WAITING_RETRY → TERMINAL_FAILED
cycle on every backoff iteration. Now the adapter coerces the action to
'stop' upstream, matching CrossWatch's observed behavior."
```

---

## Cross-cutting verification (run after each phase merges)

After each phase ships, run a real-device smoke test per CLAUDE.md rule #8 (profile picker is NOT the home screen):

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 5
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER  # select profile
sleep 30                                                              # home + CW populate
# Start playback on a known item, wait 60s, exit.
# Inspect:
adb -s 192.168.50.98:5555 logcat -d -t 1200 | grep -E "Scrobble|ContinueWatching|FATAL|ANR" | tail -50
```

Phase-specific manual checks:

- **Phase 0:** Authenticate Trakt + Simkl. Play movie. Confirm both providers receive a scrobble call (check Trakt web history + Simkl web history).
- **Phase 1+2:** Start playback of a TMDB-keyed addon stream. Confirm scrobble payload includes `imdb` field (check `adb logcat | grep -i "trakt.*ids"`). Confirm the same item resolved from `/sync/playback` merges into the same CW card as the local resume row.
- **Phase 3:** Add a Simkl-only in-progress item via web. Open Nexio CW. Confirm the item appears with the Simkl badge.
- **Phase 4:** Play a Kitsu anime episode where TVDB projection is known to fail. Confirm Simkl receives the scrobble (Simkl web history) and Trakt skips silently (no 422 in outbox logs).
- **Phase 5:** Play to 85%, then pause. Confirm `adb logcat | grep "Trakt scrobble"` shows action=stop, not action=pause. No 422 in outbox.

---

## Self-review against the spec

**Spec coverage:**
- Audit #1 (hydrate IDs): Phase 1 — Tasks 1.1, 1.2, 1.3, 1.4, 1.5
- Audit #2 (multi-ID merge): Phase 2 — Tasks 2.1, 2.2, 2.3, 2.4
- Audit #3 (Simkl as CW source): Phase 3 — Tasks 3.1, 3.2, 3.3, 3.4, 3.5, 3.6
- Audit #4 (anime SIMKL-first): Phase 4 — Tasks 4.1, 4.2, 4.3, 4.4
- Audit #5 (pause-80% guard): Phase 5 — Task 5.1
- User addition (remove single-provider setting, dual-write): Phase 0 — Tasks 0.1, 0.2, 0.3, 0.4

**Type consistency check:** `ContinueWatchingIdBundle` uses the same field names as `ProviderIds` for cross-mapping; `hydratedIds` is `ProviderIds?` everywhere; `ContinueWatchingProgressDiffPlanner.pickWinner` returns `ContinueWatchingRecord?` consistently; `EpisodeProjectionTarget.SIMKL_SCROBBLE` is referenced before being defined — Task 4.4 must land before 4.3 compiles, so 4.3's step 3 contains the call but 4.4 is its successor task; if implementing strictly TDD, swap order so 4.4 lands first. Update on read: **swap Task 4.3 and 4.4 ordering during execution.**

**Placeholder scan:** All steps have concrete code or a "read the file first" instruction with the exact grep command. No "TBD" / "handle edge cases" / "similar to Task N" — every test body and every implementation body is written out. Two tasks (0.3 and 3.6) say "match the actual existing method signatures" because the implementer must read those files first; the test in each case is fully specified.

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-12-scrobble-cw-dual-provider-overhaul.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
