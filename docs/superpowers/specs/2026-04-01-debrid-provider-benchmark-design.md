# Debrid Provider Benchmark Design

## Context

Nexio now exposes Real-Debrid and Premiumize as first-class integrations and surfaces their direct
playback library entries through the Debrid settings and Library flows. The next product need is
not automatic source selection yet, but trustworthy measurement collection against those providers
using real user media and real provider/CDN paths.

The benchmark must live on the existing Debrid integration page, stay manual in phase 1, and
persist only the latest result per provider. The immediate goal is to prove that the collected
measurements are useful before wiring them into autoplay or recommendation behavior.

## Goals / Non-Goals

- Goals:
  - Add a manual benchmark action for Real-Debrid and Premiumize on the Debrid integration screen.
  - Resolve a real playable provider library item and measure direct-stream performance by reading
    bytes into a discard sink.
  - Collect startup and sustained-throughput metrics that are representative for large debrid
    streams.
  - Persist only the latest benchmark result per provider locally on-device.
  - Keep the benchmark runtime isolated from playback caches, warm-ahead, and autoplay logic.
- Non-Goals:
  - Change stream autoplay or source ranking in this phase.
  - Sync benchmark results to the portal or Supabase.
  - Keep historical benchmark runs.
  - Benchmark TorBox or EasyDebrid in this phase.
  - Run benchmarks automatically in the background or on a schedule.

## Decisions

- Decision: Put benchmark entry points on the existing Debrid integration page.
  - Rationale: the feature is a provider-level capability and belongs next to provider connection
    state, not on the Trakt screen or playback settings.
- Decision: Build a dedicated benchmark subsystem instead of reusing playback controllers directly.
  - Rationale: phase 1 needs a clean, trustworthy measurement path that avoids cache and player
    side-effects.
- Decision: Use a hybrid architecture with a pluggable transport interface but ship only the direct
    discard-stream transport in phase 1.
  - Rationale: this preserves a clean baseline today and keeps room for a future “effective Nexio
    playback path” benchmark without redesigning storage or UI.
- Decision: Source benchmark candidates from the existing debrid library integration path.
  - Rationale: the benchmark must reflect real user media and authenticated provider behavior rather
    than a synthetic or provider-owned test asset.
- Decision: Store only the latest result per provider in a dedicated local store.
  - Rationale: this matches the approved phase-1 scope and keeps the persistence contract small and
    easy to evolve later.
- Decision: Allow only one active benchmark globally at a time.
  - Rationale: concurrent provider benchmarks would distort throughput readings and complicate the
    settings UX for little user value.

## Architecture

### Settings Integration

`DebridSettingsContent` and `DebridSettingsViewModel` remain the UI ownership layer for the
feature. Each eligible provider row gains:

- a manual `Run benchmark` action when connected
- a `Cancel` action while a benchmark is active
- live measurement status text during execution
- a concise summary of the latest stored result after completion

The ViewModel observes provider connection state plus benchmark runtime state and benchmark result
state. It does not own benchmark networking or storage logic.

### Benchmark Subsystem

Create a dedicated subsystem centered on a singleton `DebridBenchmarkService`. The service owns:

- single-flight benchmark coordination
- provider candidate selection
- transport execution
- rolling metric aggregation
- persistence of the latest provider result
- foreground-scoped cancellation behavior

The service exposes:

- current runtime state for the active benchmark
- latest stored result per provider
- `start(provider)` and `cancel()` commands

### Candidate Resolution

The benchmark resolves a real playable provider item from the existing debrid library integration
path. The candidate resolver should prefer:

1. a recent provider library item with a direct playback URL
2. metadata that includes filename and, when available, size and runtime
3. items already known to be playable through the provider integration

If no suitable library item exists, the benchmark fails with an explicit `no_playable_library_item`
reason and the UI shows a clear user-facing status.

### Transport Abstraction

Introduce a benchmark transport interface with one phase-1 implementation:

- `DirectDiscardTransport`

This transport opens the authenticated direct playback URL, reads bytes continuously into a discard
sink, and emits periodic measurement samples. It intentionally does not use:

- SimpleCache
- VOD warm-ahead
- cached stream-link reuse
- parallel playback-path optimizations

This keeps the first benchmark mode a clean provider/CDN measurement. A future transport can be
added later to measure the effective Nexio playback path while reusing the same service, state, and
storage contracts.

