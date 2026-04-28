# Executive Summary

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Branch:** `codex/integration-runtime-phase-a`
- **Base:** `main`
- **Commits:** 168 (per `branch-state.md`)
- **Files changed:** 1037 (+148,329 / -145,337) — most volume is large pre-existing API blueprint files (tmdb.json, trakt.apib, tvdb.yml, kitsu.apib, simkl.apib); substantive code lives in `core/integration`, `core/metadata/router`, `core/trace`, `core/profile`, `core/playback`, `ui/screens/home`, `ui/screens/detail`, `ui/screens/player`, `ui/screens/settings`.
- **OpenSpec changes added on this branch:** 3 (`add-metadata-router`, `add-runtime-trace-mode`, `harden-profile-boundary-contract`). `enforce-profile-boundary-scopes` predates this branch.

## Phase 1 — Generated gate verdicts

| Gate | Verdict | Key numbers | Reference |
|---|---|---|---|
| IntegrationRuntime audit | PASS | 24 providers, 125 endpoint shapes, 89 runtime-covered calls; all 7 gate counters = 0 | `03-runtime-audit/SUMMARY.md` |
| Metadata execution audit | PASS (`SIGN_OFF_AGGREGATE`) | 22 items, 20 routed, 7 cache hits, 1 stale hit, 0 policy violations, 13/13 required scenarios PASS | `04-metadata-execution-audit/SUMMARY.md` |
| Profile boundary audit | PASS | 0 violations, 5/5 required scenarios PASS | `05-profile-boundary-audit/SUMMARY.md` |
| Trace validator audit | PASS | `TraceBundleGoldenTest` 1/1 + `RuntimeTraceValidatorRealEmissionTest` 1/1 PASS | `06-trace-validator-audit/SUMMARY.md` |

## Phase 5 — Lane verdicts

| Lane | Verdict | P0 | P1 | P2 | Nit | Reference |
|---|---|---:|---:|---:|---:|---|
| A — Runtime control plane | CHANGES_REQUESTED | 0 | 2 | 1 | 2 | `lanes/A-runtime-control-plane.md` |
| B — MetadataRouter | CHANGES_REQUESTED | 0 | 4 | 2 | 1 | `lanes/B-metadata-router.md` |
| C — Provider contracts | CHANGES_REQUESTED | 0 | 3 | 2 | 1 | `lanes/C-provider-contracts.md` |
| D — Cache + backoff | CHANGES_REQUESTED | 0 | 4 | 1 | 1 | `lanes/D-cache-backoff.md` |
| E — Localization | CHANGES_REQUESTED | 0 | 3 | 1 | 1 | `lanes/E-localization.md` |
| F — Profile boundaries | CHANGES_REQUESTED | 1 | 1 | 2 | 1 | `lanes/F-profile-boundaries.md` |
| G — Continue Watching | ⚠️ PARTIAL | 0 | 1 | 1 | 1 | `lanes/G-continue-watching.md` |
| H — Playback + scrobble | ⚠️ PARTIAL | 1 | 0 | 0 | 2 | `lanes/H-playback-scrobble.md` |
| I — Trace mode | CHANGES_REQUESTED | 0 | 3 | 2 | 0 | `lanes/I-trace-mode.md` |
| J — Legacy deletion | CHANGES_REQUESTED | 0 | 0 | 2 | 2 | `lanes/J-legacy-deletion.md` |

Aggregate verdict tally: APPROVED 0, CHANGES_REQUESTED 8, ⚠️ PARTIAL 2.

## Aggregate findings

Reconciled against the canonical register `09-known-gaps.md` (Task 39 sign-off pass):

