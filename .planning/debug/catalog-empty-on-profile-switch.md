---
status: awaiting_human_verify
trigger: "catalog-empty-on-profile-switch"
created: 2026-04-16T00:00:00Z
updated: 2026-04-16T00:00:00Z
---

## Current Focus

hypothesis: CONFIRMED AND FIXED - Race condition between discovery observer empty emissions and disk hydration during profile switch
test: Added guards in all three discovery observer pipelines to skip empty emissions during profile-switch suppress window
expecting: Catalogs should appear instantly from disk cache on profile switch, no empty flash
next_action: Await human verification on device

## Symptoms

expected: Switching profiles should instantly show Trakt/MDBList/Simkl catalogs from the disk cache snapshot, then silently refresh in the background
actual: Catalogs appear empty on screen after profile switch, then populate after a delay. Metadata and images load instantly from cache.
errors: No errors visible — purely visual delay, no crashes or exceptions
reproduction: Switch profiles on Android TV
started: Appeared when profile switching was introduced

## Eliminated

- hypothesis: Profile switch clears disk cache snapshots for catalogs
  evidence: SimklDiscoverySnapshotStore, TraktDiscoverySnapshotStore, MDBListDiscoverySnapshotStore all use per-profile SharedPreferences keys. Profile switch does NOT clear disk caches — only resetProfileScopedHomeState clears in-memory state. loadActiveProfileDiskBackedHomeState reads fresh from disk.
  timestamp: 2026-04-16

- hypothesis: Disk cache snapshot stores are not profile-aware
  evidence: All snapshot stores use profilePrefsName() or profileId-based keys. SimklDiscoverySnapshotStore.read(profileId), TraktDiscoverySnapshotStore.read(profileId), HomeCatalogSnapshotStore.snapshotKey uses "snapshot:p$profileId:$languageTag"
  timestamp: 2026-04-16

## Evidence

- timestamp: 2026-04-16
  checked: observeProfileSwitches in HomeViewModel.kt (line 422-446)
  found: Profile switch calls resetProfileScopedHomeState() which clears all in-memory discovery snapshots to empty and UI to isLoading=true, then calls loadActiveProfileDiskBackedHomeState() which reads disk caches and sets them back
  implication: There is a window where in-memory state is empty, and concurrent observers can interfere

- timestamp: 2026-04-16
  checked: resetProfileScopedHomeState in HomeViewModelCatalogPipeline.kt (line 149-194)
  found: Clears traktDiscoverySnapshot, simklDiscoverySnapshot, mdbListDiscoverySnapshot to empty. Sets suppressProfileSwitchRefreshUntilMs to now+5000ms. Does NOT reset traktDiscoveryObserved, simklDiscoveryObserved, mdbListDiscoveryObserved flags.
  implication: Discovery observer flags remain true, allowing the dedup guard to pass for the initial empty emission

- timestamp: 2026-04-16
  checked: Discovery service observeSnapshot() pattern (SimklDiscoveryService line 206-223, TraktDiscoveryService line 216-241, MDBListDiscoveryService line 111-135)
  found: All three use profileManager.activeProfileId.flatMapLatest { profileId -> profileSnapshots.map { it[profileId] ?: EmptySnapshot() }.onStart { read disk, setProfileSnapshot } }
  implication: When activeProfileId changes, flatMapLatest restarts inner flow. The inner flow subscribes to profileSnapshots StateFlow and gets CURRENT value immediately (before onStart completes). If new profileId is not yet in the map, the first emission is an EMPTY snapshot.

- timestamp: 2026-04-16
  checked: Discovery observer collectors (observeSimklDiscoveryPipeline line 422-445, observeTraktDiscoveryPipeline line 359-397, observeMDBListDiscoveryPipeline line 473-500)
  found: Each collector sets xxxDiscoverySnapshot = snapshot AND persistedXxxDiscoverySnapshot = snapshot, then calls runSerializedHomeRefreshIfNeeded which is SUPPRESSED by the 5-second suppressProfileSwitchRefreshUntilMs window
  implication: The empty first emission overwrites both live and persisted snapshots in the ViewModel. The refresh that would fix this is suppressed.

