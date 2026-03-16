## Goal

Replace the current queue-driven native write model with a stock-equivalent output ownership model.

The target behavior is Media3 `DefaultAudioSink` plus `AudioTrackAudioOutput`:

- one current pending output buffer at a time
- write progress is defined only by `AudioTrack.write(..., WRITE_NON_BLOCKING)`
- the current output buffer remains owned until fully drained
- `play()` changes playback state only; it does not run custom prime/refill/recovery logic
- `hasPendingData()` and `isEnded()` are derived from pending output ownership and output playout state

This plan is grounded in:

- [DefaultAudioSink.java](/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java)
- [AudioTrackAudioOutput.java](/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java)
- current native sink implementation in [KodiActiveAEEngine.cpp](/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.cpp), [KodiActiveAEEngine.h](/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.h), and [KodiIecPipeline.cpp](/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiIecPipeline.cpp)

## Stock ownership model

### `DefaultAudioSink`

`DefaultAudioSink` has one active output buffer:

- `inputBuffer` is the current upstream buffer being processed
- `outputBuffer` is the single current buffer being drained to the audio output
- `drainOutputBuffer()` keeps writing the same `outputBuffer`
- backpressure is provided by the fact that `outputBuffer` remains non-null until fully handled
- new output is not claimed until the previous `outputBuffer` is gone

For non-PCM:

- `AudioTrackAudioOutput.write(...)` returns `fullyHandled`
- written encoded frames advance only when the full encoded buffer is submitted
- `DefaultAudioSink` keeps the same buffer pending until that happens

### `AudioTrackAudioOutput`

`AudioTrackAudioOutput.write(...)`:

- writes directly to `AudioTrack` with `WRITE_NON_BLOCKING`
- treats the current buffer as fully handled only if all bytes were written
- advances PCM written bytes on partial write
- advances encoded written frames only on full encoded-buffer submission
- does not maintain an intermediate software output queue above the current output buffer

## Current native ownership model

`KodiActiveAEEngine` still owns output through multiple native state layers:

- `packedQueue_`
- `pcmQueue_`
- `pendingPassthroughAckBytes_`
- parser backlog in `KodiIecPipeline`
- write offsets within `KodiPackedAccessUnit`
- queue duration / queue bytes bookkeeping

This is materially different from stock because output truth is distributed across:

- parser state
- queue state
- delayed input acknowledgement state
- hardware write state

That is the reason the logs still show churn patterns such as repeated partial writes followed by many zero-write loops.

## Responsibility map: current to stock-equivalent

### `packedQueue_`

Current responsibilities:

- stores packed IEC output units
- acts as backpressure boundary
- stores write progress via `writeOffset`
- stores deferred acknowledgement via `inputBytesConsumed`
- stores pending PTS/duration metadata

Stock-equivalent target:

- replace with one current pending packed output buffer
- optional parser-side staging may exist only until the next packed unit is materialized
- there must not be a native scheduler queue above the current output buffer

Concrete replacement:

- `std::optional<KodiPackedAccessUnit> pendingPackedOutput_`

Rules:

- if `pendingPackedOutput_` exists, no new upstream bytes are admitted as handled for passthrough
- `FlushPackedQueueToHardwareLocked()` becomes `DrainPendingPackedOutputLocked()`
- the same pending packed buffer remains active until fully submitted or reset

### `pcmQueue_`

Current responsibilities:

- stores deferred PCM bytes when immediate output write did not finish
- acts as a software queue separate from current write ownership

Stock-equivalent target:

- replace with one current pending PCM output buffer

Concrete replacement:

- `std::optional<PendingPcmChunk> pendingPcmOutput_`

Rules:

- PCM path follows the same ownership rule as stock `outputBuffer`
- a partially written PCM buffer remains the current pending output
- no second PCM chunk is admitted as handled while the current one is still pending

### `pendingPassthroughAckBytes_`

Current responsibility:

- remembers how many upstream bytes can be acknowledged once the packed AU is fully written

Stock-equivalent target:

- move this ownership into the pending packed output buffer itself

Concrete replacement:

- keep `inputBytesConsumed` only on the current pending packed output
- acknowledge upstream bytes when that pending packed output fully drains
- remove engine-level `pendingPassthroughAckBytes_`

### `QueueDurationUsLocked()` / `QueueBytesLocked()`

Current responsibility:

- report aggregate queue backlog

Stock-equivalent target:

- report pending output state, not synthetic queue depth

Concrete replacement:

- derive pending duration/bytes only from:
  - `pendingPackedOutput_`
  - `pendingPcmOutput_`
  - parser backlog if parser consumed bytes but has not yet emitted a packed buffer

### `hasPendingData_` / `IsEnded()`

Current responsibility:

- combines queue state, parser backlog, and written-vs-played counters

Stock-equivalent target:

- pending data is true if:
  - a pending output buffer exists
  - parser backlog exists
  - output has written frames beyond played frames

Concrete rule:

- there is no separate truth from queue depth
- current output ownership and written frames are the contract

## Refactor entry points

### 1. `KodiIecPipeline`

Current:

- `Feed(...)` appends packed output to `std::deque<KodiPackedAccessUnit>& outPackets`

Refactor:

- change `Feed(...)` to emit at most one packed unit into a caller-owned pending slot
- do not append into a queue abstraction

