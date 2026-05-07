# TrueHD Late-Stream Audio Quality Parity Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore stock-like late-stream TrueHD audio continuity and AVR lock behavior without regressing validated transport integrity or the Media3-facing sink contract.

**Architecture:** Keep the validated transport, Java `AudioSink` contract boundary, and post-start route stability untouched. The plan first freezes the post-Group C runtime truth and reproduces the remaining late-underrun path, then applies two grouped native-only fixes: (1) eliminate the remaining steady-state `forced_retry` bypass by aligning retry admission with actual `AudioTrack` play state, and (2) normalize post-underrun resume/recovery under a stable route tuple. `AudioTrack` buffer sizing is deferred behind a validation gate and only revisited if the underrun remains after the play-state mismatch is removed.

**Tech Stack:** Android, Media3, JNI/C++, Kodi AE references, Kotlin/JUnit, Gradle, ADB, Nexio passthrough validator

---

## Guardrails

- Do not touch `iecPipeline_.Feed(...)`, packed-burst capture, audio-track write capture, or transport comparison logic.
- Do not modify Java `AudioSink` contract methods beyond diagnostics already validated as safe.
- Do not re-open route-selection or route-tuple logic.
- Do not add sleeps on the playback/render path.
- Treat these as hard regression gates after every grouped patch:
  - `transportVerdict=PASS`
  - burst chain stays `8 -> 64 -> 64 -> 64`
  - `continuousPlayingWindowSatisfied=true`
  - `routeChangeCountAfterStableStart=0`
  - `routeTupleChangeCountAfterStableStart=0`
  - `routeReopenCountAfterStart=0`
- Treat operator-audible quality and AVR lock as the primary success signals. Sink counters are supporting evidence.

## Current Grounded Truth

Reference artifacts:
- `/tmp/transport-validation-truehd-1774122320221.zip`
- `/tmp/transport-validation-truehd-1774123156108.zip`
- `/tmp/passthrough-validation-192.168.50.37-truehd-groupc-manual.log`

Status update after the already-landed Group D / Group E work:
- The active branch has already removed the old `forced_retry` parity gap.
- The active branch has already made steady-state retry diagnostics explicit.
- The remaining late-stream gap is no longer retry-admission bypass. It is steady-state
  zero-write cadence under a stable route tuple.
- The latest valid artifact for the active branch is:
  - `/tmp/transport-validation-truehd-1774129824226.zip`

Grounded runtime facts from the active code and the valid Group C rerun:
- Transport is still correct.
- The Media3-facing outer behavior is still good enough: playback reaches `ENDED`, route stays stable post-startup, and no route reopen occurs.
- Audio quality is still degraded: `audioUnderrunCount=1`, operator report remains `WEAK` / `CHOPPY`.
- The strongest remaining native mismatch is that `forced_retry` still dominates the steady-state write path even after Group C.
- In the active source, `FlushTrueHdPackedQueueToHardwareLocked()` only consults retry policy when `retryingPendingRemainder && output_.IsPlaying()`.
- In the valid Group C bundle, `forced_retry` events still occur with:
  - `ownership=steady_state`
  - `startupCompleted=true`
  - `nativeOutputStarted=true`
  - `selectedPath=steady_state_path`
- That means the engine still has a state where it believes output is started, but the underlying `AudioTrack` is not actively playing, so retries bypass policy and hammer the same remainder.

Kodi / Media3 reference boundaries:
- `/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAESink.cpp`
- `/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Sinks/AESinkAUDIOTRACK.cpp`
- `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java`
- `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java`

The key parity conclusion is:
- stock Media3 keeps pending output as the truth and retries on the next renderer opportunity
- Kodi retries from a dedicated sink path and makes play/write state explicit
- our remaining divergence is a native play-state mismatch plus retry fallback under a stable route tuple

Updated parity conclusion for the active branch:
- stock Media3 still keeps pending output as the truth
- Kodi still does one bounded retry after a zero write and waits roughly a packet duration
- our active branch now most strongly diverges in late-stream steady-state cadence:
  it revisits the same remainder too aggressively on natural flushes after zero writes,
  even though route stability and transport remain correct

---

### Task 1: Freeze The Post-Group C Baseline And Root Cause Note

