# Phase 3.7 — `HomeCatalogSnapshotStore.Snapshot` reshape design

**Status:** Spec — awaiting plan generation.

**Goal:** Reshape `HomeCatalogSnapshotStore.Snapshot` to store rail structure only (`Rail` / `RailItemKey`), eliminating denormalized `MetaPreview`/`CatalogRow` content from the persisted file format. Add a new parallel persistence boundary `ResolvedDisplaySnapshotStore` that stores the typed authority's `ResolvedDisplayItem` state. Restore both at cold-start so rails render instantly with typed-authority content — preserving the current first-paint UX while completing the rule #1 enforcement in the persistence layer.

**Background:** Sub-project 3.7 in `docs/superpowers/specs/2026-05-10-phase-3-catalog-pipeline-restructure-design.md`. After Phase 3.6.5 (producer flip, commit `63aa16286`), the producer emits first-paint CatalogRow shells but the typed authority owns hydrated content. The Phase 3 spec's original assumption — that the snapshot's denormalized content was "redundant with upstream caches" — does not hold: there is no typed-authority disk cache today, and the snapshot IS the first-paint cache that makes cold-start instant. This design adds that missing typed-authority cache as part of the reshape, so the snapshot can drop denormalized content without UX regression.

**Risk:** MEDIUM. New persistence boundary increases the moving parts at cold-start. The write coordination (two files flushed in sequence) introduces a brief window where files can be inconsistent if the app is killed between writes. Error handling matrix below covers all paths. v1 → v2 migration runs once per device per profile and is the highest-risk single moment; the projection logic reuses existing slot conversion infrastructure (commit `9efd55a7c`+) and is well-tested.

---

## Architecture

### Persistence boundaries

```
HomeCatalogSnapshotStore                          ResolvedDisplaySnapshotStore (NEW)
filesDir/home-catalog-snapshot-v1/                filesDir/resolved-display-v1/
  p<profileId>_<lang>.json                          p<profileId>_<lang>.json

Snapshot v5:                                       Map<itemKey: String,
  displayRails: List<Rail>                             ResolvedDisplayItem>
  fullRails: List<Rail>
  heroItemKeys: List<RailItemKey>                  (typed content — rule #1 compliant
  orderedGroupKeys: List<String>                    end-to-end)

(structure only — no MetaPreview)
```

### Snapshot v5 data class

```kotlin
data class Snapshot(
    val displayRails: List<Rail>,
    val fullRails: List<Rail>,
    val heroItemKeys: List<RailItemKey>,
    val orderedGroupKeys: List<String> = emptyList()
)
```

`Rail` and `RailItemKey` already exist in `app/src/main/java/com/nexio/tv/domain/model/` (added in Phase 3.1, commits `a4faee398` and `1cced1db5`). No new types in the domain layer.

### Write coordination

When `HomeCatalogSnapshotStore.write(snapshot)` fires (existing 5-second debounce after pipeline activity), the caller — `persistMergedHomeSnapshotIfNeeded` in `HomeViewModelCatalogPipeline.kt` — also flushes the typed cache:

```kotlin
homeCatalogSnapshotStore.write(snapshot, posterToken, profileId)
resolvedDisplaySnapshotStore.write(
    items = resolvedDisplaySurfaceRepository.snapshotNow(profileId).associateBy { it.itemKey },
    profileId = profileId,
)
```

Two files flushed in sequence; second trails the first by milliseconds. If the app is killed between writes, the next launch reads only one of the two files — error handling table below covers both directions.

### Cold-start read flow (v2)

1. `restorePersistedCatalogSnapshotPipeline` reads `HomeCatalogSnapshotStore.Snapshot` → rail structure + `heroItemKeys`
2. New code: read `ResolvedDisplaySnapshotStore` → `Map<itemKey, ResolvedDisplayItem>`
3. New code: call `resolvedDisplaySurfaceRepository.restoreFromDisk(typedCache, profileId)` — pre-populates the repository's in-memory state
4. Existing post-3.6.5 typed-projection pipeline renders rails by looking up items via `ResolvedDisplaySurfaceRepository`
5. Rails render instantly

### Cold-start read flow (v1 legacy, one-time per device per profile)

1. `HomeCatalogSnapshotStore.streamReadSnapshot` detects `schemaVersion: 4` in file header (the current production version)
2. Parse legacy `catalogRows` / `fullCatalogRows` / `heroItems` arrays (one-time materialisation cost — bounded by current production snapshot size, typically 100-500 KB)
3. For each `MetaPreview`:
   - `metaPreview.toFirstPaintSlots(nowMs)` → `ResolvedDisplayFieldSlots`
   - `HomeRailProjectionReducer.reduce(firstPaint = slots, overlay = null, existing = null, profile = null)` → merged slot bag
   - Build `ResolvedDisplayItem` with `slots = merged`, `display = ResolvedDisplayFields.fromSlots(merged)`, `artwork = ArtworkBundle.fromSlots(merged)`, `hydrationState = HydrationState.PREVIEW_ONLY`, `updatedAtMs = nowMs`
