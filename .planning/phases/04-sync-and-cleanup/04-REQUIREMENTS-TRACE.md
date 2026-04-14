---
phase: 04-sync-and-cleanup
artifact: requirements-trace
source_milestone: pre-v1.1 sync-and-cleanup
active_root_milestone: v1.1 TVDB First-Class TV Metadata
requirements: [SYNC-01, SYNC-02, SYNC-03, SYNC-04]
status: verifier-trace-source
---

# Phase 04 Requirements Trace

## Scope Note

The active root `.planning/REQUIREMENTS.md` and `.planning/ROADMAP.md` now describe the v1.1 TVDB milestone and intentionally start at Phase 6.

This file is the phase-local historical source for completed Phase 04 SYNC requirement traceability.

This file must not be used to roll back or overwrite the active v1.1 root milestone files.

## Requirement Definitions

| Requirement | Definition |
|-------------|------------|
| SYNC-01 | Profile metadata (name, avatar, PIN state) syncs to Supabase. |
| SYNC-02 | Per-profile settings sync via independent blob push/pull, not the shared v7 account contract. |
| SYNC-03 | Profile deletion removes all DataStore files, per-profile SharedPreferences files, and Supabase remote data or records failed remote cleanup for retry. |
| SYNC-04 | Snapshot stores are classified and scoped per-profile where applicable, while shared cache stores remain shared. |

## Decision Coverage

| Decision | Requirement IDs | Coverage |
|----------|-----------------|----------|
| D-01 | SYNC-01, SYNC-02 | Event-driven push behavior, profile-switch sync, and startup pull inform profile metadata and settings sync lifecycle. |
| D-02 | SYNC-01, SYNC-02 | Last-write-wins timestamp conflict strategy applies to profile metadata and settings blob sync. |
| D-03 | SYNC-02 | v8 profile-aware settings contract separates per-profile settings from shared v7 account sync. |
| D-04 | SYNC-03 | Best-effort remote cleanup and local immediate deletion define profile deletion semantics. |
| D-05 | SYNC-03 | Profile deletion clears local Trakt/Simkl tokens without provider revocation. |
| D-06 | SYNC-03 | NexioDialog confirmation protects destructive profile deletion. |
| D-07 | SYNC-04 | Seven SharedPreferences-backed snapshot stores become per-profile. |
| D-08 | SYNC-04 | Five cache/catalog stores remain shared. |
| D-09 | SYNC-04 | Per-profile SharedPreferences stores use the `_p{id}` suffix convention. |
| D-10 | SYNC-01, SYNC-02 | Background sync remains silent except explicit Sync Now feedback. |
| D-11 | SYNC-01, SYNC-02 | Sync Now pushes profile metadata and the active profile settings blob with user feedback. |
| D-12 | SYNC-01, SYNC-02 | Startup pull is silent and allows remote settings to apply after initial UI render. |

## Plan and Evidence Map

| Requirement | Completed Plans | Completed Summaries | Verification Evidence |
|-------------|-----------------|---------------------|-----------------------|
| SYNC-01 | `04-01-PLAN.md`, `04-03-PLAN.md` | `04-01-SUMMARY.md`, `04-03-SUMMARY.md` | Observable truths 1 and 14 verified; ProfileSyncService push/pull, startup pull, and Sync Now push exist. |
| SYNC-02 | `04-02-PLAN.md`, `04-03-PLAN.md`, `04-04-PLAN.md`, `04-05-PLAN.md` | `04-02-SUMMARY.md`, `04-03-SUMMARY.md`, `04-04-SUMMARY.md`, `04-05-SUMMARY.md` | Observable truths 2 through 10 verified; v8 blob uses `layout_settings`, full snapshot imports clear missing data, observer hydrates before push, and active v7 no longer owns layout/catalog-order. |
| SYNC-03 | `04-03-PLAN.md` | `04-03-SUMMARY.md` | Observable truths 11, 12, and 15 verified; deletion removes local stores, retries failed remote cleanup, and uses NexioDialog confirmation. |
| SYNC-04 | `04-01-PLAN.md` | `04-01-SUMMARY.md` | Observable truth 13 verified; seven per-profile SharedPreferences-backed stores resolve names at call time via `_p{id}` while shared stores remain shared. |

## Verifier Lookup Rule

If a Phase 04 verifier needs to resolve SYNC-01, SYNC-02, SYNC-03, or SYNC-04 and the root .planning/REQUIREMENTS.md is on the v1.1 TVDB milestone, use this phase-local trace file as the historical requirement source.
