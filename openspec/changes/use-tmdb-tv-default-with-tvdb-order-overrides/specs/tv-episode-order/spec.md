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

#### Scenario: Continue Watching keeps TMDB identity by default

- **GIVEN** a standard TV Continue Watching record has both TMDB and TVDB provider IDs
- **AND** no TVDB episode order override exists for its canonical TMDB TV ID
- **WHEN** Continue Watching identity and next-up projection are rebuilt
- **THEN** the record canonical identity uses the TMDB TV ID
- **AND** TVDB coordinate projection is not applied

#### Scenario: Continue Watching projects coordinates only for manual TVDB order

- **GIVEN** a standard TV Continue Watching record has both TMDB and TVDB provider IDs
- **AND** a `TVDB_DEFAULT` override exists for its canonical TMDB TV ID
- **WHEN** Continue Watching next-up projection is rebuilt
- **THEN** the record canonical identity still uses the TMDB TV ID
- **AND** its season and episode coordinates use TVDB default order

#### Scenario: Stream and detail consumers use selected episode order

- **GIVEN** a hydrated standard TV show has a canonical TMDB TV ID
- **WHEN** detail episode lists or stream-fetch identities are built
- **THEN** they use TMDB episode order when no override exists
- **AND** they use TVDB default episode order when a `TVDB_DEFAULT` override exists and a TVDB sidecar ID is available
