# Catalog Inventory Repository — Design

**Date:** 2026-05-10
**Plan link:** Slots in before Plan B Task 17 (Continue Watching surface migration) in `docs/superpowers/plans/2026-05-09-resolved-display-ui-consumption-migration.md`.
**Related:** Plan A `docs/superpowers/plans/2026-05-09-resolved-display-authority.md` (the resolved-display-authority pattern this design extends to the catalog layer).

## Problem

`HomeViewModel._fullCatalogRows: MutableStateFlow<List<CatalogRow>>` (line 200 of `HomeViewModel.kt`) holds the full catalog *inventory* — every rail across every addon, Trakt synthetic group, MDBList, Simkl, TMDB, Kitsu — not the displayed rails. Under sustained Modern Home use this StateFlow value reaches 17-28 MiB (heap-dump observed: ~3,300 rails × ~50 items = ~164k `MetaPreview` instances). It has emergent ownership — neither Plan A nor Plan B mentions it by name; the shape grew from accumulating refresh sources over time.

Five consumers exist; none of them actually need the `List<CatalogRow>` shape:

| Consumer | What it actually wants |
|---|---|
| `CatalogSeeAllScreen.kt:68` (only external Compose observer) | A single rail by `(addonId, apiType, catalogId)` triple |
| `HomeViewModelCatalogPipeline.kt:1477` `rawFirstPaintBatchActive` | An emptiness flag |
| `HomeViewModelCatalogPipeline.kt:1623` `activeCatalogItemKeys` | A flat `Set<String>` of `apiType:id` for hydration filtering |
| `HomeViewModelCatalogPipeline.kt:2792` `cachedFullRows` | The previous publish's full state for `mergeCachedRowsWithLiveRows` |
| `HomeViewModelCatalogPipeline.kt:3126` (writer) | Write the next emission |

The current shape causes downstream pain: every full-inventory emission propagates to `CatalogSeeAllScreen.collectAsState`, recomposing it whenever **any** rail anywhere in the inventory changes. The retained-size dominator tree shows `HomeViewModel` itself bloated by the inventory.

## Decision

Extract a `@Singleton CatalogInventoryRepository` that owns a `Map<String, CatalogRow>` inventory keyed by `addonId_apiType_catalogId`. `HomeViewModel`'s pipeline keeps **computing** the inventory in its existing `updateCatalogRowsPipeline` and **pushes** the result via `inventoryRepository.publish(rows)`. Each consumer reads in its native shape (single rail, set, snapshot map).

This extends the Plan A `ResolvedDisplaySurfaceRepository` pattern to the *catalog* (raw addon rows) layer — the resolved layer already has it; the catalog layer hadn't.

User-approved choices within this design (brainstorming session 2026-05-10):

- **Push, not pull.** Pipeline keeps building the inventory; repo is a typed owner, not a computer. Pull (repo subscribes to upstream addon/synthetic refresh sources directly) is deferred to a future task.
- **Inventory only.** `_displayCatalogRows` (~10 visible rails) stays on `HomeViewModel`. Lean and tightly coupled to UI state.
- **Sequenced before Plan B Task 17.** CW migration (Tasks 17-20) needs single-item resolved lookups; the repository pattern this spec solidifies makes those tasks easier.

## Architecture

```
HomeViewModelCatalogPipeline.applyHomeSnapshotToUiPipeline
        │
        │ inventoryRepo.publish(composedSnapshot.fullRows)
        ▼
CatalogInventoryRepository  ─── owns Map<String, CatalogRow> ───
        ▲                                                       │
        │                                                       │
        │ snapshot() / isEmpty() / activeItemKeys()             │ observeRail(key)
        │                                                       │
HomeViewModelCatalogPipeline                          CatalogSeeAllScreen
(internal pipeline reads)                             (one rail at a time)
```

Singleton scope: `SeeAllScreen` and `HomeViewModel` are different navigation destinations; both need the same inventory. ViewModel-scoped state would force a parent-child VM relationship that doesn't exist.

## Components

### New: `CatalogInventoryRepository`

`app/src/main/java/com/nexio/tv/data/repository/CatalogInventoryRepository.kt`

