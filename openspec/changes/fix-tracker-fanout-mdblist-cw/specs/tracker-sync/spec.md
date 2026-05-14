## ADDED Requirements

### Requirement: Tracker Progress Reads Use Active Providers

Tracker progress reads SHALL include every authenticated provider in `EffectiveTrackingProviderState.activeProviders` that supports progress reads.

#### Scenario: MDBList-only progress
- **GIVEN** MDBList is authenticated and Trakt/SIMKL are not authenticated
- **WHEN** tracker progress is observed
- **THEN** MDBList playback rows are emitted as `WatchProgress`

#### Scenario: Three-provider progress
- **GIVEN** Trakt, SIMKL, and MDBList are authenticated
- **WHEN** tracker progress is observed
- **THEN** rows from all three providers are emitted for downstream Continue Watching dedupe

### Requirement: Tracker Mutations Fan Out Best-Effort

Manual watched/progress/history mutations SHALL enqueue one provider mutation for every authenticated supported provider and SHALL NOT use `effectiveProvider` as write-routing authority.

#### Scenario: One provider fails
- **GIVEN** Trakt, SIMKL, and MDBList are authenticated
- **AND** the Trakt mutation cannot be built or enqueued
- **WHEN** the user marks an episode watched
- **THEN** SIMKL and MDBList mutations are still attempted

### Requirement: Season Actions Use Batch Fan-Out

Season watched actions SHALL select only eligible unwatched aired episodes and SHALL fan out batch watched mutations to every authenticated supported tracker.

#### Scenario: Previous seasons watched
- **GIVEN** a show has unwatched aired episodes in seasons before the selected season
- **WHEN** the user marks previous seasons watched
- **THEN** one batch operation is sent through the tracker fan-out pipeline
