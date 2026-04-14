# Phase 4: Sync and Cleanup - Research

**Researched:** 2026-04-14
**Domain:** Supabase sync (profile metadata + per-profile settings v8 blob), SharedPreferences per-profile scoping, profile deletion cleanup
**Confidence:** HIGH

## Summary

Phase 4 adds three capabilities to Nexio: (1) syncing profile metadata to Supabase so profiles survive device changes, (2) syncing per-profile settings as independent JSON blobs keyed by profileId — completely separate from the v7 shared contract — so cross-profile overwrites are impossible, and (3) ensuring profile deletion leaves zero orphaned data locally or remotely. All three capabilities have proven reference implementations in NuvioTV that can be ported with minimal adaptation.

The key architectural decision from CONTEXT.md is a **clean v8 contract break**: per-profile settings move entirely out of v7 into their own blob RPCs, shared settings remain in v7, and no backwards-compatibility shims are written. The NuvioTV ProfileSyncService and ProfileSettingsSyncService are the canonical sources for implementation patterns. Both use the established `withJwtRefreshRetry` pattern and kotlinx.serialization JSON builders that are already present in the codebase.

The SharedPreferences migration covers 7 stores becoming per-profile (the snapshot stores tied to per-profile auth/library data) while 5 stores remain shared (catalog metadata caches). Profile deletion is best-effort remote cleanup with immediate local deletion: no OAuth revocation calls — just clear local tokens — and remote Supabase cleanup retries on next app start if the initial attempt fails.

**Primary recommendation:** Port NuvioTV's ProfileSyncService and ProfileSettingsSyncService directly. Adapt syncedFeatures to Nexio's per-profile DataStore feature names. Make 7 SharedPreferences snapshot stores profile-aware via a `profilePrefsName()` helper injecting ProfileManager. Extend `ProfileManager.deleteProfileDataAsync()` to cover SP files + best-effort remote cleanup.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Sync Trigger & Conflict Strategy**
- **D-01:** Sync is event-driven with debounce — push on profile edit, settings change, and profile switch. Use NuvioTV 2s flatMapLatest debounce pattern. Pull on app start only.
- **D-02:** Conflict resolution is last-write-wins using server-side `updated_at` timestamp. No merge logic — latest push overwrites.
- **D-03:** Upgrade to a v8 sync contract that is profile-aware from the ground up. Per-profile settings get their own blob RPCs keyed by profileId. Shared settings remain in the v7 contract. Clean break from v7 — no backwards-compat shims.

**Deletion Flow & Error Handling**
- **D-04:** Profile deletion uses best-effort remote cleanup. Local data (DataStore files, SharedPreferences files) is deleted immediately. Remote cleanup (Supabase row deletion, remote blob deletion) retries on next app start if it fails. Profile disappears from UI right away.
- **D-05:** No Trakt revoke API call on deletion — just clear local tokens for both Trakt and Simkl. Simpler and Trakt tokens expire in 90 days anyway.
- **D-06:** Deletion requires a confirmation dialog showing the profile name: "Delete profile '{name}'? This removes all settings and sync data." Uses NexioDialog with "Keep Profile" (auto-focused) and "Delete Profile" buttons per UI-SPEC.

**Snapshot Store Classification**
- **D-07:** 7 SharedPreferences stores become per-profile: TraktLibrarySnapshotStore, ContinueWatchingSnapshotStore, SimklLibrarySnapshotStore, SimklDiscoverySnapshotStore, SimklProgressSyncStateStore, TraktMutationOutboxStore, TraktDiscoverySnapshotStore.
- **D-08:** 5 SharedPreferences stores remain shared: HomeCatalogSnapshotStore, MetadataDiskCacheStore, CatalogDiskCacheStore, MDBListDiscoverySnapshotStore, SyntheticHomeCatalogStore.
- **D-09:** Per-profile SharedPreferences stores use the same `_p{id}` suffix convention as ProfileDataStoreFactory — bare name for Profile 1, `_p2`/`_p3`/`_p4` for others.

**Sync Status UI Feedback**
- **D-10:** Background sync is completely silent — no toasts, no indicators during normal use. Status only visible when user taps "Sync Now" in Settings.
- **D-11:** A "Sync Now" button exists in the Profiles area of Settings. Shows brief feedback ("Synced" or "Failed") after completion. D-pad focusable.
- **D-12:** Startup pull is fully silent — no loading indication, no splash hold. If remote data arrives after UI renders, settings update silently in background.

