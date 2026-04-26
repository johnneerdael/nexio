## ADDED Requirements

### Requirement: Metadata routing uses only canonical addon item inputs
The system SHALL use only addon item `id` and addon item `type` as routing authority.

#### Scenario: Item id and item type are canonical routing inputs
- **GIVEN** a catalog item with `id` and `type`
- **WHEN** MetadataRouter routes the item
- **THEN** the router uses item `id` as the canonical identifier
- **AND** the router uses item `type` as the authoritative media type

#### Scenario: Catalog-level fields are ignored for routing
- **GIVEN** a catalog item with addon id, catalog id, catalog type, source name, genres, anime type, links, popularity, or trend fields
- **WHEN** MetadataRouter routes the item
- **THEN** those fields are retained only for trace, rendering, harvest, or diagnostics
- **AND** none of those fields can select Kitsu, TMDB, or TVDB as the primary provider

#### Scenario: Addon metadata is render-only for first paint
- **GIVEN** a catalog item with name, poster, background, description, release info, runtime, rating, or genres
- **WHEN** the catalog row renders
- **THEN** those fields may render immediately
- **AND** those fields do not affect provider routing

### Requirement: Metadata routing uses deterministic provider precedence
The system SHALL route every metadata request to one primary metadata provider using deterministic precedence.

#### Scenario: Kitsu prefix routes directly to Kitsu
- **GIVEN** a metadata request whose normalized parent id starts with `kitsu:`
- **WHEN** MetadataRouter routes the request
- **THEN** the primary provider is Kitsu
- **AND** the target Kitsu id is the parsed `kitsu:` id
- **AND** AnimeIdentityIndex / Fribb is not consulted
- **AND** the route trace records a direct Kitsu-prefix decision reason

#### Scenario: MAL AniList and AniDB prefixes map to Kitsu
- **GIVEN** a metadata request whose normalized parent id starts with `mal:`, `anilist:`, or `anidb:`
- **AND** AnimeIdentityIndex / Fribb maps that id to a Kitsu id
- **WHEN** MetadataRouter routes the request
- **THEN** the primary provider is Kitsu
- **AND** the target Kitsu id is the mapped Kitsu id
- **AND** the route trace records an anime-prefix-mapped decision reason

#### Scenario: IMDb id mapping routes anime to Kitsu
- **GIVEN** a metadata request without an anime prefix
- **AND** the normalized parent id is an IMDb id
- **AND** IdMappingStore or AnimeIdentityIndex / Fribb resolves the normalized parent id to Kitsu
- **WHEN** MetadataRouter routes the request
- **THEN** the primary provider is Kitsu
- **AND** the positive mapping is persisted with source evidence

#### Scenario: Catalog labels do not route neutral ids to Kitsu
- **GIVEN** a metadata request with a neutral IMDb id such as `tt12343534`
- **AND** the source context includes anime words, Crunchyroll source names, anime genres, or anime catalog names
- **AND** IdMappingStore and AnimeIdentityIndex / Fribb do not map the id to Kitsu
- **WHEN** MetadataRouter routes the request
- **THEN** the source context is retained only as trace/debug context
- **AND** the router falls back by the per-item type

#### Scenario: Provider-native TMDB id agrees with movie type
- **GIVEN** a metadata request whose normalized parent id starts with `tmdb:`
- **AND** the item type is `movie`
- **WHEN** MetadataRouter routes the request
- **THEN** the primary provider is TMDB
- **AND** AnimeIdentityIndex / Fribb is not consulted

#### Scenario: Provider-native TVDB id agrees with series type
- **GIVEN** a metadata request whose normalized parent id starts with `tvdb:`
- **AND** the item type is `series`
- **WHEN** MetadataRouter routes the request
- **THEN** the primary provider is TVDB
- **AND** AnimeIdentityIndex / Fribb is not consulted

#### Scenario: Provider-native prefix conflicts with item type
- **GIVEN** a metadata request whose normalized parent id starts with `tmdb:` and item type is `series`
- **OR** a metadata request whose normalized parent id starts with `tvdb:` and item type is `movie`
- **WHEN** MetadataRouter routes the request
- **THEN** the route trace records `ROUTING_ID_TYPE_CONFLICT`
- **AND** the router does not use AnimeIdentityIndex / Fribb to guess a Kitsu route
- **AND** the fallback route keeps the original normalized parent id as the target id
- **AND** the route marks the target id as requiring downstream identity resolution before provider calls
- **AND** the router falls back by explicit item-type policy

#### Scenario: Static Fribb hit is persisted to IdMappingStore
- **GIVEN** an IMDb id is absent from IdMappingStore
- **AND** AnimeIdentityIndex / Fribb maps that IMDb id to Kitsu
- **WHEN** MetadataRouter routes the request
- **THEN** the router persists that mapping into IdMappingStore with source `FRIBB`
- **AND** later requests can resolve through IdMappingStore before consulting AnimeIdentityIndex / Fribb

