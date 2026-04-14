---
phase: 05-nexio-web-integration
plan: 00
subsystem: testing
tags: [android, kotlin, junit, profile-sync, nexio-web]

requires:
  - phase: 04-sync-and-cleanup
    provides: Phase 4 profile settings blob RPC names and sync service patterns
provides:
  - WEB-01 profile CRUD sync contract scaffold
  - WEB-02 per-profile auth token contract scaffold with JSON payload shape
  - WEB-03 catalog settings blob contract scaffold
  - WEB-04 formatter settings blob contract scaffold
  - WEB-05 avatar URL and color fallback contract scaffold
affects: [phase-05-nexio-web-integration, android-profile-sync, nexio-web-validation]

tech-stack:
  added: []
  patterns:
    - JVM/Robolectric unit test scaffolds with package-local AndroidJUnit4 typealias
    - Contract lists that anchor downstream implementation plans to resolved field and RPC names

key-files:
  created:
    - app/src/test/java/com/nexio/tv/sync/ProfileSyncServiceTest.kt
    - app/src/test/java/com/nexio/tv/sync/ProfileAuthSyncTest.kt
    - app/src/test/java/com/nexio/tv/sync/ProfileCatalogSyncTest.kt
    - app/src/test/java/com/nexio/tv/sync/ProfileFormatterSyncTest.kt
    - app/src/test/java/com/nexio/tv/profile/ProfileAvatarTest.kt
  modified: []

key-decisions:
  - "Defined the Robolectric AndroidJUnit4 typealias once per new test package to avoid Kotlin top-level redeclarations."
  - "Kept token fixtures synthetic and used kotlinx.serialization JsonElement/JsonObject values to prevent a Map<String, String> auth payload contract."

patterns-established:
  - "Wave 0 validation anchors use executable JUnit tests before feature implementation plans expand them."
  - "Profile settings web contracts reference the Phase 4 blob RPCs: sync_pull_profile_settings_blob and sync_push_profile_settings_blob."

requirements-completed: [WEB-01, WEB-02, WEB-03, WEB-04, WEB-05]

duration: 4 min
completed: 2026-04-14
---

# Phase 05 Plan 00: Wave 0 Web Integration Test Scaffolds Summary

**Executable Android unit-test anchors for WEB-01 through WEB-05 profile sync, auth, catalog, formatter, and avatar contracts**

## Performance

- **Duration:** 4 min
- **Started:** 2026-04-14T17:59:14Z
- **Completed:** 2026-04-14T18:02:49Z
- **Tasks:** 3
- **Files modified:** 5

## Accomplishments

- Added five Phase 5 Wave 0 test files mapped one-to-one to WEB-01 through WEB-05.
- Anchored profile CRUD and per-profile auth sync to the expected RPC/table/key names.
- Anchored catalog and formatter web settings to the resolved Phase 4 blob RPC names.
- Documented avatar URL, cache invalidation, public bucket, and nullable URL fallback requirements.

## Task Commits

Each task was committed atomically:

1. **Task 1: Create WEB-01 and WEB-02 sync contract test scaffolds** - `fe38079bc` (test)
2. **Task 2: Create WEB-03 and WEB-04 settings sync contract test scaffolds** - `0ec1b3af1` (test)
3. **Task 3: Create WEB-05 avatar contract test scaffold** - `f6691f452` (test)

**Plan metadata:** committed separately in the final docs commit

## Files Created/Modified

- `app/src/test/java/com/nexio/tv/sync/ProfileSyncServiceTest.kt` - WEB-01 scaffold for profile CRUD sync contract names.
- `app/src/test/java/com/nexio/tv/sync/ProfileAuthSyncTest.kt` - WEB-02 scaffold for auth token keys and JSON token payload shape.
- `app/src/test/java/com/nexio/tv/sync/ProfileCatalogSyncTest.kt` - WEB-03 scaffold for catalog settings blob RPCs and keys.
- `app/src/test/java/com/nexio/tv/sync/ProfileFormatterSyncTest.kt` - WEB-04 scaffold for formatter settings blob RPCs and keys.
- `app/src/test/java/com/nexio/tv/profile/ProfileAvatarTest.kt` - WEB-05 scaffold for avatar URL, bucket, invalidation token, and nullable fallback contract.

## Decisions Made

- Used one package-local `AndroidJUnit4` Robolectric typealias in `com.nexio.tv.sync` and one in `com.nexio.tv.profile`, matching the existing unit-test style without adding dependencies.
- Used only synthetic token strings in WEB-02 fixtures, preserving the information-disclosure mitigation in the plan threat model.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- The targeted Gradle commands for all three tasks reached `:app:compileArm64DebugUnitTestKotlin` and failed before executing the new tests because existing unrelated unit tests still call stale constructors or fake types. Examples include `PlayerSettingsDataStoreTest`, `PlayerSettingsDataStoreSpoolModeTest`, `SearchHistoryDataStoreTest`, `ThemeDataStoreProfileTest`, `SearchViewModelHistoryTest`, `CatalogSelectionPersistenceTest`, `SimklViewModelTest`, and `TraktViewModelPriorityHydrationTest`.
- The Kotlin daemon also rejects the local JVM option `ZGenerational` and falls back to non-daemon compilation before surfacing those unrelated test compile errors.
- File-level acceptance checks passed for every required WEB-01 through WEB-05 contract string and for the WEB-02 `JsonElement`/`JsonObject` payload shape.

## User Setup Required

None - no external service configuration required.

## Known Stubs

None - these are executable contract scaffolds by design, and no placeholder data source blocks downstream validation.

## Next Phase Readiness

Downstream Phase 5 plans can reference concrete Gradle test classes for WEB-01 through WEB-05. The broader unit-test compile failures remain outside this plan's owned file scope and should be handled by the phase or milestone that owns those stale tests.

## Self-Check: PASSED

- Found `app/src/test/java/com/nexio/tv/sync/ProfileSyncServiceTest.kt`
- Found `app/src/test/java/com/nexio/tv/sync/ProfileAuthSyncTest.kt`
- Found `app/src/test/java/com/nexio/tv/sync/ProfileCatalogSyncTest.kt`
- Found `app/src/test/java/com/nexio/tv/sync/ProfileFormatterSyncTest.kt`
- Found `app/src/test/java/com/nexio/tv/profile/ProfileAvatarTest.kt`
- Found `.planning/phases/05-nexio-web-integration/05-00-SUMMARY.md`
- Found commits `fe38079bc`, `0ec1b3af1`, and `f6691f452`

---
*Phase: 05-nexio-web-integration*
*Completed: 2026-04-14*