```kotlin
@Singleton
class CatalogInventoryRepository @Inject constructor() {
    private val _inventory = MutableStateFlow<Map<String, CatalogRow>>(emptyMap())
    val inventory: StateFlow<Map<String, CatalogRow>> = _inventory.asStateFlow()

    /** Synchronous read for HomeViewModelCatalogPipeline internal use. */
    fun snapshot(): Map<String, CatalogRow> = _inventory.value

    /** Single-rail observation. Used by CatalogSeeAllScreen. */
    fun observeRail(key: String): Flow<CatalogRow?> =
        _inventory.map { it[key] }.distinctUntilChanged()

    /** Helper for `rawFirstPaintBatchActive`. */
    fun isEmpty(): Boolean = _inventory.value.isEmpty()

    /** Aggregate `apiType:id` across all rails — replaces the inline build at
     *  HomeViewModelCatalogPipeline.kt:1623. */
    fun activeItemKeys(): Set<String>

    @Synchronized
    fun publish(rows: List<CatalogRow>) {
        // LinkedHashMap so insertion order is preserved (matches current List<CatalogRow> order).
        // Indexed-for to avoid suspending iterable allocation pattern (CLAUDE.md rule #4).
        val next = LinkedHashMap<String, CatalogRow>(rows.size)
        for (i in rows.indices) {
            val row = rows[i]
            // Defensive gate: skip rows whose triple has any blank component.
            if (row.addonId.isBlank() || row.apiType.isBlank() || row.catalogId.isBlank()) continue
            next[catalogKey(row)] = row
        }
        _inventory.value = next
    }

    fun clear() { _inventory.value = emptyMap() }

    private fun catalogKey(row: CatalogRow): String =
        "${row.addonId}_${row.apiType}_${row.catalogId}"
}
```

### Modified: `HomeViewModel`

- Inject `CatalogInventoryRepository` via constructor.
- Delete `_fullCatalogRows: MutableStateFlow<List<CatalogRow>>` and `val fullCatalogRows: StateFlow<List<CatalogRow>>` (lines 200, 201).
- Add façade method:

```kotlin
fun observeCatalogRail(key: String): Flow<CatalogRow?> =
    catalogInventoryRepository.observeRail(key)
```

This keeps `CatalogSeeAllScreen` using `hiltViewModel<HomeViewModel>()` instead of needing a new ViewModel class. (A dedicated `CatalogSeeAllViewModel` is a future option if the screen grows independent state; out of scope here.)

### Modified: `HomeViewModelCatalogPipeline`

| Site | Current | After |
|---|---|---|
| 281, 2201 | `_fullCatalogRows.value = emptyList()` | `inventoryRepository.clear()` |
| 1477 | `_fullCatalogRows.value.isEmpty()` | `inventoryRepository.isEmpty()` |
| 1623 | inline iteration of `_fullCatalogRows.value + catalogsMap.values` building `activeCatalogItemKeys: Set<String>` | `inventoryRepository.activeItemKeys()` returns the inventory contribution only as a `Set<String>` of `apiType:id`; the call site unions it with the same `catalogsMap.values.flatMap{...}.map{...}` build that exists today. The helper covers the inventory portion of the union, not the union itself. |
| 2792 | `cachedFullRows = _fullCatalogRows.value` | `cachedFullRows = inventoryRepository.snapshot().values.toList()` (snapshot-at-use-site preserved) |
| 3126 | `_fullCatalogRows.value = composedSnapshot.fullRows` | `inventoryRepository.publish(composedSnapshot.fullRows)` |

### Modified: `CatalogSeeAllScreen`

Replace at line 68:

```kotlin
// Before
val fullCatalogRows by viewModel.fullCatalogRows.collectAsState()
val catalogRow = fullCatalogRows.find {
    "${it.addonId}_${it.apiType}_${it.catalogId}" == catalogKey
}

// After
val catalogRow by viewModel.observeCatalogRail(catalogKey).collectAsState(initial = null)
```

The `LaunchedEffect(gridState, catalogRow?.items?.size)` pagination logic and the rest of the screen are unchanged.

### Files

