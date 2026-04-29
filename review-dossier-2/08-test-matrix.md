# Test Matrix — Post-Cluster-F

**Review SHA:** `774a540f8`
**Date:** 2026-04-29

---

## Architecture pins inventory

| Pin name | Location | Lane | Style | Currently passing? | Robustness |
|---|---|---|---|---|---|
| `IntegrationScopeGlobalDeprecatedNoCallersTest` | `architecture/IntegrationScopeGlobalDeprecatedNoCallersTest.kt` | A/F/J | source-grep (regex) | Yes | Low fragility — symbol names stable |
| `DeprecatedAnnotationsHaveReplaceWithTest` | `architecture/DeprecatedAnnotationsHaveReplaceWithTest.kt` | A/J | source-grep (paren-balanced parser) | Yes | Low fragility |
| `IntegrationApiShapeRegistryCoverageTest` | `architecture/IntegrationApiShapeRegistryCoverageTest.kt` | A/C/J | source-grep (two patterns) | Yes | Strong — no exemptions; catches literal shape strings |
| `FieldResolverInjectionContractTest` | `architecture/FieldResolverInjectionContractTest.kt` | B/J | source-grep (regex) | Yes | Adequate — covers `FieldResolver()` and `ProviderPlanRunner(emptySet())` but NOT `ResolvedMetadataDocument(` |
| `FieldResolverPreviewProvenanceTest` | `router/FieldResolverPreviewProvenanceTest.kt` | B | behavior (instantiates FieldResolver) | Yes | Strong — true behavior test |
| `FieldResolverContentIdInTraceTest` | `router/FieldResolverContentIdInTraceTest.kt` | B | behavior | Yes | Strong |
| `MetadataIdentityResolverNegativeCacheTest` | `router/MetadataIdentityResolverNegativeCacheTest.kt` | B | behavior | Yes | Strong |
| `MetadataRequestNormalizerTvWarningTest` | `router/MetadataRequestNormalizerTvWarningTest.kt` | B | behavior | Yes | Strong |
| `MetadataProviderTargetIdsAnimePrefixTest` | `data/integration/metadata/MetadataProviderTargetIdsAnimePrefixTest.kt` | C | behavior | Yes | Strong |
| `PremiumPosterAdapterRegistrationTest` | `data/integration/posters/PremiumPosterAdapterRegistrationTest.kt` | C | behavior (DI registration check) | Yes | Strong |
| `TraktGlobalContentCacheKeyTest` | `data/integration/trakt/TraktGlobalContentCacheKeyTest.kt` | C/J | source-grep (fixed-window 2500-char substring scan) | Yes — but false positive (D-01 shows cache key correct; spec construction throws) | **High fragility** — layout-sensitive; does NOT construct real `IntegrationSpec`; cannot detect scope mismatch |
| `DerivedOkHttpClientTraceWiringTest` | `core/network/DerivedOkHttpClientTraceWiringTest.kt` | A/I | source-grep (architecture scan) | Yes | Strong |
| `YouTubeTrailerClientTraceInterceptorTest` | `core/network/YouTubeTrailerClientTraceInterceptorTest.kt` | A/I | behavior (client construction) | Yes | Strong |
| `NoIntegrationRuntimeInjectionOutsideBoundaryTest` | `architecture/NoIntegrationRuntimeInjectionOutsideBoundaryTest.kt` | A/J | source-grep (package-based) | Yes | Low fragility — BUT allowlist contains stale `core.tmdb`/`core.tvdb` entries with zero actual uses |
| `IntegrationBoundaryTest` | `architecture/IntegrationBoundaryTest.kt` | A/C/J | source-grep (path suffix + regex) | Yes | Low fragility; `TvdbAuthService.kt` carve-out may be stale |
| `NoDirectOkHttpOutsideRuntimeTransportPackagesTest` | `architecture/NoDirectOkHttpOutsideRuntimeTransportPackagesTest.kt` | A/J | source-grep | Yes | Low fragility |
| `NoRawProviderInjectionTest` | `architecture/NoRawProviderInjectionTest.kt` | A/J | source-grep | Yes | Low fragility |
| `NoRuntimeSpecOutsideIntegrationPackagesTest` | `architecture/NoRuntimeSpecOutsideIntegrationPackagesTest.kt` | J | source-grep (package-based) | Yes | Low fragility; stale `core.tmdb`/`core.tvdb` allowlist entries |
| `IntegrationSingleFlightTest` | `core/integration/IntegrationSingleFlightTest.kt` | D | behavior (concurrency test) | Yes | Strong |
| `IntegrationBackoffManagerExponentialTest` | `core/integration/IntegrationBackoffManagerExponentialTest.kt` | D | behavior | Yes | Strong |
| `ProfileBoundaryEnforcerTest` | `core/integration/ProfileBoundaryEnforcerTest.kt` | F | behavior | Yes | Strong |
| `IntegrationPolicyRegistryTest` | `core/integration/IntegrationPolicyRegistryTest.kt` | A | behavior | Yes | Strong |
| `ProfileBoundaryArchitectureTest` | `architecture/ProfileBoundaryArchitectureTest.kt` | F/J | source-grep (function body substring + regex) | Yes | Medium fragility — function body extractor is line-start sensitive |
| `ProfileManagerReactiveSwitchDuringPlaybackTest` | `core/profile/ProfileManagerReactiveSwitchDuringPlaybackTest.kt` | F | behavior (state machine) | Yes | Strong |
| `ProfileManagerSwitchBoundaryCheckTraceTest` | `core/profile/ProfileManagerSwitchBoundaryCheckTraceTest.kt` | F | source-grep + behavior | Yes | Adequate |
| `PlaybackSessionRegistrySingleSlotTest` | `architecture/PlaybackSessionRegistrySingleSlotTest.kt` | H | behavior | Yes | Strong |
| `TrackingScrobbleServiceCheckinShapeTest` | `data/repository/TrackingScrobbleServiceCheckinShapeTest.kt` | H | behavior (reflection) | Yes | Strong |
| `SkipIntroRepositoryCanonicalSurfaceTest` | `architecture/SkipIntroRepositoryCanonicalSurfaceTest.kt` | H/J | source-grep (regex) | Yes | Low fragility |
| `AddonFirstPaintShapeArchitectureTest` | `architecture/AddonFirstPaintShapeArchitectureTest.kt` | J | source-grep (regex over homeFiles()) | **FAILING** | Scope limited to `ui/screens/home/`; non-Home callers unguarded (J-07) |
| `MetadataRouterBoundaryTest` | `architecture/MetadataRouterBoundaryTest.kt` | B/J | mixed: source-grep (3) + file existence (1) | Yes | Medium fragility |
| `MetadataProductionBoundaryTest` | `architecture/MetadataProductionBoundaryTest.kt` | B/J | source-grep (entrypoint symbol presence) | Yes | Medium fragility — symbol-presence check does not verify wiring |
| `MetadataArchitectureBoundaryTest` | `metadata/audit/MetadataArchitectureBoundaryTest.kt` | B/J | source-grep (import lines only) | Yes | Weaker than `MetadataProductionBoundaryTest`; overlapping scope |
| `ContinueWatchingSnapshotServiceObserveProfileSnapshotTest` | `data/repository/ContinueWatchingSnapshotServiceObserveProfileSnapshotTest.kt` | G | behavior (4 cases) | Yes | Strong |
| `ContinueWatchingRecordCountIncludesTraktUpNextTest` | `data/repository/ContinueWatchingRecordCountIncludesTraktUpNextTest.kt` | G | source-grep (static) | Yes | Adequate |
| `ContinueWatchingSnapshotReadTraceTest` | `data/repository/ContinueWatchingSnapshotReadTraceTest.kt` | G | behavior | Yes | **Partial** — does not assert `profileHash` or `source = "OBSERVE_SUBSCRIBE"` |
| `TraceRedactorTest` + `TraceRedactorAuthHeaderParityTest` | `core/trace/*.kt` | I | behavior | Yes | Strong — covers F-I-01 additions in `TraceRedactor`; manifest parity NOT tested |
| `RuntimeTraceInterceptorBodyGatingTest` | `core/trace/RuntimeTraceInterceptorBodyGatingTest.kt` | I | behavior (5 tests) | Yes | **Partial** — `SAFE_METADATA_RUNTIME` mode coverage absent |
| `TraceBundleGoldenTest` | `core/trace/TraceBundleGoldenTest.kt` | I | behavior (synthetic session) | Yes | Strong for bundle structure; premium poster `FieldSelectedEvent` synthesised not real |
| `RuntimeTraceValidatorRealEmissionTest` | `core/trace/RuntimeTraceValidatorRealEmissionTest.kt` | I/E | behavior (real emission) | Yes | **Partial** — drives only `kitsu:7442` route; does NOT drive TVDB episode path; `LocalizationPlanPrecedesProviderSteps` schema drift undetected |
| `DefaultIntegrationRuntimeStreamBackoffTest` | `core/integration/DefaultIntegrationRuntimeStreamBackoffTest.kt` | A | behavior | Yes (now passing) | Adequate — has stale comment saying "expected to FAIL" (A-04) |
| `IntegrationCallRuntimeTest` | `core/integration/IntegrationCallRuntimeTest.kt` | A | behavior | Yes | **Partial** — covers `call()` path backoff block; does NOT test that successful `call()` clears backoff |
| `DefaultIntegrationRuntimeStaleOn429Test` | `core/integration/DefaultIntegrationRuntimeStaleOn429Test.kt` | D | behavior | Yes | **Weak** — uses `ObserveOnly` policy, masking the real stale-guard gate |

