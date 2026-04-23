# Media3 Stock `AudioSink` Technical Design Overview

## Scope
This document describes stock Media3 `AudioSink` behavior and timing internals from:

- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioSink.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DecoderAudioRenderer.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackPositionTracker.java`

No comparison to custom integration code is included.

## 1) `AudioSink` Contract and Semantics
`AudioSink` is renderer-owned and synchronously driven by the audio renderer loop.

Primary methods:
- `configure(format, specifiedBufferSize, outputChannels)`
- `handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)`
- `play()`, `pause()`
- `flush()`, `reset()`, `release()`
- `playToEndOfStream()`, `hasPendingData()`, `isEnded()`
- `getCurrentPositionUs(sourceEnded)`

Backpressure contract:
- `handleBuffer(...) == true`: buffer fully consumed by sink.
- `handleBuffer(...) == false`: sink backpressured; renderer retries same remaining data.

Position sentinel contract:
- `CURRENT_POSITION_NOT_SET` is returned until output and timing anchor are valid.
- In stock `DefaultAudioSink`, `getCurrentPositionUs(boolean sourceEnded)` does not branch on
  `sourceEnded`; output state drives the value.

## 2) Renderer <-> Sink Lifecycle Wiring
### MediaCodecAudioRenderer
- `onEnabled(...)`:
  - tunneling enable/disable routed to sink.
  - sets player id.
  - injects renderer clock with `audioSink.setClock(getClock())`.
- `onPositionReset(...)`:
  - calls `audioSink.flush()`.
  - resets renderer position state and discontinuity allowances.
- `onStarted()`:
  - calls `audioSink.play()`.
- `onStopped()`:
  - first pulls current sink position, then `audioSink.pause()`.
- `onDisabled()`:
  - calls `audioSink.flush()`.
- `onReset()`:
  - calls `audioSink.reset()` when needed.
- `onRelease()`:
  - calls `audioSink.release()`.

Renderer state queries:
- `isReady()` delegates to `audioSink.hasPendingData()`.
- `isEnded()` requires `super.isEnded()` and `audioSink.isEnded()`.

Output buffer feeding:
1. Renderer emits decoded output buffer.
2. Calls `audioSink.handleBuffer(...)`.
3. If sink returns `false`, renderer records `nextBufferToWritePresentationTimeUs` and retries.
4. If sink returns `true`, output buffer is released.

Discontinuity hook:
- `onProcessedStreamChange()` calls `audioSink.handleDiscontinuity()`.

Output stream offset hook:
- `onOutputStreamOffsetUsChanged(...)` calls `audioSink.setOutputStreamOffsetUs(...)`.

## 3) `DefaultAudioSink` Configuration and Reconfiguration
### Configure path
- PCM input:
  - builds `AudioProcessingPipeline` with available processors and conversion path.
  - applies trim (`encoderDelay`, `encoderPadding`) and channel mapping.
- Encoded passthrough/offload:
  - empty processing pipeline (no decode-domain processor operations).
- Resolves output with `AudioOutputProvider.getOutputConfig(...)`.
- If output already initialized:
  - new config is stored as `pendingConfiguration`.
  - applied later after drain/reuse decision.

### Reconfiguration behavior in `handleBuffer(...)`
When `pendingConfiguration != null`:
1. `drainToEndOfStream()` must complete first.
2. If current output cannot be reused:
  - `playPendingData()`.
  - wait until current pending output drains (`hasPendingData()` false).
  - `flush()`, then continue with new config.
3. If reusable:
  - swap config in place.
  - for offload gapless cases, set offload EOS + delay/padding handling.
4. Reapply playback parameters and skip-silence checkpoints.

## 4) `handleBuffer(...)` Low-Level Write Flow
Core algorithm:
1. Ensure initialization (lazy output create on first real data).
2. First post-init buffer:
  - sets `startMediaTimeUs = max(0, presentationTimeUs)`.
  - clears init/sync flags.
  - applies playback params chain.
  - if sink already marked playing, invokes `play()` immediately.
3. For encoded data:
  - lazily infer `framesPerEncodedSample`.
  - if unknown (e.g., non-syncframe chunk), can drop current encoded buffer and return `true`.
4. For timing coherence:
  - expected PTS computed from `startMediaTimeUs + submittedFramesDuration`.
  - mismatch threshold: `abs(expected - presentationTimeUs) > 200_000us`.
  - mismatch sets sync-needed flag and reports sink error callback.
5. If sync-needed:
  - drain processors/output to boundary,
  - adjust `startMediaTimeUs += adjustmentUs`,
  - reapply playback params/checkpoints,
  - emit `onPositionDiscontinuity()` if adjustment non-zero.
6. Update submitted counters.
7. `processBuffers(...)` and `drainOutputBuffer(...)`.
8. If input fully consumed, return `true`; else `false` unless stall reset path triggers.

Stall guard:
- `audioOutput.isStalled()` triggers `flush()` and returns `true` to break deadlock.

## 5) Counter and Frame Accounting
Submitted:
- PCM: `submittedPcmBytes += buffer.remaining()`.
- Encoded: `submittedEncodedFrames += framesPerEncodedSample * encodedAccessUnitCount`.

Written:
- PCM: `writtenPcmBytes += bytesWritten`.
- Encoded: increments only when whole encoded input buffer is fully handled.

Derived frame counts:
- `getSubmittedFrames()`:
  - PCM: `submittedPcmBytes / inputPcmFrameSize`
  - Encoded: `submittedEncodedFrames`
- `getWrittenFrames()`:
  - PCM: `ceil(writtenPcmBytes / outputPcmFrameSize)`
  - Encoded: `writtenEncodedFrames`

Pending playout condition:
- `hasAudioOutputPendingData(writtenFrames)` compares:
  - `writtenFrames > currentPositionFrames`, where
  - `currentPositionFrames` is derived from `audioOutput.getPositionUs()` and sample rate.

## 6) Clock Path in `DefaultAudioSink.getCurrentPositionUs(...)`
Clock computation:
1. If output uninitialized or start anchor not initialized:
  - return `CURRENT_POSITION_NOT_SET`.
2. `positionUs = audioOutput.getPositionUs()`.
3. Hard clamp to submitted/written media frontier:
  - `positionUs = min(positionUs, framesToDurationUs(getWrittenFrames()))`.
4. Apply media-time mapping:
  - `positionUs = applyMediaPositionParameters(positionUs)`.
5. Apply skipped-silence compensation:
  - `positionUs = applySkipping(positionUs)`.
6. Return result.

### `applyMediaPositionParameters(...)`
- Maintains checkpoints `{playbackParameters, mediaTimeUs, audioOutputPositionUs}`.
- Advances checkpoint when output position crosses checkpoint boundary.
- For each segment:
  - estimated media duration from playout duration + speed factor.
  - actual media duration from processor chain when valid.
- Carries `mediaPositionDriftUs` across checkpoint boundaries to avoid jumps while buffers processed
  with old params are still being played.

## 7) Discontinuity and Stream Change Handling
- Explicit stream change hook from renderer calls `handleDiscontinuity()`, setting
  `startMediaTimeUsNeedsSync = true`.
- Next `handleBuffer(...)` does sync reconciliation at safe drain boundary.
- Sink emits `onPositionDiscontinuity()` after real anchor adjustment.

## 8) `AudioTrackAudioOutput` Behavior
Responsibilities:
- Owns platform `AudioTrack` instance and all direct operations.
- Provides `getPositionUs()` via `AudioTrackPositionTracker`.

Important semantics:
- `play()`:
  - calls `audioTrackPositionTracker.start()` immediately before `AudioTrack.play()`.
- `pause()`:
  - tracker pause first, then `AudioTrack.pause()`.
- `write(...)`:
  - always `WRITE_NON_BLOCKING`.
  - returns boolean fully-consumed status.
  - negative write return -> `WriteException(errorCode, isRecoverable)`.
- `flush()`:
  - resets local counters and tracker state.
  - calls `AudioTrack.flush()`.
- `stop()`:
  - tracker records end-of-stream write position.
  - calls `AudioTrack.stop()`.
- `release()`:
  - asynchronous release path.

## 9) `AudioTrackPositionTracker` Algorithm
Position source strategy:
1. Prefer reliable advancing `AudioTimestamp` poller values.
2. Fallback to smoothed playback-head estimate.

Important constants:
- max smoothing drift window: `1_000_000us`.
- max smoothing speed-change band: `10%`.
- raw playback-head update interval: `5ms`.
- playhead offset sample interval: `30_000us`.
- latency sample interval: `500_000us`.
- invalid latency cap: `10s`.

Smoothing details:
- Samples offset between (system time) and (playhead-derived playout time).
- Uses moving average over up to 10 offsets.
- When actively playing and prior sample exists:
  - computes expected position from elapsed system time and playback speed.
  - constrains current position within expected band if drift is moderate.

Latency details:
- PCM path can query platform `getLatency` (if available).
- subtracts track buffer contribution to estimate mixer/hardware latency.
- clamps negative latency to zero.
- rejects impossible latency and reports callback.

Playback-head robustness:
- expands 32-bit playback head to long with wrap tracking.
- supports expected raw head resets during reused offload transitions.
- includes stuck-zero workaround path for known platform failure states.

Advancing callback:
- emits `onPositionAdvancing(playoutStartSystemTimeMs)` once after resume/reset when position truly advances.

## 10) Offload and Tunneling Low-Level Behavior
Offload:
- write path recognizes special period after offload EOS command where writes can return 0 due to
  internal stop/restart.
- avoids falsely treating this as steady full-buffer deadlock.
- emits `onOffloadBufferFull()` only under playing + partial-write + non-transient conditions.
- offload may be disabled until next configuration if runtime support path fails.

Tunneling:
- tunneling write path uses AV-sync timestamped write mode.
- EOS drain can reuse last tunneling presentation timestamp when explicit EOS marker is processed.

## 11) Error Model and Retry Windows
`DefaultAudioSink` keeps separate pending holders for init and write failures.

`PendingExceptionHolder` policy:
- retry duration: `200ms`.
- minimum retry delay: `50ms`.
- if pending output releases exist, retry timer start is deferred.
- non-recoverable failures are retried until deadline then thrown.
- recoverable failures are thrown immediately when higher-layer recovery is expected.

Write exception handling:
- recoverable write errors may retry when output already wrote frames.
- offload recoverable early write failures can trigger offload disable + retry.

## 12) Flush, Seek, Pause, Stop, EOS
### Flush / seek reset
- Renderer seek path calls `audioSink.flush()`.
- `flush()`:
  - resets counters, buffers, checkpoints, EOS/offload flags.
  - clears pending init/write exception state.
  - releases current output (device compatibility workaround path).

### Pause / resume
- Pause:
  - renderer stopped callback -> `audioSink.pause()`.
  - sink clears playing flag and pauses output.
- Resume:
  - renderer started callback -> `audioSink.play()`.
  - output tracker start + `AudioTrack.play()`.

### EOS
1. Renderer calls `playToEndOfStream()`.
2. Sink queues processor EOS and drains.
3. Sink may call `playPendingData()`/`stop()` to seal stream completion.
4. `isEnded()` true only when EOS handled and no pending output remains.

## 13) Threading and Concurrency Model
- Renderer -> sink contract methods run on playback/renderer thread.
- `AudioTrackAudioOutput` listener set is bound to current thread context.
- AudioTrack release happens on dedicated release executor and completion callback is posted back to
  playback thread handler when possible.
- `DefaultAudioSink` tracks global pending output releases and uses that state to gate retry timing.

## 14) Listener/Callback Surface
Relevant `AudioSink.Listener` events:
- `onPositionDiscontinuity()`
- `onPositionAdvancing(playoutStartSystemTimeMs)`
- `onUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)`
- `onOffloadBufferFull()`
- `onAudioSinkError(Exception)`
- session/init and skip-silence related events

These are emitted only by current active output listener instance; stale listener events are ignored.

## 15) Detailed Sequence Snapshots
### Startup
1. Renderer configures sink.
2. First `handleBuffer` initializes output and `startMediaTimeUs`.
3. Renderer enters started state and calls `play()`.
4. Non-blocking writes proceed with backpressure (`false`) when needed.
5. Renderer repeatedly polls sink clock and updates `currentPositionUs`.

### Seek / position reset
1. Renderer `onPositionReset`.
2. Sink `flush()` clears and releases output.
3. Next post-seek input re-inits output and re-anchors start media time.
4. If PTS mismatch > 200ms, sink reconciles anchor and emits discontinuity.

### End-of-stream
1. Renderer signals EOS and calls `playToEndOfStream()`.
2. Sink drains processors and output.
3. Pending data drains to zero.
4. `isEnded()` flips true once sink fully drained.

## 16) Practical Invariants for Integrators
- `handleBuffer` must remain strictly backpressure-compliant.
- Sink position must not exceed written frontier.
- Discontinuity logic must run at safe drain boundaries.
- `play/pause/flush` ordering from renderer is authoritative.
- Pending-data and ended-state logic must remain frame-accounting based, not queue-length guessed.
