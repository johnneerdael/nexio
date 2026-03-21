# TrueHD Runtime Gap Closure Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve the remaining TrueHD audio-stutter/runtime gaps without regressing transport integrity or Media3-facing contract behavior.

**Architecture:** The active code already contains the native startup-vs-steady-state split and a native steady-state output-driven retry path. The remaining gaps are a Java-side startup/handoff controller that still acts as a cross-layer arbiter, incomplete runtime-truth/export assembly, and a late-stream native starvation policy that still uses a fixed steady-state zero-write backoff. The work must preserve the current burst chain, route stability, and player/video behavior while improving audio continuity and runtime observability.

**Tech Stack:** Android, Media3, JNI/C++, Kodi AE references, Nexio passthrough validator, Kotlin/JUnit, Gradle, ADB

---

## Scope And Superseded Plans

This plan supersedes the assumptions in:
- `/Users/jneerdael/Scripts/nexio/docs/superpowers/plans/2026-03-21-truehd-native-runtime-parity.md`
- `/Users/jneerdael/.gemini/tmp/nexio/2e1e3e2c-5bde-4261-b7c2-1d5fd0260eb6/plans/truehd-strict-audit-phase3.md`

Those plans assumed native Group 1 and much of native Group 2 were still missing. The active source now shows:
- separate native remainder slots in `startupPendingPackedOutput_` and `steadyStatePendingPackedOutput_`
- separate retry state in `startupRetryState_` and `steadyStateRetryState_`
- a steady-state retry helper that no longer uses playback-head and buffer-fit heuristics as the primary control gate

This plan therefore targets the gaps that are still active in source today.

## Guardrails

- Do not touch `iecPipeline_.Feed(...)`, packed-burst capture, audio-track write capture, or transport comparison behavior.
- Do not touch `EnsureTrueHdPassthroughOutputConfiguredLocked(...)` unless a later validation proves configuration itself is wrong.
- Do not reintroduce pre-`play()` native writes or any Java-side contract leak.
- Do not add sleeps on the playback/render path.
- Do not weaken route-stability behavior after startup.
- Treat transport `PASS`, `ENDED`, and stable post-start route metrics as hard regression gates.
- Treat operator-audible result as the primary success signal for audio-stutter work. Sink counters are supporting evidence, not the sole go/no-go rule.

## Active Source Truth

Reference files:
- `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationSessionStore.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollector.kt`
- `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationDiagnosticsExporter.kt`

Grounded code facts:
- Native Group 1 is present:
  - `startupPendingPackedOutput_`
  - `steadyStatePendingPackedOutput_`
  - `startupRetryState_`
  - `steadyStateRetryState_`
- Native Group 2 is largely present:
  - `ShouldRetrySteadyStatePendingPackedRemainderLocked(...)` is output-driven
  - steady-state positive progress resets retry episode state
- Java handoff control is still active:
  - `maybeExitTrueHdStartupOwnership("handleBuffer")`
  - `maybeExitTrueHdStartupOwnership("play")`
  - `maybeExitTrueHdStartupOwnership("playToEndOfStream")`
- Partial-write runtime events still drop the enriched startup/handoff detail.
- Runtime summary/export truth is still incomplete in valid runs.
- The native steady-state zero-write path still uses a fixed `kSteadyStateRetryZeroBackoffUs = 20000`.

Reference runtime artifacts:
- `/tmp/transport-validation-truehd-1774108753637.zip`
- `/tmp/transport-validation-truehd-1774115980501.zip`
- `/tmp/transport-validation-truehd-1774117400508.zip`

---

### Task 1: Freeze Current Baseline And Replace Stale Assumptions

**Files:**
- Reference: `/Users/jneerdael/Scripts/nexio/docs/truehd-runtime-strict-audit.md`
- Reference: `/Users/jneerdael/Scripts/nexio/docs/truehd-media3-parity-checklist.md`
- Reference: `/Users/jneerdael/Scripts/nexio/docs/superpowers/plans/2026-03-21-truehd-native-runtime-parity.md`
- Reference: `/Users/jneerdael/Scripts/nexio/docs/superpowers/plans/2026-03-21-truehd-runtime-gap-closure.md`

- [ ] **Step 1: Record the active baseline table**

Create a short working note under:
- `/Users/jneerdael/Scripts/nexio/docs/truehd-runtime-gap-closure-working-notes.md`

Include these runs:
- `1774108753637`
- `1774115980501`
- `1774117400508`

Required fields:
- `transportVerdict`
- `timeToReadyMs`
- `audioUnderrunCount`
- `writeAttemptCount`
- `zeroWriteCount`
- `partialWriteCount`
- `remainderRetryEventCount`
- `longestStuckRemainderMs`
- `continuousPlayingWindowSatisfied`