---

## Audit gradle tasks

| Task | Style | Verdict | Observations |
|---|---|---|---|
| `generateProfileBoundaryAudit` | Runs 5 behavior scenarios | **PASS** | 5 scenarios, 0 violations. Does NOT exercise authenticated Trakt global-content fetch — would have caught F2-D-01. Artifact SHA (`9f0555a5a`) differs from review SHA (`774a540f8`). |
| `generateIntegrationRuntimeAudit` | Runs runtime audit + boundary checks | **PASS** | 24 providers, 127 endpoint shapes, 93 runtime-covered calls, 0 direct-bypass calls, 0 missing policies. `loadMovieCollection()` bypass not visible because it uses `loadResponse()` not `IntegrationRuntime`. |
| `generateMetadataExecutionAudit` | Bundle PASS, Gradle FAIL | **PARTIAL** | Bundle verdict `PASS` (30 items, 0 policy violations). `MetadataExecutionAuditGoldenTest.routing rules match spec for all id types` fails: `tt14403178` IMDB-as-series reported as `ROUTING_ID_TYPE_CONFLICT` instead of `ITEM_TYPE_SERIES`. Premium poster `FieldSelectedEvent` synthesised from harness model. |
| `generateTraceValidatorAudit` | Runs 11 validator tests | **PASS** | 0 failures. `RuntimeTraceValidatorRealEmissionTest` included (wildcard pattern matches). `LocalizationPlanPrecedesProviderSteps` rule tested in isolation but not against real TVDB episode-path emissions. |

