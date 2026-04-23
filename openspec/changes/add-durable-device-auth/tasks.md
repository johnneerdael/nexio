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
- Legacy approved TVs are not silently backfilled into durable authority from owner session plus metadata alone.
- Legacy TVs without a pre-existing durable credential must reconnect once to receive a durable credential unless a stronger proof path is added later.
- Existing legacy `device_name` values become the initial stable display name when no approval-time custom name exists yet.

### Validation Status For This Fix
- [x] Narrow unit test coverage updated for Android auth policy and Supabase function contract.
- [ ] Android cold-start / upgrade / token-loss / revoked-device manual flows re-exercised end-to-end in this fix.
- [ ] Portal revoke behavior re-verified manually in this fix.
