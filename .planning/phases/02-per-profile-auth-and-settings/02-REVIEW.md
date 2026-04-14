---
phase: 02-per-profile-auth-and-settings
reviewed: 2026-04-14T00:00:00Z
depth: standard
files_reviewed: 13
files_reviewed_list:
  - app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt
  - app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt
  - app/src/main/java/com/nexio/tv/data/local/TraktAuthDataStore.kt
  - app/src/main/java/com/nexio/tv/data/local/TraktSettingsDataStore.kt
  - app/src/main/java/com/nexio/tv/data/local/SimklAuthDataStore.kt
  - app/src/main/java/com/nexio/tv/data/local/SimklSettingsDataStore.kt
  - app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt
  - app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt
  - app/src/main/java/com/nexio/tv/data/local/ThemeDataStore.kt
  - app/src/main/java/com/nexio/tv/data/local/SearchHistoryDataStore.kt
  - app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt
  - app/src/main/java/com/nexio/tv/ui/screens/settings/TraktViewModel.kt
  - app/src/main/java/com/nexio/tv/ui/screens/settings/SimklViewModel.kt
findings:
  critical: 1
  warning: 5
  info: 3
  total: 9
status: issues_found
---

# Phase 02: Code Review Report

**Reviewed:** 2026-04-14
**Depth:** standard
**Files Reviewed:** 13
**Status:** issues_found

## Summary

Phase 02 migrates 8 DataStores from singleton delegates to the `ProfileDataStoreFactory` pattern. The `flatMapLatest` wiring is correctly applied uniformly across all stores: each `Flow` property chains off `profileManager.activeProfileId.flatMapLatest { pid -> store(pid).data.map { ... } }`, and write operations consistently call `store()` which defaults to `profileManager.activeProfileId.value`. The migration is structurally sound and consistent with the NuvioTV reference pattern.

One critical bug was found in `ProfileDataStoreFactory.get()` itself (Phase 1 foundation code, but impacting Phase 2 correctness): when a deleted profile ID is reused, the factory creates a second `DataStore` instance over the same file path, which is unsupported by Jetpack DataStore. Five warnings cover correctness hazards: a read-modify-write race in `SearchHistoryDataStore.saveRecentSearch`, the universal `store()` write-target race on profile switch for mutable operations, the push-suppression timing gap in `AccountSettingsSyncService`, migration `init` blocks only running for profile 1, and a concurrent-pull hazard on the `isApplyingRemote` boolean flag. Three informational items cover code quality and consistency.

---

## Critical Issues

### CR-01: `ProfileDataStoreFactory.get()` Creates a Second DataStore Instance Over the Same File for Recycled Profile IDs

**File:** `app/src/main/java/com/nexio/tv/data/local/ProfileDataStoreFactory.kt:23-29`

**Issue:** When `profileId` is in `deletedProfileIds`, `get()` calls `cache.compute(fileName) { _, _ -> PreferenceDataStoreFactory.create { ... } }`. The lambda passed to `compute` unconditionally creates a brand-new `DataStore` instance regardless of whether one already exists in the cache. `PreferenceDataStoreFactory.create` must only be called once per file path per process — Jetpack DataStore does not support two active instances on the same file (undefined behaviour: WAL corruption, stale reads, lost writes).

This is triggered when a profile is deleted and a new profile is created and receives the same ID (IDs cycle through 2–4). After `markProfileCreated` removes the ID from `deletedProfileIds`, subsequent calls to `get()` use the normal `getOrPut` path — but the `compute` branch during the deletion window has already replaced the cache entry with a new instance. Any `flatMapLatest` observer that already holds a reference to the first (cleared) instance will not switch to the factory-created second instance, meaning writes from write functions (which go to `factory.get(activeProfileId.value, FEATURE)`) and reads from the observer flow diverge.

**Fix:**
```kotlin
fun get(profileId: Int, featureName: String): DataStore<Preferences> {
    val fileName = if (profileId == 1) featureName else "${featureName}_p${profileId}"
    // Always use getOrPut. clearProfile() already removes keys from cache, so a
    // recycled profile ID will naturally get a fresh instance on next get().
    return cache.getOrPut(fileName) {
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile(fileName)
        }
    }
}
```
Remove the `deletedProfileIds` guard branch entirely from `get()`. The `clearProfile` method already evicts the cache entry when the profile is deleted, so the next `getOrPut` call for a recycled ID correctly creates one fresh instance.

