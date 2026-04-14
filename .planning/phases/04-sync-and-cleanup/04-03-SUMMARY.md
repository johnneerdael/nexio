---
phase: 04-sync-and-cleanup
plan: 03
subsystem: sync
tags: [android, kotlin, supabase, profiles, settings-ui]

requires:
  - phase: 04-sync-and-cleanup
    plan: 01
    provides: ProfileSyncService and profilePrefsName for metadata sync and per-profile SharedPreferences naming
  - phase: 04-sync-and-cleanup
    plan: 02
    provides: ProfileSettingsSyncService v8 per-profile settings blob sync
provides:
  - Startup profile metadata and settings blob pull before v7 startup push gate opens
  - v7 account settings cleanup for per-profile settings paths
  - Profile deletion cleanup for DataStore files, SharedPreferences files, and best-effort remote cleanup retry
  - Settings Sync Now action with transient status feedback
  - Settings delete confirmation dialog using NexioDialog
affects: [phase-04-sync-and-cleanup, profile-sync, profile-cleanup, settings-ui]

tech-stack:
  added: []
  patterns:
    - Startup pull sequencing before opening push gate
    - Best-effort Supabase RPC cleanup with bounded retry persistence
    - Compose TV confirmation dialog with destructive action and safe initial focus

key-files:
  created:
    - .planning/phases/04-sync-and-cleanup/04-03-SUMMARY.md
  modified:
    - app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt
    - app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt
    - app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt
    - app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt
    - app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsViewModel.kt
    - app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt

key-decisions:
  - "Do not add profile creation UI as part of this plan; the non-primary profile device flow remains a UAT follow-up because the app currently lacks a user-accessible way to add/select non-primary profiles."
  - "Keep unit-test source-set failures out of scope because they occur in unrelated test files before ProfileManagerTest can execute."

patterns-established:
  - "StartupSyncService retries pending profile cleanup, pulls profile metadata, pulls the active profile settings blob, then starts the v8 observer before v7 sync continues."
  - "Profile deletion clears per-profile SharedPreferences cache before deleting each XML file and persists failed remote cleanup IDs in a bounded pending set."

requirements-completed: [SYNC-01, SYNC-02, SYNC-03]

duration: 27 min
completed: 2026-04-14
---

# Phase 04 Plan 03: Sync Lifecycle Integration Summary

**Startup profile pulls, v7 per-profile cleanup, full profile deletion cleanup, and Settings sync/delete controls**

## Performance

- **Duration:** 27 min
- **Started:** 2026-04-14T13:35:05Z
- **Completed:** 2026-04-14T14:01:42Z
- **Tasks:** 4
- **Files modified:** 7

## Accomplishments

- Wired `StartupSyncService` to retry pending profile remote cleanup, pull profile metadata, pull the active profile settings blob, and start the v8 settings observer before the existing v7 startup sync continues.
- Removed per-profile settings from v7 observation/payload handling while keeping Trakt and Simkl auth state in the existing v7 secrets path.
- Extended `ProfileManager.deleteProfile` cleanup to remove DataStore files, clear and delete the 7 per-profile SharedPreferences files, and attempt `sync_delete_profile` remote cleanup with bounded retry persistence.
- Added Settings UI controls for Sync Now and profile deletion, including transient sync status, `NexioDialog` confirmation copy, safe "Keep Profile" initial focus, and destructive "Delete Profile" styling.
- Recorded the checkpoint validation limitation: non-primary profile deletion on-device cannot be fully UAT-verified yet because there is not currently a user-accessible way to add/select non-primary profiles in the app.

## Task Commits

