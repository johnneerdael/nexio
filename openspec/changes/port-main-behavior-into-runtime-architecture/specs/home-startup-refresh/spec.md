## ADDED Requirements

### Requirement: Catalog row mutations refresh Modern Home through shared rail state

Catalog enable/disable actions and built-in rail setting changes MUST update Modern Home by publishing changed rail records through the catalog rail repository and preview-first home state.

#### Scenario: Addon catalog enable adds a Modern Home row

- **GIVEN** an addon catalog row is disabled
- **WHEN** the user enables it in the Catalogs menu
- **THEN** the catalog rail repository publishes a row membership change
- **AND** Modern Home renders the new row from first-paint previews
- **AND** visible items hydrate through the shared home hydration coordinator.

#### Scenario: Addon catalog disable removes a Modern Home row

- **GIVEN** an addon catalog row is visible on Modern Home
- **WHEN** the user disables it in the Catalogs menu
- **THEN** the catalog rail repository publishes a row removal
- **AND** Modern Home removes the row without requiring app restart
- **AND** canonical metadata cache entries are not deleted unless no owner still references them.

#### Scenario: Trakt or Simkl settings add a built-in rail

- **GIVEN** the user enables a Trakt or Simkl settings rail
- **WHEN** the settings change is saved
- **THEN** the built-in rail source publishes a new rail descriptor
- **AND** Modern Home renders first-paint preview rows from the provider payload
- **AND** hydration uses the shared stable ID bundle resolver and MetadataRouter.

### Requirement: Home hydration updates remain item-level

Hydration results from ported main behavior MUST update Modern Home through item overlays and stable item keys.

#### Scenario: Hydrated metadata updates one visible card

- **GIVEN** a visible home card is rendered from first-paint preview
- **WHEN** canonical metadata hydration succeeds
- **THEN** `HydratedHomeOverlayStore` receives the overlay
- **AND** the affected card display hash changes
- **AND** row order remains unchanged
- **AND** focused item identity remains unchanged.
