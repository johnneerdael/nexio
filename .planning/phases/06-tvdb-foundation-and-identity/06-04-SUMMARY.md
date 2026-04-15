---
phase: 06-tvdb-foundation-and-identity
plan: 04
subsystem: account-sync
tags: [tvdb, account-sync, supabase, settings, secrets]

requires:
  - phase: 06-01
    provides: RED account sync coverage for TVDB public settings secrecy and tvdb_api_key allowlists
  - phase: 06-02
    provides: TVDB settings DataStore, credential model, validation status, and auth/token persistence
provides:
  - TVDB public account sync model with enabled, configured, validationStatus, and lastFailure fields
  - TVDB credential secret payload using tvdb_api_key and integration:tvdb
  - AccountSettingsSyncService wiring for TVDB public settings and secret credential push/pull
  - Public sync JSON schema coverage for TVDB non-secret state
  - Supabase checked SQL allowlists and canonical/default payload support for tvdb_api_key and integrations.tvdb
affects: [06-05-tvdb-settings-ui, phase-07-tvdb-provider-replacement, account-settings-sync]

tech-stack:
  added: []
  patterns: [kotlinx.serialization sync DTOs, Supabase account secret RPC reuse, v7 public settings schema]

key-files:
  created: []
  modified:
    - app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt
    - app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt
    - app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt
    - app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt
    - docs/settings/settings-sync.schema.json
    - supabase/account_settings_sync.sql

key-decisions:
  - "Keep TVDB credentials out of public account settings JSON; sync API key and optional PIN only through tvdb_api_key with integration:tvdb."
  - "Apply TVDB public status through the existing TvdbSettingsDataStore methods instead of expanding the DataStore API outside this plan's ownership."
  - "Do not update .planning/STATE.md or .planning/ROADMAP.md because the phase orchestrator owns shared state writes after the wave."

patterns-established:
  - "TVDB public sync mirrors status metadata while AccountTvdbCredentialSecretPayload carries API key and optional PIN through secret RPCs."
  - "TVDB local-change observation emits integrations.tvdb so v7 changed-path pushes can merge TVDB independently."
  - "Supabase account secret allowlists must accept tvdb_api_key consistently across set, delete, resolve, and table constraints."

requirements-completed: [PREF-01]

duration: 17min
completed: 2026-04-15
---

# Phase 6 Plan 4: TVDB Account Sync Summary

**TVDB account sync now separates public provider state from secret-backed API key and optional PIN credentials.**

## Performance

- **Duration:** 17 min
- **Started:** 2026-04-15T01:44:04Z
- **Completed:** 2026-04-15T02:00:27Z
- **Tasks:** 3
- **Files modified:** 6

## Accomplishments

- Added `TvdbSyncSettings` and `AccountTvdbCredentialSecretPayload`, with tests proving public account sync omits `apiKey`, `pin`, and `token`.
- Wired `AccountSettingsSyncService` to observe TVDB settings, build public TVDB payload state, push credentials through `sync_set_account_secret`, delete blank credentials, and resolve/apply remote TVDB credentials.
- Updated the public sync schema and Supabase SQL contract so `integrations.tvdb` is canonicalized and `tvdb_api_key` is accepted by every checked allowlist.

## Task Commits

Each task was committed atomically:

1. **Task 1 RED: TVDB public sync contract** - `b049591f9` (test)
2. **Task 1 GREEN: TVDB sync models** - `316695474` (feat)
3. **Task 2 RED: TVDB changed-path contract** - `025f252ea` (test)
4. **Task 2 GREEN: TVDB service sync wiring** - `98cf1b1d7` (feat)
5. **Task 3: Schema and SQL allowlists** - `82a57d685` (feat)

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt` - Adds TVDB public sync and credential secret payload models.
- `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt` - Adds TVDB settings flow observation and `integrations.tvdb` changed-path emission.
- `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` - Adds TVDB DataStore injection, public sync build/apply, and secret credential set/delete/resolve paths.
- `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt` - Adds public TVDB secrecy and changed-path tests.
- `docs/settings/settings-sync.schema.json` - Adds required non-secret `integrations.tvdb` schema fields and aligns schemaVersion with v7.
- `supabase/account_settings_sync.sql` - Adds `tvdb_api_key` allowlists plus TVDB default/canonical payload merging.

## Decisions Made

- Stored TVDB API key and optional PIN together in the same `tvdb_api_key` secret payload, keyed by fixed ref `integration:tvdb`.
- Used `pin.takeIf { it.isNotBlank() }` so blank PINs are omitted from the secret payload.
- Left unrelated dirty worktree and orchestrator state files untouched.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Narrowed the inherited RED sync test to the planned private secret constants**
- **Found during:** Task 1 (Add TVDB public and secret sync models)
- **Issue:** The 06-01 RED test asserted temporary `TVDB_ACCOUNT_SECRET_TYPE` and `TVDB_ACCOUNT_SECRET_REF` constants, while this plan requires private service constants `TVDB_SECRET_TYPE` and `TVDB_SECRET_REF`.
- **Fix:** Kept the public JSON secrecy assertions, renamed the focused test to `tvdb public sync omits credential fields`, and removed the temporary constant assertions.
- **Files modified:** `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`
- **Verification:** RED run showed the remaining Task 1 failure was the missing `TvdbSyncSettings`/`IntegrationSettings.tvdb` model, then the post-implementation run no longer reported those errors.
- **Committed in:** `b049591f9`

**2. [Rule 1 - Bug] Aligned the schema document with the active v7 sync contract**
- **Found during:** Task 3 (Update sync schema and Supabase secret allowlists)
- **Issue:** `docs/settings/settings-sync.schema.json` still declared schemaVersion 6 even though the Android account config contract uses version 7.
- **Fix:** Updated the schema `const` and `default` to 7 while adding TVDB public fields.
- **Files modified:** `docs/settings/settings-sync.schema.json`
- **Verification:** `jq empty docs/settings/settings-sync.schema.json` passed and the TVDB schema greps matched.
- **Committed in:** `82a57d685`

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 bug)
**Impact on plan:** Both fixes kept the implementation aligned with the planned private secret-channel design and current v7 account sync contract.

## Issues Encountered

- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest"` remains blocked during whole-test-source compilation by out-of-scope files, including profile/player/settings constructor mismatches and future TVDB settings/fallback RED tests. The post-Task 2 run no longer reported 06-04 observer signature errors.
- The local Kotlin daemon repeatedly failed on `Unrecognized VM option 'ZGenerational'`; Gradle fell back to non-daemon compilation.
- A Task 3 commit attempt initially picked up already-staged files outside this plan. It was corrected non-destructively with a soft reset, unstaging, and a new commit containing only `docs/settings/settings-sync.schema.json` and `supabase/account_settings_sync.sql`.

## Verification

- `./gradlew assembleArm64Debug` - PASSED (`BUILD SUCCESSFUL in 1m 29s`).
- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest"` - BLOCKED at unit-test compilation by out-of-scope test files; no remaining 06-04 `tvdbSettings` signature errors were present in the final run.
- `grep -n "tvdb_api_key" supabase/account_settings_sync.sql` - PASSED with 8 matches.
- `grep -n '"tvdb"' docs/settings/settings-sync.schema.json` - PASSED.
- `grep -n 'apiKey\|pin\|token' docs/settings/settings-sync.schema.json` - PASSED with no matches.
- `jq empty docs/settings/settings-sync.schema.json` - PASSED.

## Known Stubs

None. Stub scan found only intentional DTO defaults, nullable timestamp/secret fields, and SQL local variable defaults; no UI-facing placeholder data was introduced.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 06-05 can build the TVDB settings UI against `TvdbSettingsDataStore` and rely on account sync to carry only public status state plus secret-backed credentials. Later provider-replacement phases can treat `integrations.tvdb` as the synced account-level TV provider status.

## Self-Check: PASSED

- Verified the summary, schema, SQL, and key modified source files exist on disk.
- Verified task commits exist: `b049591f9`, `316695474`, `025f252ea`, `98cf1b1d7`, `82a57d685`.
- Verified the final Task 3 commit contains only `docs/settings/settings-sync.schema.json` and `supabase/account_settings_sync.sql` after correcting the staging issue.

---
*Phase: 06-tvdb-foundation-and-identity*
*Completed: 2026-04-15*
