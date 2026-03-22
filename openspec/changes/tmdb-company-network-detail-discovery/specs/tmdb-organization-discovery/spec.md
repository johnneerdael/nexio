## ADDED Requirements
### Requirement: TMDB organization detail discovery
The system SHALL allow TMDB-backed production companies and TV networks on detail pages to open a dedicated detail view with organization metadata and matching TMDB discovery results.

#### Scenario: Open movie company detail
- **WHEN** the user selects a production company on a movie detail page
- **THEN** the app opens the organization detail view
- **AND** it loads company metadata from TMDB
- **AND** it loads movie results filtered with that company id

#### Scenario: Open TV company detail
- **WHEN** the user selects a production company on a TV detail page
- **THEN** the app opens the organization detail view
- **AND** it loads company metadata from TMDB
- **AND** it loads TV results filtered with that company id

#### Scenario: Open TV network detail
- **WHEN** the user selects a network on a TV detail page
- **THEN** the app opens the organization detail view
- **AND** it loads network metadata from TMDB
- **AND** it loads TV results filtered with that network id