- [ ] **Step 2: Record the active source hotspots**

In the same working note, quote the exact source locations that justify the new grouped plan:
- native split state in `KodiTrueHdAEEngine.h`
- steady-state retry helper in `KodiTrueHdAEEngine.cpp`
- Java handoff mutation points in `KodiTrueHdNativeAudioSink.java`
- runtime-truth assembly points in `TransportValidationSessionStore.kt`

- [ ] **Step 3: Mark the stale assumptions explicitly**

Add a “Superseded assumptions” section to the working note:
- native Group 1 is not missing
- native Group 2 is not missing in the original planned form
- remaining gaps are Java handoff control, runtime truthfulness, and late-stream steady-state starvation

- [ ] **Step 4: Commit the note**

```bash
git add /Users/jneerdael/Scripts/nexio/docs/truehd-runtime-gap-closure-working-notes.md
git commit -m "docs: freeze active truehd runtime gap baseline"
```

---

### Task 2: Add Failing Coverage For Runtime Truthfulness

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationDiagnosticsExporterTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollectorTest.kt`
- Create if needed: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationSessionStoreTest.kt`
- Reference: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationSessionStore.kt`
- Reference: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollector.kt`
- Reference: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationDiagnosticsExporter.kt`

- [ ] **Step 1: Write a failing test for runtime verdict propagation**

Add a test that constructs a runtime snapshot with:
- sink-health evidence
- operator observation
- failure codes

Assert:
- `summary.json.runtimeVerdict` is not `UNKNOWN`
- `runtime-summary.json` includes `verdict`
- `runtime-summary.json` includes non-empty `failureCodes`

- [ ] **Step 2: Run the verdict test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*TransportValidationDiagnosticsExporterTest*"
```

Expected:
- FAIL because current export path still drops verdict truth in some valid cases

- [ ] **Step 3: Write a failing test for playback stats persistence**

Add a test that simulates:
- runtime session started
- `PlaybackStatsListener` attached
- player detached/exported

Assert:
- `playbackStats` remains available in the session snapshot used for export

- [ ] **Step 4: Run the collector test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*TransportValidationRuntimeCollectorTest*"
```

Expected:
- FAIL because `playback-stats.json` is currently null in valid runs

- [ ] **Step 5: Write a failing test for default-valued field completeness**

Add a test that exports:
- `runtime-summary.json` with zero/default-valued fields
- `playback-head-health.json` with zero/default-valued fields

Assert:
- default-valued structural fields are still present in JSON

- [ ] **Step 6: Run the exporter test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*TransportValidationDiagnosticsExporterTest*"
```

Expected:
- FAIL because default-valued fields are still omitted in the affected files

- [ ] **Step 7: Commit the failing tests**

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationDiagnosticsExporterTest.kt /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollectorTest.kt /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough/TransportValidationSessionStoreTest.kt
git commit -m "test: capture truehd runtime truth export gaps"
```

---

### Task 3: Implement Group A Runtime Truthfulness

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationSessionStore.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollector.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeValidation.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationDiagnosticsExporter.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`

- [ ] **Step 1: Fix partial-write detail enrichment**

In `recordTransportValidationWriteEvent(...)`, make `AUDIO_WRITE_PARTIAL` use the same enriched `detail` string as other write event types instead of raw `nativeDetail`.

- [ ] **Step 2: Persist playback stats before detach/export**

In `TransportValidationRuntimeCollector`, cache the last non-null playback stats summary in the active session before listener detach or snapshot teardown.

- [ ] **Step 3: Ensure merged runtime summary is authoritative**

In `TransportValidationSessionStore`, verify the merged runtime summary:
- carries `verdict`
- carries `failureCodes`
- is the same summary object exported into both `summary.json` and `runtime-summary.json`

- [ ] **Step 4: Preserve default-valued fields in export**

In `TransportValidationDiagnosticsExporter`, use the default-preserving serializer for:
- `runtime-summary.json`
- `playback-head-health.json`

- [ ] **Step 5: Run the targeted unit tests**

Run:
```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*TransportValidationDiagnosticsExporterTest*" --tests "*TransportValidationRuntimeCollectorTest*" --tests "*TransportValidationSessionStoreTest*"
```

Expected:
- PASS

- [ ] **Step 6: Run a focused app build**

Run:
```bash
./gradlew --no-daemon :app:assembleDebug
```

Expected:
- PASS

