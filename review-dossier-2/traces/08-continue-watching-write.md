# Trace 08 — Continue Watching Write

**Review SHA:** `774a540f8`
**Date:** 2026-04-29
**Dossier series:** review-dossier-2
**Lane cross-references:** F-G-03, G-01, G-03, H-01, H-02, F-H-03

---

## 1. Path overview

When playback progress updates, the chain from `PlayerRuntimeController` into `WatchProgressRepositoryImpl.saveProgress` triggers two parallel writes: (a) a profile-local `SharedPreferences` write via `WatchProgressPreferences`, which feeds the reactive `allProgress` flow observed by `ContinueWatchingSnapshotService`; and (b) an optimistic in-memory state update via `TrackingProgressService.applyOptimisticProgress`. `ContinueWatchingSnapshotService` reacts to both sources and, once the session is verified via `ProfileBoundaryEnforcer.assertCanWriteProfileState`, persists the hydrated snapshot to `ContinueWatchingSnapshotStore` (SharedPreferences, not Room) and emits the `continue_watching.snapshot_write` trace event. Trakt up-next sync is handled separately through `TrackingProgressService.observeSyntheticContinueWatchingNextUp()` as a third input to the `buildRawSnapshot` combine. The integration-ownership rail is updated inside `syncContinueWatchingRail` before the snapshot is written to disk.

---

## 2. Trace steps

### Step 1 — PlayerRuntimeController periodic progress save

`PlayerRuntimeControllerPlaybackEvents.kt:197-207` defines `saveWatchProgressIfNeeded()`, which fires when the position delta exceeds `saveThresholdMs`:

```kotlin
internal fun PlayerRuntimeController.saveWatchProgressIfNeeded() {
    if (!hasRenderedFirstFrame) return
    val currentPosition = backendCurrentPosition().takeIf { it > 0L } ?: return
    val duration = getEffectiveDuration(currentPosition)
    if (kotlin.math.abs(currentPosition - lastSavedPosition) >= saveThresholdMs) {
        lastSavedPosition = currentPosition
        saveWatchProgressInternal(currentPosition, duration, syncRemote = false)
    }
}
```

The function `shouldPersistWatchProgressOnPlaybackInterval()` returns `false` at line 193 — interval-based saving is disabled; the write path is triggered by position delta only. `saveWatchProgress()` (line 209) is the stop-event path; it calls `saveWatchProgressInternal` with `syncRemote = true`.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt:193-213`

---

### Step 2 — `saveWatchProgressInternal` → `WatchProgressRepository.saveProgress`

`saveWatchProgressInternal` (line 242) constructs a `WatchProgress` value object from current player state and launches a coroutine that calls `watchProgressRepository.saveProgress(progress, syncRemote = syncRemote)`.

```kotlin
internal fun PlayerRuntimeController.saveWatchProgressInternal(position: Long, duration: Long, syncRemote: Boolean = true) {
    if (contentId.isNullOrEmpty() || contentType.isNullOrEmpty()) return
    if (position < 1000) return
    // ... build WatchProgress ...
    scope.launch {
        watchProgressRepository.saveProgress(progress, syncRemote = syncRemote)
    }
}
```

Note: `TrackingProgressService` is **not** directly injected into `PlayerRuntimeController`. The controller uses `WatchProgressRepository`, which is the boundary that calls into `TrackingProgressService`. There is no direct `PlayerViewModel → TrackingProgressService` progress tick; the path is `PlayerRuntimeController → WatchProgressRepository → TrackingProgressService`.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt:242-269`

---

### Step 3 — `WatchProgressRepositoryImpl.saveProgress`

`WatchProgressRepositoryImpl.saveProgress` (line 299) is the implementation:

```kotlin
override suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean) {
    if (!trackingProviderStateService.currentState().hasAuthenticatedProvider) {
        return
    }
    trackingProgressService.applyOptimisticProgress(progress)
    watchProgressPreferences.saveProgress(progress)
}
```

Two operations occur in sequence:
1. `trackingProgressService.applyOptimisticProgress(progress)` — pushes the progress into the in-memory optimistic overlay of the active tracking provider (Trakt or Simkl). This triggers a reactive `allProgress` emission for any downstream `Flow` observers.
2. `watchProgressPreferences.saveProgress(progress)` — writes the progress to the profile-scoped DataStore (`ProfileDataStoreFactory`) under the key `"watch_progress_preferences"`. This is the durable write; the DataStore emission also triggers downstream observers.

