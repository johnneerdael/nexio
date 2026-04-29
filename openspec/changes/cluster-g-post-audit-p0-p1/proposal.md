# Change: Cluster G Post-Audit P0 and P1 Closures

## Why

The post-cluster-F audit dossier (`review-dossier-2/`, commit `65228a3b5`) classified the branch as "NOT APPROVED for merge" due to 2 P0 findings (one a cluster-F regression) and 15 P1 findings (several being incomplete closures of prior-cluster claims):

**P0 — merge blockers:**
- **F2-D-01:** Cluster F's F-C-06 changed Trakt global-content cache keys from `accountCacheKey` to `globalContentCacheKey` but left `scope = accountScope(session)`. `ProfileBoundaryEnforcer.validateAccountScope` throws `ProfileBoundaryException` at `IntegrationSpec.init` for every authenticated Trakt user — trending/popular/recommendations/calendar Home rails dark.
- **F2-J-01:** `AddonFirstPaintShapeArchitectureTest` is failing at SHA `774a540f8`. `HomeViewModelContinueWatchingRuntimePipeline.kt:29` and `HomeViewModelPresentationPipeline.kt:474, 548` still call `getMetaFromAllAddons()` directly.

**P1 — strongly recommended:**
- **F2-H-01 + F2-H-02 + F2-F-05:** Cluster B's claimed F-H-03 P0 closure is incomplete. `checkScrobbleBoundary` is `Unit`-returning and telemetry-only; stale scrobbles credit the wrong profile.
- **F2-B-01:** `MetadataExecutionAuditGoldenTest.routing rules match spec for all id types` fails. `MetadataIdentityResolver` appends `ROUTING_ID_TYPE_CONFLICT` to all successful identity resolutions; the audit-runner override is too broad.
- **F2-A-01:** `backoffManager.clear()` only fires on `get()` success; `call()` and `open()` paths leave providers blocked after recovery.
- **F2-C-01:** `loadMovieCollection()` bypasses `IntegrationRuntime` entirely.
- **F2-D-08:** `deleteOwnedMedia` blob+DAO delete is non-transactional (orphans accumulate).
- **F2-E-01:** `TvdbLanguageMapper` silently collapses unsupported locales to English with no diagnostic.
- **F2-E-03:** Real-emission validator test doesn't exercise TVDB localization path.
- **F2-I-01:** Trace bundle stamps `gitSha = null` in production.
- **F2-I-06:** `SecondaryDoesNotOverwritePrimary` validator has inverted semantics — false positives.
- **F2-I-07:** Trace settings UI ungated in release builds (privacy/data-exposure risk).
- **F2-J-02:** No architecture pin enforces caller-side `ownerProfileId` on `checkin()`.
- **F2-J-03:** No architecture pin guards `ResolvedMetadataDocument` construction.

This change closes all 17. The cluster-F regressions are fixed within this same branch so the merge gate clears.

## What Changes

### MODIFIED

