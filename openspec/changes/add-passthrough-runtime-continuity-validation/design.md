## Context
The existing passthrough validator now has two truths:
- transport truth: burst capture and golden comparison
- runtime truth: Media3 listener/statistics-based playback-quality scoring

That split is correct, but incomplete. The current runtime layer is strongest at player-state
quality:
- `STATE_BUFFERING` / `STATE_READY`
- `isPlaying`
- dropped frames
- audio underruns when surfaced through Media3
- route snapshots

Recent TrueHD work on `192.168.50.37` exposed the remaining gap:
- transport can pass
- player-state runtime can look mostly healthy
- but audio can still be choppy, AVR lock can be weak, and the custom sink can show restart/no-
  progress behavior that never becomes a clean Media3 rebuffer

The current validator architecture already has the right extension points:
- transport capture and export in `TransportValidationSessionStore`
- app-side runtime collection in `TransportValidationRuntimeCollector`
- sink-side runtime events merged through `TransportValidationSessionStore`
- ADB control through `TransportValidationReceiver`

This change should extend that architecture instead of replacing it.

## Goals / Non-Goals
- Goals:
  - Add sink continuity and route stability truth alongside existing Media3 runtime truth
  - Catch choppy audio, weak AVR lock, micro-restarts, zero-write streaks, and playback-head stalls
    that do not necessarily become `BUFFERING`
  - Allow operator-reported AVR lock and audible quality to influence the final runtime verdict
  - Keep transport integrity and runtime continuity as separate but correlated truths
  - Preserve the current bundle-based ADB workflow
- Non-Goals:
  - Do not infer AVR front-panel decode state generically from Android APIs alone
  - Do not weaken the current transport validator or merge transport and runtime semantics
  - Do not add production telemetry or non-debug reporting
  - Do not require generic system-log parsing for the first implementation when app-owned counters
    can provide the same signal

## Decisions
- Decision: Extend the existing `passthrough-transport-validation` capability instead of creating a
  separate validation product.
  - Rationale: continuity, route stability, and operator observation are part of the same debug
    validation session and diagnostics bundle.

- Decision: Keep the current runtime layer and add a second runtime-quality layer rather than
  replacing the current Media3 collector.
  - Rationale: player-state quality and sink continuity answer different questions. Both are useful.

- Decision: Represent runtime quality with sub-verdicts that roll up into the existing
  `runtimeVerdict`.
  - Rationale: a run can have healthy player-state progression but degraded sink continuity or
    operator-reported audio quality. Those truths should remain visible.

- Decision: Collect sink continuity at the custom sink/native output boundary.
  - Rationale: this is the only layer that can reliably see repeated zero writes, partial writes,
    stuck remainders, playback-head stalls, and restart churn even when Media3 remains in `READY`.

- Decision: Add operator observation as a structured debug input.
  - Rationale: AVR lock quality and audible choppiness are partly external truths and should be able
    to downgrade the runtime verdict explicitly instead of being relegated to ad hoc notes.

## Runtime Model
The runtime model should now have four sub-verdict inputs:
- `playerStateVerdict`
- `sinkContinuityVerdict`
- `routeStabilityVerdict`
- `operatorObservationVerdict`

The exported top-level `runtimeVerdict` should remain:
- `RUNTIME_PASS`
- `RUNTIME_DEGRADED`
- `RUNTIME_FAIL`
- `RUNTIME_UNKNOWN`

Roll-up rules:
- `RUNTIME_FAIL` for hard startup or playback failures, such as startup timeout, player error,
  sustained playback-head stall, or repeated route/control-plane churn that prevents stable
  playback
- `RUNTIME_DEGRADED` when transport is correct and playback starts, but continuity, route, or
  operator observation shows weak quality
- `RUNTIME_PASS` only when the current player-state layer is healthy and all new sub-verdicts are
  healthy
- `RUNTIME_UNKNOWN` when evidence is incomplete, such as missing playback stats and insufficient
  continuity samples

The first implementation should define one shared runtime threshold/config object for all runtime
layers. That shared config should include, at minimum:
- startup timeout
- observation-window duration
- stable-start delay
- route-stability scoring start
- late end-of-run exclusion window
- playback-head stall window
- minimum playback-head advance
- zero-write streak threshold
- stuck-remainder duration threshold
- repeated underrun/restart thresholds

That shared config should be exported with every runtime diagnostics bundle so the scoring inputs
for a specific run are always visible in the exported evidence.

