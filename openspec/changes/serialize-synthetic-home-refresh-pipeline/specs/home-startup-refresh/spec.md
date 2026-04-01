## MODIFIED Requirements
### Requirement: Serialized Post-Startup Refresh Pipeline
When disk-first mode is enabled, the system SHALL restore Home from one persisted merged snapshot, SHALL process Home feed refresh in one serialized pipeline for Trakt rails, MDBList rails, and addon catalogs, and SHALL gate UI publish until metadata/image hydration for changed items is complete and the renewed merged snapshot is written.

#### Scenario: Serialized Home feed refresh includes every Home source type
- **GIVEN** disk-first mode is enabled
- **AND** Trakt rails, MDBList rails, and addon catalogs are all due for renewal
- **WHEN** post-startup refresh starts after the startup deferral window
- **THEN** Trakt synthetic renewal runs as one serialized step
- **AND** MDBList synthetic renewal runs as a later serialized step
- **AND** addon catalogs run only after the earlier serialized synthetic steps complete

#### Scenario: Persisted merged Home rows remain visible until serialized renewal completes
- **GIVEN** a persisted merged Home snapshot exists on disk
- **AND** the app is in disk-first startup mode
- **WHEN** startup observers receive discovery or preference updates during the startup deferral window
- **THEN** the system restores the persisted merged Home snapshot without eagerly rebuilding Home rows from per-source state
- **AND** the rendered rows remain visible until the serialized renewal pipeline produces the next hydrated persisted merged Home snapshot

#### Scenario: Home never renders rows directly from per-source live refresh state
- **GIVEN** Trakt, MDBList, or addon live refresh state changes in memory
- **WHEN** Home is rendering rows
- **THEN** the rendered Home rows are sourced from the persisted merged Home snapshot
- **AND** per-source live state is used only to renew disk caches and rebuild the next merged snapshot in the background serialized refresh pipeline

#### Scenario: Merged Home publish remains gated by hydration completion
- **GIVEN** a serialized refresh produces changed or new Trakt, MDBList, or addon items
- **WHEN** metadata and image hydration for the serialized refresh is still in progress
- **THEN** the refreshed Home rows are not published to Home UI
- **AND** publish occurs only after hydration and merged persisted Home snapshot write complete
- **AND** Home reloads from the renewed merged persisted snapshot instead of directly using per-source refresh results
