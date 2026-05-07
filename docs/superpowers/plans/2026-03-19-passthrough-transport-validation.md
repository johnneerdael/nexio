# Passthrough Transport Validation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a debug-only passthrough transport validation mode that launches bundled golden samples, compares live bursts against golden references automatically, supports both UI and ADB control, and exports diagnostics for codec-specific troubleshooting.

**Architecture:** The app layer owns validation orchestration, sample selection, playback launch, and diagnostics export. The custom sink/JNI boundaries remain observational hooks that capture pre-packer, packed-burst, and pre-write data, feed a comparator, and surface compact per-burst results without mutating the playback path.

**Tech Stack:** Kotlin, Jetpack Compose, DataStore, Hilt, Android TV app debug settings, BroadcastReceiver/service-style debug entry points, Media3 custom sink integration, JNI/C++, repository-root golden assets and manifests.

---

## File Map

- Modify: `app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt`
  - Persist validation-mode settings and selected sample/debug options.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModel.kt`
  - Expose validation state/events to the debug UI.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsScreen.kt`
  - Add the validation UI section.
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationManifest.kt`
  - Manifest models for bundled golden samples and compare rules.
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationManifestLoader.kt`
  - Load and validate manifest data from packaged assets.
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationController.kt`
  - Central debug-only validation session state and orchestration.
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationReceiver.kt`
  - ADB command entry point.
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationPlaybackLauncher.kt`
  - Launch bundled validation sample playback.
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationExportService.kt`
  - Build/export diagnostics bundle.
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationComparator.kt`
  - Burst alignment and codec-specific comparison logic.
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationFailureCode.kt`
  - Failure code definitions.
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationCaptureMode.kt`
  - Dump/capture mode models.
- Modify: `app/src/main/AndroidManifest.xml`
  - Register debug-only receiver/service hooks if needed.
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
  - Add validation hook plumbing for debug sessions.
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_truehd_session_bridge.cpp`
  - Add JNI plumbing for validation session metadata/results.
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdIecPipeline.cpp`
  - Capture pre-packer/packed-burst records for validation mode.
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
  - Propagate validation records through queue/write boundaries.
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp`
  - Capture pre-write bytes and emit validation records.
- Create: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/TransportValidationNativeSession.{h,cpp}`
  - Native-side validation session state, hash bookkeeping, and record emission.
- Create: `app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationManifestLoaderTest.kt`
- Create: `app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationComparatorTest.kt`
- Create: `docs/passthrough-validation-ac3.md`
- Create: `docs/passthrough-validation-eac3.md`
- Create: `docs/passthrough-validation-eac3-joc.md`
- Create: `docs/passthrough-validation-dts-core.md`
- Create: `docs/passthrough-validation-dtshd-dtsx.md`
- Create: `docs/passthrough-validation-truehd.md`

## Asset Packaging Notes

- Source golden files live in repository root `assets/`.
- Implementation should package only the required golden validation files/manifest into debug-capable builds.
- Use a build-time copy step from repository-root `assets/` into debug-capable Android asset sets.
- Keep packaging logic explicit and testable; do not depend on ad hoc runtime filesystem access to repo-root assets.

### Task 1: Define Validation Manifest and Failure Model

**Files:**
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationManifest.kt`
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationFailureCode.kt`
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationCaptureMode.kt`
- Test: `app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationComparatorTest.kt`

- [ ] **Step 1: Write the failing manifest/comparison model tests**

Add tests covering:
- manifest codec family parsing
- burst-index alignment rules
- failure code mapping for common mismatch cases

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "com.nexio.tv.debug.passthrough.*"
```

Expected: test failures because the validation model classes do not exist yet.

- [ ] **Step 3: Implement the minimal manifest and failure-code models**

Add Kotlin models for:
- sample ids
- codec family
- reference file names
- expected route tuple
- compare mode / capture mode
- explicit failure codes including:
  - `PREAMBLE_MISMATCH`
  - `BURST_ALIGNMENT_FAILED`
  - `PACKER_TO_AUDIOTRACK_MUTATION`
  - `TRUEHD_MAT_INVALID`
  - `DTSHD_CORE_ONLY_FALLBACK`
  - `EAC3_AGGREGATION_MISMATCH`

- [ ] **Step 4: Run the tests to verify they pass**

Run the same command and confirm the model tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/debug/passthrough app/src/test/java/com/nexio/tv/debug/passthrough
git commit -m "feat: add transport validation manifest models"
```

### Task 2: Package and Load Bundled Golden Assets

**Files:**
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationManifestLoader.kt`
- Modify: build configuration/assets packaging files as needed to include bundled validation assets
- Test: `app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationManifestLoaderTest.kt`

- [ ] **Step 1: Write the failing manifest loader test**

Add tests for:
- loading the packaged manifest
- resolving required file names for each bundled sample
- rejecting incomplete/malformed sample entries

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "com.nexio.tv.debug.passthrough.TransportValidationManifestLoaderTest"
```

Expected: fail because the loader and packaged manifest path do not exist yet.

