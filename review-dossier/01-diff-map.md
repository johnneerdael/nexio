# Diff Map

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Base:** `main`
- **Total commits:** 175
- **Total files changed:** 1078 files changed, 164084 insertions(+), 145526 deletions(-)

## Files changed by package

| Package | Added | Modified | Deleted |
|---|---:|---:|---:|
| core/integration | 40 | 0 | 0 |
| core/metadata/router | 16 | 0 | 0 |
| core/metadata/resolve | 0 | 0 | 0 |
| core/trace | 27 | 0 | 0 |
| core/profile | 1 | 1 | 0 |
| core/playback | 2 | 0 | 0 |
| data/integration/tmdb | 2 | 1 | 0 |
| data/integration/tvdb | 2 | 0 | 0 |
| data/integration/kitsu | 2 | 0 | 0 |
| data/integration/trakt | 1 | 0 | 0 |
| data/integration/simkl | 4 | 0 | 0 |
| data/integration/mdblist | 1 | 0 | 0 |
| data/integration/posters | 3 | 0 | 0 |
| data/integration/metadata | 11 | 0 | 0 |
| data/repository | 20 | 52 | 3 |
| data/trakt/outbox | 1 | 3 | 0 |
| data/local | 15 | 13 | 4 |
| core/di | 5 | 4 | 1 |
| ui/screens/home | 2 | 19 | 0 |
| ui/screens/detail | 0 | 3 | 0 |
| ui/screens/player | 0 | 21 | 2 |
| ui/screens/settings | 4 | 22 | 2 |
| test (`app/src/test`) | 188 | 105 | 47 |
| openspec/changes | 5 | 1 | 10 |
| `app/build.gradle.kts` | (single file) | 1 | 0 |

> Note: `core/metadata/resolve/` doesn't exist as a tree on this branch — the metadata resolver code lives under `core/metadata/router/` and `data/integration/metadata/` (which holds the localization resolver and provider adapters).

## New public APIs (substantive)

### `core/integration` — Runtime Phase A (40 new files)
- `interface IntegrationRuntime` and `class DefaultIntegrationRuntime` (orchestrates fetch / single-flight / cache / audit pipeline)
- `sealed class IntegrationScope` with concrete subclasses for `GlobalContent`, `GlobalLocalizedContent(language)`, `GlobalEnglishImage`, `Profile(profileId)`, `Account(profileId, provider, credentialHash)`, `ProfileLocal(profileId)`
- `data class IntegrationCallSpec<T>` + `data class IntegrationStreamSpec<T>` + `interface IntegrationStreamHandle<out T>` + `data class IntegrationFetchOptions` + `data class IntegrationSpec<T>`
- `interface IntegrationCodec<T>` (+ `StringIntegrationCodec`, `JsonCodec<T>`, `FileCodec`, `ByteArrayIntegrationCodec`)
- `interface IntegrationCacheStore` + `class IntegrationCacheOwnershipFactory` + `object IntegrationKeyFactory` + `object RailKeyFactory`
- `class IntegrationOwnershipService` + `data class RailMembership` + `class IntegrationOrphanCleanupService` + `class ActiveRailTracker`
- `class IntegrationBackoffManager` + `class IntegrationSingleFlight` + `class ProviderRequestGate` + `class IntegrationPlaybackGate`
- `class IntegrationPolicyRegistry` + `data class IntegrationProviderPolicy` + `enum IntegrationProvider` + `enum IntegrationWorkClass`
- `data class IntegrationNetworkPermit` + `object IntegrationNetworkPermitContext` + `class IntegrationHostClassifier` + `class IntegrationNetworkPermitInterceptor`
- `object ProfileBoundaryEnforcer` + `enum ProfileBoundaryViolation` + `class ProfileBoundaryException`
- `data class ActiveProfileSession` + `data class ProfileExecutionContext` + `data class ProviderAccountRef`
- `data class IntegrationAuditEvent` + `enum IntegrationAuditPhase` + `enum IntegrationOutcome` + `interface IntegrationAuditSink` (+ `NoOpIntegrationAuditSink`)
- `interface IntegrationHydrationCoordinator` (+ `NoOpIntegrationHydrationCoordinator`, `DefaultIntegrationHydrationCoordinator`) + `class IntegrationHydrationPlanner`
- `class RailMediaIdentityResolver` + `data class ResolvedRailMediaIdentity`
- `class IntegrationPosterFetcher` + `data class PosterIntegrationRequest`
- `object IntegrationHeaderPolicies`
- `object {Addon,Collector,CustomImdb,Debrid,GitHub,Kitsu,MDBList,Omdb,Playback,Poster,Simkl,Skip,Subtitle,Tmdb,Trakt,Tvdb,YouTubeTrailer}ApiShapes` (per-provider shape bundles)

