## ADDED Requirements

### Requirement: Rail items render from source payload before canonical hydration
Every catalog rail item SHALL produce a rail preview payload before metadata routing or provider-plan execution.

#### Scenario: Built-in rail first paint does not route
- **GIVEN** a built-in Trakt, MDBList, TMDB, Kitsu, or Simkl rail response item contains display fields
- **WHEN** Home first paint renders the rail item
- **THEN** Home uses the rail preview display fields
- **AND** MetadataRouter is not executed
- **AND** ProviderPlanRunner is not executed
- **AND** metadata runtime calls are not executed

#### Scenario: Sparse rail payload still renders
- **GIVEN** a built-in rail response item contains only title, year, and stable identifiers
- **WHEN** Home first paint renders the rail item
- **THEN** Home shows title and year with an artwork placeholder
- **AND** Home does not block on canonical metadata hydration

### Requirement: Built-in rail payload fields use rail preview source role
Provider list payload fields from built-in API rails SHALL be represented as `SourceRole.RAIL_PREVIEW` unless the selected canonical route primary provider is the same provider and canonical detail has succeeded.

#### Scenario: Rail preview is replaced by primary canonical metadata
- **GIVEN** a rail preview supplied title, poster, and overview
- **AND** visible-item hydration later returns primary canonical fields
- **WHEN** FieldResolver resolves the final display document
- **THEN** primary-owned canonical fields replace rail preview fields
- **AND** rejected rail preview candidates are traced with the reason `primary canonical field available`

#### Scenario: Rail preview remains after hydration failure
- **GIVEN** a rail preview supplied title and poster
- **AND** visible-item canonical hydration fails
- **WHEN** Home resolves the rail item display document
- **THEN** the rail preview fields remain visible
- **AND** the rail item exposes `HYDRATION_FAILED_USING_PREVIEW`

### Requirement: Rail preview storage is separate from canonical metadata storage
The system SHALL persist rail records, rail item membership, rail item preview records, and media identity records as separate ownership roots.

#### Scenario: Removing one rail keeps shared media metadata
- **GIVEN** the same media identity appears in two rails
- **WHEN** one rail is removed or expires
- **THEN** the removed rail membership and preview records may be deleted
- **AND** the shared media identity and canonical metadata remain available while another rail still owns the identity

### Requirement: Built-in rail identity facts are harvested without inferred chaining
Rail preview mappers SHALL harvest only stable identifier facts explicitly present in the provider response.

#### Scenario: Explicit direct IDs are stored
- **GIVEN** a Trakt or Simkl rail item contains IMDb, TMDB, TVDB, and provider-native IDs
- **WHEN** the item maps to `RailItemPreview`
- **THEN** direct identity facts between the provider-native ID and each supplied external ID are persisted
- **AND** direct cross-ID facts present in the same payload are persisted
- **AND** IDs not present in the source payload are not invented

### Requirement: Rail previews must extend the existing first-paint lifecycle
Built-in rail preview data SHALL enter Home through the same first-paint UI model and hydration request path used by addon previews. Provider-specific rail code SHALL be limited to page fetch, preview mapping, and identity harvesting.

#### Scenario: Rail preview uses existing Home renderer
- **GIVEN** a built-in Trakt, MDBList, TMDB, Kitsu, or Simkl rail item has been mapped to a source preview
- **WHEN** Home renders the row
- **THEN** the rendered item is a shared Home preview item
- **AND** the Home renderer does not import provider rail DTOs or rail preview mappers
- **AND** the first-paint trace source is `RAIL_PREVIEW`
- **AND** MetadataRouter and ProviderPlanRunner are not executed during first paint

#### Scenario: Rail preview uses existing visible hydration path
- **GIVEN** a rail-derived Home preview becomes visible, focused, adjacent, hero, or stale active
- **WHEN** hydration is scheduled
- **THEN** the same Home preview hydration entrypoint used by addon previews is used
- **AND** provider-specific hydration schedulers are not used
- **AND** MetadataRouter receives the preview content id, item type, source role, source provider, and stable IDs through `MetadataSourceContext`

### Requirement: Built-in rail providers enter the shared first-paint lifecycle
Trakt, MDBList, TMDB, Kitsu, and Simkl home rails MUST map provider payload items through provider-specific `RailPreviewMapper` implementations into `RailItemPreview`, then into the same shared `MetaPreview` first-paint model used by addon catalog previews.

#### Scenario: Built-in provider mapper feeds shared home renderer
- **WHEN** a built-in rail page response is available
- **THEN** each item is mapped by its provider-specific mapper
- **AND** the resulting item is converted through `RailItemPreview.toMetaPreview()`
- **AND** the Home renderer receives only shared preview/card models
- **AND** no provider-specific renderer, hydration scheduler, router, or field merge path is used
