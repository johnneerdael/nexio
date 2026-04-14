---
phase: 05-nexio-web-integration
verified: 2026-04-14T20:32:33Z
status: gaps_found
score: 2/5 must-haves verified
overrides_applied: 0
gaps:
  - truth: "A non-default profile can reorder its catalog list from nexio-web with the order persisting on-device."
    status: failed
    reason: "The web path saves the PortalSettings JSON shape, but Android profile settings sync imports only the Phase 4 encoded per-feature blob shape. Catalog order and disabled catalog keys are not transformed into layout_settings preferences, so the TV pull cannot apply the web change."
    artifacts:
      - path: "nexio-web/server/api/account/profiles/settings.post.ts"
        issue: "Posts JSON.stringify(body.settings) to sync_push_profile_settings_blob; body.settings is PortalSettings with top-level catalogs/formatter."
      - path: "nexio-web/composables/useProfileStore.ts"
        issue: "saveProfileSettings passes sanitized PortalSettings to the profile settings route."
      - path: "app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt"
        issue: "pullBlobForProfile normalizes only trakt_settings, simkl_settings, player_settings, layout_settings, and theme_settings, then imports encoded preference values."
    missing:
      - "Add a web-to-Android v8 blob adapter for catalog settings, or update Android profile settings pull to decode the web PortalSettings shape and write LayoutPreferenceDataStore keys."
      - "Add regression coverage proving a web-saved catalogs.home.homeCatalogOrderKeys value is imported into the TV layout_settings store."
  - truth: "A non-default profile can adjust formatter settings from nexio-web with changes applying on next sync."
    status: failed
    reason: "Same settings blob shape mismatch as catalogs. Web saves formatter.enabled, formatter.selectedTemplateId, and formatter.customTemplate inside PortalSettings, while Android only imports encoded player_settings preference entries."
    artifacts:
      - path: "nexio-web/server/api/account/profiles/settings.post.ts"
        issue: "Sends the web PortalSettings object directly as p_settings_json."
      - path: "nexio-web/components/portal/ProfileFormatterTab.vue"
        issue: "Correctly saves formatter updates through useProfileStore, but no adapter converts those updates to Android player_settings preferences."
      - path: "app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt"
        issue: "Does not decode PortalSettings.formatter into PlayerSettingsDataStore synced formatter keys."
    missing:
      - "Add formatter mapping from web PortalSettings to Android player_settings encoded preferences, or add Android decoding for PortalSettings.formatter."
      - "Add regression coverage proving a web-saved formatter.selectedTemplateId reaches the TV formatter selection state after pullBlobForProfile."
  - truth: "Profile photo uploaded via nexio-web is stored in Supabase Storage and displayed as the profile avatar in the TV app."
    status: partial
    reason: "The web upload and Supabase avatar_url update path exists, and Android maps Supabase avatar_url into UserProfile during pull, but ProfileDataStore serialization drops UserProfile.avatarUrl. The profile UI reads profile.avatarUrl from ProfileManager.profiles, so the URL is lost before rendering."
    artifacts:
      - path: "nexio-web/server/api/account/profiles/photo.post.ts"
        issue: "Storage upload and profile_upsert avatar_url update are present."
      - path: "app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt"
        issue: "Maps SupabaseProfile.avatarUrl into UserProfile before replaceAllProfiles."
      - path: "app/src/main/java/com/nexio/tv/data/local/ProfileDataStore.kt"
        issue: "ProfileJson omits avatarUrl in both toDomain and fromDomain, so replaceAllProfiles persists profiles without the web-uploaded avatar URL."
    missing:
      - "Add avatarUrl to ProfileJson serialization/deserialization."
      - "Add a regression test proving replaceAllProfiles preserves avatarUrl and ProfileManager.profiles emits it."
---

# Phase 5: nexio-web Integration Verification Report

