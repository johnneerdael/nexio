## Context
Startup profiling and logs show heavy early refresh fan-out from Home resume and snapshot observers, with memory-only catalog/metadata caches forcing repeated network and hydration work after process restart. The requested behavior is a deterministic disk-first startup path with deferred serialized refresh, while retaining a hard fallback to the existing baseline.

## Goals / Non-Goals
- Goals:
  - Keep first-render content local and deterministic when enabled.
  - Prevent heavy network/meta/image fan-out in first 20 seconds.
  - Serialize post-startup refresh and gate publish until hydration completes.
  - Preserve current baseline path behind a toggle.
- Non-Goals:
  - Replacing all existing caches with Room in this phase.
  - Changing discovery business logic for Trakt/MDBList selection itself.
  - Immediate redesign of UI composition/render architecture.

## Decisions
- Decision: Introduce `diskFirstHomeStartupEnabled` feature toggle (default OFF).
  - Rationale: safe rollback and A/B comparison against baseline.
- Decision: Two-phase startup coordinator.
  - Phase A (startup window): consume disk snapshots/caches only.
  - Phase B (after 20s): run serialized refresh worker.
- Decision: Add disk stores for catalogs and metadata with explicit schema version + language tag.
  - Rationale: process restart persistence and language-aware correctness.
- Decision: Publish-gate refreshed rows until metadata/image hydration completion.
  - Rationale: avoid partial rows and repeated recomposition churn.
- Decision: Keep cleanup bounded and incremental.
  - Rationale: avoid IO bursts and additional startup regressions.

## Alternatives Considered
- Always-on disk-first mode without toggle.
  - Rejected due to high regression risk and lack of instant rollback.
- Startup gate only, without persistent disk metadata cache.
  - Rejected because restart behavior would still trigger broad metadata refetch.
- Full Room migration now.
  - Deferred to limit scope and reduce delivery risk.

## Risks / Trade-offs
- Risk: stale content if refresh worker stalls.
  - Mitigation: freshness timestamps, watchdog logs, and fallback to baseline by toggle-off.
- Risk: metadata/image cleanup removes still-needed assets.
  - Mitigation: reverse-index refcounts + bounded deletion batches.
- Risk: added state complexity in Home pipeline.
  - Mitigation: coordinator ownership, explicit phase state, milestone telemetry.

## Migration Plan
1. Add toggle plumbing and no-op guards around existing refresh points.
2. Add catalog disk cache store and cached-first repository APIs.
3. Add metadata disk cache + language epoch invalidation.
4. Add serialized refresh coordinator + publish gating.
5. Add cleanup/indexing and telemetry counters.
6. Run profiling checklist and compare with baseline.

## Rollback Plan
- Disable `diskFirstHomeStartupEnabled` to restore current startup refresh behavior.
- Keep legacy code paths intact during rollout until parity/regression confidence is reached.

## Open Questions
- Final source of truth for production toggle (local setting only vs account-synced flag).
- Timeout behavior if metadata/image hydration does not complete within bounded SLA.
