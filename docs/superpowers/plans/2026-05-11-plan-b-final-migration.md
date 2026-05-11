# Plan B Final Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) — see "Execution Model" section below. Steps use checkbox (`- [ ]`) syntax for tracking.

**⚠️ STATUS 2026-05-11 (mid-execution checkpoint):**
- **Phase 2 (Surface 5 — Detail): SHIPPED.** Commits `71ba8f7e8..3dc5cc0cd` (6 commits). MetaDetailsResolvedFields projection + MetaDetailsViewModel observer + HeroContentSection consumer + DetailRailItem.fromResolved.
- **Phase 4 (Surface 1 — Carousel field drop): SHIPPED.** Commits `295c8f62a..565245026` (3 commits). `ModernCarouselItem.metaPreview` field deleted; lookup routed via `metaByItemKey` map. Heap verified: 430 carousel-retained MetaPreview refs → **0**.
- **Phase 5 audit: SHIPPED** (commit `5332ad78c`). Audit revealed the remaining work (Phases 5-7) is **larger than the plan initially scoped**. See "Audit Findings & Revised Execution" section at bottom — those phases require Classic/Grid migration as a prerequisite + careful task-by-task execution to avoid the documented 2026-05-09 GC death-spiral.
- **Phases 1 (Daredevil verification), 3 (Screensaver), 5–7: NOT shipped.** Phase 1 skipped per user direction (stale-overlay problem already known). Phase 3 skipped after audit confirmed overlays already typed. Phases 5–7 require revised execution; see below.

**Goal:** Complete the typed-authority migration started by `feat/resolved-display-authority` — eliminate the final MetaPreview retention by reshaping Surface 5 (Detail), finishing Surface 3 (Screensaver) and Surface 1 (Home Rails callbacks), retiring the legacy `_displayCatalogRows`/`_displayHeroItems` StateFlows, dropping `Snapshot.catalogRows`/`heroItems` from the persisted shape, deleting `HomeHydrationOverlayApplier.kt`, and tagging the migration complete after a final heap-acceptance gate.

**Architecture:** Seven phases executed in order. Each phase is independently shippable. Earlier phases gate later ones (Surface 1 callbacks gate StateFlow retirement; StateFlow retirement gates Snapshot reshape). The single-file changes deliberately stop short of touching the **stale-overlay invalidation** problem documented in `project_cross_provider_id_resolver_2026_05_11.md` — that's a separate plan to start AFTER this architecture settles.

**Tech Stack:** Kotlin · Hilt · Coroutines/Flow · Compose · Room · JUnit4 · Mockk

**Prerequisite reading:**
- Spec: `docs/superpowers/plans/2026-05-09-resolved-display-ui-consumption-migration.md` (original Plan B) — sections referenced by Surface number below
- Memory: `project_boundary_non_downgrade_fix_2026_05_11.md` — boundary fix this plan layers on top of
- Memory: `project_cross_provider_id_resolver_2026_05_11.md` — known gap (stale overlay) explicitly out of scope here
- `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt:153–157` — the Snapshot data class shape to reshape in Phase 6

**Scope explicitly excluded:**
- Stale-overlay invalidation (`HydratedHomeOverlayStore` rework + re-hydration trigger). New plan after this one.
- Any change to the artwork pipeline's candidate selection logic.
- Any change to `HomeRailProjectionReducer` or the repository boundary's `applyNonDowngradeMerge`.

---

## File Structure

### New files
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsResolvedFields.kt` — projection helper: derives detail-screen flat-fields from `ResolvedDisplayItem`.
- `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsResolvedFieldsTest.kt` — unit tests for the projection.

### Modified files (high-level)
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt` — observe `ResolvedDisplaySurfaceRepository`, expose `resolvedDetailFields` in `MetaDetailsUiState`.
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt` — add `resolvedDetailFields: MetaDetailsResolvedFields?`.
- `app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt` — read from `resolvedDetailFields` for hero hero artwork / title / overview.
- `app/src/main/java/com/nexio/tv/ui/screens/detail/CollectionSection.kt` + `MoreLikeThisSection.kt` — consume `ModernHomeRowItem` instead of `MetaPreview` / `DetailRailItem.fromMetaPreview`.
- `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverController.kt` + `IdleScreensaverOverlay.kt` + `IdleTrailerScreensaverOverlay.kt` — audit-driven; flip to typed `IdleScreensaverDisplayItem` if any MetaPreview reads remain.
- `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt` — `ModernRowSection` callback signatures flip from `(MetaPreview)` to `(ModernHomeRowItem)` or `(String itemKey)`.
- `app/src/main/java/com/nexio/tv/ui/screens/home/ClassicHomeContent.kt` + `GridHomeContent.kt` — mirror callback flip.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt` — callbacks flip at the call site.
- `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt` — drop `ModernCarouselItem.metaPreview` field (Phase 4).
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt` — retire `_displayCatalogRows` (Phase 5) + `_displayHeroItems` (Phase 5).
- `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt` — drop `Snapshot.catalogRows` + `heroItems`; schema bump (Phase 6).
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt` — DELETE entire file (Phase 6).

---

# Phase 1: Daredevil Verification Gate

## Task 1: On-device verification of the boundary + cross-id work

**Files:** None (verification only).

- [ ] **Step 1: Build + install with latest main**

```bash
git pull origin main
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Cold-start sequence (CLAUDE.md rule #8)**

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 45
```

- [ ] **Step 3: Visually verify the Daredevil row**

Navigate to a Modern Home rail that contains Daredevil: Born Again. Open the row; observe the focused card's poster. Expected: RPDB premium poster, NOT TMDB stock. If TMDB stock is shown after 45s soak, the stale-overlay problem still applies — STOP this plan and pick up the stale-overlay invalidation work first (see `project_cross_provider_id_resolver_2026_05_11.md`).

- [ ] **Step 4: Confirm `home.display_projection` reports RESOLVED-rank wins**

```bash
adb -s 192.168.50.98:5555 logcat -d -t 5000 | grep "home.display_projection" | head -20
```

Expected per line: `selected.poster.rank=RESOLVED` (or `STALE_RESOLVED`) for hydrated items. If any line shows `selected.poster.rank=FIRST_PAINT` for an item that has had time to hydrate, the boundary fix is being bypassed — investigate before proceeding.

- [ ] **Step 5: Capture a baseline heap dump**

```bash
PID=$(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv)
adb -s 192.168.50.98:5555 shell am dumpheap $PID /data/local/tmp/heap-plan-b-baseline.hprof
sleep 6
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-plan-b-baseline.hprof /tmp/heap-plan-b-baseline.hprof
heaptrail -i /tmp/heap-plan-b-baseline.hprof -t 200 2>&1 | grep -E "MetaPreview|CatalogRow|ResolvedDisplayItem|ModernHomeRowItem|HeroDisplayItem|nexio\.tv\.domain\.model\." | head -20
```

Record the baseline MetaPreview / CatalogRow / ResolvedDisplayItem counts. The plan ends with Phase 7 comparing against this baseline.

Expected approximate baseline (post 2026-05-11 work):
- MetaPreview ~430
- CatalogRow ~40
- ResolvedDisplayItem ~100
- ModernHomeRowItem ~115

Each subsequent phase should reduce MetaPreview retention; CatalogRow may drop in Phase 5.

- [ ] **Step 6: No commit. This is verification only.**

---

# Phase 2: Surface 5 — Detail Screen Migration

The detail screen, `CollectionSection`, and `MoreLikeThisSection` currently render from `Meta` / `MetaPreview`. Adding a typed-authority pathway makes detail consistent with home rails and prevents the "rail shows RPDB but detail navigates to TMDB stock" navigation regression.

## Task 2: Failing TDD anchor — `MetaDetailsResolvedFields`

