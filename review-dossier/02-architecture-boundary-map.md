# Architecture Boundary Map

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Phase:** 2
- **Owner task:** 9
- **Status:** COMPLETE

## Ownership questions

### Q1: Who is allowed to call `IntegrationRuntime`?

- **Allowed callers (provider adapters under `data/integration/<provider>/`):**
  - `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt:33`
  - `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuDiscoveryIntegrationProvider.kt:19`
  - `app/src/main/java/com/nexio/tv/data/integration/debrid/RealDebridAuthIntegrationProvider.kt:20`
  - `app/src/main/java/com/nexio/tv/data/integration/debrid/RealDebridIntegrationProvider.kt:27`
  - `app/src/main/java/com/nexio/tv/data/integration/debrid/PremiumizeIntegrationProvider.kt:27`
  - `app/src/main/java/com/nexio/tv/data/integration/debrid/TorBoxIntegrationProvider.kt:29`
  - `app/src/main/java/com/nexio/tv/data/integration/debrid/EasyDebridIntegrationProvider.kt:28`
  - `app/src/main/java/com/nexio/tv/data/integration/mdblist/MDBListIntegrationProvider.kt:35`
  - `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt:70`
  - `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt:52`
  - `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbLoginIntegrationProvider.kt:23`
  - `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:76`
  - `app/src/main/java/com/nexio/tv/data/integration/simkl/SimklIntegrationProvider.kt:34`
  - `app/src/main/java/com/nexio/tv/data/integration/simkl/SimklAuthIntegrationProvider.kt:25`
  - `app/src/main/java/com/nexio/tv/data/integration/omdb/OmdbIntegrationProvider.kt:33`
  - `app/src/main/java/com/nexio/tv/data/integration/imdb/CustomImdbRatingsIntegrationProvider.kt:22`
  - `app/src/main/java/com/nexio/tv/data/integration/posters/RpdbIntegrationProvider.kt:23`
  - `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt:25`
  - `app/src/main/java/com/nexio/tv/data/integration/skip/AniSkipIntegrationProvider.kt:19`
  - `app/src/main/java/com/nexio/tv/data/integration/skip/AnimeSkipIntegrationProvider.kt:22`
  - `app/src/main/java/com/nexio/tv/data/integration/skip/ArmIntegrationProvider.kt:18`
  - `app/src/main/java/com/nexio/tv/data/integration/skip/IntroDbIntegrationProvider.kt:21`
  - `app/src/main/java/com/nexio/tv/data/integration/trailer/TrailerBackendProvider.kt:18`
  - `app/src/main/java/com/nexio/tv/data/integration/youtube/YouTubeTrailerIntegrationProvider.kt:18`
  - `app/src/main/java/com/nexio/tv/data/integration/github/GitHubReleaseIntegrationProvider.kt:18`
  - `app/src/main/java/com/nexio/tv/data/integration/github/GitHubAssetDownloadIntegrationProvider.kt:22`
  - `app/src/main/java/com/nexio/tv/data/integration/playback/OpenSubtitlesHashIntegrationProvider.kt:25`
  - `app/src/main/java/com/nexio/tv/data/integration/playback/PlaybackPreflightIntegrationProvider.kt:21`
  - `app/src/main/java/com/nexio/tv/data/integration/subtitles/SubtitleTranslationIntegrationProvider.kt:30`
  - `app/src/main/java/com/nexio/tv/data/integration/subtitles/SubtitleSourceDownloadIntegrationProvider.kt:17`
  - `app/src/main/java/com/nexio/tv/data/integration/addon/AddonMetaIntegrationProvider.kt:19`
  - `app/src/main/java/com/nexio/tv/data/integration/addon/AddonCatalogIntegrationProvider.kt:19`
  - `app/src/main/java/com/nexio/tv/data/integration/addon/AddonManifestIntegrationProvider.kt:19`
  - `app/src/main/java/com/nexio/tv/data/integration/addon/AddonStreamIntegrationProvider.kt:21`
  - `app/src/main/java/com/nexio/tv/data/integration/addon/AddonSubtitleIntegrationProvider.kt:19`
  - `app/src/main/java/com/nexio/tv/data/integration/collector/ShadowAutoplayUploadIntegrationProvider.kt:16`
