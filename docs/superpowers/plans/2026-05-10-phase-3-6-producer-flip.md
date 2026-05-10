# Phase 3.6 — Catalog pipeline producer flip (highest risk)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Each sub-task ships in its own commit with smoke + (where noted) heap-dump perf gate.

**Goal:** Stop applying hydrated overlays at the producer level (`composeHydratedHomeOverlaySnapshot`), so that `_displayCatalogRows.value` and `_displayHeroItems.value` carry first-paint / structure-only MetaPreview shells. The typed authority (`ResolvedDisplaySurfaceRepository` → `ModernHomeRowItem` / `HeroDisplayItem`) becomes the sole source of hydrated content for Modern Home.

**Architecture:** The data the producer-level apply was hydrating onto MetaPreview (description, genres, releaseInfo, tomatoesRating in addition to artwork) is already present on `ResolvedDisplayItem.display: ResolvedDisplayFields` and `ResolvedDisplayItem.rating: TitleRating`. The typed projections (`ModernHomeRowItem`, `HeroDisplayItem`) currently expose only artwork + title + year + rating; `buildCatalogItem` reads description/genres/releaseInfo/tomatoesRating from the legacy MetaPreview as a fallback. Phase 3.6 expands the typed projections to expose those fields, migrates `buildCatalogItem` (and the hero builder) to read from the resolved authority first, then drops the producer-level apply.

**Tech Stack:** Kotlin · Compose · existing `ResolvedDisplaySurfaceRepository`, `HomeResolvedDisplayMapper`, `ModernHomeRowItem`, `HeroDisplayItem`, `composeHydratedHomeOverlaySnapshot`.

**Spec source:** `docs/superpowers/specs/2026-05-10-phase-3-catalog-pipeline-restructure-design.md` — sub-project 3.6 (with Phase 2D folded in per spec).

**Risk profile:** HIGH. This is the seam that caused the 2026-05-09 GC death-spiral (`5cf8c6dc5..3204278ee` reverted via `8ced1ca49`). Mitigations: (1) typed projections expanded BEFORE producer flip, so visible-content callers don't fall back to raw provider data; (2) heap-dump perf gate after every sub-task; (3) producer flip lands as the LAST sub-task — every sub-task before it is a content-side migration with no GC churn risk.

**End-state acceptance:**

1. `composeHydratedHomeOverlaySnapshot` returns its inputs unchanged (the function body is `HydratedHomeOverlaySnapshotComponents(displayRows, fullRows, heroItems)` with no apply call).
2. `buildCatalogItem` reads `description`, `genres`, `releaseInfo`, `tomatoesRating` from `resolved` (typed authority) first; MetaPreview is consulted only when `resolved` is null (transient race).
3. `applyToHeroItem` (`HomeViewModelPresentationPipeline.kt:1074`) is unreachable in production code (Phase 4 will delete it).
4. `applyHydratedHomeOverlays` and `applyHydratedHomeOverlaysToHeroItems` are unreachable in production code (Phase 4 will delete `HomeHydrationOverlayApplier.kt`).
5. Heap-dump (`heaptrail --find-referrers HomeDisplayMetadata --hops 2`) shows zero retainers in `HomeViewModelCatalogPipeline.*` chains.
6. GC pattern under sustained Modern Home use matches the post-Phase-2C-ext baseline (MetaPreview ≤ 1500, CatalogRow ≤ 100, no death-spiral).

---

## File Structure

### Modified files

| File | Change | Sub-task |
|---|---|---|
| `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRowItem.kt` | Add `description`, `genres`, `releaseInfo`, `tomatoesRating` fields. Update `from(ResolvedDisplayItem)` and `fromMetaPreview` factories. | 3.6.1 |
| `app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt` | Add `tomatoesRating: String?` to `ResolvedDisplayFields` (description/genres/releaseDate already present). | 3.6.1 |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt` | Source the new `tomatoesRating` field from the overlay's `tomatoesRating` slot when available. | 3.6.1 |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HeroDisplayItem.kt` | Add same fields as ModernHomeRowItem (Phase 2D fold-in). | 3.6.2 |
| `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt` | `buildCatalogItem`: read description/genres/releaseInfo/tomatoesRating from `resolved` first, MetaPreview as fallback only. | 3.6.3 |
| `app/src/main/java/com/nexio/tv/ui/screens/home/<hero builder file>` | Same migration for hero — read from resolved, MetaPreview as fallback. | 3.6.4 |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` | `composeHydratedHomeOverlaySnapshot` body returns inputs unchanged. Drop private `applyHydratedHomeOverlaysToHeroItems`. | 3.6.5 |

### Untouched (deferred to Phase 3.7+ / Phase 4)

- `HomeHydrationOverlayApplier.kt` — file becomes unreachable but remains for Phase 4 deletion.
- `HomeViewModelPresentationPipeline.applyToHeroItem` — same.
- `HomeDisplayMetadata.applyTo` / `applyToPreview` / `mergeFallback` / `coalesceWith` — same.
- `_displayCatalogRows` / `_displayHeroItems` / `_displayContinueWatchingItems` StateFlows — Phase 3.9 retires them.
- `HomeCatalogSnapshotStore.Snapshot` shape — Phase 3.7 reshapes.
- `ContinueWatchingMetadataSnapshot` shape — Phase 3.8 reshapes.

---

## Sub-task 3.6.1 — Expand `ModernHomeRowItem` and `ResolvedDisplayFields`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRowItem.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`