**Note on "caller" claim:** The PlayerViewModel → TrackingProgressService direct progress tick described in the task statement does not match the code. The actual call chain is `PlayerRuntimeController.saveWatchProgressInternal` → `WatchProgressRepository.saveProgress` → `TrackingProgressService.applyOptimisticProgress`. There is no direct call from `PlayerViewModel` to `TrackingProgressService`; `PlayerViewModel` uses `WatchProgressRepository` only.

**File:** `app/src/main/java/com/nexio/tv/data/repository/WatchProgressRepositoryImpl.kt:299-305`

---

### Step 4 — `WatchProgressPreferences.saveProgress` (DataStore write)

`WatchProgressPreferences.saveProgress` (line 137) writes the `WatchProgress` entry to the profile-scoped DataStore:

```kotlin
suspend fun saveProgress(progress: WatchProgress) {
    store().edit { preferences ->
        val json = preferences[watchProgressKey] ?: "{}"
        val map = parseProgressMap(json).toMutableMap()
        val key = createKey(progress)
        map[key] = progress
        // ... series-level key update for episodes ...
        val pruned = pruneOldItems(map)
        preferences[watchProgressKey] = gson.toJson(pruned)
    }
}
```

`store()` resolves to `factory.get(profileId, "watch_progress_preferences")` using the **current active profileId at write time**, not the profileId captured at write-issue time. There is no explicit `resultSession` parameter passed through to this call.

**Storage:** DataStore (`ProfileDataStoreFactory`), not Room. No `@Transaction` annotation; DataStore's `edit` block is internally serialized/atomic per key.

**File:** `app/src/main/java/com/nexio/tv/data/local/WatchProgressPreferences.kt:137-157`

---

### Step 5 — Reactive CW pipeline — `allProgress` emission into `ContinueWatchingSnapshotService`

`ContinueWatchingSnapshotService` observes `watchProgressRepository.allProgress` via a `combine` in its `init` block (lines 253-278):

```kotlin
combine(
    trackingProgressService.observeRemoteSnapshotLoaded(),
    watchProgressRepository.allProgress,
    trackingProgressService.observeContinueWatchingNextUp(),
    trackingProgressService.observeSyntheticContinueWatchingNextUp()
) { hasLoadedRemoteSnapshot, allProgress, nextUpEntries, traktUpNextEntries ->
    // ...
    LiveContinueWatchingSnapshotEmission(
        profileId = profileId,
        hasLoadedRemoteSnapshot = true,
        snapshot = buildRawSnapshot(allProgress, nextUpEntries, traktUpNextEntries)
    )
}
.collectLatest { emission ->
    // ...
    updateSnapshot(
        snapshot = snapshot,
        profileId = emission.profileId,
        resultSession = activeProfileSession()  // ← captured here, at collect time
    )
}
```

The `resultSession` is captured by calling `activeProfileSession()` **at the time the `collectLatest` lambda executes**, not at the time the progress write was initiated. This is the write-resolve time, not the write-issue time.

**File:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:250-298`

---

### Step 6 — Profile context capture timing (F-H-03 scope)

`activeProfileSession()` (line 1041):

```kotlin
private fun activeProfileSession(): ActiveProfileSession =
    runCatching { profileManager?.activeProfileSession?.value }.getOrNull()
        ?: ActiveProfileSession(
            profileId = activeProfileId(),
            sessionId = "legacy-profile:${activeProfileId()}",
            sessionOrdinal = 1L,
            startedAtMs = 1L
        )
