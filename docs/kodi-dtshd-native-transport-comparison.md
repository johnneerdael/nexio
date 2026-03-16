# DTS-HD Native Transport Comparison

## Scope

This note compares the remaining DTS-HD passthrough behavior in Nexio's custom Kodi IEC path against:

- stock Media3 audio sink transport
- Kodi's Android `AESinkAUDIOTRACK` IEC transport

The goal is to identify the correct remaining fix boundary after Media3-side startup ownership fixes were exhausted.

## Stock Media3

Relevant files:
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java`

Observed contract:
- `play()` only starts the output.
- `handleBuffer()` / `write(...)` owns the current input/output buffer progression.
- `hasPendingData()` is the renderer readiness signal.
- `AudioTrack.write(..., WRITE_NON_BLOCKING)` is used directly.
- Media3 has no concept of IEC burst-assembly windows.

Implication:
- Media3-side fixes are the right place only when Kodi IEC requirements differ from Media3 buffer ownership semantics.

## Kodi Android IEC Path

Relevant file:
- `media/xbmc/xbmc/cores/AudioEngine/Sinks/AESinkAUDIOTRACK.cpp`

Observed contract:
- AudioTrack is created, paused, and flushed on init.
- `AddPackets(...)` ensures the track is in PLAYING state before writes.
- IEC writes use `short[]` + `WRITE_BLOCKING`.
- On zero write, Kodi sleeps roughly one packet period and retries once.
- Kodi maintains real sink-delay/cache supervision via `m_delay`, `m_audiotrackbuffer_sec`, and pacing logic.

Implication:
- Kodi's IEC transport relies on explicit sink-fill supervision that our current non-blocking path does not replicate.

## Kodi Packer Behavior

Relevant file:
- `media/xbmc/xbmc/cores/AudioEngine/Utils/AEBitstreamPacker.cpp`

Observed contract:
- `PackEAC3()` is multi-access-unit and uses `m_repeat`.
- DTS/DTS-HD packing is one-frame-per-burst.

Implication:
- E-AC3 startup/ownership fixes belong on the Media3 side because the packer contract is structurally different.
- DTS/DTS-HD does not justify the same Media3-side burst-window architecture.

## Current Nexio DTS-HD State

Relevant files:
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.cpp`

Established facts from logs and audit trail:
- Stale pre-play writes were fixed already.
- Media3-side startup staging variants were explored and exhausted.
- DTS-HD startup can be improved from `2048 / 8192` to `4096 / 8192`, but this does not remove the A/V start gap.
- After `play()`, the path still collapses into repeated `384 -> 0` writes with `pendingPacked=1`.
- That post-start native transport signature is the dominant remaining defect.

## Fix Boundary

The remaining DTS-HD issue should now be treated as a native IEC transport problem, not another Media3 startup contract problem.

The next fix class should be limited to native transport behavior after `play()`:
- sink fill supervision
- handling of partial writes
- reconfigure/recovery on `-6`
- pacing policy around `pendingPackedOutput_`

The next fix class should explicitly avoid:
- more Media3-side readiness heuristics
- more DTS-HD startup staging variants
- reusing E-AC3 architectural fixes for DTS-HD