---

## Warnings

### WR-01: Read-Modify-Write Race in `SearchHistoryDataStore.saveRecentSearch`

**File:** `app/src/main/java/com/nexio/tv/data/local/SearchHistoryDataStore.kt:60-75`

**Issue:** `saveRecentSearch` reads the current history via `recentSearches.first()` (line 67–70), then writes via `store().edit { ... }` (line 72–74) in a separate suspension. Both `recentSearches` (a `flatMapLatest` flow) and `store()` (reads `activeProfileId.value` at call time) can resolve to different profiles if a switch occurs between the two calls. Additionally, even within the same profile, a concurrent `saveRecentSearch` call can produce a lost-update: both coroutines read the same current list, add their queries independently, and the second write overwrites the first.

**Fix:** Read and write within a single atomic `DataStore.edit` transaction, capturing the profile ID once:
```kotlin
suspend fun saveRecentSearch(query: String, maxItems: Int = DEFAULT_MAX_RECENT_SEARCHES) {
    val normalized = query.trim()
    if (normalized.isEmpty()) return
    val profileId = profileManager.activeProfileId.value
    store(profileId).edit { prefs ->
        val current = decodeSearchHistory(prefs[recentSearchesKey])
        val updated = nextSearchHistory(current = current, query = normalized, maxItems = maxItems)
        prefs[recentSearchesKey] = gson.toJson(updated)
    }
}
```
This eliminates the extra `first()` suspension point and makes the operation atomic within one DataStore transaction.

---

### WR-02: `store()` Write Target Is Racy Across a Profile Switch for Auth Operations

**File:** `app/src/main/java/com/nexio/tv/data/local/TraktAuthDataStore.kt:60-61`, `SimklAuthDataStore.kt:51-52` (and equivalently all mutable stores)

**Issue:** Every store's write functions call `store()` with no argument, which evaluates `profileManager.activeProfileId.value` at the moment the coroutine executes the call — not at the moment the user triggered the action. `TraktViewModel.onDisconnectClick`, `onCancelDeviceFlow`, and `onContinueWatchingDaysCapSelected` all launch a coroutine via `viewModelScope.launch { }` before calling a write function. If the active profile changes in the yield between `launch` and the write function body executing, the write lands in the wrong profile's store.

This is most dangerous for `clearAuth()` and `clearDeviceFlow()`: a user on profile 2 disconnects Trakt, the profile switches to profile 1 during the yield, and profile 1's auth token is cleared instead.

**Fix:** Capture the profile ID before the `launch` and pass it explicitly to the store write:
```kotlin
// In TraktAuthDataStore, add a profileId parameter to write functions:
suspend fun clearAuth(profileId: Int = profileManager.activeProfileId.value) {
    store(profileId).edit { preferences -> /* ... */ }
}

// In TraktViewModel.onDisconnectClick:
fun onDisconnectClick() {
    val profileId = profileManager.activeProfileId.value  // snapshot before launch
    viewModelScope.launch {
        traktAuthService.revokeAndLogout(profileId)
        // ...
    }
}
```
For settings writes triggered by explicit user interaction in the settings screen (catalog ordering, days cap), the current behaviour is acceptable since the user must be viewing that profile's settings page.

---

### WR-03: Push-Suppression Window in `AccountSettingsSyncService` Has a TOCTOU Gap and a Fixed 2-Second Timeout

**File:** `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt:210-217`, `261`, `272`

**Issue:** `observeProfileSwitches()` sets `recentlySwitchedProfile = true`, waits 2000 ms, then sets it back to `false`. Two problems:

1. **TOCTOU gap:** `schedulePush()` at line 272 reads `recentlySwitchedProfile` (check) and then launches `pushJob` (act) in non-atomic steps. A profile switch that completes in the gap between the check and the launch allows a stale-profile push to escape. `@Volatile` prevents torn reads but does not prevent this race.

