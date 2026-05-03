## ADDED Requirements

### Requirement: Canonical detail parity uses router-selected provider identity

Detail parity fixes ported from `main` MUST hydrate through the MetadataRouter, stable ID bundle resolver, provider plan executor, provider plan runner, and FieldResolver.

#### Scenario: TMDB TV detail resolves TVDB identity before TVDB execution

- **GIVEN** a detail request starts from a TMDB TV id
- **WHEN** MetadataRouter classifies the item as a series
- **THEN** the canonical provider is TVDB
- **AND** the stable ID bundle resolver resolves a TVDB series id before the TVDB detail plan runs
- **AND** the provider plan runner does not execute TVDB detail with a raw TMDB id.

#### Scenario: TMDB movie detail keeps TMDB as primary provider

- **GIVEN** a detail request starts from a TMDB movie id
- **WHEN** MetadataRouter classifies the item as a movie
- **THEN** the canonical provider is TMDB
- **AND** TMDB core detail, cast, companies, reviews, and ratings enrichment use the TMDB target id from the stable ID bundle.

#### Scenario: Trakt series detail uses known TVDB id when present

- **GIVEN** a Trakt rail or Continue Watching item includes a TVDB id for a series
- **WHEN** MetadataRouter classifies the item as a series
- **THEN** the stable ID bundle marks the TVDB id ready from input
- **AND** no Trakt id lookup is required for scrobble or canonical metadata hydration
- **AND** TVDB remains the primary detail provider.

### Requirement: Preview fields remain fallback after canonical hydration

Main parity ports MUST NOT promote rail preview, addon preview, Trakt, MDBList, or Simkl preview fields to final primary ownership unless the router-selected primary provider supplies those fields.

#### Scenario: Canonical field replaces preview field

- **GIVEN** a home or detail item initially displays a preview title
- **WHEN** canonical metadata hydration succeeds
- **THEN** FieldResolver selects the primary provider title
- **AND** the preview title is retained only as a rejected fallback candidate
- **AND** the metadata trace records the replacement.