```

This reads `profileManager.activeProfileSession.value` at the moment of invocation, which is the **resolve time** of the reactive emission, not the time the player originally triggered the progress save. If a profile switch occurs between Step 1 (progress save trigger) and Step 6 (CW snapshot collection), the `resultSession` will reflect the new profile session — allowing `canPublishProfileWrite` (Step 7) to reject it.

**Lane H finding H-02 context:** The F-H-03 claim was that `assertCanWriteProfileState` acts as a result-time re-check on the scrobble path. That claim is only satisfied for the CW write path (Step 7). The scrobble path (`TraktScrobbleService.enqueueScrobble`, `SimklScrobbleService`) calls `checkScrobbleBoundary`, which emits a telemetry event but does **not** throw — it does not halt the write. CW write is correctly protected; scrobble write is not.

---

### Step 7 — `ProfileBoundaryEnforcer.assertCanWriteProfileState` (the blocking gate)

`canPublishProfileWrite(resultSession)` (line 1064):

```kotlin
private fun canPublishProfileWrite(resultSession: ActiveProfileSession): Boolean {
    return try {
        ProfileBoundaryEnforcer.assertCanWriteProfileState(
            resultSession = resultSession,
            activeSession = activeProfileSession()
        )
        true
    } catch (exception: ProfileBoundaryException) {
        Log.d("ContinueWatching", "Skipping stale continue watching publish: ${exception.message}")
        false
    }
}
```

`ProfileBoundaryEnforcer.assertCanWriteProfileState` (line 203, delegating to line 189):

```kotlin
fun assertCanWriteProfileState(
    resultProfileId: Int,
    resultSessionId: String,
    activeProfileId: Int,
    activeSessionId: String
) {
    if (resultProfileId != activeProfileId || resultSessionId != activeSessionId) {
        throw ProfileBoundaryException(
            ProfileBoundaryViolation.STALE_SESSION_WRITE_REJECTED,
            "Rejecting stale profile write for profile=$resultProfileId session=$resultSessionId ..."
        )
    }
}
```

**Verdict on H-01 ("STALE_SESSION_WRITE_REJECTED actually rejects"):** For the **CW write path**, the rejection is real and blocking — `assertCanWriteProfileState` throws `ProfileBoundaryException`, caught by `canPublishProfileWrite` which returns `false`, preventing `syncContinueWatchingRail` and `snapshotStore.write` from executing. Lane H finding H-01 specifically applies to the **scrobble path** (`TraktScrobbleService.checkScrobbleBoundary`), not to the CW write path. For CW writes, `STALE_SESSION_WRITE_REJECTED` is a true rejection, not just telemetry.

**File:** `app/src/main/java/com/nexio/tv/core/integration/ProfileBoundaryEnforcer.kt:189-213`
**File:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:1064-1075`

---

### Step 8 — `persistRawSnapshot`: rail sync, snapshot write, and trace emission

`persistRawSnapshot` (line 938) is the inner write method, called by `updateSnapshot` → `persistRawSnapshot`:

```kotlin
private suspend fun persistRawSnapshot(
    snapshot: ContinueWatchingSnapshot,
    profileId: Int = activeProfileId(),
    resultSession: ActiveProfileSession = sessionForProfile(profileId)
): Boolean {
    val normalized = sanitizeSnapshot(snapshot)
    val hydrated = hydrateSnapshotMetadata(normalized, rawSnapshotState.value.snapshot.displayMetadataByItemKey)
    if (!canPublishProfileWrite(resultSession)) {
        return false
    }
    syncContinueWatchingRail(hydrated, profileId)
    snapshotStore.write(hydrated, profileId = profileId)
    emitWrite(
        profileId = profileId,
        recordCount = hydrated.resumeItems.size + hydrated.nextUpItems.size + hydrated.traktUpNextItems.size
    )
    // ... update rawSnapshotState, mark rail active, update lastRefreshRequestMs
    return true
}
```

Execution order (only if `canPublishProfileWrite` passes):
1. `syncContinueWatchingRail(hydrated, profileId)` — writes to Room via `IntegrationOwnershipService.upsertRailMembership`
2. `snapshotStore.write(hydrated, profileId)` — writes to profile-scoped SharedPreferences
3. `emitWrite(profileId, recordCount)` — fires the `continue_watching.snapshot_write` trace event

**File:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:938-962`

---

### Step 9 — `displayMetadataByItemKey` and `metadataSnapshotsByItemKey` overlays

Before the write, `hydrateSnapshotMetadata` (line 1188) populates `displayMetadataByItemKey` by fetching `HomeDisplayMetadata` for each item key in the snapshot. `metadataSnapshotsByItemKey` overlays are set separately via `recordMetadataSnapshot(itemKey, metadataSnapshot)`, called at click-time from the UI layer. Both maps are part of `ContinueWatchingSnapshot` and are persisted together inside `ContinueWatchingSnapshotStore.write` (line 82-104). Neither map is a shared static cache; each lives inside its own `ProfileOwnedContinueWatchingSnapshot` instance.

**File:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:1188-1229`

