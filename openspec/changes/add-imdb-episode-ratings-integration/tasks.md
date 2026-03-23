## 1. Implementation

- [x] 1.1 Add the `account-config-sync` OpenSpec delta covering v2/v3 compatibility, `integrations.imdb`, secret allowlists, and custom IMDb precedence.
- [x] 1.2 Update the Android shared sync models with `ImdbSyncSettings` and bump the contract version to 3.
- [x] 1.3 Wire Android payload construction/apply hooks so IMDb participates in the v3 contract without adding the new runtime DataStore yet.
- [x] 1.4 Add the `imdb_api_key` secret type/ref constants to Android sync constants for later resolution work.
- [x] 1.5 Update Supabase SQL so contract v2 remains supported, v3 emits `integrations.imdb`, and the new secret type is allowed.
- [x] 1.6 Align the web portal contract constants and defaults with the v3 payload shape if compilation requires it. No web change was required in this worktree.
- [x] 1.7 Add/update focused Android contract tests for serialization and routing behavior with `integrations.imdb`.
- [x] 1.8 Run focused verification for the owned sync/tests and OpenSpec validation.
