---
phase: 05-nexio-web-integration
verified: 2026-04-14T21:36:24Z
status: human_needed
score: 5/5 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: 2/5
  gaps_closed:
    - "Catalog ordering now persists as Android v8 encoded layout_settings in the tv platform row and imports into TV layout preferences."
    - "Formatter settings now persist as Android v8 encoded player_settings in the tv platform row and import into TV formatter preferences."
    - "Profile avatar URLs now survive ProfileDataStore serialization and ProfileManager emission."
  gaps_remaining: []
  regressions: []
human_verification:
  - test: "Profile settings end-to-end catalog reorder"
    expected: "Reordering or hiding catalogs in nexio-web for profile 2 updates the TV app after the next profile settings pull."
    why_human: "Requires live Supabase state plus TV runtime sync/visual confirmation."
  - test: "Profile settings end-to-end formatter change"
    expected: "Changing formatter enabled state, selected template, or custom template in nexio-web updates the TV formatter settings after the next profile settings pull."
    why_human: "Requires live Supabase state plus TV runtime sync/visual confirmation."
  - test: "Profile photo upload end-to-end"
    expected: "Uploading an avatar from nexio-web stores a cache-busted public URL and the TV app displays it instead of the color fallback after sync."
    why_human: "Requires live Supabase Storage and visual inspection of TV avatar surfaces."
  - test: "Profile CRUD end-to-end"
    expected: "Create, rename, and delete profile operations from nexio-web are reflected on TV after startup/profile sync."
    why_human: "Requires live Supabase auth/RPC state and TV runtime."
  - test: "Provider OAuth end-to-end"
    expected: "Trakt and Simkl link/unlink operations from nexio-web update profile-scoped auth status and TV auth state after sync."
    why_human: "Requires live external OAuth providers."
---

# Phase 5: nexio-web Integration Verification Report

**Phase Goal:** The master account holder can manage all profiles from nexio-web, and non-default profiles can self-manage auth, catalogs, and formatter config without touching the TV.
**Verified:** 2026-04-14T21:36:24Z
**Status:** human_needed
**Re-verification:** Yes - after gap closure plans 05-07 and 05-08

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Master account holder can create, rename, and delete profiles from nexio-web with changes reflected on-device after next sync. | VERIFIED | Regression check found web CRUD routes still call `profile_upsert` / `profile_delete`; Android still pulls `sync_pull_profiles` and calls `replaceAllProfiles`. This truth was previously verified and no gap-closure files changed the CRUD path. |
| 2 | A non-default profile can link and unlink its Trakt and Simkl accounts from nexio-web without requiring access to the TV. | VERIFIED | Regression check found profile OAuth routes still write `profile_auth_tokens` through service-role RPCs and Android `ProfileWebSyncService` still pulls the token table. This truth was previously verified and no gap-closure files changed the auth path. |
| 3 | A non-default profile can reorder its catalog list from nexio-web with the order persisting on-device. | VERIFIED | Plan 05-07 added `profile-settings-blob.ts`; POST now pulls the existing `p_platform: 'tv'` blob, merges `catalogs.home.homeCatalogOrderKeys` into `layout_settings.home_catalog_order_keys`, then pushes the merged Android v8 blob. Android `importSettingsBlob` applies encoded keys into the real `layout_settings` DataStore. |
| 4 | A non-default profile can adjust formatter settings from nexio-web with changes applying on next sync. | VERIFIED | Plan 05-07 maps formatter enabled, selected template, and custom template fields into `player_settings.synced_formatter_*` encoded keys. Android regression fixtures assert those keys import into the real `player_settings` DataStore. |
| 5 | Profile photo uploaded via nexio-web is stored in Supabase Storage and displayed as the profile avatar in the TV app. | VERIFIED | Plan 05-08 added `avatar_url` to `ProfileDataStore.ProfileJson` and maps it both directions. `ProfileSyncService` maps Supabase `avatar_url` into `UserProfile.avatarUrl`, `ProfileManager.profiles` now has a regression for emitted URLs, and TV avatar surfaces pass `profile.avatarUrl` into `ProfileAvatarCircle`. |

