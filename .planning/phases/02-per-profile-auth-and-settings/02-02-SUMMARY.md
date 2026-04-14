---
phase: 02-per-profile-auth-and-settings
plan: 02
subsystem: auth
tags: [datastore, profile, simkl, kotlin, coroutines, hilt]

# Dependency graph
requires:
  - phase: 02-per-profile-auth-and-settings
    plan: 01
    provides: "ProfileDataStoreFactory, ProfileManager, FakeProfileDataStoreFactory, FakeProfileManager test helpers, TraktAuthDataStore/TraktSettingsDataStore migration pattern"
provides:
  - SimklAuthDataStore migrated to per-profile factory pattern with flatMapLatest reactive switching
  - SimklSettingsDataStore migrated to per-profile factory pattern with flatMapLatest reactive switching
  - SimklAuthDataStoreProfileTest proving profile token isolation (2 tests)
  - SimklSettingsDataStoreProfileTest proving profile catalog prefs isolation (1 test)
affects:
  - SimklViewModel
  - SimklAuthService
  - SimklLibraryService
  - SimklTrackingRemoteDataSource
  - SimklProgressService
  - SimklDiscoveryService
  - AccountSettingsSyncService
  - AccountConfigSyncContract
  - TrackingProviderStateRepository
  - TrackingProviderStateService
  - IdleScreensaverRepository

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "flatMapLatest(activeProfileId) for profile-reactive DataStore flows"
    - "store(profileId) helper resolving DataStore from factory at call time"
    - "FEATURE constant for DataStore file naming per service"
    - "@OptIn(ExperimentalCoroutinesApi::class) on DataStore class"

key-files:
  created:
    - app/src/test/java/com/nexio/tv/data/local/SimklAuthDataStoreProfileTest.kt
    - app/src/test/java/com/nexio/tv/data/local/SimklSettingsDataStoreProfileTest.kt
  modified:
    - app/src/main/java/com/nexio/tv/data/local/SimklAuthDataStore.kt
    - app/src/main/java/com/nexio/tv/data/local/SimklSettingsDataStore.kt

key-decisions:
  - "Identical factory/flatMapLatest transform as TraktAuthDataStore applied to Simkl stores"
  - "FEATURE = 'simkl_auth_store' and 'simkl_settings' preserve existing DataStore file names for zero-migration of existing data"

patterns-established:
  - "All 4 auth/settings DataStores (Trakt + Simkl) now use ProfileDataStoreFactory + ProfileManager — pattern is complete and consistent"

requirements-completed: [AUTH-03, AUTH-04]

# Metrics
duration: 90min
completed: 2026-04-14
---

# Phase 2 Plan 02: SimklAuthDataStore and SimklSettingsDataStore Profile Migration Summary

**SimklAuthDataStore and SimklSettingsDataStore migrated from singleton Context delegates to ProfileDataStoreFactory + flatMapLatest, completing per-profile auth isolation for all 4 tracking provider DataStores**

## Performance

- **Duration:** ~90 min
- **Started:** 2026-04-14T09:45:00Z
- **Completed:** 2026-04-14T10:05:00Z
- **Tasks:** 2
- **Files modified:** 4 (2 production, 2 test)

## Accomplishments

- SimklAuthDataStore: removed `preferencesDataStore` delegate, injected `ProfileDataStoreFactory` + `ProfileManager`, added `flatMapLatest` on `activeProfileId`, replaced all 6 `context.simklAuthDataStore.edit` calls with `store().edit`
- SimklSettingsDataStore: same transform — removed delegate and `private val dataStore = context.simklSettingsDataStore`, wrapped `catalogPreferences` in `flatMapLatest`, write methods use `store().edit`
- 3 profile isolation tests pass: 2 for auth (token isolation, clearAuth scoping), 1 for settings (catalog prefs isolation)
- `assembleArm64Debug` BUILD SUCCESSFUL — all Simkl consumers (`SimklViewModel`, `SimklAuthService`, `SimklLibraryService`, `SimklTrackingRemoteDataSource`, `SimklProgressService`, `SimklDiscoveryService`, `AccountSettingsSyncService`, `AccountConfigSyncContract`, `TrackingProviderStateRepository`, `TrackingProviderStateService`, `IdleScreensaverRepository`) resolve new constructors

## Task Commits

Each task was committed atomically:

1. **Task 1: Migrate SimklAuthDataStore to factory pattern** - `0638bcb1b` (feat)
2. **Task 2: Migrate SimklSettingsDataStore and verify Simkl compilation** - `ac964c263` (feat)

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/data/local/SimklAuthDataStore.kt` - Migrated to ProfileDataStoreFactory + ProfileManager, flatMapLatest state flow, store().edit writes
- `app/src/main/java/com/nexio/tv/data/local/SimklSettingsDataStore.kt` - Migrated to ProfileDataStoreFactory + ProfileManager, flatMapLatest catalogPreferences flow
- `app/src/test/java/com/nexio/tv/data/local/SimklAuthDataStoreProfileTest.kt` - Profile token isolation tests (2 tests)
- `app/src/test/java/com/nexio/tv/data/local/SimklSettingsDataStoreProfileTest.kt` - Profile catalog preferences isolation test (1 test)

## Decisions Made

- FEATURE constant values `"simkl_auth_store"` and `"simkl_settings"` match the old `preferencesDataStore(name = ...)` values exactly, ensuring zero data migration for existing profile 1 users
- Test construction follows the exact same pattern as Plan 01's TraktAuthDataStoreProfileTest: real `ProfileDataStoreFactory(context)` with Robolectric, real `ProfileManager` constructed with temp `ProfileDataStoreImpl`

## Deviations from Plan

### Environment Setup (not a code deviation)

The worktree required bootstrapping before it could compile:
- `local.dev.properties` with `USE_MEDIA3_SOURCE=true` copied from main repo (worktrees don't inherit this untracked file)
- `media/` symlink created to the shared forked Media3 source directory (Gradle composite build required for compilation)
- Staged ASS/SSA player files from previous branch work restored to their correct target-commit versions

These were environment setup steps, not code deviations. No plan code was changed beyond the specified migration.

---

**Total deviations:** 0 code deviations — plan executed exactly as specified
**Impact on plan:** None

## Issues Encountered

- Worktree was missing `local.dev.properties` and `media/` symlink needed by the Gradle composite build (`USE_MEDIA3_SOURCE=true`). Without these, compilation failed due to player code referencing symbols only available in the forked Media3 source. Resolved by copying `local.dev.properties` and creating a symlink to the shared `media/` directory.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- All 4 auth/settings DataStores (TraktAuthDataStore, TraktSettingsDataStore, SimklAuthDataStore, SimklSettingsDataStore) are now per-profile reactive via `ProfileDataStoreFactory + flatMapLatest`
- Ready for per-profile settings DataStore migration (language, theme, player preferences, catalog order for general settings)
- Threat mitigations T-02-04, T-02-05, T-02-06 verified by profile isolation tests

---
*Phase: 02-per-profile-auth-and-settings*
*Completed: 2026-04-14*
