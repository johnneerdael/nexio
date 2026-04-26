## ADDED Requirements

### Requirement: Metadata routing uses only item identity and item type
The system SHALL use only addon item `id` and addon item `type` as routing authority.

#### Scenario: Catalog metadata is ignored for routing
- **WHEN** an addon catalog row contains source, catalog, genre, or addon labels
- **THEN** MetadataRouter does not use those labels to select a provider
- **AND** the route decision is based on the item id and item type

#### Scenario: Preview rendering does not route
- **WHEN** metadata is requested for preview rendering
- **THEN** MetadataRouter rejects preview-depth routing
- **AND** the facade returns addon metadata without provider plan execution

### Requirement: Anime identity routing is deterministic
MetadataRouter SHALL route explicit anime identifiers and deterministic anime mappings to Kitsu without catalog heuristics.

#### Scenario: Kitsu identifiers route directly
- **WHEN** an item id has the `kitsu:` prefix
- **THEN** the route provider is Kitsu
- **AND** no AnimeIdentityIndex lookup is required

#### Scenario: MAL AniList and AniDB identifiers map to Kitsu
- **WHEN** an item id has `mal:`, `anilist:`, or `anidb:` prefix
- **THEN** MetadataRouter resolves the identifier through IdMappingStore or AnimeIdentityIndex
- **AND** a successful mapping routes to Kitsu with anime media kind

#### Scenario: IMDb anime detection uses deterministic mapping
- **WHEN** an item id is an IMDb id
- **THEN** MetadataRouter checks IdMappingStore before AnimeIdentityIndex
- **AND** a successful anime mapping routes to Kitsu
- **AND** no mapping falls back by item type

### Requirement: Provider-native IDs route only when type agrees
MetadataRouter SHALL treat TMDB and TVDB identifiers as provider-native IDs rather than anime mapping inputs.

#### Scenario: Provider-native prefix agrees with item type
- **WHEN** an item id has `tmdb:` and the item type is movie
- **THEN** the route provider is TMDB
- **WHEN** an item id has `tvdb:` and the item type is series
- **THEN** the route provider is TVDB

#### Scenario: Provider-native prefix conflicts with item type
- **WHEN** an item id has `tmdb:` with series type or `tvdb:` with movie type
- **THEN** MetadataRouter records a routing id/type conflict trace
- **AND** the route uses the normalized parent id and marks target identity resolution as required

### Requirement: Provider plan execution waits for resolved identities
ProviderPlanExecutor SHALL not execute provider calls with unresolved provider-native mismatch ids.

#### Scenario: Route requires identity resolution
- **WHEN** a route has `targetIdRequiresIdentityResolution`
- **THEN** ProviderPlanExecutor refuses to build executable provider calls
- **AND** MetadataRouterFacade returns the route with no executable provider plan
- **AND** identity resolution remains owned by provider integration adapters or dedicated identity helpers

### Requirement: Downstream metadata behavior uses route media kind
After routing, downstream provider selection and plan execution SHALL use `route.mediaKind` rather than the original addon content type.

#### Scenario: IMDb anime maps to Kitsu
- **WHEN** an IMDb item with series type maps to a Kitsu identity
- **THEN** the route media kind is anime
- **AND** downstream execution uses Kitsu anime plans rather than TVDB series plans

### Requirement: Metadata fields have explicit ownership
Resolved metadata SHALL preserve primary provider ownership and allow secondary providers only to supplement configured fields.

#### Scenario: Primary fields replace addon fields
- **WHEN** a primary provider returns canonical title, overview, or artwork fields
- **THEN** those fields replace addon display fields

#### Scenario: Secondary fields supplement only
- **WHEN** a secondary resolver returns ratings, artwork decisions, or enrichment fields
- **THEN** those fields supplement the resolved document without overwriting primary-owned fields

### Requirement: Continue Watching stores route context
Continue Watching SHALL persist enough route context and click-time metadata to restore metadata deterministically after app restart.

#### Scenario: Continue Watching entry is persisted
- **WHEN** playback or Continue Watching metadata is stored
- **THEN** the snapshot includes parent id, provider, click-time display metadata, and routing version
- **AND** changed routing version can trigger a reroute

### Requirement: MetadataRouter readiness is proven by audit
MetadataRouter prerequisite provider shapes SHALL be represented by active IntegrationRuntime coverage before the router is considered ready.

#### Scenario: Active required shapes are covered
- **WHEN** the IntegrationRuntime audit is generated
- **THEN** `metadata-router-readiness.csv` contains no `ACTIVE_REQUIRED_MISSING` rows
- **AND** remaining warnings are planned inventory, exemptions, or non-router scope rows
