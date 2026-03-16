# Kodi Native AudioSink LLD Fix Plan (Startup Stability + Media3 Parity)

## Objective
Stabilize passthrough startup without regressing in-stream behavior by converging the Kodi JNI sink to stock Media3 behavior in the three remaining weak domains:
1. Startup write-progress truthfulness/backpressure
2. Transient AudioTrack lifecycle/retry handling
3. Explicit observability to prove root-cause closure

This plan is intentionally incremental and reversible.

## Non-Goals
- No full JNI architecture rewrite.
- No broad changes to in-stream seek/discontinuity clock path (currently stable).
- No unrelated changes outside:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/*`
  - docs updates for this plan.

---

## Confirmed Issues To Fix

## Issue A: Startup underflow despite queued pre-roll
Evidence:
- `Play startup ... totalWrittenFrames=0 ... packedQueue=7`
- `startup refill ... wroteFramesDelta=0`
- immediate crackle/abort or `pause because of UNDERRUN`

Interpretation:
- Startup feed is still not reliably write-progress-driven in the same way stock sink feed behaves around start.
- Renderer/sink truthfulness can drift during paused/deferred-prime window.

## Issue B: Fragile handling of transient output init/write instability
Evidence:
- prior observed init failures and unstable create/recreate windows (`-38` class behavior, AudioFlinger churn).

Interpretation:
- We lack stock-equivalent bounded retry/debounce semantics (`PendingExceptionHolder`-like behavior).

## Issue C: Root cause attribution still ambiguous in some runs
Evidence:
- mix of successful and failing startups with similar pre-roll patterns.

Interpretation:
- Need stronger per-step telemetry to separate “track full”, “write rejected”, “not primed”, and “transient dead-object/recreate” outcomes.

---

## LLD Fixes

## Fix 1: Strict startup truthfulness gate for paused passthrough admission
Target files:
- `KodiActiveAEEngine.cpp` (`WritePassthroughLocked`, queue admission path)

Design:
- While `passthrough_ && !playRequested_`, tighten admission so consumed bytes remain tightly coupled to what can be moved to output at startup.
- Keep deferred-prime safety, but prevent optimistic pre-play acceptance bursts that imply deep buffered playout.
- Admission decision order:
  1. attempt flush (will no-op under deferred-prime)
  2. compute current startup-allowed queued duration ceiling
  3. stop consuming input immediately when ceiling reached
- Ceiling policy:
  - default to **hardware-capacity bounded** ceiling
  - optionally allow small guard margin (config constant) only if proven safe in A/B logs

Applies to issues:
- Fixes **Issue A** by making `handleBuffer` truthfulness closer to stock’s output-driven behavior.
- Reduces renderer sleep mismatch risk at startup.

Success criteria:
- Pre-play accepted duration is bounded and consistent with startup policy.
- Fewer runs where `play()` starts with large packed queue + `totalWrittenFrames=0`.

---

## Fix 2: Unified flush/write convergence to OS write progress (passthrough + PCM)
Target files:
- `KodiActiveAEEngine.cpp`
  - `FlushPackedQueueToHardwareLocked`
  - `WritePcmLocked`
  - `FlushPcmQueueToHardwareLocked`

Design:
- In both passthrough and PCM loops, rely primarily on `WriteNonBlocking` progress as the authoritative limiter, matching stock `DefaultAudioSink`/`AudioTrackAudioOutput` behavior.
- Remove manual `pendingFrames`-based pre-clamp as the primary stop condition in both pipelines.
- Keep safety guards (max loop iterations, packet/chunk boundary handling, partial-write offsets, lock-safe loop bounds).
- Expected behavior:
  - keep writing queued bytes until `WriteNonBlocking` returns <=0 or queue empties
  - preserve written-frame accounting and anchor updates only on actual bytes written
- Rollout strategy:
  - land as one contractual model for both pipelines in a single pass
  - no passthrough/PCM behavior split or feature-flagged divergence

Applies to issues:
- Fixes **Issue A** path where refill logs show `wroteFramesDelta=0` while queue still holds startup payload.
- Reduces false “buffer full” conclusions from lagging head-position estimates.
- Removes architectural split between passthrough and PCM backpressure semantics.

Success criteria:
- In failing-start scenarios, post-play refill more often reports positive write delta.
- Reduction in immediate startup underrun/crackle.
- PCM path remains stable (no increased underrun rate, no CPU-spin regressions, no throughput regressions).

---

## Fix 3: Stock-like bounded retry window for init/write failures
Target files:
- `KodiNativeAudioSink.java`
- JNI bridge/native return conventions if needed (`kodi_cpp_session_bridge.cpp`, engine return/status)

Design:
- Add a small pending-exception holder in Java sink (modeled after stock semantics):
  - retry window: ~200 ms
  - minimum retry delay: ~50 ms
  - recoverable failures retried within deadline before surfacing fatal error
- Distinguish recoverable vs non-recoverable error classes from native layer.
- If current JNI API cannot classify errors, extend native result contract minimally (error code + recoverable flag).

Applies to issues:
- Directly addresses **Issue B** (`-38`/transient churn causing premature fatal abort).

Success criteria:
- Temporary init/write failures no longer immediately kill startup when recovery is possible within retry window.

---

## Fix 4: Startup state machine hardening (explicit phases)
Target files:
- `KodiActiveAEEngine.cpp` (`Play`, startup logging and transitions)

Design:
- Make startup phases explicit and logged:
  1. `PREPARED` (queue present, not started)
  2. `PRIME_ATTEMPTED`
  3. `STARTED`
  4. `POST_START_REFILL`
  5. `RUNNING`
  6. `RECOVERY_RECREATE` (if needed)
- Guarantee ordering invariants:
  - never call `Play()` on a fresh track before a successful prime/write delta
  - rebase timestamp guard only after start succeeds

Applies to issues:
- Stabilizes ambiguous start sequencing behind **Issue A/C**.

Success criteria:
- No contradictory startup logs; every failure path has deterministic phase reason.

---

## Fix 5: Observability pack for root-cause closure
Target files:
- `KodiActiveAEEngine.cpp`
- `KodiAudioTrackOutput.cpp`
- optional Java sink logs for retry windows

Design:
- Add concise structured counters/log fields (startup-only):
  - queued duration at play
  - bytes attempted/written per flush call
  - write return code histogram (`>0`, `0`, `<0`)
  - recovery attempts count
  - retry-holder active/expired states
- Keep logs gated behind existing verbose flags.

Applies to issues:
- Solves **Issue C** by turning hypotheses into measurable outcomes.

Success criteria:
- Can classify every failed startup into one of: no prime progress, transient init/write failure, teardown race, or true upstream starvation.

---

## Implementation Order (Risk-Minimized)
1. **Fix 5** (telemetry) first
2. **Fix 2** (flush/write convergence)
3. **Fix 1** (paused admission tightening)
4. **Fix 4** (startup phase hardening)
5. **Fix 3** (retry holder + JNI error classification)

Why this order:
- We first improve visibility, then apply smallest behavioral deltas in the hottest path, then add retry semantics once failure classes are clear.

---

## Validation Plan

## Test matrix
- Cold start playback (first stream)
- Start after seek
- Multiple sequential stream starts (teardown/recreate stress)
- Pause/resume loops
- Long pre-play gaps (simulate 500ms+ before `play()`)

## Required log checks
- `wroteFramesDelta` after start/refill should be consistently >0 in healthy startups.
- No immediate `pause()+flush()` within ~100ms after `play()` for healthy cases.
- Reduced `UNDERRUN` events at startup.
- Retry-holder logs show recover-and-continue for transient failures, and bounded fail-fast after deadline.

## Regression checks (must stay green)
- Seek no-crash behavior remains intact.
- In-stream A/V sync remains stable.
- Position monotonicity and discontinuity correction remain unchanged.

---

## Rollback Strategy
- Keep one unified runtime behavior across passthrough and PCM.
- If regression appears, revert the offending change set at source level (no runtime behavior forks), while preserving telemetry instrumentation.

---

## Expected Outcome
After all fixes:
- Startup path behaves practically equivalent to stock sink in backpressure truthfulness and transient error resilience.
- Startup crackle/instant-abort class of failures should be eliminated or reduced to reproducible, diagnosable edge cases.
- Current stable seek/in-stream behavior is preserved.
