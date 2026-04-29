# Known Gaps — Post-Cluster-F Audit (Review SHA `774a540f8`)

## Severity classification rules applied

**P0** — Blocks merge. Production code path broken (crash, silent data corruption, cross-profile leak, or a previously-claimed P0 fix that is actually incomplete). A previously-claimed P0 closure that is telemetry-only (no enforcement) is re-opened as P0.

**P1** — Strongly recommended pre-merge. Breaks a named contract, degrades a user-visible feature path, regresses a previously-closed finding, or blocks the next migration cluster.

**P2** — Follow-up. Audit gap, dead code, test quality issue, or consistency gap.

**Nit** — Single-line cleanup; no contract violation.

Severity normalization applied:
- Lane E used MEDIUM/LOW/INFO → MEDIUM=P1, LOW=P2, INFO=Nit
- Lane G used P3 → P3=P2 (behavior-impacting) or Nit (observability-only) depending on risk
- Lane H used P3 → Nit
- Trace agents used mixed conventions → normalized to P0/P1/P2/Nit
- Finding S-01 (scrobble boundary telemetry-only) is the direct continuation of prior-audit F-H-03 (P0); retained at P1 per lane H severity (the blast radius is narrower now that the profile-switch-during-playback crash is fixed)

---

## P0 — merge blockers

### F2-D-01: F-C-06 Trakt global-content specs crash with `ProfileBoundaryException` at spec construction — trending/popular/recommendations/calendar rails dark for all authenticated Trakt users

- **Severity:** P0
- **Lane:** D (cross-ref F)
- **Evidence:** `TraktIntegrationProvider.kt:719, 758, 796, 834, 872, 910` — `fetchCalendarShows`, `fetchTrendingMovies`, `fetchTrendingShows`, `fetchPopularMovies`, `fetchPopularShows`, `fetchRecommendations` all construct `IntegrationSpec` with `scope = accountScope(session)` (→ `IntegrationScope.Account(profileId, TRAKT, credentialHash)`) and `cacheKey = globalContentCacheKey(...)` (→ `"global:provider:TRAKT:$logicalKey"`). `ProfileBoundaryEnforcer.validateAccountScope` → `validateProfileScope` requires `Regex("(^|:)profile:${profileId}(:|$)")` in the key; `"global:provider:TRAKT:..."` does not match. Exception thrown at spec `init` before `runtime.get(spec)`.
- **Violated contract:** Lane F Contract 7 — `IntegrationScope.Account` must pair with `accountCacheKey` (containing `profile:N`); global-content endpoints must use a global-scope/global-cache-key pairing.
- **User-visible impact:** Every authenticated Trakt user who triggers Home trending/popular/recommendations/calendar rails gets a `ProfileBoundaryException` at spec construction. All four rails render dark (exception propagates as `Missing` or crashes the coroutine).
- **Required fix:** Change `scope` for the six global-content Trakt functions from `accountScope(session)` to `IntegrationScope.GlobalContent` and `profileContext = null`. Verify `rejectGlobalScopeForAuthenticatedProvider` does not block (it rejects when `profileContext != null && account != null`; setting `profileContext = null` resolves both). Add a `ProfileBoundaryAuditGoldenTest` scenario exercising authenticated Trakt global-content fetch.
- **Test or report that should catch it:** An end-to-end test using real `DefaultIntegrationRuntime` + real `ProfileBoundaryEnforcer`, two profiles calling `fetchTrendingMovies`, asserting both receive the same cached result. `TraktGlobalContentCacheKeyTest` is a source-grep proxy that does NOT construct a real `IntegrationSpec` — it cannot catch this.
- **Cross-references:** F-D-01 (Lane D primary), F-01 (Lane F angle), D-02, F-04 (boundary audit artifact stale)
- **Cluster F status:** Cluster-F regression. F-C-06 changed the cache key without changing the scope, introducing an irreconcilable scope/key pairing that `ProfileBoundaryEnforcer` correctly catches.

---

### F2-J-01: `getMetaFromAllAddons()` still called from two Home files; `AddonFirstPaintShapeArchitectureTest` is failing at SHA `774a540f8`

- **Severity:** P0
- **Lane:** J
- **Evidence:** `HomeViewModelContinueWatchingRuntimePipeline.kt:29` and `HomeViewModelPresentationPipeline.kt:474, 548` both contain `getMetaFromAllAddons()` calls. The pin test `AddonFirstPaintShapeArchitectureTest.home hydration does not call addon detail metadata directly` scans `ui/screens/home/**` and finds these calls — it is actively failing.
- **Violated contract:** Lane J Contract 14 — Home hydration must not call `getMetaFromAllAddons()` from any file under `ui/screens/home/`.
- **User-visible impact:** Direct addon detail calls in Home bypass the canonical metadata facade, silencing `metadata.route_decision`, `metadata.provider_plan`, and `metadata.field_selected` trace events for those paths. Architecture test suite fails CI.
- **Required fix:** Route the two call sites in `HomeViewModelContinueWatchingRuntimePipeline.kt` and `HomeViewModelPresentationPipeline.kt` through `MetadataRouterFacade` or a facade-wrapping helper. The architecture pin must pass before merge.
- **Test or report that should catch it:** `AddonFirstPaintShapeArchitectureTest` (currently failing).
- **Cross-references:** J-01, J-07 (non-Home callers also unguarded)
- **Cluster F status:** Pre-existing; the pin scope was broadened in commit `726de12f3` but the production fix was not made.

---

## P1 — strongly recommended pre-merge

### F2-H-01: `checkScrobbleBoundary` is telemetry-only — stale scrobbles are not blocked (incomplete F-H-03 closure)

- **Severity:** P1
- **Lane:** H (cross-ref trace 11)
- **Evidence:** `TraktScrobbleService.kt:294-303` and `SimklScrobbleService.kt:248-258` — `checkScrobbleBoundary` is `Unit`-returning; `enqueueScrobble`/`enqueueCheckin` call it and immediately fall through to `traktMutationOutboxCoordinator.enqueueAndDrain(...)` regardless of mismatch. `ProfileBoundaryEnforcer.assertCanWriteProfileState` (the function that throws) is absent from both scrobble paths.
- **Violated contract:** Lane H Contract 3 — profile-boundary violation must prevent the write. F-H-03 was claimed closed by Cluster B but the enforcement is telemetry-only.
- **User-visible impact:** A scrobble in-flight during a profile switch is credited to the wrong profile's Trakt/Simkl account. Data-integrity risk; no crash.
- **Required fix:** Convert `checkScrobbleBoundary` to throw (or return `Boolean`) and gate `enqueueAndDrain` on the result. Alternatively route through `ProfileBoundaryEnforcer.assertCanWriteProfileState`. Add a unit test asserting the mutation is NOT enqueued on profile mismatch.
- **Test or report that should catch it:** `TraktScrobbleServiceProfileBoundaryTest` / `SimklScrobbleServiceProfileBoundaryTest` asserting no enqueue on mismatch.
- **Cross-references:** H-01, H-02, H-05, S-01 (trace 11), TW-01 (trace 08), F-05 (Lane F)
- **Cluster F status:** Pre-existing incomplete closure (Cluster B claimed it closed; this review confirms it is not).

