# Diff Map — main → 774a540f8 (integration-runtime-phase-a)

> Navigation map of architecture-surface changes. Not a commit walk.
> Base: `main`. Review SHA: `774a540f8`. Total: 883 files changed (+191 625 / -6 870).

---

## 1. Files changed by package

| Package | Files Δ | Work summary |
|---|---|---|
| `core/integration` | 41 new | Entire IntegrationRuntime subsystem introduced: `IntegrationRuntime` interface + `DefaultIntegrationRuntime` impl, cache/backoff/single-flight/ownership/orphan-cleanup/network-permit/planner/hydration infra, profile-boundary enforcement, rail identity/key/preview, audit sink. |
| `core/metadata/router` | 21 new | Full metadata router layer: `MetadataRouterFacade`, `FieldResolver`, `MetadataRouter`, `ProviderPlanExecutor/Runner`, `ResolverOrchestrator`, ID-mapping store, anime-identity index, execution models, request normalizer, four network resolvers (trailer/review/recommendation/org-person). |
| `core/metadata/composition` | 1 new | `GlobalMetadataDocument` — cross-provider composition model. |
| `core/trace` | 27 new | Full runtime trace subsystem: `RuntimeTraceSink`, `TraceSessionManager`, `RuntimeTraceInterceptor`, `RuntimeTraceEventListener`, trace-mode DataStore, JSONL writer, bundle exporter, redactor, validator + rules, `UnscopedNetworkPolicyGuard`, `FirstPaintTracer`, `TracedTransport`. |
| `core/profile` | 2 new + 1 modified | `ProfileSettingsSnapshot`, `ProfileSwitchDeferralPolicy` added; `ProfileManager` extended with deferral policy and `PlaybackSessionRegistry` wiring. |
| `core/playback` | 2 new | `PlaybackSessionRegistry` (single-slot atomic slot), `PlaybackOwnerContext` — playback-owner identity for scrobble boundary checks. |
| `core/di` | 4 new + 4 modified | `IntegrationRuntimeModule`, `MetadataExecutionModule`, `MetadataRouterModule`, `RuntimeTraceModule` added; `NetworkModule`, `RepositoryModule`, `TraktMutationOutboxModule` updated for new interceptors/executors. |
| `data/integration/<provider>` | 87 new | Full provider-adapter layer across 18 provider namespaces (see §2 for list). Each namespace follows the pattern: one or more `*IntegrationProvider` implementations + optional `transport/` sub-package. |
| `data/integration/metadata` | 16 new | Metadata provider adapters: TMDB, TVDB, Kitsu, RPDB, TopPosters adapters; localization stack (`LocalizationPolicy`, `LocalizationResolver`, `TvdbEpisodeLocalization`, `LocalizationModels`); `MetadataSecondaryRepository`, `RuntimeMetadataIdentityLookup`, `MetadataAdapterCandidates`. |
| `data/local/integration` | 15 new | Room integration-cache database: `IntegrationCacheDatabase`, `IntegrationCacheDao`, `IntegrationProviderBackoffDao`, `MediaIdentityDao`, `RailStoreDao` + 9 Room entities covering cache blobs, ownership, backoff, media identity, rail rows and previews; `LocalIntegrationCacheStore`, `IntegrationBlobStore`. |
| `data/repository` | 71 modified / 16 new | Repositories migrated to integration-provider pattern; `ContinueWatchingSnapshotService` extended (profile-boundary enforcement, trace sink wiring); new repos: `CatalogRailRepository`, `DebridLibraryRepository`, `ProviderSettingsRepository`, `ReviewsRepository`, `TrackingRepository`, auth gateways for RealDebrid/Kitsu/Simkl/Trakt, `ImdbTitleSearchRepository`, `PosterRepository`, `RatingsRepository`. |
| `domain/model` | 3 new | `RailItemPreview`, `RailPreviewCatalogRowRecord`, `RailPreviewLegacyAdapters`. |
| `ui/screens/home` | new + modified | `HomeRailHydrationExecutor` (interface + `DefaultHomeRailHydrationExecutor` impl), `HomeFirstPaintMetadataMapper`; `HomeViewModel` migrated to `MetadataRouterFacade`. |
| `ui/screens/settings` | 4 new | `RuntimeTraceLiveStatusScreen/ViewModel`, `RuntimeTraceSettingsScreen/ViewModel` — trace-mode controls. |
| `core/tvdb` | 2 new + 6 modified | `TvdbLoginGateway`, `TvdbSettingsAuthGateway` extracted; `TvdbMetadataService`, `TvdbIdentityService`, `TvdbUpdateCoordinator` updated to delegate to integration provider adapters. |
| `core/image` | 2 new + 1 modified | `IntegrationPosterFetcher`, `PosterIntegrationRequest` — Coil fetcher backed by integration runtime. `ArtworkImageCacheKeys` updated. |

