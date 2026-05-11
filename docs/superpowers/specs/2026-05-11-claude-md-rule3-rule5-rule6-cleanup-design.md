# CLAUDE.md rule #3 / #5 / #6 cleanup — design

**Date:** 2026-05-11
**Status:** spec, awaiting user review
**Scope:** P0 (this session) + P1 + P2 (follow-up sessions). Implementation cycle starts after spec approval via `writing-plans`.

---

## Background

A 2026-05-11 audit followed by a heaptrail-validated heap dump (post-v0.56, 31.77 MiB raw shallow, profile-selected + 45 s Modern Home soak) found three residual classes of CLAUDE.md hard-rule violations on the home pipeline:

1. **Rule #3** — seven SharedPreferences-backed stores still calling `prefs.edit().putString(KEY, gson.toJson(payload))`. The largest, `hydrated_home_overlay_v1.xml`, is 542 KiB on-disk (10.8× the 50 KiB rule #3 ban).
2. **Rule #6** — `HomeViewModel.updateCatalogRowsPipeline` binds five large values as outer-fun locals (`currentHydratedHomeOverlays`, four discovery snapshots). One live `updateCatalogRowsPipeline$1` continuation captured in the heap suspended in `toResolvedDisplayItemsEnriched`, holding those locals in its frame for the duration of the function body's fan-out.
3. **Rule #5** — `ResolvedDisplayItem.preferredArtworkProviders` retains 526 distinct `LinkedHashMap` instances despite most items sharing the same 4-entry provider tuple. Added in Plan B Task 11; missing the content→reference memo at the producer boundary.

Plus one piece of legacy cruft: `metadata_disk_cache_v1.xml` is 217 KiB on-disk but the store has already migrated to streaming JSON in `filesDir`. The old XML lingers as a one-way migration source that was never deleted after success.

### Validated findings

| Site | Source | On-disk | vs 50 KiB ban | Heap evidence |
|---|---|---|---|---|
| `HydratedHomeOverlayStore.kt:74,77` | active putString | 542 KiB | 10.8× | confirmed |
| `MediaClipStore.kt:156` | active putString | 192 KiB | 3.8× | confirmed |
| `CatalogDiskCacheStore.kt:57` | active putString | 66 KiB | 1.3× | confirmed |
| `TraktDiscoverySnapshotStore.kt:97` | active putString | 48 KiB | at threshold | confirmed |
| `SimklDiscoverySnapshotStore.kt:101,134` | active putString | n/a (empty) | — | source-side only |
| `TvdbIdentityCacheStore.kt:52` | active putString | n/a (empty) | — | source-side only |
| `AddonRepositoryImpl.kt:125` | active putString | 35 KiB | under, growing | source-side only |
| `updateCatalogRowsPipeline` outer-fun locals (lines 2562–2586) | rule #6 | — | — | 1 live `updateCatalogRowsPipeline$1` continuation captured awaiting `toResolvedDisplayItemsEnriched` |
| `ResolvedDisplayItem.preferredArtworkProviders` | rule #5 | — | — | 526 `LinkedHashMap` instances (one per item) |
| `metadata_disk_cache_v1.xml` (legacy) | cruft | 217 KiB | (already migrated) | — |

