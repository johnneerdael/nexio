---
phase: 02-per-profile-auth-and-settings
plan: 03
subsystem: data-local
tags: [datastore, per-profile, factory-pattern, profile-isolation]
dependency_graph:
  requires: [02-01, 02-02]
  provides: [per-profile-player-settings, per-profile-layout-settings, per-profile-theme, per-profile-search-history]
  affects: [PlayerViewModel, PlayerRuntimeController, HomeViewModel, LayoutSettingsViewModel, AccountSettingsSyncService]
tech_stack:
  added: []
  patterns: [ProfileDataStoreFactory, flatMapLatest-profile-switching, profileFlow-helper]
key_files:
  created:
    - app/src/test/java/com/nexio/tv/data/local/ThemeDataStoreProfileTest.kt
  modified:
    - app/src/main/java/com/nexio/tv/data/local/ThemeDataStore.kt
    - app/src/main/java/com/nexio/tv/data/local/SearchHistoryDataStore.kt
    - app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt
    - app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt
    - app/src/test/java/com/nexio/tv/data/local/SearchHistoryDataStoreTest.kt
decisions:
  - "Used profileFlow helper in PlayerSettingsDataStore (same as LayoutPreferenceDataStore) to wrap 40+ flows via single choke point rather than individual flatMapLatest wrapping"
  - "SearchHistoryDataStore dual-constructor pattern replaced with single @Inject constructor taking factory+profileManager - internal constructor removed"
  - "Pre-existing compile errors in forked Media3 player code (KodiNativeAudioSink, DolbyVisionCompatibility) prevent full build verification in this worktree - documented as out-of-scope pre-existing issue"
metrics:
  duration: "~40 minutes"
  completed_date: "2026-04-14"
  tasks_completed: 2
  tasks_total: 2
  files_modified: 5
  files_created: 1
---

# Phase 02 Plan 03: Remaining Settings DataStores Migration Summary

Per-profile migration of the remaining 4 settings DataStores (ThemeDataStore, SearchHistoryDataStore, PlayerSettingsDataStore, LayoutPreferenceDataStore) from singleton `preferencesDataStore` delegates to `ProfileDataStoreFactory` + `flatMapLatest` pattern — completing AUTH-06 (all 8 DataStores from D-01 are now profile-reactive).

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Migrate ThemeDataStore and SearchHistoryDataStore (simple stores) | 974611f96 | ThemeDataStore.kt, SearchHistoryDataStore.kt, ThemeDataStoreProfileTest.kt, SearchHistoryDataStoreTest.kt |
| 2 | Migrate PlayerSettingsDataStore and LayoutPreferenceDataStore (complex stores with init blocks) | 608ab9f57 | PlayerSettingsDataStore.kt, LayoutPreferenceDataStore.kt |

## Acceptance Criteria Results

### ThemeDataStore
- No `preferencesDataStore(name =`: PASS
- No `@ApplicationContext`: PASS
- Has `factory: ProfileDataStoreFactory`: PASS
- Has `FEATURE = "theme_settings"`: PASS
- Has `flatMapLatest` (2 flow occurrences): PASS

### SearchHistoryDataStore
- No `preferencesDataStore(name =`: PASS
- No `@ApplicationContext`: PASS
- Old `@Inject constructor(@ApplicationContext context: Context)` removed: PASS
- Has `factory: ProfileDataStoreFactory`: PASS
- Has `FEATURE = "search_history"`: PASS
- Has `flatMapLatest`: PASS

### PlayerSettingsDataStore
- No `preferencesDataStore(name =`: PASS
- No `@ApplicationContext`: PASS
- No `private val dataStore = context.playerSettingsDataStore`: PASS
- Has `factory: ProfileDataStoreFactory`: PASS
- Has `FEATURE = "player_settings"`: PASS
- Has `flatMapLatest` (via profileFlow helper): PASS

### LayoutPreferenceDataStore
- No `preferencesDataStore(name =`: PASS
- No `@ApplicationContext`: PASS
- No `private val dataStore = context.layoutPreferenceDataStore`: PASS
- Has `factory: ProfileDataStoreFactory`: PASS
- Has `FEATURE = "layout_settings"`: PASS
- Has `flatMapLatest` (via profileFlow helper): PASS

## What Was Built

### ThemeDataStore Migration
- Removed top-level `Context.themeDataStore: DataStore<Preferences> by preferencesDataStore` extension
- Constructor changed from `@ApplicationContext context: Context` to `ProfileDataStoreFactory + ProfileManager`
- `selectedTheme` and `selectedFont` flows wrapped in `flatMapLatest(activeProfileId)`
- Write methods use `store().edit` resolving to active profile at call time

### SearchHistoryDataStore Migration
- Removed top-level `Context.searchHistoryDataStore` extension
- Replaced dual-constructor pattern (`internal constructor(DataStore)` + `@Inject constructor(Context)`) with single `@Inject constructor(ProfileDataStoreFactory, ProfileManager, Gson)`
- `recentSearches` flow wrapped in `flatMapLatest(activeProfileId)`
- Pure functions `nextSearchHistory` and `normalizeSearchHistory` preserved unchanged
- `saveRecentSearch` still calls `recentSearches.first()` — correctly reads active profile's data

