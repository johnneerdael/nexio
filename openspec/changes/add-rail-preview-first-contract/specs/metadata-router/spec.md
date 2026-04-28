## ADDED Requirements

### Requirement: Rail items render from source payload before canonical hydration
Every catalog rail item SHALL produce a rail preview payload that source/storage records can retain until Home/catalog boundary adaptation.

#### Scenario: Built-in rail first paint uses retained source preview data
- **GIVEN** a built-in Trakt, MDBList, TMDB, Kitsu, or Simkl rail response item contains display fields
- **WHEN** Home first paint renders the rail item
- **THEN** the published source/storage record still contains the rail preview display fields
- **AND** the Home/catalog boundary adapter uses those retained source fields for first-paint preview data

#### Scenario: Sparse rail payload still renders
- **GIVEN** a built-in rail response item contains only title, year, and stable identifiers
- **WHEN** Home first paint renders the rail item
- **THEN** Home shows title and year with an artwork placeholder
- **AND** the source/storage record remains publishable without canonical metadata fields

### Requirement: Built-in rail payload fields use rail preview source role
Provider list payload fields from built-in API rails SHALL be retained in source/storage records as `SourceRole.RAIL_PREVIEW` data.

#### Scenario: Rail preview source role is retained in storage
- **GIVEN** a rail preview supplied title, poster, and overview
- **WHEN** the provider payload is stored as a rail preview source record
- **THEN** the stored title, poster, and overview retain `SourceRole.RAIL_PREVIEW`
- **AND** the source/storage record preserves provider and payload provenance

#### Scenario: Rail preview storage preserves fallback display fields
- **GIVEN** a rail preview supplied title and poster
- **WHEN** the provider payload is stored as a rail preview source record
- **THEN** the stored source record retains the title and poster
- **AND** the Home/catalog boundary can adapt the retained source fields into first-paint preview data

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
Built-in rail preview source/storage data SHALL remain provider-owned until the Home/catalog boundary adapts it into the existing shared first-paint UI model. Provider-specific rail code SHALL be limited to page fetch, preview mapping, identity harvesting, and source/storage persistence.

#### Scenario: Rail preview uses existing Home renderer
- **GIVEN** a built-in Trakt, MDBList, TMDB, Kitsu, or Simkl rail item has been mapped to a source preview
- **WHEN** Home renders the row
- **THEN** the rendered item is a shared Home preview item
- **AND** the Home renderer does not import provider rail DTOs or rail preview mappers
- **AND** the first-paint trace source is `RAIL_PREVIEW`

#### Scenario: Rail preview source records stop at the Home boundary
- **GIVEN** a built-in rail source/storage record contains source role, source provider, and stable IDs
- **WHEN** the repository publishes a `CatalogRow` to Home
- **THEN** the Home/catalog boundary adapts the source/storage record into the existing shared first-paint UI model
- **AND** Home renderer code does not depend on provider-specific source/storage record types

### Requirement: Built-in rail storage supports the shared first-paint lifecycle
Source/storage proof MUST rely on the shared first-paint lifecycle requirement defined for built-in rail providers, and this spec MUST add only source/storage retention requirements.

#### Scenario: Storage proof does not introduce provider-specific lifecycle paths
- **WHEN** a built-in rail page response is available
- **THEN** source/storage proof MUST rely on the shared first-paint lifecycle requirement
- **AND** no provider-specific renderer, hydration scheduler, router, or field merge path is introduced here

### Requirement: Built-in rail snapshots MUST retain source preview records

Built-in rail services MUST persist rail source payload previews as `RailItemPreview` or source row records containing `RailItemPreview` values until the Home/catalog boundary converts them into the shared first-paint UI model.

#### Scenario: Discovery snapshot stores rail preview source data
- **GIVEN** a built-in rail provider mapper returns `RailItemPreview` values
- **WHEN** Trakt, MDBList, TMDB, Kitsu, or Simkl discovery state is cached
- **THEN** the cached snapshot MUST retain `sourcePayloadHash`
- **AND** the cached snapshot MUST retain `sourcePayloadQuality`
- **AND** the cached snapshot MUST retain `ranking`
- **AND** the cached snapshot MUST retain `hydrationState`
- **AND** the cached snapshot MUST NOT collapse the provider source payload into a `MetaPreview` list before row publication

#### Scenario: Home boundary adapts source records into first-paint previews
- **GIVEN** a cached built-in rail snapshot contains rail preview source records
- **WHEN** the repository publishes a `CatalogRow` to Home
- **THEN** each rail preview source record MUST convert through the shared Home/catalog boundary adapter into the existing first-paint preview model
- **AND** the resulting `MetaPreview` MUST preserve `FirstPaintSource.RAIL_PREVIEW`
- **AND** Home card rendering MUST not import provider DTOs or provider-specific rail records
