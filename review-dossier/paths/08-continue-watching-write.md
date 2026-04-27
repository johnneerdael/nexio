# Path 08 — Continue Watching write

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Lane:** G (Continue Watching) + F (profile boundaries) + I (trace mode)
- **Contract:** Player heartbeat persists `WatchProgress` → `WatchProgressRepository.saveProgress` → optimistic flow → CW snapshot rebuilt → `ContinueWatchingSnapshotService.persistRawSnapshot` writes via profile-keyed `snapshotStore.write(snapshot, profileId)` → `continue_watching.snapshot_write` trace event with `profileHash`.

## Chain

| # | Symbol | File:line | Expected | Observed |
|---|---|---|---|---|
| 1 | Playback heartbeat / stop / nav-away tick | `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt:175-186` (`startWatchProgressSaving`), `:382, 389` (stop/nav-away `saveWatchProgress()`), `:862, 890` (`PlayerRuntimeControllerInitialization.kt`) | periodic + lifecycle-driven progress emission | matches — heartbeat loop currently gated by `shouldPersistWatchProgressOnPlaybackInterval()` (returns `false`); the live writers are the stop/dispose paths via `saveWatchProgress()` which always persist on stop |
| 2 | `saveWatchProgressInternal(position, duration, syncRemote)` | `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt:242-270` | builds `WatchProgress` from `contentId / season / episode / position / duration / lastWatched` and launches `watchProgressRepository.saveProgress(progress, syncRemote = syncRemote)` | matches — early-returns on `contentId.isNullOrEmpty()` or `position < 1000`; duration-aware `fallbackPercent` is included |
| 3 | `WatchProgressRepositoryImpl.saveProgress` | `app/src/main/java/com/nexio/tv/data/repository/WatchProgressRepositoryImpl.kt:299-305` | gated on `hasAuthenticatedProvider`; pushes optimistic update + writes to `watchProgressPreferences` | matches — `trackingProgressService.applyOptimisticProgress(progress)` then `watchProgressPreferences.saveProgress(progress)`; *note:* the `syncRemote` flag is currently ignored in the impl (no Trakt scrobble write here — handled by the scrobble path) |
| 4 | CW recomputation pipeline reacts to `allProgress` change | `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:253-297` | `combine(observeRemoteSnapshotLoaded, watchProgressRepository.allProgress, observeContinueWatchingNextUp, observeSyntheticContinueWatchingNextUp) → buildRawSnapshot → updateSnapshot` | matches — `collectLatest` drains into `updateSnapshot(snapshot, profileId, resultSession = activeProfileSession())` after `canPublishLiveSnapshot(profileId)` gate |
| 5 | `updateSnapshot` → `persistRawSnapshot` | `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:1010-1023` then `:924-948` | sanitize → hydrate metadata → `canPublishProfileWrite(resultSession)` boundary check → `syncContinueWatchingRail(hydrated, profileId)` → write+emit | matches — short-circuits on stale session via `canPublishProfileWrite` (which calls `ProfileBoundaryEnforcer.assertCanWriteProfileState`); only on success does it advance `rawSnapshotState`, mark active rail, and update `lastRefreshRequestMs` |
| 6 | `snapshotStore.write(snapshot, profileId)` | `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:938`; store at `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt:82-104` | profile-keyed write into `getSharedPreferences(prefsName(profileId))` | matches — store always derives the prefs file from the explicit `profileId` (defaults to `activeProfileId()` only when caller omits, which `persistRawSnapshot` does not do); per Task 27 commit `ea14ced36` |
| 7 | `emitWrite(profileId, recordCount)` | `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:939-942` (call) and `:1344-1364` (impl) | builds `TraceEventEnvelope` with `eventType = "continue_watching.snapshot_write"`, payload `profileHash` (HMAC of `traceSessionId + profileId.toString()` via `TraceHash.of`), `profileId`, `recordCount`, `source = "LOCAL_PERSIST"` | matches — sink/sessionId installed via `installTraceSink` (`:1335-1342`) from `RuntimeTraceModule` (`app/src/main/java/com/nexio/tv/core/di/RuntimeTraceModule.kt:70-74`); guards on `Noop` sink + null session id |
| 8 | snapshot state update | `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:943-947` | `rawSnapshotState.value = ProfileOwnedContinueWatchingSnapshot(profileId, snapshot)`; downstream `snapshotState` flow is updated by the consolidation pipeline | matches — `rawSnapshotState` is the input to the published `snapshotState` flow that backs `observeSnapshot()` / `observeContinueWatching(profileId)` |
| 9 | Home CW row re-render | `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt:67, 95` (`watchProgressRepository`); CW rail observers consume `ContinueWatchingSnapshotService.observeContinueWatching(profileId)` | UI subscribes to per-profile CW rail and re-renders when snapshotState updates | matches — `HomeViewModel` consumes `WatchProgressRepository` (and CW rail subscribers via the snapshot service) so a state update fans out to the Home compose tree |

## What does NOT happen on this path (verified)

- ❌ NO write of one profile's snapshot to another profile's storage. `snapshotStore.write` always derives prefs name from the explicit `profileId` argument; `persistRawSnapshot` always passes the resolved `profileId` (no `activeProfileId()` shortcut on the write site). Pre-write, `canPublishProfileWrite` rejects the write outright when `resultSession` no longer matches the active profile session (`ContinueWatchingSnapshotService.kt:1050-1061`).
- ❌ NO write of CW data without a `profileId`. Both `updateSnapshot` (`:1010-1023`) and `persistRawSnapshot` (`:924-948`) carry a non-null `Int` `profileId` (default `activeProfileId()` which is `1` when no profile manager is bound — never `0` or null); store call passes it positionally as a named argument.
- ❌ NO scrobble write hitting the wrong profile on this path — scrobble routing happens through `TrackingScrobbleService` keyed by `owner.ownerProfileId` (covered by Path 11 / Path 07).

## Trace event coverage

| Event | Emitted? | Notes |
|---|---|---|
| `continue_watching.snapshot_write` | ✅ | One per successful `snapshotStore.write` from `persistRawSnapshot` (`:939-942`) and one from the route-upgrade re-write in `loadPersistedSnapshotForActiveProfile` (`:325-328`) when sanitization mutates metadata |
| `profile.boundary_check` (PASS, scope `ProfileLocal`) | ✅ | `canPublishProfileWrite` calls `ProfileBoundaryEnforcer.assertCanWriteProfileState(resultSession, activeSession)` before every write (`:1050-1061`); enforcer's static sink is installed alongside the CW sink in `RuntimeTraceModule.kt:67-69` |
| `continue_watching.snapshot_read` | ✅ (related, not on the write path) | Emitted by `observeSnapshot()` `onStart` (`:340-356`) — callers see this on subscription, not on write |

## Verdict

✅ — write path is profile-keyed end-to-end, gated by `ProfileBoundaryEnforcer`, and emits the `continue_watching.snapshot_write` trace event with a hashed profile id when trace mode is on.

## Findings

None.

## Cross-references

- Lane G (Task 31, Continue Watching trace coverage)
- Lane F (Task 32, profile boundary write enforcement)
- Path 07 — Player start (heartbeat / scrobble owner pinning)
- Path 11 — Scrobble write (planned)
- Earlier work: commit `ea14ced36` (CW trace emission via static install slot + profile-keyed `snapshotStore.write`)
