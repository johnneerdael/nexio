---
phase: 07-tvdb-provider-replacement
plan: 01
subsystem: metadata
tags: [android, kotlin, tvdb, metadata, cache]

requires:
  - phase: 06-tvdb-foundation-and-identity
    provides: TVDB settings, auth, API, token, identity, and settings UI source files
provides:
  - Provider-neutral TV metadata request, enrichment, decision, episode, and season models
  - TV metadata provider diagnostic event names for TVDB success/fallback/skip states
  - TVDB metadata disk cache methods using tvdb:: and tvdb_episode:: namespaces
affects: [07-tvdb-provider-replacement, tvdb-provider-routing, metadata-cache]

tech-stack:
  added: []
  patterns:
    - Provider-neutral Kotlin data contracts under core/tvdb
    - Separate TVDB disk-cache schema fields and key prefixes

key-files:
  created:
    - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataDiagnostics.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataModelsTest.kt
    - app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTvdbTest.kt
  modified:
    - app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt

key-decisions:
  - "Used provider-neutral TvMetadata* contracts instead of reusing TMDB-named public result types."
  - "Kept TVDB cache entries in separate tvdb:: and tvdb_episode:: namespaces with TVDB schema fields."

patterns-established:
  - "TVDB metadata cache keys include TVDB series identity, record kind or season type, language, and provider token where poster output can vary."
  - "TVDB diagnostics use stable eventName strings for provider decisions."

requirements-completed: [PREF-02, PREF-03, META-01, META-02, META-04]

duration: 10 min
completed: 2026-04-15
---

# Phase 07 Plan 01: Provider-Neutral TV Metadata Contracts Summary

**TVDB-ready metadata contracts with separate provider diagnostics and TVDB disk-cache namespaces.**

## Performance

- **Duration:** 10 min
- **Started:** 2026-04-15T03:27:40Z
- **Completed:** 2026-04-15T03:37:25Z
- **Tasks:** 3
- **Files modified:** 5

## Accomplishments

- Verified the Phase 6 TVDB foundation source files exist before any Phase 7 app-source edit.
- Added provider-neutral TV metadata models that preserve nullable TVDB IDs and TVDB-only retained fields without changing `Meta`, `Video`, or `HomeDisplayMetadata`.
- Added TVDB/TMDB provider diagnostics with stable event names for inactive, success, fallback, skipped TMDB TV, and poster-ratings override states.
- Added TVDB enrichment and season episode disk-cache methods with `tvdb::` and `tvdb_episode::` keys and TVDB-specific schema fields.

## Task Commits

Each task was committed atomically:

1. **Task 1: Block unless Phase 6 TVDB foundation source exists** - `15fbe019a` (chore)
2. **Task 2 RED: Provider-neutral model tests** - `cf6217bbd` (test)
3. **Task 2 GREEN: Provider-neutral model contracts** - `b7fb3da32` (feat)
4. **Task 3 RED: TVDB disk-cache namespace tests** - `4033bf799` (test)
5. **Task 3 GREEN: TVDB disk-cache namespace methods** - `4436787ae` (feat)

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt` - Provider-neutral TV metadata request, enrichment, decision, episode, and season models.
- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataDiagnostics.kt` - Provider enum, decision reason event names, and diagnostic event model.
- `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt` - TVDB enrichment and season episode cache read/write methods, prefixes, schema fields, and stale-epoch prefix awareness.
- `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataModelsTest.kt` - Contract tests for diagnostic event names, nullable `seriesTvdbId`, TVDB-only retained fields, and provider-neutral decisions.
- `app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTvdbTest.kt` - Contract tests for TVDB cache key namespaces and schema fields.

## Decisions Made

- Used new `TvMetadataEnrichment` and `TvEpisodeMetadata` types instead of adapting `TmdbEnrichment`, so downstream TVDB code does not leak TMDB naming into public TV provider contracts.
- Added default nullable/list/map values to provider models where appropriate so fallback adapters can omit TVDB-only fields without fabricating values.
- Kept `removeEntriesFromStaleEpochs()` behavior unchanged because global language epoch cleanup is already retired, while making the method aware of TVDB prefixes for the required future cleanup hook.

## Deviations from Plan

None - plan implementation scope stayed within the specified files and contracts.

## Issues Encountered

- Targeted unit-test commands could not complete because `:app:compileArm64DebugUnitTestKotlin` fails in unrelated existing tests before the requested test classes can run. The failing files include profile, search, player settings, Simkl, Trakt, and settings tests outside this plan's owned files.
- `./gradlew compileArm64DebugKotlin` passed after the code changes, proving the app source compiles. The targeted Gradle test commands no longer report missing `TvMetadata*` or `read/writeTvdb*` symbols after implementation.
- Kotlin daemon startup repeatedly reported unsupported `ZGenerational` and fell back to non-daemon compilation; this is existing environment noise also recorded in prior phase verification.

## Known Stubs

None. Nullable/default values in the new provider models are intentional contract defaults for absent provider fields, not UI placeholders or unwired mock data.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Ready for Plan 07-02 to implement TVDB provider data mapping and routing against these contracts. Downstream plans can depend on `TvMetadataDecisionReason` event names and the `MetadataDiskCacheStore` TVDB cache methods. The unit-test compile debt outside this plan still needs cleanup before targeted TVDB unit tests can execute normally.

## Self-Check: PASSED

- Found all created/modified plan files.
- Found all task commits in git history.
- Left `.planning/STATE.md` and `.planning/ROADMAP.md` unstaged and uncommitted; the pre-existing `.planning/STATE.md` dirty state remains owned by the orchestrator.

---
*Phase: 07-tvdb-provider-replacement*
*Completed: 2026-04-15*
