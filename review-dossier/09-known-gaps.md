# Known Gaps Register

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Phase:** 6
- **Owner task:** 35
- **Status:** COMPLETE
- **Total unique findings:** 53 (after cross-reference deduplication; source files contain 60 raw `### F-` headings — 7 duplicates folded into primary owners)
- **By severity:** P0=2, P1=21, P2=18, Nit=12

## Severity classification rules applied

- **P0** — Blocks merge. Production code path broken (crash, data loss, profile leak), claimed audit gate would fail, or spec contract silently violated without a test catch.
- **P1** — Strongly recommended pre-merge. Architectural debt or coverage gap on a contract not currently violated.
- **P2** — Follow-up. Hygiene / future-proof scaffolding.
- **Nit** — Cosmetic / cleanup.

Where the source dossier severity differed from the strict rule, the rule wins (default upward on ambiguity). Two findings were upgraded from P1 to P0 under this principle: F-F-01 (UI crash risk on profile switch) and F-H-03 (silent contract violation with no test catch).

## P0 — merge blockers

### F-F-01: UI callers of `setActiveProfile` don't catch `ProfileBoundaryException` — crash risk during active playback

- **Severity:** P0 (upgraded from source P1; production crash path)
- **Evidence:** `app/src/main/java/com/nexio/tv/MainActivity.kt:343-352`, `app/src/main/java/com/nexio/tv/MainActivity.kt:518-534`, `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModel.kt:47-51`. `ProfileBoundaryException extends IllegalArgumentException` (`app/src/main/java/com/nexio/tv/core/integration/ProfileBoundaryViolation.kt:18-21`); `grep "catch.*ProfileBoundary" app/src/main` returns one hit, in `ContinueWatchingSnapshotService.kt:1057` (the stale-write variant, not the switch variant).
- **Violated contract:** Lane F Contract 5 — switch-during-playback rejection must be safely handled by callers.
- **User-visible impact:** User long-presses the profile picker overlay during playback, selects a different profile → `setActiveProfile` throws `PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK` from inside `lifecycleScope.launch { ... }`. With no `CoroutineExceptionHandler` installed in `MainActivity`/`ProfileSelectionViewModel`, the default behaviour either crashes the app or silently cancels the launch with no toast/PIN prompt/playback stop.
- **Required fix:** Wrap each `setActiveProfile` call in `try { ... } catch (e: ProfileBoundaryException) { ... }`; surface a "Stop playback first" toast for `PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK`. Better: introduce a `ProfileSwitchResult` sealed class so the type system forces callers to handle the rejection.
- **Test or report that should catch it:** `ProfileSelectionViewModelSwitchDuringPlaybackTest` exercising `selectProfile` with an active fake playback owner and asserting no uncaught exception.
- **Cross-references:** F-10-1 (paths/10-profile-switch).

### F-H-03: No result-time `assertCanWriteProfileState` re-check on scrobble completion — `STALE_SESSION_WRITE_REJECTED` is unreachable from the scrobble path

