## 1. Implementation

- [ ] 1.1 Add a durable credential self-service Edge Function with status and revoke actions.
- [ ] 1.2 Add encrypted pending-revoke storage to Android durable credential storage.
- [ ] 1.3 Make Android logout persist pending revoke before clearing local durable auth and call self-revoke idempotently.
- [ ] 1.4 Make active Android sessions validate local durable credential status and reset to stock on revoked status.
- [ ] 1.5 Retry pending durable credential revokes on startup/auth lifecycle without enabling recovery from pending credentials.
- [ ] 1.6 Document Supabase function deployment.

## 2. Verification

- [ ] 2.1 Run `deno test --allow-env --allow-net supabase/functions/tests/device-auth.test.ts`.
- [ ] 2.2 Run focused Android auth and durable credential store tests.
- [ ] 2.3 Run `openspec validate enforce-durable-revoke-authority --strict`.
- [ ] 2.4 Run `./gradlew :app:compileUniversalReleaseKotlin` after unrelated benchmark transport compile errors are fixed.
