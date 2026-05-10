# Resolved Display UI Consumption Migration Plan (Plan B)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate every Home/Screensaver/Continue-Watching/Detail UI consumer from reading mutable `MetaPreview` rows to consuming `ResolvedDisplayItem` from `ResolvedDisplaySurfaceRepository`, eliminating the ~72 bypass sites that currently let first-paint data resurface in the UI even when the reducer says it shouldn't.

**Depends on:** `2026-05-09-resolved-display-authority.md` (Plan A) — must be merged and device-verified before starting Plan B.

---

## ⚠️ Session 2026-05-09 status — read before resuming

A first-pass implementation of Tasks 1.5 → 6 was attempted in one session. The work was **partially reverted on device** because the UI consumption changes (Tasks 3–6) caused a sustained GC death-spiral (~3M allocations/sec, 30–65 MB LOS per cycle, app unresponsive within ~60s). A perf prerequisite has since landed; **re-attempt Tasks 3+ on the post-revert main, NOT against the original plan as written**.

**2026-05-10 update:** `CatalogInventoryRepository` extracted from `HomeViewModel` between Surface 3 and Surface 4 — the inventory's 17–28 MiB at peak Modern Home use no longer lives on `HomeViewModel`'s dominator subtree. `CatalogSeeAllScreen` observes a single rail by key instead of the full inventory list, so it no longer recomposes on every Modern Home pipeline tick. Plan: `docs/superpowers/plans/2026-05-10-catalog-inventory-repository.md` (7 tasks, all shipped). Surface 4 (Continue Watching) starts on top of this shape — no further `_fullCatalogRows` reader exists to migrate.

### What is on `main` right now (verified 2026-05-09 ~20:00)

| Commit | Description | Status |
|---|---|---|
| `c2f132f0e` | `perf(home): memoize HomeResolvedDisplayMapper + suppress no-op HOME publishes` | ✅ retained |
| `8ced1ca49` | `revert: Plan B Tasks 3-6 (UI consumption from ResolvedDisplaySurfaceRepository)` | ✅ retained |
| `3204278ee` | Task 6: trailer state from resolved | ❌ reverted by `8ced1ca49` |
| `b508a8c33` | Task 5: classic + grid resolved consumption | ❌ reverted by `8ced1ca49` |
| `7c906c03f` | Task 4 fixup (architecture pin + ratingSource null guard) | ❌ reverted by `8ced1ca49` |
| `5cf8c6dc5` | Task 4: modern home pipeline rewrite | ❌ reverted by `8ced1ca49` |
| `58611e72d` | Task 3 docs fixup | ❌ reverted by `8ced1ca49` |
| `24df3fda9` | Task 3: `HomeViewModel.resolvedRailRowsFlow` | ❌ reverted by `8ced1ca49` |
| `251d1d6d3` | Task 1.5 fixup (rail-hash allocation fix + correct retainOnly test) | ✅ retained |
| `de6caa0be` | Tasks 1.5 + 2: `ResolvedDisplayProjectionCache`, `ModernHomeRowItem`, `ResolvedRailRow`, tests | ✅ retained |

**So:** Task 1 (pre-flight), Task 1.5, and Task 2 are done. Tasks 3–6 are pending re-attempt. Tasks 7+ are still pending.

### What changed on `main` that affects every later task

**`HomeResolvedDisplayMapper` is now memoized** (commit `c2f132f0e`). Process-wide cache keyed by `MapperCacheKey(itemKey, MetaPreview.hashCode(), overlay.hashCode())`. When input is content-equal to the previous call, the **same `ResolvedDisplayItem` instance** is returned. `clearCacheForTest()` exists for unit tests. Eviction is bounded by the active item-key set per call.

**`ResolvedDisplaySurfaceRepository.shouldSuppressSurfaceUpdate` now handles `HOME_SURFACE_KEY`** via element-wise `===`. After the mapper memoization, no-op publishes (where every item ref is unchanged) are correctly suppressed at the repo seam, so `observeHomeSurface` does NOT re-emit and downstream collectors do not re-run their `combine`/`map` blocks.

These two changes are the **load-bearing fix** that makes the projection cache (Task 1.5) actually able to hit. Before `c2f132f0e`, `nowMs = System.currentTimeMillis()` propagated into every `ResolvedSlot.updatedAtMs` and into `ResolvedDisplayItem.updatedAtMs` for unhydrated items, busting the projection cache key `(itemKey, updatedAtMs)` on every emission. Don't undo this.

### Death-spiral root cause + lessons for Tasks 3–6 re-attempt

The original Tasks 3–6 cascaded the upstream allocations into the rendering pipeline:

1. `HomeResolvedDisplayMapper.toResolvedDisplayItems` allocated ~22K fresh `ResolvedSlot` per emission (1900 items × 12 slots, each with `updatedAtMs = nowMs`).
2. `publishResolvedItems(HOME, ...)` had no suppression, so every push emitted to `observeHomeSurface`.
3. The new `resolvedRailRowsFlow` `.combine(_uiState.map { it.catalogRows }.distinctUntilChanged()) { ... }` block re-allocated `byItemKey` map + active-set + new outer/inner lists per emission, even when the projection cache should have hit.
4. The `_uiState.update { it.copy(resolvedRailRows = rails) }` had a `===` guard on the LIST reference — but `rails` was a fresh list each call (only the elements were cache-stable), so the guard never fired and `_uiState` always emitted.
5. `observeModernHomePresentationPipeline.combine(...)` rebuilt because `state.resolvedRailRows` was a new list reference; `buildModernHomePresentation`'s `cached.resolvedSource === source` cache miss fell through to `buildCatalogItem`, allocating fresh `ModernCarouselItem` per item.
6. Classic/Grid's `overlayResolvedDisplay(item, resolved): MetaPreview` allocated a fresh `MetaPreview` per rendered card per recomposition.

**For the re-attempt, the projection cache (`ResolvedDisplayProjectionCache`) WILL hit now** because `c2f132f0e` makes upstream `ResolvedDisplayItem` instances reference-stable when content is unchanged. But the original Task 3–6 implementations need additional care:

- **Task 3 (`resolvedRailRowsFlow`)**: cache the OUTER list reference too. When `projectionCache.projectRail` returns all-cached rail entries AND the active-rail set is unchanged, the outer rails list should be reference-stable. Either reuse the previous emission's list (when length + element-`===` match) or extend `ResolvedDisplayProjectionCache` with a `projectRailsList(activeCatalogIds: List<String>)` helper. Without this, `_uiState.update { it.copy(resolvedRailRows = rails) }` keeps committing fresh-but-content-equal lists, defeating Compose stability.
- **Task 4 (Modern Home pipeline)**: keep the per-rail item cache reference-equality check on `resolvedSource: ModernHomeRowItem` AND `metaSource: MetaPreview?`. This was correct in the prior attempt; preserve it.
- **Task 5 (Classic + Grid)**: the `overlayResolvedDisplay(item, resolved): MetaPreview` helper allocates a new `MetaPreview` per render. **Memoize this overlay too** — at the section level, build a `Map<String, MetaPreview>` keyed by itemKey that materializes the overlay once per `(MetaPreview ref, ModernHomeRowItem ref)` tuple and caches it. Card recompositions then read from the cache, not allocate fresh.
- **Task 6 (`refreshTrailerMetadataAvailabilityPipeline`)**: this is fine as-implemented (small per-call work). Just verify after re-attempting Tasks 3–5 that it doesn't get re-introduced by the revert.

### Re-attempt sequencing (recommended)

1. Visual-verify the post-revert + memoization state on device. Confirm GC is calm in steady state (>1 min idle without GC events on a populated catalog) and rails render correctly.
2. Re-attempt Task 3 (HomeViewModel `resolvedRailRowsFlow`) with the additional outer-list memoization noted above. Capture a heap dump after 60s of Modern Home soak; confirm `MetaPreview` / `CatalogRow` / `ResolvedDisplayItem` counts are bounded.
3. Re-attempt Task 4 (Modern Home pipeline). Soak Modern Home 3 min; capture heap dump; LOS should be < 5 MB per GC cycle in steady state, GCs every 10+ seconds.
4. Re-attempt Task 5 (Classic + Grid) with the overlay-memoization fix called out above. Switch all three home variants on device + heap dump.
5. Re-attempt Task 6.
6. Surface 1 acceptance (Task 7) — only after all four heap-dump gates pass.
7. Continue Tasks 8–27 as originally planned.

