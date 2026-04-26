## ADDED Requirements

### Requirement: Metadata routing uses deterministic provider precedence
The system SHALL route every metadata request to one primary metadata provider using deterministic precedence.

#### Scenario: Anime prefix routes to Kitsu
- **GIVEN** a metadata request whose normalized parent id starts with `kitsu:`, `mal:`, `anilist:`, or `anidb:`
- **WHEN** MetadataRouter routes the request
- **THEN** the primary provider is Kitsu
- **AND** the route trace records an anime-prefix decision reason

#### Scenario: Catalog anime hint routes plain IMDb anime to Kitsu
- **GIVEN** a metadata request with a plain IMDb id such as `tt12343534`
- **AND** the source context identifies the item as coming from an anime or Crunchyroll-style catalog source
- **WHEN** MetadataRouter routes the request
- **THEN** the primary provider is Kitsu
- **AND** the route trace records a catalog-source anime hint decision reason

#### Scenario: Fribb or id mapping routes anime when catalog hint is absent
- **GIVEN** a metadata request without an anime prefix
- **AND** the source context does not provide an anime catalog hint
- **AND** IdMappingStore or Fribb resolves the normalized parent id to Kitsu
- **WHEN** MetadataRouter routes the request
- **THEN** the primary provider is Kitsu
- **AND** the positive mapping is persisted with source evidence

#### Scenario: Item type fallback routes live-action series to TVDB
- **GIVEN** a metadata request without anime prefix, anime catalog hint, or Kitsu id mapping
- **AND** the item type is `series`
- **WHEN** MetadataRouter routes the request
- **THEN** the primary provider is TVDB

#### Scenario: Item type fallback routes live-action movie to TMDB
- **GIVEN** a metadata request without anime prefix, anime catalog hint, or Kitsu id mapping
- **AND** the item type is `movie`
- **WHEN** MetadataRouter routes the request
- **THEN** the primary provider is TMDB

### Requirement: Metadata routing normalizes episode ids to parent ids
The system SHALL normalize episode ids to parent ids before route decisions and cache lookups.

#### Scenario: IMDb episode id normalizes to parent IMDb id
- **GIVEN** a metadata request for `tt12343534:1:1`
- **WHEN** MetadataRouter normalizes the request
- **THEN** the parent id is `tt12343534`

#### Scenario: Kitsu episode id normalizes to parent Kitsu id
- **GIVEN** a metadata request for `kitsu:7442:1:1`
- **WHEN** MetadataRouter normalizes the request
- **THEN** the parent id is `kitsu:7442`

#### Scenario: Provider title id remains unchanged
- **GIVEN** a metadata request for `tmdb:550`
- **WHEN** MetadataRouter normalizes the request
- **THEN** the parent id is `tmdb:550`

### Requirement: Catalog item type is the live-action media-kind source of truth
The system SHALL use per-item type, not catalog manifest type, for live-action fallback routing and row rendering.

#### Scenario: Mixed Disney catalog row routes movie item to TMDB
- **GIVEN** a catalog row whose manifest type is `series`
- **AND** an item in the row has type `movie`
- **AND** no anime routing signal applies
- **WHEN** MetadataRouter routes the item
- **THEN** the primary provider is TMDB

#### Scenario: Mixed Disney catalog row routes series item to TVDB
- **GIVEN** a catalog row whose manifest type is `series`
- **AND** an item in the row has type `series`
- **AND** no anime routing signal applies
- **WHEN** MetadataRouter routes the item
- **THEN** the primary provider is TVDB

### Requirement: Provider plan execution maps primary routes to runtime-covered API shapes
The system SHALL execute primary metadata plans only through IntegrationRuntime-covered provider shapes.

#### Scenario: TMDB movie detail core uses runtime-covered shape
- **GIVEN** a MetadataRoute whose primary provider is TMDB
- **AND** the requested depth is `DETAIL_CORE`
- **WHEN** ProviderPlanExecutor builds the plan
- **THEN** the plan references `tmdb.movie.core`
- **AND** the referenced shape is active and runtime-covered by the IntegrationRuntime audit

#### Scenario: TVDB series detail core uses runtime-covered shape
- **GIVEN** a MetadataRoute whose primary provider is TVDB
- **AND** the requested depth is `DETAIL_CORE`
- **WHEN** ProviderPlanExecutor builds the plan
- **THEN** the plan references `tvdb.series.extended`
- **AND** the referenced shape is active and runtime-covered by the IntegrationRuntime audit