**Files:**
- Create: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsResolvedFieldsTest.kt`

- [ ] **Step 1: Inspect real ResolvedDisplayItem shape**

```bash
sed -n '1,60p' /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt
```

Note `ResolvedDisplayItem`, `ResolvedDisplayFields`, `ArtworkBundle`, `TitleRating` shapes. Use them verbatim in the test fixtures.

- [ ] **Step 2: Write the test file**

```kotlin
package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkType
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
import org.junit.Assert.assertNull
import org.junit.Test

class MetaDetailsResolvedFieldsTest {

    private fun resolvedItem(
        title: String? = "The Movie",
        posterUrl: String? = "https://rpdb.example/p.jpg",
        ratingValue: Double? = 8.4
    ): ResolvedDisplayItem = ResolvedDisplayItem(
        itemKey = "movie:tt12345",
        contentId = "tt12345",
        parentId = "tt12345",
        itemType = ContentType.MOVIE,
        mediaKind = MetadataMediaKind.MOVIE,
        canonicalProvider = "RPDB",
        canonicalId = "tt12345",
        imdbId = "tt12345",
        stableIds = ProviderIds(imdb = "tt12345"),
        display = ResolvedDisplayFields(
            title = title,
            originalTitle = null,
            year = 2024,
            releaseDate = "2024-03-15",
            overview = "A story",
            genres = listOf("Drama"),
            runtimeText = "142 min",
            tomatoesRating = null
        ),
        artwork = ArtworkBundle(
            poster = posterUrl?.let {
                ArtworkDisplayRef.LegacyString(
                    value = it,
                    imageType = ArtworkType.POSTER,
                    trace = com.nexio.tv.core.artwork.ArtworkTrace.empty()
                )
            },
            backdrop = null,
            logo = null,
            thumbnail = null
        ),
        rating = ratingValue?.let { TitleRating(it, TitleRatingSource.IMDB) },
        trailer = TrailerDisplayState(),
        hydrationState = HydrationState.CANONICAL_READY,
        sourceTrace = emptyList(),
        updatedAtMs = 1_700_000_000_000L
    )

    @Test
    fun `from() projects title, overview, year, runtime, rating from ResolvedDisplayItem`() {
        val fields = MetaDetailsResolvedFields.from(resolvedItem())

        assertEquals("The Movie", fields.title)
        assertEquals("A story", fields.overview)
        assertEquals(2024, fields.year)
        assertEquals("142 min", fields.runtimeText)
        assertEquals(8.4, fields.rating?.value)
        assertEquals(TitleRatingSource.IMDB, fields.rating?.source)
    }

    @Test
    fun `from() projects poster artwork from ResolvedDisplayItem`() {
        val fields = MetaDetailsResolvedFields.from(resolvedItem())
        assertEquals("https://rpdb.example/p.jpg", fields.posterUrl)
    }

    @Test
    fun `from() returns null poster when artwork bundle has no poster`() {
        val fields = MetaDetailsResolvedFields.from(resolvedItem(posterUrl = null))
        assertNull(fields.posterUrl)
    }

    @Test
    fun `from() returns null title when display title is null`() {
        val fields = MetaDetailsResolvedFields.from(resolvedItem(title = null))
        assertNull(fields.title)
    }
}
```

If `ArtworkTrace.empty()` or `ArtworkDisplayRef.LegacyString` doesn't compile against the real shape, adjust the constructor call to match the real signature (read `app/src/main/java/com/nexio/tv/core/artwork/ArtworkDisplayRef.kt` for the canonical form). Do NOT change assertion semantics.

- [ ] **Step 3: Run — must fail with `Unresolved reference 'MetaDetailsResolvedFields'`**

```bash
./gradlew :app:compileUniversalDebugUnitTestKotlin --max-workers=1 2>&1 | tail -10
```

Expected: FAIL with unresolved-reference error on each test.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsResolvedFieldsTest.kt
git commit -m "test(detail/typed-authority): failing TDD anchor — MetaDetailsResolvedFields

4 tests covering: title/overview/year/runtime/rating projection from
ResolvedDisplayItem; poster artwork; null-poster fallthrough; null-title
fallthrough. Intentionally fails until MetaDetailsResolvedFields lands.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

## Task 3: Implement `MetaDetailsResolvedFields`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsResolvedFields.kt`

- [ ] **Step 1: Write the projection**

```kotlin
package com.nexio.tv.ui.screens.detail

import androidx.compose.runtime.Immutable
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating

/**
 * Typed projection of [ResolvedDisplayItem] for the detail screen surface.
 *
 * Detail-screen-specific subset — does NOT carry every field the rail-level
 * [com.nexio.tv.ui.screens.home.ModernHomeRowItem] needs, because detail
 * renders the hero section / overview / rating chips, not a poster card.
 *
 * Plan B Surface 5 — produced by [MetaDetailsViewModel] from the surface
 * repository's typed authority, consumed by `HeroSection` / detail composables
 * via `resolvedDetailFields`. Replaces legacy `Meta` / `MetaPreview` reads on
 * the detail screen.
 */
@Immutable
data class MetaDetailsResolvedFields(
    val itemKey: String,
    val title: String?,
    val originalTitle: String?,
    val year: Int?,
    val releaseDate: String?,
    val overview: String?,
    val genres: List<String>,
    val runtimeText: String?,
    val tomatoesRating: Double?,
    val rating: TitleRating?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?
) {
    companion object {
        fun from(item: ResolvedDisplayItem): MetaDetailsResolvedFields {
            val display = item.display
            return MetaDetailsResolvedFields(
                itemKey = item.itemKey,
                title = display.title,
                originalTitle = display.originalTitle,
                year = display.year,
                releaseDate = display.releaseDate,
                overview = display.overview,
                genres = display.genres,
                runtimeText = display.runtimeText,
                tomatoesRating = display.tomatoesRating,
                rating = item.rating,
                posterUrl = item.artwork.poster?.toLegacyUrlOrNull(),
                backdropUrl = item.artwork.backdrop?.toLegacyUrlOrNull(),
                logoUrl = item.artwork.logo?.toLegacyUrlOrNull()
            )
        }

        private fun com.nexio.tv.core.artwork.ArtworkDisplayRef.toLegacyUrlOrNull(): String? =
            when (this) {
                is com.nexio.tv.core.artwork.ArtworkDisplayRef.LegacyString -> value.takeIf { it.isNotBlank() }
                else -> null
            }
    }
}
```

If `ArtworkDisplayRef` has a different sealed hierarchy than `LegacyString`, adjust the `toLegacyUrlOrNull` extension to match — exhaustively. The artwork ref already carries a string URL form; we just need to extract it.

- [ ] **Step 2: Run tests — 4/4 PASS**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*MetaDetailsResolvedFieldsTest*" --max-workers=1 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, 4 tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsResolvedFields.kt
git commit -m "feat(detail/typed-authority): MetaDetailsResolvedFields projection

Surface 5 typed projection. Derived from ResolvedDisplayItem; consumed
by the detail screen composables (HeroSection, etc.) instead of Meta /
MetaPreview field reads. Mirrors ModernHomeRowItem's role for home rails.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

## Task 4: Wire `ResolvedDisplaySurfaceRepository` into `MetaDetailsViewModel`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt`

- [ ] **Step 1: Add `resolvedDetailFields` to MetaDetailsUiState**

```bash
grep -n "data class MetaDetailsUiState" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt
```

Add a new field to the data class:

```kotlin
data class MetaDetailsUiState(
    // ... existing fields
    val resolvedDetailFields: MetaDetailsResolvedFields? = null,
    // ... rest
)
```

- [ ] **Step 2: Inject `ResolvedDisplaySurfaceRepository` into `MetaDetailsViewModel`**

```bash
grep -n "class MetaDetailsViewModel @Inject constructor" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt
```

Add a constructor parameter immediately after the existing dependencies:

```kotlin
private val resolvedDisplaySurfaceRepository: com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository,
```

