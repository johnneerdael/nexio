# Change: Add manual debrid provider benchmark collection

## Why

Nexio needs trustworthy, real-world measurement data for Real-Debrid and Premiumize before using
performance signals in source ranking or autoplay. The first step is a manual benchmark flow on the
Debrid integration screen that measures direct provider playback links using real user library items
and stores only the latest local result per provider.

## What Changes

- Add a manual benchmark action for Real-Debrid and Premiumize to the Debrid integration screen.
- Introduce a dedicated benchmark subsystem that resolves a real provider library item, streams
  bytes into a discard sink, and records startup plus sustained-throughput metrics.
- Persist only the latest local benchmark result per provider for phase 1.
- Keep benchmark execution isolated from playback caches, warm-ahead, autoplay, and portal sync.

## Impact

- Affected app: `app`
- Affected settings surface: Debrid integration page
- Affected specs: `debrid-provider-benchmark`
- Affected persistence: new local latest-result benchmark store
- Phase-1 exclusions: no autoplay decisions, no result history, no portal sync
