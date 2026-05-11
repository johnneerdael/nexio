# Resolved Display Boundary Non-Downgrade Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire `HomeRailProjectionReducer` into `ResolvedDisplaySurfaceRepository.publishResolvedItems` so the typed authority's per-item slots never downgrade — making the repository boundary the single non-downgrade enforcement point.

**Architecture:** The reducer's fourth (third positional) argument `existing` has been dead code at every production call site (always `null`). The repository's `mergeIncrementalItems` and the parallel `replace=true` path are the only places where the surface state actually mutates, and neither consults the reducer. This plan routes every per-itemKey transition through `HomeRailProjectionReducer.reduce(firstPaint=incoming.slots, overlay=null, existing=existingForKey?.slots, profile=null)` at the boundary, then re-projects the merged slots to the legacy flat fields (`artwork`, `display`, `rating`). A shared projection helper deduplicates this with `HomeResolvedDisplayMapper`'s existing projection so both sites produce byte-identical flat fields from the same slots.

**Tech Stack:** Kotlin · Hilt · Coroutines/Flow · JUnit4 · Mockk

**Background / why this plan exists:** On-device verification 2026-05-11 confirmed that Modern Home rows persistently revert to first-paint TMDB stock posters after RPDB premium posters had already rendered (screenshots in conversation). Source-code trace identified two enforcement gaps:

1. `HomeResolvedDisplayMapper.toResolvedDisplayItem:117` calls `reduce(firstPaint, overlay, existing = null, profile = null)`. Production never supplies `existing`. The rank-aware non-downgrade logic in the reducer is dead.
2. `ResolvedDisplaySurfaceRepository.publishResolvedItems` (three overloads) routes through either `mergeIncrementalItems` (line 165) or wholesale-replace (line 99, `replace = true` default for the `(surfaceKey, profileSession, items, replace)` overload). **Neither calls the reducer.** The `mergeIncrementalItems` path only preserves the trailer field via `withPreservedTrailerState`; the replace path discards prior state entirely. An incoming item with FIRST_PAINT slots overwrites a previously-published RESOLVED item at the boundary every time.

`HomeRailProjectionReducer`'s own docstring declares: "This is the ONLY place that may merge multiple display inputs into final row state. Every other site (apply seam, refresh coordinator, hydration mapper) must funnel through here." The repository is the merge site; it doesn't funnel through there.

This is not a new bug — it's the original Plan A non-downgrade contract never being closed at the boundary. Plan A built the reducer; the wiring stopped at the mapper seam, which has no access to prior state.

---

## File Structure

### New files
- `app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySlotProjection.kt` — extension functions deriving `ArtworkBundle`, `ResolvedDisplayFields`, and `TitleRating?` from `ResolvedDisplayFieldSlots`. Reused by `HomeResolvedDisplayMapper` and `ResolvedDisplaySurfaceRepository`.
- `app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepositoryNonDowngradeTest.kt` — multi-emission boundary regression tests covering both `mergeIncrementalItems` and the `replace=true` path.
- `app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySlotProjectionTest.kt` — projection helper unit test.

### Modified files
- `app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt` — rank-aware boundary merge applied uniformly across all three `publishResolvedItems` overloads via a new private `applyNonDowngradeMerge` helper.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt` — switch flat-field construction to the shared projection helper.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeRailProjectionReducer.kt` — docstring correction (no longer claims to be the only merge site; the boundary is).

---

## Task 1: Failing TDD anchor — boundary non-downgrade regression tests

**Files:**
- Create: `app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepositoryNonDowngradeTest.kt`

