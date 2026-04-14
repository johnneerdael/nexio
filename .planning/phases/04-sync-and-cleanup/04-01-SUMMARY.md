---
phase: 04-sync-and-cleanup
plan: 01
subsystem: sync
tags:
  - android
  - kotlin
  - supabase
  - profiles
  - sharedpreferences
requires: []
provides:
  - ProfileSyncService
  - profilePrefsName
  - per-profile snapshot SharedPreferences naming
affects:
  - profile metadata sync
  - Trakt snapshot isolation
  - Simkl snapshot isolation
tech_stack:
  added: []
  patterns:
    - Hilt @Inject services
    - Supabase postgrest.rpc
    - profilePrefsName suffix convention
key_files:
  created:
    - app/src/main/java/com/nexio/tv/core/sync/ProfilePrefsName.kt
    - app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt
    - app/src/test/java/com/nexio/tv/core/sync/ProfileSyncServiceTest.kt
  modified:
    - app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt
    - app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt
    - app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt
    - app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt
    - app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStore.kt
    - app/src/main/java/com/nexio/tv/data/local/SimklProgressSyncStateStore.kt
    - app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt
    - app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStore.kt
    - app/src/test/java/com/nexio/tv/data/local/ProfilePrefsNameTest.kt
    - app/src/test/java/com/nexio/tv/data/local/TraktLibrarySnapshotStoreTest.kt
decisions:
  - Used AuthManager.refreshSessionIfJwtExpired for JWT retry because that is the current repo pattern.
  - Preserved old profile-1 constructors in migrated stores so existing tests can instantiate stores without Hilt.
metrics:
  duration: "13m 46s"
  started: "2026-04-14T13:11:33Z"
  completed: "2026-04-14T13:25:19Z"
  tasks: 2
  files_modified: 13
---

# Phase 04 Plan 01: Profile Metadata Sync and Snapshot Store Scoping Summary

**Profile metadata sync via Supabase RPCs plus per-profile SharedPreferences names for Trakt and Simkl snapshot stores**

## Performance

- **Duration:** 13m 46s
- **Started:** 2026-04-14T13:11:33Z
- **Completed:** 2026-04-14T13:25:19Z
- **Tasks:** 2
- **Files modified:** 13

## Accomplishments

- Added `profilePrefsName()` with profile 1 using the bare base name and profiles 2-4 using `_p{id}` suffixes.
- Added `ProfileSyncService` with `sync_push_profiles` and `sync_pull_profiles` RPC calls, JWT refresh retry, and atomic local replacement on non-empty pulls.
- Added `avatar_id` and `pin_enabled` fields to `SupabaseProfile`.
- Migrated the 7 planned SharedPreferences snapshot stores to resolve names dynamically from the active profile at call time.
- Extended existing Trakt library snapshot store tests with profile naming assertions while preserving the existing snapshot serialization coverage.

## Task Commits

1. **Task 1 RED:** `601d79ca7` test(04-01): add failing profile sync tests
2. **Task 1 GREEN:** `61796d6c1` feat(04-01): implement profile metadata sync
3. **Task 2:** `c52f0da9d` feat(04-01): scope snapshot stores per profile

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/core/sync/ProfilePrefsName.kt` - Per-profile SharedPreferences naming helper.
- `app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt` - Supabase profile metadata push and pull service.
- `app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt` - Added profile avatar and PIN metadata fields.
- `app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt` - Dynamic per-profile SharedPreferences naming.
- `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt` - Dynamic per-profile SharedPreferences naming.
- `app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt` - Dynamic per-profile SharedPreferences naming.
- `app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStore.kt` - Dynamic per-profile SharedPreferences naming.
- `app/src/main/java/com/nexio/tv/data/local/SimklProgressSyncStateStore.kt` - Dynamic per-profile SharedPreferences naming.
- `app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt` - Dynamic per-profile SharedPreferences naming.
- `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStore.kt` - Dynamic per-profile SharedPreferences naming.
- `app/src/test/java/com/nexio/tv/data/local/ProfilePrefsNameTest.kt` - Naming helper coverage.
- `app/src/test/java/com/nexio/tv/data/local/TraktLibrarySnapshotStoreTest.kt` - Added per-profile naming coverage.
- `app/src/test/java/com/nexio/tv/core/sync/ProfileSyncServiceTest.kt` - Ignored skeleton for future RPC behavior tests.

## Decisions Made

- Used the current `AuthManager.refreshSessionIfJwtExpired()` pattern for JWT retry instead of the older `refreshSession()` wording in the plan.
- Added profile-1 compatibility constructors to migrated stores so existing non-Hilt tests can still instantiate them without touching out-of-scope test files.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Clamped pulled remote profile IDs**
- **Found during:** Task 1 (ProfileSyncService implementation)
- **Issue:** The threat model said profile IDs are clamped to 1-4, but `ProfileSyncService` writes directly through `ProfileDataStore.replaceAllProfiles()`.
- **Fix:** Filtered pulled `SupabaseProfile` rows to `profileIndex in 1..4` before replacement.
- **Files modified:** `app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt`
- **Verification:** `./gradlew assembleArm64Debug` passed.
- **Committed in:** `61796d6c1`

**2. [Rule 3 - Blocking] Preserved old store constructors for tests**
- **Found during:** Task 2 verification
- **Issue:** Existing unit tests instantiate snapshot stores directly with the old constructors. Injecting `ProfileManager` into the Hilt constructor made those tests fail compilation.
- **Fix:** Added non-Hilt compatibility constructors that resolve to profile 1 while production uses the injected `ProfileManager` path.
- **Files modified:** The 7 migrated snapshot store files.
- **Verification:** `./gradlew assembleArm64Debug` passed; targeted unit-test task no longer reported constructor errors for the owned stores.
- **Committed in:** `c52f0da9d`

**Total deviations:** 2 auto-fixed (1 missing critical, 1 blocking)
**Impact on plan:** Both changes preserve the planned behavior and keep the work scoped to the owned files.

## Issues Encountered

- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfilePrefsNameTest"` and `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.TraktLibrarySnapshotStoreTest"` could not complete because the unit-test source set has unrelated compile errors in other phase work, including `PlayerSettingsDataStore*`, `SearchHistoryDataStoreTest`, `ThemeDataStoreProfileTest`, and settings view-model tests. The targeted tests were not executed.
- Kotlin daemon startup repeatedly failed with `Unrecognized VM option 'ZGenerational'`; Gradle fell back to non-daemon compilation.

## Verification

- `./gradlew assembleArm64Debug` - passed.
- Grep checks for `avatar_id`, `pin_enabled`, `sync_push_profiles`, `sync_pull_profiles`, and `profilePrefsName` across all 7 stores - passed.
- Targeted unit test commands - blocked by unrelated unit-test compilation failures listed above.

## Known Stubs

- `app/src/test/java/com/nexio/tv/core/sync/ProfileSyncServiceTest.kt` is an ignored skeleton for future RPC behavior tests. This was requested by the plan and does not block the implemented profile sync service from compiling.

## Threat Flags

None. The new Supabase RPC surface is covered by the plan threat model.

## User Setup Required

None.

## Next Phase Readiness

Plan 04-01 implementation is ready for downstream work. The test suite needs separate cleanup for unrelated constructor drift before the targeted unit tests can execute.

## Self-Check: PASSED

- All created and modified plan 04-01 files exist.
- Task commits found: `601d79ca7`, `61796d6c1`, `c52f0da9d`.
- Summary exists at `.planning/phases/04-sync-and-cleanup/04-01-SUMMARY.md`.

---
*Phase: 04-sync-and-cleanup*
*Completed: 2026-04-14*
