# Debrid Benchmark Transport Comparison Design

## Context

Nexio already ships a manual debrid provider benchmark that measures one direct provider path and
stores the latest result per provider. That baseline proved useful, but it is not enough for the
next product decision layer. Users need to see more than one throughput number, and Nexio needs
transport metrics that can drive playback-path choices, source ranking, and seek behavior.

The next benchmark phase therefore expands the feature from a single direct-path test into a
session-level comparison between:

- `Direct`
- `Nexio Optimized`

Both transport profiles must be measured against the same resolved provider file during one
benchmark session, then shown side-by-side in the completion modal and persisted as the latest
provider result.

## Goals / Non-Goals

- Goals:
  - Benchmark both the direct provider path and Nexio’s optimized parallel path in one session.
  - Present direct and optimized results side-by-side immediately when the benchmark completes.
  - Collect startup, sustained, and seek metrics that are strong enough to inform future playback
    path and source-selection decisions.
  - Persist a latest-result session record per provider that includes both transport profiles and
    the raw samples needed for later heuristic evolution.
  - Snapshot the optimized transport configuration used during benchmarking so the comparison is
    auditable.
- Non-Goals:
  - Change autoplay or playback behavior in the same phase.
  - Introduce benchmark history beyond the latest session per provider.
  - Run synthetic provider benchmarks against non-user media.
  - Couple the benchmark to full player playback, media parsing, or cache-backed playback flows.

## Decisions

- Decision: Show direct and optimized results side-by-side in the completion modal immediately.
  - Rationale: the user asked for immediate comparison, and hiding the second transport behind an
    advanced step would remove most of the value of running both modes.
- Decision: Benchmark both transports against one resolved candidate in one benchmark session.
  - Rationale: comparing different files or hosts would invalidate the transport comparison.
- Decision: Freeze the current optimized transport configuration at benchmark start.
  - Rationale: the result must reflect the actual user-configured parallel path, not defaults or
    whatever the settings become later.
- Decision: Treat benchmarking as three measurement domains: startup, sustained transfer, and seek.
  - Rationale: throughput alone is insufficient for transport choice, especially on seek-heavy
    playback.
- Decision: Prefer percentile and stability metrics over peaks when deriving future heuristics.
  - Rationale: `p10` sustained throughput and `p95/p99` seek latency better predict user experience
    than average or peak numbers.
- Decision: Implement optimized-path benchmarking with a dedicated benchmark transport harness, not
  by driving the full player stack.
  - Rationale: this keeps benchmark results focused on transport behavior while avoiding player,
    cache, and lifecycle noise.

## Architecture

### Session Model

The benchmark evolves from a single transport run into a benchmark session with:

- shared candidate metadata
- one `DirectTransportResult`
- one `OptimizedTransportResult`
- a comparison summary

The `DebridBenchmarkService` remains the benchmark owner, but now orchestrates a two-transport
session instead of one transport run. The service still owns candidate resolution, latest-result
storage, cancellation, and UI-facing runtime state.

### Transport Layer

The transport abstraction expands to support two concrete implementations:

- `DirectBenchmarkTransport`
- `OptimizedBenchmarkTransport`

The direct transport remains a dedicated single-request discard-stream path.

The optimized transport reuses Nexio’s parallel-range transport behavior through a benchmark-only
harness built around `ParallelRangeDataSource`. It must respect the current player transport
settings, including connection count and chunk size, but it must not reuse playback cache or
warm-ahead state.

### Metrics Collection

Both transports feed a shared `BenchmarkMetricsCollector` responsible for:

- startup timing
- rolling throughput windows
- stall and read-gap tracking
- seek-sample aggregation
- percentile, variance, and coefficient-of-variation summaries

This keeps the result schema consistent across direct and optimized modes.

## Benchmark Flow

### Candidate Resolution

1. Resolve one provider file using the existing benchmark candidate rules.
2. Pin its URL, headers, host, filename, and size metadata for the entire session.
3. Snapshot the current optimized transport config before any transfer starts.

### Per-Mode Phases

Each transport profile runs three phases:

1. `Startup`
   - open from byte `0`
   - measure `initial_ttfb_ms`
2. `Sustained`
   - run the long discard benchmark
   - collect rolling throughput windows and stability metrics
3. `Seek`
   - perform repeated random range seeks against the same file
   - measure seek-TTFB distributions and failure rate

### Fairness Rules

To reduce warm-order bias:

- startup runs `Direct` then `Optimized`
- sustained runs `Optimized` then `Direct`
- seek samples alternate first mover by target