**Files:**
- Create: `/Users/jneerdael/Scripts/nexio/docs/truehd-audio-quality-parity-working-notes.md`
- Reference: `/tmp/transport-validation-truehd-1774122320221.zip`
- Reference: `/tmp/transport-validation-truehd-1774123156108.zip`
- Reference: `/tmp/passthrough-validation-192.168.50.37-truehd-groupc-manual.log`

- [ ] **Step 1: Write the baseline table**

Record these fields for both valid runs:
- `transportVerdict`
- `runtimeVerdict`
- `timeToReadyMs`
- `audioUnderrunCount`
- `droppedVideoFrames`
- `writeAttemptCount`
- `successfulWriteCount`
- `partialWriteCount`
- `zeroWriteCount`
- `remainderRetryEventCount`
- `retryReasonCounts`
- `longestStuckRemainderMs`
- `longestZeroWriteStreakMs`
- `continuousPlayingWindowSatisfied`

- [ ] **Step 2: Quote the current root-cause evidence**

In the same note, include:
- the `AudioFlinger: pause because of UNDERRUN` excerpt from `/tmp/passthrough-validation-192.168.50.37-truehd-groupc-manual.log`
- example `forced_retry` sink events from `/tmp/transport-validation-truehd-1774123156108.zip`
- the exact active source condition from `FlushTrueHdPackedQueueToHardwareLocked()` showing retry policy is only consulted when `output_.IsPlaying()`

- [ ] **Step 3: Record the reference mismatch**

Write the specific difference against references:
- stock Media3 does not have a synthetic `forced_retry` path for encoded pending output
- Kodi keeps sink retry/resume behavior tied to the real output thread state
- our engine still allows steady-state retries to bypass explicit policy when `outputStarted_` and `AudioTrack` play state diverge

- [ ] **Step 4: Save the working note**

No code changes in this task.

---

### Task 2: Add Failing Coverage For The Remaining Steady-State Retry Mismatch

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`
- Reference: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`

- [ ] **Step 1: Add a failing source-structure test for play-state-gated retry admission**

Add a test like:

```kotlin
@Test
fun steadyStateRetryAdmissionDoesNotDependOnAudioTrackPlayState() {
    val flushMethod =
        extractMethod(
            loadSource(),
            "int KodiTrueHdAEEngine::FlushTrueHdPackedQueueToHardwareLocked()",
        )

    assertFalse(flushMethod.contains("retryingPendingRemainder && output_.IsPlaying()"))
}
```

- [ ] **Step 2: Add a failing source-structure test for synthetic forced-retry fallback**

Add a test like:

```kotlin
@Test
fun steadyStateRetryDiagnosticsDoNotFallBackToForcedRetry() {
    val flushMethod =
        extractMethod(
            loadSource(),
            "int KodiTrueHdAEEngine::FlushTrueHdPackedQueueToHardwareLocked()",
        )

    assertFalse(flushMethod.contains("\"forced_retry\""))
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdAEEngineSourceStructureTest*"
```

Expected:
- FAIL on both new assertions against the active Group C code

- [ ] **Step 4: Commit the failing test update**

```bash
git add /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt
git commit -m "test: capture truehd steady-state retry parity gap"
```

---