- [ ] **Step 1**: In `ResolvedDisplaySurfaceModels.kt`, add `tomatoesRating: String?` to `ResolvedDisplayFields`. Update `equals`/`hashCode` via `data class` regen.

- [ ] **Step 2**: In `ModernHomeRowItem.kt`, add fields:
  ```kotlin
  val description: String?,
  val genres: List<String>,
  val releaseInfo: String?,
  val tomatoesRating: String?,
  ```
  Update `from(ResolvedDisplayItem)`:
  ```kotlin
  description = resolved.display.overview,
  genres = resolved.display.genres,
  releaseInfo = resolved.display.releaseDate,
  tomatoesRating = resolved.display.tomatoesRating,
  ```
  Update `fromMetaPreview(meta: MetaPreview)`:
  ```kotlin
  description = meta.description,
  genres = meta.genres,
  releaseInfo = meta.releaseInfo,
  tomatoesRating = meta.tomatoesRating,
  ```

- [ ] **Step 3**: In `HomeResolvedDisplayMapper.kt`, locate where `ResolvedDisplayFields` is constructed. Source `tomatoesRating` from the overlay's tomatoes slot when present, else `null`. Verify the existing `overview`/`genres`/`releaseDate` already pull from the overlay (no change needed if so).

- [ ] **Step 4**: Compile. `./gradlew :app:compileUniversalDebugKotlin 2>&1 | tail -5` → BUILD SUCCESSFUL.

