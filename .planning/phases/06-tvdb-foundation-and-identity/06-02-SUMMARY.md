---
phase: 06-tvdb-foundation-and-identity
plan: 02
subsystem: tvdb-auth
tags: [tvdb, datastore, retrofit, hilt, auth-cache]

requires:
  - phase: 06-01
    provides: RED unit coverage for TVDB auth, settings state, token caching, and blank PIN omission
provides:
  - TVDB settings model with validation status and active/configured helpers
  - TVDB settings and token Preferences DataStores
  - TVDB Retrofit API contract and named Hilt Retrofit binding
  - Cached TVDB bearer-token service with mutex-protected refresh and sanitized invalid state
affects: [06-03-tvdb-identity, 06-04-tvdb-sync, 06-05-tvdb-settings-ui]

tech-stack:
  added: []
  patterns: [Preferences DataStore integration settings, named Retrofit provider, credential-fingerprinted bearer-token cache]

key-files:
  created:
    - app/src/main/java/com/nexio/tv/domain/model/TvdbSettings.kt
    - app/src/main/java/com/nexio/tv/data/local/TvdbSettingsDataStore.kt
    - app/src/main/java/com/nexio/tv/data/local/TvdbTokenStore.kt
    - app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbAuthService.kt
  modified:
    - app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt

key-decisions:
  - "Keep the canonical TVDB settings model in domain/model while exposing compatibility typealiases for existing RED test imports."
  - "Use a separate @Named(\"tvdb\") Retrofit binding so TVDB does not alter existing TMDB Retrofit behavior."
  - "Fingerprint cached TVDB tokens against trimmed API key plus PIN and refresh under a Mutex before the final 24 hours."

patterns-established:
  - "TVDB local stores keep credentials/token local and expose only validation metadata as observable state."
  - "TVDB auth logs endpoint/status/reason only; request bodies, API keys, PINs, bearer values, and Authorization headers stay out of logs."
  - "Validated credential saves do not clear tokens; explicit credential edits and clears are the token-invalidation paths."

requirements-completed: [PREF-01, PREF-05, CACHE-01]

duration: 13min
completed: 2026-04-15
---

# Phase 6 Plan 2: TVDB Foundation Auth Summary

**TVDB credentials, token persistence, Retrofit wiring, and mutex-protected bearer-token reuse.**

## Performance

- **Duration:** 13 min
- **Started:** 2026-04-15T01:28:07Z
- **Completed:** 2026-04-15T01:41:00Z
- **Tasks:** 3
- **Files modified:** 6

## Accomplishments

- Added `TvdbSettings` and validation status state, plus `TvdbSettingsDataStore` and `TvdbTokenStore` using existing Preferences DataStore patterns.
- Added `TvdbApi` with `/login`, search, remote-ID search, series base, and series extended endpoints, plus `@Named("tvdb")` Retrofit/Hilt providers.
- Added `TvdbAuthService` that omits blank PINs, caches tokens with expiry and credential fingerprint metadata, serializes refreshes with `Mutex`, and records invalid credentials without logging secrets.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add TVDB settings and token stores** - `4d4137356` (feat)
2. **Task 2: Add TvdbApi and Hilt network binding** - `a6e2e7dce` (feat)
3. **Task 3: Implement cached TVDB auth service** - `c087be31b` (feat)

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/domain/model/TvdbSettings.kt` - TVDB settings and validation status domain model.
- `app/src/main/java/com/nexio/tv/data/local/TvdbSettingsDataStore.kt` - Local TVDB settings persistence and credential clearing behavior.
- `app/src/main/java/com/nexio/tv/data/local/TvdbTokenStore.kt` - Local bearer-token, expiry, and credential-fingerprint persistence.
- `app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt` - Retrofit contract and Moshi DTOs for TVDB foundation endpoints.
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAuthService.kt` - Cached TVDB auth, validation, token refresh, and sanitized failure state.
- `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt` - Named TVDB Retrofit and API providers.

