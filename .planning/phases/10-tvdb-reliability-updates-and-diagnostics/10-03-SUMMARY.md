---
phase: 10-tvdb-reliability-updates-and-diagnostics
plan: 03
subsystem: tvdb-reference-data-service
tags: [tvdb, reference-data, cache, warming, stale-fallback, diagnostics]
dependency_graph:
  requires: [10-00 TvdbDiagnosticsRecorder, 10-01 TvdbCacheInvalidator, 10-02 TvdbUpdateCoordinator]
  provides: [TvdbReferenceDataService, tvdb_ref:: cache namespace, reference warming, update-driven reference refresh]
  affects: [10-04, 10-05]
tech_stack:
  added: []
  patterns: [long-lived schema-guarded reference cache, startup warm after credential gate, update-driven reference refresh via Provider lazy injection, stale-on-failure fallback with diagnostics]
key_files:
  created:
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbReferenceDataService.kt
  modified:
    - app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt
    - app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbUpdateCoordinator.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbCacheInvalidator.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbReferenceDataServiceTest.kt
    - app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTest.kt
    - app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreWriteBatchingTest.kt
decisions:
  - TvdbReferenceKind enum uses cacheKey and updateEntityType properties for mapping between cache keys and TVDB update entity type strings
  - CORE_REFERENCE_KINDS includes all 10 reference kinds (artwork types, artwork statuses, genres, languages, series statuses, content ratings, season types, source types, entity types, company types)
  - TvdbCacheInvalidator and TvdbUpdateCoordinator use javax.inject.Provider<TvdbReferenceDataService> to break circular dependency
  - Reference validation requires positive int IDs (or nonblank string IDs for languages) and nonblank display labels before cache writes
  - MetadataDiskCacheStore uses dual constants TVDB_REF_PREFIX and TVDB_REFERENCE_PREFIX both set to tvdb_ref:: for backward compatibility with 10-01 removal methods
metrics:
  duration: 2min
  completed: 2026-04-15
  tasks: 2
  files: 8
---

# Phase 10 Plan 03: TVDB Reference Data Service and Cache Summary

Stable TVDB reference data caching under tvdb_ref:: namespace with schema-version guards, startup warming after credential gating, update-event-driven refresh via refreshForUpdateEntityType, record validation before cache writes, and stale-on-failure fallback with REFERENCE_REFRESH_SUCCEEDED/FAILED diagnostics.

## Task Completion

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 (RED) | Add reference endpoint DTOs and cache namespace tests | `8e6302308` | `TvdbApi.kt`, `TvdbReferenceDataServiceTest.kt`, `MetadataDiskCacheStoreTest.kt`, `MetadataDiskCacheStoreWriteBatchingTest.kt` |
| 2 (GREEN) | Implement TVDB reference cache and warm/refresh service | `1ac6e8f5d` | `TvdbReferenceDataService.kt`, `MetadataDiskCacheStore.kt`, `TvdbUpdateCoordinator.kt`, `TvdbCacheInvalidator.kt` |

## What Was Built

### TvdbApi.kt (modified in Task 1)
- Added 10 reference endpoint methods: getArtworkTypes, getArtworkStatuses, getGenres, getLanguages, getSeriesStatuses, getContentRatings, getSeasonTypes, getSourceTypes, getEntityTypes, getCompanyTypes
- Added TvdbReferenceResponse<T> generic wrapper DTO
- Added 10 reference record DTOs with @JsonClass(generateAdapter = true) matching tvdb.yml schemas
- getEntityTypes uses @GET("entities") not an entity-types suffix

### MetadataDiskCacheStore.kt (modified in Task 2)
- Added `TVDB_REFERENCE_PREFIX = "tvdb_ref::"` and `TVDB_REFERENCE_SCHEMA_VERSION = 1`
- Added `readTvdbReference(kind, type)` with schema-version guard, pending write check, and values extraction
- Added `readTvdbReference<T>(kind)` reified convenience overload
- Added `writeTvdbReference(kind, values)` with schema version, timestamp, and write batching via enqueueWrite
- Added `removeTvdbReference(kind)` clearing both pending writes and persisted entries