---

## 2. New production files

Grouped by purpose.

### New runtime core (core/integration — 41 files)

`IntegrationRuntime`, `DefaultIntegrationRuntime`, `IntegrationSpec`, `IntegrationCallSpec`, `IntegrationStreamSpec`, `IntegrationStreamHandle`, `IntegrationFetchOptions`, `IntegrationFetchResult`, `IntegrationCallResult`, `IntegrationLoadResult`, `IntegrationProvider`, `IntegrationProviderPolicy`, `IntegrationPolicyRegistry`, `IntegrationCachePolicy`, `IntegrationCacheStore`, `IntegrationCacheOwnership`, `IntegrationCacheOwnershipFactory`, `IntegrationBackoffManager`, `IntegrationSingleFlight`, `IntegrationHydrationCoordinator`, `IntegrationHydrationPlanner`, `IntegrationKeyFactory`, `IntegrationCodec`, `IntegrationCredentialHash`, `IntegrationHeaderPolicies`, `IntegrationNetworkPermit`, `IntegrationOrphanCleanupService`, `IntegrationOwnershipService`, `IntegrationPlaybackGate`, `IntegrationScope`, `IntegrationWorkClass`, `IntegrationApiShapes`, `IntegrationAudit`, `ProfileBoundaryEnforcer`, `ProfileBoundaryViolation`, `ProfileExecutionContext`, `ProviderRequestGate`, `ActiveRailTracker`, `RailIdentityHarvester`, `RailKeyFactory`, `RailMediaIdentityResolver`

### New metadata router layer (core/metadata/router — 21 files)

`MetadataRouterFacade`, `MetadataRouter`, `FieldResolver`, `ProviderPlanExecutor`, `ProviderPlanRunner`, `ResolverOrchestrator`, `MetadataIdentityResolver`, `MetadataRequestNormalizer`, `MetadataProviderAdapter`, `MetadataProviderAdapterShapeRegistry`, `MetadataCacheKeys`, `MetadataExecutionModels`, `MetadataModels`, `IdMappingStore`, `LocalIdMappingStore`, `AnimeIdentityIndex`, `resolver/TrailerResolver`, `resolver/ReviewResolver`, `resolver/RecommendationResolver`, `resolver/OrganizationPersonResolver`

### New trace subsystem (core/trace — 27 files)

`RuntimeTraceSink`, `RuntimeTraceContext`, `RuntimeTraceContextElement`, `RuntimeTraceContextRequestTaggingInterceptor`, `RuntimeTraceInterceptor`, `RuntimeTraceEventListener`, `RuntimeTraceValidator`, `DataStoreTraceModeProvider`, `FileRuntimeTraceSink`, `JsonlTraceWriter`, `TraceSessionManager`, `TraceBundleExporter`, `TraceEventEnvelope`, `TraceHash`, `TraceMetadataEvents`, `TraceMode`, `TraceRedactor`, `TraceSession`, `TraceSummaryGenerator`, `TraceValidationReport`, `TraceValidationRule`, `TraceValidationRules`, `TracedTransport`, `UnscopedNetworkPolicyGuard`, `SourceSurface`, `TraceCacheDecision`, `FirstPaintTracer`

### New provider adapters (data/integration — 87 files across 18 namespaces)

