## ADDED Requirements

### Requirement: Trakt global-content cache fetches share cache across profiles without ProfileBoundaryException

Trakt global-content endpoints (`fetchTrendingMovies`, `fetchTrendingShows`, `fetchPopularMovies`, `fetchPopularShows`, `fetchRecommendations`, `fetchCalendarShows`) MUST construct `IntegrationSpec` with `scope = IntegrationScope.GlobalContent` and `profileContext = null`. The combination MUST NOT trigger `ProfileBoundaryEnforcer` rejection at spec construction.

#### Scenario: Two authenticated profiles share trending cache without rejection

- **GIVEN** profile 1 (authenticated Trakt session) calls `fetchTrendingMovies(20)` and warms the cache
- **WHEN** profile 2 (authenticated Trakt session) calls `fetchTrendingMovies(20)`
- **THEN** spec construction does NOT throw `ProfileBoundaryException`
- **AND** the second call hits the cache (no network)
- **AND** the cache key string does NOT contain `"profile:"`

### Requirement: Home addon hydration is not allowed to call getMetaFromAllAddons() directly

Production code under `app/src/main/java/com/nexio/tv/ui/screens/home/` MUST NOT call `getMetaFromAllAddons()` directly. Addon-derived metadata for Home consumers MUST flow through `MetadataRouterFacade` (or a facade-wrapping helper). The architecture pin `AddonFirstPaintShapeArchitectureTest` enforces.

#### Scenario: Adding getMetaFromAllAddons() in a Home file trips the pin

- **WHEN** a developer writes `getMetaFromAllAddons(...)` in any file under `ui/screens/home/`
- **THEN** `AddonFirstPaintShapeArchitectureTest` fails with the offending file path

### Requirement: Scrobble-boundary check halts the write on profile mismatch

`TraktScrobbleService.checkScrobbleBoundary` and `SimklScrobbleService.checkScrobbleBoundary` MUST return `Boolean` (true = safe to proceed, false = mismatch detected). The callers (`enqueueScrobble`/`enqueueCheckin`) MUST early-return when `checkScrobbleBoundary` returns false. The mutation MUST NOT be enqueued in `traktMutationOutboxCoordinator` on mismatch.

#### Scenario: Profile mismatch blocks scrobble enqueue

- **GIVEN** an in-flight scrobble for `playbackOwnerProfileId = 1`
- **WHEN** the active profile is `2` at scrobble-completion time
- **THEN** `checkScrobbleBoundary` returns false
- **AND** `enqueueAndDrain` is NOT called
- **AND** `metadata.scrobble_rejected` event emits with reason `PROFILE_MISMATCH`

### Requirement: TrackingProgressService re-checks profile boundary at scrobble completion

`DefaultTrackingProgressService` MUST call `ProfileBoundaryEnforcer.assertCanWriteProfileState(...)` at the scrobble-completion path. A late-arriving result for `playbackOwnerProfileId = N` after a profile switch to `M` MUST NOT write to profile M's history.

#### Scenario: Late scrobble result after profile switch is rejected

- **GIVEN** a scrobble for `playbackOwnerProfileId = 1` is in-flight
- **WHEN** the user switches to profile 2 before the scrobble result returns
- **AND** the result returns
- **THEN** the result-time check throws `ProfileBoundaryException`
- **AND** profile 2's history is NOT updated

### Requirement: Successful call/open clears provider backoff

`DefaultIntegrationRuntime.doCallInternal` and `DefaultIntegrationRuntime.openInternal` MUST call `backoffManager.clear(spec.provider, spec.scope)` on the success branch. This mirrors the `executeProviderLoad` `get()` path behavior.

#### Scenario: YouTube trailer recovers immediately after backoff window opens via call()

- **GIVEN** YOUTUBE_TRAILER provider is in backoff via a prior `get()` 429
- **WHEN** a successful `call()` operation completes
- **THEN** `backoffManager.clear(YOUTUBE_TRAILER, ...)` is called
- **AND** subsequent `call()` invocations are not blocked by the stale backoff window

### Requirement: TMDB movie collection fetches go through IntegrationRuntime

`TmdbIntegrationProvider.loadMovieCollection(...)` MUST route through `runtime.get(IntegrationSpec(apiShapeId = TmdbApiShapes.COLLECTION, ...))`. Direct `loadResponse(...)` calls bypassing the runtime are forbidden for this endpoint.

#### Scenario: Movie collection fetch appears in runtime audit

- **WHEN** `loadMovieCollection("collection-id")` runs
- **THEN** `generateIntegrationRuntimeAudit` reports the endpoint shape `tmdb.collection` as runtime-covered

### Requirement: Cache deletion is crash-safe — DAO row goes before blob

`LocalIntegrationCacheStore.deleteOwnedMedia(mediaKey)` MUST delete the Room row(s) before deleting the underlying blob file(s). A process kill mid-operation MUST leave a dangling blob (which `IntegrationOrphanCleanupService` reaps) rather than a dangling DAO row pointing at a non-existent blob.

#### Scenario: Crash mid-delete leaves no DAO row pointing at a missing blob

- **GIVEN** `deleteOwnedMedia(mediaKey)` is called
- **WHEN** the process is killed between DAO delete and blob delete
- **THEN** no Room row references the missing blob
- **AND** the leftover blob is reapable by orphan-cleanup

