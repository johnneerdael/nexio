# TrueHD Audio Quality Full Parity Audit

## Scope

This audit compares the active TrueHD implementation in the worktree against:
- stock Media3 `DefaultAudioSink` / `AudioTrackAudioOutput`
- stock Kodi `ActiveAESink` / `AESinkAUDIOTRACK`

It is scoped to late-stream audio quality only.

Non-goals:
- re-auditing transport capture or MAT packing correctness
- re-opening Java `AudioSink` contract changes
- re-opening route selection or route tuple stability

## Active Code Under Audit

### Our implementation

- Java sink orchestration:
  - [/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java](/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java)
- Native TrueHD engine:
  - [/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp](/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp)
  - [/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h](/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h)
- Native `AudioTrack` wrapper:
  - [/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp](/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp)

### Stock references

- Media3:
  - [/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java](/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java)
  - [/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java](/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java)
- Kodi:
  - [/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAESink.cpp](/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAESink.cpp)
  - [/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Sinks/AESinkAUDIOTRACK.cpp](/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Sinks/AESinkAUDIOTRACK.cpp)

## Grounded Runtime References

- [/tmp/transport-validation-truehd-1774122320221.zip](/tmp/transport-validation-truehd-1774122320221.zip)
- [/tmp/transport-validation-truehd-1774123156108.zip](/tmp/transport-validation-truehd-1774123156108.zip)
- [/tmp/transport-validation-truehd-1774128629972.zip](/tmp/transport-validation-truehd-1774128629972.zip)
- [/tmp/transport-validation-truehd-1774129824226.zip](/tmp/transport-validation-truehd-1774129824226.zip)
- [/tmp/passthrough-validation-192.168.50.37-truehd-groupc-manual.log](/tmp/passthrough-validation-192.168.50.37-truehd-groupc-manual.log)

## What Is Already Good Enough

### Transport and route stability

- Burst chain remains `8 -> 64 -> 64 -> 64`.
- Transport verdict remains `PASS`.
- `routeChangeCountAfterStableStart=0`.
- `routeTupleChangeCountAfterStableStart=0`.
- `routeReopenCountAfterStart=0`.

This is no longer a transport or route problem.

### Outer Media3-facing behavior

- The custom Java sink still diverges from stock Media3 because it owns a TrueHD startup reservoir and handoff path.
- But in the active branch that divergence is not the leading cause of the late-stream audio defect.
- `hasPendingData()` is observational again for TrueHD handoff.
- The previously broken `forced_retry` / play-state bypass has already been removed from the active native path.

This should stay frozen unless a later audit proves otherwise.

## Current Code Shape Versus References

### Stock Media3

Stock Media3 keeps one pending encoded `outputBuffer` as the truth.

Key properties:
- no separate parser backlog plus packed-output remainder ownership
- no synthetic steady-state retry state
- a partial write just leaves the same buffer pending
- the next renderer opportunity retries it naturally

Relevant code:
- `DefaultAudioSink.drainOutputBuffer(...)`
- `AudioTrackAudioOutput.write(...)`

### Kodi

Kodi makes retry cadence explicit inside the sink path.

Key properties:
- if the sink returns `0`, Kodi sleeps roughly one packet duration and retries once
- if it still cannot write, it gives up and lets AE come back later
- raw passthrough sink retry behavior is bounded and packet-duration-shaped

Relevant code:
- `CAESinkAUDIOTRACK::AddPackets(...)`
- `CActiveAESink::OutputSamples(...)`

### Our active branch

The active branch is closer than earlier revisions, but it still differs in two important ways:

1. `ShouldRetrySteadyStatePendingPackedRemainderLocked(...)` uses a fixed repeated-zero backoff of `4000 us`.
2. `FlushTrueHdPackedQueueToHardwareLocked()` revisits the same steady-state remainder on natural renderer flushes much more aggressively than Kodi’s bounded packet-duration retry model.

## Main Remaining Parity Gap

### Gap 1: fixed 4 ms backoff is too small for the actual late-stream remainders

Current code:
- steady-state repeated-zero backoff is a hardcoded `4000 us`

Observed common late-stream remainders from `/tmp/transport-validation-truehd-1774129824226.zip`:
- `11904` bytes ~= `3.875 ms`
- `16000` bytes ~= `5.208 ms`
- `20096` bytes ~= `6.542 ms`
- `36480` bytes ~= `11.875 ms`
- `44672` bytes ~= `14.542 ms`
- full `61440`-byte MAT packet ~= `20.0 ms`

Observed packet-level sequences:
- `audio_write_zero -> audio_write_success`
- `audio_write_zero -> audio_write_zero -> audio_write_success`
- less commonly `audio_write_zero -> audio_write_zero -> audio_write_zero -> audio_write_partial`

Meaning:
- the engine is retrying the same remainder again before the sink has drained enough
- eventual success happens after materially larger playback-head movement
- the common remainder durations are often much larger than the current `4 ms` cooldown

This is the strongest current audio-quality gap.

### Gap 2: our cadence is still more aggressive than Kodi under a stable tuple

Kodi:
- zero write
- sleep roughly one packet duration
- retry once
- otherwise give up and let the outer loop come back later

Our active branch:
- zero write
- very short bounded defer
- immediate revisit on later natural flushes
- repeated `zero -> success` and `zero -> zero -> success` cycles on the same packet id

This is not a transport mismatch, but it is still an output-pacing mismatch.

### Gap 3: buffer sizing remains generic, but it is not the first fix boundary

`KodiTrueHdAudioTrackOutput::ConfigureInternal(...)` still uses:
- `targetBufferSize = max(minBufferSize, minBufferSize * 2)`

That is generic and not TrueHD-duration-aware.

However:
- the earlier headroom-only experiment regressed runtime behavior
- current evidence points more strongly at cadence than at sizing

So buffer sizing should stay deferred until the cadence gap is fixed and revalidated.

## Gaps That Are No Longer Primary

### Old play-state mismatch

The active branch no longer primarily fails on:
- `forced_retry`
- implicit steady-state retries while bypassing explicit retry reasons

That gap mattered earlier, but it is no longer the best explanation for the active late-stream stutter.

### First-attempt zero-write theory

The latest valid bundle disproves the earlier theory that the remaining churn comes from first-attempt zero writes with `offsetBytes=0`.

In `/tmp/transport-validation-truehd-1774129824226.zip`:
- exported steady-state `audio_write_zero` events all occur on true remainders with `offsetBytes>0`

## Audit Conclusion

The active branch is already past the old retry-admission problem. The remaining audio-quality mismatch is now:

> under a stable `IEC61937|192000|7.1` tuple, the native TrueHD engine revisits steady-state packed remainders too aggressively after zero writes, using a fixed `4 ms` defer that is smaller than many real remainder durations, whereas Kodi uses a bounded packet-duration-shaped retry model and stock Media3 simply keeps the pending encoded buffer as the truth until a later write opportunity.

That means the next grounded fix boundary should be:
- native only
- steady-state zero-write cadence only
- no transport changes
- no Java contract changes
- no route changes

And the next validation should use:
- pre-install of the worktree APK
- `/Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh`
- plus the same hard gates already in use for transport and route stability

## Secondary Suspects

The remaining documented non-primary suspects are tracked separately in:
- [/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-secondary-parity-suspects.md](/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-secondary-parity-suspects.md)

Those differences are intentionally deferred behind the current zero-write cadence fix. The
primary active gap for this branch remains:
- steady-state zero-write cadence under a stable `IEC61937|192000|7.1` tuple

The suspect inventory should only be reopened if:
- the cadence fix keeps transport and contract clean
- and audio quality still does not materially improve
