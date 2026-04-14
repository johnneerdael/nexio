---
phase: 05-nexio-web-integration
plan: 07
subsystem: web-android-sync
tags: [nuxt, android, datastore, profile-settings, settings-sync]

requires:
  - phase: 05-nexio-web-integration
    plan: 04
    provides: ProfileCatalogsTab and ProfileFormatterTab save PortalSettings via profile settings routes
  - phase: 05-nexio-web-integration
    plan: 06
    provides: Android profile settings sync imports v8 encoded feature blobs
provides:
  - WEB-03 web catalog ordering saved as Android v8 layout_settings preferences
  - WEB-04 web formatter settings saved as Android v8 player_settings preferences
  - Profile settings GET keeps returning PortalSettings for nexio-web profile tabs
affects: [phase-05-nexio-web-integration, nexio-web-profile-settings, android-profile-settings-sync]

tech-stack:
  added: []
  patterns:
    - PortalSettings profile saves are adapted into Android v8 encoded feature blobs before Supabase push
    - Profile settings routes read and write the Android tv platform row
    - Web profile settings merges preserve unrelated encoded preference keys and feature roots

key-files:
  created:
    - nexio-web/server/utils/profile-settings-blob.ts
    - nexio-web/tests/profile-settings-blob.test.ts
  modified:
    - nexio-web/server/api/account/profiles/settings.get.ts
    - nexio-web/server/api/account/profiles/settings.post.ts
    - app/src/test/java/com/nexio/tv/core/sync/ProfileSettingsSyncServiceTest.kt

key-decisions:
  - "Kept nexio-web's UI contract as PortalSettings while storing profile settings in the Android tv platform v8 blob row."
  - "Used pull-merge-push on POST so web-managed catalog and formatter saves preserve unrelated Android settings keys."
  - "Treated changedPaths as an allowlist for known WEB-03/WEB-04 mappings and ignored unknown paths."

patterns-established:
  - "Profile settings blob adapters should decode remote storage into UI contracts and encode UI saves into device-native sync contracts."
  - "Profile settings route tests assert platform row and pull-merge-push behavior to catch web-vs-tv row drift."

requirements-completed: [WEB-03, WEB-04]

duration: 10 min
completed: 2026-04-14
---

# Phase 05 Plan 07: Profile Settings Blob Gap Closure Summary

**PortalSettings-to-Android v8 adapter with tv-platform pull-merge-push routes and DataStore import regressions**

## Performance

- **Duration:** 10 min
- **Started:** 2026-04-14T21:21:10Z
- **Completed:** 2026-04-14T21:31:20Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments

- Added `profile-settings-blob.ts`, a bidirectional adapter between nexio-web `PortalSettings` and Android v8 encoded `layout_settings` / `player_settings` blobs.
- Wired profile settings GET to pull `p_platform: 'tv'` and decode the stored blob back into PortalSettings for the current profile tabs.
- Wired profile settings POST to pull the existing tv blob, merge changed catalog/formatter paths into it, and push the merged encoded blob back to Supabase.
- Added web regression coverage for encoding, decoding, preservation of unrelated keys, changed-path allowlisting, and route platform/merge behavior.
- Added Android real DataStore import fixtures for web-produced catalog and formatter encoded blobs.

## Task Commits

Each task was committed atomically, with TDD RED/GREEN commits where applicable:

1. **Task 1: Add PortalSettings to Android v8 profile settings blob adapter**
   - `nexio-web@25b5e29` (test): failing adapter behavior tests
   - `nexio-web@d8e9085` (feat): adapter implementation
   - `root@6c244cb01` (feat): root submodule pointer for Task 1
2. **Task 2: Wire profile settings routes through the adapter and add Android import regressions**
   - `nexio-web@a6e496f` (test): failing route regression tests
   - `root@2aa68381d` (test): Android DataStore import regressions plus web test pointer
   - `nexio-web@9367161` (feat): tv-platform route implementation
   - `root@d9f5a3d65` (feat): root submodule pointer for Task 2

**Plan metadata:** committed separately in the final docs commit.

## Files Created/Modified

- `nexio-web/server/utils/profile-settings-blob.ts` - Decodes Android v8 feature blobs into PortalSettings and merges PortalSettings changes into encoded settings blobs.
- `nexio-web/tests/profile-settings-blob.test.ts` - Covers adapter mapping, key preservation, unknown changed-path filtering, and route source regressions.
- `nexio-web/server/api/account/profiles/settings.get.ts` - Pulls the tv platform blob and returns decoded PortalSettings to the web UI.
- `nexio-web/server/api/account/profiles/settings.post.ts` - Pulls the current tv blob, merges sanitized PortalSettings changes, and pushes the encoded result.
- `app/src/test/java/com/nexio/tv/core/sync/ProfileSettingsSyncServiceTest.kt` - Adds real DataStore import tests for web-encoded catalog and formatter blobs.