- **Forbidden callers seen:** none — every match outside the interface declaration, the `DefaultIntegrationRuntime` impl, and the DI module is a provider adapter under `data/integration/<provider>/`. The only non-adapter reference is the diagnostic message at `app/src/main/java/com/nexio/tv/core/integration/IntegrationNetworkPermit.kt:76`, which is the permit interceptor itself enforcing the boundary.
- **Verdict:** ✅ enforced

### Q2: Who is allowed to call provider HTTP APIs directly?

- **Allowed:** Provider adapters under `data/integration/<provider>/` (Retrofit interfaces in `data/remote/api/`, OkHttp transports under `data/integration/<provider>/transport/`).
- **Out-of-bounds matches:** none.
  - All `Retrofit.Builder()` / `provideXxxApi(...)` matches live in `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt` (DI registration site, not a runtime caller).
  - All `@GET` / `@POST` / `@PUT` / `@DELETE` annotations are on Retrofit interfaces in `app/src/main/java/com/nexio/tv/data/remote/api/*.kt` (the API definitions themselves).
  - All `okHttpClient.newCall(...).execute()` / `call.execute()` matches live under `app/src/main/java/com/nexio/tv/data/integration/<provider>/transport/*.kt` (e.g. `DirectDiscardBenchmarkTransport.kt:54`, `OkHttpYouTubeTrailerTransport.kt:26/44`, `CometProxyHttpTransport.kt:32`, `DiskSpoolHttpTransport.kt:86`, `ImdbSearchRestTransport.kt:64`, `GitHubAssetDownloadTransport.kt:19`, `SimklProgressTransport.kt:26`, `SimklDiscoveryTransport.kt:33`, `TvLoginExchangeTransport.kt:49`, `PosterTransport.kt:22`).
  - DTOs from `data.remote.api.*` are imported by `core/tmdb/*`, `core/tvdb/*`, `core/anime/*`, and `ui/screens/search/*`, but those are pure type imports (no Retrofit/OkHttp calls).
- **Verdict:** ✅ enforced

### Q3: Who creates final metadata?

- **Sole intended producer of `ResolvedMetadataDocument`:** `FieldResolver.resolve(...)` at `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt:19` (constructs the document at line `85`).
- **Orchestrator:** `MetadataRouterFacade` at `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:16`; calls `fieldResolver.resolve(...)` at `MetadataRouterFacade.kt:52`.
- **Other producers seen:**
  - `MetadataRouterFacade.toResolvedDocument()` at `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:157` constructs a `ResolvedMetadataDocument` directly (line `158`) by wrapping the `initialDisplay: HomeDisplayMetadata` baseline (called at line `38`). It bypasses `FieldResolver` and emits `fieldOwners = emptyMap()` / `ignoredOverwrites = emptyList()`.
  - `FieldResolver` is also instantiated outside DI at `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProviderLocalizedMetadataOverlay.kt:76` (`fieldResolver = FieldResolver()`) and `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:112` (`fieldResolver = FieldResolver()`). Both call sites use it through the same `resolve(...)` entry point so they remain canonical producers, but the construction-from-UI breaks the "one Hilt-bound resolver" boundary and risks divergent behavior if `FieldResolver` ever takes constructor dependencies.
- **Verdict:** ⚠️ violations seen — one secondary producer in `MetadataRouterFacade` itself (the baseline-wrapping helper), plus two UI-side `FieldResolver()` instantiations.

### Q4: Who owns profile overlays?