1. **Task 1: Startup profile pull + v7 cleanup** - `c7784f376` (feat)
2. **Task 2: Profile deletion cleanup + tests** - `531b6b3f1` (feat)
3. **Task 3: Settings Sync Now + delete dialog** - `828c7341a` (feat)
4. **Task 4: Human verification checkpoint closure** - documented in this summary after the user checkpoint response.

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt` - Startup profile metadata pull, active settings blob pull, observer sequencing, and pending remote cleanup retry.
- `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` - v7 account sync cleanup for per-profile settings paths while retaining auth sync.
- `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt` - Per-profile SharedPreferences deletion and best-effort remote profile cleanup retry.
- `app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt` - Profile deletion coverage for SharedPreferences cleanup and remote failure local cleanup behavior.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsViewModel.kt` - Sync Now status/action state and delete dialog state/actions.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt` - Sync Now row and delete confirmation dialog.
- `.planning/phases/04-sync-and-cleanup/04-03-SUMMARY.md` - Completion summary and UAT limitation record.

## Decisions Made

- Do not expand this plan to add profile creation UI. The user checkpoint response confirmed there is likely no app flow yet to add non-primary profiles, so the non-primary deletion device flow is a validation limitation rather than implementation scope for 04-03.
- Do not edit unrelated unit-test source-set failures. `testArm64DebugUnitTest` fails before running the targeted plan tests due constructor drift in unrelated tests such as `PlayerSettingsDataStore*`, `SearchHistoryDataStore*`, `ThemeDataStoreProfileTest`, `SimklViewModelTest`, and `TraktViewModelPriorityHydrationTest`.
- Do not update `.planning/STATE.md` or `.planning/ROADMAP.md` in this continuation because the handoff explicitly excluded them from the write scope.

## Deviations from Plan

None - implementation stayed within the planned files and behavior. The remaining gaps are verification limitations, not implementation scope changes.

## Issues Encountered

- `./gradlew testArm64DebugUnitTest` fails in `:app:compileArm64DebugUnitTestKotlin` before tests execute. The fresh rerun reported the Kotlin daemon `ZGenerational` fallback problem and unrelated source-set compile errors in tests outside 04-03 ownership, including stale constructor calls in `PlayerSettingsDataStoreTest`, `PlayerSettingsDataStoreSpoolModeTest`, `SearchHistoryDataStoreTest`, `ThemeDataStoreProfileTest`, `SearchViewModelHistoryTest`, `CatalogSelectionPersistenceTest`, `SimklViewModelTest`, and `TraktViewModelPriorityHydrationTest`.
- Device UAT for deleting a non-primary profile remains blocked. The user reported there is not currently a way to add non-primary profiles, so the primary-profile static behavior and code/build checks are verified, but the non-primary delete profile device flow needs follow-up when profile creation/selection is available.

## Verification

- `git log --oneline --all --grep=04-03` - found `c7784f376`, `531b6b3f1`, and `828c7341a`.
- Grep checks passed for startup `pullFromRemote`, settings blob `pullBlobForProfile`, `startObserving`, pending cleanup retry, v7 per-profile settings comments, auth observers, profile SharedPreferences cleanup, remote deletion retry, Sync Now wiring, and `NexioDialog` delete confirmation wiring.
- `./gradlew assembleArm64Debug` - passed on 2026-04-14T14:01Z with `BUILD SUCCESSFUL`.
- `./gradlew testArm64DebugUnitTest` - failed before executing tests due the unrelated source-set compile errors listed above.

## Validation Limitations

- Non-primary profile delete flow on-device: blocked until the app exposes a way to add/select non-primary profiles.
- ProfileManager deletion tests: present in source, but not executable until unrelated unit-test source-set compile failures are repaired.

## Known Stubs

None. Stub-pattern scan hits were nullable/default state assignments and existing optional UI defaults, not unimplemented plan behavior.

## Threat Flags

None. The new Supabase RPC cleanup surface and startup pull sequencing were already covered by the plan threat model.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

The 04-03 implementation is ready for downstream work from a production build and code-wiring standpoint. Remaining follow-up is UAT availability for non-primary profile deletion once profile creation/selection exists, plus separate cleanup of unrelated unit-test constructor drift so the source set can execute targeted tests.

## Self-Check: PASSED

- Summary created at `.planning/phases/04-sync-and-cleanup/04-03-SUMMARY.md`.
- Task commits found: `c7784f376`, `531b6b3f1`, and `828c7341a`.
- Modified plan files referenced above exist.

---
*Phase: 04-sync-and-cleanup*
*Completed: 2026-04-14*
