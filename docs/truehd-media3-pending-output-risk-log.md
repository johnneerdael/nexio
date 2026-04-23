# TrueHD Media3 Pending Output Risk Log

Date: 2026-03-22
Worktree: `codex/truehd-audio-quality-parity`

## Rollback Anchor Before Batch 1

- worktree root commit: `efa01c04c`
- `.37` baseline bundle: `/tmp/transport-validation-truehd-1774146543766.zip`

## Batch 1 Scope

Files touched:

- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`

What changed:

- steady-state pending packed output now owns packet and steady-state retry metadata in one native truth object
- owner-indexed steady-state packet/retry helpers were removed from the header surface
- startup pending output and startup retry state were left structurally separate

What was explicitly not changed:

- MAT / IEC transport path
- AudioTrack tuple/config logic
- Java `AudioSink` contract methods
- route logic
- startup drain semantics

## Batch 1 Validation

- install target: `192.168.50.37:5555`
- validation bundle: `/tmp/transport-validation-truehd-1774149948737.zip`

Hard-gate read:

- `transportVerdict=PASS`
- burst chain remained `8 -> 64 -> 64 -> 64`
- route remained stable after startup:
  - `routeTupleChangeCountAfterStableStart=0`
  - `routeReopenCountAfterStart=0`
- playback still reached `ENDED` at `63685ms`

Important caveat:

- `runtimeVerdict=FAIL` and `playerStateVerdict=FAIL` remain noisy on the 120s observation window for this ~64s sample because the current validator still emits `POSITION_STALLED` even when playback ends cleanly.
- This batch is therefore judged by raw outer behavior first, not by the top-line long-window verdict string.

Observed runtime shape vs rollback anchor:

- `timeToReadyMs`: `1218 -> 1274`
- `droppedVideoFrames`: `32 -> 34`
- `audioUnderrunCount`: `1 -> 1`
- `writeAttemptCount`: `6740 -> 6838`
- `zeroWriteCount`: `2187 -> 2289`
- `remainderRetryEventCount`: `2192 -> 2290`
- `longestZeroWriteStreakMs`: `92 -> 53`
- `longestStuckRemainderMs`: `78 -> 23`

## Batch 1 Decision

Keep Batch 1.

Reason:

- no transport regression
- no route regression
- no outer playback collapse on `.37`
- the batch is structural groundwork for Media3-first pending-output truth

Known limitation carried forward:

- late-stream audio quality is still not at parity
- the final underrun plus end-of-stream dropped-frame coupling remains

## Batch 2 Scope

Files touched:

- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`

What changed:

- steady-state flush control no longer calls a dedicated steady-state retry-eligibility helper before retrying the active pending output
- zero and partial steady-state writes now keep the same pending-output truth active instead of re-admitting through helper-driven control
- startup-specific retry gating remains isolated and bounded

What was explicitly not changed:

- MAT / IEC transport path
- AudioTrack tuple/config logic
- Java `AudioSink` contract methods
- route logic
- startup handoff policy

## Batch 2 Validation

- install target: `192.168.50.37:5555`
- validation bundle: `/tmp/transport-validation-truehd-1774150499518.zip`
- comparison bundle: `/tmp/transport-validation-truehd-1774149948737.zip`

Hard-gate read:

- `transportVerdict=PASS`
- burst chain remained `8 -> 64 -> 64 -> 64`
- route remained stable after startup:
  - `routeTupleChangeCountAfterStableStart=0`
  - `routeReopenCountAfterStart=0`
- playback still reached `ENDED` at `63334ms`

Important caveat:

- `runtimeVerdict=FAIL` and `playerStateVerdict=FAIL` remain noisy on the 120s observation window for this ~64s sample.
- Raw player events are the grounded source for the outer-boundary judgment on this batch; they show `BUFFERING -> READY -> isPlaying=true -> ENDED`.

Observed runtime shape vs Batch 1:

- `timeToReadyMs`: `1274 -> 1265`
- `droppedVideoFrames`: `34 -> 28`
- `audioUnderrunCount`: `1 -> 1`
- `writeAttemptCount`: `6838 -> 6899`
- `zeroWriteCount`: `2289 -> 2347`
- `remainderRetryEventCount`: `2290 -> 2352`
- `longestZeroWriteStreakMs`: `53 -> 56`
- `longestStuckRemainderMs`: `23 -> 105`

Retry-shape note:

- steady-state drain semantics are now active in control flow
- the batch did not normalize late-stream audio yet
- the bundle still shows repeated steady-state zero-write churn, but without reopening transport, route, or startup control surfaces

## Batch 2 Decision

Keep Batch 2.

Reason:

- no transport regression
- no route regression
- no outer playback collapse on `.37`
- this batch completes the Media3-first steady-state drain control shift without touching startup or Java contract surfaces

Known limitation carried forward:

- late-stream audio quality is still not at parity
- late steady-state remainders still spend too long in repeated zero-write churn under a stable tuple

## Batch 3 Scope

Files touched:

- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdNativeAudioSinkSourceStructureTest.kt`
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-late-stream-audio-quality-audit.md`

What changed:

- `handleBuffer(...)` now becomes observational once `trueHdStartupCompleted` is already true and routes directly to the steady-state path without re-running startup handoff work
- Java startup/handoff diagnostics are still recorded, but the steady-state write path no longer depends on repeated Java-side handoff evaluation

Rejected sub-change:

- a broader Batch 3 candidate also changed `hasPendingDataForTrueHdIfNeeded()` to fall back to baseline once startup completed
- that change was treated as contract-sensitive because it touched renderer pending-data semantics
- it was backed out before final acceptance

What was explicitly not changed in the accepted Batch 3:

- MAT / IEC transport path
- AudioTrack tuple/config logic
- native steady-state drain control from Batch 2
- route logic
- startup reservoir sizing

## Batch 3 Validation

- rejected broad candidate bundle: `/tmp/transport-validation-truehd-1774150943435.zip`
- accepted narrowed bundle: `/tmp/transport-validation-truehd-1774151306697.zip`
- comparison bundle: `/tmp/transport-validation-truehd-1774150499518.zip`

Hard-gate read for accepted narrowed bundle:

- `transportVerdict=PASS`
- burst chain remained `8 -> 64 -> 64 -> 64`
- route remained stable after startup:
  - `routeTupleChangeCountAfterStableStart=0`
  - `routeReopenCountAfterStart=0`
- playback reached `ENDED` at `63331ms`
- no exported player/runtime error event was present in the bundle or validator log

Important caveat:

- the validator's 120s summary path still emits `playerStateVerdict=FAIL` / `POSITION_STALLED` for this ~64s sample even when playback ends cleanly
- raw player events and the validator log are therefore the grounded source for the outer-boundary decision

Observed runtime shape for accepted narrowed bundle vs rejected broad candidate:

- `timeToReadyMs`: `1187 -> 1073`
- `droppedVideoFrames`: `34 -> 10`
- `audioUnderrunCount`: `1 -> 1`
- `writeAttemptCount`: `6968 -> 6959`
- `zeroWriteCount`: `2416 -> 2407`
- `remainderRetryEventCount`: `2419 -> 2410`
- `longestZeroWriteStreakMs`: `100 -> 37`
- `longestStuckRemainderMs`: `7 -> 14`

## Batch 3 Decision

Keep the narrowed Batch 3 only.

Reason:

- the contract-sensitive `hasPendingData()` fallback was the risky part of the broad candidate and is not part of the accepted change
- the accepted narrowed bundle keeps transport, route stability, and end-of-stream completion intact on `.37`
- this completes the Media3-first structural pass without leaving the Java layer in charge of steady-state pacing

## Final Findings

- Batch 1, Batch 2, and the narrowed Batch 3 are all accepted on `.37`
- the Media3-first structural pass is complete
- the remaining defect is no longer startup crossover or Java handoff control
- the remaining defect is late-stream native steady-state zero-write churn under a stable tuple

