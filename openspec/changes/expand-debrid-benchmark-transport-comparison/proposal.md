# Change: Expand debrid benchmark into direct vs optimized transport comparison

## Why

The current debrid provider benchmark proves that Nexio can measure one direct provider path, but
that result is too narrow for future playback-path and seek decisions. Nexio needs richer benchmark
output that compares the raw provider path against the Nexio optimized parallel path using the same
provider file and exposes more than one throughput number.

## What Changes

- Expand the provider benchmark into a session that measures both `Direct` and `Nexio Optimized`
  transports against the same resolved provider file.
- Capture startup, sustained, and seek metrics, including percentile and stability summaries, for
  both transport profiles.
- Persist the latest provider benchmark as a dual-transport session record that includes the
  optimized transport config snapshot and raw samples.
- Replace the simple completion feedback with a side-by-side results modal that immediately shows
  direct and optimized metrics together.
- Keep benchmark runs non-idle so the app does not surface the screensaver during benchmarking.

## Impact

- Affected app: `app`
- Affected settings surface: Debrid integration page benchmark results flow
- Affected benchmark capability: `debrid-provider-benchmark`
- Affected persistence: latest benchmark session schema per provider
- Affected transport layer: benchmark-only optimized transport harness based on current parallel
  transport config
