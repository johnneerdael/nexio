# Home MetaPreview Elimination — Design

**Date:** 2026-05-10
**Status:** approved during brainstorming, awaiting plan-writing
**Companion plan (umbrella):** to be created via `writing-plans` after this spec is approved
**Predecessor:** `docs/superpowers/plans/2026-05-09-resolved-display-ui-consumption-migration.md` (Plan B Surfaces 1-5, all shipped and tagged through `plan-b-surface-5-detail`)

---

## Goal

Finish enforcing CLAUDE.md hard rule #1 across all surfaces that render typed-artwork rail cards: typed `posterRef` / `backdropRef` / `logoRef` slots strict at the consumer, no cross-type fallback, no surface receiving a raw `MetaPreview`. Eliminate `MetaPreview` as a transport in the home rendering pipeline so the reducer's non-downgrade guarantee applies end to end.

## Why this work, now

Plan B Surfaces 1-5 closed bypass sites at the surface boundary: composables, UiState reads, and the `_resolvedRailRows` / `_resolvedHeroItems` / `_resolvedContinueWatchingItems` projections all consume typed shapes. But `HomeViewModelCatalogPipeline` still produces hydrated `List<CatalogRow>` of `MetaPreview` internally, which feeds:

1. The resolved-display authority via `rowsForResolvedDisplaySurface(...)` — the typed projections' upstream.
2. Legacy MetaPreview consumers — `GridContentCard` (9 callers across home/detail/search/library/organization/cast/feed), the focused-CW hero-preview side channel in `buildContinueWatchingItem`, `ContinueWatchingMetadataSnapshot.mergeFallback`, `HomeProviderLocalizedMetadataOverlay.applyToProviderLocalizedHomeItem`, `HomeViewModelPresentationPipeline.applyToHeroItem`.

