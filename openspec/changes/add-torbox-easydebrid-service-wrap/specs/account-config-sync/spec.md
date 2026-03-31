## ADDED Requirements

### Requirement: Account-config v4 includes TorBox and EasyDebrid debrid integrations
The system SHALL emit account-config sync version 4 and SHALL include `integrations.debrid.torBox` and `integrations.debrid.easyDebrid` in the current payload shape.

#### Scenario: v4 payload includes TorBox and EasyDebrid integrations
- **WHEN** a current Android or portal client serializes an account-config payload
- **THEN** the payload uses schema version `4`
- **AND** it includes `integrations.debrid.torBox`
- **AND** it includes `integrations.debrid.easyDebrid`

#### Scenario: v3 snapshot compatibility remains available
- **WHEN** a version 3 client requests an account-config snapshot
- **THEN** the server still accepts the request
- **AND** the v3 response excludes the TorBox and EasyDebrid integration blocks

### Requirement: TorBox and EasyDebrid secret sync support
The system SHALL support dedicated account-scoped secret refs and secret types for TorBox and EasyDebrid.

#### Scenario: TorBox secret participates in sync
- **WHEN** a client stores or resolves a TorBox API key
- **THEN** it uses secret ref `integration:torbox`
- **AND** it uses secret type `torbox_api_key`

#### Scenario: EasyDebrid secret participates in sync
- **WHEN** a client stores or resolves an EasyDebrid API key
- **THEN** it uses secret ref `integration:easydebrid`
- **AND** it uses secret type `easydebrid_api_key`

### Requirement: Android and portal retain TorBox and EasyDebrid integration state
The system SHALL persist the TorBox and EasyDebrid integration status through the shared account-config contract.

#### Scenario: Portal-updated TorBox and EasyDebrid settings survive round-trip sync
- **WHEN** the portal saves TorBox or EasyDebrid integration state
- **THEN** the synced account-config payload retains those values
- **AND** a current Android client can apply them without losing existing debrid integration state

#### Scenario: Android-updated TorBox and EasyDebrid settings survive round-trip sync
- **WHEN** Android pushes account-config state with TorBox or EasyDebrid integration data
- **THEN** the portal snapshot returns the same values for current clients
