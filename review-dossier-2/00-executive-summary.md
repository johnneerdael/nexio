# Executive Summary — Post-Cluster-F Audit

**Review SHA:** `774a540f8` on `codex/integration-runtime-phase-a`
**Review date:** 2026-04-29
**Baseline for delta:** the prior audit's `review-dossier/09-known-gaps.md` (60 findings, all claimed closed via clusters A through F)

---

## Headline

Clusters A through F collectively delivered the integration runtime control plane, metadata router, provider shape registry, cache/backoff hardening, localization tracing, profile boundary enforcement, and legacy deletion. The structural wiring is largely sound: 24 providers, 127 endpoint shapes, 0 direct Retrofit bypasses in production, all four generated audit gates passing (with one downstream test failure in the metadata execution gate). However, Cluster F's final task (F-C-06, Trakt global-content cache keys) introduced a regression that renders trending, popular, recommendations, and calendar rails completely dark for every authenticated Trakt user. Additionally, the prior P0 closure of F-H-03 (scrobble boundary enforcement) is confirmed incomplete: the boundary check fires a telemetry event but does not halt the write. Two P0-level issues block this branch.

---

## What's healthy

- **All 4 generated audit gates pass at the bundle level.** `generateProfileBoundaryAudit`: PASS (5 scenarios, 0 violations). `generateIntegrationRuntimeAudit`: PASS (24 providers, 127 endpoint shapes, 93 runtime-covered calls, 0 direct-bypass calls, 0 missing policies, 0 missing header policies). `generateTraceValidatorAudit`: PASS (11 tests, 0 failures). `generateMetadataExecutionAudit`: bundle verdict PASS; 1 downstream test fails (B-01/F2-B-01 — pre-existing parallel-session WIP).
- **Profile boundary enforcement structural wiring is correct.** `ProfileBoundaryEnforcer.validateRequest` is called at construction time from all three spec types. The `assertCanSwitchProfile` gate fires before profile switches; all 3 UI callers catch `ProfileBoundaryException`. F-F-01 (prior P0 crash risk) is genuinely closed.
- **F-F-04 deferral policy is wired correctly.** `ProfileSwitchDeferralPolicy` defers reactive profile switches during playback and drains on `PlaybackSessionRegistry.ownerState` becoming null. All 4 state-machine scenarios are pinned.
- **Legacy deletion boundary is clean.** All symbols deleted in F-J-02/F-F-03/F-F-05/F-B-01/F-B-02 have zero references in production. Architecture pins for boundary enforcement (13 pin tests across Lane J) all pass.
- **Trace infrastructure is structurally sound.** `RuntimeTraceInterceptor`, `RuntimeTraceContextRequestTaggingInterceptor`, `TraceRedactor`, `FileRuntimeTraceSink`, `TraceBundleExporter`, and all 16 validator rules are correctly wired. Body gating (`INCLUDE_HTTP_BODIES_INTERNAL_ONLY` only in internal builds) passes all existing tests. YouTube trailer and OkHttp clients all wire trace interceptors (F-I-05 closed).
- **IntegrationScope.Global fully deprecated.** `IntegrationScopeGlobalDeprecatedNoCallersTest` passes. All 6 production callers migrated to `GlobalContent`, `GlobalLocalizedContent`, or `GlobalEnglishImage`.
- **Cluster F pin tests (F-B-01 through F-C-06) pass.** `FieldResolverPreviewProvenanceTest`, `FieldResolverInjectionContractTest`, `IntegrationApiShapeRegistryCoverageTest`, `PremiumPosterAdapterRegistrationTest`, `MetadataIdentityResolverNegativeCacheTest`, `MetadataRequestNormalizerTvWarningTest`, `DeprecatedAnnotationsHaveReplaceWithTest`, `IntegrationScopeGlobalDeprecatedNoCallersTest` — all green.

---

## What's broken (P0)

**F2-D-01** — `ProfileBoundaryException` at spec construction for all authenticated Trakt users triggering trending/popular/recommendations/calendar rails. F-C-06 changed the cache key from `accountCacheKey` to `globalContentCacheKey` but left `scope = accountScope(session)`. `ProfileBoundaryEnforcer.validateAccountScope` correctly catches the mismatch and throws at `IntegrationSpec.init`. All four Home rails are dark for any authenticated Trakt user. The `TraktGlobalContentCacheKeyTest` source-grep proxy did not detect this because it does not construct a real `IntegrationSpec`. `TraktIntegrationProviderRecommendationsTest` expected keys are now stale. Fix: change `scope` to `IntegrationScope.GlobalContent` and `profileContext = null` for the six affected functions.

**F2-J-01** — `AddonFirstPaintShapeArchitectureTest.home hydration does not call addon detail metadata directly` is actively failing at SHA `774a540f8`. `HomeViewModelContinueWatchingRuntimePipeline.kt:29` and `HomeViewModelPresentationPipeline.kt:474, 548` contain `getMetaFromAllAddons()` calls that the broadened pin (commit `726de12f3`) now detects. The production fix was not made. CI architecture test suite fails.

---

## What's at risk (P1)

**F2-H-01 / F2-H-02 (Lane H, cross-cut F)** — F-H-03 P0 closure from Cluster B is confirmed incomplete. `checkScrobbleBoundary` in both `TraktScrobbleService` and `SimklScrobbleService` emits `playback.scrobble_rejected` telemetry but the write proceeds unconditionally. `assertCanWriteProfileState` (the function that actually throws) has only one production caller: `ContinueWatchingSnapshotService`. Stale scrobbles are credited to the wrong profile. `TrackingProgressService` also lacks a result-time boundary re-check.

