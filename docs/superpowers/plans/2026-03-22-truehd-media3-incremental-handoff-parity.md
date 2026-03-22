# TrueHD Media3 Incremental Handoff Parity Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the active TrueHD path toward Media3-shaped startup and steady-state behavior in three validated steps without regressing transport or the outer `AudioSink` contract.

**Architecture:** Keep Media3 as the primary behavioral model and treat the current native engine as the implementation detail that must be made more passive and truth-driven. Add explicit native handoff-ready observation first, then make Java handoff follow that native truth, then refine native startup-vs-steady-state routing only after the first two steps validate cleanly.

**Tech Stack:** Android, Media3, JNI/C++, Kotlin/JUnit, ADB, Nexio passthrough validator

---

## File Map

### Production files

- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
  - add explicit native steady-state handoff-ready query surface
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
  - implement the native handoff-ready query
  - later split native startup input ownership from steady-state input ownership
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_truehd_session_bridge.cpp`
  - expose the native handoff-ready query to Java
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
  - Step 1: export/log native handoff-ready truth only
  - Step 2: replace Java heuristic handoff mutation with passive sync to native truth

### Test files

- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`
  - cover the new native handoff-ready query
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdNativeAudioSinkSourceStructureTest.kt`
  - cover the new JNI/native handoff-ready observation

### Documentation files

- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-media3-structural-risk-log.md`
  - record the restored validated baseline and the incremental pass boundaries
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-parity-working-notes.md`
  - record what each incremental step is testing and what remains intentionally untouched

## Hard Gates

- `transportVerdict=PASS`
- burst chain remains `8 -> 64 -> 64 -> 64`
- `playerStateVerdict=PASS`
- `continuousPlayingWindowSatisfied=true`
- `routeChangeCountAfterStableStart=0`
- `routeTupleChangeCountAfterStableStart=0`
- `routeReopenCountAfterStart=0`

If a step fails these gates:
- inspect the new validation bundle and log first
- identify which touched surface caused the regression
- revert only that bad change if identifiable
- reimplement surgically and rerun
- only revert to the restored baseline commit if the trigger cannot be isolated quickly

## Current Safe Baseline

- root branch baseline: `5253bb329`
- media branch baseline: `3757398fae`
- restored validation bundle: `/tmp/transport-validation-truehd-1774137621593.zip`

---

### Task 1: Add Native Handoff-Ready Observation Only

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_truehd_session_bridge.cpp`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdNativeAudioSinkSourceStructureTest.kt`

- [x] **Step 1: Add failing source-structure tests for native handoff-ready observation**

Require:
- a public native engine query such as `IsTrueHdSteadyStateHandoffReady()`
- a matching JNI method such as `nIsTrueHdSteadyStateHandoffReady(...)`
- Java observation/logging of `nativeHandoffReady`

- [x] **Step 2: Run focused tests to confirm the new assertions fail**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdAEEngineSourceStructureTest*" --tests "*KodiTrueHdNativeAudioSinkSourceStructureTest*"
```

Expected:
- FAIL on the new handoff-ready structure assertions

- [x] **Step 3: Implement the native handoff-ready query without changing control flow**

Rules:
- do not change startup/steady-state path selection yet
- do not change transport, route, or steady-state retry cadence
- expose the new native query and include it in diagnostics only

- [x] **Step 4: Re-run focused tests**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdAEEngineSourceStructureTest*" --tests "*KodiTrueHdNativeAudioSinkSourceStructureTest*"
```

Expected:
- PASS

- [x] **Step 5: Build, install, validate, analyze, and commit if hard gates hold**

Run:

```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac ':media:lib-exoplayer-kodi-cpp-audiosink:buildCMakeDebug[arm64-v8a][kodiCppAudioSinkJNI]' :app:assembleDebug
adb -s 192.168.50.37:5555 install -r /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
/Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh
```

Expected:
- hard gates remain green
- transport still passes
- runtime detail now includes native handoff-ready truth

Commit:

```bash
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality add \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/superpowers/plans/2026-03-22-truehd-media3-incremental-handoff-parity.md \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-media3-structural-risk-log.md \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdNativeAudioSinkSourceStructureTest.kt
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media add \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_truehd_session_bridge.cpp \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality commit -m "fix: expose truehd native handoff readiness"
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media commit -m "fix: expose truehd native handoff readiness"
```

---

### Task 2: Make Java Handoff Passive To Native Truth

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdNativeAudioSinkSourceStructureTest.kt`

- [x] **Step 1: Add failing tests that reject Java heuristic handoff mutation**
- [x] **Step 2: Replace heuristic handoff mutation with passive sync to native handoff-ready truth**
- [x] **Step 3: Keep `handleBuffer()` as the only decision point, but make it observational**
- [x] **Step 4: Build, install, validate with `run_adb_validation.sh`, analyze any gate failure, and commit only if hard gates hold**

---

### Task 3: Refine Native Startup-Vs-Steady-State Input Ownership

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`

- [x] **Step 1: Add failing tests for explicit startup-only vs steady-state native input ownership**
- [x] **Step 2: Split the shared pending passthrough input only after Task 2 is validated**
- [x] **Step 3: Keep transport bytes, packet capture, and steady-state retry cadence unchanged in this step**
- [x] **Step 4: Build, install, validate with `run_adb_validation.sh`, analyze any gate failure, and commit only if hard gates hold**

---

### Decision Gate After Task 3

- [x] If audio quality is still degraded but hard gates remain clean, stop and write a fresh late-stream audio-quality audit before touching cadence or buffer sizing again.
