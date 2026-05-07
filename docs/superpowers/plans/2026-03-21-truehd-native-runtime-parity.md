# TrueHD Native Runtime Parity Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the remaining TrueHD audio-stutter/runtime mismatch by fixing native startup-vs-steady-state remainder ownership, then simplifying steady-state retry pacing, without changing transport or Media3-facing contract behavior.

**Architecture:** The work stays inside the native TrueHD runtime path and runtime diagnostics. Group 1 performs a structural split so startup-owned and steady-state-owned remainders cannot share mutable lifecycle state. Group 2 rewrites only the steady-state retry policy to be output-driven and bounded. Group 3 makes validation exports distinguish real hardware events from control-policy decisions.

**Tech Stack:** Android, Media3, JNI/C++, Kodi AE references, Nexio passthrough validator, Gradle, ADB

---

### Task 1: Freeze Current Truth And Guardrails

**Files:**
- Reference: `/Users/jneerdael/Scripts/nexio/docs/truehd-runtime-strict-audit.md`
- Reference: `/Users/jneerdael/Scripts/nexio/docs/truehd-media3-parity-checklist.md`
- Reference bundles:
  - `/tmp/transport-validation-truehd-1774092901186.zip`
  - `/tmp/transport-validation-truehd-1774099727942.zip`
  - `/tmp/transport-validation-truehd-1774100424076.zip`

- [ ] **Step 1: Reconfirm the baseline and exclusions**

Read the audit and checklist and restate these guardrails in working notes:
- do not touch MAT / IEC packing
- do not touch `EnsureTrueHdPassthroughOutputConfiguredLocked(...)`
- do not touch Java `AudioSink` contract methods
- do not touch route logic or position code unless the grouped task explicitly says so

- [ ] **Step 2: Capture the current comparison table**

Record the current key metrics from the three grounded runs:
- baseline `1774092901186`
- output-driven `1774099727942`
- zero-backoff `1774100424076`

Required fields:
- `transportVerdict`
- `timeToReadyMs`
- `audioUnderrunCount`
- `writeAttemptCount`
- `zeroWriteCount`
- `partialWriteCount`
- `remainderRetryEventCount`
- `longestStuckRemainderMs`
- `routeReopenCountAfterStart`

- [ ] **Step 3: Record implementation invariants**

Carry these invariants into Group 1 and keep them explicit in code review notes:
- at most one startup-owned pending packed remainder may exist
- at most one steady-state-owned pending packed remainder may exist
- only one pending packed remainder may be write-eligible at a time

- [ ] **Step 4: Commit no code yet**

Do not start implementation until the table and exclusions are present in the working notes.

### Task 2: Add Failing Coverage For Group 1 Boundaries

**Files:**
- Investigate existing tests under `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough`
- Investigate native test options under `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink`
- If no native unit harness exists, create a narrow reproduction script or test note under `/Users/jneerdael/Scripts/nexio/docs/`

- [ ] **Step 1: Identify the smallest testable seam**

Find whether `KodiTrueHdAEEngine.cpp` can be covered through:
- existing native tests
- JNI-accessible test hooks
- or a documented validator reproduction if no automated seam exists

Expected output:
- one paragraph in working notes naming the chosen seam

- [ ] **Step 2: Write the failing test or failing reproduction spec**

Required behavior to fail before Group 1:
- startup-owned pending packed output and steady-state-owned pending packed output must not share one mutable retry/ownership state object once steady-state split is introduced

If automated:
- add a failing test that asserts startup-owned state and steady-state-owned state are distinct after handoff

If not automated:
- write a one-off failing reproduction spec that shows current logs still report startup path and steady-state ownership on the same remainder object

- [ ] **Step 3: Verify the failure**

If automated:
- run only the new failing test

If manual/spec-based:
- link the exact failing evidence from current logs:
  - `/tmp/passthrough-validation-192.168.50.37-truehd-reverted-route-pass.log`
  - `/tmp/passthrough-validation-192.168.50.37-truehd-steady-state-output-driven.log`
  - `/tmp/passthrough-validation-192.168.50.37-truehd-steady-state-zero-backoff.log`

### Task 3: Implement Group 1 Data-Structure Split

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`
- Modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`

- [ ] **Step 1: Introduce separate native state for startup and steady-state packed remainders**

Implement separate native state so startup-owned and steady-state-owned pending packed output can no longer share one mutable lifecycle and retry record.

- [ ] **Step 2: Keep startup behavior unchanged**

This task is structural only.
Do not rewrite retry cadence in this task.
Only preserve current startup behavior while separating storage and transition state.

- [ ] **Step 3: Remove premature steady-state identity on startup-owned remainders**

Ensure the active remainder is not reported as effectively steady-state while Java startup is still active.

- [ ] **Step 4: Verify invariants directly**

Confirm in code and logs:
- startup-owned and steady-state-owned remainders cannot share mutable retry state
- no active remainder is reported as effectively steady-state while Java startup is still active

- [ ] **Step 5: Run focused verification**

Run:
```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac ':media:lib-exoplayer-kodi-cpp-audiosink:buildCMakeDebug[arm64-v8a][kodiCppAudioSinkJNI]'
./gradlew --no-daemon :app:assembleDebug
```

Expected:
- both pass

