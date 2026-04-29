# Architecture Boundary Map — integration-runtime-phase-a

> Ownership facts as of SHA `774a540f8`. One section per contract question.
> Sources verified by source scan and architecture pin tests.

---

## 1. Who is allowed to call `IntegrationRuntime`?

**Contract owner:** `IntegrationRuntime` (`core/integration/IntegrationRuntime.kt:3`) — interface with three methods: `get`, `call`, `open`.

**Legitimate callers — all inside `data/integration/<provider>/`:**

| Provider package | Holder |
|---|---|
| `addon` | `AddonCatalogIntegrationProvider`, `AddonManifestIntegrationProvider`, `AddonMetaIntegrationProvider`, `AddonStreamIntegrationProvider`, `AddonSubtitleIntegrationProvider` |
| `collector` | `ShadowAutoplayUploadIntegrationProvider` |
| `debrid` | `EasyDebridIntegrationProvider`, `PremiumizeIntegrationProvider`, `RealDebridIntegrationProvider`, `RealDebridAuthIntegrationProvider`, `TorBoxIntegrationProvider` |
| `github` | `GitHubAssetDownloadIntegrationProvider`, `GitHubReleaseIntegrationProvider` |
| `imdb` | `CustomImdbRatingsIntegrationProvider` |
| `kitsu` | `KitsuDiscoveryIntegrationProvider`, `KitsuIntegrationProvider` |
| `mdblist` | `MDBListIntegrationProvider` |
| `omdb` | `OmdbIntegrationProvider` |
| `playback` | `OpenSubtitlesHashIntegrationProvider`, `PlaybackPreflightIntegrationProvider` |
| `posters` | `RpdbIntegrationProvider`, `TopPostersIntegrationProvider` |
| `simkl` | `SimklAuthIntegrationProvider`, `SimklIntegrationProvider` |
| `skip` | `AniSkipIntegrationProvider`, `AnimeSkipIntegrationProvider`, `ArmIntegrationProvider`, `IntroDbIntegrationProvider` |
| `subtitles` | `SubtitleSourceDownloadIntegrationProvider`, `SubtitleTranslationIntegrationProvider` |
| `tmdb` | `TmdbIntegrationProvider`, `TmdbExternalIdLookupProvider`, `TmdbOrganizationProvider` |
| `trailer` | `TrailerBackendProvider`, `TrailerTmdbProvider` |
| `trakt` | `TraktIntegrationProvider` |
| `tvdb` | `TvdbIntegrationProvider`, `TvdbLoginIntegrationProvider` |
| `youtube` | `YouTubeTrailerIntegrationProvider` |

**Also allowed per test allowlist:** `core/anime`, `core/tmdb`, `core/tvdb` packages (legacy services that call into providers — these are being phased out behind adapter wrappers).

**Confirmed: no UI, ViewModel, or Worker caller.**
Source scan of all `IntegrationRuntime` references in `app/src/main/java/` shows only `core/di/IntegrationRuntimeModule.kt` (binding) and `data/integration/**` files. Zero hits in `ui/`, `workers/`, or `data/repository/`.

**Enforcement:** `NoIntegrationRuntimeInjectionOutsideBoundaryTest` (`architecture/NoIntegrationRuntimeInjectionOutsideBoundaryTest.kt`) — enforces that only `com.nexio.tv.core.anime`, `data.integration`, `core.integration`, `core.di`, `core.tmdb`, `core.tvdb` may reference `IntegrationRuntime`.

**Gap:** The allowlist includes `core.tmdb` and `core.tvdb` — these are legacy metadata service packages that still exist as wrappers. As long as they remain, the boundary is enforced only in direction (no UI→runtime), not in legacy-service→runtime depth. Flagged for lane analysis.

---

## 2. Who is allowed to call provider APIs (Retrofit interfaces / OkHttp directly)?

**Contract owner:** Transport sub-packages under `data/integration/<provider>/transport/`. Each provider's transport classes are the only permitted direct Retrofit/OkHttp callers.

**Enforcement files:**
- `IntegrationBoundaryTest` (`architecture/IntegrationBoundaryTest.kt:7`) — scans all production files for Retrofit API simple names; allows only `core/di/`, `core/tvdb/TvdbAuthService.kt`, `data/integration/`, `data/remote/api/`, and three legacy auth-service carve-outs (`KitsuAuthService`, `RealDebridAuthService`, `SimklAuthService`).
- `NoDirectOkHttpOutsideRuntimeTransportPackagesTest` — blocks `OkHttpClient`/`Retrofit` references outside approved transport packages.
- `NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest` — full-tree scan.

**Confirmed:** No Retrofit API references found in `ui/`, `workers/`, `data/repository/` (excluding the three legacy auth carve-outs). `MetadataRouterBoundaryTest` additionally confirms the `core/metadata/router/` package itself does not inject any Retrofit API or `OkHttpClient`.

**Gap:** Three legacy auth-service files (`KitsuAuthService`, `RealDebridAuthService`, `SimklAuthService`) remain carve-outs in the test allowlist — they still use Retrofit directly. These are not behind integration-provider transports. Flagged for lane analysis.

