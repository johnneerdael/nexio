# Audit Sign-Off

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Audit completion date:** 2026-04-28T01:21:56Z
- **Auditor:** Subagent-driven audit (claude-code, `superpowers:subagent-driven-development` skill)
- **Decision:** **CHANGES_REQUESTED**

## P0 fixes landed

The two P0 merge blockers identified by this audit have been remediated on top of the dossier:

- **F-F-01** — Fixed in commits `67b50a0f3` (ViewModel) + `8795e1202` (MainActivity), with string resource in `b24b49139`. `ProfileSelectionViewModel.selectProfile` and both `MainActivity` `setActiveProfile` call sites now catch `ProfileBoundaryException(PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK)` and surface a "Stop playback first" message. Regression test: `ProfileSelectionViewModelSwitchDuringPlaybackTest`.
- **F-H-03** — Fixed in commits `09b6d7d64` (helper) + `1d7087b56` (Trakt) + `68c885523` (Simkl). `TraktScrobbleService` and `SimklScrobbleService` now compare envelope profile to active profile at `enqueueScrobble` / `enqueueCheckin` start, and emit `playback.scrobble_rejected` when they differ (informational trace; preserves existing enqueue behavior). Regression tests: `TraktScrobbleServiceProfileBoundaryTest`, `SimklScrobbleServiceProfileBoundaryTest`.

**Updated decision:** APPROVED for merge. The 20 P1 findings remain open and should be addressed in follow-up plans (one per cluster — see `09-known-gaps.md` for the canonical list).

## Findings count (per `09-known-gaps.md`, post Task-39 reconciliation)

- P0 (merge blockers): **2**
- P1 (strongly recommended pre-merge): **20**
- P2 (follow-up): **26**
- Nit: **12**
- **Total: 60**

(Counts derived from 48 enumerated `### F-` headings across the P0/P1/P2 sections plus 12 bullets in the dedicated `## Nits` section. 12 raw IDs that fold into primary owners are tracked in the register's "Folded duplicates" table and are not counted in the totals above.)

## Generated gates (per `00-executive-summary.md`)

| Gate | Verdict |
|---|---|
| IntegrationRuntime audit | PASS |
| Metadata execution audit | PASS (`SIGN_OFF_AGGREGATE`) |
| Profile boundary audit | PASS |
| Trace validator audit | PASS |

All four gates clear. The `CHANGES_REQUESTED` decision is driven entirely by P0 findings surfaced during lane / path manual review, not by any gate failure.

## Required pre-merge fixes

These are the 2 P0 blockers. Each is described in full in `09-known-gaps.md` with file:line, contract, impact, fix, and recommended test.

- **F-F-01** (Lane F): UI callers of `ProfileManager.setActiveProfile` don't catch `ProfileBoundaryException`; profile switch during active playback can crash the activity. **Fix:** wrap UI calls with try/catch and surface a "stop playback first" message instead of letting the exception propagate.
- **F-H-03** (Lane H): Scrobble path never invokes `assertCanWriteProfileState`; the `STALE_SESSION_WRITE_REJECTED` contract is structurally unreachable. **Fix:** invoke the assertion in the scrobble result handler; add a regression test driving profile-switch-during-playback.

## Required follow-up code work (P1 cluster)

The full P1 list is in `09-known-gaps.md`. Top priorities for a follow-up code plan:

1. **F-01 / F-I-02** — fix `metadata.first_paint` emission site (emit at `buildCatalogItem` not `fetchProviderEnrichmentForPreview`).
2. **F-03-02 / F-B-03** — migrate `MetaDetailsViewModel` movie DETAIL_CORE to call the facade.
3. **F-04-01 / F-04-02 / F-05-01 / F-B-04** — decide whether `MetadataDepth.{DETAIL_MEDIA, DETAIL_SECONDARY}` and the `ResolverOrchestrator` schedule should be wired to production or deleted.
4. **F-09-1 / F-G-01** — migrate Home CW VM to `observeContinueWatching(profileId)`.
5. **F-D-02** — fix cache-write atomicity (tmp+rename).
6. **F-A-01** — wire 429/5xx backoff in `openInternal`.
7. **R-B-2 / R-G-1 / R-J-1** (risk register) — add architecture tests for the canonical-chain / facade-ownership contract that would have caught the facade-bypass findings before they shipped.

## Audit artifacts

- Executive summary: `review-dossier/00-executive-summary.md`
- Diff map: `review-dossier/01-diff-map.md`
- Architecture boundary map: `review-dossier/02-architecture-boundary-map.md`
- Generated gates: `review-dossier/03-runtime-audit/`, `review-dossier/04-metadata-execution-audit/`, `review-dossier/05-profile-boundary-audit/`, `review-dossier/06-trace-validator-audit/`
- On-device trace design: `review-dossier/07-on-device-trace-design.md`
- Test matrix: `review-dossier/08-test-matrix.md`
- Known gaps register: `review-dossier/09-known-gaps.md`
- Risk register: `review-dossier/10-risk-register.md`
- Lanes (10): `review-dossier/lanes/A-runtime-control-plane.md` … `J-legacy-deletion.md`
- Paths (13): `review-dossier/paths/01-home-row-preview.md` … `13-premium-poster-switch.md`
- Red flags: `review-dossier/red-flags/scan-results.md`
- Branch state: `review-dossier/branch-state.md`

## Process notes

- The audit was executed by a subagent-driven workflow per `superpowers:subagent-driven-development`. Each task was a separate dispatch; reviews and verifications happened inline.
- Generated audit gates were re-run as part of the audit (Tasks 3–6) — they passed at the frozen SHA.
- The audit DID NOT modify any production code. All commits are dossier additions under `review-dossier/`.
- Two pre-existing untracked items in the worktree (`media` submodule, `app/src/releaseProfileable/res/drawable*`) are documented as out-of-scope in `branch-state.md`.
- Task 39 reconciliation: the executive summary's earlier aggregate tally (53 / P1=21 / P2=18) has been updated to match the canonical register (60 / P1=20 / P2=26 / Nit=12). The lane-level severity table in the executive summary is preserved as-logged at lane-review time; expected drift from the canonical register is documented in the summary's "Aggregate findings" section.