### Task 3: Implement Group D Native Play-State / Retry Admission Fix

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Possibly modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- Reference: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.cpp`
- Reference: `/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAESink.cpp`

- [ ] **Step 1: Remove `output_.IsPlaying()` as the gate for steady-state retry policy**

Change `FlushTrueHdPackedQueueToHardwareLocked()` so the steady-state pending-remainder path always goes through explicit retry-state control, even when the underlying `AudioTrack` play state is no longer `PLAYING`.

Required rule:
- `retryingPendingRemainder && isSteadyState` must not bypass policy just because `AudioTrack` says it is not currently playing

- [ ] **Step 2: Replace synthetic `forced_retry` with explicit steady-state reasons**

Make the steady-state path emit explicit reasons such as:
- `steady_state_output_driven`
- `steady_state_zero_retry_backoff`
- `steady_state_resume_pending`
- `steady_state_waiting_for_play_state`

Do not leave a `forced_retry` fallback in the steady-state diagnostics path.

- [ ] **Step 3: Add bounded resume handling when the engine is started but the track is no longer playing**

Implement the smallest native-only fix that keeps the current contract intact:
- if steady-state output is pending
- and `outputStarted_` is true
- and the underlying `AudioTrack` is not currently playing
- then normalize state before the next write attempt

The preferred shape is:

```cpp
if (isSteadyState && retryingPendingRemainder && outputStarted_ && !output_.IsPlaying()) {
  outputStarted_ = false;
  StartOutputIfPrimedLocked();
  if (!output_.IsPlaying()) {
    retryReason = "steady_state_waiting_for_play_state";
    break;
  }
  retryReason = "steady_state_resume_pending";
}
```

Keep this bounded:
- no sleeps
- no route/config changes
- no `InvalidateCurrentOutputLocked()` unless there is a real write error (`written < 0`)

- [ ] **Step 4: Run the focused test and make it pass**

Run:
```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdAEEngineSourceStructureTest*"
```

Expected:
- PASS

- [ ] **Step 5: Run focused native/media builds**

Run:
```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac ':media:lib-exoplayer-kodi-cpp-audiosink:buildCMakeDebug[arm64-v8a][kodiCppAudioSinkJNI]'
./gradlew --no-daemon :app:assembleDebug
```

Expected:
- PASS

- [ ] **Step 6: Commit Group D**

```bash
git add /Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp /Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h /Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt
git commit -m "fix: align truehd steady-state retry admission with play state"
```

---

### Task 4: Validate Group D Against Hard Gates And Audio Continuity

**Files:**
- Reference: `/Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh`
- Reference: `/tmp/transport-validation-truehd-1774122320221.zip`
- Reference: `/tmp/transport-validation-truehd-1774123156108.zip`

- [ ] **Step 1: Install the debug build**

Run:
```bash
adb -s 192.168.50.37:5555 install -r /Users/jneerdael/Scripts/nexio/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Expected:
- `Success`

- [ ] **Step 2: Run validator with foreground-gated discipline**

Run the standard script first:
```bash
bash /Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh
```

If the exported bundle shows zero live bursts or `UNKNOWN` transport/runtime, do not judge the patch. Re-run manually with:
- explicit `monkey` launch
- `dumpsys window | rg 'mCurrentFocus|mFocusedApp'`
- confirmation that `com.nexiodebug.tv/com.nexio.tv.MainActivity` is foreground before `start`, `capture`, and `export`

- [ ] **Step 3: Compare Group D bundle against the Group C valid baseline**

Required hard gates:
- `transportVerdict=PASS`
- burst chain remains `8 -> 64 -> 64 -> 64`
- `runtimeVerdict` is not worse than `DEGRADED`
- `continuousPlayingWindowSatisfied=true`
- `routeChangeCountAfterStableStart=0`
- `routeTupleChangeCountAfterStableStart=0`
- `routeReopenCountAfterStart=0`

Required runtime checks:
- `forced_retry` is eliminated or materially reduced
- `audioUnderrunCount` does not worsen
- operator report is not worse than baseline

- [ ] **Step 4: If Group D regresses, isolate before reverting**

Do not blindly revert.

Classify the regression as one of:
- invalid validator run only
- transport / route / contract regression
- raw sink churn only
- operator-audible regression

Only revert the specific play-state / retry-admission change if the hard gates or audible result regress.

---

### Task 5: Add Failing Reproduction For Stable-Tuple Underrun Recovery