| Namespace | Key files |
|---|---|
| `addon` | `AddonCatalogIntegrationProvider`, `AddonManifestIntegrationProvider`, `AddonMetaIntegrationProvider`, `AddonStreamIntegrationProvider`, `AddonSubtitleIntegrationProvider`, `transport/AddonStreamRequestCanceller` |
| `collector` | `ShadowAutoplayUploadIntegrationProvider`, `transport/ShadowAutoplayUploadTransport` |
| `debrid` | `EasyDebridIntegrationProvider`, `PremiumizeIntegrationProvider`, `RealDebridIntegrationProvider`, `RealDebridAuthIntegrationProvider`, `TorBoxIntegrationProvider`, `transport/DirectBenchmarkReadableSourceFactoryBuilder`, `transport/DirectDiscardBenchmarkTransport`, `transport/PlayerPipelineBenchmarkTransportFactory` (moved from `data/repository/benchmark/`) |
| `github` | `GitHubAssetDownloadIntegrationProvider`, `GitHubReleaseIntegrationProvider`, `transport/GitHubAssetDownloadTransport` |
| `imdb` | `CustomImdbRatingsIntegrationProvider`, `ImdbTitleSearchIntegrationRepository`, `transport/CustomImdbRatingsTransport`, `transport/ImdbSearchRestTransport`, `transport/ImdbSearchWebSocketTransport` |
| `kitsu` | `KitsuDiscoveryIntegrationProvider`, `KitsuIntegrationProvider` |
| `mdblist` | `MDBListIntegrationProvider` |
| `metadata` | Adapters for TMDB (primary, org-person, recommendation, review, trailer), TVDB (primary, org-person, trailer), Kitsu, RPDB, TopPosters + `LocalizationPolicy`, `LocalizationResolver`, `LocalizationModels`, `TvdbEpisodeLocalization`, `MetadataSecondaryRepository`, `RuntimeMetadataIdentityLookup` |
| `omdb` | `OmdbIntegrationProvider` |
| `playback` | `OpenSubtitlesHashIntegrationProvider`, `PlaybackPreflightIntegrationProvider`, `transport/CometProxyHttpTransport`, `transport/DiskSpoolHttpTransport`, `transport/PlaybackMediaSourceTransport`, `transport/PlaybackProbeTransport` |
| `posters` | `RpdbIntegrationProvider`, `RpdbMetadataProviderAdapter`, `TopPostersIntegrationProvider`, `TopPostersMetadataProviderAdapter`, `transport/PosterTransport` |
| `railpreview` | `RailPreviewMapper`, `TmdbRailPreviewMapper`, `TraktRailPreviewMapper`, `KitsuRailPreviewMapper`, `MDBListRailPreviewMapper`, `SimklRailPreviewMapper` |
| `simkl` | `SimklAuthIntegrationProvider`, `SimklIntegrationProvider`, `transport/SimklDiscoveryTransport`, `transport/SimklProgressTransport` |
| `skip` | `AniSkipIntegrationProvider`, `AnimeSkipIntegrationProvider`, `ArmIntegrationProvider`, `IntroDbIntegrationProvider` |
| `subtitles` | `SubtitleSourceDownloadIntegrationProvider`, `SubtitleTranslationIntegrationProvider`, `transport/*` |
| `tmdb` | `TmdbIntegrationProvider`, `TmdbExternalIdLookupProvider`, `TmdbOrganizationProvider` |
| `trailer` | `TrailerBackendProvider`, `TrailerTmdbProvider` |
| `trakt` | `TraktIntegrationProvider` |
| `tvdb` | `TvdbIntegrationProvider`, `TvdbLoginIntegrationProvider` |
| `youtube` | `YouTubeTrailerIntegrationProvider`, `transport/OkHttpYouTubeTrailerTransport`, `transport/YouTubeTrailerTransport` |
| `supabase` | `transport/TvLoginExchangeTransport` |

### New storage layer (data/local/integration — 15 files)

`IntegrationCacheDatabase`, `IntegrationCacheDao`, `IntegrationCacheEntity`, `IntegrationOwnerEntity`, `ExternalIdEntity`, `IntegrationProviderBackoffDao`, `IntegrationProviderBackoffEntity`, `MediaIdentityDao`, `MediaIdentityEntity`, `RailStoreDao`, `RailCacheEntity`, `RailItemEntity`, `RailItemPreviewEntity`, `LocalIntegrationCacheStore`, `IntegrationBlobStore`

### New DataStore (data/local)

`TraceSettingsDataStore` — single key `trace_mode` (string preferences key)

### New UI screens (ui/screens)