- [ ] **Step 3: Implement asset packaging and loader**

Package the required root `assets/` validation files into debug-capable builds and implement a loader that reads the packaged manifest.

- [ ] **Step 4: Run the tests to verify they pass**

Run the same targeted test command and confirm the loader resolves the packaged manifest correctly.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/debug/passthrough app/src/test/java/com/nexio/tv/debug/passthrough app/build.gradle.kts
git commit -m "feat: package bundled transport validation assets"
```

### Task 3: Add Validation State and Controller

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt`
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationController.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModel.kt`

- [ ] **Step 1: Write the failing controller/state tests**

Add tests for:
- enabling/disabling validation mode
- selecting a bundled sample
- configuring comparison/capture options
- exposing current validation state to the UI/ADB surfaces

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "com.nexio.tv.debug.passthrough.*Controller*"
```

Expected: fail because the controller/state plumbing does not exist yet.

- [ ] **Step 3: Implement DataStore keys and controller logic**

Add only the state needed for:
- validation enabled
- selected sample
- comparison mode
- dump mode/capture count
- export request bookkeeping

- [ ] **Step 4: Run tests and basic compile verification**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "com.nexio.tv.debug.passthrough.*"
./gradlew --no-daemon :app:compileDebugKotlin
```

Expected: controller tests pass and app compiles.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt app/src/main/java/com/nexio/tv/debug/passthrough app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModel.kt app/src/test/java/com/nexio/tv/debug/passthrough
git commit -m "feat: add transport validation controller state"
```

### Task 4: Add Debug UI Controls

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModel.kt`

- [ ] **Step 1: Add UI state contract tests if present, otherwise add ViewModel-level tests**

Cover:
- toggling validation mode
- selecting bundled sample
- selecting compare/dump modes
- starting playback
- exporting diagnostics

- [ ] **Step 2: Run the tests to verify they fail**

Run the targeted ViewModel/debug tests.

- [ ] **Step 3: Implement the debug UI section**

Add a focused debug section with:
- validation enabled toggle
- bundled sample selection
- compare mode selection
- capture mode selection:
  - preamble only
  - first `N` bursts
  - until failure
- binary dump enablement
- dump mode / burst count controls
- start / stop / export actions

- [ ] **Step 4: Compile and manually smoke-check debug UI**

Run:

```bash
./gradlew --no-daemon :app:compileDebugKotlin
```

Expected: compile success.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsScreen.kt app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsViewModel.kt
git commit -m "feat: add debug transport validation controls"
```

### Task 5: Add ADB Control Surface

**Files:**
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationController.kt`

- [ ] **Step 1: Write a failing receiver/controller command test where practical**

Cover:
- enable/disable actions
- sample selection
- start capture
- stop capture
- export

- [ ] **Step 2: Run the tests to verify they fail**

Run targeted debug receiver/controller tests.

- [ ] **Step 3: Implement the ADB-triggerable command entry point**

Use a debug-only broadcast receiver or equivalent command surface that maps ADB commands onto the controller.

Support:
- enable / disable validation
- select sample
- start validation
- stop validation
- export diagnostics
- clear previous session

- [ ] **Step 4: Verify command parsing and manifest registration**

Run:

```bash
./gradlew --no-daemon :app:assembleDebug
```

Expected: build succeeds with receiver registered correctly.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/debug/passthrough app/src/main/AndroidManifest.xml
git commit -m "feat: add adb passthrough validation controls"
```

### Task 6: Add Validation Playback Launcher

**Files:**
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationPlaybackLauncher.kt`
- Modify: relevant debug/controller wiring and playback entry files identified during implementation

- [ ] **Step 1: Write the failing launcher test**

Cover:
- bundled sample selection resolving to a concrete playback request
- validation session metadata attached to playback launch

- [ ] **Step 2: Run the tests to verify they fail**

Run targeted playback launcher tests.

- [ ] **Step 3: Implement the launcher**

Launch playback only for bundled validation samples and tag the session so sink/JNI hooks know validation is active.

- [ ] **Step 4: Run tests and compile verification**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "com.nexio.tv.debug.passthrough.*Launcher*"
./gradlew --no-daemon :app:compileDebugKotlin
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/debug/passthrough
git commit -m "feat: launch bundled validation playback sessions"
```

### Task 7: Add Native/JNI Validation Session Plumbing

**Files:**
- Create: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/TransportValidationNativeSession.h`
- Create: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/TransportValidationNativeSession.cpp`
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_truehd_session_bridge.cpp`
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`

- [ ] **Step 1: Write failing JVM/native boundary tests where feasible**

At minimum cover controller-to-JNI session metadata passing on the JVM side.

- [ ] **Step 2: Run tests to verify failure**

Run the targeted JVM tests and confirm missing plumbing.

- [ ] **Step 3: Implement validation session metadata plumbing**

Pass:
- selected sample id
- comparison mode
- dump mode
- burst limit
- validation enabled flag

without changing non-validation playback behavior.

- [ ] **Step 4: Verify JNI compilation**

Run:

```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git -C media add libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java
git -C media commit -m "feat: add native transport validation session plumbing"
```

