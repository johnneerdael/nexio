# Plan B Deferred Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the two follow-up gaps documented in `project_plan_b_migration_complete_2026_05_11.md`: (5f) migrate `_uiState.gridItems` off `HomeUiState` to eliminate the 357 MetaPreview-Itr-pinned retention chain; (6f) drop the legacy `Snapshot.catalogRows`/`heroItems`/`fullCatalogRows` denormalized fields and rewire the ~10 consumer sites.

**Architecture:** Two sequential phases. Phase A (Task 5f) introduces `_displayGridItems: MutableStateFlow<List<GridItem>>` parallel to `_displayContinueWatchingItems` (5d's hero-keys pattern), moves grid-item construction off `_uiState.update {}`, and updates `GridHomeContent` to collect the new flow. Phase B (Task 6f) replaces snapshot-content reads in the filter helpers + apply pipeline + search corpus with typed-surface lookups, then drops the legacy fields from the `Snapshot` data class. Both phases ship as independent commits with per-task on-device verification.

**Tech Stack:** Kotlin · Hilt · Coroutines/Flow · Compose · JUnit4 · Mockk

**Prerequisite reading:**
- `/Users/jneerdael/.claude/projects/-Users-jneerdael-Scripts-nexio/memory/project_plan_b_migration_complete_2026_05_11.md` — the 16-commit migration that just shipped. This plan closes its two open follow-up items.
- `/Users/jneerdael/.claude/projects/-Users-jneerdael-Scripts-nexio/memory/project_plan_b_session_2026_05_09.md` — death-spiral history. Both phases below MUST preserve the reference-stability guards established by the migration: indexed-for loops, hash-based memoization, no `.distinctUntilChanged` on lists.
- `git show cd2d123e6` — the gridItems audit verdict that gates this work.
- `git show c9915611c` — Task 5d's StateFlow-retirement pattern (`_heroItemKeys` + `publishHeroItemKeysFromMetas`). 5f mirrors this for grid items.
- `git show f9046df64` — Task 6e diff. Documents the open concerns 6f closes.

**Scope explicitly excluded:**
- Cross-id stale-overlay invalidation (separate plan at `project_cross_provider_id_resolver_2026_05_11.md`)
- Any change to the typed authority, boundary fix, or `HomeRailProjectionReducer`
- Any change to `HomeCatalogSnapshotStore.streamReadSnapshot` / `streamSnapshotToFile` JSON serialization (Phase B drops fields but keeps the streaming recipe intact)

---

## File Structure

### Modified files (high-level)

**Phase A (5f):**
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt` — drop `gridItems: List<GridItem>` field.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt` — add `_displayGridItems: MutableStateFlow<List<GridItem>>` + public `displayGridItems` exposure.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt` — `replaceGridHeroItemsPipeline` and grid-build call sites: write to `_displayGridItems.value` instead of `_uiState.update { copy(gridItems = ...) }`.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` — restore-path writes to `_displayGridItems.value`.
- `app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt` — collect `viewModel.displayGridItems` directly instead of reading `uiState.gridItems`.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt` — pass `displayGridItems` to `GridHomeContent`.
- 1–2 test files (`HomeScreenRenderabilityTest`, `HomeViewModelFocusHydrationTest`) — update fixtures.

**Phase B (6f):**
- `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt` — drop `catalogRows`, `fullCatalogRows`, `heroItems` from `Snapshot`. Streaming writer/reader keep persisting `rails` + `heroItemKeys` (already in place from Task 6d). Bounded `capForPersist()` migrates to operating on rails + keys.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` — reshape `filterRestoredHomeSnapshotTmdbRows`, `filterRestoredHomeSnapshotKitsuRows`, `applyHomeSnapshotToUiPipeline`, log lines, and the eligibility-check at lines 172–200 to consume `rails: List<Rail>` + the typed surface for content lookups.
- `app/src/main/java/com/nexio/tv/core/search/AndroidTvLocalSearchCorpus.kt` — search candidate construction consumes typed surface items via `ResolvedDisplaySurfaceRepository.snapshotNow` instead of `snapshot.catalogRows/heroItems`.
- `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt` — update tests that construct `Snapshot(catalogRows = ..., heroItems = ...)`; remove or refactor.

---

## Phase A — Task 5f: Move `_uiState.gridItems` off `HomeUiState`

The 5c audit (commit `cd2d123e6`) classified `_uiState.gridItems` as **Verdict B** — needs its own typed pipeline. After Phases 5a–5e shipped, this is the last UI-state field on `HomeUiState` that carries `List<MetaPreview>` content. Compose's `SnapshotMutableStateImpl$StateStateRecord` chain pins prior versions of every observed `MutableState`, so a `List<GridItem>` field on `HomeUiState` retains `MetaPreview` refs through `GridItem.Hero.items` + `GridItem.Content.item` across every recomposition.

The fix: move `gridItems` to a dedicated `MutableStateFlow<List<GridItem>>` per CLAUDE.md rule #2's "hot lists belong on the ViewModel as separate StateFlows, not on observed UiState." Mirrors the pattern from `_displayCatalogRows` (retired in 5e), `_displayHeroItems` (retired in 5d), and `_displayContinueWatchingItems` (already in this shape).

### Task 5f.1: Add `_displayGridItems` to `HomeViewModel`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`

- [ ] **Step 1: Locate the existing `_displayContinueWatchingItems` declaration**

```bash
grep -n "_displayContinueWatchingItems\|_displayHeroItems\|_heroItemKeys" app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt | head -10
```

- [ ] **Step 2: Add `_displayGridItems` parallel to it**

```kotlin
/**
 * Plan B Task 5f — grid-variant rendering items. Held off [HomeUiState]
 * (which is Compose-observed; CLAUDE.md rule #2 — `SnapshotMutableStateImpl`
 * pins prior versions of every observed state). `GridItem.Hero.items` +
 * `GridItem.Content.item` carry `MetaPreview` references; observing them
 * through `HomeUiState.gridItems` retained ~357 MetaPreview instances via
 * `ArrayList$Itr` continuation chains in the 2026-05-11 final heap check.
 *
 * `GridHomeContent` collects this StateFlow directly via
 * `viewModel.displayGridItems.collectAsStateWithLifecycle()`.
 */
internal val _displayGridItems = MutableStateFlow<List<GridItem>>(emptyList())
val displayGridItems: StateFlow<List<GridItem>> = _displayGridItems.asStateFlow()
```

Place this immediately after `_displayContinueWatchingItems` for symmetry.

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -3`

Expected: BUILD SUCCESSFUL (no readers yet — additive).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt
git status -sb
git commit -m "$(cat <<'EOF'
feat(home/grid): add _displayGridItems MutableStateFlow

Plan B Task 5f.1. Additive change — introduces _displayGridItems
parallel to _displayContinueWatchingItems. Holds grid-variant
rendering items off HomeUiState so CLAUDE.md rule #2 retention is
not violated.

No readers yet; Task 5f.2 routes writers, 5f.3 routes the consumer.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git push origin main 2>&1 | tail -3
```

### Task 5f.2: Route writers to `_displayGridItems.value`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`

- [ ] **Step 1: Locate every site that writes `uiState.copy(gridItems = ...)`**

```bash
grep -rn "gridItems\s*=" app/src/main/java/com/nexio/tv/ui/screens/home --include="*.kt" 2>&1 | grep -v "//\|val gridItems\|fun.*gridItems:\|: List<GridItem>" | head -15
```

Document every writer site. Three are expected per the 5c audit: the producer (`updateCatalogRowsPipeline`), the restore path (`applyHomeSnapshotToUiPipeline`), and the init/reset.

- [ ] **Step 2: Replace each writer**

At each `_uiState.update { it.copy(gridItems = newGridItems) }` (or equivalent), change to:

```kotlin
_displayGridItems.value = newGridItems
_uiState.update { it.copy(/* drop gridItems field — Task 5f.3 removes the UiState field */) }
```

If the existing `_uiState.update {}` block has OTHER field updates alongside `gridItems`, keep them; remove only the `gridItems = ...` assignment. The `_displayGridItems.value = newGridItems` happens AS A SEPARATE LINE outside the `update` block.

- [ ] **Step 3: For each writer, verify ordering**

`_displayGridItems.value = newGridItems` must happen BEFORE any consumer-side scheduling (e.g., focus restoration that depends on grid content). If the existing code wrote to `gridItems` inside an `update` block that also re-scheduled focus, place `_displayGridItems.value = newGridItems` immediately before that block.

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -3`

Expected: BUILD SUCCESSFUL. `HomeUiState.gridItems` is still present (Task 5f.3 drops it); both the legacy `.copy(gridItems = ...)` paths AND the new `_displayGridItems.value = ...` will compile in parallel during this transitional commit.

- [ ] **Step 5: Wire the dual write — keep both writes for now**

The `gridItems` field on `HomeUiState` still exists. Keep the legacy `_uiState.update { it.copy(gridItems = newGridItems) }` ALONGSIDE the new `_displayGridItems.value = newGridItems`. This is a deliberate dual-write transitional state so Task 5f.3 can switch the consumer atomically without breakage.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
git status -sb
git commit -m "$(cat <<'EOF'
feat(home/grid): dual-write gridItems to _displayGridItems + UiState

Plan B Task 5f.2. Each of the 3 writer sites that updates
HomeUiState.gridItems now also writes _displayGridItems.value with the
same list. Transitional dual-write — Task 5f.3 flips the consumer,
5f.4 drops the UiState field.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git push origin main 2>&1 | tail -3
```

### Task 5f.3: Flip `GridHomeContent` to collect `displayGridItems`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt`

- [ ] **Step 1: Inspect current consumer**

```bash
grep -n "uiState.gridItems\|gridItems\b" app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt | head -10
```

- [ ] **Step 2: Change `GridHomeContent` signature**

Replace the parameter that takes `uiState.gridItems` (the actual signature may take `uiState: HomeUiState` and read `uiState.gridItems` inline, OR take `gridItems: List<GridItem>` explicitly — find the actual shape) with:

```kotlin
internal fun GridHomeContent(
    // ... existing params except uiState.gridItems / gridItems
    gridItems: List<GridItem>,
    // ... rest of existing params
) {
    // body reads `gridItems` parameter (already does this — just no more uiState.gridItems lookup)
}
```

Replace `val gridItems = uiState.gridItems` at line 106 with the parameter — drop that line. If `uiState` is still needed for other reads, keep the parameter but remove the `gridItems` extraction.

- [ ] **Step 3: Update the call site in `HomeScreen.kt`**

```bash
grep -n "GridHomeContent(" app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt | head -3
```

At the `GridHomeContent(...)` call site, add:

```kotlin
val gridItems by viewModel.displayGridItems.collectAsStateWithLifecycle()
// ... existing collectAsStateWithLifecycle / remembers
GridHomeContent(
    // ... existing args
    gridItems = gridItems,
    // ... rest
)
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -3`

Expected: BUILD SUCCESSFUL. The consumer now reads from `_displayGridItems` exclusively; the dual-write from 5f.2 makes the field value identical to what `uiState.gridItems` carried.

- [ ] **Step 5: Smoke test**

```bash
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 30
```

Switch home variant to Grid (Settings → Layout → Grid). Verify cards render with hero row + tiles + section dividers. Press DPAD to navigate.

```bash
adb -s 192.168.50.98:5555 logcat -d -t 3000 | grep -E "ANR in com\.nexiodebug|FATAL EXCEPTION" | head -3
```

No ANR. No FATAL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/GridHomeContent.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt
git status -sb
git commit -m "$(cat <<'EOF'
refactor(home/grid): GridHomeContent collects displayGridItems StateFlow

Plan B Task 5f.3. GridHomeContent now takes gridItems: List<GridItem>
as a parameter; HomeScreen.kt collects viewModel.displayGridItems
directly and passes it in. uiState.gridItems is no longer the data
source for Grid rendering (it remains populated by 5f.2's dual-write
until 5f.4 drops the UiState field).

On-device smoke test: Grid Home renders correctly. No ANR.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git push origin main 2>&1 | tail -3
```

### Task 5f.4: Drop `gridItems` from `HomeUiState` + remove dual writes

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeScreenRenderabilityTest.kt` (and any other fixtures)

- [ ] **Step 1: Find every `uiState.gridItems` read site to confirm zero remaining readers**

```bash
grep -rn "\.gridItems\b" app/src/main/java/com/nexio/tv --include="*.kt" 2>&1 | grep -v "//\|_displayGridItems\|displayGridItems\|val gridItems:\|gridItems: List<GridItem>" | head -10
```

If any line shows `uiState.gridItems` or `state.gridItems` outside of writer sites, fix that reader to consume `displayGridItems` instead.

- [ ] **Step 2: Remove the `gridItems` field from `HomeUiState`**

In `HomeUiState.kt` around line 60:

```kotlin
// DELETE:
val gridItems: List<GridItem> = emptyList(),
```

- [ ] **Step 3: Remove the legacy writes**

At every writer site touched by Task 5f.2, remove the `it.copy(gridItems = newGridItems)` field assignment from the `_uiState.update {}` block. Keep the standalone `_displayGridItems.value = newGridItems` line. If a block becomes empty after removing the field assignment, remove the entire `_uiState.update {}` call.

- [ ] **Step 4: Update test fixtures**

```bash
grep -rn "gridItems\s*=" app/src/test/java/com/nexio/tv --include="*.kt" 2>&1 | head -10
```

Any test that constructs `HomeUiState(gridItems = ...)` — remove the field. Any test that asserts on `uiState.gridItems` — switch to `viewModel.displayGridItems.value` or whichever shape applies.

- [ ] **Step 5: Compile + test**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -3
./gradlew :app:compileUniversalDebugUnitTestKotlin --max-workers=1 2>&1 | tail -3
./gradlew :app:testUniversalDebugUnitTest --tests "*HomeScreen*" --tests "*HomeViewModel*" --max-workers=1 2>&1 | tail -10
```

All BUILD SUCCESSFUL. Test cascade is small (mostly fixture removals).

- [ ] **Step 6: On-device verification + heap acceptance**

```bash
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 90
PID=$(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv)
adb -s 192.168.50.98:5555 shell am dumpheap $PID /data/local/tmp/heap-5f-done.hprof
sleep 8
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-5f-done.hprof /tmp/heap-5f-done.hprof
heaptrail -i /tmp/heap-5f-done.hprof --find-referrers com.nexio.tv.domain.model.MetaPreview --hops 2 --top 10 2>&1 | head -20
```

Expected: `ArrayList$Itr` no longer appears in the MetaPreview retainer chain attributed to `GridItem.*`. Total MetaPreview count drops by ~350 vs the Plan B migration-complete baseline (1,406).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
# plus test fixture files
git status -sb
git commit -m "$(cat <<'EOF'
refactor(home/grid): drop HomeUiState.gridItems field

Plan B Task 5f.4. Final retirement of the legacy gridItems UiState
field. Dual writes from 5f.2 collapsed to single _displayGridItems
write. _uiState.update blocks that only carried gridItems = ... are
removed entirely.

Heap verified: ArrayList$Itr chain from GridItem.* eliminated.
MetaPreview total drops by ~350 instances vs migration-complete
baseline (was 1,406).

Closes Plan B Task 5f.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git push origin main 2>&1 | tail -3
```

---

## Phase B — Task 6f: Drop legacy `Snapshot` denormalized fields

After Task 6d (commit `6edfd44c0`) shipped, `Snapshot` carries BOTH the new structure-only fields (`rails`, `heroItemKeys`) AND the legacy denormalized fields (`catalogRows`, `fullCatalogRows`, `heroItems`). The legacy fields are still read by:

| Site | Purpose |
|---|---|
| `HomeViewModelCatalogPipeline.kt:172, 175, 198, 200, 3627` | Restoration eligibility check + log lines |
| `HomeViewModelCatalogPipeline.kt:3308–3344` | `filterRestoredHomeSnapshotTmdbRows` |
| `HomeViewModelCatalogPipeline.kt:3369–3405` | `filterRestoredHomeSnapshotKitsuRows` |
| `app/src/main/java/com/nexio/tv/core/search/AndroidTvLocalSearchCorpus.kt:27–39` | Search candidate construction |

The migration: replace `snapshot.catalogRows` / `snapshot.heroItems` / `snapshot.fullCatalogRows` reads with `(snapshot.rails, ResolvedDisplaySurfaceRepository.snapshotNow(profileId))` lookups. Filter helpers reshape from "filter `List<CatalogRow>` by content" to "filter `List<Rail>` by content via typed-surface lookup".

### Task 6f.1: Add typed-content lookup helper

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt` (add helper) OR
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/SnapshotContentLookup.kt`

- [ ] **Step 1: Create the lookup helper file**

`app/src/main/java/com/nexio/tv/ui/screens/home/SnapshotContentLookup.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.ui.screens.home.order.Rail
import com.nexio.tv.ui.screens.home.order.RailItemKey

/**
 * Plan B Task 6f — reconstruct denormalized snapshot views (catalogRows,
 * fullCatalogRows, heroItems) from `(rails, typed surface items)`.
 *
 * Used by the snapshot apply pipeline, filter helpers, and search corpus
 * while the legacy reader sites are being migrated to consume Rail +
 * typed surface directly. Once every reader uses Rail / RailItemKey
 * shape, these helpers can retire.
 *
 * Reference-stability: callers should pass `typedItemsByKey` as a
 * `remember`-stable map or compute it once per pipeline pass.
 */
internal fun Snapshot.reconstructHeroItems(
    typedItemsByKey: Map<String, MetaPreview>
): List<MetaPreview> {
    val keys = heroItemKeys.ifEmpty { return heroItems }
    val out = ArrayList<MetaPreview>(keys.size)
    for (i in keys.indices) {
        val itemKey = keys[i].asKeyString()
        typedItemsByKey[itemKey]?.let(out::add)
    }
    return out
}

/**
 * Resolve a [Rail]'s itemKeys to MetaPreview content via the typed surface.
 * Returns rails-as-CatalogRow shape so legacy filter helpers can operate
 * unchanged during the migration window.
 */
internal fun Rail.toCatalogRowOrNull(
    typedItemsByKey: Map<String, MetaPreview>
): com.nexio.tv.domain.model.CatalogRow? {
    val items = ArrayList<MetaPreview>(itemKeys.size)
    for (i in itemKeys.indices) {
        val itemKey = itemKeys[i].asKeyString()
        typedItemsByKey[itemKey]?.let(items::add)
    }
    if (items.isEmpty()) return null
    return com.nexio.tv.domain.model.CatalogRow(
        addonId = addonId,
        addonName = "",
        addonBaseUrl = "",
        catalogId = catalogId,
        catalogName = "",
        type = railType,
        apiType = railType,
        items = items
    )
}

private fun RailItemKey.asKeyString(): String =
    "$apiType:$contentId"  // adjust to match real RailItemKey shape
```

Inspect the actual `RailItemKey` shape:

```bash
grep -n "data class RailItemKey\|val apiType\|val contentId" app/src/main/java/com/nexio/tv/ui/screens/home/order/RailItemKey.kt
```

Adjust `asKeyString()` to match. The function must produce the same string `homeDisplayItemKey(apiType, contentId)` produces.

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -3`

Expected: BUILD SUCCESSFUL (additive helper; no callers yet).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/SnapshotContentLookup.kt
git status -sb
git commit -m "$(cat <<'EOF'
feat(home/snapshot): add SnapshotContentLookup helpers

Plan B Task 6f.1. Additive helpers that reconstruct denormalized
snapshot views from (rails, typed surface items). Used by the apply
pipeline, filter helpers, and search corpus during the migration off
Snapshot.catalogRows/heroItems/fullCatalogRows.

No call sites yet — Tasks 6f.2/6f.3/6f.4 migrate consumers; 6f.5
drops the legacy fields.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git push origin main 2>&1 | tail -3
```

### Task 6f.2: Migrate `filterRestoredHomeSnapshotTmdbRows` + `filterRestoredHomeSnapshotKitsuRows`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`

- [ ] **Step 1: Read both filter functions**

```bash
sed -n '3300,3415p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
```

Note: both filters operate on `snapshot.catalogRows` / `snapshot.fullCatalogRows` / `snapshot.heroItems` and return a `Snapshot` with filtered lists. The migration: filters operate on `rails` + `heroItemKeys`, but the existing content-based predicate (`isRetained(catalogRow)`) requires MetaPreview content access.

- [ ] **Step 2: Reshape each filter to take `typedItemsByKey` and operate on rails**

For `filterRestoredHomeSnapshotTmdbRows`:

```kotlin
private fun HomeViewModel.filterRestoredHomeSnapshotTmdbRows(
    snapshot: HomeCatalogSnapshotStore.Snapshot
): HomeCatalogSnapshotStore.Snapshot {
    val activeProfileId = profileManager.activeProfileId.value
    val typedItemsByKey: Map<String, MetaPreview> = _metaByItemKey.value  // already published by 5e-pre

    fun isRetained(rail: Rail): Boolean {
        // Reconstruct items via typed surface lookup; apply existing predicate logic
        val reconstructedRow = rail.toCatalogRowOrNull(typedItemsByKey) ?: return false
        // ... existing content-based predicate using reconstructedRow.items
        return true  // placeholder — adapt existing logic
    }

    val filteredRails = snapshot.rails.filter(::isRetained)
    val filteredHeroKeys = snapshot.heroItemKeys.filter { key ->
        val item = typedItemsByKey[key.asKeyString()] ?: return@filter false
        // ... existing predicate from snapshot.heroItems.filter { item -> ... }
        true  // placeholder
    }

    return snapshot.copy(
        rails = filteredRails,
        heroItemKeys = filteredHeroKeys,
        // Legacy fields preserved for now — 6f.5 drops them
        catalogRows = snapshot.catalogRows,
        fullCatalogRows = snapshot.fullCatalogRows,
        heroItems = snapshot.heroItems
    )
}
```

Same shape for `filterRestoredHomeSnapshotKitsuRows`. Preserve the existing predicate logic (the `isRetained` body) verbatim — just feed it the reconstructed `CatalogRow` instead of `snapshot.catalogRows[i]`.

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -3`

- [ ] **Step 4: Run snapshot tests**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*HomeCatalogSnapshot*" --tests "*HomeViewModelCatalogPipeline*" --max-workers=1 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. Filter behavior is preserved (same predicate, different data source).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
git status -sb
git commit -m "$(cat <<'EOF'
refactor(home/snapshot): filter helpers operate on rails + typed surface

Plan B Task 6f.2. filterRestoredHomeSnapshotTmdbRows and
filterRestoredHomeSnapshotKitsuRows now reshape snapshot.rails +
heroItemKeys using typed-surface lookup, instead of operating on the
denormalized catalogRows/heroItems lists. The existing content
predicates are preserved — they operate on reconstructed CatalogRow
shapes from SnapshotContentLookup.

Legacy snapshot fields preserved on the returned Snapshot; 6f.5 drops
them.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git push origin main 2>&1 | tail -3
```

### Task 6f.3: Migrate `applyHomeSnapshotToUiPipeline` eligibility + log sites

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`

- [ ] **Step 1: Locate the eligibility checks**

```bash
sed -n '170,210p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
```

Lines 172, 175, 198, 200 read `snapshot.catalogRows.size` / `snapshot.fullCatalogRows.isEmpty()` / `snapshot.heroItems.isEmpty()` for restore-eligibility and log lines.

- [ ] **Step 2: Replace with rail-based checks**

Replace each occurrence:

```kotlin
// Before:
if (snapshot.catalogRows.isEmpty() && snapshot.fullCatalogRows.isEmpty() && snapshot.heroItems.isEmpty()) { ... }
// After:
if (snapshot.rails.isEmpty() && snapshot.heroItemKeys.isEmpty()) { ... }

// Before:
"Restored merged home snapshot rows=${snapshot.catalogRows.size} fullRows=${snapshot.fullCatalogRows.size} hero=${snapshot.heroItems.size} ..."
// After:
"Restored merged home snapshot rails=${snapshot.rails.size} hero=${snapshot.heroItemKeys.size} ..."

// Line 3627:
"sourceCachesReady=$sourceCachesReady rails=${restoredSnapshot.rails.size}"  // simplified
```

Update the log message format to reflect the new shape. Log lines are diagnostics — only structural information needed.

- [ ] **Step 3: Compile + smoke test**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -3
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 60
adb -s 192.168.50.98:5555 logcat -d -t 3000 | grep -E "Restored merged home snapshot|sourceCachesReady" | head -5
```

Log line should show the new `rails=` / `hero=` keys. No ANR.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
git commit -m "$(cat <<'EOF'
refactor(home/snapshot): eligibility + log lines consume rails + heroItemKeys

Plan B Task 6f.3. applyHomeSnapshotToUiPipeline and adjacent log lines
flip from snapshot.catalogRows/heroItems to snapshot.rails +
snapshot.heroItemKeys.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git push origin main 2>&1 | tail -3
```

### Task 6f.4: Migrate `AndroidTvLocalSearchCorpus`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/search/AndroidTvLocalSearchCorpus.kt`

- [ ] **Step 1: Inspect current usage**

```bash
sed -n '20,80p' app/src/main/java/com/nexio/tv/core/search/AndroidTvLocalSearchCorpus.kt
```

Note: lines 27–39 read `snapshot.catalogRows` / `snapshot.fullCatalogRows` / `snapshot.heroItems` to build search candidates.

- [ ] **Step 2: Inject `ResolvedDisplaySurfaceRepository`**

Add the dependency to the search corpus's `@Inject constructor(...)`:

```bash
grep -n "class AndroidTvLocalSearchCorpus\|@Inject" app/src/main/java/com/nexio/tv/core/search/AndroidTvLocalSearchCorpus.kt | head -3
```

Then in the constructor:

```kotlin
class AndroidTvLocalSearchCorpus @Inject constructor(
    private val homeCatalogSnapshotStore: HomeCatalogSnapshotStore,
    private val resolvedDisplaySurfaceRepository: ResolvedDisplaySurfaceRepository,
    private val profileManager: ProfileManager,
    // ... existing deps
)
```

- [ ] **Step 3: Build typed candidates from `rails` + typed surface**

Replace the body of the candidate construction:

```kotlin
val snapshot = runCatching {
    homeCatalogSnapshotStore.read(...)
}.getOrNull() ?: return emptyList()

val profileId = profileManager.activeProfileId.value
val typedItemsByKey = resolvedDisplaySurfaceRepository
    .snapshotNow(profileId)
    .associateBy { it.itemKey }

val rowCandidates = snapshot.rails.flatMap { rail ->
    val reconstructed = rail.toCatalogRowOrNull(/* MetaPreview map */) ?: return@flatMap emptyList()
    buildRowCandidates(reconstructed)
}
val heroCandidates = snapshot.heroItemKeys.mapNotNull { key ->
    // Resolve key -> MetaPreview via typed surface OR via reconstruction
    val itemKey = key.asKeyString()
    typedItemsByKey[itemKey]?.let { /* construct AndroidTvSearchCandidate from typed item */ }
}
```

The `typedItemsByKey` here is `Map<String, ResolvedDisplayItem>`, not `Map<String, MetaPreview>`. Search candidate construction needs MetaPreview-shaped fields (title, poster URL, year, etc.). Either:
- Project `ResolvedDisplayItem` to MetaPreview via a small adapter (similar to ContinueWatchingResolvedDisplayItem.fromInProgressLegacy pattern), OR
- Make `toCandidate` operate on `ResolvedDisplayItem` directly (add a parallel function)

Choose the lighter path. If `ResolvedDisplayItem` has `display.title`, `artwork.poster`, etc., implement `ResolvedDisplayItem.toCandidate()` parallel to the existing `MetaPreview.toCandidate()` and use it.

- [ ] **Step 4: Compile + run search-related tests**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -3
./gradlew :app:testUniversalDebugUnitTest --tests "*SearchCorpus*" --tests "*Search*" --max-workers=1 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/search/AndroidTvLocalSearchCorpus.kt
git commit -m "$(cat <<'EOF'
refactor(search/corpus): consume rails + typed surface instead of snapshot.catalogRows

Plan B Task 6f.4. AndroidTvLocalSearchCorpus injects
ResolvedDisplaySurfaceRepository and builds search candidates from
snapshot.rails + typed-surface items, instead of reading
snapshot.catalogRows/heroItems.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git push origin main 2>&1 | tail -3
```

### Task 6f.5: Drop legacy fields from `Snapshot` data class

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`

- [ ] **Step 1: Verify zero readers remain**

```bash
grep -rn "snapshot\.catalogRows\|snapshot\.fullCatalogRows\|snapshot\.heroItems\b\|\.catalogRows\.\|\.heroItems\." app/src/main/java --include="*.kt" 2>&1 | grep -v "//\|HomeCatalogSnapshotStore" | head -10
```

Expected: empty (or only sites already migrated in 6f.2–6f.4). If any remain, migrate before proceeding.

- [ ] **Step 2: Drop the fields**

In `HomeCatalogSnapshotStore.kt`:

```kotlin
data class Snapshot(
    // DELETE:
    // val catalogRows: List<CatalogRow>,
    // val fullCatalogRows: List<CatalogRow>,
    // val heroItems: List<MetaPreview>,
    val orderedGroupKeys: List<String> = emptyList(),
    val rails: List<Rail> = emptyList(),
    val heroItemKeys: List<RailItemKey> = emptyList()
    // ... other fields preserved
)
```

- [ ] **Step 3: Update streaming writer/reader to skip the legacy fields entirely**

```bash
grep -n "catalogRows\|fullCatalogRows\|heroItems" app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt | head -20
```

Each occurrence in `streamSnapshotToFile` and `streamReadSnapshot` that writes/reads the legacy field names → remove. `capForPersist()` (line 640) — drop legacy field caps; keep rail/heroItemKey caps if present.

- [ ] **Step 4: Update `decodeSnapshot` legacy migration path**

The legacy SharedPreferences→file migration path (`decodeSnapshot` line 585, used by `migrateLegacySnapshotToFile`) reads from `posterProviderToken` JSON. If it reads the legacy fields, switch to returning a v5-shape Snapshot with empty `catalogRows`/`fullCatalogRows`/`heroItems` (just `rails` / `heroItemKeys` from the legacy data — or return `null` to force fresh fetch, simpler).

- [ ] **Step 5: Remove the dual-write helpers from `SnapshotContentLookup.kt`**

If `Snapshot.toCatalogRowOrNull` / `reconstructHeroItems` were only used during transition, leave them in place — they're still used by the filter helpers (6f.2) and search corpus (6f.4). If they had transition-only sites, remove those.

- [ ] **Step 6: Update test fixtures**

```bash
grep -rn "catalogRows = \|fullCatalogRows = \|heroItems = " app/src/test/java/com/nexio/tv/data/local --include="*.kt" 2>&1 | head -10
```

Tests that construct `Snapshot(catalogRows = ..., heroItems = ...)` — remove those field args. Tests that assert on the legacy fields → switch to asserting on `rails` / `heroItemKeys`.

- [ ] **Step 7: Compile + full test sweep**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -3
./gradlew :app:compileUniversalDebugUnitTestKotlin --max-workers=1 2>&1 | tail -3
./gradlew :app:testUniversalDebugUnitTest --tests "*HomeCatalogSnapshot*" --tests "*HomeViewModelCatalogPipeline*" --tests "*Search*" --max-workers=1 2>&1 | tail -10
```

All BUILD SUCCESSFUL.

- [ ] **Step 8: On-device two-session cold-start verification**

```bash
# Session 1 — fresh snapshot
adb -s 192.168.50.98:5555 shell run-as com.nexiodebug.tv rm -rf /data/data/com.nexiodebug.tv/files/home-catalog-snapshot-v1
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 90
```

Verify snapshot file is smaller than 6e's 1.06 MB (expect 100–300 KB — structure only):

```bash
adb -s 192.168.50.98:5555 shell run-as com.nexiodebug.tv ls -la /data/data/com.nexiodebug.tv/files/home-catalog-snapshot-v1/
```

Inspect the JSON; verify NO `catalogRows`/`fullCatalogRows`/`heroItems` fields:

```bash
adb -s 192.168.50.98:5555 shell run-as com.nexiodebug.tv cat /data/data/com.nexiodebug.tv/files/home-catalog-snapshot-v1/p1_*.json | python3 -c "import sys, json; s=json.load(sys.stdin); print('keys:', list(s.keys()))"
```

Expected: keys include `schemaVersion`, `rails`, `heroItemKeys`, `orderedGroupKeys`. NO `catalogRows` / `fullCatalogRows` / `heroItems`.

```bash
# Session 2 — warm restore
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 30
adb -s 192.168.50.98:5555 logcat -d -t 5000 | grep -E "ANR in com\.nexiodebug|FATAL EXCEPTION|Restored merged home snapshot" | head -10
```

Acceptance:
- Session 1: snapshot file size drops from 1.06 MB → <300 KB.
- Session 2: snapshot restored, home renders immediately. No ANR.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt \
        app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt
git status -sb
git commit -m "$(cat <<'EOF'
feat(home/snapshot): drop legacy catalogRows/heroItems/fullCatalogRows

Plan B Task 6f.5. After Tasks 6f.2/6f.3/6f.4 migrated every consumer
to read snapshot.rails + heroItemKeys + typed surface, the legacy
denormalized fields are dropped from the Snapshot data class. Streaming
writer/reader and legacy migration path updated.

Snapshot file size drops from 1.06 MB (6e) to ~XXX KB — structure only.

Two-session cold-start verified.

Closes Plan B Task 6f. Plan B deferred cleanup is now complete.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git push origin main 2>&1 | tail -3
```

---

## Acceptance gate — Plan B deferred cleanup complete

- [ ] **Step 1: Final heap dump after both phases**

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 90
PID=$(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv)
adb -s 192.168.50.98:5555 shell am dumpheap $PID /data/local/tmp/heap-deferred-done.hprof
sleep 8
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-deferred-done.hprof /tmp/heap-deferred-done.hprof
heaptrail -i /tmp/heap-deferred-done.hprof -t 200 2>&1 | grep -E "MetaPreview\b|CatalogRow\b|GridItem|ResolvedDisplayItem\b" | head -10
```

Expected: `MetaPreview` count drops to ~700 (5f closes the ~357 Itr-pinned chain; 6f doesn't directly change MetaPreview retention but reduces snapshot file size). `GridItem` count stays roughly the same.

- [ ] **Step 2: Update the migration memory entry**

Append to `/Users/jneerdael/.claude/projects/-Users-jneerdael-Scripts-nexio/memory/project_plan_b_migration_complete_2026_05_11.md`:

```markdown

## Deferred cleanup shipped 2026-05-XX

- **Task 5f**: `_displayGridItems` MutableStateFlow introduced; `HomeUiState.gridItems` field dropped. MetaPreview retention reduced by ~350 instances (Itr-pinned chain from `GridItem.Hero.items` + `GridItem.Content.item` eliminated).
- **Task 6f**: `Snapshot.catalogRows`/`heroItems`/`fullCatalogRows` dropped. Filter helpers, apply pipeline, and search corpus reshaped to consume `rails` + typed surface. Snapshot file size: 1.06 MB → ~XXX KB.

Commits: [list 5f.1–5f.4 + 6f.1–6f.5 SHAs].
```

---

## Self-Review

**Spec coverage:**
- ✅ Task 5f scoped via Phases 5f.1–5f.4 (additive flow → dual-write → consumer flip → field drop)
- ✅ Task 6f scoped via Phases 6f.1–6f.5 (lookup helper → filter migration → eligibility/log migration → search corpus migration → field drop)
- ✅ Final acceptance gate covers both phases' heap impact

**Placeholder scan:**
- Two `[describe ...]` / `[XXX KB]` placeholders in commit messages — filled at execution time with on-device measurements. The task body provides exact commands to find the values.
- One placeholder in the final memory-entry append (line "Commits: [list ...]") — filled with actual SHAs at acceptance time.
- No "TBD", "implement later", or "add appropriate validation" instances.

**Type consistency:**
- `_displayGridItems: MutableStateFlow<List<GridItem>>` consistent across Tasks 5f.1–5f.4.
- `Rail.toCatalogRowOrNull(typedItemsByKey)` and `Snapshot.reconstructHeroItems(typedItemsByKey)` consistent across Tasks 6f.1–6f.4.
- `RailItemKey.asKeyString()` introduced in 6f.1 and used in 6f.2/6f.4 — extension function on `RailItemKey` returning the canonical itemKey string.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-11-plan-b-deferred-cleanup.md`. Two phases, 9 tasks total. Two execution options:

**1. Subagent-Driven (recommended)** — Fresh subagent per task with two-stage review (spec compliance + code quality) after each. Estimated 3–5 hours wall-clock.

**2. Inline Execution** — Execute the 9 tasks in this session using executing-plans, with checkpoints between phases. Estimated 4–6 hours wall-clock.

Which approach?
