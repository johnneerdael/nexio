## ADDED Requirements

### Requirement: Canonical Account Config Sync Scope

The system SHALL treat only integrations configuration and catalog configuration/order as canonical
synced account settings data, while addons remain on the dedicated addon sync path.

#### Scenario: Contract-v2 write stores only synced account-owned settings
- **WHEN** an authenticated client pushes account settings with `contract_version = 2`
- **THEN** the stored canonical settings payload includes integrations configuration and catalog
  configuration/order only
- **AND** appearance, playback, debug, and other local-only settings are not stored as canonical
  synced settings

#### Scenario: Contract-v2 pull excludes local-only settings
- **WHEN** an authenticated client pulls the account snapshot with `contract_version = 2`
- **THEN** the returned settings payload includes integrations configuration and catalog
  configuration/order only
- **AND** the returned snapshot continues to include addons from the dedicated addon sync path

#### Scenario: New clients leave local-only settings local
- **WHEN** a contract-v2 client changes appearance, playback, debug, or other local-only settings
- **THEN** those changes are not included in settings sync writes
- **AND** later settings sync pulls do not apply those fields onto another device

### Requirement: Backward-Compatible Legacy Settings Contract

The system SHALL preserve compatibility for legacy settings-sync clients while using the narrowed
canonical account-config contract internally.

#### Scenario: Legacy client reads compatibility payload
- **WHEN** an authenticated legacy client pulls the account snapshot without specifying a contract
  version
- **THEN** the server returns the legacy wide settings payload shape expected by that client
- **AND** canonical integrations and catalog configuration are represented with their current values

#### Scenario: Legacy client write is normalized into canonical and compatibility data
- **WHEN** an authenticated legacy client pushes the legacy wide settings payload
- **THEN** the server extracts integrations and catalog configuration/order into the canonical
  stored account-config payload
- **AND** the server preserves local-only legacy fields in compatibility-only storage for later
  legacy reads

#### Scenario: Contract-v2 write preserves legacy compatibility data
- **GIVEN** compatibility-only legacy fields were previously stored for an account
- **WHEN** a contract-v2 client pushes updated integrations or catalog configuration
- **THEN** the canonical stored account-config payload is updated
- **AND** the previously stored compatibility-only legacy fields are preserved for legacy clients

### Requirement: Legacy Stored Rows Remain Readable During Rollout

The system SHALL support existing pre-migration settings rows that are still stored in the legacy
wide payload shape.

#### Scenario: Legacy-stored row can be read as contract v2
- **GIVEN** an account settings row exists only in the legacy wide payload shape
- **WHEN** a contract-v2 client pulls the account snapshot
- **THEN** the server derives the canonical narrowed account-config response from that legacy row

#### Scenario: Legacy-stored row can still be read as contract v1
- **GIVEN** an account settings row exists only in the legacy wide payload shape
- **WHEN** a legacy client pulls the account snapshot
- **THEN** the server returns a compatible legacy wide payload without requiring a prior migration

#### Scenario: Unknown contract version is rejected
- **WHEN** a client calls the settings push or snapshot pull RPC with an unsupported contract
  version
- **THEN** the RPC fails with a clear contract-version error