`DurableArtworkDecisionCache.kt` claim of an 87 KiB transient `StringBuilder` peak from the prior audit could not be reproduced in the current heap and is **not in scope** for this design. Read-side `gson.fromJson(rawString, type)` bans (separate CLAUDE.md rule #3 clause) are also out of scope; a separate sweep is queued.

---

## Architecture

Three independently shippable workstreams. Each commit closes one rule violation against one source site.

```
                ┌─────────────────────────────────────────┐
                │ Shared file-streaming JSON recipe       │
                │ (HomeCatalogSnapshotStore reference)    │
                └────────────┬────────────────────────────┘
                             │
   ┌─────────────────────────┼──────────────────────────────┐
   │ P0 (this session)       │ P1 (follow-up)               │
   │                         │                              │
   │ 1. HydratedHomeOverlay  │ 3. MediaClipStore            │
   │    Store migration      │ 4. CatalogDiskCacheStore     │
   │ 2. updateCatalogRows-   │ 5. TraktDiscoverySnapshot    │
   │    Pipeline rule #6     │ 6. SimklDiscoverySnapshot    │
   │                         │ 7. TvdbIdentityCacheStore    │
   │                         │ 8. AddonRepositoryImpl manifest │
   └─────────────────────────┴──────────────────────────────┘
                             │
                             │ P2 (follow-up)
                             ▼
                  9.  PreferredArtworkProvidersMemo (rule #5)
                  10. LegacyPrefsCleanupPass (delete migrated XMLs)
```

The shared recipe is the streaming write/read pattern committed at `HomeCatalogSnapshotStore.streamSnapshotToFile` (`bc7b5061a`) and the artwork-cache stores (`d2272e8f1`). The recipe is *re-applied* per store — no abstraction layer; each store has its own JSON schema and access pattern.

---

## P0 details

### 1. HydratedHomeOverlayStore — file-backed migration

**File layout:** single JSON file per profile.

Path: `filesDir/hydrated-home-overlay-v1/p<profileId>.json`

Schema:
```jsonc
{
  "schemaVersion": 1,
  "overlays": { "<overlayKey>": <HydratedHomeOverlay-json> },
  "aliases":  { "<aliasPrefsKey>": "<overlayKey>" }
}
```

`schemaVersion: 1` is the file-backed format's first revision (independent of the legacy XML, which carried no internal version field). Future schema changes (e.g., adding new fields to `HydratedHomeOverlay`) bump this number.

In-memory state (`staleItemKeys: MutableStateFlow<Set<String>>`) stays in-memory — it's transient invalidation, not persisted state.

**Read path (cold-start):** `FileInputStream` → `BufferedReader` → `JsonReader` → populated `LinkedHashMap<overlayKey, HydratedHomeOverlay>` + alias map. No String materialization at any point.

**Write path (debounced):** in-memory upserts mutate the working maps synchronously; a write-coalescer coroutine flushes the *full* state via streaming `JsonWriter` to a temp file then `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`. Debounce window: **250 ms** (rationale: hydration_applied bursts in 50–200 ms windows; 250 ms catches each burst as one write). Maximum 4 writes/sec during initial burst at ~10 ms I/O each = ~40 ms/sec CPU; quiescent after warm-up.

**Migration:** boot-once. If legacy `hydrated_home_overlay_v1` SharedPreferences XML exists AND new `p<profileId>.json` does not, read every legacy entry into memory (one big String pin during the one-shot legacy read — unavoidable for this call; never repeated), write the new file, `context.deleteSharedPreferences("hydrated_home_overlay_v1")` to delete the XML. After this single run, no future write touches SharedPreferences.

**Concurrency:** all reads/writes synchronized through a single dispatcher-bound `Channel<UpsertOp>` + dedicated `writeLoop` coroutine. The existing `MutableStateFlow<Set<String>>` for `staleItemKeys` stays untouched. Readers (`readByCanonicalIdentity`, `readOverlayForItemKey`) read directly from the in-memory map under a `synchronized` block; the write loop mutates the same map inside the same lock. No reader allocates.

**API surface unchanged.** `upsert`, `readByCanonicalIdentity`, `markStaleIfWeakerIds`, `markStaleAll`, `staleItemKeys`, `extractItemKeyFromAliasPrefsKey`, `readOverlayForItemKey` — same signatures. Plan B Task 7+8 invocation sites do not change. Existing `HydratedHomeOverlayStoreTest` unit tests pass against the new implementation unchanged (modulo the test fake substituting for the file I/O).

### 2. updateCatalogRowsPipeline — rule #6 outer-fun pin removal

**Current shape** (`HomeViewModelCatalogPipeline.kt:2556–2586`):
```kotlin
internal suspend fun HomeViewModel.updateCatalogRowsPipeline(profileSessionForSurface: ActiveProfileSession) {
    catalogRowsComputationMutex.withLock {
        ...
        val traktSnapshot   = if (...) traktDiscoverySnapshot else TraktDiscoverySnapshot()
        val simklSnapshot   = simklDiscoverySnapshot
        val mdbListSnapshot = mdbListDiscoverySnapshot
        val tmdbSnapshot    = tmdbDiscoverySnapshot
        ...
        val currentHydratedHomeOverlays = hydratedHomeOverlaysByItemKey.value
        ...
        // ~1000 lines below with many suspension points
    }
}
```

**Fix:** delete all five outer-fun local declarations. Replace each downstream use with an inline read at the suspension-adjacent block scope:
```kotlin
// At the use-site (deep below function head)
val rowsTraktSnapshot = traktDiscoverySnapshot.takeIf { activeProfileTraktAuthenticated }
    ?: TraktDiscoverySnapshot()
val rowsOverlays = hydratedHomeOverlaysByItemKey.value
val rows = buildRowsFor(rowsTraktSnapshot, rowsOverlays, ...)
// locals go out of scope at block end → next suspension's continuation no longer captures them
```

Wrapping the read+use in the *innermost* block (or an explicit `run { ... }`) ensures the Kotlin coroutine state machine's liveness analysis marks the local dead before the next suspension. The current bug is that all five values live at function scope.

**StateFlow.value freshness:** refetching inside the branch sees the latest state, not the function-entry state. This is strictly better for hydration — the function already coalesces emissions via `catalogRowsComputationMutex` and the content-equality gate at `applyNonDowngradeMerge` is the actual correctness boundary. No race introduced.

**Idempotency:** the four discovery snapshots are content-stable by design (only flip when their respective refresh completes; refresh runs serially through `runSerializedPostStartupRefreshPipeline`). Two reads within the same `updateCatalogRowsPipeline` invocation produce identical values 99% of the time; on the rare diverging case the downstream merge handles it cleanly.

---

## P1 details

Each commit clones the shared file-streaming recipe and re-applies it to one store. Per-store file layout and write coalescing decisions:

| Store | File path | Schema shape | Coalesce? |
|---|---|---|---|
| `MediaClipStore` | `media-clip-store-v1/p<profileId>.json` | `{schemaVersion: 1, records: {<key>: MediaClipRecord}}` | yes (per-clip writes burst together) |
| `CatalogDiskCacheStore` | `catalog-disk-cache-v1/<sha256(cacheKey)>.json` (per-key) | one CatalogPayload per file | no (point updates) |
| `TraktDiscoverySnapshotStore` | `trakt-discovery-snapshot-v1/p<profileId>.json` | single TraktDiscoverySnapshot | no (sparse writes, one per refresh) |
| `SimklDiscoverySnapshotStore` | `simkl-discovery-snapshot-v1/p<profileId>.json` + `simkl-external-id-cache-v1/p<profileId>.json` | two files (snapshot + id cache) | no |
| `TvdbIdentityCacheStore` | `tvdb-identity-cache-v1/<idTypeAndValue>.json` (per-key) | TvdbIdentityRecord per file | no |
| `AddonRepositoryImpl` (manifest) | `addon-manifest-cache-v1/manifests.json` | `{schemaVersion: 1, manifests: {<addonId>: Manifest}}` | no (sparse, install/uninstall) |

**File layout rule of thumb:**
- **Single file per profile** when writes touch ~all entries simultaneously (overlay store, discovery snapshot, manifest cache) — atomic rename is the natural unit.
- **Per-key files** when writes are point updates against unrelated entries (catalog cache, identity cache) — avoids whole-store rewrite per single-entry update.

**Migration per store:** same boot-once pattern as P0. Read legacy XML on first instantiation if new file is absent, write new format, `context.deleteSharedPreferences(LEGACY_NAME)`.

---

## P2 details

### 9. PreferredArtworkProvidersMemo (rule #5)

Singleton interner keyed on the 4-tuple of `ArtworkProviderId.key` strings:
```kotlin
@Singleton
class PreferredArtworkProvidersMemo @Inject constructor() {
    private val cache = ConcurrentHashMap<PreferredKey, Map<ArtworkType, ArtworkProviderId>>()

    private data class PreferredKey(
        val poster: String, val backdrop: String, val logo: String, val thumbnail: String
    )

    fun intern(
        poster: ArtworkProviderId, backdrop: ArtworkProviderId,
        logo: ArtworkProviderId, thumbnail: ArtworkProviderId
    ): Map<ArtworkType, ArtworkProviderId> =
        cache.getOrPut(PreferredKey(poster.key, backdrop.key, logo.key, thumbnail.key)) {
            mapOf(
                ArtworkType.POSTER to poster, ArtworkType.BACKDROP to backdrop,
                ArtworkType.LOGO to logo, ArtworkType.THUMBNAIL to thumbnail
            )
        }
}
```

Wiring: inject into `HomeResolvedDisplayMapper.toResolvedDisplayItems(Enriched)` as a defaulted parameter (mirrors the resolver/settings injection pattern). Replace the inline `mapOf(...)` at line 248–261 with `memo.intern(...)`.

**Cache bounding:** the key space is small (4 providers × ArtworkProviderChoiceKey states × anime/non-anime × content-type splits). Real-world bound is <100 entries; no active-set pruning needed.

**Expected effect:** 526 `LinkedHashMap` instances → ~5–10. Compose stability skip benefits: `===` cache-hit guards on `state.copy(preferredArtworkProviders = ref)` start holding.

### 10. LegacyPrefsCleanupPass

Add a one-shot cleanup at the end of each migrated store's success path:
```kotlin
if (migrationSucceeded &&
    context.getSharedPreferences(LEGACY_PREFS_NAME, MODE_PRIVATE).all.isEmpty()
) {
    context.deleteSharedPreferences(LEGACY_PREFS_NAME)
}
```

Apply to `MetadataDiskCacheStore` (already-migrated, 217 KiB legacy XML to delete) and to every P0/P1 migrated store as their migrations land.

---

## Testing strategy

### Unit tests (per store)
- New `Fake*Store` in-memory implementations + existing consumer unit tests pass unchanged.
- Pure-Kotlin, no Robolectric.

### Migration round-trip (per store)
- Robolectric test seeds legacy SharedPreferences XML with synthetic records, instantiates the new store, asserts:
  - Every record loaded into the in-memory cache
  - New file written with content-equivalent JSON (modulo whitespace)
  - Legacy prefs cleared via `deleteSharedPreferences`
- Run first for `HydratedHomeOverlayStore` (most complex: overlays + aliases + provenance), then clone for the 5 P1 stores.

### Concurrency (high-write stores only)
- `HydratedHomeOverlayStore` and `MediaClipStore`: `runBlocking` test fires 100 concurrent `upsert` calls, asserts:
  - Final file contents match expected coalesced state
  - No corruption
  - Write count ≤ ⌈100 / debounce_window⌉ via injected fake clock

### Rule #6 fix verification
- Unit test instruments `updateCatalogRowsPipeline` body with a spy on the StateFlow reads, asserts each branch re-reads `hydratedHomeOverlaysByItemKey.value` independently rather than seeing a single function-entry snapshot.
- Plus on-device heap-shape verification (see "On-device evidence" below).

### Rule #5 memo
- Unit test: `intern` returns reference-equal map for content-equal inputs across 1000 calls.
- Integration test: two `toResolvedDisplayItems` calls with identical inputs produce `ResolvedDisplayItem` instances whose `preferredArtworkProviders` are `===` equal.

### Legacy cleanup
- Unit test: cleanup runs only after a successful migration *and* the legacy prefs is empty.

---

## On-device evidence (per commit)

Every commit ships with **before/after** heap dumps captured via the rule #8 smoke sequence:
```bash
adb shell am force-stop com.nexiodebug.tv
adb logcat -c
adb shell monkey -p com.nexiodebug.tv 1
sleep 12 && adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 30
adb shell am dumpheap <pid> /sdcard/before.hprof  # repeat after fix
```

Then validation:

| Fix | Heap evidence | On-disk evidence | Functional evidence |
|---|---|---|---|
| 1. HydratedHomeOverlayStore | `heaptrail --diff-from before --diff-to after --diff-by bytes` — gson `LinkedTreeMap$Node` drops by ~10k+ (current dump: 21,688); `SharedPreferencesImpl.mMap` shrinks | `shared_prefs/hydrated_home_overlay_v1.xml` → 0 bytes / deleted; `files/hydrated-home-overlay-v1/p<id>.json` present, ≤ 600 KiB | Modern Home renders unchanged; RPDB posters still present (no v0.56 popping-fix regression); cold-start time unchanged or faster |
| 2. rule #6 fix | `heaptrail --find-referrers HomeViewModelCatalogPipelineKt\$updateCatalogRowsPipeline\$1 --hops 2` — captured-locals chain no longer contains `LinkedHashMap` or `*DiscoverySnapshot` instances | n/a | `home.hydration_applied` latency unchanged or improved |
| 3–8. P1 stores | each store's class allocations drop in `--diff-by bytes`; no new top-N retainer introduced | each legacy XML → deleted; new file present, bounded size; `shared_prefs/` total directory size shrinks | feature exercised end-to-end (clip play / addon install / discovery refresh) still works |
| 9. memo | `LinkedHashMap` count drops by ~500 (526 → ~10); `--find-referrers com.nexio.tv.domain.model.ResolvedDisplayItem --hops 1` direct-referrers count for `preferredArtworkProviders` drops to single digits | n/a | mapper test asserts `===` equality across same-content emissions |
| 10. cleanup | n/a | `ls -la shared_prefs/` shows none of the migrated legacy XML files | no functional impact |

**Aggregate at end of P0:** total `shared_prefs/` size drops from ~1.1 MiB to ≤ 700 KiB; heap raw shallow ≤ 30 MiB (from current 31.77 MiB).

**Aggregate at end of P1:** `grep -rn "putString.*gson\.toJson\|gson\.toJson.*putString" app/src/main/java` returns zero hits; `shared_prefs/` ≤ 100 KiB.

**Aggregate at end of P2:** 526-instance LinkedHashMap retention gone; all legacy XMLs deleted.

---

## Sequencing

| Step | Workstream | Commit summary | Depends on |
|---|---|---|---|
| 1 | P0 | HydratedHomeOverlayStore migration | — |
| 2 | P0 | updateCatalogRowsPipeline rule #6 outer-fun locals removal | — (independent; paired with step 1 because both touch overlay subsystem) |
| 3 | P1 | MediaClipStore migration | — |
| 4 | P1 | CatalogDiskCacheStore migration | — |
| 5 | P1 | TraktDiscoverySnapshotStore migration | — |
| 6 | P1 | SimklDiscoverySnapshotStore migration | — |
| 7 | P1 | TvdbIdentityCacheStore migration | — |
| 8 | P1 | AddonRepositoryImpl manifest cache migration | — |
| 9 | P2 | PreferredArtworkProvidersMemo singleton + mapper wiring | — |
| 10 | P2 | LegacyPrefsCleanupPass + per-store invocations | steps 1, 3–8 (no-op on stores that haven't migrated) |

P0 ships in the current session. P1 + P2 are queued for follow-up sessions.

---

## Out of scope

- **DataStore audit:** CLAUDE.md rule #3 also bans DataStore preferences > 50 KiB. The current heap shows 38 `MutablePreferences.preferencesMap` instances; their individual sizes were not measured. Queued as a separate sweep.
- **Read-side `gson.fromJson(rawString, type)` ban:** the rule #3 read-side clause (separate from the write-side ban targeted here) was not audited. Queued.
- **DurableArtworkDecisionCache transient StringBuilder peak:** the prior audit's 87 KiB claim was not reproducible in this heap. Re-audit when it resurfaces.
- **Rule #4 / `Iterable.forEach { suspend }` sweep:** current heap shows 3,044 `ArrayList$Itr` instances (healthy steady-state). No new violations detected.

---

## Risk

| Risk | Mitigation |
|---|---|
| Migration loses overlay data on first cold-start after upgrade | Boot-once migration writes the new file *before* deleting the legacy XML. If write fails, the legacy XML stays, store falls back to legacy read on next boot. |
| Debounced writes lose data on process kill | Atomic rename + write window kept short (250 ms). Hydration is content-equality gated downstream; a lost write means the next emission re-runs hydration and rewrites the overlay. No persistent data loss. |
| Refetching `StateFlow.value` inside branches sees a different snapshot mid-pipeline | The downstream `applyNonDowngradeMerge` is the correctness boundary; mid-pipeline divergence is already tolerated. No new race. |
| Memo cache grows unbounded under settings churn | Key space is small (< 100 entries in practice); no eviction needed. If a future feature explodes the key space, add an active-set retain like other memos. |
| `context.deleteSharedPreferences` is API 24+ | Project minSdk is well above 24; safe. |
