# Debrid Configuration Benchmark Matrix Design

## Context

Nexio already exposes a manual Debrid provider benchmark that compares the direct provider path with
Nexio's optimized transport path. That benchmark answers "is optimized transport better than
direct?" but it does not answer the user-facing setup question: "which parallel-download settings
work best on my device and network?"

This proposal adds a second benchmark mode for Real-Debrid and Premiumize that is explicitly
informational in phase 1. It runs a fixed matrix of optimized transport configurations against the
same resolved provider file/URL and shows the sustained 30-second average throughput for every
profile so users can compare outcomes themselves.

## Goals / Non-Goals

- Goals:
  - Add a second manual benchmark mode on the Debrid integration screen for Real-Debrid and
    Premiumize.
  - Run a fixed 9-profile optimized transport matrix against one resolved candidate file/URL per
    benchmark session.
  - Measure a 30-second sustained average throughput for each profile.
  - Mark memory-unsafe profiles as `Unsupported` without running them.
  - Show all profile outcomes in a completion UI and highlight the best successful profile.
  - Rank successful profiles by average throughput only.
- Non-Goals:
  - Replace the existing direct-vs-optimized comparison benchmark.
  - Automatically apply the best settings in phase 1.
  - Introduce autoplay/source-selection behavior changes.
  - Resolve a new file between matrix runs.
  - Add new provider support beyond Real-Debrid and Premiumize.

## Benchmark Matrix

The benchmark matrix is fixed for phase 1 and uses Nexio's optimized benchmark transport only.
Each session runs the following profiles in order:

- 2 parallel downloads @ 8 MB chunks
- 3 parallel downloads @ 8 MB chunks
- 4 parallel downloads @ 8 MB chunks
- 2 parallel downloads @ 16 MB chunks
- 3 parallel downloads @ 16 MB chunks
- 4 parallel downloads @ 16 MB chunks
- 2 parallel downloads @ 24 MB chunks
- 3 parallel downloads @ 24 MB chunks
- 4 parallel downloads @ 24 MB chunks

All profiles in one session reuse the same resolved provider candidate metadata and direct URL so
results remain comparable.

## Decisions

- Decision: expose this as a second benchmark mode rather than extending the current comparison
  benchmark.
  - Rationale: the current benchmark serves transport analysis, while the new benchmark serves a
    user configuration decision. Mixing both into one session would make the UI and persistence
    harder to understand.
- Decision: use one candidate file/URL for the whole session.
  - Rationale: configuration results must differ only by transport settings, not by CDN/file churn.
- Decision: rank results by average throughput among successful runs only.
  - Rationale: the user explicitly wants a straightforward informational ranking rather than a
    stability-weighted recommendation.
- Decision: unsupported-by-memory profiles are surfaced as a third state distinct from failed runs.
  - Rationale: users should understand that some profiles were never attempted because they exceed
    safe device memory constraints.
- Decision: show a compact grouped result view with chunk-size groups and subrows for parallelism.
  - Rationale: this preserves TV readability while still fitting nine profiles on screen with a
    top-level "best profile" summary.

## Architecture

### Settings Integration

The Debrid settings row for Real-Debrid and Premiumize gains a second benchmark entry point, for
example `Run config benchmark`, alongside the existing benchmark action/result affordance.

The settings ViewModel continues to own:
- connection state
- benchmark launch/cancel actions
- latest-result dialog state

It should now observe both benchmark services/results separately so the existing comparison modal
and the new configuration-matrix modal remain independent.

### New Benchmark Subsystem

Add a dedicated `DebridConfigBenchmarkService` (or similarly named parallel service) rather than
forcing the existing `DebridBenchmarkService` to multiplex two unrelated session schemas.

The new service owns:
- single-flight execution for configuration matrix runs
- candidate resolution via the existing benchmark candidate resolver
- per-profile memory-safety gating
- sequential execution of matrix profiles against the same candidate
- persistence of the latest configuration-matrix result per provider
- runtime progress updates for the active profile and overall session progress
- cancellation and mutual exclusion with the existing transport-comparison benchmark so only one Debrid benchmark of any kind runs at a time

