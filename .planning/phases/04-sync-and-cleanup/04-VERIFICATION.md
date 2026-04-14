---
phase: 04-sync-and-cleanup
verified: 2026-04-14T17:42:22Z
status: gaps_found
score: 15/16 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: 20/23
  gaps_closed:
    - "ProfileSettingsSyncService now uses the real LayoutPreferenceDataStore feature name layout_settings, not layout_preferences."
    - "ProfileSettingsSyncServiceTest now asserts layout_settings, rejects layout_preferences, and verifies imports write to get(2, \"layout_settings\") while leaving get(2, \"layout_preferences\") empty."
    - "AccountSettingsSyncService active v7 account sync no longer observes, exports, or applies layout/catalog-order values."
  gaps_remaining:
    - "Current .planning/REQUIREMENTS.md does not define SYNC-01, SYNC-02, SYNC-03, or SYNC-04, so Phase 04 plan requirement IDs cannot be cross-referenced against the canonical requirements file."
  regressions: []
gaps:
  - truth: "Phase 04 requirement IDs SYNC-01, SYNC-02, SYNC-03, and SYNC-04 are defined and traceable in .planning/REQUIREMENTS.md"
    status: failed
    reason: "The current .planning/REQUIREMENTS.md is for the v1.1 TVDB milestone and contains no SYNC-* requirement definitions or Phase 04 traceability rows."
    artifacts:
      - path: ".planning/REQUIREMENTS.md"
        issue: "Missing SYNC-01 through SYNC-04 definitions and traceability entries."
      - path: ".planning/ROADMAP.md"
        issue: "Current roadmap starts at Phase 6 and no longer contains Phase 04 success criteria for this verification."
    missing:
      - "Restore or archive-link the Phase 04 SYNC requirement definitions so plan frontmatter requirement IDs can be verified against .planning/REQUIREMENTS.md."
      - "If the roadmap intentionally moved to a new milestone, provide a phase-local requirements mapping for completed Phase 04 verification."
traceability:
  phase_local_requirements: ".planning/phases/04-sync-and-cleanup/04-REQUIREMENTS-TRACE.md"
  root_requirements_milestone: "v1.1 TVDB First-Class TV Metadata"
  root_roadmap_starts_at_phase: 6
  sync_ids_restored_locally: [SYNC-01, SYNC-02, SYNC-03, SYNC-04]
---

# Phase 4: Sync and Cleanup Verification Report