### `core/metadata/router` — Metadata Router (16 new files)
- `class MetadataRouter` + `class MetadataRouterFacade` + `class MetadataRequestNormalizer`
- `data class MetadataRequest`, `NormalizedMetadataRequest`, `MetadataRoute`, `MetadataRouteTrace`, `MetadataSourceContext`
- `data class ProviderPlanStep`, `ProviderExecutionPlan`, `ProviderStepResult`, `ProviderPlanRunResult`, `data class MetadataResolutionResult`
- `class ProviderPlanExecutor` + `class ProviderPlanRunner` + `class ResolverOrchestrator`
- `interface MetadataProviderAdapter` + `object MetadataProviderAdapterShapeRegistry`
- `class MetadataIdentityResolver` + `enum MetadataPrimaryProvider` + `enum MetadataDecisionReason` + `enum MetadataMediaKind` + `enum MetadataDepth` + `enum ProviderPlanRole`
- `enum ResolverType`, `enum ResolvedField`, `enum FieldOwner`, `data class FieldValue`
- `enum MetadataLocalizationFallbackRole` + `data class MetadataLocalizationRejectedCandidate` + `MetadataLocalizationFieldTrace` + `MetadataLocalizationPayloadTrace`
- `data class MetadataCandidate`, `IgnoredFieldOverwrite`, `ResolvedMetadataDocument`, `ResolverSchedule`
- `sealed class MetadataRouteFailure : RuntimeException`
- `interface AnimeIdentityIndex` (+ `AssetAnimeIdentityIndex`, `InMemoryAnimeIdentityIndex`) + `data class AnimeIdentityMapping` + `AnimeIdentityLookup` + `enum AnimeIdScheme` + `data class ParsedMetadataId` + `object MetadataIdParser`
- `class FieldResolver`
- `enum IdMappingSource` + `data class IdMapping` + `object IdMappingTtlPolicy` + `interface IdMappingStore` (+ `InMemoryIdMappingStore`, `LocalIdMappingStore`)
- `class MetadataCacheKeys`
- Composition documents: `data class GlobalMetadataDocument`, `EpisodeMetadata`, `ArtworkCandidate`, `FieldTrace`, `ProfileMetadataOverlay`, `PlaybackProgress`, `ListMembership`, `ScrobbleState`, `ProfileResolvedDisplayDocument`, `ArtworkDecision`

### `core/playback`
- `data class PlaybackOwnerContext`
- `class PlaybackSessionRegistry @Inject`

### `core/trace` — Runtime Trace Mode (27 new files)
- `enum TraceMode` + `interface TraceModeProvider` (+ `DataStoreTraceModeProvider`)
- `data class TraceSession` + `data class TraceEventEnvelope<T>` + `enum TraceEventPriority`
- `interface RuntimeTraceSink` (+ `NoopRuntimeTraceSink`, `FileRuntimeTraceSink`)
- `class JsonlTraceWriter`
- `class TraceRedactor` + `object TraceHash`
- `data class TraceBuildInfo` + `class TraceSessionManager`
- `data class RuntimeTraceContext` + `class RuntimeTraceContextElement` (`ThreadContextElement`) + `class RuntimeTraceContextRequestTaggingInterceptor` (OkHttp `Interceptor`)
- `class RuntimeTraceInterceptor` + `class RuntimeTraceEventListener`
- `class TracedTransport` + `data class TracedResponse<T>`
- `class TraceMetadataEvents` + `enum SourceSurface` + `object FirstPaintTracer`
- `interface TraceValidationRule` + `object TraceValidationRules` + `enum TraceVerdict` + `data class TraceValidationFailure` + `TraceValidationWarning` + `TraceValidationReport`
- `class RuntimeTraceValidator` + `class TraceSummaryGenerator` + `class TraceBundleExporter`
- `enum TraceCacheDecision`
- `class UnscopedNetworkPolicyGuard`

