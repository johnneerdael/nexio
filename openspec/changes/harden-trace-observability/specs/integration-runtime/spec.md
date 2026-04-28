## ADDED Requirements

### Requirement: First-paint emission fires at the canonical Home presentation boundary

The `metadata.first_paint` event MUST be emitted by `MetaPreview.toFirstPaintHomeDisplayMetadata()` when invoked from `buildCatalogItem` (or its presentation-pipeline equivalent), NOT from a router pre-flight site. Other call sites MUST use the pure `MetaPreview.toHomeDisplayMetadata()` extension.

#### Scenario: Home tile render emits exactly one first_paint per content-id per render

- **WHEN** `buildCatalogItem(item)` runs for a single Home tile
- **THEN** the trace contains exactly one `metadata.first_paint` event with `routerExecuted = false` and `networkExecuted = false`
- **AND** `fetchProviderEnrichmentForPreview` (the router pre-flight path) emits zero `metadata.first_paint` events

### Requirement: ProfileManager.setActiveProfile rejection emits profile.boundary_check trace event

When `ProfileManager.setActiveProfile(targetProfileId)` rejects a switch because playback is active, it MUST route the check through `ProfileBoundaryEnforcer.assertCanSwitchProfile(...)` which emits a `profile.boundary_check` event with `verdict = "FAIL"` and `violation = "PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK"` BEFORE throwing `ProfileBoundaryException`.

#### Scenario: Switch attempt during playback emits FAIL boundary_check before throwing

- **GIVEN** a `PlaybackSessionRegistry` with an active owner
- **WHEN** `ProfileManager.setActiveProfile(otherProfileId)` is called
- **THEN** the trace contains exactly one `profile.boundary_check` event with `verdict = "FAIL"` and `violation = "PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK"`
- **AND** the call throws `ProfileBoundaryException` AFTER the trace event fires

### Requirement: snapshot_write.recordCount includes traktUpNextItems

`ContinueWatchingSnapshotService` MUST compute `recordCount = resumeItems.size + nextUpItems.size + traktUpNextItems.size` at every emission site.

#### Scenario: Trakt-driven rail snapshot reports correct rendered count

- **GIVEN** a snapshot with `resumeItems.size = 3`, `nextUpItems.size = 2`, `traktUpNextItems.size = 5`
- **WHEN** `snapshot_write` event fires
- **THEN** `recordCount = 10` (was `5` before this change)

### Requirement: Production CW callers use observeContinueWatching(profileId), not observeSnapshot

Production code outside `ContinueWatchingSnapshotService` itself MUST consume CW state via `observeContinueWatching(activeProfileId)`. Direct `observeSnapshot()` calls from VMs / feed services are forbidden.

#### Scenario: HomeViewModelContinueWatching consumes profile-scoped flow

- **WHEN** `HomeViewModelContinueWatching` initializes
- **THEN** it subscribes to `continueWatchingSnapshotService.observeContinueWatching(activeProfileId)` (NOT `observeSnapshot()`)

## MODIFIED Requirements

### Requirement: TraceRedactor redaction set covers all auth surfaces in production

`TraceRedactor.redactedHeaders` MUST include: `authorization`, `cookie`, `set-cookie`, `x-api-key`, `x-auth-token`, `x-mdblist-apikey`, `simkl-api-key`, `trakt-api-key`, `simkl-client-id`, `x-tvdb-apikey`. `redactedJsonKeys` MUST include all OAuth POST body keys: `code`, `client_id`, plus the existing set.

#### Scenario: Trace bundle has no plaintext provider auth tokens

- **GIVEN** a trace event whose payload includes `simkl-api-key: <secret>` in headers OR `code: <oauth-code>` in a JSON body
- **WHEN** `TraceRedactor.redact(envelope)` runs
- **THEN** the value at that key is `<redacted>`

### Requirement: RuntimeTraceInterceptor only emits HTTP body samples in INCLUDE_HTTP_BODIES_INTERNAL_ONLY mode AND on internal builds

A regression test MUST exist that drives each `TraceMode × isInternalBuild` combination and asserts `trace.body_sample` is emitted ONLY when `mode = INCLUDE_HTTP_BODIES_INTERNAL_ONLY` AND `isInternalBuild = true`.

#### Scenario: Body sampling gated by mode + build flavour

- **WHEN** the trace mode is `INCLUDE_HTTP_SUMMARY` (NOT `INCLUDE_HTTP_BODIES_INTERNAL_ONLY`)
- **OR** `isInternalBuild = false`
- **THEN** zero `trace.body_sample` events fire for any HTTP request

### Requirement: All derived OkHttp clients carry the trace interceptor

`NetworkModule` derives several `OkHttpClient` instances via `baseClient.newBuilder()`. A regression test MUST assert each derived client's interceptor list contains `RuntimeTraceInterceptor`.

#### Scenario: Trakt-derived OkHttpClient carries the trace interceptor

- **WHEN** `NetworkModule.provideTraktOkHttpClient(...)` builds the Trakt-flavoured client
- **THEN** the resulting `OkHttpClient.interceptors` (or `networkInterceptors`) list contains a `RuntimeTraceInterceptor` instance
