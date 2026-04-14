# Phase 4: Sync and Cleanup - Context

**Gathered:** 2026-04-14
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase adds Supabase sync for profile metadata and per-profile settings, migrates SharedPreferences snapshot stores to per-profile naming, and ensures profile deletion leaves no orphaned data on-device or in the cloud. No new UI screens. No changes to the existing v7 shared sync contract.

</domain>

<decisions>
## Implementation Decisions

### Snapshot Store Scoping
- **D-01:** All 7 SharedPreferences snapshot stores become per-profile with profile-suffixed filenames: TraktLibrarySnapshotStore, ContinueWatchingSnapshotStore, SimklLibrarySnapshotStore, SimklDiscoverySnapshotStore, TraktDiscoverySnapshotStore, MDBListDiscoverySnapshotStore, HomeCatalogSnapshotStore. Profile 1 keeps bare names for zero-migration.
- **D-02:** Additional SharedPreferences stores classified per-profile where tied to auth/profile state: SimklProgressSyncStateStore, TraktMutationOutboxStore, SyntheticHomeCatalogStore become per-profile. MetadataDiskCacheStore and CatalogDiskCacheStore stay shared (content metadata doesn't change per profile).

### Settings Sync Transport
- **D-03:** Port NuvioTV's ProfileSettingsSyncService blob pattern. New service serializes all 8 per-profile DataStores into one JSON blob per profile. Push/pull via dedicated Supabase RPCs (sync_push_profile_settings / sync_pull_profile_settings). Runs alongside existing v7 shared sync.
- **D-04:** Existing v7 shared sync contract stays untouched. Per-profile settings that were previously in v7 (Trakt catalog prefs, Simkl catalog prefs, player formatter, tracking provider) get removed from v7 and move to per-profile blob only. Clean separation.
- **D-05:** Profile metadata sync uses dedicated RPCs: sync_push_profiles / sync_pull_profiles, ported from NuvioTV's ProfileSyncService. Metadata (name, avatar, PIN state) synced as full profile list.
- **D-06:** Supabase RPCs and table schema need to be created as part of this phase. Backend SQL functions are in-scope.

### Sync Timing and Triggers
- **D-07:** Per-profile settings push uses debounced-on-change pattern (~1.5s debounce), ported from NuvioTV. Observe per-profile DataStore changes via flatMapLatest, auto-push blob after debounce.
- **D-08:** Per-profile settings pull on app startup (after auth) and on profile switch. Ensures fresh data from cross-device changes.
- **D-09:** Profile metadata push immediately on any create/edit/delete operation. No debounce needed — metadata changes are infrequent and small.

### Deletion Cleanup Scope
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

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### NuvioTV Reference Implementation (sync source)
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/core/sync/ProfileSyncService.kt` — Profile metadata push/pull via sync_push_profiles / sync_pull_profiles RPCs
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/core/sync/ProfileSettingsSyncService.kt` — Per-profile settings blob sync with debounce, flatMapLatest on activeProfileId, syncedFeatures list

### Nexio Existing Sync Infrastructure
- `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` — Existing v7 shared sync (understand what to leave untouched, and which per-profile paths to remove)
- `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt` — v7 contract: observeAccountConfigSyncChanges, buildAccountConfigSyncPayload, push/pull params
- `app/src/main/java/com/nexio/tv/data/repository/SyncRepositoryImpl.kt` — Existing Supabase RPC call pattern (postgrest.rpc, Result wrapping, JWT refresh)

### Nexio Profile Infrastructure (Phase 1)
- `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt` — deleteProfile() with deleteProfileDataAsync(), createProfile(), updateProfile()
- `app/src/main/java/com/nexio/tv/data/local/ProfileDataStoreFactory.kt` — clearProfile(), get() with profile-suffixed filenames

### Nexio Snapshot Stores (migration targets)
- `app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt` — SharedPreferences "trakt_library_snapshot", needs per-profile naming
- `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt` — SharedPreferences "continue_watching_snapshot"
- `app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt` — SharedPreferences "simkl_library_snapshot"
- `app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStore.kt` — SharedPreferences "simkl_discovery_snapshot_v2"
- `app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt` — SharedPreferences "trakt_discovery_snapshot"
- `app/src/main/java/com/nexio/tv/data/local/MDBListDiscoverySnapshotStore.kt` — SharedPreferences "mdblist_discovery_snapshot"
- `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt` — SharedPreferences "home_catalog_snapshot"
- `app/src/main/java/com/nexio/tv/data/local/SimklProgressSyncStateStore.kt` — SharedPreferences "simkl_progress_sync_state"
- `app/src/main/java/com/nexio/tv/data/local/SyntheticHomeCatalogStore.kt` — SharedPreferences "synthetic_home_catalogs"

### Phase 2 Context (dependency)
- `.planning/phases/02-per-profile-auth-and-settings/02-CONTEXT.md` — Per-profile DataStore migration decisions, flatMapLatest pattern, auth switch behavior

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ProfileDataStoreFactory` — already handles per-profile DataStore creation and cleanup; extend pattern to SharedPreferences naming
- `ProfileManager.deleteProfileDataAsync()` — already deletes DataStore files by suffix; extend to cover SharedPreferences files and remote cleanup
- `AccountConfigStartupPushGate` — pull-before-push gate pattern; can be adapted for per-profile settings sync
- `SyncRepositoryImpl` — established pattern for Supabase RPC calls with Result wrapping

### Established Patterns
- All snapshot stores use `context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)` — PREFS_NAME needs to become profile-aware
- Existing v7 sync uses `observeAccountConfigSyncChanges()` to merge DataStore flows — per-profile sync will use similar flow merging with flatMapLatest
- NuvioTV ProfileSettingsSyncService reads raw DataStore Preferences and serializes to JSON — no domain model mapping needed
- JWT refresh retry pattern via `withJwtRefreshRetry` in NuvioTV sync services

### Integration Points
- `ProfileManager` — deletion flow needs extension for SP cleanup, remote deletion, and token revocation
- `AccountSettingsSyncService` — per-profile settings must be removed from v7 push/pull to avoid double-syncing
- `StartupSyncService` — needs to trigger per-profile settings pull alongside existing shared sync
- `TraktAuthService` / `SimklAuthService` — token revocation on profile deletion
- Supabase backend — new RPCs and table(s) for profile metadata and per-profile settings blobs

</code_context>

<specifics>
## Specific Ideas

- Port NuvioTV's ProfileSyncService and ProfileSettingsSyncService as closely as possible — these are proven patterns
- SharedPreferences per-profile naming should mirror DataStore convention: bare name for profile 1, `{name}_p{id}` for profiles 2-4
- Deletion is a multi-step cleanup: revoke tokens (best-effort) -> delete remote data (best-effort) -> delete local DataStore files -> delete local SharedPreferences files -> remove from profile list
- Supabase SQL functions should handle idempotent push (upsert semantics) and return clean responses matching existing RPC patterns

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 04-sync-and-cleanup*
*Context gathered: 2026-04-14*
