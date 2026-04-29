## ADDED Requirements

### Requirement: Trakt fetchPopularLists is treated as global content

`TraktIntegrationProvider.fetchPopularLists(...)` MUST construct its `IntegrationSpec` with `cacheKey = globalContentCacheKey(...)`, `scope = IntegrationScope.GlobalContent`, and `profileContext = null` — the same shape as the other 6 global-content fetches (closed in cluster G F2-D-01). Two profiles MUST share the cache.

#### Scenario: Two profiles share popular-lists cache

- **GIVEN** profile 1 calls `fetchPopularLists()` and warms the cache
- **WHEN** profile 2 calls `fetchPopularLists()`
- **THEN** the second call hits the cache (no network)
- **AND** the cache key contains `"global:provider:TRAKT:"` (no profile prefix)

### Requirement: ProfileBoundaryEnforcer emits profile.boundary_check trace event on FAIL

`assertCanWriteProfileState(...)` and sister methods MUST emit `profile.boundary_check` trace events with `verdict = "FAIL"` (and the relevant rejection details) when they throw `ProfileBoundaryException`. Existing PASS-side telemetry behavior is preserved.

#### Scenario: Boundary rejection records trace event

- **GIVEN** an `assertCanWriteProfileState` call where `resultProfileId != activeProfileId`
- **WHEN** the call throws `ProfileBoundaryException`
- **THEN** a `profile.boundary_check` trace event is emitted with `verdict = "FAIL"` and the violation reason

### Requirement: Validator rules exist for normalizer_warning and scrobble_rejected events

`TraceValidationRules` MUST include rules consuming `metadata.normalizer_warning` and `playback.scrobble_rejected` events. The rules confirm event payload schema (required fields present) and may flag suspicious frequencies (e.g., scrobble_rejected exceeds expected baseline).

#### Scenario: normalizer_warning payload shape rule rejects malformed events

- **GIVEN** a `metadata.normalizer_warning` event missing the `reason` field
- **WHEN** the validator rule evaluates the event stream
- **THEN** the verdict is FAIL with the missing field flagged

### Requirement: JsonlTraceWriter surfaces IOException to a callback

`JsonlTraceWriter.append(...)` MUST NOT silently swallow `IOException`. The writer MUST either expose a counter (`droppedEventCount`), surface to a callback, or emit a sentinel telemetry event so storage-full conditions are observable to support engineers.

#### Scenario: Storage-full append increments dropped counter

- **GIVEN** a `JsonlTraceWriter` whose underlying file system throws `IOException` on write
- **WHEN** `append(event)` is called
- **THEN** `droppedEventCount` is incremented by 1
- **AND** the exception is not propagated (best-effort writer)

### Requirement: Architecture pin scans Home addon hydration callers outside ui/screens/home/

A pin MUST scan all production files (or all consumers of addon-hydration helpers) for direct addon-detail calls. Cluster G F2-J-01 closed the Home-specific case; this requirement extends to ensure other consumers don't reintroduce the bypass.

#### Scenario: Adding getMetaFromAllAddons() outside the allowlist trips the pin

- **WHEN** a developer writes `getMetaFromAllAddons(...)` in any production file outside the facade-routing allowlist
- **THEN** `HomeAddonHydrationFacadeBypassPinTest` fails with the offending file path

## REMOVED Requirements

### Requirement: MetadataCacheKeys helper class exists

**Reason:** All 5 methods have zero production callers (F2-D-04 — partial closure of F-D-04 only addressed `localized`).

**Migration:** Delete the class entirely. No callers to migrate.

### Requirement: GlobalMetadataDocument data type exists

**Reason:** Unreferenced post the F-F-03 deletion sweep — survived as latent dead code.

**Migration:** Delete; no callers.

### Requirement: FieldOwner.SKIP_SEGMENTS enum value exists

**Reason:** F-12-01 removed the resolver schedule's SKIP_SEGMENTS step; the enum constant became inert namespace litter.

**Migration:** Delete the enum value and the corresponding `FieldResolver.kt:286` reference.