## Measurement Flow

### Lifecycle

1. User selects `Run benchmark` on a connected Real-Debrid or Premiumize row.
2. The settings ViewModel requests `DebridBenchmarkService.start(provider)`.
3. The service enters `resolving_candidate`, then `connecting`, then `measuring`.
4. The transport reads bytes and publishes rolling stats to the ViewModel.
5. The service either:
   - completes successfully and persists the latest provider result
   - fails with a typed reason
   - is cancelled by the user
6. The row state updates in place and remains visible after the run.

### Completion Policy

Phase 1 uses a sustained-window completion policy:

- minimum sample size: `500 MB`
- minimum sustained window: `120s`
- normal success condition: both thresholds satisfied
- hard stop: time-based safety timeout, for example `5 min`

If `500 MB` is reached before `120s`, the benchmark continues. Success requires both minimum
thresholds. A byte-based safety limit is not part of the normal completion path.

### Collected Metrics

Each successful or failed run captures:

- provider
- selected file metadata: filename, optional source file size, optional runtime
- request start timestamp
- response host / final resolved host
- time to first byte
- total bytes read
- total elapsed time
- rolling throughput windows, such as 1-second and 5-second windows
- sustained throughput summary: average, p10, p25, peak
- stall / long-gap count
- early-versus-late throughput decay
- termination reason
- measured-at timestamp

Store a derived summary alongside the raw measurement fields so phase 2 can consume stable result
fields without recomputing from raw samples.

## Persistence

Add a dedicated local benchmark result store keyed by provider. The store contains exactly one
latest result for `REAL_DEBRID` and one latest result for `PREMIUMIZE`.

Each stored result includes:

- provider
- result status
- termination reason
- measured-at timestamp
- filename
- host
- bytes read
- elapsed ms
- ttfb ms
- sustained throughput bps
- p10 throughput bps
- p25 throughput bps
- average throughput bps
- peak throughput bps
- stall count
- confidence
- optional source metadata used for the run

The store is intentionally separate from provider credential stores so benchmark data can evolve,
invalidate, or migrate independently.

## Runtime State Model

Use explicit typed states:

- `idle`
- `resolving_candidate`
- `connecting`
- `measuring`
- `completed`
- `failed`
- `cancelled`

Use explicit failure / termination reasons:

- `provider_not_connected`
- `no_playable_library_item`
- `auth_failed`
- `network_error`
- `http_error`
- `timeout`
- `cancelled`
- `truncated_insufficient_window`

## UI Behavior

Each provider row shows benchmark state inline:

- idle: `Run benchmark`
- running: live text such as `Measuring... 742 MB • 01:53 • 84 Mbps`
- completed: `Latest: 72 Mbps sustained • 380 ms TTFB`
- failed: concise failure summary plus retry affordance

Only the active provider row shows live progress. Other rows stay idle and cannot start a second
benchmark until the active one completes or is cancelled.

## Risks / Trade-offs

- Risk: provider library item quality varies, so one run may not represent every debrid link.
  - Mitigation: store filename and host with the result and keep the transport abstraction open for
    richer future benchmarking modes.
- Risk: using a dedicated benchmark transport differs from Nexio’s eventual playback path.
  - Mitigation: make transport pluggable so a playback-path transport can be added later without
    changing UI or storage contracts.
- Risk: long-running measurements consume noticeable bandwidth.
  - Mitigation: keep the feature manual, show live status, and support cancellation at all times.
- Risk: running in background would consume bandwidth without active user intent.
  - Mitigation: cancel active benchmarks when the app backgrounds in phase 1.

## Migration / Rollout

1. Add benchmark models, runtime state, and latest-result persistence.
2. Add provider candidate resolution from the existing debrid library path.
3. Add direct discard-stream transport and rolling metric aggregation.
4. Integrate runtime state and latest-result summaries into the Debrid settings screen.
5. Keep the feature manual and local-only while evaluating result quality.

## Rollback Plan

- Remove the benchmark controls from the Debrid integration screen.
- Leave provider auth and debrid library flows unchanged.
- Remove the dedicated benchmark store and service without touching playback or Service Wrap code.

## Open Questions

- Whether phase 2 should benchmark the raw provider path only, the effective Nexio playback path,
  or offer both as comparable benchmark modes.