- New: `app/src/main/java/com/nexio/tv/data/repository/CatalogInventoryRepository.kt`
- New: `app/src/test/java/com/nexio/tv/data/repository/CatalogInventoryRepositoryTest.kt`
- Modified: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modified: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Modified: `app/src/main/java/com/nexio/tv/ui/screens/CatalogSeeAllScreen.kt`

## Data flow & lifecycle

**Write path** (every Modern Home pipeline emission):

```
addon refresh / Trakt / Simkl / MDB / TMDB / Kitsu discovery
        ↓
HomeViewModelCatalogPipeline.updateCatalogRowsPipeline (Dispatchers.Default)
        ↓ produces composedSnapshot.fullRows: List<CatalogRow>
HomeViewModelCatalogPipeline.applyHomeSnapshotToUiPipeline (Main thread)
        ↓
inventoryRepository.publish(rows)   // @Synchronized; one LinkedHashMap; one StateFlow value assignment
```

`publish` runs on the main thread (where `applyHomeSnapshotToUiPipeline` runs today). Building the `LinkedHashMap` is O(N) — same shape as the current `_fullCatalogRows.value = list` assignment.

**Read paths:**

1. **Pipeline internal** — `inventoryRepository.snapshot()` returns the current Map reference. O(1) read; same snapshot-at-use-site semantics as `_fullCatalogRows.value`.
2. **`CatalogSeeAllScreen`** — `inventoryRepository.observeRail(key)` returns `Flow<CatalogRow?>` with `distinctUntilChanged`. Re-renders **only when its rail's content changes**, not on every full-inventory emission.

**Lifecycle:**

- **Cold start**: existing pipeline boots → eventually calls `publish`. Repo starts empty (matches existing `_fullCatalogRows.value = emptyList()` initial state).
- **Profile switch / sign-out**: existing reset path at lines 281 and 2201 → `inventoryRepository.clear()`.
- **Process death + restore**: `@Singleton` is process-scoped. Inventory rebuilt from the pipeline on next refresh. The on-disk snapshot file is owned by `HomeCatalogSnapshotStore` (separate concern; unchanged).
- **Multi-profile**: single inventory at process scope; profile switch clears. Matches today.

**Concurrency:**

- `publish` is `@Synchronized` to atomicize LinkedHashMap build + StateFlow assignment from the writer's perspective.
- Reads (`snapshot`, `observeRail`, `isEmpty`) read `_inventory.value` (volatile via `MutableStateFlow`). Always coherent (no torn reads).
- Multiple `observeRail` collectors share the upstream StateFlow; each pays only the cost of its own `.map`.

