## Context
The existing passthrough validator is transport-scoped: bundled sample selection, burst capture,
golden comparison, and diagnostics export. It intentionally does not score runtime playback quality.
That separation is correct, but insufficient for codec bring-up because transport success does not
guarantee startup stability, audio lock, smooth video, or steady route behavior.

The existing validator already has:
- debug UI and ADB command control
- session-scoped export bundles
- transport verdicts and failure codes
- limited route/config snapshots

The proposed addition must preserve that model while adding an independent runtime verdict.

## Goals / Non-Goals
- Goals:
  - Add runtime-quality observation to bundled validation runs
  - Keep transport and runtime verdicts separate
  - Reuse Media3 listener and playback stats surfaces already intended for playback analytics
  - Export machine-readable runtime results suitable for ADB-driven automation
- Non-Goals:
  - Do not infer AVR decode success from front-panel labels
  - Do not replace or weaken the existing transport validator
  - Do not add production-build telemetry for this feature

## Decisions
- Decision: Extend the existing `passthrough-transport-validation` capability instead of creating a
  separate spec.
  - Rationale: The runtime layer is part of the same validation workflow and export bundle, but it
    produces a second verdict rather than changing transport semantics.

- Decision: Use Media3 `Player.Listener`, `AnalyticsListener`, and `PlaybackStatsListener` as the
  primary runtime signal sources.
  - Rationale: These are the official surfaces for playback state transitions, dropped frames, audio
    underruns, playback errors, and aggregated buffering/playback durations.

- Decision: Runtime verdicts are independent from transport verdicts.
  - Rationale: A session can legitimately produce `transport=PASS` and `runtime=FAIL`.

- Decision: Keep the first implementation bounded to session-level metrics and exported event
  records.
  - Rationale: This covers the current debugging gap without overreaching into speculative quality
    heuristics.

## Runtime Model
For each validation run, the runtime collector should observe:
- prepare-to-ready timing
- prepare-to-first-isPlaying timing
- prepare-to-first-rendered-frame timing
- playback state changes
- `isPlaying` changes
- player errors
- dropped video frames
- audio underruns
- playback stats summary for buffering and stable-playing durations

The runtime collector should produce:
- runtime summary metrics
- ordered player event records
- ordered analytics event records
- runtime verdict
- runtime failure codes

All runtime thresholds in the first implementation should be centralized configuration values rather
than hardcoded truths. This includes startup timeout, observation-window duration, dropped-frame
limits, and position-stall thresholds. Those configured values should be exported with the runtime
summary so a diagnostics bundle shows which thresholds produced the runtime verdict.

## Verdict Model
Transport and runtime must be exported separately:
- `transportVerdict`
- `runtimeVerdict`

Initial runtime verdict set:
- `RUNTIME_PASS`
- `RUNTIME_DEGRADED`
- `RUNTIME_FAIL`
- `RUNTIME_UNKNOWN`

Initial runtime failure codes:
- `STARTUP_TIMEOUT`
- `READY_BUFFERING_OSCILLATION`
- `AUDIO_UNDERRUN`
- `DROPPED_VIDEO_FRAMES_HIGH`
- `POSITION_STALLED`
- `ROUTE_REPATCH_AFTER_START`
- `PLAYER_ERROR`

Initial `POSITION_STALLED` rule:
- playback position fails to advance by at least a configured minimum delta while `isPlaying=true`
  over a configured observation window

## Risks / Trade-offs
- Runtime signals are noisier than burst comparisons.
  - Mitigation: keep machine-readable summaries and exported raw event timelines so thresholds can be
    tuned later without losing evidence.

- Some route churn indicators come from system logs rather than app callbacks.
  - Mitigation: phase the first implementation around app-owned Media3 signals and route snapshots,
    with optional filtered system-log correlation exposed as a debug setting later.

## Migration Plan
1. Add runtime settings, listener collector, and export model
2. Attach runtime collection to validation playback sessions
3. Extend ADB/UI controls and docs
4. Keep existing transport exports and verdict semantics unchanged
