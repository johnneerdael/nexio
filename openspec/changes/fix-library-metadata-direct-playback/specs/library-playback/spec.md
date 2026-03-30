## ADDED Requirements

### Requirement: Library items surface hydrated Trakt metadata

The Library SHALL keep Trakt watchlist and Trakt custom lists visible while emitting hydrated
display metadata for Trakt-backed items.

#### Scenario: Watchlist entries render with hydrated artwork

- **GIVEN** the Library refreshes Trakt watchlist items with compatible Trakt, TMDB, or IMDb ids
- **WHEN** metadata is available from installed meta addons
- **THEN** the emitted Library entries include poster, background, logo, description, and other
  hydrated display metadata for those watchlist items

#### Scenario: Custom list entries render with hydrated artwork

- **GIVEN** the Library refreshes Trakt custom-list items with compatible Trakt, TMDB, or IMDb ids
- **WHEN** metadata is available from installed meta addons
- **THEN** the emitted Library entries include poster, background, logo, description, and other
  hydrated display metadata for those custom-list items

### Requirement: Debrid library direct play uses player-ready links

Debrid-backed library items SHALL expose only direct-play URLs that are ready for the internal
player to consume.

#### Scenario: Real-Debrid torrents resolve to a playable download URL

- **GIVEN** a Real-Debrid torrent is downloaded and has a matching resolved download link
- **WHEN** the debrid Library snapshot is built
- **THEN** the emitted Library entry uses that resolved download URL as its direct-play target

#### Scenario: Unresolved Real-Debrid torrents are excluded

- **GIVEN** a Real-Debrid torrent does not have a matching resolved download URL
- **WHEN** the debrid Library snapshot is built
- **THEN** the torrent is not emitted as a playable Library entry

### Requirement: Direct-library playback exits back to Library

Player sessions launched directly from Library SHALL return to Library and SHALL NOT reopen the
stream-selection route on either normal back or playback-error back.

#### Scenario: Direct library playback normal back

- **GIVEN** the user starts playback from a direct-play Library item
- **WHEN** the user presses back from the player
- **THEN** the app returns to Library
- **AND** it does not navigate to `Stream`

#### Scenario: Direct library playback error recovery

- **GIVEN** the user starts playback from a direct-play Library item
- **AND** playback fails
- **WHEN** the user chooses the player error back action
- **THEN** the app returns to Library
- **AND** it does not navigate to `Stream`