- [ ] **Step 6: Run one `.37` validator pass**

Use the exact validator workflow from:
- `/Users/jneerdael/Scripts/nexio/adb-passthrough-validator/SKILL.md`

Expected:
- transport still `PASS`
- route remains stable after startup
- no new playback regression
- audio improvement is not required yet

### Task 4: Add Failing Coverage For Group 2 Behavior

**Files:**
- Modify or add tests near the chosen seam from Task 2
- Reference: `/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAESink.cpp`
- Reference: `/Users/jneerdael/Scripts/nexio/media/xbmc/xbmc/cores/AudioEngine/Sinks/AESinkAUDIOTRACK.cpp`
- Reference: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java`
- Reference: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java`

- [ ] **Step 1: Write the failing steady-state rule**

Required steady-state rule:
- one active steady-state remainder
- one write attempt per flush
- positive write resets retry episode state
- zero write leaves remainder pending
- retry is allowed on the next natural output opportunity without playback-head / buffer-fit eligibility gating
- no sleeps on the playback/render path

- [ ] **Step 2: Verify the failure**

Run the failing test or document why the current code still depends on steady-state retry-policy state beyond pending-output truth.

### Task 5: Implement Group 2 Output-Driven Steady-State Policy

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Possibly modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.h`

- [ ] **Step 1: Restrict the rewrite to steady-state only**

Leave startup policy alone.
Only the steady-state remainder path should change.

- [ ] **Step 2: Remove steady-state eligibility heuristics from control**

Do not use playback-head delta, buffer-fit delta, or ownership heuristics as the main control gate for steady-state retries.
Keep these values available for diagnostics only:
- playback-head delta
- buffer-fit delta
- ownership
- retry reason
- cooldown state

- [ ] **Step 3: Implement bounded output-driven retry behavior**

Behavior target:
- pending steady-state remainder is the control truth
- one bounded write attempt per flush
- zero write ends the flush and keeps remainder pending
- later retry uses only a small bounded backoff if needed for repeated zero-write attempts on the same remainder
- implement bounded backoff only as timestamp/state checked on the next natural flush opportunity
- never sleep on the playback/render path

- [ ] **Step 4: Run focused verification**

Run:
```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac ':media:lib-exoplayer-kodi-cpp-audiosink:buildCMakeDebug[arm64-v8a][kodiCppAudioSinkJNI]'
./gradlew --no-daemon :app:assembleDebug
```

Expected:
- both pass

- [ ] **Step 5: Run one `.37` validator pass and compare against all grounded runs**

Compare against:
- `/tmp/transport-validation-truehd-1774092901186.zip`
- `/tmp/transport-validation-truehd-1774099727942.zip`
- `/tmp/transport-validation-truehd-1774100424076.zip`

Success target:
- transport still `PASS`
- route still stable
- `audioUnderrunCount` does not worsen
- real hardware zero-write behavior improves
- `longestStuckRemainderMs` improves materially

### Task 6: Add Failing Coverage For Group 3 Export Truthfulness

**Files:**
- Modify tests under `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough`
- Modify export/runtime files under `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough`

- [ ] **Step 1: Write failing tests for event classification**

Required export split:
- real hardware zero write
- retry suppressed by control policy
- deferred due to bounded backoff
- partial write
- remainder cleared
- underrun observed

- [ ] **Step 2: Add failing expectation for `playback-stats.json`**

Write a failing test or documented failing reproduction that proves `playback-stats.json` is still null in valid runs.

### Task 7: Implement Group 3 Runtime Truthfulness

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationDiagnosticsExporter.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollector.kt`
- Modify: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeValidation.kt`
- Modify/add tests in `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/debug/passthrough`

- [ ] **Step 1: Split control-policy events from hardware-write events**

Ensure export counters no longer count control backoff decisions as actual hardware zero writes.

- [ ] **Step 2: Verify event truthfulness first**

Run the affected exporter/runtime tests and one `.37` validator pass before treating this group as complete enough to tune against.

- [ ] **Step 3: Fix `playback-stats.json`**

Make valid runtime captures export playback stats consistently.

- [ ] **Step 4: Run focused unit verification**

Run the exact affected test targets you add or modify.

- [ ] **Step 5: Run one `.37` validator pass**

Expected:
- counters now distinguish hardware truth from control-policy truth
- transport and route still remain intact

### Task 8: Final Regression Gate

**Files:**
- Reference bundles and logs under `/tmp`
- Update:
  - `/Users/jneerdael/Scripts/nexio/docs/truehd-runtime-strict-audit.md`
  - `/Users/jneerdael/Scripts/nexio/docs/truehd-media3-parity-checklist.md`

- [ ] **Step 1: Produce a final comparison table**

Required columns:
- baseline
- post-Group-1
- post-Group-2
- post-Group-3

- [ ] **Step 2: Confirm all guardrails remain green**

Required:
- transport `PASS`
- `routeChangeCountAfterStableStart=0`
- `routeTupleChangeCountAfterStableStart=0`
- `routeReopenCountAfterStart=0`
- sustained playback to end

- [ ] **Step 3: Update the audit and parity docs**

Document what changed, what improved, and what remains open.

- [ ] **Step 4: Commit**

Commit after each group, then one final doc/update commit.
