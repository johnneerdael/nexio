# Trakt Persistent Outbox Implementation Review

## Purpose

This note translates the approved persistent-outbox plan into repo-specific implementation guidance.
It documents the current direct-write surfaces, the optimistic state that must be preserved during the migration,
and the code-quality risks that should stay visible while the worker lanes land their changes.

Primary references:
- `.omx/plans/trakt-persistent-outbox-server-truth-20260408T093200Z.md`
- `.omx/plans/prd-trakt-persistent-outbox-server-truth.md`
- `.omx/plans/test-spec-trakt-persistent-outbox-server-truth.md`

## Current direct Trakt write inventory

All known app-side Trakt writes currently live in four services and should be treated as the migration boundary for the shared outbox.

| Surface | File | Current direct writes |
| --- | --- | --- |
| Progress / history | `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt` | `addHistory`, `deletePlayback`, `removeHistory` |
| Library / lists | `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt` | `createUserList`, `updateUserList`, `deleteUserList`, `reorderUserLists`, `addToWatchlist`, `removeFromWatchlist`, `addUserListItems`, `removeUserListItems` |
| Discovery | `app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt` | `hideRecommendation` |
| Scrobble / check-in | `app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt` | `checkin`, `scrobbleStart`, `scrobblePause`, `scrobbleStop` |

## Existing optimistic behavior that must survive the refactor

### Progress / history
- `TraktProgressService.markAsWatched()` immediately updates local watched caches before forcing a refresh (`TraktProgressService.kt:508-538`).
- `removeProgress()`, `clearShowProgress()`, and `removeFromHistory()` apply optimistic removal before remote mutation and then reconcile with refreshes (`TraktProgressService.kt:552-694`).
- The service already has merge logic for optimistic progress, episode progress caches, watched caches, and synthetic next-up entries; the outbox migration should reuse those read-side reconciliation hooks instead of duplicating them elsewhere.

### Library / personal lists
- `TraktLibraryService` already exposes a strong optimistic mutation seam through `performOptimisticMutation()` (`TraktLibraryService.kt:428-442`).
- Watchlist toggles, list membership changes, list updates, deletes, and reorders already mutate the persisted snapshot before the remote call and roll back to the previous snapshot on failure (`TraktLibraryService.kt:163-367`).
- This is the cleanest existing example of a domain adapter boundary: optimistic apply/rollback stays local while transport currently remains direct.

### Discovery
- `dismissRecommendation()` currently always records the local dismissal even when the remote hide call fails (`TraktDiscoveryService.kt:330-343`).
- The outbox migration must convert this into a true optimistic adapter with rollback-to-server-truth semantics instead of a fire-and-forget best-effort write.

### Scrobble / check-in
- `TraktScrobbleService` keeps lightweight in-memory dedupe and watching-now state (`TraktScrobbleService.kt:64-149`).
- Today that dedupe is process-local only. The persistent outbox must become the durable collapse point for scrobble latest-state semantics, while this service should shrink toward request-shape building and UI-state projection.

## Review findings and migration risks

### 1. Direct-write policy is currently fragmented
Every write surface still calls `TraktApi` directly. That means rate limiting, retry policy, fairness, collapse rules, and restart durability are split across services or missing entirely.

**Why it matters:** the approved design depends on one queue owning `1 req/sec`, `Retry-After`, retry classification, and fairness. Partial migration leaves bypass paths that silently reintroduce inconsistent behavior.

### 2. Discovery already violates the desired rollback contract
`dismissRecommendation()` records the dismissed key regardless of whether the remote write succeeds.

**Why it matters:** once the outbox exists, discovery should not remain a permanent local-only dismissal if Trakt rejects the mutation. This is the clearest current mismatch against the PRD's "rollback to server truth" rule.

### 3. Progress removal can expand into multi-request bursts
`removeProgress()` and `clearShowProgress()` walk playback rows and issue one delete per playback item before optionally removing history.

**Why it matters:** without centralized queueing, a single user action can fan out into several writes and compete with scrobbles or watchlist/list mutations. The outbox worker needs to serialize these writes and the adapter should keep one user-visible optimistic intent.

### 4. Library already has the right adapter shape
`performOptimisticMutation()` captures the before-state, persists the optimistic projection, and restores on failure.

**Why it matters:** this is the pattern the shared outbox should preserve. The queue should own delivery policy, but library snapshot mutation and rollback should remain inside `TraktLibraryService` rather than moving into a generic transport layer.

### 5. Scrobble collapse is currently memory-only
`shouldSkip()` uses only the last in-memory stamp and an `8s` same-item window.

**Why it matters:** process death loses collapse history, and the logic is independent from the future global fairness/rate-limit policy. The persistent outbox should own durable collapse identity, while the service keeps only local watching-now state updates.

## Recommended adapter boundaries

### Outbox core should own
- durable storage of pending mutations and retry metadata
- priority ordering and bounded fairness
- `1 req/sec` authenticated write spacing
- `Retry-After` handling and transient-vs-terminal classification
- latest-safe-intent collapse keys, especially for scrobble/check-in churn
- startup restore and drain resumption

### Domain adapters should own
- optimistic local apply
- rollback to the previously visible server-aligned state
- success reconciliation hooks
- cache invalidation / forced refresh rules
- mutation-specific collapse identity inputs
- minimal payload construction for the Trakt API call that the outbox eventually executes

## No-bypass audit checklist

After implementation lands, re-run these checks before calling the migration complete:

```bash
rg -n "traktApi\.(addHistory|removeHistory|deletePlayback|addToWatchlist|removeFromWatchlist|addUserListItems|removeUserListItems|createUserList|updateUserList|deleteUserList|reorderUserLists|hideRecommendation|scrobbleStart|scrobblePause|scrobbleStop|checkin)\(" app/src/main/java
```

Expected end state:
- either zero matches outside the shared outbox worker / adapter execution seam,
- or only queue-owned execution sites that are intentionally centralized and documented.

## Worker-lane mapping against the approved plan

- **Outbox core / fairness lane:** build the store, scheduler, retry policy, fairness, and durable collapse semantics.
- **Progress lane:** route history and playback deletes through the outbox while keeping optimistic progress and next-up reconciliation inside `TraktProgressService`.
- **Library + discovery lane:** reuse `performOptimisticMutation()` patterns, make discovery dismissal reversible, and keep reorder/list snapshot semantics inside the domain service.
- **Scrobble lane:** move dedupe/collapse ownership into the outbox while preserving watching-now state updates for the UI.
- **Verification lane:** prove there are no remaining direct-write bypasses and that the fairness/rate-limit guarantees are observable in tests.

## Definition of done for the documentation side

The implementation should not be considered fully documented until the final merged change-set also explains:
- which outbox mutation types map to which domain adapter,
- which read caches refresh on success vs terminal failure,
- how discovery dismissal now rolls back,
- how scrobble collapse interacts with check-in and fairness,
- and how the no-direct-write audit is enforced in tests or CI.
