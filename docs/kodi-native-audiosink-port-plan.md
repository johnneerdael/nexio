# Kodi Native AudioSink Port Plan

## Purpose

This document defines the full implementation plan for replacing the current Java IEC/MAT path with a Kodi-owned native audio pipeline when a feature flag is enabled.

The goal is not a JNI wrapper around the current Java packer. The goal is:

- native ownership of capability selection
- native ownership of passthrough parsing and normalization
- native ownership of TrueHD MAT assembly
- native ownership of IEC61937 packing
- native ownership of playback timing and pause handling
- native ownership of sink-state transitions and fallback policy

When the feature is enabled, Media3 should stop owning format-specific audio logic. Java should become a thin Android platform bridge and Media3 integration shell.

## Scope

### In Scope

- Port as much of Kodi's passthrough chain as possible with minimal logic changes.
- Preserve Kodi component boundaries as much as Android allows.
- Add a dedicated native-backed `AudioSink` path for Android TV / Android passthrough playback.
- Port Kodi-style capability selection into a native policy layer fed by Android capability facts.
- Keep the Java path as a fallback while the native path is developed and validated.

### Out of Scope

- Porting Kodi's desktop/Linux/ALSA/Pulse/Windows sink backends directly.
- Replacing Android framework audio APIs with a fully native device sink if Android encoded passthrough requires `AudioTrack`.
- Immediate removal of the current Java path before parity is proven.

## Hard Constraints

### Licensing

Before implementation starts, confirm whether shipping Kodi GPL-licensed C++ inside the product is acceptable. This is a hard blocker. If legal approval is not explicit, do not proceed beyond planning.

### Source Header Policy

- New original files created for this project must use the project's GPLv3 header.
- Kodi-derived source files must retain their original Kodi GPLv2-or-later headers.
- If a Kodi-derived file is modified, add an explicit local modification note directly below the retained Kodi header.
- Do not replace Kodi headers with project-local headers on imported Kodi source files.

### Android Platform Constraint

Even with native ownership of logic, the final output path may still need a Java `AudioTrack` bridge because Android encoded passthrough and IEC encapsulation are exposed through Android media APIs. The target is native ownership of behavior, not necessarily zero Java in the final write path.

## Branching Strategy

Planning work is isolated on:

- `nexio`: `feat/kodi-native-audiosink-port-plan`
- `media`: `feat/kodi-native-audiosink-port-plan`

Implementation should continue on these branches or descendants of them until the native path is stable.

## Current Java Components To Replace On The Native-Enabled Path

These files define the current Java-owned IEC path and will be bypassed or reduced to shims when the feature is enabled:

- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsDefaultAudioSink.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsIec61937AudioOutputProvider.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AeBitstreamPacker.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/PackerMat.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsDtsClassifier.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsStreamInfo.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioOutput.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioOutputProvider.java`

Primary validation suites:

- `media/libraries/exoplayer/src/test/java/androidx/media3/exoplayer/audio/FireOsKodiIecPackerParityTest.java`
- `media/libraries/exoplayer/src/test/java/androidx/media3/exoplayer/audio/FireOsIec61937AudioOutputProviderTest.java`
- `media/libraries/exoplayer/src/test/java/androidx/media3/exoplayer/audio/FireOsDtsClassifierTest.java`

## Native Target Architecture

### Stream Classification Policy

Media3 extractor classification is the primary stream-family signal for the native path.

- Container extractors classify the track and populate `Format.sampleMimeType`.
- The native path should treat `Format.sampleMimeType` as the authoritative family selection input.
- Native code should not re-guess or override the top-level family when Media3 already classified it.
- Native code may only refine behavior within the selected family.
- Native parsing still remains responsible for:
  - frame boundary detection
  - sync-word scanning
  - subtype refinement inside the extractor-selected family
  - burst sizing
  - MAT assembly
  - stateful accumulation across buffers

Examples:

- `MimeTypes.AUDIO_DTS` routes to the DTS family.
- `MimeTypes.AUDIO_DTS_HD` routes to the DTS-HD family.
- `MimeTypes.AUDIO_DTS_X` routes to the DTS UHD / DTS:X family.
- `MimeTypes.AUDIO_TRUEHD` routes to the TrueHD family.
- `MimeTypes.AUDIO_E_AC3` and `MimeTypes.AUDIO_E_AC3_JOC` route to the E-AC3 family.
- `MimeTypes.AUDIO_AC3` routes to the AC3 family.

This keeps the native port leaner and avoids duplicating container-level classification logic already handled correctly by Media3 on non-FireOS paths.

### High-Level Data Flow

1. `MediaCodecAudioRenderer` submits encoded audio to a custom Java `AudioSink`.
2. The custom Java `AudioSink` forwards encoded buffers, device capability snapshots, route changes, and lifecycle events to a native session.
3. The native session owns:
  - capability selection
  - stream-family execution based on extractor-provided `Format.sampleMimeType`
  - backlog buffering
  - TrueHD MAT assembly
  - E-AC3 accumulation
  - DTS / DTS-HD / DTS-UHD in-family handling
  - IEC packing
  - output timing
  - pause bursts
  - playback position accounting
4. Native writes packed/direct buffers to Android through a minimal Java bridge if required by `AudioTrack`.
5. Java exposes sink state back to Media3 but does not reinterpret the audio stream.

### Native Ownership Boundary

When the feature is enabled, Java must not:

- split or normalize encoded access units
- build MAT frames
- pack IEC61937 bursts
- infer DTS subtype
- adjust TrueHD timestamps
- generate pause bursts
- run capability probing heuristics that override native decisions

Java may still:

- instantiate and release `AudioTrack`
- expose route changes and device capabilities to native
- pass extractor-provided `Format` metadata, including `sampleMimeType`, to native as the primary stream-family selector
- translate native failures into Media3 `AudioSink` exceptions
- gate the feature flag and fallback path

## User-Controlled Audio Policy

Kodi exposes audio-engine policy controls above the packer itself. The native port should do the same.

The IEC packer and MAT code must remain unaware of user preference toggles. Those toggles belong in
the capability-selection and session-policy layers.

### Required User Settings

- master enable: `IEC Packer (Custom AudioSink from Kodi)`
- per-codec passthrough enable:
  - AC3
  - E-AC3
  - DTS
  - TrueHD
  - DTS-HD / DTS:X
- DTS-HD core fallback enable
- max PCM channel layout:
  - `2.0`
  - `2.1`
  - `3.0`
  - `3.1`
  - `4.0`
  - `4.1`
  - `5.0`
  - `5.1`
  - `7.0`
  - `7.1`
- passthrough delay workaround:
  - `Passthrough Audio Delay (IEC Packer)`

### UI Naming Policy

- rename `Fire OS - IEC Packer` to `IEC Packer (Custom AudioSink from Kodi)`
- remove `Fire OS - Compatibility Fallback` from the surfaced settings UI
- rename `Fire OS - Supervise Audio Delay` to `Passthrough Audio Delay (IEC Packer)`

The legacy compatibility-fallback stored preference may remain hidden for migration purposes, but
it should not be part of the exposed product UI or the new native policy path.

### Policy Wiring Boundary

- app settings UI and datastore own persisted user preferences
- `AudioCapabilities` / `AmazonQuirks` mirror the active runtime values into Media3 globals
- `KodiNativeUserAudioSettings` snapshots those values for the native path
- native capability selector decides passthrough vs PCM vs DTS-core fallback before packetization
- packer code only processes formats that policy has already allowed

## Kodi Components To Port With Minimal Logic Changes

The native implementation should preserve these Kodi components as intact as possible:

- `CDVDAudioCodecPassthrough`
- `CPackerMAT`
- `CAEBitstreamPacker`
- `CAEPackIEC61937`
- supporting `CAEStreamInfo` structures needed by the chain
- capability-selection logic inspired by `CAEDeviceInfo` and sink support selection

Do not start with a "Java-style rewrite in C++". Start with Kodi's ownership model and adapt the Android boundaries around it. Do not reimplement container-level family detection in native when Media3 has already classified the track.

### Current Provenance Rule For Active Native Logic

- active packer logic must come from Kodi-derived sources, not from the prior Java port
- custom native files may exist only as JNI/session/Android adapter layers
- if a codec path still depends on custom packing or parsing logic, that codec path is not parity-safe yet and should stay disabled on the Kodi-native path until the Kodi source equivalent is ported

Current state after the `CAEBitstreamPacker` import:

- Kodi-derived and active:
  - `CAEPackIEC61937`
  - `CPackerMAT`
  - `CAEBitstreamPacker`
  - `CAEStreamInfo` / `CAEStreamParser`
  - reduced `CDVDAudioCodecPassthrough`
  - reduced `AEAudioFormat`
  - reduced `AEDeviceInfo`
  - reduced `ActiveAE` passthrough policy slice
  - reduced `CActiveAESettings`
  - reduced `CActiveAESink`
  - reduced `CAESinkAUDIOTRACK` capability enumeration layer
- still custom adapter code:
  - JNI bridge
  - native session wrapper
  - Android passthrough engine shell
- still pending from Kodi before the sink/policy layer is considered complete:
  - wider `ActiveAESink` state machine behavior
  - Android `AESinkAUDIOTRACK` live write / delay / drain semantics
  - any remaining CRC-dependent validation paths that are still locally adapted rather than linked to Kodi/FFmpeg exactly

### Native Parity Harness

- add Android-side native session parity tests under `lib-exoplayer-kodi-native-sink/src/androidTest`
- initial coverage should target:
  - DTS core IEC packet parity
  - TrueHD MAT plus IEC packet parity
- extend to DTS-HD once a stable authoritative fixture is available for the parser-driven path
- local development may only be able to compile these tests without executing them if no Android device or emulator is attached

## New Media3 Module

Create a dedicated native extension module instead of embedding JNI directly into `lib-exoplayer`.

### New Module

- `media/libraries/exoplayer_kodi_native_sink/`

### Reason

- Matches Media3's existing native-extension pattern.
- Keeps native toolchain and ABI packaging isolated.
- Allows feature-gated dependency from `lib-exoplayer`.
- Avoids forcing native artifacts into every consumer immediately.

## Planned New Files

### Java / Android Layer

- `media/libraries/exoplayer_kodi_native_sink/build.gradle`
- `media/libraries/exoplayer_kodi_native_sink/src/main/AndroidManifest.xml`
- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativeLibrary.java`
- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativeAudioSink.java`
- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativeAudioSinkFactory.java`
- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativeCapabilitySnapshot.java`
- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativePlaybackDecision.java`
- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativePacketMetadata.java`
- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativeException.java`
- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/AndroidAudioTrackBridge.java`

### JNI / Native Layer

- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/CMakeLists.txt`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/jni_bridge.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiAndroidPassthroughEngine.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiAndroidPassthroughEngine.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiNativeSinkSession.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiNativeSinkSession.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiCapabilitySelector.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiCapabilitySelector.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AndroidCapabilityAdapter.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AndroidCapabilityAdapter.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AndroidAudioTrackSinkBridge.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AndroidAudioTrackSinkBridge.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AEStreamInfoCompat.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AEStreamInfoCompat.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/DVDAudioCodecPassthrough.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/DVDAudioCodecPassthrough.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/PackerMAT.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/PackerMAT.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AEBitstreamPacker.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AEBitstreamPacker.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AEPackIEC61937.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AEPackIEC61937.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/RingBuffer.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/RingBuffer.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/ClockTracker.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/ClockTracker.cpp`

### Tests

- `media/libraries/exoplayer_kodi_native_sink/src/test/java/androidx/media3/exoplayer/audio/KodiNativeCapabilitySelectorTest.java`
- `media/libraries/exoplayer_kodi_native_sink/src/test/java/androidx/media3/exoplayer/audio/KodiNativeAudioSinkTest.java`
- `media/libraries/exoplayer_kodi_native_sink/src/androidTest/java/androidx/media3/exoplayer/audio/KodiNativeAudioSinkInstrumentedTest.java`
- `media/libraries/exoplayer_kodi_native_sink/src/test/jni/kodi_native_parity_test.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/test/jni/kodi_native_fixture_runner.cpp`

## Planned Existing Files To Touch

### Media3 Build / Module Wiring

- `media/core_settings.gradle`
- `media/settings.gradle`
- `media/libraries/exoplayer/build.gradle`

### Media3 Audio Integration

- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsDefaultAudioSink.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsIec61937AudioOutputProvider.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsStreamInfo.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioOutput.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioOutputProvider.java`

