# TrueHD Zero-Write Cadence Parity Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore more Kodi-like late-stream TrueHD audio continuity by making steady-state zero-write retry cadence packet-duration-aware, without regressing validated transport integrity or the Media3-facing sink contract.

**Architecture:** Keep the current transport, route stability, Java `AudioSink` contract behavior, and active startup/handoff shape unchanged. The plan applies one native-only cadence fix in `KodiTrueHdAEEngine`, validated by source-structure tests, focused builds, and `run_adb_validation.sh` after pre-installing the worktree APK. Buffer sizing stays deferred unless this cadence fix keeps hard gates clean but still leaves the same audio defect.

**Tech Stack:** Android, Media3, JNI/C++, Kodi AE references, Kotlin/JUnit, Gradle, ADB, Nexio passthrough validator

---

## File Map

### Production files

- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
  - extend steady-state retry state with explicit next-eligible retry time and zero-write streak bookkeeping
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
  - replace fixed `4000 us` repeated-zero cadence with packet-duration-shaped steady-state gating
  - keep startup retry policy unchanged
  - keep transport, route, timing, and Java contract boundaries unchanged

### Test files

- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`
  - add source-structure coverage for packet-duration-based steady-state retry cadence

### Documentation files

- Reference: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-full-parity-audit.md`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-stable-tuple-underrun-recovery.md`
  - record the new cadence fix and validation outcome

### Validation tooling

- Reference: `/Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh`
  - use this script for runtime validation after pre-installing the worktree APK

## Guardrails

- Do not touch `iecPipeline_.Feed(...)`, packed-burst capture, audio-track write capture, or transport comparison logic.
- Do not modify Java `AudioSink` contract methods.
- Do not re-open route selection or route tuple logic.
- Do not add sleeps on the playback/render path.
- Do not change `KodiTrueHdAudioTrackOutput` buffer sizing in this plan.
- Treat these as hard gates after implementation:
  - `transportVerdict=PASS`
  - burst chain remains `8 -> 64 -> 64 -> 64`
  - `routeChangeCountAfterStableStart=0`
  - `routeTupleChangeCountAfterStableStart=0`
  - `routeReopenCountAfterStart=0`
- Treat operator-audible quality and AVR lock as the primary success signals.

## Preconditions

- The active audit document exists:
  - `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-full-parity-audit.md`
- The current worktree APK path is:
  - `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- The connected Shield should be reachable by ADB before validation:
  - `adb devices`
  - expected to show either `192.168.50.37:5555` or an alias that matches `run_adb_validation.sh`

---

### Task 1: Freeze The New Audit Baseline In The Branch Notes

**Files:**
- Reference: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-full-parity-audit.md`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-stable-tuple-underrun-recovery.md`

- [ ] **Step 1: Add the new audit reference to the stable-tuple note**

Append a short section that points to:
- the full audit doc
- the current primary mismatch: fixed `4 ms` steady-state repeated-zero cadence
- the next implementation group: packet-duration-shaped zero-write gating

- [ ] **Step 2: Verify the note only reflects the active branch**

Read the updated note and confirm it does not mention:
- `forced_retry` as the active root cause
- play-state bypass as the active root cause

- [ ] **Step 3: Commit the documentation checkpoint**

```bash
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality add \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-full-parity-audit.md \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-stable-tuple-underrun-recovery.md
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality commit -m "docs: refresh truehd audio quality parity audit"
```

---

### Task 2: Add Failing Coverage For Packet-Duration-Based Zero-Write Cadence

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`
- Reference: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Reference: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`

- [ ] **Step 1: Add a failing test that rejects the fixed 4 ms steady-state repeated-zero cooldown**

Add a source-structure test like:

```kotlin
@Test
fun steadyStateRetryCadenceDoesNotUseFixedFourMillisecondBackoff() {
    val engineSource = loadSource()

    assertFalse(engineSource.contains("kSteadyStateRepeatedZeroBackoffUs = 4000"))
}
```

- [ ] **Step 2: Add a failing test that requires explicit next-eligible retry state**

Add a source-structure test like:

```kotlin
@Test
fun pendingPackedRetryStateTracksNextEligibleRetryTime() {
    val headerSource = loadHeaderSource()

    assertTrue(headerSource.contains("nextEligibleRetryTimeUs_"))
}
```

- [ ] **Step 3: Add a failing test that requires a packet-duration backoff reason**

Add a source-structure test like:

```kotlin
@Test
fun steadyStateZeroWritesUsePacketDurationBackoffReason() {
    val flushMethod =
        extractMethod(
            loadSource(),
            "int KodiTrueHdAEEngine::FlushTrueHdPackedQueueToHardwareLocked()",
        )

    assertTrue(flushMethod.contains("\"steady_state_packet_duration_backoff\""))
}
```

- [ ] **Step 4: Run the focused test to confirm it fails**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdAEEngineSourceStructureTest*"
```

Expected:
- FAIL on the new assertions

- [ ] **Step 5: Commit the failing tests**

```bash
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality add \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality commit -m "test: capture truehd zero-write cadence parity gap"
```

---

### Task 3: Implement Group F Native Packet-Duration Zero-Write Gating

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Reference: `/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Sinks/AESinkAUDIOTRACK.cpp`
- Reference: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java`

- [ ] **Step 1: Extend steady-state retry state with next-eligible retry time**

Add fields to `PendingPackedRetryState` in `KodiTrueHdAEEngine.h`:
- `nextEligibleRetryTimeUs_`
- `zeroWriteStreak_`

Ensure `Reset()` clears them.