- **Typed model:** `ProfileMetadataOverlay` at `app/src/main/java/com/nexio/tv/core/metadata/composition/ProfileMetadataOverlay.kt:5` (fields: `profileId`, `watched`, `progress`, `listMembership`, `scrobbleState`, `userRating`, `continueWatching`).
- **Typed composition document:** `ProfileResolvedDisplayDocument` at `app/src/main/java/com/nexio/tv/core/metadata/composition/ProfileResolvedDisplayDocument.kt:3` (bundles `profileId` + `global: ResolvedMetadataDocument` + `overlay: ProfileMetadataOverlay`).
- **Resolver migration status:** DEFERRED. The typed composition documents exist but no production code constructs them. The only `ProfileMetadataOverlay` / `ProfileResolvedDisplayDocument` references are the type definitions themselves and a single test (`app/src/test/java/com/nexio/tv/core/metadata/composition/CompositionTypeShapeTest.kt:20`). The resolver still emits untyped `ResolvedMetadataDocument` (`FieldResolver.kt:85`) and per-profile state lives in scattered untyped containers:
  - `HomeUiState.posterLibraryMembership` → flows through `cwWatchlistMembership: Map<String, Boolean>` in `HomeScreen.kt:649/697/836`, `ModernHomeContent.kt:314`, `ClassicHomeContent.kt:59`, `GridHomeContent.kt:76`, `ContinueWatchingSection.kt:86`, `GridContinueWatchingSection.kt:44`.
  - `MetaDetailsViewModel.pickerMembership = snapshot.listMembership` at `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:2193`.
  - `ListMembershipSnapshot.listMembership` at `app/src/main/java/com/nexio/tv/domain/model/LibraryModels.kt:90`, populated by `app/src/main/java/com/nexio/tv/data/repository/LibraryRepositoryImpl.kt:145/149`, `TraktLibraryService.kt:158`, `SimklLibraryService.kt:148`.
  - `ContinueWatchingRecord` overlay reaches consumers via `ContinueWatchingSnapshotService` (data layer) — `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:148`.
- **Verdict:** ⚠️ violations seen — typed overlay/document exist as boundary contracts but no producer or consumer wires them; profile-bound state remains spread across untyped UI/repository structures.

### Q5: Who owns CW writes?

- **Owner:** `ContinueWatchingSnapshotService` at `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:148`. The `ContinueWatchingSnapshotStore` is injected only into this service (verified by full-codebase scan).
- **Direct `ContinueWatchingSnapshotStore.write` calls outside the service:** none.
  - `ContinueWatchingSnapshotStore` (the only CW-snapshot persistence type) is referenced exactly twice in main source: at its declaration (`app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt:24`) and at its injection into `ContinueWatchingSnapshotService` (`ContinueWatchingSnapshotService.kt:155`). All in-service writes occur at `ContinueWatchingSnapshotService.kt:324` and `:938`.
  - The other `snapshotStore.write(...)` matches in repository code target distinct stores: `MDBListDiscoverySnapshotStore`, `TraktDiscoverySnapshotStore`, `SimklDiscoverySnapshotStore`, `TraktLibrarySnapshotStore`, `SimklLibrarySnapshotStore`. These are not CW.
- **Verdict:** ✅ enforced

### Q6: Who owns scrobble writes?

- **Public interface:** `TrackingScrobbleService` at `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt:37` (methods take `owner: PlaybackOwnerContext`).
- **Default impl:** `DefaultTrackingScrobbleService` at `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt:47`; bound in `app/src/main/java/com/nexio/tv/core/di/RepositoryModule.kt:83`.
- **Caller sites pass `PlaybackOwnerContext`:**
  - `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt:325` (start) — `owner = playbackOwnerContext`
  - `PlayerRuntimeControllerPlaybackEvents.kt:347` (stop) — `owner = playbackOwnerContext`
  - `PlayerRuntimeControllerPlaybackEvents.kt:411` (heartbeat start) — `owner = playbackOwnerContext`
  - All callers go through `TrackingScrobbleService` (no UI/VM bypass into `TraktScrobbleService` / `SimklScrobbleService`).