## Decisions Made

- Kept the API response shape as PortalSettings so `ProfileCatalogsTab` and `ProfileFormatterTab` did not need UI changes.
- Used the adapter to sanitize inbound POST settings before encoding, avoiding raw browser payloads crossing into Supabase storage.
- Wrote static route regression tests in the existing Node test file because the project does not have a Nuxt server-route test harness.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Ignored unknown changedPaths instead of treating them as update-all**
- **Found during:** Task 1 (adapter implementation)
- **Issue:** The first merge implementation filtered unknown changed paths to an empty list, which made a non-empty unknown `changedPaths` payload behave like "update all managed keys."
- **Fix:** Added a regression test and separated "no changedPaths supplied" from "changedPaths supplied but none are known."
- **Files modified:** `nexio-web/server/utils/profile-settings-blob.ts`, `nexio-web/tests/profile-settings-blob.test.ts`
- **Verification:** `npx tsx --test tests/profile-settings-blob.test.ts` passes with 9/9 tests.
- **Committed in:** `nexio-web@d8e9085`, `root@6c244cb01`

---

**Total deviations:** 1 auto-fixed (1 missing critical functionality)
**Impact on plan:** The fix directly implements threat mitigation T-05-07-02 and prevents client-controlled path strings from broadening the server-side merge.

## Issues Encountered

- `npm run build` in `nexio-web` exits 0. It still emits pre-existing Nuxt warnings about duplicate `PortalFormatterRichText`, duplicate formatter icon token imports, and one large client chunk.
- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.ProfileSettingsSyncServiceTest" -x lint` is blocked before running the requested tests by unrelated test source-set compile errors in existing tests, including `ProfileManagerTest`, `HomeCatalogSnapshotStoreTest`, `PlayerSettingsDataStoreTest`, `PlayerSettingsDataStoreSpoolModeTest`, `SearchHistoryDataStoreTest`, `ThemeDataStoreProfileTest`, `SearchViewModelHistoryTest`, `CatalogSelectionPersistenceTest`, `PlaybackSettingsViewModelSpoolModeTest`, `SimklViewModelTest`, and `TraktViewModelPriorityHydrationTest`.
- The same Gradle run also hit the known local Kotlin daemon `ZGenerational` issue and used the non-daemon fallback before failing on those unrelated test compile errors.

## Verification

- Passed: `cd nexio-web && npx tsx --test tests/profile-settings-blob.test.ts` (9/9 tests).
- Passed: `cd nexio-web && npm run build`.
- Blocked by unrelated existing test compile errors: `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.ProfileSettingsSyncServiceTest" -x lint`.
- Passed static acceptance: profile settings GET/POST use `p_platform: 'tv'` and no longer contain `p_platform: 'web'`.
- Passed static acceptance: POST pulls `sync_pull_profile_settings_blob` before pushing `sync_push_profile_settings_blob`.

## User Setup Required

None.

## Known Stubs

None. Stub scan only found legitimate default values and null checks in adapter/test code.

## Threat Flags

None beyond the plan threat model. The modified profile settings routes were in scope and continue using bearer-token auth, `supabaseUser(event)`, validated profile indices, and auth-scoped Supabase RPCs.

## Next Phase Readiness

WEB-03 and WEB-04 now have the missing shape adapter between nexio-web and Android profile settings sync. Phase 5 verification can re-check that catalog order and formatter settings no longer fail because of PortalSettings-vs-v8 blob mismatch; Android test-source compile drift remains a separate prerequisite for executing the targeted unit test class.

## Self-Check: PASSED

- Found `nexio-web/server/utils/profile-settings-blob.ts`
- Found `nexio-web/server/api/account/profiles/settings.get.ts`
- Found `nexio-web/server/api/account/profiles/settings.post.ts`
- Found `nexio-web/tests/profile-settings-blob.test.ts`
- Found `app/src/test/java/com/nexio/tv/core/sync/ProfileSettingsSyncServiceTest.kt`
- Found root commits `6c244cb01`, `2aa68381d`, and `d9f5a3d65`
- Found nexio-web commits `25b5e29`, `d8e9085`, `a6e496f`, and `9367161`
- Verified unrelated dirty files remain unstaged and outside plan commits

---
*Phase: 05-nexio-web-integration*
*Completed: 2026-04-14*
