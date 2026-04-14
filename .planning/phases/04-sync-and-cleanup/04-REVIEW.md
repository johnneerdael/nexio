---
phase: 04-sync-and-cleanup
reviewed: 2026-04-14T14:08:49Z
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
  info: 1
  total: 4
status: issues_found
---

# Phase 04: Code Review Report

**Reviewed:** 2026-04-14T14:08:49Z
**Depth:** standard
**Files Reviewed:** 22
**Status:** issues_found

## Summary

Reviewed the listed Kotlin source and test files for correctness, security, and maintainability. The main issues are around profile-scoped sync lifecycle: profile settings blobs are treated as full snapshots but are not cleared on import, active profile switches can push before the selected profile is pulled, and account-settings push suppression can remain enabled indefinitely after a profile switch.

## Warnings

### WR-01: Remote profile-settings deletes do not apply locally

**File:** `app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt:191`
**Issue:** `importSettingsBlob()` only writes keys present in the remote feature blob. Because `pushBlobForProfile()` exports each feature as a full snapshot, a missing key is meaningful: it means the remote profile no longer has that preference. Leaving local keys in place means cleared/defaulted settings such as nullable language selections, formatter custom templates, catalog lists, or autoplay limits can survive a remote pull and later be pushed back upstream.
**Fix:**
```kotlin
private suspend fun importSettingsBlob(profileId: Int, blob: JsonObject) {
    syncedFeatures.forEach { feature ->
        val featureBlob = blob[feature]?.let { element ->
            runCatching { element.jsonObject }.getOrNull()
        } ?: buildJsonObject {}
        val store = profileDataStoreFactory.get(profileId, feature)
        store.edit { preferences ->
            preferences.clear()
            featureBlob.forEach { (key, valueElement) ->
                val valueObj = runCatching { valueElement.jsonObject }.getOrNull() ?: return@forEach
                applyEncodedPreference(preferences, key, valueObj)
            }
        }
    }
}
```

### WR-02: Profile switches can overwrite a profile before pulling its remote blob

**File:** `app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt:81`
**Issue:** `startObserving()` switches the observed DataStore set when `activeProfileId` changes and will push the selected profile after the next local edit, but the only automatic `pullBlobForProfile()` call is at startup for the then-active profile (`StartupSyncService.kt:140`). Normal profile switching therefore never hydrates that profile's remote settings first. If profile 2 was configured on another device, switching to profile 2 here and changing one setting can export local defaults plus that edit as a full blob and overwrite the real remote profile settings.
**Fix:** Gate each profile's observer behind a successful pull for that profile, or trigger the pull from the profile-switch path before allowing pushes for the new profile. For example:
```kotlin
profileManager.activeProfileId
    .distinctUntilChanged()
    .collectLatest { profileId ->
        val pulled = pullBlobForProfile(profileId).isSuccess
        if (!pulled) return@collectLatest
        observeProfileSettings(profileId)
            .drop(1)
            .debounce(2000)
            .collect { pushBlobForProfile(profileId) }
    }
```

### WR-03: Account settings auto-push stays suppressed after any profile switch

**File:** `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt:218`
**Issue:** On every active-profile change, `observeProfileSwitches()` sets `suppressPushForSwitchGeneration`, and local account-setting changes are ignored while that value is non-zero (`AccountSettingsSyncService.kt:275`). The only clear path is `clearSuppression()` inside `pullFromRemoteAndApply()` (`AccountSettingsSyncService.kt:428`), but no code in the profile-switch observer actually schedules that pull. After a profile switch, background account-settings pushes remain disabled until some unrelated manual/startup pull happens.
**Fix:** Either remove this suppression now that per-profile settings were moved out of the account config observer, or make the switch observer perform the pull it relies on and clear suppression in a `finally` path:
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

## Info

### IN-01: Profile sync behavior tests are disabled placeholders

**File:** `app/src/test/java/com/nexio/tv/core/sync/ProfileSyncServiceTest.kt:7`
**Issue:** The new `ProfileSyncService` handles profile metadata push/pull, filtering, de-duplication, and replacement behavior, but its test class is ignored and the methods are not annotated as tests. This leaves the new sync contract unprotected despite the phase adding sync behavior.
**Fix:** Replace the ignored placeholder with executable tests using a Postgrest RPC test double, or delete the placeholder until real tests are ready so the suite does not imply coverage that is not running.

---

_Reviewed: 2026-04-14T14:08:49Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
