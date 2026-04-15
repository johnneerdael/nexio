---
phase: 06-tvdb-foundation-and-identity
plan: 03
subsystem: tvdb-identity
tags: [tvdb, identity, remote-ids, cache, fallback]

requires:
  - phase: 06-01
    provides: RED unit coverage for TVDB identity and fallback behavior
  - phase: 06-02
    provides: TVDB settings, auth service, token cache, and Retrofit API contract
provides:
  - TVDB series identity model with normalized remote-ID sources
  - SharedPreferences-backed TVDB identity lookup cache
  - TVDB identity service for TVDB ID and broad remote-ID resolution
  - Explicit TVDB provider fallback decision and sanitized fallback status recording
affects: [06-04-tvdb-sync, 06-05-tvdb-settings-ui, phase-07-tvdb-provider-replacement]

tech-stack:
  added: []
  patterns: [TVDB remote-ID normalization, SharedPreferences cache envelope, in-flight coroutine de-duping, settings-backed fallback diagnostics]

key-files:
  created:
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityModels.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbRemoteIdNormalizer.kt
    - app/src/main/java/com/nexio/tv/data/local/TvdbIdentityCacheStore.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityService.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbProviderFallback.kt
  modified:
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbIdentityServiceTest.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbProviderFallbackTest.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbDiagnosticsTest.kt

key-decisions:
  - "Keep TVDB identity lookup independent of TMDB; no TmdbApi or TmdbService dependency is introduced."
  - "Hydrate accepted remote-ID series matches through TVDB series detail before caching so exposed remote IDs are preserved."
  - "Use reason-code fallback diagnostics in settings state instead of browse-time UI or provider routing in Phase 6."

patterns-established:
  - "Remote ID cache keys use source plus normalized value, with official-site URLs trimmed for trailing slashes."
  - "Remote-ID search accepts only results with a non-null series record, then de-dupes concurrent callers with CompletableDeferred."
  - "Fallback status records exact reason codes only: not_configured, invalid_credentials, auth_unavailable, and series_not_found."

requirements-completed: [PREF-04, PREF-05, PREF-06, CACHE-01]

duration: 15min
completed: 2026-04-15
---

# Phase 6 Plan 3: TVDB Identity and Fallback Summary

**TVDB-first series identity lookup with broad remote-ID normalization, persisted cache, and settings-backed fallback decisions.**

## Performance

- **Duration:** 15 min
- **Started:** 2026-04-15T01:43:50Z
- **Completed:** 2026-04-15T01:58:44Z
- **Tasks:** 3
- **Files modified:** 8

## Accomplishments

