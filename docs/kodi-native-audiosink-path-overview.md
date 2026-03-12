# Kodi Native AudioSink Path Technical Design Overview

## Scope
This document captures the current implemented Media3 -> JNI -> native Kodi sink path in this repo.

Primary sources:
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_session_bridge.cpp`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.h`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.cpp`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiIecPipeline.h`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiIecPipeline.cpp`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiAudioTrackOutput.h`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiAudioTrackOutput.cpp`
- `media/libraries/cpp_audiosink/kodi/xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAESettings.h`
- `media/libraries/cpp_audiosink/kodi/xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAESettings.cpp`

## 1) Java AudioSink Boundary (`KodiNativeAudioSink`)
`KodiNativeAudioSink` extends `ForwardingAudioSink`, owns native session state, and forwards lifecycle/data calls through JNI.

Behavior summary:
- `configure(...)`: lazy `nCreate`, then `nConfigure`.
- `handleBuffer(...)`: calls native `nWrite`; advances input ByteBuffer by consumed bytes; returns `false` on no progress (`<= 0`) for backpressure.
- `play/pause/flush/drain/reset/release`: direct JNI mapping.
- `handleDiscontinuity()`: forwards to native retime path (`nHandleDiscontinuity`).
- `getCurrentPositionUs`: returns native clock, else `CURRENT_POSITION_NOT_SET`.
- `isEnded()`: only true after explicit EOS (`playToEndOfStream`) and native ended.
- clock feeds:
  - renderer clock: `nSetHostClockUs`.
  - playback speed: `nSetHostClockSpeed`.

## 2) JNI Session Boundary (`kodi_cpp_session_bridge.cpp`)
JNI methods map 1:1 to one `KodiActiveAEEngine` instance:
- create/release: `nCreate` / `nRelease`.
- runtime: `nConfigure`, `nWrite`, `nPlay`, `nPause`, `nFlush`, `nDrain`.
- discontinuity: `nHandleDiscontinuity`.
- telemetry: `nGetCurrentPositionUs`, `nHasPendingData`, `nIsEnded`, `nGetBufferSizeUs`.

`NativeConfig` is translated to `ActiveAE::CActiveAEMediaSettings`.

## 3) Native Orchestrator (`KodiActiveAEEngine`)
Single lock (`CCriticalSection`) state machine, no worker thread.

Core runtime state:
- lifecycle: `configured_`, `playRequested_`, `outputStarted_`, `passthrough_`, `ended_`.
- queues:
  - passthrough packed queue: `packedQueue_` (`KodiPackedAccessUnit` with partial write offset).
  - PCM queue: `pcmQueue_` (`PendingPcmChunk`).
  - queue timing: `queuedDurationUs_`, `firstQueuedPtsUs_`.
- position/clock:
  - anchor: `anchorValid_`, `anchorPtsUs_`, `anchorPlaybackFrames_`, `anchorSinkSampleRate_`, `anchorMediaSampleRate_`.
  - safe played frontier: `lastStablePlayedFrames_` (monotonic/bounded played frames for occupancy and pending checks).
  - estimator: playhead offsets, smoothed offset, last output position/system time.
  - timestamp poller state: `INITIALIZING`, `TIMESTAMP`, `TIMESTAMP_ADVANCING`, `NO_TIMESTAMP`, `ERROR`.
  - timestamp samples: init/sample intervals and last/initial timestamp frame+time snapshots.
  - mapping: `mediaPositionParameters_`, `mediaPositionParametersCheckpoints_`.
  - discontinuity: `nextExpectedPtsUs_`, `nextExpectedPtsValid_`.
  - skipping: `skippedOutputFrameCount_`.
- counters: `totalWrittenFrames_`.

Backpressure model:
- output writes are non-blocking at `AudioTrack` boundary.
- passthrough acceptance is output-progress-driven:
  - flush queued packed bytes to `AudioTrack`
  - if packed queue is still non-empty, stop accepting new upstream bytes (return backpressure)
  - feed parser at most one packed packet worth each cycle, then flush again
- no deep software pre-roll reservoir in passthrough mode (single-threaded sink, no background drain worker).

## 4) Configure / Flush / Reset
`Configure(...)`:
1. Builds requested format via `CActiveAESettings::BuildFormatForMediaSource`.
2. Sets mode (`passthrough_` when requested data format is `AE_FMT_RAW`).
3. Clears queues/counters/position state.
4. Configures parser+packer (`iecPipeline_.Configure(...)`).
5. Releases output (`output_.Release()`).

`Flush()`:
- clears software queues and parser state.
- resets position/mapping/discontinuity state.
- resets play flags.
- all modes: `output_.Release()` (recreate on next write/config).

`Reset()`:
- full teardown: clears flags, queues, parser, output, and position state.

## 5) Write Path
Public `Write(...)`:
- validates config/data.
- dispatches:
  - passthrough: `WritePassthroughLocked(...)`.
  - PCM: `WritePcmLocked(...)`.
- refreshes pending-data flag.

### 5.1 Passthrough write
`WritePassthroughLocked(...)`:
1. Flushes residual packed data to hardware.
2. If packed queue is still non-empty after flush, stops immediately (`0` additional bytes consumed) to propagate backpressure to Media3.
3. Splits feed by encoded access-unit count and parses/packs via `KodiIecPipeline::Feed(..., maxPackets=1)`.
4. On first emitted packet, configures passthrough output from packet metadata (`outputRate`, `outputChannels`).
5. Flushes emitted packet bytes to `AudioTrack` and conditionally starts output.
6. If parser consumed bytes into internal backlog but no packet emitted (`HasParserBacklog()`), stops early to avoid over-consuming without output write progress.

Important current behavior:
- while paused, writes are still attempted to paused hardware under `WRITE_NON_BLOCKING`.
- passthrough admission is now tied to real write progress, not estimated duration headroom.
- no deep software pre-roll is allowed in passthrough mode because there is no worker thread draining queue while Java thread sleeps.

### 5.2 PCM write
`WritePcmLocked(...)`:
1. Frame-aligns bytes.
2. Ensures output configured.
3. If residual PCM queue cannot be flushed under headroom, returns `0`.
4. Writes input directly using headroom-bounded chunk size (paused or playing).
5. If `playRequested_`, attempts start when primed.

### 5.3 Queue flush semantics
`FlushPackedQueueToHardwareLocked()` and `FlushPcmQueueToHardwareLocked()`:
- run whenever queue data exists and output is configured.
- are headroom-bounded (write at most available `AudioTrack` buffer headroom per call).
- support partial writes by tracking per-packet/per-chunk offsets and leaving residue queued.
- call `OnBytesWrittenLocked(...)` for accounting/anchor/discontinuity.

## 6) Parser+Packer (`KodiIecPipeline`)
Pipeline responsibilities:
- parse encoded bitstream into access units.
- map AU metadata (PTS/duration/stream info).
- pack IEC61937 payloads (`CAEBitstreamPacker`).
- emit `KodiPackedAccessUnit` entries with:
  - packed bytes,
  - PTS/duration,
  - output sample rate/channels for hardware config.

No AudioTrack ownership, no playback thread ownership.

## 7) Hardware Output (`KodiAudioTrackOutput`)
Thin wrapper around `CJNIAudioTrack`.

Configure:
- sample rate/channels/encoding normalization.
- passthrough encoding prefers `ENCODING_IEC61937`.
- channel mask from channel count.
- buffer size: minBuffer -> 2x minBuffer target.
- creates track, then immediately `pause()` + `flush()`.

Write:
- `WriteNonBlocking(...)` uses `AudioTrack.write(..., WRITE_NON_BLOCKING)`.

Position:
- `GetPlaybackFrames64()` extends 32-bit playback head with wrap counter.
- `GetTimestamp(...)` returns AudioTrack timestamp (frame position + nanoTime mapped to us).
- wrap handling uses a large backward-delta guard so small backward head jumps
  (track reset/recreation artifacts) are not treated as true uint32 rollover.

Flush/release:
- `Flush()` pauses+flushes, resets wrap counters.
- `Release()` releases track and clears state.

## 8) Start Policy
`Play()`:
- sets `playRequested_ = true`.
- ensures PCM output configured (passthrough output is packet-driven on first packed AU).
- resets position estimator/timestamp poller state for resume safety.
- starts output only when primed (`totalWrittenFrames_ > safePlayedFrames`).

`Pause()`:
- clears play/start flags.
- calls `output_.Pause()`.
- resets position estimator/timestamp poller state.

`Drain()`:
- flushes queued data into hardware in both modes; if primed it may start output and continue draining.

## 9) Position Clock Path
Entry:
- `GetCurrentPositionUs()` -> `ComputePositionFromHardwareLocked()`.

`ComputePositionFromHardwareLocked()`:
1. If anchor/output invalid: returns `CURRENT_POSITION_NOT_SET`.
2. Gets output-domain position from `GetAudioOutputPositionUsLocked()`.
3. Clamps to written frontier (`GetWrittenAudioOutputPositionUsLocked()`).
4. Applies media mapping checkpoints (`ApplyMediaPositionParametersLocked`).
5. Applies skipped-frame compensation (`ApplySkippingLocked`).
6. Monotonic clamp against last reported position.

### 9.1 Output-domain estimator
`GetAudioOutputPositionUsLocked()`:
- base raw estimate from playback head delta since anchor.
- timestamp path is guarded by a poller-like state machine:
  - `INITIALIZING` -> `TIMESTAMP` -> `TIMESTAMP_ADVANCING` promotion only when timestamp plausibly advances.
  - transitions to `NO_TIMESTAMP` / `ERROR` on timeout or plausibility failure.
- plausibility checks include:
  - timestamp system-time offset vs current system time bounds,
  - timestamp-position estimate vs playback-head estimate bounds.
- only in `TIMESTAMP_ADVANCING` state is timestamp-based position used.
- otherwise fallback to smoothed playhead estimate:
  - periodic `(rawPositionUs - systemTimeUs)` sampling,
  - averaged offset,
  - estimate via `systemTime + smoothedOffset`.
- final drift smoothing constrains reported movement against expected progression.

### 9.2 Media-time mapping
`ApplyMediaPositionParametersLocked(...)`:
- consumes checkpoints when output position reaches checkpoint boundary.
- scales playout duration by checkpoint playback speed.
- applies drift term while waiting for next checkpoint.

`SetHostClockSpeed(...)`:
- on speed change with valid anchor, snapshots current output/media position into checkpoint queue.

### 9.3 Skip compensation
`ApplySkippingLocked(...)`:
- adds skipped output frame duration (in media rate domain) to mapped position.

### 9.4 Discontinuity reconciliation
`HandleDiscontinuity()`:
- mirrors stock `DefaultAudioSink.handleDiscontinuity` contract by setting sync-needed state for the next media-time boundary.

`UpdateExpectedPtsLocked(...)`:
- compares packet PTS with rolling expected PTS.
- if abs(delta) > `DISCONTINUITY_THRESHOLD_US` (200ms), marks sync-needed and defers reanchor until drain-safe point.

`ReanchorForDiscontinuityLocked(...)`:
- resets anchor to discontinuity packet PTS and current playback head.
- resets estimator + mapping checkpoints.

## 10) Pending / Ended / Buffer Reporting
`HasPendingData()`:
- true when software queues are non-empty, parser backlog exists (`iecPipeline_.HasParserBacklog()`), or `totalWrittenFrames_ > safePlayedFrames`.
- `safePlayedFrames` is monotonic and bounded to written frontier, and does not advance while not actively started.

`IsEnded()`:
- Java-side parity behavior: true only after explicit EOS path and no pending data.

`GetBufferSizeUs()`:
- from `AudioTrack.getBufferSizeInFrames()` and sample rate.

## 11) Current Observed Runtime Characteristics
- Passthrough output is recreated after passthrough flush (`Flush -> output.Release`).
- Writes are non-blocking at AudioTrack boundary.
- Passthrough backpressure is output-progress-driven and parser-backlog aware (no deep software pre-roll).
- Pause/resume rebase resets estimator/timestamp state to avoid stale timestamp carry-over.
- Position is anchored to packet PTS and mapped through guarded timestamp/playhead estimator + checkpoints.