### F2-H-02: `TrackingProgressService` result-time `assertCanWriteProfileState` re-check absent

- **Severity:** P1
- **Lane:** H
- **Evidence:** `DefaultTrackingProgressService.kt` (entire file) — no call to `assertCanWriteProfileState` or any analogous profile-boundary gate. `DefaultTrackingProgressServiceTest` covers provider-routing but asserts no boundary-rejection behavior.
- **Violated contract:** F-H-03 — result-time re-check on scrobble completion must reject stale writes.
- **User-visible impact:** Late-arriving scrobble result after a profile switch writes to the wrong profile's history.
- **Required fix:** Implement result-time boundary check in the scrobble completion path. Add a test asserting the write is rejected, not just traced.
- **Cross-references:** H-02, F2-H-01
- **Cluster F status:** Pre-existing incomplete closure.

### F2-B-01: `MetadataExecutionAuditGoldenTest.routing rules match spec for all id types` fails — IMDB-as-series misreported as `ROUTING_ID_TYPE_CONFLICT`

- **Severity:** P1
- **Lane:** B
- **Evidence:** `MetadataAuditRunner.kt:582-586` — `if (trace.any { it.reason == ROUTING_ID_TYPE_CONFLICT })` override is too broad. `MetadataIdentityResolver.kt:79` — appends `ROUTING_ID_TYPE_CONFLICT` trace entry on any successful identity resolution, including non-conflict routes. `MetadataExecutionAuditGoldenTest.kt:130` asserts `ITEM_TYPE_SERIES` but gets `ROUTING_ID_TYPE_CONFLICT`.
- **Violated contract:** Audit golden test gate fails. The audit artifact is incorrect for the `netflix-series`/IMDB scenario.
- **User-visible impact:** CI gate fails. The audit sign-off artifact misreports routing reasons; cannot be used as evidence of routing correctness.
- **Required fix:** Option A — `MetadataIdentityResolver` should not emit `ROUTING_ID_TYPE_CONFLICT` for routes that originated from `ITEM_TYPE_SERIES`/`ITEM_TYPE_MOVIE`. Option B — Fix `MetadataAuditRunner.toAuditEvent` to check `route.reason == ROUTING_ID_TYPE_CONFLICT` not `trace.any { ... }`.
- **Test or report that should catch it:** `MetadataExecutionAuditGoldenTest` (currently failing).
- **Cross-references:** B-01, B-06
- **Cluster F status:** Pre-existing; introduced by parallel-session commit `4427f22ed` (`imdbToTvdb` support).

### F2-A-01: `backoffManager.clear()` not called on successful `call()` or `open()` result

- **Severity:** P1
- **Lane:** A
- **Evidence:** `DefaultIntegrationRuntime.kt:318-320` (`doCallInternal` success branch) and `DefaultIntegrationRuntime.kt:392-395` (`openInternal` success branch) — neither calls `backoffManager.clear(spec.provider, spec.scope)`. Contrast with `executeProviderLoad:632-634` (`get()` path) which does call it.
- **Violated contract:** C-8/C-9 — a successful operation through any runtime entry point resets the backoff block.
- **User-visible impact:** For providers like YOUTUBE_TRAILER and SUBTITLE_SOURCE_DOWNLOAD (exclusively `call()`/`open()` paths), a single 429/5xx that triggers backoff via a `get()` path will lock out the `call()`/`open()` paths until the window expires, silently missing subtitles or trailer playback.
- **Required fix:** In `doCallInternal`, add `backoffManager.clear(spec.provider, spec.scope)` in the `is IntegrationCallResult.Success` branch. In `openInternal`, add it after the success `spec.open()` call. Mirror `executeProviderLoad:632-634`.
- **Test or report that should catch it:** New test in `IntegrationCallRuntimeTest`: "successful call clears backoff for provider-scope."
- **Cross-references:** A-01
- **Cluster F status:** Pre-existing (not introduced by Cluster F).

### F2-C-01: `TmdbApiShapes.COLLECTION` dead constant AND `loadMovieCollection()` bypasses `IntegrationRuntime`

- **Severity:** P1
- **Lane:** C
- **Evidence:** `TmdbIntegrationProvider.kt:1351-1362` — `loadMovieCollection()` uses `loadResponse(request = "tmdb_collection", ...)` directly, not `runtime.get(IntegrationSpec(apiShapeId = TmdbApiShapes.COLLECTION, ...))`. The shape constant exists but has no production caller.
- **Violated contract:** Lane C Contract 1 — all production HTTP calls to a third-party provider go through `IntegrationRuntime`. No backoff, no single-flight, no audit trail for collection endpoint.
- **User-visible impact:** Movie collection data is invisible to audit, backoff, and trace. A rate-limited TMDB response on the collection endpoint is not backed off or deduplicated.
- **Required fix:** Migrate `loadMovieCollection()` to `runtime.get(IntegrationSpec(apiShapeId = TmdbApiShapes.COLLECTION, ...))` via the `tmdbRuntimeGet()` pattern.
- **Test or report that should catch it:** `generateIntegrationRuntimeAudit` would gain a new covered call. `IntegrationApiShapeRegistryCoverageTest` would then have a caller to detect.
- **Cross-references:** C-01
- **Cluster F status:** Pre-existing (missed by F-C-02 sweep).

### F2-J-02: `checkin()` callers never supply `ownerProfileId`; implicit profile context, no architecture pin

- **Severity:** P1
- **Lane:** J (cross-ref H)
- **Evidence:** `HomeViewModelContinueWatching.kt:590` and `MetaDetailsViewModel.kt:3076` both call `trackingScrobbleService.checkin(item)` without `ownerProfileId`. Downstream Trakt and Simkl scrobble services route through account-scoped integration calls. No architecture pin verifies this.
- **Violated contract:** Lane H Contract 10 — caller-side intent must be explicit; ambient profile fallback is undocumented.
- **User-visible impact:** A profile switch between the UI action firing and `currentState()` being read may credit the checkin to the post-switch profile.
- **Required fix:** Either document the ambient-profile fallback with `// ARCHITECTURE` comments at both call sites and add a caller-side pin, or surface the active profileId from callers that have it.
- **Cross-references:** J-02, H-03
- **Cluster F status:** Pre-existing.

### F2-J-03: `ResolvedMetadataDocument` direct construction is not pinned — any file could bypass field-resolution provenance

- **Severity:** P1
- **Lane:** J (cross-ref B)
- **Evidence:** Two construction sites at `FieldResolver.kt:250` and `MetadataRouterFacade.kt:69` are correct, but no architecture pin prevents a third site from appearing. `FieldResolverInjectionContractTest` (F-B-02 pin) covers `FieldResolver()` and `ProviderPlanRunner(emptySet())` but not `ResolvedMetadataDocument(`.
- **Violated contract:** Ownership of `ResolvedMetadataDocument` construction must be confined to `FieldResolver` and `MetadataRouterFacade`.
- **User-visible impact:** A future developer could inject a synthetic `ResolvedMetadataDocument` bypassing field ownership rules.
- **Required fix:** Add an architecture pin test scanning production sources for `ResolvedMetadataDocument(` and asserting only the two allowed sites.
- **Cross-references:** J-03, B-05
- **Cluster F status:** Pre-existing.