### `core/profile`
- `data class ProfileSettingsSnapshot`
- `ProfileManager` (modified) — Hilt constructor extended with `PlaybackSessionRegistry`

### `data/integration/metadata` — Provider adapters & localization (11 new files)
- `class TmdbMetadataProviderAdapter`, `class TvdbMetadataProviderAdapter`, `class KitsuMetadataProviderAdapter`
- `class MetadataSecondaryRepository` + `class RuntimeMetadataIdentityLookup`
- Internal localization resolver: `object LocalizationResolver`, `data class LocalizationPolicy`, `enum FallbackRole`, `LocalizedFieldCandidate`, `SelectedLocalizedField`, `LocalizedFieldRejection`, `LocalizedEpisodeBundle`, `LocalizedEpisodeMetadata`, `LocalizedEpisodeFieldSource`, `LocalizedPayloadFetch<T>`, `NormalizedLanguage`
- `internal object MetadataProviderTargetIds`
- `internal object TvdbEpisodeLocalization`

### `data/integration/{tmdb,tvdb,kitsu,trakt,simkl,mdblist,posters,...}` — Provider integration providers
Substantive new providers (one per provider call surface):
- `AddonCatalogIntegrationProvider`, `AddonManifestIntegrationProvider`, `AddonMetaIntegrationProvider`, `AddonStreamIntegrationProvider`, `AddonSubtitleIntegrationProvider`, `AddonStreamRequestCanceller`, `ShadowAutoplayUploadIntegrationProvider`
- `EasyDebridIntegrationProvider`, `PremiumizeIntegrationProvider`, `RealDebridAuthIntegrationProvider`, `RealDebridIntegrationProvider`, `TorBoxIntegrationProvider`
- `DirectBenchmarkReadableSourceFactoryBuilder`, `DirectDiscardBenchmarkTransport`
- `GitHubReleaseIntegrationProvider`, `GitHubAssetDownloadIntegrationProvider` (+ `GitHubAssetDownloadStream`, `GitHubAssetDownloadTransport`, `GitHubAssetDownloadTransportResult`)
- `CustomImdbRatingsIntegrationProvider` (+ `ImdbTitleSearchIntegrationRepository`, `CustomImdbRatingsTransport`, `CustomImdbTransportResult`, `CustomImdbPayload`, `object CustomImdbRatingsRequests`)
- `interface ImdbSearchRestTransport` (+ `OkHttpImdbSearchRestTransport`) + `interface ImdbSearchWebSocketTransport` (+ `OkHttpImdbSearchWebSocketTransport`)
- `KitsuIntegrationProvider`, `KitsuDiscoveryIntegrationProvider`, `KitsuAdvancedDetailPayload`
- `MDBListIntegrationProvider`
- `OmdbIntegrationProvider`
- `OpenSubtitlesHashIntegrationProvider` (+ `OpenSubtitlesHashTransport`, `PlaybackProbeTransport`)
- `PlaybackPreflightIntegrationProvider` + `PlaybackMediaSourceTransport` + `internal interface CometProxyHttpTransport` (+ `OkHttpCometProxyHttpTransport`) + `internal interface DiskSpoolHttpTransport`/`Response` (+ `OkHttpDiskSpoolHttpTransport`)
- `RpdbIntegrationProvider`, `TopPostersIntegrationProvider` (+ `PosterTransport`, `PosterTransportResult`)
- `SimklAuthIntegrationProvider`, `SimklIntegrationProvider`, `SimklDiscoveryTransport`, `SimklProgressTransport` (+ `SimklProgressTransportResult`)
- `AniSkipIntegrationProvider`, `AnimeSkipIntegrationProvider`, `ArmIntegrationProvider`, `IntroDbIntegrationProvider`
- `SubtitleSourceDownloadIntegrationProvider`, `SubtitleTranslationIntegrationProvider` (+ paired transports + result types)
- `TmdbMetadataService`, `TmdbService` (refactored TMDB call surface)
- `interface TvdbLoginGateway` + `class TvdbSettingsAuthGateway`
- `interface ProviderMetadataRouter`