**Phase Goal:** The master account holder can manage all profiles from nexio-web, and non-default profiles can self-manage auth, catalogs, and formatter config without touching the TV.
**Verified:** 2026-04-14T20:32:33Z
**Status:** gaps_found
**Re-verification:** No - initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Master account holder can create, rename, and delete profiles from nexio-web with changes reflected on-device after next sync. | VERIFIED | Web profile CRUD exists in `useProfileStore.ts` and routes through `/api/account/profiles/{index,upsert,delete}`. Supabase `profile_upsert` and `profile_delete` are auth.uid-scoped, reject invalid indices, and `profile_delete` refuses profile 1. Android startup sync calls `ProfileSyncService.pullFromRemote()`, maps remote rows to `UserProfile`, and `replaceAllProfiles()` removes missing/deleted profiles. |
| 2 | A non-default profile can link and unlink its Trakt and Simkl accounts from nexio-web without requiring access to the TV. | VERIFIED | `AuthPanel.vue` profile mode calls profile-scoped Trakt/Simkl start/disconnect actions. OAuth authorize/callback routes sign profileIndex into state, exchange browser OAuth codes, and write `profile_auth_tokens` through service-role RPCs. Trakt unlink calls revoke before tombstone write; Simkl unlink uses the accepted token-delete/tombstone-only decision. Android `ProfileWebSyncService` pulls rows and applies linked/unlinked state to per-profile auth stores. |
| 3 | A non-default profile can reorder its catalog list from nexio-web with the order persisting on-device. | FAILED | Web UI and route exist, but the data shape saved by web is not the shape Android imports. See gap 1. |
| 4 | A non-default profile can adjust formatter settings from nexio-web with changes applying on next sync. | FAILED | Web UI and route exist, but formatter settings are saved as PortalSettings and are not decoded into Android `player_settings`. See gap 2. |
| 5 | Profile photo uploaded via nexio-web is stored in Supabase Storage and displayed as the profile avatar in the TV app. | FAILED | Web upload/storage/update path exists and TV UI passes `profile.avatarUrl`, but Android profile persistence drops `avatarUrl` before UI rendering. See gap 3. |