---

## Coverage gaps (no test exists)

- **F2-D-01 / F2-F-04:** No test constructs a real `DefaultIntegrationRuntime` + real `ProfileBoundaryEnforcer`, calls `TraktIntegrationProvider.fetchTrendingMovies()`, and asserts no exception is thrown. Propose: `TraktGlobalContentSpecBoundaryTest` — end-to-end with two profiles sharing a single cached trending result.
- **F2-J-01:** No post-fix test verifying `HomeViewModelContinueWatchingRuntimePipeline` and `HomeViewModelPresentationPipeline` route through `MetadataRouterFacade` rather than calling `getMetaFromAllAddons()`. The `AddonFirstPaintShapeArchitectureTest` once passing covers the Home case; non-Home callers (J-07) remain unguarded.
- **F2-H-01:** No test asserting that `enqueueScrobble`/`enqueueCheckin` do NOT call `enqueueAndDrain` when `envelopeProfileId != activeProfileId`. Propose: `TraktScrobbleServiceProfileBoundaryTest.scrobble is not enqueued when profile mismatch detected`.
- **F2-A-01:** No test asserting that a successful `call()` clears the backoff for the provider+scope. Propose: `IntegrationCallRuntimeTest.successful call clears backoff for provider-scope`.
- **F2-J-03 / F2-B-05:** No `ResolvedMetadataDocumentOwnershipTest` scanning production sources for `ResolvedMetadataDocument(` and asserting only two permitted sites. Propose the test per B-05.
- **F2-C-07:** No `MetadataAdapterUnknownPrefixTraceTest` verifying that adapters return `emptyCandidate(provider)` (not throw, not leak fields) when supplied a mismatched prefix.
- **F2-I-01:** No `TraceBuildInfoGitShaTest` asserting non-null `gitSha` in exported `app-build-info.json`.
- **F2-I-02:** No `TraceRedactorManifestParityTest` asserting `TraceBundleExporter.redactionManifest()` contains exactly the same keys as the live `TraceRedactor` sets.
- **F2-I-04:** No tests for `SAFE_METADATA_RUNTIME` mode in `RuntimeTraceInterceptorBodyGatingTest` — propose two new tests.
- **F2-I-05:** No `ExpiredMissPrecedesNetworkRequest` validator rule or tests for `EXPIRED_MISS` and `WRITE` `TraceCacheDecision` values.
- **F2-I-06:** No test asserting `SecondaryDoesNotOverwritePrimary` rule returns PASS for primary-wins-with-competition scenario. Propose: add to `RuntimeTraceValidatorTest`.
- **F2-I-08:** No validator rule or test for `metadata.normalizer_warning` events.
- **F2-I-09:** No validator rule or test for `playback.scrobble_rejected` structural invariants.
- **F2-I-10:** No `JsonlTraceWriterIoFailureTest` asserting separate IO error counter increments on storage failure.
- **F2-E-03 / F2-I-11:** No `RuntimeTraceValidatorRealEmissionTest` scenario driving the TVDB episode-bundle path to validate `LocalizationPlanPrecedesProviderSteps` schema coherence end-to-end.
- **F2-TM-02:** No test verifying that `fetchRecommendations` harvests from `resolution.providerRunResult?.stepResults` rather than always calling `repo.fetchMoreLikeThis()`.
- **F2-TM-03:** No test for `OrganizationDetailViewModel` using `MetadataRouterFacade.fetchPersonDetail()` (once migrated).
- **F2-T13-A:** No `FieldSelectedTraceTest` scenario with a real or synthetic `MetadataCandidate(provider = RPDB, fields = {POSTER → FieldValue(..., ARTWORK)})` asserting the emitted `field_selected` event has `sourceRole = "ARTWORK"` and `selectedProvider = "rpdb"`.
- **F2-D-08:** No test covering `deleteOwnedMedia` transactionality — particularly the partial-write window when a process kill occurs between blob delete and Room DAO delete.
- **F2-G-03:** No test covering `upsertRailMembership` partial-write window when a process kill occurs between rail header write and item write.

