# TrueHD Late-Stream Audio Quality Audit

Date: 2026-03-22
Worktree: `codex/truehd-audio-quality-parity`
Scope: read-only late-stream audit after Media3-first incremental handoff Step 3

## Goal

Re-ground the remaining TrueHD defect using the active code and the latest valid runtime bundle, without proposing or applying a fix.

This audit is intentionally limited to the late-stream audio-quality failure shape:

- transport already passes
- route tuple is already stable after startup
- player state already reaches `ENDED`
- playback head stays monotonic

The question here is only: what still makes the late stream go choppy and eventually drag video quality down?

## Grounding

### Active implementation under audit

- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`

### Reference implementations

Primary behavioral guardrail:

- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java`

Secondary low-level sink comparison:

- `media/xbmc/xbmc/cores/AudioEngine/Sinks/AESinkAUDIOTRACK.cpp`
- `media/xbmc/xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAESink.cpp`

### Runtime artifacts

Primary artifact under audit:

- `/tmp/transport-validation-truehd-1774140016993.zip`
- unpacked at `/tmp/tv1774140016993`

Comparison artifacts:

- `/tmp/transport-validation-truehd-1774139208103.zip`
- `/tmp/transport-validation-truehd-1774137621593.zip`

## Bundle Truth

From `/tmp/tv1774140016993`:

- `transportVerdict=PASS`
- `comparisonResultCount=72`
- `runtimeVerdict=DEGRADED`
- `playerStateVerdict=PASS`
- `continuousPlayingWindowSatisfied=true`
- `routeTupleChangeCountAfterStableStart=0`
- `routeReopenCountAfterStart=0`
- `audioUnderrunCount=1`
- `droppedVideoFrames=44`
- `timeToReadyMs=876`
- playback reaches `ENDED` at `63683ms`

Playback-head health is not the defect boundary in this run:

- `sampleCount=4231`
- `monotonic=true`
- `backwardJumpCount=0`
- `longestStallMs=89`
- `stalledWhilePlaying=false`

So the current late-stream failure is not transport, not route churn, and not a position-estimator collapse.

## Late-Stream Failure Shape

The dominant bad steady-state write pattern in `/tmp/tv1774140016993/sink-health.json` is:

- `zero -> zero -> zero -> success`

Packet-sequence counts:

- `success-only`: `460`
- `zero-zero-zero-success`: `368`
- `zero-zero-success`: `50`

Representative steady-state remainder sizes for the repeated-zero packets:

- `44672`
- `24192`
- `48768`
- `52864`
- `40576`
- `3712`
- `32384`
- `7808`

Observed wait from first zero to later success:

- `zero-zero-success` median: about `28ms`
- `zero-zero-zero-success` median: about `37ms`
- worst observed sequences in this run: about `48ms`

This is the core current defect shape:

- same route tuple
- same steady-state path
- same steady-state native ownership
- repeated zero writes on the same remainder
- then eventual success tens of milliseconds later
- then late underrun

## What The Active Code Is Doing

### Java side

`KodiTrueHdNativeAudioSink.handleBuffer(...)` chooses between startup and steady-state paths.

Important current state:

- startup handoff is still custom
- but the late-stream bad events are no longer explained by startup crossover

Early crossover still exists in the run, but the actual late failure window is already fully steady-state:

- `selectedPath=steady_state_path`
- `nativeRemainderOwnership=steady_state`

So the late-stream defect is not currently blocked on more startup-path analysis.

### Native side

The active late-stream logic is in:

- `ShouldRetrySteadyStatePendingPackedRemainderLocked(...)`
- `FlushTrueHdPackedQueueToHardwareLocked(...)`

Current steady-state behavior:

1. A pending packed remainder with `writeOffset > 0` is treated as an explicit retry episode.
2. On a repeated zero write, the engine sets `nextEligibleRetryTimeUs_` using `ComputeSteadyStateRetryBackoffUsLocked(...)`.
3. That backoff is packet-duration-shaped, but clamped to `4000..20000 us`.
4. The engine then revisits the same remainder again on later flushes.

The current run shows this clearly in `retryReasonCounts`:

- `steady_state_output_driven=878`
- `steady_state_packet_duration_backoff=1207`

