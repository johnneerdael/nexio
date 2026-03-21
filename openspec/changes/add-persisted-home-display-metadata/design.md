## Context
Nexio already has disk-backed catalog snapshots and language-aware metadata disk caching, but Home still restores and publishes raw rows that are only partially hydrated for display. Focus-time enrichment masks the issue for some feeds, but it creates inconsistent UX and leaves Continue Watching materially behind. The desired architecture is that cached Home feeds are enriched for display before they are persisted and restored.

## Goals / Non-Goals
- Goals:
  - Make all Modern Home rails restore with display-ready metadata from disk.
  - Put normal catalogs and Continue Watching on a shared display metadata path.
  - Keep locale correctness for persisted metadata.
  - Prevent stale metadata storage growth when Home feeds rotate.
- Non-Goals:
  - Replace every metadata cache in the app with a new storage layer.
  - Guarantee full metadata coverage when upstream providers provide nothing.
  - Redesign the Modern Home hero/card layout in this change.

## Decisions
- Decision: Persist enriched Home-display metadata before snapshot publish.
  - Rationale: Home should render deterministically from disk without focus-time repair.
- Decision: Introduce a shared Home display metadata model used by catalog items and Continue Watching items.
  - Rationale: current split models are the main reason metadata parity is inconsistent.
- Decision: Keep focus-time enrichment only as fallback repair.
  - Rationale: protects UX if a persisted entry is missing or stale without making focus the primary hydration trigger.
- Decision: Track Home metadata references by snapshot/feed membership and evict unreferenced entries in bounded batches.
  - Rationale: prevents storage growth when feed membership changes over time.
- Decision: Tie persisted metadata invalidation to existing language epoch/version changes.
  - Rationale: avoids serving wrong-locale metadata after app language changes.

## Alternatives Considered
- Continue relying on source `MetaPreview` plus focus-time enrichment.
  - Rejected because this is exactly what causes inconsistent rails today.
- Enrich only the currently selected/focused Home item and cache it.
  - Rejected because cold-start and non-focused rails would still be inconsistent.
- Store all Home display metadata only inside full Home snapshots with no shared keyed cache.
  - Partially viable, but risks duplication and makes eviction/reuse harder; final storage shape remains an implementation choice.

## Risks / Trade-offs
- Risk: publish latency rises if every changed item must hydrate before snapshot write.
  - Mitigation: hydrate only changed/new items, reuse persisted metadata aggressively, and allow bounded fallback to previous persisted metadata.
- Risk: cleanup deletes metadata that is still needed by another Home feed source.
  - Mitigation: use keyed reference tracking across all persisted Home feed memberships before eviction.
- Risk: Continue Watching refresh churn causes repeated snapshot rewrites.
  - Mitigation: debounce snapshot persistence and update only changed items.

## Migration Plan
1. Define the shared Home display metadata model and snapshot shape.
2. Fold catalog and Continue Watching item building onto that shared metadata model.
3. Update refresh/publish flow so enrichment runs before persisted snapshot publish.
4. Add reverse index/reference tracking and bounded cleanup for unreferenced metadata.
5. Keep focus-time enrichment as a fallback and trim duplicated UI-side metadata assembly.
6. Validate startup restore, locale invalidation, and dropped-feed eviction behavior.

## Rollback Plan
- Fall back to existing raw snapshot + focus-time enrichment behavior if persisted display hydration causes regressions.
- Keep legacy snapshot read compatibility during rollout so old caches do not brick Home restore.

## Open Questions
- Whether to version-bump `HomeCatalogSnapshotStore` and `ContinueWatchingSnapshotStore` together or add backward-compatible optional fields first.
- Whether image/logo existence checks should be part of the same publish gate or stay owned by the existing image hydration coordinator.
