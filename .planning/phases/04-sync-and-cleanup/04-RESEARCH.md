# Phase 4: Sync and Cleanup - Research

**Researched:** 2026-04-14
**Domain:** Supabase sync (profile metadata + per-profile settings blob), SharedPreferences per-profile scoping, profile deletion cleanup
**Confidence:** HIGH

## Summary

Phase 4 adds three capabilities to Nexio: (1) syncing profile metadata to Supabase so profiles survive device changes, (2) syncing per-profile settings as independent JSON blobs so cross-profile overwrites are impossible, and (3) ensuring profile deletion leaves zero orphaned data locally or remotely. All three capabilities have proven reference implementations in NuvioTV that can be ported with minimal adaptation.

The NuvioTV `ProfileSyncService` and `ProfileSettingsSyncService` are the canonical sources. The profile metadata sync is a straightforward push/pull of the full profile list via `sync_push_profiles` / `sync_pull_profiles` RPCs. The per-profile settings sync serializes raw DataStore Preferences into a typed JSON blob (`{type, value}` encoding), pushes/pulls via `sync_push_profile_settings_blob` / `sync_pull_profile_settings_blob` RPCs, and uses a debounced `flatMapLatest` observer to auto-push on local changes. Both services use the established `withJwtRefreshRetry` pattern and kotlinx.serialization JSON builders.

The SharedPreferences migration is the most mechanically intensive part: 10 SharedPreferences-backed stores need per-profile naming (7 snapshot stores + TraktMutationOutboxStore + SimklProgressSyncStateStore + SyntheticHomeCatalogStore). Each store currently uses a hardcoded `PREFS_NAME` constant; per-profile naming follows the DataStore convention (bare name for profile 1, `{name}_p{id}` for profiles 2-4). Profile deletion must clean up all these files plus invoke remote deletion RPCs and best-effort OAuth token revocation.

