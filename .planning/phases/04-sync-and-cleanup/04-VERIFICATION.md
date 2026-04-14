---
phase: 04-sync-and-cleanup
verified: 2026-04-14T14:13:53Z
status: gaps_found
score: 17/18 must-haves verified
overrides_applied: 0
gaps:
  - truth: "Per-profile settings push and pull via independent blob RPCs, not the shared v7 contract, so Profile 2 changes never overwrite Profile 1 data"
    status: partial
    reason: "ProfileSettingsSyncService has v8 RPC push/pull and profileId-scoped DataStore access, but pull import is not a full snapshot apply: missing remote keys and omitted feature blobs leave old local preferences in place. The profile-switch observer also allows pushes for a newly selected profile without first pulling that profile's remote blob."
    artifacts:
      - path: "app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt"
        issue: "importSettingsBlob() writes only keys present in the remote blob and never clears absent keys or absent synced feature blobs."
      - path: "app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt"
        issue: "startObserving() switches to the selected profile and can push after debounce without a pullBlobForProfile(profileId) gate for that profile."
    missing:
      - "Clear each synced feature DataStore, or reconcile deletions explicitly, before applying that feature's remote blob."
      - "Treat a missing synced feature in the remote blob as an empty snapshot for that feature."
      - "Hydrate a profile's settings blob on profile switch before enabling observer-driven pushes for that profile."
---

# Phase 4: Sync and Cleanup Verification Report

