# Trace 09 — Continue Watching Render

**Review SHA:** `774a540f8`
**Date:** 2026-04-29
**Dossier series:** review-dossier-2
**Lane cross-references:** F-G-01, F-G-02, G-02

---

## 1. Path overview

When `HomeActivity` creates `HomeViewModel`, the CW pipeline is initiated by `loadContinueWatchingPipeline()` (defined in `HomeViewModelContinueWatching.kt`). The pipeline subscribes to `ContinueWatchingSnapshotService.observeProfileSnapshot(profileId)`, which acts as the sole reactive source for the three CW rails rendered on Home: the resume rail (in-progress items), the nextUp rail (next-episode items), and the traktUpNext rail (Trakt-synthetic up-next items). In parallel, `AndroidTvFeedCatalogService` uses the same typed API for the Android TV launcher channel feed. `AndroidTvChannelPublisher` observes the unscoped `observeSnapshot()` for a trigger-only signal before delegating to `AndroidTvFeedCatalogService` for the actual data fetch.

---

## 2. Trace steps

### Step 1 — ViewModel initialization

`HomeViewModel.kt:349` materializes `activeHomeProfileSession` from `profileManager.activeProfileId.value` at construction time. This session object carries `profileId`, `sessionId`, and `sessionOrdinal`. The baked-in `profileId` is the value at the time the ViewModel is created; `MainActivity.recreate()` on profile switch ensures a fresh ViewModel (and thus a fresh `profileId`) for each profile session.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt:349`

### Step 2 — Pipeline launch

`loadContinueWatchingPipeline()` (`HomeViewModelContinueWatching.kt:71`) launches a coroutine in `viewModelScope` and calls:

```
continueWatchingSnapshotService.observeProfileSnapshot(activeHomeProfileSession.profileId)
    .collectLatest { snapshot -> ... }
```

The call site records the lane G-01 closure: a comment at line 73–75 explicitly annotates this as "F-G-01 path B" and notes that filtering is applied upstream inside `observeProfileSnapshot`, so no manual `.filter` or unwrapping is needed at the collector.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:71-77`

### Step 3 — `observeProfileSnapshot` implementation

`ContinueWatchingSnapshotService.observeProfileSnapshot(profileId: Int)` (`ContinueWatchingSnapshotService.kt:365-370`):

```kotlin
fun observeProfileSnapshot(profileId: Int): Flow<ContinueWatchingSnapshot> {
    require(profileId > 0) { "observeProfileSnapshot.profileId must be positive, got $profileId" }
    return observeSnapshot()
        .filter { it.profileId == profileId }
        .map { it.snapshot }
}
```

This method:
- Rejects `profileId <= 0` with an `IllegalArgumentException` at the API boundary.
- Chains on the unscoped `observeSnapshot()` internally (the single source of truth for all in-process CW state).
- Applies `.filter { it.profileId == profileId }` — emissions whose `ProfileOwnedContinueWatchingSnapshot.profileId` does not match are dropped before the downstream `map`.
- Unwraps to the `ContinueWatchingSnapshot` (profile-stripped, ready for consumption).

**File:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:359-370`

### Step 4 — Snapshot emission scoping

`observeSnapshot()` (`ContinueWatchingSnapshotService.kt:341-357`) emits from `snapshotState: MutableStateFlow<ProfileOwnedContinueWatchingSnapshot>`. Every write to `snapshotState` (including live-pipeline emissions and profile-switch clearing) stamps the `profileId` of the active profile at write time. On profile switch, `profileSwitched.collectLatest` (`:192-196`) calls `markProfileAwaitingLiveReset`, resets `persistedSnapshotReady = false`, and calls `loadPersistedSnapshotForActiveProfile(clearWhenMissing = true)`, which writes a fresh empty `ProfileOwnedContinueWatchingSnapshot(profileId = <new profile>)`. Consequently, downstream consumers of `observeProfileSnapshot(<old profileId>)` will not receive the new profile's emissions because the `.filter { it.profileId == profileId }` predicate does not match.

The `canPublishLiveSnapshot` gate (`:1099-1113`) provides an additional layer: live remote-snapshot emissions for a profile are suppressed until the profile has observed a remote-reset cycle, preventing stale data from the old profile from being published under the new profile's ID.

**File:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:341-357`, `180-299`

