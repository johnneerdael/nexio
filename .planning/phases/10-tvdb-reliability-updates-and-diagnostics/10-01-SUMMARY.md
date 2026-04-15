---
phase: 10-tvdb-reliability-updates-and-diagnostics
plan: 01
subsystem: tvdb-update-processor
tags: [tvdb, updates, cache-invalidation, merge-alias, cursor-ordering]
dependency_graph:
  requires: [10-00 TvdbDiagnosticsRecorder, Phase 6-9 TvdbApi, MetadataDiskCacheStore]
  provides: [TvdbUpdateProcessor, TvdbCacheInvalidator, TvdbUpdateStateStore, TvdbMergeAliasStore, TVDB cache removal methods]
  affects: [10-02, 10-03, 10-04]
tech_stack:
  added: []
  patterns: [Preferences DataStore cursor store, entity-type cache invalidation mapping, merge alias read-path remapping, paged update processing with cursor-after-success ordering]
key_files:
  created:
    - app/src/main/java/com/nexio/tv/data/local/TvdbUpdateStateStore.kt
    - app/src/main/java/com/nexio/tv/data/local/TvdbMergeAliasStore.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbCacheInvalidator.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbUpdateProcessor.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbUpdateProcessorTest.kt
  modified:
    - app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt
    - app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt
decisions:
  - TvdbUpdateProcessor uses overloaded processSince() instead of suspend default parameter (Kotlin limitation)
  - MetadataDiskCacheStore gains three TVDB-specific removal methods (removeTvdbSeriesEntries, removeTvdbEpisodeEntries, removeTvdbRefEntries) using prefix-based SharedPreferences key scanning
  - TvdbMergeAliasStore uses colon-delimited string values for compact DataStore alias storage
  - Malformed events (null entityType, methodInt, or recordId) are skipped with UNKNOWN_UPDATE_EVENT diagnostic, not broad cache purge
metrics:
  duration: 9min
  completed: 2026-04-15
  tasks: 2
  files: 7
---

# Phase 10 Plan 01: TVDB Update Processor Summary

TVDB /updates paged processing with entity-type cache invalidation, merge alias persistence for read-path remapping, and cursor-after-success ordering backed by Preferences DataStore and TvdbDiagnosticsRecorder integration.

## Task Completion

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 (RED) | Add update API DTOs and failing processor tests | `139db4c3d` | `TvdbApi.kt`, `TvdbUpdateProcessorTest.kt` |
| 2 (GREEN) | Implement update state, invalidation, and cursor ordering | `e6e0dca0c`, `b871762ad` | `TvdbUpdateStateStore.kt`, `TvdbMergeAliasStore.kt`, `TvdbCacheInvalidator.kt`, `TvdbUpdateProcessor.kt`, `MetadataDiskCacheStore.kt`, `TvdbUpdateProcessorTest.kt` |

## What Was Built

### TvdbApi.kt (modified)
- Added `getUpdates()` endpoint mapping to `@GET("updates")` with `since`, `type`, and `page` query parameters
- Added `TvdbUpdatesResponse` DTO with status, data list, and links
- Added `TvdbEntityUpdate` DTO with entityType, method, methodInt, recordId, timeStamp, seriesId, mergeToId, mergeToEntityType
- Added `TvdbLinks` DTO for pagination (prev, self, next, totalItems, pageSize)

### TvdbUpdateStateStore.kt (new)
- Preferences DataStore named `tvdb_update_state`
- Stores `last_successful_cursor` (Long), `last_refresh_status` (String), `last_refresh_at_ms` (Long), `last_refresh_error` (sanitized String)
- Exposes `lastSuccessfulCursor: Flow<Long>`, `currentCursor()`, `storeSuccessfulCursor()`, `recordStatus()`

### TvdbMergeAliasStore.kt (new)
- Preferences DataStore named `tvdb_merge_aliases`
- Stores aliases keyed by normalized `entityType:recordId` with target `toEntityType:toId:updatedAtMs`
- Rejects blank entity types, non-positive IDs, and self-maps
- `resolveAlias()` is the read-path contract for later metadata readers
- `removeAlias()` cleans stale aliases on pure delete events

### TvdbCacheInvalidator.kt (new)
- Maps entity types to TVDB cache namespace evictions via MetadataDiskCacheStore
- `series` invalidates tvdb:: and tvdb_episode:: entries for recordId
- `episodes` invalidates episode/series entries for seriesId when present
- `seasons` invalidates series/episode entries for seriesId when present
- `artwork` invalidates series metadata for seriesId when present
- Reference types (artworktypes, genres, languages, content_ratings, seasontypes, sourcetypes, entity_types, company_types) invalidate tvdb_ref:: entries
- Unknown/malformed events record UNKNOWN_UPDATE_EVENT diagnostic, do not purge all TVDB data
- Merge events purge old source entries and call TvdbMergeAliasStore.recordAlias
- Delete events purge source entries and call TvdbMergeAliasStore.removeAlias