- **Routing uses `owner.ownerProfileId`:** ✅ at `TrackingScrobbleService.kt:58/62/72/76/86/90`. No `profileManager.activeProfileId.value` reads in either `TrackingScrobbleService.kt` or `PlayerRuntimeControllerPlaybackEvents.kt`.
- **Underlying provider services** (`SimklScrobbleService.kt:77/92/107`, `TraktScrobbleService.kt:102/117/132`) accept `ownerProfileId: Int? = null` but are only called from `DefaultTrackingScrobbleService`. The default value is a hazard but is never relied on by current callers.
- **Verdict:** ✅ enforced (with a soft-spot finding for the nullable `ownerProfileId` defaults on the underlying provider services).

### Q7: Who owns localization fallback?

- **Policy type:** `LocalizationPolicy` at `app/src/main/java/com/nexio/tv/data/integration/metadata/LocalizationPolicy.kt:12` (factories: `tvdb(...)` line 35, `tmdb(...)` line 47, `kitsu(...)` line 59).
- **Field selection:** `LocalizationResolver.selectField(...)` at `app/src/main/java/com/nexio/tv/data/integration/metadata/LocalizationResolver.kt:7` (`object LocalizationResolver` declared at line 5; priority assignment at line 56).
- **Payload orchestration sites (where requested-language and English/fallback payloads are fetched):**
  - `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapter.kt:26` — builds `LocalizationPolicy.tvdb(language)` and triggers fallback fetch at line `37` (`language = policy.fallbackLanguage.providerCode`).
  - `app/src/main/java/com/nexio/tv/data/integration/metadata/TmdbMetadataProviderAdapter.kt:30` — `LocalizationPolicy.tmdb(language)`; fetches fallback episode/season payloads at lines `46/73/93`; merges via `LocalizationResolver.selectField(...)` at lines `153/161`.
  - `app/src/main/java/com/nexio/tv/data/integration/metadata/KitsuMetadataProviderAdapter.kt:30` — `LocalizationPolicy.kitsu(route.language)`.
  - `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbEpisodeLocalization.kt:52/57` and `TvdbIntegrationProvider.kt:486/491` (low-level series-translation fetch driven by `policy.fallbackLanguage`).
  - `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt:124..359` is the shared candidate-builder used by all three provider adapters (calls `LocalizationResolver.selectField` and stamps `policy.fallbackLanguage` into fallback candidates).
- **Trace seam:** `metadata.localization_plan` was deleted because no canonical orchestration site emits it. The seam where the policy meets fetch+merge orchestration is split across each provider adapter (TVDB/TMDB/Kitsu) plus `MetadataAdapterCandidates` and `TvdbEpisodeLocalization`. There is no single owner that decides "fetch fallback now / record the plan" — each adapter inlines that decision.
- **Verdict:** ⚠️ violations seen — the policy + selector are clean and centralized, but fallback orchestration is fragmented across three provider adapters with no shared trace seam, matching the audit-plan note that this remains a known soft spot.

## Findings to file in lane reviews

(For Tasks 25–34 to pick up. Each entry follows the standardized format from the plan.)

### Lane B (MetadataRouter / ProviderPlanRunner / FieldResolver)

- **B-Q3.1 — Secondary `ResolvedMetadataDocument` producer in MetadataRouterFacade.**
  - Symptom: `MetadataRouterFacade.toResolvedDocument()` constructs `ResolvedMetadataDocument` directly, bypassing `FieldResolver`.
  - Evidence: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:157-169` (constructor call at `:158`); used by `:38` to wrap `initialDisplay`.
  - Impact: Final document produced with `fieldOwners = emptyMap()` and `ignoredOverwrites = emptyList()`, indistinguishable downstream from a resolver-produced doc but missing provenance.
  - Suggested fix: Route the baseline through `FieldResolver` with a `BASELINE` provider candidate, or document the wrapper as the canonical "no candidates" path and tag its `fieldOwners` accordingly.
- **B-Q3.2 — UI-side `FieldResolver` instantiation breaks DI single-binding.**
  - Symptom: `FieldResolver()` constructed manually outside DI in two UI files.
  - Evidence: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProviderLocalizedMetadataOverlay.kt:76`, `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:112`.
  - Impact: Future changes that add constructor parameters to `FieldResolver` (e.g. trace sink, policy injection) will silently regress these call sites.
  - Suggested fix: Inject `FieldResolver` via Hilt, or refactor the helpers to accept a `FieldResolver` parameter from the surrounding ViewModel/DI graph.