- [ ] **Step 7: Commit Group A**

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationSessionStore.kt /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollector.kt /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeValidation.kt /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationDiagnosticsExporter.kt /Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java
git commit -m "fix: restore truehd runtime truth export"
```

---

### Task 4: Add Failing Reproduction For Java Handoff Control

**Files:**
- Create: `/Users/jneerdael/Scripts/nexio/docs/truehd-java-handoff-reproduction.md`
- Reference: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
- Reference bundles:
  - `/tmp/transport-validation-truehd-1774115980501.zip`
  - `/tmp/transport-validation-truehd-1774117400508.zip`

- [ ] **Step 1: Document the failing crossover pattern**

Record the current failure pattern with exact event/log excerpts:
- Java still owns `startup_path`
- native reports `nativeRemainderOwnership=steady_state`
- no visible outer regression is required for this to still count as a parity mismatch

- [ ] **Step 2: Define the desired invariant**

Write the exact invariant in the doc:
- Java startup ownership may only transition at one bounded decision point
- `play()` and `playToEndOfStream()` must not mutate startup ownership
- `handleBuffer(...)` must be the only path selector
- the startup path must never issue steady-state direct writes after handoff is logically complete

- [ ] **Step 3: Link the exact source locations**

Reference:
- `maybeExitTrueHdStartupOwnership("handleBuffer")`
- `maybeExitTrueHdStartupOwnership("play")`
- `maybeExitTrueHdStartupOwnership("playToEndOfStream")`
- `handleTrueHdStartupBuffer(...)`
- `handleTrueHdSteadyStateBuffer(...)`

- [ ] **Step 4: Commit the reproduction note**

```bash
git add /Users/jneerdael/Scripts/nexio/docs/truehd-java-handoff-reproduction.md
git commit -m "docs: capture truehd java handoff parity gap"
```

---

### Task 5: Implement Group B Java Handoff Containment

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
- Reference only: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java`

- [ ] **Step 1: Remove non-buffer ownership mutations**

Remove `maybeExitTrueHdStartupOwnership(...)` from:
- `play()`
- `playToEndOfStream()`

Do not add new ownership mutation points outside `handleBuffer(...)`.

- [ ] **Step 2: Introduce a single path-decision helper**

Extract a helper such as:
```java
private boolean shouldRemainOnTrueHdStartupPath() {
  return !trueHdStartupCompleted
      || hasPendingPassthroughStartupWindow()
      || isTrueHdStartupRefillRequired()
      || shouldAllowBoundedTrueHdStartupRefill();
}
```

Refine the exact condition during implementation, but keep the rule:
- `handleBuffer(...)` is the only place that decides `startup_path` vs `steady_state_path`

- [ ] **Step 3: Make startup writes startup-only**

Adjust `handleTrueHdStartupBuffer(...)` so it only:
- fills the startup reservoir
- flushes the startup reservoir
- returns control once startup should hand off

Do not let `handleTrueHdStartupBuffer(...)` directly become a steady-state direct-write path in the same decision cycle.

- [ ] **Step 4: Keep `hasPendingData()` observational**

Do not add any ownership mutation back into:
- `hasPendingData()`
- `isEnded()`
- `getAudioTrackBufferSizeUs()`

- [ ] **Step 5: Run focused media builds**

Run:
```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac ':media:lib-exoplayer-kodi-cpp-audiosink:buildCMakeDebug[arm64-v8a][kodiCppAudioSinkJNI]'
./gradlew --no-daemon :app:assembleDebug
```

Expected:
- PASS

- [ ] **Step 6: Install and validate on `.37`**

Run:
```bash
adb -s 192.168.50.37:5555 install -r /Users/jneerdael/Scripts/nexio/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
bash /Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh
```

Expected hard gates:
- `transportVerdict=PASS`
- burst chain remains `8 -> 64 -> 64 -> 64`
- route metrics remain stable after startup
- playback still reaches `ENDED`

Expected parity improvement:
- no `startup_path` write sequence persists once Java has logically handed off

- [ ] **Step 7: Commit Group B**

```bash
git add /Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java
git commit -m "fix: constrain truehd java startup handoff"
```

---

### Task 6: Add Failing Reproduction For Late-Stream Native Starvation

**Files:**
- Create: `/Users/jneerdael/Scripts/nexio/docs/truehd-late-starvation-reproduction.md`
- Reference: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Reference bundles:
  - `/tmp/transport-validation-truehd-1774108753637.zip`
  - `/tmp/transport-validation-truehd-1774115980501.zip`

- [ ] **Step 1: Document the current steady-state backoff policy**

Quote the active source:
- `kSteadyStateRetryZeroBackoffUs = 20000`
- steady-state retry suppression inside `ShouldRetrySteadyStatePendingPackedRemainderLocked(...)`

- [ ] **Step 2: Record the mismatch against references**

Reference:
- Kodi retry behavior in `/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAESink.cpp`
- Kodi sink retry behavior in `/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Sinks/AESinkAUDIOTRACK.cpp`
- Media3 pending-buffer behavior in `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java`
- Media3 non-blocking output write in `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java`

