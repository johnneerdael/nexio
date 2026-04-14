---
phase: 04-sync-and-cleanup
verified: 2026-04-14T16:59:48Z
status: gaps_found
score: 20/23 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: 17/18
  gaps_closed:
    - "ProfileSettingsSyncService.importSettingsBlob() now clears each synced feature store before applying remote values, treats missing synced feature blobs as empty snapshots, normalizes remote blobs, and signs the normalized blob for echo-push suppression."
    - "ProfileSettingsSyncService.startObserving() now calls pullBlobForProfile(profileId) before observeProfileSettings(profileId), and failed hydration prevents observer-driven pushes for that profile."
  gaps_remaining:
    - "SYNC-02 remains partial because the v8 settings blob watches layout_preferences while the real LayoutPreferenceDataStore uses layout_settings, and layout/catalog-order flows still participate in the shared v7 account sync path."
  regressions: []
gaps:
  - truth: "Per-profile settings push and pull via independent blob RPCs, not the shared v7 contract, so Profile 2 changes never overwrite Profile 1 data"
    status: partial
    reason: "04-04 fixed full-snapshot import and pull-before-observe gating, but the actual layout/catalog-order settings do not flow through the v8 per-profile blob. ProfileSettingsSyncService syncs a separate layout_preferences DataStore, while LayoutPreferenceDataStore persists to layout_settings. AccountSettingsSyncService also still observes, exports, and applies layout catalog-order values through the shared v7 account contract."
    artifacts:
      - path: "app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt"
        issue: "syncedFeatures contains layout_preferences, which is not the real LayoutPreferenceDataStore feature name."
      - path: "app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt"
        issue: "The real layout settings DataStore feature name is layout_settings."
      - path: "app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt"
        issue: "v7 account sync still observes, pushes, and applies layout catalog-order settings from LayoutPreferenceDataStore."
      - path: "app/src/test/java/com/nexio/tv/core/sync/ProfileSettingsSyncServiceTest.kt"
        issue: "The syncedFeatures regression test locks in layout_preferences instead of the actual layout_settings feature."
    missing:
      - "Change ProfileSettingsSyncService.syncedFeatures and tests from layout_preferences to layout_settings, or add an intentional compatibility bridge that reads/writes the real layout_settings store."
      - "Remove or neutralize per-profile layout/catalog-order paths from the shared v7 account sync observer, payload builder, and apply path so non-primary profile layout changes cannot overwrite shared/Profile 1 data."
      - "Add a regression that writes ProfileDataStoreFactory.get(profileId, \"layout_settings\") and proves v8 export/import observes that store, not layout_preferences."
---

# Phase 4: Sync and Cleanup Verification Report

