## ADDED Requirements

### Requirement: Persisted Home Display Metadata
The system SHALL persist Home-display-ready metadata for all Home rail item types so restored Home snapshots can render consistent metadata without requiring focus-time enrichment.

#### Scenario: Catalog row restores display-ready metadata
- **GIVEN** a Home catalog row was refreshed and persisted
- **AND** display metadata hydration completed for its items
- **WHEN** the app process restarts and Home restores from disk
- **THEN** the restored row includes persisted display-ready metadata for hero/card presentation
- **AND** Home does not depend on item focus to fill in title, logo, description, genres, release info, runtime, or rating when that metadata was previously hydrated

#### Scenario: Continue Watching restores display-ready metadata
- **GIVEN** Continue Watching items were refreshed and persisted
- **WHEN** Home restores Continue Watching from disk
- **THEN** Continue Watching items use the same persisted display metadata path as normal Home items
- **AND** their hero/card metadata is not limited to raw snapshot defaults when persisted enrichment exists

### Requirement: Shared Metadata Path Across Home Feeds
The system SHALL use a shared Home display metadata model for normal catalog items and Continue Watching items.

#### Scenario: Common metadata shape
- **GIVEN** a catalog item and a Continue Watching item reference media shown on Home
- **WHEN** their display data is built for Modern Home
- **THEN** both use the same metadata shape for title, logo, description, genres, release info, runtime, rating, and artwork references

### Requirement: Publish After Hydration
The system SHALL hydrate changed/new Home items before publishing persisted snapshots used for Home restore, with bounded fallback to previously persisted metadata when fresh hydration cannot complete.

#### Scenario: Changed items hydrate before publish
- **GIVEN** a Home refresh produces changed or new items
- **WHEN** the refreshed snapshot is prepared for persistence
- **THEN** the system hydrates those items before publishing the snapshot
- **AND** unchanged items may reuse existing persisted metadata

#### Scenario: Fallback to previous persisted metadata
- **GIVEN** a Home item already has persisted display metadata
- **AND** fresh hydration for the same item is temporarily unavailable
- **WHEN** a refreshed snapshot is published
- **THEN** the system may reuse the previous persisted metadata for that item instead of regressing to sparse raw metadata

### Requirement: Language-Aware Persisted Metadata
Persisted Home display metadata SHALL respect the active app language and be invalidated when the language epoch changes.

#### Scenario: Language change invalidates persisted display metadata
- **GIVEN** Home display metadata was persisted for language `L1`
- **WHEN** the app language changes to `L2`
- **THEN** persisted Home display metadata for `L1` is treated as stale for Home rendering
- **AND** Home snapshots are rehydrated with `L2` metadata before being republished as current

### Requirement: Evict Unreferenced Home Metadata
The system SHALL clean up persisted Home display metadata for items that are no longer referenced by any current Home feed membership, using bounded cleanup to avoid storage growth.

#### Scenario: Item drops out of Home feeds
- **GIVEN** an item was previously persisted with Home display metadata
- **AND** that item no longer appears in any persisted Home catalog row or Continue Watching snapshot
- **WHEN** Home cleanup runs
- **THEN** the item's persisted Home display metadata is removed in a bounded cleanup batch

#### Scenario: Shared item remains referenced
- **GIVEN** an item appears in multiple Home feeds
- **WHEN** it is removed from one feed but still referenced by another
- **THEN** its persisted Home display metadata is retained

### Requirement: Cold-Start Metadata Consistency
Cold-start Home restore SHALL present consistent metadata across rail types without requiring the user to navigate/focus items first.

#### Scenario: No focus-time repair required
- **GIVEN** Home restores from persisted snapshots after process restart
- **WHEN** multiple rows from different sources are shown on screen
- **THEN** rows with previously hydrated metadata render consistently on first paint
- **AND** the user is not required to focus individual items to make their metadata appear