- [ ] **Step 5**: Build APK + smoke (rule #8 sequence) → no FATAL/ANR/ClassCast/NoSuchMethod.

- [ ] **Step 6**: Commit by explicit path (rule #7).

**Risk:** LOW. Pure additive — new fields with sensible defaults. `buildCatalogItem` doesn't read them yet (3.6.3).

---

## Sub-task 3.6.2 — Expand `HeroDisplayItem` (Phase 2D fold-in)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HeroDisplayItem.kt`

- [ ] **Step 1**: Add same fields (`description`, `genres`, `releaseInfo`, `tomatoesRating`, plus `runtime: String?`) to `HeroDisplayItem`. The hero already consumes more fields than rails (`applyToHeroItem` writes `description`, `genres`, `releaseInfo`, `runtime`).

- [ ] **Step 2**: Update factory(ies) to populate from `ResolvedDisplayItem.display.*`.

- [ ] **Step 3**: Compile + smoke + commit.

**Risk:** LOW. Additive.

---

## Sub-task 3.6.3 — `buildCatalogItem` reads from resolved authority first

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`

- [ ] **Step 1**: In `buildCatalogItem` (line 612), change:
  ```kotlin
  val description = displayMetadata.description ?: item?.description
  val tomatoesText = (displayMetadata.tomatoesRating ?: item?.tomatoesRating)
      ?.let(::formatPreviewTomatoesRating)
  val genres = displayMetadata.genres.ifEmpty { item?.genres ?: emptyList() }.take(3)
  ```
  to:
  ```kotlin
  val description = resolved.description ?: item?.description
  val tomatoesText = (resolved.tomatoesRating ?: item?.tomatoesRating)
      ?.let(::formatPreviewTomatoesRating)
  val genres = resolved.genres.ifEmpty { item?.genres ?: emptyList() }.take(3)
  ```
  And `trailerReleaseInfo`:
  ```kotlin
  val trailerReleaseInfo = resolved.releaseInfo ?: displayMetadata.releaseInfo ?: item?.releaseInfo
  ```

- [ ] **Step 2**: Architecture-pin comment (lines 624-636) — `displayMetadata` lookup remains for the architecture test (RailPreviewLifecycleArchitectureTest), but `displayMetadata.description/genres/etc.` reads can be replaced with `resolved.*` reads. Keep `item.toFirstPaintHomeDisplayMetadata()` invocation; just don't read the merged fields off it for primary content.

- [ ] **Step 3**: Compile + smoke + heap-dump perf gate. Compare MetaPreview / CatalogRow counts to the post-2C-ext baseline (1,285 / 52). Expect equal or lower.

- [ ] **Step 4**: Commit by explicit path.

**Risk:** MEDIUM. Visible-content path migration. UX regression possible if the typed authority's `overview`/`genres`/`releaseDate` aren't populated as reliably as `displayMetadata`'s were. Smoke test must include rail content visible verification (focused card description visible, genres chip visible).

---

## Sub-task 3.6.4 — Hero builder reads from resolved authority first

**Files:**
- Modify: hero builder (locate during execution; likely `ModernHomeHero.kt` or `HeroPanel.kt`)

- [ ] **Step 1**: Find the hero render-path consumer that currently reads MetaPreview `description` / `genres` / `releaseInfo` / `runtime` / `tomatoesRating`. Migrate to read from `HeroDisplayItem` (resolved) with MetaPreview fallback.

- [ ] **Step 2**: Compile + smoke + commit.

**Risk:** MEDIUM. Same as 3.6.3 but for hero.

---

## Sub-task 3.6.5 — Producer flip

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`

- [ ] **Step 1**: In `composeHydratedHomeOverlaySnapshot` (line 627), replace the function body:
  ```kotlin
  internal fun composeHydratedHomeOverlaySnapshot(
      displayRows: List<CatalogRow>,
      fullRows: List<CatalogRow>,
      heroItems: List<MetaPreview>,
      overlaysByItemKey: Map<String, HydratedHomeOverlay>,
      heroTmdbSettings: TmdbSettings = TmdbSettings()
  ): HydratedHomeOverlaySnapshotComponents {
      // Phase 3.6 — producer no longer applies overlays. The typed authority
      // (ResolvedDisplaySurfaceRepository) is the sole source of hydrated content
      // for Modern Home; rail/hero structure is the only thing the producer emits.
      // Overlay map remains parameter so callers don't change shape; ignored here.
      return HydratedHomeOverlaySnapshotComponents(
          displayRows = displayRows,
          fullRows = fullRows,
          heroItems = heroItems
      )
  }
  ```

- [ ] **Step 2**: Drop the private `applyHydratedHomeOverlaysToHeroItems` extension at line 652 (now unreachable).

- [ ] **Step 3**: Compile + smoke (rule #8) + heap-dump perf gate.

  Heap-dump expectations:
  - MetaPreview count: ≤ 1,500 (vs 1,285 baseline — small variance OK)
  - CatalogRow count: ≤ 100 (vs 52 baseline)
  - HomeDisplayMetadata count: should DROP (overlay-merged HomeDisplayMetadata no longer produced via apply path; only first-paint projections remain)
  - Typed projections (ModernHomeRowItem, HeroDisplayItem, ResolvedDisplayItem): unchanged
  - GC pattern: no death-spiral signature (no Background concurrent GCs every <1s with >30 MB LOS)

  If heap regresses or smoke fails, REVERT this sub-task (not the whole phase) and investigate.

- [ ] **Step 4**: Commit by explicit path.

**Risk:** HIGH. The actual flip. By this point the consumers have all migrated, so this should be a no-op for visible UX, but heap behavior is the sensitive signal.

---

## Sub-task 3.6.6 — Final 3.6 verification

- [ ] **Step 1**: 60-second Modern Home soak (scroll, navigate between rails, focus different items).

- [ ] **Step 2**: Capture heap dump. Confirm:
  - `heaptrail --find-referrers HomeDisplayMetadata --hops 2` shows no retainers in `HomeViewModelCatalogPipeline.*` chains.
  - GC pattern healthy.

- [ ] **Step 3**: Update `project_phase2c_ext_heap_milestone.md` memory with post-3.6 numbers.

---

## Self-review

**1. Spec coverage:**

The Phase 3 design spec sub-project 3.6 says: "After 3.6, the producer no longer applies HomeHydrationOverlayApplier/applyTo — it composes structure only; item hydration is the authority's job."

Tasks 3.6.1-3.6.5 implement exactly that. The spec also folds Phase 2D into 3.6 (hero overlay path) — covered by 3.6.2 + 3.6.4.

**2. Placeholder scan:** None. Each sub-task has explicit file paths, expected end state, and a heap-gate exit condition where applicable.

**3. Type consistency:**
- `ResolvedDisplayFields.tomatoesRating: String?` — new, mirrors existing `overview: String?` shape.
- `ModernHomeRowItem.{description, genres, releaseInfo, tomatoesRating}` — new, sourced from `ResolvedDisplayFields`.
- `HeroDisplayItem.{description, genres, releaseInfo, runtime, tomatoesRating}` — new.

**4. Risk:** Mitigated by sequencing — content-side projection expansion lands first (3.6.1-3.6.4), producer flip lands last (3.6.5) when no consumer needs MetaPreview's hydrated fields anymore.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-10-phase-3-6-producer-flip.md`.

Recommended execution: **Subagent-Driven Development** — fresh subagent per sub-task with two-stage review (spec compliance + code quality). Sub-task 3.6.5 (producer flip) MUST land in its own commit, preceded by a heap-dump baseline capture.