### Claude's Discretion
- Supabase RPC naming and table schema design for profile metadata and settings blobs
- Debounce implementation details (coroutine scope, cancellation handling)
- Retry mechanism for failed remote cleanup on next app start
- v8 contract internal structure and migration path from v7

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SYNC-01 | Profile metadata (name, avatar, PIN state) syncs to Supabase | ProfileSyncService port pattern (D-05, D-09); SupabaseProfile model needs avatarId + pinEnabled fields; push/pull RPCs documented |
| SYNC-02 | Per-profile settings sync via independent blob push/pull (not v7 contract) | v8 contract design (D-03); ProfileSettingsSyncService port pattern (D-01, D-07, D-08); syncedFeatures list mapped to Nexio; v7 removal paths identified |
| SYNC-03 | Profile deletion removes all DataStore files, SharedPreferences, and Supabase remote data | Deletion flow documented (D-04, D-05, D-06); 7 SP stores + DataStore files + best-effort remote RPC + token clear |
| SYNC-04 | Snapshot stores classified and scoped per-profile where applicable | Full classification: 7 stores per-profile (D-07), 5 stores shared (D-08); migration pattern documented (D-09) |
</phase_requirements>

## Standard Stack

### Core (already in project)
| Library | Purpose | Why Standard |
|---------|---------|--------------|
| kotlinx.serialization | JSON encoding for Supabase RPC params and blob serialization | Already used throughout sync layer (buildJsonObject, put, JsonObject, JsonPrimitive) [VERIFIED: codebase grep] |
| Gson | SharedPreferences snapshot store serialization | All 7 snapshot stores use Gson internally; no migration needed [VERIFIED: codebase grep] |
| Supabase Postgrest | RPC calls to Supabase backend | Already used via `postgrest.rpc()` pattern in AccountSettingsSyncService [VERIFIED: codebase grep] |
| Hilt/Dagger | Dependency injection for new sync services | All existing services use `@Inject constructor` + `@Singleton` [VERIFIED: codebase grep] |
| Kotlin Coroutines + Flow | Reactive observation, debounce, flatMapLatest | Exact patterns from existing AccountSettingsSyncService and NuvioTV ProfileSettingsSyncService [VERIFIED: codebase grep] |
| DataStore Preferences | Per-profile settings storage via ProfileDataStoreFactory | Phase 2 already migrated per-profile stores; Phase 4 sync reads these [VERIFIED: codebase grep] |

### No New Libraries Required
This phase requires zero new dependencies. All functionality uses existing libraries. [VERIFIED: codebase analysis]

## Architecture Patterns

### Recommended Project Structure
```
com.nexio.tv.core.sync/
  ProfileSyncService.kt             # NEW: Profile metadata push/pull
  ProfileSettingsSyncService.kt     # NEW: Per-profile settings blob sync (v8)
  StartupSyncService.kt             # MODIFY: Add per-profile pull trigger on startup
  AccountSettingsSyncService.kt     # MODIFY: Remove per-profile paths from v7 observer

com.nexio.tv.data.local/
  TraktLibrarySnapshotStore.kt      # MODIFY: Per-profile PREFS_NAME via ProfileManager
  ContinueWatchingSnapshotStore.kt  # MODIFY: Per-profile PREFS_NAME via ProfileManager
  SimklLibrarySnapshotStore.kt      # MODIFY: Per-profile PREFS_NAME via ProfileManager
  SimklDiscoverySnapshotStore.kt    # MODIFY: Per-profile PREFS_NAME via ProfileManager
  SimklProgressSyncStateStore.kt    # MODIFY: Per-profile PREFS_NAME via ProfileManager
  TraktDiscoverySnapshotStore.kt    # MODIFY: Per-profile PREFS_NAME via ProfileManager
  # HomeCatalogSnapshotStore, MetadataDiskCacheStore, CatalogDiskCacheStore,
  # MDBListDiscoverySnapshotStore, SyntheticHomeCatalogStore — NO CHANGE (shared)

com.nexio.tv.data.trakt.outbox/
  TraktMutationOutboxStore.kt       # MODIFY: Per-profile PREFS_NAME via ProfileManager

com.nexio.tv.core.profile/
  ProfileManager.kt                 # MODIFY: Extend deleteProfileDataAsync() for SP files + remote

com.nexio.tv.data.remote.supabase/
  SupabaseModels.kt                 # MODIFY: Update SupabaseProfile (avatarId, pinEnabled)
  AccountSyncModels.kt              # MODIFY: Add v8 blob models (ProfileSettingsBlobResponse)
```

### Pattern 1: SharedPreferences Per-Profile Naming
**What:** Each of the 7 per-profile SP stores receives the active profile ID and computes its PREFS_NAME dynamically, following the `_p{id}` suffix convention from D-09.
**When to use:** For exactly the 7 stores listed in D-07. The 5 stores in D-08 keep their existing hardcoded PREFS_NAME unchanged.
**Key insight:** Profile 1 uses the bare name for zero-migration. Profiles 2–4 use `{baseName}_p{id}`.

```kotlin
// Source: [VERIFIED: existing ProfileDataStoreFactory.get() pattern]
// Standalone top-level helper (can live in a shared file or each store)
fun profilePrefsName(baseName: String, profileId: Int): String =
    if (profileId == 1) baseName else "${baseName}_p${profileId}"
```