### F2-I-01: `TraceSession.gitSha` is always `null` in production — trace bundle stamps null SHA

- **Severity:** P1
- **Lane:** I
- **Evidence:** `RuntimeTraceModule.kt:41` — `gitSha = null`. `TraceBundleExporter.kt:92` — `"gitSha" to session.gitSha` produces `"gitSha": null` in `app-build-info.json`. No `buildConfigField` captures the current commit SHA.
- **Violated contract:** C-4 — bundle provenance: support engineers cannot correlate a trace bundle to a specific commit.
- **User-visible impact:** Trace bundles from buggy builds cannot be matched to the exact source code. Diagnostic utility reduced.
- **Required fix:** Add `buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")` in `app/build.gradle.kts`; wire into `provideTraceBuildInfo()`. Also fix the audit stamp SHA inconsistency (SHA `9f0555a5a` vs review SHA `774a540f8`).
- **Test or report that should catch it:** `TraceBuildInfoGitShaTest` asserting non-null gitSha in exported bundle.
- **Cross-references:** I-06, Stage-1 SHA inconsistency note
- **Cluster F status:** Pre-existing.

### F2-E-01: `TvdbLanguageMapper` silently collapses unsupported locales to English with no diagnostic event

- **Severity:** P1 (MEDIUM → P1)
- **Lane:** E
- **Evidence:** `TvdbLanguageMapper.kt` — any locale outside the 7-language whitelist (English, Spanish, French, German, Dutch, Simplified Chinese, Traditional Chinese) falls through `else` → returns `"eng"`. No `metadata.localization_plan` field or separate event records this collapse.
- **Violated contract:** Localization policy observability — unsupported locale produces `requestedIsFallback = true` silently; per-episode fetch is skipped without any trace signal.
- **User-visible impact:** Italian, Portuguese, Polish, Russian, Korean, Arabic, Japanese users see English episode titles/overviews with no explanation.
- **Required fix:** Add a `isCollapsedToFallback: Boolean` field to `NormalizedLanguage` or emit a `metadata.localization_plan` field (e.g., `localeCollapsedToFallback: true`) when `normalize()` triggers the `else` branch. Or expand mapper to pass through ISO-639-3 codes for all TVDB-supported languages.
- **Cross-references:** E-01
- **Cluster F status:** Pre-existing.

### F2-E-03: `RuntimeTraceValidatorRealEmissionTest` does not exercise `LocalizationPlanPrecedesProviderSteps` with real TVDB episode-path emissions

- **Severity:** P1 (MEDIUM → P1)
- **Lane:** E (cross-ref I)
- **Evidence:** `RuntimeTraceValidatorRealEmissionTest.kt` drives `MetadataRouter.route()` for `kitsu:7442` only; does not drive `TvdbMetadataProviderAdapter.execute()` with `SERIES_EPISODES_LANGUAGE`. The validator rule uses payload key `"provider"` from `localization_plan` — if the key is renamed, neither the isolation tests nor the real-emission test would catch it.
- **Violated contract:** C-7 — end-to-end schema coherence between emission and rule.
- **Required fix:** Extend `RuntimeTraceValidatorRealEmissionTest` with a TVDB episode-bundle scenario that emits both `emitLocalizationPlan` and per-episode `emitFieldSelected`, then validates `LocalizationPlanPrecedesProviderSteps` PASS.
- **Cross-references:** E-03, I-12
- **Cluster F status:** Pre-existing.

### F2-F-05: Scrobble boundary check is observational only — stale scrobble is logged but not blocked (Lane F angle of F2-H-01)

- **Severity:** P1
- **Lane:** F
- **Evidence:** `TraktScrobbleService.checkScrobbleBoundary:294` and `SimklScrobbleService.checkScrobbleBoundary:248` both call `emitScrobbleRejected(...)` but do NOT throw and do NOT return early. `enqueueCheckin` and `enqueueScrobble` immediately follow.
- **Violated contract:** Lane F Contract 6 — scrobble operations carry the playback-owner profile ID and must be rejected if it no longer matches the active profile.
- **Required fix:** Same as F2-H-01.
- **Cross-references:** F-05, F2-H-01, S-01

### F2-I-06: `SecondaryDoesNotOverwritePrimary` validator rule has inverted semantics — false positives on primary-wins scenarios

- **Severity:** P1
- **Lane:** I (cross-ref trace 13)
- **Evidence:** `TraceValidationRules.SecondaryDoesNotOverwritePrimary:171-185` — fires when `rejectedCandidates` is non-empty for protected fields, regardless of whether the winner was primary or secondary. A primary-wins scenario with rejected secondary candidates produces a false-positive FAIL verdict.
- **Violated contract:** C-5 — validator audit correctness. A true secondary-overwrite bug where `rejectedCandidates` happens to be empty would be missed (false negative).
- **Required fix:** Add `sourceRole == "SECONDARY"` (or equivalent) to the filter condition. Add a test case asserting primary-wins-with-competition produces PASS.
- **Cross-references:** I-08, 13-D
- **Cluster F status:** Pre-existing.

### F2-D-08: `LocalIntegrationCacheStore.deleteOwnedMedia` blob+DAO delete is non-transactional

- **Severity:** P1
- **Lane:** D
- **Evidence:** `LocalIntegrationCacheStore.kt:79-85` — `ownedEntries.forEach { entry -> blobStore.delete(entry.blobPath) }` then `cacheDao.deleteByMediaKey(mediaKey)`. A process kill between blob delete and DAO delete leaves an orphaned Room row pointing at a non-existent blob. Over time, orphaned rows accumulate.
- **Violated contract:** F-D-02 atomicity fix addressed `write`; `deleteOwnedMedia` was not included.
- **Required fix:** Run the DAO delete first (so a crash leaves a dangling blob rather than a dangling DB row), or wrap in a transaction.
- **Cross-references:** D-08
- **Cluster F status:** Pre-existing; F-D-02 fix did not cover deletion.

### F2-I-07: Trace settings UI reachable by any user in all builds — no dev-mode gate

- **Severity:** P1
- **Lane:** I
- **Evidence:** `PlaybackSettingsSections.kt:635-642` — "Runtime & Metadata Trace" entry rendered unconditionally with no `BuildConfig.DEBUG` guard. All four `TraceMode` values (including `INCLUDE_HTTP_BODIES_INTERNAL_ONLY`) are exposed in the picker without restriction. Body emission is gated at the interceptor level, but URL + headers are captured in `INCLUDE_HTTP_SUMMARY` for any user on a retail device.
- **Violated contract:** C-10 — access control for trace settings.
- **User-visible impact:** Privacy / data exposure risk on release builds. Non-technical retail TV users can enable HTTP trace capture.
- **Required fix:** Option A (preferred): gate the entry with `if (BuildConfig.IS_DEBUG_BUILD)`. Option B: restrict available modes to `OFF` and `SAFE_METADATA_RUNTIME` in non-debug builds.
- **Cross-references:** I-07
- **Cluster F status:** Pre-existing.