### TvdbUpdateProcessor.kt (new)
- `@Inject constructor(api, stateStore, invalidator, diagnosticsRecorder, authService)`
- Fetches /updates pages, dispatches methodInt 1/2 to invalidateChanged, methodInt 3 to invalidateDeletedOrMerged
- Tracks high watermark from non-null timeStamp values
- Calls storeSuccessfulCursor ONLY after all pages and invalidations complete
- On exception, records UPDATE_REFRESH_FAILED without cursor advancement
- Records UPDATE_REFRESH_STARTED/SUCCEEDED/FAILED diagnostics through TvdbDiagnosticsRecorder
- Emits structured Android logs from structuredLogFields() after sanitization

### MetadataDiskCacheStore.kt (modified)
- Added `TVDB_REF_PREFIX = "tvdb_ref::"` constant
- Added `removeTvdbSeriesEntries(seriesId)` targeting `tvdb::$seriesId::` keys
- Added `removeTvdbEpisodeEntries(seriesId)` targeting `tvdb_episode::$seriesId::` keys
- Added `removeTvdbRefEntries(refType)` targeting `tvdb_ref::$refType::` keys
- Shared `removePrefixedEntries()` helper for prefix-based key scanning and removal

## Test Coverage

- **TvdbUpdateProcessorTest** (6 tests):
  - `processSince invalidates changed events before advancing cursor` - verifies invalidation-before-cursor ordering
  - `processSince purges deleted records` - verifies delete events route through invalidateDeletedOrMerged
  - `processSince purges duplicate merge source and records merge target` - verifies merge events with mergeToId
  - `processSince stores duplicate merge alias for read path` - verifies merge alias recording through invalidator
  - `processSince does not advance cursor when invalidation fails` - verifies no cursor advancement on exception
  - `processSince ignores malformed event and records unknown update diagnostic` - verifies UNKNOWN_UPDATE_EVENT diagnostic and continued processing

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Kotlin suspend default parameter not supported**
- **Found during:** Task 2 GREEN phase
- **Issue:** `suspend fun processSince(since: Long = stateStore.currentCursor())` is not valid Kotlin -- suspend calls in default parameter values are unsupported.
- **Fix:** Split into two overloads: `processSince()` (no-arg, calls currentCursor internally) and `processSince(since: Long)`.
- **Files modified:** `TvdbUpdateProcessor.kt`
- **Commit:** `b871762ad`

**2. [Rule 1 - Bug] MockK slot cannot capture multiple diagnostic records**
- **Found during:** Task 1 RED->GREEN transition
- **Issue:** `slot<TvdbReliabilityDiagnostic>()` only captures the last value, but the processor records STARTED, UNKNOWN, and SUCCEEDED diagnostics in one call.
- **Fix:** Replaced `slot` + `capture` with `match { it.reason == TvdbReliabilityReason.UNKNOWN_UPDATE_EVENT }` for targeted verification.
- **Files modified:** `TvdbUpdateProcessorTest.kt`
- **Commit:** `b871762ad`

### Pre-existing Issues (Out of Scope)

**PlaybackBufferNetworkSettings and related playback file changes:** Same pre-existing uncommitted changes in 5 playback-related files as documented in 10-00-SUMMARY. These were stashed before execution. Stash operation created an intermediate "profile work" commit containing plan 10-01 production files alongside playback files.

## Decisions Made

1. TvdbUpdateProcessor uses overloaded methods instead of suspend default parameter due to Kotlin language limitation.
2. MetadataDiskCacheStore adds prefix-based removal using SharedPreferences key scanning rather than individual key tracking.
3. TvdbMergeAliasStore uses colon-delimited string values (`toEntityType:toId:updatedAtMs`) for compact single-key storage per alias.
4. Malformed events skip with UNKNOWN_UPDATE_EVENT diagnostic rather than purging all TVDB data (T-10-01-01 mitigation).

## Known Stubs

None. All implementations are fully wired with real DataStore persistence, cache invalidation, and diagnostics recording.

## Threat Mitigations

| Threat | Mitigation | Status |
|--------|-----------|--------|
| T-10-01-01 (Tampering) | Validate entityType, recordId, timeStamp, and method before mutation; unknown events emit UNKNOWN_UPDATE_EVENT instead of broad purge | Verified |
| T-10-01-02 (DoS) | Advance last_successful_cursor only after all pages and cache invalidations complete; exception path does not advance cursor | Verified |
| T-10-01-03 (Info Disclosure) | Use TvdbReliabilityDiagnostic.sanitized() and structuredLogFields() for refresh errors; never log Authorization headers, API keys, PINs, bearer tokens, full URLs, or raw response bodies | Verified |

## Self-Check: PASSED

- All 7 created/modified files verified on disk
- All 3 commits verified in git log (139db4c3d, e6e0dca0c, b871762ad)
