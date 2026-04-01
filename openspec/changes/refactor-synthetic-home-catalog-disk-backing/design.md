## Context
Nexio already has a disk-first Home path for addon catalogs: fetch/refresh, hydrate metadata, prefetch images, persist, then publish. Trakt and MDBList rails bypass that pipeline because they are built from separate discovery snapshots and converted into `CatalogRow`s late in `HomeViewModelCatalogPipeline`.

This creates two problems:
- Startup/render behavior is inconsistent across feed types.
- Synthetic rails do not naturally inherit the same diffing, publish gating, and cache cleanup guarantees as addon catalogs.

## Goals / Non-Goals
- Goals:
  - Treat Trakt and MDBList Home rails as persisted catalog rows, not ephemeral UI synthesis.
  - Preserve disk-first startup semantics for all Home feeds.
  - Reuse existing hydration, diff, and cleanup mechanisms as much as possible.
- Non-Goals:
  - Replacing the upstream Trakt/MDBList discovery services themselves.
  - Changing feed selection/product behavior beyond persistence and publish timing.
  - Reworking Android TV recommendation feeds.

## Decisions
- Decision: introduce a persisted synthetic-catalog store or extend the existing Home catalog persistence layer so Trakt/MDBList rails are materialized as stored `CatalogRow`s before UI assembly.
  - Rationale: the UI should not need to know whether a row came from an addon or an integration.

- Decision: keep discovery refresh separate from Home publish.
  - Rationale: Trakt/MDBList services can still fetch upstream data on their own cadence, but Home should only swap to refreshed rows after the persisted row cache is renewed.

- Decision: unify reference tracking for synthetic and addon rows.
  - Rationale: metadata/image cleanup only works reliably if all Home-visible item references are accounted for in the same cleanup set.

## Risks / Trade-offs
- Risk: duplicate persistence layers if synthetic rows are stored separately from addon catalog snapshots.
  - Mitigation: prefer extending existing Home row persistence structures over introducing a parallel schema unless separation is clearly simpler.

- Risk: synthetic feeds may refresh more often than addon catalogs and cause churn.
  - Mitigation: preserve startup deferral and staged publish rules; diff rows before overwriting persisted state.

## Migration Plan
1. Add persisted storage for synthetic Home rows.
2. Materialize Trakt/MDBList rows into that storage after discovery refresh.
3. Update Home row assembly to consume persisted synthetic rows instead of in-memory synthesis.
4. Merge synthetic row references into metadata/image cleanup.
5. Remove obsolete direct synthetic-row UI assembly paths.

## Open Questions
- Whether synthetic rows should reuse `HomeCatalogSnapshotStore` directly or live in a dedicated persisted row store with the same semantics.