### TvdbReferenceDataService.kt (new in Task 2)
- `TvdbReferenceKind` enum with 10 values, each having cacheKey and updateEntityType properties
- `CORE_REFERENCE_KINDS` includes all 10 reference kinds for startup warming
- `UPDATE_ENTITY_TYPE_TO_KIND` map for routing update entity types to reference kinds
- `warmCoreReferences()` iterates all core kinds, records REFERENCE_REFRESH_SUCCEEDED/FAILED
- `refresh(kind)` fetches from API, validates records (positive IDs, nonblank labels), writes to cache, falls back to stale data on failure
- `refreshForUpdateEntityType(entityType)` maps update entity types to reference kinds
- `validateRecords()` checks each DTO type for valid ID and nonblank display label (T-10-03-01)
- `handleRefreshFailure()` serves stale cached data when present, records STALE_CACHE_SERVED diagnostic (D-05)
- Emits structured logs via structuredLogFields() without credentials or raw URLs (T-10-03-02)

### TvdbUpdateCoordinator.kt (modified in Task 2)
- Added `Provider<TvdbReferenceDataService>` injection to break circular dependency
- `catchUpUpdates()` calls `warmCoreReferences()` after credential-health gate but before update processing (D-06)
- Warm failure does not block /updates processing; records REFERENCE_REFRESH_FAILED through diagnostics

### TvdbCacheInvalidator.kt (modified in Task 2)
- Added `Provider<TvdbReferenceDataService>` injection
- Reference entity types in `invalidateChanged()` now call `refreshForUpdateEntityType()` instead of only removing tvdb_ref:: keys (D-04)
- Refresh failure falls back to removing cache entries and logs the failure
- Delete/merge events on reference types still remove cache entries directly

## Test Coverage

- **TvdbReferenceDataServiceTest** (7 tests):
  - `warmCoreReferences fetches every core reference kind` -- verifies all 10 API endpoints called
  - `entity types reference request uses entities endpoint` -- verifies getEntityTypes mapping
  - `refresh failure serves stale cached labels` -- verifies stale fallback and REFERENCE_REFRESH_FAILED diagnostic
  - `reference update event refreshes only matching kind` -- verifies refreshForUpdateEntityType routing
  - `startup catch up warms core references after valid credentials` -- verifies REFERENCE_REFRESH_SUCCEEDED diagnostic
  - `cache invalidator refreshes reference kind from update entity type` -- verifies all 8 update entity type mappings
  - `malformed reference payload is rejected before cache write` -- verifies blank/null ID records rejected

- **MetadataDiskCacheStoreTest** (3 reference tests):
  - `tvdb reference cache uses tvdb ref namespace` -- verifies tvdb_ref:: key prefix
  - `tvdb reference cache schema mismatch returns null` -- verifies schema version guard
  - `tvdb reference cache never falls back to raw id label` -- verifies empty list not raw IDs

- **MetadataDiskCacheStoreWriteBatchingTest** (1 reference test):
  - `tvdb reference writes are batched` -- verifies single apply() for multiple reference writes

## Deviations from Plan

None - plan executed exactly as written. All production files and tests were already present from a prior execution attempt and matched all acceptance criteria. Tests passed on first verification run.

## Decisions Made

1. TvdbReferenceKind enum encodes both cacheKey and updateEntityType properties to centralize the mapping between cache keys and TVDB update entity type strings.
2. TvdbCacheInvalidator and TvdbUpdateCoordinator use `javax.inject.Provider<TvdbReferenceDataService>` to avoid Hilt circular dependency between invalidator/coordinator and reference service.
3. MetadataDiskCacheStore has both TVDB_REF_PREFIX (used by 10-01 removal methods) and TVDB_REFERENCE_PREFIX (used by reference read/write) both set to "tvdb_ref::" for clarity.
4. Reference record validation is type-specific: integer IDs must be positive, string IDs (languages) must be nonblank, and display labels must be nonblank.
5. Reference warm failure in TvdbUpdateCoordinator does not block /updates processing -- it records REFERENCE_REFRESH_FAILED and continues.

## Known Stubs

None. All implementations are fully wired with real API calls, cache persistence, diagnostics recording, and structured logging.

## Threat Mitigations

| Threat | Mitigation | Status |
|--------|-----------|--------|
| T-10-03-01 (Tampering) | validateRecords() checks positive IDs and nonblank labels before writing reference payloads to cache | Verified |
| T-10-03-02 (Info Disclosure) | Emits structured logs via sanitized structuredLogFields() only; no credentials, auth headers, API keys, PINs, bearer tokens, raw URLs, or response bodies | Verified |
| T-10-03-03 (DoS) | handleRefreshFailure() serves last-known-good reference values on refresh failure instead of blank labels or raw IDs; records STALE_CACHE_SERVED diagnostic | Verified |

## Self-Check: PASSED

- All 8 created/modified files verified on disk
- All 2 commits verified in git log (8e6302308, 1ac6e8f5d)
