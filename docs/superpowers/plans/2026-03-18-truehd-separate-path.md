# TrueHD Separate Path Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a separate Dolby TrueHD passthrough path that leaves the restored shared Atmos/DTS/DTS-HD path untouched and includes byte-level validation logging from the first runtime test.

**Architecture:** Add a TrueHD-only entry sink and a full separate Java/JNI/native TrueHD stack. Keep the current shared Kodi sink files as the stable baseline for every non-TrueHD codec. Implement MAT-style packing, constant-rate transport, and dump/log instrumentation in the TrueHD-only files.

**Tech Stack:** Kotlin, Java, Media3 audio sink integration, JNI/C++, Android `AudioTrack`, Kodi AudioEngine-derived utilities.

---

### Task 1: Add the TrueHD entry-point routing sink

**Files:**
- Create: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdEntryAudioSink.java`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Test: build verification only in this phase

- [ ] **Step 1: Define the routing contract**

Document in code comments that routing happens only after `configure()` knows the real `Format.sampleMimeType`, and that non-TrueHD remains on the shared baseline sink.

- [ ] **Step 2: Implement `KodiTrueHdEntryAudioSink`**

Create a forwarding entry sink that owns:
- one baseline `KodiNativeAudioSink`
- one dedicated `KodiTrueHdNativeAudioSink`
- an active delegate selected on `configure()`

- [ ] **Step 3: Route all non-TrueHD methods to the baseline sink**

Ensure non-TrueHD `configure`, `handleBuffer`, `play`, `pause`, `flush`, `hasPendingData`, and `playToEndOfStream` pass through unchanged to the baseline sink.

- [ ] **Step 4: Switch app wiring to the entry sink**

Modify `PlayerRuntimeControllerInitialization.kt` so `buildAudioSink()` returns the new entry sink when experimental Fire OS IEC passthrough is enabled.

- [ ] **Step 5: Compile the app**

Run: `./gradlew --no-daemon :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt \
  media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdEntryAudioSink.java
git commit -m "Add TrueHD entry sink routing"
```

### Task 2: Add the separate TrueHD Java/native bridge

**Files:**
- Create: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
- Create: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_truehd_session_bridge.cpp`
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/CMakeLists.txt`

- [ ] **Step 1: Define the JNI surface**

Specify the native calls the TrueHD sink needs for:
- session creation/release
- configure
- write
- play/pause/flush
- pending-data and write-error consumption
- byte-dump/log toggles

- [ ] **Step 2: Implement `KodiTrueHdNativeAudioSink`**

Add a dedicated Java sink with its own session lifecycle and no edits to `KodiNativeAudioSink`.

- [ ] **Step 3: Implement JNI bridge exports**

Create `kodi_cpp_truehd_session_bridge.cpp` with only the TrueHD-specific JNI entry points.

- [ ] **Step 4: Register new native sources**

Update `CMakeLists.txt` to compile the TrueHD-only bridge and engine files without touching the shared source list.

- [ ] **Step 5: Assemble the media module**

Run: `./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java \
  media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_truehd_session_bridge.cpp \
  media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/CMakeLists.txt
git commit -m "Add dedicated TrueHD JNI sink bridge"
```

### Task 3: Build the separate TrueHD engine and output path

**Files:**
- Create: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- Create: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Create: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.h`
- Create: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp`

- [ ] **Step 1: Copy baseline engine structure into dedicated TrueHD files**

Start from the current restored baseline behavior, but place it in separate TrueHD files instead of editing shared files.

- [ ] **Step 2: Add TrueHD-only output configuration hooks**

Implement TrueHD-specific `AudioTrack` configuration and write handling in `KodiTrueHdAudioTrackOutput`, including explicit logging of encoding, sample rate, and channel mask.

- [ ] **Step 3: Add explicit pending-data and playback accounting inside the TrueHD engine**

Keep all startup, drain, and write-accounting state local to the TrueHD engine.

- [ ] **Step 4: Ensure no shared JNI/native file is reopened**

Audit the change list and verify only the new TrueHD files plus `CMakeLists.txt` changed in native code.

- [ ] **Step 5: Assemble the media module**

Run: `./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h \
  media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp \
  media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.h \
  media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp
git commit -m "Add dedicated TrueHD engine and output path"
```

### Task 4: Implement MAT-style TrueHD packing and cadence

**Files:**
- Create: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdIecPipeline.h`
- Create: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdIecPipeline.cpp`

- [ ] **Step 1: Define the TrueHD packed-unit structure**

Include fields for:
- packed bytes
- write offset
- input bytes consumed
- pts
- duration
- source access-unit count
- MAT frame metadata needed for logging

- [ ] **Step 2: Implement AU aggregation**

Accumulate TrueHD access units toward the MAT transport model instead of raw AU-to-IEC output.

- [ ] **Step 3: Implement MAT sizing and constant-rate padding**

Encode the project invariants:
- MAT frame size `61424`
- 2560-byte nominal interval handling
- explicit zero padding

- [ ] **Step 4: Emit deterministic packet metadata**

Record enough per-packet metadata to verify `Pa`, `Pb`, `Pc`, `Pd`, access-unit counts, and burst cadence from logs/dumps.

- [ ] **Step 5: Assemble the media module**

Run: `./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdIecPipeline.h \
  media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdIecPipeline.cpp
git commit -m "Implement MAT-style TrueHD transport pipeline"
```

### Task 5: Add byte-level validation logging and dump support

**Files:**
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdIecPipeline.cpp`
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp`
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_truehd_session_bridge.cpp`

- [ ] **Step 1: Add input AU capture**

Capture the bytes entering the TrueHD pipeline with enough metadata to correlate them to packed output.

- [ ] **Step 2: Add packed MAT/IEC capture**

Capture the exact bytes emitted by the packer before Java/AudioTrack sees them.

- [ ] **Step 3: Add AudioTrack write capture**

Capture the exact bytes and offsets handed to `AudioTrack.write`.

- [ ] **Step 4: Add structured log metadata**

Log:
- packet size
- AU count
- pts
- duration
- `Pc` / `Pd`
- padding length
- write return values

- [ ] **Step 5: Assemble the media module**

Run: `./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java \
  media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp \
  media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdIecPipeline.cpp \
  media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp \
  media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_truehd_session_bridge.cpp
git commit -m "Add TrueHD byte validation logging"
```

### Task 6: Final integration verification before runtime testing

**Files:**
- Verify only

- [ ] **Step 1: Confirm shared-path files remain unchanged**

Run:

```bash
git -C media diff --name-only c7c231b0fccafa9cc00b556d3ba105a74dbbc872 -- \
  libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java \
  libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.cpp \
  libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiAudioTrackOutput.cpp \
  libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiIecPipeline.cpp \
  libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_session_bridge.cpp \
  libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java \
  libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java
```

Expected: no output

- [ ] **Step 2: Build the full app**

Run: `./gradlew --no-daemon :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Review log coverage against the guide**

Confirm the implementation can validate:
- input AU bytes
- packed MAT/IEC bytes
- AudioTrack write bytes
- route/config metadata

- [ ] **Step 4: Commit**

```bash
git add app media
git commit -m "Wire separate TrueHD path with validation support"
```
