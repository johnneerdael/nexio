# TrueHD Media3 Pending Output Truth Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the TrueHD late-stream steady-state path toward Media3's one-pending-output truth model without regressing transport integrity, route stability, or Media3-facing playback contract behavior.

**Architecture:** Keep startup behavior intact and isolate the change to the steady-state TrueHD path. Replace packet-retry-driven steady-state control with a single pending-output truth model in native code first, then make Java observational once steady state is active. Validate after every pass with the existing ADB validation workflow and commit only on clean hard gates.

**Tech Stack:** Android Kotlin, Media3 `AudioSink`, JNI/C++, custom Kodi-derived TrueHD native sink, OpenSpec, ADB passthrough validator

## Execution Model

- Work only in grouped batches, not isolated micro-fixes.
- Each batch may include a few tightly related edits, but it must ship as one validated unit.
- After each successful `.37` validation, commit the batch immediately before starting the next one.
- If a batch regresses transport or the outer playback contract, inspect the bundle first, identify which edit inside the batch touched that boundary, and rework or remove only that part before retrying.
- Do not stack a new hypothesis on top of an unvalidated batch.

## Current Rollback Anchor

- Worktree branch: `codex/truehd-audio-quality-parity`
- Current accepted commit before the next batch: `efa01c04c`
- Device baseline bundle on `.37`: `/tmp/transport-validation-truehd-1774146543766.zip`

---

## File Map

**Production**
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`

**Tests**
- Modify: `app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdNativeAudioSinkSourceStructureTest.kt`

**Docs**
- Modify: `docs/truehd-late-stream-audio-quality-audit.md`
- Create: `docs/truehd-media3-pending-output-risk-log.md`

## Invariants

- Transport burst chain must remain `8 -> 64 -> 64 -> 64`
- `transportVerdict` must remain `PASS`
- `playerStateVerdict` must remain `PASS`
- `continuousPlayingWindowSatisfied` must remain `true`
- `routeTupleChangeCountAfterStableStart` must remain `0`
- `routeReopenCountAfterStart` must remain `0`
- No Java method other than `handleBuffer()` may mutate startup handoff state
- Startup path behavior must remain isolated from steady-state output modeling

## Validation Command

Use this after each pass:

```bash
ANDROID_SERIAL=192.168.50.37:5555 ./gradlew :app:installDebug
./scripts/run_adb_validation.sh truehd 192.168.50.37
```

If the script fails to launch playback, verify foreground app state before interpreting the run.

### Task 1: Pass 1 - Single Steady-State Pending Output Owner

**Files:**
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Test: `app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`
- Create: `docs/truehd-media3-pending-output-risk-log.md`

- [ ] **Step 1: Write the failing structural test**

Add a source-structure assertion proving the steady-state path is centered on one active pending packed output owner and no secondary steady-state retry-owned output object is introduced.

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdAEEngineSourceStructureTest*"
```

Expected: FAIL on the new steady-state pending-output assertion.

- [ ] **Step 3: Implement the minimal native ownership refactor**

Modify `KodiTrueHdAEEngine.cpp` and `KodiTrueHdAEEngine.h` so:

- steady-state output flow has exactly one pending packed output truth
- startup-owned pending output remains isolated
- steady-state flush logic no longer depends on separate packet-retry ownership for control

Do not change:

- MAT / IEC packing
- AudioTrack config
- Java `AudioSink` contract methods
- route logic

- [ ] **Step 4: Run the focused test to verify it passes**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdAEEngineSourceStructureTest*"
```

Expected: PASS

- [ ] **Step 5: Build the modified targets**

Run:

```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac ':media:lib-exoplayer-kodi-cpp-audiosink:buildCMakeDebug[arm64-v8a][kodiCppAudioSinkJNI]' :app:assembleDebug
```

Expected: PASS

- [ ] **Step 6: Run runtime validation**

Run:

```bash
ANDROID_SERIAL=192.168.50.37:5555 ./gradlew :app:installDebug
./scripts/run_adb_validation.sh truehd 192.168.50.37
```

Expected:

- transport hard gates stay green
- outer runtime hard gates stay green
- no route churn regression

- [ ] **Step 7: Analyze any regression before reverting**

If hard gates regress:

- inspect the new bundle and log first
- identify which changed control path touched contract or transport behavior
- revert only the offending part
- revalidate before moving on

- [ ] **Step 8: Commit Pass 1**

```bash
git add app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt docs/truehd-media3-pending-output-risk-log.md media
git commit -m "refactor: unify truehd steady-state pending output ownership"
```

### Task 2: Pass 2 - Media3-Like Steady-State Drain Semantics

**Files:**
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Test: `app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`

- [ ] **Step 1: Write the failing structural test**

Add assertions proving the steady-state path treats partial and zero writes as a still-pending output truth instead of as explicit packet retry episodes that control re-admission.

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdAEEngineSourceStructureTest*"
```