Additional fairness constraints:

- use fresh transport instances per phase
- disable disk cache and persisted bootstrap reuse
- use the same seek target list for both transports
- store execution order in the session result

## Metrics

### Startup Metrics

Per transport:

- `initial_ttfb_ms`
- optional `dns_ms`
- optional `connect_ms`
- optional `tls_ms`
- `startup_fail_rate`

### Sustained Metrics

Per transport:

- `avg_throughput_mbps`
- `p10_throughput_mbps`
- `p50_throughput_mbps`
- `peak_throughput_mbps`
- `throughput_stddev_mbps`
- `throughput_cv`
- `stall_count`
- `max_read_gap_ms`
- `bytes_transferred`
- `elapsed_ms`

`min throughput` is intentionally not promoted as a primary metric because it is too noisy. `p10`
is the preferred lower-bound signal for future decisions.

### Seek Metrics

Per transport:

- `seek_ttfb_p50_ms`
- `seek_ttfb_p95_ms`
- `seek_ttfb_p99_ms`
- `seek_ttfb_stddev_ms`
- `seek_fail_rate`
- optional `post_seek_2s_throughput_mbps`

These metrics are more important than averages when deciding whether a transport is appropriate for
seek-heavy playback.

## Persistence

The latest-result store should evolve from one provider summary into one provider benchmark session:

- `provider`
- `measured_at_ms`
- `candidate`
  - `filename`
  - `size_bytes`
  - `host`
  - `direct_url_fingerprint`
- `session`
  - `benchmark_version`
  - `execution_order`
  - `total_elapsed_ms`
- `direct`
  - `startup`
  - `sustained`
  - `seek`
  - `raw_samples`
- `optimized`
  - `startup`
  - `sustained`
  - `seek`
  - `transport_config_snapshot`
  - `raw_samples`
- `comparison`
  - `sustained_winner`
  - `seek_winner`
  - `stability_winner`

Raw sample arrays are acceptable because only the latest session per provider is stored.

## Completion Modal

The completion modal should open automatically when a benchmark finishes successfully and show two
columns immediately:

- `Direct`
- `Nexio Optimized`

Shared header context:

- provider
- host / CDN
- filename
- size
- measured-at time

Each column shows:

- Initial TTFB
- Average throughput
- P10 throughput
- Peak throughput
- Throughput stddev
- Throughput CV
- Stall count
- Max read gap
- Seek TTFB p50
- Seek TTFB p95
- Seek TTFB p99
- Seek fail rate

The optimized column also shows the config snapshot used for that run, for example
`4 connections • 8 MB chunks`.

The modal should also surface a compact winner summary:

- best sustained
- best seek latency
- most stable

## Future Decision Signals

The stored metrics should later support these derived signals:

- `safe_sustained_budget_mbps`
  - derived primarily from `p10_throughput_mbps`
- `preferred_transport_for_startup`
  - derived primarily from `initial_ttfb_ms`
- `preferred_transport_for_seeking`
  - derived primarily from `seek_ttfb_p95_ms`, `seek_ttfb_p99_ms`, and `seek_fail_rate`
- `transport_stability_score`
  - derived from `throughput_cv`, `stall_count`, and `max_read_gap_ms`
- `seek_confidence`
  - derived from the seek tail metrics plus failure rate

These signals are deliberately more tail- and stability-oriented than mean-oriented.

## Risks / Trade-offs

- Risk: running both transports increases benchmark duration and bandwidth cost.
  - Mitigation: keep the flow manual, show phase/status progress, and allow cancellation.
- Risk: alternating direct and optimized phases still cannot eliminate all CDN warm-state effects.
  - Mitigation: pin the candidate, alternate order by phase, and store execution order explicitly.
- Risk: optimized-path benchmarking may drift from real playback if the benchmark harness diverges
  from `ParallelRangeDataSource`.
  - Mitigation: build the optimized benchmark transport around the same range-fetch engine and keep
    transport instrumentation hooks lightweight and reusable.
- Risk: showing too many metrics could overwhelm casual users.
  - Mitigation: keep the modal structured and comparative, with shared context once and the same
    metric order in both columns.

## Rollout

1. Extend benchmark models and storage to represent a dual-transport session.
2. Add shared metrics collection for startup, sustained, and seek phases.
3. Implement the optimized benchmark transport using the current parallel transport config.
4. Expand the completion modal into side-by-side direct vs optimized results.
5. Keep transport-selection decisions disabled until the new metrics prove trustworthy.