---

### Step 10 — Trakt up-next sync path

`observeSyntheticContinueWatchingNextUp()` in `DefaultTrackingProgressService` (line 145) routes through the active tracking provider's synthetic next-up flow. For Trakt this is `traktProgressService.observeSyntheticContinueWatchingNextUp()` enriched via `tvdbContinueWatchingTimingEnricher.enrich(...)`. The result lands in `traktUpNextEntries` in the `combine` at Step 5 and is included in `buildRawSnapshot` as `traktUpNextItems`.

The `traktUpNextItems` list is included in the `recordCount` formula at all three emission sites (F-G-03 closure):
- `loadPersistedSnapshotForActiveProfile` (line 328): `normalized.resumeItems.size + normalized.nextUpItems.size + normalized.traktUpNextItems.size`
- `observeSnapshot` `emitRead` (line 348): same formula
- `persistRawSnapshot` `emitWrite` (line 955): `hydrated.resumeItems.size + hydrated.nextUpItems.size + hydrated.traktUpNextItems.size`

**File:** `app/src/main/java/com/nexio/tv/data/repository/TrackingProgressService.kt:145-159`

---

### Step 11 — `continue_watching.snapshot_write` event emission

`emitWrite` (companion object, line 1358):

```kotlin
internal fun emitWrite(profileId: Int, recordCount: Int) {
    if (traceSink === com.nexio.tv.core.trace.NoopRuntimeTraceSink) return
    val sid = traceSessionId() ?: return
    val profileHash = com.nexio.tv.core.trace.TraceHash.of(sid, profileId.toString())
    traceSink.emit(
        com.nexio.tv.core.trace.TraceEventEnvelope(
            traceSessionId = sid,
            sequence = traceSeq.incrementAndGet(),
            wallClockMs = System.currentTimeMillis(),
            elapsedRealtimeMs = System.nanoTime() / 1_000_000,
            threadName = Thread.currentThread().name,
            eventType = "continue_watching.snapshot_write",
            payload = mapOf(
                "profileHash" to profileHash,
                "profileId" to profileId,
                "recordCount" to recordCount,
                "source" to "LOCAL_PERSIST"
            )
        )
    )
}
```

There is **exactly one** call site for `emitWrite` in the production path: `persistRawSnapshot` at line 953. There is a second call in `loadPersistedSnapshotForActiveProfile` at line 326-329, which fires only when a persisted snapshot is normalized/upgraded on load (schema version mismatch or routing version upgrade). The `observeSnapshot` `onStart` block calls `emitRead`, not `emitWrite`.

**Emission sites for `continue_watching.snapshot_write`:**
1. `persistRawSnapshot` (line 953) — the live write path from new data
2. `loadPersistedSnapshotForActiveProfile` (line 326-329) — only when a persisted snapshot is upgraded on load (conditional: `if (normalized.metadataSnapshotsByItemKey != persisted.metadataSnapshotsByItemKey)`)

