## ADDED Requirements

### Requirement: TV login offers matched durable device reuse
The portal SHALL offer durable device credential reuse only for active credentials owned by the approving account whose stored device metadata matches the current TV login request.

#### Scenario: One active credential matches the TV request
- **WHEN** a signed-in user opens a pending TV login approval for a device whose normalized model and compatible platform match exactly one active durable credential owned by the user
- **THEN** the portal shows an option to reuse that matched device
- **AND** the portal also shows an option to create a new device

#### Scenario: Multiple active credentials match the TV request
- **WHEN** a signed-in user opens a pending TV login approval and multiple active credentials match the request metadata
- **THEN** the portal shows only those matched credentials as reuse candidates
- **AND** each candidate includes enough non-secret metadata to distinguish it, including display name and last-seen time when available
- **AND** the portal also shows an option to create a new device

#### Scenario: No active credential matches the TV request
- **WHEN** a signed-in user opens a pending TV login approval and no active credential matches the request metadata
- **THEN** the portal does not show unrelated account devices as reuse candidates
- **AND** approval uses the create-new-device flow

### Requirement: Reuse rotates credential material
The backend SHALL reuse a selected logical device by rotating its durable credential material instead of recovering or returning the old secret.

#### Scenario: User approves matched device reuse
- **WHEN** the user approves TV login by selecting a matched existing durable credential
- **THEN** the backend generates a new device public id and device secret
- **AND** the selected credential row is updated with the new public id and credential hash
- **AND** the selected credential row remains the logical device record for the account
- **AND** the handoff returns the rotated public id and secret to the TV app

#### Scenario: Old install attempts recovery after reuse rotation
- **WHEN** an older app install attempts durable session exchange with the pre-rotation credential
- **THEN** the backend rejects the exchange as an invalid durable device credential

#### Scenario: User chooses create new device
- **WHEN** the user approves TV login by choosing create new device
- **THEN** the backend creates and activates a new durable credential using the existing new-device behavior
- **AND** no existing logical device credential is rotated

### Requirement: Reuse activation is transactional
The backend SHALL consume reuse handoffs and rotate the selected credential atomically.

#### Scenario: Reuse handoff activation succeeds
- **WHEN** the TV app activates a valid reuse handoff before expiry
- **THEN** the handoff is marked used
- **AND** the selected credential row is updated with the rotated credential hash and current requester/linkage metadata in the same transaction

#### Scenario: Selected credential becomes invalid before activation
- **WHEN** the selected credential is revoked, deleted, no longer owned by the approving account, or no longer matches the approved request before activation
- **THEN** activation fails without consuming the handoff
- **AND** no credential row is partially rotated