### `data/repository` — Continue-watching, Trakt outbox, sync glue (20 new + 52 modified)
- `data class ContinueWatchingRecord` + `data class EpisodeContext` + `enum Source`
- New API on `TrackingProgressService` and `ContinueWatchingSnapshotService`: `observeContinueWatching(profileId)`
- `class DebridBenchmarkCollectionUploader` (internal constructor)

### `ui/screens/home`
- `fun MetaPreview.toFirstPaintHomeDisplayMetadata()` extension (Compose first-paint metadata adapter)

### `ui/screens/settings`
- `class RuntimeTraceSettingsViewModel` + `class RuntimeTraceLiveStatusViewModel`
- New row `troubleshooting_runtime_trace` in `PlaybackSettingsSections.kt`

## New DI bindings

- **`RuntimeTraceModule.kt`** (NEW Hilt module) provides:
  - `TraceBuildInfo`, `TraceSessionManager`, `RuntimeTraceSink` (file-backed via manager), `TraceModeProvider` (DataStore), `TraceRedactor`, `TraceMetadataEvents`, `UnscopedNetworkPolicyGuard`, `RuntimeTraceContextRequestTaggingInterceptor`, `RuntimeTraceInterceptor`, `RuntimeTraceEventListener.Factory`
- **`IntegrationRuntimeModule.kt`** (NEW Hilt module) provides:
  - `IntegrationPolicyRegistry`, Room `IntegrationCacheDatabase`, DAOs (`IntegrationCacheDao`, `IntegrationProviderBackoffDao`, `RailStoreDao`, `MediaIdentityDao`), `IntegrationCacheStore`, `IntegrationAuditSink` (NoOp), `IntegrationRuntime`, `IntegrationHydrationCoordinator`, `HomeRailHydrationExecutor` + `@Binds` for runtime collaborators
- **`MetadataRouterModule.kt`** + **`MetadataExecutionModule.kt`** (NEW) — wire router/executor/orchestrator/identity-resolver bindings
- **`IntegrationProviderModule.kt`** (NEW) — registers all `*IntegrationProvider` implementations into the runtime's policy registry
- **`NetworkModule.kt`** (modified) — `OkHttpClient` now wires `RuntimeTraceContextRequestTaggingInterceptor`, `RuntimeTraceInterceptor`, `RuntimeTraceEventListener.Factory`, `IntegrationNetworkPermitInterceptor`; YouTube trailer main + probe clients added; ImdbSearch REST/WebSocket transports added; Kitsu (REST + OAuth) + Imdb search Retrofit instances added
- **`TraktMutationOutboxModule.kt`** (modified) — provides `ProviderMutationOutboxCoordinator`
- **`OpenSubtitlesModule.kt`** (DELETED) — module removed alongside the deleted OpenSubtitles stack
- **`ProfileManager`** Hilt constructor — adds `PlaybackSessionRegistry` parameter

## New storage schemas

- **`TraceSettingsDataStore`** (NEW, `data/local/TraceSettingsDataStore.kt`) — single key `trace_mode` (string)
- **Trace session files** — `<context.filesDir>/traces/<sessionId>/trace-events.jsonl` (newline-delimited JSON, written via `JsonlTraceWriter`)
- **Integration cache Room schema** (NEW, `data/local/integration/`):
  - `IntegrationCacheEntity`, `IntegrationOwnerEntity`, `IntegrationProviderBackoffEntity`, `RailCacheEntity`, `RailItemEntity`, `MediaIdentityEntity`, `ExternalIdEntity` + `MediaIdentityDao`