2. **Hardcoded 2-second window:** `flatMapLatest` emits initial values for each switched-to store; on a cold start or under storage pressure these emissions can arrive beyond 2 seconds. Any emission arriving after the window closes causes a push of the new profile's local state before the remote pull for that profile has completed, potentially overwriting the correct remote state with local defaults.

**Fix:** Gate pushes on pull completion rather than on elapsed time. Track a switch generation counter that is incremented on every profile switch and cleared only after `pullFromRemoteAndApply` succeeds for the new profile:
```kotlin
@Volatile private var suppressPushForSwitchGeneration: Long = 0L
private var currentSwitchGeneration: Long = 0L

private fun observeProfileSwitches() {
    scope.launch {
        profileManager.profileSwitched.collect {
            val gen = ++currentSwitchGeneration
            suppressPushForSwitchGeneration = gen
            pushJob?.cancel()
            pushJob = null
            // Trigger the post-switch pull, which calls clearSuppression(gen) on success.
        }
    }
}

private fun clearSuppression(gen: Long) {
    if (suppressPushForSwitchGeneration == gen) suppressPushForSwitchGeneration = 0L
}
```

---

### WR-04: `LayoutPreferenceDataStore` and `PlayerSettingsDataStore` Migration `init` Blocks Only Run for the Active Profile at Construction Time

**File:** `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt:112-118`, `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt:598-677`

**Issue:** Both `init` blocks call `store().edit { ... }` with no argument, resolving to `profileManager.activeProfileId.value` at singleton construction time (always profile 1 on first launch). Non-primary profiles 2–4 never receive these one-time migrations. Concretely:

- **LayoutPreferenceDataStore:** Profile 2+ will not have `sidebarCollapsedKey` pre-set to `true` (the "sidebar collapsed by default" migration) and will not have `hideUnreleasedContentKey` set to `false`.
- **PlayerSettingsDataStore:** Profiles 2+ will not receive: stream selection defaults V2 (uniform formatting, group streams, etc.), preferred audio language reset to `ORIGINAL`, legacy stream autoplay retirement, or load control buffer retuning.

New profiles will see wrong defaults until the user manually changes the setting or until the remote pull applies correct values.

**Fix:** Move the migration write into the first read of each profile's store. Since `applyLayoutPreferenceMigrations` and `applyPlayerSettingsMigrations` are already idempotent (sentinel-guarded), they can be called inside a `onEach`/`onStart` on the DataStore's `data` flow — or run eagerly inside `profileFlow` on each profile switch:
```kotlin
// In LayoutPreferenceDataStore, replace the init block with:
private fun <T> profileFlow(extract: (prefs: Preferences) -> T): Flow<T> =
    profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data
            .onStart { store(pid).edit { applyLayoutPreferenceMigrations(it) } }
            .map { prefs -> extract(prefs) }
    }
```
Remove the `init { ioScope.launch { store().edit { ... } } }` block.

---

### WR-05: Concurrent Calls to `pullFromRemoteAndApply` Can Prematurely Clear `isApplyingRemote`

**File:** `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt:395-441`

**Issue:** `isApplyingRemote` is set to `true` at line 407 and reset in a `finally` block at line 434. `pullFromRemoteAndApply` is a `public suspend` function callable from external callers (startup sync path). If two concurrent callers overlap — one from the startup sync and one from the conflict-resolution path at line 341 — the second caller sets `isApplyingRemote = true` while the first is already applying, and when the first's `finally` executes it resets `isApplyingRemote = false` while the second caller is still writing settings. This unblocks `schedulePush` mid-apply, allowing a push to race against an in-progress remote settings application.

**Fix:** Use a `Mutex` for the apply critical section:
```kotlin
private val applyingRemoteMutex = Mutex()

// In pullFromRemoteAndApply, replace the boolean flag with:
applyingRemoteMutex.withLock {
    isApplyingRemote = true
    try {
        applyAccountConfigSyncSettings(...)
        applyRemoteSecrets(snapshot.settings)
        lastAppliedRemoteRevision = snapshot.settingsRevision
        // clear pending paths ...
    } finally {
        isApplyingRemote = false
    }
}
```

---

## Info

