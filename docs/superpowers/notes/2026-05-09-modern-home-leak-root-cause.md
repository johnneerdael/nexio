# Modern Home Memory Leak — Root-Cause Investigation

**Date:** 2026-05-09
**Investigator:** Claude (during `feat/resolved-display-authority` branch verification)
**Device:** UGOOS-AM6 rooted debug, 192.168.50.98
**APKs analyzed:**
- Branch tip `6258748f3` (post Task-8-revert) — heap dump 567 MB, 947K MetaPreview
- Pre-plan baseline `e34f06f6e` — heap dump 229 MB, 28K MetaPreview (in 43 s)

## Symptom

Both branch and baseline hang on Modern Home with severe GC pressure:
- Java heap saturates near `Heap Size` capacity (425-472 MB / 450-496 MB)
- Continuous Background-concurrent-copying GC: 1 GC/sec, 280 ms - 1.4 s pauses
- 72-89% janky frames; 95th-percentile frame render time 2-5 seconds
- App eventually becomes unresponsive

User confirms baseline (pre-plan, no branch code) **also hangs** on Modern Home with the same symptom. The leak is therefore **pre-existing in app architecture**, not introduced by this branch.

## Retention Chain

```
GC ROOTS (multiple)
  ↓
~91 Object[] arrays (~3,000 CatalogRow refs each)
  ↓ Object[].elementData
~47K ArrayList<MetaPreview>  
  ↓ CatalogRow.items
947K MetaPreview retained
```

## Top GC-root paths to those 91 Object[] arrays (3-hop trace)

| Holder | Refs | Notes |
|---|---|---|
| `ArrayList$Itr.this$0` + `ArrayList$ListItr.this$0` | 39 | **Iterator capture in suspended coroutines** |
| `LinkedHashMap.value` | 14 | Map with CatalogRow values |
| `kotlin.Pair.second` | 8 | Pair holding ArrayList |
| `PersistedSyntheticCatalogGroup.rows` + `SyntheticCatalogOrderGroup.rows` | 16 | Synthetic catalog state |
| `HomeCatalogSnapshotStore$Snapshot.fullCatalogRows` + `.catalogRows` | 10 | **Persist coroutines retaining Snapshots** |
| `ModernHomeContentState.catalogRows` + `HomeUiState.catalogRows` | 8 | Compose UI state |
| `androidx.compose.runtime.SlotTable.slots` + `SlotWriter.slots` | 48 | **Compose composition retention** |
| `kotlin.collections...asSequence$1.this_asSequence_inlined` | 39,392 | **`asSequence()` chain retention** (44 sites in home/) |

## Concrete Bug #1: `persistHomeSnapshotDebouncedPipeline`

`HomeViewModelCatalogPipeline.kt:3458`

```kotlin
homeSnapshotPersistJob?.cancel()
homeSnapshotPersistJob = viewModelScope.launch(Dispatchers.IO) {
    delay(HOME_SNAPSHOT_PERSIST_DEBOUNCE_MS)
    if (homeSnapshotPersistGeneration != persistGeneration) return@launch
    ...
    integrationOwnershipService.syncRails(...)  // suspend, captures snapshot in continuation
    homeCatalogSnapshotStore.write(latestSnapshot, ...)  // blocking IO, no cancellation check
    ...
}
```

**Problem:** `cancel()` is cooperative. After `delay()` returns, the coroutine runs through `syncRails()` and the blocking `write()` without ever checking `isActive`. The captured `snapshot` (full catalog) stays alive in the coroutine continuation until the I/O completes. With debounced calls firing every catalog refresh (~9/sec on Modern Home), 4-5 cancelled-but-still-running coroutines each retain a full Snapshot.

**Heap proof:** 5 Snapshot instances retained, 4 of them held by `HomeViewModelCatalogPipelineKt$persistHomeSnapshotDebouncedPipeline$...` continuation closures.

**Fix:** Add `ensureActive()` between `delay()` and IO work. Better: use `withTimeoutOrNull` or chunk the IO with explicit yield points.

## Concrete Bug #2: Iterator capture in `IntegrationOwnershipService.syncRails`

`IntegrationOwnershipService.kt:53`

```kotlin
suspend fun syncRails(namespacePrefix: String, memberships: List<RailMembership>) {
    val desiredKeys = memberships.map { it.rail.railKey }.toSet()
    railStoreDao.railsWithPrefix(namespacePrefix).forEach { existing ->  // ← iterator captured
        if (existing.railKey !in desiredKeys) {
            removeRail(existing.railKey)  // suspend
        }
    }
    memberships.forEach { upsertRailMembership(it) }  // ← iterator captured + suspend
}
```

