# TrueHD Media3 Structural Parity Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the active TrueHD startup and steady-state path so steady-state behavior is Media3-shaped, while keeping transport integrity and the Media3-facing contract stable.

**Architecture:** Treat Media3 as the primary behavioral model and Kodi as a secondary low-level reference only. Keep the Java `AudioSink` contract unchanged, move startup completion ownership fully into the native engine, split startup-only state from steady-state state, and collapse steady-state native handling toward a single pending-output truth model with no custom retry-admission heuristics in steady state.

**Tech Stack:** Android, Media3, JNI/C++, Kotlin/JUnit, ADB, Nexio passthrough validator

---

## File Map

### Production files

- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
  - split startup-only and steady-state native state explicitly
  - add native startup-completion query surface
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
  - isolate startup input/output ownership from steady-state ownership
  - remove steady-state retry-admission policy from control flow
  - make steady-state output follow a single pending-output truth model
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
  - make Java handoff passive once native startup completion begins
  - stop Java-side startup heuristics from choosing the steady-state path
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_truehd_session_bridge.cpp`
  - expose the new native startup-completion query to Java

### Test files

- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`
  - source-structure coverage for the steady-state output truth model
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdNativeAudioSinkSourceStructureTest.kt`
  - source-structure coverage for passive Java handoff

### Documentation files

- Create: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-media3-structural-risk-log.md`
  - checkpoint risky touched surfaces, current revert commits, and any regression notes
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-parity-working-notes.md`
  - record the structural-pass hypothesis and validation result

### Validation tooling

- Reference: `/Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh`
- Reference: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

## Guardrails

- Do not touch MAT/IEC transport capture, comparison, or packer plumbing.
- Do not change Java `AudioSink` method signatures or outer Media3 contract semantics.
- Do not change route tuple/config selection logic.
- Do not change `KodiTrueHdAudioTrackOutput` sizing in this pass.
- Do not add sleeps on the Java path or new blocking waits on the render path.
- Treat these as hard validation gates:
  - `transportVerdict=PASS`
  - burst chain remains `8 -> 64 -> 64 -> 64`
  - `routeChangeCountAfterStableStart=0`
  - `routeTupleChangeCountAfterStableStart=0`
  - `routeReopenCountAfterStart=0`

## Current Revert Point

- Root branch commit: `5253bb329`
- Media branch commit: `3757398fae`

If the structural pass regresses hard gates and the trigger cannot be identified quickly from the new bundle/logs, revert to these commits and stop.

---

### Task 1: Freeze The Structural-Pass Baseline And Risk Log

**Files:**
- Create: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-media3-structural-risk-log.md`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-parity-working-notes.md`

- [ ] **Step 1: Create the risk log with the current revert point**

Record:
- the root and media revert commits
- the risky files touched in this pass
- the instruction to analyze regressions before reverting

- [ ] **Step 2: Update the working notes with the structural-pass hypothesis**

Record the root-cause hypothesis:
- current divergence is no longer just retry cadence
- Java startup heuristics plus layered native ownership still diverge from Media3 steady-state behavior
- this pass will target state ownership, not buffer sizing

- [ ] **Step 3: Commit the docs checkpoint**

```bash
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality add \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-media3-structural-risk-log.md \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-parity-working-notes.md
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality commit -m "docs: checkpoint truehd structural parity baseline"
```

---

### Task 2: Add Failing Structural Coverage

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdNativeAudioSinkSourceStructureTest.kt`

- [ ] **Step 1: Add a failing native-structure test that rejects steady-state retry admission**

Require that `FlushTrueHdPackedQueueToHardwareLocked()` no longer calls:
- `ShouldRetrySteadyStatePendingPackedRemainderLocked(`
- `"steady_state_waiting_for_play_state"`

- [ ] **Step 2: Add a failing native-structure test that requires explicit startup/steady-state separation**

Require one or both of:
- explicit startup-only pending input state
- explicit steady-state pending input state
- explicit native startup-completion query

- [ ] **Step 3: Add a failing Java-structure test for passive handoff**

Require that `handleBuffer(...)` no longer calls:
- `maybeExitTrueHdStartupOwnership("handleBuffer")`

And require a passive/native-synced method such as:
- `syncTrueHdStartupStateFromNative`
- or equivalent

- [ ] **Step 4: Run focused tests to confirm they fail**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdAEEngineSourceStructureTest*" --tests "*KodiTrueHdNativeAudioSinkSourceStructureTest*"
```

Expected:
- FAIL on the new structural assertions

- [ ] **Step 5: Commit the failing tests**

```bash
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality add \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdNativeAudioSinkSourceStructureTest.kt
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality commit -m "test: capture truehd media3 structural parity gap"
```

---

### Task 3: Implement Native Startup/Steady-State Separation

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`

- [ ] **Step 1: Split startup-only and steady-state pending input state**

Add explicit native state for:
- startup pending passthrough input
- steady-state pending passthrough input

