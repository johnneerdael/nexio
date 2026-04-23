# TrueHD Runtime Strict Audit (Post Phase 1)

This document is a strict read-only audit of the current TrueHD passthrough runtime problem, updated after the successful execution of Parity Patch Phase 1 (Groups 1-3).

The goal is to identify the remaining source-level behavior mismatches that explain the late stream `audioUnderrunCount=1` and audible stutter without reopening:
- Media3-facing `AudioSink` contract behavior
- MAT / IEC transport correctness
- `AudioTrack` output tuple configuration
- Java-side route handling

## Grounded Runtime Truth (Post Phase 1)

Primary baseline truth on `192.168.50.37` from the latest validation passes:
- bundle: `transport-validation-truehd-1774108753637.zip`
- `transportVerdict=PASS`
- `referenceBurstCount=8`
- `packerInputBurstCount=64`
- `packedBurstCount=64`
- `audioTrackWriteBurstCount=64`

Key metrics:
- `timeToReadyMs=1027`
- `audioUnderrunCount=1`
- `continuousPlayingWindowSatisfied=true`
- `routeChangeCount=1`
- `longestZeroWriteStreakMs=62`
- `longestStuckRemainderMs=457`
- `maxZeroWriteStreak` is now fully absent, proving previous high counts were conflated control artifacts.

## Root Cause Boundary

The transport is completely verified. The architecture has been successfully decoupled:
- Startup and steady-state remainders no longer share mutable lifecycles.
- Complex playback-head and buffer-fit heuristics no longer block steady-state write progress.
- Metrics now truthfully distinguish between hardware zero-writes and control backoff.

The remaining defect boundary is narrowly constrained to **late stream track starvation**. The `audioUnderrunCount=1` proves that the `AudioTrack` runs dry late in the stream.

## Mismatch Map (Phase 2)

### Mismatch 5: Fixed 20ms Control Backoff vs Media3 Immediate Polling

Current code path:
- `KodiTrueHdAEEngine.cpp` in `ShouldRetrySteadyStatePendingPackedRemainderLocked(...)` uses `kSteadyStateRetryZeroBackoffUs = 20000;` (20ms).
- If a hardware zero-write occurs (track is full), we suppress retries on that remainder for a hardcoded 20ms window.
- The engine returns backpressure to the Java layer, and if Media3 calls `handleBuffer` within that 20ms window, the native layer immediately returns 0 without even querying the hardware `AudioTrack`.

Reference behavior:
- **Media3:** `DefaultAudioSink` and `AudioTrackAudioOutput` do not implement artificial backoffs. If a non-blocking write returns 0, Media3 simply leaves the buffer pending and retries it on the next ExoPlayer `doSomeWork` loop (typically every ~10ms). Media3 trusts the OS `AudioTrack` as the source of truth.
- **Kodi:** Uses a dedicated output thread (`CActiveAESink`) that can sleep, but it calculates sleep intervals based tightly on the frame duration of the blocked packet, not a fixed 20ms monolithic block.

Why this matters:
- ExoPlayer's render loop is sensitive. If the OS `AudioTrack` clears space 2ms after a zero-write, but our native sink is locked in a 20ms artificial backoff, we waste 18ms of valuable write opportunity.
- For high-bitrate TrueHD MAT streams where the track buffer only holds a few bursts, missing an 18ms write window almost guarantees a track underrun.

Audit classification:
- Primary runtime mismatch. The artificial backoff is starving the track.

### Mismatch 6: Threading Model & Buffer Sizing

Current code path:
- The native sink is driven entirely synchronously by Media3's `MediaCodecAudioRenderer` thread calling `handleBuffer`.
- TrueHD MAT bursts are massive (~61,440 bytes).

Reference behavior:
- **Kodi:** Has a dedicated `CActiveAESink` output thread. It pulls from a lock-free queue and pushes to the `AudioTrack`, completely decoupled from the decoder thread.
- **Media3:** Driven synchronously, but relies on configuring a large enough `AudioTrack` buffer to absorb thread scheduling jitter.

Why this matters:
- Because we share Media3's thread model but have massive burst requirements, we cannot afford to artificially delay writes. If the `AudioTrack` buffer is not scaled up to handle the TrueHD burst multipliers, any JVM thread jitter combined with our 20ms backoff causes an underrun.

Audit classification:
- Secondary architectural mismatch. 

## Recommended Phase 2 Grouped Patch Plan

### Patch Group 4: Eradicate Artificial Backoff for True OS-Driven Pacing
Goal:
- Allow the OS `AudioTrack` to act as the absolute source of truth for backpressure.

Targets:
- `KodiTrueHdAEEngine.cpp`

Change shape:
- Completely remove `kSteadyStateRetryZeroBackoffUs`.
- When Media3 invokes a write, always attempt the non-blocking `output_.WriteNonBlocking`.
- If it returns 0, we simply update our offsets and return 0 (backpressure) to Java.
- We rely on Media3's native ~10ms poll loop to retry.
- This perfectly matches Media3's stock `AudioTrackAudioOutput` behavior.

### Patch Group 5: TrueHD AudioTrack Buffer Multiplier
Goal:
- Ensure the `AudioTrack` has enough margin to absorb ExoPlayer JVM thread scheduling jitter when dealing with massive MAT bursts.

Targets:
- Java sink config / Native output configuration.

Change shape:
- Investigate and potentially increase the buffer size requested when configuring the `AudioTrack` for IEC61937 TrueHD to ensure it can hold at least 3-4 MAT bursts comfortably.