**Store injection change:** Each of the 7 SP stores currently has `@Singleton` scope and reads a hardcoded `PREFS_NAME` constant. They need access to the active profile ID. The recommended approach:

Inject `ProfileManager` into each store and read `profileManager.activeProfileId.value` in each `read()`, `write()`, and `clear()` call. The stores remain singletons but resolve the correct SP file at call time. This matches how all per-profile DataStores already work in the codebase. [ASSUMED: Option A — inject ProfileManager. Option B would be a factory pattern, which is heavier and unnecessary since these stores have simple read/write/clear APIs.]

### Pattern 2: ProfileSyncService (Metadata Sync — SYNC-01)
**What:** Port of NuvioTV's ProfileSyncService. Pushes full profile list to Supabase on create/edit/delete (D-09: immediate push, no debounce). Pulls on startup.

Key structure:
```kotlin
// Source: [VERIFIED: NuvioTV ProfileSyncService.kt — full file read]
@Singleton
class ProfileSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileDataStore: ProfileDataStore,
    private val profileManager: ProfileManager
) {
    // Uses withJwtRefreshRetry pattern (same as AccountSettingsSyncService)

    suspend fun pushToRemote(): Result<Unit>
    // Builds p_profiles JsonArray from profileManager.profiles.value
    // Calls postgrest.rpc("sync_push_profiles", params)

    suspend fun pullFromRemote(): Result<List<UserProfile>>
    // Calls postgrest.rpc("sync_pull_profiles")
    // Decodes SupabaseProfile list, maps to UserProfile
    // Calls profileDataStore.replaceAllProfiles(profiles)
}
```

**Adaptation needed for Nexio:**
- Nexio's `SupabaseProfile` is missing `avatarId` and `pinEnabled` fields that exist in UserProfile [VERIFIED: SupabaseModels.kt line 125-135 — fields absent]. These must be added before push/pull can carry full metadata.
- PIN-related RPCs (set_profile_pin, clear_profile_pin, verify_profile_pin) may be partially implemented by Phase 3. Coordinate to avoid duplicate table creation — use `CREATE TABLE IF NOT EXISTS` and `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`.

### Pattern 3: ProfileSettingsSyncService (v8 Blob Sync — SYNC-02)
**What:** New service implementing the v8 per-profile settings contract (D-03). Serializes per-profile DataStore Preferences into a typed JSON blob per profileId, pushes/pulls via dedicated RPCs. Runs alongside the existing AccountSettingsSyncService (v7 shared sync), which continues to handle shared settings.

Key structure:
```kotlin
// Source: [VERIFIED: NuvioTV ProfileSettingsSyncService.kt — full file read, 393 lines]
@Singleton
class ProfileSettingsSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileManager: ProfileManager,
    private val profileDataStoreFactory: ProfileDataStoreFactory
) {
    private val syncedFeatures = listOf(/* see below */)

    // Observer: flatMapLatest on activeProfileId
    //   -> combine all feature store flows
    //   -> drop(1) -> distinctUntilChanged
    //   -> debounce(2000ms) per D-01
    //   -> push blob for activeProfileId

    suspend fun pushBlobForProfile(profileId: Int): Result<Unit>
    // exportSettingsBlob(profileId) -> postgrest.rpc("sync_push_profile_settings_blob", ...)

    suspend fun pullBlobForProfile(profileId: Int): Result<Unit>
    // postgrest.rpc("sync_pull_profile_settings_blob", ...) -> importSettingsBlob(profileId)

    // applyingRemoteBlob: Boolean flag to suppress observer during importSettingsBlob
    // skipNextPushSignature: String? to prevent echo push after pull
    // syncMutex: Mutex to serialize push/pull operations
}
```

**Nexio syncedFeatures list — settings DataStores only (auth DataStores excluded):**

Auth DataStores (TraktAuthDataStore `"trakt_auth_store"`, SimklAuthDataStore `"simkl_auth_store"`) are NOT in the blob. Auth tokens sync via the existing secrets mechanism in v7. Including them in the blob would create duplicate sync paths. [VERIFIED: NuvioTV source confirms auth stores excluded from syncedFeatures]

Recommended syncedFeatures for Nexio:
```kotlin
private val syncedFeatures = listOf(
    "trakt_settings",       // TraktSettingsDataStore.FEATURE [VERIFIED: uses factory]
    "simkl_settings",       // SimklSettingsDataStore.FEATURE [VERIFIED: uses factory]
    "player_settings",      // PlayerSettingsDataStore [VERIFIED: uses factory]
    "layout_preferences",   // LayoutPreferenceDataStore [VERIFIED: uses factory]
    "theme_settings"        // ThemeDataStore [VERIFIED: uses factory]
    // search_history excluded: local-only, not synced cross-device
)
```