---

## 3. Who creates final `ResolvedMetadataDocument` / `MetadataResolutionResult`?

**Contract owner:** `MetadataRouterFacade` (`core/metadata/router/MetadataRouterFacade.kt`) — the only site that constructs `MetadataResolutionResult`. `FieldResolver` (`core/metadata/router/FieldResolver.kt:250`) constructs `ResolvedMetadataDocument` as its output, but is only ever called *from* `MetadataRouterFacade`.

**Evidence:**
- `ResolvedMetadataDocument(...)` constructed at: `FieldResolver.kt:250` (internal build) and `MetadataRouterFacade.kt:69` (empty-document fast-path for PREVIEW depth with no preview candidate). Both are inside `core/metadata/router/`.
- `MetadataResolutionResult(...)` constructed at: `MetadataRouterFacade.kt:82` (PREVIEW path) and `MetadataRouterFacade.kt:145` (full resolve path). No other construction sites in production source.
- `MetadataModels.kt:148` defines the `data class` — never instantiated directly by callers.

**Enforcement:** `MetadataRouterBoundaryTest` verifies `MetadataRouterFacade.kt` exists and declares the class; `MetadataProductionBoundaryTest` verifies entrypoint files reference `MetadataRouterFacade` and not legacy services. No dedicated test pins `ResolvedMetadataDocument` constructor to `FieldResolver` only.

**Gap:** No architecture pin test enforces "only `FieldResolver`/`MetadataRouterFacade` may `ResolvedMetadataDocument(...)`". The constraint is currently proven by source scan only, not by a compile-time or static-analysis gate. Flagged for lane analysis.

---

## 4. Who owns profile overlays?

**Context:** `F-F-03` deleted a previous profile-overlay mechanism. The only remaining "overlay" reference is `ProfilePinOverlay.kt` (`ui/screens/profile/ProfilePinOverlay.kt`) — a Compose PIN-entry UI overlay for locked profiles. This is UI-layer chrome, not a data-layer overlay.

**Profile boundary ownership** has been consolidated into `ProfileBoundaryEnforcer` (`core/integration/ProfileBoundaryEnforcer.kt`):
- `validateRequest(provider, scope, cacheKey, profileContext)` — called from `IntegrationSpec.init`, `IntegrationCallSpec.init`, `IntegrationStreamSpec.init`. Enforces per-request profile scope.
- `assertCanSwitchProfile(currentId, targetId, isIdle)` — called from `ProfileManager.setActiveProfile` (`core/profile/ProfileManager.kt:138`).
- `assertCanWriteProfileState(callerProfileId, activeProfileId)` — called from `ContinueWatchingSnapshotService` write paths.

**Profile switch deferral** is owned by `ProfileSwitchDeferralPolicy` (`core/profile/ProfileSwitchDeferralPolicy.kt:14`), created inside `ProfileManager` and consulted before switching.

**Conclusion:** No data-layer profile overlay logic remains. Overlay is purely a PIN-entry UI element. Cross-cutting profile boundary enforcement is owned by `ProfileBoundaryEnforcer`. Trace events for boundary checks emit `profile.boundary_check` events observable via `RuntimeTraceSink`.

---

## 5. Who owns `ContinueWatching` writes?

**Contract owner:** `ContinueWatchingSnapshotService` (`data/repository/ContinueWatchingSnapshotService.kt:149`). All write paths (insert, remove, reinsert, mark-episodes, rollback) are methods on this service. Room is written only through its internal DAO access.

**Callers (read / observe only from UI):**
- `HomeViewModel` (`ui/screens/home/HomeViewModel.kt:110`) — holds service reference, calls observe methods.
- `HomeViewModelContinueWatching` (`ui/screens/home/HomeViewModelContinueWatching.kt`) — calls `observeSnapshot()`, `applyEpisodesMarked`, `rollbackEpisodes`, `removeShowOptimistically`; passes `EpisodeRef` lists. Does not call Room DAOs directly.
- `HomeRailHydrationExecutor` (`ui/screens/home/HomeRailHydrationExecutor.kt:42`) — holds reference, reads snapshot.
- `MetaDetailsViewModel` (`ui/screens/detail/MetaDetailsViewModel.kt:186`) — calls `recordMetadataSnapshot`, `removeResumeEntry`.
- `AndroidTvFeedCatalogService` and `AndroidTvChannelPublisher` — read-only observe.
- `ContinueWatchingAirAlarmReceiver` — reads snapshot for alarm scheduling.

**Confirmed:** No UI caller accesses Room DAOs or constructs `ContinueWatchingRecord` directly. All write-path entry points go through `ContinueWatchingSnapshotService` methods.

**Profile boundary enforcement:** `ProfileBoundaryEnforcer.assertCanWriteProfileState` is called inside `ContinueWatchingSnapshotService` write paths (`ContinueWatchingSnapshotService.kt:1066`). The static trace-sink slot is installed by `RuntimeTraceModule` (side-effect in `provideRuntimeTraceSink`).