- **Total unique findings:** 60 (48 enumerated under `### F-` headings in P0/P1/P2 sections + 12 Nits enumerated as bullets in the dedicated `## Nits` section). Folded duplicates (12 raw IDs that collapse into primary owners — listed in the register's "Folded duplicates" table) are not counted.
- **P0 (merge blockers):** 2
- **P1 (strongly recommended pre-merge):** 20
- **P2 (follow-up):** 26
- **Nits:** 12

Note: the lane-verdict table above shows Lane-level severity tallies as logged at lane-review time. The canonical, post-deduplication aggregate is the four numbers immediately above; minor lane vs. register drift is expected because (a) some findings span two lanes and the register attributes them to a single primary owner, and (b) cross-ref folds (e.g. F-09-1 → F-G-01, F-10-1 → F-F-01) collapse multiple lane-level entries into one register entry.

See `09-known-gaps.md` for the full register.

## Production path coverage

13 of 13 paths traced. See `paths/`. Verdict distribution:
- ✅ PASS: 3 (paths 02 home-visible-item-enrichment, 07 player-start, 08 continue-watching-write)
- ⚠️ PARTIAL / WARN: 6 (paths 01 home-row-preview, 06 season-tab, 09 continue-watching-render, 10 profile-switch, 11 scrobble, 12 skip-segment-lookup)
- ❌ FAIL: 4 (paths 03 detail-core, 04 detail-media, 05 detail-secondary, 13 premium-poster-switch)

## Risk register

21 plausible future risks documented. See `10-risk-register.md`. Most-cited mitigation: architecture tests enforcing the canonical-chain contract — a generic `architecture/CanonicalChainBoundaryTest.kt` suite would address 9 of 21 risks.

## Merge recommendation

**CHANGES_REQUESTED**

### Rationale

The branch successfully clears all four generated gates (runtime, metadata-execution, profile-boundary, trace-validator), which proves the new architecture's _structural_ wiring is correct. However, lane reviews surfaced 2 merge-blocking P0 findings where production behavior silently diverges from the spec contract:

1. **F-F-01** — UI callers of `ProfileManager.setActiveProfile` don't catch `ProfileBoundaryException`, so triggering a profile switch during active playback can crash the activity. This is a user-visible crash on a real interactive flow.
2. **F-H-03** — The scrobble path never invokes `assertCanWriteProfileState`, so the contract "late scrobble after profile switch is rejected via `STALE_SESSION_WRITE_REJECTED`" is structurally unreachable. No test catches it. A late scrobble result will write to the wrong profile in production despite the spec saying otherwise.

Beyond P0s, 20 P1 findings cluster around three themes:
- **Facade bypass** — significant production paths (`MetaDetailsViewModel.kt:1406` for movie DETAIL_CORE TMDB enrichment, `SkipIntroRepository`, the trailer pipeline, reviews/recommendations, cast/person, premium poster URL-rewrite) call repositories/services directly instead of `MetadataRouterFacade`. The "facade owns metadata execution" contract has implementation gaps.
- **Resolver orchestration dead-or-half-wired** — `ResolverOrchestrator.schedule()` is invoked from `MetadataRouterFacade.kt:34`, but the schedule it produces is never dispatched. `metadata.resolver_schedule` event fires but nothing acts on it. DETAIL_MEDIA / DETAIL_SECONDARY depths have no production callers.
- **Trace event emission placement** — `metadata.first_paint` fires from a router-invoking site (`fetchProviderEnrichmentForPreview`), violating its `routerExecuted = false` claim. The validator rule `PreviewMustNotRouteOrNetwork` would fail on real traces.

### Required pre-merge fixes (P0)

- **F-F-01** — Wrap UI calls to `ProfileManager.setActiveProfile` with try/catch; show a user-facing "stop playback first" message instead of letting the exception propagate.
- **F-H-03** — Wire `ProfileBoundaryEnforcer.assertCanWriteProfileState(playbackOwnerProfileId, playbackOwnerSessionId, activeProfileId, activeSessionId)` into the scrobble result-handling path so late writes are discarded; add a regression test driving a profile-switch-during-playback scenario.

### Strongly recommended (P1) — should land in a follow-on change

The full P1 list is in `09-known-gaps.md`. Top priorities for a follow-up code plan:

1. Fix `metadata.first_paint` emission site (F-01 / F-I-02) — emit at `buildCatalogItem` (`ModernHomeModels.kt:570`), not at `fetchProviderEnrichmentForPreview`.
2. Migrate `MetaDetailsViewModel` movie DETAIL_CORE to call the facade rather than `metadataSecondaryRepository.fetchTmdbEnrichment` (F-03-02 / F-B-03).
3. Either delete `MetadataDepth.{DETAIL_MEDIA, DETAIL_SECONDARY}` + `ResolverOrchestrator` orchestration bookkeeping, OR wire the depths to actual production paths (F-04-01, F-04-02, F-05-01, F-B-04).
4. Migrate Home CW VM to the explicit `observeContinueWatching(profileId)` API (F-09-1 / F-G-01).
5. Pre-stream/pre-fetch atomicity fix for cache writes (F-D-02).
6. 429/5xx backoff in `openInternal` (F-A-01).
7. Add the architecture tests that would have caught the facade-bypass findings — see `R-B-2`, `R-G-1`, `R-J-1` mitigations in the risk register.

### Follow-up (P2 + Nit)

26 P2 + 12 Nit findings. Defer to a polish PR or sweep them as separate small commits. None blocks merge.

## What to do next

1. Author addresses F-F-01 and F-H-03. Add regression tests.
2. Re-run the four generated gates against the post-fix SHA — they should still PASS.
3. Re-run `RuntimeTraceValidatorRealEmissionTest` — should still PASS.
4. Open a follow-up plan for the P1 cluster (the audit-style review can drive it directly using `09-known-gaps.md`).
5. Decide whether to also address P1s pre-merge or in a follow-on.
