## ADDED Requirements

### Requirement: Scrobble heartbeats preserve hydrated provider IDs

Heartbeat scrobbles MUST use the same hydrated provider ID bundle as initial start/pause/stop scrobbles for the current playback item.

#### Scenario: Simkl heartbeat after hydrated start

- **GIVEN** playback started with hydrated IMDb, TMDb, TVDb, Simkl, and anime IDs
- **WHEN** a heartbeat start scrobble is sent
- **THEN** the outgoing item includes the hydrated ID bundle.

### Requirement: Completed scrobbles auto-remove watchlist entries

Successful completed stop scrobbles MUST request removal of the matching item from active provider watchlists using preserved source refs and strong IDs.

#### Scenario: Completed item exists in Trakt and Simkl watchlists

- **GIVEN** a unified watchlist membership is present in Trakt and Simkl
- **WHEN** a completed stop scrobble succeeds at or above the completion threshold
- **THEN** one remove mutation is enqueued for each matching provider source ref
- **AND** duplicate auto-remove requests for the same item are suppressed within the TTL window.

### Requirement: Trakt episode scrobble 404 uses ID search fallback

Trakt episode scrobble failures with HTTP 404 MUST retry once using `/search/{id_type}/{id}?type=episode` when a TMDb, TVDb, or IMDb identifier is available.

#### Scenario: Episode payload title/year fails but search finds episode ids

- **GIVEN** Trakt rejects an episode scrobble with HTTP 404
- **WHEN** Trakt search returns episode IDs
- **THEN** the scrobble is retried with the returned episode IDs
- **AND** success settles the original outbox mutation.

### Requirement: MDBList scrobble extension seam is documented

MDBList scrobble writes MUST be added through the existing tracking service and provider mutation outbox architecture, not as direct playback-time network calls.

#### Scenario: Future MDBList scrobble implementation follows the outbox seam

- **GIVEN** MDBList scrobble writes are implemented after this change
- **WHEN** playback emits start, pause, stop, or completion events
- **THEN** MDBList writes are routed through a provider-specific scrobble service and mutation adapter
- **AND** the adapter uses MDBList scrobble endpoints, hydrated IDs, profile ownership, scrobble priority, and the shared completed-watchlist auto-remove coordinator.