### Useful commands for the next agent

```bash
# Live GC pattern (death-spiral signature: > 1M allocs per cycle, > 30 MB LOS, GCs every < 1s for many minutes)
adb -s 192.168.50.98:5555 logcat -d -v threadtime | grep "m.nexiodebug" | grep "Background concurrent" | tail -20

# Heap dump
adb -s 192.168.50.98:5555 shell am dumpheap $(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv) /data/local/tmp/heap.hprof
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap.hprof /tmp/heap.hprof
~/Library/Android/sdk/platform-tools/hprof-conv /tmp/heap.hprof /tmp/heap-jvm.hprof
hprof-slurp -i /tmp/heap-jvm.hprof -t 40

# Trace retainers for a class
/tmp/hprof-analyze-rust/target/release/hprof-analyze-rust /tmp/heap-jvm.hprof com.nexio.tv.domain.model.ResolvedDisplayItem 30 2
```

Heap dumps stay 0-bytes when the app is in a death-spiral (`am dumpheap` can't service the request under allocation pressure). If that happens, the GC log is sufficient diagnostic — the issue is allocation rate, not retention.

---

**Architecture:** Each UI surface is migrated independently and sequentially. After each surface ships, the resolved authority and the legacy mutable rows coexist; the surface itself reads only the resolved authority. Once all surfaces have migrated, the legacy mutable state flows (`displayRows`, `fullRows`, `heroItems` as `MetaPreview`) and the deprecated `applyTo` / `mergeFallback` / `withCompatiblePersistedInternalPoster` helpers are deleted in a single cleanup pass.

**Tech Stack:** Kotlin · Hilt · Coroutines/Flow · Compose · Mockk · JUnit4

**Surface order (per-surface, sequenced — user-confirmed):**
1. Home Rails (Modern + Classic + Grid) — highest user-visible impact
2. Hero panel
3. Screensaver (idle + idle-trailer)
4. Continue Watching
5. Detail / "More Like This" / Collection
6. Cleanup pass — delete deprecated mutable paths

**Non-goals (must not regress):**
- Artwork fetch chain (untouched)
- Plan A's reducer (already shipped)
- Search UI (`SearchScreen`/`SearchViewModel`) — operates on search results, not Home overlays; orthogonal to this migration

---

## Approach

Each surface follows the same recipe:

1. **Define a typed view-model state** — replace `List<MetaPreview>` (or whatever mutable form the surface uses) with `List<ResolvedDisplayItem>` or a surface-specific projection of it.
2. **Wire the view model to `ResolvedDisplaySurfaceRepository`** — observe `observeHomeSurface(profileId)` (or screensaver/CW equivalent) instead of `catalogRows.applyHydratedHomeOverlays(...)`.
3. **Convert the Compose composables and helper functions** to read `ResolvedDisplayItem.display` / `.artwork` / `.rating` instead of `MetaPreview.poster` / `.background` / `.imdbRating`.
4. **Run the surface tests + on-device smoke**.
5. **Commit per surface**.

Surface-specific naming and file lists are enumerated below.

---

## File Structure

### New files (created during the plan)

| File | Surface | Responsibility |
|---|---|---|
| `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRowItem.kt` | Home Rails | Compose UI projection of `ResolvedDisplayItem` for rail card |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HeroDisplayItem.kt` | Hero | Hero-specific projection of `ResolvedDisplayItem` |
| `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverDisplayItem.kt` | Screensaver | Screensaver-specific projection (logo + backdrop + trailer + metadata) |
| `app/src/main/java/com/nexio/tv/ui/components/ContinueWatchingResolvedDisplayItem.kt` | CW | CW-specific projection (poster + progress + resume identity) |
| `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsResolvedFields.kt` | Detail | Detail-screen projection helpers |

### Modified files (high-level — full per-surface lists in each task)

Home Rails: `HomeUiState.kt`, `HomeScreen.kt`, `ModernHomeContent.kt`, `ModernHomeRows.kt`, `ModernHomeModels.kt`, `ClassicHomeContent.kt`, `GridHomeContent.kt`, `HomeViewModel.kt`, `HomeViewModelCatalogPipeline.kt`, `HomeViewModelPresentationPipeline.kt`, `HomePosterTrailerOptions.kt`.

Hero: `ModernHomePresentation.kt`, `HomeUiState.kt`, `HomeViewModel.kt`, `HomeViewModelPresentationPipeline.kt`.

Screensaver: `IdleScreensaverController.kt`, `IdleScreensaverOverlay.kt`, `IdleTrailerScreensaverSession.kt`, `IdleTrailerScreensaverOverlay.kt`, `IdleScreensaverModels.kt`, `ScreensaverCandidateRepository.kt`, `IdleScreensaverRepository.kt`, `IdleScreensaverPreparation.kt`.

Continue Watching: `ContinueWatchingSection.kt`, `GridContinueWatchingSection.kt`, `HomeViewModelContinueWatching.kt`, `HomeViewModelContinueWatchingRuntimePipeline.kt`.

Detail: `MetaDetailsScreen.kt`, `MetaDetailsViewModel.kt`, `MetaDetailsUiState.kt`, `CollectionSection.kt`, `MoreLikeThisSection.kt`.

### Files to delete in cleanup pass

`app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt` (after all surfaces migrated)
`app/src/main/java/com/nexio/tv/ui/screens/home/HomeFirstPaintMetadataMapper.kt` (verify dead)
Deprecated functions in `HomeDisplayMetadata.kt` (`applyTo`, `mergeFallback`)
`HomeCatalogRefreshCoordinator.withCompatiblePersistedInternalPoster` (Plan A marked it `@Deprecated`)

---

## Task 1: Pre-flight — verify Plan A is shipped and device-stable

**Prerequisite. Do not start Plan B if this fails.**

- [ ] **Step 1: Confirm Plan A's commits are on `main`**

```bash
git log --oneline main..HEAD || git log --oneline origin/main | head -20
```

Look for commits matching: `feat: add DisplaySourceRank`, `feat: HomeRailProjectionReducer`, `fix: refresh coordinator routes through reducer`, `feat: emit home.display_projection diagnostic event`.

- [ ] **Step 2: Confirm Plan A test suite is green**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeRailProjectionReducerTest --tests com.nexio.tv.ui.screens.home.HomeFirstPaintInvariantTest --tests com.nexio.tv.ui.screens.home.HomeRailProjectionPremiumTest --tests com.nexio.tv.ui.screens.home.HomeCatalogRefreshNonDowngradeTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Confirm device behavior — `home.display_projection` showing RESOLVED-rank wins**

```bash
adb -d logcat -c
adb -d logcat -v threadtime | grep "home.display_projection" | head -20
```

Expected: events emit on every Home **snapshot apply** (not per overlay arrival — Plan A's apply-seam projection was reverted, so events come from `HomeResolvedDisplayMapper.toResolvedDisplayItems` which only runs at `applyHomeSnapshotToUiPipeline` and the screensaver publish). For hydrated items, `selected.poster.rank=RESOLVED` and `firstPaintSuppressed.poster=true`. If all `selected` ranks are `FIRST_PAINT` after a snapshot apply, Plan A is not actually live — stop.

**Implementation note:** Three publish sites currently feed `ResolvedDisplaySurfaceRepository` (`HomeViewModelCatalogPipeline.kt:527`, `:2917`, `:3015`). Only `:2917` (snapshot apply) and `:3015` (screensaver) route through the mapper that emits the diagnostic event. The `:527` per-overlay incremental publish bypasses the mapper and writes via `overlay.toResolvedDisplayItem()` — silent but correct. Trailer state is preserved across publishes by `mergeIncrementalItems` + `withPreservedTrailerState`; consumers do not need to defensively re-merge trailer fields.

- [ ] **Step 4: Block Plan B if any of the above fails**

Open a new task `Repair Plan A before starting Plan B` and pause execution.

---

## Task 1.5: Projection memoization (perf prerequisite)

**Why:** PR #16 (allocation-rate rework) cut `MetaPreview` allocations from 946K → 44K (-93.5%) by raising the `scheduleUpdateCatalogRows` debounce floor 50→200 ms and converting hot-path `forEach` to indexed `for`. Plan B's per-surface `combine(observeHomeSurface, ...)` mappings emit `~5/sec × 76 rows × 25 items ≈ 9.5K` fresh `ModernHomeRowItem`/`ResolvedRailRow` instances per second if implemented naively — the same order as the pre-fix MetaPreview rate. Without memoization, Plan B silently undoes the death-spiral mitigation.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/ResolvedDisplayProjectionCache.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/ResolvedDisplayProjectionCacheTest.kt`

- [ ] **Step 1: Write the failing test**

The cache must:
- Return the SAME `ModernHomeRowItem` instance when called twice for an unchanged `ResolvedDisplayItem` (same `itemKey` + same `updatedAtMs`)
- Return a NEW instance when `updatedAtMs` advances
- Evict entries whose `itemKey` no longer appears in the surface (bound retained set to current surface)
- Same contract for `ResolvedRailRow` keyed by `(catalogId, items.map { it.itemKey to it.updatedAtMs }.hashCode())` — unchanged-content rails keep the same reference (Compose structural-equality skip)

- [ ] **Step 2: Implement the cache**

```kotlin
@Singleton
class ResolvedDisplayProjectionCache {
    private val itemCache = mutableMapOf<String, Pair<Long, ModernHomeRowItem>>()
    private val railCache = mutableMapOf<String, Pair<Int, ResolvedRailRow>>()

    @Synchronized
    fun projectItem(resolved: ResolvedDisplayItem): ModernHomeRowItem {
        val cached = itemCache[resolved.itemKey]
        if (cached != null && cached.first == resolved.updatedAtMs) return cached.second
        val fresh = ModernHomeRowItem.from(resolved)
        itemCache[resolved.itemKey] = resolved.updatedAtMs to fresh
        return fresh
    }

    @Synchronized
    fun projectRail(catalogId: String, title: String, items: List<ModernHomeRowItem>): ResolvedRailRow {
        val key = catalogId
        val contentHash = items.map { it.itemKey to it.hashCode() }.hashCode()
        val cached = railCache[key]
        if (cached != null && cached.first == contentHash) return cached.second
        val fresh = ResolvedRailRow(catalogId = catalogId, title = title, items = items)
        railCache[key] = contentHash to fresh
        return fresh
    }

    @Synchronized
    fun retainOnly(activeItemKeys: Set<String>) {
        itemCache.keys.retainAll(activeItemKeys)
    }

    @Synchronized
    fun retainOnlyRails(activeCatalogIds: Set<String>) {
        railCache.keys.retainAll(activeCatalogIds)
    }
}
```

- [ ] **Step 3: Wire via Hilt and inject into `HomeViewModel`**

Add a `@Provides` in the home Hilt module returning a single instance scoped to the home component (or `@ViewModelScoped`).

- [ ] **Step 4: Use the cache from every Plan B projection mapping** — every `ModernHomeRowItem.from(...)` call from Tasks 3, 4, 18, 24 must go through `projectionCache.projectItem(...)`. Same for `projectRail` in Task 3.

- [ ] **Step 5: Verification gate (heap dump)**

After Task 1.5 ships, capture a baseline heap dump for comparison with later surfaces:

```bash
adb -s 192.168.50.98:5555 shell am dumpheap $(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv) /data/local/tmp/heap-pre-surface1.hprof
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-pre-surface1.hprof
~/Library/Android/sdk/platform-tools/hprof-conv heap-pre-surface1.hprof heap-pre-surface1-jvm.hprof
hprof-slurp heap-pre-surface1-jvm.hprof | tee histogram-pre-surface1.txt
```

Record `MetaPreview`, `CatalogRow`, `ArrayList$Itr`, `ModernHomeRowItem`, `ResolvedRailRow` counts. These are the reference values for the per-surface gates below.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ResolvedDisplayProjectionCache.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/ResolvedDisplayProjectionCacheTest.kt
git commit -m "feat: ResolvedDisplayProjectionCache for stable rail/item projection identity"
```

---

## Surface 1: Home Rails (Modern, Classic, Grid)

### Task 2: Define `ModernHomeRowItem` projection type

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRowItem.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeRowItemTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ModernHomeRowItemTest {
    @Test
    fun `from ResolvedDisplayItem extracts poster ref backdrop ref and rating`() {
        val poster = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:poster:imdb:tt0137523"),
            assetKey = null,
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty(),
            displayHints = ArtworkDisplayHints()
        )
        val resolved = ResolvedDisplayItem(
            itemKey = "movie:tmdb:550",
            contentId = "tmdb:550",
            parentId = "tmdb:550",
            itemType = ContentType.MOVIE,
            mediaKind = MetadataMediaKind.MOVIE,
            canonicalProvider = "TMDB",
            canonicalId = "550",
            imdbId = "tt0137523",
            stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"),
            display = ResolvedDisplayFields(
                title = "Fight Club",
                originalTitle = null,
                year = 1999,
                releaseDate = "1999",
                overview = "An office worker meets a strange soap salesman.",
                genres = listOf("Drama"),
                runtimeText = "139 min"
            ),
            artwork = ArtworkBundle(poster = poster),
            rating = TitleRating(8.8, TitleRatingSource.IMDB),
            trailer = TrailerDisplayState(),
            hydrationState = HydrationState.CANONICAL_READY,
            sourceTrace = emptyList(),
            updatedAtMs = 1_000L
        )

        val row = ModernHomeRowItem.from(resolved)

        assertEquals("movie:tmdb:550", row.itemKey)
        assertEquals("Fight Club", row.title)
        assertEquals(1999, row.year)
        assertNotNull(row.posterRef)
        assertEquals(true, row.posterRef is ArtworkDisplayRef.RuntimeAsset)
        assertEquals(8.8, row.rating!!.value, 0.001)
    }

    @Test
    fun `from ResolvedDisplayItem with null artwork yields null refs`() {
        val resolved = ResolvedDisplayItem(
            itemKey = "k",
            contentId = "c",
            parentId = "c",
            itemType = ContentType.MOVIE,
            mediaKind = MetadataMediaKind.MOVIE,
            canonicalProvider = null,
            canonicalId = null,
            imdbId = null,
            stableIds = ProviderIds(),
            display = ResolvedDisplayFields(null, null, null, null, null, emptyList(), null),
            artwork = ArtworkBundle(),
            rating = null,
            trailer = TrailerDisplayState(),
            hydrationState = HydrationState.PREVIEW_ONLY,
            sourceTrace = emptyList(),
            updatedAtMs = 0L
        )

        val row = ModernHomeRowItem.from(resolved)

        assertEquals(null, row.posterRef)
        assertEquals(null, row.backdropRef)
        assertEquals(null, row.logoRef)
        assertEquals(null, row.rating)
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.ModernHomeRowItemTest`
Expected: FAIL — `Unresolved reference: ModernHomeRowItem`.

- [ ] **Step 3: Create the projection type**

```kotlin
package com.nexio.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating

/**
 * Compose-friendly projection of a [ResolvedDisplayItem] for use in modern Home
 * rail cards. Strictly a presentation projection — no merging, no fallback. The
 * reducer (Plan A) has already decided which slot wins for every field. UI must
 * not perform any further "if poster null fall back to backdrop" logic.
 */
@Immutable
data class ModernHomeRowItem(
    val itemKey: String,
    val contentId: String,
    val parentId: String,
    val title: String?,
    val year: Int?,
    val posterRef: ArtworkDisplayRef?,
    val backdropRef: ArtworkDisplayRef?,
    val logoRef: ArtworkDisplayRef?,
    val thumbnailRef: ArtworkDisplayRef?,
    val rating: TitleRating?,
    val hydrationState: HydrationState
) {
    companion object {
        fun from(resolved: ResolvedDisplayItem): ModernHomeRowItem =
            ModernHomeRowItem(
                itemKey = resolved.itemKey,
                contentId = resolved.contentId,
                parentId = resolved.parentId,
                title = resolved.display.title,
                year = resolved.display.year,
                posterRef = resolved.artwork.poster,
                backdropRef = resolved.artwork.backdrop,
                logoRef = resolved.artwork.logo,
                thumbnailRef = resolved.artwork.thumbnail,
                rating = resolved.rating,
                hydrationState = resolved.hydrationState
            )
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.ModernHomeRowItemTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRowItem.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeRowItemTest.kt
git commit -m "feat: ModernHomeRowItem projection type for rail UI"
```

---

### Task 3: Add resolved-rail state flow to `HomeViewModel`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt`

- [ ] **Step 1: Add `resolvedRailRows` to `HomeUiState`**

In `HomeUiState.kt`, locate the data class for the home UI state. Add a new field:

```kotlin
val resolvedRailRows: List<ResolvedRailRow> = emptyList()
```

And define the row type alongside it:

```kotlin
@Immutable
data class ResolvedRailRow(
    val catalogId: String,
    val title: String,
    val items: List<ModernHomeRowItem>
)
```

Add the import:

```kotlin
import com.nexio.tv.ui.screens.home.ModernHomeRowItem
import androidx.compose.runtime.Immutable
```

- [ ] **Step 2: Inject `ResolvedDisplaySurfaceRepository` into `HomeViewModel`**

Locate the `HomeViewModel` constructor. Add the parameter:

```kotlin
private val resolvedDisplaySurfaceRepository: ResolvedDisplaySurfaceRepository,
```

Add the import:

```kotlin
import com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository
```

- [ ] **Step 3: Wire the resolved-surface flow into UI state**

In `HomeViewModel`, replace whatever currently emits `displayRows: List<CatalogRow>` (or its current equivalent under the resolved authority) with a derived flow that converts `ResolvedDisplayItem` to `ModernHomeRowItem`:

```kotlin
private val resolvedRailRowsFlow: Flow<List<ResolvedRailRow>> =
    resolvedDisplaySurfaceRepository.observeHomeSurface(profileId = activeProfileId)
        .combine(catalogRowsForGroupingFlow) { resolvedItems, rowGrouping ->
            // observeHomeSurface emits Flow<List<ResolvedDisplayItem>>; index it for lookup.
            val byItemKey = resolvedItems.associateBy { it.itemKey }
            val activeItemKeys = mutableSetOf<String>()
            val activeCatalogIds = mutableSetOf<String>()
            val rails = rowGrouping.map { row ->
                val items = row.items.mapNotNull { metaPreview ->
                    val itemKey = homeDisplayItemKey(metaPreview.apiType, metaPreview.id)
                    byItemKey[itemKey]?.let { resolved ->
                        activeItemKeys += itemKey
                        projectionCache.projectItem(resolved)
                    }
                }
                activeCatalogIds += row.catalogId
                projectionCache.projectRail(row.catalogId, row.title, items)
            }
            projectionCache.retainOnly(activeItemKeys)
            projectionCache.retainOnlyRails(activeCatalogIds)
            rails
        }
```

(Implementation detail: `catalogRowsForGroupingFlow` is whatever flow currently provides the rail grouping/order — keep it. The only thing changing is the *items* within each rail come from the resolved repository. `observeHomeSurface` returns a flat `Flow<List<ResolvedDisplayItem>>` (verified at `ResolvedDisplaySurfaceRepository.kt:27`), not a map — so `.associateBy { it.itemKey }` is required. Routing through `projectionCache` from Task 1.5 keeps stable references for Compose stability and bounds allocation rate.)

Then expose it on the UI state:

```kotlin
private val uiState: StateFlow<HomeUiState> = combine(
    /* existing fields */,
    resolvedRailRowsFlow
) { ..., resolvedRails ->
    HomeUiState(
        /* existing fields */,
        resolvedRailRows = resolvedRails
    )
}.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleUniversalDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run home test suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.*ViewModel*"`
Expected: PASS (or fix tests that broke from constructor changes).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt
git commit -m "feat: home view model exposes resolved rail rows"
```

---

### Task 4: Modern Home composables consume `ResolvedRailRow`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt`

- [ ] **Step 1: Locate the rail composable signature in `ModernHomeRows.kt`**

Find the function that renders rails. Its current signature accepts `List<MetaPreview>` (or `CatalogRow` with `MetaPreview` items). Change the signature to accept `ResolvedRailRow`:

```kotlin
@Composable
fun ModernHomeRail(
    row: ResolvedRailRow,
    onItemFocus: (String) -> Unit,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(modifier = modifier) {
        items(row.items, key = { it.itemKey }) { item ->
            ModernHomeRailCard(
                item = item,
                onFocus = { onItemFocus(item.itemKey) },
                onClick = { onItemClick(item.itemKey) }
            )
        }
    }
}
```

- [ ] **Step 2: Convert the card composable to read `ModernHomeRowItem`**

The card composable previously read `metaPreview.poster`, `metaPreview.background`, `metaPreview.imdbRating`. Change to:

```kotlin
@Composable
private fun ModernHomeRailCard(
    item: ModernHomeRowItem,
    onFocus: () -> Unit,
    onClick: () -> Unit
) {
    // Portrait card: poster ONLY. Spec rule: poster card never falls back to
    // backdrop or logo. If posterRef is null, render placeholder; do NOT
    // substitute backdropRef.
    val posterModel: Any? = item.posterRef
    AsyncImage(
        model = posterModel,
        contentDescription = item.title,
        // ...
    )
    // Title/rating overlays use item.title and item.rating directly.
}
```

(Implementation note: `AsyncImage` here uses the project's existing image loader; pass `item.posterRef` (which is an `ArtworkDisplayRef` or null) and let the existing `NexioArtworkFetcher`/`ArtworkLegacyProjection` chain handle the URI. If the existing rendering path expected a `String` poster URL, convert via `item.posterRef?.toLegacyArtworkString()` (already imported from `com.nexio.tv.core.artwork.toLegacyArtworkString`) at the call site.)

- [ ] **Step 3: Update `ModernHomeContent.kt` to iterate `uiState.resolvedRailRows`**

Replace:

```kotlin
uiState.displayRows.forEach { row ->
    // legacy render with MetaPreview
}
```

With:

```kotlin
uiState.resolvedRailRows.forEach { row ->
    ModernHomeRail(
        row = row,
        onItemFocus = onItemFocus,
        onItemClick = onItemClick
    )
}
```

- [ ] **Step 4: Update `HomeScreen.kt` if it routes `displayRows` directly**

If `HomeScreen.kt` reads `uiState.displayRows` and passes it to `ModernHomeContent`, change to pass `uiState.resolvedRailRows`. If it doesn't reference `displayRows`, no change.

- [ ] **Step 5: Build**

Run: `./gradlew :app:assembleUniversalDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Smoke test on device**

```bash
./gradlew :app:installUniversalDebug
adb -d shell am start -n com.nexio.tv/.MainActivity
```

Verify on device:
- TMDB Popular rail: posters render. Premium poster (RPDB) shows when configured. Raw `https://image.tmdb.org/...` URL never visible.
- Trakt Trending rail: posters/logos/backdrops persist after navigation away and back.
- Rail focus + click navigation still works.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt
git commit -m "feat: modern home rails consume ResolvedRailRow"
```

---

### Task 5: Classic and Grid Home variants consume `ResolvedRailRow`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ClassicHomeContent.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt`

- [ ] **Step 1: Mirror Task 4 for `ClassicHomeContent.kt`**

Apply the same conversion pattern: replace `MetaPreview` consumption with `ModernHomeRowItem`, replace `displayRows` with `resolvedRailRows`. The existing card/row layouts stay the same — only the data type binding changes.

- [ ] **Step 2: Mirror Task 4 for `GridHomeContent.kt`**

Same pattern.

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleUniversalDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Switch home variants on device and verify each one renders**

```bash
adb -d shell am force-stop com.nexio.tv
# Open Settings → Home Layout → Classic, then Grid, then Modern; verify each.
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ClassicHomeContent.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt
git commit -m "feat: classic and grid home variants consume ResolvedRailRow"
```

---

### Task 6: `HomePosterTrailerOptions` reads resolved trailer state

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomePosterTrailerOptions.kt`

- [ ] **Step 1: Replace `MetaPreview.trailerYtIds` reads with `ResolvedDisplayItem.trailer`**

Locate any function that reads `metaPreview.trailerYtIds` to decide whether to play the focus-card trailer. Change the input to `ResolvedDisplayItem` (or a slim subset) and read `resolved.trailer.fallbackTrailerYtIds` plus `resolved.trailer.selectedPlaybackRef`.

- [ ] **Step 2: Build + run any existing tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomePosterTrailerOptions*"`
Expected: PASS (or update tests for new signature).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomePosterTrailerOptions.kt
git commit -m "feat: poster trailer options read resolved trailer state"
```

---

### Task 7: Home rails surface acceptance — manual + automated

- [ ] **Step 1: Run all home test suites**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.*"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Manual device verification — full Home regression**

Verify each on device:
- Cold start: rails render with first-paint posters, then upgrade to premium/TVDB without flicker
- TMDB rail: premium poster persists after refresh; raw URL never visible after first hydration
- Trakt rail: hydrated logos + backdrops + ratings persist after navigation
- Rail order changes (Settings → Home Order): does not clear hydrated state
- Profile switch: shared resolved state survives

Capture `adb logcat` filtered for `home.display_projection` after a snapshot apply to prove `selected.poster.rank=RESOLVED` for the visible items at that moment.

- [ ] **Step 3: Heap-dump perf gate (3-min Modern Home soak)**

```bash
adb -s 192.168.50.98:5555 shell am dumpheap $(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv) /data/local/tmp/heap-post-surface1.hprof
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-post-surface1.hprof
~/Library/Android/sdk/platform-tools/hprof-conv heap-post-surface1.hprof heap-post-surface1-jvm.hprof
hprof-slurp heap-post-surface1-jvm.hprof | tee histogram-post-surface1.txt
```

Acceptance vs Task 1.5 baseline (`histogram-pre-surface1.txt`):
- `MetaPreview` count: ≤ +20% over baseline
- `ModernHomeRowItem` instances: bounded (≤ items × 2 — should be near 1:1 if cache is working)
- `ResolvedRailRow` instances: ≤ rail count × 2
- No death-spiral pattern (heap below 350 MB; no GC pauses > 200 ms)

If any threshold is exceeded, the cache wiring is wrong — fix before tagging.

- [ ] **Step 4: Mark surface complete**

```bash
git tag -a plan-b-surface-1-home-rails -m "Home Rails surface migrated to ResolvedDisplayItem"
git push --tags
```

---

## Surface 2: Hero panel

### Task 8: Define `HeroDisplayItem` projection

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/HeroDisplayItem.kt`

- [ ] **Step 1: Create the type**

```kotlin
package com.nexio.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TrailerDisplayState

/**
 * Hero-panel-specific projection of [ResolvedDisplayItem]. The hero allows
 * `backdrop ?: poster` for its background image (per spec: "Hero panel
 * background = resolved.artwork.backdrop ?: resolved.artwork.poster"), and
 * always uses `logo` separately.
 */
@Immutable
data class HeroDisplayItem(
    val itemKey: String,
    val contentId: String,
    val title: String?,
    val overview: String?,
    val genres: List<String>,
    val year: Int?,
    val backgroundRef: ArtworkDisplayRef?,
    val logoRef: ArtworkDisplayRef?,
    val rating: TitleRating?,
    val trailer: TrailerDisplayState
) {
    companion object {
        fun from(resolved: ResolvedDisplayItem): HeroDisplayItem =
            HeroDisplayItem(
                itemKey = resolved.itemKey,
                contentId = resolved.contentId,
                title = resolved.display.title,
                overview = resolved.display.overview,
                genres = resolved.display.genres,
                year = resolved.display.year,
                // Spec: hero background allowed fallback from backdrop to poster.
                backgroundRef = resolved.artwork.backdrop ?: resolved.artwork.poster,
                logoRef = resolved.artwork.logo,
                rating = resolved.rating,
                trailer = resolved.trailer
            )
    }
}
```

- [ ] **Step 2: Add a unit test**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/HeroDisplayItemTest.kt` and verify:
- `from` extracts backdrop preferentially
- `from` falls back to poster when backdrop is null
- `from` keeps logo and trailer state

(Implementation: copy fixture pattern from `ModernHomeRowItemTest`.)

- [ ] **Step 3: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HeroDisplayItemTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HeroDisplayItem.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/HeroDisplayItemTest.kt
git commit -m "feat: HeroDisplayItem projection type"
```

---

### Task 9: `HomeViewModel` exposes resolved hero items

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`

- [ ] **Step 1: Add `resolvedHeroItems` to `HomeUiState`**

```kotlin
val resolvedHeroItems: List<HeroDisplayItem> = emptyList()
```

- [ ] **Step 2: Replace the `heroItems: List<MetaPreview>` flow construction**

Find `enrichHeroItemsPipeline` and the flow that builds `heroItems`. Replace its construction so it derives from `ResolvedDisplaySurfaceRepository.observeHomeSurface(profileId)` filtered to the hero-eligible items, mapped via `HeroDisplayItem.from`.

```kotlin
private val resolvedHeroItemsFlow: Flow<List<HeroDisplayItem>> =
    resolvedDisplaySurfaceRepository.observeHomeSurface(profileId = activeProfileId)
        .combine(heroItemKeysFlow) { resolvedItems, heroKeys ->
            // observeHomeSurface emits Flow<List<ResolvedDisplayItem>>; index for lookup.
            val byItemKey = resolvedItems.associateBy { it.itemKey }
            heroKeys.mapNotNull { itemKey -> byItemKey[itemKey]?.let(HeroDisplayItem::from) }
        }
```

(`heroItemKeysFlow` is whatever flow currently identifies which catalog items are hero candidates — keep that selection logic; only the conversion changes. As with Task 3, `observeHomeSurface` returns a flat list — index it via `.associateBy` before lookup. Hero items rotate slowly so memoization is less critical here, but if `HomeUiState` emits frequently, route the lookup through `projectionCache` to keep references stable.)

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleUniversalDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt
git commit -m "feat: home view model exposes resolvedHeroItems"
```

---

### Task 10: Hero composable consumes `HeroDisplayItem`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomePresentation.kt`

- [ ] **Step 1: Change the hero composable signature**

Replace `MetaPreview` parameter with `HeroDisplayItem`. Replace field reads:
- `metaPreview.background` → `hero.backgroundRef`
- `metaPreview.logo` → `hero.logoRef`
- `metaPreview.imdbRating` → `hero.rating?.value`
- `metaPreview.name` → `hero.title`
- `metaPreview.description` → `hero.overview`
- `metaPreview.genres` → `hero.genres`

- [ ] **Step 2: Update the call site to pass `uiState.resolvedHeroItems[focusedHeroIndex]`**

- [ ] **Step 3: Build + smoke test on device**

Verify hero panel renders backdrop + logo + metadata correctly. The hero should never lose its hydrated logo/backdrop after navigation.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomePresentation.kt
git commit -m "feat: hero panel composable consumes HeroDisplayItem"
```

---

### Task 11: Hero surface acceptance

- [ ] **Step 1: Run home test suites**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.*"`
Expected: PASS.

- [ ] **Step 2: Device verification**

Verify hero behaviour:
- Hero rotates through items, each displays correct hydrated logo + backdrop + rating
- Hero survives rail navigation/return
- Hero survives profile switch (shared resolved state)

- [ ] **Step 3: Heap-dump perf gate (3-min Modern Home + hero rotation soak)**

Repeat the `am dumpheap` + `hprof-conv` + `hprof-slurp` flow from Task 7 Step 3. Acceptance: no regression in `MetaPreview`/`HeroDisplayItem`/`ModernHomeRowItem` counts vs Task 7 post-baseline. Heap stays below 350 MB.

- [ ] **Step 4: Mark surface complete**

```bash
git tag -a plan-b-surface-2-hero -m "Hero panel migrated to ResolvedDisplayItem"
git push --tags
```

---

## Surface 3: Screensaver

### Task 12: Define `IdleScreensaverDisplayItem` projection

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverDisplayItem.kt`

- [ ] **Step 1: Create the type**

```kotlin
package com.nexio.tv.ui.screensaver

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TrailerDisplayState

/**
 * Screensaver-specific projection of [ResolvedDisplayItem]. Used by both
 * `IdleScreensaverOverlay` (variant 1: backdrop + logo + metadata) and
 * `IdleTrailerScreensaverOverlay` (variant 2: trailer + metadata).
 */
@Immutable
data class IdleScreensaverDisplayItem(
    val itemKey: String,
    val contentId: String,
    val title: String?,
    val overview: String?,
    val genres: List<String>,
    val year: Int?,
    val backgroundRef: ArtworkDisplayRef?,
    val logoRef: ArtworkDisplayRef?,
    val rating: TitleRating?,
    val trailer: TrailerDisplayState
) {
    companion object {
        fun from(resolved: ResolvedDisplayItem): IdleScreensaverDisplayItem =
            IdleScreensaverDisplayItem(
                itemKey = resolved.itemKey,
                contentId = resolved.contentId,
                title = resolved.display.title,
                overview = resolved.display.overview,
                genres = resolved.display.genres,
                year = resolved.display.year,
                backgroundRef = resolved.artwork.backdrop ?: resolved.artwork.poster,
                logoRef = resolved.artwork.logo,
                rating = resolved.rating,
                trailer = resolved.trailer
            )
    }
}
```

- [ ] **Step 2: Add unit test**

Create `app/src/test/java/com/nexio/tv/ui/screensaver/IdleScreensaverDisplayItemTest.kt` mirroring the `HeroDisplayItemTest` structure.

- [ ] **Step 3: Run + commit**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screensaver.IdleScreensaverDisplayItemTest
git add app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverDisplayItem.kt \
        app/src/test/java/com/nexio/tv/ui/screensaver/IdleScreensaverDisplayItemTest.kt
git commit -m "feat: IdleScreensaverDisplayItem projection type"
```

---

### Task 13: `ScreensaverCandidateRepository` already integrates — confirmation only

**Status:** Already done in Plan A. `ScreensaverCandidateRepository.kt:65` calls `surfaceRepository.getSnapshot(SCREENSAVER_SURFACE_KEY, profileId)` and returns `ResolvedDisplayItem`-shaped data. Skip this task; it is a no-op confirmation.

- [ ] **Step 1: One-line verification**

```bash
grep -n "ResolvedDisplaySurfaceRepository\|observeScreensaverSurface\|SCREENSAVER_SURFACE_KEY" app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt
```

Expect at least one `getSnapshot` or `observeScreensaverSurface` reference. If absent, escalate — Plan A's screensaver wiring regressed.

---

### Task 14: `IdleScreensaverController` consumes `IdleScreensaverDisplayItem`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverController.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverModels.kt`

- [ ] **Step 1: Replace `List<MetaPreview>` candidate state with `List<IdleScreensaverDisplayItem>`**

In the controller's state model, change candidate-list type to `IdleScreensaverDisplayItem`. The screensaver source is `resolvedDisplaySurfaceRepository.observeScreensaverSurface(profileId)` which emits `Flow<List<ResolvedDisplayItem>>` (flat list — no `.associateBy` needed since the screensaver consumes the list directly in rotation order). In the rotation/selection logic, map `IdleScreensaverDisplayItem.from(resolved)` over the list.

**Trailer-state preservation note:** `mergeIncrementalItems`/`withPreservedTrailerState` in the repository already retain prior trailer state when re-published with empty trailer. The screensaver controller does NOT need to defensively merge trailer fields itself.

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleUniversalDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverController.kt \
        app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverModels.kt
git commit -m "feat: idle screensaver controller consumes resolved display item"
```

---

### Task 15: Screensaver overlays consume `IdleScreensaverDisplayItem`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverOverlay.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverSession.kt`

- [ ] **Step 1: Update both overlays' parameter types**

Replace `MetaPreview` with `IdleScreensaverDisplayItem`. Field reads update accordingly:
- `metaPreview.background` → `item.backgroundRef`
- `metaPreview.logo` → `item.logoRef`
- `metaPreview.name`/`description`/`genres` → `item.title`/`overview`/`genres`
- `metaPreview.imdbRating` → `item.rating?.value`
- `metaPreview.trailerYtIds` → `item.trailer.fallbackTrailerYtIds`

- [ ] **Step 2: `IdleTrailerScreensaverSession` trailer-playback path**

If the session reads `metaPreview.trailerYtIds[0]` to start playback, switch to `item.trailer.selectedPlaybackRef` (preferred — already resolved by trailer resolver) with fallback to `item.trailer.fallbackTrailerYtIds.firstOrNull()`.

- [ ] **Step 3: Build + device smoke**

```bash
./gradlew :app:installUniversalDebug
# Trigger screensaver: leave app idle for screensaver timeout (Settings > Display)
```

Verify both screensaver variants:
- Variant 1 (idle): backdrop + logo + metadata persist; never falls back to placeholder when hydrated state exists
- Variant 2 (trailer): trailer plays, metadata overlay shows resolved title/genres/rating

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverOverlay.kt \
        app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt \
        app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverSession.kt
git commit -m "feat: screensaver overlays consume IdleScreensaverDisplayItem"
```

---

### Task 16: Screensaver surface acceptance

- [ ] **Step 1: Run screensaver tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screensaver.*" --tests "com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest" --tests "com.nexio.tv.data.repository.IdleScreensaverPreparationTest"`
Expected: PASS.

- [ ] **Step 2: Device verification**

Trigger screensaver multiple times; verify:
- First trigger after cold-start uses fully-hydrated state
- Subsequent triggers don't downgrade to first-paint
- Trailer screensaver starts trailer playback with resolved trailer ref

- [ ] **Step 3: Heap-dump perf gate**

Repeat the heap-dump flow. Soak: alternate Modern Home for 90 s, then idle to trigger screensaver for 90 s, capture. Acceptance: no regression vs Task 11 baseline.

- [ ] **Step 4: Mark surface complete**

```bash
git tag -a plan-b-surface-3-screensaver -m "Screensaver migrated to ResolvedDisplayItem"
git push --tags
```

---

## Surface 4: Continue Watching

### Task 17: Define `ContinueWatchingResolvedDisplayItem` projection

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/components/ContinueWatchingResolvedDisplayItem.kt`

CW already has its own `ContinueWatchingMetadataSnapshot` data path. The projection should keep CW's resume-identity / progress fields but draw the *display* fields (poster, title, etc.) from the resolved authority.

- [ ] **Step 1: Create the type**

```kotlin
package com.nexio.tv.ui.components

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.data.repository.ContinueWatchingRecord
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating

/**
 * CW row projection: combines [ContinueWatchingRecord] (resume identity, progress,
 * last-played timing) with [ResolvedDisplayItem] (display fields, artwork) so CW
 * cards display authoritative artwork and metadata while preserving CW-specific
 * progress UI.
 */
@Immutable
data class ContinueWatchingResolvedDisplayItem(
    val itemKey: String,
    val contentId: String,
    val title: String?,
    val posterRef: ArtworkDisplayRef?,
    val backdropRef: ArtworkDisplayRef?,
    val logoRef: ArtworkDisplayRef?,
    val rating: TitleRating?,
    val record: ContinueWatchingRecord
) {
    val progressPercent: Float
        get() = record.progressPercent
    val resumeMs: Long
        get() = record.resumeMs
    val lastPlayedAtMs: Long
        get() = record.lastPlayedAtMs

    companion object {
        fun from(resolved: ResolvedDisplayItem, record: ContinueWatchingRecord): ContinueWatchingResolvedDisplayItem =
            ContinueWatchingResolvedDisplayItem(
                itemKey = resolved.itemKey,
                contentId = resolved.contentId,
                title = resolved.display.title,
                posterRef = resolved.artwork.poster,
                backdropRef = resolved.artwork.backdrop,
                logoRef = resolved.artwork.logo,
                rating = resolved.rating,
                record = record
            )
    }
}
```

(Implementation note: confirm `ContinueWatchingRecord`'s exact fields — may need `progressPercent`, `resumeMs`, `lastPlayedAtMs` as different names.)

- [ ] **Step 2: Add unit test + commit**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.components.ContinueWatchingResolvedDisplayItemTest
git add app/src/main/java/com/nexio/tv/ui/components/ContinueWatchingResolvedDisplayItem.kt \
        app/src/test/java/com/nexio/tv/ui/components/ContinueWatchingResolvedDisplayItemTest.kt
git commit -m "feat: ContinueWatchingResolvedDisplayItem projection type"
```

---

### Task 18: `HomeViewModelContinueWatching` produces resolved CW rows

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt`

- [ ] **Step 1: Add `resolvedContinueWatchingItems: List<ContinueWatchingResolvedDisplayItem>` to `HomeUiState`**

- [ ] **Step 2: Combine the CW timeline with `ResolvedDisplaySurfaceRepository.observeHomeSurface`**

Replace the existing CW row construction so each CW record looks up its resolved item by `itemKey` and projects via `ContinueWatchingResolvedDisplayItem.from(resolved, record)`.

```kotlin
private val resolvedCwItemsFlow: Flow<List<ContinueWatchingResolvedDisplayItem>> =
    resolvedDisplaySurfaceRepository.observeHomeSurface(profileId = activeProfileId)
        .combine(continueWatchingTimelineFlow) { resolvedItems, cwRecords ->
            // observeHomeSurface returns Flow<List<ResolvedDisplayItem>>; index for lookup.
            val byItemKey = resolvedItems.associateBy { it.itemKey }
            cwRecords.mapNotNull { record ->
                byItemKey[record.itemKey]?.let { resolved ->
                    ContinueWatchingResolvedDisplayItem.from(resolved, record)
                }
            }
        }
```

(If a CW record has no resolved entry — e.g. an item watched once that has since dropped from any rail — fall back to a single-shot `resolvedDisplaySurfaceRepository.observeItem(profileId, record.itemKey)` lookup, OR keep the legacy `MetaPreview` for that record only. Do NOT fail the CW row entirely.)

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleUniversalDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt
git commit -m "feat: home view model exposes resolved CW items"
```

---

### Task 19: CW composables consume `ContinueWatchingResolvedDisplayItem`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/components/ContinueWatchingSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/components/GridContinueWatchingSection.kt`

- [ ] **Step 1: Replace `MetaPreview` parameter with `ContinueWatchingResolvedDisplayItem`**

Field reads:
- `metaPreview.poster` → `item.posterRef`
- `metaPreview.background` → `item.backdropRef`
- `metaPreview.name` → `item.title`
- progress / resume / last-played reads from `item.record` (already typed)

- [ ] **Step 2: Update call sites in `ModernHomeContent.kt`/`ClassicHomeContent.kt`/`GridHomeContent.kt`**

Pass `uiState.resolvedContinueWatchingItems` instead of the legacy CW list.

- [ ] **Step 3: Device verification**

Verify:
- CW cards show correct posters (premium when configured)
- Progress bars render correctly
- Resume click works (resume identity preserved)
- After playback ends and CW updates, the row reprojects correctly

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/components/ContinueWatchingSection.kt \
        app/src/main/java/com/nexio/tv/ui/components/GridContinueWatchingSection.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/ClassicHomeContent.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt
git commit -m "feat: continue watching consumes ContinueWatchingResolvedDisplayItem"
```

---

### Task 20: CW surface acceptance

- [ ] **Step 1: Run CW tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatching*" --tests "com.nexio.tv.ui.components.ContinueWatching*"`
Expected: PASS.

- [ ] **Step 2: Device verification — full CW flow**

- Start playback, navigate back; CW row updates
- Re-open, resume click works
- Profile switch: profile1 CW does not appear in profile2

- [ ] **Step 3: Heap-dump perf gate**

Soak: 60 s Modern Home, start playback for 60 s, return to home for 60 s; capture. Acceptance: no regression vs Task 16 baseline.

- [ ] **Step 4: Mark surface complete**

```bash
git tag -a plan-b-surface-4-cw -m "Continue Watching migrated to ResolvedDisplayItem"
git push --tags
```

---

## Surface 5: Detail screen

### Task 21: Define `MetaDetailsResolvedFields` helpers

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsResolvedFields.kt`

- [ ] **Step 1: Create the helper**

```kotlin
package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating

internal data class MetaDetailsResolvedFields(
    val title: String?,
    val overview: String?,
    val genres: List<String>,
    val year: Int?,
    val runtimeText: String?,
    val posterRef: ArtworkDisplayRef?,
    val backdropRef: ArtworkDisplayRef?,
    val logoRef: ArtworkDisplayRef?,
    val rating: TitleRating?
) {
    companion object {
        fun from(resolved: ResolvedDisplayItem): MetaDetailsResolvedFields =
            MetaDetailsResolvedFields(
                title = resolved.display.title,
                overview = resolved.display.overview,
                genres = resolved.display.genres,
                year = resolved.display.year,
                runtimeText = resolved.display.runtimeText,
                posterRef = resolved.artwork.poster,
                backdropRef = resolved.artwork.backdrop,
                logoRef = resolved.artwork.logo,
                rating = resolved.rating
            )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsResolvedFields.kt
git commit -m "feat: MetaDetailsResolvedFields helper"
```

---

### Task 22: `MetaDetailsViewModel` exposes resolved hero fields for detail

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt`

- [ ] **Step 1: Wire `ResolvedDisplaySurfaceRepository` into the detail VM**

The detail screen typically receives a content id at navigation time. Use the existing `ResolvedDisplaySurfaceRepository.observeItem(profileId, itemKey)` (defined at `ResolvedDisplaySurfaceRepository.kt:38`) — returns `Flow<ResolvedDisplayItem?>`. **No new repository API needed.**

Caveat: `observeItem` only finds items that appear in `HOME_SURFACE_KEY`. If detail is opened for an item the user found via search and never via Home, the resolved item may not be in the repository. In that case, fall back to detail's existing source for the missing fields (do not block detail rendering on this).

- [ ] **Step 2: Add `resolvedDetailFields: MetaDetailsResolvedFields?` to `MetaDetailsUiState`**

- [ ] **Step 3: Project the resolved item via `MetaDetailsResolvedFields.from`**

(If the detail screen also reads provider-specific fields not yet in `ResolvedDisplayItem` — cast, crew, etc. — keep those flowing from the existing detail repository alongside the resolved fields. Detail screen may continue to use multiple sources; only the *display* fields move to resolved.)

- [ ] **Step 4: Build + commit**

```bash
./gradlew :app:assembleUniversalDebug
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt \
        app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt
git commit -m "feat: detail view model exposes resolved display fields"
```

---

### Task 23: Detail composable consumes `MetaDetailsResolvedFields`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`

- [ ] **Step 1: Replace `MetaPreview`/`Meta` field reads with `resolvedDetailFields`**

In the hero/header portion of detail, switch to `state.resolvedDetailFields?.posterRef`, `.backdropRef`, `.logoRef`, `.title`, `.overview`, `.rating` etc.

(Other detail sections — cast, related — may continue with their own sources.)

- [ ] **Step 2: Device verification**

Open detail for various titles:
- TMDB movie: detail header shows premium poster + backdrop + logo
- Trakt show: detail header shows TVDB-resolved logo/backdrop, hydrated rating
- Anime (Kitsu): detail header shows kitsu poster, no logo if kitsu lacks one (preview logo from rail survives)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt
git commit -m "feat: detail screen consumes MetaDetailsResolvedFields"
```

---

### Task 24: `CollectionSection` and `MoreLikeThisSection` consume resolved items

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/CollectionSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MoreLikeThisSection.kt`

- [ ] **Step 1: Replace `List<MetaPreview>` items with `List<ModernHomeRowItem>` (already defined Surface 1)**

These rails behave like Home rails — reuse `ModernHomeRowItem`. The view model produces them from the same `ResolvedDisplaySurfaceRepository`.

- [ ] **Step 2: Build + smoke test on device**

Verify "More Like This" rail under any movie/show: cards render correct posters, never raw addon URLs.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/CollectionSection.kt \
        app/src/main/java/com/nexio/tv/ui/screens/detail/MoreLikeThisSection.kt
git commit -m "feat: collection and more-like-this consume ModernHomeRowItem"
```

---

### Task 25: Detail surface acceptance

- [ ] **Step 1: Run detail tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.detail.*"`
Expected: PASS.

- [ ] **Step 2: Device verification**

- Open detail for TMDB movie, Trakt show, Kitsu anime
- Verify hero artwork persists across navigation
- Verify "More Like This" rail items use resolved artwork

- [ ] **Step 3: Heap-dump perf gate**

Soak: 60 s Modern Home, open detail, scroll More-Like-This rail, return to home; capture. Acceptance: no regression vs Task 20 baseline.

- [ ] **Step 4: Mark surface complete**

```bash
git tag -a plan-b-surface-5-detail -m "Detail screen migrated to ResolvedDisplayItem"
git push --tags
```

---

## Cleanup pass

> **Incremental cleanup recommendation (post-session):** The legacy `MetaPreview` mutable-row state is the source of the allocation rate that PR #16 fixed. Coexistence between legacy + resolved fields lengthens the perf-risk window. **Delete each surface's legacy field from `HomeUiState` (and its construction in `HomeViewModelCatalogPipeline`) as part of the surface's final commit**, not in this task. Specifically:
>
> - End of Task 4: delete `displayRows: List<CatalogRow>` from `HomeUiState` and the rail-construction code that produces it
> - End of Task 10: delete `heroItems: List<MetaPreview>` from `HomeUiState`
> - End of Task 15: delete the legacy screensaver `MetaPreview` candidate path through `IdleScreensaverModels`
> - End of Task 19: delete `continueWatchingItems: List<MetaPreview>` from `HomeUiState`
>
> Task 26 below then becomes a smaller, structural cleanup — deleting the helpers (`HomeHydrationOverlayApplier`, `applyTo`, `mergeFallback`, `withCompatiblePersistedInternalPoster`) that no longer have callers.

### Task 26: Delete deprecated mutable-row helpers

**Files:**
- Delete: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt` (remove `applyTo`, `mergeFallback` — both are `@Deprecated` since Plan A commit `639024f58`)
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt` (remove `withCompatiblePersistedInternalPoster` and any remaining `applyTo` callers)
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` (delete any remaining legacy `MutableStateFlow<List<MetaPreview>>` plumbing if Tasks 4/10/15/19 incremental cleanup left residue)
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt` (verify legacy fields are already gone from per-surface incremental cleanup)

- [ ] **Step 1: Verify nothing still depends on the deprecated paths**

```bash
grep -rn "applyHydratedHomeOverlays\|HomeHydrationOverlayApplier\|HomeDisplayMetadata\.applyTo\|HomeDisplayMetadata\.mergeFallback\|withCompatiblePersistedInternalPoster" app/src/main app/src/test
```

Expected: zero hits in `app/src/main`. Some hits in tests are OK if those tests are themselves deprecated.

- [ ] **Step 2: Delete deprecated files and functions**

```bash
git rm app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt
```

In `HomeDisplayMetadata.kt`, delete `applyTo` (lines 81-115) and `mergeFallback` (lines 140-166), plus their private helpers (`preferDurableArtworkRef`, `mergeFallbackArtwork`, `mergeAppliedArtwork`, `sanitizedTitleRating`).

In `HomeCatalogRefreshCoordinator.kt`, delete `withCompatiblePersistedInternalPoster` and any `@Deprecated` shims.

In `HomeViewModelCatalogPipeline.kt`, delete the legacy `MutableStateFlow<List<MetaPreview>>` for `displayRows`/`fullRows`/`heroItems` and their assignments (catch any remaining downstream consumers — they're build-time errors now).

In `HomeUiState.kt`, delete the legacy fields.

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleUniversalDebug`
Expected: BUILD SUCCESSFUL. If anything still references the deleted symbols, fix that consumer (it was missed during the per-surface pass).

- [ ] **Step 4: Run full test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Final device verification**

Reboot the device, cold-start the app. Verify:
- All Home variants render correctly
- Hero rotates and displays correct artwork
- Screensaver triggers and renders correctly
- CW persists progress and posters
- Detail navigation shows correct artwork
- `home.display_projection` events show RESOLVED-rank wins for every hydrated item

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: delete deprecated mutable-row display paths"
```

---

### Task 27: Final acceptance + tag

- [ ] **Step 1: Run the full app test suite one more time**

Run: `./gradlew :app:testDebugUnitTest :app:lintUniversalDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the spec mandatory test suites from Plan A**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeFirstPaintInvariantTest" --tests "com.nexio.tv.ui.screens.home.HomeRailProjectionPremiumTest" --tests "com.nexio.tv.ui.screens.home.HomeRailProjectionRatingTest" --tests "com.nexio.tv.ui.screens.home.HomeRailProjectionAliasTest" --tests "com.nexio.tv.ui.screens.home.HomeRailProjectionKitsuTest" --tests "com.nexio.tv.ui.screens.home.HomeProfileSwitchPreservationTest"`
Expected: ALL PASS.

- [ ] **Step 3: Tag the migration complete**

```bash
git tag -a plan-b-complete -m "Resolved display authority UI migration complete: first-paint can never resurface"
git push --tags
```

- [ ] **Step 4: Update root README / CLAUDE.md if needed**

If `CLAUDE.md` documents the Home data flow, update it to describe the resolved authority architecture as the only authority — no longer "MetaPreview is mutated as overlays apply."

- [ ] **Step 5: Final commit**

```bash
git add CLAUDE.md
git commit -m "docs: document resolved display authority"
```

---

## Execution Handoff

After saving this plan, execution recommendation matches Plan A: `superpowers:subagent-driven-development` (recommended) for fresh-subagent-per-task with two-stage review, or `superpowers:executing-plans` for inline.

**Critical sequencing reminder:** Plan A must be merged AND device-verified before Plan B starts. Plan B's surface migrations all depend on `ResolvedDisplaySurfaceRepository` being correctly populated. Plan A is on `main` (PR #15) and the publisher path is intact at `HomeViewModelCatalogPipeline.kt:527, :2917, :3015`, even though Plan A Task 8's apply-seam reducer-projection was reverted.

## Tooling

- **Heap analysis:** `hprof-slurp` is on PATH (`~/Scripts/hprof-slurp`, fork with 32-bit support and largest-array `object_id` surfacing for retainer tracing). Use after `hprof-conv` strips Android-specific records.
- **Heap-dump cadence:** capture pre-Surface-1 baseline (Task 1.5 Step 5), then post-each-surface (Tasks 7, 11, 16, 20, 25). Diff `MetaPreview`/`ModernHomeRowItem`/`ResolvedRailRow`/`CatalogRow` counts.
- **Reverse-reference tracing:** if a heap-dump gate fails, use `hprof-slurp`'s largest-array object IDs to trace retainers. The legacy `/tmp/hprof-analyze-rust/` tool is no longer needed.
- **Logcat filter for verification:** `adb logcat -v threadtime | grep -E "home\\.display_projection|home\\.snapshot_|Background concurrent.*GC"`

## Pre-Plan-B housekeeping

Before starting Task 1, verify the working tree is clean:

```bash
git status
```

Specifically: the in-flight TVDB trailer mapper edits (`TvdbTrailerMapper.kt`, `TvdbTrailerResolver.kt`, `TvdbApi.kt`, `IntegrationApiShapes.kt`) must be committed or reverted before Plan B begins, so Task 15's `selectedPlaybackRef` consumption is built on a stable trailer-resolver contract.

**Per-surface checkpoints:** after each surface tag (`plan-b-surface-N-*`), pause for device verification before starting the next surface. The migration is designed to be safely interruptible — at any tag point the app is in a working hybrid state where some surfaces use resolved authority and others still use legacy mutable rows.