**File:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:1358-1378`

---

### Step 12 — Room DAO usage and `@Transaction`

`ContinueWatchingSnapshotStore` uses Android `SharedPreferences` for primary snapshot storage — **not Room**. There is no Room DAO for the CW snapshot itself. Room is used only for the integration-ownership rail metadata via `RailStoreDao` and `MediaIdentityDao` inside `syncContinueWatchingRail`.

`RailStoreDao` carries `@Transaction` on two methods:
- `replaceRailItems(railKey, items)` (line 56-62): `@Transaction` — atomically deletes old items then inserts new ones.
- `deleteRailWithMembership(railKey)` (line 64-68): `@Transaction` — atomically deletes items and rail header.

`IntegrationOwnershipService.upsertRailMembership` (line 25-38) calls these DAOs sequentially without an outer `@Transaction` wrapper, which is the G-03 finding:

```
1. railStoreDao.itemsForRail(...)            — SELECT (no Transaction)
2. railStoreDao.upsertRail(...)              — INSERT OR REPLACE (no Transaction)
3. railStoreDao.replaceRailItems(...)        — @Transaction internally
4. mediaIdentityDao.upsertMediaIdentity()   — per-identity (no Transaction)
5. mediaIdentityDao.replaceExternalIds()    — per-media-key (no Transaction)
```

Steps 2-5 are individually atomic but not jointly atomic. A process kill between step 2 (rail header written) and step 3 (items written) leaves an orphaned rail cache row with no items.

**Summary on `@Transaction`:** The CW snapshot store (SharedPreferences) has no `@Transaction` concept; DataStore's `edit` block provides internal atomicity per store. The Room ownership rail write has no outer transaction — see G-03.

**File:** `app/src/main/java/com/nexio/tv/data/local/integration/RailStoreDao.kt:56-68`
**File:** `app/src/main/java/com/nexio/tv/core/integration/IntegrationOwnershipService.kt:25-38`

---

## 3. Findings

### TW-01 — Profile context captured at write-resolve time, not write-issue time (incomplete for F-H-03 scrobble path)

**Severity:** P2 (for scrobble path; the CW write path is correctly guarded)

The task description states "profile context captured at write-issue time, not write-resolve time (F-H-03 — but Lane H found this is INCOMPLETE; only telemetry, not blocking)."

For the **CW write path**, `resultSession = activeProfileSession()` is captured in the `collectLatest` callback at Step 5 — this is the write-resolve time, not the issue time. However, this is **correct behavior**: the resolve-time session is compared against the current session inside `assertCanWriteProfileState`. If the profile switched between issue and resolve, the sessions will differ and the write is rejected. The rejection is a true block (throws `ProfileBoundaryException`), so the CW write path satisfies the spirit of F-H-03.

For the **scrobble path** (H-01, H-02), `checkScrobbleBoundary` in both `TraktScrobbleService` (line 294) and `SimklScrobbleService` (line 248) emits `playback.scrobble_rejected` telemetry but does **not** halt the write. The mutation proceeds to `traktMutationOutboxCoordinator.enqueueAndDrain` regardless. The F-H-03 closure claim in prior review context is inaccurate for scrobbles — the boundary signal is telemetry-only there.

**Evidence:** `TraktScrobbleService.kt:294-303` — `checkScrobbleBoundary` is `Unit`-returning, callers in `enqueueCheckin` and `enqueueScrobble` do not inspect a return value or catch a thrown exception.

---

### TW-02 — `recordCount` formula covers all three rails at both `snapshot_write` emission sites

**Severity:** Pass / Confirmed closed (F-G-03)

Both emission sites for `continue_watching.snapshot_write` include `traktUpNextItems.size`:

- `persistRawSnapshot` line 955: `hydrated.resumeItems.size + hydrated.nextUpItems.size + hydrated.traktUpNextItems.size`
- `loadPersistedSnapshotForActiveProfile` line 328-329: `normalized.resumeItems.size + normalized.nextUpItems.size + normalized.traktUpNextItems.size`

The `ContinueWatchingRecordCountIncludesTraktUpNextTest` static test pins this at the source level. G-01 verifies the companion `snapshot_read` event's `emitRead` call also uses the three-rail formula (line 348).

**Residual gap from G-01:** The `snapshot_read` trace test does not assert `profileHash` or `source = "OBSERVE_SUBSCRIBE"`, leaving those two payload fields unguarded against future refactoring.

---

### TW-03 — Single production caller for `assertCanWriteProfileState` on the CW write path

**Severity:** Informational

`ProfileBoundaryEnforcer.assertCanWriteProfileState` (the two-param overload) has one production call site: `ContinueWatchingSnapshotService.canPublishProfileWrite` (line 1066). All other callers in `TraktScrobbleService` and `SimklScrobbleService` use `checkScrobbleBoundary` (telemetry-only), not `assertCanWriteProfileState`. This confirms that the CW write path is the only data-layer path where `STALE_SESSION_WRITE_REJECTED` actually prevents a write.

---

### TW-04 — `snapshotStore.write` uses current active profileId at write time, not the session's profileId

**Severity:** P3 (potential for cross-profile write under race)

`ContinueWatchingSnapshotStore.write(snapshot, profileId = profileId)` receives an explicit `profileId` argument from `persistRawSnapshot`. However, the profile gating relies on `canPublishProfileWrite(resultSession)` returning `true`, which means `resultSession.profileId == activeProfileSession().profileId`. After this check passes, `snapshotStore.write` is called on a separate line — there is a narrow race window between the `assertCanWriteProfileState` check and the actual `snapshotStore.write` call where the active profile could change a second time.

This window is narrower than the equivalent scrobble window (which spans a full async network round-trip), but it is non-zero. The consequence would be writing the snapshot for `profileId=N` into the correct profile file, but with content that was assembled while profile `N` was active and the session validated — so the write would target the correct profile file. The `profileId` parameter to `snapshotStore.write` is the one from `persistRawSnapshot`'s parameter, not freshly sampled. This means the file-targeting is correct even if the active profile changes after validation. Risk: low; behavioral impact: none identified.

---

## 4. Call chain summary

```
PlayerRuntimeControllerPlaybackEvents.saveWatchProgressIfNeeded()
  └─ saveWatchProgressInternal(position, duration, syncRemote=false)
        └─ [scope.launch] watchProgressRepository.saveProgress(progress)
              ├─ [guard] trackingProviderStateService.currentState().hasAuthenticatedProvider
              ├─ trackingProgressService.applyOptimisticProgress(progress)
              │     └─ DefaultTrackingProgressService → TraktProgressService | SimklProgressService
              │           └─ optimistic StateFlow update → allProgress reactive emission
              └─ watchProgressPreferences.saveProgress(progress)
                    └─ ProfileDataStoreFactory.get(activeProfileId, "watch_progress_preferences").edit { ... }
                          └─ DataStore emit → WatchProgressPreferences.allProgress Flow

