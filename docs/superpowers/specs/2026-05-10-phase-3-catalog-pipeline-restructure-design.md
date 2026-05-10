# Phase 3 — Catalog Pipeline Restructure (Authority-Owned Item Data)

**Date:** 2026-05-10
**Status:** approved during brainstorming (architecture-driven decisions delegated; user directive "right implementation matching desired architecture")
**Predecessor:** `docs/superpowers/specs/2026-05-10-home-metapreview-elimination-design.md` (umbrella spec)
**Parent commit history:** Phases 0 / 1A / 1B / 1C / 1D / 1F / 1G / 2A / 2B / 2C already shipped (commits `e4511e6b8` → `e071c8d5b`).

---

## Goal

Eliminate `MetaPreview` as a transport in the home pipeline, runtime AND persistence. After Phase 3, the home pipeline emits typed shapes end-to-end; persistence stores rail structure separately from item data; `ResolvedDisplaySurfaceRepository` is the single source of truth for item content; the deprecated mutable-row helpers become unreachable so Phase 4 can delete them.

This is the producer-side counterpart to Plan B (Surfaces 1-5) which migrated the consumer side. The umbrella spec's original "persistence is out of scope" stance has been retired (see umbrella spec revision note).

## Why this work, now

Two reasons the producer half can't be deferred further:

1. **Rule #1 attack surface.** Every read-boundary conversion from legacy MetaPreview-shape persistence (`HomeCatalogSnapshotStore.Snapshot`, `ContinueWatchingMetadataSnapshot`) into the runtime pipeline is a place where untyped data flows in. Each conversion is a future rule #1 regression waiting to happen — surfaces that "happen to read the legacy field" silently bypass the typed-slot contract.

2. **Architectural redundancy.** `HomeCatalogSnapshotStore.Snapshot` currently denormalizes upstream metadata (poster URLs, names) into MetaPreview rows. That same data is ALREADY persisted by `DurableArtworkDecisionCache`, `MetadataDiskCacheStore`, and the resolved authority's persistence path. The snapshot is doing two jobs (rail structure + metadata) when it only needs one (rail structure). Removing the duplication eliminates a class of "which copy is authoritative?" bugs and a sizable on-disk footprint.

## Target architecture

**Authority owns item data; structure is separate.** The runtime end state:

```
MetadataDisplayRouter (raw provider output)
        │
        ▼
HomeRailProjectionReducer.reduce(firstPaint, overlay, existing)
        │
        ▼
ResolvedDisplaySurfaceRepository (HOME_SURFACE_KEY) ◄── single source of truth for item data
        │
        ▼
Item lookup by key
        ▲
        │ ┌─────────────────────────────────────┐
        │ │ Rail structure (separate concern):  │
        │ │   CatalogInventoryRepository        │
        │ │   .observeRail(key)                 │
        │ │   ├─ catalogId                      │
        │ │   ├─ addonId / apiType / title      │
        │ │   └─ List<RailItemKey>              │
        │ └─────────────────────────────────────┘
        │
   resolvedRailRowsFlow.map { rail →
       ResolvedRailRow(
           catalogId = rail.catalogId,
           title = rail.title,
           items = rail.itemKeys.mapNotNull { authority.lookup(it) }
                                .map { ModernHomeRowItem.from(it) }
       )
   }
```

**Key reshape:** `CatalogRow` (currently `(catalogId, addonId, apiType, title, items: List<MetaPreview>)`) becomes the structural-only shape `(catalogId, addonId, apiType, title, itemKeys: List<RailItemKey>)`. The `items` field's TYPE changes from `List<MetaPreview>` to `List<RailItemKey>` where `RailItemKey = (apiType, contentId)` — minimal, opaque, looks-up-in-authority.

**Persistence reshape (also in scope):**

- `HomeCatalogSnapshotStore.Snapshot`: drop the MetaPreview-denormalized fields; store ONLY rail structure (rail metadata + `List<RailItemKey>`). The MetaPreview fields' purposes are already served by upstream caches (`DurableArtworkDecisionCache`, `MetadataDiskCacheStore`). Cold-start reads rail structure here; the resolved authority hydrates item content from its own persistence path.
- `ContinueWatchingMetadataSnapshot`: replace `clickTimeDisplayMetadata: HomeDisplayMetadata` with a per-field `ResolvedSlot<T>` bag matching Plan A's reducer vocabulary. The click-time-display intent (preserve what the user saw when tapping) is captured more faithfully by typed slots with rank + provider provenance than by the current `HomeDisplayMetadata` bag.