**Score:** 2/5 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `supabase/migrations/20260414000000_phase5_profile_auth_tokens.sql` | Per-profile auth tokens and tombstones | VERIFIED | Defines `profile_auth_tokens`, RLS policies, `service_set_profile_auth_token`, `service_delete_profile_auth_tokens`, and service-role grants. |
| `supabase/migrations/20260414000100_phase5_profile_avatar_storage.sql` | Profile table, avatar bucket, profile CRUD RPCs | VERIFIED | Defines `profiles`, `profile-avatars`, Storage policies, `profile_upsert`, `profile_delete`, and `profile_auth_status`. |
| `nexio-web/composables/useProfileStore.ts` | Web profile CRUD/auth/settings/photo state | PARTIAL | CRUD/auth/photo state is wired. Settings save path sends PortalSettings without Android v8 blob conversion. |
| `nexio-web/components/portal/ProfileDashboard.vue` | Profile grid and create/delete affordances | VERIFIED | Fetches profiles, creates next available profile, opens detail view, and wires delete modal. |
| `nexio-web/components/portal/ProfileDetailShell.vue` | Profile detail tabs | VERIFIED | Wires `AuthPanel`, `ProfileCatalogsTab`, `ProfileFormatterTab`, and `ProfileEditorSection`. |
| `nexio-web/server/api/account/profiles/photo.post.ts` | Avatar upload and resize | VERIFIED | Validates profileIndex, MIME, size, sharp decoding; resizes to 256x256 JPEG; uploads to Storage; writes avatar_url through `profile_upsert`. |
| `app/src/main/java/com/nexio/tv/core/sync/ProfileWebSyncService.kt` | Web auth token pull | VERIFIED | Pulls `profile_auth_tokens`, decodes `JsonObject` payloads, applies Trakt/Simkl links and unlink tombstones to profile-index auth stores. |
| `app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt` | Profile settings sync | PARTIAL | Sync is wired, but imports only encoded per-feature blobs; it cannot consume the web PortalSettings blob currently saved by nexio-web. |
| `app/src/main/java/com/nexio/tv/data/local/ProfileDataStore.kt` | Local profile persistence | PARTIAL | Persists profile name/color/PIN/avatarId but omits `avatarUrl`, breaking TV avatar display. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `ProfileDashboard.vue` | `useProfileStore.upsertProfile/deleteProfile` | create/manage/delete handlers | WIRED | Dashboard create and delete actions call store methods. |
| `useProfileStore.ts` | profile CRUD API routes | authenticated `$fetch` with Authorization header | WIRED | `fetchProfiles`, `upsertProfile`, and `deleteProfile` call `/api/account/profiles/*`. |
| profile CRUD API routes | Supabase RPC/table | `profile_upsert`, `profile_delete`, `profiles` select | WIRED | Routes call auth-scoped Supabase paths after `supabaseUser(event)`. |
| `ProfileDetailShell.vue` | profile auth/catalog/formatter tabs | component imports and props | WIRED | Auth, Catalogs, and Formatter tabs render concrete components, not placeholders. |
| profile auth routes | `profile_auth_tokens` | service-role RPCs | WIRED | Trakt/Simkl callback and disconnect routes call token set/delete RPCs. |
| `StartupSyncService.kt` | `ProfileWebSyncService.kt` | startup and profile switch calls | WIRED | Startup sync and `profileSwitched` collector call `syncActiveProfile`. |
| profile settings web route | Android profile settings sync | shared RPC names | HOLLOW | Both sides call `sync_push_profile_settings_blob` / `sync_pull_profile_settings_blob`, but payload schemas do not match. |
| `photo.post.ts` | Supabase Storage/profile row | Storage REST upload plus `profile_upsert` | WIRED | Stores `profile-avatars/{userId}/{profileIndex}.jpg` and writes cache-busted public URL. |
| `ProfileSyncService.kt` | TV avatar UI | `UserProfile.avatarUrl` | HOLLOW | Mapping and UI props exist, but `ProfileDataStore.ProfileJson` drops the URL between sync and render. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| `ProfileDashboard.vue` | `state.profiles` | `/api/account/profiles/` -> Supabase `profiles` table | Yes | FLOWING |
| `AuthPanel.vue` profile mode | `profileAuthStatus` | `/api/account/profiles/auth-status` -> `profile_auth_status` RPC | Yes | FLOWING |
| `ProfileWebSyncService.kt` | `remoteTokens` | Postgrest `profile_auth_tokens` table query | Yes | FLOWING |
| `ProfileCatalogsTab.vue` | `profileSettings.settings.catalogs` | `/api/account/profiles/settings` | Partially | HOLLOW - web settings shape is not imported by Android v8 blob sync. |
| `ProfileFormatterTab.vue` | `profileSettings.settings.formatter` | `/api/account/profiles/settings` | Partially | HOLLOW - web settings shape is not imported by Android v8 blob sync. |
| TV avatar surfaces | `profile.avatarUrl` | Supabase `avatar_url` -> `ProfileSyncService` -> `ProfileDataStore` -> `ProfileManager.profiles` | No | HOLLOW - `ProfileDataStore.ProfileJson` omits avatarUrl. |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Web/Android profile route and model wiring | `rg -n "profile_upsert|profile_delete|avatarUrl|syncActiveProfile|sync_push_profile_settings_blob|sync_pull_profile_settings_blob" ...` | Found all expected route, RPC, and model references | PASS |
| Avatar URL persistence through Android DataStore | `rg -n "ProfileJson\\(|avatarUrl|avatar_url" app/src/main/java/com/nexio/tv/data/local/ProfileDataStore.kt app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt` | `ProfileSyncService` maps avatarUrl, but `ProfileDataStore.ProfileJson` has no avatarUrl field | FAIL |
| Settings payload compatibility | Static trace from `settings.post.ts` to `ProfileSettingsSyncService.kt` | Web posts PortalSettings; Android imports only encoded `syncedFeatures` blobs | FAIL |
| Full builds/tests | Not rerun | Phase summaries record `nexio-web npm run build` and `assembleArm64Debug` passing; targeted Android unit tests are blocked by known unrelated source-set compile drift | SKIPPED |

