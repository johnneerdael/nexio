---
phase: 01-foundation
plan: 02
subsystem: profile-persistence
tags: [profile, datastore, gson, hilt, di, crud, stateflow]
dependency_graph:
  requires: [01-01]
  provides: [ProfileDataStore, ProfileManager, ProfileModule]
  affects: [phase-02-datastore-migrations, phase-03-profile-ui, phase-04-sync]
tech_stack:
  added:
    - Gson (via existing libs.gson dependency — no new library added)
    - ProfileDataStoreImpl base class for testable DataStore logic separation
  patterns:
    - Hilt @Singleton + @Inject constructor for all three new classes
    - DataStore<Preferences> delegate (preferencesDataStore) for profile_settings
    - ProfileDataStoreImpl / ProfileDataStore split: logic in open base, Hilt subclass for production
    - ProfileManager reads DataStore.profilesList.first() directly for mutation guards (avoids StateFlow cache lag)
    - Injectable CoroutineScope in ProfileManager for test determinism
key_files:
  created:
    - app/src/main/java/com/nexio/tv/data/local/ProfileDataStore.kt
    - app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt
    - app/src/main/java/com/nexio/tv/core/di/ProfileModule.kt
    - app/src/test/java/com/nexio/tv/data/local/ProfileDataStoreTest.kt
    - app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt
  modified: []
decisions:
  - "ProfileDataStoreImpl/ProfileDataStore split: open base class holds all logic, @Singleton subclass used by Hilt — avoids Context dependency in tests"
  - "ProfileManager reads dataStore.profilesList.first() for mutation guards instead of profiles.value StateFlow — eliminates cache-lag race conditions in both production and tests"
  - "ProfileManager accepts injectable CoroutineScope — production uses SupervisorJob+IO, tests use backgroundScope for deterministic virtual-time execution"
  - "ProfileJson uses @SerializedName annotations (Gson idiom), not @JsonClass (Moshi) — consistent with D-01 decision to use Gson"
  - "No usesPrimaryPlugins field in ProfileJson or ProfileManager — plugin concept removed per D-04"
metrics:
  duration: ~35 minutes
  completed_date: "2026-04-14"
  tasks_completed: 2
  files_created: 5
  files_modified: 0
---

# Phase 01 Plan 02: ProfileDataStore, ProfileManager, and ProfileModule Summary

**One-liner:** Gson-based profile list persistence with StateFlow CRUD API, max-4 enforcement, ID slot reuse, and Hilt Gson binding via ProfileModule.

## What Was Built

### Task 1: ProfileDataStore + ProfileModule (commit db7fe3e2d)

**ProfileDataStore.kt** — Gson-backed profile list persistence for `profile_settings` DataStore.

- `ProfileDataStoreImpl` (open base): holds all DataStore logic, accepts `DataStore<Preferences>` + `Gson` directly — testable without Context
- `ProfileDataStore` (@Singleton subclass): extends `ProfileDataStoreImpl`, injected by Hilt with `context.profileDataStore` delegate
- `ProfileJson` internal DTO: `@SerializedName` annotations, `pinEnabled: Boolean = false` field, no `usesPrimaryPlugins`
- Silent migration: `defaultPrimaryProfile()` returns `UserProfile(id=1, name="Default", avatarColorHex="#1E88E5")`
- Corrupted JSON falls back to default profile (try/catch in `parseProfiles`)
- `normalizeProfiles()` always ensures profile 1 exists in any persisted list
- `replaceAllProfiles()` provided for Phase 4 sync use

**ProfileModule.kt** — Hilt `@Module @InstallIn(SingletonComponent::class)` with `provideGson(): Gson` @Singleton binding (no existing Gson binding existed in the graph).

**ProfileDataStoreTest.kt** — 12 unit tests covering: empty DataStore default, activeProfileId default, upsert add/update, delete, delete-of-active reset, setActiveProfile, normalizeProfiles, corrupted JSON fallback, ProfileJson round-trip (all fields), replaceAllProfiles.

### Task 2: ProfileManager (commit 4f99d23e2)

**ProfileManager.kt** — CRUD coordinator with StateFlows and Hilt injection.