4. Build v2 Snapshot shape:
   - For each `CatalogRow` → `Rail(catalogId, addonId, apiType, title, items = row.items.map { RailItemKey(it.apiType, it.id) })`
   - `heroItemKeys = heroItems.map { RailItemKey(it.apiType, it.id) }`
   - `orderedGroupKeys` preserved as-is
5. Write the projected typed cache: `ResolvedDisplaySnapshotStore.write(projectedTypedCache, profileId)`
6. Return the v2 Snapshot
7. Future writes emit v2 only — the v1 file is overwritten on next snapshot flush

The v1 projection logic is read-only, deterministic, and self-contained inside `HomeCatalogSnapshotStore.streamReadSnapshot`. No call site changes for the migration.

---

## Component map

### Created files

| File | Responsibility |
|---|---|
| `app/src/main/java/com/nexio/tv/data/local/ResolvedDisplaySnapshotStore.kt` | File-backed streaming JSON persistence for `Map<itemKey: String, ResolvedDisplayItem>`. Mirrors `HomeCatalogSnapshotStore` recipe: streaming `JsonReader` over `BufferedReader` over `FileInputStream` for reads; streaming `JsonWriter` over `BufferedWriter` over `FileOutputStream` + atomic `Files.move` rename for writes. Per-profile + per-language file path under `filesDir/resolved-display-v1/p<profileId>_<lang>.json`. |
| `app/src/main/java/com/nexio/tv/data/local/SnapshotV1MigrationProjector.kt` | Pure function: takes legacy v1 `CatalogRow`/`MetaPreview` lists, returns projected `(Snapshot, Map<itemKey, ResolvedDisplayItem>)` for v2 write. Self-contained so the v1 read path stays readable and the projection logic is unit-testable. |
| `app/src/test/java/com/nexio/tv/data/local/ResolvedDisplaySnapshotStoreTest.kt` | Round-trip tests for the new persistence class. |
| `app/src/test/java/com/nexio/tv/data/local/SnapshotV1MigrationProjectorTest.kt` | Unit tests for the v1 → v2 projection logic. |

### Modified files

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt` | `Snapshot` data class field swap (catalogRows/fullCatalogRows/heroItems → displayRails/fullRails/heroItemKeys). `streamReadSnapshot` detects schema v4 vs v5 and dispatches to v1 migration or v2 streaming. SCHEMA_VERSION bumped 4 → 5. Write path always emits v5. |
| `app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt` | Add `fun restoreFromDisk(items: Map<String, ResolvedDisplayItem>, profileId: Int)`. Pre-populates the in-memory state. Idempotent — multiple calls with the same items are no-ops. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` | ~16 call sites updated. Producer no longer writes `catalogRows: List<CatalogRow>` to the snapshot — instead derives rail structure from `_displayCatalogRows.value` (still typed as `List<CatalogRow>` at the producer; the snapshot persistence step projects to `List<Rail>`). Read consumers update to read `snapshot.displayRails` (structure only) and look up items via the typed authority. `restorePersistedCatalogSnapshotPipeline` adds the `ResolvedDisplaySnapshotStore` read + restore step. `persistMergedHomeSnapshotIfNeeded` adds the typed cache write step. |

### Untouched

