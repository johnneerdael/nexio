# Profile Boundary Audit Verdict

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Audit task:** `:app:generateProfileBoundaryAudit`
- **Verdict:** PASS
- **Violations:** 0 (expected: 0)

## Required scenarios

| Scenario | Status |
|---|---|
| profile2_same_language_uses_profile1_metadata_cache_without_network | PASS |
| profile2_different_language_fetches_text_only_not_images | PASS |
| trakt_and_simkl_account_calls_are_account_scoped | PASS |
| continue_watching_profile1_not_visible_in_profile2 | PASS |
| profile_switch_rejects_stale_profile_write | PASS |

## Audit field schema (per scenario)

The schema is intentionally heterogeneous: the two metadata-cache scenarios only carry cache-shape evidence (`cacheKey`, `profileIdInCacheKey`, `credentialHashInCacheKey`, `networkExecuted`, `language`/`imageLanguage`, `scope`, `writeTarget`). The three identity-scoped scenarios (account / continue-watching / stale-write) carry the identity-trace fields added in commit `0a1e804d4`.

| Scenario | activeProfileId | targetProfileId | writeTarget | credentialTraceHash | sessionHash |
|---|---|---|---|---|---|
| profile2_same_language_uses_profile1_metadata_cache_without_network | n/a | n/a | present | n/a | n/a |
| profile2_different_language_fetches_text_only_not_images | n/a | n/a | present | n/a | n/a |
| trakt_and_simkl_account_calls_are_account_scoped | present | present | present | present | present |
| continue_watching_profile1_not_visible_in_profile2 | present | present | present | n/a | present |
| profile_switch_rejects_stale_profile_write | present | present | present | n/a | present |

`credentialTraceHash` is only emitted by the account-scoped Trakt/Simkl scenario (the only one whose contract requires correlating the OAuth token-trace hash to the active profile). `continue_watching_*` and `profile_switch_rejects_*` are local DB / write-router scenarios with no remote credential to trace, so the field is correctly absent rather than missing. Every scenario whose contract requires a given field carries it.

## Pass criteria

- Verdict `PASS`. Violations 0.
- Every required scenario PASS (5 / 5).
- Every audit field present on every scenario whose contract requires it.

Any failure goes to `lanes/F-profile-boundaries.md` (Task 30 owner) as a P0.

## Outcome

PASS — gate cleared. No P0 filed.
