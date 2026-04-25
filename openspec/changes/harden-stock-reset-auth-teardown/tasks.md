## 1. Implementation

- [x] 1.1 Make remote durable-auth revoke disable live account sync before local stock reset writes.
- [x] 1.2 Add explicit stock account-config defaults.
- [x] 1.3 Add profile-explicit Kitsu auth clearing and use it from account reset.
- [x] 1.4 Add local reset suppression coverage so reset writes cannot push stock defaults remotely.

## 2. Verification

- [ ] 2.1 Run focused auth, sync, and Kitsu tests. Blocked before test execution by unrelated benchmark integration compile errors in `app/src/main/java/com/nexio/tv/data/integration/benchmark/transport/`.
- [ ] 2.2 Run `./gradlew :app:compileUniversalReleaseKotlin`. Blocked by unrelated benchmark integration compile errors in `app/src/main/java/com/nexio/tv/data/integration/benchmark/transport/`.
- [x] 2.3 Run `openspec validate harden-stock-reset-auth-teardown --strict`.
