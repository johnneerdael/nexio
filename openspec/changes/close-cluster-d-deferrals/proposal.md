## Why

Cluster D's SIGN-OFF (commit `58274df89`) documented two deferrals:

1. **F-I-05 partial closure:** `provideYouTubeTrailerMainOkHttpClient` + `provideYouTubeTrailerProbeOkHttpClient` (`NetworkModule.kt:745, 758`) construct fresh `OkHttpClient.Builder()` instances WITHOUT wiring `RuntimeTraceInterceptor`. The architecture pin (`DerivedOkHttpClientTraceWiringTest`) caught the count at 4 (base + playback + two trailer clients) but didn't enforce wiring on the trailer clients themselves.
2. **F-G-01 path B:** Cluster D Tasks 4/5 took path A (chained `.filter { it.profileId == activeProfileId }` on the existing `observeSnapshot()` flow). Path B (full migration to `observeContinueWatching(profileId): Flow<List<ContinueWatchingRecord>>`) was deferred because the lean record shape lacks fields downstream consumers need (snapshot-level `traktUpNextItems`, `displayMetadataByItemKey`, `metadataSnapshotsByItemKey`).

This change closes both. The CW migration uses a different lever than the original "switch to records" framing: introduce a typed profile-scoped snapshot flow that preserves the snapshot shape but enforces the profile filter at the API boundary.

## What Changes

### MODIFIED

- `NetworkModule.provideYouTubeTrailerMainOkHttpClient(...)` and `provideYouTubeTrailerProbeOkHttpClient(...)` now inject `RuntimeTraceInterceptor` + `RuntimeTraceContextRequestTaggingInterceptor` and wire them as application + network interceptors (F-I-05).
- `DerivedOkHttpClientTraceWiringTest` count ratcheted from `<= 4` to `<= 2` (only base + playback are now legitimate fresh constructions); a positive test asserts the two YouTube trailer clients carry the trace interceptor.
- `HomeViewModelContinueWatching.kt:73-95` and `AndroidTvFeedCatalogService.kt:153, 232` replace `observeSnapshot().filter { it.profileId == ... }` with `observeProfileSnapshot(profileId)`. Eliminates the path A workaround in favor of a typed API.

### ADDED

- `ContinueWatchingSnapshotService.observeProfileSnapshot(profileId: Int): Flow<ContinueWatchingSnapshot>` — derives from `observeSnapshot()` with profile filter + unwrapped snapshot shape.
- `ContinueWatchingSnapshotServiceObserveProfileSnapshotTest` regression test (filter behavior + flow shape).
- `YouTubeTrailerClientTraceInterceptorTest` — asserts both `@Named` trailer clients carry `RuntimeTraceInterceptor` in their network-interceptor list.

## Impact

- Affected specs: `integration-runtime`.
- Affected code: 4 production files modified + 2 new test files.
- Behavior changes:
  - YouTube trailer fetches now appear in trace bundles (previously invisible).
  - CW consumers go through a typed profile-scoped API; the manual `.filter { ... }` workaround is gone.
- No new dependencies. No new trace events. No persistent schema changes (the new flow derives from the existing snapshot).