#### Scenario: AnimeIdentityIndex rejects provider-native ids
- **GIVEN** a TMDB or TVDB provider-native id
- **WHEN** MetadataRouter routes the request
- **THEN** AnimeIdentityIndex is not called with that id
- **AND** provider-native routing or conflict handling decides the route

#### Scenario: Negative mappings expire
- **GIVEN** IdMappingStore records a negative mapping
- **WHEN** 30 days have elapsed
- **THEN** the negative mapping is expired
- **AND** permanent LOCAL, FRIBB, and ROUTER_OBSERVED mappings are not expired by that TTL

#### Scenario: IdMappingStore overwrite priority is deterministic
- **GIVEN** multiple mappings exist for the same source id
- **WHEN** IdMappingStore resolves that source id
- **THEN** mappings are preferred in priority order LOCAL, ROUTER_OBSERVED, FRIBB, NEGATIVE
- **AND** `IdMappingStore.persist()` does not allow a lower-priority mapping to overwrite a higher-priority mapping

#### Scenario: Item type fallback routes live-action series to TVDB
- **GIVEN** a metadata request without Kitsu prefix, mapped anime prefix, or neutral-id Kitsu mapping
- **AND** the item type is `series`
- **WHEN** MetadataRouter routes the request
- **THEN** the primary provider is TVDB

#### Scenario: Item type fallback routes live-action movie to TMDB
- **GIVEN** a metadata request without Kitsu prefix, mapped anime prefix, or neutral-id Kitsu mapping
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

#### Scenario: Provider plan waits for unresolved provider-native conflict identity
- **GIVEN** a MetadataRoute with `targetIdRequiresIdentityResolution`
- **WHEN** ProviderPlanExecutor builds the plan
- **THEN** the executor refuses to build provider API calls
- **AND** identity resolution must convert the target to a provider-native id first
- **AND** provider integration adapters or their identity helpers own that identity resolution before IntegrationRuntime execution
- **AND** MetadataRouter and ProviderPlanExecutor do not perform provider-native identity conversion

#### Scenario: Provider plan uses route media kind
- **GIVEN** a MetadataRoute whose original request content type differs from its resolved route media kind
- **WHEN** ProviderPlanExecutor builds the plan
- **THEN** the executor selects provider behavior from `route.mediaKind`
- **AND** the executor does not use the original request content type for plan selection
- **AND** downstream resolver logic also uses `route.mediaKind` for provider behavior after routing

#### Scenario: ContentType is not used for provider decisions after routing
- **GIVEN** a MetadataRoute exists
- **WHEN** downstream planning or resolver code selects provider behavior
- **THEN** it uses `route.mediaKind`
- **AND** it does not use the original request `ContentType`

#### Scenario: TVDB translation is language-gated
- **GIVEN** a MetadataRoute whose primary provider is TVDB
- **AND** the requested language is the default or base language
- **WHEN** ProviderPlanExecutor builds the plan
- **THEN** `tvdb.series.translation` is not required for identity resolution
- **AND** translation work is scheduled only when the requested language differs from the default or base language

### Requirement: Secondary resolvers run only at allowed depths
The system SHALL run secondary resolvers according to request depth.

#### Scenario: Preview does not require provider network
- **GIVEN** a metadata request at `PREVIEW` depth
- **WHEN** ResolverOrchestrator evaluates secondary work
- **THEN** addon metadata is sufficient for initial output
- **AND** secondary network resolvers are not required

#### Scenario: Initial preview render does not execute MetadataRouter
- **GIVEN** a catalog row with many addon items
- **WHEN** the row first renders
- **THEN** the UI renders addon item metadata directly
- **AND** MetadataRouter is not executed for every row item
- **AND** routing is deferred until an item becomes visible, detail opens, playback starts, or enrichment is explicitly requested

#### Scenario: MetadataRouter rejects preview requests
- **GIVEN** a metadata request at `PREVIEW` depth
- **WHEN** code calls MetadataRouter directly
- **THEN** MetadataRouter rejects the request
- **AND** callers must use the facade preview path that returns addon metadata without routing

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
- **AND** the current routing policy version is persisted

#### Scenario: Continue Watching reroutes stale policy version once
- **GIVEN** a Continue Watching entry has a stored routing policy version older than the current routing policy version
- **WHEN** Continue Watching prepares that item
- **THEN** the item may be rerouted once
- **AND** the stored route is updated with the current routing policy version

#### Scenario: Routing rule changes require version bump
- **GIVEN** routing precedence, AnimeIdentityIndex behavior, or IdMappingStore semantics change
- **WHEN** the change is implemented
- **THEN** the current routing policy version is bumped

#### Scenario: Continue Watching reuses mapped Kitsu route for anime
- **GIVEN** an anime episode with a plain IMDb id was routed to Kitsu at playback start through IdMappingStore or AnimeIdentityIndex / Fribb
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