Write the explicit mismatch:
- our native path suppresses retry for a fixed 20ms
- stock Media3 retries on the next natural renderer loop
- Kodi uses packet-duration-bound sleep on a dedicated sink thread, which we do not have

- [ ] **Step 3: Define the target policy**

Write the required steady-state rule:
- first steady-state zero write on a remainder does not trigger a fixed 20ms lockout
- any bounded backoff must be shorter than one render-loop starvation window
- positive write resets the retry episode
- no sleeps on the playback/render path

- [ ] **Step 4: Commit the reproduction note**

```bash
git add /Users/jneerdael/Scripts/nexio/docs/truehd-late-starvation-reproduction.md
git commit -m "docs: capture truehd late starvation reproduction"
```

---

### Task 7: Implement Group C Late-Stream Native Starvation Fix

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Possibly modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`

- [ ] **Step 1: Remove the fixed 20ms steady-state zero-write lockout**

Replace:
```cpp
constexpr int kSteadyStateRetryZeroBackoffUs = 20000;
```

With a bounded policy that does not waste an entire renderer opportunity window.

- [ ] **Step 2: Implement next-opportunity-first behavior**

Steady-state rule:
- first zero write on a remainder: keep remainder pending and allow retry on the next natural flush
- repeated zero writes on the same remainder: optional bounded backoff only if needed
- positive write: reset the retry episode immediately

- [ ] **Step 3: Keep diagnostic fields but remove them from control**

Continue recording:
- `playbackHeadDeltaFrames`
- `bufferFitDeltaFrames`
- `retryReason`
- `ownership`

Do not use them as the primary gate for steady-state retry admission.

- [ ] **Step 4: Run focused native/media builds**

Run:
```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac ':media:lib-exoplayer-kodi-cpp-audiosink:buildCMakeDebug[arm64-v8a][kodiCppAudioSinkJNI]'
./gradlew --no-daemon :app:assembleDebug
```

Expected:
- PASS

- [ ] **Step 5: Install and validate on `.37`**

Run:
```bash
adb -s 192.168.50.37:5555 install -r /Users/jneerdael/Scripts/nexio/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
bash /Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh
```

Required hard gates:
- `transportVerdict=PASS`
- route still stable after startup
- playback still reaches `ENDED`

Required audio-quality checks:
- operator report is not worse than baseline
- `audioUnderrunCount` does not worsen
- `longestStuckRemainderMs` improves or is unchanged
- `zeroWriteCount` is interpreted together with the operator report, not by itself

- [ ] **Step 6: If validation regresses, isolate before reverting**

Do not blindly revert the whole patch.
Instead:
- compare current bundle against `/tmp/transport-validation-truehd-1774115980501.zip`
- identify whether regression is:
  - transport
  - route stability
  - outer player lifecycle
  - raw sink churn only
- revert only the specific native starvation-policy change that caused the regression

- [ ] **Step 7: Commit Group C**

```bash
git add /Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp /Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h
git commit -m "fix: reduce truehd steady-state starvation"
```

---

### Task 8: Final Validation Gate And Checklist Rewrite

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/docs/truehd-media3-parity-checklist.md`
- Modify: `/Users/jneerdael/Scripts/nexio/docs/truehd-runtime-strict-audit.md`

- [ ] **Step 1: Run one final full validation pass**

Run:
```bash
bash /Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh
```

Record:
- bundle path
- log path
- operator observation

- [ ] **Step 2: Rewrite the parity checklist from the new grounded truth**

Update:
- active remaining gap
- resolved gap list
- explicit statement of whether Java handoff is still a live suspect
- explicit statement of whether late native starvation is still the live suspect

- [ ] **Step 3: Update the strict audit**

Replace stale statements such as:
- “native Group 1 still required”
- “Group 2 still missing”

With the current code-grounded truth.

- [ ] **Step 4: Run docs-only verification**

Run:
```bash
rg -n "Group 1|Group 2|20ms|startup_path|steady_state_path" /Users/jneerdael/Scripts/nexio/docs
```

Expected:
- no stale contradictory guidance remains

- [ ] **Step 5: Commit final docs**

```bash
git add /Users/jneerdael/Scripts/nexio/docs/truehd-media3-parity-checklist.md /Users/jneerdael/Scripts/nexio/docs/truehd-runtime-strict-audit.md /Users/jneerdael/Scripts/nexio/docs/superpowers/plans/2026-03-21-truehd-runtime-gap-closure.md
git commit -m "docs: refresh truehd runtime gap closure guidance"
```

---

Plan complete and saved to `/Users/jneerdael/Scripts/nexio/docs/superpowers/plans/2026-03-21-truehd-runtime-gap-closure.md`. Ready to execute?