### Existing Tests To Extend Or Reuse

- `media/libraries/exoplayer/src/test/java/androidx/media3/exoplayer/audio/FireOsKodiIecPackerParityTest.java`
- `media/libraries/exoplayer/src/test/java/androidx/media3/exoplayer/audio/FireOsIec61937AudioOutputProviderTest.java`
- `media/libraries/exoplayer/src/test/java/androidx/media3/exoplayer/audio/FireOsDtsClassifierTest.java`

### Planning Artifact

- `docs/kodi-native-audiosink-port-plan.md`

## Phase Breakdown

## Phase 0: Legal, Fixtures, and Architecture Freeze

### Goal

Unblock the project legally and freeze the ownership model before code is written.

### Created Files

- `docs/kodi-native-audiosink-port-plan.md`

### Touched Files

- none in `media`

### Tasks

- Confirm GPL distribution acceptability for shipping Kodi-derived C++.
- Freeze the native-owned architecture defined in this document.
- Gather and catalog parity fixtures:
  - AC3
  - E-AC3
  - DTS core
  - DTS-HD MA
  - DTS-UHD if present
  - TrueHD / Atmos
- Catalog known failing real-world streams and expected Kodi behavior.

### Exit Criteria

- Legal approval is explicit.
- Fixture inventory is checked in or documented.
- Native ownership boundary is approved.

