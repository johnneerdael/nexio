## ADDED Requirements

### Requirement: Durable Credential Revoke Is Authoritative For Active Devices

An Android TV device with a local durable credential SHALL treat that credential's remote status as authoritative even when a Supabase refresh token is still locally available.

#### Scenario: Active session detects remote durable revoke

- **GIVEN** an Android TV device has a full Supabase session and a complete local durable credential
- **AND** the credential row in Supabase has `status = 'revoked'`
- **WHEN** the app starts, publishes an authenticated session, or handles session refresh recovery
- **THEN** the app resets local account-owned state to stock defaults
- **AND** the app clears the local durable credential
- **AND** the app clears the local Supabase session
- **AND** the app transitions to reconnect/session-lost instead of remaining authenticated

#### Scenario: Active session tolerates transient durable status failure

- **GIVEN** an Android TV device has a full Supabase session and a complete local durable credential
- **WHEN** the durable credential status endpoint fails with a transient network or server error
- **THEN** the app does not clear local account state
- **AND** the app keeps the current auth state so a later status check can retry

### Requirement: Manual Logout Reliably Revokes Durable Credential

Manual logout SHALL make the local durable credential unusable for future sessions and SHALL retry remote durable credential revoke when the first revoke attempt cannot complete.

#### Scenario: Manual logout revokes online

- **GIVEN** an Android TV device has a full account session and a complete local durable credential
- **WHEN** the user manually logs out while the network is available
- **THEN** the app stores a pending revoke record before clearing the active durable credential
- **AND** the app calls the self-service revoke endpoint with the durable credential secret
- **AND** the remote credential row becomes `status = 'revoked'`
- **AND** the app clears the pending revoke record
- **AND** the active local durable credential is cleared

#### Scenario: Manual logout queues revoke while offline

- **GIVEN** an Android TV device has a full account session and a complete local durable credential
- **WHEN** the user manually logs out while the remote revoke endpoint is unavailable
- **THEN** the app resets local account-owned state to stock defaults
- **AND** the app clears the active local durable credential
- **AND** the app retains only an encrypted pending-revoke record
- **AND** future durable recovery does not use the pending-revoke record
- **AND** the app retries remote revoke when network/auth lifecycle work resumes
