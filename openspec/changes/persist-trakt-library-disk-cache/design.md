## Context

The Library screen is currently inconsistent with the newer disk-first Home architecture. Trakt
watchlist and personal lists are restored only from in-memory state, and Library observers trigger
live refresh on start. That causes avoidable latency every time the user re-enters Library.

At the same time, the debrid readable-list layout duplicates the same title across multiple text
rows and is taller than necessary.

## Goals / Non-Goals

- Goals:
  - Restore Trakt watchlist and personal lists from persisted disk state before live refresh.
  - Allow the blocking loading screen only until the first Trakt library snapshot exists.
  - Keep warm-cache refreshes and optimistic mutations publishing through persisted snapshot state.
  - Compact debrid readable rows to one useful title line.
- Non-Goals:
  - Put Real-Debrid, Premiumize, or TorBox fetching on a disk-backed restore path.
  - Redesign Library filters, poster grid layout, or navigation model.
  - Generalize Home and Library persistence into one shared abstraction in this phase.

## Decisions

- Decision: Add a dedicated `TraktLibrarySnapshotStore` owned by `TraktLibraryService`.
  - Rationale: the Trakt library service already owns the Trakt snapshot shape, membership
    mutation, and metadata hydration rules.
- Decision: Remove `ensureFresh()` from Library observer startup for Trakt-backed flows.
  - Rationale: opening Library should render from restored snapshot state, not trigger a blocking
    live fetch.
- Decision: Treat the first authenticated uncached Trakt sync as the only blocking Library load.
  - Rationale: this preserves a clear first-run UX while guaranteeing that later opens are fast.
- Decision: Persist optimistic Trakt snapshot mutations immediately and roll back memory plus disk
  together if the network mutation fails.
  - Rationale: keeps restored state aligned with the currently visible Library UI.
- Decision: Keep debrid data flow unchanged and limit that part of the change to row presentation.
  - Rationale: the user explicitly scoped disk-backed behavior to Trakt lists only.

## Alternatives Considered

- Add a read-through cache in `LibraryRepositoryImpl` while leaving observer-driven refresh in
  `TraktLibraryService`.
  - Rejected because it preserves the wrong ownership boundary and keeps live fetch behavior
    coupled to observer startup.
- Generalize Home discovery persistence into a shared cache layer for Library and Home.
  - Rejected for this phase because it broadens the change into infrastructure work without being
    necessary to fix Library latency.

## Risks / Trade-offs

- Risk: persisted Trakt library payload grows too large once hydrated metadata is included.
  - Mitigation: persist only the fields already needed for restored Library rendering and keep the
    snapshot defensive/replaceable.
- Risk: optimistic mutation persistence could leave disk state ahead of the server after failures.
  - Mitigation: reuse the current rollback path and make it rewrite the previous persisted snapshot
    as part of the same failure handling path.
- Risk: warm-cache refresh bugs could regress the screen to empty.
  - Mitigation: keep the last good snapshot visible until a replacement snapshot is ready.

## Migration Plan

1. Define the persisted Trakt library snapshot and metadata store.
2. Restore persisted snapshot state during `TraktLibraryService` startup.
3. Remove observer-triggered refresh from Trakt Library flows and keep explicit refresh paths.
4. Persist successful refresh results plus optimistic mutations.
5. Update Library loading state so only the first uncached Trakt sync blocks.
6. Compact debrid readable rows and validate focused behavior.

## Rollback Plan

- Disable the restore-first path by reverting `TraktLibraryService` to its current observer-driven
  live fetch behavior.
- Leave the snapshot store isolated so rollback does not require wider Home or repository changes.

## Open Questions

- Whether the persisted Trakt library snapshot should version its metadata payload up front or start
  with backward-compatible optional fields first.
