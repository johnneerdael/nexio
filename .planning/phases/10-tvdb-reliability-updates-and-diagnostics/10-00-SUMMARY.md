---
phase: 10-tvdb-reliability-updates-and-diagnostics
plan: 00
subsystem: tvdb-diagnostics-foundation
tags: [tvdb, diagnostics, hilt, datastore, sanitization]
dependency_graph:
  requires: [Phase 6-9 TVDB source files]
  provides: [TvdbReliabilityReason, TvdbReliabilityDiagnostic, TvdbDiagnosticsRecorder, TvdbDiagnosticsDataStore, TvdbDiagnosticsModule]
  affects: [10-01, 10-02, 10-03, 10-04, 10-05]
tech_stack:
  added: []
  patterns: [Preferences DataStore bounded snapshot, typed reason enum with sanitized payloads, Hilt @Binds interface binding]
key_files:
  created:
    - .planning/phases/10-tvdb-reliability-updates-and-diagnostics/10-BINDINGS.md
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbReliabilityDiagnostics.kt
    - app/src/main/java/com/nexio/tv/data/local/TvdbDiagnosticsDataStore.kt
    - app/src/main/java/com/nexio/tv/core/di/TvdbDiagnosticsModule.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbReliabilityDiagnosticsTest.kt
    - app/src/test/java/com/nexio/tv/data/local/TvdbDiagnosticsDataStoreTest.kt
  modified: []
decisions:
  - Phase 6-9 TVDB source gate confirms all 13 files present with required symbols
  - Sanitization regex handles Authorization Bearer TOKEN multi-word patterns and standalone bearer prefix
  - DataStore bounded snapshot stores 9 category fields for later UI projection
  - NETWORK_CALL_BLOCKED and UNKNOWN_UPDATE_EVENT logged but not persisted to snapshot
metrics:
  duration: 7min
  completed: 2026-04-15
  tasks: 3
  files: 6
---

# Phase 10 Plan 00: Diagnostics Foundation Summary

Bind Phase 10 to Phase 6-9 TVDB source, create shared diagnostic contract with 15 typed reason codes and secret-sanitized payloads, and wire a concrete Hilt-bound Preferences DataStore recorder before reliability producers enter the graph.

## Task Completion

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Gate and bind Phase 6-9 TVDB source contracts | `371de9a30` | `10-BINDINGS.md` |
| 2 (RED) | Failing test for typed reliability diagnostics | `ded661bf7` | `TvdbReliabilityDiagnosticsTest.kt` |
| 2 (GREEN) | Implement typed sanitized reliability diagnostics | `a4903abb9` | `TvdbReliabilityDiagnostics.kt` |
| 3 (RED) | Failing test for diagnostics recorder storage | `665bc096d` | `TvdbDiagnosticsDataStoreTest.kt` |
| 3 (GREEN) | Create concrete diagnostics recorder storage and Hilt binding | `2e4a09e96` | `TvdbDiagnosticsDataStore.kt`, `TvdbDiagnosticsModule.kt` |

## What Was Built

### 10-BINDINGS.md
Phase 10 binding table documenting all 13 Phase 6-9 TVDB source files with roles, file paths, required symbols, and bound status. Every row confirmed `yes`.

### TvdbReliabilityDiagnostics.kt
- `TvdbReliabilityReason` enum with 15 exact reason codes covering provider choice, fallback, update/reference refresh, stale cache, invalid credentials, date-only gating, poster override, skipped TMDB TV fetches, and network call blocking.
- `TvdbReliabilityDiagnostic` data class with reason, surface, tvdbId, entityType, fallbackProvider, message, occurredAtMs.
- `sanitized()` extension that redacts authorization, bearer, apikey, api_key, apiKey, pin, and token values with `[redacted]`.
- `userStatusLine()` returning user-facing messages for 4 reasons (invalid credentials, stale cache, update/reference refresh failure), null for all others.
- `TvdbDiagnosticsRecorder` interface with `suspend fun record(diagnostic)`.
- `structuredLogFields()` that calls sanitized() first and returns only non-secret string fields.

### TvdbDiagnosticsDataStore.kt
- Concrete `TvdbDiagnosticsRecorder` implementation using Preferences DataStore named `tvdb_diagnostics`.
- Bounded snapshot with 9 category fields: provider decision, fallback reason, update/reference refresh status, airtime reason, poster reason, TMDB skip reason, invalid credential status, stale cache status.
- `record()` calls `sanitized()` before persistence, routes reasons to correct snapshot fields, emits structured Android logs with tag `TvdbReliability`.
- `TvdbDiagnosticsSnapshot` data class exposed via `Flow<TvdbDiagnosticsSnapshot>`.

### TvdbDiagnosticsModule.kt
- Hilt `@Binds` binding from `TvdbDiagnosticsRecorder` to `TvdbDiagnosticsDataStore` in `SingletonComponent`.

## Test Coverage

- **TvdbReliabilityDiagnosticsTest** (12 tests): reason code completeness, secret sanitization (7 patterns), null/non-secret message preservation, user status lines (4 user-facing + 11 non-user-facing), structured log field sanitization, optional field omission, interface shape.
- **TvdbDiagnosticsDataStoreTest** (5 tests): recorder implementation check, secret redaction before persistence, all 9 snapshot field recording, Hilt module binding reflection, structured log sanitization in stored data.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Sanitization regex missed multi-word Authorization Bearer values**
- **Found during:** Task 2 GREEN phase
- **Issue:** Single `\S+` regex only matched the first word after separator, leaving actual secret token exposed in `Authorization: Bearer TOKEN` patterns.
- **Fix:** Split into two patterns: `SECRET_KEY_VALUE_PATTERN` with optional second `\S+` for multi-word values, and `SECRET_BEARER_PATTERN` for standalone `bearer TOKEN`.
- **Files modified:** `TvdbReliabilityDiagnostics.kt`
- **Commit:** `a4903abb9`

### Pre-existing Issues (Out of Scope)

**PlaybackBufferNetworkSettingsTest compilation error:** Uncommitted local changes in 5 playback-related files break test compilation with `Unresolved reference 'resolveEffectiveDiskSpoolStorageLocation'`. Tests were run with these files temporarily stashed. Logged to `deferred-items.md`.

## Decisions Made

1. Sanitization uses two regex patterns to handle both `key=value` and standalone `bearer TOKEN` forms.
2. `NETWORK_CALL_BLOCKED` and `UNKNOWN_UPDATE_EVENT` are logged but not persisted to the bounded snapshot (no snapshot field needed for these transient events).
3. DataStore snapshot fields map multiple related reasons to single keys (e.g., `UPDATE_REFRESH_STARTED/SUCCEEDED/FAILED` all write `lastUpdateRefreshStatus`).

## Known Stubs

None. All implementations are fully wired with real DataStore persistence and no placeholder data.

## Threat Mitigations

| Threat | Mitigation | Status |
|--------|-----------|--------|
| T-10-00-01 (Tampering) | File existence + symbol grep gate before Phase 10 implementation | Verified |
| T-10-00-02 (Info Disclosure) | `sanitized()` redacts 7 secret patterns; tests confirm no leakage | Verified |
| T-10-00-03 (Repudiation) | 15 exact typed reason codes cover all D-11 requirements | Verified |
| T-10-00-04 (DoS) | `TvdbDiagnosticsModule` @Binds recorder before any producer plan | Verified |

## Self-Check: PASSED

- All 6 created files verified on disk
- All 5 commits verified in git log (371de9a30, ded661bf7, a4903abb9, 665bc096d, 2e4a09e96)