### IN-01: `SimklViewModel.applyAuthState` Unconditionally Calls `startPollingIfNeeded`, Inconsistent with `TraktViewModel`

**File:** `app/src/main/java/com/nexio/tv/ui/screens/settings/SimklViewModel.kt:180`

**Issue:** `applyAuthState` ends with an unconditional `startPollingIfNeeded(force = false)` call regardless of the resolved `mode`. While `startPollingIfNeeded` internally cancels the job for non-`AWAITING_APPROVAL` modes, this is inconsistent with `TraktViewModel.applyAuthState` which explicitly gates: `if (mode == AWAITING_APPROVAL) startPollingIfNeeded(force = false) else { pollJob?.cancel(); pollJob = null }`. The Simkl pattern is not wrong, but the inconsistency makes the intent harder to verify and wastes a function call on every auth state emission.

**Fix:**
```kotlin
if (mode == SimklConnectionMode.AWAITING_APPROVAL) {
    startPollingIfNeeded(force = false)
} else {
    pollJob?.cancel()
    pollJob = null
}
```

---

### IN-02: User-Visible Error Strings Hardcoded in English in `TraktViewModel` and `SimklViewModel`

**File:** `app/src/main/java/com/nexio/tv/ui/screens/settings/TraktViewModel.kt:112-113`, `128-130`, `381-388`, `402-408`, `412-419`, `423-430`, `SimklViewModel.kt:86-88`

**Issue:** Several user-visible error and status messages are hardcoded English strings rather than string resources. Examples: `"Missing TRAKT_CLIENT_ID or TRAKT_CLIENT_SECRET in local.properties"`, `"Failed to start Trakt auth"`, `"Device code expired. Start again."`, `"Authorization denied on Trakt."`, `"Rate limited, slowing down polling..."`, `"Missing SIMKL_CLIENT_ID in local.properties"`. The app has `values-de`, `values-es`, `values-fr`, `values-nl`, `values-zh-rCN` translation files — these strings will remain in English for all locales.

**Fix:** Add entries to `values/strings.xml` (and translation files) and replace hardcoded strings with `context.getString(R.string.trakt_error_missing_credentials)` etc. `TraktViewModel` already injects `context` for this purpose.

---

### IN-03: `ProfileManager.deleteProfileDataAsync` Deletes DataStore Files Without Guaranteeing the DataStore Flush Is Durable

**File:** `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt:115-129`

**Issue:** `deleteProfileDataAsync` calls `factory.clearProfile(profileId)` — which calls `store.edit { it.clear() }` via `runCatching` — then immediately iterates the filesystem to delete `.preferences_pb` files. DataStore's `edit` is a suspend function; `clearProfile` is itself a `suspend fun` so it correctly awaits the edit. However, `runCatching { store.edit { it.clear() } }` wraps the `edit` coroutine builder inline — confirm that this does not inadvertently convert the `suspend` call to a fire-and-forget (it does not in current Kotlin/coroutine versions, but the `runCatching` wrapper inside a non-inline lambda is worth auditing). If the DataStore write is in-flight when the file is deleted, the DataStore background writer may recreate the file after deletion.

Additionally, the filename suffix check `"_p${profileId}.preferences_pb"` does not account for the DataStore tmp/backup files (`*.preferences_pb.tmp`, `*.preferences_pb.bak`) which DataStore creates during atomic writes. These orphaned temp files should also be cleaned up.

**Fix:**
```kotlin
private suspend fun deleteProfileDataAsync(profileId: Int) {
    if (profileId == 1) return
    factory.clearProfile(profileId)  // awaits DataStore.edit completion
    withContext(Dispatchers.IO) {
        val prefix = "_p${profileId}.preferences_pb"
        val dataStoreDir = File(context.filesDir, "datastore")
        if (dataStoreDir.exists()) {
            dataStoreDir.listFiles()
                ?.filter { f -> f.name.contains("_p${profileId}.preferences_pb") }
                ?.forEach { f -> f.delete() }
        }
    }
}
```
The broader filter `contains("_p${profileId}.preferences_pb")` catches `*.preferences_pb`, `*.preferences_pb.tmp`, and `*.preferences_pb.bak` in one pass.

---

_Reviewed: 2026-04-14_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