## Continuity Signals
The sink continuity layer should continuously record while playback is expected to be active:
- `AudioTrack.write()` attempts
- requested bytes and written bytes
- zero-write count
- partial-write count
- longest consecutive zero-write streak
- time since last successful write
- time since last playback-head advance
- playback-head frame delta samples every configured interval
- audio timestamp availability and discontinuity count
- restart count
- underrun count
- output queue depth in packets, bytes, and duration
- pending remainder bytes and how long they remain stuck

These signals should produce machine-readable failure codes including:
- `AUDIO_WRITE_ZERO_STREAK`
- `PLAYBACK_HEAD_STALLED`
- `POSITION_STALLED_WHILE_PLAYING`
- `AUDIO_TIMESTAMP_DISCONTINUITY`
- `PARTIAL_WRITE_REMAINDER_STUCK`
- `AUDIO_UNDERRUN_REPEATED`
- `OUTPUT_RESTART_CHURN`

## Route Stability Signals
The route layer should continuously record after the first stable start:
- current encoding
- sample rate
- channel mask
- direct playback support result
- route/device identifier when available
- route change count after stable start
- output reopen / recreate / reset events when known to the app-owned sink path

These signals should produce machine-readable failure codes including:
- `ROUTE_REOPEN_AFTER_START`
- `ROUTE_RECLASSIFIED_AFTER_START`

Optional filtered system-log correlation can be added later, but the first implementation should
prefer app-owned counters and structured sink events.

## Scoring Windows
The first implementation should define explicit steady-state scoring windows instead of relying on
implicit timing:
- `stableStartAt`: when startup is considered complete and steady-state continuity scoring begins
- `routeScoringStartAt`: when route-stability scoring begins after startup settles
- `lateNoiseExclusionWindowMs`: how much late end-of-run noise is excluded from default scoring

By default:
- continuity and route-stability failure scoring should begin only after `stableStartAt`
- route-stability scoring should begin at `routeScoringStartAt`
- late end-of-run noise after playback is otherwise healthy should be excluded by default when it
  occurs inside `lateNoiseExclusionWindowMs`

Raw events should still be exported even when excluded from scoring.

## Playback-Head Health
The runtime collector should sample playback-head health every configurable `100-250ms` while
`isPlaying=true` and playback is expected to be active. From those samples it should compute:
- whether playback head advanced
- longest playback-head stall duration
- whether sink position moved backward
- whether sink position remained monotonic
- whether sink position drifted materially from expected wall-clock progress

The first implementation should use explicit thresholds, not vague heuristics. Those thresholds must
be centralized configuration values and exported with the runtime summary.

## Operator Observation Model
The validator command surface should accept structured operator observations such as:
- AVR lock: `good | weak | none`
- audio quality: `clean | choppy | dropouts`
- optional note

These values should be stored in the session, exported as `operator-observation.json`, and allowed
to downgrade runtime scoring through failure codes such as:
- `WEAK_AVR_LOCK_REPORTED`
- `CHOPPY_AUDIO_REPORTED`

Operator input should never upgrade a failing technical run to pass. It can only preserve or
downgrade the runtime verdict.

## Export Model
In addition to the current runtime files, the bundle should add:
- `sink-health.json`
- `route-health.json`
- `playback-head-health.json`
- `operator-observation.json`

`runtime-summary.json` should also include:
- sub-verdicts for player-state, sink continuity, route stability, and operator observation
- continuity metrics such as max zero-write streak, longest playback-head stall, underrun count,
  restart count, and stuck remainder duration
- route-stability metrics such as post-start route change count and route tuple changes
- operator observation fields

## Risks / Trade-offs
- The validator may become too eager to fail on late end-of-run noise.
  - Mitigation: score continuity and route metrics within the configured observation window by
    default, and export raw late-session events separately.

- Native/output instrumentation can drift from the actual sink contract if it mutates behavior.
  - Mitigation: keep the collector observational only and continue to treat stock Media3 sink
    semantics as the external contract reference.

- Operator observations can introduce subjectivity.
  - Mitigation: keep them structured, additive, and clearly separated from app-observed metrics.

## Migration Plan
1. Add continuity, route, playback-head, and operator data models plus export files
2. Extend native/output and Java sink layers with observational counters only
3. Extend the runtime collector and verdict calculator with sub-verdicts and new failure codes
4. Add ADB control surface for operator observations
5. Update docs and validator skill guidance so runtime troubleshooting distinguishes:
   - transport truth
   - player-state truth
   - sink continuity truth
   - route stability truth
   - operator truth
