## MODIFIED Requirements
### Requirement: Catalog and Metadata Persistence
The system SHALL persist addon-backed, Trakt-backed, and MDBList-backed Home catalog rows and metadata caches on disk so process restarts do not require full refetch for unchanged data.

#### Scenario: Catalog cache survives process restart
- **GIVEN** a Home catalog row from an addon, Trakt, or MDBList feed was previously fetched and persisted
- **WHEN** the app process restarts
- **THEN** Home can load that row from disk cache before any network fetch

#### Scenario: Metadata cache is language aware
- **GIVEN** metadata exists for item key `type:id` in language `L1`
- **WHEN** metadata is requested in language `L1`
- **THEN** the disk cache entry may be used without network fetch
- **AND** requests for other languages MUST be treated as stale/missing

### Requirement: Serialized Post-Startup Refresh Pipeline
When disk-first mode is enabled, the system SHALL process addon-backed, Trakt-backed, and MDBList-backed Home row refresh in a serialized pipeline and SHALL gate UI publish until metadata/image hydration for changed items is complete.

#### Scenario: Serialized catalog refresh
- **GIVEN** multiple Home rows are due for refresh
- **WHEN** post-startup refresh starts
- **THEN** rows are processed sequentially by a single coordinator worker

#### Scenario: Publish gated by hydration completion
- **GIVEN** a row refresh result contains changed/new items
- **WHEN** metadata and image hydration is still in progress for that row
- **THEN** the refreshed row is not published to Home UI
- **AND** publish occurs only after hydration completes or configured fallback timeout policy is reached

#### Scenario: Synthetic feeds publish only after persisted cache renewal
- **GIVEN** Trakt or MDBList discovery refresh produces new row contents
- **WHEN** the persisted Home row cache has not yet been updated
- **THEN** Home continues rendering the previous persisted row
- **AND** the new row becomes visible only after the renewed disk-backed row is written and ready

### Requirement: Incremental Diff and Cleanup
The system SHALL diff refreshed Home rows against cached rows and clean up orphaned metadata/image cache entries when items are no longer referenced by addon, Trakt, or MDBList Home feeds.

#### Scenario: Added and retained items
- **GIVEN** a refreshed Home row is compared to cached state
- **WHEN** items are retained
- **THEN** existing cached metadata/images are reused
- **AND** only added/changed items are queued for hydration

#### Scenario: Removed unreferenced item cleanup
- **GIVEN** an item is removed from Home rows
- **AND** item reference count across addon, Trakt, and MDBList Home feeds reaches zero
- **WHEN** cleanup runs
- **THEN** associated metadata and image cache entries are deleted in bounded batches
