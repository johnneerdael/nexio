# Change: Add debrid configuration benchmark matrix

## Why

The current debrid benchmark compares direct and optimized transports, but it does not answer the
user setup question of which parallel-download settings work best on a specific device and network.
Users need a benchmark mode that evaluates a fixed matrix of optimized transport profiles and shows
all profile outcomes clearly.

## What Changes

- Add a second Debrid settings benchmark mode for Real-Debrid and Premiumize dedicated to
  configuration benchmarking.
- Run nine optimized transport profiles (2/3/4 connections at 8/16/24 MB chunks) against the same
  resolved file/URL in one session.
- Measure 30-second sustained average throughput per runnable profile.
- Mark memory-unsafe profiles as `Unsupported` without running them.
- Persist the latest configuration benchmark result per provider and present grouped completion
  results with a highlighted best successful profile when one exists.
- Keep the matrix benchmark cancellable and mutually exclusive with the existing transport-comparison
  benchmark.
- Fail clearly if the resolved provider URL expires mid-session instead of silently re-resolving a
  different file.

## Impact

- Affected app: `app`
- Affected settings surface: Debrid integration benchmark actions/results
- Affected benchmark capability: `debrid-provider-benchmark`
- Affected persistence: additional latest-result schema per provider for config benchmarks
- Affected transport layer: optimized benchmark transport reuse for sustained-only profile runs
