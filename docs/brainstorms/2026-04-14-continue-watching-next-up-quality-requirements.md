---
date: 2026-04-14
topic: continue-watching-next-up-quality
---

# Continue Watching Next-Up Quality

## Problem Frame

Nexio's Continue Watching feed should pick the right next episode more reliably when Trakt is the tracking source. Trakt does not expose a single continue-watching feed; the checked-in API blueprint documents separate building blocks for playback progress, watched history, show progress, hidden progress, and current watching. The current Nexio pipeline already mixes local resume items and derived next-up entries, but next-up can still be wrong when local metadata is incomplete, season ordering differs, or the local derivation disagrees with Trakt's show-progress semantics.

The goal is to improve next-up correctness without returning to an unbounded per-show Trakt query strategy.

## Requirements

**Next-Up Correctness**
- R1. Continue Watching must continue to prefer resume entries over next-up entries for a show when the user has an active paused or partially watched episode.
- R2. The feed must validate next-up candidates with Trakt when local derivation is ambiguous, stale, or mutation-affected.
- R3. Trakt validation must use a bounded escalation model: local derivation remains the default, and Trakt show-progress validation is used only for high-priority candidates.
- R4. High-priority candidates must be defined by user-visible risk, not popularity. At minimum, candidates are high priority when they are likely to appear in the visible Continue Watching range, were recently active, were affected by a user mutation, have stale validation, or could only be derived through a weak fallback.
- R5. When Trakt validation and local metadata disagree about the current aired next episode, the validated Trakt next episode should win for the show, while local/addon metadata can still supply artwork, runtime, descriptions, and display enrichment.
- R6. Validation must preserve hidden/dropped show and hidden season behavior so that dismissed Trakt progress does not reappear through the escalation path.

**Bounded API Use**
- R7. Validation must have a per-refresh budget so a large watched library cannot cause an API burst.
- R8. Validation results must be cached with explicit freshness rules, and mutation-affected shows may bypass the normal cache once.
- R9. The feed must degrade gracefully when Trakt validation fails or is throttled: keep the best local result, avoid blanking the row, and retry on a later eligible refresh.

**Feed Semantics**
- R10. The user-facing Continue Watching row should remain a mixed activity timeline of resume and next-up items, ordered by relevant recent activity rather than by arbitrary source order.
- R11. Continue Watching next-up rows must not include unaired future episodes. TV detail may continue to show unaired future episodes, but Continue Watching should not expose a user toggle for this behavior.
- R12. The implementation should include decision visibility suitable for debugging wrong next-up reports, such as why a show was locally derived, Trakt-validated, cache-hit, skipped, or suppressed.

## Success Criteria

- For visible Continue Watching candidates, recently watched shows resolve to the same current aired next episode as Trakt show progress when validation is eligible.
- Paused or partially watched episodes continue to suppress a next-up row for the same show.
- Shows with missing local metadata no longer routinely fall back to incorrect `episode + 1` entries when they are visible or recently active.
- Large Trakt accounts do not trigger unbounded show-progress requests during Home refresh.
- If Trakt validation fails, the feed remains usable and does not flicker to empty or remove otherwise reasonable aired local entries.

## Scope Boundaries

- Do not build or assume a nonexistent Trakt continue-watching endpoint.
- Do not use watchlist as an active watching source; `trakt.apib` explicitly advises using watched and show-progress APIs for active watching semantics.
- Do not make Trakt show-progress validation mandatory for every watched show on every refresh.
- Do not make this a general metadata enrichment redesign. Artwork and display polish can be preserved, but the target problem is next-up selection accuracy.
- Do not change unrelated feed surfaces unless they share the same next-up source-of-truth decision.
- Do not change TV detail behavior for unaired future episodes; the no-unaired rule applies to Continue Watching.

## Key Decisions

- Use hybrid escalation: keep Nexio's cheap local derivation first, then validate high-risk candidates with Trakt. This keeps the rate-limit profile closer to the current Nexio approach while correcting the cases users are most likely to see.
- Define high priority by visibility and ambiguity: the feed should spend API budget where a wrong result is visible or likely to be acted on, not on every show in the user's history.
- Keep Trakt as the next-up authority only after validation: local metadata still matters for UI enrichment and fallback behavior, but Trakt should settle the actual episode when the app escalates.

## Dependencies / Assumptions

- `trakt.apib` is the local source for the Trakt API contract. Relevant documented building blocks include `/sync/playback/{type}`, `/sync/watched/{type}`, `/shows/{id}/progress/watched`, `/users/hidden/{section}`, and `/sync/last_activities`.
- Nexio already has a mixed Continue Watching pipeline in `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingTimeline.kt`, `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`, and `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`.
- Comparison repos suggest useful patterns, but changes should be cherry-picked only after checking that they fit Nexio's current snapshot/outbox/tracking architecture.

## Alternatives Considered

| Approach | Outcome |
| --- | --- |
| Trakt-first for all shows | Most authoritative, but likely too expensive and fragile for large accounts. |
| Metadata-first only | Cheapest, but leaves known wrong-next-up cases unresolved when local metadata is incomplete or wrong. |
| Hybrid escalation | Best fit for the stated problem: validates the candidates where correctness matters while keeping API use bounded. |

## Outstanding Questions

### Resolve Before Planning

- None.

### Deferred to Planning

- [Affects R4][Technical] Choose the exact visible candidate budget, likely starting around 20 feed candidates.
- [Affects R7][Technical] Choose the validation request budget and concurrency, likely starting around 5 shows per refresh with concurrency 2.
- [Affects R8][Technical] Choose positive and negative cache TTLs for validated next-up results.
- [Affects R11][Technical] Remove the `showUnairedNextUp` setting from Trakt settings UI, local storage, and account settings sync without changing TV detail unaired behavior.
- [Affects R12][Technical] Decide whether debug visibility should be log-only, structured state, or test-only instrumentation.

## Next Steps

-> /ce:plan for structured implementation planning.