**Phase Goal:** Profile metadata and per-profile settings sync to Supabase, and deleting a profile leaves no orphaned data anywhere on-device or in the cloud
**Verified:** 2026-04-14T17:42:22Z
**Status:** gaps_found
**Re-verification:** Yes - after gap closure plan 04-05

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|---|---|---|
| 1 | Profile metadata syncs to Supabase and is restored on startup/new device | VERIFIED | `ProfileSyncService.pushToRemote()` builds profile metadata with `profile_index`, `name`, `avatar_color_hex`, `uses_primary_addons`, `avatar_id`, and `pin_enabled`, then calls `sync_push_profiles`. `pullFromRemote()` calls `sync_pull_profiles`, maps remote rows to `UserProfile`, and calls `replaceAllProfiles()` for non-empty pulls. `StartupSyncService.pullRemoteProfileState()` calls `profileSyncService.pullFromRemote()` before account snapshot sync. |
| 2 | Per-profile settings push and pull via independent blob RPCs, not the shared v7 account contract | VERIFIED | `ProfileSettingsSyncService` uses `sync_push_profile_settings_blob` and `sync_pull_profile_settings_blob` with `p_profile_id`; `syncedFeatures` now includes `layout_settings` at line 73. `AccountSettingsSyncService` active v7 paths use `emptyFlow()` for layout observers, `emptyList()` for layout payload fields, and no longer apply `settings.catalogs.home.*` in `applySharedAccountConfigSyncSettings()`. |
| 3 | The v8 per-profile settings blob reads, observes, exports, imports, and signs the real `layout_settings` feature | VERIFIED | `ProfileSettingsSyncService.syncedFeatures` lists `layout_settings`, `observeProfileSettings()` and `exportSettingsBlob()` both call `profileDataStoreFactory.get(profileId, feature)`, `importSettingsBlob()` clears and writes that same feature, and the test writes `get(2, "layout_settings")` while proving `layout_preferences` remains empty. |
| 4 | Full-snapshot settings imports clear absent keys and missing synced feature blobs | VERIFIED | `normalizeSettingsBlob()` inserts an empty object for every synced feature, and `importSettingsBlob()` calls `preferences.clear()` before applying each feature blob. Tests cover absent local keys and a missing feature blob. |
| 5 | Profile switch hydration runs before observer-driven settings pushes | VERIFIED | `startObserving()` uses `distinctUntilChanged().flatMapLatest`, calls `pullBlobForProfile(profileId)` before `observeProfileSettings(profileId)`, skips observation when hydration fails, and debounces observed changes for 2000 ms before `pushBlobForProfile(profileId)`. |
| 6 | Echo pushes after a settings pull are suppressed | VERIFIED | `pullBlobForProfile()` sets `applyingRemoteBlob`, imports the normalized blob, records `skipNextPushSignature`, and `pushBlobForProfile()` returns early when the exported signature matches. |
| 7 | Settings push and pull are serialized | VERIFIED | Both `pushBlobForProfile()` and `pullBlobForProfile()` execute inside `syncMutex.withLock`. |
| 8 | AccountSettingsSyncService v7 no longer observes layout/catalog-order DataStore flows | VERIFIED | `observeLocalChanges()` passes `emptyFlow()` for `heroCatalogSelections`, `homeCatalogOrderKeys`, and `disabledHomeCatalogKeys`. |
| 9 | AccountSettingsSyncService v7 no longer exports layout/catalog-order payload values from the active profile | VERIFIED | `buildLocalPayload()` sets `heroCatalogKeys`, `homeCatalogOrderKeys`, and `disabledHomeCatalogKeys` to `emptyList()` and no longer reads the `LayoutPreferenceDataStore` flows for these payload values. |
| 10 | AccountSettingsSyncService v7 no longer applies shared remote layout/catalog-order values into LayoutPreferenceDataStore | VERIFIED | The active `applySharedAccountConfigSyncSettings(AccountConfigSyncPayload)` contains only a v8 ownership comment for layout/catalog-order values and does not call `setHeroCatalogKeys`, `setHomeCatalogOrderKeys`, or `setDisabledHomeCatalogKeys`. The old private `applyRemoteSettings(AccountSettingsPayload)` still contains legacy writes but has no call sites. |
| 11 | Deleting a profile removes DataStore files, SharedPreferences files, and Supabase remote data | VERIFIED | `ProfileManager.deleteProfile()` rejects profile 1, validates the target via the latest DataStore profile list, calls `sync_delete_profile`, clears `ProfileDataStoreFactory` state, deletes `_p{id}.preferences_pb` files, and clears/deletes all seven per-profile SharedPreferences XML files. Remote failures are stored in `pending_remote_cleanup`. |
| 12 | Failed remote cleanup is retried on next app start | VERIFIED | `StartupSyncService.retryPendingRemoteCleanup()` reads `pending_remote_cleanup`, retries `sync_delete_profile`, removes successful IDs, and persists the remaining bounded set. |
| 13 | Snapshot stores are scoped per active profile at call time | VERIFIED | The seven migrated SharedPreferences-backed stores import `profilePrefsName()` and resolve names from `profileManager.activeProfileId.value` or injected active-profile lookup at read/write/clear sites. `profilePrefsName(baseName, 1)` preserves the bare name, while non-primary profiles use `_p{id}`. |
| 14 | Sync Now pushes profile metadata and the active settings blob with user feedback | VERIFIED | `SettingsViewModel.triggerSyncNow()` calls `profileSyncService.pushToRemote()` and `profileSettingsSyncService.pushBlobForProfile(activeId)`; `SettingsScreen` wires the Sync Now action and status display. |
| 15 | Delete confirmation uses `NexioDialog` with safe and destructive actions | VERIFIED | `SettingsScreen.DeleteProfileDialog()` uses `NexioDialog`, offers a safe "Keep Profile" action, and exposes a destructive "Delete Profile" action. |
| 16 | Phase 04 requirement IDs are defined and traceable in `.planning/REQUIREMENTS.md` | FAILED | All Phase 04 plans declare SYNC-01 through SYNC-04, but the current `.planning/REQUIREMENTS.md` contains only TVDB milestone requirements (`PREF-*`, `AIR-*`, `META-*`, `UX-*`, `CACHE-*`) and no SYNC definitions. Current `.planning/ROADMAP.md` also starts at Phase 6. |