**Enforcement:** `MetadataProductionBoundaryTest` — `continue_watching_write` ownership path asserts `MetadataRouterFacade` symbol present in `ContinueWatchingSnapshotService.kt`; `continue_watching_render` asserts both `MetadataRouterFacade` and `ContinueWatchingSnapshotService` in `HomeViewModelContinueWatching.kt`.

---

## 6. Who owns `scrobble` / `checkin` calls?

**Contract owner:** `TrackingScrobbleService` interface (`data/repository/TrackingScrobbleService.kt:37`). Methods: `scrobbleStart`, `scrobbleStop`, `scrobblePause` (all require `PlaybackOwnerContext`); `checkin` (optional `ownerProfileId`); `observeWatchingNowState`.

**Callers — all go through the service interface:**

| Caller | Methods used |
|---|---|
| `PlayerRuntimeControllerPlaybackEvents` | `scrobbleStart`, `scrobbleStop` (via `PlayerRuntimeController.trackingScrobbleService`) |
| `HomeViewModelContinueWatching` | `checkin` |
| `MetaDetailsViewModel` | `checkin` |

**Implementation path:** `TrackingScrobbleService` impl delegates to `TraktScrobbleMutationAdapter` (outbox pattern → `TraktIntegrationProvider.scrobble`) and `SimklScrobbleService` (via `SimklTrackingRemoteDataSource` → `SimklIntegrationProvider.scrobble*`). Neither the Trakt nor Simkl Retrofit API is called outside `data/integration/`.

**Confirmed:** No production site calls `TraktScrobbleMutationAdapter` or `SimklScrobbleMutationAdapter` directly from UI or ViewModel. The outbox pattern (`ProviderMutationOutboxCoordinator`) is the only other path to scrobble RPCs.

**Enforcement:** `TrackingScrobbleServiceCheckinShapeTest` and `TrackingScrobbleServicePlaybackOwnerTest` (`data/repository/`) pin the owner-context shape. `SimklScrobbleServiceProfileBoundaryTest` and `TraktScrobbleServiceProfileBoundaryTest` enforce profile isolation.

**Gap:** `checkin` callers in `HomeViewModelContinueWatching` and `MetaDetailsViewModel` pass `ownerProfileId: null` (default). The service implementation resolves the effective provider state from context, but the absence of an explicit `PlaybackOwnerContext` for checkin (vs scrobble-start/stop) means profile-scope on checkin is validated only at the service-impl level, not structurally. No architecture pin test currently enforces that `checkin` callers supply a non-null `ownerProfileId`. Flagged for lane analysis.

---

## 7. Who owns localization fallback?

**Contract owner:** `LocalizationPolicy` (`data/integration/metadata/LocalizationPolicy.kt:12`), `internal data class`, with factory methods `LocalizationPolicy.tvdb(language)`, `.tmdb(language)`, `.kitsu(language)`.

**Entry points:**

| Provider | How LocalizationPolicy is used |
|---|---|
| `TvdbIntegrationProvider` | Calls `LocalizationPolicy.tvdb(requestedLanguage)` at `TvdbIntegrationProvider.kt:486`; passes to `TvdbEpisodeLocalization` for per-episode translation fallback |
| `TmdbIntegrationProvider` | References `LocalizationPolicy.CURRENT_VERSION` on cache-key construction (`TmdbIntegrationProvider.kt:311, 347, 383`) |
| `TvdbEpisodeLocalization` | Top-level function accepting `LocalizationPolicy` (`TvdbEpisodeLocalization.kt:12,26`) — delegates field selection to `LocalizationResolver.selectField` |
| `LocalizationResolver` | `object` (`data/integration/metadata/LocalizationResolver.kt`) — stateless field picker that applies the policy's language chain and `allowProviderFallbackForMissingLocalizedFields` flag |

**Policy constants:**
- `CURRENT_VERSION = 2` — cache key fragment; bump invalidates all cached translations for that provider.
- `DEFAULT_PER_EPISODE_TRANSLATION_FALLBACK_CAP = 8` — limits per-request episode fallback fetches (TVDB only).
- `fallbackLanguageEmbeddedInResponse = true` for Kitsu (F-E-04) — avoids a second fetch for the fallback language since Kitsu embeds it in the first response.

**Enforcement:** `LocalizationPolicy` is `internal` to its package — it cannot be constructed by providers in other packages without import. The localization path is tested by: architecture test `FieldResolverInjectionContractTest`, and directly by `TvdbEpisodeLocalization`-related tests. There is no dedicated architecture pin that forbids other packages from creating `LocalizationPolicy` instances directly (though `internal` visibility provides compile-time protection within the module boundary).

**Gap:** Localization fallback is currently applied at the *provider adapter* level (`TvdbIntegrationProvider`, `TmdbIntegrationProvider`) rather than at a single canonical layer. TMDB only uses `LocalizationPolicy` for cache-key versioning — its actual language-fallback logic is not yet routed through `LocalizationResolver`. No architecture test enforces that all providers must construct their policy via the factory (vs ad-hoc language selection). Flagged for lane analysis.