## Decisions Made

- Used the plan’s `TvdbSettings` contract exactly in the domain model, then added Kotlin typealiases in local/core packages to keep the 06-01 RED tests aligned without moving the canonical model.
- Kept token refresh logic in one service instead of adding interceptors so future TVDB identity/detail services can explicitly request a cached bearer header.
- Treated `saveCredentials` as a post-validation persistence method and left token invalidation on `setCredentials` and `clearCredentials`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added compatibility aliases for 06-01 RED imports**
- **Found during:** Task 1 and Task 3
- **Issue:** The plan places `TvdbSettings` in `domain/model`, while existing RED tests import `TvdbSettings` from `data.local` and `TvdbValidationStatus`/`TvdbTokenStore` from `core.tvdb`.
- **Fix:** Added Kotlin typealiases that point to the canonical production types without duplicating models.
- **Files modified:** `TvdbSettingsDataStore.kt`, `TvdbAuthService.kt`
- **Verification:** The final targeted auth-test compile no longer reports unresolved references for `TvdbSettings`, `TvdbValidationStatus`, `TvdbTokenStore`, `TvdbApi`, `TvdbLoginRequest`, `TvdbLoginResponse`, or `TvdbAuthService`.
- **Committed in:** `4d4137356`, `c087be31b`

**2. [Rule 1 - Bug] Kept validated credential saves from clearing fresh tokens**
- **Found during:** Task 3 (Implement cached TVDB auth service)
- **Issue:** The initial `saveCredentials` implementation cleared tokens whenever credential values changed. In the validation flow, credentials are saved after a successful login, which would delete the freshly persisted bearer token.
- **Fix:** Kept token clearing on `setCredentials` and `clearCredentials`, but made `saveCredentials` preserve token state for post-validation saves.
- **Files modified:** `TvdbSettingsDataStore.kt`
- **Verification:** `./gradlew assembleArm64Debug` passed after the change; Task 3 grep verified `Mutex`, refresh constants, blank PIN omission, and no secret-bearing log calls.
- **Committed in:** `c087be31b`

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 bug)
**Impact on plan:** Both changes preserve the planned architecture while making the implementation compile-safe and preventing validation from invalidating its own cached token.

## Issues Encountered

- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbAuthServiceTest"` could not execute the filtered auth tests because Gradle compiles all unit-test sources first. Remaining compile blockers are outside this plan’s owned files: future Phase 6 RED contracts (`TvdbSyncSettings`, TVDB identity/diagnostics/fallback/settings UI classes) plus unrelated dirty profile/playback/settings test constructor mismatches.
- Kotlin compilation repeatedly reported local daemon startup failure from `Unrecognized VM option 'ZGenerational'`, then used the fallback non-daemon compiler path.

## Verification

- `./gradlew assembleArm64Debug` - PASSED (`BUILD SUCCESSFUL in 12s` on final run).
- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbAuthServiceTest"` - BLOCKED at unit-test compilation by out-of-scope RED/future-plan and unrelated dirty test files; current plan auth/API/store unresolved references were cleared.

## Known Stubs

None. Stub scan found only intentional empty/default values in settings state, token state, and nullable API DTO fields. Existing placeholder URLs in `NetworkModule.kt` predate this plan and were not introduced here.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 06-03 can build TVDB identity lookup on top of `TvdbApi.searchByRemoteId`, `TvdbAuthService.bearerToken()`, and the credential-fingerprinted token cache. Plan 06-04 still needs public/secret sync models and Supabase allowlist work before the broader Phase 6 RED sync tests can compile.

## Self-Check: PASSED

- Verified all created source files and this summary exist on disk.
- Verified task commits exist: `4d4137356`, `a6e2e7dce`, `c087be31b`.

---
*Phase: 06-tvdb-foundation-and-identity*
*Completed: 2026-04-15*