**Phase Goal:** Profile metadata and per-profile settings sync to Supabase, and deleting a profile leaves no orphaned data anywhere on-device or in the cloud
**Verified:** 2026-04-14T16:59:48Z
**Status:** gaps_found
**Re-verification:** Yes - after gap closure plan 04-04

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|---|---|---|
| 1 | Profile metadata syncs to Supabase and is restored on startup/new device | VERIFIED | `ProfileSyncService.pushToRemote()` calls `sync_push_profiles`; `pullFromRemote()` calls `sync_pull_profiles`, maps `avatar_id` and `pin_enabled`, filters profile IDs to 1..4, and calls `replaceAllProfiles()` on non-empty pulls. `StartupSyncService.pullRemoteProfileState()` calls `profileSyncService.pullFromRemote()` before v7 account sync. |
| 2 | Per-profile settings push and pull via independent blob RPCs, not v7, so Profile 2 changes never overwrite Profile 1 data | FAILED | Dedicated v8 blob RPCs exist, and 04-04 fixed full-snapshot import plus pull-before-observe. However, `ProfileSettingsSyncService.syncedFeatures` uses `layout_preferences` while the real layout store uses `layout_settings`, and v7 account sync still observes/exports/applies layout catalog-order preferences. Layout/catalog-order changes can therefore bypass the intended per-profile v8 path. |
| 3 | Deleting a profile removes DataStore files, SharedPreferences files, and Supabase remote data | VERIFIED | `ProfileManager.deleteProfile()` rejects profile 1, attempts `sync_delete_profile`, calls `factory.clearProfile(profileId)`, deletes `_p{id}.preferences_pb` files, clears/deletes the 7 per-profile SharedPreferences XML files, and persists `pending_remote_cleanup` on remote failure. |
| 4 | TraktLibrary and ContinueWatching snapshot stores are classified and scoped per-profile where applicable, with shared stores remaining shared | VERIFIED | The 7 migrated SP-backed stores use `profilePrefsName()` and dynamic active-profile lookup; the shared stores listed in the plan were not migrated. |
| 5 | Profile metadata can be pushed to Supabase via `ProfileSyncService.pushToRemote()` | VERIFIED | Push payload includes `profile_index`, `name`, `avatar_color_hex`, `uses_primary_addons`, `avatar_id`, and `pin_enabled`, then calls `postgrest.rpc("sync_push_profiles", params)`. |
| 6 | Profile metadata can be pulled and replaces local profiles atomically | VERIFIED | Pull decodes `SupabaseProfile`, maps to `UserProfile`, and writes via `ProfileDataStore.replaceAllProfiles()` inside one DataStore edit. |
| 7 | Each of 7 SharedPreferences snapshot stores resolves PREFS_NAME dynamically per active profile at call time | VERIFIED | All 7 SP stores call `prefsName()` at read/write/clear sites; production constructors source the active profile from `profileManager.activeProfileId.value`. |
| 8 | Profile 1 snapshot stores use bare PREFS_NAME | VERIFIED | `profilePrefsName(baseName, 1)` returns `baseName`; tests assert bare `trakt_library_snapshot`. |
| 9 | Profile 2-4 snapshot stores use `_p{id}` suffix | VERIFIED | `profilePrefsName(baseName, profileId)` returns `"${baseName}_p${profileId}"` for non-primary IDs; tests cover profiles 2 and 4. |
| 10 | `TraktLibrarySnapshotStore` resolves to `trakt_library_snapshot_p2` for profile 2 | VERIFIED | `TraktLibrarySnapshotStoreTest` asserts `profilePrefsName(TraktLibrarySnapshotStore.BASE_PREFS_NAME, 2) == "trakt_library_snapshot_p2"`. |
| 11 | Per-profile settings serialize into typed JSON blob and push keyed by profileId | FAILED | Typed encoding and profileId RPC push exist, but the blob includes `layout_preferences` instead of the real `layout_settings` DataStore. The blob does not serialize actual layout/catalog-order settings. |
| 12 | Pulling a settings blob applies encoded preferences to the correct profile's DataStore instances | VERIFIED | After 04-04, `importSettingsBlob()` normalizes the blob, iterates synced features, calls `profileDataStoreFactory.get(profileId, feature)`, clears the feature store, and applies encoded values. This is verified for configured synced features; the layout feature-name mismatch is counted in truths 2 and 11. |
| 13 | Settings observer debounces DataStore changes and cancels pending work on profile switch | VERIFIED | `startObserving()` uses `activeProfileId.map { it }.distinctUntilChanged().flatMapLatest`, then `drop(1).debounce(2000)` before pushing. |
| 14 | Echo pushes after pull are suppressed | VERIFIED | `pullBlobForProfile()` sets `applyingRemoteBlob`, imports the normalized blob, stores `skipNextPushSignature`, and `pushBlobForProfile()` returns early when signatures match. |
| 15 | Settings push and pull are mutex-serialized | VERIFIED | Both `pushBlobForProfile()` and `pullBlobForProfile()` run inside `syncMutex.withLock`. |
| 16 | Startup pulls profile metadata and active settings before v7 push is allowed | VERIFIED | `pullRemoteProfileState()` retries pending cleanup, pulls metadata, pulls the active profile settings blob, starts the v8 observer, and only then the startup sequence proceeds to account snapshot sync. |
| 17 | Per-profile settings paths are removed from v7 observer and payload builder | FAILED | Trakt catalog, Simkl catalog, and player paths were removed or emptied, but layout/catalog-order paths remain in v7: `heroCatalogSelections`, `homeCatalogOrderKeys`, and `disabledHomeCatalogKeys` are still observed, exported, and applied through `AccountSettingsSyncService`. |
| 18 | Failed remote cleanup is retried on next app start | VERIFIED | `deleteProfileRemote()` stores failed IDs under `pending_remote_cleanup`; `StartupSyncService.retryPendingRemoteCleanup()` reads, retries `sync_delete_profile`, removes successes, and bounds the pending set. |
| 19 | Settings Sync Now pushes profile metadata and settings blob with feedback | VERIFIED | `SettingsViewModel.triggerSyncNow()` calls `profileSyncService.pushToRemote()` and `profileSettingsSyncService.pushBlobForProfile(activeId)`; `SettingsScreen` renders Sync Now status feedback. |
| 20 | Delete confirmation uses `NexioDialog` with safe and destructive actions | VERIFIED | `DeleteProfileDialog()` uses `NexioDialog`, shows "Keep Profile", and exposes a destructive "Delete Profile" action. |
| 21 | Settings blob pull applies full snapshot semantics for every synced feature | VERIFIED | 04-04 added `normalizeSettingsBlob()` and `preferences.clear()` inside `importSettingsBlob()` for every synced feature. |
| 22 | Remote-absent preference keys and missing synced feature blobs clear local preferences | VERIFIED | `importSettingsBlob()` normalizes missing features to `{}` and clears each feature store before applying remote-present keys; tests cover absent keys and missing feature blobs. |
| 23 | Profile switch hydration runs before observer-driven pushes | VERIFIED | `startObserving()` calls `pullBlobForProfile(profileId)` before `observeProfileSettings(profileId)` and returns without emitting if hydration fails. |

