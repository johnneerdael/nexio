# Phase 10: TVDB Reliability, Updates, and Diagnostics - Research

**Researched:** 2026-04-14 [VERIFIED: current_date]
**Domain:** Kotlin Android TV metadata cache invalidation, TVDB reference caching, graceful provider fallback, WorkManager periodic refresh, diagnostics, and user documentation [VERIFIED: CLAUDE.md; VERIFIED: 10-CONTEXT.md; VERIFIED: codebase inspection]
**Confidence:** HIGH for local cache/settings/Continue Watching patterns and TVDB `/updates` contract; MEDIUM for exact Phase 6-9 implementation class names because the current worktree contains planning artifacts but no production TVDB provider classes yet. [VERIFIED: rg Tvdb app/src/main/java; VERIFIED: .planning/phases/06-tvdb-foundation-and-identity/06-RESEARCH.md; VERIFIED: .planning/phases/09-tvdb-advanced-tv-surfaces/09-RESEARCH.md]

<user_constraints>
## User Constraints (from CONTEXT.md)

Source: copied verbatim from `.planning/phases/10-tvdb-reliability-updates-and-diagnostics/10-CONTEXT.md`. [VERIFIED: 10-CONTEXT.md]

### Locked Decisions
## Implementation Decisions

### Update-aware invalidation
- **D-01:** TVDB `/updates` is the primary freshness driver for TVDB metadata. Store the last successful update cursor, poll `/updates?since=...`, invalidate changed entity IDs, and use cache schema keys, language epochs, provider tokens, and record timestamps as safety checks.
- **D-02:** TVDB delete events should purge affected cache entries. Duplicate merge events should purge old IDs and remap to `mergeToType` / `mergeToId` where TVDB provides those fields.
- **D-03:** TVDB update checks should run as background periodic work plus app-start catch-up. Do not block normal metadata reads on inline `/updates` calls.

### Stable reference-data caching
- **D-04:** Stable TVDB reference data should use long-lived caches, refreshed through `/updates` when relevant reference entity types change and guarded by schema-version escape hatches.
- **D-05:** Reference-data refresh failures should use last-known-good cached data and expose the refresh failure through diagnostics. Stale labels are preferred over blank metadata or raw IDs.
- **D-06:** Once TVDB credentials validate, Nexio should warm core reference data during TVDB setup or startup, then refresh through update signals. Core references include artwork types, genres, languages, statuses, content ratings, season types, source types, entity types, and company types.

### Graceful failure behavior
- **D-07:** During temporary TVDB outages, TV detail and Continue Watching should serve last-known-good TVDB data when present. Use explicit fallback only when cached TVDB data cannot safely satisfy the surface, and record the reason.
- **D-08:** If TVDB credentials become invalid after previously working, keep cached TVDB data as last-known-good, block new TVDB network calls until credentials are fixed, surface invalid status, and use explicit fallback when needed.
- **D-09:** Missing TVDB fields should use field-level fallback with reason codes. Keep TVDB as the record provider, fill only missing fields from safe existing sources where allowed, and record reasons such as `missing_airs_time`, `date_only_gating`, or `poster_ratings_override`.

### Diagnostics and docs
- **D-10:** TVDB diagnostics should be visible in three layers: user-facing status in TVDB settings, detailed provider/cache/fallback diagnostics under Debug, and structured logs.
- **D-11:** Diagnostics must represent provider choice, fallback reason, missing `airsTime`, date-only gating, poster-ratings override, skipped TMDB TV fetches, update refresh status, stale cache served, and invalid credentials.
- **D-12:** User-facing docs should cover TVDB setup, TVDB/TMDB/poster-ratings precedence, exact Continue Watching air-time behavior, date-only fallback, stale-cache behavior, and where to find diagnostics.

### the agent's Discretion
- Exact WorkManager/job scheduling interval for periodic `/updates` checks, as long as startup catch-up and background periodic refresh are both present.
- Exact cache store shape, DTO names, and schema-version numbers.
- Exact diagnostic enum/event names and log tag names, as long as the decided reasons are represented.
- Exact placement of user-facing documentation, as long as setup, precedence, exact timing, fallback, stale-cache behavior, and diagnostics are covered.

### Claude's Discretion
See "the agent's Discretion" above, copied verbatim from CONTEXT.md.