**Critical dependency on Phase 2:** All 5 features above must be backed by `ProfileDataStoreFactory.get(profileId, featureName)` before Phase 4 can implement the blob sync. The codebase already shows `flatMapLatest` usage in TraktSettingsDataStore, SimklSettingsDataStore, PlayerSettingsDataStore, LayoutPreferenceDataStore, and ThemeDataStore [VERIFIED: grep results], confirming factory migration. If any remain on singleton delegates when Phase 4 starts, those features must be excluded from syncedFeatures until migration completes.

### Pattern 4: V7 Contract Cleanup (SYNC-02)
**What:** Remove per-profile settings from AccountSettingsSyncService's v7 observer and payload builder to prevent double-syncing. Per D-03 this is a clean break — no shims.

Settings to remove from v7 `observeAccountConfigSyncChangedPaths()`:
- `traktCatalogPreferences` flow [VERIFIED: AccountSettingsSyncService.kt line 270]
- `simklCatalogPreferences` flow [VERIFIED: AccountSettingsSyncService.kt line 271]
- `playerSettings` flow for `trackingProvider` and `formatter` paths [VERIFIED: AccountSettingsSyncService.kt lines 272-273]

Settings to remove from v7 `buildLocalPayload()`:
- `traktSettingsDataStore.catalogPreferences` reads
- `simklSettingsDataStore.catalogPreferences` reads
- Player `trackingProvider` and formatter fields from AccountConfigSyncPayload

Settings to remove from v7 `applyAccountConfigSyncSettings()`:
- Trakt catalog prefs writes (`traktSettingsDataStore.setCatalogPreferences(...)`)
- Simkl catalog prefs writes (`simklSettingsDataStore.setCatalogPreferences(...)`)
- Player tracking provider write
- Formatter writes

**Critical migration risk:** These settings were previously synced for single-profile users. After removal from v7, they only sync via the per-profile blob. On first launch after upgrade, the per-profile blob pull must complete before any v7 push executes, or the removed fields could revert. The existing `AccountConfigStartupPushGate` pattern handles v7 push gating; an analogous gate is needed for the v8 blob service. [VERIFIED: AccountConfigStartupPushGate pattern in AccountSettingsSyncService.kt]

### Pattern 5: Profile Deletion Cleanup (SYNC-03)
**What:** Extended deletion flow covering all data scopes per D-04 and D-05.

**Deletion sequence:**
```
1. Show NexioDialog confirmation (D-06): "Delete profile '{name}'?..."
   - "Keep Profile" auto-focused, "Delete Profile" destructive
2. On confirm:
   a. Best-effort remote: POST sync_delete_profile Supabase RPC
      - If fails: log and schedule retry flag for next app start
      - Never block on this step
   b. Clear local Trakt tokens for the profile (read from factory.get(profileId, "trakt_auth_store"), call traktAuthDataStore.clearAuth() scoped to that profileId)
   c. Clear local Simkl tokens for the profile (same pattern)
      NOTE: No revocation API call per D-05
   d. ProfileDataStoreFactory.clearProfile(profileId) — existing
   e. Delete DataStore .preferences_pb files — existing logic in ProfileManager.deleteProfileDataAsync()
   f. Delete per-profile SharedPreferences files (NEW — 7 files)
   g. ProfileDataStore.deleteProfile(id) — existing
3. Profile removed from UI immediately (steps 2d-2g are synchronous local ops)
```

**SP file deletion — 7 files for profileId != 1:**
```kotlin
// Source: [Pattern derived from ProfileManager.deleteProfileDataAsync() + Android SP conventions]
private fun deleteSharedPreferencesForProfile(profileId: Int) {
    if (profileId == 1) return
    val suffix = "_p${profileId}"
    val spStoreBaseNames = listOf(
        "trakt_library_snapshot",
        "continue_watching_snapshot",
        "simkl_library_snapshot",
        "simkl_discovery_snapshot_v2",   // Note: v2 suffix is part of the base name
        "simkl_progress_sync_state",
        "trakt_mutation_outbox",
        "trakt_discovery_snapshot"
    )
    spStoreBaseNames.forEach { baseName ->
        val prefsName = "${baseName}${suffix}"
        // Clear in-memory cache BEFORE deleting file (avoids Android SP cache staleness)
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val file = File(context.applicationInfo.dataDir, "shared_prefs/${prefsName}.xml")
        if (file.exists()) file.delete()
    }
}
```

