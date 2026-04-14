---
phase: 04-sync-and-cleanup
reviewed: 2026-04-14T16:50:03Z
depth: standard
files_reviewed: 22
files_reviewed_list:
  - app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt
  - app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt
  - app/src/main/java/com/nexio/tv/core/sync/ProfilePrefsName.kt
  - app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt
  - app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt
  - app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt
  - app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt
  - app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStore.kt
  - app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt
  - app/src/main/java/com/nexio/tv/data/local/SimklProgressSyncStateStore.kt
  - app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt
  - app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt
  - app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt
  - app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt
  - app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStore.kt
  - app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt
  - app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsViewModel.kt
  - app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt
  - app/src/test/java/com/nexio/tv/core/sync/ProfileSettingsSyncServiceTest.kt
  - app/src/test/java/com/nexio/tv/core/sync/ProfileSyncServiceTest.kt
  - app/src/test/java/com/nexio/tv/data/local/ProfilePrefsNameTest.kt
  - app/src/test/java/com/nexio/tv/data/local/TraktLibrarySnapshotStoreTest.kt
findings:
  critical: 0
  warning: 3
  info: 2
  total: 5
status: issues_found
---

# Phase 04: Code Review Report

**Reviewed:** 2026-04-14T16:50:03Z
**Depth:** standard
**Files Reviewed:** 22
**Status:** issues_found

## Summary

Reviewed the listed Kotlin source and test files after the 04-04 gap-closure changes. The previous findings about full-snapshot imports and pull-before-observe profile hydration in `ProfileSettingsSyncService` were resolved. Current concerns are the new profile settings blob using the wrong layout DataStore feature name, account-settings push suppression still persisting after profile switches, and profile metadata pushes reading a possibly stale `StateFlow` snapshot.

## Warnings

### WR-01: Profile settings blob watches the wrong layout DataStore

**File:** `app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt:73`
**Issue:** `syncedFeatures` contains `"layout_preferences"`, but the real `LayoutPreferenceDataStore` feature name is `"layout_settings"` (`app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt:69`). `ProfileDataStoreFactory.get()` maps feature names directly to DataStore file names, so the new blob sync observes/imports/exports a separate empty `layout_preferences[_pN]` store instead of the actual layout settings. Layout changes therefore do not round-trip through the v8 profile settings blob.
**Fix:**
```kotlin
val syncedFeatures = listOf(
    "trakt_settings",
    "simkl_settings",
    "player_settings",
    "layout_settings",
    "theme_settings"
)
```
Update `ProfileSettingsSyncServiceTest` to expect `"layout_settings"` and add a regression that writes a real layout preference via `ProfileDataStoreFactory.get(profileId, "layout_settings")` and verifies import/export touches that store, not `layout_preferences`.

### WR-02: Account settings auto-push stays suppressed after profile switches

**File:** `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt:218`
**Issue:** `observeProfileSwitches()` sets `suppressPushForSwitchGeneration` on every active-profile change and cancels any pending push, while `observeLocalChanges()` and `schedulePush()` ignore changes as long as the generation is non-zero. The only normal clear path is `clearSuppression()` inside `pullFromRemoteAndApply()` (`AccountSettingsSyncService.kt:428`), but the profile-switch observer does not schedule that pull. After switching profiles, shared account-setting changes can be ignored until some unrelated account pull happens.
**Fix:** Either remove the profile-switch suppression after profile-scoped settings are fully out of the account config observer, or make the switch path schedule and clear the pull it relies on:
```kotlin
profileManager.activeProfileId.drop(1).collect {
    val gen = ++currentSwitchGeneration
    suppressPushForSwitchGeneration = gen
    pushJob?.cancel()
    pushJob = null
    scope.launch {
        try {
            pullFromRemoteAndApply(clearPendingChanges = false)
        } finally {
            clearSuppression(gen)
        }
    }
}
```

### WR-03: Profile metadata push can miss fresh local profile changes

**File:** `app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt:35`
**Issue:** `pushToRemote()` reads `profileManager.profiles.value`, which is the cached `StateFlow` value. `ProfileManager` itself already avoids this pattern for create/update/delete because its `StateFlow` can lag behind DataStore writes. An immediate profile sync after creating, updating, or deleting a profile can therefore push the stale profile list.
**Fix:**
```kotlin
val profiles = profileDataStore.profilesList.first()
```
Use the already-injected `ProfileDataStore` as the source of truth for push payloads, and add a test that mutates the profile DataStore then immediately calls `pushToRemote()`.

## Info

### IN-01: Profile sync behavior tests are disabled placeholders

**File:** `app/src/test/java/com/nexio/tv/core/sync/ProfileSyncServiceTest.kt:7`
**Issue:** `ProfileSyncService` owns the profile metadata push/pull contract, but its test class is ignored and its methods are not executable tests. This leaves filtering, de-duplication, replacement behavior, and push encoding unprotected.
**Fix:** Replace the ignored placeholder with executable tests using a Postgrest RPC test double, or delete the placeholder until real tests are ready.

### IN-02: Legacy account settings apply path is now dead code

**File:** `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt:637`
**Issue:** The private `applyRemoteSettings(AccountSettingsPayload)` path is no longer called now that pulls use `applySharedAccountConfigSyncSettings()`. Keeping the old method also keeps legacy per-profile player/layout writes and constructor dependencies alive, which can mislead future maintenance.
**Fix:** Delete the unused method and remove the imports/constructor dependencies that only remain for it, or rewire it intentionally if the legacy payload path is still required.

---

_Reviewed: 2026-04-14T16:50:03Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