**Phase Goal:** Profile metadata and per-profile settings sync to Supabase, and deleting a profile leaves no orphaned data anywhere on-device or in the cloud
**Verified:** 2026-04-14T14:13:53Z
**Status:** gaps_found
**Re-verification:** No - initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Profile metadata syncs to Supabase and is restored on startup/new device | VERIFIED | `ProfileSyncService.pushToRemote()` writes `profile_index`, `name`, `avatar_color_hex`, `avatar_id`, and `pin_enabled` to `sync_push_profiles`; `pullFromRemote()` calls `sync_pull_profiles`, maps to `UserProfile`, and calls `replaceAllProfiles()` on non-empty results. `StartupSyncService.pullRemoteProfileState()` calls `profileSyncService.pullFromRemote()` before v7 account snapshot sync. |
| 2 | Per-profile settings push and pull via independent blob RPCs, not v7 | FAILED | `ProfileSettingsSyncService` uses `sync_push_profile_settings_blob` and `sync_pull_profile_settings_blob` keyed by `p_profile_id`, but `importSettingsBlob()` only writes remote-present keys and never clears old local keys. `startObserving()` pushes on profile switch without first pulling that switched profile's blob. This can leave stale local settings or overwrite remote profile settings with local defaults. |
| 3 | Profile deletion removes DataStore files, SharedPreferences files, and remote data | VERIFIED | `ProfileManager.deleteProfile()` rejects profile 1, calls `deleteProfileRemote()`, `factory.clearProfile(profileId)`, deletes `_p{id}.preferences_pb` files, and calls `deleteSharedPreferencesForProfile()` for the 7 per-profile SP stores. Remote failure records `pending_remote_cleanup`; `StartupSyncService.retryPendingRemoteCleanup()` retries `sync_delete_profile`. |
| 4 | Snapshot stores are classified and scoped per-profile where applicable | VERIFIED | The 7 migrated SP-backed stores import `profilePrefsName`, read `profileManager.activeProfileId.value` in production constructors, and call `context.getSharedPreferences(prefsName(), ...)`; profile 1 keeps bare names and profiles 2-4 get `_p{id}` suffixes. |
| 5 | Each of 7 SharedPreferences snapshot stores resolves names dynamically at call time | VERIFIED | `TraktLibrarySnapshotStore`, `ContinueWatchingSnapshotStore`, `SimklLibrarySnapshotStore`, `SimklDiscoverySnapshotStore`, `SimklProgressSyncStateStore`, `TraktDiscoverySnapshotStore`, and `TraktMutationOutboxStore` all call `prefsName()` at read/write/clear sites. |
| 6 | Profile 1 snapshot stores use bare PREFS_NAME | VERIFIED | `profilePrefsName(baseName, 1)` returns `baseName`; tests assert `trakt_library_snapshot` for profile 1. |
| 7 | Profile 2-4 snapshot stores use `_p{id}` suffix | VERIFIED | `profilePrefsName(baseName, profileId)` returns `"${baseName}_p${profileId}"` when `profileId != 1`; tests cover profiles 2 and 4. |
| 8 | TraktLibrarySnapshotStore resolves to `trakt_library_snapshot_p2` for profile 2 | VERIFIED | `TraktLibrarySnapshotStoreTest` asserts `profilePrefsName(TraktLibrarySnapshotStore.BASE_PREFS_NAME, 2) == "trakt_library_snapshot_p2"`. |
| 9 | Settings blob push serializes typed preferences keyed by profileId | VERIFIED | `pushBlobForProfile(profileId)` exports five `syncedFeatures`, encodes typed preference values, and calls `sync_push_profile_settings_blob` with `p_profile_id`, `p_settings_json`, and `p_platform`. |
| 10 | Settings blob pull applies preferences to the correct profile stores | FAILED | It targets `profileDataStoreFactory.get(profileId, feature)`, but the full snapshot semantics are incomplete because missing keys/features are not cleared. |
| 11 | Settings observer debounces and cancels pending work on profile switch | VERIFIED | `startObserving()` uses `profileManager.activeProfileId.flatMapLatest`, `drop(1)`, and `debounce(2000)` before `pushBlobForProfile(profileId)`. |
| 12 | Echo pushes after pull are suppressed | VERIFIED | `pullBlobForProfile()` sets `applyingRemoteBlob` during import and assigns `skipNextPushSignature`; `pushBlobForProfile()` returns early when the exported signature matches. |
| 13 | Settings push and pull are mutex-serialized | VERIFIED | Both `pushBlobForProfile()` and `pullBlobForProfile()` wrap work in `syncMutex.withLock`. |
| 14 | Startup pulls profile metadata and active settings before v7 account sync continues | VERIFIED | `scheduleStartupPull()` runs `pullRemoteProfileState().fold(... pullRemoteSnapshot() ...)`; `pullRemoteProfileState()` calls profile metadata pull, active profile blob pull, then `startObserving()`. |
| 15 | Per-profile settings paths are removed from v7 observer and payload builder | VERIFIED | v7 observer passes `emptyFlow()` for `traktCatalogPreferences`, `simklCatalogPreferences`, and `playerSettings`; payload builder emits empty/default values with `Moved to v8` comments for Trakt/Simkl catalogs, tracking provider, and formatter. The old `applyRemoteSettings()` path appears uncalled. |
| 16 | Failed remote cleanup is retried on next app start | VERIFIED | `deleteProfileRemote()` persists failed IDs in `profile_cleanup_state` under `pending_remote_cleanup`; `StartupSyncService.retryPendingRemoteCleanup()` reads and retries those IDs, removing successes. |
| 17 | Settings Sync Now triggers profile metadata and settings blob push with feedback | VERIFIED | `SettingsViewModel.triggerSyncNow()` calls `profileSyncService.pushToRemote()` and `profileSettingsSyncService.pushBlobForProfile(activeId)` and drives `SyncStatus`; `SettingsScreen` renders `Sync Now`, progress, success, and error text. |
| 18 | Delete confirmation uses NexioDialog with safe and destructive actions | VERIFIED | `DeleteProfileDialog()` uses `NexioDialog`, focuses `Keep Profile`, and exposes a destructive `Delete Profile` button that calls `confirmDeleteProfile()`. |