## Phase 1: Native Module Scaffolding

### Goal

Add a new Media3 native extension module and loadable library skeleton.

### Created Files

- `media/libraries/exoplayer_kodi_native_sink/build.gradle`
- `media/libraries/exoplayer_kodi_native_sink/src/main/AndroidManifest.xml`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/CMakeLists.txt`
- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativeLibrary.java`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/jni_bridge.cpp`

### Touched Files

- `media/core_settings.gradle`
- `media/libraries/exoplayer/build.gradle`

### Tasks

- Register the new module in Media3 Gradle settings.
- Follow the existing native module pattern used by decoder extensions.
- Add a library loader and a trivial smoke-test JNI function.
- Verify standalone and composite builds can see the new module.

### Exit Criteria

- Module builds in `media`.
- Module builds in composite `nexio`.
- JNI library loads on device and in tests.

## Phase 2: Java Shell For Native Sink

### Goal

Introduce a feature-gated Java `AudioSink` shell that delegates all enabled-path logic to native.

### Created Files

- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativeAudioSink.java`
- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativeAudioSinkFactory.java`
- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativeException.java`

### Touched Files

- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsDefaultAudioSink.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioOutput.java`

### Tasks

- Add a feature flag for the native path.
- Define how the renderer picks the native sink.
- Keep the Java shell free of format-specific logic.
- Translate native errors to `AudioSink`/`AudioOutput` exceptions.

### Exit Criteria

- Media3 can instantiate the native sink path under a feature flag.
- Disabled path remains unchanged.

## Phase 3: Android Capability Snapshot and Native Capability Selection

### Goal

Replace Media3's probe-matrix heuristics with a native Kodi-style capability selector fed by Android capability facts.

### Created Files

- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativeCapabilitySnapshot.java`
- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativePlaybackDecision.java`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiCapabilitySelector.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiCapabilitySelector.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AndroidCapabilityAdapter.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AndroidCapabilityAdapter.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/test/java/androidx/media3/exoplayer/audio/KodiNativeCapabilitySelectorTest.java`

### Touched Files

- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsIec61937AudioOutputProvider.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioOutputProvider.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsStreamInfo.java`

### Tasks

- Gather Android capability facts in Java without making passthrough decisions there.
- Feed that snapshot to native.
- Port Kodi-like selection logic for:
  - raw vs IEC
  - stereo vs multichannel carrier
  - stream-type support
  - fallback decisions
- Disable the current FireOS probe matrix on the native-enabled path.
- Keep Media3 extractor MIME classification as the primary stream-family input into native.

### Exit Criteria

- Native returns a playback decision for each test capability snapshot.
- No Java heuristic probing remains active on the native-enabled path.
- The native selector consumes extractor-provided MIME family information rather than re-detecting family from payload bytes.

## Phase 4: Native Sink Session Skeleton

### Goal

Create a native session object that owns lifecycle, buffering, route changes, and packet dequeue.

### Created Files

- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiNativeSinkSession.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiNativeSinkSession.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiAndroidPassthroughEngine.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiAndroidPassthroughEngine.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativePacketMetadata.java`