`RuntimeTraceLiveStatusScreen`, `RuntimeTraceLiveStatusViewModel`, `RuntimeTraceSettingsScreen`, `RuntimeTraceSettingsViewModel`

### New build tooling (buildSrc)

`IntegrationAuditPlugin`, `GenerateIntegrationRuntimeAuditTask`, `IntegrationAuditModels`, `IntegrationAuditReportBuilder`, `IntegrationAuditReportWriter`, `IntegrationContractRegistry`, `IntegrationSourceScanner`

---

## 3. Deleted production files

| File | Reason |
|---|---|
| `data/repository/benchmark/PlayerPipelineBenchmarkTransportFactory.kt` | Moved to `data/integration/debrid/transport/` (git rename R078) |
| `ui/screens/player/PlayerPlaybackNetworking.kt` | Deleted — networking moved into integration-provider transports |

---

## 4. Modified public APIs

| Surface | Change summary |
|---|---|
| `MetadataRouterFacade` | New class (was not on `main`). Public surface: `routeRequest`, `resolveRequest`, `fetchTvEnrichment`, `fetchTmdbEnrichment`, `fetchTrailer`, `fetchReviews`, `fetchReviewsPage`, `fetchRecommendations`, `findPersonIdByExactName`, `findCompanyIdByExactName`, `fetchPersonDetail`, `fetchTvEpisodeEnrichment`, `fetchTvSeasonEpisodes`. |
| `FieldResolver` | New class. Public: `resolve(primary, secondary, requestContentId)`, `resolveWithPreview(preview, primary, secondary, requestContentId)`. |
| `IntegrationRuntime` | New interface. Public: `get(spec, options)`, `call(spec)`, `open(spec)`. |
| `ContinueWatchingSnapshotService` | Extended: `installTraceSink(sink, sessionIdProvider)` static slot added; `observeProfileSnapshot(profileId)`, `applyEpisodesMarked`, `rollbackEpisodes`, `removeShowOptimistically`, `recordMetadataSnapshot` added/modified. |
| `ProfileManager` | `ProfileSwitchDeferralPolicy` wired internally; `setActiveProfile` now calls `ProfileBoundaryEnforcer.assertCanSwitchProfile` and `PlaybackSessionRegistry.isIdle` guard. |
| `PlaybackSessionRegistry` | New class. Public: `register(context)`, `unregister(token)`, `activeOwner()`, `isIdle()`. |
| `TrackingScrobbleService` | Interface now requires `PlaybackOwnerContext owner` on `scrobbleStart/Stop/Pause`; `checkin` gains optional `ownerProfileId`. |
| `LocalizationPolicy` | New class (`data/integration/metadata`). Factory methods: `LocalizationPolicy.tvdb(language)`, `.tmdb(language)`, `.kitsu(language)`. Carries `CURRENT_VERSION = 2`. |
| `HomeRailHydrationExecutor` | New interface (extracted from HomeViewModel). Impl: `DefaultHomeRailHydrationExecutor`. |

---

## 5. New DI bindings

### `IntegrationRuntimeModule`
- `@Provides @Singleton` `IntegrationPolicyRegistry` ← `defaultIntegrationPolicyRegistry()`
- `@Provides @Singleton` `IntegrationCacheDatabase` (Room, `integration-cache.db`)
- `@Provides` `IntegrationCacheDao` ← `database.cacheDao()`
- `@Provides` `IntegrationProviderBackoffDao` ← `database.backoffDao()`
- `@Provides` `RailStoreDao` ← `database.railStoreDao()`
- `@Provides` `MediaIdentityDao` ← `database.mediaIdentityDao()`
- `@Provides @Singleton` `IntegrationCacheStore` ← `LocalIntegrationCacheStore`
- `@Provides @Singleton` `IntegrationAuditSink` ← `NoOpIntegrationAuditSink`
- `@Provides @Singleton` `IntegrationRuntime` ← `DefaultIntegrationRuntime`
- `@Provides @Singleton` `IntegrationHydrationCoordinator` ← `DefaultIntegrationHydrationCoordinator`
- `@Provides @Singleton` `HomeRailHydrationExecutor` ← `DefaultHomeRailHydrationExecutor`