**Read-time legacy compat for both:** detect legacy schema on read; project to the new shape in memory; rewrite on next write. No migration step required; standard cache-version-rolls-forward pattern. Existing users don't lose data.

## End-state acceptance

1. `grep -rn "List<CatalogRow>\\b\\|val items: List<MetaPreview>" app/src/main` returns empty (or only references in deleted-this-phase test files).
2. `HomeCatalogSnapshotStore.Snapshot` does NOT contain any `MetaPreview` field — only rail structure (`catalogId`, `addonId`, `apiType`, `title`, `List<RailItemKey>`).
3. `ContinueWatchingMetadataSnapshot` does NOT contain `HomeDisplayMetadata` — uses typed `ResolvedSlot<T>` bag.
4. `_displayCatalogRows.value`, `_displayHeroItems.value`, `_displayContinueWatchingItems.value` all carry typed shapes (the legacy types are gone or renamed to structure-only).
5. `HomeHydrationOverlayApplier.kt` is deleted.
6. `HomeDisplayMetadata.applyTo` / `applyToPreview` / `mergeFallback` / `coalesceWith` are deleted.
7. `HomeCatalogRefreshCoordinator.withCompatiblePersistedInternalPoster` is deleted.
8. `HomeViewModelPresentationPipeline.applyToHeroItem` is migrated to typed slots or deleted (Phase 2D's original scope).
9. `heaptrail --find-referrers MetaPreview --hops 3` from a Modern Home soak heap shows retention ONLY in addon-API parsing (transient) and the current navigation back-stack. Zero retainers in `HomeViewModel.*` / `_display*.*` / `HomeCatalogSnapshotStore.*` chains.
10. On-device cold-start with existing legacy-shape snapshot file works (read-time compat path verified).
11. GC pattern under sustained Modern Home use matches the post-Plan-B baseline (no death-spiral; sub-millisecond concurrent pauses).

## Phase decomposition (10 sub-projects)

Each sub-project ships independently with its own commit, smoke test, and (for risk-sensitive ones) heap-dump perf gate.

### Phase 3 prerequisites (stand-alone)

**Sub-project 3.pre1 — Phase 1E migration.** `AndroidTvFeedBrowserScreen` last `.toRailCardData()` call site. Drops one more legacy-adapter caller before Phase 3 starts. Same shape as Phase 1A-D — small, standalone, no risk.

### Phase 3 runtime sub-projects

**Sub-project 3.1 — Define `RailItemKey` + `Rail` structural types.**
- New type `RailItemKey(apiType: String, contentId: String)` — looks up in authority via `homeDisplayItemKey(apiType, contentId)`.
- New type `Rail` (or rename existing `CatalogRow` carefully) carrying ONLY structure: `catalogId`, `addonId`, `apiType`, `title`, `items: List<RailItemKey>`.
- Unit tests for type equality, key derivation.
- No production wiring changes yet; just the types exist.

**Sub-project 3.2 — `resolvedRailRowsFlow` reads from authority directly.**
- Today: `combine(observeHomeSurface, _displayCatalogRows) { resolved, catalogRows → byKey.lookup }` joins legacy MetaPreview rows with authority.
- After: `combine(observeHomeSurface, _railStructure) { resolved, rails → rails.map { rail → rail.itemKeys.mapNotNull { resolved[it.key] } } }` looks up items directly in authority.
- The producer pipeline now needs to expose rail structure as a typed StateFlow alongside the legacy `_displayCatalogRows`. Add `_railStructure: StateFlow<List<Rail>>` as a parallel emission.
- The legacy `_displayCatalogRows` stays alive for now; this is consumer migration only.
- Heap-dump perf gate after.

**Sub-project 3.3 — `resolvedHeroItemsFlow` reads from authority directly.**
- Same shape as 3.2 but for hero. Phase 2D is folded here.
- `applyToHeroItem` either becomes a typed-slot operation (rewrite) or dies (delete) depending on whether the resolved authority already absorbs the hero overlay. Investigate during plan-writing.
- Heap-dump perf gate.

**Sub-project 3.4 — `resolvedContinueWatchingItemsFlow` reads from authority directly. (SKIPPED 2026-05-10.)**
- Investigation during execution found this is effectively a no-op. `_displayContinueWatchingItems` carries `ContinueWatchingItem` (a typed sealed class with `InProgress` / `NextUp` variants), not `MetaPreview`. The CW flow already escaped MetaPreview in Plan B Surface 4 (commit `782cc529d`). The resolved projection's `projectCwInProgress(resolved, item)` / `projectCwNextUp(resolved, item)` factories embed `source: ContinueWatchingItem.InProgress/NextUp` directly — they need the full sealed-class instance for variant-specific state (progress, episode info), not just a key.
- The architectural change 3.4 was supposed to deliver (typed key-only consumption) doesn't apply: CW items carry variant-specific state that doesn't fit a key-only model. Rails/hero migrations (3.2/3.3) work because their per-item data is uniform (just artwork + title) and lives in the authority; CW's per-item data is heterogeneous (resume timing vs. up-next episode) and lives on the `ContinueWatchingItem` itself.
- The genuine CW pipeline MetaPreview elimination lives in **Sub-project 3.8** (`ContinueWatchingMetadataSnapshot` persistence reshape — `HomeDisplayMetadata` → `ResolvedSlot<T>`). 3.8 carries 3.4's original intent.

**Sub-project 3.5 — Screensaver bulk publication uses authority lookup.**
- `publishTmdbTrendingScreensaverSurface` currently does `rowsForResolvedDisplaySurface(sourceRows, overlaysByItemKey)` on `List<CatalogRow>` of MetaPreview.
- After: source rows are rail structure; per-item lookup goes through `HomeResolvedDisplayMapper.toResolvedDisplayItem(structure, overlay)` which already exists.
- This is the call site that uses `rowsForResolvedDisplaySurface` → `HomeHydrationOverlayApplier` (the deprecated chain). After migration, neither is needed for screensaver.

**Sub-project 3.6 — Catalog pipeline producer flips to emit rail structure.**
- `composeHydratedHomeOverlaySnapshot` currently emits `displayRows: List<CatalogRow<MetaPreview>>`, `fullRows: List<CatalogRow<MetaPreview>>`, `heroItems: List<MetaPreview>`.
- After: emits `displayRails: List<Rail>`, `fullRails: List<Rail>`, `heroItemKeys: List<RailItemKey>`. The producer no longer applies `HomeHydrationOverlayApplier`/`applyTo` — it composes structure only; item hydration is the authority's job.
- This is the load-bearing flip. By the time we reach 3.6, the only consumers of `_displayCatalogRows`/`_displayHeroItems`/`_displayContinueWatchingItems` should be: legacy snapshot store (3.7 handles), legacy screensaver (3.5 handles), legacy persistence (3.7/3.8 handle), and the legacy data fields on `HomeUiState`-adjacent types (these are themselves to-be-deleted in Phase 4).
- **High risk — multiple intermediate commits with heap-dump perf gates between each.** This is the seam that caused the 2026-05-09 death-spiral. The risk is mitigated by 3.2-3.5 having already removed the consumers that previously broke.

### Phase 3 persistence sub-projects

**Sub-project 3.7 — `HomeCatalogSnapshotStore.Snapshot` reshape.**
- Current shape (per existing code): `Snapshot(version, catalogRows: List<CatalogRow>, fullCatalogRows: List<CatalogRow>, heroItems: List<MetaPreview>)` plus various metadata.
- New shape: `Snapshot(version, version-bumped-to-v2, displayRails: List<Rail>, fullRails: List<Rail>, heroItemKeys: List<RailItemKey>)` plus structural metadata only.
- Read path: detect legacy v1 schema, project to v2 in memory (lossy — drop the denormalized MetaPreview fields), continue. The denormalized data was redundant with upstream caches — losing it on read costs nothing.
- Write path: always emit v2.
- Test: round-trip v1 read + v2 write + v2 read produces equivalent rail structure.

**Sub-project 3.8 — `ContinueWatchingMetadataSnapshot` reshape.**
- Current shape: `(routingVersion, parentId, primaryProvider, decisionReason, clickTimeDisplayMetadata: HomeDisplayMetadata)`.
- New shape: `(routingVersion, parentId, primaryProvider, decisionReason, clickTimeSlots: ResolvedDisplayFieldSlots)` — uses Plan A's typed-slot bag instead of HomeDisplayMetadata.
- Bump `CURRENT_ROUTING_VERSION` to 2. Legacy v1 records project HomeDisplayMetadata → ResolvedDisplayFieldSlots at read time (lossy in provider-provenance — legacy doesn't have it; default to `FIRST_PAINT` rank).
- `renderDisplayMetadata` (currently uses `coalesceWith`) re-shapes to use `ResolvedSlot.choose(...)` per field instead. The `coalesceWith` extension becomes unreachable after this, ready for Phase 4 deletion.

### Phase 3 cleanup sub-projects

**Sub-project 3.9 — Drop `_displayCatalogRows` / `_displayHeroItems` / `_displayContinueWatchingItems` StateFlows.**
- After 3.2-3.6, no consumer reads these fields' MetaPreview-shape items. The flows might still exist as structural conduits (e.g. carrying rail structure); rename to `_railStructure` etc. or drop entirely depending on whether structure is exposed elsewhere.
- This is the last runtime-side flip.

**Sub-project 3.10 — Verify zero MetaPreview retainers + final heap dump.**
- Capture Modern Home soak heap.
- `heaptrail --find-referrers MetaPreview --hops 3` against the dump.
- Expected: zero retainers in `HomeViewModel.*` / `_display*.*` / `HomeCatalogSnapshotStore.*` chains.
- If any unexpected retainer surfaces, root-cause + ship a follow-up sub-project. Don't proceed to Phase 4 until this gate is green.

## Sequencing

```
3.pre1 (Feed) — standalone, lowest risk, ships first
   │
   ▼
3.1 (types)
   │
   ├──► 3.2 (rails consumer) ──┐
   ├──► 3.3 (hero consumer) ───┤  all parallel-safe
   ├──► 3.4 (CW consumer) ─────┤  each with heap-gate
   └──► 3.5 (screensaver) ─────┘
                                │
                                ▼
                              3.6 (producer flip) ◄── HIGHEST RISK; staged in sub-commits
                                │
                                ▼
                            ┌── 3.7 (snapshot store) ──┐
                            └── 3.8 (CW snapshot) ─────┘  parallel-safe
                                            │
                                            ▼
                                          3.9 (drop legacy StateFlows)
                                            │
                                            ▼
                                          3.10 (verify) ─── final heap gate
                                            │
                                            ▼
                                          Phase 4 (delete deprecated helpers)
```

## Risk mitigation

**The 2026-05-09 death-spiral is the cautionary tale.** That incident happened when the producer + consumers were flipped atomically. Mitigations baked into the staging:

1. **Consumers migrate first (3.2-3.5), producer flips last (3.6).** By the time 3.6 lands, the seams that previously broke (resolved projections, screensaver bulk publication) are already on the typed path. The producer flip only affects the few remaining MetaPreview consumers (snapshot store, hero overlay) — all of which Phase 3 has explicit migrations for.
2. **Heap-dump perf gate after every sub-project.** GC pattern check (free MB/cycle, pause duration, allocation rate) compared to post-Plan-B baseline. Any sub-project showing regression bisects to a specific change.
3. **3.6 ships in multiple intermediate commits.** Not one big-bang. Each commit verified independently. The spec's first attempt at this seam (`5cf8c6dc5`..`3204278ee`) shipped 5 surfaces in one go — that's what caused the death-spiral. This time, each consumer's read source flips in its own commit before the producer's emission shape flips.
4. **Legacy read-time compat for persistence.** Sub-projects 3.7 and 3.8 each ship a legacy-schema-on-read fallback so existing on-disk data continues to work. No user-facing data loss; cold-start picks up v2 on next write.

## Out of scope (for Phase 3 specifically)

- **Phase 4 (delete deprecated helpers).** Gates on Phase 3's completion. Sub-project 3.10's heap gate is the precondition for Phase 4.
- **MetaPreview class deletion.** The class survives — addon-API parsing keeps producing it as raw provider data. Phase 3 eliminates MetaPreview as the *home pipeline's transport*, not the *addon-API parsing layer*.
- **Persistence migration for `SavedLibraryItem`.** Library is its own separate persistence concern (saved/watched items) not in the home pipeline. Schema reshape for library is a separate project.

## References

- Umbrella spec: `docs/superpowers/specs/2026-05-10-home-metapreview-elimination-design.md` (updated 2026-05-10 to retire the persistence-out-of-scope stance).
- Plan A spec: `docs/superpowers/plans/2026-05-09-resolved-display-authority.md` — `HomeRailProjectionReducer`, `ResolvedSlot<T>`, `DisplaySourceRank`.
- 2026-05-09 incident report: `docs/superpowers/notes/2026-05-09-modern-home-leak-root-cause.md` — the death-spiral root cause that informs 3.6's sub-commit staging.
- CW Loading-branch soft-clear follow-up: project memory (defer to post-Phase-3-and-4).
- CLAUDE.md hard rule #1 (single typed display authority), hard rule #7 (stage-by-explicit-path, no `git stash`/`-A`/`-a`).