- **Severity:** P0 (upgraded from source P1; spec contract silently violated, no test catches)
- **Evidence:** `grep -rn "assertCanWriteProfileState" app/src/main/java` returns only `core/integration/ProfileBoundaryEnforcer.kt:126,140,144` (decls) and `data/repository/ContinueWatchingSnapshotService.kt:1052` (the CW-write call site). Neither `TraktScrobbleService.kt:102-272`, `SimklScrobbleService.kt:77-134`, `DefaultTrackingScrobbleService.kt:53-93`, nor `app/src/main/java/com/nexio/tv/data/trakt/outbox/ProviderMutationOutboxCoordinator.kt` consults the active profile id again at result-time.
- **Violated contract:** Lane H Contract 6 — late scrobble after profile switch must be rejected via `assertCanWriteProfileState` and emit `STALE_SESSION_WRITE_REJECTED`.
- **User-visible impact:** No user-visible regression today (scrobble correctly posts against the original profile's account). Operator/observability impact: trace bundles never contain `STALE_SESSION_WRITE_REJECTED` events for scrobbles, so cross-profile drift is invisible. The architectural intent ("every cross-profile write goes through `assertCanWriteProfileState`") is unenforced for scrobbles.
- **Required fix:** At `ProviderMutationOutboxCoordinator.enqueueAndDrain(envelope)` (or at the start of `enqueueScrobble`/`enqueueCheckin`), invoke `ProfileBoundaryEnforcer.assertCanWriteProfileState(expectedProfileId = envelope.profileId, actualProfileId = profileManager.activeProfileId.value, operation = "scrobble.${envelope.action}")`. On `BoundaryViolation`, emit `playback.scrobble_rejected` and either reject (rolling back optimistic watching-now state) or document the policy and emit informational trace.
- **Test or report that should catch it:** `TraktScrobbleServiceProfileBoundaryTest` and `SimklScrobbleServiceProfileBoundaryTest`. Add a `TraceValidationRules` rule pairing every `playback.scrobble_request` with `playback.scrobble_completed` or `playback.scrobble_rejected`.
- **Cross-references:** Path 11.

## P1 — strongly recommended pre-merge

### F-A-01: Stream `open()` failures do not register backoff

- **Severity:** P1
- **Evidence:** `app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt:385-389` — `openInternal`'s catch branch records `FAILED` without invoking `noteSyntheticNetworkFailure(...)`, mirroring neither `executeProviderLoad` nor `callInternal`.
- **Violated contract:** Lane A — 429/5xx + synthetic network failure must register backoff.
- **User-visible impact:** A degraded streaming endpoint (SSE / chunked transport) is not gated by `IntegrationBackoffManager`; subsequent open attempts hammer the upstream and bypass rate-limit recovery applied to peer `get`/`call` operations.
- **Required fix:** In the `catch` branch of `openInternal`, invoke `noteSyntheticNetworkFailure(provider, scope, retryAfterMs = null, reason = exception.message)` before recording `FAILED`.
- **Test or report that should catch it:** `DefaultIntegrationRuntimeStreamBackoffTest` asserting `backoffManager.isBlocked` becomes true after a stream open exception.
- **Cross-references:** Lane D F-D-01 (sibling stale-fallback gap), Lane J (architecture tests don't cover backoff parity).

### F-TM-02: No single-flight regression test

- **Severity:** P1
- **Evidence:** `review-dossier/08-test-matrix.md:29,96`. No `SingleFlight*Test` exists under `app/src/test/java/com/nexio/tv/core/integration/`.
- **Violated contract:** Single-flight joins concurrent operations for the same key (test-coverage gap).
- **User-visible impact:** A regression dropping `mutex.withLock` would not be caught — concurrent fetches on the same cache key would both hit the network, doubling load on rate-limited providers.
- **Required fix:** Coroutine test racing two `getInternal` calls with the same `cacheKey`, asserting the loader runs once and both callers receive the same `IntegrationFetchResult`.
- **Test or report that should catch it:** New `IntegrationSingleFlightTest` plus `DefaultIntegrationRuntimeSingleFlightTest` exercising `executeCacheFirst` end-to-end.
- **Cross-references:** Lane A F-A-02 (related: single-flight only on CacheFirst).

### F-B-03: DETAIL_CORE bypasses facade — calls `metadataSecondaryRepository.fetchTmdbEnrichment` directly

- **Severity:** P1
- **Evidence:** `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1391` and `:1406` invoke `metadataSecondaryRepository.fetchTmdbEnrichment(...)` directly.
- **Violated contract:** Facade is the single entry point for TMDB/TVDB/KITSU enrichment; bypassing it skips `MetadataRouter.route`, `MetadataIdentityResolver.resolve`, `ProviderPlanRunner.run` and silences `metadata.route_decision`/`metadata.identity_resolution`/`metadata.provider_plan`/`metadata.field_selected`.
- **User-visible impact:** Movie DETAIL_CORE TMDB enrichment is invisible to trace mode and validators. The manual merge at `:1414-1419` performs `tvEnrichment ?: tmdbEnrichment` field-level fallback that does not respect `FieldResolver`'s primary-wins ownership rule.
- **Required fix:** Replace both call sites with `metadataRouterFacade.fetchTvEnrichment(...)` (or a TMDB-equivalent facade method).
- **Test or report that should catch it:** Path 03 trace assertion that movie loads emit one `metadata.route_decision` and one `metadata.field_selected` per non-null field; static-analysis rule banning `metadataSecondaryRepository.fetchTmdbEnrichment` calls outside the metadata router package.
- **Cross-references:** F-03-02, F-RF-01 (red-flag scan).

### F-C-01: TMDB person/company helpers bypass `IntegrationRuntime`

- **Severity:** P1
- **Evidence:** `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt:740-788` — `loadPersonDetails`, `loadPersonCombinedCredits`, `searchPeople`, `searchCompanies` call `tmdbApi.*` via the local `loadResponse(...)` helper without an enclosing `runtime.get/call` spec.
- **Violated contract:** Lane C Contract 1 — all provider calls go through `IntegrationRuntime`.
- **User-visible impact:** Person browse, company browse and search hit the TMDB API without observability; circuit-broken TMDB or rate-limit storms during search are not reflected in trace mode and bypass runtime-level retry/backoff.
- **Required fix:** Wrap each function in `runtime.call(IntegrationCallSpec(...))` referencing existing `TmdbApiShapes.PERSON_DETAIL`, `PERSON_COMBINED_CREDITS` constants and add `SEARCH_PEOPLE`/`SEARCH_COMPANIES` shapes (also closes part of F-C-02).
- **Test or report that should catch it:** Detekt rule banning `tmdbApi.*` outside an enclosing `runtime.call/get` lambda; `TmdbIntegrationProviderRuntimeContractTest` exercising every public method against a fake `IntegrationRuntime`.

### F-D-01: 429/5xx in cache-miss path returns Missing instead of falling back to stale

- **Severity:** P1
- **Evidence:** `app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt:611-628` — HttpError branch of `executeProviderLoad` registers backoff at `:617-624` then returns `IntegrationFetchResult.Missing` at `:627` without consulting `cacheStore.readStale(spec)`. Contrast `:544-557` which does fall back to stale on Missing.
- **Violated contract:** Lane D Contract 2 — stale-on-429 fallback returns the stale value AND registers backoff.
- **User-visible impact:** When TMDB/TVDB/Trakt return 429 on a CacheFirst metadata fetch where the entry is expired-fresh but still-stale, the caller receives `Missing`; UI surfaces (Home rows, MetaDetails enrichment, ContinueWatching localization) flicker to placeholder for one render cycle even though usable cached data exists.
- **Required fix:** In the HttpError and NetworkError branches of `executeProviderLoad`, attempt `cacheStore.readStale(spec)` before returning Missing; on hit return `IntegrationFetchResult.Stale(...)` plus a `STALE_HIT` `runtime.cache_decision` with `reason = "stale-cache-hit-backoff-inline"`.
- **Test or report that should catch it:** `DefaultIntegrationRuntimeStaleOn429Test`.
- **Cross-references:** F-A-01 (stream backoff parity), F-TM-01 (test-matrix).

### F-D-02: Cache write is non-atomic — blob and Room row updated separately

- **Severity:** P1
- **Evidence:** `app/src/main/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStore.kt:54-67` — `file.writeBytes(...)` (truncate-then-write) precedes `cacheDao.upsertCacheEntry(...)`. No `tmp+rename`, no `@Transaction`. `IntegrationCacheDao.kt:11` is a single `@Insert(REPLACE)`.
- **Violated contract:** Lane D Contract 3 — cache writes atomic and don't race with reads.
- **User-visible impact:** A reader running between truncate and final flush reads 0 / partial bytes; `spec.codec.decode` throws or yields garbage. Window is microseconds but reachable on rapid invalidation or when single-flight is bypassed (F-A-02).
- **Required fix:** (a) Write blob to `${blobPath}.tmp` then `File.renameTo(blobPath)`; (b) wrap rename + DAO upsert in a Room `@Transaction suspend fun`; (c) `readFresh` must tolerate `FileNotFoundException` and short reads.
- **Test or report that should catch it:** `LocalIntegrationCacheStoreAtomicityTest` (Robolectric/coroutine) — parallel writer + tight-loop reader, assert no read throws or returns corrupt decode.

### F-E-01: `LocalizedEpisodeBundle` per-episode fallback counter is computed but not surfaced to trace

- **Severity:** P1
- **Evidence:** `app/src/main/java/com/nexio/tv/data/integration/metadata/LocalizationModels.kt:42-49` declares `perEpisodeTranslationFallbacksAttempted/maxPerEpisodeTranslationFallbacksAllowed`; `TvdbEpisodeLocalization.kt:75-80` populates them; `TvdbMetadataProviderAdapter.kt:62-89` consumes only `bundle.episodes`/`bundle.localizationPayloads` — counters dropped.
- **Violated contract:** Lane E Contract 4 — per-episode translation fallback counter visible in trace.
- **User-visible impact:** Operators reading trace cannot see whether the per-request cap was hit or how many translations were attempted; a regression silently raising the cap would not be caught.
- **Required fix:** Either (a) extend `MetadataLocalizationPayloadTrace` with optional counter fields and have `TvdbMetadataProviderAdapter` synthesize a payload trace per bundle, or (b) include counters in the deferred `metadata.localization_plan` event (F-E-02). (b) is preferred.
- **Test or report that should catch it:** `TvdbLocalizationBundleCounterTraceTest` plus a `RuntimeTraceValidator` rule.

### F-E-02: `metadata.localization_plan` event has no production emission site

- **Severity:** P1
- **Evidence:** `grep -rn "emitLocalizationPlan\|metadata.localization_plan" app/src/main/java` returns zero. `TraceMetadataEvents.kt:1-209` exposes no localization-plan helper. Commit `e3a3ab8d7` removed the previous helper. Boundary-map Q7 documents "no canonical orchestration site emits `metadata.localization_plan`".
- **Violated contract:** Lane E Contract 5 — `metadata.localization_plan` must be emitted.
- **User-visible impact:** Trace bundles cannot answer "what localization policy did NEXIO actually apply?" — per-fetch payloads exist, the high-level policy state does not (policy version, cross-provider fallback flag, requestedIsFallback, per-episode cap).
- **Required fix:** (1) Introduce a `LocalizationOrchestrator` (or extend `MetadataProviderAdapter` SPI with `policyForRoute(route)`) so there is one canonical seam; (2) add `TraceMetadataEvents.emitLocalizationPlan(...)`; (3) add validator rule "every metadata-route trace span targeting TVDB/TMDB/Kitsu must contain at least one `metadata.localization_plan` before its first `provider_step`".
- **Test or report that should catch it:** `MetadataRouterLocalizationPlanTraceTest` for Dutch profile → TVDB SERIES route asserting `requestedLanguage = "nld"`, `fallbackLanguage = "eng"`, `policyVersion = 2`, `perEpisodeFallbacksAllowed = 8`.
- **Cross-references:** F-06-01 (trace-validator audit), boundary map Q7.

### F-E-03: `metadata.field_selected` is emitted only by `FieldResolver`, never for per-episode title/overview decisions

- **Severity:** P1
- **Evidence:** `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt:74` is the only emit site. Per-episode field winners computed in `LocalizationResolver.selectField` are stored in `LocalizedEpisodeMetadata.fieldSources` and converted to `MetadataLocalizationFieldTrace` in `TvdbMetadataProviderAdapter.kt:71-81`, `TmdbMetadataProviderAdapter.kt:153-172`, `KitsuMetadataProviderAdapter.kt:97-107` — never reach `emitFieldSelected`.
- **Violated contract:** Cross-references Lane E Contract 5 (the trace gap is paired with `metadata.localization_plan`); F-06-02.
- **User-visible impact:** The most operationally important localization decision — which language won for episode 4 of season 2's title — is invisible. Support cannot determine if the wrong language is from missing localized field, placeholder cleanup, or cross-provider routing bug.
- **Required fix:** Emit `metadata.field_selected` inside each adapter's per-episode loop after `LocalizationResolver.selectField` returns, with `contentId = "tvdb:$tvdbId:s${season}e${number}"` and existing `valuePreview`/`ownershipRule = "localization-resolver: ${selected.fallbackRole.name}"`/rejected candidates.
- **Test or report that should catch it:** `TvdbMetadataProviderAdapterEpisodeFieldSelectedTraceTest` plus a validator rule.

### F-G-01: Profile-scoped CW read API has zero production callers — Home VM filters manually on a wider flow

- **Severity:** P1
- **Evidence:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:358-363` declares `observeContinueWatching(profileId: Int)`; `grep -rn "observeContinueWatching(" --include="*.kt" app/src/main/` returns two hits (declaration + deprecation `message` text). Home VM uses `observeSnapshot()` with manual filter at `HomeViewModelContinueWatching.kt:75-78`. `AndroidTvFeedCatalogService.kt:150` reads `observeSnapshot().first()` with no profile filter.
- **Violated contract:** Lane G Contract 2 — production read API must be the typed profile-scoped flow.
- **User-visible impact:** None today (the snapshot is correctly tagged at write time and the manual guard catches mismatches). Latent: any consumer dropping or inverting the manual filter (especially `AndroidTvFeedCatalogService` from a background worker after profile switch) would publish foreign-profile CW into Android TV channels.
- **Required fix:** Route every CW read through `observeContinueWatching(activeProfileId)`. Update `HomeViewModelContinueWatching.kt:73` and `AndroidTvFeedCatalogService.kt:150`. Optionally tighten via `@RestrictTo` / internal visibility on the bare overload.
- **Test or report that should catch it:** `ContinueWatchingObserveProfileScopedTest`; lint rule banning `observeSnapshot().collect(...)` outside the service.
- **Cross-references:** F-RF-03, F-09-1.

### F-I-02: First-paint emission is wired to a router pre-flight, not the canonical first-paint boundary

- **Severity:** P1
- **Evidence:** `app/src/main/java/com/nexio/tv/ui/screens/home/HomeFirstPaintMetadataMapper.kt:15` is invoked only from `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt:729` and `:749` — inside `fetchProviderEnrichmentForPreview`, which immediately calls `metadataRouterFacade.fetchTvEnrichment(...)`. The actual Home tile first paint (`buildCatalogItem` in `ModernHomeModels.kt:570`) calls the pure `MetaPreview.toHomeDisplayMetadata()` and never touches `FirstPaintTracer`.
- **Violated contract:** Trace event taxonomy — `metadata.first_paint(routerExecuted=false, networkExecuted=false)` must mark the addon-only render boundary so `PreviewMustNotRouteOrNetwork` can correlate.
- **User-visible impact:** `PreviewMustNotRouteOrNetwork` becomes a no-op for the Home carousel; Path 01 vs Path 02 cannot be distinguished in trace recordings; `routerExecuted = false` events are immediately followed by router events for the same `contentId`, misleading anyone reading a captured trace.
- **Required fix:** Move `FirstPaintTracer.recordHomePreview(...)` invocation into `buildCatalogItem` (or an adjacent presentation-builder once-per-contentId throttle); replace the two pre-flight call sites with the pure `item.toHomeDisplayMetadata()`.
- **Test or report that should catch it:** Unit/UI test asserting `buildCatalogItem` emits exactly one `metadata.first_paint` per content-id per render and `fetchProviderEnrichmentForPreview` emits zero. Strengthen `PreviewMustNotRouteOrNetwork`.
- **Cross-references:** F-01, F-02-01, F-RF-02.

### F-I-03: `RuntimeTraceValidatorRealEmissionTest` is excluded from the audit-task filter

- **Severity:** P1
- **Evidence:** `app/build.gradle.kts:403-410` — `generateTraceValidatorAudit`'s `includeTestsMatching` filter is restricted to `com.nexio.tv.core.trace.TraceBundleGoldenTest`. `RuntimeTraceValidatorRealEmissionTest` (added in `39b0df54a`) is not exercised by the audit gate.
- **Violated contract:** Audit-gate intent — every validator-affecting test should run under the audit task.
- **User-visible impact:** A future schema drift between an emission site and a validator lookup will pass the synthetic-event golden but break real-emission validation silently. Audit summary will report PASS while production traces fail validation.
- **Required fix:** Extend `includeTestsMatching` to also include `com.nexio.tv.core.trace.RuntimeTraceValidatorRealEmissionTest`; optionally widen to `com.nexio.tv.core.trace.*Validator*Test`. Add a meta-assertion that every `*Validator*Test` under `core.trace` is in the filter.
- **Test or report that should catch it:** Audit JUnit XML directory must list both test class XMLs.

### F-04-01: `MetadataDepth.DETAIL_MEDIA` has no production caller

- **Severity:** P1
- **Evidence:** Repo-wide search returns three production hits (enum decl + orchestrator branch + plan-executor branch) and zero call-sites that request the depth.
- **Violated contract:** Path 04 contract — DETAIL_MEDIA enrichment via canonical chain.
- **User-visible impact:** Whole DETAIL_MEDIA pathway is dead. Trace events `metadata.resolver_schedule(depth=DETAIL_MEDIA)`, `metadata.route_decision(depth=DETAIL_MEDIA)`, `metadata.provider_plan(...MOVIE_VIDEOS)`, `metadata.field_selected(...TRAILER_LIST)` cannot fire.
- **Required fix:** Either (a) wire the detail VM to issue a follow-up `fetch...(depth = DETAIL_MEDIA)` after first paint (preferred), or (b) remove `DETAIL_MEDIA` from the depth enum / orchestrator / plan executor and document the collapsed-into-DETAIL_CORE behaviour.
- **Test or report that should catch it:** Trace-validator rule asserting `metadata.resolver_schedule` for DETAIL_MEDIA fires when the detail screen is opened.

### F-04-03: Trailer enrichment bypasses the canonical metadata facade

- **Severity:** P1
- **Evidence:** `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:2660-2700` (`fetchTrailerUrl` → `trailerService.resolveTrailer` at `:2680`); `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt:54-80`; `app/src/main/java/com/nexio/tv/data/integration/trailer/TrailerTmdbProvider.kt:30-80`.
- **Violated contract:** Path 04 — DETAIL_MEDIA trailer enrichment via canonical chain.
- **User-visible impact:** Same observability + ownership-enforcement gap as F-B-03. Trailer pipeline never enters `FieldResolver`; `metadata.route_decision`/`metadata.provider_plan`/`metadata.field_selected` correlation is lost; multi-provider ownership rules cannot apply.
- **Required fix:** Introduce a `TRAILER_LIST` `ResolvedField`; have `TrailerTmdbProvider`/`TvdbTrailerResolver` participate as candidates inside `MetadataProviderAdapter` returns for `MOVIE_VIDEOS`/`TV_VIDEOS` plan steps; have detail VM call the facade with `DETAIL_MEDIA` and read trailers off the resolved doc. Keep `TrailerService` for playback-stage concerns.
- **Test or report that should catch it:** Path 04 trace assertion that opening a detail screen emits `metadata.field_selected(field=TRAILER_LIST, ...)`.

### F-05-01: `MetadataDepth.DETAIL_SECONDARY` has no production caller

- **Severity:** P1
- **Evidence:** Repo-wide search returns enum/orchestrator/plan-executor declarations only; no consumer requests this depth.
- **Violated contract:** Path 05 contract.
- **User-visible impact:** The deepest non-PLAYER metadata profile is dead from the consumer's perspective. None of the contract's resolver scheduling, plan composition, or field-ownership guarantees apply to reviews/recommendations/persons.
- **Required fix:** Either (a) wire the detail VM to issue a `fetch...(depth = DETAIL_SECONDARY)` call (third enrichment step after CORE / MEDIA), implement `ReviewResolver`/`RecommendationResolver`/`OrganizationPersonResolver`, surface fields through `FieldResolver`; or (b) remove `DETAIL_SECONDARY` from the depth enum/orchestrator/plan executor and document the actual direct-repo behaviour.
- **Test or report that should catch it:** Trace-validator rule similar to F-04-01.

### F-05-02: Reviews fetched via direct repository, bypassing the canonical metadata facade

- **Severity:** P1
- **Evidence:** `MetaDetailsViewModel.kt:1074` calls `metadataSecondaryRepository.fetchReviews(...)`; `:1119` calls `reviewsRepository.fetchTraktReviewPage(...)`. `MetadataSecondaryRepository.kt:33-37` delegates to `TmdbMetadataService.fetchReviews(...)`. No `ReviewResolver` class exists.
- **Violated contract:** Path 05 contract; same anti-pattern as F-B-03 / F-04-03.
- **User-visible impact:** `metadata.field_selected(REVIEW_LIST)` cannot fire; multi-provider ownership rules don't apply; Trakt vs TMDB review merging is hand-rolled in the VM.
- **Required fix:** Introduce a `REVIEW_LIST` `ResolvedField`; have TMDB and Trakt review fetchers participate as candidates for `MOVIE_REVIEWS`/`TV_REVIEWS` plan steps under `DETAIL_SECONDARY`.
- **Test or report that should catch it:** Path 05 trace assertion + static-analysis rule banning `metadataSecondaryRepository.fetchReviews` outside the metadata router package.

### F-05-03: Recommendations ("More like this") fetched via direct repository, bypassing the canonical metadata facade

- **Severity:** P1
- **Evidence:** `MetaDetailsViewModel.kt:875` calls `metadataSecondaryRepository.fetchMoreLikeThis(...)`. `MetadataSecondaryRepository.kt:27-31` delegates to `TmdbMetadataService.fetchMoreLikeThis(...)`. No `RecommendationResolver` class exists.
- **Violated contract:** Path 05 contract; same anti-pattern.
- **User-visible impact:** `metadata.field_selected(RECOMMENDATION_LIST)` cannot fire; FieldResolver-style candidate ranking unavailable when blending TMDB/Trakt sources.
- **Required fix:** Introduce a `RECOMMENDATION_LIST` `ResolvedField`; have `TmdbMetadataService.fetchMoreLikeThis` and Trakt providers participate as candidates for `MOVIE_RECOMMENDATIONS`/`TV_RECOMMENDATIONS` plan steps.
- **Test or report that should catch it:** Path 05 trace assertion.

### F-05-04: Cast / crew / company enrichment bypasses the canonical chain

- **Severity:** P1
- **Evidence:** `MetaDetailsViewModel.kt:1505` constructs `MetaCastMember(...)` from collapsed DETAIL_CORE response; `:1620-1625` invoke `findPersonIdByExactName`/`findCompanyIdByExactName` directly. `CastDetailViewModel.kt:17,45-47` calls `tvdbPersonService.fetchPersonDetail(personId)` and `metadataSecondaryRepository.fetchPersonDetail(...)` directly. No `OrganizationPersonResolver` class exists.
- **Violated contract:** Path 05 contract; same anti-pattern.
- **User-visible impact:** `metadata.field_selected(CAST_LIST/CREW_LIST/ORGANIZATION_LIST)` cannot fire; cross-provider person-id reconciliation (TVDB vs TMDB) is performed by ad-hoc VM logic instead of `FieldResolver`.
- **Required fix:** Introduce `CAST_LIST`/`CREW_LIST`/`ORGANIZATION_LIST` `ResolvedField`s; route both VM cast-list build and `CastDetailViewModel` person-detail through the facade with TVDB-first preference via candidate priority.
- **Test or report that should catch it:** Path 05 trace assertion.

### F-12-01: `ResolverType.SKIP_SEGMENTS` has no resolver implementation

- **Severity:** P1
- **Evidence:** `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt:28,53,64` (enum decls); `ResolverOrchestrator.kt:45` (only producer). Repo-wide search for `SkipSegmentResolver`/`class.*SkipSegment`/`SkipResolver` returns zero matches.
- **Violated contract:** Path 12 — PLAYER depth via canonical facade should fan skip segments through `SkipSegmentResolver`.
- **User-visible impact:** `metadata.resolver_schedule` lists `SKIP_SEGMENTS` as `scheduled` (misleading — implies a resolver ran when none exists). `MetadataResolutionResult.resolverSchedule.networkResolvers` is computed but never iterated.
- **Required fix:** Either (a) introduce a `SkipSegmentResolver` interface + `ResolvedField.SKIP_INTERVALS`, plumb into `FieldResolver.resolve(...)`, and have facade dispatch `resolverSchedule.networkResolvers`; or (b) remove `SKIP_SEGMENTS` from `ResolverType` and document `SkipIntroRepository` as canonical surface.
- **Test or report that should catch it:** Architecture/golden test asserting every `ResolverType` value has a corresponding resolver class.
- **Cross-references:** F-B-04 (orchestrator schedule output never dispatched).

### F-12-02: Skip-segment fetch bypasses the canonical metadata facade

- **Severity:** P1
- **Evidence:** Caller `PlayerRuntimeControllerObservers.kt:443-498` (`fetchSkipIntervals`) invoked from `PlayerRuntimeControllerStreams.kt:777` and `:316,391`. Repository `SkipIntroRepository.kt:97-105` and arbiter `:26-43`. Providers under `data/integration/skip/`.
- **Violated contract:** Path 12 — same anti-pattern as F-B-03/F-04-03/F-05-02..04.
- **User-visible impact:** The PLAYER-depth claim that "SKIP_SEGMENTS is scheduled" is true at the schedule-emission level but false at the dispatch level. Premium/multi-provider ownership rules cannot apply.
- **Required fix:** Couples to F-12-01 fix (a). Move `SkipProviderArbiter` logic into `MetadataRouter`; have controller read `SkipInterval`s off the resolved document; the in-memory `ConcurrentHashMap` cache on `SkipIntroRepository` becomes redundant once skip is a `ResolvedField`.
- **Test or report that should catch it:** Path 12 trace assertion.

## P2 — follow-up

### F-A-02: Single-flight only applied to CacheFirst path

- **Severity:** P2
- **Evidence:** `app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt:482` (sole caller of `singleFlight.run`); `executeWithoutCache`/`executeMutation`/`callInternal`/`openInternal`/`executeProviderLoad` lack coalescing.
- **Violated contract:** Single-flight join across concurrent identical operations (intended for non-cache paths).
- **User-visible impact:** Concurrent identical specs issue duplicated upstream requests; for mutations (rapid retry of Trakt scrobble POSTs) this can produce double-write outcomes.
- **Required fix:** Extend `IntegrationSingleFlight` to key on `operationKey + scope.storageKey` for non-cache paths with explicit opt-out for mutations that must duplicate; or document the contract narrowing.
- **Test or report that should catch it:** Companion to F-TM-02 — parameterised single-flight regression test exercising each policy branch + `call` + `open`.

### F-B-01: PREVIEW path constructs `ResolvedMetadataDocument` outside `FieldResolver`

- **Severity:** P2
- **Evidence:** `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:157-169` — `HomeDisplayMetadata.toResolvedDocument()` builds a doc with `fieldOwners = emptyMap()`, bypassing `FieldResolver`. Reached from PREVIEW branch (`:37-47`).
- **Violated contract:** "FieldResolver.resolve is the SOLE final field owner".
- **User-visible impact:** PREVIEW-depth `ResolvedMetadataDocument` has no provenance; validators relying on `fieldOwners` see an empty map.
- **Required fix:** Either route PREVIEW through `FieldResolver.resolve(primary = MetadataCandidate(provider = ADDON, fields = …), secondary = emptyList())` or formalise the carve-out via a separate `AddonDisplayDocument` type.
- **Test or report that should catch it:** `FieldResolverPreviewProvenanceTest` asserting non-empty `fieldOwners` for non-null fields.

### F-B-02: UI-side `FieldResolver()` direct instantiation in fallback facades

- **Severity:** P2
- **Evidence:** `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:98-113` and `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProviderLocalizedMetadataOverlay.kt:62-77` call `FieldResolver()` (no-arg → `NoopRuntimeTraceSink`) and `ProviderPlanRunner(emptySet())`.
- **Violated contract:** B-Q3.2; F-03-01; F-02-02 — singleton-injected resolver bypassed; field-selection trace silenced; adapter set empty so any non-PREVIEW request throws `MissingPlanStepAdapter`.
- **User-visible impact:** When Hilt fails or legacy code paths invoke fallback constructor, ViewModel obtains a non-functional facade; `resolveHomeRequest` extension silently swallows exceptions.
- **Required fix:** Delete both manual fallback constructors; require the facade to be `@Inject`ed (or fail fast). If needed for tests, move into `*test*` source set.
- **Test or report that should catch it:** `FieldResolverInjectionContractTest`; Detekt rule banning `FieldResolver()` and `ProviderPlanRunner(emptySet())` outside `*test*`.
- **Cross-references:** F-03-01, F-02-02.

### F-B-04: `ResolverOrchestrator.schedule` output is event-only, never dispatched

- **Severity:** P2
- **Evidence:** `MetadataRouterFacade.kt:34` calls `resolverOrchestrator.schedule(...)`; `ResolverOrchestrator.kt:19-66` builds `localResolvers`/`networkResolvers` and emits `metadata.resolver_schedule`. Schedule stored on `MetadataResolutionResult.resolverSchedule` but no caller iterates `localResolvers`/`networkResolvers` to dispatch.
- **Violated contract:** Reconciles F-04-02 + RF#13 — orchestrator IS invoked; its schedule is dispatched nowhere.
- **User-visible impact:** Trace mode advertises a `metadata.resolver_schedule` envelope that suggests resolvers will run, but no resolver pipeline is exercised. Validators asserting "every scheduled resolver fires" can't pass.
- **Required fix:** Either (a) wire `ResolverOrchestrator` output into a dispatcher running each `ResolverType` against a registered handler, or (b) demote to a planner-only artifact and remove the trace event.
- **Test or report that should catch it:** Trace validator rule `ScheduledResolversAreDispatched` (or its removal under option b).
- **Cross-references:** F-04-02, F-12-01.

### F-B-06: `MetadataIdentityResolver` does not register negative ID mappings

- **Severity:** P2
- **Evidence:** `MetadataIdentityResolver.kt:43` returns unresolved route on null `lookupResult`; no negative-cache write despite `IdMappingSource.NEGATIVE`/`IdMappingTtlPolicy.NEGATIVE_TTL_MS = 30 days` existing.
- **Violated contract:** Negative-mapping policy exists but is unused.
- **User-visible impact:** Every detail load for tmdb-as-series or tvdb-as-movie conflict re-runs the identity lookup, wasting network and producing duplicate failed `metadata.identity_resolution` events.
- **Required fix:** When `lookupResult == null`, persist `IdMapping(... source = NEGATIVE, evidence = "identity lookup failed")`; check the store at top of `resolve()`.
- **Test or report that should catch it:** `MetadataIdentityResolverNegativeCacheTest`.

### F-C-02: `apiShapeId` literals bypass the `*ApiShapes` policy registry

- **Severity:** P2
- **Evidence:** `TvdbIntegrationProvider.kt:677,686,695,698,701` — five reference-record branches use string literals not in `TvdbApiShapes`. `TraktIntegrationProvider.kt` ~45 sites use literal shape strings (device_code/token_refresh/user.settings/last_activities/watched/hidden_items/episode.history/playback/user.lists/etc.). `SimklIntegrationProvider.kt:128,189,202,215,228` — `simkl.playback`/`simkl.scrobble` literals.
- **Violated contract:** Lane C Contract 2 — policy registry must enumerate the surface area.
- **User-visible impact:** Indirect — calls succeed, but a typo creates a different shape that bypasses per-shape rate limits / circuit breakers; audit reports under-report Trakt and Simkl footprint by ~70%.
- **Required fix:** Move every literal apiShapeId/operationKey into the matching `*ApiShapes` object; add the missing constants. Detekt rule asserting `apiShapeId = …` argument must be a property reference.
- **Test or report that should catch it:** `IntegrationApiShapeRegistryCoverageTest` scanning bytecode for all `IntegrationSpec` constructions.

### F-C-03: ID prefix parser is missing `mal:`, `anilist:`, `anidb:`

- **Severity:** P2
- **Evidence:** `MetadataProviderTargetIds.kt:3-23` declares only `tmdbInt`/`tvdbInt`/`kitsu`. No central helper for `tt`/`mal:`/`anilist:`/`anidb:`. Provider adapters silently return `emptyCandidate(this.provider)` for unknown prefixes.
- **Violated contract:** Lane C Contract 3.
- **User-visible impact:** `mal:31964` direct from a Stremio addon → silent empty candidate, no error, no trace event.
- **Required fix:** Either (a) extend `MetadataProviderTargetIds` with parsers for each prefix, or (b) emit `metadata.identity_resolution { success = false, reason = "unsupported_id_prefix" }` upstream.
- **Test or report that should catch it:** `MetadataProviderTargetIdsAnimePrefixTest`; `MetadataAdapterUnknownPrefixTraceTest`.

### F-C-04: Premium poster providers are not registered as metadata adapters

- **Severity:** P2
- **Evidence:** `RpdbIntegrationProvider.kt:22-57` and `TopPostersIntegrationProvider.kt:24-60` are wrapped in `IntegrationRuntime` but have no `MetadataProviderAdapter` registration; `MetadataPrimaryProvider` enum lists only TMDB/TVDB/KITSU. `PosterRatingsUrlResolver.apply(meta, …)` rewrites `Meta.poster` directly outside `FieldResolver`.
- **Violated contract:** Cross-lane visibility for F-50; functional behaviour correct but trace contract `SecondaryDoesNotOverwritePrimary` cannot validate POSTER overrides.
- **User-visible impact:** Trace bundles can't show "TMDB poster overridden by RPDB"; validators relying on `metadata.field_selected` for POSTER pass-by-default for premium switches.
- **Required fix:** Register `RpdbMetadataProviderAdapter`/`TopPostersMetadataProviderAdapter` under `MetadataPrimaryProvider.RPDB`/`TOP_POSTERS` (or a separate `MetadataArtworkProvider` axis); have `PosterRatingsUrlResolver` produce a `MetadataCandidate` for FieldResolver to merge.
- **Test or report that should catch it:** Path 13 contract test; trace-validator rule `PremiumArtworkOverrideEmitsFieldSelected`.

### F-D-03: `INVALIDATED` and `EVICTED` cache-decision values are dead

- **Severity:** P2
- **Evidence:** `app/src/main/java/com/nexio/tv/core/trace/TraceCacheDecision.kt:11-12` declares both; zero production emissions. `IntegrationOrphanCleanupService.cleanupIfOrphaned` and `LocalIntegrationCacheStore.deleteOwnedMedia` delete blob+rows but don't emit through `RuntimeTraceSink`.
- **Violated contract:** Lane D Contract 4.
- **User-visible impact:** Audit consumers can't distinguish orphan-cleanup from a normal cache miss; CI rules gating on `EVICTED` have nothing to count.
- **Required fix:** Either (a) add `IntegrationCacheStore.delete(cacheKey, reason)` overload that emits `EVICTED` via `RuntimeTraceSink`; have `IntegrationOrphanCleanupService` call this; add `INVALIDATED` emit on overwrite. Or (b) drop the values from `TraceCacheDecision`.
- **Test or report that should catch it:** Architecture/golden test asserting every `TraceCacheDecision` is emitted from at least one production code path.

### F-D-04: `MetadataCacheKeys.localized` is unused — providers build localized keys ad-hoc

- **Severity:** P2
- **Evidence:** `MetadataCacheKeys.localized(...)` has zero production callers (only `MetadataCacheKeysTest`); localized keys built inline by individual providers.
- **Violated contract:** Lane D Contract 6 — structurally satisfiable but not centrally enforced.
- **User-visible impact:** A future policy-version bump must be remembered independently per provider; missing one silently serves the previous policy's payload as current.
- **Required fix:** Migrate provider-side localized cache key builders to call `MetadataCacheKeys.localized(...)`; add per-provider unit tests asserting key contains `:lang:<expected>` and `:policy:<expected-version>`. Or delete the helper if dead.
- **Test or report that should catch it:** Architecture rule scanning provider adapter sources for literal `lang:` + `policy:` near `cacheKey =` declarations.

### F-D-06: `IntegrationBackoffManager` has no exponential schedule, no clear-on-success

- **Severity:** P2
- **Evidence:** `IntegrationBackoffManager.kt:19` — fixed `2_000L`/`5_000L`; no growth term, no jitter, no successful-call clear; no `consecutiveFailures` counter.
- **Violated contract:** Test matrix Contract 3 (partial).
- **User-visible impact:** Flapping providers spend full window blocked even after success; long-running rate-limits unblock after 5s and re-trip immediately.
- **Required fix:** (a) Exponential backoff with cap and jitter; (b) explicit `clear(provider, scope)` from `executeProviderLoad` on success; (c) honor explicit zero `Retry-After`.
- **Test or report that should catch it:** Extend `IntegrationBackoffManagerTest`.

### F-E-04: `Kitsu` localized fetch is single-language; English fallback contract is not enforceable

- **Severity:** P2
- **Evidence:** `KitsuMetadataProviderAdapter.kt:33-65` fetches `fetchEnrichment(...)` once; language-fallback delegated to Kitsu API response shape.
- **Violated contract:** None hard-violated; latent fragility for Kitsu Contracts 1/3.
- **User-visible impact:** For obscure regional content where Kitsu only returns one locale, the canonical title (often Japanese romaji) shows with no English fallback.
- **Required fix:** Either (a) document the design and add `LocalizationPolicy.kitsu(...)` field `fallbackLanguageEmbeddedInResponse: Boolean = true`, or (b) issue a second Kitsu fetch in the requested locale when missing.
- **Test or report that should catch it:** `KitsuMetadataProviderAdapterMissingLocaleTest`.

### F-F-02: Profile-switch rejection short-circuits before enforcer, so no `profile.boundary_check` trace event fires

- **Severity:** P2
- **Evidence:** `ProfileManager.kt:113-119` constructs `ProfileBoundaryException` inline without invoking `ProfileBoundaryEnforcer.validateRequest` (the only emitter of `profile.boundary_check`).
- **Violated contract:** Trace observability axiom — any boundary verdict (PASS/FAIL) should be observable.
- **User-visible impact:** Trace bundles uploaded after a "tried to switch profiles during playback" report contain zero `profile.boundary_check` events tagged FAIL with `PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK`.
- **Required fix:** Either (a) move the playback check into `ProfileBoundaryEnforcer.assertCanSwitchProfile(...)` wrapping in the same `emitBoundaryCheck` pattern, or (b) inline a `traceSink.emit(...)` in `ProfileManager`.
- **Test or report that should catch it:** Trace validator rule pairing every `ProfileBoundaryException` construction with a `profile.boundary_check` event in the same `traceSessionId`.
- **Cross-references:** F-10-2.

### F-F-03: `ProfileMetadataOverlay` defined but unused in production

- **Severity:** P2
- **Evidence:** `ProfileMetadataOverlay.kt:5-17` and `ProfileResolvedDisplayDocument.kt:3-11` defined; zero consumer call sites. Only test reference: `CompositionTypeShapeTest:20-26`.
- **Violated contract:** None directly — dead code surface; boundary-map Q4 deferred refactor.
- **User-visible impact:** None today. Latent risk during future overlay-routing refactor.
- **Required fix:** Either (a) wire as the single output type of profile-overlay composition (preferred), or (b) delete and document the deferral.
- **Test or report that should catch it:** "Dead types" report; Lane J pickup if it survives one more cycle.

### F-F-04: Reactive `dataStore.activeProfileId.collect` silently ignores switch during playback

- **Severity:** P2
- **Evidence:** `ProfileManager.kt:84-101` — `Log.w` and `return@collect` instead of throwing or deferring; no trace event.
- **Violated contract:** Lane F Contract 5 (asymmetric — imperative throws, reactive logs).
- **User-visible impact:** Sibling-device push of active profile via Postgres → `ProfileSyncService` is silently ignored; user's profile context appears to spontaneously revert after they expect the push to apply.
- **Required fix:** Either (a) make reactive path throw and let parent scope's `CoroutineExceptionHandler` schedule retry after `playbackSessionRegistry` becomes idle, or (b) enqueue `pendingActiveProfileId` and drain on idle. (b) is the correct UX. Add a `profile.boundary_check` trace event for the deferral.
- **Test or report that should catch it:** `ProfileManagerReactiveSwitchDuringPlaybackTest`.

### F-G-02: `continue_watching.snapshot_read` has no test coverage

- **Severity:** P2
- **Evidence:** `grep -rn "snapshot_read" app/src/test/` returns zero hits. Production emission at `ContinueWatchingSnapshotService.kt:343-355,1366-1386` is correctly wired but un-asserted. Subtle drift: `recordCount` is read at subscribe time, but the actual delivery is gated by `combine + filterNotNull`.
- **Violated contract:** Lane G Contract 5 — production guard exists, no automated guard prevents regression.
- **User-visible impact:** None. Operator: a drift between `snapshot_read.recordCount` and the rendered count would mislead trace-bundle analysis.
- **Required fix:** `ContinueWatchingSnapshotReadTraceTest` asserting envelope shape; either move `emitRead(...)` inside the `combine { ... takeIf { ready } }.filterNotNull()` so recorded count matches delivered, or document the drift.
- **Test or report that should catch it:** Same as required fix; add a validator rule.

### F-G-03: `snapshot_write.recordCount` excludes `traktUpNextItems` — undercount on Trakt-driven rails

- **Severity:** P2 (source P3)
- **Evidence:** `ContinueWatchingSnapshotService.kt:325-328` and `:939-942` compute `recordCount = resumeItems.size + nextUpItems.size`. `ContinueWatchingSnapshot.traktUpNextItems` (`:67-75`) is excluded. `toContinueWatchingRecords()` likewise.
- **Violated contract:** Lane G Contract 4 (loose).
- **User-visible impact:** None directly. Operator: trace-bundle CW counts are not the rendered count; silent deficit equal to `traktUpNextItems.size`.
- **Required fix:** Either (a) include `traktUpNextItems.size` in `recordCount` and add per-rail keys, or (b) extend `toContinueWatchingRecords()` to lift `traktUpNextItems` into records.
- **Test or report that should catch it:** Add to `ContinueWatchingProfileScopedQueryTest`.

### F-H-01: No architecture pin asserting `checkin()` retains the `ownerProfileId: Int? = null` shape

- **Severity:** P2 (source P3)
- **Evidence:** `TrackingScrobbleService.kt:41` retains `Int?` form intentionally. No architecture-pin test enforces this.
- **Violated contract:** Lane H Contract 2 holds today; F-TM-08 unresolved.
- **User-visible impact:** A future "harmonize the scrobble surface" refactor migrating `checkin` to `PlaybackOwnerContext` would compile and pass tests but force every checkin call site to fabricate a context that never existed.
- **Required fix:** `TrackingScrobbleServiceCheckinShapeTest` reflection assertion. Document asymmetry rationale in interface KDoc.
- **Test or report that should catch it:** Same as required fix.

### F-H-02: `PlaybackSessionRegistry` is single-slot — concurrent `PlayerViewModel`s would silently overwrite each other's registration

- **Severity:** P2 (source P3)
- **Evidence:** `PlaybackSessionRegistry.kt:9-29` holds a single `AtomicReference<Entry?>`. `register(context)` always overwrites. Today no callers consume `activeOwner()` so this is latent.
- **Violated contract:** Lane H Contract 4 holds for single-VM case; doesn't state behavior under concurrent VMs.
- **User-visible impact:** None today. Latent if any future feature (e.g., `STALE_SESSION_WRITE_REJECTED` rule per F-H-03) consumes `activeOwner()`.
- **Required fix:** Either (a) document single-slot constraint and pin via architecture test, or (b) replace with `ConcurrentHashMap<String, PlaybackOwnerContext>` and have `activeOwner()` return most recent by `startedAtEpochMs`.
- **Test or report that should catch it:** `PlaybackSessionRegistryConcurrentRegistrationTest`.

### F-I-01: `TraceRedactor` redaction set lags the actual auth surface

- **Severity:** P2
- **Evidence:** `TraceRedactor.kt:4-18`; `TraceRedactorTest.kt` covers only Authorization/api_key/access_token/refresh_token. Missing: `simkl-api-key`, `trakt-api-key`, `simkl-client-id`. Also missing JSON keys `code`, `client_id`, `pin`.
- **Violated contract:** Lane I Contract 4 — header set covers contract list as written; real-world Simkl/Trakt API key headers are not in the set.
- **User-visible impact:** Internal trace bundles uploaded via `TraceBundleExporter` from a debug build (or shared with support) carry plaintext provider API keys for Simkl/Trakt and TVDB pin variants.
- **Required fix:** Extend `redactedHeaders` and `redactedJsonKeys` with Simkl/Trakt header and OAuth POST keys.
- **Test or report that should catch it:** Parameterised header-name fixture in `TraceRedactorTest`; CI lint that `BuildConfig.TRAKT_CLIENT_ID`/Simkl secrets never appear in any captured trace JSONL.

### F-I-04: Negative invariant "bodies absent when `mode != INCLUDE_HTTP_BODIES_INTERNAL_ONLY`" is not asserted by any test

- **Severity:** P2
- **Evidence:** No test exercises an HTTP request through `RuntimeTraceInterceptor` in `INCLUDE_HTTP_SUMMARY` mode and asserts no `trace.body_sample` or body field is emitted.
- **Violated contract:** Lane I Contract 5 — production code correct; regression guard missing.
- **User-visible impact:** A future change removing `&& isInternalBuild` guard or adding a body-emit on a sibling event type would not be caught. Combined with F-I-01, blast radius is "leak provider auth in plaintext to release-channel trace bundles".
- **Required fix:** `RuntimeTraceInterceptorBodyGatingTest` driving each `TraceMode × isInternalBuild` combination.
- **Test or report that should catch it:** Same as required fix; add to audit-task `includeTestsMatching` filter.

### F-I-05: Derived OkHttp clients (`okHttpClient.newBuilder()`) are not pinned by an interceptor-survival test

- **Severity:** P2
- **Evidence:** `NetworkModule.kt:212-298` — Trakt/Simkl/MDBList derived clients via `newBuilder()`; `TraceInterceptorOrderingTest` covers only base client.
- **Violated contract:** Lane I Contract 2 — production wiring correct; regression guard missing.
- **User-visible impact:** A maintainer introducing a new derived client and accidentally calling `OkHttpClient.Builder()` from scratch silently loses tracing for an entire provider.
- **Required fix:** `DerivedOkHttpClientTraceWiringTest` asserting each `@Named` `OkHttpClient` carries the trace interceptors.
- **Test or report that should catch it:** Same as required fix.

### F-J-01: Architecture tests would not have caught the facade-bypass findings reported in Lanes B/C/H

- **Severity:** P2
- **Evidence:** `MetadataRouterBoundaryTest.kt:21-33` whitelists `EpisodeRatingsSelectionRepository.kt`, `TrailerService.kt`, `MetadataSecondaryRepository.kt`, and the three legacy `*MetadataService.kt` files.
- **Violated contract:** "Architecture tests ban legacy code paths" — partial.
- **User-visible impact:** None directly; coverage gap that masks the existence of facade-bypass findings.
- **Required fix:** Either retire the whitelisted services or replace the path-suffix whitelist with a per-symbol allowlist scoped to `core/metadata/router/`.
- **Test or report that should catch it:** Tighter boundary tests; cross-references F-B-03/F-04-03/F-05-02..04/F-12-02.

### F-J-03: `IntegrationScope.Global` is `@Deprecated` but still constructed in production

- **Severity:** P2
- **Evidence:** `OpenSubtitlesHashIntegrationProvider.kt:44` constructs `IntegrationScope.Global`; `ProfileBoundaryEnforcer.kt:39, 301` references the constant.
- **Violated contract:** Deprecation contract — `@Deprecated` items should not have live callers.
- **User-visible impact:** None directly; deprecation hygiene regression.
- **Required fix:** Migrate `OpenSubtitlesHashIntegrationProvider` to one of the explicit `Global*` variants and drop `Global`, or remove the deprecation and document the remaining purpose.
- **Test or report that should catch it:** Architecture rule banning `IntegrationScope.Global` outside enforcer-internal references.

### F-03-03: `loadMeta()` uses Stremio meta-addon as the primary detail source, not the canonical facade

- **Severity:** P2
- **Evidence:** `MetaDetailsViewModel.kt:556-700`; `getMetaFromAllAddons` at `:598/669` or `getMeta` at `:612, 653`.
- **Violated contract:** Path 03 framing — DETAIL_CORE depth fetch goes through facade. The actual primary source is the Stremio addon response; the facade only enriches.
- **User-visible impact:** None today; architectural-intent mismatch.
- **Required fix:** Either (a) make this layering explicit in the Path 03 contract/spec, or (b) flip priority so canonical facade runs first.
- **Test or report that should catch it:** Spec/contract update or facade-first refactor test.

### F-04-04: Detail-screen artwork is collapsed into DETAIL_CORE response, not a separate DETAIL_MEDIA fetch

- **Severity:** P2
- **Evidence:** `MetaDetailsViewModel.kt:1418-1419, 1448-1454` reads `backdrop`/`logo` from `tvEnrichment`/`tmdbEnrichment` (DETAIL_CORE returns). No separate DETAIL_MEDIA round-trip; no `ResolverType.ARTWORK` resolver class.
- **Violated contract:** Path 04 — DETAIL_MEDIA artwork resolver pass.
- **User-visible impact:** None; user-visible behaviour is roughly equivalent because *_CORE responses already include backdrop/logo.
- **Required fix:** Either implement an `ArtworkResolver` running at DETAIL_MEDIA depth, or strike `ResolverType.ARTWORK` from the orchestrator schedule and document artwork as DETAIL_CORE-collapsed.
- **Test or report that should catch it:** Same axis as F-04-01 / F-12-01.

## Nits

- **F-A-03 (Lane A)** — `runtime.cache_decision` events suppressed when sink is Noop; `app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt:66,95`.
- **F-A-04 (Lane A)** — Unused `policy` parameter in `executeObserveOnly`; `DefaultIntegrationRuntime.kt:405-412`.
- **F-B-05 (Lane B)** — `FieldResolver.emitFieldSelected` uses `primary.provider.name` as `contentId`; `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt:74-82`.
- **F-B-07 (Lane B)** — `MetadataRequestNormalizer` swallows `ContentType.TV` into `MediaKind.SERIES` silently; `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizer.kt:39-45`.
- **F-C-05 (Lane C)** — Premium poster cache keys use `apiKey.hashCode()`; `PosterRatingsUrlResolver.kt:127,151`.
- **F-C-06 (Lane C)** — Trakt account-scoped cache keys for global content duplicate per profile; `TraktIntegrationProvider.kt:758-989, 1275-1276`.
- **F-D-05 (Lane D)** — Single-flight key map can leak typed casts on coincidental key collision; `app/src/main/java/com/nexio/tv/core/integration/IntegrationSingleFlight.kt:12,19-22,38`.
- **F-E-05 (Lane E)** — `TvdbEpisodeLocalization.idsMissingLocalizedFields` truncates before sorting — non-determinism risk; `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbEpisodeLocalization.kt:100-108`.
- **F-F-05 (Lane F)** — `validateLegacyAccountScope` is a partial guard — only blocks `profile:` substring, not credential leakage; `app/src/main/java/com/nexio/tv/core/integration/ProfileBoundaryEnforcer.kt:263-271`. Cross-ref F-J-02.
- **F-J-02 (Lane J)** — `IntegrationScope.Account(providerAccountId)` legacy constructor + `validateLegacyAccountScope` deletion candidate; `IntegrationScope.kt:86-100`, `ProfileBoundaryEnforcer.kt:263`.
- **F-J-04 (Lane J)** — `@Deprecated("Use GlobalContent…")` on `IntegrationScope.Global`/`Account(providerAccountId)`/`observeContinueWatchingNextUp` lacks `ReplaceWith` and removal date; `IntegrationScope.kt:36, 86`, `TrackingProgressService.kt`.
- **F-02-01 (Path 02)** — `MetadataRouterFacade.fetchTvEnrichment` reuses Path-01 first-paint snapshot at the router pre-flight site; `HomeViewModelPresentationPipeline.kt:729, 749`. Cross-ref F-I-02.

## Aggregate cross-reference index

| F-NN | Title | Severity | Lane(s) | Source dossier files |
|---|---|---|---|---|
| F-A-01 | Stream open() failures don't register backoff | P1 | A | `lanes/A-runtime-control-plane.md` |
| F-A-02 | Single-flight only on CacheFirst path | P2 | A | `lanes/A-runtime-control-plane.md` |
| F-A-03 | cache_decision events suppressed when Noop sink | Nit | A | `lanes/A-runtime-control-plane.md` |
| F-A-04 | Unused `policy` parameter in `executeObserveOnly` | Nit | A | `lanes/A-runtime-control-plane.md` |
| F-TM-02 | No single-flight regression test | P1 | A | `lanes/A-runtime-control-plane.md`, `08-test-matrix.md` |
| F-B-01 | PREVIEW constructs `ResolvedMetadataDocument` outside `FieldResolver` | P2 | B | `lanes/B-metadata-router.md` |
| F-B-02 | UI-side `FieldResolver()` direct instantiation | P2 | B | `lanes/B-metadata-router.md` (cross-ref `paths/03-detail-core.md` F-03-01, `paths/02-home-visible-item-enrichment.md` F-02-02) |
| F-B-03 | DETAIL_CORE bypasses facade for TMDB enrichment | P1 | B | `lanes/B-metadata-router.md`, `paths/03-detail-core.md` (F-03-02), `red-flags/scan-results.md` (F-RF-01) |
| F-B-04 | `ResolverOrchestrator.schedule` output never dispatched | P2 | B | `lanes/B-metadata-router.md` (cross-ref `paths/04-detail-media.md` F-04-02, `paths/12-skip-segment-lookup.md` F-12-01) |
| F-B-05 | `FieldResolver.emitFieldSelected` uses provider name as contentId | Nit | B | `lanes/B-metadata-router.md` |
| F-B-06 | `MetadataIdentityResolver` doesn't register negative ID mappings | P2 | B | `lanes/B-metadata-router.md` |
| F-B-07 | `MetadataRequestNormalizer` collapses TV→SERIES silently | Nit | B | `lanes/B-metadata-router.md` |
| F-C-01 | TMDB person/company helpers bypass `IntegrationRuntime` | P1 | C | `lanes/C-provider-contracts.md` |
| F-C-02 | `apiShapeId` literals bypass `*ApiShapes` registry | P2 | C | `lanes/C-provider-contracts.md` |
| F-C-03 | ID prefix parser missing `mal:`/`anilist:`/`anidb:` | P2 | C | `lanes/C-provider-contracts.md` |
| F-C-04 | Premium poster providers not registered as metadata adapters | P2 | C | `lanes/C-provider-contracts.md` (cross-ref Path 13 F-50) |
| F-C-05 | Premium poster cache keys use `apiKey.hashCode()` | Nit | C | `lanes/C-provider-contracts.md` (cross-ref Path 13 F-53) |
| F-C-06 | Trakt account-scoped cache keys duplicate per profile for global content | Nit | C | `lanes/C-provider-contracts.md` |
| F-D-01 | 429/5xx in cache-miss path returns Missing instead of stale | P1 | D | `lanes/D-cache-backoff.md` |
| F-D-02 | Cache write is non-atomic — blob and Room row updated separately | P1 | D | `lanes/D-cache-backoff.md` |
| F-D-03 | `INVALIDATED`/`EVICTED` cache-decision values are dead | P2 | D | `lanes/D-cache-backoff.md` |
| F-D-04 | `MetadataCacheKeys.localized` unused — providers build keys ad-hoc | P2 | D | `lanes/D-cache-backoff.md` (cross-ref Lane E) |
| F-D-05 | Single-flight key map can leak typed casts on key collision | Nit | D | `lanes/D-cache-backoff.md` |
| F-D-06 | `IntegrationBackoffManager` no exponential schedule, no clear-on-success | P2 | D | `lanes/D-cache-backoff.md`, `08-test-matrix.md` (F-TM-01) |
| F-E-01 | `LocalizedEpisodeBundle` per-episode fallback counter not surfaced to trace | P1 | E | `lanes/E-localization.md` |
| F-E-02 | `metadata.localization_plan` event has no production emission site | P1 | E | `lanes/E-localization.md`, `06-trace-validator-audit` (F-06-01) |
| F-E-03 | `metadata.field_selected` not emitted for per-episode title/overview | P1 | E | `lanes/E-localization.md`, `06-trace-validator-audit` (F-06-02) |
| F-E-04 | Kitsu single-language fetch — English fallback contract not enforceable | P2 | E | `lanes/E-localization.md` |
| F-E-05 | `TvdbEpisodeLocalization.idsMissingLocalizedFields` truncates before sorting | Nit | E | `lanes/E-localization.md` |
| F-F-01 | UI callers don't catch `ProfileBoundaryException` — crash risk | P0 | F | `lanes/F-profile-boundaries.md`, `paths/10-profile-switch.md` (F-10-1) |
| F-F-02 | Profile-switch rejection bypasses enforcer — no `profile.boundary_check` event | P2 | F | `lanes/F-profile-boundaries.md`, `paths/10-profile-switch.md` (F-10-2) |
| F-F-03 | `ProfileMetadataOverlay` defined but unused in production | P2 | F | `lanes/F-profile-boundaries.md`, boundary-map Q4 |
| F-F-04 | Reactive `dataStore.activeProfileId.collect` silently ignores playback | P2 | F | `lanes/F-profile-boundaries.md` |
| F-F-05 | `validateLegacyAccountScope` is a partial guard | Nit | F | `lanes/F-profile-boundaries.md` (cross-ref `lanes/J-legacy-deletion.md` F-J-02) |
| F-G-01 | Profile-scoped CW read API has zero production callers | P1 | G | `lanes/G-continue-watching.md`, `red-flags/scan-results.md` (F-RF-03) |
| F-G-02 | `continue_watching.snapshot_read` has no test coverage | P2 | G | `lanes/G-continue-watching.md` |
| F-G-03 | `snapshot_write.recordCount` excludes `traktUpNextItems` | P2 | G | `lanes/G-continue-watching.md` |
| F-H-01 | No architecture pin asserting `checkin()` retains `ownerProfileId: Int? = null` | P2 | H | `lanes/H-playback-scrobble.md`, `08-test-matrix.md` (F-TM-08) |
| F-H-02 | `PlaybackSessionRegistry` is single-slot — concurrent VMs overwrite | P2 | H | `lanes/H-playback-scrobble.md` |
| F-H-03 | No result-time `assertCanWriteProfileState` re-check on scrobble completion | P0 | H | `lanes/H-playback-scrobble.md` (cross-ref Path 11) |
| F-I-01 | `TraceRedactor` redaction set lags actual auth surface | P2 | I | `lanes/I-trace-mode.md`, `08-test-matrix.md` (F-TM-09) |
| F-I-02 | First-paint emission wired to router pre-flight, not canonical boundary | P1 | I, B | `lanes/I-trace-mode.md`, `paths/01-home-row-preview.md` (F-01), `paths/02-home-visible-item-enrichment.md` (F-02-01), `red-flags/scan-results.md` (F-RF-02) |
| F-I-03 | `RuntimeTraceValidatorRealEmissionTest` excluded from audit-task filter | P1 | I | `lanes/I-trace-mode.md`, `06-trace-validator-audit/SUMMARY.md` |
| F-I-04 | "bodies absent when mode != INCLUDE_HTTP_BODIES_INTERNAL_ONLY" not asserted | P2 | I | `lanes/I-trace-mode.md`, `08-test-matrix.md` (F-TM-10) |
| F-I-05 | Derived OkHttp clients not pinned by interceptor-survival test | P2 | I | `lanes/I-trace-mode.md`, `08-test-matrix.md` (F-TM-11) |
| F-J-01 | Architecture tests would not have caught facade-bypass findings | P2 | J | `lanes/J-legacy-deletion.md` |
| F-J-02 | `IntegrationScope.Account(providerAccountId)` deletion candidate | Nit | J | `lanes/J-legacy-deletion.md` (cross-ref F-F-05) |
| F-J-03 | `IntegrationScope.Global` `@Deprecated` but still constructed | P2 | J | `lanes/J-legacy-deletion.md` |
| F-J-04 | `@Deprecated` markers lack `ReplaceWith`/removal date | Nit | J | `lanes/J-legacy-deletion.md` |
| F-03-03 | `loadMeta()` uses Stremio meta-addon as primary detail source | P2 | B (Path 03) | `paths/03-detail-core.md` |
| F-04-01 | `MetadataDepth.DETAIL_MEDIA` has no production caller | P1 | B (Path 04) | `paths/04-detail-media.md` |
| F-04-03 | Trailer enrichment bypasses canonical metadata facade | P1 | B (Path 04) | `paths/04-detail-media.md` |
| F-04-04 | Detail-screen artwork collapsed into DETAIL_CORE response | P2 | B (Path 04) | `paths/04-detail-media.md` |
| F-05-01 | `MetadataDepth.DETAIL_SECONDARY` has no production caller | P1 | B (Path 05) | `paths/05-detail-secondary.md` |
| F-05-02 | Reviews fetched via direct repository, bypassing facade | P1 | B (Path 05) | `paths/05-detail-secondary.md` |
| F-05-03 | Recommendations fetched via direct repository, bypassing facade | P1 | B (Path 05) | `paths/05-detail-secondary.md` |
| F-05-04 | Cast/crew/company enrichment bypasses canonical chain | P1 | B (Path 05) | `paths/05-detail-secondary.md` |
| F-12-01 | `ResolverType.SKIP_SEGMENTS` has no resolver implementation | P1 | B (Path 12) | `paths/12-skip-segment-lookup.md` |
| F-12-02 | Skip-segment fetch bypasses canonical metadata facade | P1 | B (Path 12) | `paths/12-skip-segment-lookup.md` |
| F-02-01 | First-paint snapshot reused at router pre-flight site | Nit | I (Path 02) | `paths/02-home-visible-item-enrichment.md` (cross-ref F-I-02) |

### Folded duplicates (cross-referenced; not separately enumerated)

| Folded F-NN | Folded into | Reason |
|---|---|---|
| F-01 (paths/01) | F-I-02 | Same root: first-paint emission misplaced |
| F-02-02 (paths/02) | F-B-02 | Same root: manual fallback facade with `emptySet()` adapters and noop trace |
| F-03-01 (paths/03) | F-B-02 | Same root: `MetaDetailsViewModel` direct `FieldResolver()` + adapter-empty facade |
| F-03-02 (paths/03) | F-B-03 | Same root: DETAIL_CORE TMDB bypass |
| F-04-02 (paths/04) | F-B-04 | Reconciled: orchestrator IS invoked (RF#13 confirmed); schedule output is never dispatched |
| F-10-1 (paths/10) | F-F-01 | Same root: UI catch missing |
| F-10-2 (paths/10) | F-F-02 | Same root: rejection not observable |
| F-RF-01 (red-flags) | F-B-03 | Cross-ref staged in scan |
| F-RF-02 (red-flags) | F-I-02 | Cross-ref staged in scan |
| F-RF-03 (red-flags) | F-G-01 | Cross-ref staged in scan |
| F-09-1 (paths/09 staged) | F-G-01 | Cross-ref staged in scan |
| F-TM-* (test matrix) | individual lane findings | F-TM-01→F-D-06; F-TM-02→F-A-02/F-TM-02 carried; F-TM-06→F-D-04/F-E-01; F-TM-08→F-H-01; F-TM-09→F-I-01; F-TM-10→F-I-04; F-TM-11→F-I-05 |

## Source dossier files

- Lanes: `lanes/A-runtime-control-plane.md`, `lanes/B-metadata-router.md`, `lanes/C-provider-contracts.md`, `lanes/D-cache-backoff.md`, `lanes/E-localization.md`, `lanes/F-profile-boundaries.md`, `lanes/G-continue-watching.md`, `lanes/H-playback-scrobble.md`, `lanes/I-trace-mode.md`, `lanes/J-legacy-deletion.md`
- Paths: `paths/01-home-row-preview.md`, `paths/02-home-visible-item-enrichment.md`, `paths/03-detail-core.md`, `paths/04-detail-media.md`, `paths/05-detail-secondary.md`, `paths/06-season-tab.md`, `paths/07-player-start.md`, `paths/08-continue-watching-write.md`, `paths/09-continue-watching-render.md`, `paths/10-profile-switch.md`, `paths/11-scrobble.md`, `paths/12-skip-segment-lookup.md`, `paths/13-premium-poster-switch.md`
- Red flags: `red-flags/scan-results.md`
- Test matrix: `08-test-matrix.md`
- Boundary map: `02-architecture-boundary-map.md`