- **Provider mutation outbox** (NEW, `data/trakt/outbox/`):
  - `ProviderMutationOutboxCoordinator`, `TraktMutationOutboxCoordinator`, `TraktMutationOutboxStore`, `TraktMutationOutboxWorker`
- Additional new DataStores under `data/local/`: `KitsuAuthDataStore`, `LayoutPreferenceDataStore`, `PlayerSettingsDataStore`, `SimklAuthDataStore`, `ThemeDataStore`, `TraktAuthDataStore`

## New gradle audit tasks

- `:app:generateRuntimeEventAuditSample` (Test task) — feeds the integration-runtime audit JSON sample
- `:app:generateIntegrationRuntimeAudit` (custom `GenerateIntegrationRuntimeAuditTask`) — depends on `generateRuntimeEventAuditSample`
- `:app:generateMetadataExecutionAudit` (Test task)
- `:app:generateProfileBoundaryAudit` (Test task)
- `:app:generateTraceValidatorAudit` (Test task) — added in commit `5e2c7cc27`, retargeted to `TraceBundleGoldenTest` in commit `11709e6a1`

## Deleted files (production)

- `app/src/main/java/com/nexio/tv/core/di/OpenSubtitlesModule.kt`
- `app/src/main/java/com/nexio/tv/data/local/OpenSubtitlesPreferences.kt`
- `app/src/main/java/com/nexio/tv/data/remote/api/OpenSubtitlesApiClient.kt` (+ `OpenSubtitlesArchiveExtractor`, `OpenSubtitlesRestApi`)
- `app/src/main/java/com/nexio/tv/data/remote/dto/OpenSubtitlesRestSubtitleDto.kt`
- `app/src/main/java/com/nexio/tv/data/remote/model/OpenSubtitlesSearchResult.kt`
- `app/src/main/java/com/nexio/tv/data/repository/OpenSubtitlesSourceImpl.kt`
- `app/src/main/java/com/nexio/tv/domain/repository/OpenSubtitlesSource.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/settings/OpenSubtitlesSettingsScreen.kt` + `OpenSubtitlesSettingsViewModel.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`, `LoadingTimeoutController.kt`
- Several auth/probe artifacts: `BurnInProtectionState.kt`, `PlayProbeCache.kt`, `ProbeProfilingDiagnostic.kt`, `SubtitleBurnInProtection.kt`, `auth/{AuthFailureCodes,AuthRecoveryInterceptor,AuthRecoveryTracker,EgressIpFingerprint,PlaybackAuthFingerprintHolder,PlaybackErrorClassifier,TransientFailureCodes}.kt`
- `app/src/main/java/com/nexio/tv/core/auth/{LocalAccountResetCoordinator,StockDeviceState}.kt`
- `app/src/main/java/com/nexio/tv/data/local/{DurableDeviceCredentialStore,SubtitleBackgroundClamp,SupabaseSessionBackupDataStore}.kt`
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/{DeviceCapabilityReportUploader,DeviceCapabilitySnapshotSerializer}.kt`
- `app/src/main/java/com/nexio/tv/ui/components/TrailerKeepScreenOnPolicy.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/account/AuthQrLaunchPolicy.kt`
- `app/src/main/java/com/nexio/tv/ui/theme/BreathingFocusRing.kt`

Plus a large set of corresponding test deletions under `app/src/test/...` (47 deleted test files in total — see `data/audit-diff-name-status.txt`).

## OpenSpec changes added

The three OpenSpec changes whose proposal/specs/tasks landed on this branch:

- `add-metadata-router`
- `add-runtime-trace-mode`
- `harden-profile-boundary-contract`

Note: `enforce-profile-boundary-scopes` exists in the working tree but its proposal/spec/tasks files were already on `main` prior to this branch (no `A` entries under `openspec/changes/enforce-profile-boundary-scopes/` in `git diff main..HEAD`). The 10 deleted entries under `openspec/changes/` correspond to other, earlier-archived branches (e.g. `add-durable-device-auth/*`, `enforce-durable-revoke-authority/*`, `harden-stock-reset-auth-teardown/*`).
