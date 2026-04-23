# TrueHD Secondary Parity Suspects

This note lists the remaining known differences between the active TrueHD path and the stock
Media3 / stock Kodi references that are still plausible audio-quality suspects, but are not the
current first patch target.

Current first patch target remains:
- steady-state zero-write cadence in the native TrueHD engine

That target stays first because it is directly supported by the latest valid runtime bundles and is
the smallest native-only change that keeps transport and contract boundaries frozen.

## Why Kodi is the immediate behavioral reference, not full stock Media3

The short answer:
- Media3 is still the outer contract guardrail.
- Kodi is the closer behavioral reference for the current native write loop.

Why:
- stock Media3 does not have our custom native parser + packed-output remainder engine at all
- stock Media3 keeps one encoded pending buffer as the truth and retries naturally at the renderer
  level
- our current implementation already owns:
  - pending passthrough input
  - packed output packets
  - startup vs steady-state packed ownership
  - native retry state
- that means the safest near-term parity move is to make the native loop behave more like Kodi’s
  sink retry cadence, rather than trying to collapse the whole design into Media3’s simpler model
  in one step

So:
- Media3 tells us which contract surfaces must not regress
- Kodi is the better immediate model for native passthrough write cadence
- a full simplification toward Media3 may still be desirable later, but it is a larger
  architectural change and should not be bundled into the current audio-quality fix path

## Suspect Inventory

### Suspect 1: Java TrueHD startup reservoir and handoff controller

**Our code**
- [/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java](/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java)

**Reference**
- [/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java](/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java)

**Difference**
- we still have a Java-owned TrueHD startup reservoir, startup refill policy, and handoff logic
- stock Media3 does not have a TrueHD-specific startup reservoir like this

**Why it is still a suspect**
- it is a large behavioral divergence
- it still controls when bytes stay on `startup_path` versus move to `steady_state_path`
- it can still influence pacing before and around late-stream state transitions

**Why it is not the first next patch**
- the latest evidence points more directly at native zero-write cadence under a stable tuple
- changing this path has a higher risk of contract regressions and startup/video regressions

### Suspect 2: layered native ownership model is still more complex than both references

**Our code**
- [/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp](/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp)
- [/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h](/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h)

**Reference**
- [/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java](/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java)
- [/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAESink.cpp](/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAESink.cpp)

**Difference**
- we still manage:
  - `pendingPassthroughInput_`
  - `startupPendingPackedOutput_`
  - `steadyStatePendingPackedOutput_`
  - per-owner retry state
- Media3 does not layer ownership like this
- Kodi’s sink retry behavior is simpler and more local

**Why it is still a suspect**
- more ownership layers means more chances for cadence mistakes or truth mismatches
- it may still be causing unnecessary revisit pressure or delayed clear-down behavior

**Why it is not the first next patch**
- the current evidence now points to a specific cadence parameter problem inside the existing model
- collapsing the model would be a broader refactor, not a surgical audio-quality fix

### Suspect 3: startup retry policy is still heuristic-heavy

**Our code**
- `ShouldRetryStartupPendingPackedRemainderLocked(...)` in
  [KodiTrueHdAEEngine.cpp](/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp)

**Reference**
- [/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Sinks/AESinkAUDIOTRACK.cpp](/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Sinks/AESinkAUDIOTRACK.cpp)

**Difference**
- startup retry still considers playback-head delta, buffer-fit delta, cooldown, and meaningful
  previous progress
- Kodi’s passthrough sink is more direct and bounded

**Why it is still a suspect**
- although the current late-stream symptom is steady-state, startup heuristics may still shape the
  initial state that the late stream inherits

**Why it is not the first next patch**
- current stutter evidence is happening under stable post-start steady-state ownership
- changing startup heuristics again risks re-opening already-stabilized startup behavior

### Suspect 4: generic `AudioTrack` buffer sizing

**Our code**
- [/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp](/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp)

**Difference**
- `ConfigureInternal(...)` still uses generic sizing:
  - `targetBufferSize = max(minBufferSize, minBufferSize * 2)`
- it is not explicitly shaped around TrueHD packet duration

**Why it is still a suspect**
- if the sink buffer is poorly matched to 20 ms MAT packet cadence, late-stream remainders can be
  more likely

**Why it is not the first next patch**
- the earlier headroom-only experiment regressed runtime
- current evidence points more directly at retry cadence than at pure headroom
- sizing should be revisited only after the cadence fix is tested

### Suspect 5: native `HasPendingData()` truth is still more policy-heavy than Media3

**Our code**
- `HasPendingData()` in
  [KodiTrueHdAEEngine.cpp](/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp)

**Reference**
- `hasPendingData()` in
  [DefaultAudioSink.java](/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java)

**Difference**
- our native truth includes parser backlog, pending input, pending packed output, and written-vs-played
  frame accounting
- stock Media3 is simpler at this boundary

**Why it is still a suspect**
- if `hasPendingData` stays true too eagerly, the renderer may revisit the same pending remainder
  more aggressively than intended

**Why it is not the first next patch**
- the latest runtime bundles point more directly at the steady-state repeated-zero cooldown value
- changing pending-data truth has higher contract risk

### Suspect 6: late-stream recovery may still be too coupled to the same native retry episode

**Our code**
- steady-state retry bookkeeping in
  [KodiTrueHdAEEngine.cpp](/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp)

**Difference**
- we still explicitly track retry episodes, first offset, last offset, last played frames, and
  last buffer-fit frames
- stock Media3 does not expose this kind of explicit retry machine

**Why it is still a suspect**
- even after cadence is fixed, the bookkeeping model itself may still be too complex and still
  hide a simpler pending-buffer truth model underneath

**Why it is not the first next patch**
- there is now a smaller isolated cadence problem to test first

## Priority Order After The Current Cadence Fix

If the packet-duration cadence fix keeps transport and contract clean but audio is still not at
parity, the next suspects should be revisited in this order:

1. native `HasPendingData()` truth versus renderer revisit pressure
2. generic `AudioTrack` buffer sizing
3. startup reservoir / handoff complexity
4. larger native ownership-model simplification toward a more Media3-like pending-buffer truth

## Practical Rule

Do not bundle these suspects into the current cadence patch.

They should stay documented as:
- valid follow-up suspects
- not the first next fix boundary
- especially not worth reopening if the cadence fix already solves the audible defect

## Decision Gate After The Cadence Fix

Use this handoff rule after the packet-duration zero-write cadence change is validated:

- if the cadence fix keeps transport and contract clean and materially improves audible quality,
  stop there and do not reopen the secondary suspects
- if the cadence fix keeps transport and contract clean but audible quality is still not materially
  improved, the next follow-up plan should target exactly one of the secondary suspects in the
  priority order above
- do not bundle multiple secondary suspects into the first follow-up plan
- do not reopen buffer sizing, startup complexity, or `HasPendingData()` truth in the same patch as
  the cadence fix
