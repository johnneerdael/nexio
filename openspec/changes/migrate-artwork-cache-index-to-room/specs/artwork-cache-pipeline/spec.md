## ADDED Requirements

### Requirement: Artwork decisions and asset records use a Room-backed durable index
The system SHALL persist artwork decisions and artwork asset records in a Room-backed cache index
while keeping raw image bytes in the app-owned artwork file cache.

#### Scenario: Decision and asset metadata are stored in Room
- **GIVEN** artwork routing selects a persisted-safe artwork decision
- **WHEN** the selected artwork is materialized into bytes
- **THEN** the system stores the artwork decision in the Room-backed decision table
- **AND** stores the image bytes through `ArtworkAssetDiskCache`
- **AND** stores the asset record in the Room-backed asset-record table
- **AND** does not store image bytes in SQLite

#### Scenario: Existing snapshot stores remain file-backed JSON
- **GIVEN** home first-paint or resolved-display snapshots are persisted
- **WHEN** the artwork cache index is migrated to Room
- **THEN** `HomeCatalogSnapshotStore` remains a bounded JSON snapshot store
- **AND** `ResolvedDisplaySnapshotStore` remains a bounded JSON snapshot store
- **AND** the migration does not require whole-home snapshots to move into SQLite

### Requirement: Durable asset refs are promoted only after asset-record persistence succeeds
The system SHALL NOT persist a `nexio-artwork://asset/{assetKey}` display ref unless the asset
record is durable enough to support restart-time recovery.

#### Scenario: Asset record write succeeds
- **GIVEN** artwork bytes have been written to the app-owned artwork file cache
- **AND** the matching asset record has been persisted in Room
- **WHEN** display metadata or snapshots are written
- **THEN** the display ref may use `nexio-artwork://asset/{assetKey}`

#### Scenario: Asset record write fails
- **GIVEN** artwork bytes have been written to the app-owned artwork file cache
- **AND** the matching asset record write fails
- **WHEN** display metadata or snapshots are written
- **THEN** the display ref does not promote to a durable asset URI
- **AND** the current session may still render the local file if available

### Requirement: Startup artwork prefers direct asset-file hits before decision lookup
The system SHALL use a persisted `assetKey` to render readable local artwork before consulting
decision metadata or executing network work.

#### Scenario: Asset file exists
- **GIVEN** a startup snapshot contains `nexio-artwork://asset/{assetKey}`
- **AND** `ArtworkAssetDiskCache` has a readable file for that asset key
- **WHEN** Coil requests the artwork
- **THEN** `NexioArtworkFetcher` returns the local file as a disk source
- **AND** the system does not look up the decision before rendering
- **AND** the system does not execute network work before rendering

#### Scenario: Asset file is missing
- **GIVEN** a startup snapshot contains `nexio-artwork://asset/{assetKey}`
- **AND** `ArtworkAssetDiskCache` has no readable file for that asset key
- **WHEN** Coil requests the artwork
- **THEN** the system looks up the asset record in Room
- **AND** rehydrates through the stored `decisionKey` when available
- **AND** falls back to placeholder behavior only when recovery cannot satisfy the request

### Requirement: Legacy decision refs recover latest readable assets before network
The system SHALL recover legacy `nexio-artwork://decision/{decisionKey}` refs through the Room index
before performing artwork network work.

#### Scenario: Latest readable asset exists
- **GIVEN** a startup snapshot contains `nexio-artwork://decision/{decisionKey}`
- **AND** the Room asset-record table has a latest asset for that decision key
- **AND** the referenced asset file is readable
- **WHEN** Coil requests the artwork
- **THEN** the system renders the local asset file
- **AND** does not execute network work before rendering
- **AND** may repair the display ref to an asset URI on a later persist

#### Scenario: No readable asset exists
- **GIVEN** a startup snapshot contains `nexio-artwork://decision/{decisionKey}`
- **AND** the Room asset-record table has no readable asset for that decision key
- **WHEN** Coil requests the artwork
- **THEN** the system materializes the stored decision if available
- **AND** executes network only if no local or stale-valid source can satisfy the request

### Requirement: JSON artwork cache migration is tolerant and reversible
The system SHALL import existing JSON artwork decisions and JSON asset records into Room without
blocking first paint or deleting the old files during the initial rollout.

#### Scenario: JSON import succeeds with mixed validity
- **GIVEN** existing JSON artwork decision or asset-record files contain valid and malformed entries
- **WHEN** the Room-backed cache index initializes
- **THEN** valid entries are imported
- **AND** malformed entries are skipped and counted
- **AND** the JSON source files remain available after import

#### Scenario: SQLite initialization fails
- **GIVEN** Room open, migration, or import fails
- **WHEN** artwork cache stores are requested for the session
- **THEN** the system falls back to the existing JSON-backed stores
- **AND** emits diagnostics for the fallback
- **AND** does not block home first paint solely because SQLite is unavailable

### Requirement: Artwork cache repair is bounded background work
The system SHALL repair missing artwork index data after the home surface can render, without
running an unbounded startup sweep on the critical path.

#### Scenario: Repair can reconstruct missing index data
- **GIVEN** a readable artwork file exists for a deterministic asset key
- **AND** enough decision or source metadata exists to reconstruct a valid asset record
- **WHEN** the bounded repair worker runs
- **THEN** the system writes or refreshes the missing asset record
- **AND** future restarts can recover the asset through Room

#### Scenario: Repair finds an orphaned record
- **GIVEN** the Room asset-record table references an asset file that is missing or unreadable
- **WHEN** the bounded repair worker runs
- **THEN** the system marks or prunes the orphaned record according to cache policy
- **AND** avoids repeated hot retries for the same unrecoverable ref during startup

### Requirement: Coil remains the renderer and request image cache
The system SHALL keep Coil responsible for decoded/request image caching and SHALL NOT introduce a
second persistent decoded bitmap or texture database.

#### Scenario: Stable asset refs feed Coil
- **GIVEN** a display model contains `nexio-artwork://asset/{assetKey}`
- **WHEN** Coil renders the model
- **THEN** the custom artwork fetcher supplies bytes from the app-owned file cache or rehydration path
- **AND** Coil may use its memory and disk caches for renderer-level reuse

#### Scenario: Restart loses memory textures
- **GIVEN** the app process restarts
- **WHEN** home first paint renders again
- **THEN** the system relies on stable asset refs, app-owned artwork files, and Coil disk cache
- **AND** does not rely on persistent GPU textures or a custom bitmap database
