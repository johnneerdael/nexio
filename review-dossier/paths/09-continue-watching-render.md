# Path 09 — Continue Watching render

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Lane:** G (CW) + F (profile boundaries) + I (trace mode)
- **Contract:** Home CW row reads via `observeContinueWatching(profileId)` (NOT parameterless). Filters by active profile. First emission per profile fires `continue_watching.snapshot_read`. No cross-profile leakage.

## Chain

| # | Symbol | File:line | Expected | Observed |
|---|---|---|---|---|
| 1 | `ModernHomeRows` Compose row item rendering CW card | `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt:567-582` (`ModernPayload.ContinueWatching → ModernContinueWatchingRowItem`); per-card UI at `:121-152` (`ContinueWatchingCard` invocation) | observes the CW flow indirectly via `HomeUiState.continueWatchingItems` and renders one `ContinueWatchingCard` per row item | matches — Compose draws from `ModernPayload.ContinueWatching` produced from the UI-state list |
| 2 | UI-state plumbing into modern presentation | `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt:204` (`continueWatchingItems = state.continueWatchingItems`); modern row build at `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomePresentation.kt:11-46` | converts `HomeUiState.continueWatchingItems` into a `ModernPayload.ContinueWatching` row (or empties the row when no items) | matches — the modern presenter caches per-list identity to avoid re-render when items are equal (`:14, :38`) |
| 3 | `HomeViewModel` CW collector | `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:71-118` (`loadContinueWatchingPipeline()`) | should call `continueWatchingSnapshotService.observeContinueWatching(activeHomeProfileSession.profileId)` | ⚠️ **deviation** — calls `continueWatchingSnapshotService.observeSnapshot()` (line `:73`) and then guards with `if (ownedSnapshot.profileId != activeHomeProfileSession.profileId) return@collectLatest` (line `:75-78`). The explicit-profileId convenience API exists (`ContinueWatchingSnapshotService.kt:358-363`) but is unused in production. Filtering is still correct because `observeSnapshot()` returns a `ProfileOwnedContinueWatchingSnapshot` whose `profileId` is checked, but the read goes through the parameterless wrapper. See finding F-09-1. |
| 4 | `ContinueWatchingSnapshotService.observeSnapshot()` | `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:340-356` | combines `snapshotState` with `persistedSnapshotReady`, gates on readiness, and on subscription emits `continue_watching.snapshot_read` via `onStart` plus kicks a refresh | matches — `onStart` resolves `profileId` from `snapshotState.value.profileId` (falls back to `activeProfileId()` when `<= 0`) and calls `emitRead(profileId, recordCount)` |
| 5 | Profile-scoped wrapper (intended public API for readers) | `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:358-363` (`observeContinueWatching(profileId)`) | filters to records owned by `profileId` (returns `emptyList()` when ownership differs) | matches at the API surface — but **no production caller exists** (only `ContinueWatchingProfileScopedQueryTest`); F-09-1 |
| 6 | `emitRead(profileId, recordCount)` (first-emission per subscription) | `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:1366-1386` (impl); call site `:345-348` | builds `TraceEventEnvelope` with `eventType = "continue_watching.snapshot_read"`, payload `profileHash` (HMAC of `traceSessionId + profileId.toString()` via `TraceHash.of`), `profileId`, `recordCount`, `source = "OBSERVE_SUBSCRIBE"` | matches — guards on `Noop` sink + null session id; sink installed via `RuntimeTraceModule.kt:70-74` |
| 7 | UI tile list re-render | `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:104-118` (`_uiState.update { … }`) → `HomeUiState.continueWatchingItems` (`HomeUiState.kt:17`) → `ModernHomePresentation.kt:11-38` → `ModernHomeRows.kt:567-582` | snapshot delta produces a new `continueWatchingItems` list which propagates through the presenter into the Compose row | matches — equality short-circuit at `:106-111` skips redundant updates; presenter rebuilds payload only when items change |

## Production callers of the parameterless API