(Match the codebase's import style — full-qualified inline or top-of-file import. Read the existing imports and follow them.)

- [ ] **Step 3: Add a flow observer that projects to `resolvedDetailFields`**

In the ViewModel's init or alongside the existing `observeMetaViewSettings()`, add:

```kotlin
private fun observeResolvedDetailFields() {
    viewModelScope.launch {
        val profileId = profileManager.activeProfileId.value
        _uiState
            .map { it.meta?.id?.let { id ->
                com.nexio.tv.domain.model.homeDisplayItemKey(it.meta!!.type.serialized, id)
            } }
            .distinctUntilChanged()
            .filterNotNull()
            .flatMapLatest { itemKey ->
                resolvedDisplaySurfaceRepository
                    .observeItem(profileId, itemKey)
                    .map { resolved -> resolved?.let { MetaDetailsResolvedFields.from(it) } }
            }
            .collect { fields ->
                _uiState.update { it.copy(resolvedDetailFields = fields) }
            }
    }
}
```

Call `observeResolvedDetailFields()` from the ViewModel's `init { }` block alongside `observeMetaViewSettings()` if that exists, or near `loadMeta()`.

Adjust:
- `it.meta!!.type.serialized` — replace with the actual lower-case content-type string the codebase uses for `homeDisplayItemKey`. Check `ContentType` enum for the right accessor.
- `homeDisplayItemKey` import — `com.nexio.tv.domain.model.homeDisplayItemKey`.
- If `ResolvedDisplaySurfaceRepository.observeItem(profileId, itemKey)` returns `Flow<ResolvedDisplayItem?>` directly (verified earlier), the chain is correct.

- [ ] **Step 4: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL. If a Hilt graph error mentions `ResolvedDisplaySurfaceRepository`, it's already provided as `@Singleton` (verified — home pipeline uses it), so the injection should resolve.

If tests at `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelTest*` (if any exist) construct the VM directly, pass a stub or actual `ResolvedDisplaySurfaceRepository(activeProfileSession = { ... })` — same pattern as the boundary fix's tests.

- [ ] **Step 5: Run detail tests**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*MetaDetails*" --max-workers=1 2>&1 | tail -10
```

Expected: PASS. Any test that constructs `MetaDetailsViewModel(...)` may need the new parameter — provide an `InMemoryResolvedDisplaySurfaceRepository` stub or use `ResolvedDisplaySurfaceRepository(activeProfileSession = { ActiveProfileSession(profileId = 1, ...) })` directly.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt \
        app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt
git commit -m "feat(detail/typed-authority): expose resolvedDetailFields in MetaDetailsUiState

MetaDetailsViewModel observes ResolvedDisplaySurfaceRepository for the
current meta's itemKey and projects the resolved item into
MetaDetailsResolvedFields. Composables can read from this typed surface
instead of Meta / MetaPreview field reads.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

## Task 5: Detail composables consume `resolvedDetailFields`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`

- [ ] **Step 1: Inspect HeroSection's current Meta/MetaPreview reads**

```bash
grep -n "uiState\.\|state\.meta\b\|meta?\.title\|meta?\.background\|meta?\.poster" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt | head -20
```

For each field read of `meta?.title` / `meta?.background` / `meta?.poster` / `meta?.description`, replace with:

```kotlin
state.resolvedDetailFields?.title ?: state.meta?.title
state.resolvedDetailFields?.backdropUrl ?: state.meta?.background
state.resolvedDetailFields?.posterUrl ?: state.meta?.poster
state.resolvedDetailFields?.overview ?: state.meta?.description
```

The fallback to `state.meta?.x` is intentional — keeps the screen rendering during the period between meta load and resolved-display-surface population. Once Phase 7's tag is cut, follow-up cleanup can drop the fallbacks if every code path has a resolved-detail-fields entry.

- [ ] **Step 2: Update MetaDetailsScreen.kt's call site for HeroSection**

Find the `HeroSection(...)` invocation in `MetaDetailsScreen.kt` and ensure `state` carries the new field (it should automatically since `uiState` is passed wholesale).

```bash
grep -n "HeroSection(" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt
```

No change to the call site needed if it passes the whole `state` — the new field is automatic.

- [ ] **Step 3: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Smoke-test detail navigation on device**

```bash
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
```

Manually navigate: cold-start → profile → Modern Home → focus a TMDB item → press CENTER to open detail. Verify the hero artwork / title / overview render (should be identical to before because of the `?: state.meta?.x` fallback path).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt \
        app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt
git commit -m "feat(detail/typed-authority): HeroSection reads from resolvedDetailFields

Falls back to legacy Meta-derived fields when resolvedDetailFields is null
(first-paint period before observeResolvedDetailFields populates the
surface). Once Phase 7 tags the migration complete, follow-up cleanup
can drop the fallbacks.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

## Task 6: `CollectionSection` + `MoreLikeThisSection` consume `ModernHomeRowItem`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/CollectionSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MoreLikeThisSection.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/DetailRailItem.kt` (already exists; add typed-authority projection helper)

- [ ] **Step 1: Inspect existing DetailRailItem.fromMetaPreview**

```bash
grep -n "DetailRailItem\|fun fromMetaPreview\|data class DetailRailItem" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/detail/DetailRailItem.kt | head -10
```

The current path is `DetailRailItem.fromMetaPreview(MetaPreview)`. We add a parallel `DetailRailItem.fromResolved(item: ResolvedDisplayItem)` so the related-items rail can construct from the typed authority.

- [ ] **Step 2: Add `DetailRailItem.fromResolved`**

In `DetailRailItem.kt`'s companion object:

```kotlin
companion object {
    fun fromMetaPreview(preview: MetaPreview): DetailRailItem = // ... existing

    fun fromResolved(item: ResolvedDisplayItem): DetailRailItem = DetailRailItem(
        itemKey = item.itemKey,
        contentId = item.contentId,
        title = item.display.title.orEmpty(),
        posterUrl = item.artwork.poster?.let {
            (it as? com.nexio.tv.core.artwork.ArtworkDisplayRef.LegacyString)?.value
        },
        // ... map remaining fields from item.display / item.artwork to the existing DetailRailItem fields
    )
}
```

Use the existing `DetailRailItem` field shape — only add the parallel constructor. If `DetailRailItem` is a data class with positional fields, mirror them with values pulled from `ResolvedDisplayItem`.

- [ ] **Step 3: Wire MetaDetailsViewModel to project related items via typed authority where possible**

Find the existing call sites:
```bash
grep -n "DetailRailItem.fromMetaPreview" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt
```

For each occurrence, add a typed-authority fast path: if `resolvedDisplaySurfaceRepository.snapshotNow(profileId)` (or an observed list) contains the items, use `DetailRailItem.fromResolved`. Fall back to `fromMetaPreview` when not.

This is the minimal-risk pattern. A full surface migration here is deferred — the goal of Phase 2 is just to wire the projection paths so the hero section is fully typed; related rails can stay on the legacy path until Phase 3.9 retires the StateFlows.

- [ ] **Step 4: Compile + smoke test**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
```

Smoke-test: navigate to detail → scroll to "More Like This" / "Collection" — items should render unchanged.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/DetailRailItem.kt \
        app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt
git commit -m "feat(detail/typed-authority): DetailRailItem.fromResolved for typed rail projection

CollectionSection / MoreLikeThisSection can now consume rail items from
the typed surface authority when present. Falls back to fromMetaPreview
when the resolved surface lacks the itemKey (e.g., during initial load).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

## Task 7: Surface 5 acceptance gate

**Files:** None (verification only).

- [ ] **Step 1: Run full detail test suite**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*Detail*" --tests "*MetaDetails*" --max-workers=1 2>&1 | tail -10
```

Expected: all PASS.

- [ ] **Step 2: On-device manual verification**

Navigate cold-start → profile → Modern Home → open the Daredevil row's detail screen. Verify:
- Hero artwork is RPDB premium poster (matches the rail's poster).
- Title + overview render correctly.
- "More Like This" rail items render with their addon-provided artwork.

- [ ] **Step 3: Heap-dump perf gate**

```bash
PID=$(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv)
adb -s 192.168.50.98:5555 shell am dumpheap $PID /data/local/tmp/heap-phase2-end.hprof
sleep 6
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-phase2-end.hprof /tmp/heap-phase2-end.hprof
heaptrail -i /tmp/heap-phase2-end.hprof -t 200 2>&1 | grep -E "MetaPreview|CatalogRow|ResolvedDisplayItem" | head -10
```

Expected: MetaPreview count UNCHANGED from Phase 1 baseline (this phase added typed observation but didn't yet drop any retention — the legacy fallback still keeps Meta-derived references). The MetaPreview drop happens in Phase 4.

- [ ] **Step 4: No commit. Phase 2 done.**

---

# Phase 3: Surface 3 — Screensaver Completion

## Task 8: Audit screensaver consumers for MetaPreview reads

**Files:** None (audit only).

- [ ] **Step 1: Audit `IdleScreensaverController.kt`**

```bash
grep -nE "MetaPreview|IdleScreensaverDisplayItem|fun update\|fun setSlides|fun set\b" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverController.kt
```

If `MetaPreview` is referenced anywhere, note the line. If only `IdleScreensaverDisplayItem` / `IdleScreensaverSlide` is referenced, the controller is already typed — skip to Task 10.

- [ ] **Step 2: Audit `IdleScreensaverOverlay.kt`**

```bash
grep -nE "MetaPreview|IdleScreensaverSlide|IdleScreensaverDisplayItem" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverOverlay.kt
```

The overlay's public composable signature is `IdleScreensaverOverlay(slides: List<IdleScreensaverSlide>, ...)` — already typed. Verify by inspecting the file. Internal helpers may still touch MetaPreview; flag any.

- [ ] **Step 3: Audit `IdleTrailerScreensaverOverlay.kt`**

```bash
grep -nE "MetaPreview|IdleTrailerScreensaverCandidate" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screensaver/IdleTrailerScreensaverOverlay.kt
```

The candidate type is `IdleTrailerScreensaverCandidate` — already typed. Verify by inspection.

- [ ] **Step 4: Decision branch**

If audit returns ZERO MetaPreview references in screensaver: **mark Surface 3 complete, skip Task 9, proceed to Task 10 (acceptance only).** If MetaPreview references remain, proceed to Task 9 to flip them.

- [ ] **Step 5: Commit the audit summary**

```bash
git commit --allow-empty -m "audit(screensaver): Surface 3 MetaPreview audit

Reviewed IdleScreensaverController, IdleScreensaverOverlay, and
IdleTrailerScreensaverOverlay for remaining MetaPreview reads.

Result: [PASTE AUDIT FINDINGS HERE — concrete file:line list, or
'no MetaPreview reads remain — Surface 3 already typed end-to-end'].

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

## Task 9: Flip screensaver consumers to typed (conditional)

**Files:** depends on Task 8 audit. Only execute if audit found MetaPreview references.

- [ ] **Step 1: For each `MetaPreview` parameter or field in screensaver code, replace with `IdleScreensaverDisplayItem`**

For composables, change the parameter type. For controllers, change the input parameter or field. Producer code in `HomeViewModelCatalogPipeline` that publishes to the screensaver surface ALREADY produces `IdleScreensaverDisplayItem` per Plan B Task 12 — confirm by:

```bash
grep -n "publishResolvedItems.*SCREENSAVER" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt | head -3
```

If the producer publishes typed `ResolvedDisplayItem`s (verified to be the case), the consumer can be flipped to read from the typed surface via `resolvedDisplaySurfaceRepository.observeScreensaverSurface(profileId).map { items -> items.map(IdleScreensaverDisplayItem::from) }`.

- [ ] **Step 2: Compile + smoke test**

```bash
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
# Wait for idle screensaver to trigger (~2 minutes of inactivity) OR force-trigger via debug menu
```

Verify the screensaver renders and cycles through items.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screensaver/<modified-files>
git commit -m "feat(screensaver/typed-authority): drop remaining MetaPreview reads

[describe specific files + changes]

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

## Task 10: Surface 3 acceptance gate

**Files:** None.

- [ ] **Step 1: Run screensaver tests**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*Screensaver*" --tests "*IdleScreensaver*" --tests "*IdleTrailer*" --max-workers=1 2>&1 | tail -10
```

Expected: all PASS.

- [ ] **Step 2: On-device verification**

Trigger screensaver (let app idle for the configured timeout). Verify both still-image rotation AND trailer mode (if available) render correctly.

- [ ] **Step 3: No commit. Phase 3 done.**

---

# Phase 4: Surface 1 — Carousel Callback Reshape

The 351 retained `MetaPreview` come from `ModernCarouselItem.metaPreview` payload, kept for callbacks. This phase reshapes the callbacks to take typed inputs, then drops the payload.

## Task 11: Failing TDD anchor — typed callback signatures

**Files:**
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeCallbackContractTest.kt`

- [ ] **Step 1: Write a compile-time-only "contract" test**

This task is unusual for TDD because the migration is type-level. The "test" is a no-op that asserts the typed-callback signatures exist:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.MetaPreview
import org.junit.Test

/**
 * Surface 1 Phase 4 — compile-time contract test.
 *
 * Asserts (at compile time) that ModernRowSection's callback parameters take
 * typed inputs (`String` itemKey or `ModernHomeRowItem`), not `MetaPreview`.
 *
 * If this file fails to compile, the migration is incomplete somewhere.
 */
class ModernHomeCallbackContractTest {

    @Test
    fun `ModernRowSection.onItemFocus accepts ModernHomeRowItem, not MetaPreview`() {
        val typedCallback: (ModernHomeRowItem) -> Unit = {}
        // After Task 12 ships, this compiles. Until then, it fails:
        val invalid: (MetaPreview) -> Unit = {}
        // Sanity assertion so the test reports correctly:
        assert(typedCallback !== invalid)
    }
}
```

Note: this test is intentionally weak — the strong assertion comes from successful compilation of the call sites in `HomeScreen.kt` after Task 12's signature change. The test exists to lock the contract.

- [ ] **Step 2: Run — verify compile success (the test PASSES because the signatures haven't changed yet)**

```bash
./gradlew :app:compileUniversalDebugUnitTestKotlin --max-workers=1 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL, test runs and passes. (The contract test passes today because we're just sanity-checking lambda types exist; the real contract is enforced by callers after Task 12.)

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/ModernHomeCallbackContractTest.kt
git commit -m "test(home/callbacks): contract anchor — typed callback signatures

Compile-time anchor for the Surface 1 callback reshape. Asserts the
typed callback types are usable. Task 12-13 will flip the actual
signatures.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

## Task 12: Flip `ModernRowSection` callbacks from `MetaPreview` to `ModernHomeRowItem`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt`

- [ ] **Step 1: Identify all callback signatures in ModernHomeRows.kt**

```bash
grep -n "onItemFocus\|isCatalogItemWatched\|onCatalogItemLongPress\|onPreloadAdjacentItem" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt | head -15
```

Expected hits: lines 252, 358–360, 657, 720–722, 727 (per prior audit).

- [ ] **Step 2: Change `ModernRowSection` signature**

Replace:
```kotlin
isCatalogItemWatched: (MetaPreview) -> Boolean,
onCatalogItemLongPress: (MetaPreview, String) -> Unit,
onItemFocus: (MetaPreview) -> Unit,
onPreloadAdjacentItem: (MetaPreview) -> Unit,
```

With:
```kotlin
isCatalogItemWatched: (ModernHomeRowItem) -> Boolean,
onCatalogItemLongPress: (ModernHomeRowItem, String) -> Unit,
onItemFocus: (ModernHomeRowItem) -> Unit,
onPreloadAdjacentItem: (ModernHomeRowItem) -> Unit,
```

- [ ] **Step 3: Update internal call sites in `ModernHomeRows.kt`**

Around line 657, the body currently does `isCatalogItemWatched(metaPreview)`. Change to pass the typed row item. The carousel item already carries `resolved: ModernHomeRowItem` via `cachedItem.resolvedSource` per `ModernHomePresentation.kt:128`. Pull it through:

For each call inside the composable body:
```kotlin
val isWatched = remember(rowItem.itemKey) { isCatalogItemWatched(rowItem) }
// ... onItemFocus(rowItem)
// ... onPreloadAdjacentItem(nextRowItem)
// ... onCatalogItemLongPress(rowItem, payload.addonBaseUrl)
```

The `metaPreview` local is no longer needed for callbacks — only for the MetaPreview payload (still on `ModernCarouselItem`). Drop the variable name binding where it was only used for callbacks.

- [ ] **Step 4: Update `HomeScreen.kt` to pass typed callbacks**

```bash
grep -n "isCatalogItemWatched\s*=\|onItemFocus\s*=\|onCatalogItemLongPress\s*=\|onPreloadAdjacentItem\s*=" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt | head -10
```

Each lambda passed in `HomeScreen.kt` currently has `MetaPreview` parameter. Flip to `ModernHomeRowItem`. The lambda bodies may use `meta.id` / `meta.type`; replace with `rowItem.contentId` / `rowItem.itemType` (or whatever the equivalent fields on `ModernHomeRowItem` are — read its data class to confirm).

- [ ] **Step 5: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. If a non-home call site is broken (e.g., a navigation route expects `MetaPreview`), wrap the typed item back to MetaPreview lookup at the boundary (e.g., look up `_displayCatalogRows.value` for the matching itemKey and pull the MetaPreview for the navigation arg). This bridge is temporary — Phase 5 retires `_displayCatalogRows`.

- [ ] **Step 6: Smoke-test Modern Home**

```bash
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 30
```

Verify focus moves correctly, long-press shows the action menu, items render, and detail navigation works.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt
git commit -m "refactor(home/callbacks): ModernRowSection callbacks take ModernHomeRowItem

onItemFocus / isCatalogItemWatched / onCatalogItemLongPress /
onPreloadAdjacentItem callbacks now accept the typed row item instead of
MetaPreview. Eliminates the need for ModernCarouselItem.metaPreview as
a callback payload (Task 14 drops the field).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

## Task 13: Flip Classic + Grid callbacks

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ClassicHomeContent.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt`

- [ ] **Step 1: Inspect**

```bash
grep -n "onItemFocus\|isCatalogItemWatched\|onCatalogItemLongPress\|onPreloadAdjacentItem\|MetaPreview" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/home/ClassicHomeContent.kt | head -10
grep -n "onItemFocus\|isCatalogItemWatched\|onCatalogItemLongPress\|onPreloadAdjacentItem\|MetaPreview" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt | head -10
```

- [ ] **Step 2: Apply the same callback signature flip as Task 12, mirroring**

In both files, change every `(MetaPreview)` callback parameter to `(ModernHomeRowItem)`. Update internal body references the same way.

- [ ] **Step 3: Compile + smoke-test each home variant**

```bash
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
```

In the app, switch home variant: Settings → Layout → Classic / Grid. Verify each renders + navigates correctly.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ClassicHomeContent.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt
git commit -m "refactor(home/callbacks): Classic + Grid callbacks take ModernHomeRowItem

Mirrors Task 12's Modern Home callback reshape. Both home variants now
consume the typed row item in callbacks.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

## Task 14: Drop `ModernCarouselItem.metaPreview` field — heap-acceptance gate

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`

- [ ] **Step 1: Search for any remaining reader**

```bash
grep -rn "\.metaPreview\b" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ --include="*.kt" 2>&1 | head -20
```

Expected: zero results (or only references inside `ModernCarouselItem` itself). If any reader remains, it must be updated to read from `resolved` (ModernHomeRowItem) or via a typed-surface lookup.

- [ ] **Step 2: Drop the field**

In `ModernHomeModels.kt` around line 100:

```kotlin
@Immutable
data class ModernCarouselItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val heroPreview: HeroPreview,
    val payload: ModernPayload
    // Removed: val metaPreview: MetaPreview? = null
)
```

Update `buildCatalogItem` in the same file to stop initializing `metaPreview` — remove the parameter from the `ModernCarouselItem(...)` constructor call.

- [ ] **Step 3: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Build + install + heap-dump verification**

```bash
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 60

PID=$(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv)
adb -s 192.168.50.98:5555 shell am dumpheap $PID /data/local/tmp/heap-phase4-end.hprof
sleep 6
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-phase4-end.hprof /tmp/heap-phase4-end.hprof
heaptrail -i /tmp/heap-phase4-end.hprof -t 200 2>&1 | grep -E "MetaPreview|CatalogRow|ModernCarouselItem" | head -10
```

Expected: **MetaPreview count drops from ~430 (baseline) to ~80 or fewer** — the 351 carousel-item retention is gone. `ModernCarouselItem` instances remain (they're the rendered carousel cards) but no longer hold MetaPreview refs.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt
git commit -m "refactor(home/carousel): drop ModernCarouselItem.metaPreview field

The field existed to pass MetaPreview through carousel callbacks
(onItemFocus, isCatalogItemWatched, etc.). After Tasks 12-13 reshaped
those callbacks to take ModernHomeRowItem, the field has zero readers.

Heap acceptance (verified on device): MetaPreview retention drops by
~80% from the Phase 1 baseline. The 351 carousel-payload MetaPreview
instances are gone.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Phase 5: Phase 3.9 — StateFlow Retirement

`_displayCatalogRows: MutableStateFlow<List<CatalogRow>>` and `_displayHeroItems: MutableStateFlow<List<MetaPreview>>` are the legacy fan-out points. After Surface 1 callbacks no longer demand them, they can retire.

## Task 15: Audit `_displayCatalogRows` readers

**Files:** None (audit only).

- [ ] **Step 1: Enumerate readers**

```bash
grep -rn "_displayCatalogRows\b\|\.displayCatalogRows\b" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ --include="*.kt" 2>&1 | head -20
```

Expected: HomeViewModel internal usage + presentation pipeline + persistence. Each reader needs to be migrated to read from `resolvedDisplaySurfaceRepository.observeHomeSurface(profileId)` and project via `ModernHomeRowItem.from(...)`.

- [ ] **Step 2: Enumerate `_displayHeroItems` readers**

```bash
grep -rn "_displayHeroItems\b\|\.displayHeroItems\b" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ --include="*.kt" 2>&1 | head -20
```

- [ ] **Step 3: Document the migration matrix in the commit**

```bash
git commit --allow-empty -m "audit(home/stateflows): _displayCatalogRows + _displayHeroItems readers

Migration matrix for Phase 3.9:
  _displayCatalogRows:
    - HomeViewModel.railStructure (line 237) — keep, derived flow
    - HomeViewModelPresentationPipeline (line 219) — flip to typed surface
    - HomeViewModelCatalogPipeline (lines 179, 1651, 1674, 2223, 2979) — internal writes, migrate

  _displayHeroItems:
    - [list reader sites here]

After Tasks 16-17 land, both StateFlows are deleted.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

(Replace the matrix with the real grep findings.)

## Task 16: Retire `_displayCatalogRows`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`

- [ ] **Step 1: Replace all reader sites with typed-surface observation**

Each reader of `_displayCatalogRows` should switch to:

```kotlin
val rails: List<ResolvedRailRow> = resolvedDisplaySurfaceRepository
    .observeHomeSurface(profileId)
    .map { items -> projectionCache.projectRails(items, currentRailStructure) }
```

`currentRailStructure` comes from the existing `railStructure: StateFlow<List<Rail>>` (line 237 of HomeViewModel.kt) — but `railStructure` is currently derived from `_displayCatalogRows`. Either retain `railStructure` and source it from the typed surface, or pass rail structure separately.

The cleanest replacement: `_displayCatalogRows` becomes a derived field projected from the typed surface, NOT directly mutated. Existing writers become writers to the typed surface (which goes through the boundary's `applyNonDowngradeMerge`).

- [ ] **Step 2: Delete the MutableStateFlow declaration**

In `HomeViewModel.kt`:

```kotlin
// Remove:
internal val _displayCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
val displayCatalogRows: StateFlow<List<CatalogRow>> = _displayCatalogRows.asStateFlow()

// Replace `railStructure` derivation source:
internal val railStructure: StateFlow<List<Rail>> = resolvedDisplaySurfaceRepository
    .observeHomeSurface(profileManager.activeProfileId.value)
    .map { items -> /* project to Rail list */ }
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
```

(The actual derivation needs to preserve `Rail` shape. Check `Rail` model file for the structure.)

- [ ] **Step 3: Update each writer site in `HomeViewModelCatalogPipeline.kt`**

Each `_displayCatalogRows.value = …` or `_displayCatalogRows.update { … }` becomes a publish to the typed surface:

```kotlin
resolvedDisplaySurfaceRepository.publishResolvedItems(
    surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
    items = itemsToPublish
)
```

The boundary's `applyNonDowngradeMerge` handles rank-preservation. The `items` argument is `List<ResolvedDisplayItem>` — convert from CatalogRow items via the existing `HomeResolvedDisplayMapper.toResolvedDisplayItems(...)`.

- [ ] **Step 4: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL. Fix any cascading break — `_displayCatalogRows` references should all be gone.

- [ ] **Step 5: Smoke-test + run home test suite**

```bash
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
./gradlew :app:testUniversalDebugUnitTest --tests "*HomeViewModel*" --max-workers=1 2>&1 | tail -10
```

Expected: install succeeds; tests pass. On device: verify home renders, all 3 home variants work.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt
git commit -m "refactor(home/stateflows): retire _displayCatalogRows

All readers + writers now route through ResolvedDisplaySurfaceRepository.
railStructure becomes a derived projection from the typed surface.

Plan B Phase 3.9. Gates the Snapshot.catalogRows drop (Phase 4 final).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

## Task 17: Retire `_displayHeroItems`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`

- [ ] **Step 1: Mirror Task 16's pattern for hero items**

Replace `_displayHeroItems: MutableStateFlow<List<MetaPreview>>` with a derived flow from the typed surface filtered to hero items. The existing `HeroDisplayItem` typed projection (Phase 3.6.2) handles this.

```kotlin
// Replace `_displayHeroItems` with derived flow:
internal val displayHeroItems: StateFlow<List<HeroDisplayItem>> = resolvedDisplaySurfaceRepository
    .observeHomeSurface(profileManager.activeProfileId.value)
    .map { items -> heroItemsFromResolved(items) }  // existing helper
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
```

- [ ] **Step 2: Update consumers expecting `List<MetaPreview>`**

Each consumer either accepts the typed `List<HeroDisplayItem>` directly or — if it can't be changed immediately — bridges via a final-mile MetaPreview lookup. Bridges should be marked with a `// TODO Phase 6` comment so cleanup catches them.

- [ ] **Step 3: Compile + smoke + commit**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
# Smoke test: cold-start, verify hero rotates correctly.
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt
git commit -m "refactor(home/stateflows): retire _displayHeroItems

Hero items now derived from the typed surface filtered to hero
candidates. HeroDisplayItem is the consumer-facing type. MetaPreview
bridge comments marked for Phase 6 cleanup.

Plan B Phase 3.9 complete.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Phase 6: Phase 4 Final — Snapshot Reshape + Helper Deletion

## Task 18: Drop `Snapshot.catalogRows` + `heroItems` from persisted snapshot

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`

- [ ] **Step 1: Schema bump**

In `HomeCatalogSnapshotStore.kt`:

```bash
grep -n "SCHEMA_VERSION\|data class Snapshot\b" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt | head -5
```

Bump `SCHEMA_VERSION` (4 → 5 per Phase 3.7 deferred work). At read time, any snapshot with version < 5 is discarded (cold-start fallback to fresh fetch).

- [ ] **Step 2: Drop the fields from the data class**

Replace:
```kotlin
data class Snapshot(
    val catalogRows: List<CatalogRow>,
    val fullCatalogRows: List<CatalogRow>,
    val heroItems: List<MetaPreview>,
    // ... rest
)
```

With:
```kotlin
data class Snapshot(
    val rails: List<Rail>,
    val heroItemKeys: List<String>,
    // ... rest unchanged
)
```

`Rail` carries `(railKey, catalogId, addonId, type, itemKeys: List<String>)` — structure only, no MetaPreview.

- [ ] **Step 3: Update writers + readers**

Writers in `HomeViewModelCatalogPipeline` (the `persistMergedHomeSnapshotIfNeeded` path and similar) build `Snapshot(rails = …, heroItemKeys = …)` from the typed surface, not from `CatalogRow.items`.

Readers project Snapshot → typed surface restoration via the existing `ResolvedDisplaySnapshotStore.restoreFromDisk(items, profileId)` infrastructure (Phase 3.7 narrowed, commit `f705ad049`).

- [ ] **Step 4: Gut the ~920 LOC sanitization subsystem**

`HomeCatalogSnapshotStore.kt` lines 545–1466 contain MetaPreview-content sanitization (`repairArtworkWriteInvariants`, `sanitizeForSnapshot`, `posterProviderTagMismatches`, `buildRailMemberships`). With the field drop, these have no inputs.

```bash
grep -n "fun repairArtworkWriteInvariants\|fun sanitizeForSnapshot\|fun posterProviderTagMismatches\|fun buildRailMemberships" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt
```

Delete each function and its callers within the file.

- [ ] **Step 5: Compile + run snapshot tests**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
./gradlew :app:testUniversalDebugUnitTest --tests "*HomeCatalogSnapshotStore*" --max-workers=1 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL. Tests may need rewrite — the 18+ tests asserting MetaPreview content sanitization are obsolete; delete them. Tests asserting Snapshot serialization round-trip are kept, updated to use the new shape.

- [ ] **Step 6: On-device cold-start verification**

```bash
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 shell run-as com.nexiodebug.tv rm -rf /data/data/com.nexiodebug.tv/files/home-catalog-snapshot-v1
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
```

Clear the snapshot file to simulate fresh first-cold-start with the new schema. Verify home renders correctly. Then second cold-start should restore from the v5 snapshot.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt \
        app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt
git commit -m "feat(home/snapshot): reshape Snapshot to Rail/heroItemKeys only

Drops Snapshot.catalogRows / fullCatalogRows / heroItems (denormalized
MetaPreview/CatalogRow content). Schema bump 4 → 5; v4 snapshots are
discarded on read.

Gutted ~920 LOC of MetaPreview-content sanitization (no longer needed
once typed authority is the single rendering source).

Plan B Phase 4 final — closes Phase 3.7 spec.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

## Task 19: Delete `HomeHydrationOverlayApplier.kt`

**Files:**
- Delete: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt`

- [ ] **Step 1: Verify zero readers remain**

```bash
grep -rn "HomeHydrationOverlayApplier\|overlayFromMap\|homeOverlayItemKey\|displayHashForHomeOverlay" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ --include="*.kt" 2>&1 | grep -v "HomeHydrationOverlayApplier.kt" | head -10
```

If any reader remains, migrate it first. Most likely:
- `overlayFromMap(...)` callers — replaced by `overlayFromMapEnriched(...)` from CrossID Task 6. The legacy non-suspend variant has no consumers.
- `homeOverlayItemKey()` extension — may be used by tests; if so, replace with `homeDisplayItemKey(apiType, id)` directly.
- `displayHashForHomeOverlay()` — check call sites.

- [ ] **Step 2: Delete the file**

```bash
rm /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt
```

- [ ] **Step 3: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL. Any lingering reference needs inlining or replacement.

- [ ] **Step 4: Commit**

```bash
git add -u app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt
git status -sb | head -3
git commit -m "feat(home/cleanup): delete HomeHydrationOverlayApplier.kt

Last reader was overlayFromMap (sync variant) — replaced by the suspend
overlayFromMapEnriched variant from CrossID Task 6 (commit d78546243).
The Phase 4 partial cleanup (commit 86bcac70d) already deleted the
overlay-application functions in this file. The remaining helpers
(homeOverlayItemKey, displayHashForHomeOverlay) had no consumers after
the producer flip + boundary fix landed.

Plan B Task 26 closed.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Phase 7: Phase 3.10 — Final Acceptance + Tag

## Task 20: Final heap-acceptance gate

**Files:** None.

- [ ] **Step 1: Cold-start sequence**

```bash
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 90
```

90s soak — full home + hero rotation + screensaver candidates loaded.

- [ ] **Step 2: Capture heap dump + leak suspects**

```bash
PID=$(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv)
adb -s 192.168.50.98:5555 shell am dumpheap $PID /data/local/tmp/heap-phase7-final.hprof
sleep 6
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-phase7-final.hprof /tmp/heap-phase7-final.hprof
heaptrail -i /tmp/heap-phase7-final.hprof --leak-suspects 0.03 --exclude-soft-weak -t 12 2>&1 | head -60
heaptrail -i /tmp/heap-phase7-final.hprof -t 200 2>&1 | grep -E "MetaPreview|CatalogRow|ResolvedDisplayItem|ModernHomeRowItem|HeroDisplayItem" | head -10
```

- [ ] **Step 3: Acceptance criteria**

| Class | Phase 1 baseline | Phase 7 acceptance |
|---|---|---|
| `MetaPreview` | ~430 | ≤ 100 (only non-home surfaces) |
| `CatalogRow` | ~40 | ≤ 5 (StateFlow retired) |
| `ResolvedDisplayItem` | ~100 | ≤ 200 (typed authority is dominant) |
| `ModernHomeRowItem` | ~115 | ~115 (unchanged — rendering carrier) |
| `HomeHydrationOverlay` | — | unchanged |

The `AnimeIdMappingService` 14.94 MiB and `TrailerPlayer` ExoPlayer ~1.5 MiB stay as today (separate optimizations, not in scope).

If any acceptance criterion fails, STOP and trace before tagging.

- [ ] **Step 4: Compare against Phase 1 baseline + commit the diff**

```bash
heaptrail --diff-from /tmp/heap-plan-b-baseline.hprof --diff-to /tmp/heap-phase7-final.hprof --diff-by bytes --top 30 2>&1 | head -40
```

Document the per-class delta in the next commit message.

## Task 21: Final commit — tag migration complete + memory entry

**Files:**
- Update: `/Users/jneerdael/.claude/projects/-Users-jneerdael-Scripts-nexio/memory/MEMORY.md`
- Create: `/Users/jneerdael/.claude/projects/-Users-jneerdael-Scripts-nexio/memory/project_plan_b_migration_complete_<date>.md`

- [ ] **Step 1: Write the migration-complete memory entry**

```markdown
---
name: Plan B UI consumption migration complete (<date>)
description: feat/resolved-display-authority's UI consumption migration finished. Every home surface (rails, hero, screensaver, detail, continue-watching) consumes the typed ResolvedDisplaySurfaceRepository instead of MetaPreview / CatalogRow. Legacy _displayCatalogRows / _displayHeroItems StateFlows retired; Snapshot reshape complete; HomeHydrationOverlayApplier deleted. Heap acceptance gate passed.
type: project
---

**21 tasks shipped across 7 phases (commits <sha1>..<shaN>).**

[Insert migration summary, heap diff stats, and link to follow-up plans.]
```

- [ ] **Step 2: Add MEMORY.md index entry**

```markdown
- [Plan B migration complete <date>](project_plan_b_migration_complete_<date>.md) — typed-authority migration done; all 5 surfaces consume ResolvedDisplaySurfaceRepository; legacy StateFlows / Snapshot fields / HomeHydrationOverlayApplier retired; heap acceptance passed
```

- [ ] **Step 3: Optionally create a git tag**

```bash
git tag -a plan-b-migration-complete -m "Plan B (feat/resolved-display-authority) UI consumption migration complete

All 5 surfaces (Home Rails, Hero, Screensaver, Continue Watching, Detail)
consume ResolvedDisplaySurfaceRepository as their authoritative source.
Legacy _displayCatalogRows / _displayHeroItems retired; Snapshot reshaped;
HomeHydrationOverlayApplier deleted.

Heap acceptance:
  MetaPreview: 430 → <count>
  CatalogRow: 40 → <count>
  [...]"
git push origin plan-b-migration-complete
```

- [ ] **Step 4: No commit. The tag IS the commit marker.**

---

## Self-Review

**Spec coverage check:**
- ✅ Phase 1 — Daredevil verification gate (Task 1)
- ✅ Phase 2 — Surface 5 Detail migration (Tasks 2-7)
- ✅ Phase 3 — Surface 3 Screensaver completion (Tasks 8-10)
- ✅ Phase 4 — Surface 1 callback reshape + MetaPreview drop (Tasks 11-14)
- ✅ Phase 5 — Phase 3.9 StateFlow retirement (Tasks 15-17)
- ✅ Phase 6 — Phase 4 final Snapshot reshape + helper deletion (Tasks 18-19)
- ✅ Phase 7 — Phase 3.10 acceptance + tag (Tasks 20-21)

**Placeholder scan:** Three `[describe specific files + changes]` / `[list reader sites here]` / `[Insert migration summary…]` placeholders in commit-message templates — INTENTIONAL. These are filled in at execution time once the audit/work yields real values. The task body provides exact commands to find the values; the placeholder cannot be filled in advance because it requires running the audit.

**Type consistency:** `MetaDetailsResolvedFields`, `ModernHomeRowItem`, `IdleScreensaverDisplayItem`, `HeroDisplayItem`, `ResolvedDisplayItem`, `ResolvedDisplaySurfaceRepository` — all consistent across tasks. Callback signature flip from `(MetaPreview)` → `(ModernHomeRowItem)` is uniform across Tasks 12-14.

**Scope discipline:** The stale-overlay invalidation problem (RPDB premium not rendering for TMDB rails) is **NOT** addressed in this plan. That's an architectural follow-up to be planned after this one ships. Documented in the "Scope explicitly excluded" section.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-11-plan-b-final-migration.md`. 21 tasks across 7 phases. Two execution options:

**1. Subagent-Driven (recommended)** — Fresh subagent per task with two-stage review (spec compliance + code quality) after each. Estimated 4-8 hours wall-clock depending on review iteration.

**2. Inline Execution** — Execute the 21 tasks in this session using executing-plans, with checkpoints between phases. Estimated 6-12 hours wall-clock.

Which approach?

---

## Audit Findings & Revised Execution Model (added 2026-05-11)

After Phase 4 shipped, a Phase 5 audit (commit `5332ad78c`) revealed that Tasks 16–21 are **larger and more risk-laden than the plan initially scoped**. The plan's original assumption — "each phase is independently shippable in one or two commits" — does not hold for Phases 5–7. The following findings revise the execution model for the remaining work.

### Audit finding 1: `_displayCatalogRows` cannot be cleanly "derived from typed surface"

The plan's Task 16 Step 1 suggested:

```kotlin
val rails: List<ResolvedRailRow> = resolvedDisplaySurfaceRepository
    .observeHomeSurface(profileId)
    .map { items -> projectionCache.projectRails(items, currentRailStructure) }
```

But `CatalogRow.items: List<MetaPreview>` is the legacy item shape. The typed surface holds `List<ResolvedDisplayItem>` — there is no MetaPreview list to project from on every emission. Materializing MetaPreview from ResolvedDisplayItem on every typed-surface emission risks the **exact 2026-05-09 GC death-spiral** documented in `project_plan_b_session_2026_05_09.md` (3M allocs/sec, app unresponsive in 60s).

**Implication:** Phase 5 cannot retire `_displayCatalogRows` without first migrating every consumer to consume `ResolvedDisplayItem` directly. That migration is its own multi-task effort, **not a single "audit + retire" task pair**.

### Audit finding 2: Classic + Grid Home not migrated to typed surface

Plan B's original Task 5 (Surface 1 — Classic + Grid consumption migration) was reverted in 2026-05-09 (commit `8ced1ca49`) due to the death spiral and has NEVER been re-attempted. The current main has:

- **Modern Home**: typed surface consumed via `modernHomePresentation` builder pipeline
- **Classic Home**: still consumes `displayCatalogRows: StateFlow<List<CatalogRow>>` directly
- **Grid Home**: still consumes `displayCatalogRows` directly

Retiring `_displayCatalogRows` requires Classic + Grid to be migrated to the typed surface first. The plan as written does not include this prerequisite work.

### Audit finding 3: HomeViewModelCatalogPipeline scale is 4,487 lines

Writer/reader counts per the audit:
- `_displayCatalogRows`: 9 writer/reader sites — most embedded in deeply nested state machines (snapshot persistence at line 3199, restoration paths, refresh-trigger logic)
- `_displayHeroItems`: 5 writer/reader sites
- Each writer is structurally load-bearing — the catalog pipeline's internal state depends on the StateFlow's value reflecting the current rendered catalog

**Implication:** Each writer migration is its own focused commit; inline batch retirement risks subtle ordering bugs.

### Audit finding 4: `HomeHydrationOverlayApplier.kt` cannot yet be deleted

The plan's Task 19 says "verify zero readers remain". The audit found **active readers** in:
- `HomeViewModelPresentationPipeline.kt` (7 sites)
- `HomeViewModelCatalogPipeline.kt` (3 sites)
- `HomeResolvedDisplayMapper.kt`
- `HomeHydrationCoordinator.kt`
- `HomeCatalogRefreshCoordinator.kt`

The surviving helpers (`homeOverlayItemKey`, `displayHashForHomeOverlay`, `overlayFromMap`, `overlayFromMapEnriched`) all have non-trivial consumers. Each must be inlined or replaced before the file can be deleted.

### Audit finding 5: `applyHomeSnapshotToUiPipeline` fans out to three flows

The plan's Task 18 (Snapshot reshape) requires rewriting `applyHomeSnapshotToUiPipeline` (around line 3199), which currently writes the snapshot's denormalized fields to:
1. `_displayCatalogRows` (deleted by Phase 5)
2. `_displayHeroItems` (deleted by Phase 5)
3. `_uiState.gridItems` (this is a separate state location not in the plan)

Dropping `Snapshot.catalogRows`/`heroItems` requires restructuring this pipeline output to consume the typed surface instead. The `gridItems` consumer adds an unexpected dependency the plan didn't account for.

### Revised execution model for Phases 5–7

The audit's recommendation is to execute the remaining work as **one focused subagent task at a time**, with **on-device cold-start + 90s soak + GC log inspection between each task**. The plan's "Subagent-Driven (recommended)" handoff above is the correct path; **Inline Execution against this seam is not advised** given the documented death-spiral risk.

Concrete task sequencing (replaces Phase 5–7 task list above):

| New Task | Description | Risk |
|---|---|---|
| **5a** | Migrate `ClassicHomeContent.kt` to consume typed surface via `modernHomePresentation` (or sibling pipeline). Add typed `ClassicHomeRowItem` projection if Classic has different rendering needs than Modern. | Medium — Classic is less-used than Modern; failures are visible but lower-blast-radius. |
| **5b** | Migrate `GridHomeContent.kt` to consume typed surface. Mirror approach from 5a. | Medium — same reasoning. |
| **5c** | Audit `_uiState.gridItems` consumers; either retire (route via typed surface) or document its intended scope. | Low — discovery-only. |
| **5d** | Retire `_displayHeroItems` — fewer writer/reader sites (5) than `_displayCatalogRows`; smaller blast radius for testing the retirement pattern. | Medium — `HeroDisplayItem` typed projection already exists, just needs wiring through. |
| **5e** | Retire `_displayCatalogRows` — the load-bearing change. Touches 9 sites in `HomeViewModelCatalogPipeline.kt` + 14+ readers across `HomeScreen.kt` Classic/Grid/Modern paths (after 5a + 5b migrate those). | **HIGH** — direct repeat of the 2026-05-09 death-spiral seam. Requires per-commit on-device GC log + 90s soak verification. |
| **6a** | Inline / migrate `HomeHydrationOverlayApplier`'s 4 surviving helpers across the 5 consumer files. | Medium — mechanical refactor across many files. |
| **6b** | Delete `HomeHydrationOverlayApplier.kt`. | Trivial after 6a. |
| **6c** | Rewrite `applyHomeSnapshotToUiPipeline` to consume typed surface state instead of fanning out to legacy flows. | High — load-bearing for cold-start UX (snapshot restore is the first-paint mechanism). |
| **6d** | Drop `Snapshot.catalogRows` / `heroItems` from `HomeCatalogSnapshotStore` data class. Schema bump 4→5; v4 snapshots discarded on read. | Medium — gated on 6c being clean. |
| **6e** | Gut the ~920 LOC MetaPreview-content sanitization in `HomeCatalogSnapshotStore` (`repairArtworkWriteInvariants`, `sanitizeForSnapshot`, `posterProviderTagMismatches`, `buildRailMemberships`) + delete the 18+ associated tests. | Low — pure removal after 6d. |
| **7** | Final heap acceptance + migration-complete tag. | Verification only. |

**Per-task verification protocol** (CLAUDE.md rule #8 + death-spiral safeguards):

After each task above:

```bash
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 90  # not 60 — Modern Home steady state needs longer to settle in this branch

# Death-spiral signature check (ANR class from 2026-05-09):
adb -s 192.168.50.98:5555 logcat -d -t 5000 | grep -E "m\.nexiodebug.t.*Background concurrent" | tail -10
# Healthy: 24 s+ between GCs in steady state; LOS freed per cycle < 5 MB.
# Death spiral: GCs every < 1s for multiple minutes; LOS > 30 MB per cycle.

# Heap dump only after this passes:
PID=$(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv)
adb -s 192.168.50.98:5555 shell am dumpheap $PID /data/local/tmp/heap-task-<N>.hprof
```

**If GC log shows death-spiral signature in any task, REVERT immediately**. The boundary fix + producer-side memoization handle the reference-stability requirement, but a misplaced `.map { ... }` chain that allocates per emission can re-trigger the cascade. Indexed-for loops + `remember`-stabilized projections + `stateIn` (not `shareIn` with buffer) are required.

### Pickup guide for the next session

Start the next session by:

1. **Read this file's audit findings section (above).**
2. **Read `project_plan_b_session_2026_05_09.md`** for the death-spiral root cause and `c2f132f0e`-style memoization patterns.
3. **Read `project_boundary_non_downgrade_fix_2026_05_11.md`** for the typed authority's non-downgrade guarantee.
4. **Read the audit commit** (`git show 5332ad78c`) for the writer/reader site inventory.
5. **Dispatch Task 5a as a focused subagent** with full context, on-device verification per the protocol above, and explicit blocker criteria.
6. After 5a verifies clean, proceed to 5b. Do NOT batch multiple tasks per dispatch — each task is a checkpoint.

Estimated wall-clock for the revised 5a–7 sequence: **4–8 hours across 2–3 sessions**, depending on review iteration and how cleanly Classic/Grid migrate.
