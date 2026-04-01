## ADDED Requirements
### Requirement: Shared Preview Ratings Metadata
The system SHALL expose Rotten Tomatoes ratings in shared preview metadata so discovery surfaces can render IMDb and Rotten Tomatoes consistently without per-surface rating fetch logic.

#### Scenario: Preview metadata is enriched with Rotten Tomatoes
- **WHEN** preview metadata is prepared for a movie or series item and MDBList ratings are available
- **THEN** the preview metadata includes the Rotten Tomatoes score alongside existing preview fields
- **AND** the enrichment uses the same MDBList repository flow and settings gating as the detail screen

#### Scenario: Rotten Tomatoes is unavailable
- **WHEN** preview metadata is prepared and MDBList is disabled, not configured, or returns no Rotten Tomatoes rating
- **THEN** preview metadata remains usable without a Rotten Tomatoes score
- **AND** consumers continue rendering available metadata without failure

### Requirement: Idle Screensaver Prompt And Ratings Presentation
The idle screensaver SHALL minimize static text exposure and display only the requested preview metadata fields.

#### Scenario: A new screensaver slide becomes visible
- **WHEN** the screensaver first shows a slide or advances to another slide
- **THEN** the `Press OK for details` prompt is fully visible for 5 seconds
- **AND** the prompt fades out over 1.5 seconds after that interval

#### Scenario: Screensaver metadata is rendered
- **WHEN** the idle screensaver renders a visible slide
- **THEN** it shows genre, IMDb, and Rotten Tomatoes metadata when available
- **AND** it does not show the description, year, or runtime text for that slide

### Requirement: Modern Home Hero Ratings Presentation
The modern home hero SHALL render Rotten Tomatoes beside IMDb while preserving existing year and description content.

#### Scenario: Modern home hero has both ratings
- **WHEN** the focused modern home item has both IMDb and Rotten Tomatoes scores
- **THEN** the hero metadata row shows year, IMDb, and Rotten Tomatoes
- **AND** the hero description remains visible using the existing layout treatment

#### Scenario: Modern home hero has only IMDb
- **WHEN** the focused modern home item lacks Rotten Tomatoes but has IMDb
- **THEN** the hero metadata row still renders IMDb using the existing presentation
- **AND** the absence of Rotten Tomatoes does not remove the year or description