**Score:** 15/16 observable truths verified. Code-level Phase 04 goal checks pass; the remaining gap is requirements traceability against the current planning files.

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt` | Profile metadata push/pull to Supabase | VERIFIED | Exists, substantive, uses `sync_push_profiles` and `sync_pull_profiles`, maps avatar/PIN fields, and is wired into startup and Sync Now. Advisory warning remains: push reads `profileManager.profiles.value`, which can lag immediate DataStore writes. |
| `app/src/main/java/com/nexio/tv/core/sync/ProfilePrefsName.kt` | Per-profile SharedPreferences naming helper | VERIFIED | Returns bare base name for profile 1 and `_p{id}` suffix for non-primary profiles. |
| `app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt` | `SupabaseProfile` avatar/PIN metadata | VERIFIED | Contains `@SerialName("avatar_id") val avatarId` and `@SerialName("pin_enabled") val pinEnabled`. |
| `app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt` | Per-profile settings blob sync | VERIFIED | Uses dedicated v8 RPCs, `layout_settings`, full-snapshot import, echo suppression, mutex serialization, and pull-before-observe hydration. |
| `app/src/test/java/com/nexio/tv/core/sync/ProfileSettingsSyncServiceTest.kt` | Regression tests for v8 settings behavior | VERIFIED | Asserts exact synced feature list with `layout_settings`, asserts `layout_preferences` is excluded, and verifies imports write to the real layout store while leaving the unused store empty. Test execution remains blocked by unrelated source-set compile drift per known gate context. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` | Shared v7 sync neutralized for per-profile layout/catalog-order paths | VERIFIED | Active observer, payload, and apply path no longer move layout/catalog-order values through v7. |
| `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt` | Actual profile-scoped layout settings store | VERIFIED | Uses `ProfileDataStoreFactory.get(profileId, "layout_settings")`. |
| `app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt` | Startup metadata/settings pull and cleanup retry | VERIFIED | Pulls profile metadata, pulls active settings blob, starts the v8 observer, and retries pending remote cleanup. Advisory warning remains for forced resync drain during an active successful pull. |
| `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt` | Profile deletion cleanup | VERIFIED | Deletes non-primary local DataStore and SP files and attempts/persists remote cleanup. |
| `.planning/REQUIREMENTS.md` | SYNC requirement definitions and traceability | MISSING | Current file is for the TVDB milestone and does not define SYNC-01 through SYNC-04. |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `ProfileSyncService` | Supabase Postgrest | `sync_push_profiles` / `sync_pull_profiles` | WIRED | Both RPCs are present with response handling. |
| `ProfileSyncService.pullFromRemote()` | `ProfileDataStore.replaceAllProfiles()` | decoded remote profile list | WIRED | Non-empty remote profile list replaces local profiles. |
| Snapshot stores | `ProfileManager.activeProfileId` | `profilePrefsName()` at read/write/clear time | WIRED | All seven migrated SP stores resolve profile-specific names dynamically. |
| `ProfileSettingsSyncService` | Supabase Postgrest | `sync_push_profile_settings_blob` / `sync_pull_profile_settings_blob` | WIRED | Dedicated v8 RPCs use `p_profile_id` and `p_platform`. |
| `ProfileSettingsSyncService.syncedFeatures` | `LayoutPreferenceDataStore` | matching `ProfileDataStoreFactory` feature name | WIRED | Production v8 sync and `LayoutPreferenceDataStore` both use `layout_settings`; `layout_preferences` appears only in negative tests. |
| `ProfileSettingsSyncService.pullBlobForProfile()` | `importSettingsBlob()` | normalized blob and signature | WIRED | `normalizeSettingsBlob(rawBlob)` is used for import and `skipNextPushSignature`. |
| `ProfileSettingsSyncService.startObserving()` | `pullBlobForProfile()` | profile hydration gate before observer | WIRED | Pull runs before observation and failed hydration skips pushes. |
| `AccountSettingsSyncService` | v8 ownership boundary | v7 layout fields empty/ignored | WIRED | Active v7 observer/payload/apply paths no longer read or write layout/catalog-order values. |
| `StartupSyncService` | metadata/settings pull | startup pull sequence | WIRED | Calls `profileSyncService.pullFromRemote()` and `profileSettingsSyncService.pullBlobForProfile(activeId)`. |
| `ProfileManager.deleteProfile()` | local/remote cleanup | DataStore, SP, and Supabase cleanup | WIRED | Cleanup code and retry persistence are present. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|---|---|---|---|---|
| `ProfileSyncService.kt` | `profiles` | `profileManager.profiles.value` | Yes, with stale-read advisory | FLOWING - payload is real, but review warning WR-02 correctly notes direct DataStore reads would avoid StateFlow lag after immediate profile edits. |
| `ProfileSettingsSyncService.kt` | `blob` | `ProfileDataStoreFactory.get(profileId, feature).data.first()` | Yes | FLOWING - features include `layout_settings`, and imports/exports operate on the same per-profile DataStore feature list. |
| `LayoutPreferenceDataStore.kt` | layout/catalog-order preferences | `ProfileDataStoreFactory.get(profileId, "layout_settings")` | Yes | FLOWING - now connected to v8 profile settings sync by matching feature name. |
| `AccountSettingsSyncService.kt` | layout/catalog-order preferences | no active v7 data source | N/A | BLOCKED FROM V7 - the intended state after 04-05; values no longer flow through shared account sync. |
| `StartupSyncService.kt` | active profile settings | `profileManager.activeProfileId.value` | Yes | FLOWING - pulls active settings blob and starts v8 observer. |
| `ProfileManager.kt` | profile cleanup target | `deleteProfile(id)` and profile list | Yes | FLOWING - validates non-primary profile and deletes local/remote scoped data. |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| Production Android build | `./gradlew assembleArm64Debug` | Known gate context: passed after 04-05 | PASS |
| ProfileSettingsSyncService targeted unit test | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.ProfileSettingsSyncServiceTest"` | Known gate context: blocked by unrelated unit-test source-set compile drift; `ProfileSettingsSyncServiceTest.kt` was not reported as a compile failure source | SKIP |
| Schema drift | schema drift check | Known gate context: `drift_detected=false` | PASS |
| v8 layout feature routing | `rg "layout_preferences|layout_settings" app/src/main/java app/src/test/java` | Production code uses `layout_settings`; `layout_preferences` appears only in negative test coverage | PASS |
| active v7 layout routing | `rg "layoutPreferenceDataStore\\.(heroCatalogSelections|homeCatalogOrderKeys|disabledHomeCatalogKeys|setHeroCatalogKeys|setHomeCatalogOrderKeys|setDisabledHomeCatalogKeys)" AccountSettingsSyncService.kt` | No active observer/payload/apply hits; only dead legacy `applyRemoteSettings(AccountSettingsPayload)` contains old writes and has no call sites | PASS |
| requirements traceability | `rg "SYNC-01|SYNC-02|SYNC-03|SYNC-04" .planning/REQUIREMENTS.md .planning/ROADMAP.md` | No matches in current files | FAIL |

