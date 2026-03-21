# home-startup-refresh Specification

## Purpose
TBD - created by archiving change add-home-disk-first-startup-toggle. Update Purpose after archive.
## Requirements
### Requirement: Toggleable Disk-First Startup Mode
The system SHALL provide a feature toggle that controls whether Home startup uses disk-first deterministic refresh behavior or the existing baseline behavior.

#### Scenario: Toggle disabled uses baseline behavior
- **GIVEN** `diskFirstHomeStartupEnabled` is disabled
- **WHEN** Home startup/resume occurs
- **THEN** the system follows the current baseline refresh behavior
- **AND** no disk-first gating or serialized coordinator behavior is required

#### Scenario: Toggle enabled uses disk-first behavior
- **GIVEN** `diskFirstHomeStartupEnabled` is enabled
- **WHEN** Home startup/resume occurs
- **THEN** the system applies disk-first startup behavior and deferred refresh rules

### Requirement: Startup Network Deferral Window
When disk-first mode is enabled, the system SHALL suppress heavy Home discovery/catalog/metadata/image network refresh in the first 20 seconds after startup unless explicitly user-forced.

#### Scenario: Startup window defers refresh
- **GIVEN** disk-first mode is enabled
- **AND** the app is within the first 20 seconds after Home startup
- **WHEN** Home foreground refresh or observer-triggered discovery refresh is evaluated
- **THEN** heavy network refresh is deferred
- **AND** Home content is served from local cached/snapshot data

#### Scenario: Post-window refresh resumes
- **GIVEN** disk-first mode is enabled
- **AND** the startup deferral window has elapsed
- **WHEN** refresh is due by staleness or notifier signal
- **THEN** refresh execution is allowed

### Requirement: Catalog and Metadata Persistence
The system SHALL persist catalog and metadata caches on disk so process restarts do not require full refetch for unchanged data.

#### Scenario: Catalog cache survives process restart
- **GIVEN** a catalog was previously fetched and persisted
- **WHEN** the app process restarts
- **THEN** Home can load that catalog from disk cache before any network fetch

#### Scenario: Metadata cache is language aware
- **GIVEN** metadata exists for item key `type:id` in language `L1`
- **WHEN** metadata is requested in language `L1`
- **THEN** the disk cache entry may be used without network fetch
- **AND** requests for other languages MUST be treated as stale/missing

### Requirement: Serialized Post-Startup Refresh Pipeline
When disk-first mode is enabled, the system SHALL process catalog refresh in a serialized pipeline and SHALL gate UI publish until metadata/image hydration for changed items is complete.

#### Scenario: Serialized catalog refresh
- **GIVEN** multiple catalogs are due for refresh
- **WHEN** post-startup refresh starts
- **THEN** catalogs are processed sequentially by a single coordinator worker

#### Scenario: Publish gated by hydration completion
- **GIVEN** a catalog refresh result contains changed/new items
- **WHEN** metadata and image hydration is still in progress for that catalog
- **THEN** the refreshed catalog is not published to Home UI
- **AND** publish occurs only after hydration completes or configured fallback timeout policy is reached

### Requirement: Incremental Diff and Cleanup
The system SHALL diff refreshed catalogs against cached catalogs and clean up orphaned metadata/image cache entries when items are no longer referenced.

#### Scenario: Added and retained items
- **GIVEN** a refreshed catalog is compared to cached state
- **WHEN** items are retained
- **THEN** existing cached metadata/images are reused
- **AND** only added/changed items are queued for hydration

#### Scenario: Removed unreferenced item cleanup
- **GIVEN** an item is removed from catalogs
- **AND** item reference count across catalogs reaches zero
- **WHEN** cleanup runs
- **THEN** associated metadata and image cache entries are deleted in bounded batches

### Requirement: Locale Change Rehydration
The system SHALL invalidate metadata cache entries for Home content when display language changes and rehydrate metadata in the new language before publishing refreshed rows.

#### Scenario: Language change invalidates metadata
- **GIVEN** disk-first mode is enabled
- **AND** metadata cache entries exist for a previous language
- **WHEN** app display language changes
- **THEN** metadata cache entries are marked stale for the new language epoch
- **AND** refreshed rows are published with metadata hydrated in the new language

### Requirement: Startup Telemetry for Mode Comparison
The system SHALL emit startup refresh milestones and counters behind the startup telemetry logging toggle to compare baseline and disk-first behavior.

#### Scenario: Telemetry enabled
- **GIVEN** startup telemetry logging is enabled
- **WHEN** disk-first startup mode runs
- **THEN** logs include milestone events for refresh, metadata hydration, image hydration, and publish
- **AND** logs include counters for added items, missing metadata/images, and cleanup counts

#### Scenario: Telemetry disabled
- **GIVEN** startup telemetry logging is disabled
- **WHEN** startup mode runs
- **THEN** milestone/counter logs are not emitted

