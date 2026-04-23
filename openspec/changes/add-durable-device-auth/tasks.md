## 1. OpenSpec
- [ ] Add a `durable-device-auth` capability spec covering durable credential issuance, silent startup recovery, device revocation, and revocation visibility in the portal.
- [ ] Document architecture, migration, and rollout decisions for replacing session-only TV persistence with durable device credentials.

## 2. Supabase durable credential model
- [ ] Add a server-side durable device credential store separate from `linked_devices`, including hashed secret storage, status, timestamps, and device metadata.
- [ ] Update the TV approval exchange flow so approval issues both linkage metadata and a one-time durable device credential payload for the TV.
- [ ] Add a server-side device-session exchange endpoint that validates the durable credential and mints a fresh owner Supabase session for the TV.
- [ ] Add server-side revocation paths so a revoked durable credential can no longer mint new sessions.

## 3. Android TV auth recovery
- [ ] Add secure on-device storage for the durable credential and supporting metadata.
- [ ] Update startup auth recovery to try live session restore first, then durable device-session exchange, before showing any reconnect UI.
- [ ] Ensure sync only resumes after a real Supabase session is minted from the device credential, not from cached identity alone.
- [ ] Ensure explicit sign-out and server-authoritative device revocation clear the local durable credential.

## 4. Portal device management
- [ ] Extend `nexio-web` device management surfaces to show durable device auth state in addition to current linkage metadata.
- [ ] Make device disconnect/revoke invalidate the durable credential that controls future TV session issuance.
- [ ] Surface revocation results clearly so users understand that future reconnect is blocked while already-issued JWTs expire naturally.

## 5. Migration and validation
- [ ] Define migration behavior for already-linked devices that currently only have `linked_devices` rows and owner sessions.
- [ ] Validate Android cold start, upgrade restart, token-loss recovery, revoked-device startup, and explicit sign-out flows.
- [ ] Verify portal revoke behavior, session reissue denial after revoke, and bounded post-revoke access-token expiry behavior.

### Migration Note
- Legacy approved TVs that still possess a live Supabase session are backfilled in-place with a durable device credential on their next successful startup only when the legacy `linked_devices` row can be matched unambiguously from current device metadata.
- Legacy TVs that have already lost all live session state, or whose legacy linkage cannot be matched unambiguously, must reconnect once to receive a durable credential.
- Existing legacy `device_name` values become the initial stable display name when no approval-time custom name exists yet.