**Score:** 20/23 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt` | Profile metadata push/pull to Supabase | VERIFIED | Exists, substantive, calls `sync_push_profiles` and `sync_pull_profiles`, maps avatar/PIN fields, and is wired into startup and Sync Now. Review warning remains: push reads `profileManager.profiles.value`, which can lag direct DataStore writes. |
| `app/src/main/java/com/nexio/tv/core/sync/ProfilePrefsName.kt` | Per-profile SharedPreferences naming helper | VERIFIED | Bare profile 1 and `_p{id}` suffix for profiles 2-4. |
| `app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt` | `SupabaseProfile` avatar/PIN metadata | VERIFIED | Contains `@SerialName("avatar_id") val avatarId` and `@SerialName("pin_enabled") val pinEnabled`. |
| `app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt` | Per-profile settings blob sync | PARTIAL | Full-snapshot import and pull-before-observe are now present, but `syncedFeatures` uses `layout_preferences` instead of actual `layout_settings`. |
| `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt` | `ProfileSettingsBlobResponse` model | VERIFIED | Contains the profile settings blob response model used by `pullBlobForProfile()`. |
| `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt` | Actual profile-scoped layout settings store | VERIFIED | Uses `ProfileDataStoreFactory.get(profileId, "layout_settings")`; this confirms the mismatch with `layout_preferences`. |
| `app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt` | Startup metadata/settings pull and cleanup retry | VERIFIED | Pulls profile metadata, pulls active settings blob, starts observer, and retries pending remote cleanup. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` | v7 cleanup for per-profile settings paths | PARTIAL | Trakt/Simkl/player paths are emptied, but layout/catalog-order values still flow through shared v7 observer, payload, and apply paths. |
| `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt` | Profile deletion cleanup | VERIFIED | Deletes non-primary local DataStore and SP files and attempts/persists remote cleanup. |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt` | Sync Now and delete dialog UI | VERIFIED | Renders Sync Now and `NexioDialog` delete confirmation. |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsViewModel.kt` | Sync/delete actions | VERIFIED | Sync Now pushes metadata and active profile blob; delete action calls `ProfileManager.deleteProfile()`. |
| `app/src/test/java/com/nexio/tv/core/sync/ProfileSettingsSyncServiceTest.kt` | Regression tests for 04-04 behavior | PARTIAL | Contains 04-04 regression tests, but also asserts `layout_preferences`, preserving the wrong feature-name contract. Test execution remains blocked by unrelated source-set compile drift. |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `ProfileSyncService` | Supabase Postgrest | `sync_push_profiles` / `sync_pull_profiles` | WIRED | Both RPCs are present with response handling. |
| `ProfileSyncService.pullFromRemote()` | `ProfileDataStore.replaceAllProfiles()` | decoded remote profile list | WIRED | Non-empty remote profile list replaces local profiles. |
| Snapshot stores | `ProfileManager.activeProfileId` | `profilePrefsName()` at read/write/clear time | WIRED | All 7 migrated SP stores resolve profile-specific names dynamically. |
| `ProfileSettingsSyncService` | Supabase Postgrest | `sync_push_profile_settings_blob` / `sync_pull_profile_settings_blob` | WIRED | Dedicated v8 RPCs are used with `p_profile_id` and `p_platform`. |
| `ProfileSettingsSyncService` | `ProfileDataStoreFactory` | `profileDataStoreFactory.get(profileId, feature)` | PARTIAL | Correctly profile-scoped for listed features, but the listed layout feature is wrong (`layout_preferences` instead of `layout_settings`). |
| `ProfileSettingsSyncService.pullBlobForProfile()` | `importSettingsBlob()` | normalized blob and signature | WIRED | `normalizeSettingsBlob(rawBlob)` is used for both import and `skipNextPushSignature`. |
| `ProfileSettingsSyncService.startObserving()` | `pullBlobForProfile()` | profile hydration gate before observer | WIRED | Pull runs before `observeProfileSettings(profileId)` and failure skips pushes. |
| `StartupSyncService` | metadata/settings pull | startup pull sequence | WIRED | Calls `profileSyncService.pullFromRemote()` and `profileSettingsSyncService.pullBlobForProfile(activeId)`. |
| `AccountSettingsSyncService` | shared v7 account sync | layout preference observer/payload/apply | NOT_WIRED_TO_GOAL | Layout/catalog-order values remain wired to shared v7, contrary to SYNC-02's independent blob intent. |
| `ProfileManager.deleteProfile()` | local/remote cleanup | DataStore, SP, and Supabase cleanup | WIRED | Cleanup code and retry persistence are present. |
| `SettingsViewModel.triggerSyncNow()` | metadata/blob push | Sync Now button | WIRED | Calls both profile push services and updates status. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|---|---|---|---|---|
| `ProfileSyncService.kt` | `profiles` | `profileManager.profiles.value` | Yes, but possibly stale | FLOWING with warning - payload is real, but review correctly notes direct DataStore reads would avoid StateFlow lag after immediate profile edits. |
| `ProfileSettingsSyncService.kt` | `blob` | `ProfileDataStoreFactory.get(profileId, feature).data.first()` | Partial | HOLLOW for layout - exports/imports a real blob for listed features, but the layout entry points at an unused `layout_preferences` store. |
| `LayoutPreferenceDataStore.kt` | layout/catalog-order preferences | `ProfileDataStoreFactory.get(profileId, "layout_settings")` | Yes | DISCONNECTED from v8 - the actual layout store is not included in `ProfileSettingsSyncService.syncedFeatures`. |
| `AccountSettingsSyncService.kt` | layout/catalog-order preferences | `layoutPreferenceDataStore` flows | Yes | MISROUTED - still flows through shared v7 account sync rather than the independent per-profile blob path. |
| `StartupSyncService.kt` | active profile settings | `profileManager.activeProfileId.value` | Yes | FLOWING - pulls active settings blob and starts v8 observer. |
| `ProfileManager.kt` | profile cleanup target | `deleteProfile(id)` and profile list | Yes | FLOWING - validates non-primary profile and deletes local/remote scoped data. |
| `SettingsScreen.kt` | sync/delete UI state | `SettingsViewModel` state flows | Yes | FLOWING - UI actions reach push/delete services. |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| Production Android build | `./gradlew assembleArm64Debug` | Known gate context: passed after 04-04 | PASS |
| ProfileSettingsSyncService targeted unit test | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.ProfileSettingsSyncServiceTest"` | Known gate context: blocked by unrelated unit-test source-set compile drift; target file not reported as failing source | SKIP |
| Schema drift | schema drift check | Known gate context: `drift_detected=false` | PASS |
| Layout feature-name consistency | `rg "layout_preferences|layout_settings"` across sync/data-store files | Found `layout_preferences` in `ProfileSettingsSyncService` and tests; found actual `layout_settings` in `LayoutPreferenceDataStore` | FAIL |
| Runtime Supabase behavior | Not run | Requires live auth/session and remote Supabase | SKIP |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| SYNC-01 | 04-01, 04-03 | Profile metadata syncs to Supabase | SATISFIED | Metadata push/pull RPCs exist, include avatar/PIN state, and startup pulls metadata before account snapshot sync. |
| SYNC-02 | 04-02, 04-03, 04-04 | Per-profile settings sync via independent blob push/pull, not v7 contract | BLOCKED | v8 blob RPCs, full-snapshot import, and pull-before-observe exist, but actual layout/catalog-order settings use `layout_settings`, while v8 sync watches `layout_preferences`; v7 still observes/exports/applies layout/catalog-order paths. |
| SYNC-03 | 04-03 | Profile deletion removes all DataStore files, SharedPreferences, and Supabase remote data | SATISFIED | `ProfileManager` local cleanup and remote cleanup retry are implemented. |
| SYNC-04 | 04-01 | Snapshot stores are classified and scoped per-profile where applicable | SATISFIED | 7 SP-backed snapshot/outbox stores use per-profile names; profile 1 bare naming is preserved. |

