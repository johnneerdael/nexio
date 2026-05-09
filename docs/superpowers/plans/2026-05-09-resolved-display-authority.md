# Resolved Display Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the rank-based display projection authority so first-paint can never overwrite hydrated metadata, premium artwork, or resolved ratings on Home.

**Architecture:** Introduce `DisplaySourceRank` + `ResolvedSlot<T>` per-field provenance, build a single `HomeRailProjectionReducer` that implements the spec's non-downgrade `choose()` rule, reroute the apply seam (`HomeHydrationOverlayApplier` + `HomeDisplayMetadata.applyTo`) and the catalog refresh inversion through the reducer, and fix the overlay observer's downgrade leak. UI consumption migration is a separate plan (`2026-05-XX-resolved-display-ui-consumption-migration.md`).

**Tech Stack:** Kotlin · Hilt · Coroutines/Flow · Compose · Mockk · JUnit4

**Spec source of truth:** the architecture document the user provided in this session ("Nexio Shared Resolution & Display Architecture"). Field-source rank, choose() rule, first-paint invariant, and mandatory tests come directly from it.

**Out of scope (lands in Plan B):** the ~72 UI/ViewModel sites that read `HomeViewModel.displayRows` / `fullRows` / `heroItems` (mutable `MetaPreview`) and need to be converted to consume `ResolvedDisplayItem` from `ResolvedDisplaySurfaceRepository`.

**Non-goals (must not regress):** artwork fetch chain (`ArtworkRouter` → `MetadataArtworkDecisionResolver` → `ArtworkAssetRepository` → `NexioArtworkFetcher`); apply-seam content-equality gate (already shipped at `HomeViewModelCatalogPipeline.kt:506-516` and `HomeHydrationCoordinator.kt`); destructive `poster=null` repair guarded by cache fallback (already shipped at `HomeCatalogSnapshotStore.kt`).

---

## File Structure

### New files

| File | Responsibility |
|---|---|
| `app/src/main/java/com/nexio/tv/domain/model/DisplaySourceRank.kt` | Enum `EMPTY < PLACEHOLDER < FIRST_PAINT < STALE_RESOLVED < RESOLVED < USER_PROFILE_OVERLAY` |
| `app/src/main/java/com/nexio/tv/domain/model/ResolvedSlot.kt` | Per-field generic value+rank+provider+role+timestamps+trace |
| `app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplayFieldSlots.kt` | Bag of `ResolvedSlot<T>` for each display field (title/poster/rating/etc.) |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeRailProjectionReducer.kt` | The single reducer: `project(firstPaint, overlay, existing, profile) → ResolvedDisplayItem` |
| `app/src/main/java/com/nexio/tv/ui/screens/home/SlotConversions.kt` | `MetaPreview.toFirstPaintSlots()` and `HydratedHomeOverlay.toResolvedSlots()` extension helpers |
| `app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailProjectionReducerTest.kt` | Reducer unit tests (non-downgrade core) |
| `app/src/test/java/com/nexio/tv/ui/screens/home/HomeFirstPaintInvariantTest.kt` | Spec mandatory tests for first-paint invariant |
| `app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailProjectionPremiumTest.kt` | Spec mandatory premium-poster tests |
| `app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailProjectionRatingTest.kt` | Spec mandatory rating tests |
| `app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailProjectionAliasTest.kt` | Spec mandatory overlay-alias tests |

### Modified files

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt:9-26` | Add nullable `slots: ResolvedDisplayFieldSlots?` field; default null for backwards compatibility during migration |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt:32-100` | Route `toResolvedDisplayItem()` through `HomeRailProjectionReducer.project(...)`; populate `slots` |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt:11-25` | `CatalogRow.applyHydratedHomeOverlays(...)` projects via reducer and downgrades to MetaPreview only at the boundary |
| `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt:81-115,140-166` | Mark `applyTo` and `mergeFallback` as `@Deprecated` once reducer takes over; preserve impl until callers migrate |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt:137-170,474-482` | Replace `mergePersistedHomeDisplayMetadata` with a reducer call; eliminate the inversion |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:688-706` | `observeHydratedHomeOverlaysForRows`: preserve prior overlays as STALE_RESOLVED rank instead of clearing |
| `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:1140-1165` | `applyRatingResolverSelection`: fall back to `primary.fields[REMOTE_IDS]["imdb"]` when `previewStableIds.imdb` is blank |
| `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt` | Add `emitHomeDisplayProjection(...)` event |

---

## Task 1: Add `DisplaySourceRank` enum

**Files:**
- Create: `app/src/main/java/com/nexio/tv/domain/model/DisplaySourceRank.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/domain/model/DisplaySourceRankTest.kt`:

```kotlin
package com.nexio.tv.domain.model

import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySourceRankTest {
    @Test
    fun `rank ordering matches spec`() {
        assertTrue(DisplaySourceRank.EMPTY < DisplaySourceRank.PLACEHOLDER)
        assertTrue(DisplaySourceRank.PLACEHOLDER < DisplaySourceRank.FIRST_PAINT)
        assertTrue(DisplaySourceRank.FIRST_PAINT < DisplaySourceRank.STALE_RESOLVED)
        assertTrue(DisplaySourceRank.STALE_RESOLVED < DisplaySourceRank.RESOLVED)
        assertTrue(DisplaySourceRank.RESOLVED < DisplaySourceRank.USER_PROFILE_OVERLAY)
    }
}
```

- [ ] **Step 2: Run test — verify it fails (compile error)**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.DisplaySourceRankTest`
Expected: FAIL — `Unresolved reference: DisplaySourceRank`.

- [ ] **Step 3: Create the enum**

```kotlin
package com.nexio.tv.domain.model

/**
 * Per-field source rank used by [HomeRailProjectionReducer] to enforce the
 * non-downgrade rule: first-paint may only initialize a field that has no
 * higher-ranked source. Ordering matches the spec: a higher ordinal beats a
 * lower one.
 */
enum class DisplaySourceRank {
    EMPTY,
    PLACEHOLDER,
    FIRST_PAINT,
    STALE_RESOLVED,
    RESOLVED,
    USER_PROFILE_OVERLAY
}
```

- [ ] **Step 4: Run test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.DisplaySourceRankTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/DisplaySourceRank.kt \
        app/src/test/java/com/nexio/tv/domain/model/DisplaySourceRankTest.kt
git commit -m "feat: add DisplaySourceRank for non-downgrade reducer"
```

---

## Task 2: Add `ResolvedSlot<T>` type

**Files:**
- Create: `app/src/main/java/com/nexio/tv/domain/model/ResolvedSlot.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/domain/model/ResolvedSlotTest.kt`:

```kotlin
package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvedSlotTest {
    @Test
    fun `chooseHigherRank prefers RESOLVED over FIRST_PAINT`() {
        val resolved = ResolvedSlot(
            value = "resolved-poster",
            rank = DisplaySourceRank.RESOLVED,
            provider = "TVDB",
            role = "PRIMARY",
            updatedAtMs = 100L,
            expiresAtMs = null,
            trace = listOf("hydration:tvdb")
        )
        val firstPaint = ResolvedSlot(
            value = "first-paint-poster",
            rank = DisplaySourceRank.FIRST_PAINT,
            provider = "TRAKT",
            role = "RAIL_PREVIEW",
            updatedAtMs = 200L,
            expiresAtMs = null,
            trace = listOf("rail:trakt")
        )
        assertEquals(resolved, ResolvedSlot.chooseHigherRank(resolved, firstPaint))
        assertEquals(resolved, ResolvedSlot.chooseHigherRank(firstPaint, resolved))
    }

    @Test
    fun `chooseHigherRank prefers non-null when other side is null`() {
        val firstPaint = ResolvedSlot(
            value = "first-paint",
            rank = DisplaySourceRank.FIRST_PAINT,
            provider = "TMDB",
            role = "RAIL_PREVIEW",
            updatedAtMs = 100L,
            expiresAtMs = null,
            trace = emptyList()
        )
        val empty = ResolvedSlot<String>(
            value = null,
            rank = DisplaySourceRank.EMPTY,
            provider = null,
            role = null,
            updatedAtMs = 100L,
            expiresAtMs = null,
            trace = emptyList()
        )
        assertEquals(firstPaint, ResolvedSlot.chooseHigherRank(firstPaint, empty))
    }

    @Test
    fun `chooseHigherRank with null value at higher rank still beats lower rank with value`() {
        // Spec rule: a higher rank ALWAYS wins, even if its value is null.
        // Null at RESOLVED means "this provider explicitly resolved no value" — must not
        // be silently replaced by FIRST_PAINT.
        val resolvedNull = ResolvedSlot<String>(
            value = null,
            rank = DisplaySourceRank.RESOLVED,
            provider = "TVDB",
            role = "PRIMARY",
            updatedAtMs = 200L,
            expiresAtMs = null,
            trace = listOf("hydration:tvdb:no_logo")
        )
        val firstPaint = ResolvedSlot(
            value = "first-paint-logo",
            rank = DisplaySourceRank.FIRST_PAINT,
            provider = "TRAKT",
            role = "RAIL_PREVIEW",
            updatedAtMs = 100L,
            expiresAtMs = null,
            trace = emptyList()
        )
        assertEquals(resolvedNull, ResolvedSlot.chooseHigherRank(resolvedNull, firstPaint))
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.ResolvedSlotTest`
Expected: FAIL — `Unresolved reference: ResolvedSlot`.

- [ ] **Step 3: Create the type**

