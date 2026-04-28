# Test Matrix

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Phase:** 4
- **Owner task:** Task 24

## Method

This matrix maps each major contract documented in the runtime / metadata / profile / trace
audit lanes to the test (or audit-task gate) that guards it. Test files were inventoried via
`git diff main..HEAD --name-only -- 'app/src/test/**/*.kt'` (321 .kt test files on this
branch). For each contract row a directed grep was executed against the test tree and the
relevant production symbol(s).

Coverage legend:
- ✅ — direct test or hard production guard with a corresponding test
- ⚠️ — partial: test exists but does not exercise the full surface
- ❌ — no automated guard

## Contracts and their guards

### Runtime layer

| # | Contract | Guarded by | File:line | Coverage |
|---|---|---|---|---|
| 1 | Authenticated Trakt/Simkl call uses Account scope (no Global) | `ProfileBoundaryEnforcerTraktSimklGlobalScopeTest` exercising `ProfileBoundaryEnforcer.rejectGlobalScopeForAuthenticatedProvider` | `app/src/test/java/com/nexio/tv/core/integration/ProfileBoundaryEnforcerTraktSimklGlobalScopeTest.kt` + `app/src/main/java/com/nexio/tv/core/integration/ProfileBoundaryEnforcer.kt:37,290` | ✅ |
| 2 | Cache HIT suppresses network for the same operation | `RuntimeCacheDecisionTraceTest`, `IntegrationCacheOwnershipTest`, validator rule `FreshCacheHitSuppressesNetwork` exercised via `RuntimeTraceValidatorRealEmissionTest` | `app/src/test/java/com/nexio/tv/core/trace/RuntimeCacheDecisionTraceTest.kt`, `app/src/test/java/com/nexio/tv/core/integration/IntegrationCacheOwnershipTest.kt`, `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceValidatorRealEmissionTest.kt` | ✅ |
| 3 | 429/5xx triggers backoff | `IntegrationBackoffManagerTest` (only 429 + Retry-After path) | `app/src/test/java/com/nexio/tv/core/integration/IntegrationBackoffManagerTest.kt:11-25` | ⚠️ — only one happy-path assertion; no 5xx, no exponential schedule, no clear-on-success |
| 4 | Single-flight joins concurrent operations for the same key | No dedicated `singleFlight` / coalescing test in either runtime or repository tests; `joinSingleFlight`/`coalesce` symbols absent in production | (none) | ❌ |
| 5 | Profile-bound spec construction requires `ProfileExecutionContext` | `ProfileBoundaryEnforcerTest`, `ProfileExecutionContextAccessorsTest`, `ProfileBoundaryRuntimeTest`, `ProfileBoundaryAuditGoldenTest` | `app/src/test/java/com/nexio/tv/core/integration/ProfileBoundaryEnforcerTest.kt`, `app/src/test/java/com/nexio/tv/core/integration/ProfileExecutionContextAccessorsTest.kt`, `app/src/test/java/com/nexio/tv/core/integration/ProfileBoundaryRuntimeTest.kt`, `app/src/test/java/com/nexio/tv/core/integration/ProfileBoundaryAuditGoldenTest.kt` | ✅ |

### Metadata layer