**Files:**
- Create: `/Users/jneerdael/Scripts/nexio/docs/truehd-stable-tuple-underrun-recovery.md`
- Reference: `/tmp/transport-validation-truehd-1774123156108.zip`
- Reference: `/tmp/passthrough-validation-192.168.50.37-truehd-groupc-manual.log`
- Reference: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Reference: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp`

- [ ] **Step 1: Document the post-underrun recovery path**

Capture:
- `AudioFlinger: pause because of UNDERRUN`
- any subsequent patch / encoder reopen lines
- the fact that `routeTupleChangeCountAfterStableStart=0`
- the fact that the route tuple remains `IEC61937|192000|7.1`

- [ ] **Step 2: Write the required invariant**

Record the target invariant:
- once the route tuple is stable
- a late underrun may require resume/recovery
- but it must not devolve into hidden retry churn or AVR relock loops under the same tuple

- [ ] **Step 3: Save the note**

No production code changes in this task.

---

### Task 6: Implement Group E Native Stable-Tuple Underrun Recovery

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Possibly modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- Possibly modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp`
- Possibly modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.h`

- [ ] **Step 1: Make underrun / non-playing output an explicit native recovery state**

Introduce a small native-only recovery path that distinguishes:
- normal steady-state pending output
- steady-state pending output waiting for resume after underrun / auto-pause

Do not encode this as route churn or Java contract behavior.

- [ ] **Step 2: Preserve pending steady-state remainder ownership across resume**

On underrun / auto-pause:
- do not discard or re-home the steady-state pending remainder
- do not reset route/config state
- do not zero write offsets unless there is a real fatal write error

Keep the pending encoded remainder as the truth, matching the stock Media3 / Kodi direction.

- [ ] **Step 3: Keep restart / recovery bounded**

Allowed:
- explicit resume of the existing `AudioTrack`
- clearing only play-state flags needed to re-prime
- explicit diagnostic reason fields

Not allowed:
- route mutation
- transport-path mutation
- blanket `InvalidateCurrentOutputLocked()` on simple underrun
- sleeps on the render path

- [ ] **Step 4: Add the smallest useful diagnostic fields**

If needed, add native diagnostics for:
- `outputStarted`
- `audioTrackPlaying`
- `resumeAttempted`
- `resumeSucceeded`
- `underrunRecoveryActive`

Keep them diagnostic only.

- [ ] **Step 5: Run focused builds**

Run:
```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac ':media:lib-exoplayer-kodi-cpp-audiosink:buildCMakeDebug[arm64-v8a][kodiCppAudioSinkJNI]'
./gradlew --no-daemon :app:assembleDebug
```

Expected:
- PASS

- [ ] **Step 6: Validate Group E on `.37`**

Run:
```bash
adb -s 192.168.50.37:5555 install -r /Users/jneerdael/Scripts/nexio/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
bash /Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh
```

If the run is invalid, re-run manually with foreground gating before judging the patch.

Required hard gates:
- `transportVerdict=PASS`
- route metrics remain stable after startup
- playback still reaches `ENDED`

Required audio-quality checks:
- operator result is better or unchanged
- `audioUnderrunCount` improves or is unchanged
- `forced_retry` stays reduced
- no new route tuple churn

- [ ] **Step 7: Commit Group E**

```bash
git add /Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp /Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h /Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp /Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.h
git commit -m "fix: stabilize truehd underrun recovery under steady tuple"
```

---

### Task 7: Only If Still Needed, Re-open TrueHD Output Buffer Margin

**Files:**
- Reference first: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAudioTrackOutput.cpp`
- Reference first: `/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Sinks/AESinkAUDIOTRACK.cpp`

- [ ] **Step 1: Re-check whether underrun remains after Group D and Group E**

Only continue if:
- transport is still clean
- route is still stable
- `audioUnderrunCount` remains `1`
- operator still reports relock / choppy audio

- [ ] **Step 2: Audit buffer sizing with the earlier regression in mind**

Do not repeat the old standalone headroom patch.

Only consider buffer sizing if the new evidence shows:
- the recovery path is correct
- retries are no longer bypassing policy
- the remaining problem is still pure late starvation

- [ ] **Step 3: If reopened, couple any buffer-size change to the new recovery model**

No standalone buffer-size tweak.

Any future buffer-size patch must be validated against:
- operator audio quality
- `audioUnderrunCount`
- `longestStuckRemainderMs`
- transport / route hard gates

---

### Task 8: Final Validation Gate And Checklist Rewrite

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/docs/truehd-media3-parity-checklist.md`
- Modify: `/Users/jneerdael/Scripts/nexio/docs/truehd-runtime-strict-audit.md`
- Modify: `/Users/jneerdael/Scripts/nexio/docs/truehd-audio-quality-parity-working-notes.md`

- [ ] **Step 1: Run one final full validation pass**

Run the validated `.37` workflow and keep:
- the exported bundle path
- the log path
- the operator observation

- [ ] **Step 2: Rewrite the checklist based on the final grouped result**

Update:
- whether `forced_retry` is still a live divergence
- whether stable-tuple underrun recovery is now stock-like
- whether buffer-sizing work is still open

- [ ] **Step 3: Record the final decision**

Write one of:
- “audio quality parity restored enough to stop”
- “native late-stream recovery improved but buffer margin still open”
- “root cause shifted; new strict audit required”