```kotlin
package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Per-field display value with provenance. Used by [HomeRailProjectionReducer] to
 * enforce non-downgrade selection (a higher [rank] always beats a lower rank,
 * even when the higher-rank slot's [value] is null — null at RESOLVED means the
 * authoritative source explicitly produced no value, which must not be papered
 * over by a lower-rank fallback).
 */
@Immutable
data class ResolvedSlot<T>(
    val value: T?,
    val rank: DisplaySourceRank,
    val provider: String?,
    val role: String?,
    val updatedAtMs: Long,
    val expiresAtMs: Long?,
    val trace: List<String>
) {
    companion object {
        fun <T> chooseHigherRank(a: ResolvedSlot<T>, b: ResolvedSlot<T>): ResolvedSlot<T> =
            if (a.rank.ordinal >= b.rank.ordinal) a else b

        fun <T> empty(nowMs: Long): ResolvedSlot<T> =
            ResolvedSlot(
                value = null,
                rank = DisplaySourceRank.EMPTY,
                provider = null,
                role = null,
                updatedAtMs = nowMs,
                expiresAtMs = null,
                trace = emptyList()
            )
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.domain.model.ResolvedSlotTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/ResolvedSlot.kt \
        app/src/test/java/com/nexio/tv/domain/model/ResolvedSlotTest.kt
git commit -m "feat: add ResolvedSlot<T> with chooseHigherRank rule"
```

---

## Task 3: Add `ResolvedDisplayFieldSlots` bag

**Files:**
- Create: `app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplayFieldSlots.kt`

- [ ] **Step 1: Create the bag type (no test yet — purely structural; tested by reducer in Task 7)**

```kotlin
package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef

/**
 * Per-field source-ranked slots for a Home row. Each slot carries its own
 * provenance so [HomeRailProjectionReducer] can apply the spec's non-downgrade
 * rule on every field independently. Artwork is split per type — poster /
 * backdrop / logo / thumbnail — never merged into one "best image".
 */
@Immutable
data class ResolvedDisplayFieldSlots(
    val title: ResolvedSlot<String>,
    val originalTitle: ResolvedSlot<String>,
    val overview: ResolvedSlot<String>,
    val genres: ResolvedSlot<List<String>>,
    val releaseInfo: ResolvedSlot<String>,
    val runtime: ResolvedSlot<String>,
    val rating: ResolvedSlot<TitleRating>,
    val poster: ResolvedSlot<ArtworkDisplayRef>,
    val backdrop: ResolvedSlot<ArtworkDisplayRef>,
    val logo: ResolvedSlot<ArtworkDisplayRef>,
    val thumbnail: ResolvedSlot<ArtworkDisplayRef>,
    val posterProviderTag: ResolvedSlot<String>
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplayFieldSlots.kt
git commit -m "feat: add ResolvedDisplayFieldSlots bag"
```

---

## Task 4: Extend `ResolvedDisplayItem` with optional `slots`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt:9-26`

- [ ] **Step 1: Add nullable slots field (default null preserves call-site compatibility)**

Replace the `data class ResolvedDisplayItem` block (lines 9-26) with:

```kotlin
@Immutable
data class ResolvedDisplayItem(
    val itemKey: String,
    val contentId: String,
    val parentId: String,
    val itemType: ContentType,
    val mediaKind: MetadataMediaKind,
    val canonicalProvider: String?,
    val canonicalId: String?,
    val imdbId: String?,
    val stableIds: ProviderIds,
    val display: ResolvedDisplayFields,
    val artwork: ArtworkBundle,
    val rating: TitleRating?,
    val trailer: TrailerDisplayState,
    val hydrationState: HydrationState,
    val sourceTrace: List<HydratedHomeFieldTrace>,
    val updatedAtMs: Long,
    /**
     * Per-field rank-aware slots. Populated by [HomeRailProjectionReducer]; null
     * when the item was constructed by legacy paths that haven't migrated yet.
     * Consumers must tolerate null and fall back to the flat fields above.
     */
    val slots: ResolvedDisplayFieldSlots? = null
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL (default arg means existing call sites still compile).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt
git commit -m "feat: add optional slots field to ResolvedDisplayItem"
```

---

