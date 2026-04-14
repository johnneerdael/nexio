---
phase: 01-foundation
plan: "01"
subsystem: domain-model, data-local
tags: [user-profile, datastore, profile-isolation, kotlin, hilt]
dependency_graph:
  requires: []
  provides: [UserProfile, ProfileDataStoreFactory]
  affects: [ProfileDataStore, ProfileManager, ProfileModule]
tech_stack:
  added: []
  patterns: [ConcurrentHashMap-based DataStore factory, per-profile file naming convention]
key_files:
  created:
    - app/src/main/java/com/nexio/tv/data/local/ProfileDataStoreFactory.kt
    - app/src/test/java/com/nexio/tv/data/local/ProfileDataStoreFactoryTest.kt
    - app/src/test/java/com/nexio/tv/domain/model/UserProfileTest.kt
  modified:
    - app/src/main/java/com/nexio/tv/domain/model/UserProfile.kt
decisions:
  - "Used Robolectric ApplicationContext for ProfileDataStoreFactory tests — preferencesDataStoreFile is an Android extension function not available in pure JVM unit tests"
  - "media symlink workaround: worktree lacked submodule; created local.properties with USE_MEDIA3_SOURCE=true and symlinked main repo media dir for build — symlink removed post-test, media restored to HEAD"
metrics:
  duration: "~11 minutes"
  completed: "2026-04-14"
  tasks_completed: 1
  files_created: 3
  files_modified: 1
---

# Phase 01 Plan 01: UserProfile Extension and ProfileDataStoreFactory Summary

**One-liner:** Extended UserProfile with avatarId/pinEnabled fields and created ConcurrentHashMap-based ProfileDataStoreFactory with per-profile DataStore file isolation (bare names for Profile 1, _pN suffix for profiles 2-4).

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Extend UserProfile model and create ProfileDataStoreFactory | 9b5ab49e6 | UserProfile.kt (modified), ProfileDataStoreFactory.kt (created), ProfileDataStoreFactoryTest.kt (created), UserProfileTest.kt (created) |

## What Was Built

### UserProfile.kt (extended)

Added two new fields with defaults after `usesPrimaryAddons`, maintaining full backward compatibility with all existing call sites:

```kotlin
val avatarId: String? = null       // D-02: Supabase avatar catalog ref
val pinEnabled: Boolean = false    // D-03: server-side PIN lock state
```

`isPrimary` computed property preserved. `usesPrimaryPlugins` not added (per plan constraint).

### ProfileDataStoreFactory.kt (new)

Verbatim port from NuvioTV with package changed to `com.nexio.tv.data.local`. Provides:

- `get(profileId, featureName)` — returns cached DataStore; Profile 1 uses bare filename, profiles 2-4 use `featureName_pN`
- `clearProfile(profileId)` — marks deleted, clears and evicts cache entries; Profile 1 protected
- `isProfileDeleted(profileId)` — tracks cleared profile IDs
- `markProfileCreated(profileId)` — removes from deleted set for slot reuse

## Test Results

All 13 tests pass (`./gradlew testArm64DebugUnitTest`):

- `UserProfileTest`: 7 tests — backward compat, isPrimary, avatarId/pinEnabled defaults and explicit setting, usesPrimaryAddons default
- `ProfileDataStoreFactoryTest`: 6 tests — caching, profile isolation, clearProfile tracking, markProfileCreated reset, Profile 1 no-op guard, fresh instance after clear

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Worktree missing media submodule causing TextRenderer compile error**
- **Found during:** Task 1 (RED test run)
- **Issue:** The worktree at `.claude/worktrees/agent-a216d593` had no `media` submodule directory and no `local.properties`. With `USE_MEDIA3_SOURCE` unset (defaulting false), the build tried to use published Media3 artifacts that lack the 4-argument `TextRenderer` constructor used in `PlayerRuntimeControllerInitialization.kt`. Result: `compileArm64DebugKotlin` failed before unit tests could compile.
- **Fix:** Created `local.properties` with `USE_MEDIA3_SOURCE=true` and temporarily symlinked `~/Scripts/nexio/media` into the worktree. After tests passed, symlink was removed and `media` submodule path restored to HEAD via `git checkout -- media`.
- **Files modified:** `local.properties` (worktree-only, gitignored), temporary `media` symlink (removed)
- **Commit:** not committed (worktree setup only)

**2. [Rule 1 - Bug] Test using mockk for `preferencesDataStoreFile` extension failed**
- **Found during:** Task 1 (first GREEN test run)
- **Issue:** `preferencesDataStoreFile` is an Android extension function on `Context` not resolvable in the JVM unit test classpath. MockK could not mock it.
- **Fix:** Switched `ProfileDataStoreFactoryTest` from MockK + manual Context mock to `@RunWith(RobolectricTestRunner::class)` + `ApplicationProvider.getApplicationContext()`. Robolectric is already a test dependency (`org.robolectric:robolectric:4.13`).
- **Files modified:** `ProfileDataStoreFactoryTest.kt`
- **Commit:** 9b5ab49e6

## Known Stubs

None. Both files are fully implemented with no placeholder values.

## Threat Flags

None. All new code is local DataStore infrastructure — no network endpoints, no auth paths, no IPC. Consistent with the plan's threat model (T-01-01, T-01-02 both accepted).

## Self-Check: PASSED

- `app/src/main/java/com/nexio/tv/domain/model/UserProfile.kt` — FOUND, contains `avatarId` and `pinEnabled`
- `app/src/main/java/com/nexio/tv/data/local/ProfileDataStoreFactory.kt` — FOUND
- `app/src/test/java/com/nexio/tv/data/local/ProfileDataStoreFactoryTest.kt` — FOUND, 6 tests
- `app/src/test/java/com/nexio/tv/domain/model/UserProfileTest.kt` — FOUND, 7 tests
- Commit `9b5ab49e6` — FOUND in git log
