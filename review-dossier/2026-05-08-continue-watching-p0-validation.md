# Continue Watching P0 Shared Identity Playback Validation

Date: 2026-05-08
Worktree: `/Users/jneerdael/Scripts/nexio/.worktrees/cw-p0-identity-playback`
Base HEAD: `21bed694666acf73786f8ea563eca1a255aa61b3`

## P0 Acceptance

- Citadel local TVDB progress resolves canonical key `profile:1:series:tvdb:393268:s2e1`.
- Citadel local TVDB progress gets stream fetch id `tt9794044:2:1`.
- Citadel Trakt IMDb progress resolves to the same canonical key.
- Home Continue Watching renders from `snapshot.records`.
- Local TVDB and Trakt IMDb Citadel aliases render as one card.
- Click route preserves resume `videoId=tvdb:393268:2:1`.
- Click route passes `streamVideoId=tt9794044:2:1`.
- Identity resolution failure preserves a low-confidence legacy row.
- Snapshot canonicalization uses suspend transforms and no `runBlocking`.
- CW identity resolution uses `MetadataDepth.IDENTITY`, not `DETAIL_CORE`.

## Source Contract Added

Added `ProfileSettingsScopeContractTest.continue watching p0 shared identity playback wiring exists` to assert:

- `ContinueWatchingSnapshotService.kt` has a suspend raw snapshot build path.
- `runBlockingSafelyForSnapshot` is absent.
- `ContinueWatchingMerger.merge` is used for canonical CW record merging.
- `ContinueWatchingIdentityResolver.kt` requests `MetadataDepth.IDENTITY`.
- `HomeViewModelContinueWatching.kt` calls `buildContinueWatchingItemsForSnapshot`.
- `NexioNavHost.kt` passes in-progress CW `streamVideoId = item.streamFetchVideoId` while preserving `videoId = item.progress.videoId`.

The contract also asserts the requested literal `snapshot.records.isNotEmpty()` Home wiring. Current source branches through `snapshot.records.isEmpty()` in `buildContinueWatchingItemsForSnapshot`, so the new contract exposes that mismatch.

## Test Commands And Results

### Focused P0 Command

Command:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataDepthIdentityTest --tests com.nexio.tv.data.repository.ContinueWatchingIdentityModelsTest --tests com.nexio.tv.data.repository.ContinueWatchingItemKeysTest --tests com.nexio.tv.data.repository.StreamFetchIdentityResolverTest --tests com.nexio.tv.data.repository.ContinueWatchingRecordTest --tests com.nexio.tv.data.repository.ContinueWatchingIdentityResolverTest --tests com.nexio.tv.data.repository.ContinueWatchingMergerTest --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest --tests com.nexio.tv.ui.navigation.ScreenStreamRouteTest --tests com.nexio.tv.ui.navigation.StreamRuntimeRoutingTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest
```

Result: FAILED. `152 tests completed, 5 failed`.

Failed tests:

- `ProfileSettingsScopeContractTest > new trakt mutations are stamped with captured profile id` at `ProfileSettingsScopeContractTest.kt:948`
- `ProfileSettingsScopeContractTest > trakt outbox adapters execute with envelope profile session` at `ProfileSettingsScopeContractTest.kt:893`
- `ProfileSettingsScopeContractTest > continue watching p0 shared identity playback wiring exists` at `ProfileSettingsScopeContractTest.kt:792`
- `ProfileSettingsScopeContractTest > trakt progress runtime state is profile keyed` at `ProfileSettingsScopeContractTest.kt:741`
- `ProfileSettingsScopeContractTest > tracking library services enforce profile owned state and api sessions` at `ProfileSettingsScopeContractTest.kt:911`

### Impacted Command

Command:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingMetadataRouterTest --tests com.nexio.tv.data.repository.ContinueWatchingTimelineTest --tests com.nexio.tv.data.repository.ContinueWatchingTimelineAirDateTest --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceProfileBoundaryTest --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingProfileScopedTest --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingProjectionTest --tests com.nexio.tv.ui.screens.stream.StreamScreenViewModelDeterministicAutoplayTest
```

Result: FAILED. `60 tests completed, 5 failed`.

Failed tests:

- `ContinueWatchingSnapshotServiceProfileBoundaryTest > continue watching syncs rail ownership before persisting snapshot` at `ContinueWatchingSnapshotServiceProfileBoundaryTest.kt:287`
- `ContinueWatchingSnapshotServiceProfileBoundaryTest > continue watching ownership rail is profile scoped and canonicalized` at `ContinueWatchingSnapshotServiceProfileBoundaryTest.kt:287`
- `ContinueWatchingSnapshotServiceProfileBoundaryTest > stale default profile emission after switch is not stamped as secondary profile snapshot` at `ContinueWatchingSnapshotServiceProfileBoundaryTest.kt:287`
- `ContinueWatchingSnapshotServiceProfileBoundaryTest > observing snapshot waits for persisted active profile snapshot before emitting default empty` with `io.mockk.MockKException at MockKStub.kt:93`
- `HomeViewModelContinueWatchingProfileScopedTest > accepted continue watching snapshot cancels previous enrichment before eligibility check` at `HomeViewModelContinueWatchingProfileScopedTest.kt:308`

## Notes

- The existing `ProfileSettingsScopeContractTest` baseline is still failing in the tracking/profile ownership contract area.
- The existing `ContinueWatchingSnapshotServiceProfileBoundaryTest` baseline is still failing in the profile boundary area.
- The new P0 source contract currently fails only on the requested literal Home `snapshot.records.isNotEmpty()` expression; the implementation uses the equivalent inverse branch, `snapshot.records.isEmpty()`, before falling through to canonical record rendering.