### Transport Reuse

Reuse the current optimized benchmark transport/runtime instead of introducing a new network path.
Each matrix profile simply injects a different frozen
`DebridBenchmarkTransportConfigSnapshot(parallelConnectionCount, parallelChunkSizeMb)`.

The direct transport is not part of this feature.

### Memory Safety Gate

Before running a profile, estimate its memory pressure from the configured parallelism and chunk
size. If the estimate exceeds the current benchmark-safe budget for the device, mark the profile as
`Unsupported` and continue to the next profile.

Phase 1 should keep the gate deterministic and explainable. The stored result should capture:
- the attempted profile
- final status (`Success`, `Failed`, `Unsupported`)
- average throughput when successful
- failure reason when failed
- unsupported reason when skipped for safety

## Result Model

Persist a separate latest-result record per provider for the configuration matrix benchmark.

Recommended stored shape:
- provider
- measured-at timestamp
- candidate metadata (filename, size, host, URL fingerprint)
- session summary (total elapsed, total profiles, successful profiles, best profile)
- list of nine ordered profile results

Each profile result should include:
- connection count
- chunk size MB
- status: `SUCCESS`, `FAILED`, `UNSUPPORTED`
- average throughput Mbps (success only)
- transferred bytes / elapsed ms (success or partial failure if available)
- termination/failure reason
- unsupported reason
- config snapshot

## Measurement Semantics

Each runnable profile uses:
- optimized transport only
- one sustained measurement window of 30 seconds
- average throughput as the ranking metric

The service should not carry performance state between profiles beyond candidate reuse. Each profile
run should start with a fresh metrics collector and fresh optimized benchmark transport instance so
outcomes remain attributable to the profile under test.

## UI Behavior

### Row State

Each eligible Debrid provider row gains:
- `Run config benchmark`
- `Cancel` while the configuration benchmark is active
- live progress text such as `Testing 3x / 16 MB (5 of 9)`
- latest result summary such as `Best: 4x / 16 MB • 742 Mbps`
- when no profile succeeds, a fallback summary such as `No successful profile` with counts for failed/unsupported rows

### Completion Modal

On successful session completion, open a dedicated configuration benchmark modal.

Layout:
- top summary banner: best successful profile + measured-at timestamp
- grouped sections by chunk size: `8 MB`, `16 MB`, `24 MB`
- within each section, compact rows for `2x`, `3x`, `4x`
- each row shows one of:
  - `742 Mbps` for success
  - `Failed` (optionally with short detail)
  - `Unsupported` / `Skipped: exceeds safe memory`

This preserves readability while using less space than nine independent cards.

If no profile succeeds, the modal should still open when the session completed its matrix pass, but the top
summary switches from `Best profile` to a neutral `No successful profile` summary and the result remains
reopenable for inspection.

## Risks / Trade-offs

- Risk: a direct URL may expire mid-session across nine runs.
  - Mitigation: if provider URLs are too short-lived, fail the affected session clearly rather than silently
    re-resolving another file, because fairness is more important than salvaging the run. Persist and
    reopen the partial result when useful, but do not compute a best-profile banner unless at least one
    profile succeeded.
- Risk: the total session may consume substantial bandwidth/time.
  - Mitigation: keep it manual, visible, and cancellable.
- Risk: memory-safety estimation could be too strict or too permissive.
  - Mitigation: start with a conservative gate and surface unsupported reasons explicitly.
- Risk: users may assume Nexio will apply the best settings automatically.
  - Mitigation: label the phase-1 result as informational and defer auto-apply to phase 2.

## Phase 2 Follow-up

Potential future extension after this informational phase:
- prompt the user to apply the best successful profile automatically
- optionally offer a revert affordance if later playback behavior regresses