| # | Contract | Guarded by | File:line | Coverage |
|---|---|---|---|---|
| 6 | PREVIEW depth never reaches `MetadataRouter.route` | Hard production guard `require(request.depth != MetadataDepth.PREVIEW)` and PREVIEW-bypass test in `MetadataRouterFacadeTest` returning `resolverSchedule` only | `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouter.kt:18`, `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:37`, `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeTest.kt:26-32`, `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterPrecedenceTest.kt:257,270` | ✅ |
| 7 | `usedInputs` excludes catalog/genre/animeType/link/trend | No test asserting the negative set on `MetadataRequest.usedInputs` (only positive-shape assertions in `MetadataRequestNormalizerTest`) | `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizerTest.kt` | ⚠️ — partial; does not enumerate excluded categories |
| 8 | Provider-native conflict goes through `MetadataIdentityResolver.resolve` first | `MetadataIdentityResolverTest`, `IdentityResolutionTraceTest`, `LocalIdMappingStoreTest` | `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataIdentityResolverTest.kt`, `app/src/test/java/com/nexio/tv/core/metadata/router/IdentityResolutionTraceTest.kt`, `app/src/test/java/com/nexio/tv/core/metadata/router/LocalIdMappingStoreTest.kt` | ✅ |
| 9 | `FieldResolver` is the sole producer of `ResolvedMetadataDocument` | `FieldResolverTest`, `FieldSelectedTraceTest`, `MetadataArchitectureBoundaryTest` | `app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverTest.kt`, `app/src/test/java/com/nexio/tv/core/metadata/router/FieldSelectedTraceTest.kt`, `app/src/test/java/com/nexio/tv/metadata/audit/MetadataArchitectureBoundaryTest.kt` | ⚠️ — no negative architecture rule asserting that NO other class returns `ResolvedMetadataDocument` |
| 10 | Premium artwork can override POSTER but NOT TITLE/OVERVIEW/EPISODE_LIST | No test in the metadata router or `RpdbIntegrationProvider` test exercising the field-override allow-list; `OverridePolicy` symbol not found in production | `app/src/test/java/com/nexio/tv/data/integration/posters/RpdbIntegrationProviderTest.kt` (positive poster path only) | ❌ |
| 11 | TVDB localized → TVDB English fallback (NOT TMDB) | `TvdbCoreLocalizationTest` (`localized missing overview falls back to english tvdb translation not extended original overview`), `TvdbEpisodeLocalizationTest` (`missing localized episode falls back to english metadata`), `TvdbGracefulFallbackTest` | `app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbCoreLocalizationTest.kt:13`, `app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbEpisodeLocalizationTest.kt:194`, `app/src/test/java/com/nexio/tv/core/tvdb/TvdbGracefulFallbackTest.kt` | ✅ |
| 12 | English localized payload uses CacheFirst policy | `LocalizationPolicyTest`, `TmdbLocalizationPolicyTest`, `KitsuLocalizationPolicyTest` | `app/src/test/java/com/nexio/tv/data/integration/metadata/LocalizationPolicyTest.kt`, `app/src/test/java/com/nexio/tv/data/integration/metadata/TmdbLocalizationPolicyTest.kt`, `app/src/test/java/com/nexio/tv/data/integration/metadata/KitsuLocalizationPolicyTest.kt` | ⚠️ — policy chosen, but no test asserts subsequent runtime call honors `CacheFirst` for the English variant specifically |

### Profile boundary

| # | Contract | Guarded by | File:line | Coverage |
|---|---|---|---|---|
| 13 | Profile2 cannot read Profile1 Trakt token | `TraktAuthDataStoreCrossProfileTest`, `TraktAuthDataStoreProfileTest` | `app/src/test/java/com/nexio/tv/data/local/TraktAuthDataStoreCrossProfileTest.kt`, `app/src/test/java/com/nexio/tv/data/local/TraktAuthDataStoreProfileTest.kt` | ✅ |
| 14 | Profile2 cannot read Profile1 Simkl token | `SimklAuthDataStoreCrossProfileTest`, `SimklAuthDataStoreProfileTest` | `app/src/test/java/com/nexio/tv/data/local/SimklAuthDataStoreCrossProfileTest.kt`, `app/src/test/java/com/nexio/tv/data/local/SimklAuthDataStoreProfileTest.kt` | ✅ |
| 15 | Outbox drain filters by profileId AND credentialHash | `ProviderMutationOutboxCrossProfileTest`, `TraktMutationOutboxCoordinatorTest`, `TraktMutationOutboxStoreTest`, `TraktMutationOutboxPolicyTest` | `app/src/test/java/com/nexio/tv/data/trakt/outbox/ProviderMutationOutboxCrossProfileTest.kt`, `app/src/test/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxCoordinatorTest.kt`, `app/src/test/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStoreTest.kt`, `app/src/test/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxPolicyTest.kt` | ✅ |
| 16 | `setActiveProfile` rejected during active playback | `ProfileSwitchDuringPlaybackTest`, `PlaybackSessionRegistryTest` | `app/src/test/java/com/nexio/tv/core/profile/ProfileSwitchDuringPlaybackTest.kt`, `app/src/test/java/com/nexio/tv/core/playback/PlaybackSessionRegistryTest.kt` | ✅ |
| 17 | Stale-session async writes rejected via `assertCanWriteProfileState` | `ProfileBoundaryRuntimeTest`, `ProfileBoundaryEnforcerTest` invoke the validation but no test directly exercises the post-switch async-write rejection path on a real DataStore | `app/src/test/java/com/nexio/tv/core/integration/ProfileBoundaryRuntimeTest.kt`, `app/src/test/java/com/nexio/tv/core/integration/ProfileBoundaryEnforcerTest.kt` | ⚠️ — symbolic guard tested, end-to-end stale-write rejection on durable stores not exercised |

### Continue Watching