**F2-B-01 (Lane B)** — `MetadataExecutionAuditGoldenTest.routing rules match spec for all id types` fails. `MetadataIdentityResolver` appends `ROUTING_ID_TYPE_CONFLICT` to the trace of any successfully resolved identity, including non-conflict routes. `MetadataAuditRunner.toAuditEvent` override fires on any trace entry containing this reason, misreporting the routing reason for `tt14403178` (IMDB-as-series). The audit sign-off artifact is incorrect for the `netflix-series` scenario.

**F2-A-01 (Lane A)** — `backoffManager.clear()` is NOT called on successful `call()` or `open()` results. Only the `get()` path clears backoff. Providers like YOUTUBE_TRAILER and SUBTITLE_SOURCE_DOWNLOAD (exclusively `call()`/`open()` paths) stay blocked for the full backoff window after a transient error on any `get()` path to the same provider+scope.

**F2-C-01 (Lane C)** — `TmdbIntegrationProvider.loadMovieCollection()` bypasses `IntegrationRuntime` entirely via a bare `loadResponse()` call. No backoff, no single-flight, no audit trail. `TmdbApiShapes.COLLECTION` exists but has no production caller. The F-C-02 sweep missed this.

**F2-I-01 (Lane I)** — `TraceSession.gitSha` is always `null` in production. Trace bundles cannot be correlated to a specific commit. Diagnostic utility reduced.

---

## Decision recommendation

**NOT APPROVED for merge.**

Two P0 issues block: F2-D-01 (Trakt global-content crash for authenticated users — cluster F regression) and F2-J-01 (architecture pin failing at SHA `774a540f8`). These must be resolved before merge. After both P0 fixes, the four generated gates should be re-run and the boundary audit regenerated at the new SHA with the Trakt global-content scenario included.

Additionally, before merge, the following P1 items should be addressed:
- F2-H-01/F2-H-02: Complete the F-H-03 closure by converting `checkScrobbleBoundary` to halt the write on mismatch.
- F2-B-01: Fix `MetadataAuditRunner.toAuditEvent` override to restore a passing CI golden test.

The remaining P1s (F2-A-01, F2-C-01, F2-I-01, F2-E-01, F2-E-03, F2-F-05, F2-I-06, F2-D-08, F2-I-07, F2-J-02, F2-J-03) are strongly recommended for a follow-on cluster before the branch reaches main, but are not strict blockers if F2-D-01, F2-J-01, F2-H-01, and F2-B-01 are resolved.

---

## Counts

| Severity | Count |
|---|---:|
| P0 | 2 |
| P1 | 15 |
| P2 | 27 |
| Nit | 34 |
| **Total** | **78** |

---

## What changed from the prior audit

The prior audit (`review-dossier/09-known-gaps.md`) recorded 60 findings (P0=2, P1=21, P2=18, Nit=12) with decision CHANGES_REQUESTED. Clusters A through F claimed to close all 60. This post-cluster-F audit finds:

**Genuinely closed:** The two prior P0s are partially closed. F-F-01 (UI crash on profile switch during playback) is genuinely fixed — all 3 UI callers now catch `ProfileBoundaryException`. F-H-03 (scrobble boundary — no `assertCanWriteProfileState`) is NOT fully closed: telemetry fires but the write is never halted. The majority of prior P1s and P2s are closed: cache atomicity (F-D-02), stale-on-429 fallback (F-D-01), single-flight typed keys (F-D-05), backoff exponential schedule (F-D-06), literal `apiShapeId` migration (F-C-02), anime prefix parsers (F-C-03), premium poster adapters (F-C-04), stable hash (F-C-05), facade bypass fixes (F-B-01 through F-B-07), localization tracing (F-E-01 through F-E-05), and legacy deletion (F-J-02 through F-J-04). The resolver orchestration depths (F-04/F-05) are now live with 6 production callers confirmed.

**Partially closed:** F-H-03 is telemetry-only (no enforcement). F-G-01 path B is closed for `HomeViewModelContinueWatching` and `AndroidTvFeedCatalogService` but `AndroidTvChannelPublisher` still uses unscoped `observeSnapshot()`. F-G-02 has a test but payload assertions are incomplete. F-04-03 (trailer facade bypass) is closed for title-trailer but season-media paths remain direct `TrailerService` calls. F-05-03/F-05-04 (recommendations/cast bypass) are partially closed.

**New findings from cluster work itself:** The largest new issue is F2-D-01, a direct cluster-F regression: F-C-06 changed the Trakt cache key without changing the scope, causing `ProfileBoundaryEnforcer` to throw at spec construction for every authenticated Trakt user on 4 Home rails. F2-J-01 (architecture pin failing) emerged from the broadened pin scope (commit `726de12f3`) without the corresponding production fix. F2-I-06 (inverted validator rule semantics) and F2-T13-C (lowercase `selectedProvider` mismatch) are new observations from deeper trace analysis. The overall net: approximately 42 of the prior 60 findings are genuinely closed, ~6 are partially closed, and 30 new findings emerged (12 from the cluster work itself as regressions or incomplete closures, 18 from previously uninspected surfaces).
