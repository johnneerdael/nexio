# TrueHD Native Runtime Parity Implementation Plan (Phase 2)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the final late-stream track starvation gap causing `audioUnderrunCount=1` and audible stutter without reopening stable transport or contract layers. This is accomplished by eradicating artificial output backoff (Group 4) and configuring a stable OS buffer multiplier for massive MAT bursts (Group 5).

**Architecture:** The work stays inside the native TrueHD runtime steady-state path and Android OS `AudioTrack` configuration surface.

**Tech Stack:** Android, Media3, JNI/C++, Kodi AE references, Nexio passthrough validator, Gradle, ADB

---

### Task 1: Freeze Current Truth And Guardrails

**Files:**
- Reference: `/Users/jneerdael/Scripts/nexio/docs/truehd-runtime-strict-audit.md`
- Reference: `/Users/jneerdael/Scripts/nexio/docs/truehd-media3-parity-checklist.md`
- Reference bundle: `/tmp/transport-validation-truehd-1774108753637.zip`

- [ ] **Step 1: Reconfirm the baseline and exclusions**

Read the audit and checklist and restate these guardrails in working notes:
- do not touch MAT / IEC packing
- do not touch Java `AudioSink` contract methods (`handleBuffer`, `hasPendingData`, `isEnded`, `getCurrentPositionUs`)
- do not change route logic or position code
- do not break the startup vs steady-state remainder split accomplished in Phase 1

- [ ] **Step 2: Capture the current comparison table**

Record the current key metrics from the Phase 1 final run `1774108753637`:
- `transportVerdict`: PASS
- `audioUnderrunCount`: 1
- `continuousPlayingWindowSatisfied`: true
- `routeChangeCount`: 1
- `longestZeroWriteStreakMs`: 62
- `longestStuckRemainderMs`: 457
- `maxZeroWriteStreak`: (absent/0)

- [ ] **Step 3: Commit no code yet**

Do not start implementation until the table and exclusions are present in the working notes.

---

### Task 2: Implement Group 4 (Eradicate Artificial Steady-State Backoff)

**Goal:** Allow Media3's ExoPlayer `doSomeWork` poll loop to drive the hardware directly instead of throttling steady-state writes using an artificial 20ms backoff in the native layer.

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp`

- [ ] **Step 1: Locate steady-state retry control**
Find `ShouldRetrySteadyStatePendingPackedRemainderLocked` inside `KodiTrueHdAEEngine.cpp`.

- [ ] **Step 2: Strip the artificial 20ms cooldown**
Remove `kSteadyStateRetryZeroBackoffUs` logic. The method should compute the diagnostic metrics (`playbackHeadDeltaFrames`, `bufferFitDeltaFrames`) for export truthfulness but should **unconditionally return true** with `retryReason = "steady_state_output_driven"`.
*Why? Media3 will invoke `handleBuffer` on its own 10ms loop. If the track is full, `WriteNonBlocking` will return 0, we update offsets, and yield backpressure natively. We do not need to prevent Media3 from trying again when it chooses to.*

- [ ] **Step 3: Run focused unit verification**
Run:
```bash
./gradlew --no-daemon :media:lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac ':media:lib-exoplayer-kodi-cpp-audiosink:buildCMakeDebug[arm64-v8a][kodiCppAudioSinkJNI]'
./gradlew --no-daemon :app:assembleDebug
```
Expected: Both pass.

- [ ] **Step 4: Run one ADB validator pass**
Use the `truehd` validation workflow.
**Expected:** Transport still `PASS`. No new playback stalls. The `audioUnderrunCount` might disappear, or it might persist if Group 5 (buffer sizing) is also required.

---

### Task 3: Implement Group 5 (TrueHD AudioTrack Buffer Multiplier)

**Goal:** Ensure the OS `AudioTrack` buffer is large enough to hold at least 3-4 massive MAT bursts (each ~61,440 bytes) to absorb JVM thread scheduling jitter now that we are relying purely on Media3's synchronous poll loop.

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiTrueHdNativeAudioSink.java` (if buffer size config originates from Java properties/config mapping) OR `KodiTrueHdAEEngine.cpp` in `EnsureTrueHdPassthroughOutputConfiguredLocked`.

- [ ] **Step 1: Analyze current buffer sizing**
Investigate how `output_.ConfigureTrueHd(...)` determines the `AudioTrack` buffer size. In stock Media3, `DefaultAudioSink` applies a multiplier (e.g., `PASSTHROUGH_BUFFER_DURATION_US`) when calculating the min buffer size via `AudioTrack.getMinBufferSize`.

- [ ] **Step 2: Increase TrueHD specific buffer margin**
If the buffer sizing logic limits the capacity to a generic IEC passthrough duration (e.g., 250ms), explicitly override or multiply it for TrueHD/MAT to guarantee at least 100ms-250ms of physical buffer capacity *at the MAT frame size* (~61440 bytes per 8 frames). 
*Note: Make sure to check if `output_.ConfigureTrueHd` calls into the bridge where we can pass a specific multiplier, or if we modify the Java-side `AudioTrack` builder directly.*

- [ ] **Step 3: Run focused unit verification**
Compile native and Java targets via gradle.

- [ ] **Step 4: Run final ADB validator pass**
Use the `truehd` validation workflow.
**Success Target:** 
- `transportVerdict=PASS`
- `audioUnderrunCount=0` (The starvation gap is closed!)
- `continuousPlayingWindowSatisfied=true`

---

### Task 4: Final Regression Gate & Parity Update

**Files:**
- Reference bundles and logs under `/tmp`
- Update:
  - `/Users/jneerdael/Scripts/nexio/docs/truehd-runtime-strict-audit.md`
  - `/Users/jneerdael/Scripts/nexio/docs/truehd-media3-parity-checklist.md`

- [ ] **Step 1: Produce a final comparison table**
Record the final validation metrics proving `audioUnderrunCount` is resolved while transport remains perfect.

- [ ] **Step 2: Confirm all guardrails remain green**
Required:
- transport `PASS`
- `routeChangeCountAfterStableStart=0`
- `routeReopenCountAfterStart=0`
- sustained playback to end without stall.

- [ ] **Step 3: Update the audit and parity docs**
Check off Action Items 1 and 2 in `truehd-media3-parity-checklist.md` as `stock-like` or `proven necessary divergence` depending on the outcome.

- [ ] **Step 4: Commit**
Commit the final implementation of Phase 2.
