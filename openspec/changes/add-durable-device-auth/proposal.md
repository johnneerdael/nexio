# Change: Add Durable Device Auth

## Why
Android TV durable sign-in currently depends on a mix of normal Supabase session persistence and a
new Nexio-owned durable device credential. That leaves two hardening gaps:

- a revoked TV can continue minting fresh sessions while the app is running if the Supabase SDK
  refreshes in the background outside Nexio's explicit recovery path
- a stale durable credential can remain on disk across later manual sign-in/sign-up, which risks
  reviving the wrong account on the next cold start

The durable credential must be the authority for future TV renewals, and locally stored durable
state must stay aligned with the currently authenticated account.

## What Changes
- Disable Supabase SDK background auto-refresh in the Android app so session renewal flows through
  `AuthManager`'s explicit refresh and durable-recovery logic.
- Bind durable device credentials on disk to an owning Supabase user ID.
- Clear legacy or mismatched durable credentials after direct sign-in/sign-up so a manual account
  switch cannot preserve the prior account's durable credential.
- Clear owner-mismatched durable credentials when an authenticated session appears with a different
  user than the credential claims.
- Preserve the TV login exchange flow, but disable metadata-only legacy durable credential
  backfill from the normal app/runtime path until a stronger proof path exists.

## Impact
- Affected specs: `durable-device-auth` (new capability)
- Affected code:
  - `app/src/main/java/com/nexio/tv/core/di/SupabaseModule.kt`
  - `app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt`
  - `app/src/main/java/com/nexio/tv/data/local/DurableDeviceCredentialStore.kt`
  - `app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt`
  - `app/src/test/java/com/nexio/tv/data/local/DurableDeviceCredentialStoreTest.kt`
- Supporting docs:
  - `openspec/changes/add-durable-device-auth/design.md`

## Rollout & Safety
- Keep the change Android-only and narrowly scoped to renewal authority and local owner binding.
- Continue using explicit `AuthManager` refresh attempts so transient failures do not sign users out
  prematurely.
- Clear only complete durable credentials whose owner is missing or mismatched during manual
  account auth, which favors preventing wrong-account resurrection over preserving ambiguous legacy
  credentials.
- Require a one-time reconnect for legacy devices that do not already hold a durable credential,
  rather than silently promoting authority from owner session plus metadata matching.
