# Kodi Native AudioSink Stock Parity Narrow Note

Date: 2026-03-14

Scope: This note lists only the remaining parity gaps that are directly supported by both:
- current local Media3 stock source, primarily:
  - `DefaultAudioSink.handleBuffer`
  - `DefaultAudioSink.drainOutputBuffer`
  - `DefaultAudioSink.flush`
  - `DefaultAudioSink.hasPendingData`
  - `DefaultAudioSink.isEnded`
  - `AudioTrackAudioOutput.write/play/pause/flush`
- current `debug-atmos.log`

It intentionally excludes speculative fixes.

## Stock facts that matter

1. `DefaultAudioSink.handleBuffer` is output-progress driven.
- It initializes output if needed.
- It calls into draining logic.
- It returns `false` when output is not ready or draining cannot progress.
- It does not add an extra software-queue truth model above the output boundary.

2. `DefaultAudioSink.drainOutputBuffer` delegates write truth to `audioOutput.write(...)`.
- In `AudioTrackAudioOutput.write`, the authoritative limiter is `AudioTrack.write(..., WRITE_NON_BLOCKING)`.
- For non-PCM, encoded frames are only counted once the full buffer is submitted.
- Recoverable write failures are retried through `PendingExceptionHolder`.

3. `DefaultAudioSink.flush` fully resets output state.
- It releases the current output on flush.
- It clears pending exception holders.
- It resets sink state used for pending-data/end-of-stream tracking.

4. `AudioTrackAudioOutput.play()` starts position tracking immediately before `audioTrack.play()`.
- `pause()` pauses position tracking and then pauses the track.
- `flush()` resets position tracking and written-frame counters.

## Proven remaining gaps

### Gap 1: Paused preroll is still too permissive relative to output-side progress

Evidence from log:
- `13:14:56.903`: `prePlayAcceptGapUs=365424`
- `13:15:20.441`: `prePlayAcceptGapUs=784819`
- `13:15:24.744`: `prePlayAcceptGapUs=5087280`

At each of those starts we still report:
- `packedQueue=1`
- non-zero `totalWrittenFrames`
- startup proceeds as if preroll is healthy

Why this is a stock parity gap:
- Stock `DefaultAudioSink` does not maintain a separate native software queue that can drift semantically away from actual output-side progress while still reporting successful consumption upstream.
- Our current native path still accepts data into `packedQueue_` and relies on our own queue/output coupling to represent truth.

What is safe to conclude:
- The remaining startup mismatch is still on the paused passthrough admission boundary.

What is not yet proven:
- A precise one-line code change that fully eliminates the gap without changing the C++ queue architecture.

### Gap 2: Native passthrough flush pattern is still not stock-like

Evidence from log:
- repeated `FlushPackedQueueToHardwareLocked` sequences in `RUNNING`:
  - `bytesWritten=16384`
  - then `bytesWritten=8096`
  - then `bytesWritten=16288`
  - then `bytesWritten=96` with `lastWriteResult=0`
  - followed by repeated `writes=0`

Why this is a stock parity gap:
- Stock `AudioTrackAudioOutput.write` performs one `WRITE_NON_BLOCKING` call and treats that result as the write truth for that invocation.
- Our C++ passthrough path is still fragmenting a queued packet across repeated native flush calls in a pattern that is produced by our queueing/packing loop, not by stock Media3 control flow.

What is safe to conclude:
- The remaining oscillation is a native queue/flush behavior gap, not a Java `handleBuffer` contract gap.

What is not yet proven:
- Whether the correct stock-aligned fix is packet-boundary preservation, stricter paused admission, or a smaller change in how partial packet writes are re-presented.

### Gap 3: Startup underrun is still real

Evidence from log:
- `13:14:57.668`: `pause because of UNDERRUN, framesReady = 0`

Why this matters:
- This confirms the startup issue is not just logging noise or a timing-metric artifact.
- Even after recent Java and native cleanups, the passthrough startup path still reaches hardware starvation.

Why this is not enough by itself to justify a new code change:
- The underrun is an outcome, not a precise root cause.
- The two proven upstream gaps above are better change targets than the underrun itself.

## Areas that are currently close enough and should not be changed blindly

1. Java recoverable retry behavior
- Current custom sink now only arms retry on no-progress native failures.
- That is close to stock `PendingExceptionHolder` intent.

2. Flush/release policy
- Native path already releases on flush, which matches current stock behavior closely enough for this issue.

3. Position tracking / written-frontier clamp
- Current startup failures in the latest logs do not point to clock math as the primary trigger.

## Narrow implementation rule for next pass

Only implement a native change if it satisfies both:
- it moves passthrough startup behavior closer to stock's direct output-progress truth model
- it is justified by one of the proven gaps above

Avoid:
- new timing heuristics
- new startup special cases
- changes to position tracking
- Java-side backpressure additions

## Candidate next-pass targets that remain plausible but not yet proven enough

1. Tighten paused passthrough admission so successful upstream consumption cannot outpace actual output-side forward progress.
2. Reduce native packet re-fragmentation churn so flush behavior is closer to one write-truth decision per output attempt.

These remain candidates, not approved fixes, until grounded more tightly against source and code path behavior.
