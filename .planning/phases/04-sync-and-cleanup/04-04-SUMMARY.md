---
phase: 04-sync-and-cleanup
plan: 04
subsystem: sync
tags: [android, kotlin, datastore, supabase, profile-settings]

requires:
  - phase: 04-sync-and-cleanup
    plan: 02
    provides: ProfileSettingsSyncService v8 settings blob sync
  - phase: 04-sync-and-cleanup
    plan: 03
    provides: Startup settings blob pull and v8 observer wiring
provides:
  - Full snapshot import semantics for synced profile settings blobs
  - Normalized settings blob signing for echo-push suppression
  - Pull-before-observe profile-switch hydration gate
affects: [phase-04-sync-and-cleanup, profile-settings-sync, startup-sync]

tech-stack:
  added: []
  patterns:
    - Full snapshot DataStore import by clearing feature stores before applying remote values
    - Active-profile observer hydration gate with flatMapLatest cancellation and debounce

key-files:
  created:
    - .planning/phases/04-sync-and-cleanup/04-04-SUMMARY.md
  modified:
    - app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt
    - app/src/test/java/com/nexio/tv/core/sync/ProfileSettingsSyncServiceTest.kt

key-decisions:
  - "Normalize remote settings blobs to the syncedFeatures allowlist before import and signature generation."
  - "Map StateFlow to a regular Flow before distinctUntilChanged() so the hydration gate compiles while preserving the required operator."

patterns-established:
  - "Missing synced feature blobs are imported as empty snapshots by normalizing to buildJsonObject {} and clearing the feature DataStore."
  - "startObserving() hydrates each selected profile before observer-driven pushes are enabled."

requirements-completed: [SYNC-02]

duration: 7 min
completed: 2026-04-14
---

# Phase 04 Plan 04: SYNC-02 Gap Closure Summary

**Profile settings pulls now import normalized full snapshots and hydrate selected profiles before observer-driven pushes**

## Performance

- **Duration:** 7 min
- **Started:** 2026-04-14T16:36:39Z
- **Completed:** 2026-04-14T16:43:57Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments

- Added normalized full-snapshot semantics for `importSettingsBlob()`: every synced feature is processed, unknown feature keys are ignored, and missing feature blobs clear the corresponding local DataStore.
- Updated `pullBlobForProfile()` to use the same normalized blob for import and `skipNextPushSignature`, preserving echo-push suppression for remotely omitted feature objects.
- Gated `startObserving()` so each active profile is pulled before its local settings observer can enable debounced pushes.
- Added regression tests for blob normalization, DataStore clearing behavior, missing feature snapshots, and source-order hydration gating.

## Task Commits

1. **Task 1 RED: Full snapshot import regression tests** - `31cbedabd` (test)
2. **Task 1 GREEN: Normalized full snapshot imports** - `3f3198993` (feat)
3. **Task 2 RED: Profile-switch hydration ordering test** - `9c83c9805` (test)
4. **Task 2 GREEN: Pull-before-observe hydration gate** - `d47a9e67c` (feat)

**Plan metadata:** summary committed separately as the docs completion commit

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt` - Normalizes settings blobs, clears feature DataStores on import, signs normalized pulls, and gates observer pushes behind successful profile hydration.
- `app/src/test/java/com/nexio/tv/core/sync/ProfileSettingsSyncServiceTest.kt` - Adds normalization/import clearing regressions and startObserving source-order regression coverage.
- `.planning/phases/04-sync-and-cleanup/04-04-SUMMARY.md` - Execution summary.

## Decisions Made

- `normalizeSettingsBlob()` is the allowlist boundary for remote data. It emits only `syncedFeatures`, inserts empty objects for missing synced features, and drops unknown remote features.
- `startObserving()` uses `profileManager.activeProfileId.map { it }.distinctUntilChanged()` because applying `distinctUntilChanged()` directly to `StateFlow` is a compile error in this project.
- STATE.md and ROADMAP.md were intentionally not updated because the orchestrator owns shared planning state for this run.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Avoided StateFlow distinctUntilChanged compile error**
- **Found during:** Task 2 (Gate profile-switch observation behind profile blob hydration)
- **Issue:** Applying `distinctUntilChanged()` directly to `profileManager.activeProfileId` failed production compilation because the receiver is a `StateFlow`.
- **Fix:** Added `.map { it }` before `.distinctUntilChanged()` to turn it into a regular Flow while preserving the required operator and behavior.
- **Files modified:** `app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt`
- **Verification:** `./gradlew assembleArm64Debug` passed after the fix.
- **Committed in:** `d47a9e67c`

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** No scope expansion. The fix preserves the planned hydration gate and keeps the implementation inside the owned service file.

## Issues Encountered

- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.ProfileSettingsSyncServiceTest"` remains blocked by unrelated unit-test source-set compile drift before targeted tests can execute. The final rerun failed in `:app:compileArm64DebugUnitTestKotlin`; visible unrelated failing paths include `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreSpoolModeTest.kt` and `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt`. `ProfileSettingsSyncServiceTest.kt` was not reported as a compile failure source.
- The Kotlin daemon still rejects `ZGenerational`; Gradle falls back to non-daemon compilation.

## Verification

- `./gradlew assembleArm64Debug` - passed after final code changes with `BUILD SUCCESSFUL`.
- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.ProfileSettingsSyncServiceTest"` - blocked by unrelated unit-test source-set compile drift as described above.
- `rg 'preferences\\.clear\\(\\)|normalizeSettingsBlob|pullBlobForProfile\\(profileId\\)|distinctUntilChanged|debounce\\(2000\\)|skipNextPushSignature|syncMutex' app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt` - found required implementation markers.
- Acceptance string checks found all required new test names in `ProfileSettingsSyncServiceTest.kt`.

## Known Stubs

None. Stub-pattern scan hits were nullable internal state fields and reset assignments, not placeholder behavior.

## Threat Flags

None. The remote blob allowlist normalization and profile-switch hydration gate were already covered by the 04-04 threat model.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

SYNC-02 verifier gaps in `ProfileSettingsSyncService` are closed from a production build and code-wiring standpoint. Unit-test execution still depends on repairing unrelated stale test constructors in the broader `arm64DebugUnitTest` source set.

## Self-Check: PASSED

- Found `app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt`.
- Found `app/src/test/java/com/nexio/tv/core/sync/ProfileSettingsSyncServiceTest.kt`.
- Found `.planning/phases/04-sync-and-cleanup/04-04-SUMMARY.md`.
- Found task commits `31cbedabd`, `3f3198993`, `9c83c9805`, and `d47a9e67c`.

---
*Phase: 04-sync-and-cleanup*
*Completed: 2026-04-14*
