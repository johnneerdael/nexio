# Modern Home Allocation Rate + Retention Rework

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to execute. Each task ends with a heap-dump verification gate against the metrics established here. Do NOT skip verification — surgical fixes alone are confirmed insufficient.

**Goal:** Eliminate the Modern Home death spiral. Today on rooted device 192.168.50.98 (debug build), both `e34f06f6e` baseline and `feat/resolved-display-authority` branch (with Bug #1 + Bug #2 fixes) hang within ~3 minutes of Modern Home use, heap pinned at 511/512 MB with 2-3 second GC pauses freeing 10-20 KB per cycle. Iterator and Gson retention have been fixed (96% / 100% reductions verified). Remaining problem is **allocation rate**: each catalog refresh allocates ~1,900 fresh `MetaPreview` instances, and the cumulative retained heap (mostly via Compose composition + still-running coroutines) saturates the JVM cap.

**Architecture context:** The `updateCatalogRowsPipeline` function fires on every overlay update, every Trakt/Simkl/MDBList/TMDB/Kitsu snapshot change, and every UI state change. It rebuilds CatalogRow lists from raw rail data, allocating fresh `CatalogRow` and `MetaPreview` instances even when content is structurally unchanged. The downstream apply seam, hero enrichment, mapper, and snapshot store all create derivative copies.

**Out of scope:** Bug #1 (`persistHomeSnapshotDebouncedPipeline` cancellation cooperation) and Bug #2 (`syncRails` iterator capture) are already fixed on `feat/resolved-display-authority` (commits `89d857147` and `bc0a53603`). They are KEPT.

## Verification gates (heap metrics)

After each task, soak Modern Home for 3 minutes and capture a heap dump. Acceptance bars:

| Metric | Pre-rework (current branch+fixes) | Acceptance after each phase |
|---|---|---|
| Heap dump size at 3 min | 450 MB | Phase 1: ≤ 350 MB |
| `MetaPreview` instances | 674,303 | Phase 1: ≤ 200,000 |
| `CatalogRow` instances | 33,722 | Phase 1: ≤ 5,000 |
| `Object[]` arrays containing CatalogRow | 23 | ≤ 10 |
| Death-spiral hit (heap saturates 512 MB) | yes within 3 min | NO within 10 min |
| `home.snapshot_decision_lookup decisionLookupCount` per event | 103,830 (peak) | ≤ 5,000 |

Tooling: `/tmp/hprof-analyze-rust/target/release/hprof-analyze-rust` for class histogram + 1/2/3-hop reverse-reference traces. Standard adb flow:
```
adb -s 192.168.50.98:5555 shell am dumpheap $(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv) /data/local/tmp/heap.hprof
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap.hprof
~/Library/Android/sdk/platform-tools/hprof-conv heap.hprof heap-jvm.hprof
/tmp/hprof-analyze-rust/target/release/hprof-analyze-rust heap-jvm.hprof com.nexio.tv.domain.model.MetaPreview
```

---

## Phase 1 — Coalesce `updateCatalogRowsPipeline` allocation

The dominant allocator. Currently fires ~9 times per second on Modern Home. The early-return signature gate at `HomeViewModelCatalogPipeline.kt:2565` (`computationSignature == lastCatalogComputationSignature`) compares hashes, so it short-circuits identical inputs. But the signature includes `currentHydratedHomeOverlays` (the overlay map) which changes per overlay arrival. Fix: tighter equality on the overlay contribution; share allocations across refreshes.

### Task 1.1: Memoize `CatalogRow` construction by content key

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:2570-2840` (the `updateResult` builder block)
- Add: `app/src/main/java/com/nexio/tv/ui/screens/home/CatalogRowMemo.kt` — a per-pipeline memoization cache keyed by `(addonId, catalogId, items.contentSignature)` returning a stable `CatalogRow` reference

**Approach:**
- Compute a content-hash of `(catalogId + items.map { it.id + it.firstPaintHash })` per row
- If the cache has the same content-hash, return the SAME `CatalogRow` reference (no allocation)
- The cache is a `WeakHashMap<String, CatalogRow>` keyed by content hash → CatalogRow weak ref. Bound by `min(N catalogs × 2, 200)` entries with LRU eviction.

**Verification:** Capture heap, expect `CatalogRow` count to drop from 33,722 → ~few hundred (one per active catalog × generation overlap, not 622×). 

### Task 1.2: Persistent (structural-share) `List<MetaPreview>` for row items

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/CatalogRow.kt` — change `items: List<MetaPreview>` to `items: PersistentList<MetaPreview>` (kotlinx.collections.immutable)
- Modify: every callsite that constructs CatalogRow with a `List<MetaPreview>` (search for `CatalogRow(...)` and `.copy(items = ...)`)

**Approach:** PersistentList copies share underlying tree structure; `list.copy(items = list.items.add(item))` allocates ~32 bytes vs creating a new `ArrayList<MetaPreview>` with full copy.

**Verification:** Object[] count for `Object[].elementData` containing MetaPreview drops; ArrayList instance count drops correspondingly.

### Task 1.3: Trigger-rate audit of `scheduleUpdateCatalogRows`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt:882-905` (`scheduleUpdateCatalogRows` debounce)

**Approach:** Today the debounce is 50-200 ms based on `pendingCatalogLoads`. On Modern Home (no pending loads, just overlay arrivals) the floor is 50 ms = 20 calls/sec. Increase floor to 250 ms when neither rails nor overlays have changed in the last 1 s (tracking via timestamp of last actual content change). Use a separate `lastSignificantChangeAtMs` timestamp.

**Verification:** Pipeline-call count over 3 min drops 5-10×. Use a counter increment in `updateCatalogRowsPipeline` and emit a periodic trace event.

---

## Phase 2 — Compose composition retention

Pre-fix dump showed 32 `SlotTable.slots` + 16 `SlotWriter.slots` references holding `Object[]` containing CatalogRow. Post Bug #1+#2 fix that count is **0** in 3-hop trace. The Compose retention may have been transient. Re-validate after Phase 1; if Compose composition still holds CatalogRow, investigate `mutableStateOf<List<CatalogRow>>` patterns.

### Task 2.1: State holder audit

**Files:**
- `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt` and `ModernHomeRows.kt` — search for `mutableStateOf<List<CatalogRow>>`, `mutableStateOf<List<MetaPreview>>`
- Check if any state holder retains the prior list across recompositions instead of replacing it

**Approach:** Compose's `MutableState<T>` keeps the prior value via `SnapshotStateRecord` until the next snapshot apply. With `T = List<CatalogRow>`, that's a full prior-version retention. Switch to `mutableStateOf<PersistentList<CatalogRow>>` (after Task 1.2 lands) so the prior version shares structure.

**Verification:** Capture heap during recomposition. `SlotTable.slots` references holding CatalogRow Object[] should remain 0.

---

## Phase 3 — Continuation closure capture audit

Bug #1+#2 fixed the highest-impact sites. Audit remaining suspend functions that iterate catalog data with suspending bodies.

### Task 3.1: Grep for suspending forEach over catalog content

```
grep -rn "items\.forEach\|catalogRows\.forEach\|displayRows\.forEach\|fullCatalogRows\.forEach\|fullRows\.forEach\|heroItems\.forEach\|memberships\.forEach" app/src/main/java/ | grep -v test/
```

**Files (audit each, fix with indexed-for if body suspends):**
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt:364`
- `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt:362,372,382` (forEachIndexed in sanitize)
- `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt:270,271` (forEach in build)
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:574, 2279, 2627` (forEach in update path)
- `app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt:320`
- `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt:1914`

**Approach:** For each, determine if the body suspends. If yes, replace `forEach` with `for (i in list.indices)` to avoid `ArrayList$Itr` allocation+capture in the continuation.

**Verification:** Capture heap. `ArrayList$Itr` instances drop from current 1,882 → < 200 (the rest will be Collections.UnmodifiableCollection.iterator() and other pre-existing patterns).

### Task 3.2: `asSequence()` audit

44 sites in home/. Already dropped from 39,392 to 211 via Bug #1 fix. Most remaining are transient. Re-audit after Phase 1 — if any `asSequence().toList()` could be replaced with eager iteration, reduce.

---

## Phase 4 — `DurableArtworkDecisionCache` write batching

`HomeCatalogSnapshotStore.read` triggers ~103,830 `decisionLookupCount` per snapshot read on a saturated catalog. Each lookup allocates intermediate Gson trees during the cache JSON parse path. The lookup's frequency multiplies the allocation cost.

### Task 4.1: Reduce `home.snapshot_decision_lookup` per-row work

**Files:**
- `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt:1069-1097` (`recordDecisionLookup`)
- `app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCache.kt:475-490` (`persistJsonToFile`)

**Approach:** The cache currently writes the entire JSON tree via `gson.toJson(storeJson)` on every `put()`. With ~1,149 cached decisions and many puts per second, that's huge JSON serialization overhead. Add a debounce / coalesce on writes (e.g., schedule write 200 ms after the last `put`, batching multiple decisions into one disk write).

**Verification:** GC events drop in count (currently ~38/min). Allocation rate drops accordingly.

---

## Phase 5 — Final verification

After Phases 1-4 land, soak Modern Home for **10 continuous minutes** and capture heap. Acceptance:
- Heap stays below 350 MB the entire time
- No GC pause longer than 200 ms
- No "Waiting for blocking GC" log lines
- Modern Home navigation feels responsive (user-confirmed)

If any phase fails its gate, revert that phase's changes and reopen its tasks.

---

## Tooling references

- Heap analysis tool: `/tmp/hprof-analyze-rust/` — Rust + jvm-hprof crate, supports 32-bit Android hprofs (after `hprof-conv`). 1/2/3-hop reverse-reference tracing.
- Custom Go analyzer (less feature-rich): `/tmp/hprof-analyze/`
- Logcat filter: `adb shell logcat -v threadtime | grep -E "Background concurrent.*GC|home\.|Nexio\."`
- Profile-select bypass: `adb shell am start -n com.nexiodebug.tv/com.nexio.tv.MainActivity` then click profile manually (TV remote required)

## Pre-existing baseline reference data (e34f06f6e)

For diff comparisons during this rework, the pre-plan baseline at `e34f06f6e` exhibited:
- 28,627 MetaPreview at 43 s elapsed (heap 229 MB)
- App also hangs on Modern Home within similar time frame — leak is pre-existing in app architecture

This rework's ambition is to fix the architecture, not to match baseline.