---

## Stale tests (failing or never-asserting)

- **`MetadataExecutionAuditGoldenTest.routing rules match spec for all id types`** — failing (F2-B-01). `tt14403178` (IMDB series) routed with `ROUTING_ID_TYPE_CONFLICT` instead of `ITEM_TYPE_SERIES`.
- **`FieldSelectedTraceTest.secondary field rejected`** — failing (F2-B-02). Rejection reason wording mismatch: test expects `"primary"`, code emits `"field already filled"`.
- **`MetadataRouterPrecedenceTest.provider native id type conflict records conflict and falls back by item type`** — failing (F2-B-03). `buildTargetIds` refactor moved TMDB-scheme IDs to the TMDB key; test assertion is stale.
- **`MetadataRouterTargetIdsImdbTest` x2** — failing (F2-B-04). `previewStableIds` routing WIP committed red.
- **`AddonFirstPaintShapeArchitectureTest.home hydration does not call addon detail metadata directly`** — failing (F2-J-01). Production fix not made.
- **`ContinueWatchingSnapshotReadTraceTest`** — passing but never-asserting on `profileHash` or `source = "OBSERVE_SUBSCRIBE"` (F2-G-01).
- **`DefaultIntegrationRuntimeStaleOn429Test.executeProviderLoad HTTP 429 with stale cache returns Stale not Missing`** — passing but testing wrong scenario: uses `ObserveOnly` policy, mocks `readStale` to bypass the real policy gate (F2-D-03).
- **`TraktIntegrationProviderRecommendationsTest.trending and popular reads`** — expected keys are stale post F-C-06; test either fails or passes for wrong reason (F2-D-02).
- **`DefaultIntegrationRuntimeStreamBackoffTest`** — has stale KDoc comment "expected to FAIL until Task 3" even though it now passes (F2-A-04 nit).
- **`IntegrationCallRuntimeTest`** — does not test that successful `call()` clears backoff for provider+scope (F2-A-01 test gap).
- **`TvdbSettingsNoAdvancedToggleTest` line 44** — type mismatch warning still present in audit XML (F2-meta-01).