### Step 5 — `continue_watching.snapshot_read` trace event

The `onStart` operator on `observeSnapshot()` (`:344-356`) calls `emitRead(profileId, recordCount)` synchronously upon each new subscription. The `emitRead` companion (`ContinueWatchingSnapshotService.kt:1380-1400`) builds a `TraceEventEnvelope` with:

- `eventType = "continue_watching.snapshot_read"`
- `payload["profileId"]` — the raw integer profile ID of the current in-memory snapshot
- `payload["profileHash"]` — a session-keyed hash (`TraceHash.of(sid, profileId.toString())`)
- `payload["recordCount"]` — computed as `snapshot.resumeItems.size + snapshot.nextUpItems.size + snapshot.traktUpNextItems.size` (all three rails; F-G-03 closed)
- `payload["source"] = "OBSERVE_SUBSCRIBE"`

The event fires once per subscription at the moment of `collect`, not per emission. This means the `recordCount` reflects the in-memory state at subscription time, which may not match the count of items ultimately delivered if `persistedSnapshotReady` has not yet transitioned to `true` (the timing-drift issue documented as G-01 in lane G).

**File:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:1380-1401`

### Step 6 — Snapshot consumption and rail construction

Inside the `collectLatest` lambda (`HomeViewModelContinueWatching.kt:77-158`), the CW pipeline:

1. Captures `homeProfileGeneration` for stale-emission detection.
2. Calls `buildMixedContinueWatchingTimeline(resumeItems, nextUpItems, ...)` to produce an ordered interleaved `List<ContinueWatchingTimelineRow>`.
3. Maps each row to `ContinueWatchingItem.InProgress` (resume) or `ContinueWatchingItem.NextUp` (next-episode).
4. Filters out `NextUp` items that have not yet aired (`!item.info.hasAired`).
5. Maps `snapshot.traktUpNextItems` to `ContinueWatchingItem.NextUp` separately (traktUpNext rail).
6. Checks `isCurrentHomeProfileGeneration(capturedGeneration)` before publishing to `_uiState` — discards the emission if the ViewModel has since rotated to a new profile generation.
7. Updates `_uiState` with `continueWatchingItems`, `traktUpNextItems`, and `initialContinueWatchingResolved = true`.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:77-158`

### Step 7 — AndroidTvFeedCatalogService (two sites)

`AndroidTvFeedCatalogService.resolveSelectedRows` (`AndroidTvFeedCatalogService.kt:123-203`) and `AndroidTvFeedCatalogService.resolveFeed` (`:205-278`) both read the active profile at call time:

```kotlin
val activeProfileId = profileManager.activeProfileId.value
val continueWatchingSnapshot = continueWatchingSnapshotService
    .observeProfileSnapshot(activeProfileId)
    .first()
```

Site 1: `resolveSelectedRows` at line 153-156.
Site 2: `resolveFeed` at line 231-234.

Both take `.first()` (one-shot read, not a long-running subscription), which still passes through the `.filter { it.profileId == activeProfileId }` predicate inside `observeProfileSnapshot`. Using `profileManager.activeProfileId.value` (a `StateFlow` current value) means the profileId is resolved at the moment `resolveSelectedRows`/`resolveFeed` is invoked. This is appropriate for a synchronous-style one-shot call but does not guard against a profile switch occurring between the time `activeProfileId` is captured and the time `buildContinueWatchingItems(continueWatchingSnapshot)` is called. This is a latent TOCTOU concern but is narrow in practice (the call is short-lived).

**File:** `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogService.kt:153-156`, `231-234`

### Step 8 — AndroidTvChannelPublisher unscoped trigger (G-02 finding)

`AndroidTvChannelPublisher.kt:67-82` uses the unscoped `observeSnapshot()` as a change-detection trigger:

```kotlin
combine(
    dataStore.preferences,
    continueWatchingSnapshotService.observeSnapshot()
) { prefs, snapshot ->
    prefs.enabled &&
        AndroidTvFeedCatalogService.CONTINUE_WATCHING_FEED_KEY in prefs.selectedFeedKeys &&
        snapshot.snapshot.updatedAtMs > 0L
}
    .distinctUntilChanged()
    .collect { shouldSync ->
        if (shouldSync) { requestSync("continue_watching_changed") }
    }
```

The `snapshot` object in this lambda is a `ProfileOwnedContinueWatchingSnapshot`. Only `snapshot.snapshot.updatedAtMs` is read — none of the content fields (`resumeItems`, `nextUpItems`, `displayMetadataByItemKey`) are accessed here. The actual CW data for Android TV programs is fetched inside `syncNow()` → `feedCatalogService.resolveSelectedRows(prefs.selectedFeedKeys)` → `observeProfileSnapshot(activeProfileId)` (the already-migrated, profile-scoped path).

This means no profile content leaks through the `AndroidTvChannelPublisher` trigger at SHA `774a540f8`. However, lane G-02 documents the latent risk: any future addition of content-reading to the `combine` lambda will operate without a profile-ID guard, because `observeSnapshot()` emits `ProfileOwnedContinueWatchingSnapshot` for whatever the current active profile is — it will always emit the active profile's data regardless of which profile the publisher was initialized for. The publisher has its own `CoroutineScope(SupervisorJob() + Dispatchers.IO)` and no profile-switch cancel signal, so it would continue reacting to snapshot changes even after a profile switch until the singleton scope terminates.

**File:** `app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelPublisher.kt:67-82`

---

## 3. Findings

### T09-F-01: F-G-01 path B — `HomeViewModelContinueWatching` subscription confirmed

- **Status:** CLOSED (F-G-01 path B)
- **Evidence:** `HomeViewModelContinueWatching.kt:76` calls `continueWatchingSnapshotService.observeProfileSnapshot(activeHomeProfileSession.profileId)`. The comment at lines 73-75 documents the F-G-01 path B rationale inline. No manual `.filter { it.profileId == ... }` or `return@collectLatest` pattern exists in the collector; the filtering is entirely inside `observeProfileSnapshot`. The secondary stale-generation check (`isCurrentHomeProfileGeneration(capturedGeneration)`) at line 98-101 provides defense-in-depth against emissions that arrive during a ViewModel re-initialization window.

### T09-F-02: F-G-01 path B — `AndroidTvFeedCatalogService` two-site migration confirmed

- **Status:** CLOSED (F-G-01 path B)
- **Evidence:** Both `resolveSelectedRows` (line 153-156) and `resolveFeed` (line 231-234) in `AndroidTvFeedCatalogService.kt` call `continueWatchingSnapshotService.observeProfileSnapshot(activeProfileId).first()`. The `activeProfileId` is captured from `profileManager.activeProfileId.value` at call time. No unscoped `observeSnapshot()` call is used for CW data reads in this class.

### T09-F-03: Snapshot emission only fires for the active profile — no foreign profile leak

- **Status:** CONFIRMED
- **Evidence:** `observeProfileSnapshot(profileId)` applies `.filter { it.profileId == profileId }` before `.map { it.snapshot }` (`:368-369`). All writes to `snapshotState` are stamped with the active profile's ID at write time (`:282, 295, 316-317, 331`). The profile-switch path explicitly writes `ProfileOwnedContinueWatchingSnapshot(profileId = newProfileId)` with a blank snapshot, causing the prior profile's `observeProfileSnapshot(oldProfileId)` subscription to receive no further emissions. The `canPublishLiveSnapshot` gate (`:1099-1113`) provides an additional guard against live data from a prior profile being re-emitted under the new profile ID. No cross-profile emission path was identified.

### T09-F-04: `continue_watching.snapshot_read` trace event — emission confirmed, test coverage partial