Expected: FAIL on the new steady-state drain semantics assertions.

- [ ] **Step 3: Implement the minimal steady-state drain rewrite**

Change steady-state logic so:

- partial write leaves the same pending output truth active
- zero write leaves the same pending output truth active
- steady-state control stops modeling those as rich packet retry episodes
- diagnostics may still record retry details, but control must follow pending-output truth semantics

Do not change:

- startup retry policy
- transport capture
- Java `play/pause/flush`

- [ ] **Step 4: Run the focused test to verify it passes**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdAEEngineSourceStructureTest*"
```

Expected: PASS

- [ ] **Step 5: Build the modified targets**

Run:

```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac ':media:lib-exoplayer-kodi-cpp-audiosink:buildCMakeDebug[arm64-v8a][kodiCppAudioSinkJNI]' :app:assembleDebug
```

Expected: PASS

- [ ] **Step 6: Run runtime validation**

Run:

```bash
ANDROID_SERIAL=192.168.50.37:5555 ./gradlew :app:installDebug
./scripts/run_adb_validation.sh truehd 192.168.50.37
```

Expected:

- hard gates stay green
- sink-health moves away from repeated `zero -> zero -> zero -> success`

- [ ] **Step 7: Analyze any regression before reverting**

If hard gates regress:

- compare against the Pass 1 bundle
- isolate whether the regression came from zero-write treatment, partial-write treatment, or end-of-stream handling
- revert only the offending sub-change

- [ ] **Step 8: Commit Pass 2**

```bash
git add app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt media
git commit -m "refactor: align truehd steady-state drain semantics with media3"
```

### Task 3: Pass 3 - Passive Java Once Steady State Begins

**Files:**
- Modify: `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java`
- Test: `app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdNativeAudioSinkSourceStructureTest.kt`
- Modify: `docs/truehd-late-stream-audio-quality-audit.md`
- Modify: `docs/truehd-media3-pending-output-risk-log.md`

- [ ] **Step 1: Write the failing structural test**

Add assertions proving Java remains observational once steady-state begins and no longer participates in steady-state output pacing decisions.

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdNativeAudioSinkSourceStructureTest*"
```

Expected: FAIL on the new passive-handoff assertions.

- [ ] **Step 3: Implement the minimal Java containment change**

Update `KodiTrueHdNativeAudioSink.java` so:

- Java may still choose startup vs steady-state path at handoff time
- once steady state is active, Java becomes observational and does not influence output pacing
- `hasPendingData()` remains observational

- [ ] **Step 4: Run the focused test to verify it passes**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdNativeAudioSinkSourceStructureTest*"
```

Expected: PASS

- [ ] **Step 5: Build the modified targets**

Run:

```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac ':media:lib-exoplayer-kodi-cpp-audiosink:buildCMakeDebug[arm64-v8a][kodiCppAudioSinkJNI]' :app:assembleDebug
```

Expected: PASS

- [ ] **Step 6: Run runtime validation**

Run:

```bash
ANDROID_SERIAL=192.168.50.37:5555 ./gradlew :app:installDebug
./scripts/run_adb_validation.sh truehd 192.168.50.37
```

Expected:

- transport and player hard gates stay green
- no startup-path-after-handoff events
- late-stream audio continuity is not worse than Pass 2

- [ ] **Step 7: Analyze any regression before reverting**

If hard gates regress:

- compare against the Pass 2 bundle
- isolate whether the regression came from handoff timing or pending-data truth
- revert only that part and revalidate

- [ ] **Step 8: Commit Pass 3**

```bash
git add app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdNativeAudioSinkSourceStructureTest.kt docs/truehd-late-stream-audio-quality-audit.md docs/truehd-media3-pending-output-risk-log.md media
git commit -m "refactor: make truehd java handoff passive in steady state"
```