**Score:** 17/18 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt` | Profile metadata push/pull to Supabase | VERIFIED | Exists, substantive, uses `sync_push_profiles`, `sync_pull_profiles`, maps `avatarId` and `pinEnabled`, and is injected into startup/settings flows. |
| `app/src/main/java/com/nexio/tv/core/sync/ProfilePrefsName.kt` | Per-profile SharedPreferences naming helper | VERIFIED | Exists and implements bare profile 1 plus `_p{id}` suffix for others. |
| `app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt` | `SupabaseProfile` avatar/PIN metadata | VERIFIED | Contains `@SerialName("avatar_id") val avatarId` and `@SerialName("pin_enabled") val pinEnabled`. |
| `app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt` | Per-profile settings blob sync | PARTIAL | Exists and wired, but pull import does not clear removed remote keys/features and profile-switch push can run before pull. |
| `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt` | `ProfileSettingsBlobResponse` model | VERIFIED | Contains `profile_id`, `settings_json`, platform, user ID, and updated-at fields. |
| `app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt` | Startup metadata/settings pull and remote cleanup retry | VERIFIED | Pulls profile metadata, active settings blob, starts observer, then continues v7 account sync; retries pending cleanup IDs. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` | v7 cleanup for per-profile paths | VERIFIED | Per-profile observer inputs are `emptyFlow()` and v7 payload/apply sections mark Trakt/Simkl catalog and player tracking/formatter as moved to v8. |
| `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt` | Profile deletion cleanup | VERIFIED | Deletes non-primary local DataStore files, clears/deletes 7 SP files, calls remote cleanup RPC, and persists retry IDs on failure. |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt` | Sync Now and delete dialog UI | VERIFIED | Renders Sync Now status controls and a `NexioDialog` delete confirmation. |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsViewModel.kt` | Sync/delete actions | VERIFIED | Sync Now pushes metadata and active blob; delete action calls `ProfileManager.deleteProfile()`. |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `ProfileSyncService` | Supabase Postgrest | `sync_push_profiles` and `sync_pull_profiles` RPCs | WIRED | Manual grep found both RPCs and response handling. |
| Snapshot stores | `ProfileManager.activeProfileId` | `profilePrefsName()` at read/write/clear | WIRED | All 7 migrated SP stores use an injected active profile lambda from `profileManager.activeProfileId.value` in production constructors. |
| `ProfileSettingsSyncService` | `ProfileDataStoreFactory` | `profileDataStoreFactory.get(profileId, feature)` | WIRED | Used in observer, export, and import paths. |
| Settings observer | Supabase Postgrest | debounce to `pushBlobForProfile(profileId)` | WIRED | Observer pushes after `flatMapLatest` and `debounce(2000)`, but lacks pull-before-push gating for newly selected profiles. |
| `StartupSyncService` | `ProfileSyncService.pullFromRemote()` | startup pull sequence | WIRED | Called before account snapshot sync fallback proceeds. |
| `StartupSyncService` | `ProfileSettingsSyncService.pullBlobForProfile()` | active profile blob pull | WIRED | Called for `profileManager.activeProfileId.value`. |
| `ProfileManager.deleteProfile()` | SP cleanup | `deleteSharedPreferencesForProfile()` | WIRED | Called after DataStore cleanup in deletion sequence. |
| `SettingsViewModel.triggerSyncNow()` | metadata/blob push | ProfileSyncService + ProfileSettingsSyncService | WIRED | Calls both push methods and reports combined status. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|---|---|---|---|---|
| `ProfileSyncService.kt` | `profiles` | `profileManager.profiles.value` | Yes | FLOWING - payload built from current profile state and decoded remote rows replace local profiles. |
| `ProfileSettingsSyncService.kt` | `blob` | Profile-scoped DataStore preferences via `ProfileDataStoreFactory.get(profileId, feature).data.first()` | Partial | HOLLOW - full snapshot semantics are incomplete on import because stale local keys survive if absent remotely. |
| `StartupSyncService.kt` | active profile ID | `profileManager.activeProfileId.value` | Yes | FLOWING - pulls the active profile's settings blob before v7 sync. |
| `SettingsScreen.kt` | sync/delete UI state | `SettingsViewModel` StateFlows | Yes | FLOWING - UI collects `syncStatus`, `activeProfile`, `showDeleteDialog`, and `deleteInProgress`. |
| `ProfileManager.kt` | profile cleanup target | `deleteProfile(id)` argument plus current profile list | Yes | FLOWING - validates non-primary existing profile, then deletes local and remote profile-scoped data. |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| Production Android build | Not rerun in verifier; known gate context says `./gradlew assembleArm64Debug` passed during each executor plan | Build previously passed | PASS |
| Targeted unit tests | Not rerun in verifier; known gate context says `compileArm64DebugUnitTestKotlin` fails before execution due unrelated stale tests outside Phase 4 ownership | Blocked before phase tests can execute | SKIP |
| Schema drift | Known gate context says schema drift check returned `drift_detected=false` | No schema drift | PASS |
| Runtime Supabase RPC behavior | Not invoked; would require live auth/session and remote Supabase | External service integration | SKIP |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| SYNC-01 | 04-01, 04-03 | Profile metadata syncs to Supabase | SATISFIED | Metadata push/pull RPCs exist, include name/avatar/PIN state, and startup pulls metadata before v7 account sync. |
| SYNC-02 | 04-02, 04-03 | Per-profile settings sync via independent blob push/pull, not v7 contract | PARTIAL | Dedicated v8 blob RPCs and v7 cleanup exist, but remote deletes/absent keys are not applied locally and profile-switch push can run before remote blob pull. |
| SYNC-03 | 04-03 | Profile deletion removes all DataStore files, SharedPreferences, and Supabase remote data | SATISFIED | Code deletes local profile DataStore/SP artifacts, calls `sync_delete_profile`, and persists retry IDs on remote failure. |
| SYNC-04 | 04-01 | Snapshot stores are classified and scoped per-profile where applicable | SATISFIED | 7 SP stores use profile-scoped names; profile 1 bare naming is preserved. |