**Retry on next app start:** Store a Set<Int> of profileIds with pending remote cleanup in a lightweight SharedPreferences entry (e.g., `"pending_remote_cleanup"` in a shared prefs). On StartupSyncService run, check this set, attempt the RPC for each, remove on success. [ASSUMED: implementation approach is Claude's discretion per CONTEXT.md]

### Pattern 6: Supabase RPC and Table Schema (Claude's Discretion)
**What:** Backend SQL functions and tables needed for profile sync.

**Tables needed:**

```sql
-- profiles table (may already exist from Phase 3 PIN work — use IF NOT EXISTS)
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

-- profile_settings table (v8 blob store, one row per user+profile+platform)
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
| `sync_push_profiles` | `p_profiles jsonb` | Upsert all profiles for calling user; delete any not in the array |
| `sync_pull_profiles` | (none) | Return all profiles for calling user ordered by profile_index |
| `sync_push_profile_settings_blob` | `p_profile_id int, p_settings_json jsonb, p_platform text` | Upsert settings blob for profile+platform; update updated_at |
| `sync_pull_profile_settings_blob` | `p_profile_id int, p_platform text` | Return settings blob row for profile+platform |
| `sync_delete_profile` | `p_profile_id int` | Delete profile row + settings blob for the given profile_id |

[ASSUMED: SQL schema design is Claude's discretion per CONTEXT.md. Patterns follow NuvioTV conventions.]

### Anti-Patterns to Avoid
- **Syncing auth tokens in the settings blob:** Auth tokens have their own secrets sync mechanism in v7. Including them in the blob creates duplicate sync paths and potential conflicts.
- **Blocking deletion on network failures:** D-04 is explicit: remote cleanup is best-effort. Never let a network error prevent local profile deletion from completing.
- **Mutating profile 1 SP filenames:** Profile 1 MUST keep bare names. `profilePrefsName(baseName, 1)` must return `baseName` unchanged.
- **Calling Trakt/Simkl revocation on deletion:** D-05 explicitly prohibits this. Clear local tokens only.
- **Applying per-profile naming to shared stores:** HomeCatalogSnapshotStore, MetadataDiskCacheStore, CatalogDiskCacheStore, MDBListDiscoverySnapshotStore, SyntheticHomeCatalogStore must NOT receive ProfileManager injection — they stay singleton with hardcoded PREFS_NAMEs.
- **Showing any UI during background sync:** D-10 requires fully silent background sync. No toasts, no indicators outside the explicit "Sync Now" button in settings.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JSON blob serialization | Custom serializer for DataStore Preferences | NuvioTV's `encodePreferenceValue` / `applyEncodedPreference` pattern | Handles all 7 Preference types (String, Boolean, Int, Long, Float, Double, StringSet) with type tags; production-proven |
| Settings change detection | Custom diff logic | NuvioTV's signature-based comparison (`buildSettingsSignature`) | Efficiently detects changes without full blob comparison; prevents echo pushes after pull |
| Push/pull concurrency | Custom locking | `kotlinx.coroutines.sync.Mutex` with `syncMutex.withLock` | Already proven in NuvioTV ProfileSettingsSyncService |
| JWT refresh | Custom retry logic | Existing `withJwtRefreshRetry` pattern | Already used in AccountSettingsSyncService [VERIFIED: lines 311-318] |
| Debounce | Custom timer or delay loop | `flatMapLatest` + `debounce(2000)` operator chain | flatMapLatest auto-cancels on profile switch, debounce coalesces rapid changes; NuvioTV pattern [VERIFIED: NuvioTV source] |

**Key insight:** NuvioTV's ProfileSettingsSyncService is 393 lines of proven production code. Porting it directly is lower-risk than reimplementing the same logic from scratch.

## Common Pitfalls

### Pitfall 1: Echo Push After Pull
**What goes wrong:** Pull applies remote settings -> local DataStore flows emit -> observer triggers a push of the same data back to remote, creating a sync loop.
**Why it happens:** The observer watches DataStore flows and cannot distinguish local user edits from remote blob imports.
**How to avoid:** Use the `applyingRemoteBlob` flag to suppress the observer during `importSettingsBlob()`, plus `skipNextPushSignature` to skip the next push if its signature matches what was just pulled. Both mechanisms are present in NuvioTV's implementation. [VERIFIED: NuvioTV ProfileSettingsSyncService lines 69-70, 159, 268-271]
**Warning signs:** Infinite sync loop on pull; rapid push/pull cycling visible in Logcat.

### Pitfall 2: Orphaned SharedPreferences Files on Deletion
**What goes wrong:** Profile deleted but SP files remain on disk; if the same profile ID is recycled, old snapshot data reappears.
**Why it happens:** Current `ProfileManager.deleteProfileDataAsync()` only scans for `.preferences_pb` files (DataStore format). SharedPreferences files live in `shared_prefs/` with `.xml` extension and are not covered.
**How to avoid:** Extend deletion to call `getSharedPreferences(prefsName).edit().clear().commit()` then delete the `.xml` file for each of the 7 per-profile SP store names. Clear before delete to flush Android's in-memory SP cache. [VERIFIED: Android SharedPreferences caches instances in static map — clearing in-memory state before file deletion is required]
**Warning signs:** After delete + recreate of same profile ID, old Trakt library data appears immediately without a sync.

### Pitfall 3: Race Between Profile Switch and Debounced Push
**What goes wrong:** User switches profile during a 2s debounce window; push fires with new profileId but data blob is from old profile.
**Why it happens:** A timing gap between debounce completion and flatMapLatest cancellation.
**How to avoid:** `flatMapLatest` wraps the ENTIRE inner flow including the `debounce()` call. When `activeProfileId` changes, the entire inner coroutine — including any pending debounce — is cancelled and a new one starts for the new profile. Verify the flatMapLatest wraps `combine(allFeatureFlows).debounce(2000).collect { push(newProfileId) }` as a single chain. [VERIFIED: NuvioTV source confirms correct scoping]
**Warning signs:** Log showing push for profileId=2 while active profile is 1.

### Pitfall 4: Reading Stale SharedPreferences After Profile Switch
**What goes wrong:** After switching from profile 2 to profile 1, a snapshot store still returns profile 2's data.
**Why it happens:** SP stores currently resolve their PREFS_NAME at construction time. After migration, they must resolve at call time.
**How to avoid:** `prefsName()` must call `profileManager.activeProfileId.value` on every `read()`, `write()`, and `clear()` invocation — not cached in a field. [VERIFIED: This matches how ProfileDataStoreFactory.get() resolves per-profile DataStores at call time]
**Warning signs:** Library list from profile 2 shows up for profile 1 immediately after switch.

### Pitfall 5: V7 Removal Causing Settings Loss on Upgrade
**What goes wrong:** App upgrades: v7 sync fires before per-profile blob pull completes, overwriting removed fields (traktCatalogPreferences, simklCatalogPreferences, trackingProvider) with default values.
**Why it happens:** The existing `AccountConfigStartupPushGate` only gates v7 pushes for the shared contract. There is no gate preventing v7 push before the v8 blob pull completes on first run.
**How to avoid:** The v8 ProfileSettingsSyncService must complete its initial pull for the active profile before AccountSettingsSyncService is allowed to push. Create a `ProfileSettingsStartupPullGate` analogous to `AccountConfigStartupPushGate`, or sequence the startup pull order so profile settings pull always runs first. [ASSUMED: gate implementation is Claude's discretion]
**Warning signs:** After upgrading, catalog order and formatter settings revert to defaults on second device.

### Pitfall 6: Retry Accumulation for Remote Cleanup
**What goes wrong:** App is offline repeatedly; pending cleanup set grows unbounded.
**Why it happens:** Each failed deletion adds an entry; if the set is never pruned, deleted profile IDs pile up indefinitely.
**How to avoid:** Cap the retry set at 4 entries (max profiles). On successful remote delete, remove the ID. Treat the set as a simple `Set<Int>` persisted as a comma-separated string in a shared prefs key. [ASSUMED]
**Warning signs:** Unexpected RPC calls on app start for profile IDs that no longer exist locally.

## Code Examples

### SharedPreferences Per-Profile Naming Helper
```kotlin
// Source: [Pattern derived from ProfileDataStoreFactory.get() — VERIFIED]
/**
 * Returns a profile-scoped SharedPreferences name.
 * Profile 1 keeps the bare baseName for zero-migration (D-09).
 */
fun profilePrefsName(baseName: String, profileId: Int): String =
    if (profileId == 1) baseName else "${baseName}_p${profileId}"
```

### Snapshot Store Migration Template
```kotlin
// Source: [Pattern derived from existing TraktLibrarySnapshotStore + ProfileDataStoreFactory — VERIFIED]

// BEFORE (singleton, hardcoded name):
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

// AFTER (profile-aware, resolved at call time):
@Singleton
class TraktLibrarySnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val profileManager: ProfileManager          // NEW
) {
    companion object {
        internal const val BASE_PREFS_NAME = "trakt_library_snapshot"
    }

    private fun prefsName(): String =                   // NEW — called at each read/write/clear
        profilePrefsName(BASE_PREFS_NAME, profileManager.activeProfileId.value)

    fun read(): Snapshot? {
        val prefs = context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE) // changed
        // ... rest unchanged
    }
    fun write(snapshot: Snapshot) {
        val prefs = context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE) // changed
        // ... rest unchanged
    }
    fun clear() {
        val prefs = context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE) // changed
        // ... rest unchanged
    }
}
```

### Typed Preference Value Encoding (from NuvioTV)
```kotlin
// Source: [VERIFIED: NuvioTV ProfileSettingsSyncService.encodePreferenceValue()]
// Encodes DataStore preference values with type information for safe deserialization
private fun encodePreferenceValue(rawValue: Any?): JsonObject? {
    return when (rawValue) {
        is String  -> buildJsonObject { put("type", "string");     put("value", rawValue) }
        is Boolean -> buildJsonObject { put("type", "boolean");    put("value", rawValue) }
        is Int     -> buildJsonObject { put("type", "int");        put("value", rawValue) }
        is Long    -> buildJsonObject { put("type", "long");       put("value", rawValue) }
        is Float   -> buildJsonObject { put("type", "float");      put("value", rawValue) }
        is Double  -> buildJsonObject { put("type", "double");     put("value", rawValue) }
        is Set<*>  -> {
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

### SupabaseProfile Model Update Needed
```kotlin
// Source: [VERIFIED: SupabaseModels.kt lines 125-135 — current model missing these fields]
// Fields to ADD to SupabaseProfile:
@SerialName("avatar_id") val avatarId: String? = null,
@SerialName("pin_enabled") val pinEnabled: Boolean = false,
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| All settings in v7 shared sync (single profile) | Per-profile settings in v8 independent blob + shared settings remain in v7 | Phase 4 (this phase) | Prevents cross-profile overwrites; SYNC-02 |
| SP stores hardcoded to single file (bare name) | 7 SP stores profile-aware with `_p{id}` suffix | Phase 4 (this phase) | Enables per-profile snapshot isolation; SYNC-04 |
| Deletion: DataStore files only | Deletion: DataStore + 7 SP files + best-effort remote cleanup | Phase 4 (this phase) | No orphaned data; SYNC-03 |
| Remote token revocation on profile delete | Local token clear only (no revocation API call) | Phase 4 decision (D-05) | Simpler; Trakt tokens expire in 90 days |

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Inject ProfileManager into SP stores (Option A) rather than a factory | Architecture Patterns - Pattern 1 | Medium — if stores need to read non-active profiles (e.g., during deletion), a profileId parameter may be needed |
| A2 | SharedPreferences files are stored at `{dataDir}/shared_prefs/{name}.xml` | Common Pitfalls - Pitfall 2 | Low — standard Android behavior; could vary on custom ROMs |
| A3 | Supabase SQL schema follows NuvioTV conventions with profiles and profile_settings tables | Architecture Patterns - Pattern 6 | Low — schema is Claude's discretion per CONTEXT.md |
| A4 | search_history excluded from syncedFeatures (local-only, not synced cross-device) | Architecture Patterns - Pattern 3 | Low — NuvioTV excludes it; consistent with privacy expectations |
| A5 | All 5 per-profile DataStores (trakt_settings, simkl_settings, player_settings, layout_preferences, theme_settings) use ProfileDataStoreFactory before Phase 4 starts | Architecture Patterns - Pattern 3 | HIGH — flatMapLatest usage verified in codebase for all 5, but if any still use singleton delegates, syncedFeatures must be scoped accordingly |
| A6 | Retry mechanism for remote cleanup uses a lightweight shared prefs set persisted across app starts | Architecture Patterns - Pattern 5 | Low — other implementations possible; Claude's discretion |

## Open Questions (RESOLVED)

1. **Profiles Table Overlap with Phase 3 (PIN)**
   - What we know: Phase 3 implements PIN-related RPCs (set_profile_pin, verify_profile_pin). These may already create the `profiles` table in Supabase.
   - What's unclear: Does Phase 3 fully create the `profiles` table including `avatar_id` and `pin_enabled`, or does Phase 4 need to add those columns?
   - Recommendation: RESOLVED — Phase 4 SQL should be fully idempotent using `CREATE TABLE IF NOT EXISTS` and `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`. Planner should add a note to coordinate with Phase 3 SQL migrations.

2. **SimklDiscoverySnapshotStore Legacy Name**
   - What we know: `SimklDiscoverySnapshotStore` has both `PREFS_NAME = "simkl_discovery_snapshot_v2"` and `LEGACY_PREFS_NAME = "simkl_discovery_snapshot"` (read-only migration path). [VERIFIED: codebase grep]
   - What's unclear: Should per-profile naming apply to the legacy name during deletion?
   - Recommendation: RESOLVED — Only apply per-profile naming to `PREFS_NAME` (`"simkl_discovery_snapshot_v2"`). The legacy name only exists for Profile 1 (pre-multi-profile users) and is read-only for migration — no deletion needed.

3. **AccountConfigStartupPushGate Sequencing for v8**
   - What we know: v7 pushes are gated by `AccountConfigStartupPushGate` until remote pull succeeds. Removing per-profile paths from v7 requires the v8 blob pull to run first, or upgrade users lose settings.
   - What's unclear: Should one gate block both services, or should they each have independent gates?
   - Recommendation: RESOLVED — Independent gates per service. The v8 ProfileSettingsSyncService should gate its own pushes with its own pull-completion tracker, analogous to the existing v7 gate. Both gates must complete before any push from either service fires.

## Project Constraints (from CLAUDE.md)

- **Small, targeted changes:** Prefer small changes over broad refactors. Each SP store migration is a discrete commit. [CITED: CLAUDE.md]
- **Preserve existing architecture:** SP stores keep their existing public API (read/write/clear). Only internal PREFS_NAME computation changes. [CITED: CLAUDE.md]
- **No new libraries:** All functionality uses existing dependencies. [CITED: CLAUDE.md]
- **Keep domain code free of Android framework dependencies:** New sync services live in `core.sync` (framework-adjacent), not domain layer. [CITED: CLAUDE.md]
- **Build command for development:** `./gradlew assembleArm64Debug` [CITED: CLAUDE.md]
- **Test command:** `./gradlew testArm64DebugUnitTest` [CITED: CLAUDE.md]

## Environment Availability

Step 2.6: SKIPPED (no external tool dependencies — this phase is code/config changes plus Supabase SQL migrations; Supabase access is via the existing SDK already configured in the project)

## Validation Architecture

`workflow.nyquist_validation` not set in config.json — treated as enabled.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit4 + Kotlin coroutines test (already in project) |
| Config file | `./gradlew testArm64DebugUnitTest` |
| Quick run command | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.*"` |
| Full suite command | `./gradlew testArm64DebugUnitTest` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SYNC-01 | ProfileSyncService push encodes all UserProfile fields correctly | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.ProfileSyncServiceTest"` | ❌ Wave 0 |
| SYNC-01 | ProfileSyncService pull replaces profile list atomically | unit | same | ❌ Wave 0 |
| SYNC-02 | profilePrefsName() returns bare name for profile 1, suffixed name for profiles 2-4 | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfilePrefsNameTest"` | ❌ Wave 0 |
| SYNC-02 | ProfileSettingsSyncService blob encodes all 7 Preference types correctly | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.ProfileSettingsSyncServiceTest"` | ❌ Wave 0 |
| SYNC-03 | deleteSharedPreferencesForProfile() clears and deletes all 7 SP files | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.profile.ProfileManagerTest"` | ❌ Wave 0 |
| SYNC-03 | Profile deletion with remote failure still completes local deletion | unit | same | ❌ Wave 0 |
| SYNC-04 | TraktLibrarySnapshotStore reads from profile-suffixed SP name when profile != 1 | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.TraktLibrarySnapshotStoreTest"` | ❌ Wave 0 |

### Wave 0 Gaps
- [ ] `tests/ProfileSyncServiceTest.kt` — covers SYNC-01
- [ ] `tests/ProfileSettingsSyncServiceTest.kt` — covers SYNC-02
- [ ] `tests/ProfilePrefsNameTest.kt` — covers SYNC-02 naming helper
- [ ] `tests/ProfileManagerTest.kt` (extend existing if present) — covers SYNC-03 deletion
- [ ] `tests/TraktLibrarySnapshotStoreTest.kt` — covers SYNC-04 per-profile reads

## Sources

### Primary (HIGH confidence)
- NuvioTV ProfileSyncService.kt — full file read, 186 lines, profile metadata push/pull pattern [VERIFIED: NuvioTV source]
- NuvioTV ProfileSettingsSyncService.kt — full file read, 393 lines, settings blob sync with debounce/flatMapLatest [VERIFIED: NuvioTV source]
- Nexio AccountSettingsSyncService.kt — 400+ lines read, v7 sync contract, push/pull/observe patterns, withJwtRefreshRetry [VERIFIED: file read]
- Nexio ProfileManager.kt — full file read, deleteProfileDataAsync(), createProfile() [VERIFIED: file read]
- Nexio ProfileDataStoreFactory.kt — full file read, get(), clearProfile(), per-profile naming convention [VERIFIED: file read]
- Nexio StartupSyncService.kt — full file read, startup pull flow, push gate pattern [VERIFIED: file read]
- Nexio AccountSyncModels.kt — full file read, v7 contract data classes, existing schema [VERIFIED: file read]
- Nexio SupabaseModels.kt — full file read, SupabaseProfile model (missing avatarId, pinEnabled confirmed) [VERIFIED: file read]
- All 7 per-profile SP stores — PREFS_NAME constants and read/write/clear patterns [VERIFIED: grep + file reads]
- All 5 shared SP stores — confirmed hardcoded PREFS_NAME, no ProfileManager injection [VERIFIED: grep]

### Secondary (MEDIUM confidence)
- Nexio UserProfile model — fields verified (id, name, avatarColorHex, usesPrimaryAddons, avatarId, pinEnabled)
- Phase 4 CONTEXT.md — all locked decisions (D-01 through D-12) and discretion areas

### Tertiary (LOW confidence)
- None

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in project, no new dependencies
- Architecture (SP naming, deletion): HIGH — derived from verified codebase patterns
- Architecture (v8 blob sync): HIGH — porting proven NuvioTV patterns with source available
- SQL schema: MEDIUM — follows NuvioTV conventions but exact schema is Claude's discretion
- Phase 2 DataStore dependency: MEDIUM — flatMapLatest usage verified for all 5 features, but factory backing assumed complete

**Research date:** 2026-04-14
**Valid until:** 2026-05-14 (30 days — stable domain, no external dependency changes expected)