---

## P2 — follow-up

### F2-B-02: `FieldSelectedTraceTest.secondary field rejected` fails — rejection reason wording mismatch

- **Severity:** P2
- **Lane:** B
- **Evidence:** `FieldSelectedTraceTest.kt:70` asserts `contains("primary")`; `FieldResolver.kt:355` emits `"field already filled"`.
- **Required fix:** Update test assertion to `"field already filled"` or update `FieldResolver` to use a reason including "primary".
- **Cross-references:** B-02

### F2-B-03: `MetadataRouterPrecedenceTest.provider native id type conflict` fails — stale test expectation after `buildTargetIds` refactor

- **Severity:** P2
- **Lane:** B
- **Evidence:** `MetadataRouterPrecedenceTest.kt:153-157` expects `targetIds[TVDB] == "tmdb:1399"` but commit `5468aba18` moved TMDB-scheme IDs to the TMDB key. Production routing is correct; test is stale.
- **Required fix:** Update test: `assertEquals("tmdb:1399", route.targetIds[MetadataPrimaryProvider.TMDB])`, `assertNull(route.targetIds[MetadataPrimaryProvider.TVDB])`, `assertTrue(route.targetIdRequiresIdentityResolution)`.
- **Cross-references:** B-03

### F2-B-04: `MetadataRouterTargetIdsImdbTest` x2 failures — `previewStableIds` routing WIP committed red

- **Severity:** P2
- **Lane:** B
- **Evidence:** Two tests in `MetadataRouterTargetIdsImdbTest.kt` depend on behavior not yet fully implemented for `previewStableIds` routing. Committed red.
- **Required fix:** Implement missing routing logic or mark `@Ignore` with tracking note.
- **Cross-references:** B-04

### F2-B-05: No architecture pin guards `ResolvedMetadataDocument` construction to only `FieldResolver` and `MetadataRouterFacade` (P2 companion to F2-J-03)

- **Severity:** P2
- **Lane:** B
- **Evidence:** `grep -rn "ResolvedMetadataDocument(" app/src/main` finds exactly 2 sites; no pin prevents a third.
- **Required fix:** Create `app/src/test/java/com/nexio/tv/architecture/ResolvedMetadataDocumentOwnershipTest.kt`.
- **Cross-references:** B-05, J-03

### F2-C-02: Six dead constants in `IntegrationApiShapes.kt` for unimplemented features

- **Severity:** P2
- **Lane:** C
- **Evidence:** `KitsuApiShapes.ADVANCED_DETAIL`, `TraktApiShapes.COLLECTION_MOVIES`, `TraktApiShapes.COLLECTION_SHOWS`, `YouTubeTrailerApiShapes.DEVICE_CODE`, `YouTubeTrailerApiShapes.TOKEN`, `MDBListApiShapes.USER` — zero production callers. F-C-02 pin does not check whether a constant has a caller.
- **Required fix:** Add `// TODO(<ticket>): unimplemented` to planned-but-not-implemented constants; remove constants with no planned use.
- **Cross-references:** C-02

### F2-C-03: `fetchPopularLists` uses `accountCacheKey` for a global endpoint (missed in F-C-06)

- **Severity:** P2
- **Lane:** C
- **Evidence:** `TraktIntegrationProvider.kt:956-963` — `fetchPopularLists()` calls `accountCacheKey(session, "trakt:popular:lists:...")`. The underlying `GET /lists/popular` endpoint is fully public/non-personalized. F-C-06 migrated 6 functions but missed this one.
- **Required fix:** Change to `globalContentCacheKey("trakt:popular:lists:page:$page:limit:$limit")`.
- **Cross-references:** C-03

### F2-C-06: Auth-service carve-outs in `IntegrationBoundaryTest` lack migration path documentation

- **Severity:** P2
- **Lane:** C
- **Evidence:** `IntegrationBoundaryTest.kt:16-21` exempts `KitsuAuthService`, `RealDebridAuthService`, `SimklAuthService` with no comment or ticket reference. `KitsuAuthService` retains a direct `KitsuAuthApi` call.
- **Required fix:** Add a comment block documenting whether exemptions are permanent (OAuth token exchanges exempt) or temporary (migration pending).
- **Cross-references:** C-06, J-05

### F2-C-07: `MetadataAdapterUnknownPrefixTraceTest` is absent — adapter prefix-rejection behavior unpinned

- **Severity:** P2
- **Lane:** C
- **Evidence:** Test referenced in briefing does not exist. Each adapter has a `?: return emptyCandidate(provider)` guard for unrecognized prefixes but no test verifies this.
- **Required fix:** Create `MetadataAdapterUnknownPrefixTraceTest` verifying that supplying a mismatched prefix causes `emptyCandidate(provider)` return without throwing and without field leaks.
- **Cross-references:** C-07

### F2-C-08: `TmdbApiShapes.SEASON_VIDEOS` in `MetadataProviderAdapterShapeRegistry` but not in adapter's `supports()` set

- **Severity:** P2
- **Lane:** C
- **Evidence:** `MetadataProviderAdapterShapeRegistry.kt:16` includes `TmdbApiShapes.SEASON_VIDEOS`; `TmdbMetadataProviderAdapter.kt:228-238` `tmdbShapes` set does not include it. Dispatcher claims support but adapter returns `false` from `supports()` — silent no-op.
- **Required fix:** Remove from registry if no plan step generates `SEASON_VIDEOS`; if planned, add `TODO` comment and remove from registry until implemented.
- **Cross-references:** C-08

### F2-D-02: `TraktIntegrationProviderRecommendationsTest` expected cache keys are stale post F-C-06

- **Severity:** P2
- **Lane:** D
- **Evidence:** `TraktIntegrationProviderRecommendationsTest.kt:119-127` asserts `"profile:1:provider:TRAKT:credential:trakt-test-1:trakt:trending:movies:limit:20"`. Production code now uses `globalContentCacheKey` → `"global:provider:TRAKT:..."`. Test is stale or failing with empty keys (if spec construction throws D-01).
- **Required fix:** Update expected keys to `globalContentCacheKey` format. Add real end-to-end test per F2-D-01.
- **Cross-references:** D-02

### F2-D-03: F-D-01 regression test uses `ObserveOnly` policy, masking the stale-guard bypass

- **Severity:** P2
- **Lane:** D
- **Evidence:** `DefaultIntegrationRuntimeStaleOn429Test.kt` constructs spec with `cachePolicy = IntegrationCachePolicy.ObserveOnly(...)`. Production `LocalIntegrationCacheStore.readStale` returns `null` immediately for non-`CacheFirst` specs (line 31). The test mocks `readStale` to bypass this guard.
- **Required fix:** Change test spec to `CacheFirst(ttlMs = 60_000L, staleAfterExpiryMs = 60_000L)` with a real/pre-seeded cache store.
- **Cross-references:** D-03

