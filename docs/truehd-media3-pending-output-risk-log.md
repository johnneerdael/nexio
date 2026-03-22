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