**Score:** 5/5 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `nexio-web/server/utils/profile-settings-blob.ts` | PortalSettings <-> Android v8 encoded blob adapter | VERIFIED | Exports `portalSettingsFromProfileSettingsBlob` and `mergePortalSettingsIntoProfileSettingsBlob`; encodes catalog and formatter keys while preserving unrelated feature roots. |
| `nexio-web/server/api/account/profiles/settings.get.ts` | Decode stored v8 blob to PortalSettings | VERIFIED | Calls `sync_pull_profile_settings_blob` with `p_platform: 'tv'` and returns `portalSettingsFromProfileSettingsBlob(...)` for the current UI contract. |
| `nexio-web/server/api/account/profiles/settings.post.ts` | Merge PortalSettings into stored Android v8 blob | VERIFIED | Pulls existing `tv` blob before push, merges with `mergePortalSettingsIntoProfileSettingsBlob`, and sends the merged blob to `sync_push_profile_settings_blob`. |
| `nexio-web/tests/profile-settings-blob.test.ts` | Adapter and route regression coverage | VERIFIED | `npx tsx --test tests/profile-settings-blob.test.ts` passed 9/9 tests. |
| `app/src/test/java/com/nexio/tv/core/sync/ProfileSettingsSyncServiceTest.kt` | Android DataStore import regressions | VERIFIED | Contains real `layout_settings` and `player_settings` import fixture assertions for web-produced encoded catalog and formatter blobs. Targeted execution remains blocked by unrelated source-set compile drift. |
| `app/src/main/java/com/nexio/tv/data/local/ProfileDataStore.kt` | Avatar URL serialization | VERIFIED | `ProfileJson` contains nullable `@SerializedName("avatar_url") val avatarUrl`; `toDomain()` and `fromDomain()` map the field. |
| `app/src/test/java/com/nexio/tv/data/local/ProfileDataStoreTest.kt` | Avatar persistence regressions | VERIFIED | Covers round-trip avatar URL persistence and `replaceAllProfiles` preserving cache-busted web avatar URLs. |
| `app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt` | ProfileManager avatar emission regression | VERIFIED | Test-local harness writes through `ProfileDataStoreImpl.replaceAllProfiles` and asserts `manager.profiles` emits profile 2 with the expected `avatarUrl`. |
| `app/src/test/java/com/nexio/tv/profile/ProfileAvatarTest.kt` | WEB-05 persistence-flow coverage | VERIFIED | Now includes a real `replaceAllProfiles` persistence-flow assertion in addition to contract-string checks. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `ProfileCatalogsTab.vue` / `useProfileStore.ts` | `settings.post.ts` | `saveProfileSettings(..., changedPaths)` | WIRED | Catalog reorder/toggle paths pass `catalogs.home.homeCatalogOrderKeys` and `catalogs.home.disabledHomeCatalogKeys`; fallback persist sends all managed paths. |
| `ProfileFormatterTab.vue` / `useProfileStore.ts` | `settings.post.ts` | `updateProfileSetting` changed paths | WIRED | Formatter updates pass changed paths for formatter fields; fallback persist sends all managed paths. |
| `settings.post.ts` | Supabase profile settings blob | pull-merge-push | WIRED | Pulls `sync_pull_profile_settings_blob` before pushing `sync_push_profile_settings_blob`, both with `p_platform: 'tv'`. |
| Stored `layout_settings` blob | TV layout preferences | `pullBlobForProfile` -> `importSettingsBlob` | WIRED | Android import clears and writes the `layout_settings` feature store. Test fixture asserts `home_catalog_order_keys` and `disabled_home_catalog_keys` land in the real DataStore. |
| Stored `player_settings` blob | TV formatter preferences | `pullBlobForProfile` -> `importSettingsBlob` | WIRED | Android import writes generic encoded preference keys. Test fixture asserts `synced_formatter_selected_template_id`, enabled, and custom template values land in the real DataStore. |
| `ProfileSyncService.kt` | `ProfileDataStore.kt` | `replaceAllProfiles` with `UserProfile.avatarUrl` | WIRED | Sync maps `SupabaseProfile.avatarUrl` into `UserProfile`, then `ProfileDataStore` now preserves `avatar_url`. |
| `ProfileDataStore.kt` | `ProfileManager.profiles` | `profilesList` StateFlow | WIRED | `ProfileManagerTest` proves the persisted avatar URL is emitted by `manager.profiles`. |
| `ProfileManager.profiles` | TV avatar surfaces | `profile.avatarUrl` prop | WIRED | `ProfileSelectionScreen`, `ModernSidebarBlurPanel`, and `SettingsScreen` pass `profile.avatarUrl` into `ProfileAvatarCircle`, which loads it via Coil `AsyncImage`. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| `ProfileCatalogsTab.vue` | `settings.catalogs.home.homeCatalogOrderKeys` | GET decodes `layout_settings.home_catalog_order_keys`; POST encodes the changed PortalSettings path back into the stored tv blob | Yes | FLOWING |
| `ProfileFormatterTab.vue` | `settings.formatter.*` | GET decodes `player_settings.synced_formatter_*`; POST encodes changed formatter paths into the stored tv blob | Yes | FLOWING |
| `ProfileSettingsSyncService.kt` | encoded feature blob | `sync_pull_profile_settings_blob(p_platform='tv')` | Yes | FLOWING |
| `ProfileDataStore.kt` | `UserProfile.avatarUrl` | Supabase `profiles.avatar_url` -> `ProfileSyncService` -> `replaceAllProfiles` -> `profilesList` | Yes | FLOWING |
| `ProfileAvatarCircle.kt` | `avatarImageUrl` | `ProfileManager.profiles` consumers pass `profile.avatarUrl` | Yes | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Web profile settings adapter and route regressions | `npx tsx --test tests/profile-settings-blob.test.ts` from `nexio-web/` | 9 tests passed, 0 failed | PASS |
| Android production source compiles after gap closures | `./gradlew compileArm64DebugKotlin -x lint` | BUILD SUCCESSFUL in 2s | PASS |
| Targeted Android unit test execution | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfileDataStoreTest" -x lint` | Fails at `:app:compileArm64DebugUnitTestKotlin` before selected tests run due unrelated stale test-source errors in `HomeCatalogSnapshotStoreTest`, `PlayerSettingsDataStore*`, `SearchHistoryDataStoreTest`, `ThemeDataStoreProfileTest`, and others | BLOCKED |
| Plan 05-07 artifact verification | `gsd-tools verify artifacts 05-07-PLAN.md` | 5/5 artifacts passed | PASS |
| Plan 05-08 artifact/key-link verification | `gsd-tools verify artifacts/key-links 05-08-PLAN.md` | 4/4 artifacts and 3/3 key links passed | PASS |