- [ ] **Step 1: Write the failing test file**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.TrailerDisplayState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedDisplaySurfaceRepositoryNonDowngradeTest {

    private val profileSession = ActiveProfileSession(profileId = 1, sessionId = "test-session")
    private val repo = ResolvedDisplaySurfaceRepository(activeProfileSession = { profileSession })

    private val nowMs = 1_700_000_000_000L
    private val itemKey = "movie:tt12345"

    private fun artworkRef(url: String, provider: String): ArtworkDisplayRef =
        ArtworkDisplayRef(
            url = url,
            type = ArtworkType.POSTER,
            provider = ArtworkProviderId.RuntimeProvider(provider)
        )

    private fun slots(
        posterUrl: String?,
        posterRank: DisplaySourceRank,
        posterProvider: String = "TMDB"
    ): ResolvedDisplayFieldSlots {
        val posterSlot = if (posterUrl == null) {
            ResolvedSlot(value = null, rank = DisplaySourceRank.EMPTY, updatedAtMs = nowMs)
        } else {
            ResolvedSlot(
                value = artworkRef(posterUrl, posterProvider),
                rank = posterRank,
                updatedAtMs = nowMs
            )
        }
        val emptyString = ResolvedSlot<String>(null, DisplaySourceRank.EMPTY, nowMs)
        val emptyList = ResolvedSlot<List<String>>(null, DisplaySourceRank.EMPTY, nowMs)
        val emptyArtwork = ResolvedSlot<ArtworkDisplayRef>(null, DisplaySourceRank.EMPTY, nowMs)
        val emptyRating = ResolvedSlot<com.nexio.tv.domain.model.TitleRating>(null, DisplaySourceRank.EMPTY, nowMs)
        return ResolvedDisplayFieldSlots(
            title = emptyString,
            originalTitle = emptyString,
            overview = emptyString,
            genres = emptyList,
            releaseInfo = emptyString,
            runtime = emptyString,
            rating = emptyRating,
            poster = posterSlot,
            backdrop = emptyArtwork,
            logo = emptyArtwork,
            thumbnail = emptyArtwork,
            posterProviderTag = emptyString
        )
    }

    private fun item(
        posterUrl: String?,
        posterRank: DisplaySourceRank,
        posterProvider: String = "TMDB"
    ): ResolvedDisplayItem {
        val s = slots(posterUrl, posterRank, posterProvider)
        return ResolvedDisplayItem(
            itemKey = itemKey,
            contentId = "12345",
            parentId = "12345",
            itemType = ContentType.MOVIE,
            mediaKind = MetadataMediaKind.MOVIE,
            canonicalProvider = null,
            canonicalId = null,
            imdbId = null,
            stableIds = ProviderIds(),
            display = ResolvedDisplayFields(
                title = null, originalTitle = null, year = null,
                releaseDate = null, overview = null, genres = emptyList(),
                runtimeText = null, tomatoesRating = null
            ),
            artwork = ArtworkBundle(
                poster = s.poster.value,
                backdrop = null, logo = null, thumbnail = null
            ),
            rating = null,
            trailer = TrailerDisplayState(),
            hydrationState = if (posterRank == DisplaySourceRank.RESOLVED)
                HydrationState.CANONICAL_READY else HydrationState.PREVIEW_ONLY,
            sourceTrace = emptyList(),
            updatedAtMs = nowMs,
            slots = s
        )
    }

    @Test
    fun `firstPaint over RESOLVED is rejected — RESOLVED slots preserved`() = runTest {
        // First publish: RESOLVED RPDB poster (hydrated)
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://rpdb.example/p1.jpg", DisplaySourceRank.RESOLVED, "RPDB")),
            replace = true
        )

        // Second publish: FIRST_PAINT TMDB poster (stale producer emission with no overlay)
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://tmdb.example/stock.jpg", DisplaySourceRank.FIRST_PAINT, "TMDB")),
            replace = true
        )

        val after = repo.observeHomeSurface(profileSession.profileId).first()
        assertEquals(1, after.size)
        val poster = after[0].slots!!.poster
        assertEquals(DisplaySourceRank.RESOLVED, poster.rank)
        assertEquals("https://rpdb.example/p1.jpg", poster.value?.url)
        assertEquals("RPDB", (poster.value?.provider as ArtworkProviderId.RuntimeProvider).id)
        // Flat artwork field must also reflect the merged slot (re-projection)
        assertEquals("https://rpdb.example/p1.jpg", after[0].artwork.poster?.url)
    }

    @Test
    fun `RESOLVED over FIRST_PAINT promotes to RESOLVED slots`() = runTest {
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://tmdb.example/stock.jpg", DisplaySourceRank.FIRST_PAINT, "TMDB")),
            replace = true
        )
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://rpdb.example/p1.jpg", DisplaySourceRank.RESOLVED, "RPDB")),
            replace = true
        )

        val after = repo.observeHomeSurface(profileSession.profileId).first()
        assertEquals(DisplaySourceRank.RESOLVED, after[0].slots!!.poster.rank)
        assertEquals("https://rpdb.example/p1.jpg", after[0].artwork.poster?.url)
    }

    @Test
    fun `replace=false additive path also enforces non-downgrade per itemKey`() = runTest {
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://rpdb.example/p1.jpg", DisplaySourceRank.RESOLVED, "RPDB")),
            replace = true
        )
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://tmdb.example/stock.jpg", DisplaySourceRank.FIRST_PAINT, "TMDB")),
            replace = false  // additive merge path
        )

        val after = repo.observeHomeSurface(profileSession.profileId).first()
        assertEquals(1, after.size)
        assertEquals(DisplaySourceRank.RESOLVED, after[0].slots!!.poster.rank)
        assertEquals("RPDB", (after[0].slots!!.poster.value?.provider as ArtworkProviderId.RuntimeProvider).id)
    }

    @Test
    fun `single-overload publish (no profileSession) enforces non-downgrade`() = runTest {
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            items = listOf(item("https://rpdb.example/p1.jpg", DisplaySourceRank.RESOLVED, "RPDB"))
        )
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            items = listOf(item("https://tmdb.example/stock.jpg", DisplaySourceRank.FIRST_PAINT, "TMDB"))
        )

        val after = repo.observeHomeSurface(profileSession.profileId).first()
        assertEquals(DisplaySourceRank.RESOLVED, after[0].slots!!.poster.rank)
    }

    @Test
    fun `existing slots null falls through to incoming as-is`() = runTest {
        // Legacy producer publishing without slots
        val legacyItem = item("https://legacy.example/p.jpg", DisplaySourceRank.FIRST_PAINT)
            .copy(slots = null)
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(legacyItem),
            replace = true
        )
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://new.example/p.jpg", DisplaySourceRank.RESOLVED, "RPDB")),
            replace = true
        )

        val after = repo.observeHomeSurface(profileSession.profileId).first()
        assertEquals("https://new.example/p.jpg", after[0].artwork.poster?.url)
    }

    @Test
    fun `incoming slots null cannot enforce — takes incoming verbatim`() = runTest {
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://rpdb.example/p1.jpg", DisplaySourceRank.RESOLVED, "RPDB")),
            replace = true
        )
        val legacyItem = item("https://legacy.example/p.jpg", DisplaySourceRank.FIRST_PAINT)
            .copy(slots = null)
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(legacyItem),
            replace = true
        )

        val after = repo.observeHomeSurface(profileSession.profileId).first()
        // No slot info on incoming → cannot enforce; legacy path wins.
        assertEquals("https://legacy.example/p.jpg", after[0].artwork.poster?.url)
    }

    @Test
    fun `reference stability — identical-content republish returns existing instance`() = runTest {
        val resolved = item("https://rpdb.example/p1.jpg", DisplaySourceRank.RESOLVED, "RPDB")
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(resolved),
            replace = true
        )
        val firstSurface = repo.observeHomeSurface(profileSession.profileId).first()
        val firstItem = firstSurface[0]

        // Re-publish a NEW instance with identical content (typical mapper-memo cache miss scenario)
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(resolved.copy()),
            replace = true
        )
        val secondSurface = repo.observeHomeSurface(profileSession.profileId).first()
        assertSame(
            "Identical-content republish must preserve reference for downstream `===` short-circuit",
            firstItem,
            secondSurface[0]
        )
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "*ResolvedDisplaySurfaceRepositoryNonDowngradeTest*"`
Expected: FAIL — the `firstPaint over RESOLVED is rejected` test in particular will show the regression: the surface after the second publish has FIRST_PAINT TMDB poster instead of RESOLVED RPDB.

- [ ] **Step 3: Commit the failing tests**

```bash
git add app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepositoryNonDowngradeTest.kt
git commit -m "$(cat <<'EOF'
test(repo/boundary): failing TDD anchor — boundary non-downgrade contract

