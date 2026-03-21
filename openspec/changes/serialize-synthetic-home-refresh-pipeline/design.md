## Context
Addon catalogs already refresh through a serialized coordinator after the startup deferral window. Trakt and MDBList synthetic Home rows were moved to persisted disk-backed state, but Home rendering still depends on fragmented per-source restore and rebuild state inside the Home pipeline. That creates two problems:
- startup pressure can still occur before the serialized refresh window
- Home can publish partial or competing row sets instead of one persisted merged snapshot

The desired architecture is stricter: addon catalogs, Trakt rails, and MDBList rails should all behave like one class of Home feed from a startup and refresh perspective. Home should restore from one merged persisted snapshot in configured order and should never render directly from per-source live refresh state. Per-source caches are only inputs to background snapshot renewal.

## Goals / Non-Goals
- Goals:
  - Restore one persisted merged Home snapshot immediately on startup without rebuilding source rows first
  - Run Trakt, MDBList, and addon renewal only inside the serialized post-startup Home refresh queue
  - Ensure Home reads rendered rows only from the persisted merged Home snapshot, never directly from per-source live or intermediate restore state
  - Ensure per-source metadata/image hydration and persistence occur before the merged Home snapshot is rebuilt and published
  - Preserve existing disk-first behavior where refreshed rows publish only after hydration completes
- Non-Goals:
  - Rework the metadata storage backend away from SharedPreferences in this change
  - Change the visual ordering or content definitions of Trakt/MDBList rails
  - Re-architect Continue Watching into the same queue in this change

## Decisions
- Decision: Home MUST render only from the persisted merged Home snapshot at startup and during refresh
  - Rationale: disk-first Home requires one authoritative rendered source of truth
- Decision: observer-triggered source updates during the startup deferral window MUST not rebuild or publish Home rows
  - Rationale: persisted merged rows are the startup source of truth; renewal belongs to the serialized refresh phase
- Decision: Trakt, MDBList, and addon renewal MUST be represented as sequential units in the same queue
  - Rationale: this prevents one source from publishing partial Home state ahead of the others
- Decision: per-source disk snapshots remain internal refresh inputs until the serialized renewal produces a new merged hydrated Home snapshot and Home reloads from that snapshot
  - Rationale: this matches the intended disk-backed publish model for every Home source type

## Risks / Trade-offs
- Risk: stale synthetic rows may remain visible slightly longer after startup
  - Mitigation: serialized post-startup refresh still renews them automatically after the deferral window
- Risk: changing refresh ordering could affect telemetry assumptions
  - Mitigation: update startup telemetry to distinguish synthetic queued/started/published milestones
- Risk: removing incremental publishes may delay some user-visible updates until the serialized pipeline completes
  - Mitigation: preserve the existing merged snapshot during refresh and publish one coherent refreshed snapshot at pipeline end

## Migration Plan
1. Stop observer-driven or per-source restore paths from publishing Home rows directly.
2. Keep the merged Home snapshot as the only rendered startup source.
3. Add explicit serialized Trakt and MDBList refresh units alongside addon refresh.
4. Rebuild and persist one merged Home snapshot only after serialized source refresh and hydration complete.
5. Update tests and startup telemetry around the new sequencing.