### Requirements Coverage

The current `.planning/ROADMAP.md` and `.planning/REQUIREMENTS.md` have moved on to the v1.1 TVDB milestone, so Phase 5 WEB requirements are assessed from Phase 5 plan frontmatter and the previous verification report.

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| WEB-01 | 05-01, 05-02, 05-06 | Profile CRUD from web and TV sync | SATISFIED | Previously verified; regression grep confirms web CRUD routes and Android profile pull remain wired. Human live Supabase UAT still required. |
| WEB-02 | 05-01, 05-03, 05-06 | Per-profile Trakt/Simkl auth link/unlink | SATISFIED | Previously verified; regression grep confirms service-role token RPCs and Android token pull remain wired. Human OAuth UAT still required. |
| WEB-03 | 05-04, 05-06, 05-07 | Per-profile catalog ordering | SATISFIED | Gap closed by 05-07 adapter, tv-platform route wiring, web tests, and Android import fixtures. |
| WEB-04 | 05-04, 05-06, 05-07 | Per-profile formatter config | SATISFIED | Gap closed by 05-07 formatter mapping into `player_settings.synced_formatter_*` and Android import fixtures. |
| WEB-05 | 05-01, 05-05, 05-06, 05-08 | Profile photo upload and TV avatar display | SATISFIED | Gap closed by `avatar_url` ProfileDataStore persistence, ProfileManager emission regression, and existing TV avatar UI wiring. |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| None | - | - | - | No blocker anti-patterns found in the gap-closure files. Nullable defaults and empty-object fallbacks in the adapter/ProfileJson are compatibility behavior, not stubs. |

### Human Verification Required

### 1. Profile Settings Catalog Reorder

**Test:** Sign into nexio-web, open profile 2, reorder or hide home catalogs, then trigger the TV app's next profile settings sync.
**Expected:** TV profile 2 uses the new catalog order and disabled catalog list.
**Why human:** Requires live Supabase profile settings rows and TV runtime behavior.

### 2. Profile Settings Formatter Change

**Test:** Change formatter enabled state, selected template, and a custom template from nexio-web for profile 2, then sync the TV app.
**Expected:** TV profile 2 applies the same formatter state.
**Why human:** Requires live Supabase settings rows and TV runtime behavior.

### 3. Profile Photo Upload

**Test:** Upload a JPEG/PNG/WebP for profile 2 from nexio-web, sync TV, and inspect profile selection, sidebar, and settings header.
**Expected:** The resized avatar image appears on TV; removing it returns to the color fallback.
**Why human:** Requires live Supabase Storage plus visual inspection of TV UI.

### 4. Profile CRUD End-to-End

**Test:** Create profile 2, rename it, delete it from nexio-web, then launch/sync the TV app.
**Expected:** TV profile list reflects create, rename, and delete after sync.
**Why human:** Requires live Supabase auth/RPC state and TV runtime.

### 5. Provider OAuth End-to-End

**Test:** Link and unlink Trakt and Simkl from profile 2 in nexio-web.
**Expected:** Web linked status updates, Supabase token rows/tombstones update, and TV profile 2 auth state changes after sync.
**Why human:** Requires live external OAuth providers.

### Gaps Summary

No automated blocking gaps remain from the previous Phase 5 verification. The three prior failures were closed:

1. Catalog settings now use the Android v8 `layout_settings` blob shape on the `tv` platform row.
2. Formatter settings now use the Android v8 `player_settings` blob shape on the `tv` platform row.
3. Web-uploaded avatar URLs now survive Android profile persistence and reach `ProfileManager.profiles`.

Overall status is `human_needed`, not `passed`, because live Supabase/OAuth/TV-runtime flows still require manual verification.

---

_Verified: 2026-04-14T21:36:24Z_
_Verifier: Codex (gsd-verifier)_