- **Status:** PARTIALLY CLOSED (G-01 open)
- **Evidence:** `emitRead` at `ContinueWatchingSnapshotService.kt:1380-1401` emits `eventType = "continue_watching.snapshot_read"` with `profileId`, `profileHash`, `recordCount` (all three rails), and `source = "OBSERVE_SUBSCRIBE"` on every `observeSnapshot()` subscription. The `ContinueWatchingSnapshotReadTraceTest` (added for F-G-02 closure) asserts `profileId` and `recordCount` are present and non-negative but does not assert `profileHash` or verify `source == "OBSERVE_SUBSCRIBE"`. The timing-drift concern (emitted before `persistedSnapshotReady = true`, so `recordCount` at emit time may not match the count of items eventually delivered) is undocumented in the emitter's KDoc. Per lane finding G-01, this is a P3 open item.

### T09-F-05: `AndroidTvChannelPublisher` uses unscoped `observeSnapshot()` as trigger — G-02 confirmed

- **Status:** CONFIRMED OPEN (G-02)
- **Evidence:** `AndroidTvChannelPublisher.kt:70` calls `continueWatchingSnapshotService.observeSnapshot()` inside a `combine` block. Only `snapshot.snapshot.updatedAtMs` is read; no content fields are accessed. The CW data itself is fetched via `feedCatalogService.resolveSelectedRows(...)` → `observeProfileSnapshot(activeProfileId)` in `syncNow()`. The trigger-only use does not produce a profile data leak at SHA `774a540f8`. However, the publisher's `CoroutineScope` (a `SupervisorJob` + `Dispatchers.IO` scope independent of any profile lifecycle) has no cancel mechanism on profile switch, creating a latent risk that future content-reading additions to the `combine` lambda would bypass the profile boundary. Lane G-02 recommends migrating the trigger to `observeProfileSnapshot(profileManager.activeProfileId.value)` or a `flatMap`-over-`activeProfileId` pattern.

---

## 4. Summary table

| Check | Status | Evidence location |
|---|---|---|
| `HomeViewModelContinueWatching` uses `observeProfileSnapshot(profileId)` | PASS | `HomeViewModelContinueWatching.kt:76` |
| `AndroidTvFeedCatalogService.resolveSelectedRows` uses `observeProfileSnapshot` | PASS | `AndroidTvFeedCatalogService.kt:153-156` |
| `AndroidTvFeedCatalogService.resolveFeed` uses `observeProfileSnapshot` | PASS | `AndroidTvFeedCatalogService.kt:231-234` |
| Foreign profile snapshots filtered before consumer receives emission | PASS | `ContinueWatchingSnapshotService.kt:368` |
| `continue_watching.snapshot_read` emitted on subscription | PASS | `ContinueWatchingSnapshotService.kt:1391` |
| `snapshot_read` payload includes all four required fields | PASS (emit) / PARTIAL (test) | `ContinueWatchingSnapshotService.kt:1392-1397`; G-01 open |
| `AndroidTvChannelPublisher` uses unscoped `observeSnapshot()` | CONFIRMED OPEN | `AndroidTvChannelPublisher.kt:70`; G-02 |
| No content fields read from unscoped `observeSnapshot()` in publisher | PASS (current) | `AndroidTvChannelPublisher.kt:67-82` |

---

## 5. Lane cross-references

| Lane ID | Title | Status at this SHA |
|---|---|---|
| F-G-01 (path B) | `observeProfileSnapshot` typed API; migration of `HomeVM` and `AndroidTvFeedCatalogService` | CLOSED |
| F-G-02 | `continue_watching.snapshot_read` trace event test coverage | PARTIALLY CLOSED — test exists; `profileHash` and `source` assertions absent (G-01) |
| F-G-03 | `recordCount` includes `traktUpNextItems.size` at all three emission sites | CLOSED |
| G-01 | `snapshot_read` test does not assert `profileHash` or `source`; timing-drift undocumented | OPEN (P3) |
| G-02 | `AndroidTvChannelPublisher` trigger-only use of unscoped `observeSnapshot()` | OPEN (P3) |