Multi-emission regression tests for ResolvedDisplaySurfaceRepository proving
the typed authority's slots downgrade at the publishResolvedItems boundary.
HomeRailProjectionReducer's `existing` arg is dead code at every production
call site, and the repository's mergeIncrementalItems / wholesale-replace
paths do not consult the reducer at all. UI-confirmed regression on
2026-05-11 (Modern Home rows reverting from RPDB premium posters to TMDB
stock posters after hydration completed).

These tests intentionally fail until the boundary enforcement lands.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Slots → flat-fields projection helper

The existing flat-fields projection lives inline in `HomeResolvedDisplayMapper.toResolvedDisplayItem:160–186`. Extract it so both the mapper and the new boundary merge produce byte-identical flat fields from the same slots.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySlotProjection.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySlotProjectionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolvedDisplaySlotProjectionTest {

    private val nowMs = 1_700_000_000_000L

    private fun emptySlots(): ResolvedDisplayFieldSlots {
        val emptyString = ResolvedSlot<String>(null, DisplaySourceRank.EMPTY, nowMs)
        val emptyList = ResolvedSlot<List<String>>(null, DisplaySourceRank.EMPTY, nowMs)
        val emptyArtwork = ResolvedSlot<ArtworkDisplayRef>(null, DisplaySourceRank.EMPTY, nowMs)
        val emptyRating = ResolvedSlot<TitleRating>(null, DisplaySourceRank.EMPTY, nowMs)
        return ResolvedDisplayFieldSlots(
            title = emptyString,
            originalTitle = emptyString,
            overview = emptyString,
            genres = emptyList,
            releaseInfo = emptyString,
            runtime = emptyString,
            rating = emptyRating,
            poster = emptyArtwork,
            backdrop = emptyArtwork,
            logo = emptyArtwork,
            thumbnail = emptyArtwork,
            posterProviderTag = emptyString
        )
    }

    @Test
    fun `projects artwork bundle from poster, backdrop, logo, thumbnail slots`() {
        val posterRef = ArtworkDisplayRef(
            url = "https://example.com/p.jpg",
            type = ArtworkType.POSTER,
            provider = ArtworkProviderId.RuntimeProvider("RPDB")
        )
        val backdropRef = ArtworkDisplayRef(
            url = "https://example.com/b.jpg",
            type = ArtworkType.BACKDROP,
            provider = ArtworkProviderId.RuntimeProvider("TVDB")
        )
        val slots = emptySlots().copy(
            poster = ResolvedSlot(posterRef, DisplaySourceRank.RESOLVED, nowMs),
            backdrop = ResolvedSlot(backdropRef, DisplaySourceRank.RESOLVED, nowMs)
        )

        val bundle = slots.toArtworkBundle()

        assertEquals(posterRef, bundle.poster)
        assertEquals(backdropRef, bundle.backdrop)
        assertNull(bundle.logo)
        assertNull(bundle.thumbnail)
    }

    @Test
    fun `projects ResolvedDisplayFields from text + list slots`() {
        val slots = emptySlots().copy(
            title = ResolvedSlot("The Movie", DisplaySourceRank.RESOLVED, nowMs),
            originalTitle = ResolvedSlot("Le Film", DisplaySourceRank.RESOLVED, nowMs),
            overview = ResolvedSlot("A story", DisplaySourceRank.RESOLVED, nowMs),
            genres = ResolvedSlot(listOf("Drama", "Crime"), DisplaySourceRank.RESOLVED, nowMs),
            releaseInfo = ResolvedSlot("2024-03-15", DisplaySourceRank.RESOLVED, nowMs),
            runtime = ResolvedSlot("142 min", DisplaySourceRank.RESOLVED, nowMs)
        )
        val fields = slots.toResolvedDisplayFields(fallbackTitle = "fallback", fallbackTomatoesRating = 87.5)

        assertEquals("The Movie", fields.title)
        assertEquals("Le Film", fields.originalTitle)
        assertEquals(2024, fields.year)
        assertEquals("2024-03-15", fields.releaseDate)
        assertEquals("A story", fields.overview)
        assertEquals(listOf("Drama", "Crime"), fields.genres)
        assertEquals("142 min", fields.runtimeText)
        assertEquals(87.5, fields.tomatoesRating)
    }

    @Test
    fun `title falls back when slot value is null`() {
        val slots = emptySlots()
        val fields = slots.toResolvedDisplayFields(fallbackTitle = "Fallback Title", fallbackTomatoesRating = null)
        assertEquals("Fallback Title", fields.title)
        assertNull(fields.year)
    }

    @Test
    fun `releaseInfo with non-4-char prefix returns null year`() {
        val slots = emptySlots().copy(
            releaseInfo = ResolvedSlot("TBA", DisplaySourceRank.RESOLVED, nowMs)
        )
        val fields = slots.toResolvedDisplayFields(fallbackTitle = "x", fallbackTomatoesRating = null)
        assertNull(fields.year)
    }

    @Test
    fun `projects rating from slot when present`() {
        val rating = TitleRating(value = 8.4, source = TitleRatingSource.IMDB)
        val slots = emptySlots().copy(
            rating = ResolvedSlot(rating, DisplaySourceRank.RESOLVED, nowMs)
        )
        assertEquals(rating, slots.toRating())
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "*ResolvedDisplaySlotProjectionTest*"`
Expected: FAIL with "unresolved reference: toArtworkBundle" (and similar for the other helpers).

- [ ] **Step 3: Create the projection helper**

```kotlin
// app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySlotProjection.kt
package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.TitleRating

/**
 * Project [ResolvedDisplayFieldSlots] → flat-field shapes that legacy renderers consume.
 *
 * Shared between [com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapper] (which produces
 * fresh items from a CatalogRow + overlay) and [ResolvedDisplaySurfaceRepository] (which
 * re-projects after the boundary's rank-aware merge). Both paths must produce byte-
 * identical flat fields from the same slots, otherwise a downgrade-protected merge at the
 * boundary could leak through if either projection diverged.
 */
internal fun ResolvedDisplayFieldSlots.toArtworkBundle(): ArtworkBundle =
    ArtworkBundle(
        poster = poster.value,
        backdrop = backdrop.value,
        logo = logo.value,
        thumbnail = thumbnail.value
    ).enforceArtworkTypeBoundaries()

internal fun ResolvedDisplayFieldSlots.toResolvedDisplayFields(
    fallbackTitle: String,
    fallbackTomatoesRating: Double?
): ResolvedDisplayFields {
    val effectiveTitle = title.value ?: fallbackTitle
    val yearText = releaseInfo.value?.take(4)?.takeIf { it.length == 4 }
    return ResolvedDisplayFields(
        title = effectiveTitle,
        originalTitle = originalTitle.value,
        year = yearText?.toIntOrNull(),
        releaseDate = releaseInfo.value,
        overview = overview.value,
        genres = genres.value.orEmpty(),
        runtimeText = runtime.value,
        tomatoesRating = fallbackTomatoesRating
    )
}

internal fun ResolvedDisplayFieldSlots.toRating(): TitleRating? = rating.value
```

Note on `tomatoesRating`: the original mapper projects `tomatoesRating = overlay?.fields?.tomatoesRating` (mapper line 168) — sourced from the overlay payload, not from any slot. There is no `tomatoes` field in `ResolvedDisplayFieldSlots`. The helper takes it as a `fallbackTomatoesRating` parameter; the mapper passes the overlay value, the boundary passes whatever the existing or incoming item already had. This preserves current behavior exactly until a proper `tomatoes` slot is added later.

- [ ] **Step 4: Run tests — verify they pass**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "*ResolvedDisplaySlotProjectionTest*"`
Expected: PASS — all 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySlotProjection.kt \
        app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySlotProjectionTest.kt
git commit -m "$(cat <<'EOF'
refactor(repo): extract ResolvedDisplayFieldSlots → flat-field projection helper

Extracts ArtworkBundle / ResolvedDisplayFields / TitleRating projection
from HomeResolvedDisplayMapper into a shared helper. Used in the next
commit by ResolvedDisplaySurfaceRepository's new boundary merge so both
sites produce byte-identical flat fields from the same slots.

No behavior change at this commit — the helper exists alongside the
inline mapper code. The mapper migration to the helper lands in a
follow-up task once the boundary merge consumes it too.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Boundary non-downgrade merge — the actual fix

Wire `HomeRailProjectionReducer` into `ResolvedDisplaySurfaceRepository` so every per-itemKey transition is rank-checked, uniformly across all three `publishResolvedItems` overloads.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt`

- [ ] **Step 1: Read the current file**

The current `mergeIncrementalItems` (lines 165–200) is allocation-tuned but ignores slot rank entirely. The `replace=true` path (line 99–101) doesn't go through any merge at all. Both must route per-itemKey transitions through a new private helper.

- [ ] **Step 2: Add the boundary merge helper**

Add this to `ResolvedDisplaySurfaceRepository.kt` (after `withPreservedTrailerState`, before `shouldSuppressSurfaceUpdate`):

```kotlin
/**
 * Non-downgrade per-itemKey merge — the load-bearing rule #1 enforcement point.
 *
 * For each [incoming] item that has a counterpart in [existingByKey] (matched by
 * itemKey), runs HomeRailProjectionReducer over (firstPaint=incoming.slots,
 * existing=existing.slots) and rebuilds the incoming item with the rank-winning
 * slots — preserving non-slot fields (trailer, hydrationState, etc.) from the
 * incoming side, since the producer's view of those is more current than the
 * repository's prior emission.
 *
 * Reference-stability: when the reducer's output equals the existing item's
 * slots field-for-field (i.e. all winners came from existing), returns the
 * existing instance so downstream `===` short-circuits hold.
 *
 * Slot-aware merge is skipped (incoming returned as-is) when either side has
 * `slots == null` — that signals a legacy code path that hasn't migrated to
 * the typed slot model. Once Phase 4 retires the legacy paths, this fallback
 * becomes dead code and can be removed.
 */
private fun applyNonDowngradeMerge(
    incoming: ResolvedDisplayItem,
    existing: ResolvedDisplayItem?
): ResolvedDisplayItem {
    if (existing == null) return incoming
    val incomingSlots = incoming.slots ?: return incoming
    val existingSlots = existing.slots ?: return incoming

    val mergedSlots = com.nexio.tv.ui.screens.home.HomeRailProjectionReducer.reduce(
        firstPaint = incomingSlots,
        overlay = null,
        existing = existingSlots,
        profile = null
    )

    if (mergedSlots == existingSlots && incoming.slotDerivedFieldsMatch(existing)) {
        // All slot winners came from existing AND incoming's non-slot fields
        // are unchanged — return existing to preserve reference stability.
        return existing
    }

    val mergedArtwork = mergedSlots.toArtworkBundle()
    val mergedDisplay = mergedSlots.toResolvedDisplayFields(
        fallbackTitle = incoming.display.title.orEmpty(),
        fallbackTomatoesRating = incoming.display.tomatoesRating ?: existing.display.tomatoesRating
    )
    val mergedRating = mergedSlots.toRating() ?: incoming.rating

    return incoming.copy(
        slots = mergedSlots,
        artwork = mergedArtwork,
        display = mergedDisplay,
        rating = mergedRating
    )
}

/**
 * True when [other] has the same slot-derived flat fields as `this`. Used to
 * decide whether reference stability can short-circuit a no-op merge.
 */
private fun ResolvedDisplayItem.slotDerivedFieldsMatch(other: ResolvedDisplayItem): Boolean =
    artwork == other.artwork && display == other.display && rating == other.rating
```

- [ ] **Step 3: Wire the helper into `mergeIncrementalItems` (replace=false path)**

Replace the body of `mergeIncrementalItems` (current lines 165–200) with:

```kotlin
private fun mergeIncrementalItems(
    existing: List<ResolvedDisplayItem>,
    incoming: List<ResolvedDisplayItem>
): List<ResolvedDisplayItem> {
    if (existing.isEmpty()) return incoming
    if (existing.size == incoming.size) {
        var sameInPlace = true
        for (i in existing.indices) {
            if (existing[i] !== incoming[i]) { sameInPlace = false; break }
        }
        if (sameInPlace) return existing
    }
    val existingByKey = HashMap<String, ResolvedDisplayItem>(existing.size)
    for (i in existing.indices) {
        val item = existing[i]
        existingByKey[item.itemKey] = item
    }
    val mergedIncoming = ArrayList<ResolvedDisplayItem>(incoming.size)
    val incomingKeys = HashSet<String>(incoming.size)
    for (i in incoming.indices) {
        val item = incoming[i]
        val existingForKey = existingByKey[item.itemKey]
        val rankProtected = applyNonDowngradeMerge(item, existingForKey)
        mergedIncoming += rankProtected.withPreservedTrailerState(existingForKey)
        incomingKeys += item.itemKey
    }
    val out = ArrayList<ResolvedDisplayItem>(existing.size + mergedIncoming.size)
    for (i in existing.indices) {
        val item = existing[i]
        if (item.itemKey !in incomingKeys) out += item
    }
    for (i in mergedIncoming.indices) out += mergedIncoming[i]
    return out
}
```

The change is one new line (`val rankProtected = applyNonDowngradeMerge(...)`) plus replacing `item.withPreservedTrailerState(...)` with `rankProtected.withPreservedTrailerState(...)`. The fast-path reference-equality short-circuit at the top is unchanged — when content is unchanged across emissions, the merge path is not entered.

- [ ] **Step 4: Wire the helper into the `replace=true` path of the third overload**

Modify `publishResolvedItems(surfaceKey, profileSession, items, replace)` (current lines 84–114). Replace the body so that when `replace=true`, every incoming item is still rank-checked against its previous counterpart:

```kotlin
@Synchronized
fun publishResolvedItems(
    surfaceKey: String,
    profileSession: ActiveProfileSession,
    items: List<ResolvedDisplayItem>,
    replace: Boolean = true
): Boolean {
    if (!isSupportedSurface(surfaceKey)) return false
    val active = activeProfileSession()
    if (active.profileId != profileSession.profileId || active.sessionId != profileSession.sessionId) {
        return false
    }

    var published = false
    surfaces.update { current ->
        val currentSurface = current[surfaceKey].orEmpty()
        val existingList = currentSurface[profileSession.profileId].orEmpty()
        val nextItems = if (replace) {
            applyNonDowngradeMergeForReplace(existingList, items)
        } else {
            mergeIncrementalItems(existingList, items)
        }.distinctBy { item -> item.itemKey }
        if (shouldSuppressSurfaceUpdate(surfaceKey, existingList, nextItems)) {
            current
        } else {
            published = true
            current + (surfaceKey to (currentSurface + (profileSession.profileId to nextItems)))
        }
    }
    return published
}
```

Add the new helper for the wholesale-replace path:

```kotlin
/**
 * Wholesale-replace path: the surface becomes exactly [incoming], but per-item
 * slots that appear on both sides are rank-merged so [incoming]'s FIRST_PAINT
 * cannot overwrite a previously-published RESOLVED slot.
 *
 * Allocation-tuned: when [incoming] is element-wise reference-equal to
 * [existing], returns [existing] unchanged. Otherwise builds a fresh list
 * applying the per-itemKey merge.
 */
private fun applyNonDowngradeMergeForReplace(
    existing: List<ResolvedDisplayItem>,
    incoming: List<ResolvedDisplayItem>
): List<ResolvedDisplayItem> {
    if (existing.isEmpty()) return incoming
    if (existing.size == incoming.size) {
        var sameInPlace = true
        for (i in existing.indices) {
            if (existing[i] !== incoming[i]) { sameInPlace = false; break }
        }
        if (sameInPlace) return existing
    }
    val existingByKey = HashMap<String, ResolvedDisplayItem>(existing.size)
    for (i in existing.indices) {
        val item = existing[i]
        existingByKey[item.itemKey] = item
    }
    val out = ArrayList<ResolvedDisplayItem>(incoming.size)
    for (i in incoming.indices) {
        val item = incoming[i]
        val existingForKey = existingByKey[item.itemKey]
        val rankProtected = applyNonDowngradeMerge(item, existingForKey)
        out += rankProtected.withPreservedTrailerState(existingForKey)
    }
    return out
}
```

Note: this changes wholesale-replace semantics to "the SET of items becomes exactly incoming, but per-item slots that appear on both sides are rank-merged." Items in `existing` whose itemKey is NOT in `incoming` are still dropped — that's still wholesale set replacement. Only the per-item slot data is rank-protected.

- [ ] **Step 5: Wire the helper into the single-overload publish (no profileSession)**

The first overload at lines 50–71 also goes through `mergeIncrementalItems`, so Task 3 Step 3 already covers it. No further change to this overload's body.

- [ ] **Step 6: Run boundary tests — verify they pass**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "*ResolvedDisplaySurfaceRepositoryNonDowngradeTest*"`
Expected: PASS — all 7 tests.

- [ ] **Step 7: Run the full repository test suite to catch any pre-existing test that depended on the downgrade-by-default behavior**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "*ResolvedDisplaySurfaceRepository*"`
Expected: PASS for all tests. If any pre-existing test fails, read the test — it's likely asserting downgrade behavior implicitly (e.g., "after re-publish, item should be X" where X happens to be the FIRST_PAINT value of the second publish). Update the assertion to reflect the new correct contract: RESOLVED dominates.

- [ ] **Step 8: Run the full home test suite to catch downstream regressions**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "*home*" --tests "*Home*"`
Expected: PASS for all home tests. The mapper's reducer call still works (it's combining `firstPaint + overlay`); the boundary now adds the `existing` enforcement on top. Mapper tests should be unaffected.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt
git commit -m "$(cat <<'EOF'
fix(repo): enforce non-downgrade at ResolvedDisplaySurfaceRepository boundary

Wires HomeRailProjectionReducer into publishResolvedItems so every per-
itemKey transition is rank-checked against previously-published slots.

Before this commit, the reducer's `existing` argument was dead code at
every production call site — HomeResolvedDisplayMapper passes `null`, and
the repository's mergeIncrementalItems / wholesale-replace paths never
called the reducer at all. An incoming FIRST_PAINT slot overwrote a
previously-published RESOLVED slot every time the producer re-emitted
without a current overlay (cold-start, transient hydration map rebuild,
network blip refresh).

On-device verification 2026-05-11 captured this as the user-visible
"Modern Home rows reverting from RPDB premium posters to TMDB stock"
regression (screenshots tmdb-series.png, tmdb-movies.png, from.png).

The fix routes every replace=false additive merge AND every replace=true
wholesale-replace through a new private applyNonDowngradeMerge helper:

  reduce(firstPaint = incoming.slots, overlay = null,
         existing = existingByKey[itemKey]?.slots, profile = null)
  → re-project flat fields (artwork, display, rating) from merged slots

Reference-stability preserved: when all reducer winners come from
existing AND non-slot fields are unchanged, returns the existing instance
so downstream `===` short-circuits in mergeIncrementalItems' fast path
and shouldSuppressSurfaceUpdate continue to hold.

Legacy-path fallback: when either side has `slots == null` (legacy code
that pre-dates the typed slot model), the merge is skipped and the
prior replace semantics are used. This becomes dead code once Phase 4
retires the legacy paths.

Boundary regression tests (7) added in the prior commit now pass.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Migrate `HomeResolvedDisplayMapper` to the shared projection helper

DRY cleanup: the mapper still hand-rolls the `ArtworkBundle(...).enforceArtworkTypeBoundaries()` + `ResolvedDisplayFields(...)` blocks inline. Switch to the shared helper from Task 2 so both sites produce identical projections from identical slots.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`

- [ ] **Step 1: Update the mapper's flat-field construction**

In `HomeResolvedDisplayMapper.toResolvedDisplayItem` (line 146 onwards), replace the hand-rolled `display = ResolvedDisplayFields(...)`, `artwork = ArtworkBundle(...).enforceArtworkTypeBoundaries()`, and `rating = mergedSlots.rating.value` lines with calls to the shared helper.

Add the import at the top of the file:

```kotlin
import com.nexio.tv.data.repository.toArtworkBundle
import com.nexio.tv.data.repository.toResolvedDisplayFields
import com.nexio.tv.data.repository.toRating
```

Replace lines 160–176 (the body of the `ResolvedDisplayItem(...)` constructor for `display`, `artwork`, `rating`):

```kotlin
            display = mergedSlots.toResolvedDisplayFields(
                fallbackTitle = title,
                fallbackTomatoesRating = overlay?.fields?.tomatoesRating
            ),
            artwork = mergedSlots.toArtworkBundle(),
            rating = mergedSlots.toRating(),
```

Note: the mapper currently passes `title = title` (line 161, where `title` is the local `mergedSlots.title.value ?: name`). The new helper handles the fallback internally — pass `name` (the original `MetaPreview.name`) as `fallbackTitle`. The result is identical when `mergedSlots.title.value` is non-null; when null, both old and new fall back to `name`.

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileUniversalDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the mapper test suite to confirm no behavior change**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "*HomeResolvedDisplayMapperTest*"`
Expected: PASS for all existing mapper tests. Projection output is byte-identical with the helper.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt
git commit -m "$(cat <<'EOF'
refactor(home/mapper): use shared slots → flat-fields projection helper

DRY follow-up to the boundary non-downgrade fix. HomeResolvedDisplayMapper
and ResolvedDisplaySurfaceRepository now produce flat ArtworkBundle /
ResolvedDisplayFields / TitleRating from the same shared projection
helper, ensuring the boundary-merged item's flat fields are byte-
identical to what the mapper would have produced if it had had access to
the same merged slots in the first place. No behavior change.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Correct the `HomeRailProjectionReducer` docstring

The reducer's docstring (lines 8–19) declares it the "ONLY place" that may merge display inputs. That has never been true in practice and is now provably false (the repository boundary owns the merge). Update the docstring to describe what the reducer actually is: a pure rank-aware slot picker callable at any merge site.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeRailProjectionReducer.kt`

- [ ] **Step 1: Update the docstring**

Replace lines 8–19 with:

```kotlin
/**
 * Pure rank-aware slot picker. For every field in [ResolvedDisplayFieldSlots],
 * picks the highest-ranked non-null candidate among (firstPaint, overlay,
 * existing, profile). A higher rank ALWAYS beats a lower rank, even when the
 * higher-rank slot's value is null — null at RESOLVED means "the authoritative
 * source explicitly produced no value", which must not be papered over by
 * FIRST_PAINT.
 *
 * **Where this is invoked from:**
 *
 * - [com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository.applyNonDowngradeMerge]
 *   — the load-bearing **non-downgrade enforcement point**. Every per-itemKey
 *   transition published into the typed authority routes through here with
 *   `existing = previously-published slots`. This is what guarantees the
 *   "RESOLVED never downgrades to FIRST_PAINT" contract end-to-end.
 *
 * - [com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapper.toResolvedDisplayItem]
 *   — combines a single CatalogRow item's `firstPaint` slots with its overlay
 *   slots. Passes `existing = null` because the mapper has no access to the
 *   repository's current state. The non-downgrade contract is enforced
 *   downstream at the boundary, not here.
 *
 * - [com.nexio.tv.ui.screens.home.HomeCatalogRefreshCoordinator],
 *   [com.nexio.tv.ui.screens.home.HomeViewModelContinueWatching],
 *   [com.nexio.tv.data.repository.ContinueWatchingMetadataSnapshot] — non-home
 *   surface merges for CW and refresh coordination paths.
 */
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileUniversalDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeRailProjectionReducer.kt
git commit -m "$(cat <<'EOF'
docs(reducer): correct contract description — boundary is the enforcement site

HomeRailProjectionReducer is a pure rank-aware slot picker. The previous
docstring claimed it was the "ONLY place" that merges display inputs,
which was never true: the repository boundary is where state actually
mutates, and it didn't funnel through the reducer until the prior commit.

The reducer is callable at any merge site. The non-downgrade contract is
enforced at ResolvedDisplaySurfaceRepository.applyNonDowngradeMerge, not
at the reducer itself.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: On-device verification — Modern Home no longer reverts to first paint

**Files:** N/A (verification only)

- [ ] **Step 1: Build + install**

Run:
```bash
./gradlew :app:installUniversalDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Cold-start the app with the smoke-test sequence (CLAUDE.md rule #8)**

Run:
```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 30
```

- [ ] **Step 3: Capture a logcat snippet showing `home.display_projection` events with RESOLVED-rank wins**

Run:
```bash
adb -s 192.168.50.98:5555 logcat -d -t 2000 | grep "home.display_projection" | head -30
```
Expected: each event line shows `selected.poster.rank=RESOLVED` (or `STALE_RESOLVED`) for items that have hydrated. If any line shows `selected.poster.rank=FIRST_PAINT` for an item that was previously hydrated in this session, the boundary enforcement is not catching that emission path — read the event's full payload to see which producer emitted the FIRST_PAINT and trace from there.

- [ ] **Step 4: Manual visual verification**

Navigate Modern Home to a row with a previously-RPDB-poster item (the user's tmdb-series / tmdb-movies screenshots had several). Observe for at least 60s during normal hydration / refresh cycles. The premium RPDB poster must NOT revert to a TMDB stock poster.

Expected outcome: posters render the RPDB premium artwork and stay on it. No visible revert to first-paint TMDB stock posters during the soak.

- [ ] **Step 5: Capture a heap dump for completeness**

Run:
```bash
PID=$(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv)
adb -s 192.168.50.98:5555 shell am dumpheap $PID /data/local/tmp/heap-boundary-fix.hprof
sleep 6
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-boundary-fix.hprof /tmp/heap-boundary-fix.hprof
heaptrail -i /tmp/heap-boundary-fix.hprof --leak-suspects 0.03 --exclude-soft-weak -t 10 | head -50
```
Expected: home pipeline retention remains stable (`MetaPreview` ≲ 500, `CatalogRow` ≲ 50), `ResolvedDisplaySurfaceRepository` retained size still small (<50 KiB). No new dominator entries introduced by the merge helper. Confirm `AnimeIdMappingService` and `TrailerPlayer DefaultLoadControl` remain the top retainers (as expected from the prior two commits).

- [ ] **Step 6: Commit nothing — this task is verification only**

If all on-device checks pass, the boundary enforcement fix is complete. Memory entry update happens in Task 7.

---

## Task 7: Update memory + plan tracking

**Files:**
- Create: `/Users/jneerdael/.claude/projects/-Users-jneerdael-Scripts-nexio/memory/project_boundary_non_downgrade_fix_2026_05_11.md`
- Modify: `/Users/jneerdael/.claude/projects/-Users-jneerdael-Scripts-nexio/memory/MEMORY.md`
- Modify: `/Users/jneerdael/.claude/projects/-Users-jneerdael-Scripts-nexio/memory/project_resolved_display_authority_regression.md` (mark fixed)

- [ ] **Step 1: Write the new memory entry**

```markdown
---
name: Boundary non-downgrade enforcement fix (2026-05-11)
description: ResolvedDisplaySurfaceRepository.publishResolvedItems now routes every per-itemKey transition through HomeRailProjectionReducer with existing=previous slots. Closes the 2026-05-09 first-paint-revert regression that survived through Phase 3.6.5 because the reducer was wired at the mapper seam (existing=null) and the repository boundary never called the reducer at all.
type: project
---

**Root cause:** `HomeRailProjectionReducer`'s `existing` arg was dead code at every production call site. `HomeResolvedDisplayMapper:117` passed null; `ResolvedDisplaySurfaceRepository.publishResolvedItems` (three overloads) never called the reducer at all — `mergeIncrementalItems` only preserved trailer field via `withPreservedTrailerState`, and the `replace=true` path wholesale-replaced. An incoming FIRST_PAINT slot overwrote a previously-published RESOLVED slot every time the producer re-emitted without a current overlay (cold-start, transient hydration map rebuild, refresh).

This is why Phase 3.6.5 + Phase 3.7 + every "typed authority is rendering source" claim was visibly false on device — the typed authority's content was being downgraded at the boundary, regardless of how clean the rendering code was.

**Fix:** New private `ResolvedDisplaySurfaceRepository.applyNonDowngradeMerge(incoming, existing)` helper invokes `HomeRailProjectionReducer.reduce(firstPaint=incoming.slots, overlay=null, existing=existing.slots, profile=null)` and re-projects flat fields (artwork, display, rating) via the new shared `ResolvedDisplaySlotProjection.kt` helpers. Both `mergeIncrementalItems` (replace=false additive merge) and the `replace=true` wholesale-replace path now route every per-itemKey transition through it.

**Why:** Plan A's non-downgrade contract was documented in the reducer's docstring but never wired at the boundary. **How to apply:** when adding new producers that publish into `ResolvedDisplaySurfaceRepository`, the boundary enforces non-downgrade regardless of the producer's view of state — producers can always emit FIRST_PAINT-rank items safely, and the boundary will preserve any higher-rank slots already present.

**Verification:**
- 7 new boundary regression tests (`ResolvedDisplaySurfaceRepositoryNonDowngradeTest`) cover both replace paths + per-slot rank mixing + reference stability + legacy `slots=null` passthrough.
- 5 projection helper unit tests (`ResolvedDisplaySlotProjectionTest`).
- On-device verification: Modern Home RPDB premium posters stable through 60s+ soak; no revert to TMDB stock posters.

**Commits:**
- `<sha1>` test(repo/boundary): failing TDD anchor
- `<sha2>` refactor(repo): extract ResolvedDisplayFieldSlots → flat-field projection helper
- `<sha3>` fix(repo): enforce non-downgrade at ResolvedDisplaySurfaceRepository boundary
- `<sha4>` refactor(home/mapper): use shared slots → flat-fields projection helper
- `<sha5>` docs(reducer): correct contract description — boundary is the enforcement site

**Implication for Plan A / Plan B status:** Plan A Tasks 7–10 ("reroute through the reducer") landed structurally but not semantically — the reducer was called but its rank-aware branch was dead. This fix closes that semantic gap. Plan B's remaining surface migrations (Surface 3 Screensaver, Surface 5 Detail) can now proceed knowing the typed authority's content is rank-protected.
```

Replace `<sha1>` … `<sha5>` with the actual commit SHAs from the corresponding tasks before saving.

- [ ] **Step 2: Add the index entry to MEMORY.md**

Add this line at the end of `MEMORY.md`:

```markdown
- [Boundary non-downgrade enforcement fix 2026-05-11](project_boundary_non_downgrade_fix_2026_05_11.md) — closes 2026-05-09 first-paint revert regression; reducer now invoked at ResolvedDisplaySurfaceRepository boundary with existing slots; Plan A Tasks 7-10 semantic gap closed
```

- [ ] **Step 3: Mark the old regression memory as resolved**

In `project_resolved_display_authority_regression.md`, add at the top (right after the frontmatter):

```markdown
**STATUS 2026-05-11: RESOLVED.** Root cause was a wiring gap at the
ResolvedDisplaySurfaceRepository boundary — the reducer's `existing` arg
was dead code at every production call site. Fixed by routing every
per-itemKey transition through `applyNonDowngradeMerge` at the boundary.
See `project_boundary_non_downgrade_fix_2026_05_11.md`.
```

- [ ] **Step 4: No commit needed — memory files are outside the repo**

Memory files are written directly. No `git add` / `git commit`.

---

## Self-Review Checklist (run after writing the plan, before handing off)

**Spec coverage:**

- [x] Problem statement: first-paint revert observed on device — covered by Tasks 1 (failing tests) + 3 (fix) + 6 (verification).
- [x] Root cause: reducer not wired at boundary — Task 3 wires it.
- [x] Both replace=false and replace=true paths affected — Task 3 Steps 3 + 4 cover both.
- [x] Single-overload publish also affected — Task 3 Step 5 confirms it routes through `mergeIncrementalItems`.
- [x] Mapper code duplication eliminated — Task 4.
- [x] Docstring contract correction — Task 5.
- [x] On-device verification — Task 6 with full smoke-test sequence per CLAUDE.md rule #8.
- [x] Memory updates — Task 7.

**Placeholder scan:** none — every step has actual code, actual test code, actual commands with expected output, and exact file paths.

**Type consistency:** `applyNonDowngradeMerge(incoming: ResolvedDisplayItem, existing: ResolvedDisplayItem?)` signature consistent across Task 3 Steps 2, 3, and 4. `ResolvedDisplayFieldSlots.toArtworkBundle()` / `.toResolvedDisplayFields(fallbackTitle, fallbackTomatoesRating)` / `.toRating()` consistent across Tasks 2 and 4. `applyNonDowngradeMergeForReplace(existing, incoming)` is distinct from `applyNonDowngradeMerge(incoming, existing)` and only used by the replace=true path — argument order intentionally matches `mergeIncrementalItems(existing, incoming)` for consistency with the existing helper next to it.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-11-boundary-non-downgrade-enforcement.md`. Two execution options:

**1. Subagent-Driven (recommended)** — Fresh subagent per task with two-stage review (spec compliance + code quality) after each.

**2. Inline Execution** — Execute the 7 tasks in this session using executing-plans, with checkpoints between tasks.

Which approach?