ContinueWatchingSnapshotService.init [scope.launch — reactive combine]
  └─ combine(observeRemoteSnapshotLoaded, allProgress, nextUpEntries, traktUpNextEntries)
        └─ [if canPublishLiveSnapshot] updateSnapshot(snapshot, profileId, resultSession=activeProfileSession())
              └─ persistRawSnapshot(snapshot, profileId, resultSession)
                    ├─ [guard] canPublishProfileWrite(resultSession)
                    │     └─ ProfileBoundaryEnforcer.assertCanWriteProfileState(resultSession, activeProfileSession())
                    │           └─ [if stale] throws ProfileBoundaryException → returns false → ABORT
                    ├─ syncContinueWatchingRail(hydrated, profileId)
                    │     └─ IntegrationOwnershipService.upsertRailMembership(...)
                    │           ├─ railStoreDao.upsertRail(...)       [no outer @Transaction]
                    │           ├─ railStoreDao.replaceRailItems(...) [@Transaction internally]
                    │           └─ mediaIdentityDao.upsertMediaIdentity / replaceExternalIds per item
                    ├─ snapshotStore.write(hydrated, profileId)
                    │     └─ SharedPreferences.edit().putString(SNAPSHOT_KEY, ...).apply()
                    └─ emitWrite(profileId, recordCount=resumeItems+nextUpItems+traktUpNextItems)
                          └─ traceSink.emit(TraceEventEnvelope(eventType="continue_watching.snapshot_write", ...))
