## ADDED Requirements

### Requirement: MDBList watchlist source participates in Unified Watchlist

Nexio SHALL read MDBList watchlist rows for an authenticated MDBList profile and merge them into Unified Watchlist using the existing `UnifiedWatchlistMembershipReducer`.

#### Scenario: Item exists in Trakt and MDBList

- **GIVEN** a Trakt watchlist movie and an MDBList watchlist movie share the same TMDb or IMDb ID
- **WHEN** Unified Watchlist memberships are reduced
- **THEN** one canonical membership is emitted
- **AND** `presentIn` contains `TRAKT` and `MDBLIST`
- **AND** the UI receives a provider-neutral Library row

### Requirement: MDBList scrobble fan-out

Nexio SHALL send playback start, pause, and stop scrobble mutations to MDBList when the active profile has MDBList enabled and authenticated.

#### Scenario: Movie playback starts with MDBList authenticated

- **GIVEN** the active profile has a valid MDBList API key
- **AND** movie playback emits a start scrobble at 20 percent
- **WHEN** tracking fan-out runs
- **THEN** Nexio enqueues an MDBList scrobble mutation with path `/scrobble/start`
- **AND** the payload contains `movie.ids` using strong IDs before title/year fallback

### Requirement: MDBList watchlist auto-remove

Nexio SHALL remove a completed movie from MDBList watchlist when its unified membership includes an MDBList watchlist source reference.

#### Scenario: Completed movie exists in three watchlist providers

- **GIVEN** a movie is present in Trakt, Simkl, and MDBList watchlists
- **WHEN** completion scrobble reaches the configured completion threshold
- **THEN** Nexio removes the movie from all three watchlist providers
- **AND** duplicate remove calls are suppressed by the existing auto-remove TTL dedupe.
