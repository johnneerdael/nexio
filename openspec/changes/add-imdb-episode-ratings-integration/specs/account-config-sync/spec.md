## ADDED Requirements

### Requirement: IMDb account integration sync
The system SHALL support a dedicated account-scoped IMDb integration under `integrations.imdb` with `enabled` and `baseUrl` fields in the account-config sync contract.

#### Scenario: v3 payload includes IMDb integration
- **WHEN** a v3 account-config payload is serialized or stored
- **THEN** it includes `integrations.imdb.enabled`
- **AND** it includes `integrations.imdb.baseUrl`

#### Scenario: v2 snapshot stays compatible
- **WHEN** a v2 client requests an account-config snapshot
- **THEN** the response excludes `integrations.imdb`
- **AND** the existing supported account-config fields are still returned

### Requirement: Web configuration support
The system SHALL allow web-side configuration of the account-scoped IMDb integration through the same `integrations.imdb` contract fields used by Android sync.

#### Scenario: Web-updated IMDb settings are retained in sync
- **WHEN** a web client updates `integrations.imdb.enabled` or `integrations.imdb.baseUrl`
- **THEN** the values are retained in account-config sync
- **AND** subsequent snapshots return the updated IMDb configuration for v3 clients

### Requirement: Android configuration support
The system SHALL allow Android-side configuration of the account-scoped IMDb integration through the same `integrations.imdb` contract fields and the `imdb_api_key` secret.

#### Scenario: Android sync includes IMDb configuration and secret support
- **WHEN** an Android client builds or applies an account-config snapshot
- **THEN** it includes the `integrations.imdb` block in v3 payloads
- **AND** it participates in `imdb_api_key` secret synchronization for `integration:imdb`

### Requirement: Contract version compatibility
The system SHALL accept contract versions 2 and 3 for account-config sync requests, SHALL emit version 3 for current clients, and SHALL keep version 2 support active for older requests.

#### Scenario: Version 2 request remains supported
- **WHEN** a client sends a contract version 2 push or pull request
- **THEN** the request is accepted
- **AND** the server responds using the v2 snapshot shape

#### Scenario: Version 3 request uses the expanded snapshot shape
- **WHEN** a client sends a contract version 3 push or pull request
- **THEN** the request is accepted
- **AND** the server responds using the v3 snapshot shape that includes `integrations.imdb`

### Requirement: IMDb API key secret support
The system SHALL accept an `imdb_api_key` secret type scoped to `integration:imdb` for account-config secret synchronization.

#### Scenario: IMDb secret is allowed
- **WHEN** a client stores or resolves an `imdb_api_key` for `integration:imdb`
- **THEN** the secret type is accepted by the account secret allowlist
- **AND** the secret can participate in account sync

### Requirement: Custom IMDb primary precedence
When the custom IMDb integration is enabled and configured, the system SHALL treat it as the primary episode-ratings source and SHALL NOT fall back to OMDb or TMDB episode-ratings behavior while it remains active.

#### Scenario: Active IMDb integration suppresses fallback
- **WHEN** `integrations.imdb.enabled` is true and `integrations.imdb.baseUrl` is configured
- **THEN** custom IMDb is the primary episode-ratings source
- **AND** OMDb/TMDB fallback is not used while the custom IMDb integration remains active