No orphaned Phase 4 requirement IDs were found in `.planning/REQUIREMENTS.md`; SYNC-01 through SYNC-04 are all claimed by Phase 4 plans.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|---|---:|---|---|---|
| `app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt` | 186 | Full snapshot import without clearing existing preferences | Blocker | Remote-cleared settings remain local and can be pushed back upstream. |
| `app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt` | 82 | Profile-switch observer lacks pull-before-push gate | Warning | A newly selected profile can push local defaults/old data before remote settings are hydrated. |
| `app/src/test/java/com/nexio/tv/core/sync/ProfileSyncServiceTest.kt` | 7 | Ignored placeholder test class | Info | New metadata sync behavior is unprotected by executable unit tests, but production code is present and wired. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` | 218 | Account settings push suppression depends on external pull | Warning | Review debt: shared account auto-push can remain suppressed after profile switch. This affects shared account sync more than the Phase 4 profile metadata/settings/deletion goal. |

### Human Verification Required

Not gating this report because automated verification already found a blocking gap. The Phase 04-03 human checkpoint remains true: non-primary profile deletion cannot be fully validated on-device until the app exposes a user-accessible way to add/select non-primary profiles.

### Gaps Summary

Phase 4 is mostly implemented and wired, but SYNC-02 is only partial. The v8 per-profile settings blob service exists and avoids the shared v7 contract, yet the pull path is not a real full-snapshot sync: old local preferences survive when remote keys are removed. In addition, profile switches can begin observing and pushing the selected profile without first hydrating that profile's remote blob.

Fix `ProfileSettingsSyncService.importSettingsBlob()` to clear or reconcile each synced feature before applying remote values, and gate profile-switch observer pushes behind a successful `pullBlobForProfile(profileId)` for that profile.

---

_Verified: 2026-04-14T14:13:53Z_
_Verifier: Claude (gsd-verifier)_
