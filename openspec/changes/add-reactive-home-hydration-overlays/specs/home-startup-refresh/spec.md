## ADDED Requirements

### Requirement: Reactive Home Hydration Overlay

Modern Home SHALL render first-paint previews immediately when preview rows are available and SHALL update individual home cards when hydrated metadata overlays arrive.

#### Scenario: First paint does not wait for hydration
- **GIVEN** a rail item has first-paint preview fields
- **WHEN** Modern Home publishes the row
- **THEN** the row is rendered from preview fields
- **AND** per-item canonical metadata, rating, MetadataRouter, ProviderPlanRunner, or metadata runtime hydration is not required before first paint

#### Scenario: Visible and adjacent hydration update current cards
- **GIVEN** visible and adjacent home cards are rendered from preview fields
- **WHEN** canonical hydration resolves a hydrated home overlay for the card
- **THEN** Modern Home updates the existing card display fields in place
- **AND** row order is unchanged
- **AND** focused item identity is unchanged

#### Scenario: Adjacent hydration uses same overlay path
- **GIVEN** a home card is within the adjacent +/-2 hydration window around focused content
- **WHEN** adjacent hydration is requested
- **THEN** the request uses the shared HomeHydrationCoordinator
- **AND** the hydrated result is persisted as a hydrated home overlay
- **AND** the card is updated through overlay observation without a provider-specific path

#### Scenario: Cache-hit overlay updates without network
- **GIVEN** a hydrated overlay exists in local storage for a visible card
- **WHEN** Home observes overlays for current item keys
- **THEN** the card is updated without provider network

#### Scenario: Overlay lookup is scoped and aliased
- **GIVEN** hydrated overlays exist for canonical identity `C1` in language `L1` and policy scope `P1`
- **AND** item keys `K1` and `K2` are aliases for canonical identity `C1`
- **WHEN** Home observes overlays for item key `K2` in language `L1` and policy scope `P1`
- **THEN** the overlay for `C1` is applied to `K2`
- **AND** overlays for a different language or policy scope are ignored

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
- **AND** no provider-specific home renderer, provider-specific FieldResolver, or provider-specific field merge path is used
- **AND** the shared FieldResolver remains part of the canonical hydration path

## MODIFIED Requirements

### Requirement: Serialized Post-Startup Refresh Pipeline

When disk-first mode is enabled, the system SHALL process catalog refresh in a serialized pipeline and SHALL gate UI publish for refreshed catalog snapshots until snapshot metadata/image readiness rules are satisfied. This gate SHALL NOT block first-paint preview publish on per-item canonical metadata or rating hydration; durable hydrated overlays SHALL update existing cards after first paint.

#### Scenario: Serialized catalog refresh
- **GIVEN** multiple catalogs are due for refresh
- **WHEN** post-startup refresh starts
- **THEN** catalogs are processed sequentially by a single coordinator worker

#### Scenario: Publish gated by hydration completion
- **GIVEN** a catalog refresh result contains changed/new items
- **WHEN** metadata and image hydration required for refreshed catalog snapshot readiness is still in progress for that catalog
- **THEN** the refreshed catalog is not published to Home UI
- **AND** publish occurs only after hydration completes or configured fallback timeout policy is reached

#### Scenario: First-paint preview publish is not gated by overlay hydration
- **GIVEN** disk-first mode has preview rows available for initial Home publish
- **WHEN** per-item canonical metadata, rating, or durable overlay hydration is still in progress
- **THEN** Modern Home SHALL publish the preview rows without waiting for that per-item hydration
- **AND** durable overlay hydration updates the existing cards after first paint
- **AND** this does not bypass catalog snapshot availability, metadata cache language, or image readiness rules required before initial row availability
