## ADDED Requirements

### Requirement: PREVIEW resolution carries field provenance

The PREVIEW branch in `MetadataRouterFacade.resolveRequest` MUST route through `FieldResolver.resolveWithPreview(preview = previewCandidate, primary = null, secondary = emptyList())` so the resulting `ResolvedMetadataDocument.fieldOwners` is non-empty (each preview-derived field is owned by `FieldOwner.PREVIEW`).

#### Scenario: PREVIEW response has non-empty fieldOwners

- **GIVEN** a PREVIEW request with `sourceContext.addonMetadata` populated (title, overview, poster)
- **WHEN** `resolveRequest(request)` returns
- **THEN** `result.resolvedDocument.fieldOwners` contains `ResolvedField.TITLE -> FieldOwner.PREVIEW` (and matching entries for the other non-null preview fields)

### Requirement: FieldResolver trace events carry the request contentId

`FieldResolver.resolve(...)` and `FieldResolver.resolveWithPreview(...)` MUST accept a `requestContentId: String? = null` parameter. When non-null, `requestContentId` MUST be used as the `contentId` field in emitted `metadata.field_selected` events. When null, the existing fallback (`primary?.provider?.name ?: preview?.provider?.name ?: "UNKNOWN"`) applies.

#### Scenario: field_selected event carries real contentId

- **GIVEN** `MetadataRouterFacade.resolveRequest(request)` is called with `request.contentId = "tmdb:550"`
- **WHEN** `FieldResolver.resolveWithPreview(...)` emits `metadata.field_selected`
- **THEN** the event payload `contentId = "tmdb:550"` (not `"TMDB"`)

### Requirement: Failed identity lookups are negative-cached

`MetadataIdentityResolver.resolve(route)` MUST persist an `IdMapping` with `source = IdMappingSource.NEGATIVE` when `lookupResult == null`. On entry, the resolver MUST first check `idMappingStore` for an existing NEGATIVE mapping and return early (without network call) if one exists and is unexpired (per `IdMappingTtlPolicy.NEGATIVE_TTL_MS`).

#### Scenario: Repeat failed lookup short-circuits via negative cache

- **GIVEN** a previous `resolve(route)` for `parentId = "tmdb:nonexistent"` returned no mapping AND wrote `IdMapping(... source = NEGATIVE)`
- **WHEN** a second `resolve(route)` is called within 30 days for the same `parentId`
- **THEN** no network call is made
- **AND** the resolver returns the original (unresolved) route

### Requirement: TV ContentType normalization emits trace warning

When `MetadataRequestNormalizer.toMetadataMediaKind()` coerces `ContentType.TV` → `MediaKind.SERIES`, the normalizer MUST emit a `metadata.normalizer_warning` trace event with `payload.reason = "TV_TYPE_COERCED_TO_SERIES"` and `payload.contentId = request.contentId`. Other `ContentType` mappings remain silent.

#### Scenario: TV request emits warning

- **GIVEN** an incoming `MetadataRequest(contentType = ContentType.TV)`
- **WHEN** the normalizer maps to `MediaKind.SERIES`
- **THEN** a `metadata.normalizer_warning` event is emitted with the documented reason

### Requirement: Production code does not directly construct FieldResolver or empty ProviderPlanRunner

Production code MUST NOT directly construct `FieldResolver()` (no-arg) or `ProviderPlanRunner(emptySet())`. A production-source architecture pin scans `app/src/main/java` for these constructions. Hilt is the sole construction path.

#### Scenario: Adding FieldResolver() in production trips the test

- **WHEN** a developer writes `FieldResolver()` (no-arg) in any `app/src/main/java/**/*.kt` file
- **THEN** `FieldResolverInjectionContractTest` fails

### Requirement: apiShapeId arguments are property references, not literals

The `apiShapeId = ...` named argument in `IntegrationSpec(...)` and `IntegrationCallSpec(...)` constructions in production code MUST be a property reference matching `<Object>ApiShapes.<CONSTANT>` — string literals are forbidden. A production-source architecture pin scans `app/src/main/java` for `apiShapeId = "..."` literals.

#### Scenario: Adding apiShapeId = "trakt.foo" trips the test

