---
phase: 04-sync-and-cleanup
reviewed: 2026-04-14T17:38:32Z
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

**Reviewed:** 2026-04-14T17:38:32Z
**Depth:** standard
**Files Reviewed:** 22
**Status:** issues_found

## Summary

Reviewed the listed Kotlin source and test files after the 04-05 gap closure. The previous `layout_preferences`/`layout_settings` finding is resolved: `ProfileSettingsSyncService` now syncs `layout_settings`, and the regression test verifies `layout_preferences` stays unused. The previous v7 layout/catalog-order routing concern is also resolved in the active account-sync path: `AccountSettingsSyncService` now observes empty layout/catalog flows, pushes empty moved-field lists for home/Trakt/SIMKL catalog order, and does not apply those fields through `applySharedAccountConfigSyncSettings`.

Remaining concerns are in sync orchestration: account-settings pushes can still remain suppressed after a profile switch, profile metadata push still reads a possibly stale `StateFlow`, and startup force-resync requests made during an active successful pull are dropped.

## Warnings

### WR-01: Account settings auto-push stays suppressed after profile switches

**File:** `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt:218`
**Issue:** `observeProfileSwitches()` sets `suppressPushForSwitchGeneration` on every active-profile change and cancels any pending push, while `observeLocalChanges()` and `schedulePush()` ignore changes as long as that generation is non-zero. The only normal clear path is `clearSuppression()` inside `pullFromRemoteAndApply()` at line 429, but the profile-switch observer no longer schedules an account pull. After switching profiles, shared account-setting changes can be ignored until some unrelated account pull happens.
**Fix:**
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
Alternatively, remove this suppression entirely now that profile-scoped layout, player, Trakt, and SIMKL settings are owned by the v8 profile blob observer.

### WR-02: Profile metadata push can miss fresh local profile changes

**File:** `app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt:35`
**Issue:** `pushToRemote()` reads `profileManager.profiles.value`, which is the cached `StateFlow` value. `ProfileManager` already avoids this pattern for create/update/delete because the `StateFlow` can lag behind DataStore writes. An immediate profile sync after creating, updating, or deleting a profile can therefore push the stale profile list.
**Fix:**
```kotlin
val profiles = profileDataStore.profilesList.first()
```
Use the already-injected `ProfileDataStore` as the source of truth for push payloads, and add a regression that mutates profile DataStore state then immediately calls `pushToRemote()`.

### WR-03: Pending forced startup resync is dropped after a successful active pull

**File:** `app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt:109`
**Issue:** When `requestSyncNow()` runs during an active startup pull, `scheduleStartupPull()` stores `pendingResyncKey` at line 91. If the active pull then succeeds, the coroutine exits via `return@launch` at line 113 before the pending-resync drain at lines 119-124. That means a forced sync request made while a pull is in flight is silently dropped whenever the in-flight pull succeeds, even if the forced request was intended to pick up changes made after that pull began.
**Fix:**
```kotlin
startupPullJob = scope.launch {
    var syncCompleted = false
    for (attempt in 1..maxAttempts) {
        val result = pullRemoteProfileState()
            .fold(
                onSuccess = { pullRemoteSnapshot() },
                onFailure = { profileError ->
                    Log.w(TAG, "Startup profile sync failed before account snapshot sync", profileError)
                    pullRemoteSnapshot()
                }
            )
        if (result.isSuccess) {
            lastPulledKey = key
            accountSettingsSyncService.markStartupRemotePullSucceeded(userId)
            syncCompleted = true
            break
        }
        if (attempt < maxAttempts) delay(3000)
    }

    val resyncKey = pendingResyncKey
    if (resyncKey != null) {
        pendingResyncKey = null
        if (!syncCompleted || resyncKey != lastPulledKey) {
            scheduleStartupPull(userId, force = true)
        }
    }
}
```
The exact loop shape can differ, but the pending-resync drain must run after both success and failure paths.

## Info

### IN-01: Profile sync behavior tests are disabled placeholders

**File:** `app/src/test/java/com/nexio/tv/core/sync/ProfileSyncServiceTest.kt:7`
**Issue:** `ProfileSyncService` owns the profile metadata push/pull contract, but its test class is ignored and its methods are not executable tests. This leaves filtering, de-duplication, replacement behavior, and push encoding unprotected.
**Fix:** Replace the ignored placeholder with executable tests using a Postgrest RPC test double, or delete the placeholder until real tests are ready.

### IN-02: Legacy account settings apply path is now dead code

**File:** `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt:638`
**Issue:** The private `applyRemoteSettings(AccountSettingsPayload)` path is no longer called now that pulls use `applySharedAccountConfigSyncSettings()`. Keeping the old method also keeps legacy per-profile player/layout writes and constructor dependencies alive, which can mislead future maintenance and makes the old v7 layout apply code appear active when it is not.
**Fix:** Delete the unused method and remove the imports/constructor dependencies that only remain for it, or rewire it intentionally if the legacy payload path is still required.

---

_Reviewed: 2026-04-14T17:38:32Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