### Touched Files

- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/jni_bridge.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativeAudioSink.java`

### Tasks

- Define session create / configure / queue / drain / play / pause / flush / stop / reset / release.
- Add native backlog handling across buffers.
- Add route-change invalidation and session reconfiguration.
- Establish packet metadata returned from native:
  - kind
  - bytes
  - total frames
  - normalized access units
  - effective PTS

### Exit Criteria

- Native session API is stable enough to support format-specific stages.
- Java shell can create and talk to native session successfully.

## Phase 5: Port `CAEPackIEC61937`

### Goal

Port Kodi's IEC packing code with minimal logic changes and validate pure burst parity.

### Created Files

- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AEPackIEC61937.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AEPackIEC61937.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/test/jni/kodi_native_parity_test.cpp`

### Touched Files

- `media/libraries/exoplayer/src/test/java/androidx/media3/exoplayer/audio/FireOsKodiIecPackerParityTest.java`

### Tasks

- Port AC3, E-AC3, DTS core, DTS-HD, TrueHD IEC packing.
- Port pause-burst generation.
- Reuse current parity fixtures where possible.
- Add native host-side parity runner before integrating with the sink.

### Exit Criteria

- Native burst bytes match Kodi for fixture coverage.
- Java JNI bridge can call the native packer in isolation.

## Phase 6: Port `CAEBitstreamPacker`

### Goal

Port Kodi's format-agnostic bitstream packer and all stateful burst accumulation behavior.

### Created Files

- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AEBitstreamPacker.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AEBitstreamPacker.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AEStreamInfoCompat.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AEStreamInfoCompat.cpp`

### Touched Files

- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiNativeSinkSession.cpp`

### Tasks

- Port pause dedupe.
- Port E-AC3 repeat-count switching rules.
- Port DTS-HD burst-period handling.
- Port output-rate rules.

### Exit Criteria

- Native bitstream packer reproduces Kodi fixture bytes and state transitions.
- Existing Java parity tests can be mirrored against native outputs.

## Phase 7: Port `CPackerMAT`

### Goal

Port Kodi's MAT assembler and move all TrueHD accumulation out of Java.

### Created Files

- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/PackerMAT.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/PackerMAT.cpp`

### Touched Files

- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiNativeSinkSession.cpp`
- `media/libraries/exoplayer/src/test/java/androidx/media3/exoplayer/audio/FireOsIec61937AudioOutputProviderTest.java`

### Tasks

- Preserve Kodi's framing, padding, seek detection, and MAT markers.
- Make native TrueHD output appear only when a full MAT packet exists.
- Attach the earliest contributing PTS to emitted MAT packets.
- Remove Java-side TrueHD MAT ownership from the enabled path entirely.

### Exit Criteria

- TrueHD parity fixtures match Kodi.
- Known TrueHD stutter sample is ready for end-to-end device validation.

## Phase 8: Port `CDVDAudioCodecPassthrough` Ownership Model

### Goal

Port the parser/backlog/session behavior that Kodi uses above MAT and IEC packing.

### Created Files

- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/DVDAudioCodecPassthrough.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/DVDAudioCodecPassthrough.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/RingBuffer.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/RingBuffer.cpp`

### Touched Files

- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiNativeSinkSession.cpp`

### Tasks

- Port backlog handling and partial-buffer accumulation.
- Port sync recovery / seek reset behavior.
- Port stream info updates as input is parsed.
- Ensure DTS, E-AC3, and TrueHD ownership mirrors Kodi's codec layer while still using Media3's extractor MIME family as the initial route into the native path.

### Exit Criteria

- Native session now owns normalization before final packing.
- Java no longer inspects encoded payloads on the native-enabled path.

## Phase 9: Native Sink Clock, Pause, and Playback Position

### Goal

Move timing ownership to native so Media3 no longer runs format-specific clock corrections on the native path.

### Created Files

- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/ClockTracker.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/ClockTracker.cpp`

### Touched Files

- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiNativeSinkSession.cpp`
- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativeAudioSink.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsDefaultAudioSink.java`

### Tasks

- Move pause-burst ownership native.
- Move playback position accounting native.
- Make Java `getPositionUs()` a thin native query on the enabled path.
- Remove native-enabled reliance on `syntheticPauseRemainingUs` and other Java timing workarounds.

### Exit Criteria

- Native sink reports stable position through pause/resume/seek cycles.
- Java timing hacks are no longer active on the native path.

## Phase 10: Android AudioTrack Bridge

### Goal

Constrain Java to a minimal transport bridge if Android encoded passthrough still requires `AudioTrack`.

### Created Files

- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/AndroidAudioTrackBridge.java`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AndroidAudioTrackSinkBridge.h`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/AndroidAudioTrackSinkBridge.cpp`

### Touched Files

- `media/libraries/exoplayer_kodi_native_sink/src/main/java/androidx/media3/exoplayer/audio/KodiNativeAudioSink.java`
- `media/libraries/exoplayer_kodi_native_sink/src/main/jni/KodiNativeSinkSession.cpp`

### Tasks

- Configure `AudioTrack` from native-selected mode only.
- Write direct buffers from native-owned packets.
- Expose playback head and timestamp data back to native.
- Keep no stream parsing or format decisions in the bridge.

### Exit Criteria

- Final platform write path is a transport shim only.
- All behavior above it is native-owned.

## Phase 11: Integration Cleanup

### Goal

Strip Java-enabled-path logic down to a compatibility fallback and remove duplicate active ownership.

### Touched Files

- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsIec61937AudioOutputProvider.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsDefaultAudioSink.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AeBitstreamPacker.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/PackerMat.java`
- `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsDtsClassifier.java`

### Tasks

- Ensure native-enabled path bypasses Java packet normalization entirely.
- Keep Java implementation only as fallback/reference until removal is safe.
- Remove dead native-enabled branches and redundant telemetry paths.

### Exit Criteria

- The feature-flagged native path is single-owner end to end.
- Java fallback path still works independently.

## Test Strategy By Phase

### Host Native Tests

- Byte parity vs Kodi outputs for:
  - AC3
  - E-AC3
  - DTS core
  - DTS-HD
  - TrueHD / MAT / IEC
- State-transition tests:
  - E-AC3 repeat-count changes
  - DTS short-header prefixes
  - TrueHD seek and partial MAT flush
  - pause burst dedupe

### Java / Native Integration Tests

- Verify JNI direct-buffer round trips.
- Verify metadata parity with native packet outputs.
- Verify route change and reset semantics.

### Device Tests

- Known failing TrueHD/Atmos sample
- Known failing DTS-HD sample
- AC3 known-good sample
- E-AC3 known-good sample
- DTS core known-good sample
- pause/resume
- seek
- flush
- sink recreation
- route hot swap

## Rollout Strategy

### Feature Flags

- `fireos_kodi_native_sink_enabled`
- `fireos_kodi_native_capability_selection_enabled`
- `fireos_kodi_native_truehd_enabled`
- `fireos_kodi_native_eac3_enabled`
- `fireos_kodi_native_dts_enabled`
- `fireos_kodi_native_pause_clock_enabled`

### Rollout Order

1. Capability selection only
2. Native packer unit parity only
3. Native TrueHD vertical slice
4. Native E-AC3
5. Native DTS / DTS-HD
6. Native pause / timing ownership
7. Full native-enabled sink path

## Definition of Done

The port is complete when all of the following are true:

- The native feature path owns capability selection, normalization, MAT, IEC, timing, and sink-state behavior.
- Media3 Java code no longer performs format-specific audio handling on the enabled path.
- Native fixture outputs show zero byte differences against Kodi for covered formats.
- On-device playback of the known failing DTS-HD and TrueHD samples matches Kodi behavior without crash or stutter.
- Pause, seek, flush, route change, and sink reopen behavior are stable on device.
- The Java fallback path remains functional while the native path is still feature gated.

## Phase 1 First Vertical Slice Recommendation

Start implementation with the smallest meaningful Kodi-owned path:

1. Native module scaffolding
2. Java shell sink
3. Android capability snapshot
4. Native capability selector
5. Native `AEPackIEC61937`
6. Native `CAEBitstreamPacker`
7. Native `CPackerMAT`
8. TrueHD-only native playback path

This gives the highest-value proof point first because TrueHD is where the current architecture diverges the most from Kodi.
