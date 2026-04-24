## ADDED Requirements

### Requirement: Local Stock Reset On Auth Teardown

When a user manually logs out or a durable device credential is authoritatively revoked, the Android TV app SHALL reset local account-owned state to stock defaults before presenting the signed-out or reconnect UI.

#### Scenario: Manual logout resets local account-owned state

- **GIVEN** the device has a full account session with custom profiles, addons, integrations, provider credentials, tracking settings, and formatter settings
- **WHEN** the user manually logs out on the device
- **THEN** the device has only the stock `Default` profile
- **AND** only stock addons remain installed locally
- **AND** integration credentials are cleared locally
- **AND** tracking and formatter settings match the stock defaults

#### Scenario: Remote durable-auth revoke resets without remote push

- **GIVEN** the device has a full account session and local account sync observers are active
- **WHEN** the durable auth credential is authoritatively rejected or revoked
- **THEN** the app disables live account sync before local reset writes can be observed as user edits
- **AND** the app does not push stock defaults to the remote account as part of local teardown
- **AND** the device transitions to reconnect or signed-out UI with stock local account state

#### Scenario: Profile-scoped credentials clear the stock account profile

- **GIVEN** profile-scoped provider credentials exist for multiple local profiles
- **WHEN** account-owned local state is reset to stock
- **THEN** credentials belonging to the stock account profile are cleared by explicit profile id
- **AND** reset behavior does not depend on the currently active profile at call time