### Lane C (Provider contracts)

- (none — Q2 verdict is ✅ enforced.)

### Lane E (Localization)

- **E-Q7.1 — No canonical orchestration site emits `metadata.localization_plan`.**
  - Symptom: Fallback orchestration (decide → fetch fallback payload → merge via `LocalizationResolver`) is duplicated across each provider adapter, with no shared trace seam.
  - Evidence: `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapter.kt:26-37`, `TmdbMetadataProviderAdapter.kt:30-93`, `KitsuMetadataProviderAdapter.kt:30`, `TvdbEpisodeLocalization.kt:52-187`, `MetadataAdapterCandidates.kt:124-359`. `metadata.localization_plan` returns zero matches in main source.
  - Impact: We cannot validate localization fallback behavior at the trace level; the deleted helper is unrecoverable without first naming an owner.
  - Suggested fix: Introduce a `LocalizationOrchestrator` (or extend `LocalizationPolicy` with a `plan(...)` step) that all three adapters delegate to, and re-emit `metadata.localization_plan` from that single seam.

### Lane F (Profile boundaries)

- **F-Q4.1 — `ProfileMetadataOverlay` / `ProfileResolvedDisplayDocument` are unused in production.**
  - Symptom: The typed composition contracts exist and are shape-tested, but no production code constructs or consumes them.
  - Evidence: `app/src/main/java/com/nexio/tv/core/metadata/composition/ProfileMetadataOverlay.kt:5`, `ProfileResolvedDisplayDocument.kt:3`. Only consumer is `app/src/test/java/com/nexio/tv/core/metadata/composition/CompositionTypeShapeTest.kt:20-31`.
  - Impact: Profile-bound overlay state remains scattered across untyped UI/data structures (`HomeUiState.posterLibraryMembership`, `MetaDetailsViewModel.pickerMembership`, `ListMembershipSnapshot.listMembership` at `LibraryModels.kt:90`, ContinueWatching state in `ContinueWatchingSnapshotService.kt:148`) — boundary cannot be enforced.
  - Suggested fix: Either land the resolver migration that produces `ProfileResolvedDisplayDocument` (per the harden-profile-boundary-contract OpenSpec deferral) or document a dated removal plan if the typed boundary is being abandoned.

### Lane G (Continue Watching)

- (none — Q5 verdict is ✅ enforced. `ContinueWatchingSnapshotStore` is injected only into `ContinueWatchingSnapshotService`.)

### Lane H (Playback / scrobble)

- **H-Q6.1 — Underlying provider scrobble services accept nullable `ownerProfileId` defaults.**
  - Symptom: `SimklScrobbleService.scrobble{Start,Stop,Pause}` and `TraktScrobbleService.scrobble{Start,Stop,Pause}` accept `ownerProfileId: Int? = null`. Callers today (`DefaultTrackingScrobbleService`) always pass `owner.ownerProfileId`, but the default-null hatch lets future callers regress to active-profile semantics.
  - Evidence: `app/src/main/java/com/nexio/tv/data/repository/SimklScrobbleService.kt:77/92/107`, `app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt:102/117/132`.
  - Impact: Profile-boundary contract is enforced by convention, not by type. A caller that omits the parameter would silently route by active profile.
  - Suggested fix: Drop the default value (require `ownerProfileId: Int`) or wrap in a non-nullable `PlaybackOwnerContext` parameter to match the public `TrackingScrobbleService` API.