### `MetadataExecutionModule`
- `@Binds @IntoSet` `MetadataProviderAdapter` for: `TmdbMetadataProviderAdapter`, `TvdbMetadataProviderAdapter`, `KitsuMetadataProviderAdapter`, `TmdbTrailerMetadataAdapter`, `TvdbTrailerMetadataAdapter`, `TmdbReviewMetadataAdapter`, `TraktReviewMetadataAdapter`, `TmdbRecommendationMetadataAdapter`, `TmdbOrganizationPersonAdapter`, `TvdbOrganizationPersonAdapter`, `RpdbMetadataProviderAdapter`, `TopPostersMetadataProviderAdapter`
- `@Binds` `MetadataIdentityResolver.Lookup` ← `RuntimeMetadataIdentityLookup`

### `MetadataRouterModule`
- `@Binds @Singleton` `IdMappingStore` ← `LocalIdMappingStore`
- `@Binds @Singleton` `AnimeIdentityIndex` ← `AssetAnimeIdentityIndex`

### `IntegrationProviderModule`
- `@Binds @Singleton` `ProviderSettingsRepository` ← `DefaultProviderSettingsRepository`
- `@Binds @Singleton` `ReviewsRepository` ← `DefaultReviewsRepository`
- `@Binds @Singleton` `TvdbLoginGateway` ← `TvdbLoginIntegrationProvider`
- `@Binds @Singleton` `TmdbExternalIdLookupProvider` ← `DefaultTmdbExternalIdLookupProvider`

### `RuntimeTraceModule`
- `@Provides @Singleton` `TraceBuildInfo`
- `@Provides @Singleton` `TraceSessionManager`
- `@Provides @Singleton` `RuntimeTraceSink` (+ side-effect: installs `ProfileBoundaryEnforcer` and `ContinueWatchingSnapshotService` static sinks)
- `@Provides @Singleton` `TraceModeProvider` ← `DataStoreTraceModeProvider`
- `@Provides @Singleton` `TraceRedactor`
- `@Provides @Singleton` `TraceMetadataEvents` (+ side-effect: installs `FirstPaintTracer`)
- `@Provides @Singleton` `UnscopedNetworkPolicyGuard`
- `@Provides @Singleton` `RuntimeTraceContextRequestTaggingInterceptor`
- `@Provides @Singleton` `RuntimeTraceInterceptor`
- `@Provides @Singleton` `EventListener.Factory` ← `RuntimeTraceEventListener` factory

---

## 6. New storage schemas

### Room — `IntegrationCacheDatabase` (`integration-cache.db`)

| Entity | Purpose |
|---|---|
| `IntegrationCacheEntity` | Blob cache entries keyed by provider+cacheKey |
| `IntegrationOwnerEntity` | Ownership records per provider+contentId |
| `ExternalIdEntity` | Cross-provider external ID mappings (IMDB, TVDB, TMDB) |
| `IntegrationProviderBackoffEntity` | Per-provider backoff state (nextAllowedMs) |
| `MediaIdentityEntity` | Canonical media identity with resolved external IDs |
| `RailCacheEntity` | Rail-level cache header (provider, catalog, ttl) |
| `RailItemEntity` | Individual rail item row |
| `RailItemPreviewEntity` | Poster/title preview data per rail item |

### DataStore

| Key | File | Purpose |
|---|---|---|
| `trace_mode` (string) | `trace_settings` | Persists `TraceMode` enum (OFF / ALWAYS_ON / AUTO) |

---

## 7. New generated audit tasks

All added in `app/build.gradle.kts`, group `verification`:

| Task name | What it runs |
|---|---|
| `generateRuntimeEventAuditSample` | Runs `DefaultIntegrationRuntimeTest` to emit JSONL event sample |
| `generateIntegrationRuntimeAudit` | `GenerateIntegrationRuntimeAuditTask` — scans source, validates API shapes/contracts, writes Markdown+JSON reports to `build/reports/integration-runtime-audit/` |
| `generateMetadataExecutionAudit` | Runs `MetadataExecutionAuditGoldenTest` + `MetadataArchitectureBoundaryTest` |
| `generateProfileBoundaryAudit` | Runs `ProfileBoundaryAuditGoldenTest` |
| `generateTraceValidatorAudit` | Runs `TraceBundleGoldenTest` + `*Validator*Test` |

---

## 8. Changed test files

### New architecture pin tests (`architecture/`)

