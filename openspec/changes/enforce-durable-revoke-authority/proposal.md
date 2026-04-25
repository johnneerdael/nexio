# Enforce Durable Revoke Authority

## Why

Remote durable-device revoke currently prevents future durable session exchange, but an Android TV device with an already-valid Supabase refresh token can continue as authenticated until that token is rejected. Manual logout also attempts remote revoke only while the local session is still present; if the attempt fails, the local credential is cleared and the app loses the data needed to retry.

## What Changes

- Add a no-JWT device credential self-service Edge Function that validates `device_public_id + device_secret`.
- Let Android query durable credential status during active-session publication and refresh recovery.
- Treat a revoked durable credential as authoritative: reset local state to stock, clear local credential/session, and transition to reconnect/session-lost.
- Store encrypted pending-revoke credentials during manual logout so failed remote revoke attempts can retry after local session teardown.
- Keep local stock reset push-suppressed so logout/revoke never pushes stock defaults to the account.

## Impact

- Android TV app auth lifecycle.
- Supabase Edge Functions deployment required.
- No schema migration required.
- Existing web revoke RPC remains unchanged and continues to mark rows revoked.
