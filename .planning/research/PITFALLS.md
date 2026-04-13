# Pitfalls Research

**Domain:** Adding multi-profile support to existing single-user Android TV / Kotlin / DataStore / Hilt app
**Researched:** 2026-04-14
**Confidence:** HIGH — based on direct inspection of Nexio codebase, NuvioTV reference implementation, and the specific code paths that must change

---

## Critical Pitfalls

### Pitfall 1: Kotlin `preferencesDataStore` delegate creates a process-wide singleton that cannot be replaced

**What goes wrong:**
Every Nexio DataStore today is declared as a Kotlin property delegate:
```kotlin
private val Context.traktAuthDataStore: DataStore<Preferences> by preferencesDataStore(name = "trakt_auth_store")
```
This creates a single global `DataStore` instance bound to the file name. When you try to add per-profile support by keeping this pattern and just reading/writing different keys inside the same store, all profiles share the same backing file. Alternatively, if you remove the delegate and try to call `PreferenceDataStoreFactory.create { ... }` for the same filename a second time within the same process, DataStore throws `IllegalStateException: There are multiple DataStores active for the same file`. This crash is silent until the second profile's DataStore is first accessed.

**Why it happens:**
DataStore enforces a one-instance-per-file invariant at the process level. The `preferencesDataStore` delegate uses an internal singleton registry. Creating a new `PreferenceDataStoreFactory.create` for the same path without going through that registry results in two instances both holding write locks on the same file.

**How to avoid:**
Remove every `by preferencesDataStore(name = "...")` delegate declaration from all per-profile DataStore classes. Replace with `ProfileDataStoreFactory.get(profileId, featureName)` which holds a `ConcurrentHashMap` keyed by `"${featureName}_p${profileId}"` (profile 1 keeps the bare name for zero-migration). Never call `PreferenceDataStoreFactory.create` directly outside the factory. The NuvioTV `ProfileDataStoreFactory` pattern is the correct approach — adopt it verbatim.

**Warning signs:**
- `IllegalStateException: There are multiple DataStores active for the same file` in logcat
- Settings changes written by one profile silently affecting another
- Profile 2's Trakt token showing up in Profile 1's settings screen

**Phase to address:** Foundation phase — ProfileDataStoreFactory must be in place before any other profile work begins. All other pitfalls depend on this being correct first.

---

### Pitfall 2: `TraktAuthDataStore` and `SimklAuthDataStore` become profile-unaware at the token level

**What goes wrong:**
Nexio's `TraktAuthDataStore` injects `@ApplicationContext` and binds directly to `context.traktAuthDataStore` (the process-wide delegate). `TraktAuthService` is `@Singleton` and calls `traktAuthDataStore.state.first()` to get the current token. After a profile switch, `TraktAuthService.executeAuthorizedRequest` still uses the token from the file that was open before the switch — it reads whatever `state.first()` returns, which is still backed by the old singleton file. Profile 2's Trakt API calls go out with Profile 1's access token. Library writes (scrobbles, watch marks) are applied to the wrong Trakt account.

**Why it happens:**
`TraktAuthService` holds a reference to the injected `TraktAuthDataStore` singleton. After a profile switch, the singleton's backing `DataStore` file doesn't change — it is frozen at construction time. There is no `flatMapLatest` on `activeProfileId` because the old design had no concept of a profile.

**How to avoid:**
Port `TraktAuthDataStore` to the NuvioTV pattern exactly: constructor takes `ProfileDataStoreFactory` and `ProfileManager`, `store()` function calls `factory.get(profileId, FEATURE)`, and the `state` Flow uses `profileManager.activeProfileId.flatMapLatest { profileId -> store(profileId).data.map { ... } }`. Apply the same transformation to `SimklAuthDataStore`. Both auth services (`TraktAuthService`, `SimklProgressService`) become automatically profile-aware because they read from the profile-switched flow. Do **not** inject `@ApplicationContext` into the new auth stores.

**Warning signs:**
- After switching from Profile 2 back to Profile 1, Trakt scrobbles arrive in Profile 2's account
- `TraktAuthService.getCurrentAuthState()` returns auth state that doesn't match the UI-shown username after a profile switch
- Integration tests pass with a single profile but fail when a second profile is added to the test fixture

**Phase to address:** OAuth isolation phase — implement as a unit alongside the DataStore factory. Do not split these across phases.

---

### Pitfall 3: `TraktLibrarySnapshotStore`, `ContinueWatchingSnapshotStore`, and `HomeCatalogSnapshotStore` use hardcoded `getSharedPreferences` names and are invisible to the factory