## Task 5: `MetaPreview.toFirstPaintSlots()` helper

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/SlotConversions.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/SlotConversionsTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SlotConversionsTest {
    @Test
    fun `MetaPreview converts to FIRST_PAINT slots with rail provenance`() {
        val item = MetaPreview(
            id = "tt0137523",
            type = ContentType.MOVIE,
            apiType = "movie",
            name = "Fight Club",
            poster = "https://image.tmdb.org/poster.jpg",
            background = "https://image.tmdb.org/backdrop.jpg",
            logo = null,
            description = "A description",
            genres = listOf("Drama"),
            releaseInfo = "1999",
            runtime = "139 min",
            imdbRating = 8.8f,
            ratingSource = null,
            tomatoesRating = null,
            trailerYtIds = emptyList(),
            language = "en",
            posterProviderTag = null,
            posterShape = null,
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceProvider = null,
            firstPaintStableIds = ProviderIds(imdb = "tt0137523"),
            firstPaintRailSource = null,
            firstPaintSourceItemId = "tt0137523",
            artwork = null
        )

        val slots = item.toFirstPaintSlots(nowMs = 1_000L)

        assertEquals(DisplaySourceRank.FIRST_PAINT, slots.title.rank)
        assertEquals("Fight Club", slots.title.value)
        assertEquals(DisplaySourceRank.FIRST_PAINT, slots.poster.rank)
        assertNotNull(slots.poster.value)
        // Backdrop present, logo absent -> EMPTY rank
        assertEquals(DisplaySourceRank.FIRST_PAINT, slots.backdrop.rank)
        assertEquals(DisplaySourceRank.EMPTY, slots.logo.rank)
        // Rating present and valid
        assertEquals(DisplaySourceRank.FIRST_PAINT, slots.rating.rank)
        assertEquals(8.8, slots.rating.value!!.value, 0.001)
    }

    @Test
    fun `MetaPreview with null fields yields EMPTY slots`() {
        val item = MetaPreview(
            id = "x:1",
            type = ContentType.MOVIE,
            apiType = "movie",
            name = null,
            poster = null,
            background = null,
            logo = null,
            description = null,
            genres = emptyList(),
            releaseInfo = null,
            runtime = null,
            imdbRating = null,
            ratingSource = null,
            tomatoesRating = null,
            trailerYtIds = emptyList(),
            language = null,
            posterProviderTag = null,
            posterShape = null,
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceProvider = null,
            firstPaintStableIds = ProviderIds(),
            firstPaintRailSource = null,
            firstPaintSourceItemId = "x:1",
            artwork = null
        )

        val slots = item.toFirstPaintSlots(nowMs = 1_000L)

        assertEquals(DisplaySourceRank.EMPTY, slots.title.rank)
        assertEquals(DisplaySourceRank.EMPTY, slots.poster.rank)
        assertEquals(DisplaySourceRank.EMPTY, slots.rating.rank)
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.SlotConversionsTest`
Expected: FAIL — `Unresolved reference: toFirstPaintSlots`.

- [ ] **Step 3: Create the helper**

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.RatingValueValidator
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource

private const val ROLE_RAIL_PREVIEW = "RAIL_PREVIEW"
private const val ROLE_HYDRATION_RESOLVED = "HYDRATION_RESOLVED"

/**
 * Projects a rail-emitted [MetaPreview] into rank-tagged slots. The rail row is
 * always FIRST_PAINT — it is INPUT to the reducer, never authority. Any field
 * that is null/blank/empty becomes an EMPTY slot so a higher-rank source can
 * fill it; conversely a non-null FIRST_PAINT slot still loses to RESOLVED.
 */
fun MetaPreview.toFirstPaintSlots(nowMs: Long): ResolvedDisplayFieldSlots {
    val railProvider = firstPaintRailSource?.name ?: firstPaintSourceProvider?.name
    return ResolvedDisplayFieldSlots(
        title = stringSlot(name, railProvider, ROLE_RAIL_PREVIEW, nowMs),
        originalTitle = ResolvedSlot.empty(nowMs),
        overview = stringSlot(description, railProvider, ROLE_RAIL_PREVIEW, nowMs),
        genres = listSlot(genres, railProvider, ROLE_RAIL_PREVIEW, nowMs),
        releaseInfo = stringSlot(releaseInfo, railProvider, ROLE_RAIL_PREVIEW, nowMs),
        runtime = stringSlot(runtime, railProvider, ROLE_RAIL_PREVIEW, nowMs),
        rating = ratingSlot(imdbRating, ratingSource, railProvider, ROLE_RAIL_PREVIEW, nowMs),
        poster = artworkSlot(poster, ArtworkType.POSTER, railProvider, ROLE_RAIL_PREVIEW, nowMs),
        backdrop = artworkSlot(background, ArtworkType.BACKDROP, railProvider, ROLE_RAIL_PREVIEW, nowMs),
        logo = artworkSlot(logo, ArtworkType.LOGO, railProvider, ROLE_RAIL_PREVIEW, nowMs),
        thumbnail = artworkSlot(
            artwork?.thumbnail?.let { com.nexio.tv.core.artwork.toLegacyArtworkString(it) },
            ArtworkType.THUMBNAIL, railProvider, ROLE_RAIL_PREVIEW, nowMs
        ),
        posterProviderTag = stringSlot(posterProviderTag, railProvider, ROLE_RAIL_PREVIEW, nowMs)
    )
}

/**
 * Projects a [HydratedHomeOverlay] into rank-tagged slots at RESOLVED or
 * STALE_RESOLVED rank depending on freshness. Field provenance comes from the
 * overlay's per-field trace where available, falling back to the canonical
 * provider name.
 */
fun HydratedHomeOverlay.toResolvedSlots(nowMs: Long, isStale: Boolean): ResolvedDisplayFieldSlots {
    val rank = if (isStale) DisplaySourceRank.STALE_RESOLVED else DisplaySourceRank.RESOLVED
    val provider = canonicalProvider.name
    fun providerFor(field: String): String =
        fieldTrace.firstOrNull { it.field.equals(field, ignoreCase = true) }?.selectedProvider
            ?: provider
    fun roleFor(field: String): String =
        fieldTrace.firstOrNull { it.field.equals(field, ignoreCase = true) }?.sourceRole
            ?: ROLE_HYDRATION_RESOLVED
    return ResolvedDisplayFieldSlots(
        title = stringSlot(fields.title, providerFor("title"), roleFor("title"), updatedAtMs, rank),
        originalTitle = ResolvedSlot.empty(nowMs),
        overview = stringSlot(fields.description, providerFor("description"), roleFor("description"), updatedAtMs, rank),
        genres = listSlot(fields.genres, providerFor("genres"), roleFor("genres"), updatedAtMs, rank),
        releaseInfo = stringSlot(fields.releaseInfo, providerFor("releaseInfo"), roleFor("releaseInfo"), updatedAtMs, rank),
        runtime = stringSlot(fields.runtime, providerFor("runtime"), roleFor("runtime"), updatedAtMs, rank),
        rating = ratingSlot(fields.imdbRating, fields.ratingSource, providerFor("rating"), roleFor("rating"), updatedAtMs, rank),
        poster = artworkSlotFromBundle(fields.artwork?.poster, fields.poster, ArtworkType.POSTER, providerFor("poster"), roleFor("poster"), updatedAtMs, rank),
        backdrop = artworkSlotFromBundle(fields.artwork?.backdrop, fields.backdrop, ArtworkType.BACKDROP, providerFor("backdrop"), roleFor("backdrop"), updatedAtMs, rank),
        logo = artworkSlotFromBundle(fields.artwork?.logo, fields.logo, ArtworkType.LOGO, providerFor("logo"), roleFor("logo"), updatedAtMs, rank),
        thumbnail = artworkSlotFromBundle(fields.artwork?.thumbnail, fields.thumbnail, ArtworkType.THUMBNAIL, providerFor("thumbnail"), roleFor("thumbnail"), updatedAtMs, rank),
        posterProviderTag = stringSlot(fields.posterProviderTag, providerFor("posterProviderTag"), roleFor("posterProviderTag"), updatedAtMs, rank)
    )
}

private fun stringSlot(
    value: String?,
    provider: String?,
    role: String?,
    nowMs: Long,
    rank: DisplaySourceRank = DisplaySourceRank.FIRST_PAINT
): ResolvedSlot<String> {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
    return ResolvedSlot(
        value = trimmed,
        rank = if (trimmed == null) DisplaySourceRank.EMPTY else rank,
        provider = provider,
        role = role,
        updatedAtMs = nowMs,
        expiresAtMs = null,
        trace = emptyList()
    )
}

private fun listSlot(
    value: List<String>,
    provider: String?,
    role: String?,
    nowMs: Long,
    rank: DisplaySourceRank = DisplaySourceRank.FIRST_PAINT
): ResolvedSlot<List<String>> {
    val nonEmpty = value.takeIf { it.isNotEmpty() }
    return ResolvedSlot(
        value = nonEmpty,
        rank = if (nonEmpty == null) DisplaySourceRank.EMPTY else rank,
        provider = provider,
        role = role,
        updatedAtMs = nowMs,
        expiresAtMs = null,
        trace = emptyList()
    )
}

private fun ratingSlot(
    rating: Float?,
    source: TitleRatingSource?,
    provider: String?,
    role: String?,
    nowMs: Long,
    rank: DisplaySourceRank = DisplaySourceRank.FIRST_PAINT
): ResolvedSlot<TitleRating> {
    val sanitized = RatingValueValidator.sanitizeTitleRating(rating)
    val tr = sanitized?.let { TitleRating(it.toDouble(), source ?: TitleRatingSource.IMDB) }
    return ResolvedSlot(
        value = tr,
        rank = if (tr == null) DisplaySourceRank.EMPTY else rank,
        provider = provider,
        role = role,
        updatedAtMs = nowMs,
        expiresAtMs = null,
        trace = emptyList()
    )
}

private fun artworkSlot(
    legacy: String?,
    type: ArtworkType,
    provider: String?,
    role: String?,
    nowMs: Long,
    rank: DisplaySourceRank = DisplaySourceRank.FIRST_PAINT
): ResolvedSlot<ArtworkDisplayRef> {
    val trimmed = legacy?.trim()?.takeIf { it.isNotEmpty() }
    val ref: ArtworkDisplayRef? = trimmed?.let {
        ArtworkDisplayRef.LegacyString(value = it, imageType = type, trace = ArtworkTrace.empty())
    }
    return ResolvedSlot(
        value = ref,
        rank = if (ref == null) DisplaySourceRank.EMPTY else rank,
        provider = provider,
        role = role,
        updatedAtMs = nowMs,
        expiresAtMs = null,
        trace = emptyList()
    )
}

private fun artworkSlotFromBundle(
    structured: ArtworkDisplayRef?,
    legacyFallback: String?,
    type: ArtworkType,
    provider: String?,
    role: String?,
    nowMs: Long,
    rank: DisplaySourceRank
): ResolvedSlot<ArtworkDisplayRef> {
    val ref: ArtworkDisplayRef? = structured ?: legacyFallback
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { ArtworkDisplayRef.LegacyString(it, type, ArtworkTrace.empty()) }
    return ResolvedSlot(
        value = ref,
        rank = if (ref == null) DisplaySourceRank.EMPTY else rank,
        provider = provider,
        role = role,
        updatedAtMs = nowMs,
        expiresAtMs = null,
        trace = emptyList()
    )
}
```

- [ ] **Step 4: Run test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.SlotConversionsTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/SlotConversions.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/SlotConversionsTest.kt
git commit -m "feat: add MetaPreview/HydratedHomeOverlay -> slot conversions"
```

---

## Task 6: `HomeRailProjectionReducer` — the single non-downgrade gate

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeRailProjectionReducer.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailProjectionReducerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRailProjectionReducerTest {
    @Test
    fun `RESOLVED poster beats FIRST_PAINT poster`() {
        val firstPaint = slotsWithPoster(rank = DisplaySourceRank.FIRST_PAINT, value = "https://addon/raw.jpg")
        val resolved = slotsWithPoster(rank = DisplaySourceRank.RESOLVED, value = "nexio-artwork://decision/abc")
        val merged = HomeRailProjectionReducer.reduce(firstPaint, resolved, existing = null, profile = null)
        assertEquals("nexio-artwork://decision/abc", (merged.poster.value as ArtworkDisplayRef.LegacyString).value)
        assertEquals(DisplaySourceRank.RESOLVED, merged.poster.rank)
    }

    @Test
    fun `STALE_RESOLVED beats FIRST_PAINT but loses to RESOLVED`() {
        val firstPaint = slotsWithPoster(DisplaySourceRank.FIRST_PAINT, "https://addon/raw.jpg")
        val stale = slotsWithPoster(DisplaySourceRank.STALE_RESOLVED, "nexio-artwork://decision/stale")
        val resolved = slotsWithPoster(DisplaySourceRank.RESOLVED, "nexio-artwork://decision/fresh")

        val mergedStale = HomeRailProjectionReducer.reduce(firstPaint, overlay = null, existing = stale, profile = null)
        assertEquals("nexio-artwork://decision/stale", (mergedStale.poster.value as ArtworkDisplayRef.LegacyString).value)

        val mergedFresh = HomeRailProjectionReducer.reduce(firstPaint, overlay = resolved, existing = stale, profile = null)
        assertEquals("nexio-artwork://decision/fresh", (mergedFresh.poster.value as ArtworkDisplayRef.LegacyString).value)
    }

    @Test
    fun `RESOLVED null logo is preserved against FIRST_PAINT logo`() {
        // Spec: "First paint may not replace TVDB logo/backdrop/poster with no value."
        // Inverse: a RESOLVED slot whose authoritative source produced no value
        // (e.g. Kitsu has no clearlogo) must not be overwritten by FIRST_PAINT.
        val firstPaint = slotsWithLogo(DisplaySourceRank.FIRST_PAINT, "first-paint-logo.png")
        val resolvedNull = slotsWithLogo(DisplaySourceRank.RESOLVED, value = null)
        val merged = HomeRailProjectionReducer.reduce(firstPaint, overlay = resolvedNull, existing = null, profile = null)
        assertEquals(null, merged.logo.value)
        assertEquals(DisplaySourceRank.RESOLVED, merged.logo.rank)
    }

    @Test
    fun `FIRST_PAINT only fills empty existing slots`() {
        // Spec: "First paint may fill only fields that remain empty."
        val firstPaint = slotsWithPoster(DisplaySourceRank.FIRST_PAINT, "https://addon/raw.jpg")
        val emptySlots = ResolvedDisplayFieldSlots(
            title = ResolvedSlot.empty(0L),
            originalTitle = ResolvedSlot.empty(0L),
            overview = ResolvedSlot.empty(0L),
            genres = ResolvedSlot.empty(0L),
            releaseInfo = ResolvedSlot.empty(0L),
            runtime = ResolvedSlot.empty(0L),
            rating = ResolvedSlot.empty(0L),
            poster = ResolvedSlot.empty(0L),
            backdrop = ResolvedSlot.empty(0L),
            logo = ResolvedSlot.empty(0L),
            thumbnail = ResolvedSlot.empty(0L),
            posterProviderTag = ResolvedSlot.empty(0L)
        )
        val merged = HomeRailProjectionReducer.reduce(firstPaint, overlay = null, existing = emptySlots, profile = null)
        assertEquals(DisplaySourceRank.FIRST_PAINT, merged.poster.rank)
    }

    @Test
    fun `USER_PROFILE_OVERLAY beats RESOLVED`() {
        val resolved = slotsWithPoster(DisplaySourceRank.RESOLVED, "nexio-artwork://decision/abc")
        val profile = slotsWithPoster(DisplaySourceRank.USER_PROFILE_OVERLAY, "nexio-artwork://decision/user-pinned")
        val merged = HomeRailProjectionReducer.reduce(firstPaint = resolved, overlay = null, existing = null, profile = profile)
        assertEquals("nexio-artwork://decision/user-pinned", (merged.poster.value as ArtworkDisplayRef.LegacyString).value)
        assertEquals(DisplaySourceRank.USER_PROFILE_OVERLAY, merged.poster.rank)
    }

    private fun slotsWithPoster(rank: DisplaySourceRank, value: String?): ResolvedDisplayFieldSlots {
        val ref: ArtworkDisplayRef? = value?.let {
            ArtworkDisplayRef.LegacyString(it, ArtworkType.POSTER, ArtworkTrace.empty())
        }
        val r = if (value == null && rank != DisplaySourceRank.RESOLVED && rank != DisplaySourceRank.STALE_RESOLVED) DisplaySourceRank.EMPTY else rank
        return ResolvedDisplayFieldSlots(
            title = ResolvedSlot.empty(0L),
            originalTitle = ResolvedSlot.empty(0L),
            overview = ResolvedSlot.empty(0L),
            genres = ResolvedSlot.empty(0L),
            releaseInfo = ResolvedSlot.empty(0L),
            runtime = ResolvedSlot.empty(0L),
            rating = ResolvedSlot.empty(0L),
            poster = ResolvedSlot(ref, r, "TEST", "TEST", 0L, null, emptyList()),
            backdrop = ResolvedSlot.empty(0L),
            logo = ResolvedSlot.empty(0L),
            thumbnail = ResolvedSlot.empty(0L),
            posterProviderTag = ResolvedSlot.empty(0L)
        )
    }

    private fun slotsWithLogo(rank: DisplaySourceRank, value: String?): ResolvedDisplayFieldSlots {
        val ref: ArtworkDisplayRef? = value?.let {
            ArtworkDisplayRef.LegacyString(it, ArtworkType.LOGO, ArtworkTrace.empty())
        }
        return ResolvedDisplayFieldSlots(
            title = ResolvedSlot.empty(0L),
            originalTitle = ResolvedSlot.empty(0L),
            overview = ResolvedSlot.empty(0L),
            genres = ResolvedSlot.empty(0L),
            releaseInfo = ResolvedSlot.empty(0L),
            runtime = ResolvedSlot.empty(0L),
            rating = ResolvedSlot.empty(0L),
            poster = ResolvedSlot.empty(0L),
            backdrop = ResolvedSlot.empty(0L),
            logo = ResolvedSlot(ref, rank, "TEST", "TEST", 0L, null, emptyList()),
            thumbnail = ResolvedSlot.empty(0L),
            posterProviderTag = ResolvedSlot.empty(0L)
        )
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeRailProjectionReducerTest`
Expected: FAIL — `Unresolved reference: HomeRailProjectionReducer`.

- [ ] **Step 3: Create the reducer**

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.TitleRating

/**
 * The single non-downgrade reducer for Home rail projection. Implements the
 * spec's `choose()` rule: for every field, the highest-ranked slot among
 * (firstPaint, overlay, existing, profile) wins. A higher rank ALWAYS beats a
 * lower rank, even when the higher-rank slot's value is null — null at RESOLVED
 * means "the authoritative source explicitly produced no value", which must
 * not be papered over by FIRST_PAINT.
 *
 * This is the ONLY place that may merge multiple display inputs into final
 * row state. Every other site (apply seam, refresh coordinator, hydration
 * mapper) must funnel through here.
 */
internal object HomeRailProjectionReducer {
    fun reduce(
        firstPaint: ResolvedDisplayFieldSlots,
        overlay: ResolvedDisplayFieldSlots?,
        existing: ResolvedDisplayFieldSlots?,
        profile: ResolvedDisplayFieldSlots?
    ): ResolvedDisplayFieldSlots = ResolvedDisplayFieldSlots(
        title = chooseString(firstPaint.title, overlay?.title, existing?.title, profile?.title),
        originalTitle = chooseString(firstPaint.originalTitle, overlay?.originalTitle, existing?.originalTitle, profile?.originalTitle),
        overview = chooseString(firstPaint.overview, overlay?.overview, existing?.overview, profile?.overview),
        genres = chooseList(firstPaint.genres, overlay?.genres, existing?.genres, profile?.genres),
        releaseInfo = chooseString(firstPaint.releaseInfo, overlay?.releaseInfo, existing?.releaseInfo, profile?.releaseInfo),
        runtime = chooseString(firstPaint.runtime, overlay?.runtime, existing?.runtime, profile?.runtime),
        rating = chooseRating(firstPaint.rating, overlay?.rating, existing?.rating, profile?.rating),
        poster = chooseArtwork(firstPaint.poster, overlay?.poster, existing?.poster, profile?.poster),
        backdrop = chooseArtwork(firstPaint.backdrop, overlay?.backdrop, existing?.backdrop, profile?.backdrop),
        logo = chooseArtwork(firstPaint.logo, overlay?.logo, existing?.logo, profile?.logo),
        thumbnail = chooseArtwork(firstPaint.thumbnail, overlay?.thumbnail, existing?.thumbnail, profile?.thumbnail),
        posterProviderTag = chooseString(firstPaint.posterProviderTag, overlay?.posterProviderTag, existing?.posterProviderTag, profile?.posterProviderTag)
    )

    private fun chooseString(vararg slots: ResolvedSlot<String>?): ResolvedSlot<String> =
        slots.filterNotNull().reduce(ResolvedSlot.Companion::chooseHigherRank)

    private fun chooseList(vararg slots: ResolvedSlot<List<String>>?): ResolvedSlot<List<String>> =
        slots.filterNotNull().reduce(ResolvedSlot.Companion::chooseHigherRank)

    private fun chooseRating(vararg slots: ResolvedSlot<TitleRating>?): ResolvedSlot<TitleRating> =
        slots.filterNotNull().reduce(ResolvedSlot.Companion::chooseHigherRank)

    private fun chooseArtwork(vararg slots: ResolvedSlot<ArtworkDisplayRef>?): ResolvedSlot<ArtworkDisplayRef> =
        slots.filterNotNull().reduce(ResolvedSlot.Companion::chooseHigherRank)
}
```

- [ ] **Step 4: Run test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeRailProjectionReducerTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeRailProjectionReducer.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailProjectionReducerTest.kt
git commit -m "feat: HomeRailProjectionReducer with non-downgrade choose rule"
```

---

## Task 7: Reroute `HomeResolvedDisplayMapper` through the reducer

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt:42-100`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperReducerTest.kt`:

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
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HomeResolvedDisplayMapperReducerTest {
    @Test
    fun `mapper output populates slots and resolved poster wins over rail addon URL`() {
        val rawAddonItem = MetaPreview(
            id = "tmdb:550",
            type = ContentType.MOVIE,
            apiType = "movie",
            name = "Fight Club (rail)",
            poster = "https://image.tmdb.org/raw.jpg",
            background = null,
            logo = null,
            description = null,
            genres = emptyList(),
            releaseInfo = null,
            runtime = null,
            imdbRating = null,
            ratingSource = null,
            tomatoesRating = null,
            trailerYtIds = emptyList(),
            language = null,
            posterProviderTag = null,
            posterShape = null,
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceProvider = null,
            firstPaintStableIds = ProviderIds(tmdb = "550"),
            firstPaintRailSource = null,
            firstPaintSourceItemId = "tmdb:550",
            artwork = null
        )
        val itemKey = homeDisplayItemKey(rawAddonItem.apiType, rawAddonItem.id)
        val resolvedPoster = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:poster:imdb:tt0137523"),
            assetKey = null,
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty(),
            displayHints = ArtworkDisplayHints()
        )
        val overlayFields = HomeDisplayMetadata(
            title = "Fight Club (resolved)",
            artwork = ArtworkBundle(poster = resolvedPoster)
        )
        val overlay = HydratedHomeOverlay(
            overlayKey = hydratedHomeOverlayKey(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "550",
                contentType = ContentType.MOVIE,
                languageTag = "en-US",
                policyVersion = 1
            ),
            itemKey = itemKey,
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = "tt0137523",
            contentType = ContentType.MOVIE,
            languageTag = "en-US",
            policyVersion = 1,
            fields = overlayFields,
            fieldTrace = emptyList(),
            displayHash = overlayFields.hydratedHomeDisplayHash(),
            updatedAtMs = 1_000L,
            staleAtMs = 2_000L,
            expiresAtMs = 3_000L,
            state = HomeItemHydrationState.CANONICAL_READY
        )

        val items = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(CatalogRow(catalogId = "tmdb:popular", type = ContentType.MOVIE, title = "Popular", items = listOf(rawAddonItem))),
            overlaysByItemKey = mapOf(itemKey to overlay)
        )

        val resolved = items.single()
        assertNotNull(resolved.slots)
        assertEquals(DisplaySourceRank.RESOLVED, resolved.slots!!.poster.rank)
        // Title: overlay wins
        assertEquals("Fight Club (resolved)", resolved.slots.title.value)
        // Poster: durable decision URI from overlay survives — never replaced by raw URL
        val posterRef = resolved.slots.poster.value
        assertNotNull(posterRef)
        assertEquals(true, posterRef is ArtworkDisplayRef.RuntimeAsset)
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperReducerTest`
Expected: FAIL — `slots` is null.

- [ ] **Step 3: Update the mapper to project via the reducer**

In `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`, replace the body of the private `MetaPreview.toResolvedDisplayItem(...)` (lines 42-100) with:

```kotlin
    private fun MetaPreview.toResolvedDisplayItem(
        overlaysByItemKey: Map<String, HydratedHomeOverlay>,
        nowMs: Long,
        resolveTrailer: ((TrailerResolveRequest) -> TrailerResolution)?
    ): ResolvedDisplayItem {
        val itemKey = homeDisplayItemKey(apiType, id)
        val overlay = overlayFromMap(overlaysByItemKey)

        val firstPaintSlots = toFirstPaintSlots(nowMs)
        val overlaySlots = overlay?.toResolvedSlots(nowMs, isStale = overlay.isStale(nowMs))
        val mergedSlots = HomeRailProjectionReducer.reduce(
            firstPaint = firstPaintSlots,
            overlay = overlaySlots,
            existing = null,
            profile = null
        )

        val stableIds = firstPaintStableIds.withOverlayStableId(overlay)
        val title = mergedSlots.title.value ?: name
        val year = mergedSlots.releaseInfo.value?.take(4)?.takeIf { it.length == 4 }
        val trailerState = resolveHomeTrailerDisplayState(
            itemKey = itemKey,
            title = title ?: id,
            year = year,
            stableIds = stableIds,
            fallbackYtIds = trailerYtIds,
            apiType = apiType,
            contentId = id,
            resolveTrailer = resolveTrailer
        )

        return ResolvedDisplayItem(
            itemKey = itemKey,
            contentId = id,
            parentId = id,
            itemType = type,
            mediaKind = when (apiType.lowercase()) {
                "movie" -> MetadataMediaKind.MOVIE
                "series", "tv", "show" -> MetadataMediaKind.SERIES
                else -> MetadataMediaKind.UNKNOWN
            },
            canonicalProvider = overlay?.canonicalProvider?.name,
            canonicalId = overlay?.canonicalId,
            imdbId = stableIds.imdb,
            stableIds = stableIds,
            display = ResolvedDisplayFields(
                title = title,
                originalTitle = mergedSlots.originalTitle.value,
                year = year?.toIntOrNull(),
                releaseDate = mergedSlots.releaseInfo.value,
                overview = mergedSlots.overview.value,
                genres = mergedSlots.genres.value.orEmpty(),
                runtimeText = mergedSlots.runtime.value
            ),
            artwork = ArtworkBundle(
                poster = mergedSlots.poster.value,
                backdrop = mergedSlots.backdrop.value,
                logo = mergedSlots.logo.value,
                thumbnail = mergedSlots.thumbnail.value
            ).enforceArtworkTypeBoundaries(),
            rating = mergedSlots.rating.value,
            trailer = trailerState,
            hydrationState = when {
                overlay == null -> HydrationState.PREVIEW_ONLY
                overlay.isStale(nowMs) -> HydrationState.STALE_READY
                else -> HydrationState.CANONICAL_READY
            },
            sourceTrace = overlay?.fieldTrace.orEmpty(),
            updatedAtMs = overlay?.updatedAtMs ?: nowMs,
            slots = mergedSlots
        )
    }
```

You may also need to delete the unused `mergeFallback` import at line 29 once the function compiles.

- [ ] **Step 4: Run test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperReducerTest`
Expected: PASS.

- [ ] **Step 5: Run the full mapper test suite to confirm no regression**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapperTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperReducerTest.kt
git commit -m "feat: route HomeResolvedDisplayMapper through projection reducer"
```

---

## Task 8: Reroute `HomeHydrationOverlayApplier` through the reducer

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt:11-25`

This task makes the apply-seam itself rank-aware. After this lands, even legacy UI sites that still consume `MetaPreview` directly get correct overlay-wins-over-first-paint behavior, because the seam is the writer.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplierTest.kt`:

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
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeHydrationOverlayApplierTest {
    @Test
    fun `apply seam preserves durable decision URI on second pass after a refresh re-emits raw addon URL`() {
        // First pass: rail emits raw addon URL, overlay hydrates a decision URI.
        // Result: the in-memory MetaPreview should carry the durable decision URI.
        // Second pass: rail re-emits the raw addon URL again. The apply seam runs again
        // with the SAME overlay. The durable decision URI must still win — the raw URL
        // must not resurface.
        val rawItem = baseRawItem()
        val overlay = baseOverlay()

        val rowWithRaw = CatalogRow(catalogId = "tmdb:popular", type = ContentType.MOVIE, title = "Popular", items = listOf(rawItem))
        val applied = rowWithRaw.applyHydratedHomeOverlays(mapOf(overlay.itemKey to overlay))
        val firstPosterValue = applied.items[0].poster
        assertEquals(true, firstPosterValue?.startsWith("nexio-artwork://"))

        // Simulate refresh: rail re-emits same raw row, apply seam runs again with same overlay.
        val rowAgain = CatalogRow(catalogId = "tmdb:popular", type = ContentType.MOVIE, title = "Popular", items = listOf(rawItem))
        val appliedAgain = rowAgain.applyHydratedHomeOverlays(mapOf(overlay.itemKey to overlay))
        val secondPosterValue = appliedAgain.items[0].poster
        assertEquals(firstPosterValue, secondPosterValue)
        assertEquals(true, secondPosterValue?.startsWith("nexio-artwork://"))
    }

    private fun baseRawItem(): MetaPreview = MetaPreview(
        id = "tmdb:550",
        type = ContentType.MOVIE,
        apiType = "movie",
        name = "Fight Club (rail)",
        poster = "https://image.tmdb.org/raw.jpg",
        background = null,
        logo = null,
        description = null,
        genres = emptyList(),
        releaseInfo = null,
        runtime = null,
        imdbRating = null,
        ratingSource = null,
        tomatoesRating = null,
        trailerYtIds = emptyList(),
        language = null,
        posterProviderTag = null,
        posterShape = null,
        firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
        firstPaintSourceProvider = null,
        firstPaintStableIds = ProviderIds(tmdb = "550"),
        firstPaintRailSource = null,
        firstPaintSourceItemId = "tmdb:550",
        artwork = null
    )

    private fun baseOverlay(): HydratedHomeOverlay {
        val itemKey = homeDisplayItemKey("movie", "tmdb:550")
        val resolvedPoster = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:poster:imdb:tt0137523"),
            assetKey = null,
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty(),
            displayHints = ArtworkDisplayHints()
        )
        val fields = HomeDisplayMetadata(
            title = "Fight Club (resolved)",
            artwork = ArtworkBundle(poster = resolvedPoster)
        )
        return HydratedHomeOverlay(
            overlayKey = hydratedHomeOverlayKey(ProviderId.TMDB, "550", ContentType.MOVIE, "en-US", 1),
            itemKey = itemKey,
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = "tt0137523",
            contentType = ContentType.MOVIE,
            languageTag = "en-US",
            policyVersion = 1,
            fields = fields,
            fieldTrace = emptyList(),
            displayHash = fields.hydratedHomeDisplayHash(),
            updatedAtMs = 1_000L,
            staleAtMs = 2_000L,
            expiresAtMs = 3_000L,
            state = HomeItemHydrationState.CANONICAL_READY
        )
    }
}
```

- [ ] **Step 2: Run test — verify it fails or passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeHydrationOverlayApplierTest`
Expected: depends on the existing `applyTo` behavior. If `preferDurableArtworkRef` already preserves the durable ref correctly the test passes; if not, it fails. Either result is informative — record it in the commit message.

- [ ] **Step 3: Update the apply seam to project via the reducer**

Replace the body of `internal fun CatalogRow.applyHydratedHomeOverlays(overlaysByItemKey)` (HomeHydrationOverlayApplier.kt:11-25) with:

```kotlin
internal fun CatalogRow.applyHydratedHomeOverlays(
    overlaysByItemKey: Map<String, HydratedHomeOverlay>
): CatalogRow {
    if (overlaysByItemKey.isEmpty()) return this
    val nowMs = System.currentTimeMillis()

    var changed = false
    val updatedItems = items.map { item ->
        val overlay = item.overlayFromMap(overlaysByItemKey) ?: return@map item
        val firstPaintSlots = item.toFirstPaintSlots(nowMs)
        val overlaySlots = overlay.toResolvedSlots(nowMs, isStale = overlay.isStale(nowMs))
        val merged = HomeRailProjectionReducer.reduce(
            firstPaint = firstPaintSlots,
            overlay = overlaySlots,
            existing = null,
            profile = null
        )
        val updated = item.applyMergedSlots(merged)
        if (updated !== item) changed = true
        updated
    }

    return if (changed) copy(items = updatedItems) else this
}

/**
 * Down-projects a reduced [ResolvedDisplayFieldSlots] back onto a [MetaPreview]
 * for legacy consumers that still read the mutable row. Once Plan B (UI
 * consumption migration) lands and consumers move to [ResolvedDisplayItem],
 * this function and its callers can be deleted.
 */
private fun MetaPreview.applyMergedSlots(slots: com.nexio.tv.domain.model.ResolvedDisplayFieldSlots): MetaPreview {
    val posterRef = slots.poster.value
    val backdropRef = slots.backdrop.value
    val logoRef = slots.logo.value
    val thumbnailRef = slots.thumbnail.value
    val ratingValue = slots.rating.value?.value?.toFloat()
    val ratingSource = slots.rating.value?.source
    return copy(
        name = slots.title.value ?: name,
        description = slots.overview.value ?: description,
        genres = slots.genres.value ?: genres,
        releaseInfo = slots.releaseInfo.value ?: releaseInfo,
        runtime = slots.runtime.value ?: runtime,
        imdbRating = ratingValue ?: imdbRating,
        ratingSource = ratingSource ?: this.ratingSource,
        poster = posterRef.toLegacyArtworkString() ?: poster,
        background = backdropRef.toLegacyArtworkString() ?: background,
        logo = logoRef.toLegacyArtworkString() ?: logo,
        posterProviderTag = slots.posterProviderTag.value ?: posterProviderTag,
        artwork = com.nexio.tv.core.artwork.ArtworkBundle(
            poster = posterRef,
            backdrop = backdropRef,
            logo = logoRef,
            thumbnail = thumbnailRef
        ).enforceArtworkTypeBoundaries().emptyOrNull()
    )
}
```

Add the imports near the top of the file:

```kotlin
import com.nexio.tv.core.artwork.emptyOrNull
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
import com.nexio.tv.core.artwork.toLegacyArtworkString
```

- [ ] **Step 4: Run test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeHydrationOverlayApplierTest`
Expected: PASS.

- [ ] **Step 5: Run full home test suite to catch regressions**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.*`
Expected: ALL PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplierTest.kt
git commit -m "feat: apply seam projects via HomeRailProjectionReducer"
```

---

## Task 9: Fix the inversion in `HomeCatalogRefreshCoordinator.mergePersistedHomeDisplayMetadata`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt:474-482` (and the call site around line 157-170)

The current implementation is `(externalMeta ?: currentItem).toHomeDisplayMetadata().mergeFallback(persistedFallback?.toHomeDisplayMetadata()).applyTo(currentItem)` — currentItem is the raw rail row (FIRST_PAINT) treated as primary; persistedFallback (RESOLVED) is treated as fallback. This is the architectural inversion.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshNonDowngradeTest.kt`:

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
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HomeCatalogRefreshNonDowngradeTest {
    @Test
    fun `refresh does not overwrite persisted durable poster with raw rail URL`() {
        val rawAddonItem = MetaPreview(
            id = "tmdb:550",
            type = ContentType.MOVIE,
            apiType = "movie",
            name = "Fight Club (rail)",
            poster = "https://image.tmdb.org/raw.jpg",
            background = null,
            logo = null,
            description = null,
            genres = emptyList(),
            releaseInfo = null,
            runtime = null,
            imdbRating = null,
            ratingSource = null,
            tomatoesRating = null,
            trailerYtIds = emptyList(),
            language = null,
            posterProviderTag = null,
            posterShape = null,
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceProvider = null,
            firstPaintStableIds = ProviderIds(tmdb = "550"),
            firstPaintRailSource = null,
            firstPaintSourceItemId = "tmdb:550",
            artwork = null
        )
        val resolvedPoster = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:poster:imdb:tt0137523"),
            assetKey = null,
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty(),
            displayHints = ArtworkDisplayHints()
        )
        val persistedFallback = rawAddonItem.copy(
            poster = "nexio-artwork://decision/artwork-decision:poster:imdb:tt0137523",
            posterProviderTag = "rpdb",
            artwork = ArtworkBundle(poster = resolvedPoster)
        )

        val merged = HomeCatalogRefreshCoordinator.projectRailRowAgainstPersistedForTest(
            rawRailItem = rawAddonItem,
            persistedFallback = persistedFallback,
            externalMeta = null
        )

        assertEquals(true, merged.poster?.startsWith("nexio-artwork://"))
        assertNotNull(merged.artwork?.poster)
        assertEquals("rpdb", merged.posterProviderTag)
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeCatalogRefreshNonDowngradeTest`
Expected: FAIL — function `projectRailRowAgainstPersistedForTest` doesn't exist (or fails because the inversion is still in place).

- [ ] **Step 3: Replace `mergePersistedHomeDisplayMetadata` with a reducer call**

In `HomeCatalogRefreshCoordinator.kt`, locate `private fun mergePersistedHomeDisplayMetadata(...)` (~line 474-482) and replace with:

```kotlin
    /**
     * Projects a freshly-emitted rail row against the persisted (overlay-applied)
     * row using [HomeRailProjectionReducer]. The rail row is FIRST_PAINT input;
     * the persisted row carries STALE_RESOLVED (or RESOLVED if a hydration just
     * landed) state. Reducer guarantees first-paint cannot downgrade resolved
     * fields. Replaces the prior `mergeFallback`-with-rail-as-primary inversion
     * that allowed raw rail URLs to overwrite durable artwork refs.
     */
    private fun mergePersistedHomeDisplayMetadata(
        currentItem: MetaPreview,
        persistedFallback: MetaPreview?,
        externalMeta: MetaPreview?
    ): MetaPreview {
        if (persistedFallback == null && externalMeta == null) return currentItem
        val nowMs = System.currentTimeMillis()
        val firstPaintSlots = currentItem.toFirstPaintSlots(nowMs)
        // The persisted/external rows are the higher-rank input; mark STALE_RESOLVED
        // because they came from a prior hydration cycle (not from this refresh).
        val resolvedRow = externalMeta ?: persistedFallback!!
        val resolvedSlots = resolvedRow.toFirstPaintSlots(nowMs).toStaleResolved()
        val merged = HomeRailProjectionReducer.reduce(
            firstPaint = firstPaintSlots,
            overlay = null,
            existing = resolvedSlots,
            profile = null
        )
        return currentItem.applyMergedSlotsForRefresh(merged)
    }

    @VisibleForTesting
    internal fun projectRailRowAgainstPersistedForTest(
        rawRailItem: MetaPreview,
        persistedFallback: MetaPreview?,
        externalMeta: MetaPreview?
    ): MetaPreview = mergePersistedHomeDisplayMetadata(rawRailItem, persistedFallback, externalMeta)

    private fun MetaPreview.applyMergedSlotsForRefresh(
        slots: ResolvedDisplayFieldSlots
    ): MetaPreview {
        val posterRef = slots.poster.value
        val backdropRef = slots.backdrop.value
        val logoRef = slots.logo.value
        val thumbnailRef = slots.thumbnail.value
        val rating = slots.rating.value
        return copy(
            name = slots.title.value ?: name,
            description = slots.overview.value ?: description,
            genres = slots.genres.value ?: genres,
            releaseInfo = slots.releaseInfo.value ?: releaseInfo,
            runtime = slots.runtime.value ?: runtime,
            imdbRating = rating?.value?.toFloat() ?: imdbRating,
            ratingSource = rating?.source ?: ratingSource,
            poster = posterRef.toLegacyArtworkString() ?: poster,
            background = backdropRef.toLegacyArtworkString() ?: background,
            logo = logoRef.toLegacyArtworkString() ?: logo,
            posterProviderTag = slots.posterProviderTag.value ?: posterProviderTag,
            artwork = ArtworkBundle(
                poster = posterRef,
                backdrop = backdropRef,
                logo = logoRef,
                thumbnail = thumbnailRef
            ).enforceArtworkTypeBoundaries().emptyOrNull()
        )
    }

    private fun ResolvedDisplayFieldSlots.toStaleResolved(): ResolvedDisplayFieldSlots {
        fun <T> ResolvedSlot<T>.promoted(): ResolvedSlot<T> =
            if (rank == DisplaySourceRank.FIRST_PAINT) copy(rank = DisplaySourceRank.STALE_RESOLVED) else this
        return ResolvedDisplayFieldSlots(
            title = title.promoted(),
            originalTitle = originalTitle.promoted(),
            overview = overview.promoted(),
            genres = genres.promoted(),
            releaseInfo = releaseInfo.promoted(),
            runtime = runtime.promoted(),
            rating = rating.promoted(),
            poster = poster.promoted(),
            backdrop = backdrop.promoted(),
            logo = logo.promoted(),
            thumbnail = thumbnail.promoted(),
            posterProviderTag = posterProviderTag.promoted()
        )
    }
```

Add the imports (top of `HomeCatalogRefreshCoordinator.kt`):

```kotlin
import androidx.annotation.VisibleForTesting
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.emptyOrNull
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedSlot
```

- [ ] **Step 4: Run test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeCatalogRefreshNonDowngradeTest`
Expected: PASS.

- [ ] **Step 5: Confirm `withCompatiblePersistedInternalPoster` is now redundant**

The fragile `preserve` predicate in `withCompatiblePersistedInternalPoster` (lines 410-427) was only needed because the inversion was dropping durable refs. With the reducer in place, durable refs survive automatically. Mark the function `@Deprecated("redundant after reducer; remove once Plan B lands")` but do not delete yet — other call sites may exist.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshNonDowngradeTest.kt
git commit -m "fix: refresh coordinator routes through reducer (kills inversion)"
```

---

## Task 10: Overlay observer non-downgrade

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:688-706`

The current `observeHydratedHomeOverlaysForRows` clears the overlay map when subscribed itemKeys change (Trakt rail re-emit drops all prior overlays → fully hydrated rows fall back to first-paint). Fix: when the new overlay observation produces an empty map but the previous map was non-empty, retain the previous overlays as STALE_RESOLVED (the reducer already handles the rank correctly).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/OverlayObserverStalePreservationTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.HydratedHomeOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OverlayObserverStalePreservationTest {
    @Test
    fun `empty new map preserves prior overlays`() {
        val prior = mapOf("k1" to fakeOverlay("k1"))
        val merged = preserveStaleOverlays(prior, emptyMap())
        assertEquals(prior, merged)
    }

    @Test
    fun `non-empty new map replaces prior entries for same keys`() {
        val priorOverlay = fakeOverlay("k1", updatedAt = 100L)
        val freshOverlay = fakeOverlay("k1", updatedAt = 200L)
        val merged = preserveStaleOverlays(mapOf("k1" to priorOverlay), mapOf("k1" to freshOverlay))
        assertSame(freshOverlay, merged["k1"])
    }

    @Test
    fun `non-empty new map for different keys keeps both prior and fresh`() {
        val merged = preserveStaleOverlays(
            previous = mapOf("k1" to fakeOverlay("k1")),
            next = mapOf("k2" to fakeOverlay("k2"))
        )
        assertEquals(setOf("k1", "k2"), merged.keys)
    }

    private fun fakeOverlay(key: String, updatedAt: Long = 0L): HydratedHomeOverlay =
        com.nexio.tv.ui.screens.home.testutil.HydratedHomeOverlayFixtures.minimal(itemKey = key, updatedAtMs = updatedAt)
}
```

(If `HydratedHomeOverlayFixtures` does not exist, create a small testutil file with a `minimal(...)` factory that builds a valid `HydratedHomeOverlay` for tests.)

- [ ] **Step 2: Run test — verify it fails (compile error)**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.OverlayObserverStalePreservationTest`
Expected: FAIL — `Unresolved reference: preserveStaleOverlays`.

- [ ] **Step 3: Add the helper function**

In `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`, add (private file-level or inside the class — the test imports it as a top-level function in the package, so make it `internal` at file scope):

```kotlin
internal fun preserveStaleOverlays(
    previous: Map<String, HydratedHomeOverlay>,
    next: Map<String, HydratedHomeOverlay>
): Map<String, HydratedHomeOverlay> {
    if (next.isEmpty()) return previous
    if (previous.isEmpty()) return next
    val merged = HashMap<String, HydratedHomeOverlay>(previous.size + next.size)
    merged.putAll(previous)
    merged.putAll(next)
    return merged
}
```

- [ ] **Step 4: Wire it into `observeHydratedHomeOverlaysForRows`**

Locate the assignment around line 703 (`hydratedHomeOverlaysByItemKey.value = overlays`) and replace with:

```kotlin
hydratedHomeOverlaysByItemKey.update { previous ->
    preserveStaleOverlays(previous = previous, next = overlays)
}
```

(Adjust if the field is exposed via a different state-flow accessor; the intent is to merge rather than overwrite.)

- [ ] **Step 5: Run test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.OverlayObserverStalePreservationTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/OverlayObserverStalePreservationTest.kt
git commit -m "fix: overlay observer preserves prior overlays on empty re-emit"
```

---

## Task 11: `MetadataRouterFacade` rating fallback to canonical REMOTE_IDS

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:1140-1165`

This is the deferred fix from earlier — it lands here under the RatingResolver-ownership umbrella because the resolver belongs at projection precedence, not at the request-context gate. Until UI projection-time resolver runs (a Plan B item), pulling imdb id from the canonical's REMOTE_IDS is the cheapest place to fix the gate.

- [ ] **Step 1: Write the failing test**

Locate or create `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeRatingFallbackTest.kt` and add:

```kotlin
package com.nexio.tv.core.metadata.router

// imports per existing facade tests

@Test
fun `applyRatingResolverSelection uses primary REMOTE_IDS imdb when previewStableIds imdb blank`() {
    // Construct a request whose previewStableIds.imdb is null (TMDB rail case).
    // Construct a primary MetadataCandidate whose fields[REMOTE_IDS] contains
    // mapOf("imdb" to setOf("tt0137523")).
    // Stub TitleRatingOverrideRepository to return a known CUSTOM_IMDB rating
    // for tt0137523.
    // Invoke applyRatingResolverSelection and assert the chosen rating source
    // is CUSTOM_IMDB and value matches the stub.
    // (Use the existing facade test fixtures; copy the pattern from any test in
    // MetadataRouterFacadeRatingResolverTest.)
}
```

(Implementer: use the exact fixture builders already in the facade test suite — no need to invent new ones.)

- [ ] **Step 2: Run test — verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterFacadeRatingFallbackTest`
Expected: FAIL — current code short-circuits on the null imdb gate.

- [ ] **Step 3: Apply the fallback in `applyRatingResolverSelection`**

At `MetadataRouterFacade.kt:1140`, replace:

```kotlin
val imdbId = request.sourceContext.previewStableIds.imdb
```

with:

```kotlin
val imdbId = request.sourceContext.previewStableIds.imdb
    ?.takeIf { it.isNotBlank() }
    ?: (primary?.fields?.get(ResolvedField.REMOTE_IDS)?.value as? Map<*, *>)
        ?.get("imdb")
        ?.let { it as? Iterable<*> }
        ?.firstNotNullOfOrNull { (it as? String)?.trim()?.takeIf(String::isNotBlank) }
```

Then around line 1161 where `providerIds = request.sourceContext.previewStableIds` is passed, change to:

```kotlin
providerIds = request.sourceContext.previewStableIds.copy(imdb = imdbId)
```

- [ ] **Step 4: Run test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterFacadeRatingFallbackTest`
Expected: PASS.

- [ ] **Step 5: Run full facade test suite**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterFacade*`
Expected: ALL PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt \
        app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeRatingFallbackTest.kt
git commit -m "fix: rating resolver falls back to canonical REMOTE_IDS imdb"
```

---

## Task 12: `home.display_projection` diagnostic event

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`

This event proves at runtime which slot won every poster/backdrop/logo/rating decision per row. It is the primary verification tool for Plan B and beyond.

- [ ] **Step 1: Add `emitHomeDisplayProjection`**

In `TraceMetadataEvents.kt`, add a new method following the existing emit pattern:

```kotlin
fun emitHomeDisplayProjection(
    itemKey: String,
    sourceRail: String?,
    firstPaintSummary: Map<String, Any?>,
    overlaySummary: Map<String, Any?>?,
    existingSummary: Map<String, Any?>?,
    selectedSummary: Map<String, Any?>,
    firstPaintSuppressedSummary: Map<String, Any?>
)
```

Implementation should mirror the existing `emitHomeHydrationApplied` event shape — emit a single event with payload containing `firstPaint`, `overlay`, `existing`, `selected`, `firstPaintSuppressed` keys.

- [ ] **Step 2: Emit from the mapper**

After the reducer call in `HomeResolvedDisplayMapper.toResolvedDisplayItem`, before returning the `ResolvedDisplayItem`, add (file-private helper):

```kotlin
private fun emitProjectionTrace(
    traceEvents: TraceMetadataEvents,
    itemKey: String,
    sourceRail: String?,
    firstPaint: ResolvedDisplayFieldSlots,
    overlay: ResolvedDisplayFieldSlots?,
    merged: ResolvedDisplayFieldSlots
) {
    traceEvents.emitHomeDisplayProjection(
        itemKey = itemKey,
        sourceRail = sourceRail,
        firstPaintSummary = mapOf(
            "poster" to (firstPaint.poster.rank != DisplaySourceRank.EMPTY),
            "backdrop" to (firstPaint.backdrop.rank != DisplaySourceRank.EMPTY),
            "logo" to (firstPaint.logo.rank != DisplaySourceRank.EMPTY),
            "rating" to firstPaint.rating.value?.value
        ),
        overlaySummary = overlay?.let {
            mapOf(
                "found" to true,
                "poster" to it.poster.provider,
                "backdrop" to it.backdrop.provider,
                "logo" to it.logo.provider,
                "rating" to it.rating.provider
            )
        },
        existingSummary = null,
        selectedSummary = mapOf(
            "poster" to mapOf("provider" to merged.poster.provider, "rank" to merged.poster.rank.name),
            "backdrop" to mapOf("provider" to merged.backdrop.provider, "rank" to merged.backdrop.rank.name),
            "logo" to mapOf("provider" to merged.logo.provider, "rank" to merged.logo.rank.name),
            "rating" to mapOf("provider" to merged.rating.provider, "rank" to merged.rating.rank.name)
        ),
        firstPaintSuppressedSummary = mapOf(
            "poster" to (merged.poster.rank.ordinal > DisplaySourceRank.FIRST_PAINT.ordinal && firstPaint.poster.rank == DisplaySourceRank.FIRST_PAINT),
            "backdrop" to (merged.backdrop.rank.ordinal > DisplaySourceRank.FIRST_PAINT.ordinal && firstPaint.backdrop.rank == DisplaySourceRank.FIRST_PAINT),
            "logo" to (merged.logo.rank.ordinal > DisplaySourceRank.FIRST_PAINT.ordinal && firstPaint.logo.rank == DisplaySourceRank.FIRST_PAINT)
        )
    )
}
```

Wire `traceEvents` into the mapper via constructor injection (or pass as a parameter to `toResolvedDisplayItems`).

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleUniversalDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt
git commit -m "feat: emit home.display_projection diagnostic event"
```

---

## Task 13: Spec mandatory tests — first-paint non-downgrade core

**Files:**
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeFirstPaintInvariantTest.kt`

These five tests come verbatim from the spec's "First-paint non-downgrade tests" section.

- [ ] **Step 1: Write the test file**

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedSlot
// ... slot fixture builders ...
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFirstPaintInvariantTest {
    @Test
    fun `late_first_paint_does_not_replace_hydrated_poster`() {
        val resolved = slotsWithPoster(DisplaySourceRank.RESOLVED, "nexio-artwork://decision/abc")
        val lateFirstPaint = slotsWithPoster(DisplaySourceRank.FIRST_PAINT, "https://addon/raw.jpg")
        val merged = HomeRailProjectionReducer.reduce(
            firstPaint = lateFirstPaint,
            overlay = null,
            existing = resolved,
            profile = null
        )
        assertEquals(DisplaySourceRank.RESOLVED, merged.poster.rank)
    }

    @Test
    fun `late_first_paint_does_not_replace_hydrated_backdrop`() {
        // Mirror with backdrop slot
        val resolved = emptySlots().copy(backdrop = ResolvedSlot(value = artworkRef("nexio-artwork://decision/bg"), rank = DisplaySourceRank.RESOLVED, provider = "TVDB", role = "PRIMARY", updatedAtMs = 0L, expiresAtMs = null, trace = emptyList()))
        val lateFirstPaint = emptySlots().copy(backdrop = ResolvedSlot(value = artworkRef("https://addon/bg.jpg"), rank = DisplaySourceRank.FIRST_PAINT, provider = "TRAKT", role = "RAIL_PREVIEW", updatedAtMs = 0L, expiresAtMs = null, trace = emptyList()))
        val merged = HomeRailProjectionReducer.reduce(lateFirstPaint, null, resolved, null)
        assertEquals(DisplaySourceRank.RESOLVED, merged.backdrop.rank)
    }

    @Test
    fun `late_first_paint_does_not_replace_hydrated_logo`() {
        // analogous, logo slot
        // Implementer: copy the pattern from the backdrop test, swap field
    }

    @Test
    fun `late_first_paint_does_not_clear_resolved_rating`() {
        // RESOLVED rating with value 8.0 vs FIRST_PAINT with value null -> RESOLVED wins
        // Implementer: build slots with rating fields per pattern above
    }

    @Test
    fun `late_first_paint_null_fields_do_not_remove_hydrated_fields`() {
        // RESOLVED title "Real Title" vs FIRST_PAINT title null -> RESOLVED wins
        // (because EMPTY < RESOLVED)
    }

    // shared helpers
    private fun emptySlots(): ResolvedDisplayFieldSlots = /* see HomeRailProjectionReducerTest helpers */
    private fun slotsWithPoster(rank: DisplaySourceRank, value: String?): ResolvedDisplayFieldSlots = /* shared */
    private fun artworkRef(value: String) = com.nexio.tv.core.artwork.ArtworkDisplayRef.LegacyString(value, com.nexio.tv.core.artwork.ArtworkType.BACKDROP, com.nexio.tv.core.artwork.ArtworkTrace.empty())
}
```

(Implementer: factor the slot builder helpers into a shared `HomeReducerTestFixtures.kt` testutil so this and the reducer test reuse them.)

- [ ] **Step 2: Run tests — verify all pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeFirstPaintInvariantTest`
Expected: PASS (5 tests).

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/HomeFirstPaintInvariantTest.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/testutil/HomeReducerTestFixtures.kt
git commit -m "test: spec first-paint non-downgrade invariant tests"
```

---

## Task 14: Spec mandatory tests — premium poster + stale-if-error

**Files:**
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailProjectionPremiumTest.kt`

Spec section "Premium poster tests" requires:
- `rpdb_poster_wins_for_tv_when_configured`
- `rpdb_poster_wins_for_movie_when_configured`
- `soft_refresh_failure_keeps_stale_rpdb_poster`
- `fresh_premium_pending_does_not_persist_tmdb_as_authoritative_replacement`
- `premium_provider_disabled_allows_fallback`

- [ ] **Step 1: Implement each test using the reducer fixtures from Task 13**

For each test: build the matching slots configuration (poster slot at the right rank with the right provider), call the reducer, assert the chosen poster's provider/rank.

- [ ] **Step 2: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeRailProjectionPremiumTest`
Expected: PASS (5 tests).

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailProjectionPremiumTest.kt
git commit -m "test: spec premium poster + stale-if-error invariant tests"
```

---

## Task 15: Spec mandatory tests — rating, kitsu, alias

**Files:**
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailProjectionRatingTest.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailProjectionAliasTest.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailProjectionKitsuTest.kt`

Spec sections "Rating tests", "Kitsu tests", and "Overlay key tests" enumerate:

Rating: `tmdb_rail_vote_average_becomes_rating_candidate`, `tvdb_canonical_series_can_use_tmdb_rating`, `rating_source_not_defaulted_to_imdb_when_unknown`, `invalid_preview_rating_rejected`, `rating_update_reprojects_home_row`.

Kitsu: `kitsu_poster_typed_as_poster`, `kitsu_cover_typed_as_backdrop`, `kitsu_does_not_emit_logo_candidate`, `kitsu_preview_logo_survives_when_primary_has_no_logo`, `kitsu_overlay_never_writes_integration_poster`.

Alias: `overlay_written_under_tvdb_canonical_key_read_by_trakt_row`, `overlay_written_under_tmdb_tv_key_read_by_tvdb_canonical_row`, `overlay_written_under_row_key_read_after_alias_change`.

- [ ] **Step 1: Implement each suite**

Rating tests use the reducer fixtures + `HomeRailProjectionReducer.reduce`. Alias tests exercise `HomeArtworkOverlayKeys.aliasesFor` plus `HydratedHomeOverlayStore.upsert`/`readForItemKeys` via integration. Kitsu tests verify artwork-type boundaries — the kitsu adapter never emits a LOGO candidate, so the LOGO slot stays EMPTY at RESOLVED rank, allowing FIRST_PAINT preview logo to survive.

Each test follows the pattern: build slots, call `reduce`, assert.

- [ ] **Step 2: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeRailProjection*Test"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailProjectionRatingTest.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailProjectionAliasTest.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailProjectionKitsuTest.kt
git commit -m "test: spec rating, kitsu, and overlay-alias invariant tests"
```

---

## Task 16: Profile-switch preservation tests

**Files:**
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeProfileSwitchPreservationTest.kt`

Spec section "Profile tests" requires:
- `profile_switch_does_not_clear_shared_resolved_artwork`
- `profile_switch_does_not_refetch_metadata_when_cache_fresh`
- `profile2_does_not_see_profile1_continue_watching`

The reducer alone covers (1) — verify a profile switch event simulated as a "fresh first-paint emit" against an existing slot bundle preserves RESOLVED. Tests (2) and (3) belong elsewhere (cache layer, CW snapshot). Implement (1) here; mark (2) and (3) `@Ignore("covered by ContinueWatchingSnapshotStoreTest / MetadataDiskCacheStoreTest")` with a comment pointing at the existing coverage.

- [ ] **Step 1: Write the tests**

(Pattern matches Task 13.)

- [ ] **Step 2: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeProfileSwitchPreservationTest`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/HomeProfileSwitchPreservationTest.kt
git commit -m "test: profile-switch preserves shared resolved artwork"
```

---

## Task 17: Self-review against the spec + run full suite + device verification

- [ ] **Step 1: Self-review**

Walk the spec's "Mandatory tests" list (lines marked under each section) and check each against this plan. Open the plan file and grep for each test name. Any missing? Add a follow-up task here (don't ship without coverage).

```bash
grep -E "(late_first_paint|trakt_tv_row|tmdb_tv_row|tvdb_logo_only|tvdb_backdrop|tvdb_logo_asset|poster_card_never|rpdb_poster_wins|soft_refresh_failure|fresh_premium_pending|premium_provider_disabled|kitsu_poster_typed|kitsu_cover_typed|kitsu_does_not_emit|kitsu_preview_logo|kitsu_overlay_never|tmdb_rail_vote_average|tvdb_canonical_series|rating_source_not|invalid_preview_rating|rating_update_reprojects|overlay_written_under|profile_switch_does|profile2_does_not)" docs/superpowers/plans/2026-05-09-resolved-display-authority.md
```

- [ ] **Step 2: Run the full app test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Build + install on device + verify**

```bash
./gradlew :app:installUniversalDebug
adb -d shell am start -n com.nexio.tv/.MainActivity
adb -d logcat -c
adb -d logcat -v threadtime nexio:V '*:S' | grep -E "home\.(display_projection|hydration_applied|hydration_ignored)"
```

Verify in the device logcat:
- `home.display_projection` events emit per row with `selected.poster.rank` showing `RESOLVED` for any item the overlay has hydrated
- `firstPaintSuppressed.poster=true` for those items
- `home.hydration_applied` rate is bounded (no 73/sec flood)
- Trakt rail items keep their hydrated logos/backdrops/posters across catalog refreshes
- TMDB rail items keep their RPDB/TopPosters decision URI; the raw `https://image.tmdb.org/...` URL never appears as the displayed poster after the first hydration

Manual UI checks on TV:
- Trakt Trending Shows: posters, logos, backdrops persist after navigating away and back
- TMDB Popular Movies: premium poster persists; rating shows custom IMDB value where applicable
- Kitsu Anime: poster typed correctly (never a backdrop in poster card); preview logo survives if Kitsu has none

- [ ] **Step 4: Final commit (if any cleanups)**

```bash
git status
# If self-review revealed anything, commit. Otherwise nothing to do.
```

---

## Execution Handoff

After saving this plan, the implementer should follow `superpowers:subagent-driven-development` (recommended) for fresh-subagent-per-task execution, or `superpowers:executing-plans` for inline execution.

**Plan B (follow-up):** `2026-05-XX-resolved-display-ui-consumption-migration.md` — per-surface migration of the ~72 bypass sites to consume `ResolvedDisplayItem`. Sequenced: Home rails → hero → screensaver → continue-watching → detail. Each surface is one milestone with its own task list and tests.