- [ ] **Step 2: Add a helper that computes packet-duration-shaped retry backoff**

In `KodiTrueHdAEEngine.cpp`, add a helper like:

```cpp
int64_t KodiTrueHdAEEngine::ComputeSteadyStateRetryBackoffUsLocked(
    const KodiPackedAccessUnit& packet,
    int remainingBytes) const
```

Required behavior:
- if `packet.durationUs > 0` and packet has bytes, derive backoff from:
  - `packet.durationUs * remainingBytes / packet.bytes.size()`
- otherwise derive fallback duration from:
  - `remainingBytes / output_.FrameSizeBytes() / output_.SampleRate()`
- clamp the result to a bounded range:
  - minimum `4000 us`
  - maximum `20000 us`

- [ ] **Step 3: Replace the fixed 4 ms repeated-zero logic**

In `ShouldRetrySteadyStatePendingPackedRemainderLocked(...)`:
- remove the fixed `kSteadyStateRepeatedZeroBackoffUs = 4000`
- gate retries on `steadyStateRetryState_.nextEligibleRetryTimeUs_`
- if the current time is still before that deadline:
  - return `false`
  - set `retryReason = "steady_state_packet_duration_backoff"`

Keep:
- startup retry policy unchanged
- playback-head and buffer-fit values as diagnostics only

- [ ] **Step 4: Arm the next-eligible retry deadline when a steady-state zero write occurs**

In `FlushTrueHdPackedQueueToHardwareLocked()`:
- when `written <= 0` on a steady-state remainder
- and `written == 0`
- set:
  - `zeroWriteStreak_ += 1`
  - `nextEligibleRetryTimeUs_ = nowUs + ComputeSteadyStateRetryBackoffUsLocked(...)`
- record the explicit reason `steady_state_packet_duration_backoff`

Keep the fix bounded:
- no sleeps
- no route changes
- no output invalidation on `written == 0`
- only invalidate/release on `written < 0`

- [ ] **Step 5: Reset zero-write cadence state only after real progress**

When `written > 0` for the same steady-state remainder:
- clear `zeroWriteStreak_`
- clear `nextEligibleRetryTimeUs_`
- keep the packet/remainder ownership intact
- do not reset the whole retry episode unless the packet is complete

- [ ] **Step 6: Run the focused test and make it pass**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdAEEngineSourceStructureTest*"
```

Expected:
- PASS

- [ ] **Step 7: Run focused native/media builds**

Run:

```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac ':media:lib-exoplayer-kodi-cpp-audiosink:buildCMakeDebug[arm64-v8a][kodiCppAudioSinkJNI]'
./gradlew --no-daemon :app:assembleDebug
```

Expected:
- PASS

- [ ] **Step 8: Commit Group F**

```bash
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality add \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality commit -m "fix: align truehd steady-state zero-write cadence with packet duration"
```

---

### Task 4: Validate Group F With The Project Validation Script

**Files:**
- Reference: `/Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh`
- Reference: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

- [ ] **Step 1: Preflight the ADB alias expected by the script**

Run:

```bash
adb devices
adb -s 192.168.50.37 shell getprop ro.product.model
```

Expected:
- the script alias `192.168.50.37` resolves successfully

If it does not:
- stop and fix the harness separately before validating this production change

- [ ] **Step 2: Install the worktree debug APK**

Run:

```bash
adb -s 192.168.50.37:5555 install -r /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Expected:
- `Success`

- [ ] **Step 3: Run the project validation script**

Run:

```bash
/Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh
```

Expected:
- a new bundle at `/tmp/transport-validation-truehd-*.zip`
- a new log at `/tmp/passthrough-validation-truehd.log`

- [ ] **Step 4: Check hard gates in the exported bundle**

Verify:
- `transportVerdict=PASS`
- burst chain `8 -> 64 -> 64 -> 64`
- `routeChangeCountAfterStableStart=0`
- `routeTupleChangeCountAfterStableStart=0`
- `routeReopenCountAfterStart=0`

- [ ] **Step 5: Check the audio-quality counters against the prior valid Group E bundle**

Compare against:
- `/tmp/transport-validation-truehd-1774129824226.zip`

Check whether the new run lowers:
- `zeroWriteCount`
- `remainderRetryEventCount`
- count of packets with `zero -> zero -> success`

Also confirm whether:
- operator AVR lock improves
- operator choppy-audio report improves

- [ ] **Step 6: Commit the validation note update**

Update:
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-stable-tuple-underrun-recovery.md`

Then commit:

```bash
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality add \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-stable-tuple-underrun-recovery.md
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality commit -m "docs: record truehd zero-write cadence validation"
```

---

### Task 5: Stop-Conditions And Follow-Up Gate

**Files:**
- Reference: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-full-parity-audit.md`
- Reference: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-stable-tuple-underrun-recovery.md`

- [ ] **Step 1: Stop if any hard gate regresses**

If any of these fail:
- transport
- route stability
- burst chain

Then stop and write a short root-cause note before attempting any further fix.

- [ ] **Step 2: Stop if operator audio does not improve despite cleaner counters**

If:
- counters improve
- transport remains clean
- but audible stutter / AVR relock remains unchanged

Then do not guess at buffer sizing in the same branch step.

Write a follow-up audit note that compares:
- packet-duration cadence after Group F
- `KodiTrueHdAudioTrackOutput::ConfigureInternal(...)`
- the remaining late-stream movie behavior

- [ ] **Step 3: Only then plan a separate buffer-sizing or priming follow-up**

That follow-up must be a separate plan, not a “while I’m here” extension of this one.
