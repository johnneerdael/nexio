---
phase: 02-per-profile-auth-and-settings
fixed_at: 2026-04-14T10:58:03Z
review_path: .planning/phases/02-per-profile-auth-and-settings/02-REVIEW.md
iteration: 1
findings_in_scope: 6
fixed: 6
skipped: 0
status: all_fixed
---

# Phase 02: Code Review Fix Report

**Fixed at:** 2026-04-14T10:58:03Z
**Source review:** .planning/phases/02-per-profile-auth-and-settings/02-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 6 (1 Critical, 5 Warning)
- Fixed: 6
- Skipped: 0

## Fixed Issues

### CR-01: ProfileDataStoreFactory.get() Creates a Second DataStore Instance Over the Same File for Recycled Profile IDs

**Files modified:** `app/src/main/java/com/nexio/tv/data/local/ProfileDataStoreFactory.kt`
**Commit:** 5ac908ed0
**Applied fix:** Removed the `deletedProfileIds` guard branch from `get()` that unconditionally called `cache.compute(fileName) { _, _ -> PreferenceDataStoreFactory.create { ... } }`. Replaced with a single `cache.getOrPut` path. The `clearProfile()` method already evicts cache entries on deletion, so a recycled profile ID will naturally get one fresh instance on next `getOrPut` without creating a second DataStore over the same file path.

---

### WR-01: Read-Modify-Write Race in SearchHistoryDataStore.saveRecentSearch

**Files modified:** `app/src/main/java/com/nexio/tv/data/local/SearchHistoryDataStore.kt`
**Commit:** d33df28e9
**Applied fix:** Eliminated the separate `recentSearches.first()` suspension point before the write. The fix captures `profileManager.activeProfileId.value` once before calling `store(profileId).edit { ... }`, then reads and updates the history atomically within the single `edit` lambda using `decodeSearchHistory(prefs[recentSearchesKey])`. Also removed the now-unused `kotlinx.coroutines.flow.first` import.

---

### WR-02: store() Write Target Is Racy Across a Profile Switch for Auth Operations

**Files modified:** `app/src/main/java/com/nexio/tv/data/local/TraktAuthDataStore.kt`, `app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt`, `app/src/main/java/com/nexio/tv/ui/screens/settings/TraktViewModel.kt`
**Commit:** 3bb8df25f
**Applied fix:** Added `profileId: Int = profileManager.activeProfileId.value` parameter to `TraktAuthDataStore.clearAuth()` and `clearDeviceFlow()` so callers can pass a pre-snapshotted ID. Updated `TraktAuthService.revokeAndLogout()` to accept an optional `profileId: Int?` and forward it to `clearAuth()`. In `TraktViewModel`, snapshotted `profileManager.activeProfileId.value` before `viewModelScope.launch` in both `onDisconnectClick()` and `onCancelDeviceFlow()`, then passed that snapshot to the respective data store/service calls.

---

### WR-03: Push-Suppression Window in AccountSettingsSyncService Has a TOCTOU Gap and a Fixed 2-Second Timeout

**Files modified:** `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
**Commit:** 646bf731a
**Applied fix:** Replaced the `@Volatile recentlySwitchedProfile: Boolean` + `delay(2000)` pattern with a generation counter approach. Added `@Volatile suppressPushForSwitchGeneration: Long` and `currentSwitchGeneration: Long`. `observeProfileSwitches()` now increments `currentSwitchGeneration`, writes it to `suppressPushForSwitchGeneration`, cancels any pending push job, and does not use a fixed delay. Added `clearSuppression(gen: Long)` which atomically clears suppression only if the generation matches. `pullFromRemoteAndApply()` captures `switchGenAtPullStart` before the network call and calls `clearSuppression(switchGenAtPullStart)` on successful apply. Both `observeLocalChanges()` and `schedulePush()` now check `suppressPushForSwitchGeneration != 0L` instead of `recentlySwitchedProfile`.

---

### WR-04: LayoutPreferenceDataStore and PlayerSettingsDataStore Migration init Blocks Only Run for the Active Profile at Construction Time

**Files modified:** `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt`, `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
**Commit:** 934cb8afb
**Applied fix:**

**LayoutPreferenceDataStore:** Removed the `init { ioScope.launch { store().edit { applyLayoutPreferenceMigrations(it) } } }` block and `ioScope` field. Updated `profileFlow()` to chain `.onStart { store(pid).edit { applyLayoutPreferenceMigrations(it) } }` before `.map { ... }`, so migrations run for each profile the first time its flow is subscribed. Removed unused `CoroutineScope`, `SupervisorJob`, `Dispatchers`, and `launch` imports; added `onStart` import.

**PlayerSettingsDataStore:** Extracted the full `init` block body (buffer retuning migrations + `applyPlayerSettingsMigrations` + language normalization) into a new private `applyAllPlayerMigrations(prefs: MutablePreferences)` member function. Updated `profileFlow()` to chain `.onStart { store(pid).edit { applyAllPlayerMigrations(it) } }`. Removed `ioScope` field and the `init` block. Removed unused `CoroutineScope`, `SupervisorJob`, `Dispatchers`, and `launch` imports; added `onStart` import.

---

### WR-05: Concurrent Calls to pullFromRemoteAndApply Can Prematurely Clear isApplyingRemote

**Files modified:** `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
**Commit:** d912dc96a
**Applied fix:** Added `private val applyingRemoteMutex = Mutex()` field. Wrapped the entire apply critical section in `pullFromRemoteAndApply()` with `applyingRemoteMutex.withLock { ... }`, ensuring only one caller at a time can set/clear `isApplyingRemote` and execute the apply sequence. Added `kotlinx.coroutines.sync.Mutex` and `kotlinx.coroutines.sync.withLock` imports.

---

_Fixed: 2026-04-14T10:58:03Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
