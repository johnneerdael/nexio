## 1. OpenSpec
- [x] Add a `durable-device-auth` capability spec covering durable credential issuance, silent startup recovery, device revocation, and revocation visibility in the portal.
- [x] Document architecture, migration, and rollout decisions for replacing session-only TV persistence with durable device credentials.

## 2. Supabase durable credential model
- [x] Add a server-side durable device credential store separate from `linked_devices`, including hashed secret storage, status, timestamps, and device metadata.
- [x] Update the TV approval exchange flow so approval issues both linkage metadata and a one-time durable device credential payload for the TV.
- [x] Add a server-side device-session exchange endpoint that validates the durable credential and mints a fresh owner Supabase session for the TV.
- [x] Add server-side revocation paths so a revoked durable credential can no longer mint new sessions.

## 3. Android TV auth recovery
- [x] Add secure on-device storage for the durable credential and supporting metadata.
- [x] Update startup auth recovery to try live session restore first, then durable device-session exchange, before showing any reconnect UI.
- [x] Ensure sync only resumes after a real Supabase session is minted from the device credential, not from cached identity alone.
- [x] Ensure explicit sign-out and server-authoritative device revocation clear the local durable credential.

## 4. Portal device management
- [x] Extend `nexio-web` device management surfaces to show durable device auth state in addition to current linkage metadata.
- [x] Make device disconnect/revoke invalidate the durable credential that controls future TV session issuance.
- [x] Surface revocation results clearly so users understand that future reconnect is blocked while already-issued JWTs expire naturally.

## 5. Migration and validation
- [x] Define migration behavior for already-linked devices that currently only have `linked_devices` rows and owner sessions.
- [ ] Validate Android cold start, upgrade restart, token-loss recovery, revoked-device startup, and explicit sign-out flows.
- [x] Verify portal revoke behavior, session reissue denial after revoke, and bounded post-revoke access-token expiry behavior.

### Migration Note
- Legacy approved TVs are not silently backfilled into durable authority from owner session plus metadata alone.
- Legacy TVs without a pre-existing durable credential must reconnect once to receive a durable credential in this rollout.
- Existing legacy `device_name` values become the initial stable display name when no approval-time custom name exists yet.

### Validation Status For This Fix
- [x] Narrow unit test coverage updated for Android auth policy and Supabase function contract.
- [ ] Android cold-start / upgrade / token-loss / revoked-device manual flows re-exercised end-to-end in this fix.
- [x] Portal revoke behavior re-verified end-to-end in this fix.

### Verification Note 2026-04-24
- Portal/device revoke verification was re-run against project `yjyuomfgkqwmjvnoxurn` using the remediation branch code, a local `nexio-web` server, and disposable test users/devices.
- Verified outcomes:
  - `POST /api/account/devices/revoke` returned `200`
  - the matching `device_credentials` row transitioned to `status = 'revoked'` with `revoked_at` populated
  - post-revoke `device-session-exchange` returned `401 Invalid durable device credential`
  - the pre-revoke owner JWT still authenticated successfully via `auth/v1/user` immediately after revoke

### Android Validation Blocker 2026-04-24
- Attempted to execute the remaining Android matrix locally from this branch by provisioning an emulator:
  - installed `system-images;android-36.1;google_apis;arm64-v8a`
  - created AVD `NexioAuth36`
  - launched the emulator with `-no-window -no-audio -wipe-data -no-snapshot -accel off`
- Result: the guest never progressed past `adb offline`, so the app could not be installed or exercised for cold-start / upgrade / token-loss / revoked-device / explicit sign-out runtime checks.
- Emulator evidence from the host:
  - `adb devices -l` remained `emulator-5554 offline`
  - emulator logs reported `hvf is not enabled on this aarch64 host`
  - emulator logs repeatedly reported `qemu-system-aarch64-headless: qemu_mprotect__osdep: mprotect failed: Permission denied`
- Because no physical Android/TV device is attached to this machine and the local emulator cannot reach a usable online state, the remaining Android validation item is still pending external runtime access.