| Caller | File:line | Acceptable? |
|---|---|---|
| `ContinueWatchingSnapshotService` (internal `combine`) — `trackingProgressService.observeContinueWatchingNextUp()` | `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:256` | ✅ — single internal use behind `@Suppress("DEPRECATION")` (line `:252`); per Task 10. |
| (no other production call sites) | — | ✅ — `grep`ped `app/src/main` returned only the above; the deprecation `@Deprecated` message at `TrackingProgressService.kt:48` is honored. |

> Test-only references to `observeContinueWatchingNextUp` (mock setups in `ContinueWatchingSnapshotServiceProfileBoundaryTest`, `ContinueWatchingSnapshotServiceMutationTest`, `DefaultTrackingProgressServiceTest`, `WatchProgressRepositoryProviderRoutingTest`, `MarkSeasonWatchedTest`, `SimklProgressServiceTest`, `TraktProgressServiceTest`-adjacent, `ContinueWatchingMetadataRouterTest`) are MockK stubs targeting the same suppressed internal API and are out-of-scope for production-caller accounting.

## What does NOT happen on this path (verified)

- ❌ NO cross-profile record emission — the Home VM rejects foreign snapshots in `loadContinueWatchingPipeline()` (`HomeViewModelContinueWatching.kt:75-78`); additionally, `canPublishLiveSnapshot(profileId)` in the snapshot service (`ContinueWatchingSnapshotService.kt:285`) blocks live publishes before the active profile's remote reset has landed.
- ❌ NO production caller of the deprecated parameterless API outside the snapshot service itself (`ContinueWatchingSnapshotService.kt:256` is the sole site, suppressed per Task 10).
- ❌ NO unprofiled read trace — `emitRead` always carries a positive `profileId` (falls back to `activeProfileId()` only when `snapshotState.value.profileId <= 0`, which itself is `1` when no profile manager is bound).

## Trace event coverage

| Event | Emitted? | Notes |
|---|---|---|
| `continue_watching.snapshot_read` | ✅ | `ContinueWatchingSnapshotService.observeSnapshot()` `onStart` block fires `emitRead(profileId, recordCount)` once per subscription. The Home VM subscribes once per `loadContinueWatchingPipeline()` invocation; on profile switch the VM relaunches the collector via the home-profile generation reset, producing a fresh first-emission per profile. |

## Verdict

⚠️ — Behaviorally correct (profile filtering is enforced, trace fires, no leakage), but the read does not flow through the explicit-profileId API the contract specifies. The Home VM still uses `observeSnapshot()` and post-filters by `ownedSnapshot.profileId`. This works because `ProfileOwnedContinueWatchingSnapshot` carries the owner's profileId, but it leaves the `observeContinueWatching(profileId)` convenience wrapper unused in production and means the UI surface receives a stream that may briefly carry a foreign-profile snapshot for the collector to drop, rather than receiving an already-filtered `List<ContinueWatchingRecord>`.

## Findings

- **F-09-1 (low):** `ContinueWatchingSnapshotService.observeContinueWatching(profileId)` (`app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:358-363`), introduced per commit `f54ce6eb0` to be the read API for profile-scoped consumers, has zero production callers. The Home CW pipeline (`HomeViewModelContinueWatching.kt:73`) consumes the lower-level `observeSnapshot()` flow and applies its own `if (ownedSnapshot.profileId != activeHomeProfileSession.profileId) return@collectLatest` filter. Recommendation: either route the Home VM through `observeContinueWatching(activeHomeProfileSession.profileId)` (returns already-filtered records and centralizes the boundary check), or delete the unused wrapper to avoid API surface drift. No leakage today, but the manual collector-side filter is a divergence from the documented contract and the only thing standing between a foreign snapshot and the UI.

## Cross-references

- Path 08 (CW write) — same snapshot pipeline, write-side counterpart.
- Lane G (Task 31) — CW behavioral coverage.
- Earlier work: commits `f54ce6eb0` (explicit-profileId API), `d623575d5` (deprecation of parameterless tracker API), `ea14ced36` (read/write trace emissions).
