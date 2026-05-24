# Change: Reuse durable device credentials after reinstall

## Why
Reinstalling Nexio on the same Android TV / Fire TV device currently creates another durable device credential even when the account already has an active credential for that displayed device. This duplicates linked devices and weakens the value of durable device identity.

## What Changes
- Let nexio-web offer reuse only for active durable credentials owned by the signed-in account whose stored device metadata matches the current TV login request.
- Require explicit user selection before reuse.
- Reuse the logical device record by rotating the credential public id and secret hash instead of recovering the old secret.
- Preserve the existing create-new-device path when no match exists or the user declines reuse.

## Impact
- Affected app: `nexio-web`
- Affected backend: Supabase TV login and durable credential functions / migrations
- Affected Android surface: QR login exchange compatibility only
- Affected specs: `portal-auth`

