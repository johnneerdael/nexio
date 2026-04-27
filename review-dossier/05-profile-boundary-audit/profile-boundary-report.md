# Profile Boundary Audit

- Artifact role: `PROFILE_BOUNDARY_SIGN_OFF`
- Verdict: `PASS`
- Git SHA: `9215de320`
- Git worktree: `DIRTY`
- Scenarios: `5`
- Violations: `0`

| Scenario | Status | Scope | Provider | Active→Target | WriteTarget | Notes |
| --- | ---: | --- | --- | --- | --- | --- |
| `profile2_same_language_uses_profile1_metadata_cache_without_network` | **PASS** | `GlobalLocalizedContent` | `TMDB` | -→- | `GlobalCache` | Global metadata is keyed by provider, shape, canonical id, language, and policy only. |
| `profile2_different_language_fetches_text_only_not_images` | **PASS** | `GlobalEnglishImage` | `TMDB` | -→- | `GlobalImageCache` | Image cache key is fixed to imageLang:en and omits profile/display-language tokens. |
| `trakt_and_simkl_account_calls_are_account_scoped` | **PASS** | `Account` | `TRAKT,SIMKL` | 1→1 | `ProfileStore(p1)|ProfileStore(p2)` | Trakt and Simkl account calls validate profile, provider, and credential tokens. |
| `continue_watching_profile1_not_visible_in_profile2` | **PASS** | `ProfileLocal` | `LOCAL` | 1→1 | `ProfileLocalStore(p1)` | Continue Watching keys are profile-local and require matching profile context. |
| `profile_switch_rejects_stale_profile_write` | **PASS** | `ProfileLocal` | `LOCAL` | 2→1 | `DiscardedByEnforcer` | Rejected with STALE_SESSION_WRITE_REJECTED when async result session no longer matches active profile. |