| # | Contract | Guarded by | File:line | Coverage |
|---|---|---|---|---|
| 18 | CW write is profile-keyed | `ContinueWatchingSnapshotServiceMutationTest`, `ContinueWatchingSnapshotServiceProfileBoundaryTest`, `HomeCatalogSnapshotStoreTest` | `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt`, `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceProfileBoundaryTest.kt`, `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt` | ✅ |
| 19 | Profile1 records not visible to Profile2 | `ContinueWatchingSnapshotServiceProfileBoundaryTest`, `ContinueWatchingProfileScopedQueryTest` | `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceProfileBoundaryTest.kt`, `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingProfileScopedQueryTest.kt` | ✅ |
| 20 | CW typed record has profileId field | `ContinueWatchingRecordTest`, `ContinueWatchingProfileScopedQueryTest` (`mapping rejects non-positive profileId via record init`) | `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingRecordTest.kt`, `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingProfileScopedQueryTest.kt:37` | ✅ |
| 21 | CW query API takes explicit profileId | `ContinueWatchingProfileScopedQueryTest` (`ProfileOwnedContinueWatchingSnapshot.toContinueWatchingRecords()` constructs records with explicit `profileId`) | `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingProfileScopedQueryTest.kt:17-27` | ✅ |

### Playback / scrobble

| # | Contract | Guarded by | File:line | Coverage |
|---|---|---|---|---|
| 22 | Scrobble uses playback owner profile (not current active) | `TrackingScrobbleServicePlaybackOwnerTest`, `PlaybackOwnerContextTest` | `app/src/test/java/com/nexio/tv/data/repository/TrackingScrobbleServicePlaybackOwnerTest.kt`, `app/src/test/java/com/nexio/tv/core/playback/PlaybackOwnerContextTest.kt` | ✅ |
| 23 | `PlaybackSessionRegistry` tracks active session | `PlaybackSessionRegistryTest`, `ProfileSwitchDuringPlaybackTest` | `app/src/test/java/com/nexio/tv/core/playback/PlaybackSessionRegistryTest.kt`, `app/src/test/java/com/nexio/tv/core/profile/ProfileSwitchDuringPlaybackTest.kt` | ✅ |
| 24 | `checkin()` correctly NOT migrated to PlaybackOwnerContext | Production: `TrackingScrobbleService.checkin` exposes optional `ownerProfileId` and uses ad-hoc profile lookup; `TrackingWatchingNowRoutingTest` and `TraktMutationRoutingAuditTest` cover routing. There is no negative test asserting that `checkin()` does NOT participate in the `PlaybackOwnerContext` migration nor an architecture rule preventing such migration. | `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt:41,95`, `app/src/test/java/com/nexio/tv/data/repository/TrackingWatchingNowRoutingTest.kt`, `app/src/test/java/com/nexio/tv/data/repository/TraktMutationRoutingAuditTest.kt` | ❌ — contract intentionally not migrated, but no automated guard pins this design decision |

### Trace mode

| # | Contract | Guarded by | File:line | Coverage |
|---|---|---|---|---|
| 25 | HTTP request correlates to runtime op via `RuntimeTraceContext` tag | `RuntimeTraceContextRequestTaggingInterceptorTest`, `RuntimeTraceContextElementTest`, `RuntimeTraceContextElementThreadLocalTest` | `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceContextRequestTaggingInterceptorTest.kt`, `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceContextElementTest.kt`, `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceContextElementThreadLocalTest.kt` | ✅ |
| 26 | Redaction covers Authorization, api_key, OAuth tokens, etc. | `TraceRedactorTest` (Authorization header, api_key URL param) | `app/src/test/java/com/nexio/tv/core/trace/TraceRedactorTest.kt:12-28` | ⚠️ — covers Authorization + `api_key`; no explicit cases for OAuth `access_token`, `refresh_token`, Trakt `Bearer`, Simkl `simkl-api-key` header, X-API-Key, cookies |
| 27 | Body capture gated to internal-only mode | `TraceModeTest`, `RuntimeTraceInterceptorTest` (mode-gated capture path) | `app/src/test/java/com/nexio/tv/core/trace/TraceModeTest.kt`, `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceInterceptorTest.kt` | ⚠️ — mode toggling exercised, but no explicit assertion `internalOnly == false ⇒ body MUST be absent` |
| 28 | JSONL writer flushes after each append | `JsonlTraceWriterTest`, `FileRuntimeTraceSinkTest`, `FileRuntimeTraceSinkRingBufferTest` | `app/src/test/java/com/nexio/tv/core/trace/JsonlTraceWriterTest.kt`, `app/src/test/java/com/nexio/tv/core/trace/FileRuntimeTraceSinkTest.kt`, `app/src/test/java/com/nexio/tv/core/trace/FileRuntimeTraceSinkRingBufferTest.kt` | ✅ |
| 29 | Validator agrees with real emissions | `RuntimeTraceValidatorRealEmissionTest`, `TraceBundleGoldenTest`, `TraceValidationRulesTest` | `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceValidatorRealEmissionTest.kt`, `app/src/test/java/com/nexio/tv/core/trace/TraceBundleGoldenTest.kt`, `app/src/test/java/com/nexio/tv/core/trace/TraceValidationRulesTest.kt` | ✅ |
| 30 | `RuntimeTraceContextElement` carries via `ThreadContextElement` | `RuntimeTraceContextElementThreadLocalTest`, `RuntimeTraceContextElementTest` | `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceContextElementThreadLocalTest.kt`, `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceContextElementTest.kt` | ✅ |
| 31 | Trace as network interceptor (sees post-auth request on derived clients) | Production wiring uses `addNetworkInterceptor(traceInterceptor)`; `TraceInterceptorOrderingTest` asserts ordering vs auth | `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt:127,205`, `app/src/test/java/com/nexio/tv/core/trace/TraceInterceptorOrderingTest.kt` | ⚠️ — wired correctly but no test on a derived client (e.g. one created via `okHttpClient.newBuilder().addInterceptor(...).build()`) demonstrating the trace interceptor still fires |

