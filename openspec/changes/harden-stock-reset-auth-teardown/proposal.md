# Harden Stock Reset Auth Teardown

## Why

Logout and remote durable-auth revoke must remove local account state immediately. The current implementation resets local DataStores before every live auth/sync path is guaranteed inactive, and one provider reset depends on the active profile instead of targeting the default account profile explicitly.

## What Changes

- Ensure remote durable-auth revoke disables account sync before stock reset writes occur.
- Define stock account settings explicitly instead of relying on DTO constructor defaults.
- Clear profile-scoped provider credentials against the default legacy profile deterministically.
- Add executable tests proving reset does not schedule remote account pushes and credentials are cleared.

## Impact

- Android TV app only.
- No Supabase schema migration.
- No remote data deletion during logout; this is local-device teardown only.