### Requirement: TvdbLanguageMapper surfaces unsupported-locale collapse via diagnostic

`TvdbLanguageMapper.normalize(locale)` MUST surface whether the requested locale was collapsed to the English fallback because it lies outside the supported whitelist. The result type MUST include `isCollapsedToFallback: Boolean`. The localized fetch path MUST propagate this into the `metadata.localization_plan` payload as `localeCollapsedToFallback: true` when set.

#### Scenario: Italian locale collapse emits diagnostic

- **GIVEN** the user device locale is Italian (`it`)
- **WHEN** `TvdbLanguageMapper.normalize("it")` is called
- **THEN** the result has `isCollapsedToFallback = true`
- **AND** the `metadata.localization_plan` event payload includes `localeCollapsedToFallback: true`

### Requirement: Trace bundles stamp the build commit SHA

`TraceBuildInfo.gitSha` MUST be non-null in production builds. The build script MUST inject `BuildConfig.GIT_SHA` from `git rev-parse HEAD` at build time. The Hilt module providing `TraceBuildInfo` MUST consume this value (no hardcoded null).

#### Scenario: Exported trace bundle includes the commit SHA

- **GIVEN** a debug or release build
- **WHEN** a trace bundle is exported
- **THEN** the bundle's `app-build-info.json` `gitSha` field is non-null and matches the build commit SHA

### Requirement: SecondaryDoesNotOverwritePrimary validator only fires when secondary actually wins

`TraceValidationRules.SecondaryDoesNotOverwritePrimary` MUST filter `metadata.field_selected` events by `selectedSourceRole == "SECONDARY"` (or the equivalent winner-was-secondary signal) before evaluating `rejectedCandidates`. A primary-wins-with-rejected-secondary-candidates scenario MUST NOT produce a FAIL verdict.

#### Scenario: Primary wins with rejected secondaries — validator passes

- **GIVEN** a `metadata.field_selected` event where `selectedSourceRole = "PRIMARY"` and `rejectedCandidates` is non-empty
- **WHEN** `SecondaryDoesNotOverwritePrimary` evaluates
- **THEN** the verdict is PASS

### Requirement: Trace settings UI is gated on debug builds

The "Runtime & Metadata Trace" entry in `PlaybackSettingsSections` MUST be hidden when `BuildConfig.IS_DEBUG_BUILD` is false. Release-build users MUST NOT be able to enable HTTP trace capture from the settings UI.

#### Scenario: Release build hides the trace settings entry

- **WHEN** `BuildConfig.IS_DEBUG_BUILD == false`
- **THEN** the "Runtime & Metadata Trace" picker is not rendered

### Requirement: checkin() callers explicitly supply ownerProfileId

All production callers of `TrackingScrobbleService.checkin(item, message, ownerProfileId)` MUST supply a non-null `ownerProfileId`. An architecture pin scans `app/src/main/java` for calls to `checkin(` and asserts the third argument is supplied with a non-null reference.

#### Scenario: Adding checkin(item) without ownerProfileId trips the pin

- **WHEN** a developer writes `trackingScrobbleService.checkin(item)` (no `ownerProfileId`)
- **THEN** `CheckinCallerOwnerProfileIdContractTest` fails with the offending file path

### Requirement: ResolvedMetadataDocument is constructed only by FieldResolver and MetadataRouterFacade

A production-source architecture pin MUST scan `app/src/main/java` for `ResolvedMetadataDocument(` constructions and assert they appear only in `FieldResolver.kt` and `MetadataRouterFacade.kt`. Any third construction site MUST trip the pin.

#### Scenario: Adding ResolvedMetadataDocument construction in a third file trips the pin

- **WHEN** a developer writes `ResolvedMetadataDocument(...)` in any file other than the two allowlisted
- **THEN** `ResolvedMetadataDocumentConstructionContractTest` fails

## MODIFIED Requirements

### Requirement: MetadataIdentityResolver does not emit ROUTING_ID_TYPE_CONFLICT for routes originating as ITEM_TYPE_SERIES or ITEM_TYPE_MOVIE

`MetadataIdentityResolver.resolve(route)` MUST NOT append a `MetadataRouteTrace(reason = ROUTING_ID_TYPE_CONFLICT, ...)` entry to `route.trace` when `route.reason` is `ITEM_TYPE_SERIES` or `ITEM_TYPE_MOVIE`. The trace entry is only valid when the route's original reason was `ROUTING_ID_TYPE_CONFLICT` (i.e. the resolver was called specifically to break a provider-native conflict).

#### Scenario: IMDB-as-series identity resolution does not poison the route trace

- **GIVEN** a route with `parentId = "tt14403178"`, `provider = TVDB`, `reason = ITEM_TYPE_SERIES`, and `targetIdRequiresIdentityResolution = true`
- **WHEN** `MetadataIdentityResolver.resolve(route)` succeeds via `imdbToTvdb`
- **THEN** the returned route's `trace` does NOT contain a `ROUTING_ID_TYPE_CONFLICT` entry
- **AND** the audit golden test `MetadataExecutionAuditGoldenTest.routing rules match spec for all id types` PASSES
