# TrueHD Media3 Structural Pass Risk Log

## Revert point

- Root branch: `5253bb329`
- Media branch: `3757398fae`

## Risk surfaces for this pass

- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_truehd_session_bridge.cpp`

## Rule for this pass

- If validation regresses transport or the outer contract boundary, inspect the new bundle/log first.
- If the trigger is identifiable, correct that trigger and rerun.
- If the trigger is not identifiable quickly, revert to the commits above and stop.

## Structural regression bundles

- `/tmp/transport-validation-truehd-1774136334796.zip`
  - trigger: Java switched to `steady_state_path` while the observed native remainder ownership was still `startup`, producing a zero-write loop on a small leftover startup tail.
- `/tmp/transport-validation-truehd-1774136686607.zip`
  - trigger: after suppressing the premature Java handoff, the startup path stayed active but continued to run entirely under the startup-owned native remainder, collapsing the burst chain to `8 -> 41 -> 14 -> 14`.
- `/tmp/transport-validation-truehd-1774136989212.zip`
  - trigger: the tighter startup-ownership guard kept Java on `startup_path`, but native startup ownership still never handed off and the run stalled with repeated startup zero writes.
- `/tmp/transport-validation-truehd-1774137389058.zip`
  - trigger: letting the startup handler direct-write once the startup reservoir drained did not restore the outer runtime; the startup-owned native remainder still stalled and the player still failed at `280ms`.

## Current decision

- The structural pass remains regressed on the outer runtime boundary.
- Revert structural code changes to the baseline commits above before continuing audio-quality work.

## Incremental restart baseline

- restored clean bundle: `/tmp/transport-validation-truehd-1774137621593.zip`
- hard gates restored:
  - `transportVerdict=PASS`
  - `runtimeVerdict=PASS`
  - `playerStateVerdict=PASS`
  - `continuousPlayingWindowSatisfied=true`

## Incremental pass order

- Step 1: native handoff-ready observation only
- Step 2: Java handoff becomes passive to native truth
- Step 3: native startup-vs-steady-state input ownership split

## Incremental pass rule

- Do not bundle Step 1, Step 2, and Step 3 into one runtime change.
- Validate with `/Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh` after each successful build/install.
- If a step regresses transport or the outer runtime boundary, isolate the triggering touched surface before reverting.

## Step 1: Native handoff-ready observation

- touched files:
  - `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
  - `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
  - `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_truehd_session_bridge.cpp`
  - `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`

- first validation bundle: `/tmp/transport-validation-truehd-1774138519900.zip`
  - result: transport and player-state stayed clean, but runtime degraded on `droppedVideoFrames=62`
  - likely trigger: the new native handoff-ready JNI query was added to the write-diagnostics hot path

- refined validation bundle: `/tmp/transport-validation-truehd-1774138851778.zip`
  - result: transport stayed `PASS`, player-state stayed `PASS`, route stability stayed clean, and the new `nativeHandoffReady=` field is present in log output
  - refinement: cache the native handoff-ready truth per Java handoff-decision cycle instead of re-querying it during write-event detail append
  - remaining caveat: runtime still exported `DEGRADED` on `droppedVideoFrames=44`, but sink churn returned to baseline-like levels, so this is treated as acceptable for Step 1 rather than a proven contract/transport regression