Architecturally:
- **Non-downgrade enforcement** (Plan A reducer) only protects fields that flow through the reducer. Producer-pipeline MetaPreview consumers bypass it.
- **Type-strict slot contract** (rule #1) is violated wherever raw `MetaPreview.poster: String?` reaches a card primitive that could do `poster ?: backdrop` cross-type fallback.
- **Memory-leak retention shape** (947K MetaPreview observed pre-Plan-A) was caused by `MutableStateFlow<List<MetaPreview>>` patterns retaining shadow Compose snapshots. Surfaces 1-5 removed observed lists from `HomeUiState`; this work removes them from the producer pipeline.

This is the producer half of the migration that Plan B was the consumer half of.

## Target architecture

The home pipeline emits typed projections end-to-end. `MetadataDisplayRouter` outputs flow through the catalog pipeline into typed `_displayCatalogRails` / `_displayHeroItems` (typed) / `_displayContinueWatchingItems` (typed) StateFlows. `ResolvedDisplaySurfaceRepository` is populated from those typed shapes. Surfaces consume the resolved authority. No composable receives a raw `MetaPreview`. Every `GridContentCard` caller passes a typed projection that implements a shared `RailCardData` interface.

`MetaPreview` is permitted only at non-rendering boundaries:
- Addon-API parsing layer (incoming raw provider data).
- Persistence formats (`SavedLibraryItem`, `ContinueWatchingMetadataSnapshot`, `HomeCatalogSnapshotStore.Snapshot`) — converted to typed at read boundary.
- Navigation arguments where existing serialization expects MetaPreview shape (separate cleanup).

## End-state acceptance

1. `grep -rn "GridContentCard(item:" app/src/main` shows ALL callers passing a typed projection (or the shared `RailCardData` interface).
2. `heaptrail --find-referrers MetaPreview --hops 3` from a Modern Home soak heap shows retention only in: addon-API parsing (transient), persistence schema instances (bounded), and current navigation back-stack. Zero retainers in `HomeViewModel.*` / `_display*Items.*` / `HomeCatalogSnapshotStore.*` / `applyHydratedHomeOverlays.*` chains.
3. `HomeHydrationOverlayApplier.kt`, `HomeDisplayMetadata.applyTo`, `HomeDisplayMetadata.mergeFallback`, `withCompatiblePersistedInternalPoster`, `displayHashForHomeOverlay`, `rowsForResolvedDisplaySurface` deleted.
4. `HomeViewModelPresentationPipeline.applyToHeroItem` deleted (or migrated to operate on typed shapes).
5. CLAUDE.md hard rule #1 updated with the new "no MetaPreview in home rendering pipeline" invariant; producer-side examples added.

## Phase decomposition (13 sub-projects)

Each sub-project ships independently with its own commit, smoke test, and (where applicable) heap-dump perf gate. Each gets its own implementation plan via `writing-plans`.

### Phase 0 — Foundation: shared `RailCardData` interface + `GridContentCard` migration

Define a Kotlin interface in `app/src/main/java/com/nexio/tv/ui/components/RailCardData.kt`:

```kotlin
@Immutable
interface RailCardData {
    val itemKey: String
    val contentId: String
    val title: String?
    val posterRef: ArtworkDisplayRef?
    val backdropRef: ArtworkDisplayRef?
    val logoRef: ArtworkDisplayRef?
    val releaseInfoText: String?
    val rating: TitleRating?
    // additional fields as GridContentCard's actual reads dictate; minimum viable contract
}
```

Existing typed projections (`ModernHomeRowItem`, `DetailRailItem`, etc.) implement it.

`GridContentCard` flips signature: `fun GridContentCard(item: RailCardData, ...)`.

Provide a `MetaPreview.toRailCardData()` legacy adapter so all 9 existing callers can pass through without per-surface migration yet (mirrors the `DetailRailItem.fromMetaPreview` pattern landed in commit `d777c65b3`). Adapter wraps strings as `ArtworkDisplayRef.LegacyString(POSTER/BACKDROP/LOGO)` and embeds the source `MetaPreview` so callbacks can extract it.

**Acceptance:** Phase 0 merge keeps every existing call site working with no behavior change. Type-strict contract is enforced at the card primitive boundary even before per-surface projections land.

### Phase 1A — `SearchDiscoverSection` → `SearchResultItem`

Define `SearchResultItem` projection. Migrate `SearchDiscoverSection`. May expose search-specific fields (relevance score, match highlights) on the projection. Drop the `MetaPreview.toRailCardData()` adapter at this call site.

### Phase 1B — `LibraryScreen` → `LibraryRailItem`

Define `LibraryRailItem`. Migrate `LibraryScreen`. May expose library-specific fields (saved state, list membership, last-watched). Drop adapter at this call site.

### Phase 1C — `OrganizationDetailScreen` → `OrganizationCatalogItem`

Define `OrganizationCatalogItem`. Migrate. Drop adapter at this call site.

### Phase 1D — `CastDetailScreen` → reuse `DetailRailItem`

Cast filmography is detail-rail-shaped (movies/shows attached to an actor). Reuse `DetailRailItem` rather than introducing a new type. Drop adapter at this call site.

### Phase 1E — `AndroidTvFeedBrowserScreen` → `FeedRailItem` or skip

If this screen renders Android TV system recommendations, it may already use platform types — investigate during plan-writing. If it does use `MetaPreview` via `GridContentCard`, define a thin `FeedRailItem`.

### Phase 1F — `CatalogSeeAllScreen` → reuse `ModernHomeRowItem`

`CatalogSeeAllScreen` already observes a single rail by key via `CatalogInventoryRepository.observeRail(key)` (per the catalog-inventory plan landed earlier today). Convert that flow to emit `List<ModernHomeRowItem>` instead of `CatalogRow` of `MetaPreview`. Drop adapter at this call site.

### Phase 1G — `GridHomeContent` → reuse `ModernHomeRowItem`

The grid layout of home rails. Same data source as Modern Home rails. Drop adapter at this call site.

### Phase 2A — Hero-preview side channel migration

`buildContinueWatchingItem` in `ModernHomeModels.kt:386` reads `displayMetadata.displayPoster` chain to build the focused-CW hero preview. The CW row card itself uses the typed projection; this is the OTHER consumer flagged as a rule #1 follow-up in commit `c2c4db253`. Migrate to read from `resolved.posterRef` / `backdropRef` / `logoRef.toLegacyArtworkString()`.

### Phase 2B — `ContinueWatchingMetadataSnapshot.mergeFallback` migration

Currently merges `HomeDisplayMetadata` instances at the persistence boundary. Replace with a typed-shape-aware merge that operates on `ResolvedSlot<T>` data (matching Plan A's reducer language). Persistence on-disk shape stays; conversion at read boundary.

### Phase 2C — `HomeProviderLocalizedMetadataOverlay.applyToProviderLocalizedHomeItem` migration

Localization overlay currently maps `HomeDisplayMetadata` onto `MetaPreview`. Migrate to apply to typed slots.

### Phase 2D — `HomeViewModelPresentationPipeline.applyToHeroItem` migration

Same shape as 2C. Hero-specific localization overlay. Migrate or delete depending on whether `HeroDisplayItem` already absorbs the localization at the projection layer.

### Phase 3 — Catalog pipeline restructure (HIGHEST RISK)

Producer-side change. `HomeViewModelCatalogPipeline.kt` flips:
- `_displayCatalogRows` / `_displayHeroItems` / `_displayContinueWatchingItems` flip from `List<CatalogRow>`/`List<MetaPreview>` to typed shapes.
- `applyHydratedHomeOverlays` chain replaced with reducer-direct projection from `MetadataDisplayRouter` outputs.
- `HydratedHomeOverlay` shape may need to flip from `HomeDisplayMetadata` carrier to typed-slot carrier.
- `rowsForResolvedDisplaySurface` removed; `ResolvedDisplaySurfaceRepository` populated directly from the typed pipeline output.

**Risk:** the death-spiral incident on 2026-05-09 (Plan B Tasks 3-6 first attempt) was caused by producer-pipeline restructure. Mitigation:
- Phase 3 gets its own brainstorming session before plan-writing.
- Multiple intermediate commits during the migration, each with heap-dump perf gate.
- Pre-flight: every consumer in Phase 1 + Phase 2 must already read typed shapes. Verify with grep before starting.
- Acceptance: heap dump after sustained Modern Home soak shows GC pattern matching post-`81e6b21e6` baseline (e.g. 17-43 MB heap, sub-millisecond concurrent GC pauses, no blocking GCs).

### Phase 4 — Cleanup

Delete:
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt` (entire file)
- `HomeDisplayMetadata.applyTo` and helper `applyTo*` private functions
- `HomeDisplayMetadata.mergeFallback` and helper `mergeFallback*` private functions
- `HomeCatalogRefreshCoordinator.withCompatiblePersistedInternalPoster`
- `HomeFirstPaintMetadataMapper.kt` (if dead post-Phase-3)
- `MetaPreview.toRailCardData()` adapter (only safe after every `GridContentCard` caller has migrated)

Update CLAUDE.md hard rule #1 with the new invariant. Add the producer-side enforcement to the rule's "how to verify" section.

## Sub-project sequencing

```
Phase 0 (foundation) ◄── blocks all
   │
   ├──► Phase 1A-G (per-surface migrations) ──┐  ◄ parallel-safe
   │                                          │
   └──► Phase 2A-D (home internal) ──────┐    │  ◄ parallel-safe
                                         ▼    ▼
                                       Phase 3 (catalog restructure) ◄ HIGHEST RISK
                                            │
                                            ▼
                                       Phase 4 (delete deprecated)
```

**Hard ordering rules:**
- Phase 0 must merge before any Phase 1 surface migration.
- Phase 3 depends on all Phase 2 sub-projects; producer cannot flip while internal MetaPreview consumers still expect the legacy shape.
- Phase 4 depends on Phase 3 + Phase 1G (last `GridContentCard` caller migration that drops the adapter).

**Soft ordering recommendation:**
- Phase 1F + 1G first — both reuse `ModernHomeRowItem`; lowest risk, smallest scope.
- Phase 2A next — already-flagged rule #1 follow-up with known scope.
- Phase 1A-E + 2B-D in parallel after that.
- Phase 3 last (with its own brainstorm).
- Phase 4 final cleanup.

## Risk + perf-gate matrix

| Phase | Blast radius | Heap-gate after | Notes |
|---|---|---|---|
| 0 | 9 callers | Modern Home soak | Adapter wrapper — no allocation regression expected |
| 1A-G | 1-2 files each | Surface-specific smoke; heap optional | |
| 2A | `ModernHomeModels` only | Modern Home soak (focused CW item) | Visual verification of focused-CW preview |
| 2B | CW snapshot service | Steady-state Modern Home + CW interaction | |
| 2C-D | Localization paths | Profile switch + locale change soak | |
| 3 | Catalog pipeline core | **Heap dump after each commit + GC trace + sustained soak** | Death-spiral risk; stage in 3-4 sub-commits |
| 4 | Pure deletion | Compile + smoke | |

## Out of scope (explicit)

The following are NOT part of this spec, even though they relate:

- **Migrating `MetadataDisplayRouter`'s output type.** Router still returns its current types; Phase 3 wraps router output into typed shapes at the consumer (catalog pipeline), not at the router.
- **Reshaping persistence schemas.** `SavedLibraryItem`, `ContinueWatchingMetadataSnapshot`, `HomeCatalogSnapshotStore.Snapshot` keep their current on-disk MetaPreview-derived shape. Conversion happens at the read boundary. A separate "schema v2" project can re-shape persistence later.
- **Search-as-a-surface in `ResolvedDisplaySurfaceRepository`.** Phase 1A wraps search results in `SearchResultItem`; the search backend still calls addons directly. Routing search through the resolved authority as a `SEARCH_SURFACE_KEY` is a separate project.
- **Detail-screen `Meta` round-trip elimination.** Plan B Task 23's TODO (the `resolvedDetail.toMeta(...)` call at `MetaDetailsViewModel.kt:3386`) stays. Detail HeroSection already reads from `resolvedDetail` first; the `Meta` round-trip is for non-display logic. Separate cleanup.
- **`MetaPreview` deletion entirely.** The class survives — addon-API parsing keeps producing it; persistence keeps consuming it. The architectural goal is "no `MetaPreview` through the home rendering pipeline," not "no `MetaPreview` anywhere."
- **CW Loading-branch soft-clear** (already saved as separate post-Plan-B follow-up project memory; defer to that).

## Tech stack

Kotlin · Hilt · Coroutines/Flow · Compose · Mockk · JUnit4 — same as Plan B. No new dependencies.

## References

- Plan A: `docs/superpowers/plans/2026-05-09-resolved-display-authority.md` — non-downgrade reducer authority.
- Plan B: `docs/superpowers/plans/2026-05-09-resolved-display-ui-consumption-migration.md` — Surface 1-5 consumer migration (predecessor).
- CLAUDE.md hard rule #1: typed `posterRef`/`backdropRef`/`logoRef` strict, single display authority.
- Memory leak investigation: `docs/superpowers/notes/2026-05-09-modern-home-leak-root-cause.md` — 947K MetaPreview retention root cause.
- Catalog inventory plan: `docs/superpowers/plans/2026-05-10-catalog-inventory-repository.md` — `CatalogInventoryRepository` extracted earlier today; provides the typed observe-by-rail flow Phase 1F builds on.
- Plan B Task 23 commit `c2c4db253` — `// TODO(Plan B Surface 4 follow-up)` flagged in `ModernHomeModels.kt:386` for Phase 2A.
- CW regression fix `81e6b21e6` — establishes the `fromInProgressLegacy` pattern used by Phase 0's `MetaPreview.toRailCardData()` adapter.