```

---

## 5. Verification results

| Check | Result | Evidence |
|---|---|---|
| Caller: PlayerViewModel → TrackingProgressService → CW write | **Partial** — The path is `PlayerRuntimeController → WatchProgressRepository → TrackingProgressService`. There is no direct PlayerViewModel→TrackingProgressService progress tick; the intermediary is `WatchProgressRepository`. | `PlayerRuntimeControllerPlaybackEvents.kt:267`, `WatchProgressRepositoryImpl.kt:299-304` |
| Profile context captured at write-issue time | **Not applicable for CW path** — context is captured at write-resolve time (collectLatest), then validated against current session. This is the correct design; stale sessions are rejected at resolution, not issue. | `ContinueWatchingSnapshotService.kt:296, 1066` |
| F-H-03 — INCOMPLETE (only telemetry, not blocking) | **Confirmed for scrobble path only** — CW write path uses `assertCanWriteProfileState` which throws and blocks. Scrobble path uses `checkScrobbleBoundary` which is telemetry-only (H-01). | `TraktScrobbleService.kt:294-303`, `ContinueWatchingSnapshotService.kt:1064-1075` |
| `assertCanWriteProfileState` runs before Room/SharedPreferences write | **Confirmed** — `canPublishProfileWrite(resultSession)` is called before `syncContinueWatchingRail` and `snapshotStore.write` in `persistRawSnapshot`. | `ContinueWatchingSnapshotService.kt:948-955` |
| `STALE_SESSION_WRITE_REJECTED` actually rejects CW writes | **Confirmed** — `assertCanWriteProfileState` throws `ProfileBoundaryException(STALE_SESSION_WRITE_REJECTED)`, caught by `canPublishProfileWrite` which returns `false`, aborting the write. | `ProfileBoundaryEnforcer.kt:189-200`, `ContinueWatchingSnapshotService.kt:1064-1075` |
| H-01 — `STALE_SESSION_WRITE_REJECTED` does not reject scrobble writes | **Confirmed** — `TraktScrobbleService.checkScrobbleBoundary` and `SimklScrobbleService.checkScrobbleBoundary` emit telemetry only; `enqueueScrobble`/`enqueueCheckin` do not inspect a return value or catch a thrown exception. | `TraktScrobbleService.kt:259-303` |
| Room DAO uses suspend + `@Transaction` | **Partial** — `RailStoreDao.replaceRailItems` and `deleteRailWithMembership` carry `@Transaction`. `upsertRail`, `upsertMediaIdentity`, `replaceExternalIds` do not. `upsertRailMembership` in `IntegrationOwnershipService` has no outer `@Transaction` — G-03 finding. CW snapshot store uses SharedPreferences (no Room, no `@Transaction` concept). | `RailStoreDao.kt:56-68`, `IntegrationOwnershipService.kt:25-38` |
| `continue_watching.snapshot_write` emits `recordCount` including `traktUpNextItems.size` (F-G-03) | **Confirmed** — `persistRawSnapshot` line 955 formula: `hydrated.resumeItems.size + hydrated.nextUpItems.size + hydrated.traktUpNextItems.size`. Pinned by `ContinueWatchingRecordCountIncludesTraktUpNextTest`. | `ContinueWatchingSnapshotService.kt:953-955` |
| `recordCount` formula matches all emission sites (G-01) | **Confirmed** — All three sites (lines 328, 348, 955) use the same three-rail formula. Residual G-01 gap: `snapshot_read` test does not assert `profileHash` or `source` in payload. | `ContinueWatchingSnapshotService.kt:328, 348, 955` |
| `snapshot_write` emission sites count | **2** — `persistRawSnapshot` (live path, always fires on successful write); `loadPersistedSnapshotForActiveProfile` (conditional — only when normalized metadata differs from persisted). Lane description mentions one site but there are two. | `ContinueWatchingSnapshotService.kt:326-329, 953` |

---

## 6. Lane cross-reference index

| Finding | Lane | Status at this trace |
|---|---|---|
| F-G-03 — `recordCount` must include `traktUpNextItems.size` | Lane G, G-01 | Closed — all three formula sites confirmed. Both `snapshot_write` sites use three-rail formula. |
| G-01 — `snapshot_read` test missing `profileHash`/`source` assertion | Lane G | Open — not addressed in the write path but affects the companion read event. |
| G-03 — `upsertRailMembership` lacks outer `@Transaction` | Lane G | Confirmed — `IntegrationOwnershipService.upsertRailMembership` has no outer transaction. P2 finding. |
| H-01 — `checkScrobbleBoundary` telemetry-only (does not halt write) | Lane H | Confirmed — applies only to scrobble path. CW write path correctly uses `assertCanWriteProfileState`. |
| H-02 — `assertCanWriteProfileState` absent from `TrackingProgressService` | Lane H | Confirmed — no `assertCanWriteProfileState` call in `DefaultTrackingProgressService`. P1 finding on scrobble path. |
| F-H-03 — profile context at write-issue vs write-resolve | Lane H | Confirmed incomplete for scrobble path; CW write path resolves correctly via `canPublishProfileWrite`. |