### Deferred Ideas (OUT OF SCOPE)
None - discussion stayed within phase scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| UX-03 | TVDB failures degrade gracefully with validation or diagnostic signals instead of making Continue Watching or TV detail look randomly late, empty, or inconsistent. | Plan last-known-good serving, credential health, typed fallback reasons, settings status, Debug diagnostics, and structured logs. [VERIFIED: REQUIREMENTS.md; VERIFIED: 10-CONTEXT.md; VERIFIED: ContinueWatchingSnapshotService.kt; VERIFIED: TmdbSettingsViewModel.kt] |
| CACHE-02 | TVDB cache invalidation accounts for TVDB update signals or record timestamps so metadata can improve without aggressive refetching. | Use `/updates?since=...` cursor polling, update/delete/merge handling, schema/language/provider-token cache guards, and record `lastUpdated` as safety fallback. [VERIFIED: REQUIREMENTS.md; VERIFIED: 10-CONTEXT.md; VERIFIED: tvdb.yml; CITED: https://github.com/thetvdb/v4-api] |
| CACHE-03 | Stable TVDB reference data such as artwork types, genres, languages, statuses, and content ratings is heavily cached in line with TVDB guidance. | Build a TVDB reference-data cache warmed after credential validation/startup, refreshed by update entity types, and served stale-on-refresh-failure. [VERIFIED: REQUIREMENTS.md; VERIFIED: 10-CONTEXT.md; CITED: https://github.com/thetvdb/v4-api; VERIFIED: tvdb.yml] |
</phase_requirements>

## Project Constraints (from CLAUDE.md and prompt AGENTS instructions)

- Nexio is an Android TV / Fire TV Kotlin app using Jetpack Compose under package `com.nexio.tv`. [VERIFIED: CLAUDE.md]
- Preserve existing architecture and naming patterns; prefer small targeted changes over broad refactors. [VERIFIED: CLAUDE.md]
- Keep domain code free of Android framework dependencies; put Android scheduling behind app/data-layer wrappers. [VERIFIED: CLAUDE.md; VERIFIED: current package layout]
- Do not introduce new libraries or patterns unless clearly justified by the existing codebase. [VERIFIED: CLAUDE.md]
- Use `arm64` for local development unless there is a clear reason not to. [VERIFIED: CLAUDE.md]
- Build/test commands are `./gradlew assembleArm64Debug`, `./gradlew testArm64DebugUnitTest`, targeted `--tests`, and `./gradlew lintArm64Debug`. [VERIFIED: CLAUDE.md]
- No repo-root `AGENTS.md` file exists in this checkout; the prompt-provided AGENTS instructions are scoped to `plugins/compound-engineering/` and matter only if a plan touches that plugin directory. [VERIFIED: cat AGENTS.md; VERIFIED: prompt AGENTS block]
- No project-local `.claude/skills/` or `.agents/skills/` directories exist in this checkout. [VERIFIED: rg --files .claude/skills; VERIFIED: rg --files .agents/skills]
- Do not read or expose local TVDB secrets such as `.thetvdb.apikey`; research used `tvdb.yml`, official docs, and mocked-test patterns instead. [VERIFIED: user prompt; VERIFIED: research actions]

## Summary

Phase 10 should be planned as the reliability layer over the Phase 6-9 TVDB provider work: verify that TVDB settings/auth/identity/router/airtime/advanced-surface code exists, then add update polling, cache invalidation, reference-data warming, stale-cache serving, and diagnostics around those existing provider seams. [VERIFIED: ROADMAP.md; VERIFIED: 10-CONTEXT.md; VERIFIED: rg Tvdb app/src/main/java]

The current worktree still has no production `Tvdb*` provider classes, so the first planning wave must bind to the actual Phase 6-9 implementation branch before editing. The current local patterns to reuse are `MetadataDiskCacheStore` for schema-versioned provider caches, DataStore-backed settings/status screens, `ContinueWatchingSnapshotService` for last-known-good Continue Watching metadata merges, `AirDateGate` for air-date diagnostics, `PosterRatingsUrlResolver` for poster override provenance, and structured Android logs for detailed debug traces. [VERIFIED: MetadataDiskCacheStore.kt; VERIFIED: TmdbSettingsDataStore.kt; VERIFIED: DebugSettingsDataStore.kt; VERIFIED: ContinueWatchingSnapshotService.kt; VERIFIED: AirDateGate.kt; VERIFIED: PosterRatingsUrlResolver.kt]

Official TVDB guidance supports this phase shape: maintain a local copy/cache where possible, monitor `/updates`, handle delete merge metadata, and cache reference endpoints such as artwork types, content ratings, genres, languages, statuses, source types, entity types, and company types for a week or longer. [CITED: https://github.com/thetvdb/v4-api; VERIFIED: tvdb.yml]

**Primary recommendation:** Add `TvdbUpdateCoordinator`, `TvdbReferenceDataStore`, `TvdbCacheInvalidator`, and `TvdbDiagnosticsStore`; schedule coarse `/updates` polling with WorkManager plus app-start catch-up, never inline-block metadata reads on updates, and serve last-known-good TVDB data with typed fallback reasons when TVDB is unavailable or credentials become invalid. [VERIFIED: 10-CONTEXT.md; VERIFIED: MetadataDiskCacheStore.kt; CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest]

## Standard Stack

### Core

| Library / Component | Version | Purpose | Why Standard |
|---------------------|---------|---------|--------------|
| Kotlin / Android Gradle Plugin | Kotlin `2.3.0`, AGP `8.13.2` | Android app implementation and tests | Already configured for the app; no language/runtime change is needed. [VERIFIED: gradle/libs.versions.toml; VERIFIED: app/build.gradle.kts] |
| Hilt | `2.58` | Inject TVDB update, cache, reference, diagnostics, and worker dependencies | Existing services, ViewModels, and network modules use Hilt singletons. [VERIFIED: gradle/libs.versions.toml; VERIFIED: NetworkModule.kt; VERIFIED: TmdbSettingsViewModel.kt] |
| Retrofit + Moshi | Retrofit `2.9.0`, Moshi `1.15.1` | TVDB `/updates` and reference endpoint API calls | Existing provider APIs use Retrofit interfaces and Moshi conversion. [VERIFIED: gradle/libs.versions.toml; VERIFIED: NetworkModule.kt; VERIFIED: TmdbApi.kt] |
| OkHttp | `4.12.0` | Shared TVDB HTTP transport | The app already provides a shared OkHttp client and named provider clients through `NetworkModule`. [VERIFIED: gradle/libs.versions.toml; VERIFIED: NetworkModule.kt] |
| AndroidX DataStore Preferences | `1.1.1` | TVDB credential health, update cursor/status, and diagnostic snapshot state | Existing integration settings and debug flags use Preferences DataStore. [VERIFIED: gradle/libs.versions.toml; VERIFIED: TmdbSettingsDataStore.kt; VERIFIED: DebugSettingsDataStore.kt] |
| `MetadataDiskCacheStore` | app component | TVDB metadata and reference cache namespaces, schema versions, language epoch, provider-token partitioning, batched writes | Existing metadata cache has schema versions, language epoch checks, provider-token keys, stale cleanup, and pending write batching. [VERIFIED: MetadataDiskCacheStore.kt; VERIFIED: MetadataDiskCacheStoreTest.kt; VERIFIED: MetadataDiskCacheStoreWriteBatchingTest.kt] |
| WorkManager | `androidx.work:work-runtime-ktx` `2.11.2` | Durable coarse periodic `/updates` checks with network constraints | Android docs describe `PeriodicWorkRequest` as repeating work with a 15 minute minimum interval and OS-managed inexact execution; Google Maven metadata lists `2.11.2` as current release. [CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest; VERIFIED: Google Maven metadata] |
| Hilt WorkManager integration | `androidx.hilt:hilt-work` `1.3.0` and `androidx.hilt:hilt-compiler` `1.3.0` | Constructor injection for `TvdbUpdateWorker` | Maven metadata shows `1.3.0` as the latest non-alpha `androidx.hilt` WorkManager integration release; use non-alpha unless the app intentionally opts into alpha. [VERIFIED: Google Maven metadata] |

### Supporting

| Library / Component | Version | Purpose | When to Use |
|---------------------|---------|---------|-------------|
| `androidx.work:work-testing` | `2.11.2` | Unit/integration tests for periodic update worker scheduling and constraints | Use when adding WorkManager worker tests. [VERIFIED: Google Maven metadata] |
| JUnit 4 | `4.13.2` | Unit tests | Existing app unit tests use JUnit 4. [VERIFIED: app/build.gradle.kts] |
| kotlinx-coroutines-test | `1.8.1` | Deterministic tests for update processing, stale-cache fallback, and diagnostics flows | Existing repository/ViewModel tests use coroutine test utilities. [VERIFIED: app/build.gradle.kts; VERIFIED: ContinueWatchingTimelineAirDateTest.kt] |
| MockK | `1.13.12` | Mock TVDB services, cache stores, and fallback provider calls | Existing tests use MockK for call-count and behavior assertions. [VERIFIED: app/build.gradle.kts; VERIFIED: TmdbMetadataPerformanceTest.kt] |
| MockWebServer | `4.12.0` | HTTP-level tests for `/updates` pagination, 401, outage, and reference endpoint responses | Available in current test dependencies. [VERIFIED: app/build.gradle.kts] |
| Robolectric | `4.13` | Android framework-dependent tests for settings/status stores and WorkManager bootstrap if needed | Existing Android Context/DataStore tests use Robolectric. [VERIFIED: app/build.gradle.kts; VERIFIED: ProfileDataStoreTest.kt] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| WorkManager periodic polling | App singleton coroutine loop only | App coroutine loops die with process lifetime; Phase 10 requires background periodic work plus startup catch-up, so WorkManager is justified despite the new dependency. [VERIFIED: 10-CONTEXT.md; VERIFIED: NexioApplication.kt; CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest] |
| WorkManager periodic polling | AlarmManager | AlarmManager is appropriate for exact-at-instant Continue Watching air-time refresh from Phase 8, but `/updates` polling is inexact periodic network work. [VERIFIED: 08-RESEARCH.md; CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest] |
| Hard-coded reference labels | Enum constants committed from one API snapshot | TVDB recommends heavy caching reference endpoints and recommends against hard-coding values unless necessary. [CITED: https://github.com/thetvdb/v4-api] |
| Record-level fallback | Field-level fallback with typed reasons | User decisions require TVDB to remain record provider and fill only missing safe fields with reasons such as `missing_airs_time` or `poster_ratings_override`. [VERIFIED: 10-CONTEXT.md] |
| A TVDB caching proxy | Direct client caches now, proxy-compatible design later | v2 OPS requirements defer a dedicated proxy, and the current milestone explicitly does not require users to configure one. [VERIFIED: REQUIREMENTS.md; CITED: https://github.com/thetvdb/v4-api] |

**Installation:**

```kotlin
// gradle/libs.versions.toml
work = "2.11.2" // [VERIFIED: Google Maven metadata]
hiltWork = "1.3.0" // [VERIFIED: Google Maven metadata]

androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
androidx-work-testing = { group = "androidx.work", name = "work-testing", version.ref = "work" }
androidx-hilt-work = { group = "androidx.hilt", name = "hilt-work", version.ref = "hiltWork" }
androidx-hilt-compiler = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "hiltWork" }
```

```kotlin
// app/build.gradle.kts
implementation(libs.androidx.work.runtime.ktx) // [VERIFIED: Gradle catalog pattern]
implementation(libs.androidx.hilt.work) // [VERIFIED: Hilt dependency pattern]
ksp(libs.androidx.hilt.compiler) // [VERIFIED: KSP pattern in app/build.gradle.kts]
testImplementation(libs.androidx.work.testing) // [VERIFIED: test dependency pattern]
```

**Version verification:** This is a Gradle Android project, not an npm project; recommended new Android artifact versions were verified from Google Maven metadata on 2026-04-14, and existing versions were verified from `gradle/libs.versions.toml` and `app/build.gradle.kts`. [VERIFIED: Google Maven metadata; VERIFIED: gradle/libs.versions.toml; VERIFIED: app/build.gradle.kts]

## Architecture Patterns

### Recommended Project Structure

Use the exact Phase 6-9 class names if they differ; names below are planning placeholders until those phases land in source. [VERIFIED: rg Tvdb app/src/main/java; VERIFIED: 06-RESEARCH.md; VERIFIED: 09-RESEARCH.md]

```text
app/src/main/java/com/nexio/tv/
├── core/tvdb/
│   ├── TvdbUpdateCoordinator.kt          # app-start catch-up + worker entrypoint orchestration
│   ├── TvdbUpdateProcessor.kt            # /updates paging, cursor, delete/merge/update handling
│   ├── TvdbCacheInvalidator.kt           # entity-type to cache-key invalidation mapping
│   ├── TvdbReferenceDataService.kt       # warm/read/refresh stable reference endpoints
│   ├── TvdbDiagnostics.kt                # typed reason/event enums and payloads
│   └── TvdbCredentialHealth.kt           # valid/invalid/outage/network-call gating
├── data/local/
│   ├── MetadataDiskCacheStore.kt         # add TVDB metadata/reference namespaces
│   └── TvdbDiagnosticsDataStore.kt       # update status, credential status, last decisions
├── data/remote/api/
│   └── TvdbApi.kt                        # add /updates and reference endpoints if absent
├── workers/
│   └── TvdbUpdateWorker.kt               # WorkManager periodic update check
├── ui/screens/settings/
│   ├── TvdbSettingsScreen.kt             # user-facing status
│   └── DebugSettingsScreen.kt            # detailed diagnostics
└── docs/
    └── nexio-power-user-setup-guide.md   # TVDB setup and precedence docs
```

### Pattern 1: Update Cursor Polling Is Outside Normal Reads

**What:** Store the last fully processed TVDB update cursor, schedule background polling, and run app-start catch-up without blocking `getMeta`, Detail, or Continue Watching reads. [VERIFIED: 10-CONTEXT.md; VERIFIED: MetaRepositoryImpl.kt; VERIFIED: ContinueWatchingSnapshotService.kt]

**When to use:** Use for CACHE-02 and all stale metadata refresh decisions. [VERIFIED: REQUIREMENTS.md]

**Example:**

```kotlin
// Sources: TVDB /updates schema in tvdb.yml and WorkManager periodic guidance.
// [VERIFIED: tvdb.yml; CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest]
suspend fun catchUpUpdates(trigger: TvdbUpdateTrigger): TvdbUpdateResult {
    if (!credentialHealth.canCallTvdb()) {
        diagnostics.record(TvdbDiagnostic.InvalidCredentialsBlocked(trigger))
        return TvdbUpdateResult.Blocked
    }

    val startCursor = updateState.lastSuccessfulCursor()
    val processed = updateProcessor.processSince(startCursor)
    updateState.storeSuccessfulCursor(processed.highWatermark)
    diagnostics.record(TvdbDiagnostic.UpdateRefreshSucceeded(trigger, processed.summary))
    return TvdbUpdateResult.Success(processed.summary)
}
```

### Pattern 2: Process `/updates` Pages Before Advancing Cursor

**What:** Fetch every page returned by `/updates?since=...`, invalidate affected caches, then advance the stored cursor only after all pages and cache mutations complete. [VERIFIED: tvdb.yml; CITED: https://github.com/thetvdb/v4-api]

**When to use:** Use for update, delete, and merge events. [VERIFIED: 10-CONTEXT.md]

**Example:**

```kotlin
// Source: EntityUpdate fields: entityType, methodInt, recordId, timeStamp, seriesId, mergeToId, mergeToEntityType.
// [VERIFIED: tvdb.yml]
for (event in page.data) {
    when (event.methodInt) {
        1, 2 -> invalidator.invalidateChanged(event)
        3 -> invalidator.invalidateDeletedOrMerged(event)
        else -> diagnostics.record(TvdbDiagnostic.UnknownUpdateMethod(event.methodInt))
    }
    highWatermark = maxOf(highWatermark, event.timeStamp ?: highWatermark)
}
```

### Pattern 3: Reference Cache Is Long-Lived, Schema-Guarded, And Stale-Serveable

**What:** Cache reference endpoint payloads under `tvdb_ref::` keys with a schema version, `updatedAtMs`, optional source entity type, and last refresh status; serve cached labels when refresh fails. [VERIFIED: MetadataDiskCacheStore.kt; VERIFIED: 10-CONTEXT.md; CITED: https://github.com/thetvdb/v4-api]

**When to use:** Use for artwork types, artwork statuses, genres, languages, series statuses, content ratings, season types, source types, entity types, company types, and countries when used by timing or display. [VERIFIED: tvdb.yml; VERIFIED: 10-CONTEXT.md]

**Example:**

```kotlin
// Source: MetadataDiskCacheStore schema/language/pending-write pattern.
// [VERIFIED: MetadataDiskCacheStore.kt]
data class TvdbReferenceCacheEntry<T>(
    val schemaVersion: Int,
    val kind: TvdbReferenceKind,
    val updatedAtMs: Long,
    val values: List<T>,
    val lastRefreshError: String? = null
)
```

### Pattern 4: Last-Known-Good Beats Blank UI

**What:** When TVDB network calls fail or credentials turn invalid after previously working, use cached TVDB data if it satisfies the surface; use explicit fallback only when cached TVDB data is missing or insufficient. [VERIFIED: 10-CONTEXT.md; VERIFIED: ContinueWatchingSnapshotService.kt; VERIFIED: HomeViewModelContinueWatching.kt]

**When to use:** Use for TV detail metadata and Continue Watching display/runtime metadata. [VERIFIED: UX-03 in REQUIREMENTS.md]

**Example:**

```kotlin
// Source: ContinueWatchingSnapshotService already merges fetched display metadata with fallback metadata.
// [VERIFIED: ContinueWatchingSnapshotService.kt]
val decision = tvdbProvider.fetchSeries(request)
when {
    decision.cachedValue != null -> Result.Success(
        value = decision.cachedValue,
        diagnostics = listOf(TvdbReason.StaleCacheServed(decision.failureReason))
    )
    decision.fallbackValue != null -> Result.Success(
        value = decision.fallbackValue,
        diagnostics = listOf(TvdbReason.ExplicitFallback(decision.failureReason))
    )
    else -> Result.EmptyWithDiagnostic(decision.failureReason)
}
```

### Pattern 5: Diagnostics Are Typed Results, Not Freeform Strings

**What:** Return provider/cache/fallback decisions as typed values from provider routing and cache layers, then project them into user status, Debug details, and structured logs. [VERIFIED: 10-CONTEXT.md; VERIFIED: DebugSettingsDataStore.kt; VERIFIED: DebugSettingsScreen.kt]

**When to use:** Use for provider choice, fallback reason, missing `airsTime`, date-only gating, poster-ratings override, skipped TMDB TV fetches, update refresh status, stale-cache served, and invalid credentials. [VERIFIED: 10-CONTEXT.md]

**Example:**

```kotlin
// Source: Phase 10 diagnostics list; enum names are planning placeholders.
// [VERIFIED: 10-CONTEXT.md; ASSUMED]
sealed interface TvdbDiagnosticReason {
    data object TvdbProviderChosen : TvdbDiagnosticReason
    data class ExplicitFallback(val reason: String) : TvdbDiagnosticReason
    data object MissingAirsTime : TvdbDiagnosticReason
    data object DateOnlyGating : TvdbDiagnosticReason
    data object PosterRatingsOverride : TvdbDiagnosticReason
    data object TmdbTvFetchSkipped : TvdbDiagnosticReason
    data class UpdateRefreshStatus(val status: String) : TvdbDiagnosticReason
    data object StaleCacheServed : TvdbDiagnosticReason
    data object InvalidCredentials : TvdbDiagnosticReason
}
```

### Anti-Patterns To Avoid

- **Inline `/updates` calls during metadata reads:** Normal TV metadata reads should not wait on update polling. [VERIFIED: 10-CONTEXT.md]
- **Advancing the update cursor before cache invalidation succeeds:** This can permanently skip a changed record. [VERIFIED: tvdb.yml; ASSUMED]
- **Purging all TVDB cache on 401:** The phase explicitly keeps last-known-good cached TVDB data when credentials become invalid after previously working. [VERIFIED: 10-CONTEXT.md]
- **Hard-coding reference IDs instead of caching endpoint payloads:** TVDB recommends heavy caching rather than hard-coding stable endpoint values. [CITED: https://github.com/thetvdb/v4-api]
- **Record-level TMDB fallback for one missing field:** Missing TVDB fields should use field-level fallback where safe and keep TVDB as record provider. [VERIFIED: 10-CONTEXT.md]
- **Treating WorkManager periodic execution as exact:** Android documents periodic work as inexact and subject to battery optimizations; exact airtime scheduling remains Phase 8's alarm path. [CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest; VERIFIED: 08-RESEARCH.md]

## TVDB Update And Reference API Findings

| Topic | Finding | Planning Impact |
|-------|---------|-----------------|
| `/updates` parameters | `/updates` requires `since`, accepts optional `type`, `action`, and `page`, and returns `data`, `status`, and `links`. [VERIFIED: tvdb.yml] | Processor must support pagination and typed filters, but the main cursor should be global unless implementation proves type-specific cursors are needed. [ASSUMED] |
| Update event shape | `EntityUpdate` has `entityType`, `methodInt`, `method`, `recordType`, `recordId`, `timeStamp`, `seriesId`, `mergeToId`, and `mergeToEntityType`. [VERIFIED: tvdb.yml] | Invalidation must map both direct IDs and episode `seriesId` to cached series/season/episode records. [ASSUMED] |
| Create/update/delete semantics | TVDB docs say `methodInt` indicates created, updated, or deleted, and duplicate deletions may include merge target fields. [VERIFIED: tvdb.yml; CITED: https://github.com/thetvdb/v4-api] | Delete events purge caches; duplicate merge events purge old IDs and optionally record remap diagnostics. [VERIFIED: 10-CONTEXT.md] |
| Reference endpoints | `tvdb.yml` includes `/artwork/types`, `/artwork/statuses`, `/genres`, `/languages`, `/series/statuses`, `/content/ratings`, `/seasons/types`, `/sources/types`, `/entities`, `/companies/types`, and `/countries`. [VERIFIED: tvdb.yml] | `TvdbReferenceDataService` should warm the subset required by Phase 7-9 surfaces and keep schema escape hatches. [VERIFIED: 10-CONTEXT.md] |
| Heavy-cache guidance | TVDB official README says artwork types, content ratings, entity types, genres, languages, movie/series statuses, people types, and source types can be cached for a week or longer. [CITED: https://github.com/thetvdb/v4-api] | Use long TTLs and `/updates` refresh triggers; do not aggressively refetch stable references on browsing paths. [VERIFIED: 10-CONTEXT.md] |
| Full copy / proxy guidance | TVDB official README recommends maintaining a database copy or caching proxy for direct end-user usage, and says to monitor `/updates` when maintaining a copy. [CITED: https://github.com/thetvdb/v4-api] | Nexio should stay proxy-compatible but not require a proxy in this milestone. [VERIFIED: REQUIREMENTS.md] |

## Cache Invalidation Map

| TVDB Update Entity Type | Cache Entries To Invalidate | Notes |
|-------------------------|-----------------------------|-------|
| `series` | Series extended/base cache, display metadata cache, Home/Detail/CW TVDB series metadata, TVDB artwork references for that series | `SeriesExtendedRecord` includes `lastUpdated`, `airsTime`, content ratings, genres, season types, status, and artwork fields used by earlier phases. [VERIFIED: tvdb.yml; VERIFIED: 07-RESEARCH.md; VERIFIED: 09-RESEARCH.md] |
| `episodes` | Episode cache, season episode-list cache, Continue Watching timing metadata for affected `seriesId`, runtime/overview/image hydration | `EntityUpdate.seriesId` is only present for episode records, so processor should use it to invalidate parent series-derived season/episode caches. [VERIFIED: tvdb.yml] |
| `seasons` / `seasontypes` | Season-order cache, default season episode-list cache, season-type reference cache | Phase 9 preserves default season type and season-type metadata. [VERIFIED: 09-CONTEXT.md; VERIFIED: tvdb.yml] |
| `artwork` / `artworktypes` | Series artwork cache and artwork-type reference cache | TVDB artwork type IDs come from `/artwork/types`. [VERIFIED: tvdb.yml] |
| `content_ratings` | Reference cache and any mapped age-rating output cache | Phase 9 replaces TV content ratings from TVDB when active. [VERIFIED: 09-CONTEXT.md; VERIFIED: tvdb.yml] |
| `genres` | Genre reference cache and mapped genre output cache | TVDB official guidance lists genres as heavy-cacheable reference data. [CITED: https://github.com/thetvdb/v4-api] |
| `languages` | Language reference cache and language-label output cache | Existing cache already has language epoch behavior; TVDB labels should follow that pattern. [VERIFIED: MetadataDiskCacheStore.kt; VERIFIED: tvdb.yml] |
| `company_types` / `companies` | Company-type reference cache and networks/companies output cache | Phase 9 maps companies/networks into existing `MetaCompany` surfaces. [VERIFIED: 09-CONTEXT.md; VERIFIED: tvdb.yml] |
| `entity_types` / `sourcetypes` | Entity/source reference cache | Phase 10 context includes entity and source types as core references. [VERIFIED: 10-CONTEXT.md; VERIFIED: tvdb.yml] |
| Unknown type | Diagnostics only, no broad purge by default | Unknown types should be logged for follow-up; broad purge risks excessive refetching. [ASSUMED] |

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Periodic update polling | Manual while-loop thread or app-lifetime coroutine scheduler | WorkManager `PeriodicWorkRequest` plus app-start catch-up | WorkManager is the Android component for persistent periodic work; app coroutines do not survive process death. [CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest; VERIFIED: NexioApplication.kt] |
| Exact Continue Watching airtime trigger | WorkManager periodic polling | Phase 8 AlarmManager exact/inexact scheduler | WorkManager periodic work is inexact and has a 15-minute minimum interval. [CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest; VERIFIED: 08-RESEARCH.md] |
| TVDB HTTP stack | Custom HTTP client or generated SDK | Existing Retrofit/Moshi/OkHttp module patterns | Current app provider APIs use Retrofit/Moshi and shared OkHttp. [VERIFIED: NetworkModule.kt; VERIFIED: TmdbApi.kt] |
| Reference-data enums | Hard-coded tables copied from one API response | `TvdbReferenceDataService` cached endpoint payloads | TVDB recommends heavily caching these endpoints and recommends against hard-coding unless necessary. [CITED: https://github.com/thetvdb/v4-api] |
| Cache storage primitives | New unbatched SharedPreferences writes per record | Extend/mirror `MetadataDiskCacheStore` batching/schema/language-provider-token patterns | Existing store already solves schema compatibility, pending write batching, and stale epoch cleanup. [VERIFIED: MetadataDiskCacheStore.kt; VERIFIED: MetadataDiskCacheStoreWriteBatchingTest.kt] |
| Fallback explanations | Freeform strings at call sites | Central typed `TvdbDiagnosticReason` enums mapped to logs/settings/debug UI | Phase 10 requires many specific reasons; typed reasons keep tests and docs aligned. [VERIFIED: 10-CONTEXT.md; ASSUMED] |
| Cache proxy | Require user-hosted proxy in v1.1 | Direct app caches with proxy-compatible service boundary | v2 OPS requirements defer proxy/mirror work. [VERIFIED: REQUIREMENTS.md; CITED: https://github.com/thetvdb/v4-api] |

**Key insight:** The phase's hard part is preserving trust in existing UI while data freshness changes in the background. Update polling, reference refresh, and credential health must change cache eligibility and diagnostics, not synchronously blank TV detail or Continue Watching rows. [VERIFIED: 10-CONTEXT.md; VERIFIED: ContinueWatchingSnapshotService.kt]

## Common Pitfalls

### Pitfall 1: Cursor Advancement Before Full Processing

**What goes wrong:** Nexio stores a new update cursor after fetching a page but before all invalidations complete, permanently missing cache invalidation for failed events. [VERIFIED: tvdb.yml; ASSUMED]

**Why it happens:** `/updates` is paginated and event processing touches multiple cache namespaces. [VERIFIED: tvdb.yml; VERIFIED: MetadataDiskCacheStore.kt]

**How to avoid:** Keep a local high-watermark in memory, process all pages, invalidate caches, then write `lastSuccessfulCursor` once. [VERIFIED: 10-CONTEXT.md; ASSUMED]

**Warning signs:** Tests assert API call success but not cursor write ordering on cache invalidation failure. [ASSUMED]

### Pitfall 2: Cache Purge On Invalid Credentials

**What goes wrong:** A 401 response clears cached TVDB metadata and makes TV detail or Continue Watching look empty after a previously valid setup. [VERIFIED: 10-CONTEXT.md]

**Why it happens:** Credential invalidation is treated as "TVDB disabled" rather than "network calls blocked but last-known-good cache still usable." [VERIFIED: 10-CONTEXT.md]

**How to avoid:** Store credential health separately from cache content, block new TVDB calls while invalid, serve last-known-good cache where safe, and surface invalid status in TVDB settings. [VERIFIED: 10-CONTEXT.md]

**Warning signs:** Tests for 401 assert cache clear or check only validation error state without asserting cached display survives. [ASSUMED]

### Pitfall 3: Reference Refresh Failure Blanks Labels

**What goes wrong:** Genres, content ratings, languages, or artwork type labels disappear when a reference endpoint refresh fails. [VERIFIED: 10-CONTEXT.md]

**Why it happens:** Reference reads require a fresh network fetch instead of serving stale cached values. [VERIFIED: 10-CONTEXT.md; CITED: https://github.com/thetvdb/v4-api]

**How to avoid:** Serve stale reference data with a diagnostic refresh-failure status and schema-version escape hatch. [VERIFIED: 10-CONTEXT.md]

**Warning signs:** Mapper tests expect raw IDs or blank labels after a mocked reference outage. [ASSUMED]

### Pitfall 4: WorkManager Treated As Exact Timing

**What goes wrong:** `/updates` polling or Continue Watching recheck expectations assume periodic work fires at an exact time. [CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest]

**Why it happens:** WorkManager periodic work is durable but explicitly inexact and subject to OS constraints. [CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest]

**How to avoid:** Use WorkManager for coarse `/updates` polling only; use Phase 8's AlarmManager scheduler for exact Continue Watching availability instants. [VERIFIED: 08-RESEARCH.md; CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest]

**Warning signs:** Phase 10 tests assert an exact WorkManager fire time rather than eventual worker invocation under constraints. [ASSUMED]

### Pitfall 5: Diagnostics Drift Across Layers

**What goes wrong:** Provider router logs one reason, Debug UI shows another, and docs describe a third behavior. [VERIFIED: 10-CONTEXT.md]

**Why it happens:** Reasons are emitted as unstructured string messages at each call site. [ASSUMED]

**How to avoid:** Define a single `TvdbDiagnosticReason` model and adapt it to settings summary, Debug detail, and log payloads. [VERIFIED: 10-CONTEXT.md; ASSUMED]

**Warning signs:** New tests assert log substrings instead of typed reason fields. [ASSUMED]

### Pitfall 6: Field-Level Fallback Becomes Provider Merge

**What goes wrong:** TVDB success paths silently mix TMDB record data into the normal provider result and reintroduce duplicate TMDB TV fetches. [VERIFIED: REQUIREMENTS.md; VERIFIED: 07-CONTEXT.md; VERIFIED: 10-CONTEXT.md]

**Why it happens:** Missing TVDB fields are filled by ad hoc TMDB calls instead of safe existing/cached source data. [ASSUMED]

**How to avoid:** Only apply field-level fallback from already available safe data unless the provider router has explicitly chosen TMDB fallback with a reason. [VERIFIED: 10-CONTEXT.md; VERIFIED: 07-CONTEXT.md]

**Warning signs:** A mapper calls `TmdbMetadataService.fetchEnrichment` for a missing TVDB field on a TVDB-success path. [VERIFIED: TmdbMetadataService.kt; VERIFIED: 07-RESEARCH.md]

### Pitfall 7: Delete/Merge Events Only Purge Direct IDs

**What goes wrong:** A deleted duplicate series or episode leaves stale cache rows under old IDs while UI continues to show outdated metadata. [VERIFIED: tvdb.yml; VERIFIED: 10-CONTEXT.md]

**Why it happens:** Update handling ignores `mergeToId`, `mergeToEntityType`, or episode `seriesId`. [VERIFIED: tvdb.yml]

**How to avoid:** Purge old IDs, invalidate parent series caches for episode events, and record merge remaps when TVDB provides a target. [VERIFIED: 10-CONTEXT.md; VERIFIED: tvdb.yml]

**Warning signs:** Tests cover update events but not delete-with-merge events. [ASSUMED]

## Code Examples

Verified patterns and recommended shapes for planning:

### WorkManager Update Worker

```kotlin
// Sources: AndroidX WorkManager periodic work and existing Hilt/KSP patterns.
// [CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest; VERIFIED: app/build.gradle.kts]
@HiltWorker
class TvdbUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val coordinator: TvdbUpdateCoordinator
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return when (coordinator.catchUpUpdates(TvdbUpdateTrigger.PeriodicWorker)) {
            is TvdbUpdateResult.Success -> Result.success()
            TvdbUpdateResult.Blocked -> Result.success()
            is TvdbUpdateResult.RetryableFailure -> Result.retry()
            is TvdbUpdateResult.FatalFailure -> Result.failure()
        }
    }
}
```

### Unique Periodic Scheduling

```kotlin
// Source: WorkManager unique periodic API and PeriodicWorkRequest constraints.
// [CITED: https://developer.android.com/reference/androidx/work/WorkManager; CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest]
val request = PeriodicWorkRequestBuilder<TvdbUpdateWorker>(
    repeatInterval = 12,
    repeatIntervalTimeUnit = TimeUnit.HOURS
).setConstraints(
    Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
).addTag("tvdb_updates").build()

WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "tvdb_updates",
    ExistingPeriodicWorkPolicy.UPDATE,
    request
)
```

### Update Event Invalidation

```kotlin
// Source: EntityUpdate schema and Phase 10 merge/delete decisions.
// [VERIFIED: tvdb.yml; VERIFIED: 10-CONTEXT.md]
fun invalidateDeletedOrMerged(event: TvdbEntityUpdate) {
    metadataCache.removeTvdbEntity(event.entityType, event.recordId)
    event.seriesId?.let(metadataCache::removeTvdbSeriesDerivedEntries)

    if (event.mergeToId != null && !event.mergeToEntityType.isNullOrBlank()) {
        diagnostics.record(
            TvdbDiagnostic.MergeRedirected(
                fromType = event.entityType,
                fromId = event.recordId,
                toType = event.mergeToEntityType,
                toId = event.mergeToId
            )
        )
    }
}
```

### Reference Data Read With Stale Fallback

```kotlin
// Source: Phase 10 stale-reference decision and MetadataDiskCacheStore schema pattern.
// [VERIFIED: 10-CONTEXT.md; VERIFIED: MetadataDiskCacheStore.kt]
suspend fun genres(): TvdbReferenceResult<TvdbGenre> {
    val cached = referenceStore.read(TvdbReferenceKind.Genres)
    return runCatching {
        api.getAllGenres(auth.bearer()).body()?.data.orEmpty()
    }.fold(
        onSuccess = { fresh ->
            referenceStore.write(TvdbReferenceKind.Genres, fresh)
            TvdbReferenceResult.Fresh(fresh)
        },
        onFailure = { error ->
            diagnostics.record(TvdbDiagnostic.ReferenceRefreshFailed("genres", error.safeName()))
            TvdbReferenceResult.Stale(cached.orEmpty())
        }
    )
}
```

### Diagnostic Projection

```kotlin
// Source: Phase 10 three-layer diagnostics decision.
// [VERIFIED: 10-CONTEXT.md]
fun TvdbDiagnosticReason.toUserStatus(): String? = when (this) {
    TvdbDiagnosticReason.InvalidCredentials -> "TVDB credentials need attention"
    is TvdbDiagnosticReason.UpdateRefreshStatus -> "TVDB cache refresh: $status"
    else -> null
}

fun TvdbDiagnosticReason.toDebugLine(): String = when (this) {
    TvdbDiagnosticReason.PosterRatingsOverride -> "Poster came from poster-ratings"
    TvdbDiagnosticReason.TmdbTvFetchSkipped -> "Skipped TMDB TV fetch because TVDB supplied this surface"
    TvdbDiagnosticReason.MissingAirsTime -> "Exact air time unavailable: missing airsTime"
    TvdbDiagnosticReason.DateOnlyGating -> "Continue Watching used date-only gating"
    else -> toString()
}
```

## State Of The Art

| Old Approach | Current Approach | When Changed / Verified | Impact |
|--------------|------------------|--------------------------|--------|
| Blind TTL-only provider cache | TVDB `/updates` cursor invalidation plus record timestamp/schema/language/provider-token guards | Phase 10 decision and TVDB official README verified 2026-04-14. [VERIFIED: 10-CONTEXT.md; CITED: https://github.com/thetvdb/v4-api] | Planner should schedule background update processing and invalidate only affected TVDB cache namespaces. |
| Fresh-reference-or-blank labels | Long-lived reference cache with stale-on-failure behavior | TVDB heavy-cache guidance verified 2026-04-14. [CITED: https://github.com/thetvdb/v4-api; VERIFIED: 10-CONTEXT.md] | Planner should test stale reference labels survive endpoint outages. |
| Silent provider fallback | Typed provider/cache/fallback diagnostics in settings, Debug UI, and logs | Phase 10 decision verified 2026-04-14. [VERIFIED: 10-CONTEXT.md] | Planner should require diagnostics assertions, not just UI behavior assertions. |
| App-lifetime coroutine polling | WorkManager periodic worker plus app-start catch-up | AndroidX WorkManager docs and current app startup pattern verified 2026-04-14. [CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest; VERIFIED: NexioApplication.kt; VERIFIED: StartupSyncService.kt] | Planner should add justified WorkManager dependencies or explicitly choose a weaker fallback. |
| Purge on provider invalid | Block TVDB calls on invalid credentials while retaining last-known-good cache | Phase 10 decision verified 2026-04-14. [VERIFIED: 10-CONTEXT.md] | Planner should separate credential health from cache retention. |

**Deprecated/outdated:**
- Using TMDB TV enrichment as an implicit fallback after TVDB success is outdated for this milestone; fallback must be explicit and observable. [VERIFIED: REQUIREMENTS.md; VERIFIED: 07-CONTEXT.md; VERIFIED: 10-CONTEXT.md]
- Requiring fresh TVDB reference endpoint success before labels render is outdated relative to TVDB's heavy-cache guidance and Phase 10 stale-label decision. [CITED: https://github.com/thetvdb/v4-api; VERIFIED: 10-CONTEXT.md]
- Relying only on local `lastUpdated` timestamps is weaker than `/updates` because Phase 10 locks `/updates` as the primary freshness driver. [VERIFIED: 10-CONTEXT.md; VERIFIED: tvdb.yml]

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `androidx.hilt:hilt-work` `1.3.0` is the preferred non-alpha Hilt WorkManager integration version even though Maven metadata lists `1.4.0-alpha01` as latest. | Standard Stack | If the project accepts alpha dependencies, planner may choose `1.4.0-alpha01`; otherwise stable `1.3.0` avoids alpha risk. |
| A2 | A single global TVDB update cursor is preferable to per-entity-type cursors for the first implementation. | Architecture Patterns / TVDB Findings | If `/updates` paging or rate behavior makes global polling too broad, planner may need type-specific cursors. |
| A3 | A 12-hour WorkManager interval is a reasonable starting point for update polling. | Code Examples | If product wants faster freshness or TVDB API policy imposes tighter limits, planner should adjust interval. |
| A4 | Unknown update entity types should produce diagnostics but not broad cache purge by default. | Cache Invalidation Map | If stale data risk is judged higher than refetch cost, planner may choose broader invalidation for unknown entity types. |
| A5 | Reference cache can live in or beside `MetadataDiskCacheStore` rather than a new Room database. | Architecture Patterns | If reference payload size grows beyond SharedPreferences comfort, planner may need a small file/Room-backed store. |

## Open Questions (RESOLVED)

1. **Has Phase 6-9 source landed on the execution branch?** [VERIFIED: rg Tvdb app/src/main/java]
   - What we know: Planning artifacts for Phases 6-9 exist, but current production source only has incidental `tvdb` references in TMDB, Simkl, and poster-ratings code. [VERIFIED: rg Tvdb app/src/main/java; VERIFIED: .planning/phases/06-tvdb-foundation-and-identity/06-RESEARCH.md]
   - Resolution: Plan `10-00` is the required Wave 0 binding gate. Execution must stop with `CHECKPOINT REACHED` if Phase 6-9 source files are absent; later plans read `10-BINDINGS.md` for exact class names. [VERIFIED: 10-00-PLAN.md]

2. **Will the project accept WorkManager as a new dependency?** [VERIFIED: CLAUDE.md; VERIFIED: app/build.gradle.kts]
   - What we know: WorkManager is not currently in the app dependency catalog, but Phase 10 requires background periodic update checks. [VERIFIED: app/build.gradle.kts; VERIFIED: 10-CONTEXT.md]
   - Resolution: Use WorkManager in plan `10-02` because D-03 requires background periodic update checks plus app-start catch-up; app-start-only catch-up would not satisfy the locked decision. [VERIFIED: 10-02-PLAN.md; CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest]

3. **Which diagnostics history depth should Debug settings retain?** [VERIFIED: DebugSettingsDataStore.kt]
   - What we know: Current Debug settings primarily store toggles, not a diagnostic event ring buffer. [VERIFIED: DebugSettingsDataStore.kt; VERIFIED: DebugSettingsScreen.kt]
   - Resolution: Plan a bounded current snapshot, not a ring buffer. Plan `10-05` stores last provider decision, fallback, update refresh, reference refresh, air-time, poster, skipped TMDB, invalid credential, and stale-cache status fields. [VERIFIED: 10-05-PLAN.md]

4. **Where should user-facing TVDB docs live?** [VERIFIED: docs/nexio-power-user-setup-guide.md; VERIFIED: docs/nexio-features-list.md]
   - What we know: `docs/nexio-power-user-setup-guide.md` currently recommends TMDB as the primary metadata setup and does not describe TVDB setup/precedence. [VERIFIED: docs/nexio-power-user-setup-guide.md]
   - Resolution: Update the existing power-user setup guide and feature list in plan `10-06`; do not create a separate TVDB doc unless the executor finds the existing docs cannot stay readable. [VERIFIED: 10-CONTEXT.md; VERIFIED: 10-06-PLAN.md]

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|-------------|-----------|---------|----------|
| JDK | Gradle build/tests | Yes | OpenJDK `17.0.18` | None needed. [VERIFIED: java -version] |
| Gradle wrapper | Build/test/lint | Yes | Gradle `8.13` | None needed. [VERIFIED: ./gradlew --version] |
| Android SDK / adb | Optional device checks | Yes | `/Users/jneerdael/Library/Android/sdk/platform-tools/adb` | JVM/Robolectric tests remain available. [VERIFIED: command -v adb] |
| `curl` | Official metadata checks and optional API smoke | Yes | `/usr/bin/curl` | MockWebServer for automated tests. [VERIFIED: command -v curl; VERIFIED: app/build.gradle.kts] |
| `tvdb.yml` | TVDB local API contract | Yes | TVDB API V4 spec, local file | Official GitHub docs as fallback. [VERIFIED: tvdb.yml; CITED: https://github.com/thetvdb/v4-api] |
| WorkManager dependency | Periodic `/updates` worker | Not installed in project | Recommended `2.11.2` | Add Gradle dependencies; app-start catch-up alone is weaker. [VERIFIED: app/build.gradle.kts; VERIFIED: Google Maven metadata] |
| `tvdb-cli` | Optional live API probing | No | - | Use `tvdb.yml`, Retrofit tests, MockWebServer, and no-secret local fixtures. [VERIFIED: command -v tvdb-cli; VERIFIED: user prompt] |
| Live TVDB credential | Manual live validation only | Not inspected | - | Use mocked tests and checked-in `tvdb.yml`; do not read secrets during research. [VERIFIED: user prompt; VERIFIED: research actions] |

**Missing dependencies with no fallback:**
- None for planning and JVM-unit-testable implementation; WorkManager is missing but has a clear Gradle add path. [VERIFIED: app/build.gradle.kts; VERIFIED: Google Maven metadata]

**Missing dependencies with fallback:**
- `tvdb-cli` is missing; use `tvdb.yml`, MockWebServer, and Retrofit tests. [VERIFIED: command -v tvdb-cli; VERIFIED: tvdb.yml; VERIFIED: app/build.gradle.kts]
- Live TVDB credentials are not required for automated verification; use mocks by default. [VERIFIED: user prompt; VERIFIED: app/build.gradle.kts]

## Validation Architecture

Validation is enabled because `.planning/config.json` does not set `workflow.nyquist_validation` to `false`. [VERIFIED: .planning/config.json]

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 4 `4.13.2`, MockK `1.13.12`, kotlinx-coroutines-test `1.8.1`, MockWebServer `4.12.0`, Robolectric `4.13`, and recommended WorkManager testing `2.11.2`. [VERIFIED: app/build.gradle.kts; VERIFIED: Google Maven metadata] |
| Config file | Gradle Kotlin DSL via `build.gradle.kts`, `app/build.gradle.kts`, and `gradle/libs.versions.toml`. [VERIFIED: build.gradle.kts; VERIFIED: app/build.gradle.kts; VERIFIED: gradle/libs.versions.toml] |
| Quick run command | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.*" --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreTest"` adjusted to actual Phase 6-9 package names. [VERIFIED: CLAUDE.md; ASSUMED] |
| Full suite command | `./gradlew testArm64DebugUnitTest` [VERIFIED: CLAUDE.md] |

### Phase Requirements -> Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| UX-03 | TVDB outage serves cached TV detail metadata and records `stale_cache_served` instead of blanking fields. | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbGracefulFallbackTest"` | No - Wave 0. [VERIFIED: rg Tvdb app/src/test/java] |
| UX-03 | Invalid credentials block new TVDB network calls, keep last-known-good cache, surface settings status, and use explicit fallback only when needed. | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbCredentialHealthTest" --tests "com.nexio.tv.ui.screens.settings.TvdbSettingsViewModelTest"` | No - Wave 0. [VERIFIED: rg Tvdb app/src/test/java] |
| UX-03 | Diagnostics explain provider choice, fallback reason, missing `airsTime`, date-only gating, poster-ratings override, skipped TMDB TV fetches, update status, stale cache, and invalid credentials. | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbDiagnosticsTest"` | No - Wave 0. [VERIFIED: 10-CONTEXT.md; VERIFIED: rg Tvdb app/src/test/java] |
| CACHE-02 | `/updates` polling handles update/create/delete/merge events and invalidates affected series/episode/reference cache keys before advancing cursor. | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbUpdateProcessorTest"` | No - Wave 0. [VERIFIED: tvdb.yml; VERIFIED: rg Tvdb app/src/test/java] |
| CACHE-02 | WorkManager periodic update worker is uniquely scheduled with network constraints and app-start catch-up uses the same coordinator. | unit/Robolectric | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbUpdateSchedulingTest"` | No - Wave 0. [VERIFIED: app/build.gradle.kts; VERIFIED: 10-CONTEXT.md] |
| CACHE-03 | Reference cache warms core reference endpoints after valid credentials and serves cached labels when refresh fails. | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbReferenceDataServiceTest"` | No - Wave 0. [VERIFIED: tvdb.yml; CITED: https://github.com/thetvdb/v4-api] |
| CACHE-03 | Stable reference schema version changes invalidate old reference payloads without raw IDs leaking into display. | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreTest"` | Existing file, needs TVDB cases. [VERIFIED: MetadataDiskCacheStoreTest.kt] |

### Sampling Rate

- **Per task commit:** Run the narrow test class for the touched update/cache/reference/diagnostic component. [VERIFIED: CLAUDE.md; ASSUMED]
- **Per wave merge:** Run `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.*"` plus touched settings/cache/Continue Watching tests after Phase 6-9 package names are known. [VERIFIED: CLAUDE.md; ASSUMED]
- **Phase gate:** Run `./gradlew testArm64DebugUnitTest`, `./gradlew assembleArm64Debug`, and `./gradlew lintArm64Debug`. [VERIFIED: CLAUDE.md]

### Wave 0 Gaps

- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbUpdateProcessorTest.kt` - covers CACHE-02 `/updates` pagination, update/delete/merge invalidation, and cursor ordering. [VERIFIED: tvdb.yml]
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbReferenceDataServiceTest.kt` - covers CACHE-03 warming, stale-on-failure, schema guard, and update-triggered refresh. [VERIFIED: tvdb.yml; CITED: https://github.com/thetvdb/v4-api]
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbGracefulFallbackTest.kt` - covers UX-03 last-known-good cache and explicit fallback behavior. [VERIFIED: 10-CONTEXT.md]
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbDiagnosticsTest.kt` - covers all required diagnostic reason codes. [VERIFIED: 10-CONTEXT.md]
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbCredentialHealthTest.kt` - covers invalid credential network-call blocking without cache purge. [VERIFIED: 10-CONTEXT.md]
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbUpdateSchedulingTest.kt` - covers WorkManager unique periodic scheduling and app-start catch-up coordinator wiring if WorkManager is added. [CITED: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest; ASSUMED]
- [ ] Extend `app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTest.kt` and `MetadataDiskCacheStoreWriteBatchingTest.kt` for TVDB cache/reference namespace behavior. [VERIFIED: existing test files]
- [ ] Extend settings/docs tests or add static assertions if the project has resource-copy tests after Phase 6 adds TVDB strings. [VERIFIED: strings.xml; ASSUMED]

## Security Domain

Security enforcement is enabled by default because `.planning/config.json` does not set `security_enforcement` to `false`. [VERIFIED: .planning/config.json]

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|------------------|
| V2 Authentication | Yes | Consume Phase 6 TVDB auth/token service; never log API key, PIN, bearer token, or authorization headers. [VERIFIED: 06-RESEARCH.md; VERIFIED: 10-CONTEXT.md] |
| V3 Session Management | Yes | Treat invalid credentials/token failures as credential health state; block new TVDB network calls until fixed while retaining last-known-good cache. [VERIFIED: 10-CONTEXT.md] |
| V4 Access Control | Low | WorkManager worker should only perform app-internal cache refresh using existing local credentials and should not expose a public IPC surface. [VERIFIED: Android app architecture; ASSUMED] |
| V5 Input Validation | Yes | Validate `/updates` entity types, IDs, timestamps, merge targets, reference payloads, URLs, language tags, and diagnostic fields before cache mutations. [VERIFIED: tvdb.yml; VERIFIED: MetadataDiskCacheStore.kt] |
| V6 Cryptography | Yes for secrets | Do not hand-roll crypto; credential storage remains Phase 6 secret-backed settings/sync and local status stores must not contain raw secrets. [VERIFIED: 06-RESEARCH.md; VERIFIED: 10-CONTEXT.md] |

### Known Threat Patterns For TVDB Reliability

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| TVDB API key/PIN/token in logs, cache keys, diagnostics, or docs | Information Disclosure | Store only credential health/status in diagnostics, sanitize logs, and do not include raw auth data in cache keys. [VERIFIED: 06-RESEARCH.md; VERIFIED: user prompt] |
| Malformed `/updates` event causes broad cache corruption | Tampering / Denial of Service | Validate IDs and entity types, ignore unknown unsafe events with diagnostics, and advance cursor only after successful processing. [VERIFIED: tvdb.yml; ASSUMED] |
| Invalid credentials wipe cached metadata | Denial of Service | Separate credential health from cache retention and serve last-known-good data where safe. [VERIFIED: 10-CONTEXT.md] |
| WorkManager worker re-auth storm | Denial of Service | Use Phase 6 token cache and credential-health gate; return success when blocked by invalid credentials rather than retry-looping. [VERIFIED: 06-RESEARCH.md; VERIFIED: 10-CONTEXT.md] |
| Silent fallback hides provider privacy/performance regressions | Repudiation / Information Disclosure | Emit typed diagnostics for fallback and skipped TMDB TV fetches, and test representative normal-success paths. [VERIFIED: REQUIREMENTS.md; VERIFIED: 07-CONTEXT.md; VERIFIED: 10-CONTEXT.md] |

## Sources

### Primary (HIGH confidence)

- `.planning/phases/10-tvdb-reliability-updates-and-diagnostics/10-CONTEXT.md` - locked Phase 10 decisions, diagnostics, fallback, cache, and docs scope. [VERIFIED: file read]
- `.planning/REQUIREMENTS.md` - UX-03, CACHE-02, CACHE-03 descriptions and v2 OPS deferrals. [VERIFIED: file read]
- `.planning/ROADMAP.md` - Phase 10 goal, dependencies, and success criteria. [VERIFIED: file read]
- `.planning/STATE.md` - milestone status and Phase 10 blocker note. [VERIFIED: file read]
- `CLAUDE.md` - project architecture, dependency, and verification constraints. [VERIFIED: file read]
- `tvdb.yml` - local TVDB OpenAPI contract for `/updates`, `EntityUpdate`, reference endpoints, `SeriesExtendedRecord.lastUpdated`, `airsTime`, and reference schemas. [VERIFIED: file read]
- `https://github.com/thetvdb/v4-api` - official TVDB API README for `/updates`, merge/delete guidance, caching proxy/full-copy guidance, and reference endpoint heavy caching. [CITED: official GitHub]
- `https://developer.android.com/reference/androidx/work/PeriodicWorkRequest` - AndroidX periodic work behavior, minimum interval, and inexact execution. [CITED: Android Developers]
- `https://developer.android.com/reference/androidx/work/WorkManager` - unique periodic work API reference. [CITED: Android Developers]
- Google Maven metadata for `androidx.work:work-runtime-ktx`, `androidx.work:work-testing`, `androidx.hilt:hilt-work`, and `androidx.hilt:hilt-compiler`. [VERIFIED: curl to dl.google.com]
- `MetadataDiskCacheStore.kt`, `MetadataDiskCacheStoreTest.kt`, and `MetadataDiskCacheStoreWriteBatchingTest.kt` - cache schema, language epoch, provider token, stale cleanup, and write batching patterns. [VERIFIED: file read]
- `ContinueWatchingSnapshotService.kt` and `ContinueWatchingSnapshotStore.kt` - last-known-good display metadata merge and snapshot persistence patterns. [VERIFIED: file read]
- `AirDateGate.kt`, `AirDateGateTest.kt`, and `ContinueWatchingTimelineAirDateTest.kt` - current date-only/precise gate and timer tests. [VERIFIED: file read]
- `DebugSettingsDataStore.kt`, `DebugSettingsViewModel.kt`, and `DebugSettingsScreen.kt` - Debug settings storage/UI pattern. [VERIFIED: file read]
- `TmdbSettingsDataStore.kt`, `TmdbSettingsViewModel.kt`, `TmdbSettingsScreen.kt`, and `SettingsScreen.kt` - integration settings and validation UI pattern. [VERIFIED: file read]
- `PosterRatingsUrlResolver.kt` - poster-ratings override behavior and TVDB ID support for TopPosters. [VERIFIED: file read]
- `docs/nexio-power-user-setup-guide.md` and `docs/nexio-features-list.md` - current user-facing docs that need TVDB updates. [VERIFIED: file read]

### Secondary (MEDIUM confidence)

- `.planning/phases/06-tvdb-foundation-and-identity/06-RESEARCH.md`, `07-RESEARCH.md`, `08-RESEARCH.md`, and `09-RESEARCH.md` - prior phase planned architecture and constraints; exact code is absent in current worktree. [VERIFIED: file read; VERIFIED: rg Tvdb app/src/main/java]
- Android WorkManager topic pages were searched/opened, but the most useful stable citations were the API references above. [CITED: Android Developers]

### Tertiary (LOW confidence)

- None used as authoritative implementation sources. [VERIFIED: research process]

## Metadata

**Confidence breakdown:**
- Standard stack: MEDIUM - existing app stack is verified, and WorkManager versions are verified, but adding WorkManager is a justified new dependency requiring planner/user acceptance under CLAUDE.md. [VERIFIED: gradle/libs.versions.toml; VERIFIED: app/build.gradle.kts; VERIFIED: Google Maven metadata; VERIFIED: CLAUDE.md]
- Architecture: MEDIUM - local cache/settings/diagnostic/Continue Watching seams are verified, but exact TVDB provider classes from Phases 6-9 are not present in current source. [VERIFIED: codebase inspection; VERIFIED: rg Tvdb app/src/main/java]
- TVDB API contract: HIGH - `/updates`, reference endpoints, `EntityUpdate`, and update semantics were verified from local `tvdb.yml` and official GitHub README. [VERIFIED: tvdb.yml; CITED: https://github.com/thetvdb/v4-api]
- Pitfalls: HIGH for cursor/cache/credential/reference-diagnostic risks grounded in Phase 10 decisions and local code; MEDIUM for WorkManager/Hilt implementation details until dependency acceptance is decided. [VERIFIED: 10-CONTEXT.md; VERIFIED: MetadataDiskCacheStore.kt; CITED: Android Developers]

**Research date:** 2026-04-14 [VERIFIED: current_date]
**Valid until:** 2026-05-14 for local architecture; re-check WorkManager/Hilt Maven metadata and TVDB official docs if implementation starts after that date. [ASSUMED]