- `TraktIntegrationProvider`: 6 global-content fns (`fetchTrendingMovies`, `fetchTrendingShows`, `fetchPopularMovies`, `fetchPopularShows`, `fetchRecommendations`, `fetchCalendarShows`) use `scope = IntegrationScope.GlobalContent` + `profileContext = null` (F2-D-01).
- `HomeViewModelContinueWatchingRuntimePipeline.kt:29` and `HomeViewModelPresentationPipeline.kt:474, 548` route through `MetadataRouterFacade` instead of direct `getMetaFromAllAddons()` (F2-J-01).
- `TraktScrobbleService.checkScrobbleBoundary` and `SimklScrobbleService.checkScrobbleBoundary` return `Boolean`; callers early-return on false (F2-H-01 + F2-F-05).
- `DefaultTrackingProgressService` re-checks `assertCanWriteProfileState` at result-completion time (F2-H-02).
- `MetadataIdentityResolver.resolve` does not emit `ROUTING_ID_TYPE_CONFLICT` for routes that originated as `ITEM_TYPE_SERIES`/`ITEM_TYPE_MOVIE` (F2-B-01).
- `DefaultIntegrationRuntime.doCallInternal` + `openInternal` success branches call `backoffManager.clear(spec.provider, spec.scope)` (F2-A-01).
- `TmdbIntegrationProvider.loadMovieCollection` routes through `runtime.get(IntegrationSpec(apiShapeId = TmdbApiShapes.COLLECTION, ...))` (F2-C-01).
- `LocalIntegrationCacheStore.deleteOwnedMedia` runs DAO delete before blob delete (F2-D-08).
- `TvdbLanguageMapper` adds `isCollapsedToFallback` field; callers pass it to localization-plan event (F2-E-01).
- `RuntimeTraceModule` provides `TraceBuildInfo` with `gitSha = BuildConfig.GIT_SHA` (F2-I-01).
- `TraceValidationRules.SecondaryDoesNotOverwritePrimary` filter narrows to actual secondary winners (F2-I-06).
- `PlaybackSettingsSections` gates "Runtime & Metadata Trace" entry on `BuildConfig.IS_DEBUG_BUILD` (F2-I-07).
- `HomeViewModelContinueWatching.kt:590` and `MetaDetailsViewModel.kt:3076` thread `ownerProfileId` into `checkin(item, ownerProfileId = ...)` (F2-J-02).

### ADDED

- `app/build.gradle.kts`: `buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")` (F2-I-01).
- `RuntimeTraceValidatorRealEmissionTest`: TVDB episode-bundle scenario (F2-E-03).
- `TraktScrobbleServiceProfileBoundaryTest`, `SimklScrobbleServiceProfileBoundaryTest`: assert no enqueue on profile mismatch (F2-H-01).
- `TrackingProgressServiceProfileBoundaryRecheckTest`: assert result-time rejection (F2-H-02).
- `IntegrationCallRuntimeBackoffClearTest`: assert success clears backoff for call/open (F2-A-01).
- `TmdbCollectionRuntimeCoverageTest`: assert `loadMovieCollection` routes through runtime (F2-C-01).
- `TvdbLanguageMapperFallbackDiagnosticTest`: assert `isCollapsedToFallback` set (F2-E-01).
- `TraceBuildInfoGitShaTest`: assert non-null `gitSha` in bundle (F2-I-01).
- `SecondaryDoesNotOverwritePrimaryNoFalsePositiveTest`: assert primary-wins-with-competition is PASS (F2-I-06).
- `ResolvedMetadataDocumentConstructionContractTest`: pin only `FieldResolver` + `MetadataRouterFacade` may construct (F2-J-03).
- `CheckinCallerOwnerProfileIdContractTest`: pin `checkin(...)` callers supply `ownerProfileId` (F2-J-02).
- `trakt_authenticated_global_content_shared_cache` boundary audit scenario (F2-D-01).

## Impact

- Affected specs: `integration-runtime`.
- Affected code: 16 production files modified + 11 new test files + 1 new audit fixture + 1 build script edit.
- Behavior changes:
  - Authenticated Trakt users see Home trending/popular/recommendations/calendar rails again (F2-D-01).
  - Architecture-pin CI suite passes (F2-J-01).
  - Stale scrobbles during profile switch are blocked, not just logged (F2-H-01 + F2-H-02 + F2-F-05).
  - YouTube trailer + subtitle providers recover from transient errors immediately on next `call()`/`open()` (F2-A-01).
  - TMDB collection fetches gain backoff + audit + single-flight (F2-C-01).
  - Cache-deletion crash leaves orphan blob (recoverable) instead of orphan DB row (F2-D-08).
  - Italian/Portuguese/etc. users see a `localeCollapsedToFallback` diagnostic in trace bundles (F2-E-01).
  - Trace bundles stamp the actual git SHA, supporting bundle-to-commit correlation (F2-I-01).
  - `SecondaryDoesNotOverwritePrimary` validator no longer false-positives on primary-wins scenarios (F2-I-06).
  - Trace settings UI hidden in release builds (F2-I-07).
- No new dependencies. No new trace event types beyond payload field additions. No persistent schema changes.
