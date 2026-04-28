# Red Flag Scan Results

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Phase:** 4
- **Owner task:** 23

## Static-scan results

| # | Red flag | Hits | Verdict | Notes |
|---|---|---:|---|---|
| 1 | Facade called, result ignored | 18 callers | ✅ | All facade calls assign the result (`val decision = ...`, `val enrichment = ...`, `val route = ...`, `val episodeRuntime = ...`). Surveyed: `HomeProviderLocalizedMetadataOverlay.kt:33`, `HomeViewModelContinueWatchingRuntimePipeline.kt:42,61,83`, `HomeViewModelPresentationPipeline.kt:723,743`, `HomeViewModelContinueWatching.kt:263,346`, `MetaDetailsViewModel.kt:1347,1773,2356`, `PlayerRuntimeControllerMetadata.kt:64,84`, `ContinueWatchingSnapshotService.kt:856`, `TvdbContinueWatchingTimingEnricher.kt:45,46`. No fire-and-forget pattern detected. |
| 2 | Legacy router after facade | 4 | ❌ | Confirms F-03-02. `MetaDetailsViewModel.kt:1391` and `:1406` invoke `metadataSecondaryRepository.fetchTmdbEnrichment(...)` for TMDB enrichment, bypassing `MetadataRouterFacade`. (Also `HomeProviderLocalizedMetadataOverlay.kt:64` and `MetaDetailsViewModel.kt:100` instantiate the inner `MetadataRouter` directly inside the local default-facade builder — that is wrapped by a facade so not a leak.) |
| 3 | Provider adapter parses prefixed IDs incorrectly | 1 | ✅ | Only hit: `MDBListIntegrationProvider.kt:248-250` correctly distinguishes `tt`-prefixed IMDb IDs from `tmdb:`-prefixed TMDB IDs using `startsWith` guard before `removePrefix("tmdb:")`. No buggy prefix stripping found across `data/integration/**`. |
| 4 | Provider-native conflict silently rewrites IDs | 12 | ✅ | Every consumer of `route.targetIdRequiresIdentityResolution=true` routes through `MetadataIdentityResolver.resolve(...)` first. Verified in `MetadataRouterFacade.kt:27` and `:93` (resolver runs before plan execution). `ProviderPlanExecutor.kt:12` defensively asserts `check(!route.targetIdRequiresIdentityResolution)` — adapter execution is unreachable with an unresolved route. `MetadataIdentityResolver.kt:22,47` returns the route untouched if `requiresIdentityResolution=false` and clears the flag once resolved. |
| 5 | PREVIEW triggers route/runtime | 4 | ❌ | Related to F-01. `MetadataRouter.kt:18` correctly rejects PREVIEW with `require(...)`. `MetadataRouterFacade.kt:37` short-circuits PREVIEW before routing. `ResolverOrchestrator.kt:24` and `ProviderPlanExecutor.kt:140` also gate PREVIEW. However `FirstPaintTracer.kt:31` emits `metadata.first_paint` from `recordHomePreview(...)`, called via `HomeFirstPaintMetadataMapper.kt:17` during the home preview render path — confirming F-01 (the event is wired but at the wrong site, since preview never enters the router). |
| 6 | TVDB localized → TMDB fallback | 12 | ✅ | Per Path 06 contract (field-level localized→fallback within TVDB only): `TvdbEpisodeLocalization.kt:31` enforces `check(!policy.allowProviderFallbackForMissingLocalizedFields)`. The `TVDB_FALLBACK_TMDB` paths in `TvMetadataRouter.kt` are the documented provider-level fallback (TVDB inactive / record missing / identity missing) — outside the localization contract. |
| 7 | English fallback re-fetched when cached | n/a | ✅ | Manual review of `TvdbEpisodeLocalization.kt`: the helper is pure and operates on already-fetched record lists; it does not re-issue the English fetch per call. Caching is owned upstream by the IntegrationRuntime (CacheFirst policies for TVDB shapes). |
| 8 | Image cache varies by profile language | 19 | ✅ | `ArtworkImageCacheKeys.kt:37` hardcodes `"artwork:$provider:$type:$itemId:imageLang:en:policy:1"`. All call sites (`ModernHomeRows.kt`, `HomeCatalogRefreshCoordinator.kt`, `EpisodesSection.kt`, `ContentCard.kt`, `ContinueWatchingSection.kt`, `GridContentCard.kt`, `AndroidTvChannelArtworkCache.kt`) go through this object. `ProfileBoundaryEnforcer.kt:169,311` enforces the `imageLang:en` regex at runtime. |
| 9 | Global metadata contains overlay fields | 1 | ✅ | `GlobalMetadataDocument.kt:3-19` declares only provider-owned fields: `contentId, provider, language, title, overview, runtimeMinutes, episodeMetadata, artworkCandidates, fieldTrace`. No watched/progress/list/scrobble/CW state. (`CompositionTypeShapeTest` reflection test enforces this in CI.) |
| 10 | Trakt/Simkl Global scope | 0 / 1 | ✅ | `TraktIntegrationProvider.kt:92,105` use `IntegrationScope.Profile(session.profileId)`. Simkl integration providers carry no `IntegrationScope` literal in `data/integration/simkl/**`. The single Simkl `GlobalContent` reference is `SimklAuthIntegrationProvider.kt:64` — auth/device flow (allowed exception). `OpenSubtitlesHashIntegrationProvider.kt:44` uses `Global` but that is unrelated to Trakt/Simkl. `ProfileBoundaryEnforcer.kt:37,290` runtime-rejects Global scope for authenticated providers as the safety net. |
| 11 | CW query lacks profile | 1 declared / 0 callers | ❌ | Confirms F-09-1. `ContinueWatchingSnapshotService.kt:358` declares `fun observeContinueWatching(profileId: Int)` but `grep observeContinueWatching\(` finds zero production callers (only the deprecation `message` string in `TrackingProgressService.kt:48` references it). Production CW pipeline still uses the non-explicit `observeContinueWatchingNextUp()` route. |
| 12 | Scrobble uses current profile | 6 | ✅ | Per Path 11. `TrackingScrobbleService.kt:53,58,62,67,72,76,81,86,90` all forward `owner.ownerProfileId` (from `PlaybackOwnerContext`) to Trakt/Simkl scrobble services. `PlayerRuntimeControllerPlaybackEvents.kt:325,347,411` invokes scrobble with the playback owner. No call site uses `profileManager.activeProfileId.value` for scrobble. |
| 13 | Trace helper exists with 0 callers | 6/6 have callers | ✅ (with caveat) | Per-method counts below. All six emit methods have exactly 1 production caller. `emitFirstPaint` count > 0 but emission site is the wrong one (F-01). `emitResolverSchedule` is wired (contradicts F-04-02 prediction of 0). |