---

## Test design recommendations

1. **Prefer behavior tests over source-grep for scope/cache-key contract validation.** The F2-D-01 regression (Trakt global-content throws at spec construction) was invisible to `TraktGlobalContentCacheKeyTest` because that test scans function bodies for `accountCacheKey(` but never constructs a real `IntegrationSpec` or invokes `ProfileBoundaryEnforcer`. A behavior test that calls `fetchTrendingMovies()` and asserts no exception is thrown is authoritative. Reserve source-grep pins for structural rules (symbol names, method shapes, package boundary) where behavior tests require excessive DI wiring.

2. **Separate audit-runner correctness from production-behavior tests.** `MetadataExecutionAuditGoldenTest` currently validates both the audit harness's model and (indirectly) production behavior. The golden test for premium posters passes because the harness synthesises the expected `FieldSelectedEvent` directly — it does not exercise the real `RpdbMetadataProviderAdapter`. Create dedicated behavior tests for production adapter output; keep the golden test as a harness-correctness check only.

3. **Architecture pins must pass at the time they are merged.** The `AddonFirstPaintShapeArchitectureTest` was broadened (commit `726de12f3`) without a corresponding production fix, leaving a failing pin in the codebase. A failing pin is worse than no pin — it trains reviewers to ignore test failures. Policy: architecture pins must be green at merge, or the scope must remain narrower until the production fix is ready.

4. **`assertCanWriteProfileState` (not just trace emission) is the criterion for "boundary check closed."** F-H-03 was accepted as closed because `checkScrobbleBoundary` emits a `playback.scrobble_rejected` event. The acceptance criterion should have been: "the mutation is NOT enqueued when profileId mismatches." Require an asserting unit test before closing any P0 boundary-enforcement finding.

5. **Add a cross-check between `TraceRedactor` entries and `TraceBundleExporter.redactionManifest()`.** The `TraceRedactorManifestParityTest` pattern (assert manifest equals live `TraceRedactor` sets) would have caught the F2-I-02 gap automatically. This test is cheap and high-value — add it as part of the F-I-01 follow-up.
