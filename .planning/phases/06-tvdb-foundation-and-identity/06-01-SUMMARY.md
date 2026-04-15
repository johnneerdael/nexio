---
phase: 06-tvdb-foundation-and-identity
plan: 01
subsystem: testing
tags: [tvdb, junit4, mockk, account-sync, supabase]

requires: []
provides:
  - RED unit coverage for TVDB authentication, token caching, and blank PIN omission
  - RED unit coverage for TVDB remote-ID identity preservation and in-flight de-duping
  - RED settings, sync, fallback, diagnostics, and SQL allowlist validation targets
affects: [06-02-tvdb-settings-auth, 06-03-tvdb-identity, 06-04-tvdb-sync, 06-05-tvdb-settings-ui]

tech-stack:
  added: []
  patterns: [JUnit4 coroutine tests, MockK service/API contracts, static SQL contract assertions]

key-files:
  created:
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbAuthServiceTest.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbIdentityServiceTest.kt
    - app/src/test/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModelTest.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbProviderFallbackTest.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbDiagnosticsTest.kt
    - app/src/test/java/com/nexio/tv/core/sync/TvdbSecretAllowlistStaticTest.kt
  modified:
    - app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt

key-decisions:
  - "Use RED tests as the Phase 6 implementation contract instead of adding production TVDB code in Wave 0."
  - "Represent TVDB public sync with enabled, configured, validationStatus, and lastFailure only; credentials stay behind tvdb_api_key and integration:tvdb secret references."

patterns-established:
  - "TVDB auth tests assert request DTO shape directly so blank PIN omission is executable."
  - "TVDB identity tests preserve remote IDs by normalized source key, including TVDB, IMDb, TMDB, TV Maze, Wikidata, official-site, and OTHER."
  - "TVDB sync tests assert credential fields are absent from public JSON before sync implementation exists."

requirements-completed: [PREF-01, PREF-04, PREF-05, PREF-06, CACHE-01]

duration: 10min
completed: 2026-04-15
---

# Phase 6 Plan 1: TVDB Validation Coverage Summary

**Executable RED coverage for TVDB credentials, identity matching, public sync secrecy, fallback diagnostics, and Supabase secret allowlists.**

## Performance

- **Duration:** 10 min
- **Started:** 2026-04-15T01:15:30Z
- **Completed:** 2026-04-15T01:25:05Z
- **Tasks:** 3
- **Files modified:** 7

## Accomplishments

- Added RED TVDB auth tests covering `/login` payload shape, blank PIN omission, non-blank PIN inclusion, token persistence, token-safe result display, and 401 invalid credentials.
- Added RED TVDB identity tests covering in-flight de-duping and preservation of TVDB, IMDb, TMDB, TV Maze, Wikidata, official-site, and OTHER remote IDs.
- Added RED settings, public sync, fallback, diagnostics, and static SQL/API-contract tests that downstream Phase 6 plans must turn green.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add RED TVDB auth and identity tests** - `57eb889d9` (test)
2. **Task 2: Add RED TVDB settings, sync, and fallback tests** - `ac1f0139a` (test)
3. **Task 3: Add RED TVDB SQL/static validation target** - `49a3b60be` (test)

Additional test-contract correction:

- `f73903b52` - aligned the auth test invalid status name with the settings test contract.

## Files Created/Modified

- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbAuthServiceTest.kt` - RED auth/token tests for TVDB login behavior.
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbIdentityServiceTest.kt` - RED identity lookup and in-flight de-duping tests.
- `app/src/test/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModelTest.kt` - RED settings validation, masking, and enablement tests.
- `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt` - Added TVDB public sync secrecy assertions.
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbProviderFallbackTest.kt` - RED provider fallback and diagnostics behavior tests.
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbDiagnosticsTest.kt` - RED sanitized fallback diagnostic tests.
- `app/src/test/java/com/nexio/tv/core/sync/TvdbSecretAllowlistStaticTest.kt` - RED static validation for `tvdb.yml` and Supabase `tvdb_api_key` allowlists.

## Decisions Made

- No production TVDB implementation was added in this wave. The plan’s output is intentionally RED tests only.
- The public account sync contract is tested as non-secret TVDB state only: `enabled`, `configured`, `validationStatus`, and `lastFailure`.
- The secret sync contract is tested against `tvdb_api_key` and `integration:tvdb`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Replaced Java 11-only file reads in static test**
- **Found during:** Task 3 (Add RED TVDB SQL/static validation target)
- **Issue:** `Files.readString` was unavailable under the Android unit-test compile target.
- **Fix:** Switched the static test to `Files.readAllBytes(...).toString(StandardCharsets.UTF_8)`.
- **Files modified:** `app/src/test/java/com/nexio/tv/core/sync/TvdbSecretAllowlistStaticTest.kt`
- **Verification:** Re-ran the Task 3 targeted Gradle command; the `Files.readString` error no longer appeared.
- **Committed in:** `49a3b60be`

**2. [Rule 1 - Bug] Aligned invalid credential status naming across RED tests**
- **Found during:** Task 3 verification review
- **Issue:** The auth test used `InvalidCredentials` while settings tests used `INVALID`, creating an inconsistent contract for the same validation state.
- **Fix:** Updated the auth test to assert `TvdbValidationStatus.INVALID`.
- **Files modified:** `app/src/test/java/com/nexio/tv/core/tvdb/TvdbAuthServiceTest.kt`
- **Verification:** Grep confirmed both auth and settings tests now use `TvdbValidationStatus.INVALID`.
- **Committed in:** `f73903b52`

---

**Total deviations:** 2 auto-fixed (2 bug fixes)
**Impact on plan:** Both fixes kept the RED validation surface internally consistent and compatible with the project test target. No production implementation was added.

## Issues Encountered

- The targeted Gradle commands correctly failed RED on missing planned TVDB classes and sync models, including `TvdbAuthService`, `TvdbApi`, `TvdbSettingsViewModel`, `TvdbSyncSettings`, diagnostics/fallback types, and TVDB secret constants.
- Gradle also emitted a local Kotlin daemon startup issue: `Unrecognized VM option 'ZGenerational'`. Gradle fell back to non-daemon compilation and continued.
- The compile pass also surfaced unrelated pre-existing dirty-worktree test failures in profile/playback/settings files, including `ProfileManagerTest`, `PlayerSettingsDataStore*Test`, search/theme tests, and settings view model tests. Those files were outside this plan and were not modified, staged, or committed.

## Known Stubs

None. Stub-pattern scan only found intentional test literals such as empty API-key inputs, null assertions, and regex text.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plans 06-02 through 06-05 now have explicit RED contracts for TVDB auth/settings, identity, public and secret sync, fallback diagnostics, and SQL allowlist behavior. The RED targets will remain blocked until the planned production classes and Supabase allowlist updates are implemented.

## Self-Check: PASSED

- Verified all created files and the summary exist on disk.
- Verified task/deviation commits exist: `57eb889d9`, `ac1f0139a`, `f73903b52`, `49a3b60be`.

---
*Phase: 06-tvdb-foundation-and-identity*
*Completed: 2026-04-15*