- Added TVDB identity models and source normalization for TVDB, IMDb, TMDB, TV Maze, Wikidata, official-site, and OTHER remote IDs.
- Added `TvdbIdentityCacheStore` using `tvdb_identity_cache_v1`, `tvdb_identity::` keys, Gson payloads, and schema version `1` without credential fields.
- Added `TvdbIdentityService` with TVDB auth, remote-ID search, series-only filtering, detail hydration, persisted caching, and in-flight request de-duping.
- Added `TvdbProviderFallback` with explicit `UseTvdb` / `UseFallback(reason)` decisions and sanitized fallback reason recording to TVDB settings.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add identity models, source normalization, and persisted cache** - `b361cbdb3` (feat)
2. **Task 2: Implement TVDB identity service with de-duped lookup** - `3c0f5be58` (feat)
3. **Task 3: Implement provider fallback diagnostic decision model** - `feb62f8ef` (feat)

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityModels.kt` - TVDB remote-ID and series identity data models.
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbRemoteIdNormalizer.kt` - Remote-ID source and value normalization helpers.
- `app/src/main/java/com/nexio/tv/data/local/TvdbIdentityCacheStore.kt` - Persisted identity lookup cache.
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityService.kt` - Authenticated TVDB identity resolution, filtering, hydration, cache, and in-flight de-duping.
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbProviderFallback.kt` - Foundation provider decision and fallback status recording.
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbIdentityServiceTest.kt` - Updated RED identity test to use the actual TVDB API DTO contract.
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbProviderFallbackTest.kt` - Updated RED fallback test to the planned decision model.
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbDiagnosticsTest.kt` - Updated RED diagnostics test to the planned settings-backed fallback recording.

## Decisions Made

- TVDB remote-ID matches are hydrated through series detail before caching. This preserves remote IDs from TVDB detail responses and keeps downstream provider replacement from needing TMDB identity calls.
- The fallback foundation exposes a decision model only. It does not route to TMDB or call TVDB/TMDB APIs, matching the Phase 6 boundary.
- Unknown fallback strings are collapsed to `auth_unavailable` before logging or settings persistence so raw failure text cannot leak credential material.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed injectable constructor cycle in identity service**
- **Found during:** Task 2 (Implement TVDB identity service with de-duped lookup)
- **Issue:** The first service constructor shape produced a Kotlin delegation-cycle compile error while trying to support both Hilt injection and cache-free unit tests.
- **Fix:** Switched to a single `@Inject` primary constructor with an optional cache-store parameter default for tests.
- **Files modified:** `app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityService.kt`
- **Verification:** `./gradlew assembleArm64Debug` passed after the fix.
- **Committed in:** `3c0f5be58`

**2. [Rule 3 - Blocking] Aligned identity RED test with real TVDB API DTOs**
- **Found during:** Task 2 (Implement TVDB identity service with de-duped lookup)
- **Issue:** The 06-01 RED test mocked `searchByRemoteId` as returning `TvdbSeriesIdentity`, but 06-02 established `Response<TvdbRemoteIdSearchResponse>`.
- **Fix:** Updated the test to mock `TvdbRemoteIdSearchResponse` plus `getSeriesExtended` hydration.
- **Files modified:** `app/src/test/java/com/nexio/tv/core/tvdb/TvdbIdentityServiceTest.kt`
- **Verification:** The final combined unit-test compile no longer reports identity-test return-type errors; production assemble passed.
- **Committed in:** `3c0f5be58`

**3. [Rule 3 - Blocking] Aligned fallback RED tests with final decision-model contract**
- **Found during:** Task 3 (Implement provider fallback diagnostic decision model)
- **Issue:** The 06-01 RED fallback tests described a provider router and diagnostics recorder, while the 06-03 plan finalized a small settings-backed decision service that does not call providers.
- **Fix:** Updated fallback and diagnostics tests to assert `TvdbProviderDecision` results and sanitized `saveValidationFailure(FALLBACK_ACTIVE, reason)` behavior.
- **Files modified:** `app/src/test/java/com/nexio/tv/core/tvdb/TvdbProviderFallbackTest.kt`, `app/src/test/java/com/nexio/tv/core/tvdb/TvdbDiagnosticsTest.kt`
- **Verification:** The final combined unit-test compile no longer reports missing fallback/diagnostics classes; production assemble passed.
- **Committed in:** `feb62f8ef`

---

**Total deviations:** 3 auto-fixed (1 bug, 2 blocking)
**Impact on plan:** All changes stayed inside the planned identity/fallback surface or the explicitly allowed 06-01 RED test adjustments. No shared state, roadmap, sync schema, Supabase SQL, or unrelated dirty files were staged.

## Issues Encountered

- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbIdentityServiceTest" --tests "com.nexio.tv.core.tvdb.TvdbProviderFallbackTest" --tests "com.nexio.tv.core.tvdb.TvdbDiagnosticsTest"` is still blocked during global unit-test compilation by out-of-scope tests, including profile, playback settings, search history/theme profile tests, Trakt/Simkl constructor changes, and future `TvdbSettingsViewModelTest` UI classes.
- Gradle intermittently hit shared build-directory races while parallel executors were active, including `transformArm64DebugClassesWithAsm` delete failures and a missing Kotlin class output. Retrying production assemble succeeded.
- Kotlin daemon startup continues to emit `Unrecognized VM option 'ZGenerational'`, then falls back to non-daemon compilation.

## Verification

- `./gradlew assembleArm64Debug` - PASSED (`BUILD SUCCESSFUL in 19s` on final run).
- Combined targeted TVDB unit-test command - BLOCKED at global unit-test compilation by out-of-scope tests listed above; TVDB identity/fallback compile errors introduced by 06-01 RED drift were cleared.

## Known Stubs

None. Stub scan found only nullable data-model/default constructor values and null checks used by production logic.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Phase 7 provider replacement can call `TvdbIdentityService` for TVDB IDs and broad remote IDs without importing TMDB identity services. The fallback decision model is available for inactive or unusable TVDB states while preserving existing TMDB-backed behavior until provider replacement is implemented.

## Self-Check: PASSED

- Verified all created production files and this summary exist on disk.
- Verified task commits exist: `b361cbdb3`, `3c0f5be58`, `feb62f8ef`.

---
*Phase: 06-tvdb-foundation-and-identity*
*Completed: 2026-04-15*
