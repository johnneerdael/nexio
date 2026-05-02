## ADDED Requirements

### Requirement: Reactive Home Hydration Overlay

Modern Home SHALL render first-paint previews immediately and SHALL update individual home cards when hydrated metadata overlays arrive.

#### Scenario: First paint does not wait for hydration
- **GIVEN** a rail item has first-paint preview fields
- **WHEN** Modern Home publishes the row
- **THEN** the row is rendered from preview fields
- **AND** no MetadataRouter, ProviderPlanRunner, rating API, or metadata runtime call is required before first paint

#### Scenario: Visible hydration updates current card
- **GIVEN** a visible home card is rendered from preview fields
- **WHEN** canonical hydration resolves a hydrated home overlay for the card
- **THEN** Modern Home updates the existing card display fields in place
- **AND** row order is unchanged
- **AND** focused item identity is unchanged

#### Scenario: Cache-hit overlay updates without network
- **GIVEN** a hydrated overlay exists in local storage for a visible card
- **WHEN** Home observes overlays for current item keys
- **THEN** the card is updated without provider network

#### Scenario: Hydration failure keeps preview
- **GIVEN** a visible card is rendered from preview fields
- **WHEN** identity resolution or canonical hydration fails
- **THEN** the preview remains visible
- **AND** the item state is `FAILED_USING_PREVIEW`

#### Scenario: Late hydration ignored after profile switch
- **GIVEN** a home hydration job started for one profile generation
- **WHEN** the active profile, language, or home generation changes before the job finishes
- **THEN** the hydration result is ignored
- **AND** a `home.hydration_ignored` trace event records the reason

#### Scenario: No provider-specific home path
- **GIVEN** a home item comes from addon, Trakt, MDBList, TMDB, Kitsu, or Simkl
- **WHEN** hydration is requested
- **THEN** the request uses the shared HomeHydrationCoordinator and existing MetadataRouterFacade path
- **AND** no provider-specific home renderer or FieldResolver is used