## Latest Good Rollback Point

- latest accepted validation bundle: `/tmp/transport-validation-truehd-1774151306697.zip`
- last committed root checkpoint before Batch 3 commit: `065cfdaad`
- last committed media checkpoint before Batch 3 commit: `e44828554c`

## Four-Batch Teardown Rollback Anchor

- root commit before Batch 1: `ece921745`
- media commit before Batch 1: `d4e7d5c8a3`
- accepted `.37` baseline bundle: `/tmp/transport-validation-truehd-1774151306697.zip`

## Four-Batch Teardown Batch 3 Status

Files touched:

- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`

What changed:

- removed the now-dead steady-state helper declaration `ComputeSteadyStateRetryBackoffUsLocked(...)`
- removed the matching dead helper definition from the native engine source
- added a structure test to lock that helper out of the steady-state surface going forward

Grounded read:

- this helper was already unused in control flow after Batch 2
- the code change is therefore structural cleanup, not a live steady-state behavior rewrite

Validation caveat:

- the follow-up `.37` validator run after install completed, but export reused the old bundle name `/tmp/transport-validation-truehd-1774154048961.zip`
- logcat showed `Export requested without an active transport validation session snapshot`
- that run is not trustworthy enough to use as Batch 3 runtime evidence

Decision:

- keep the code change as low-risk dead-code cleanup
- do not treat the reused bundle as a validation result
- revalidate from the main workspace before making a behavioral claim about Batch 3

## Four-Batch Teardown - Batch 1

Scope:

- remove steady-state control use of `PendingPackedRetryState`
- keep startup retry logic isolated
- keep Java startup contract and `hasPendingData()` unchanged
- keep MAT / IEC transport unchanged
- keep route / output tuple logic unchanged

Files touched:

- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`

What changed:

- steady-state pending packed output now owns a dedicated `PendingSteadyStateControlState`
- startup still uses `PendingPackedRetryState startupRetryState_`
- the hot flush loop was refactored to bind startup and steady-state control separately, without changing the intended steady-state behavior in this batch

Why this batch is risky:

- it touches the late-stream steady-state flush loop directly
- even though the intended change is structural only, any control-flow drift there can surface as playback degradation

Validation:

- install target: `192.168.50.37:5555`
- validation bundle: `/tmp/transport-validation-truehd-1774153252455.zip`
- comparison baseline bundle: `/tmp/transport-validation-truehd-1774151306697.zip`

Hard-gate read:

- `transportVerdict=PASS`
- burst chain remained `8 -> 64 -> 64 -> 64`
- raw player events still reached `ENDED`
- route stayed stable after startup:
  - `routeTupleChangeCountAfterStableStart=0`
  - `routeReopenCountAfterStart=0`

Important caveat:

- `runtimeVerdict=FAIL` remains noisy here, just like the accepted baseline, because the 120s validator summary still flags `POSITION_STALLED` on this ~64s sample even when playback reaches `ENDED`
- the grounded contract read for this batch therefore comes from transport, raw player events, and route stability first

Observed runtime shape vs accepted baseline:

- `timeToReadyMs`: `1073 -> 999`
- `droppedVideoFrames`: `10 -> 12`
- `audioUnderrunCount`: `1 -> 1`
- `writeAttemptCount`: `6959 -> 7107`
- `zeroWriteCount`: `2407 -> 2556`
- `remainderRetryEventCount`: `2410 -> 2561`
- `longestZeroWriteStreakMs`: `37 -> 65`
- `longestStuckRemainderMs`: `14 -> 81`

Batch 1 decision:

- keep Batch 1

Reason:

- no transport regression
- no route regression
- no end-of-stream regression
- the runtime summary degraded only within the same noisy verdict band already present on the accepted baseline
- the changed metrics are directionally worse, but they did not cross into a new outer-boundary failure mode and the code change is the required structural isolation step for the later scheduler teardown
