## 1. Implementation

- [x] 1.1 Add a durable credential self-service Edge Function with status and revoke actions.
- [x] 1.2 Add encrypted pending-revoke storage to Android durable credential storage.
- [x] 1.3 Make Android logout persist pending revoke before clearing local durable auth and call self-revoke idempotently.
- [x] 1.4 Make active Android sessions validate local durable credential status and reset to stock on revoked status.
- [x] 1.5 Retry pending durable credential revokes on startup/auth lifecycle without enabling recovery from pending credentials.
- [x] 1.6 Document Supabase function deployment.

## 2. Verification

- [x] 2.1 Run `deno test --allow-env --allow-net supabase/functions/tests/device-auth.test.ts`.
- [ ] 2.2 Run focused Android auth and durable credential store tests. Blocked before test execution by unrelated benchmark transport compile errors in `app/src/main/java/com/nexio/tv/data/integration/benchmark/transport/`.
- [x] 2.3 Run `openspec validate enforce-durable-revoke-authority --strict`.
- [ ] 2.4 Run `./gradlew :app:compileUniversalReleaseKotlin`. Blocked by unrelated benchmark transport compile errors in `app/src/main/java/com/nexio/tv/data/integration/benchmark/transport/`.