### Requirements Coverage

### Phase-Local Requirement Traceability Source

The active root `.planning/REQUIREMENTS.md` and `.planning/ROADMAP.md` now describe v1.1 TVDB and do not define Phase 04 `SYNC-*` IDs.

`.planning/phases/04-sync-and-cleanup/04-REQUIREMENTS-TRACE.md` is the verifier-readable historical trace source for `SYNC-01` through `SYNC-04`.

Future verification of Phase 04 should use that trace artifact instead of restoring old Phase 04 requirements into the root v1.1 files.

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| SYNC-01 | 04-01, 04-03 | Profile metadata syncs to Supabase | SATISFIED IN CODE, UNTRACEABLE IN REQUIREMENTS | Metadata push/pull and startup pull are implemented, but `.planning/REQUIREMENTS.md` no longer defines SYNC-01. |
| SYNC-02 | 04-02, 04-03, 04-04, 04-05 | Per-profile settings sync via independent blob push/pull, not v7 contract | SATISFIED IN CODE, UNTRACEABLE IN REQUIREMENTS | 04-05 closed the layout-settings and v7-routing gap, but `.planning/REQUIREMENTS.md` no longer defines SYNC-02. |
| SYNC-03 | 04-03 | Profile deletion removes on-device and Supabase remote data | SATISFIED IN CODE, UNTRACEABLE IN REQUIREMENTS | Local and remote cleanup/retry are implemented, but `.planning/REQUIREMENTS.md` no longer defines SYNC-03. |
| SYNC-04 | 04-01 | Snapshot stores are scoped per-profile where applicable | SATISFIED IN CODE, UNTRACEABLE IN REQUIREMENTS | Seven SP stores use dynamic per-profile names, but `.planning/REQUIREMENTS.md` no longer defines SYNC-04. |