### F2-F-02: `ProfileSwitchDeferralPolicy` not tested for last-writer-wins when multiple reactive switches arrive during playback

- **Severity:** P2
- **Lane:** F
- **Evidence:** `ProfileSwitchDeferralPolicy.onIncomingSwitch` overwrites `pendingActiveProfileId` on each call during playback. Two rapid switches during playback result in only the last one draining. `ProfileManagerReactiveSwitchDuringPlaybackTest` covers only a single incoming switch.
- **Required fix:** Add a test: two consecutive incoming switches during playback; assert only the last one drains. Consider emitting a trace event for discarded pending switches.
- **Cross-references:** F-02

### F2-F-03: `assertCanWriteProfileState` does not emit `profile.boundary_check` trace event on FAIL

- **Severity:** P2
- **Lane:** F
- **Evidence:** `ProfileBoundaryEnforcer.kt:189` — `assertCanWriteProfileState` throws directly without calling `emitBoundaryCheck`. A stale-write rejection produces no trace event. Only `assertCanSwitchProfile` (F-F-02) was wired with explicit trace emission.
- **Required fix:** Add `emitBoundaryCheck(...)` call in `assertCanWriteProfileState` before throw, mirroring `assertCanSwitchProfile`. Add a test for the write-rejection trace emission path.
- **Cross-references:** F-03

### F2-F-04: Profile boundary audit artifact SHA is `9f0555a5a`, not the review SHA `774a540f8`

- **Severity:** P2
- **Lane:** F
- **Evidence:** `review-dossier-2/05-profile-boundary-audit/profile-boundary-report.md` records `Git SHA: 9f0555a5a` and `Git worktree: DIRTY`. The D-01 defect was introduced between these SHAs. No audit scenario exercises authenticated Trakt global-content fetch.
- **Required fix:** Regenerate the profile boundary audit at SHA `774a540f8` after D-01 fix is applied. Add a sixth scenario: authenticated Trakt global-content fetch with `GlobalContent` scope.
- **Cross-references:** F-04

### F2-F-06: `GlobalLocalizedContent` and `GlobalEnglishImage` scopes are defined but never used in production `IntegrationSpec` construction

- **Severity:** P2
- **Lane:** F
- **Evidence:** Grep of all production `IntegrationSpec`, `IntegrationCallSpec`, `IntegrationStreamSpec` constructions shows zero occurrences of `scope = IntegrationScope.GlobalLocalizedContent(...)` or `scope = IntegrationScope.GlobalEnglishImage`. `TraceValidationRules.kt:133` checks `if (scope != "GlobalEnglishImage") return@filter false` — this rule can never match a real runtime event.
- **Required fix:** Either adopt the scopes in provider adapters (migration work) or document that `GlobalContent` is intentionally used for all global specs and deprecate/remove unused scopes including the dead validator rule branch.
- **Cross-references:** F-06

### F2-G-03: `IntegrationOwnershipService.upsertRailMembership` has no outer `@Transaction` wrapper — partial-write window for CW rail

- **Severity:** P2
- **Lane:** G (cross-ref trace 08)
- **Evidence:** `IntegrationOwnershipService.kt:25-38` — steps 2-5 (rail header upsert, items replace, identity upserts) are individually atomic but not jointly atomic. A process kill between step 2 and step 3 leaves an orphaned rail header with no items.
- **Required fix:** Wrap the body of `upsertRailMembership` in a `@Transaction`-annotated DAO method, or promote `replaceRailItems` to also upsert the rail header atomically.
- **Cross-references:** G-03, TW (trace 08 Step 12)

### F2-I-02: `TraceRedactor` F-I-01 additions not reflected in `TraceBundleExporter.redactionManifest()`

- **Severity:** P2
- **Lane:** I
- **Evidence:** `TraceRedactor.kt:10-14` — 10 entries including F-I-01 additions (`simkl-api-key`, `trakt-api-key`, `simkl-client-id`, `x-tvdb-apikey`, `code`, `client_id`). `TraceBundleExporter.kt:72-75` — manifest still lists only the original 6 headers and 12 JSON keys.
- **Required fix:** Add the 4 F-I-01 headers and 2 OAuth body keys to `redactionManifest()`. Add a `TraceRedactorManifestParityTest` asserting manifest equals live `TraceRedactor` sets.
- **Cross-references:** I-01

### F2-I-04: `RuntimeTraceInterceptorBodyGatingTest` missing `SAFE_METADATA_RUNTIME` mode coverage

- **Severity:** P2
- **Lane:** I
- **Evidence:** `RuntimeTraceInterceptorBodyGatingTest.kt` has 5 tests covering `INCLUDE_HTTP_SUMMARY` and `INCLUDE_HTTP_BODIES_INTERNAL_ONLY` combinations. `SAFE_METADATA_RUNTIME` + internal and `SAFE_METADATA_RUNTIME` + non-internal are absent.
- **Required fix:** Add 2 tests asserting no `trace.body_sample` for `SAFE_METADATA_RUNTIME` mode.
- **Cross-references:** I-04

### F2-I-05: `EXPIRED_MISS` and `WRITE` `TraceCacheDecision` values have no validator rule consuming them

- **Severity:** P2
- **Lane:** I (cross-ref D)
- **Evidence:** `DefaultIntegrationRuntime.kt` emits `EXPIRED_MISS` (lines 488, 581) and `WRITE` (line 551). `RuntimeTraceValidator.kt:19-31` counts only `HIT`, `MISS_THEN_NETWORK`, `STALE_HIT`. No rule asserts invariants for the other 4 values.
- **Required fix:** Add `ExpiredMissPrecedesNetworkRequest` rule. Add `EXPIRED_MISS` and `WRITE` counters to `TraceValidationReport`.
- **Cross-references:** I-05, D-05

### F2-I-08: `emitNormalizerWarning` (F-B-07) has no validator rule consuming `metadata.normalizer_warning` events

- **Severity:** P2
- **Lane:** I
- **Evidence:** `TraceMetadataEvents.kt:143-159` emits `"metadata.normalizer_warning"`. `MetadataRequestNormalizer.kt:48` calls it. `TraceValidationRules.ALL` (16 rules) — none reference this event type.
- **Required fix:** Add a `NormalizerWarningHasContentId` rule asserting every `metadata.normalizer_warning` carries a non-blank `contentId` and `reason`. Or document as intentionally uncovered.
- **Cross-references:** I-10

### F2-I-09: `emitScrobbleRejected` production callers exist but no validator rule consumes `playback.scrobble_rejected` events

- **Severity:** P2
- **Lane:** I (cross-ref H, trace 11)
- **Evidence:** `SimklScrobbleService.kt:251` and `TraktScrobbleService.kt:297` call `emitScrobbleRejected`. `TraceValidationRules.ALL` — no rule references `"playback.scrobble_rejected"`. The event name misrepresents the action (write proceeds — see F2-H-01).
- **Required fix:** Add `ScrobbleRejectedHasProfileIds` rule OR rename the event to `"playback.scrobble_boundary_mismatch"` pending enforcement fix.
- **Cross-references:** I-11, S-02, S-03