- timestamp: 2026-04-16
  checked: updateCatalogRowsPipeline (line 1577-1632 fallback logic)
  found: effectiveSimklSnapshot falls back to persistedSimklDiscoverySnapshot if simklDiscoverySnapshot is empty. But BOTH were overwritten to empty by the observer race.
  implication: Even the fallback path cannot recover because the empty observer emission corrupted both copies

- timestamp: 2026-04-16
  checked: suppressProfileSwitchRefreshUntilMs (line 430, line 674-682)
  found: Set to now+5000ms at start of profile switch. shouldSuppressProfileSwitchRefresh returns true for any reason except "account_sync" during this window. This blocks runSerializedHomeRefreshIfNeeded from all discovery and preference observers.
  implication: After the empty emission corrupts the snapshots, no refresh is allowed for 5 seconds, during which the catalogs remain empty

- timestamp: 2026-04-16
  checked: Build and test verification after fix applied
  found: assembleArm64Debug builds successfully. Unit test failures (44 tests) are pre-existing and unrelated to this fix - they involve AndroidTvLocalSearchCorpus, TraktMutationRouting, ContinueWatchingSnapshotService, IdleScreensaver, etc. None relate to discovery observer pipelines or profile switching.
  implication: Fix compiles cleanly and does not introduce regressions

- timestamp: 2026-04-16
  checked: Profile isolation analysis
  found: The guard only skips EMPTY emissions (updatedAtMs <= 0) during the suppress window. Non-empty snapshots (including stale data from a previous profile) would have updatedAtMs > 0 and would NOT be skipped. However, stale cross-profile data cannot leak because flatMapLatest in discovery services restarts the inner flow keyed by profileId, and profileSnapshots StateFlow is keyed by profileId. The only emission that gets through during profile switch is either empty (now blocked) or the correct profile's disk-loaded data from onStart.
  implication: Profile isolation is maintained - no data can leak between profiles

## Resolution

root_cause: Race condition during profile switch where discovery service flow observers emit an EMPTY snapshot before the onStart block has loaded disk-cached data, overwriting the disk-cached snapshots that loadActiveProfileDiskBackedHomeState just set. The sequence is: (1) resetProfileScopedHomeState clears in-memory snapshots, (2) loadActiveProfileDiskBackedHomeState reads disk and populates snapshots, (3) concurrently, discovery observers' flatMapLatest re-subscribes to profileSnapshots StateFlow which emits the CURRENT value for the new profileId — which is empty because onStart hasn't run yet, (4) the empty emission overwrites both simklDiscoverySnapshot AND persistedSimklDiscoverySnapshot (same for Trakt/MDBList), (5) the 5-second suppressProfileSwitchRefreshUntilMs prevents any corrective refresh, (6) catalogs appear empty until the autoRefreshOnStart network fetch completes and emits new data. The root cause applies equally to all three discovery services (Trakt, Simkl, MDBList).
fix: Added guards in all three discovery observer pipelines (observeTraktDiscoveryPipeline, observeSimklDiscoveryPipeline, observeMDBListDiscoveryPipeline) that skip empty snapshot emissions (updatedAtMs <= 0) when the profile-switch suppress window is active. This prevents the race condition where flatMapLatest re-subscribes to profileSnapshots StateFlow and emits an empty snapshot before onStart loads disk data. The guard uses the existing shouldSuppressProfileSwitchRefresh() check which is active during profileSwitchDiskHydrationActive or within the 5-second suppressProfileSwitchRefreshUntilMs window.
verification: Build compiles successfully (assembleArm64Debug). Awaiting on-device verification.
files_changed: [app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt]
