# Change: Refactor TrueHD steady-state output toward Media3 pending-output truth

## Why

TrueHD transport integrity and outer Media3-facing runtime behavior are now good enough, but late-stream runtime validation still shows repeated steady-state zero-write cycles, audible degradation, and eventual underrun under a stable route tuple. The active native TrueHD path still models steady-state output as packet-scoped retry episodes instead of as one pending encoded output truth, which is the strongest remaining parity gap against stock Media3.

## What Changes

- Refactor the TrueHD steady-state native output path toward one pending encoded output truth
- Keep startup behavior isolated from steady-state output control
- Make Java observational once steady-state playback begins
- Preserve transport, route stability, and Media3-facing contract behavior
- Add validation-driven checkpoints after each pass before expanding the change

## Impact

- Affected specs: `passthrough-transport-validation`
- Affected code:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
  - `app/src/test/java/com/nexio/tv/debug/passthrough/*`