### F2-I-10: `JsonlTraceWriter.append()` silently swallows `IOException` — storage-full events undetectable

- **Severity:** P2
- **Lane:** I
- **Evidence:** `JsonlTraceWriter.kt:21-30` — uses `PrintWriter` which catches `IOException` internally. `droppedCount()` only increments for byte-cap violations, not IO failures. `eventsDropped()` in UI shows 0 even when IO is failing.
- **Required fix:** Replace `PrintWriter` with `BufferedWriter`; wrap `out.write(line)` in try-catch; increment separate `ioErrors` counter. Expose `ioErrorCount()` in `RuntimeTraceLiveStatusUiState`.
- **Cross-references:** I-09

### F2-J-04: `core/tmdb` and `core/tvdb` on two allowlists with zero actual `IntegrationRuntime`/`IntegrationSpec` uses — pre-granted exemptions

- **Severity:** P2
- **Lane:** J (cross-ref A, F)
- **Evidence:** `NoIntegrationRuntimeInjectionOutsideBoundaryTest.kt:11-17` and `NoRuntimeSpecOutsideIntegrationPackagesTest` both exempt `com.nexio.tv.core.tmdb` and `com.nexio.tv.core.tvdb`. Grep of both packages at SHA `774a540f8` shows zero references to `IntegrationRuntime`, `IntegrationSpec`, or `IntegrationCallSpec`.
- **Required fix:** Remove the allowlist entries or add a companion test asserting neither package contains `IntegrationRuntime` references.
- **Cross-references:** J-04, A-03, F-09

### F2-J-05: Auth-service carve-outs outside integration boundary with no migration plan

- **Severity:** P2
- **Lane:** J
- **Evidence:** `KitsuAuthService.kt:39-40` — direct `api.token(...)` call via `KitsuAuthApi`. `IntegrationBoundaryTest` and `NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest` both exempt the three auth services. No migration plan or target date.
- **Required fix:** Document whether exemptions are permanent policy (OAuth token exchanges exempt) or temporary tech-debt. If migration is planned, open a tracking ticket.
- **Cross-references:** J-05, C-06

### F2-J-06: F-C-06 pin (`TraktGlobalContentCacheKeyTest`) is a fixed-window source-grep proxy — layout-sensitive, cannot detect helper-inlining regressions

- **Severity:** P2
- **Lane:** J
- **Evidence:** `TraktGlobalContentCacheKeyTest` reads up to 2500 chars from each function body and checks for `accountCacheKey(`. `Task 26` had to add a separator comment block to maintain text layout for this test. If a global-content fetch function is refactored such that `accountCacheKey` appears beyond the 2500-char window, the test produces a false negative.
- **Required fix:** Replace the source-grep window approach with a structured test that programmatically parses cache key construction patterns. Or fix F2-C-03 (migrate `fetchPopularLists` to `globalContentCacheKey`), which eliminates the need for the separator.
- **Cross-references:** J-06, C-05

### F2-J-07: `getMetaFromAllAddons()` callers outside `ui/screens/home/` have no facade-bypass pin

- **Severity:** P2
- **Lane:** J
- **Evidence:** `StreamScreenViewModel.kt`, `PlayerRuntimeControllerStreams.kt`, and `IdleScreensaverPreparation.kt` all call `getMetaFromAllAddons()` with no architecture test forbidding this.
- **Required fix:** Extend the facade-bypass pin to cover non-Home callers, or open a tracked task to migrate them.
- **Cross-references:** J-07

### F2-TM-01: Season trailer and recap paths bypass canonical facade — no `metadata.route_decision` or `metadata.resolver_schedule` events

- **Severity:** P2
- **Lane:** Trace 04
- **Evidence:** `MetaDetailsViewModel.kt:2734` (`fetchSeasonTrailer`) and `MetaDetailsViewModel.kt:2656` (`fetchSeasonRecap`) call `TrailerService` methods directly, emitting no metadata trace events.
- **Required fix:** Introduce `MetadataRouterFacade.fetchSeasonTrailer()` / `fetchSeasonRecap()` routing through `resolveRequest(depth = DETAIL_MEDIA)`, or add lightweight trace emit via `TraceMetadataEvents.emitSeasonMediaResolution(...)`.
- **Cross-references:** TM-01

### F2-TM-02: `fetchRecommendations` discards resolver output — recommendations data does not flow through resolver pipeline

- **Severity:** P2
- **Lane:** Trace 05
- **Evidence:** `MetadataRouterFacade.fetchRecommendations(...)` calls `resolveRequest()` (dispatches `RecommendationResolver`) but discards the resolution result and calls `repo.fetchMoreLikeThis(tmdbId, contentType)` directly. `TmdbRecommendationMetadataAdapter` output is silently ignored.
- **Required fix:** Mirror `fetchReviewsPage` harvest pattern — after `resolveRequest()`, collect `ResolvedField.RECOMMENDATIONS` from step results, fall back to `repo.fetchMoreLikeThis()` only when resolver produced no candidates.
- **Cross-references:** DS-01

### F2-TM-03: `OrganizationDetailViewModel` bypasses `MetadataRouterFacade` entirely

- **Severity:** P2
- **Lane:** Trace 05
- **Evidence:** `OrganizationDetailViewModel.kt:*` injects `TmdbOrganizationService` directly. No `MetadataDepth`, no trace events, no `OrganizationPersonResolver` involvement.
- **Required fix:** Route through `MetadataRouterFacade.fetchPersonDetail()` or a dedicated `fetchOrganizationDetail()` facade method.
- **Cross-references:** DS-02

### F2-T13-A: Audit golden test synthesises premium POSTER `FieldSelectedEvent` from harness model, not real adapter output

- **Severity:** P2
- **Lane:** Trace 13
- **Evidence:** `MetadataAuditRunner.kt:204-228` synthesises `FieldSelectedEvent` directly from `scenario.premiumArtworkProvider`. Real `RpdbMetadataProviderAdapter.execute()` is bypassed. A regression in adapter `sourceProvider` string or `FieldResolver.selectField()` for `FieldOwner.ARTWORK` would not be caught.
- **Required fix:** Add a `FieldSelectedTraceTest` scenario injecting a real or synthetic `MetadataCandidate` with `provider = RPDB, fields = {POSTER → FieldValue(..., ARTWORK)}` and asserting the emitted `field_selected` event has `sourceRole = "ARTWORK"`, `selectedProvider = "rpdb"`.
- **Cross-references:** 13-A

### F2-T13-C: `selectedProvider` case mismatch — real trace events emit `"rpdb"` but audit model asserts `"RPDB"`

- **Severity:** P2
- **Lane:** Trace 13
- **Evidence:** `RpdbMetadataProviderAdapter.kt:72` — `sourceProvider = "rpdb"` (lowercase). `MetadataExecutionAuditGoldenTest.kt:434` asserts `selectedProvider == "TOP_POSTERS"` (uppercase). Real trace events carry lowercase; downstream validator rules expecting uppercase will silently fail to match.
- **Required fix:** Normalize `sourceProvider` to uppercase in `selectField()`, or document that artwork-only providers use lowercase and update the golden test.
- **Cross-references:** 13-C