**What goes wrong:**
These three stores use `context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)` with hardcoded names (`"trakt_library_snapshot"`, `"continue_watching_snapshot"`, `"home_catalog_snapshot"`). They are not DataStore files and are not governed by `ProfileDataStoreFactory`. After multi-profile is added, all profiles share the same snapshot — Profile 2's library renders the cached data from Profile 1 until a full network refresh. Profile deletion cleanup in `ProfileManager.deleteProfileDataAsync` scans for DataStore `.preferences_pb` files but misses these `.xml` SharedPreferences files entirely, leaving orphaned data on disk.

**Why it happens:**
SharedPreferences files live in `shared_prefs/` not `datastore/`. The suffix-scanning cleanup loop in NuvioTV (and the future Nexio equivalent) only matches `_p${profileId}.preferences_pb`. SharedPreferences have no matching naming convention in the reference implementation.

**How to avoid:**
For `TraktLibrarySnapshotStore` specifically: it is profile-specific (Trakt library is per-account) and must be migrated to per-profile naming: `"trakt_library_snapshot_p${profileId}"` or moved to DataStore via the factory. For `ContinueWatchingSnapshotStore`: determine whether continue-watching is per-profile. If yes, same treatment. For `HomeCatalogSnapshotStore`: this is likely shared and can remain global. Add explicit per-profile SharedPreferences cleanup in `deleteProfileDataAsync` using the same suffix pattern as the DataStore cleanup loop.

**Warning signs:**
- Profile 1's library entries appear immediately when switching to a fresh Profile 2 (before network load)
- After deleting Profile 2, `shared_prefs/trakt_library_snapshot_p2.xml` still exists on-device
- `adb shell run-as com.nexio.tv ls files/datastore` shows no orphan but `shared_prefs/` has stale files

**Phase to address:** Profile deletion cleanup phase — audit all stores (DataStore and SharedPreferences) as a checklist, not just DataStore files.

---

### Pitfall 4: Hilt `@Singleton` services remain singleton even after the DataStore they depend on becomes profile-scoped

**What goes wrong:**
Services like `TraktLibraryService`, `SimklProgressService`, and `AccountSettingsSyncService` are `@Singleton` and hold references to auth DataStores and settings DataStores injected at construction. Converting the DataStores to profile-aware doesn't automatically make the services profile-aware unless the service re-reads the store on every operation. The `tokenRefreshMutex` in `TraktAuthService` is per-singleton — if Profile 1 and Profile 2 share the same `TraktAuthService` instance and one profile triggers a refresh while the other is making a request, the mutex protects the wrong token. A `@Singleton` service that caches a `StateFlow` captured at init time (rather than following the `flatMapLatest` pattern) will serve stale per-profile state for the lifetime of the process.

**Why it happens:**
Hilt does not have a per-profile scope. There is no natural DI scope between `@Singleton` (process-wide) and `@ActivityScoped` (too narrow). Developers assume that because the DataStore is now profile-aware, the services reading from it are also profile-aware — but only the Flow-based reactive paths automatically follow profile switches. Any code path that calls `dataStore.state.first()` in a non-reactive context (e.g. `executeAuthorizedRequest` calls `getCurrentAuthState()` which calls `state.first()`) reads from the _current_ active profile's store at the moment of the call. This is actually correct IF the `state` Flow is properly hooked to `flatMapLatest` on `activeProfileId`. If it is not, `first()` returns from the globally-scoped store.

**How to avoid:**
Do not introduce a profile-scoped Hilt component. Instead, follow the NuvioTV pattern: keep all services as `@Singleton`, but make every DataStore they depend on profile-reactive via `flatMapLatest(activeProfileId)`. The key invariant: every `store()` call in a profile-aware DataStore must call `factory.get(profileManager.activeProfileId.value, FEATURE)` at call-time, not at construction time. Verify this by checking that no DataStore class stores a `val dataStore = ...` field referencing a fixed file.

**Warning signs:**
- `TraktAuthService.refreshTokenIfNeeded` completes successfully but the new token is written to the wrong profile's file
- `AccountSettingsSyncService` push fires after profile switch and overwrites Profile 1 settings with Profile 2's data
- Unit tests that swap `activeProfileId` don't see state changes in services that were already instantiated

**Phase to address:** Foundation phase, during ProfileDataStoreFactory implementation. Add a review checklist: for every `@Singleton` that holds a DataStore reference, verify the reference is resolved dynamically per call.

---