**Primary recommendation:** Port NuvioTV's ProfileSyncService and ProfileSettingsSyncService as closely as possible, adapt the syncedFeatures list to Nexio's per-profile DataStore feature names, make all 10 SharedPreferences stores profile-aware via a `profileSuffix(profileId)` helper, and extend `ProfileManager.deleteProfileDataAsync()` to cover SP files + remote cleanup + token revocation.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** All 7 SharedPreferences snapshot stores become per-profile with profile-suffixed filenames: TraktLibrarySnapshotStore, ContinueWatchingSnapshotStore, SimklLibrarySnapshotStore, SimklDiscoverySnapshotStore, TraktDiscoverySnapshotStore, MDBListDiscoverySnapshotStore, HomeCatalogSnapshotStore. Profile 1 keeps bare names for zero-migration.
- **D-02:** Additional SharedPreferences stores classified per-profile where tied to auth/profile state: SimklProgressSyncStateStore, TraktMutationOutboxStore, SyntheticHomeCatalogStore become per-profile. MetadataDiskCacheStore and CatalogDiskCacheStore stay shared (content metadata doesn't change per profile).
- **D-03:** Port NuvioTV's ProfileSettingsSyncService blob pattern. New service serializes all 8 per-profile DataStores into one JSON blob per profile. Push/pull via dedicated Supabase RPCs (sync_push_profile_settings / sync_pull_profile_settings). Runs alongside existing v7 shared sync.
- **D-04:** Existing v7 shared sync contract stays untouched. Per-profile settings that were previously in v7 (Trakt catalog prefs, Simkl catalog prefs, player formatter, tracking provider) get removed from v7 and move to per-profile blob only. Clean separation.
- **D-05:** Profile metadata sync uses dedicated RPCs: sync_push_profiles / sync_pull_profiles, ported from NuvioTV's ProfileSyncService. Metadata (name, avatar, PIN state) synced as full profile list.
- **D-06:** Supabase RPCs and table schema need to be created as part of this phase. Backend SQL functions are in-scope.
- **D-07:** Per-profile settings push uses debounced-on-change pattern (~1.5s debounce), ported from NuvioTV. Observe per-profile DataStore changes via flatMapLatest, auto-push blob after debounce.
- **D-08:** Per-profile settings pull on app startup (after auth) and on profile switch. Ensures fresh data from cross-device changes.
- **D-09:** Profile metadata push immediately on any create/edit/delete operation. No debounce needed -- metadata changes are infrequent and small.
- **D-10:** SharedPreferences snapshot files use per-profile naming scheme (e.g. `trakt_library_snapshot_p2`). On deletion, delete the matching SP file by suffix. Profile 1 keeps bare names for zero-migration. Consistent with DataStore naming convention.
- **D-11:** Profile deletion calls a sync_delete_profile Supabase RPC that removes the profile row and its settings blob server-side. No cloud orphans.
- **D-12:** Profile deletion revokes Trakt and Simkl OAuth tokens remotely before discarding local tokens. Prevents orphaned authorized apps in the user's Trakt/Simkl account.
- **D-13:** Remote cleanup (token revocation, Supabase deletion) is best-effort. Attempt revocation and remote delete, but always complete local deletion regardless of network failures. Log failures for potential retry on next sync. Never block the user from deleting a profile.

### Claude's Discretion
- Order of snapshot store migrations (can batch or sequence)
- Exact JSON blob schema for per-profile settings (serialize DataStore preferences as key-value pairs vs structured sections)
- Supabase SQL function signatures and table schema design
- Pull-before-push gate logic for per-profile settings (analogous to existing AccountConfigStartupPushGate)
- Error retry strategy for failed remote cleanup on deletion

### Deferred Ideas (OUT OF SCOPE)
None -- discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SYNC-01 | Profile metadata (name, avatar, PIN state) syncs to Supabase | ProfileSyncService port pattern (D-05, D-09); SupabaseProfile model needs avatarId + pinEnabled fields; push/pull RPCs documented |
| SYNC-02 | Per-profile settings sync via independent blob push/pull (not v7 contract) | ProfileSettingsSyncService port pattern (D-03, D-07, D-08); syncedFeatures list mapped to Nexio; v7 contract removal paths identified (D-04) |
| SYNC-03 | Profile deletion removes all DataStore files, SharedPreferences, and Supabase remote data | Deletion flow architecture documented; 10 SP stores + DataStore files + remote RPC + token revocation (D-10 through D-13) |
| SYNC-04 | Snapshot stores classified and scoped per-profile where applicable | Full classification complete: 10 stores per-profile, 2 stores shared (D-01, D-02); migration pattern documented |
</phase_requirements>

## Standard Stack

### Core (already in project)
| Library | Purpose | Why Standard |
|---------|---------|--------------|
| kotlinx.serialization | JSON encoding for Supabase RPC params and blob serialization | Already used throughout sync layer (buildJsonObject, put, JsonObject, JsonPrimitive) [VERIFIED: codebase grep] |
| Gson | SharedPreferences snapshot store serialization | All 7 snapshot stores use Gson internally; no migration needed [VERIFIED: codebase grep] |
| Supabase Postgrest | RPC calls to Supabase backend | Already used via `postgrest.rpc()` pattern in SyncRepositoryImpl and AccountSettingsSyncService [VERIFIED: codebase grep] |
| Hilt/Dagger | Dependency injection for new sync services | All existing services use `@Inject constructor` + `@Singleton` [VERIFIED: codebase grep] |
| Kotlin Coroutines + Flow | Reactive observation, debounce, flatMapLatest | Exact patterns from NuvioTV ProfileSettingsSyncService [VERIFIED: NuvioTV source] |
| DataStore Preferences | Per-profile settings storage via ProfileDataStoreFactory | Phase 2 already migrated 4 stores; Phase 4 sync reads these [VERIFIED: codebase grep] |

### No New Libraries Required
This phase requires zero new dependencies. All functionality uses existing libraries. [VERIFIED: codebase analysis]

## Architecture Patterns

### Recommended Project Structure
```
com.nexio.tv.core.sync/
  ProfileSyncService.kt          # NEW: Profile metadata push/pull
  ProfileSettingsSyncService.kt   # NEW: Per-profile settings blob sync
  StartupSyncService.kt          # MODIFY: Add per-profile settings pull trigger
  AccountSettingsSyncService.kt   # MODIFY: Remove per-profile paths from v7

com.nexio.tv.data.local/
  TraktLibrarySnapshotStore.kt    # MODIFY: Per-profile PREFS_NAME
  ContinueWatchingSnapshotStore.kt # MODIFY: Per-profile PREFS_NAME
  SimklLibrarySnapshotStore.kt    # MODIFY: Per-profile PREFS_NAME
  SimklDiscoverySnapshotStore.kt  # MODIFY: Per-profile PREFS_NAME
  TraktDiscoverySnapshotStore.kt  # MODIFY: Per-profile PREFS_NAME
  MDBListDiscoverySnapshotStore.kt # MODIFY: Per-profile PREFS_NAME
  HomeCatalogSnapshotStore.kt     # MODIFY: Per-profile PREFS_NAME
  SimklProgressSyncStateStore.kt  # MODIFY: Per-profile PREFS_NAME
  SyntheticHomeCatalogStore.kt    # MODIFY: Per-profile PREFS_NAME

com.nexio.tv.data.trakt.outbox/
  TraktMutationOutboxStore.kt     # MODIFY: Per-profile PREFS_NAME

com.nexio.tv.core.profile/
  ProfileManager.kt               # MODIFY: Extend deleteProfileDataAsync()

com.nexio.tv.data.remote.supabase/
  SupabaseModels.kt               # MODIFY: Add SupabaseProfileSettingsBlob, update SupabaseProfile
```

### Pattern 1: SharedPreferences Per-Profile Naming
**What:** Each SP store receives the active profile ID and computes its PREFS_NAME dynamically.
**When to use:** For all 10 stores classified as per-profile (D-01, D-02).
**Key insight:** Profile 1 uses bare name for zero-migration. Profiles 2-4 use `{baseName}_p{id}`.

```kotlin
// Source: [VERIFIED: existing ProfileDataStoreFactory.get() pattern]
// Helper function (or inline logic in each store)
private fun profilePrefsName(baseName: String, profileId: Int): String =
    if (profileId == 1) baseName else "${baseName}_p${profileId}"
```

**Store injection change:** Each SP store currently has `@Singleton` scope and `@Inject constructor(@ApplicationContext context: Context, ...)`. The stores need access to the active profile ID. Two approaches:

- **Option A (recommended):** Inject `ProfileManager` and read `activeProfileId.value` in each read/write/clear call. The stores remain singletons but their internal PREFS_NAME is dynamic.
- **Option B:** Make stores profile-scoped with a factory. This is heavier and unnecessary since SP stores are simple wrappers.

Option A is recommended because it matches how the stores are already used -- callers invoke `store.read()`, `store.write(snapshot)`, `store.clear()` without profile context. The store internally resolves the correct SP file. [ASSUMED]

### Pattern 2: ProfileSyncService (Metadata Sync)
**What:** Port of NuvioTV's ProfileSyncService. Pushes full profile list to Supabase on create/edit/delete. Pulls on startup.
**Source:** `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/core/sync/ProfileSyncService.kt` [VERIFIED: file read]

Key structure:
```kotlin
@Singleton
class ProfileSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileDataStore: ProfileDataStore,
    private val profileManager: ProfileManager
) {
    // withJwtRefreshRetry pattern (same as existing services)
    
    suspend fun pushToRemote(): Result<Unit>
    // Builds p_profiles JsonArray from profileManager.profiles.value
    // Calls postgrest.rpc("sync_push_profiles", params)
    
    suspend fun pullFromRemote(): Result<List<UserProfile>>
    // Calls postgrest.rpc("sync_pull_profiles")
    // Decodes SupabaseProfile list, maps to UserProfile
    // Calls profileDataStore.replaceAllProfiles(profiles)
    
    suspend fun deleteProfileData(profileId: Int): Result<Unit>
    // Calls postgrest.rpc("sync_delete_profile_data", params)
}
```

**Adaptation needed for Nexio:**
- NuvioTV SupabaseProfile has `avatarId` field; Nexio's SupabaseProfile is missing `avatarId` and `pinEnabled` -- need to add these [VERIFIED: codebase grep]
- PIN-related RPCs (set_profile_pin, clear_profile_pin, verify_profile_pin, sync_pull_profile_locks) may already be partially implemented for Phase 3 UI; coordinate with those

### Pattern 3: ProfileSettingsSyncService (Blob Sync)
**What:** Port of NuvioTV's ProfileSettingsSyncService. Serializes per-profile DataStore preferences into typed JSON blob, pushes/pulls via dedicated RPCs.
**Source:** `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/core/sync/ProfileSettingsSyncService.kt` [VERIFIED: file read]

Key structure:
```kotlin
@Singleton
class ProfileSettingsSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileManager: ProfileManager,
    private val profileDataStoreFactory: ProfileDataStoreFactory
) {
    private val syncedFeatures = listOf(/* feature DataStore names */)
    
    // Observer: flatMapLatest on activeProfileId -> combine all feature flows
    //   -> drop(1) -> distinctUntilChanged -> debounce(1500ms) -> push
    
    // Push: exportSettingsBlob(profileId) -> postgrest.rpc("sync_push_profile_settings_blob")
    // Pull: postgrest.rpc("sync_pull_profile_settings_blob") -> importSettingsBlob(profileId)
    
    // Signature comparison to skip no-op pulls
    // skipNextPushSignature to prevent echo after pull
    // syncMutex to serialize push/pull
    // applyingRemoteBlob flag to suppress observer during import
}
```

**Nexio syncedFeatures list (mapped from per-profile DataStores):**

Currently migrated to ProfileDataStoreFactory (Phase 2):
- `"trakt_auth_store"` (TraktAuthDataStore.FEATURE) [VERIFIED: codebase grep]
- `"simkl_auth_store"` (SimklAuthDataStore.FEATURE) [VERIFIED: codebase grep]
- `"trakt_settings"` (TraktSettingsDataStore.FEATURE) [VERIFIED: codebase grep]
- `"simkl_settings"` (SimklSettingsDataStore.FEATURE) [VERIFIED: codebase grep]

Not yet migrated (still singleton delegates -- Phase 2 may complete these before Phase 4):
- `"player_settings"` (PlayerSettingsDataStore) [VERIFIED: codebase grep]
- `"layout_preferences"` (LayoutPreferenceDataStore) [VERIFIED: codebase grep]
- `"theme_settings"` (ThemeDataStore) [VERIFIED: codebase grep]
- `"search_history"` (SearchHistoryDataStore) [VERIFIED: codebase grep]

**Critical dependency:** The ProfileSettingsSyncService reads DataStores via `profileDataStoreFactory.get(profileId, feature)`. Only 4 of 8 per-profile DataStores are currently using the factory. If Phase 2 has not completed migrating the remaining 4 by the time Phase 4 starts, the syncedFeatures list must be limited to only the factory-backed stores, or Phase 2 must complete first.

**Note on auth stores in blob:** NuvioTV's syncedFeatures does NOT include auth stores (trakt_auth_store, simkl_auth_store) in the blob because auth tokens are synced via the separate secrets mechanism in v7. Nexio should follow the same pattern -- the `syncedFeatures` list should contain only settings DataStores, not auth DataStores. [VERIFIED: NuvioTV source - syncedFeatures contains theme_settings, layout_settings, player_settings, trailer_settings, tmdb_settings, mdblist_settings, animeskip_settings, track_preference]

**Recommended Nexio syncedFeatures (settings only, excluding auth):**
```kotlin
private val syncedFeatures = listOf(
    "trakt_settings",
    "simkl_settings",
    "player_settings",
    "layout_preferences",
    "theme_settings"
    // search_history excluded: local-only, not synced cross-device
)
```

### Pattern 4: V7 Contract Cleanup
**What:** Remove per-profile settings from v7 shared sync to prevent double-syncing (D-04).
**Where:** `AccountConfigSyncContract.kt` and `AccountSettingsSyncService.kt`

Settings to remove from v7:
- `traktCatalogPreferences` flow from `observeAccountConfigSyncChangedPaths()` [VERIFIED: line 154]
- `simklCatalogPreferences` flow from `observeAccountConfigSyncChangedPaths()` [VERIFIED: line 155]
- `playerSettings` -> `"playback.streamSelection.trackingProvider"` and `"formatter"` paths [VERIFIED: lines 157-160]
- Corresponding reads in `buildAccountConfigSyncPayload()` for `trackingProvider` and `formatter` [VERIFIED: lines 203-241]
- Corresponding writes in `applyAccountConfigSyncSettings()` for traktSettings catalog prefs, simklSettings catalog prefs, playerSettings tracking provider, and playerSettings formatter [VERIFIED: lines 400-423]

**Critical risk:** These settings were previously synced for single-profile users. After removal from v7, they ONLY sync via the per-profile blob. The pull-before-push gate must ensure the per-profile blob pull completes before any v7 push happens, to prevent data loss on first sync after upgrade.

### Pattern 5: Profile Deletion Cleanup
**What:** Extended deletion flow that covers all data scopes.
**Sequence:** (based on D-10 through D-13)

```
1. Revoke Trakt OAuth token (best-effort, from profile's TraktAuthDataStore)
2. Revoke Simkl OAuth token (best-effort, from profile's SimklAuthDataStore)
3. Call sync_delete_profile_data RPC (best-effort, removes remote profile + settings blob)
4. Push updated profile list to remote (sync_push_profiles without the deleted profile)
5. Delete DataStore files (existing: factory.clearProfile + file deletion)
6. Delete SharedPreferences files (NEW: iterate all 10 per-profile SP names)
7. Remove profile from local ProfileDataStore
```

Steps 1-4 are best-effort (catch exceptions, log, continue). Steps 5-7 always execute.

**Token revocation specifics:**
- Trakt: `TraktAuthService.revokeAndLogout()` calls `traktApi.revokeToken(TraktRevokeRequestDto(...))` then `traktAuthDataStore.clearAuth()` [VERIFIED: TraktAuthService.kt lines 234-250]
- Simkl: `SimklAuthService.revokeAndLogout()` only calls `simklAuthDataStore.clearAuth()` -- Simkl has no revocation API [VERIFIED: SimklAuthService.kt lines 96-98]

**Important:** For profile deletion, we need to revoke the **specific profile's** tokens, not the active profile's. This means reading tokens from the target profile's DataStore (via `profileDataStoreFactory.get(profileId, "trakt_auth_store")`) rather than calling `revokeAndLogout()` which operates on the active profile. A dedicated `revokeTokenForProfile(profileId: Int)` method is needed.

### Pattern 6: Supabase RPC and Table Schema
**What:** Backend SQL functions and tables needed for profile sync (D-06).

**Tables needed:**

```sql
-- profiles table (may already exist from Phase 3 PIN work)
CREATE TABLE IF NOT EXISTS profiles (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    profile_index INT NOT NULL,
    name TEXT NOT NULL DEFAULT '',
    avatar_color_hex TEXT NOT NULL DEFAULT '#1E88E5',
    uses_primary_addons BOOLEAN NOT NULL DEFAULT false,
    avatar_id TEXT,
    pin_hash TEXT,
    pin_enabled BOOLEAN NOT NULL DEFAULT false,
    pin_locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, profile_index)
);

-- profile_settings table
CREATE TABLE IF NOT EXISTS profile_settings (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    profile_id INT NOT NULL,
    platform TEXT NOT NULL DEFAULT 'tv',
    settings_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, profile_id, platform)
);
```

**RPCs needed:**

| RPC Name | Parameters | Behavior |
|----------|-----------|----------|
| `sync_push_profiles` | `p_profiles: jsonb[]` | Upsert all profiles for calling user; delete any not in the array |
| `sync_pull_profiles` | (none) | Return all profiles for calling user |
| `sync_push_profile_settings_blob` | `p_profile_id: int, p_settings_json: jsonb, p_platform: text` | Upsert settings blob for profile+platform |
| `sync_pull_profile_settings_blob` | `p_profile_id: int, p_platform: text` | Return settings blob for profile+platform |
| `sync_delete_profile_data` | `p_profile_id: int` | Delete profile row + settings blob for the given profile |

[ASSUMED: SQL schema design is Claude's discretion per CONTEXT.md. Patterns follow NuvioTV conventions.]

### Anti-Patterns to Avoid
- **Syncing auth tokens in the settings blob:** Auth tokens have their own secrets sync mechanism in v7. Including them in the blob would create duplicate sync paths and potential conflicts.
- **Blocking deletion on network failures:** D-13 explicitly states remote cleanup is best-effort. Never let a network error prevent local profile deletion.
- **Mutating profile 1 SP filenames:** Profile 1 MUST keep bare names. Any code that constructs a per-profile filename for profile 1 should return the original name unchanged.
- **Syncing search history:** Search history is local-only. NuvioTV does not sync it. Including it in the blob would create cross-device privacy concerns.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JSON blob serialization | Custom serializer for DataStore Preferences | NuvioTV's `encodePreferenceValue` / `applyEncodedPreference` pattern | Handles all 7 Preference types (String, Boolean, Int, Long, Float, Double, StringSet) with type tags; proven in production |
| Settings change detection | Custom diff logic | NuvioTV's signature-based comparison (`buildSettingsSignature`) | Efficiently detects changes without full blob comparison; prevents echo pushes after pull |
| Push/pull concurrency | Custom locking | `kotlinx.coroutines.sync.Mutex` with `syncMutex.withLock` | Already proven in NuvioTV ProfileSettingsSyncService |
| JWT refresh | Custom retry logic | Existing `withJwtRefreshRetry` pattern | Already used in AccountSettingsSyncService and SyncRepositoryImpl |

**Key insight:** NuvioTV's ProfileSettingsSyncService is 393 lines of proven production code. Porting it directly is lower-risk than reimplementing the same logic.

## Common Pitfalls

### Pitfall 1: Echo Push After Pull
**What goes wrong:** Pull applies remote settings -> local DataStore flows emit changes -> observer triggers a push of the same data back to remote.
**Why it happens:** The observer watches DataStore flows; it cannot distinguish local user edits from remote blob imports.
**How to avoid:** Use the `applyingRemoteBlob` flag to suppress the observer during `importSettingsBlob()`, plus `skipNextPushSignature` to skip the next push if its signature matches what was just pulled. Both mechanisms are in NuvioTV's implementation. [VERIFIED: NuvioTV ProfileSettingsSyncService lines 69-70, 159, 268-271]
**Warning signs:** Infinite sync loop on pull, rapid push/pull cycling in logs.

### Pitfall 2: Data Loss During V7-to-Blob Migration
**What goes wrong:** First app launch after upgrade: v7 push fires before per-profile blob pull completes, overwriting per-profile settings with stale shared data.
**Why it happens:** The existing `AccountConfigStartupPushGate` only gates v7 pushes. A separate gate is needed for per-profile blob sync.
**How to avoid:** The per-profile blob pull must complete before v7 settings removal takes effect. On upgrade, the first launch should: (1) pull per-profile blob, (2) if blob is empty, export current local per-profile settings as initial blob and push, (3) then remove per-profile paths from v7 push/pull.
**Warning signs:** After upgrade, per-profile settings (catalog order, formatter, tracking provider) revert to defaults on second device.

### Pitfall 3: Orphaned SharedPreferences Files on Deletion
**What goes wrong:** Profile deleted but SP files remain on disk because the deletion code only handles DataStore files.
**Why it happens:** Current `ProfileManager.deleteProfileDataAsync()` only looks for files ending with `_p${profileId}.preferences_pb` (DataStore format). SharedPreferences files are stored in `shared_prefs/` directory with `.xml` extension.
**How to avoid:** Extend deletion to also scan `context.applicationInfo.dataDir + "/shared_prefs/"` for files matching `*_p${profileId}.xml`. [ASSUMED: Standard Android SharedPreferences file location]
**Warning signs:** After deleting and re-creating a profile with the same ID, old snapshot data appears.

### Pitfall 4: Revoking Wrong Profile's Token
**What goes wrong:** Profile deletion revokes the active profile's Trakt/Simkl token instead of the deleted profile's token.
**Why it happens:** `TraktAuthService.revokeAndLogout()` and `SimklAuthService.revokeAndLogout()` operate on the current active profile's DataStore. If the user is on profile 1 and deletes profile 3, calling these methods would revoke profile 1's tokens.
**How to avoid:** Read the target profile's access token directly from `profileDataStoreFactory.get(profileId, "trakt_auth_store")` and call the revocation API with that specific token. Do NOT use the existing `revokeAndLogout()` methods for deletion.
**Warning signs:** Active profile loses auth after deleting a different profile.

### Pitfall 5: SharedPreferences Singleton Caching
**What goes wrong:** After deleting a profile's SP file and recreating the same profile ID, `context.getSharedPreferences(name)` returns cached in-memory data from the deleted file.
**Why it happens:** Android caches SharedPreferences instances in a static map keyed by filename. Deleting the XML file does not invalidate the in-memory cache.
**How to avoid:** Call `sharedPrefs.edit().clear().commit()` BEFORE deleting the file, to clear the in-memory cache. Then delete the file. [VERIFIED: this is a known Android behavior]
**Warning signs:** Ghost data persists after profile deletion until app restart.

### Pitfall 6: Race Between Profile Switch and Settings Push
**What goes wrong:** User switches profile during a debounced push window. The push fires with the new profile ID but the signature was computed from the old profile's data.
**Why it happens:** `flatMapLatest` cancels the old profile's flow, but a pending debounce coroutine might survive briefly.
**How to avoid:** `flatMapLatest` automatically handles this -- when `activeProfileId` changes, the entire inner flow (including pending debounce) is cancelled and restarted for the new profile. Verify `flatMapLatest` wraps the entire chain including `debounce`. [VERIFIED: NuvioTV source lines 251-276 confirms correct scoping]

## Code Examples

### SharedPreferences Per-Profile Naming Helper
```kotlin
// Source: [Pattern derived from ProfileDataStoreFactory.get()]
/**
 * Returns a profile-scoped SharedPreferences name.
 * Profile 1 keeps the bare baseName for zero-migration.
 */
fun profilePrefsName(baseName: String, profileId: Int): String =
    if (profileId == 1) baseName else "${baseName}_p${profileId}"
```

### Snapshot Store Migration Template
```kotlin
// Source: [Pattern derived from existing TraktLibrarySnapshotStore + ProfileDataStoreFactory]
// Before (singleton, hardcoded name):
@Singleton
class TraktLibrarySnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metadataDiskCacheStore: MetadataDiskCacheStore
) {
    companion object {
        private const val PREFS_NAME = "trakt_library_snapshot"
    }
    fun read(): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // ...
    }
}

// After (profile-aware):
@Singleton
class TraktLibrarySnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val profileManager: ProfileManager
) {
    companion object {
        internal const val BASE_PREFS_NAME = "trakt_library_snapshot"
    }
    private fun prefsName(): String =
        profilePrefsName(BASE_PREFS_NAME, profileManager.activeProfileId.value)

    fun read(): Snapshot? {
        val prefs = context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
        // ... (rest unchanged)
    }
}
```

### Profile Deletion SharedPreferences Cleanup
```kotlin
// Source: [Pattern derived from ProfileManager.deleteProfileDataAsync() + Android SP conventions]
private fun deleteSharedPreferencesForProfile(profileId: Int) {
    if (profileId == 1) return  // Never delete profile 1 files

    val suffix = "_p${profileId}"
    val spStoreBaseNames = listOf(
        "trakt_library_snapshot",
        "continue_watching_snapshot",
        "simkl_library_snapshot",
        "simkl_discovery_snapshot_v2",
        "trakt_discovery_snapshot",
        "mdblist_discovery_snapshot",
        "home_catalog_snapshot",
        "simkl_progress_sync_state",
        "trakt_mutation_outbox",
        "synthetic_home_catalogs"
    )

    spStoreBaseNames.forEach { baseName ->
        val prefsName = "${baseName}${suffix}"
        // Clear in-memory cache first
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Delete the XML file
        val file = File(context.applicationInfo.dataDir, "shared_prefs/${prefsName}.xml")
        if (file.exists()) file.delete()
    }
}
```

### Typed Preference Value Encoding (from NuvioTV)
```kotlin
// Source: [VERIFIED: NuvioTV ProfileSettingsSyncService.encodePreferenceValue()]
// Encodes DataStore preference values with type information for safe deserialization
private fun encodePreferenceValue(rawValue: Any?): JsonObject? {
    return when (rawValue) {
        is String -> buildJsonObject { put("type", "string"); put("value", rawValue) }
        is Boolean -> buildJsonObject { put("type", "boolean"); put("value", rawValue) }
        is Int -> buildJsonObject { put("type", "int"); put("value", rawValue) }
        is Long -> buildJsonObject { put("type", "long"); put("value", rawValue) }
        is Float -> buildJsonObject { put("type", "float"); put("value", rawValue) }
        is Double -> buildJsonObject { put("type", "double"); put("value", rawValue) }
        is Set<*> -> {
            if (!rawValue.all { it is String }) return null
            buildJsonObject {
                put("type", "string_set")
                put("value", JsonArray(rawValue.map { JsonPrimitive(it as String) }.sortedBy { it.content }))
            }
        }
        else -> null
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| All settings in v7 shared sync | Per-profile settings in independent blob + shared settings remain in v7 | Phase 4 (this phase) | Prevents cross-profile overwrites |
| SP stores hardcoded to single file | SP stores profile-aware with `_p{id}` suffix | Phase 4 (this phase) | Enables per-profile snapshot isolation |
| Deletion: DataStore files only | Deletion: DataStore + SP files + remote data + token revocation | Phase 4 (this phase) | No orphaned data anywhere |

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Option A (inject ProfileManager into SP stores) is the best approach for per-profile SP naming | Architecture Patterns - Pattern 1 | Medium -- could require factory pattern instead if stores need to read non-active profiles |
| A2 | SharedPreferences files are stored at `{dataDir}/shared_prefs/{name}.xml` | Common Pitfalls - Pitfall 3 | Low -- this is standard Android behavior but could vary on custom ROMs |
| A3 | Supabase SQL schema design follows NuvioTV conventions with profiles and profile_settings tables | Architecture Patterns - Pattern 6 | Low -- schema is Claude's discretion per CONTEXT.md |
| A4 | search_history should be excluded from syncedFeatures (local-only) | Architecture Patterns - Pattern 3 | Low -- NuvioTV excludes it; user would need to explicitly request cross-device search history sync |
| A5 | Phase 2 will complete remaining 4 DataStore migrations (PlayerSettings, LayoutPreference, Theme, SearchHistory) before Phase 4 starts | Architecture Patterns - Pattern 3 | HIGH -- if not complete, syncedFeatures must be reduced or Phase 2 must be finished first |

## Open Questions

1. **Phase 2 DataStore Migration Completeness**
   - What we know: Phase 2 has migrated 4/8 per-profile DataStores to ProfileDataStoreFactory (TraktAuth, SimklAuth, TraktSettings, SimklSettings). The remaining 4 (PlayerSettings, LayoutPreference, Theme, SearchHistory) still use singleton delegates.
   - What's unclear: Will Phase 2 complete all 8 migrations before Phase 4 starts?
   - Recommendation: Phase 4 plans should assume Phase 2 is complete (per roadmap dependency). If not, the syncedFeatures list must be scoped to only factory-backed stores. Planner should add an explicit prerequisite check.

2. **Profiles Table Overlap with Phase 3 (PIN)**
   - What we know: Phase 3 implements PIN-related RPCs (set_profile_pin, clear_profile_pin, verify_profile_pin). These likely create the profiles table.
   - What's unclear: Does Phase 3 create the profiles table or does Phase 4 need to?
   - Recommendation: Phase 4 SQL should use `CREATE TABLE IF NOT EXISTS` and `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` to be idempotent. If Phase 3 already created the table, Phase 4 just adds the profile_settings table and any missing columns.

3. **SimklDiscoverySnapshotStore Legacy Migration**
   - What we know: SimklDiscoverySnapshotStore has both `PREFS_NAME = "simkl_discovery_snapshot_v2"` and `LEGACY_PREFS_NAME = "simkl_discovery_snapshot"`. The legacy cleanup reads the old file.
   - What's unclear: Should per-profile naming apply to the legacy name too?
   - Recommendation: Only apply per-profile naming to the current `PREFS_NAME` (v2). The legacy name is read-only for migration and only exists for profile 1 (pre-multi-profile users).

## Project Constraints (from CLAUDE.md)

- **Small, targeted changes:** Prefer small changes over broad refactors. Each SP store migration can be a discrete commit.
- **Preserve existing architecture:** SP stores keep their existing API (read/write/clear). Only internal PREFS_NAME computation changes.
- **No new libraries:** All functionality uses existing dependencies.
- **Keep domain code free of Android framework dependencies:** Sync services live in `core.sync` (framework-adjacent), not in domain layer.
- **Build command:** `./gradlew assembleArm64Debug` for development builds.
- **Test command:** `./gradlew testArm64DebugUnitTest` for unit tests.

## Sources

### Primary (HIGH confidence)
- NuvioTV ProfileSyncService.kt -- full file read, 186 lines, profile metadata push/pull pattern
- NuvioTV ProfileSettingsSyncService.kt -- full file read, 393 lines, settings blob sync with debounce/flatMapLatest
- NuvioTV SupabaseModels.kt -- SupabaseProfile, SupabaseProfileSettingsBlob, SupabaseProfileLockState models
- Nexio AccountSettingsSyncService.kt -- 400+ lines read, v7 sync contract, push/pull/observe patterns
- Nexio AccountConfigSyncContract.kt -- full file read, v7 contract functions, per-profile paths identified
- Nexio ProfileManager.kt -- full file read, deleteProfileDataAsync(), createProfile(), existing deletion flow
- Nexio ProfileDataStoreFactory.kt -- full file read, get(), clearProfile(), per-profile naming convention
- Nexio StartupSyncService.kt -- full file read, startup pull flow, push gate pattern
- Nexio TraktAuthService.kt -- revokeAndLogout() pattern (lines 234-250)
- Nexio SimklAuthService.kt -- revokeAndLogout() pattern (lines 96-98)
- Nexio SyncRepositoryImpl.kt -- full file read, RPC call pattern
- All 10 SP stores -- PREFS_NAME constants and read/write/clear patterns verified

### Secondary (MEDIUM confidence)
- Nexio UserProfile model -- fields verified (id, name, avatarColorHex, usesPrimaryAddons, avatarId, pinEnabled)
- Nexio SupabaseProfile model -- fields verified (missing avatarId and pinEnabled vs NuvioTV)
- Phase 2 CONTEXT.md -- DataStore classification and migration decisions

### Tertiary (LOW confidence)
- None

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- all libraries already in project, no new deps
- Architecture: HIGH -- porting proven NuvioTV patterns with source code available
- Pitfalls: HIGH -- identified from actual code analysis, not hypothetical
- SQL schema: MEDIUM -- follows NuvioTV conventions but exact schema is Claude's discretion
- Phase 2 dependency: MEDIUM -- 4/8 DataStore migrations verified complete, remaining 4 assumed

**Research date:** 2026-04-14
**Valid until:** 2026-05-14 (30 days -- stable domain, no external dependency changes expected)