### Task 8: Add Capture Hooks at the Three Validation Boundaries

**Files:**
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdIecPipeline.cpp`
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp`

- [ ] **Step 1: Add failing/native validation assertions where feasible**

At minimum add targeted native/unit test coverage if the module supports it; otherwise define a manual validation checklist in the task notes before implementation.

- [ ] **Step 2: Implement observational capture hooks**

Capture:
- pre-packer input bytes
- packed IEC burst bytes
- exact bytes passed to `AudioTrack.write`

Ensure validation mode off is a no-op.

- [ ] **Step 3: Emit structured per-burst records**

Each record should include:
- codec/sample id
- burst index
- PTS
- burst size
- logical preamble fields
- payload/padding counts
- hash summaries
- compact pass/fail summaries

Use stable binary dump names:
- `codec_packer_in_000001.bin`
- `codec_packed_000001.bin`
- `codec_audiotrack_write_000001.bin`

- [ ] **Step 4: Verify media module build**

Run:

```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git -C media add libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdIecPipeline.cpp libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp
git -C media commit -m "feat: add transport validation capture hooks"
```

### Task 9: Implement Burst Comparator and Failure Codes

**Files:**
- Create/Modify: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationComparator.kt`
- Modify: app/native record handling glue as needed
- Test: `app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationComparatorTest.kt`

- [ ] **Step 1: Write failing comparator tests**

Cover:
- aligned `reference burst N` vs `live burst N`
- pass cases for known-good data
- mismatch cases mapping to explicit failure codes

- [ ] **Step 2: Run the tests to verify they fail**

Run the targeted comparator tests.

- [ ] **Step 3: Implement the comparator**

Include:
- per-codec compare rules
- `reference burst N` vs `live burst N` alignment
- logical `Pa/Pb/Pc/Pd` normalization
- explicit failure codes
- compact pass/fail summaries

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "com.nexio.tv.debug.passthrough.TransportValidationComparatorTest"
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/debug/passthrough app/src/test/java/com/nexio/tv/debug/passthrough
git commit -m "feat: compare validation bursts against golden references"
```

### Task 10: Add Diagnostics Export

**Files:**
- Create: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationExportService.kt`
- Modify: controller/UI/ADB wiring as needed

- [ ] **Step 1: Write failing export tests where practical**

Cover:
- export bundle contents
- naming/location conventions
- inclusion of logs/dumps/metadata

- [ ] **Step 2: Run the tests to verify they fail**

Run targeted export tests.

- [ ] **Step 3: Implement export packaging**

Include:
- structured logs
- optional binary dumps
- selected sample metadata
- route/config snapshot including:
  - routed device
  - encoding
  - sample rate
  - channel mask
  - direct playback support result
  - `AudioTrack` state
  - per-burst comparison results
  - manifest version
  - asset checksum information

- [ ] **Step 4: Run tests and compile verification**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "com.nexio.tv.debug.passthrough.*Export*"
./gradlew --no-daemon :app:compileDebugKotlin
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/debug/passthrough
git commit -m "feat: export transport validation diagnostics"
```

### Task 11: Add Per-Codec Docs

**Files:**
- Create: `docs/passthrough-validation-ac3.md`
- Create: `docs/passthrough-validation-eac3.md`
- Create: `docs/passthrough-validation-eac3-joc.md`
- Create: `docs/passthrough-validation-dts-core.md`
- Create: `docs/passthrough-validation-dtshd-dtsx.md`
- Create: `docs/passthrough-validation-truehd.md`

- [ ] **Step 1: Draft codec-specific ADB collection docs**

For each bundled codec sample, document:
- enable commands
- sample selection commands
- capture commands
- export commands
- expected pass/fail fields to inspect

- [ ] **Step 2: Verify docs against actual implemented command surface**

Run the documented commands against a debug build and ensure they match reality.

- [ ] **Step 3: Commit**

```bash
git add docs/passthrough-validation-*.md
git commit -m "docs: add passthrough validation adb workflows"
```

### Task 12: End-to-End Validation and Integration

**Files:**
- Modify: updated manifest/docs/code touched above as needed

- [ ] **Step 1: Build debug and release-capable validation artifacts**

Run:

```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:assembleDebug
./gradlew --no-daemon :app:assembleDebug
```

- [ ] **Step 2: Run one UI-driven validation session**

Use the debug screen to launch a bundled sample and verify:
- playback starts
- bursts are compared automatically
- results are logged/exported

- [ ] **Step 3: Run one ADB-driven validation session**

Use the documented commands to:
- enable validation
- select a bundled sample
- start playback/capture
- export diagnostics

- [ ] **Step 4: Verify non-validation playback remains unchanged**

Run a normal non-validation playback session and confirm no validation behavior leaks into it.

- [ ] **Step 5: Update any remaining task checkboxes and commit**

```bash
git add docs/superpowers/plans/2026-03-19-passthrough-transport-validation.md openspec/changes/add-passthrough-transport-validation
git commit -m "docs: finalize passthrough transport validation plan"
```