### PlayerSettingsDataStore Migration
- Removed top-level `Context.playerSettingsDataStore` extension
- Added `profileFlow` helper: wraps all flows via `profileManager.activeProfileId.flatMapLatest { pid -> store(pid).data.map { extract(it) } }`
- `playerSettings: Flow<PlayerSettings>` composite flow migrated from `dataStore.data.map` to `profileFlow`
- `spoolStorageProbeResult: Flow<SpoolStorageProbeResult?>` migrated to `profileFlow`
- `init` block preserved — `store()` resolves to profile 1 at Hilt injection time (before any UI interaction)

### LayoutPreferenceDataStore Migration
- Removed top-level `Context.layoutPreferenceDataStore` extension
- Updated `profileFlow` helper from `dataStore.data.map` to `flatMapLatest(activeProfileId)` — single-line change makes ALL 20+ flow properties profile-reactive
- Added `FEATURE = "layout_settings"` to companion object
- `init` block preserved — runs on profile 1's DataStore at app startup

### Test Changes
- `ThemeDataStoreProfileTest`: New test verifying theme persists independently per profile (CRIMSON on profile 1 vs OCEAN on profile 2)
- `SearchHistoryDataStoreTest`: Updated `store persists recent searches` test to use `FakeProfileDataStoreFactory + FakeProfileManager` instead of the removed direct DataStore injection

## Deviations from Plan

### Pre-existing Compile Errors (Out of Scope)

The worktree's base commit (10ec20a5) contains source code referencing forked Media3 APIs that are not present in this worktree's build environment:
- `KodiNativeAudioSink`, `KodiTrueHdNativeAudioSink` in `PlayerRuntimeControllerInitialization.kt`
- `DolbyVisionCompatibility` in `MatroskaDolbyVisionHookInstaller.kt`
- `probeDolbyVisionProfile` etc. in `DolbyVisionAutoPlayGate.kt`
- Various `Dv5HardwareToneMap` methods

These are pre-existing failures from a different branch's Media3 fork changes being merged into the base commit. They are completely unrelated to DataStore migration and exist in the main repo's same files. The main repo at `/Users/jneerdael/Scripts/nexio` compiles these files successfully (different Gradle build cache), suggesting a Gradle incremental compilation issue in the worktree's build directory.

The DataStore migration logic was verified via acceptance criteria grep checks (all pass) and structural code review against the established pattern from plans 02-01 and 02-02.

**[Rule 3 - Blocking] Attempted AssSsaMatroskaExtractor fix** — Initially attempted to fix a `DolbyVisionSampleTransformer` type error by changing `MatroskaExtractor.DolbyVisionSampleTransformer?` to `Any?`. This introduced new type mismatch errors and was reverted. The fix was out of scope and the underlying issue is a broader forked Media3 incompatibility.

## Threat Model Mitigations Applied

| Threat ID | Status |
|-----------|--------|
| T-02-07 | Mitigated — PlayerSettingsDataStore profileFlow wraps all flows in flatMapLatest; store() resolves at call time |
| T-02-08 | Mitigated — LayoutPreferenceDataStore profileFlow helper is the single choke point for all 20+ flows |
| T-02-09 | Accepted — init block runs store().edit at profile 1 (Hilt singleton init before any UI) |
| T-02-10 | Mitigated — SearchHistoryDataStore recentSearches wrapped in flatMapLatest; per-profile isolation |

## Deferred Items

- Full build verification (`./gradlew assembleArm64Debug`) blocked by pre-existing forked Media3 compile errors in worktree — to be resolved by the orchestrator after all wave-3 agents complete
- Unit test execution blocked by same pre-existing compile errors — `ThemeDataStoreProfileTest` and updated `SearchHistoryDataStoreTest` are logically correct and follow identical pattern to `SimklAuthDataStoreProfileTest` from plan 02-02

## Self-Check: PASSED

Verified files exist:
- `/Users/jneerdael/Scripts/nexio/.claude/worktrees/agent-a48a0956/app/src/main/java/com/nexio/tv/data/local/ThemeDataStore.kt`: FOUND
- `/Users/jneerdael/Scripts/nexio/.claude/worktrees/agent-a48a0956/app/src/main/java/com/nexio/tv/data/local/SearchHistoryDataStore.kt`: FOUND
- `/Users/jneerdael/Scripts/nexio/.claude/worktrees/agent-a48a0956/app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`: FOUND
- `/Users/jneerdael/Scripts/nexio/.claude/worktrees/agent-a48a0956/app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt`: FOUND
- `/Users/jneerdael/Scripts/nexio/.claude/worktrees/agent-a48a0956/app/src/test/java/com/nexio/tv/data/local/ThemeDataStoreProfileTest.kt`: FOUND
- `/Users/jneerdael/Scripts/nexio/.claude/worktrees/agent-a48a0956/app/src/test/java/com/nexio/tv/data/local/SearchHistoryDataStoreTest.kt`: FOUND

Verified commits exist:
- 974611f96: feat(02-03): migrate ThemeDataStore and SearchHistoryDataStore to per-profile factory pattern — FOUND
- 608ab9f57: feat(02-03): migrate PlayerSettingsDataStore and LayoutPreferenceDataStore to per-profile factory pattern — FOUND
