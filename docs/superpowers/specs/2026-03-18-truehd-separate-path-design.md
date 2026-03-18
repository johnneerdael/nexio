# TrueHD Separate Path Design

## Goal

Reintroduce Dolby TrueHD/Atmos passthrough work on a fully separate implementation path so `AC-3`, `E-AC-3`, `DTS`, and `DTS-HD/DTS:X` remain on the restored known-good shared path.

## Constraints

- Detection must remain in the normal Media3 flow until `Format.sampleMimeType` is known.
- Separation starts only after TrueHD is authoritatively identified at sink configuration time.
- The restored shared path must remain untouched for:
  - `KodiNativeAudioSink`
  - `KodiActiveAEEngine`
  - `KodiAudioTrackOutput`
  - `KodiIecPipeline`
  - `kodi_cpp_session_bridge`
- `media/libraries/cpp_audiosink` must not be modified unless absolutely required for JNI bridge constraints.
- Runtime testing starts only after the full implementation and required diagnostics are in place.

## Baseline

- The current `E-AC3`, `DTS`, and `DTS-HD/DTS:X` path is the restored `c7c231b0fccafa9cc00b556d3ba105a74dbbc872` baseline in `media`.
- App-side sink wiring is the restored baseline path through `KodiNativeAudioSink`.
- Prior shared-path TrueHD experiments are not reused as a design basis.

## Detection Boundary

Detection remains on the existing Media3 path until the audio sink is configured with a resolved `Format`.

- Before detection: no routing changes.
- At `configure()` time: if `sampleMimeType == audio/true-hd`, route into the separate TrueHD stack.
- For all other codecs: stay on the restored shared sink path.

This keeps the split at the first reliable format boundary without moving detection earlier into speculative code.

## Architecture

### App Entry

- `PlayerRuntimeControllerInitialization.kt` will instantiate a dedicated entry sink instead of directly instantiating the shared sink.
- The entry sink will delegate:
  - non-TrueHD formats to the restored baseline `KodiNativeAudioSink`
  - TrueHD formats to a separate `KodiTrueHdNativeAudioSink`

### Separate TrueHD Java Layer

- `KodiTrueHdEntryAudioSink.java`
  - Thin routing sink.
  - Decides active delegate only after `configure()` sees the real format.
- `KodiTrueHdNativeAudioSink.java`
  - Dedicated Java bridge for TrueHD.
  - Owns TrueHD-only startup buffering, pending-data semantics, play/pause/flush handling, and byte-dump logging.

### Separate TrueHD Native Layer

- `KodiTrueHdAEEngine.h/.cpp`
  - TrueHD-only pending-input/output state.
  - TrueHD-only playback accounting and write flow.
- `KodiTrueHdIecPipeline.h/.cpp`
  - TrueHD parser-to-packer pipeline.
  - MAT-style aggregation, cadence, and padding.
- `KodiTrueHdAudioTrackOutput.h/.cpp`
  - TrueHD-only `AudioTrack` configuration and write behavior.
- `kodi_cpp_truehd_session_bridge.cpp`
  - Dedicated JNI bridge exports for the TrueHD sink.

Duplication is acceptable. Reopening shared method bodies is not.

## Implementation Against The Root-Cause Guide

### Phase A: Prove Byte Correctness Before Android Mutation

The TrueHD path must emit binary captures for:

1. Input TrueHD access units entering the packer
2. Packed MAT/IEC output produced by the native pipeline
3. Exact bytes handed to `AudioTrack.write`

Diagnostics must support validation of:

- IEC preamble sync words
- `Pc` / `Pd`
- packet sizes
- source access-unit counts
- burst spacing
- zero padding

### Phase B: Implement MAT-Style Transport

`KodiTrueHdIecPipeline` must implement MAT-style transport rather than raw AU-to-IEC wrapping.

Target invariants:

- 24-frame aggregation model, or a clearly justified equivalent
- `MAT_FRAME_SIZE = 61424`
- 2560-byte nominal frame-interval spacing
- constant-rate transport via explicit zero padding
- preserved logical burst boundaries

### Phase C: Preserve Bytes Across JNI / Java / AudioTrack

The TrueHD Java/native path must explicitly handle:

- partial writes
- retry behavior
- burst-unit integrity across retries
- no resample / mix / volume mutation on packed transport bytes
- byte-oriented logging at each boundary

### Phase D: Verify Android Route and Output Configuration

The TrueHD output layer must log:

- requested and opened audio encoding
- sample rate
- channel mask / channel count
- passthrough/direct route signals where available

This is not to change the route blindly; it is to make the route auditable after implementation.

## File Plan

### Create

- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdEntryAudioSink.java`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdIecPipeline.h`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdIecPipeline.cpp`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.h`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp`
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_truehd_session_bridge.cpp`

### Modify

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  - switch audio sink entry construction to the new TrueHD-aware entry sink
- `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/CMakeLists.txt`
  - add the new TrueHD-only native sources

## Logging Requirements

The implementation must be runtime-ready with byte validation from the first test run.

At minimum, logging must support:

- saving binary dumps or deterministic raw captures
- correlating each dump with:
  - codec
  - pts
  - AU count
  - burst size
  - MAT size
  - write offset

The point is to avoid another round of retrofitting diagnostics after failed runtime tests.

## Non-Goals

- Reworking the restored shared Atmos/DTS/DTS-HD path
- Reusing prior mixed shared/TrueHD experiments
- Optimizing for minimal code duplication
- Touching `media/libraries/cpp_audiosink` unless JNI bridge constraints make it unavoidable

## Risks

- MAT transport details may still require adjustment after byte-level validation.
- Android may still require TrueHD-specific route/config handling even with correct bytes.
- A fully separate path increases code volume, but that trade is accepted to protect the shared codecs.

## Success Criteria

- Non-TrueHD codecs still execute only on the restored baseline path.
- TrueHD executes only on the separate stack after format detection.
- The new stack implements the guide’s Phase A-D requirements before runtime testing begins.
- The first runtime test already produces enough logs/dumps for byte-level validation.