Do not keep one shared pending input object across both phases.

- [ ] **Step 2: Add explicit native startup-completion state/query**

Add native state that becomes true once steady-state ownership is acquired and startup-only state is drained or transferred.

Expose a native accessor that Java can query passively.

- [ ] **Step 3: Split the TrueHD flush path into startup and steady-state helpers**

Keep startup behavior separate from steady-state behavior:
- startup helper may keep the existing startup-specific pacing/recovery logic
- steady-state helper must not reuse startup retry admission heuristics

- [ ] **Step 4: Collapse steady-state output to one pending-output truth model**

For steady-state:
- if a pending packed remainder exists, attempt one non-blocking write in that flush
- if `written == 0`, keep the same pending output and return without invalidating the output
- if `written > 0`, keep the same pending output until it is fully drained
- if `written < 0`, keep the current negative-error recovery path

Do not gate steady-state writes on:
- `ShouldRetrySteadyStatePendingPackedRemainderLocked(...)`
- play-state retry admission heuristics
- startup ownership heuristics

- [ ] **Step 5: Remove steady-state play-state waiting from the active control path**

The structural target is:
- startup owns startup-only recovery
- steady-state owns only the pending output remainder

So the steady-state path should not emit or depend on:
- `"steady_state_waiting_for_play_state"`

- [ ] **Step 6: Run the focused structural tests**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdAEEngineSourceStructureTest*" --tests "*KodiTrueHdNativeAudioSinkSourceStructureTest*"
```

Expected:
- PASS for the new structural assertions that cover the native side

---

### Task 4: Make Java Handoff Passive

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_truehd_session_bridge.cpp`

- [ ] **Step 1: Add the JNI bridge method for native startup completion**

Add a native bridge entry such as:
- `nIsTrueHdStartupComplete(long nativeHandle)`

- [ ] **Step 2: Replace Java-side handoff mutation with passive syncing**

In `handleBuffer(...)`:
- stop calling `maybeExitTrueHdStartupOwnership("handleBuffer")`
- sync Java startup state from the native query instead
- once native startup completion is observed, stay on the steady-state path permanently for that session

- [ ] **Step 3: Keep startup/steady-state instrumentation truthful**

Update the Java-side detail fields so they still report:
- `startupActive`
- `startupCompleted`
- `handoffTriggered`
- `selectedPath`

But derive the transition from native completion, not Java heuristics.

- [ ] **Step 4: Run the focused structural tests again**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdAEEngineSourceStructureTest*" --tests "*KodiTrueHdNativeAudioSinkSourceStructureTest*"
```

Expected:
- PASS

- [ ] **Step 5: Commit the structural refactor**

```bash
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media add \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_truehd_session_bridge.cpp
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media commit -m "refactor: align truehd steady-state ownership with media3"
```

---

### Task 5: Build And Validate The Structural Pass

**Files:**
- Reference: `/Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-media3-structural-risk-log.md`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-parity-working-notes.md`

- [ ] **Step 1: Run focused builds**

Run:

```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac ':media:lib-exoplayer-kodi-cpp-audiosink:buildCMakeDebug[arm64-v8a][kodiCppAudioSinkJNI]'
./gradlew --no-daemon :app:assembleDebug
```

Expected:
- PASS

- [ ] **Step 2: Install the worktree APK**

Run:

```bash
adb -s 192.168.50.37:5555 install -r /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Expected:
- `Success`

- [ ] **Step 3: Validate with the project script**

Run:

```bash
/Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh
```

Expected:
- new bundle at `/tmp/transport-validation-truehd-*.zip`
- log at `/tmp/passthrough-validation-truehd.log`

- [ ] **Step 4: Check hard gates first**

Verify:
- `transportVerdict=PASS`
- burst chain `8 -> 64 -> 64 -> 64`
- `routeChangeCountAfterStableStart=0`
- `routeTupleChangeCountAfterStableStart=0`
- `routeReopenCountAfterStart=0`

- [ ] **Step 5: If there is a regression, analyze it before reverting**

If any hard gate regresses:
- inspect the new bundle and log
- identify whether the trigger is:
  - Java passive handoff
  - native startup/steady input split
  - steady-state pending-output simplification
- if the trigger is identifiable and local, correct it and rerun validation
- if the trigger remains unclear after investigation, revert to:
  - root `5253bb329`
  - media `3757398fae`

Record the result in the risk log before reverting.

- [ ] **Step 6: If hard gates hold, compare audio-shape metrics**

Compare against:
- `/tmp/transport-validation-truehd-1774133657269.zip`

Check:
- `zeroWriteCount`
- `remainderRetryEventCount`
- `audioUnderrunCount`
- `droppedVideoFrames`
- late `zero -> zero -> success` packet count

- [ ] **Step 7: Record the result in docs and commit/push**

Update:
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-media3-structural-risk-log.md`
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-parity-working-notes.md`

Then commit and push root + media with the new submodule pointer.