**Problem:** `forEach` allocates an `ArrayList$Itr` whose `this$0` field pins the parent ArrayList. When the lambda body suspends (via `removeRail`/`upsertRailMembership`), the iterator is saved in the coroutine continuation. The parent `memberships` list — and everything it transitively references — stays alive until the function completes. Same pattern occurs in many other suspend functions (98 in home/+integration/).

**Heap proof:** `IntegrationOwnershipService$syncRails$1.L$4` directly holds 4 `ArrayList$Itr` instances; `ProviderPlanRunner$run$1.L$5` holds 8.

**Fix:** Either (a) split into non-suspending iteration + batched suspending operations, (b) use indexed `for` instead of `forEach` (does not capture iterator state in continuation), or (c) snapshot the list to a primitive array before iterating.

## Concrete Bug #3: Compose `SlotTable`/`SlotWriter` retention

48 references from Compose composition internals (`SlotTable.slots`, `SlotWriter.slots`) to `Object[]` arrays containing CatalogRow. Each Compose recomposition can keep a shadow snapshot of state. With `MutableState<List<CatalogRow>>` patterns, the previous list's structure may be retained until the next snapshot apply.

**Likely fix:** Investigate which Compose state holders contain `List<CatalogRow>` vs immutable `ImmutableList<CatalogRow>`. The latter does not retain via SnapshotStateList.

## Concrete Bug #4: `asSequence()` chain retention

39,392 `kotlin.collections.CollectionsKt___CollectionsKt$asSequence$$inlined$Sequence$1` instances. 44 `asSequence()` call sites in home/. When a sequence is held by a Flow/StateFlow/Compose state, its captured iterator pins the source list.

**Fix:** Audit `.asSequence()` usage. Prefer `.toList()` or eager evaluation when the result is stored.

## Allocation rate (independent of all the above)

Each catalog refresh allocates fresh CatalogRow + MetaPreview instances (rail re-emit produces fresh `MetaPreview.copy()`, etc.). With ~9 refreshes/sec and 76 rows × 25 items, that's ~17,100 allocations/sec just at steady state. Even with all leaks fixed, the allocation rate alone keeps the heap under pressure.

**Fix:** Memoize MetaPreview/CatalogRow when input content is unchanged. Reduce refresh frequency. Move from raw `List<MetaPreview>` to `kotlinx.collections.immutable.PersistentList` for structural sharing.

## Why this branch (`feat/resolved-display-authority`) does not change the picture

Compared to baseline (e34f06f6e):
- Branch's *new* types (`ResolvedDisplayItem`, `ResolvedDisplayFieldSlots`, `ResolvedSlot`) are minimally retained (131 / 0 / few hundred).
- Branch's only behavior change still in code is `Task 9` catalog refresh routing through the reducer. Per-call work is heavier but it doesn't increase the *number* of CatalogRow/MetaPreview allocations.
- The heap-size delta (567 MB vs 229 MB) is explained by elapsed time on Modern Home (~30 min vs 43 s); both grow at similar per-second rates.

## Recommended order of fixes

1. **Bug #1 (debounced persist)** — small surgical change, high impact, easy to verify with heap dump.
2. **Bug #2 (syncRails forEach)** — small mechanical change: convert to indexed for or copy-to-array.
3. **Bug #4 (asSequence audit)** — touch every `.asSequence()` site that stores into long-lived state.
4. **Bug #3 (Compose retention)** — bigger investigation, requires Compose snapshot tracing.
5. **Allocation-rate** — architectural; needs persistent collections + memoization. Out of scope for any single bug fix.

## Tooling notes

- `hprof-conv` strips Android-specific heap records (`0x8D` ROOT_VM_INTERNAL, etc.) needed by JVM-style parsers.
- `jvm-hprof` Rust crate (0.1.0) panics on Android-specific subrecords; works on hprof-conv'd output.
- `hprof-slurp` doesn't support 32-bit pointers (Android always emits 32-bit).
- Custom Go (`hprof-parser`) and Rust (`jvm-hprof`) analyzers were extended for 1-, 2-, and 3-hop reverse-reference tracing in `/tmp/hprof-analyze-rust/` — useful for any future leak hunt.