All four requested Phase 04 IDs are declared in Phase 04 plan frontmatter and have code evidence. None can be cross-referenced to the current canonical `.planning/REQUIREMENTS.md`.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|---|---:|---|---|---|
| `.planning/REQUIREMENTS.md` | 1 | Phase 04 SYNC IDs absent from canonical requirements | Blocker | The verifier cannot account for SYNC-01 through SYNC-04 against the requested requirements source. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` | 218 | Profile-switch account push suppression can remain uncleared | Warning | Advisory WR-01 from latest review; not blocking Phase 04 code goal after per-profile layout/player/catalog ownership moved to v8. |
| `app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt` | 35 | Profile metadata push reads cached `StateFlow` | Warning | Advisory WR-02; immediate sync after a local profile mutation can push stale metadata. |
| `app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt` | 109 | Pending forced startup resync can be dropped after active pull succeeds | Warning | Advisory WR-03; not part of the closed SYNC-02 gap. |
| `app/src/test/java/com/nexio/tv/core/sync/ProfileSyncServiceTest.kt` | 7 | Ignored placeholder test class | Info | Profile metadata behavior remains weakly protected by executable unit tests. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` | 638 | Dead legacy `applyRemoteSettings(AccountSettingsPayload)` path | Info | The old method still contains v7 layout writes but has no call sites; latest review marks cleanup as informational. |

### Human Verification Required

No gating human verification item is added for this report. Known device UAT for non-primary profile deletion remains blocked because the app lacks a user-accessible profile creation/selection path; prior verification treated that as non-gating when automated goal checks pass.

### Gaps Summary

Plan 04-05 closes the previous code gap. The v8 profile settings blob now uses the real `layout_settings` DataStore feature, regression coverage rejects the unused `layout_preferences` path, and the active shared v7 account sync path no longer observes, exports, or applies layout/catalog-order values.

The remaining blocker is planning traceability, not production code behavior: the current `.planning/REQUIREMENTS.md` and `.planning/ROADMAP.md` have moved to the TVDB milestone and no longer contain Phase 04/SYNC requirement definitions. Because the verification request explicitly requires cross-referencing SYNC-01 through SYNC-04 against `.planning/REQUIREMENTS.md`, those IDs cannot be fully accounted for until the completed Phase 04 requirement mapping is restored or linked.

---

_Verified: 2026-04-14T17:42:22Z_
_Verifier: Claude (gsd-verifier)_