That means the branch is no longer failing on the old `forced_retry` behavior.
The remaining problem is that the bounded retry machine is still active very frequently and still produces repeated zero-write episodes late in the stream.

## Media3 Comparison

Stock Media3 behavior is materially simpler:

- `DefaultAudioSink.drainOutputBuffer(...)` keeps one pending encoded `outputBuffer` as the truth.
- `AudioTrackAudioOutput.write(...)` performs a non-blocking write and returns whether the buffer was fully handled.
- If it was not fully handled, the same pending buffer remains active on the next drain.
- `hasPendingData()` is observational.

Important implication:

Media3 does not build a packet-scoped retry episode with explicit retry counts, retry reasons, and remainder ownership transitions the way our native TrueHD path still does.

So the strongest remaining parity gap versus Media3 is now:

> steady-state output is still modeled as explicit packet retry episodes rather than as one pending encoded output truth.

That does not mean transport or the Java contract are wrong. It means the remaining divergence is in the steady-state native output model itself.

## Kodi Comparison

Kodi is still useful as a secondary sink-side sanity check, not as the top-level architecture target.

Kodi sink behavior:

- `AESinkAUDIOTRACK.cpp` retries a zero write once after roughly one packet duration, then gives up.
- `ActiveAESink.cpp` retries boundedly around the sink loop and eventually fails the write if the sink never progresses.

Compared with our current engine:

- Kodi has a simpler bounded retry rhythm
- our engine keeps much richer packet-scoped retry state alive across more flushes

So the current branch is more complex than both references:

- more complex than Media3 architecturally
- more complex than Kodi operationally

## Primary Conclusion

The fresh late-stream audit points to this as the most grounded current diagnosis:

> the remaining TrueHD audio-quality defect is now a fully steady-state native output-model mismatch, not a startup handoff issue, not a route issue, and not a transport issue.

More specifically:

- the old `forced_retry` gap is no longer the active root cause
- the current failure is repeated zero-write retry episodes on steady-state remainders under a stable tuple
- those episodes are still happening often enough to create late-stream audio degradation and eventual underrun

## Remaining Parity Gaps

### Primary gap

Steady-state native output is still packet-retry-driven instead of pending-buffer-driven.

Why this matters:

- Media3 keeps one pending encoded output truth
- our engine still carries explicit packet retry episodes, retry counters, retry reasons, and remainder-specific cadence state

### Secondary gap

The retry backoff shape is still not matching actual observed late-stream readiness.

Why this matters:

- the active backoff is at most `20ms`
- actual successful recovery from the first zero often arrives around `28-48ms`
- so even the packet-duration-shaped backoff is still shorter than the real ready time in many late-stream cases

### Tertiary gap

Early startup/steady crossover still exists in some events.

Why it is not the primary target now:

- the late-stream failure window is already fully steady-state
- fixing the crossover alone is unlikely to solve the observed late-stream stutter

## What This Audit Rules Out

This run does not support reopening:

- MAT / IEC transport work
- route selection / repatch work
- playback-head estimator work
- broad player-state / Media3 contract surgery

Those boundaries are not where the active late-stream defect is presenting.

## Recommended Next Boundary

The next investigation or implementation pass should be Media3-first and steady-state-only:

- keep transport frozen
- keep Java contract surfaces frozen
- keep route logic frozen
- focus on making steady-state output behave more like one pending encoded truth and less like repeated packet retry episodes

That is now the highest-signal parity boundary from the active code and the latest valid late-stream bundle.

## Post-Pass Status

After completing the three-pass Media3-first structural refactor, the accepted `.37` rollback anchor is now:

- `/tmp/transport-validation-truehd-1774151306697.zip`

What changed in that accepted post-pass state:

- Java is no longer responsible for steady-state handoff work once startup is already complete
- the accepted Batch 3 explicitly does **not** change `hasPendingData()` semantics after startup completion

What did not change:

- transport remains clean
- route remains stable after startup
- playback still reaches `ENDED`
- late-stream audio is still degraded by native steady-state zero-write churn

So the structural Media3-first pass is complete, and the next boundary should stay below the contract surface:

- native late-stream steady-state cadence / recovery only