- Primary `@Inject` constructor: `ProfileDataStore + ProfileDataStoreFactory + @ApplicationContext Context` (production)
- Internal constructor: `ProfileDataStoreImpl + factory + context + scope` (test injection)
- `profiles: StateFlow<List<UserProfile>>` — eager, initial value `[UserProfile(1, "Default", "#1E88E5")]`
- `activeProfileId: StateFlow<Int>` — eager, initial value `1`
- `createProfile`: reads `dataStore.profilesList.first()` (not `profiles.value`) to avoid cache lag; guards `size >= 4`; slot reuse from `(2..4).firstOrNull { it !in usedIds }`; empty name defaults to `"Profile $nextId"`
- `deleteProfile`: blocks id==1; reads DataStore directly; calls `deleteProfileDataAsync` (factory.clearProfile + file cleanup)
- `updateProfile`: reads DataStore directly; delegates to upsert
- `setActiveProfile`: reads DataStore directly; no-op for non-existent id
- `deleteProfileDataAsync`: clears factory cache, deletes `_p{id}.preferences_pb` files from `context.filesDir/datastore`

**ProfileManagerTest.kt** — 17 unit tests covering all behavior items from the plan.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] ProfileManager.createProfile guard reads DataStore directly instead of profiles.value**

- **Found during:** Task 2 test iteration
- **Issue:** `createProfile` used `profiles.value` (StateFlow cache) which lags behind DataStore writes. After 3 sequential `createProfile` calls, the StateFlow hadn't propagated all updates yet, so the 4th call saw stale size and returned `true` instead of `false`.
- **Fix:** Changed all mutation guards (`createProfile`, `deleteProfile`, `updateProfile`, `setActiveProfile`) to call `dataStore.profilesList.first()` — reads the DataStore flow directly for the latest state.
- **Files modified:** `ProfileManager.kt`
- **Commit:** 4f99d23e2

**2. [Rule 1 - Bug] ProfileDataStore testability required open base class split**

- **Found during:** Task 1 implementation
- **Issue:** `ProfileDataStore` uses Android Context delegate (`preferencesDataStore`) making direct test construction impossible without Robolectric context.
- **Fix:** Split into `ProfileDataStoreImpl` (open class, takes `DataStore<Preferences>` + `Gson` — pure Kotlin, no Context) and `ProfileDataStore` (@Singleton Hilt subclass). Tests use `ProfileDataStoreImpl` directly with `PreferenceDataStoreFactory.create(tempFile)`.
- **Files modified:** `ProfileDataStore.kt`
- **Commit:** db7fe3e2d

**3. [Rule 1 - Bug] ProfileManager requires injectable CoroutineScope for test determinism**

- **Found during:** Task 2 test iteration
- **Issue:** `ProfileManager` created its own `CoroutineScope(SupervisorJob() + Dispatchers.IO)` which runs on real threads outside `runTest` virtual time. `profiles.first { it.size >= 4 }` would time out because StateFlow updates on IO threads weren't predictable from the test scheduler.
- **Fix:** Added `scope: CoroutineScope` parameter to the primary constructor. Production Hilt constructor creates `CoroutineScope(SupervisorJob() + Dispatchers.IO)`. Test constructor passes `backgroundScope`.
- **Files modified:** `ProfileManager.kt`
- **Commit:** 4f99d23e2

## Verification Results

```
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfileDataStoreTest" \
  --tests "com.nexio.tv.core.profile.ProfileManagerTest"
→ BUILD SUCCESSFUL (29 tests, 0 failures)

./gradlew assembleArm64Debug
→ BUILD SUCCESSFUL (Hilt graph compiles, all 4 new singletons resolved)

grep "name = \"Default\"" ProfileDataStore.kt → 1 match
grep "name = \"Default\"" ProfileManager.kt → 1 match
grep "fun provideGson" ProfileModule.kt → 1 match
grep -c "usesPrimaryPlugins" ProfileDataStore.kt → 0
grep -c "usesPrimaryPlugins" ProfileManager.kt → 0
```

## Known Stubs

None. All data flows are wired end-to-end: ProfileDataStore reads/writes real DataStore files, ProfileManager reads from DataStore directly for mutations and exposes StateFlows to UI.

## Threat Flags

None. All new classes are local-only infrastructure with no new network endpoints, auth paths, or IPC surfaces. Threat register items T-01-03 through T-01-06 addressed:
- T-01-04 (DoS / max-4 guard): `if (current.size >= 4) return false` in `createProfile` — mitigated.
- T-01-03 (corrupted JSON): `parseProfiles` try/catch with fallback to `defaultPrimaryProfile()` — mitigated.
- T-01-06 (file cleanup path traversal): suffix constructed from integer ID only — no user input in path.

## Self-Check: PASSED

| Item | Status |
|------|--------|
| ProfileDataStore.kt | FOUND |
| ProfileManager.kt | FOUND |
| ProfileModule.kt | FOUND |
| ProfileDataStoreTest.kt | FOUND |
| ProfileManagerTest.kt | FOUND |
| 01-02-SUMMARY.md | FOUND |
| commit db7fe3e2d | FOUND |
| commit 4f99d23e2 | FOUND |
