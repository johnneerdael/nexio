## ADDED Requirements
### Requirement: Conditional Screensaver Source Selection
The idle screensaver SHALL select its content source based on Trakt eligibility and home trending rail configuration.

#### Scenario: Trakt source is eligible
- **WHEN** Trakt authentication is active
- **AND** both `Trending Movies` and `Trending Shows` are enabled in the home Trakt catalog preferences
- **THEN** the screensaver uses Trakt trending content as its source instead of Cinemeta

#### Scenario: Trakt source is not eligible
- **WHEN** Trakt authentication is unavailable
- **OR** either home trending rail is disabled
- **THEN** the screensaver uses the existing Cinemeta source

### Requirement: Trakt Snapshot Reuse For Screensaver Content
The idle screensaver SHALL reuse the existing Trakt discovery snapshot rather than fetching duplicate trending data for screensaver preparation.

#### Scenario: Startup refresh has populated the Trakt snapshot
- **WHEN** the startup refresh flow completes and the Trakt discovery snapshot contains trending movie and show items
- **THEN** the screensaver builds its Trakt-backed rotation from that snapshot
- **AND** it does not trigger a second dedicated Trakt discovery fetch for the same screensaver content

#### Scenario: Trakt-backed rotation is built
- **WHEN** the screensaver uses the Trakt source
- **THEN** it includes up to 10 trending movie items and up to 10 trending show items
- **AND** the rotation can contain up to 20 total items

#### Scenario: Trakt snapshot content is unavailable at selection time
- **WHEN** the Trakt source would otherwise be eligible but the required snapshot data is unavailable
- **THEN** the screensaver falls back to the Cinemeta source
