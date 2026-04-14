---
phase: 05-nexio-web-integration
plan: 08
subsystem: android
tags: [android, kotlin, datastore, profiles, avatars, web-sync]

requires:
  - phase: 05-nexio-web-integration
    plan: 05
    provides: nexio-web profile photo upload with cache-busted avatar_url
  - phase: 05-nexio-web-integration
    plan: 06
    provides: Android Supabase avatar_url mapping and TV avatar UI wiring
provides:
  - WEB-05 avatarUrl persistence through ProfileDataStore
  - ProfileManager.profiles regression for web-uploaded avatar URL emission
  - ProfileAvatar WEB-05 persistence-flow coverage replacing contract-only proof
affects: [android-profile-persistence, android-profile-manager, android-avatar-ui]

tech-stack:
  added: []
  patterns:
    - Nullable ProfileJson fields preserve legacy DataStore JSON compatibility
    - ProfileManager tests use a test-local harness to share the backing ProfileDataStoreImpl without changing production APIs

key-files:
  created: []
  modified:
    - app/src/main/java/com/nexio/tv/data/local/ProfileDataStore.kt
    - app/src/test/java/com/nexio/tv/data/local/ProfileDataStoreTest.kt
    - app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt
    - app/src/test/java/com/nexio/tv/profile/ProfileAvatarTest.kt

key-decisions:
  - "Kept avatar_url nullable with a default in ProfileJson so legacy persisted profile JSON remains readable."
  - "Kept ProfileManager production APIs unchanged; tests expose the backing ProfileDataStoreImpl only through a test-local harness."

patterns-established:
  - "WEB-05 regressions now exercise replaceAllProfiles persistence with cache-busted ?t= avatar URLs."

requirements-completed: [WEB-05]

duration: 5 min
completed: 2026-04-14
---

# Phase 05 Plan 08: Avatar Persistence Gap Closure Summary

**Web-uploaded profile avatar URLs now survive Android profile persistence and reach ProfileManager emissions for TV avatar surfaces**

## Performance

- **Duration:** 5 min
- **Started:** 2026-04-14T21:12:57Z
- **Completed:** 2026-04-14T21:18:04Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments

- Added `avatar_url` to `ProfileDataStore.ProfileJson` and mapped it in both `toDomain()` and `fromDomain()`.
- Expanded `ProfileDataStoreTest` to preserve a cache-busted web avatar URL through both round-trip persistence and `replaceAllProfiles`.
- Added a `ProfileManagerTest` regression proving `manager.profiles` emits profile 2 with the persisted `avatarUrl`.
- Upgraded `ProfileAvatarTest` from contract-only checks to a real `ProfileDataStoreImpl.replaceAllProfiles` persistence-flow assertion.

## Task Commits

Each task was committed atomically:

1. **Task 1 RED: Add failing avatarUrl persistence tests** - `d4cecb686` (test)
2. **Task 1 GREEN: Preserve profile avatarUrl in DataStore** - `c70aa60c8` (fix)
3. **Task 2: Cover ProfileManager and avatar UI persistence flow** - `35c256bac` (test)

**Plan metadata:** committed separately in the final docs commit

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/data/local/ProfileDataStore.kt` - Persists `UserProfile.avatarUrl` as nullable `avatar_url`.
- `app/src/test/java/com/nexio/tv/data/local/ProfileDataStoreTest.kt` - Covers avatar URL round-trip and web-sync replacement persistence.
- `app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt` - Adds a test-local manager/store harness and asserts `ProfileManager.profiles` emits the URL.
- `app/src/test/java/com/nexio/tv/profile/ProfileAvatarTest.kt` - Adds WEB-05 persistence-flow coverage using `ProfileDataStoreImpl`.

## Decisions Made

- Kept `avatar_url` nullable and defaulted, matching the threat mitigation for malformed or legacy profile JSON.
- Did not add any production `ProfileManager` testing API; the backing store exposure is test-local only.
- Left existing avatar UI files untouched because they already pass `profile.avatarUrl` into `ProfileAvatarCircle`.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- Targeted unit-test commands were blocked before test execution by pre-existing `:app:compileArm64DebugUnitTestKotlin` failures in unrelated tests, including stale `PlayerSettingsDataStore*`, `HomeCatalogSnapshotStoreTest`, `SearchHistoryDataStoreTest`, `ThemeDataStoreProfileTest`, `SearchViewModelHistoryTest`, `CatalogSelectionPersistenceTest`, `SimklViewModelTest`, and `TraktViewModelPriorityHydrationTest` constructor/mocking errors.
- The local Kotlin daemon still rejects `ZGenerational` and falls back to non-daemon compilation; this is the same environment behavior recorded in prior Phase 5 summaries.

## Verification

- Blocked by unrelated unit-test source-set compile failures: `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfileDataStoreTest" -x lint`.
- Blocked by unrelated unit-test source-set compile failures: `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.profile.ProfileManagerTest" --tests "com.nexio.tv.profile.ProfileAvatarTest" -x lint`.
- Passed: `./gradlew compileArm64DebugKotlin -x lint`.
- Passed: `./gradlew assembleArm64Debug`.
- Passed: static WEB-05 trace for `avatar_url`, `avatarUrl`, `replaceAllProfiles`, and existing `ProfileAvatarCircle` consumers.

## User Setup Required

None.

## Known Stubs

None. The nullable `avatarUrl` defaults are compatibility behavior, not placeholder data sources.

## Threat Flags

None. This plan preserves an already-approved public avatar URL through local persistence and does not add network endpoints, auth paths, file access patterns, or schema changes.

## Next Phase Readiness

The WEB-05 Android persistence gap is closed. Remaining Phase 5 verification can focus on live nexio-web upload plus TV sync UAT, while WEB-03 and WEB-04 settings-shape gaps remain separate.

## Self-Check: PASSED

- Found `.planning/phases/05-nexio-web-integration/05-08-SUMMARY.md`
- Found `app/src/main/java/com/nexio/tv/data/local/ProfileDataStore.kt`
- Found `app/src/test/java/com/nexio/tv/data/local/ProfileDataStoreTest.kt`
- Found `app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt`
- Found `app/src/test/java/com/nexio/tv/profile/ProfileAvatarTest.kt`
- Found commits `d4cecb686`, `c70aa60c8`, and `35c256bac`
- Verified unrelated dirty/untracked files remain outside plan commits

---
*Phase: 05-nexio-web-integration*
*Completed: 2026-04-14*