- **WHEN** a developer writes `apiShapeId = "trakt.foo"` in any production file
- **THEN** `IntegrationApiShapeRegistryCoverageTest` fails with the offending file path

### Requirement: ID prefix parser handles anime prefixes

`MetadataProviderTargetIds` MUST expose `mal(raw): String?`, `anilist(raw): String?`, `anidb(raw): String?`, and `imdb(raw): String?` parser functions. Each accepts the same `<prefix>:<value>` format as the existing `tmdbInt`/`tvdbInt`/`kitsu` helpers and returns the value portion for matching prefixes (case-insensitive), or `null` for non-matching prefixes.

#### Scenario: mal:31964 parses to "31964"

- **GIVEN** raw `"mal:31964"`
- **WHEN** `MetadataProviderTargetIds.mal(raw)` is called
- **THEN** the result is `"31964"`

#### Scenario: tmdb:550 does not match mal()

- **GIVEN** raw `"tmdb:550"`
- **WHEN** `MetadataProviderTargetIds.mal(raw)` is called
- **THEN** the result is `null`

### Requirement: Premium poster providers register as metadata adapters

The `MetadataPrimaryProvider` enum MUST contain `RPDB` and `TOP_POSTERS` entries. `RpdbMetadataProviderAdapter` and `TopPostersMetadataProviderAdapter` MUST be registered (via Hilt `@IntoMap`) under those provider keys. Each adapter produces a `MetadataCandidate` with `fields[ResolvedField.POSTER]` set so `FieldResolver` can merge premium artwork via the canonical ownership path.

#### Scenario: RPDB-resolved poster goes through FieldResolver

- **GIVEN** RPDB adapter resolves a poster URL for `tmdb:550`
- **WHEN** `FieldResolver.resolve(primary = tmdbCandidate, secondary = listOf(rpdbCandidate))` runs
- **THEN** `metadata.field_selected` is emitted for `POSTER` with `selectedProvider = "RPDB"` (or "TMDB" depending on policy)

### Requirement: Premium poster cache keys are stable across JVM runs

`PosterRatingsUrlResolver` MUST NOT use `apiKey.hashCode()` in cache keys. Use a deterministic hash (SHA-256, truncated to 8 hex chars) so cache keys for the same `apiKey` are identical across process restarts.

#### Scenario: Same apiKey produces same cache key across instantiations

- **WHEN** two separate `PosterRatingsUrlResolver` invocations build a cache key for the same (provider, id, apiKey) tuple
- **THEN** the resulting cache keys are byte-equal

### Requirement: Trakt global-content cache keys are not profile-scoped

Trakt endpoints serving global content (trending/popular/recommended/calendar) MUST use a `globalContentCacheKey(logicalKey)` that does NOT include a `profile:N:` prefix. Two profiles fetching the same global rail MUST hit the same cache entry. User-scoped endpoints (watched/history/playback/scrobble) continue to use `accountCacheKey(...)` with profile + credential prefixes.

#### Scenario: Trending movies shares cache between profiles

- **GIVEN** profile 1 calls `fetchTrendingMovies(limit = 20)` and warms the cache
- **WHEN** profile 2 calls `fetchTrendingMovies(limit = 20)`
- **THEN** the second call hits the cache (no network call)
- **AND** the cache key string does NOT contain `"profile:"`

## REMOVED Requirements

### Requirement: PREVIEW resolution returns a synthetic ResolvedMetadataDocument with empty fieldOwners

**Reason:** Empty `fieldOwners` defeats validators relying on the field-ownership map. The PREVIEW carve-out is now routed through `FieldResolver.resolveWithPreview(preview, primary = null, secondary = emptyList())`, which produces proper `FieldOwner.PREVIEW` entries.

**Migration:** None required — the new code path is a strict upgrade of the same surface.

### Requirement: ViewModel-side fallback constructors for MetadataRouterFacade

**Reason:** The fallback paths produce non-functional facades (no Hilt-injected adapters, no trace sink) and silently swallow exceptions. Better to fail fast on Hilt misconfiguration than serve broken state.

**Migration:** Required `metadataRouterFacade` to be `@Inject`ed. Remove any direct `FieldResolver()` / `ProviderPlanRunner(emptySet())` construction; the architecture pin will fail loud if these patterns reappear.
