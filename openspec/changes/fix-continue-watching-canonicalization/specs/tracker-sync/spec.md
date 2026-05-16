## ADDED Requirements

### Requirement: Continue Watching Uses Canonical Episode Coordinates

Continue Watching SHALL publish non-anime series rows with TVDB season and episode coordinates whenever TVDB identity is resolvable for the series.

#### Scenario: Provider numbering differs from TVDB
- **GIVEN** a non-anime series row is sourced from Trakt, SIMKL, MDBList, or local playback
- **AND** the row can be resolved to a TVDB series
- **AND** the provider-reported season/episode differs from TVDB ordering
- **WHEN** Continue Watching persists the row
- **THEN** the persisted row uses the TVDB season and episode coordinates
- **AND** the row label and stream-fetch identity use those same TVDB coordinates

#### Scenario: Anime remains on anime projection
- **GIVEN** a Continue Watching row is identified as anime
- **WHEN** Continue Watching projects the row
- **THEN** it uses the existing anime/Kitsu projection path
- **AND** it is not forced into TVDB coordinates solely because a TVDB ID exists

### Requirement: Continue Watching Suppresses Watched Anchors

Continue Watching SHALL suppress resume, next-up, synthetic next-up, and retained snapshot rows that match a completed or watched canonical episode coordinate at or before the watched anchor.

#### Scenario: Watched row has a different provider alias
- **GIVEN** Trakt watched history contains a completed episode for a TVDB series
- **AND** a next-up candidate for the same show arrives keyed by IMDb, TMDB, Trakt, SIMKL, MDBList, or TVDB
- **WHEN** the candidate coordinate is at or before the watched coordinate after canonicalization
- **THEN** Continue Watching does not persist the candidate

#### Scenario: Retained snapshot row becomes completed
- **GIVEN** a previous snapshot contains a next-up or resume row
- **AND** the latest provider/local progress contains a completed watched anchor for the same canonical coordinate
- **WHEN** snapshot retention considers preserving the previous row
- **THEN** the row is not retained

### Requirement: Continue Watching Requires Known Aired Evidence For Next-Up

Continue Watching main-feed next-up rows SHALL require known aired evidence and SHALL NOT treat unknown air dates as aired.

#### Scenario: Unknown air date
- **GIVEN** a next-up candidate has no TVDB availability instant
- **AND** it has no first-aired timestamp
- **AND** it has no parseable first-aired date
- **WHEN** Continue Watching builds the snapshot
- **THEN** the candidate is not persisted in the main Continue Watching feed

#### Scenario: Concrete future air date
- **GIVEN** a next-up candidate has a concrete future air timestamp
- **WHEN** Continue Watching builds the snapshot
- **THEN** the candidate is excluded from the main feed
- **AND** the candidate is scheduled for reemit using the concrete future timestamp