### F2-G-01: `snapshot_read` test does not assert `profileHash` or `source = "OBSERVE_SUBSCRIBE"`

- **Severity:** P2 (P3 normalized)
- **Lane:** G
- **Evidence:** `ContinueWatchingSnapshotReadTraceTest.kt:51-59` asserts `profileId` and `recordCount` presence but not `profileHash` or `source`. No `TraceValidationRule` for `continue_watching.snapshot_read` analogous to the write-side rule.
- **Required fix:** Extend test assertions; add a validator rule for `snapshot_read`.
- **Cross-references:** G-01

### F2-S-04: `PlaybackOwnerContext.ownerSessionId` never used in scrobble boundary check — re-entry gap

- **Severity:** P2
- **Lane:** Trace 11
- **Evidence:** `checkScrobbleBoundary` compares `envelopeProfileId` against `profileManager.activeProfileId.value` only. `ownerSessionId` is captured on `PlaybackOwnerContext` but discarded. A profile-1 → profile-2 → profile-1 re-entry scenario is not detected.
- **Required fix:** When implementing the F2-H-01 fix, use session-ID comparison via `ownerSessionId`, not just profile-ID comparison.
- **Cross-references:** S-04

### F2-E-04: `TvdbMetadataService` is a legacy parallel TVDB localization path bypassing `LocalizationPolicy`

- **Severity:** P2 (LOW → P2)
- **Lane:** E (cross-ref trace 06)
- **Evidence:** `TvdbMetadataService.kt:646` calls `TvdbLanguageMapper.normalize()` directly; does not call `LocalizationPolicy.tvdb()`, does not emit `metadata.localization_plan`, does not route through `LocalizationResolver`. Used by `TvMetadataRouter.fetchSeasonEpisodes()`.
- **Required fix:** Either migrate `TvdbMetadataService` to construct and pass a `LocalizationPolicy`, or add a deprecation comment and an architecture test preventing new callers from being introduced outside `TvMetadataRouter`.
- **Cross-references:** E-04, T-01 (trace 06)

---

## Nits

- **F2-A-02:** `IntegrationNetworkPermitInterceptor` in `AUDIT_ONLY` mode with no plan/test for `ENFORCE` — `NetworkModule.kt:114-117`. Add documentation of migration path and a unit test for the `ENFORCE` throw contract. (A-02)
- **F2-A-03:** `NoIntegrationRuntimeInjectionOutsideBoundaryTest` allowlist has stale entries for `core.tmdb` and `core.tvdb` — `NoIntegrationRuntimeInjectionOutsideBoundaryTest.kt:11-17`. Remove or invert to assert zero uses. (A-03 / J-04)
- **F2-A-04:** Stale test comment in `DefaultIntegrationRuntimeStreamBackoffTest.kt:17` — "expected to FAIL until Task 3 fixes the catch branch" — fix is in place, comment is factually wrong. (A-04)
- **F2-A-05:** `IntegrationCachePolicy.ObserveOnly` is behaviourally identical to `Disabled`; name misleads. Add KDoc clarifying it is a network-pass-through policy. (A-05)
- **F2-B-06:** `MetadataIdentityResolver` uses `ROUTING_ID_TYPE_CONFLICT` as success-resolution trace reason — semantically misleading; root cause of F2-B-01. (B-06)
- **F2-B-07:** `GlobalMetadataDocument.kt` in `core/metadata/composition/` is unreferenced dead code — delete. (B-07)
- **F2-B-08:** `fetchTmdbEnrichment` intentional-discard pattern has no machine-checkable marker. Consider `resolveRequestForTraceOnly()` naming convention or `@TraceOnly` annotation. (B-08, DC-01, TM-04)
- **F2-C-04:** `TraktIntegrationProvider` comments endpoints use `accountCacheKey` — public/non-user-specific; rationale undocumented. Add comment explaining why `accountCacheKey` vs `globalContentCacheKey`. (C-04)
- **F2-C-05:** Section-separator comment block in `TraktIntegrationProvider.kt:952-954` is a fragile test dependency for `TraktGlobalContentCacheKeyTest`. (C-05)
- **F2-C-09:** TMDB enrichment image cache fragmented by display language — informational design note; no action required unless a language-invariant image cache is desired. (C-09)
- **F2-D-04:** `MetadataCacheKeys` entire class is dead code (not just `localized` — F-D-04 was a partial closure). All five remaining methods have zero production callers. Delete or document a concrete adoption plan. (D-04)
- **F2-D-05:** Five `TraceCacheDecision` values lack consumer-side test assertions — `MISS_THEN_NETWORK`, `EXPIRED_MISS`, `BYPASS_DISABLED`, `OBSERVE_ONLY`, `WRITE`. Extend `RuntimeCacheDecisionTraceTest`. (D-05)
- **F2-D-06:** `IntegrationSingleFlight.run(cacheKey: String)` raw overload has no `@VisibleForTesting` annotation. Annotate or restrict to `internal`. (D-06)
- **F2-D-07:** `IntegrationOrphanCleanupService.cleanupAll` is sequential with no parallelism or batch SQL. Consider `coroutineScope { map { async { ... } }.awaitAll() }` or batch `WHERE ownerToken IN (...)`. (D-07)
- **F2-D-09:** Global-content Trakt backoff not shared across profiles — only relevant post F2-D-01 fix; if scope changes to `GlobalContent`, both profiles share the backoff key (correct for global endpoints). (D-09)
- **F2-E-02:** Kitsu enrichment cache key omits `policyVersion` — a future `CURRENT_VERSION` bump would not invalidate Kitsu enrichment. Add `policy:$policyVersion` to the cache key. (E-02)
- **F2-E-05:** `selectKitsuSynopsisField()` always resolves to English; no production `emitFieldSelected` for this decision. Add a comment explaining the `synopsis` field is single-language. (E-05)
- **F2-E-06:** `emitLocalizationPlan` called twice for `SERIES_EPISODES_LANGUAGE` — initial emission always shows `perEpisodeFallbacksAttempted = 0`. Suppress the initial call; rely on post-bundle emission. (E-06)
- **F2-F-07:** `TraktLibraryService.refresh` uses a manual `profileId == activeProfileId()` staleness guard rather than `assertCanWriteProfileState`. Low urgency (functionally correct) but inconsistent with boundary enforcement observability. (F-07)
- **F2-F-08:** `ProfileManager.setActiveProfile` writes `_activeProfileId` directly AND to DataStore, leaving `deferralPolicy.activeProfileId` briefly desynchronized. Self-correcting on next collect; add a comment documenting the dual-write desync window. (F-08)
- **F2-G-02:** `AndroidTvChannelPublisher` still uses unscoped `observeSnapshot()` as a trigger-only signal. Content is not currently read, but the latent path is open. Replace with `observeProfileSnapshot(profileManager.activeProfileId.value)`. (G-02)
- **F2-G-04:** `enrichContinueWatchingItemWithProvider` broad `catch (e: Exception)` returns `item` unchanged; NPE from null injectable is caught and logged but not rethrown. In correctly-wired production this is safe; masks misconfiguration in partial test harnesses. (G-04)
- **F2-H-03:** `checkin` call sites in `HomeViewModelContinueWatching.kt:590` and `MetaDetailsViewModel.kt:3076` omit `ownerProfileId` — documented as ambient-profile fallback; add `// ARCHITECTURE: checkin is ambient-profile, no playback session` comment at both call sites. (H-03)
- **F2-H-04:** `PlaybackOwnerContext.traktAccount` and `.simklAccount` are always `null` in production. Either populate or remove the fields. (H-04)
- **F2-H-06:** Skip-segment cache key language-independence is correct but undocumented. Add `// Language is intentionally excluded: skip timestamps are audio/subtitle-track-independent.` (H-06)
- **F2-H-07:** `PlayerViewModel.stopAndRelease()` and `onCleared()` both call `unregisterPlaybackSession()` — idempotent but dual-path unregistration order is non-deterministic. Consider moving exclusively to `onCleared()`. (H-07)
- **F2-I-02-nit:** `RuntimeTraceModule.kt:100-101` comment references `MetaPreview.toHomeDisplayMetadata()` but actual wiring is `toFirstPaintHomeDisplayMetadata()`. Update comment. (I-02)
- **F2-I-03:** `includeTestsMatching("com.nexio.tv.core.trace.*Validator*Test")` wildcard works but relies on naming convention. Add a dedicated explicit pattern line for `RuntimeTraceValidatorRealEmissionTest`. (I-03)
- **F2-I-11:** `LocalizationPlanPrecedesProviderSteps` validator rule schema drift undetected by real-emission test (TVDB episode path not driven). Cross-reference E-03 / I-12. (I-12)
- **F2-J-08:** `MetadataArchitectureBoundaryTest` (import scan) duplicates `MetadataProductionBoundaryTest` (content scan) with weaker coverage and overlapping scope. Consolidate. (J-08)
- **F2-meta-01:** `TvdbSettingsNoAdvancedToggleTest` line 44 type mismatch warning (noted in audit XML) — warning still present; verify and address if surfacing a real type drift. (per Stage 1 audit)
- **F2-T-02-nit:** `fetchTvEpisodeEnrichment` calls `router.route`, `identityResolver.resolve`, and `providerPlanExecutor.buildPlan` directly without calling `resolveRequest` — no `metadata.resolver_schedule` event fires for the SEASON-depth sub-path. Document or fix. (02-B, trace 02)
- **F2-skip-01:** `FieldOwner.SKIP_SEGMENTS` is a dead enum constant after F-12-01 removal. Remove from `FieldOwner` enum and `FieldResolver.kt:286`. (F-12-01 residual)
- **F2-13-E:** Both `RpdbMetadataProviderAdapter` and `TopPostersMetadataProviderAdapter` contain a private `MetadataMediaKind.toContentType()` extension — duplicated code. Extract to a shared `PosterAdapterUtils.kt`. (13-E)