## Per-emit method caller counts

| Method | Production callers (excl. self) | Status |
|---|---:|---|
| `emitFirstPaint` | 1 | ❌ — note F-01: emitted from `FirstPaintTracer.kt:31` via `HomeFirstPaintMetadataMapper.kt:17`, which is the home preview render site. Preview must not emit `metadata.first_paint`; the validator rule `PreviewMustNotRouteOrNetwork` cross-checks. |
| `emitRouteDecision` | 1 | ✅ — `MetadataRouter.kt:255` (sole production routing site) |
| `emitIdentityResolution` | 1 | ✅ — `MetadataIdentityResolver.kt:32` |
| `emitProviderPlan` | 1 | ✅ — `ProviderPlanRunner.kt:15` |
| `emitResolverSchedule` | 1 | ✅ — `ResolverOrchestrator.kt:55` (contradicts F-04-02 prediction of 0; revisit F-04-02 in Lane B) |
| `emitFieldSelected` | 1 | ✅ — `FieldResolver.kt:74` |

## Validator rules without an event source

| Rule | Inspects event type | Source emissions in production | Status |
|---|---|---|---|
| PreviewMustNotRouteOrNetwork | metadata.first_paint | 1 (`FirstPaintTracer`) | ✅ source exists; rule is the F-01 detector |
| RouteDecisionUsedInputs | metadata.route_decision | 1 (`MetadataRouter`) | ✅ |
| FreshCacheHitSuppressesNetwork | runtime.cache_decision + http.request | runtime emits via IntegrationRuntime; http via OkHttp telemetry | ✅ (production source present in runtime telemetry layer) |
| RuntimeCallHasApiShapeId | runtime.operation_start | IntegrationRuntime `startOperation` | ✅ |
| NetworkCallHasRuntimeOperationId | http.request | OkHttp interceptor | ✅ |
| ProfileBoundCallHasProfileHash | profile.boundary_check | `ProfileBoundaryEnforcer` | ✅ |
| AccountBoundCallHasCredentialHash | profile.boundary_check | `ProfileBoundaryEnforcer` | ✅ |
| GlobalKeyHasNoProfile | runtime.operation_start + runtime.cache_decision | IntegrationRuntime | ✅ |
| ImageKeyUsesEnglish | runtime.cache_decision | IntegrationRuntime + `ProfileBoundaryEnforcer` regex (`ArtworkImageCacheKeys`) | ✅ |
| IdentityResolutionPrecedesProviderConflict | metadata.identity_resolution + metadata.provider_plan | `MetadataIdentityResolver` + `ProviderPlanRunner` | ✅ |
| FieldHasOwnershipRule | metadata.field_selected | `FieldResolver` | ✅ |
| SecondaryDoesNotOverwritePrimary | metadata.field_selected | `FieldResolver` | ✅ |
| TraktSimklUsesCorrectProfile | profile.boundary_check | `ProfileBoundaryEnforcer` | ✅ |
| NoStaleProfileWritesAfterSwitch | profile.boundary_check + continue_watching.snapshot_write | `ProfileBoundaryEnforcer` + `ContinueWatchingSnapshotService` | ✅ |

All validator rules have at least one production emission source.

## Findings staged for lane files

For each ❌ verdict above, the candidate finding (cross-referenced where it confirms an existing F-NN):

- **F-RF-01** (cross-ref F-03-02): Legacy `metadataSecondaryRepository.fetchTmdbEnrichment(...)` called from `MetaDetailsViewModel.kt:1391` and `:1406`, bypassing `MetadataRouterFacade`. → assigned to **Lane B** (Metadata Router).
- **F-RF-02** (cross-ref F-01): `metadata.first_paint` is emitted from `FirstPaintTracer.recordHomePreview` via `HomeFirstPaintMetadataMapper.kt:17`, which is the preview render path. PREVIEW depth must not produce `first_paint`. → assigned to **Lane I** (Trace Mode).
- **F-RF-03** (cross-ref F-09-1): `ContinueWatchingSnapshotService.observeContinueWatching(profileId: Int)` declared at `:358` but has zero production callers; CW pipeline still routes via the non-profile-explicit `observeContinueWatchingNextUp()`. → assigned to **Lane G** (Continue Watching).

No new (non-cross-referenced) red-flag findings were uncovered.

## Cross-references

- Production path findings (F-01, F-03-02, F-04-02, F-09-1)
- Boundary map (`02-architecture-boundary-map.md`)
- Trace validator audit (`06-trace-validator-audit/`)
- Lane B (Metadata Router), Lane G (Continue Watching), Lane I (Trace Mode) — pre-staged below.
