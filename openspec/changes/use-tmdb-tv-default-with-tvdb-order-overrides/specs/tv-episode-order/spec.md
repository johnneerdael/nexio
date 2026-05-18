## ADDED Requirements

### Requirement: Manual TVDB episode order override

NEXIO SHALL default standard TV shows to TMDB episode order and allow a global manual override keyed by canonical TMDB TV ID to use TVDB default season numbering.

#### Scenario: Unknown show uses TMDB order

- **GIVEN** no override exists for `tmdb:tv:12345`
- **WHEN** episode order is resolved
- **THEN** the selected order is `TMDB_DEFAULT`

#### Scenario: Manual TVDB override uses TVDB order

- **GIVEN** a `TVDB_DEFAULT` override exists for `tmdb:tv:12345`
- **AND** the hydrated provider IDs include a TVDB series ID
- **WHEN** episode order is resolved
- **THEN** the selected order is `TVDB_DEFAULT`

#### Scenario: Missing TVDB sidecar falls back for the request

- **GIVEN** a `TVDB_DEFAULT` override exists for `tmdb:tv:12345`
- **AND** the hydrated provider IDs do not include a TVDB series ID
- **WHEN** episode order is resolved
- **THEN** the request falls back to `TMDB_DEFAULT`
- **AND** the stored override remains unchanged