- `MetaPreview` data class (still emitted by the producer at runtime; just not persisted)
- `CatalogRow` data class (still emitted by the producer at runtime; just not persisted)
- `_displayCatalogRows` / `_displayHeroItems` StateFlows (runtime carrier of first-paint shells; reshape is Phase 3.9's concern)
- `Rail` / `RailItemKey` types (already exist from Phase 3.1)

---

## Error handling matrix

| Scenario | Behavior |
|---|---|
| Both files present, v2 schema, valid | Normal cold-start. Rails render instantly with typed-authority content. |
| Both files present, v1 snapshot schema | v1 migration runs once. Both files written in v2 shape during the read. Future reads use v2 streaming path. |
| Snapshot file present, typed cache file missing (interrupted v2 write OR fresh app install) | Rails render with empty content for the first emission. Pipeline waits for addon API. Same UX as Option A would have been; bounded to this edge case rather than every cold-start. |
| Snapshot file missing, typed cache file present (corrupt/interrupted state) | Ignore the typed cache (no rail structure to render against). Pipeline waits for fresh addon data. Same as fresh install. |
| Snapshot file schema > 5 (future version downgrade) | Return null (existing pattern). Treat as fresh install. |
| Typed cache file schema > 1 (future version downgrade) | Return null. Snapshot still readable; pipeline waits for fresh data — same as second-row above. |
| JsonReader parse failure on either file | `runCatching` + `logWarning` + return null. Treated as missing file. |
| v1 migration projection failure for an item | Skip that item (drop from typed cache). Other items still render. Snapshot still gets the v2 Rail structure for that catalog (without the failed RailItemKey). |
| Race: snapshot read returns before typed cache read | Block typed cache restore step until both reads complete (existing `restorePersistedCatalogSnapshotPipeline` is suspending; chain the reads). |

---

## Testing

### Unit tests

| Test | Coverage |
|---|---|
| `ResolvedDisplaySnapshotStoreTest` | v2 round-trip (write → read returns same map); empty map round-trip; malformed file → null + log warning; schema-version-too-high → null. |
| `SnapshotV1MigrationProjectorTest` | v1 fixture (CatalogRow/MetaPreview) → projected (Snapshot v5, typed cache Map). Asserts `FIRST_PAINT` rank on projected slots; asserts `hydrationState = PREVIEW_ONLY` on each ResolvedDisplayItem; asserts rail structure preserves `catalogId`/`addonId`/`apiType`/`title`/`items` keys 1:1; asserts heroItemKeys preserves order. |
| `HomeCatalogSnapshotStoreTest` (existing) | Update to use v5 shape constructors. Remove the v1 round-trip test (writes are v5-only after this commit). Add a v1 read → v5 return + typed cache write integration test. |

### On-device verification

Two-stage rollout:
1. **First post-upgrade launch:** the existing user's snapshot file is v1. The migration projection runs, both files are written in v2 shape. Verify rails render with the same content as pre-upgrade (no visible regression). This is the v1 migration acceptance.
2. **Second launch:** the steady-state v2 path runs. Verify rails render instantly with typed-authority content (no blank state). This is the v2 acceptance.

Plus the standard smoke (rule #8 — profile-tap-required sequence): zero FATAL/ANR/ClassCast/NoSuchMethod/JsonSyntaxException across launch + soak.

### Acceptance criteria

- `HomeCatalogSnapshotStore.Snapshot` no longer has `catalogRows` / `fullCatalogRows` / `heroItems` fields; only `displayRails` / `fullRails` / `heroItemKeys` / `orderedGroupKeys`.
- New `ResolvedDisplaySnapshotStore` file exists at `filesDir/resolved-display-v1/p<profileId>_<lang>.json` after the first home pipeline emission post-upgrade.
- v1 → v2 migration runs once per device per profile (silent — same UX as steady state). Verified via on-device first-post-upgrade launch.
- Steady-state cold-start renders rails with typed-authority content (no blank state). Verified via on-device second launch.
- Heap (`heaptrail --find-referrers MetaPreview --hops 2`): the `HomeCatalogSnapshotStore.*` retainer chain shows zero `MetaPreview` instances. Existing `HydratedHomeOverlay.fields → MetaPreview` chains and runtime `_displayCatalogRows` chains remain (out of scope).
- GC pattern matches the current post-3.6.5 baseline (idle gaps 5 s+; LOS < 5 MB per cycle).
- Smoke clean (no FATAL/ANR/ClassCast/NoSuchMethod/JsonSyntaxException).

---

## Non-goals

- **Eliminating `MetaPreview` / `CatalogRow` from runtime:** the producer still emits these as first-paint shells. Phase 3.9 (drop legacy StateFlows) is the runtime reshape concern.
- **Persisting `HydratedHomeOverlay` overlay state:** the overlay store is independent and has its own persistence (`HydratedHomeOverlayStore`). Out of scope.
- **Cross-version compatibility beyond v1 → v5:** there is no v2/v3/v4 transient schema. Production has been on a schema for a while; the bump is from current (4) to new (5) with v4 supported one-way read-only for migration.
- **Background pre-warming of typed cache before the first emission:** the typed cache is written after the first emission completes (existing 5 s debounce). Pre-warming on app start is out of scope.

---

## Self-review

**1. Placeholder scan:** No "TBD", no "TODO". Every component has explicit file path and responsibility. Pseudocode is illustrative; full implementations belong to the plan.

**2. Internal consistency:** Field names match across architecture diagram, data class declaration, component map, and acceptance criteria (`displayRails` / `fullRails` / `heroItemKeys` / `orderedGroupKeys`). Two persistence files, two read steps, two write steps — symmetric throughout.

**3. Scope check:** Single implementation plan. The new persistence class + reshape + ~16 call sites + v1 migration is bounded. No further decomposition needed.

**4. Ambiguity check:** Two judgement calls made explicit:
- v1 → v2 migration projects MetaPreview content to `FIRST_PAINT` rank (preserves cold-start UX through upgrade); items with missing fields produce slots at `EMPTY` rank (lose to upstream when fresh data arrives — same as `MetaPreview.toFirstPaintSlots` behavior elsewhere).
- Schema version bumps from 4 → 5 (the current production version is 4 per `HomeCatalogSnapshotStore.SCHEMA_VERSION`; v4 is the migration source, v5 is the new shape).
