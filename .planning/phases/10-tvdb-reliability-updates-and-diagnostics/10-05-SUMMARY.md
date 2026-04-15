---
phase: 10-tvdb-reliability-updates-and-diagnostics
plan: 05
subsystem: tvdb
tags: [tvdb, diagnostics, settings-ui, debug-ui, snapshot-projection]

requires:
  - phase: 10-00
    provides: TvdbDiagnosticsRecorder Hilt binding, TvdbDiagnosticsSnapshot, TvdbDiagnosticsDataStore
  - phase: 10-04
    provides: Graceful fallback diagnostics (STALE_CACHE_SERVED, INVALID_CREDENTIALS, field-level)
provides:
  - User-facing TVDB reliability status in TVDB settings (D-10)
  - Detailed provider/cache/fallback diagnostics in Debug settings (D-10, D-11)
  - settingsStatusLine() and debugDetailLines() snapshot projection extensions
affects: [settings-ui, debug-ui]

tech-stack:
  added: []
  patterns: [snapshot-projection-extensions, diagnostics-ui-layering]

key-files:
  created: []
  modified:
    - app/src/main/java/com/nexio/tv/data/local/TvdbDiagnosticsDataStore.kt
    - app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModel.kt
    - app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsScreen.kt
    - app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModel.kt
    - app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsScreen.kt
    - app/src/main/res/values/strings.xml
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbDiagnosticsTest.kt
    - app/src/test/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModelTest.kt

key-decisions:
  - "settingsStatusLine() prioritizes invalid credentials > stale cache > update failed > reference failed"
  - "debugDetailLines() returns all nine snapshot fields as labeled strings without exposing secrets"
  - "Debug-only reasons (provider choice, poster override, date-only, missing airsTime, TMDB skip) excluded from settings projection"

patterns-established:
  - "Snapshot projection: extension functions on TvdbDiagnosticsSnapshot for settings vs debug UI layering"
  - "Diagnostics UI layering: settings shows user-facing status, debug shows all nine reason fields"

requirements-completed: [UX-03, CACHE-02, CACHE-03]

duration: 18min
completed: 2026-04-15
---

# Phase 10 Plan 05: Settings UI and Diagnostics Display Summary

**TVDB diagnostics projected into user-facing settings status and detailed Debug diagnostics via snapshot extension functions consuming the shared recorder**

## Performance

- **Duration:** 18 min
- **Started:** 2026-04-15T20:16:41Z
- **Completed:** 2026-04-15T20:34:35Z
- **Tasks:** 1
- **Files modified:** 8

## Accomplishments
- TvdbDiagnosticsSnapshot gains `settingsStatusLine()` extension for user-facing status (invalid credentials, stale cache, update/reference refresh failures)
- TvdbDiagnosticsSnapshot gains `debugDetailLines()` extension for all nine diagnostic fields as labeled strings
- TvdbSettingsViewModel injects TvdbDiagnosticsDataStore and exposes `tvdbStatusLine` and `tvdbLastRefreshLine` to UI state
- TvdbSettingsScreen shows reliability status row when diagnostics issues are present
- DebugSettingsViewModel injects TvdbDiagnosticsDataStore and exposes `tvdbDiagnostics` snapshot
- DebugSettingsScreen adds TVDB Diagnostics section with `debug_tvdb_diagnostics` key showing all detail lines
- strings.xml adds `tvdb_status_invalid_credentials`, `tvdb_status_stale_cache`, `tvdb_status_update_failed`, `tvdb_status_reference_failed`, `debug_tvdb_diagnostics_title`, `debug_tvdb_diagnostics_empty`
- 6 new tests across TvdbDiagnosticsTest and TvdbSettingsViewModelTest, all passing

## Task Commits

Each task was committed atomically:

1. **Task 1 (RED):** Add failing tests for TVDB diagnostics UI projection - `e2c3ecf30` (test)
2. **Task 1 (GREEN):** Wire TVDB diagnostics into settings and Debug UI - `b40b4c357` (feat)

## Files Created/Modified
- `app/src/main/java/com/nexio/tv/data/local/TvdbDiagnosticsDataStore.kt` - settingsStatusLine() and debugDetailLines() snapshot projection extensions
- `app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModel.kt` - Inject TvdbDiagnosticsDataStore, expose tvdbStatusLine/tvdbLastRefreshLine
- `app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsScreen.kt` - Reliability status row in settings UI
- `app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModel.kt` - Inject TvdbDiagnosticsDataStore, expose tvdbDiagnostics snapshot
- `app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsScreen.kt` - TVDB Diagnostics section with debug detail lines
- `app/src/main/res/values/strings.xml` - 6 new diagnostic status string resources
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbDiagnosticsTest.kt` - 5 new projection tests
- `app/src/test/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModelTest.kt` - 1 new diagnostics status test + updated 7 existing tests for new constructor

## Decisions Made
- settingsStatusLine() prioritizes invalid credentials > stale cache > update failed > reference failed
- debugDetailLines() returns all nine snapshot fields as labeled strings without exposing secrets
- Debug-only reasons (provider choice, poster override, date-only, missing airsTime, TMDB skip) excluded from user-facing settings projection

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed missing TrackingProvider import in TraktProgressService.kt**
- **Found during:** Task 1 GREEN
- **Issue:** Pre-existing dirty file had unresolved `TrackingProvider` reference blocking compilation
- **Fix:** Added `import com.nexio.tv.domain.model.TrackingProvider`
- **Files modified:** TraktProgressService.kt
- **Committed in:** b40b4c357

**2. [Rule 3 - Blocking] Stale Hilt KSP artifacts required full clean build**
- **Found during:** Task 1 GREEN
- **Issue:** `MainActivity_GeneratedInjector` NullPointerException during Hilt annotation processing due to stale generated code
- **Fix:** Full clean build (`./gradlew clean`) before running KSP + Kotlin + Hilt Java compilation
- **Files modified:** None (build environment)

**3. [Rule 3 - Blocking] Updated 7 existing TvdbSettingsViewModelTest constructors**
- **Found during:** Task 1 GREEN
- **Issue:** TvdbSettingsViewModel gained new `diagnosticsDataStore` constructor parameter; existing tests failed to compile
- **Fix:** Added `emptyDiagnosticsDataStore` mock field and passed to all 7 existing constructor calls
- **Files modified:** TvdbSettingsViewModelTest.kt
- **Committed in:** b40b4c357

---

**Total deviations:** 3 auto-fixed (all blocking)
**Impact on plan:** All auto-fixes were necessary for compilation. No scope creep.

## Issues Encountered
None beyond the auto-fixed compilation issues documented above.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- TVDB diagnostics visible in three layers: user settings, debug settings, and structured logs
- Ready for plan 10-06 (user-facing documentation)

---
*Phase: 10-tvdb-reliability-updates-and-diagnostics*
*Completed: 2026-04-15*

## Self-Check: PASSED
- All 8 key files exist on disk
- Both task commits (e2c3ecf30, b40b4c357) found in git log
