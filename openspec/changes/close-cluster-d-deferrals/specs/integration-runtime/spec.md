## ADDED Requirements

### Requirement: YouTube trailer OkHttpClients carry the trace interceptor

`NetworkModule.provideYouTubeTrailerMainOkHttpClient` and `provideYouTubeTrailerProbeOkHttpClient` MUST inject `RuntimeTraceInterceptor` and `RuntimeTraceContextRequestTaggingInterceptor`, wiring the tagging interceptor as an application interceptor and the trace interceptor as a network interceptor (matching the base provider pattern).

#### Scenario: YouTube trailer fetch appears in trace bundle

- **WHEN** a YouTube trailer fetch is initiated (main or probe client)
- **THEN** the resulting trace bundle contains an `http.request` event for the trailer URL

### Requirement: ContinueWatchingSnapshotService exposes a typed profile-scoped snapshot flow

`ContinueWatchingSnapshotService.observeProfileSnapshot(profileId: Int): Flow<ContinueWatchingSnapshot>` MUST emit ONLY snapshots whose `profileId` matches the requested id, returning the unwrapped `ContinueWatchingSnapshot` (preserving snapshot-shape consumers like `displayMetadataByItemKey`).

#### Scenario: HomeViewModel subscribes to profile-scoped snapshot

- **GIVEN** the active home profile id is `2`
- **WHEN** `HomeViewModelContinueWatching` subscribes
- **THEN** it calls `observeProfileSnapshot(2)` (not `observeSnapshot().filter { ... }`)
- **AND** snapshots emitted to the VM have `profileId == 2`

## MODIFIED Requirements

### Requirement: Production NetworkModule constructs at most 2 OkHttpClients from scratch

The architecture pin `DerivedOkHttpClientTraceWiringTest` MUST assert that `NetworkModule.kt` contains at most 2 fresh `OkHttpClient.Builder()` constructions (the base provider + the playback provider, both of which wire trace interceptors manually). All other clients MUST derive via `baseClient.newBuilder()` to inherit interceptors.

#### Scenario: Adding a third fresh OkHttpClient.Builder() trips the test

- **WHEN** a developer adds `OkHttpClient.Builder()` (without `.newBuilder()`) in `NetworkModule.kt` for a new provider
- **THEN** `DerivedOkHttpClientTraceWiringTest` fails with the new count