No orphaned Phase 4 requirement IDs were found. `.planning/REQUIREMENTS.md` maps SYNC-01 through SYNC-04 to Phase 4, and all four IDs appear in Phase 4 plan frontmatter.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|---|---:|---|---|---|
| `app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt` | 73 | Wrong feature name: `layout_preferences` | Blocker | v8 settings blob sync misses the real `layout_settings` DataStore. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` | 236 | v7 still observes layout catalog-order flows | Blocker | Non-primary profile layout/catalog-order changes can still enter the shared v7 account sync path. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` | 559 | v7 payload still exports layout catalog-order values | Blocker | Shared account payload can be built from the active profile's layout settings. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` | 580 | v7 apply path still writes layout catalog-order values | Blocker | Shared remote layout/catalog-order values can overwrite active profile layout settings. |
| `app/src/test/java/com/nexio/tv/core/sync/ProfileSettingsSyncServiceTest.kt` | 160 | Test asserts `layout_preferences` | Warning | Regression coverage preserves the wrong feature-name contract. |
| `app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt` | 35 | Reads `profileManager.profiles.value` | Warning | Immediate metadata sync after local profile edits can miss fresh DataStore writes if the StateFlow lags. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` | 220 | Profile-switch suppression lacks local clear path except account pull | Warning | Advisory review debt for shared account sync; not the primary Phase 4 profile-settings blocker. |
| `app/src/test/java/com/nexio/tv/core/sync/ProfileSyncServiceTest.kt` | 7 | Ignored placeholder test class | Info | Profile metadata RPC behavior is unprotected by executable tests. |

### Human Verification Required

Not gating this report because automated verification found a blocking SYNC-02 gap. The known device UAT limitation remains: non-primary profile deletion cannot be fully validated on-device until the app exposes a user-accessible way to add/select non-primary profiles.

### Gaps Summary

Plan 04-04 closed the previous verifier gap: settings blob pulls now normalize remote blobs, clear feature stores before import, treat missing features as empty snapshots, and hydrate selected profiles before observer-driven pushes.

Phase 4 still does not achieve SYNC-02. The implementation syncs `layout_preferences`, but the real per-profile layout/catalog-order DataStore is named `layout_settings`. At the same time, `AccountSettingsSyncService` still watches, exports, and applies layout catalog-order values through the shared v7 account sync path. This directly conflicts with the roadmap contract that per-profile settings use independent blob RPCs so Profile 2 changes do not overwrite Profile 1 data.

No later roadmap phase clearly owns this Android-side correction. Phase 5 depends on Phase 4 and plans to use Phase 4 blob RPCs, so this remains an actionable Phase 4 gap rather than a deferred item.

---

_Verified: 2026-04-14T16:59:48Z_
_Verifier: Claude (gsd-verifier)_