#### Scenario: Kitsu anime detail core uses runtime-covered shape
- **GIVEN** a MetadataRoute whose primary provider is Kitsu
- **AND** the requested depth is `DETAIL_CORE`
- **WHEN** ProviderPlanExecutor builds the plan
- **THEN** the plan references `kitsu.anime.core`
- **AND** the referenced shape is active and runtime-covered by the IntegrationRuntime audit

### Requirement: Secondary resolvers run only at allowed depths
The system SHALL run secondary resolvers according to request depth.

#### Scenario: Preview does not require provider network
- **GIVEN** a metadata request at `PREVIEW` depth
- **WHEN** ResolverOrchestrator evaluates secondary work
- **THEN** addon metadata is sufficient for initial output
- **AND** secondary network resolvers are not required

#### Scenario: Skip resolver does not run during detail core
- **GIVEN** a metadata request at `DETAIL_CORE` depth
- **WHEN** ResolverOrchestrator evaluates secondary work
- **THEN** SkipSegmentResolver is not scheduled

#### Scenario: Player depth schedules player-only resolvers
- **GIVEN** a metadata request at `PLAYER` depth
- **WHEN** ResolverOrchestrator evaluates secondary work
- **THEN** TrackingResolver and SkipSegmentResolver may be scheduled
- **AND** broad metadata prefetch is not scheduled

### Requirement: Field resolution enforces provider ownership
The system SHALL create final user-visible metadata only through FieldResolver ownership rules.

#### Scenario: Secondary rating cannot overwrite primary title
- **GIVEN** a primary metadata candidate with a title
- **AND** a rating resolver candidate also contains a title-like field
- **WHEN** FieldResolver resolves the document
- **THEN** the primary title is preserved
- **AND** the secondary overwrite attempt is traceable as ignored

#### Scenario: Artwork provider cannot change identity
- **GIVEN** a primary metadata candidate with canonical identity
- **AND** an artwork resolver candidate with a poster
- **WHEN** FieldResolver resolves the document
- **THEN** the canonical identity remains owned by the primary provider
- **AND** only artwork decision fields may use the artwork candidate

### Requirement: Artwork decisions are cached separately from primary metadata
The system SHALL cache artwork decisions separately from provider metadata and image bytes.

#### Scenario: Premium artwork setting change invalidates artwork decision
- **GIVEN** primary metadata for a title is fresh
- **AND** an artwork decision exists for the current artwork policy
- **WHEN** the user changes premium artwork provider, API key, style, badge, thumbnail, language, region, or artwork priority settings
- **THEN** the artwork decision cache misses or invalidates
- **AND** the resolved display document cache misses or invalidates
- **AND** the primary provider metadata cache remains valid unless primary metadata policy changed

### Requirement: Continue Watching preserves route and click-time addon metadata
The system SHALL persist route and click-time addon display metadata at playback start for Continue Watching rendering.

#### Scenario: Playback start persists route context
- **GIVEN** playback starts from a catalog or detail item
- **WHEN** the watch progress entry is created or updated
- **THEN** the entry records the normalized parent id
- **AND** the entry records the selected primary provider route
- **AND** click-time addon HomeDisplayMetadata is persisted for fallback rendering

#### Scenario: Continue Watching reuses Kitsu route for Crunchyroll anime
- **GIVEN** a Crunchyroll-style anime episode with a plain IMDb id was routed to Kitsu at playback start
- **WHEN** Continue Watching renders that item later
- **THEN** the stored Kitsu route is reused
- **AND** the item is not re-routed to TVDB from the partial episode id

#### Scenario: Continue Watching offline render uses click-time metadata
- **GIVEN** click-time addon HomeDisplayMetadata was persisted at playback start
- **AND** canonical refresh is unavailable
- **WHEN** Continue Watching renders the item
- **THEN** title, poster, and description fall back to the click-time addon metadata before older persisted fallback data

### Requirement: MetadataRouter migration removes TvMetadataRouter production dependency
The system SHALL migrate production callers to the new MetadataRouter stack and retire `TvMetadataRouter`.

#### Scenario: Production callers no longer import TvMetadataRouter
- **GIVEN** the MetadataRouter migration is complete
- **WHEN** architecture tests scan production source
- **THEN** no production caller imports or injects `TvMetadataRouter`

#### Scenario: Router and resolver layers do not bypass IntegrationRuntime
- **GIVEN** the MetadataRouter migration is complete
- **WHEN** architecture tests scan router and resolver source
- **THEN** those layers do not inject Retrofit APIs, provider auth services, OkHttp clients, or raw provider APIs
- **AND** provider network access remains below IntegrationRuntime-owned provider adapters