### Requirements Coverage

The current `.planning/REQUIREMENTS.md` has already been replaced by the v1.1 TVDB milestone and no longer contains WEB-01 through WEB-05. Coverage below uses the Phase 5 plan frontmatter and user-provided success criteria.

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| WEB-01 | 05-01, 05-02, 05-06 | Profile CRUD from web and TV sync | SATISFIED | Web CRUD routes/UI exist; Android startup profile pull and profile replacement exist. Live Supabase RPC behavior still needs UAT. |
| WEB-02 | 05-01, 05-03, 05-06 | Per-profile Trakt/Simkl auth link/unlink | SATISFIED | Profile OAuth routes, token table/tombstones, profile AuthPanel mode, and Android token pull are wired. |
| WEB-03 | 05-04, 05-06 | Per-profile catalog ordering | BLOCKED | Web UI persists PortalSettings; Android expects encoded `layout_settings` blob. |
| WEB-04 | 05-04, 05-06 | Per-profile formatter config | BLOCKED | Web UI persists PortalSettings; Android expects encoded `player_settings` blob. |
| WEB-05 | 05-01, 05-05, 05-06 | Profile photo upload and TV avatar display | BLOCKED | Storage/upload path exists; Android drops avatarUrl in profile persistence. |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `app/src/test/java/com/nexio/tv/profile/ProfileAvatarTest.kt` | 16 | Contract-string scaffold only | Warning | Test documents `avatarUrl` but does not exercise `ProfileDataStore.replaceAllProfiles`, so the avatarUrl persistence gap was not caught. |
| `app/src/test/java/com/nexio/tv/sync/ProfileCatalogSyncTest.kt` | 12 | Contract-string scaffold only | Warning | Test asserts RPC/key names but not the web-to-Android settings blob shape. |
| `app/src/test/java/com/nexio/tv/sync/ProfileFormatterSyncTest.kt` | 12 | Contract-string scaffold only | Warning | Test asserts RPC/key names but not formatter value application after Android import. |

### Human Verification Required

These should be run after the gaps are fixed:

1. **Profile CRUD end-to-end**
   - Test: Sign into nexio-web, create profile 2, rename it, delete it, then launch/sync the TV app.
   - Expected: Profile list on TV reflects create/rename/delete after next startup sync.
   - Why human: Requires live Supabase auth/RPC state and the TV app runtime.

2. **Provider OAuth end-to-end**
   - Test: Link and unlink Trakt and Simkl from profile 2 in nexio-web.
   - Expected: Linked status updates in web, Supabase token rows/tombstones update, and TV profile 2 auth state changes after sync.
   - Why human: Requires live external OAuth providers.

3. **Photo upload end-to-end**
   - Test: Upload a JPEG/PNG/WebP for profile 2 from nexio-web, sync TV, and inspect profile selection/sidebar/settings header.
   - Expected: Resized uploaded image appears on all TV avatar surfaces; removing it returns to color fallback.
   - Why human: Requires live Supabase Storage and visual inspection.

### Gaps Summary

Phase 5 is not goal-complete yet. The web CRUD and profile auth paths are substantively implemented, but two cross-platform data-flow contracts are hollow:

1. Web profile settings save a `PortalSettings` JSON blob while Android profile settings sync imports an encoded per-feature DataStore blob. Catalog and formatter changes therefore do not apply on-device.
2. Web avatar upload persists `profiles.avatar_url`, and Android initially maps it, but `ProfileDataStore.ProfileJson` omits `avatarUrl`, dropping it before UI rendering.

The next fix phase should focus on these two data-shape adapters/persistence gaps, then add regression tests that exercise the actual data flow rather than only asserting contract strings.

---

_Verified: 2026-04-14T20:32:33Z_
_Verifier: Codex (gsd-verifier)_