### Pitfall 5: `AccountConfigSyncContract` (v7) mixes shared and per-profile settings in a single payload with no profile discriminator

**What goes wrong:**
The existing `AccountSettingsSyncService` pushes/pulls a single `AccountConfigSyncPayload` (contract version 7) that includes both shared settings (debrid, TMDB, addons) and per-profile settings (Trakt auth, Simkl auth, catalog preferences, theme, player). After multi-profile is added, a push from Profile 2 will overwrite Profile 1's Trakt auth in the remote payload. A pull to Profile 1 after Profile 2 has pushed will clobber Profile 1's Trakt token with Profile 2's token. The Supabase RPC `sync_push_settings_v7` has no concept of `profile_id` in its parameters.

**Why it happens:**
The original design assumed one account = one set of settings. The v7 payload is structurally flat. Extending it by adding a `profileId` field to the existing contract breaks existing clients on v7 that don't send a profile ID.

**How to avoid:**
Follow the NuvioTV split: keep the existing `AccountConfigSyncContract` (v7) for shared settings only (strip out Trakt auth, Simkl auth, catalog prefs, theme, player from the payload). Add a new separate `ProfileSettingsSyncService` (modeled on NuvioTV's implementation) that uses `sync_push_profile_settings_blob` / `sync_pull_profile_settings_blob` RPCs with an explicit `p_profile_id` parameter. The Supabase schema needs a `profile_settings_blobs` table keyed by `(user_id, profile_id, platform)`. Do not attempt to add profile_id to the v7 contract — create a parallel mechanism.

**Warning signs:**
- After Profile 2 logs into Trakt, Profile 1's TraktScreen shows Profile 2's username
- Remote sync pull on Profile 1 clears its Trakt authentication
- Supabase `account_config_snapshots` table has no `profile_id` column in the existing schema

**Phase to address:** Supabase sync phase — requires both schema migration (add `profile_settings_blobs` table) and a new sync service. Must come after OAuth isolation is complete so the correct tokens exist per profile.

---

### Pitfall 6: Profile switch is not atomic — in-flight operations race against the new active profile

**What goes wrong:**
`ProfileManager.setActiveProfile(id)` writes the new `activeProfileId` to the profile DataStore. The `ProfileDataStoreFactory.store()` call resolves the active profile ID at call-time from `profileManager.activeProfileId.value`. Between the moment `setActiveProfile` is called and the moment `activeProfileId.value` updates in the StateFlow (there is a coroutine emission lag), any concurrent `store()` call can resolve to either the old or new profile. If a Trakt scrobble fires during profile switch (e.g. the user is mid-episode when they switch), the scrobble can land in an indeterminate profile's token store.

**Why it happens:**
`StateFlow.value` is synchronous but the update to it is posted from a coroutine on `Dispatchers.IO`. Between the suspend call to `dataStore.edit` and the StateFlow emission propagating to all collectors, there is a window where `activeProfileId.value` still reflects the old profile. The `TraktMutationOutboxCoordinator` may have pending mutations that continue executing after the profile ID changes.

**How to avoid:**
Add a profile-switch gate: before writing the new `activeProfileId`, drain the Trakt/Simkl outbox for the current profile and cancel any in-flight sync jobs. Model this after NuvioTV's `ProfileSettingsSyncService.syncMutex`. Suspend point in `setActiveProfile` should wait for in-flight operations to complete (or cancel them with a timeout). For the scrobble case: the `TraktMutationOutboxCoordinator` should tag each queued mutation with the `profileId` active at enqueue time, not at execution time.

**Warning signs:**
- Scrobble appears in the wrong Trakt account after switching profiles while a show is playing
- `AccountSettingsSyncService` push fires with the new profile's data but the old profile's token
- Integration tests that switch profiles mid-operation produce flaky results

**Phase to address:** Profile switching phase — explicitly design the switch sequence as: pause sync → flush outbox → write new activeProfileId → resume sync on new profile.

---

### Pitfall 7: Data migration from single-profile to multi-profile — Profile 1 must not lose existing data

**What goes wrong:**
All current Nexio DataStore files use bare names: `"trakt_auth_store"`, `"simkl_auth_store"`, `"simkl_settings"`, etc. The `ProfileDataStoreFactory` assigns Profile 1 the bare name (no `_p1` suffix) deliberately. But the `TraktLibrarySnapshotStore` and `ContinueWatchingSnapshotStore` use `getSharedPreferences("trakt_library_snapshot")`. If the migration accidentally adds a `_p1` suffix to these SharedPreferences names during refactoring, the existing user's library snapshot becomes invisible and they see an empty library until a full network refresh. Similarly, if `TraktAuthDataStore` is naively renamed to `"trakt_auth_store_p1"` instead of keeping `"trakt_auth_store"` for profile 1, all existing users are logged out of Trakt on first launch of the update.

**Why it happens:**
Developers refactoring the DataStore naming to follow the `_p${profileId}` convention apply it uniformly without special-casing Profile 1. The NuvioTV factory correctly special-cases this: `val fileName = if (profileId == 1) featureName else "${featureName}_p${profileId}"`. Forgetting this for SharedPreferences stores or misapplying it to the DataStore factory breaks the zero-migration guarantee for existing users.

**How to avoid:**
Enforce the rule: Profile 1 always uses the bare filename. Write a migration test that: (1) creates a DataStore with the old bare name, writes a token, (2) constructs `ProfileDataStoreFactory`, calls `get(1, featureName)`, and asserts the token is still readable. Do the same for SharedPreferences snapshot stores. Add a comment to every affected class noting that the `_p1` suffix must never be used.

**Warning signs:**
- Existing beta users report being logged out of Trakt after installing the multi-profile update
- `TraktAuthService.getCurrentAuthState().isAuthenticated` returns false after update for Profile 1 users
- Crashlytics shows a spike in "No active Trakt device code" errors after the update ships

**Phase to address:** Foundation phase, before any code ships to users. Write migration tests for every DataStore class being refactored.

---

### Pitfall 8: PIN lock bypassed by navigating directly to the profile's settings screen

**What goes wrong:**
The PIN lock is enforced in the profile selection screen (the screen shown when switching profiles). However, if the app's back-stack allows navigating directly to settings without going through profile selection (e.g. a deep link, a settings shortcut from the home screen, or pressing back into the settings stack), the PIN check is never triggered. Profile 2's Trakt account and watch history become accessible without PIN entry.

**Why it happens:**
The PIN check is a UI-layer concern placed only at the profile selection entry point. Any navigation path that bypasses that screen also bypasses the check. Android TV deep links and back-stack manipulation make this likely to be exploited unintentionally (a user re-entering a settings screen they were last on).

**How to avoid:**
Store PIN lock state as a session-scoped boolean in the `ProfileManager` (e.g. `unlockedProfileIds: Set<Int>`), cleared on process restart. Check this in the ViewModel of any screen that shows per-profile data (not just the profile selection screen). The `ProfileSelectionViewModel` already has `isProfilePinEnabled` — extend this to a `isProfileUnlocked(profileId)` gate that ViewModels check before emitting profile-scoped state. NuvioTV's `ProfileSyncService` includes `verifyProfilePin` as a remote check — mirror this.

**Warning signs:**
- Navigating back from Settings to the home screen and then forward to Settings again bypasses the PIN prompt
- After process death and restart, the previous profile is auto-selected without PIN re-entry
- Users report seeing another household member's watch history without entering a PIN

**Phase to address:** PIN lock phase — design the unlock session state in `ProfileManager` from the start rather than adding it as an afterthought.

---

### Pitfall 9: Orphaned DataStore files and SharedPreferences after profile deletion

**What goes wrong:**
NuvioTV's `deleteProfileDataAsync` scans `context.filesDir/datastore/` for files ending in `_p${profileId}.preferences_pb` and deletes them. This correctly handles DataStore proto files. However:
1. SharedPreferences files live in `context.filesDir/../shared_prefs/` (not `filesDir/datastore/`) — NuvioTV's scan misses them.
2. The `deletedProfileIds` set in `ProfileDataStoreFactory` prevents re-use of the cached (now-cleared) DataStore instance, but `cache.remove(key)` removes it from the in-memory cache. If any coroutine that was using the old store instance completes its `edit` call after `clearProfile` has returned, it writes to the already-deleted file (the DataStore instance is still alive in the caller's stack frame even though it was removed from the cache).

**Why it happens:**
The cache eviction and file deletion are not atomic with respect to in-flight `edit` calls. DataStore's `edit` lambda is a suspend function — a caller may suspend before the lambda runs, and during that suspension the profile gets deleted. The lambda then writes to a store that the cleanup code considered gone.

**How to avoid:**
Before calling `factory.clearProfile(profileId)`, wait for all in-flight DataStore operations on that profile to complete. The simplest approach: track a `Mutex` per profile ID in the factory; `clearProfile` acquires all active profile mutexes before clearing. For SharedPreferences orphans: add a dedicated `deleteProfileSharedPreferences(profileId, context)` function that deletes `shared_prefs/trakt_library_snapshot_p${profileId}.xml`, etc. Include this in `deleteProfileDataAsync`. NuvioTV's `ProfileSyncService.deleteProfileData` handles the remote side — ensure local cleanup is also complete before the remote call.

**Warning signs:**
- After deleting Profile 2 and recreating it, Profile 2 has stale settings from the previous Profile 2 (file was not deleted, just removed from cache)
- `adb shell run-as com.nexio.tv ls shared_prefs/` shows files like `trakt_library_snapshot_p2.xml` after Profile 2 is deleted
- `edit` calls throw exceptions after profile deletion in stress tests

**Phase to address:** Profile deletion cleanup phase — write a deletion test that creates a profile, populates all its stores, deletes it, and asserts zero remaining files with the profile suffix.

---

### Pitfall 10: Android TV D-pad navigation — profile selection grid loses focus after state update

**What goes wrong:**
Profile selection grids rendered with `LazyVerticalGrid` or `TvLazyVerticalGrid` lose D-pad focus when the underlying list state changes (e.g. after a profile is created or deleted). The focused item index shifts because the list recomposes with a different item count. On Android TV, lost focus is not recovered automatically — the user sees a grid with no highlighted item and D-pad inputs are ignored until they navigate away and back.

**Why it happens:**
Compose for TV's `TvLazyVerticalGrid` does not persist focus across recompositions by default when the item count changes. The `FocusRequester` pattern used in Nexio's existing TV screens assumes a stable list. Profile creation/deletion changes the list size mid-session.

**How to avoid:**
Use `remember { FocusRequester() }` scoped to each profile card and explicitly call `focusRequester.requestFocus()` on the previously-focused item after a state update. Alternatively, use `key(profile.id)` in the grid's item block so Compose re-uses existing items by identity rather than position. Test all operations (create, delete, edit, switch) with D-pad only — no touch input. Profile creation in `ProfileSelectionViewModel` already uses an `_isCreating` guard — add a post-creation `LaunchedEffect` that re-requests focus on the newly created profile card.

**Warning signs:**
- After creating a new profile, no card is highlighted and D-pad inputs do nothing
- After deleting a profile, focus jumps to an unexpected position or disappears
- The profile selection screen becomes unusable via remote after any list mutation

**Phase to address:** Profile selection UI phase — must be tested exclusively with a physical remote or D-pad emulator, not with mouse/touch.

---

### Pitfall 11: Profile selection screen shown on cold start for single-profile users

**What goes wrong:**
The opt-in rule — profile selection only shown when 2+ profiles exist — is implemented correctly in `ProfileManager`. But if the profile list is loaded from DataStore asynchronously, there is a window at cold start where `profiles.value` is the default `listOf(UserProfile(id=1, ...))` (the `SharingStarted.Eagerly` initial value) and the startup navigation logic reads this before the DataStore has emitted. If the navigation guard checks `profiles.value.size >= 2` during this window, it correctly doesn't show the screen. But if the startup pull from Supabase (via `ProfileSyncService.pullFromRemote`) completes and adds a second profile after navigation has already committed, the profile selection screen is never shown for a user who should have seen it.

**Why it happens:**
`ProfileManager.profiles` is a `StateFlow` initialized with `SharingStarted.Eagerly` and a default of a single profile. Navigation decisions made before the Supabase pull completes use the default, not the true state. This is a TOCTOU issue: the time of check (navigation) precedes the time of truth (remote pull result).

**How to avoid:**
Move the "show profile selection?" decision to after the startup Supabase pull completes. Add a `profilesReadyForNavigation: StateFlow<Boolean>` to `ProfileManager` that emits `false` until the first DataStore emission arrives (not the default). The startup sync sequence should be: load local profiles → navigate if multi-profile → (then in background) pull remote profiles → if profile count changed, trigger navigation update. Never use `profiles.value` for navigation; always collect from the Flow.

**Warning signs:**
- A user with two profiles on a fresh install never sees the profile selection screen
- Adding a second profile on another device doesn't trigger the profile selection screen on the TV after the next startup sync
- `ProfileManager.profiles.value` returns `[Profile 1]` at the time navigation runs, even though the DataStore contains two profiles

**Phase to address:** Profile selection UI phase — specifically the startup navigation logic.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Keep `TraktAuthService.tokenRefreshMutex` as a single Mutex for all profiles | Avoids per-profile mutex complexity | Profile 1's token refresh blocks Profile 2's concurrent request | Never — use per-profile keyed mutexes or scope the mutex to the current profile's operation |
| Store `activeProfileId` as a plain Int field instead of StateFlow | Simpler reads in service code | Profile switches don't propagate reactively; services use stale profile ID | Never — all profile-aware code must react to StateFlow |
| Keep `AccountConfigSyncContract` v7 and add `profileId` to it | Avoids new Supabase RPC | Breaks existing app versions; requires coordinated rollout with backend | Only if Supabase migration can guarantee all clients update simultaneously — impractical |
| Implement profile selection as a modal dialog over home screen | Faster to build than a dedicated screen | D-pad focus management in dialogs on TV is extremely fragile | MVP only, replace with dedicated screen before GA |
| Skip per-profile SharedPreferences cleanup on deletion | Saves implementation effort | Orphaned `.xml` files accumulate across profile cycles; data from a deleted profile leaks into a re-created profile with the same ID | Never — profile ID reuse (IDs 2-4 cycle) makes this a guaranteed data corruption scenario |

---

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| Trakt OAuth device flow | Starting a device flow for Profile 2 while Profile 1 still has an in-progress device flow (deviceCode stored in Profile 1's store) | `saveDeviceFlow` writes to `store()` which resolves current profile at call-time. Ensure the profile is fully switched before starting a new device flow. Clear the device flow on the previous profile explicitly during switch. |
| Simkl OAuth | Simkl uses a PIN-based flow with a `deviceCode` stored in `SimklAuthDataStore`. Same singleton-to-factory migration applies — `context.simklAuthDataStore` delegate must be replaced. | Replace with `factory.get(profileId, "simkl_auth_store")` following the same NuvioTV TraktAuthDataStore pattern. |
| Supabase `sync_push_profile_settings_blob` | Calling the RPC without a valid `p_profile_id` (passing 0 or null) silently upserts to the wrong row | Always validate `profileId >= 1` before any profile RPC call. `ProfileManager` guarantees IDs 1-4; add an assertion. |
| `AccountConfigSyncContract` v7 shared settings push | After profile switch, the shared settings push fires because the DataStore change observer sees `simklAuthState` change (profile switch causes a new emission) | The v7 sync observer should ignore changes from per-profile stores (Trakt auth, Simkl auth) if those are moved out of the v7 payload. Remove them from `observeAccountConfigSyncChanges`. |
| Profile photo via nexio-web / Supabase Storage | Profile photo URL stored as `avatarId` in `ProfileDataStore` (global store). If `AvatarRepository.getAvatarImageUrl` is called before the avatar catalog is loaded, it returns null and the profile card renders without an avatar permanently. | Add a loading state to `ProfileSelectionViewModel.avatarCatalog` and defer profile card rendering until catalog is loaded, or use a placeholder that updates on catalog arrival. |

---

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| `ProfileDataStoreFactory.get()` called in a tight loop | Each call resolves from `ConcurrentHashMap` — cheap, but if called inside `LazyVerticalGrid`'s item composable on every recomposition, it triggers coroutine collections | Cache the resolved `DataStore` reference at ViewModel init time, not in the composable | With 4 profiles and frequent recompositions (focus changes) |
| `flatMapLatest(activeProfileId)` on every DataStore restarts the upstream Flow on every profile switch | Each switch cancels and re-subscribes all active collectors — expected — but if `SharingStarted.WhileSubscribed(5000)` is used, there is a 5s window where the old profile's data is still in cache | Use `SharingStarted.Eagerly` in `ProfileManager` StateFlows that drive navigation decisions | Immediately on first profile switch |
| `ProfileSettingsSyncService.exportSettingsBlob` iterates all synced features and reads each DataStore synchronously via `.first()` | A full export reads 8+ DataStore files serially; on slow storage this takes hundreds of ms | Run export on `Dispatchers.IO` (already done in NuvioTV), and debounce the push trigger (NuvioTV uses 1500ms debounce) | Not a scaling issue, but will cause UI jank if called on Main thread |
| NuvioTV `ProfileDataStoreFactory.clearProfile` calls `store.edit { it.clear() }` for each cached store — DataStore `edit` is a suspend function queued on an internal coroutine | If called from a scope that is being cancelled (e.g. `viewModelScope` during profile deletion), the `edit` may not complete | Call `clearProfile` from a `SupervisorJob` scope that outlives the ViewModel, as done in `ProfileManager` | On low-memory devices where the OS kills activities during profile deletion |

---

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Trakt/Simkl access tokens stored in per-profile DataStore files with default permissions | DataStore files in `filesDir/datastore/` are private to the app on non-rooted devices. On rooted devices all tokens are readable. No additional risk introduced by multi-profile — same as current single-profile design. | Acceptable — no change needed. Document that tokens are app-private, not hardware-backed. |
| PIN hash stored locally in a DataStore | If stored as plain text or a reversible encoding, the PIN can be extracted from the DataStore file on rooted devices | NuvioTV stores PINs server-side in Supabase (via `set_profile_pin` RPC with server-side hashing). Follow this — never store the raw PIN or a client-side hash locally. Only store `pinEnabled: Boolean`. |
| Profile IDs are predictable integers 1-4 | An attacker who can manipulate the `activeProfileId` DataStore value (rooted device) can switch to any profile without PIN | Acceptable risk for a household app — equivalent to physical access to the device. Do not design the security model around rooted-device threat. |
| `verifyProfilePin` RPC sends the PIN as a plain string in the JSON request body | The PIN is transmitted over HTTPS to Supabase. Rate limiting is enforced server-side (`retryAfterSeconds` in the response). | Ensure all Supabase calls use HTTPS (enforced by Supabase client). Implement the server-side rate limit response handling — `ProfileSelectionViewModel.verifyProfilePin` must respect `retryAfterSeconds` and show a countdown UI. |

---

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| Showing profile selection on every app launch even when only one profile exists | Single-profile households (the majority) get an unwanted extra screen on every cold start | Enforce the opt-in rule at the routing level: `profileManager.profiles.value.size >= 2` gates the profile selection route. Test this after adding and then deleting a second profile — ensure the screen stops appearing. |
| No visual feedback during profile switch on a slow device (DataStore write takes 100-300ms) | User taps a profile card, nothing appears to happen, they tap again, and the app switches twice | Show an immediate loading indicator on the selected profile card. Use `_isLoading: MutableStateFlow<Boolean>` in `ProfileSelectionViewModel.selectProfile`. |
| Profile deletion requires too many D-pad presses to confirm | Accidental deletions are irrecoverable (all per-profile data is gone) | Require a separate confirmation step — navigate to a confirmation screen rather than using a dialog (dialogs are focus-management nightmares on TV). Consider a double-confirm if the profile has a Trakt account linked. |
| Profile name input on Android TV requires the system keyboard | Android TV system keyboard is slow to appear and hard to use with a remote | Use a pre-set list of names (like Netflix's "Kid", "Teen", custom name entry optional), or a character grid. If free text is required, limit to 20 characters and debounce input validation. |
| Active profile indicator not visible during settings navigation | Users lose track of which profile's settings they are editing | Show the active profile name/avatar in the settings screen header as a non-interactive badge. Refresh it reactively from `ProfileManager.activeProfile`. |

---

## "Looks Done But Isn't" Checklist

- [ ] **Profile 1 data migration:** Verify `factory.get(1, "trakt_auth_store")` resolves to `filesDir/datastore/trakt_auth_store.preferences_pb` (bare name, no suffix). Run this check against a DataStore populated with real tokens before shipping.
- [ ] **Profile deletion cleanup:** After deleting Profile 2, check BOTH `filesDir/datastore/` AND `shared_prefs/` for any file ending in `_p2`. Automate as an instrumented test.
- [ ] **SharedPreferences snapshot stores scoped correctly:** `TraktLibrarySnapshotStore` uses `PREFS_NAME = "trakt_library_snapshot"` with no profile suffix. Verify whether it is per-profile or global and update accordingly. This is easy to miss because it is not a DataStore file.
- [ ] **Trakt scrobble on correct profile after switch:** Play a video on Profile 1, switch to Profile 2, play a video, verify Trakt histories. Check both accounts in the Trakt web UI.
- [ ] **AccountConfigSyncContract v7 no longer includes per-profile fields:** After the split, verify that `buildAccountConfigSyncPayload` does not include `traktAuthState` or `simklAuthState` in the shared payload.
- [ ] **PIN cannot be bypassed via back navigation:** From Profile 2 (PIN-locked), navigate to settings, press back to home, navigate forward to settings again. Verify the PIN prompt reappears (it should, if the session unlock state is stored in memory only).
- [ ] **Profile selection screen absent for single-profile users:** Create a profile, populate it, delete it. Verify the profile selection screen no longer appears at startup.
- [ ] **D-pad focus preserved after profile list mutation:** Create a profile via D-pad, verify focus lands on the new profile card. Delete a profile via D-pad, verify focus lands on an adjacent card, not nowhere.

---

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Wrong DataStore file naming causes data loss for existing Profile 1 users | HIGH | Emergency patch release; cannot recover data already lost. Mitigate: add a one-time migration that checks if `trakt_auth_store.preferences_pb` exists before attempting factory migration. |
| TraktAuthService writing tokens to wrong profile due to missed `flatMapLatest` | MEDIUM | Hotfix: add `flatMapLatest(activeProfileId)` to `TraktAuthDataStore.state`. Existing wrong-profile tokens must be cleared. Add a `clearAuth()` call during the fix deployment if cross-contamination is detected. |
| Orphaned SharedPreferences files leaking cross-profile data | LOW | Add cleanup on next profile creation: if a profile with ID N is being created and `_pN.xml` files already exist from a previous deleted profile, clear them. |
| Profile switch race condition causing wrong-account scrobble | MEDIUM | Instrument the outbox with profile tags; add a validation step in `TraktMutationOutboxCoordinator.execute` that checks `mutation.profileId == profileManager.activeProfileId.value` before executing. |
| AccountConfigSyncContract v7 payload contamination between profiles | HIGH | Requires coordinated backend + client rollout. Add a `profileId` field to the v7 payload as a non-breaking addition; backend ignores it but client can use it for validation. Full fix requires the NuvioTV split approach. |

---

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| `preferencesDataStore` delegate singleton conflict | Phase 1 (Foundation: ProfileDataStoreFactory) | Unit test: two `factory.get(profileId, feature)` calls return same instance; `factory.get(1, f)` opens bare filename |
| TraktAuthDataStore not profile-aware | Phase 2 (OAuth isolation) | Integration test: switch activeProfileId, verify `state.first()` reflects new profile's token |
| SimklAuthDataStore not profile-aware | Phase 2 (OAuth isolation) | Same as above for Simkl |
| SharedPreferences snapshot stores not scoped | Phase 3 (Profile data isolation audit) | Manual checklist + instrumented file scan after operations |
| Hilt @Singleton services using stale DataStore reference | Phase 1 (Foundation) | Code review checklist: no DataStore field assigned at construction time in any service |
| AccountConfigSyncContract v7 mixing shared/per-profile data | Phase 4 (Supabase sync split) | Integration test: Profile 2 push does not modify Profile 1's Trakt auth in Supabase |
| Profile switch race with in-flight operations | Phase 5 (Profile switching UX) | Stress test: rapid profile switches during active scrobble |
| Data migration Profile 1 not losing data | Phase 1 (Foundation) | Migration test: populate bare-name DataStore, install new code, verify data intact |
| PIN lock bypassable via back navigation | Phase 6 (PIN lock) | Manual QA: back-navigate through all routes, verify PIN re-prompt |
| Orphaned files after profile deletion | Phase 3 (Profile data isolation) | Instrumented test: delete profile, scan both `datastore/` and `shared_prefs/` |
| D-pad focus lost after list mutation | Phase 5 (Profile selection UI) | Manual QA with physical remote only |
| Profile selection shown on cold start single-profile | Phase 5 (Profile selection UI) | Automated test: single profile, simulate cold start, verify no profile selection route entered |

---

## Sources

- Direct inspection of `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/TraktAuthDataStore.kt` — confirmed `by preferencesDataStore(name = "trakt_auth_store")` delegate pattern
- Direct inspection of `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/SimklAuthDataStore.kt` — same pattern
- Direct inspection of `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt` — confirmed `getSharedPreferences("trakt_library_snapshot")` hardcoded
- Direct inspection of `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt` and `HomeCatalogSnapshotStore.kt` — same SharedPreferences pattern
- Direct inspection of `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt` — confirmed v7 contract includes both shared and per-profile fields in single payload
- NuvioTV reference: `ProfileDataStoreFactory.kt` — ConcurrentHashMap, `deletedProfileIds`, profile 1 bare-name rule
- NuvioTV reference: `TraktAuthDataStore.kt` — `flatMapLatest(activeProfileId)` pattern, `store()` resolves at call-time
- NuvioTV reference: `ProfileSettingsSyncService.kt` — profile blob sync pattern, `syncMutex`, `applyingRemoteBlob` guard
- NuvioTV reference: `ProfileManager.kt` — `deleteProfileDataAsync` suffix scan (DataStore only, SharedPreferences not included — confirmed gap)
- NuvioTV reference: `ProfileSyncService.kt` — `p_profile_id` parameter on Supabase RPCs, PIN management RPCs

---
*Pitfalls research for: Multi-profile support migration — Nexio Android TV (Kotlin/DataStore/Hilt)*
*Researched: 2026-04-14*