## Coverage summary

- 31 contracts evaluated
- ✅ 19
- ⚠️ 9
- ❌ 3

## Coverage gaps (P1 candidate findings)

Each row below corresponds to a ⚠️ or ❌ contract above. Findings are tagged `F-TM-NN` so
they can be cross-referenced from the lane files in Phase 5 (lanes A/B/D/E/F/H/I).

- **F-TM-01** (Contract 3, ⚠️): `IntegrationBackoffManagerTest` only exercises 429 + Retry-After; no 5xx escalation, no exponential schedule, no clear-on-success or honor-on-second-call test. Lane: D-cache-backoff.
- **F-TM-02** (Contract 4, ❌): No single-flight / coalescing test anywhere on the branch and no `joinSingleFlight`/`coalesce` symbol in production. Either the contract is unimplemented or the implementation is implicit and untestable. Lane: A-runtime-control-plane.
- **F-TM-03** (Contract 7, ⚠️): `MetadataRequestNormalizerTest` asserts what `usedInputs` includes but never asserts that catalog / genre / animeType / link / trend are excluded. A hostile test passing each excluded category should be added. Lane: B-metadata-router.
- **F-TM-04** (Contract 9, ⚠️): No architectural negative test pinning `FieldResolver` as the SOLE producer of `ResolvedMetadataDocument`. `MetadataArchitectureBoundaryTest` should be extended with a class-scan rule. Lane: B-metadata-router.
- **F-TM-05** (Contract 10, ❌): No test exercises premium-artwork override allow-list (POSTER yes, TITLE/OVERVIEW/EPISODE_LIST no). `RpdbIntegrationProviderTest` only covers happy poster path. Lane: B-metadata-router.
- **F-TM-06** (Contract 12, ⚠️): Localization policy tests assert the chosen policy but no integration-level test verifies the runtime call for the English variant actually uses CacheFirst (cache-hit suppression path). Lane: E-localization.
- **F-TM-07** (Contract 17, ⚠️): `assertCanWriteProfileState` is unit-tested at the enforcer level but there is no end-to-end test that an in-flight async write to a real DataStore (Trakt token, Simkl token, CW snapshot) is rejected after `setActiveProfile`. Lane: F-profile-boundaries.
- **F-TM-08** (Contract 24, ❌): The intentional decision to NOT migrate `checkin()` to `PlaybackOwnerContext` is not pinned by any architecture test. A future migration could silently break the contract. An architecture rule asserting `TrackingScrobbleService.checkin` does NOT take a `PlaybackOwnerContext` should be added. Lane: H-playback-scrobble.
- **F-TM-09** (Contract 26, ⚠️): `TraceRedactorTest` covers Authorization + `api_key`, but does not cover OAuth `access_token` / `refresh_token` body fields, Simkl `simkl-api-key` header, `X-API-Key`, cookies, or query-string `token` variants. Lane: I-trace-mode.
- **F-TM-10** (Contract 27, ⚠️): No assertion of the form "when mode != internal, request/response bodies MUST be absent from emitted events". Mode toggling is tested but the negative invariant is not. Lane: I-trace-mode.
- **F-TM-11** (Contract 31, ⚠️): Trace is correctly wired as `addNetworkInterceptor`, and `TraceInterceptorOrderingTest` covers ordering, but no test exercises a derived OkHttp client (`okHttpClient.newBuilder()…build()`) to confirm the network interceptor still fires after derivation — the most common foot-gun. Lane: I-trace-mode.

## Cross-references

- Production-path findings (F-01 .. F-53) — see `00-executive-summary.md`
- Red-flag scan — see `red-flags/scan-results.md`
- Boundary map — see `02-architecture-boundary-map.md`
- Lane files: `lanes/A-runtime-control-plane.md`, `lanes/B-metadata-router.md`,
  `lanes/D-cache-backoff.md`, `lanes/E-localization.md`,
  `lanes/F-profile-boundaries.md`, `lanes/H-playback-scrobble.md`,
  `lanes/I-trace-mode.md` (lane authors should append `F-TM-*` IDs as they
  pick up Phase-5 work).
