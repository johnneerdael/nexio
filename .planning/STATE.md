# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-14)

**Core value:** Reliable, high-quality streaming playback with smart source selection and seamless library tracking across debrid providers.
**Current focus:** Phase 1 — Foundation

## Current Position

Phase: 1 of 5 (Foundation)
Plan: 0 of ? in current phase
Status: Ready to plan
Last activity: 2026-04-14 — Roadmap created for v1.0 Multi-Profile Support

Progress: [░░░░░░░░░░] 0%

## Performance Metrics

**Velocity:**
- Total plans completed: 0
- Average duration: —
- Total execution time: —

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| - | - | - | - |

**Recent Trend:**
- Last 5 plans: —
- Trend: —

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- Foundation: Adopt NuvioTV ProfileDataStoreFactory pattern (ConcurrentHashMap + lazy init, Profile 1 uses bare filenames for zero-migration)
- Foundation: Max 4 profiles — matches NuvioTV, keeps DataStore file count manageable
- Foundation: Photo upload via nexio-web only — TV remote input not suited for file pickers
- Foundation: Use Gson (not Moshi) for ProfileDataStore JSON serialization — Nexio already uses Gson elsewhere

### Pending Todos

None yet.

### Blockers/Concerns

- Phase 2: Audit all 27+ DataStore files to classify each as shared vs per-profile before migrating
- Phase 3: D-pad focus management and PIN entry UX need prototype testing with physical remote
- Phase 4: Supabase schema design — RPC signatures and table structures need confirmation before implementation
- Phase 4: SharedPreferences audit needed — TraktLibrarySnapshotStore, ContinueWatchingSnapshotStore, HomeCatalogSnapshotStore need classification

## Session Continuity

Last session: 2026-04-14
Stopped at: Roadmap created. STATE.md initialized. Ready to plan Phase 1.
Resume file: None