---

## Aggregate cross-reference index

| New ID | Lane finding ID | Trace finding ID(s) | Original-audit ID (prior dossier) |
|---|---|---|---|
| F2-D-01 | D-01, F-01 | TW-01 (trace 08), S-01 (trace 11) | F-C-06 (cluster F regression) |
| F2-J-01 | J-01 | — | F-J-01 (meta-finding; partially closed) |
| F2-H-01 | H-01, H-02, H-05, F-05 | S-01 (trace 11), TW-01 (trace 08) | F-H-03 (P0, claimed closed) |
| F2-H-02 | H-02 | TW-01 | F-H-03 (P0, claimed closed) |
| F2-B-01 | B-01 | — | SIGN-OFF: "pre-existing parallel-session WIP regression" |
| F2-A-01 | A-01 | — | F-A-01 (prior P1, closed by Cluster B; re-verified still open for call/open paths) |
| F2-C-01 | C-01 | — | F-C-01 (prior P1, TMDB person/company bypass — partial; this is collection endpoint) |
| F2-J-02 | J-02, H-03 | — | New; H-03 (ambiguous callers) |
| F2-J-03 | J-03, B-05 | — | New |
| F2-I-01 | I-06 | — | Stage-1 SHA inconsistency |
| F2-E-01 | E-01 | T-01 (trace 06) | New |
| F2-E-03 | E-03, I-12 | — | New |
| F2-F-05 | F-05, H-01 | S-01 | F-H-03 |
| F2-I-06 | I-08 | 13-D | New |
| F2-D-08 | D-08 | — | F-D-02 (partial closure; deletion path not addressed) |
| F2-I-07 | I-07 | — | New |
| F2-B-02 | B-02 | — | SIGN-OFF pre-existing |
| F2-B-03 | B-03 | — | SIGN-OFF pre-existing |
| F2-B-04 | B-04 | — | SIGN-OFF pre-existing |
| F2-B-05 | B-05, J-03 | — | New |
| F2-C-02 | C-02 | — | New |
| F2-C-03 | C-03 | — | F-C-06 (missed in sweep) |
| F2-C-06 | C-06, J-05 | — | New |
| F2-C-07 | C-07 | — | New |
| F2-C-08 | C-08 | — | New |
| F2-D-02 | D-02 | — | F-C-06 (stale test) |
| F2-D-03 | D-03 | — | F-D-01 (test quality) |
| F2-F-02 | F-02 | — | New |
| F2-F-03 | F-03 | — | F-F-02 (partial; write path not wired) |
| F2-F-04 | F-04 | — | F-C-06 (audit artifact stale) |
| F2-F-06 | F-06 | — | New |
| F2-G-03 | G-03 | TW (trace 08) | New |
| F2-I-02 | I-01 | — | F-I-01 (manifest not updated) |
| F2-I-04 | I-04 | — | F-I-04 (test gap) |
| F2-I-05 | I-05, D-05 | — | New |
| F2-I-08 | I-10 | — | New |
| F2-I-09 | I-11 | S-02, S-03 (trace 11) | F-H-03 (telemetry event semantics) |
| F2-I-10 | I-09 | — | New |
| F2-J-04 | J-04, A-03, F-09 | — | New |
| F2-J-05 | J-05, C-06 | — | New |
| F2-J-06 | J-06, C-05 | — | New |
| F2-J-07 | J-07 | — | F-J-01 (non-Home callers unguarded) |
| F2-TM-01 | — | TM-01 (trace 04) | F-04-03 (partial) |
| F2-TM-02 | — | DS-01 (trace 05) | F-05-03 (partial) |
| F2-TM-03 | — | DS-02 (trace 05) | F-05-04 (partial) |
| F2-T13-A | — | 13-A (trace 13) | F-C-04 (test gap) |
| F2-T13-C | — | 13-C (trace 13) | New |
| F2-G-01 | G-01 | T09-F-04 (trace 09) | F-G-02 (partial closure) |
| F2-S-04 | — | S-04 (trace 11) | New |
| F2-E-04 | E-04 | T-01 (trace 06) | New |