| Test | What it enforces |
|---|---|
| `IntegrationBoundaryTest` | No Retrofit API usage outside `data/integration/` |
| `NoIntegrationRuntimeInjectionOutsideBoundaryTest` | `IntegrationRuntime` not injected in feature/UI packages |
| `NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest` | Full-tree scan for direct Retrofit API usage |
| `NoDirectOkHttpOutsideRuntimeTransportPackagesTest` | OkHttpClient/Retrofit not used outside transport packages |
| `NoDirectAuthProviderNetworkOwnersTest` | Auth services not bypassed for network ownership |
| `NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest` | Auth services only used inside integration boundary |
| `MetadataRouterBoundaryTest` | Production callers use `MetadataRouterFacade`, not legacy services |
| `MetadataProductionBoundaryTest` | Entrypoints use facade/repository ownership symbols |
| `IntegrationProviderContractConformanceTest` | All providers satisfy contract registry |
| `IntegrationApiShapeRegistryCoverageTest` | All API shapes are registered |
| `NoRawProviderInjectionTest` | Raw provider types not injected outside `data/integration/` |
| `ProfileBoundaryArchitectureTest` | Profile-boundary enforcement structure |
| `NoRuntimeSpecOutsideIntegrationPackagesTest` | Spec types scoped to integration packages |
| `PlaybackSessionRegistrySingleSlotTest` | Registry single-slot invariant |
| `SkipIntroRepositoryCanonicalSurfaceTest` | SkipIntro uses canonical surface |
| `RailOwnershipLifecycleTest`, `RailPreviewLifecycleArchitectureTest`, `NoBlockingRailOwnershipSyncTest` | Rail ownership lifecycle |

### New fixture/golden tests

`DefaultIntegrationRuntimeTest`, `DefaultIntegrationRuntimeCoalesceTest`, `DefaultIntegrationRuntimeStaleOn429Test`, `DefaultIntegrationRuntimeStreamBackoffTest`, `IntegrationBackoffManagerTest/ExponentialTest`, `IntegrationCacheOwnershipTest`, `IntegrationSingleFlightTest`, `IntegrationHydrationCoordinatorTest`, `IntegrationHydrationPlannerTest`, `IntegrationOwnershipServiceTest`, `IntegrationOrphanCleanupServiceTest`, `ProfileBoundaryAuditGoldenTest`, `ProfileBoundaryCheckTraceTest`, `MetadataExecutionAuditGoldenTest`

### New service/provider tests

Simkl, TorBox, EasyDebrid, Premiumize scrobble + routing tests; `ContinueWatchingSnapshotServiceObserveProfileSnapshotTest`, `ContinueWatchingSnapshotTraceTest`; `HomeFirstPaintMetadataMapperTest`, `HomeRailHydrationExecutorTest`, `HomeViewModelContinueWatchingProfileScopedTest`; `TrackingScrobbleServiceCheckinShapeTest`, `TrackingScrobbleServicePlaybackOwnerTest`; trailer boundary tests; GitHub provider tests.

---

## 9. Changed production callers

| Caller | Before | After |
|---|---|---|
| `HomeViewModel` | Direct Addon hydration in ViewModel body | Delegates to `HomeRailHydrationExecutor`; metadata lookups via `MetadataRouterFacade` |
| `MetaDetailsViewModel` | Calls `TvMetadataRouter`/`TmdbMetadataService` directly | All paths go through `MetadataRouterFacade.*` (fetch enrichment, trailer, reviews, recommendations, person detail) |
| `PlayerRuntimeController` | Direct network calls, `PlayerPlaybackNetworking` | Streams through integration-provider transports; scrobble calls carry `PlaybackOwnerContext` |
| `TvdbMetadataService` / `TmdbMetadataService` | Callers | Now called only by corresponding `*MetadataProviderAdapter` inside `data/integration/metadata/` |
| `ContinueWatchingSnapshotService` | No boundary checks on write | `ProfileBoundaryEnforcer.assertCanWriteProfileState` called before all write paths |
| `TrackingScrobbleService` callers (`HomeViewModelContinueWatching`, `MetaDetailsViewModel`, `PlayerRuntimeControllerPlaybackEvents`) | Scrobble calls without owner context | Calls now include `PlaybackOwnerContext` on start/stop/pause |