Target API shape:

```cpp
int Feed(const uint8_t* data,
         int size,
         int64_t presentationTimeUs,
         KodiPackedAccessUnit* outPacket,
         bool* emittedPacket);
```

Behavior:

- parser may consume bytes without emitting
- if a packed unit is emitted, ownership transfers directly into `pendingPackedOutput_`
- parser backlog remains internal to `KodiIecPipeline`

### 2. `WritePassthroughLocked()`

Current:

- flush queue
- maybe acknowledge deferred bytes
- maybe feed parser
- maybe append to queue
- maybe flush again

Refactor:

- if `pendingPackedOutput_` exists:
  - drain it
  - if still pending, return 0
- if no pending packed output exists:
  - feed parser until either:
    - no bytes consumed
    - parser backlog only accepted input
    - one packed output buffer is emitted
- if a packed output buffer is emitted:
  - configure output if needed
  - drain it once
  - only acknowledge consumed upstream bytes if the packed output fully drains

Contract:

- one pending packed buffer at a time
- no queue-driven admission beyond that

### 3. `WritePcmLocked()`

Current:

- flushes `pcmQueue_`
- writes current bytes directly
- on partial write, stores remainder into `pcmQueue_`

Refactor:

- if `pendingPcmOutput_` exists:
  - drain it
  - if still pending, return 0
- if no pending PCM output exists:
  - create pending chunk from current aligned bytes
  - drain once
  - return bytes fully handled according to stock ownership semantics

This is not a passthrough-only policy. It is the same output ownership rule as stock.

### 4. `FlushPackedQueueToHardwareLocked()` and `FlushPcmQueueToHardwareLocked()`

Current:

- bounded loops over queue structures

Refactor:

- replace with:
  - `DrainPendingPackedOutputLocked()`
  - `DrainPendingPcmOutputLocked()`

Rules:

- each drains only the single current pending output buffer
- `WriteNonBlocking` remains the only authority
- partial write keeps the same pending output active
- completion clears the pending output and advances counters

### 5. `Play()`

Current state after latest cleanup:

- mostly aligned

Keep:

- set `playRequested_`
- ensure PCM output config if needed
- reset estimator state
- call `StartOutputIfPrimedLocked()`

Do not reintroduce:

- custom prime loops
- refill loops
- recovery in `Play()`

### 6. `Drain()`

Current:

- repeatedly calls queue flushers

Refactor:

- drain only the current pending output buffer
- end-of-stream is reached only when:
  - no pending output buffer remains
  - parser backlog is empty
  - written frames are fully played out

### 7. `Flush()` / `Reset()` / `HandleDiscontinuity()`

Refactor requirements:

- clear `pendingPackedOutput_`
- clear `pendingPcmOutput_`
- clear parser backlog
- clear any deferred input-consumption ownership
- clear startup and written-frontier state as already done

## Member changes

### Remove

- `std::deque<KodiPackedAccessUnit> packedQueue_`
- `std::deque<PendingPcmChunk> pcmQueue_`
- `int pendingPassthroughAckBytes_`

### Add

- `std::optional<KodiPackedAccessUnit> pendingPackedOutput_`
- `std::optional<PendingPcmChunk> pendingPcmOutput_`

### Revisit

- `queuedDurationUs_`
- `firstQueuedPtsUs_`
- queue logging strings

These should become pending-output metrics, not queue metrics.

## Logging changes

Current logs still reflect queue-driven architecture:

- `packedQueue=...`
- `pcmQueue=...`
- queue duration / queue bytes

Refactor target:

- `pendingPacked=0|1`
- `pendingPcm=0|1`
- `pendingPackedBytesRemaining=...`
- `pendingPcmBytesRemaining=...`
- parser backlog present yes/no

This will make post-change logs directly comparable to stock ownership behavior.

## Expected behavior changes

### Startup

Expected improvement:

- no synthetic queue backlog surviving into `play()`
- no repeated queue flush churn while a single packet remains pending
- `STARTED` reflects actual platform playing state plus a real current output buffer model

### Seek / resume

Expected improvement:

- no repeated repacketized churn after seek
- reduced chance of pop/crackle caused by pending output ownership being split across queue and delayed ack state

### PCM and passthrough parity

Expected improvement:

- both paths follow the same ownership rule:
  - one current pending output buffer
  - output progress decides upstream progress

Codec differences remain only in:

- parser/packing
- non-PCM frame accounting
- output configuration

## Implementation order

1. Add pending-output members and remove queue-only assumptions from state helpers.
2. Refactor `KodiIecPipeline::Feed(...)` to emit one pending packed buffer instead of queue append.
3. Replace `WritePassthroughLocked()` with single-pending-buffer ownership.
4. Replace PCM queue path with single pending PCM buffer ownership.
5. Replace queue flush helpers with pending-output drain helpers.
6. Update `HasPendingData()`, `IsEnded()`, `Drain()`, and queue metrics/logging.
7. Re-run build and validate startup/seek logs against the latest failure signatures.

## Non-goals

- no changes to timestamp plausibility logic in this pass
- no changes to written-frontier clamping in this pass
- no codec-specific heuristics for startup timing
- no new Java-side backpressure rules

The refactor target is ownership parity with stock, not another heuristic fix layer.
