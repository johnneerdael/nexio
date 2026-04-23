## ADDED Requirements

### Requirement: Durable Credential Controls Future Session Renewal

The Android TV app SHALL route durable-device session renewal through explicit Nexio auth recovery
logic instead of Supabase SDK background auto-refresh.

#### Scenario: Revoked durable credential cannot keep refreshing in the background
- **WHEN** a durable-linked TV is running with a previously issued Supabase refresh token
- **AND** the durable credential has been revoked server-side
- **THEN** the app only attempts renewal through explicit `AuthManager` refresh or durable recovery
  paths
- **AND** the Supabase SDK does not mint fresh sessions automatically in the background

#### Scenario: Explicit renewal falls back to durable recovery on authoritative refresh rejection
- **WHEN** an explicit Supabase refresh attempt is authoritatively rejected
- **AND** a durable credential is still present on disk
- **THEN** Nexio attempts durable device session recovery
- **AND** if the durable exchange is authoritatively rejected, the local durable credential is
  cleared before the app surfaces reconnect or signed-out state

### Requirement: Durable Credential Stays Bound To The Authenticated Owner

The Android TV app SHALL prevent stale durable credentials from surviving a later direct manual
sign-in or sign-up to a different account.

#### Scenario: Manual account auth clears ambiguous or foreign durable credentials
- **WHEN** a user completes a direct email sign-in or sign-up
- **AND** the stored durable credential is complete but has no owner binding or belongs to a
  different owner
- **THEN** the app clears the stored durable credential before it can be reused on a later cold
  start

#### Scenario: Matching authenticated owner preserves durable credential
- **WHEN** a full authenticated session appears for the same owner as the stored durable credential
- **THEN** the app keeps the durable credential on disk
- **AND** it may bind the credential to that owner if the owner metadata was previously missing

#### Scenario: Authenticated owner mismatch clears stale durable credential
- **WHEN** a full authenticated session appears for a different owner than the stored durable
  credential declares
- **THEN** the app clears the stored durable credential
- **AND** any later cold start must rely on the current account's own durable credential or a fresh
  reconnect flow
