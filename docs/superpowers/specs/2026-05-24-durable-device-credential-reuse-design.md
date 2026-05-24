# Durable Device Credential Reuse Design

Date: 2026-05-24

## Summary

When a user reinstalls Nexio on the same Android TV / Fire TV device, the QR auth flow currently creates a brand new durable device credential even if the account already has an active durable credential for the same displayed device. The approved design is to let nexio-web offer reuse only for active credentials whose stored device metadata matches the TV login request, then rotate the credential secret while preserving the logical device record.

This avoids accumulating duplicate durable devices after reinstall while preserving the current security property that old device secrets are never recoverable from the database.

## Goals

- Reuse an existing logical device only when the authenticated account has an active durable credential matching the current TV login device metadata.
- Require explicit user approval in nexio-web before reuse.
- Rotate the credential material during reuse instead of returning the old secret.
- Preserve the current Android app exchange shape: after QR approval, Android receives a `device_public_id`, `device_secret`, access token, and refresh token, saves the credential, activates the handoff, and imports the owner session.
- Keep the no-match path identical to today's behavior: create a new durable credential and logical device.

## Non-Goals

- Do not show all account devices as reuse candidates.
- Do not infer same physical device from weak metadata without user confirmation.
- Do not store recoverable plaintext device secrets server-side.
- Do not require Android to persist reinstall-proof local identifiers. Reinstall is handled by the authenticated web approval path.

## Current Flow

`AccountViewModel.startQrLogin()` starts a TV login session through `AuthManager.startTvLoginSession(...)`. The TV app polls until nexio-web approves the session, then `AuthManager.exchangeTvLoginSession(...)` calls `supabase/functions/tv-logins-exchange`.

The exchange function always:

- upserts a `linked_devices` row for the anonymous TV requester user,
- generates a fresh `device_public_id` and `device_secret`,
- stages a `device_credential_handoffs` row,
- mints an owner session,
- returns the fresh credential to Android.

Android saves the returned credential in `DurableDeviceCredentialStore`, calls `device-credential-activate`, and later restores sessions through `device-session-exchange`.

## Proposed Flow

### Candidate Discovery

When nexio-web loads an approved TV login request for a signed-in owner, it queries active `device_credentials` for that owner where the stored metadata matches the TV login request:

- `device_name` matches when both sides provide a normalized non-empty value.
- `device_model` matches when both sides provide a normalized non-empty value.
- `device_platform` should be compatible, defaulting current Android TV requests to `Android TV`.

The first version should require `device_model` equality and prefer `device_name` equality when available. This keeps matching conservative for devices with generic names.

### Web UX

If there is one matched active credential, nexio-web offers:

- Reuse this device
- Create new device

If several active credentials match, nexio-web shows only those matched candidates with `display_name`, model/name, and `last_seen_at`, plus Create new device.

If no active credential matches, nexio-web does not show reuse and keeps the existing new-device approval behavior.

### Credential Rotation

On reuse approval, the backend does not attempt to recover the old secret. Instead it:

1. Keeps the existing logical `device_credentials.id`.
2. Generates a new `device_public_id` and `device_secret`.
3. Replaces the selected credential row's public id and credential hash with the rotated values.
4. Rebinds the row to the current TV requester `device_user_id` and current `linked_device_id`.
5. Clears `revoked_at`, sets `status = 'active'`, updates device metadata and `last_seen_at`.
6. Stages a handoff containing the rotated credential values for Android activation.

The old app install loses access immediately because its old `device_public_id` / `device_secret` no longer matches an active credential row.

### New Device Approval

When the user chooses Create new device, the backend preserves today's behavior: create a new durable credential handoff and activate it into a new or requester-conflict-updated `device_credentials` row.

## Backend Shape

The existing `device_credential_handoffs` table can carry both new-device and reuse rotations if it gets a nullable `reuse_device_credential_id` column referencing `device_credentials(id)`.

`tv-logins-exchange` should accept an optional selected credential id or reuse mode from the approved web flow. The function validates that the selected credential:

- belongs to the approving owner,
- is active,
- matches the current TV login metadata,
- is not expired or already consumed through the TV login session,
- is the candidate the web approval recorded.

Activation should branch:

- If `reuse_device_credential_id` is null, insert/update as today's new-device path.
- If it is present, update that exact credential row with rotated public id/hash and new requester/linkage metadata.

The rotation update must be transactionally tied to consuming the handoff so concurrent exchanges cannot activate two secrets for the same logical credential.

## Android Impact

Android should not need a new auth state model for the first implementation. It already accepts the returned `device_public_id` and `device_secret`, saves them, activates the handoff, and imports the owner session.

The Android-visible behavior is:

- Reinstall + web reuse: Android receives a rotated credential for the existing logical device.
- Reinstall + create new: Android receives a new credential as today.
- Old install after reuse rotation: durable recovery fails authoritatively and transitions through the existing revoked/session-lost path.

## Error Handling

- Candidate disappears before exchange: return a conflict and ask nexio-web to refresh candidates.
- Candidate no longer matches request metadata: reject reuse and require choosing Create new device or refreshing.
- Rotation conflict: consume no handoff, return conflict, and allow retry.
- Activation failure after Android receives the rotated credential: keep the handoff valid until expiry or until successful activation; Android can retry activation through the existing flow.
- Old credential recovery after rotation: `device-session-exchange` returns 401 because the old public id/hash no longer matches.

## Testing

- Supabase function tests for matched candidate filtering.
- Supabase function tests that unmatched active credentials are not offered.
- Supabase function tests that reuse rotates public id/hash on the selected logical credential row.
- Supabase function tests that the old credential cannot exchange after rotation.
- Supabase function tests that Create new device preserves current behavior.
- Android unit test coverage can stay focused on compatibility: `finalizeTvLoginExchange` still saves the returned rotated credential before activation/import.

## Rollout

This is backward-compatible for Android because the response payload stays stable. Deploy backend/web changes first. Existing Android builds keep working with new-device approvals and can receive rotated credentials without code changes.