**Reference stability** (CLAUDE.md rule #5):

- Each `publish` produces a fresh `LinkedHashMap`. The inventory StateFlow itself does NOT achieve `===` skip on content-equal emissions.
- `observeRail` mitigates for SeeAll: `distinctUntilChanged` on the rail value short-circuits when the rail's reference is unchanged. CatalogRow is interned upstream by the existing `CatalogRowMemo` (Plan B Task 1.1).
- For internal pipeline reads, no `===` guard exists today; no regression.
- Future optimization: a `CatalogInventoryMemo` that interns the LinkedHashMap reference when keys + values match the prior emission. Deferred — not in this spec.

## Error handling & edge cases

| Scenario | Behavior |
|---|---|
| `publish(rows)` row with blank `addonId` / `apiType` / `catalogId` | Skipped silently. Pipeline shouldn't produce these; defensive gate. |
| Duplicate triple keys in same `publish` | LinkedHashMap insertion-order with overwrite — last wins. Matches today's upstream dedupe. |
| `observeRail(key)` for a key that doesn't exist | Flow emits `null`, then keeps observing. Compose `collectAsState(initial = null)` renders empty/loading. |
| `snapshot()` concurrent with `publish` | `MutableStateFlow.value` is volatile — reader sees old map or new map; never torn. |
| `clear()` while a SeeAll observer is collecting | `observeRail(key).map { it[key] }` flips to `null` → SeeAll renders empty. Same as profile-switch behavior today. |
| `clear()` followed by stale in-flight `publish` for old profile | `@Synchronized` serializes; latest write wins. Same race exists today; not a regression. |
| Empty publish vs first-paint detection | `publish(emptyList())` and `clear()` both make `isEmpty()` true. Pipeline's `rawFirstPaintBatchActive` doesn't distinguish today; preserve. |
| `activeItemKeys()` cost | One `HashSet<String>` allocation; tens of KB. Same iteration cost as today's inline build, just relocated. |
| `@Synchronized publish` blocking main thread | Only one writer (the pipeline). Contention is essentially nil. Internal reads don't take the monitor. |

**Out of scope (deliberate):**

- LinkedHashMap content-signature memoization (defer until measured need).
- Per-profile inventory dimension (single inventory; profile switch clears).
- Inventory persistence (`HomeCatalogSnapshotStore` already persists; repo is in-memory only).

## Testing strategy

### Unit tests — `CatalogInventoryRepositoryTest.kt` (new, 12 cases)

| Test | Verifies |
|---|---|
| `publish then snapshot returns map keyed by addonId_apiType_catalogId` | Round-trip lookup |
| `publish preserves insertion order via LinkedHashMap` | Order parity with current List |
| `publish overwrites prior entry for same key` | Duplicate-triple semantics |
| `publish skips rows with blank addonId apiType or catalogId` | Defensive gate |
| `isEmpty true on init and after clear` | Lifecycle hooks |
| `isEmpty false after non-empty publish` | Sanity |
| `observeRail emits initial null then rail when published` | Cold subscriber gets latest on subscribe |
| `observeRail filters distinct — same content yields one emission` | `distinctUntilChanged` |
| `observeRail returns null when rail removed from publish` | Disappearance signal |
| `clear emits empty map to all observers` | Profile-switch path |
| `activeItemKeys aggregates apiType:id across all rails` | Helper correctness |
| `concurrent publish and snapshot does not tear` | Multiple coroutines; no exceptions; coherent reads |

### Integration tests (existing surface, migrated)

Tests in `app/src/test/java/com/nexio/tv/ui/screens/home/` that mock `HomeViewModel.fullCatalogRows` migrate to mocking `CatalogInventoryRepository`. Most likely sites: `HomeViewModelTest*`, `HomeViewModelCatalogPipeline*Test`. We grep `fullCatalogRows` in the test tree and migrate per site.

### Plan A regression coverage (existing, unaffected)

`home.display_projection`, `HomeRailProjectionReducer`, `ResolvedDisplayProjectionCache` — the resolved layer is unchanged.

### On-device verification gates

| Gate | How |
|---|---|
| SeeAll loads typical rail | Open Settings → addons → tap a "See all" link → grid renders with same items as before |
| SeeAll pagination still works | Scroll near bottom; load-more triggers; new items append |
| SeeAll recomposition frequency | While SeeAll is on screen, force a Modern Home refresh in background; SeeAll should NOT recompose unless the visible rail's content changes |
| Modern Home cold-start parity | Cold-start; rails render; no orphaned empties |
| Profile switch parity | Switch profile → inventory clears → rails repopulate from new profile |
| Heap dump after sustained soak | `heaptrail --leak-suspects --retained-size --exclude-soft-weak`: no `_fullCatalogRows`-shaped retainer; inventory map appears as a separate `CatalogInventoryRepository` dominator at ~17-28 MiB during peak |
| Filter-pass GC pattern unchanged | `filterDisabledHomeCatalogRows` already fixed in commit `da4706f0f`; behavior should remain at 27-42 MB / 0.6-0.7s concurrent GC |

### Acceptance threshold

- All existing home test suites pass (modulo the 5 pre-existing failures from baseline `026025bdf` — not regressions).
- New `CatalogInventoryRepositoryTest` 12/12 pass.
- Build: `:app:assembleUniversalDebug` succeeds.
- Heap dominator tree: `HomeViewModel`'s retained size **drops** by the inventory delta; inventory appears as a separate `CatalogInventoryRepository` dominator.

## Sequencing

Insert as a single discrete change between Plan B Surface 3 (Screensaver, complete) and Surface 4 (Continue Watching, Tasks 17-20). Continue Watching's resolved migration also needs single-item-by-key resolved lookup; this design solidifies the repository-singleton pattern that makes those tasks easier.
