# Playback Instrumentation — Work Plan

Spec source: `.omc/specs/deep-interview-playback-instrumentation.md` (RALPLAN-DR short mode)

## 1. Requirements Summary
- Produce one correlated playback trace (sessionId + monotonic `elapsedRealtimeNanos`) spanning player, PRDS, scheduler, OkHttp, frontier, cache, decode, device-health so stutters on "should-be-comfortable" streams classify deterministically.
- Release-safe: runtime toggle in `DebridSettingsContent`, no `BuildConfig.DEBUG`, no ProGuard-strippable reflection; toggle-OFF is an inline early-return.
- Hot path: lock-free MPSC ring + dedicated writer thread, drop-on-overflow, zero steady-state allocation; p99 enqueue ≤ 5 µs, toggle-off overhead ≤ 1%.
- Sink: rotating JSONL in `filesDir/playback-traces/<sessionId>.jsonl` (≤ 8 MiB, ≤ 20 sessions); export via FileProvider share + SAF `ACTION_CREATE_DOCUMENT`.
- Non-goals: no policy/scheduler/cache logic changes, no Perfetto, no remote upload, no fixes to known suspects — instrumentation only (spec §Non-Goals, §F).

## 2. RALPLAN-DR Summary (SHORT mode)

### Principles
1. Never perturb the hot path — emit outside `synchronized` blocks, use `HotPathHistogram` (`LongAdder`) aggregators, one volatile read + early return when disabled.
2. One `sessionId` + one monotonic clock (`SystemClock.elapsedRealtimeNanos()`) end-to-end for correlation.
3. Release-safe by construction — `@JvmField` enabled flag, `inline` emit, no `BuildConfig.DEBUG`, ProGuard keep rules for `PlaybackTracer`/`EventFamily`.
4. Reuse existing seams (`TransportValidationRuntimeCollector`, `PlayerTransportTelemetry`) — wrap, never duplicate Media3 `AnalyticsListener`.
5. Instrumentation only in v1 — no behavioral fixes; suspects become follow-up tickets gated on classifier evidence.

### Decision Drivers
1. Unblock deterministic stutter classification (TRANSPORT / FACADE / SCHEDULER / FRONTIER / CACHE / DECODE / DEVICE).
2. Zero measurable hot-path overhead on release APK.
3. Usable on Fire TV release builds without rebuilding / adb.

### Viable Options
- **Option A — Single-pass**: land tracer + all 9 insertion sections + classifier + microbenchmark in one change.
  - Pros: one merge, one review.
  - Cons: huge blast radius across 6 hot-path files; impossible to parallelize; microbenchmark regressions hard to bisect; release-build issues surface last.
- **Option B — Staged rollout (spec §E order)** *(RECOMMENDED)*: tracer+toggle → UI/export → session header → PRDS facade → scheduler+OkHttp → frontier aggregators → cache/warm-ahead → collector reuse+device health → classifier+fixture → microbenchmark gate.
  - Pros: each stage independently testable; hot-path stages isolated; parallel lanes possible (UI vs tracer core); microbenchmark is the final gate so regressions bisect to one package.
  - Cons: more PRs / coordination surface.
- **Option C — Spec-only, no code this cycle**.
  - Pros: zero risk.
  - Cons: does not satisfy the goal; stutters remain unclassifiable; wastes the locked spec.

**Recommendation: Option B.** Invalidation: A fails Principle 1 (can't protect hot path without staged microbenchmark gate) and Driver 2. C fails Driver 1 outright.

## 3. Work Packages

Legend: P = parallelizable with previous package.

### WP1 — Tracer core + toggle + tests
- **Owner**: executor (opus) + test-engineer
- **Files (NEW)**: `app/src/main/java/com/nexio/tv/instrumentation/PlaybackTracer.kt`, `SessionHeader.kt`, `SessionWriter.kt`, `TraceRecord.kt`, `PayloadBuilder.kt`, `EventFamily.kt`, `HotPathHistogram.kt`, `PlaybackTraceToggle.kt`; Hilt module `app/src/main/java/com/nexio/tv/core/di/PlaybackTracerModule.kt`; ProGuard keep rule `app/proguard-rules.pro`; FileProvider `app/src/main/res/xml/file_paths.xml`.
- **Anchors**: spec §A.1–A.4.
- **Deps**: none.
- **Acceptance**: unit tests for ring overflow→`tracer_overflow`, rotation at 8 MiB, session lifecycle; `HotPathHistogram` p50/p99 deterministic with seeded inputs; `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.instrumentation.*"` green; `./gradlew assembleArm64Release` succeeds (keep rules verified).
- **Amendment (round 2)**:
  - A4: Add `org.jctools:jctools-core:4.0.5` to `gradle/libs.versions.toml` AND `app/build.gradle.kts` dependencies. Add `androidx.benchmark-junit4` in the same pass if not already present (check `libs.versions.toml` first). Ship a sanity stress test that round-trips `MpscArrayQueue<TraceRecord>` under 4 producers / 1 consumer. No hand-rolled ring; no fallback hedge.
  - A7: `TraceRecord.obtain()` uses a `ThreadLocal<ArrayDeque<TraceRecord>>` with a 64-element per-thread cap; overflow recycles into a shared bounded `MpscArrayQueue<TraceRecord>` freelist (capacity 1024). No `ConcurrentLinkedQueue`. Unit test asserts zero allocation on the steady-state hot path once thread-local pools are warmed.
  - C8: ProGuard keep rules are:
    - `-keep class com.nexio.tv.instrumentation.** { *; }`
    - `-keepclassmembers class com.nexio.tv.instrumentation.PlaybackTracer { public static boolean enabled; }`
    Verify via `./gradlew assembleArm64Release` that the mapping file retains these symbols and R8 does not inline the `@JvmField` check away.
- **Parallelizable?** No — foundation.

### WP2 — Debrid settings toggle + export actions (P with WP3–WP4)
- **Owner**: designer + executor
- **Files**: `app/src/main/java/com/nexio/tv/ui/screens/settings/DebridSettingsContent.kt` (~line 272+ add `item { PlaybackDiagnosticsSection(...) }`), `DebridUiState` (lines 110–142 add `playbackTraceEnabled`, `lastTraceSummary`), `DebridSettingsViewModel.kt` (collect `enabledFlow`, add `setPlaybackTraceEnabled`/`exportLastSession`/`exportAllSessions`/`copyLastToDownloads`), `AndroidManifest.xml` FileProvider.
- **Anchors**: spec §C.8.
- **Deps**: WP1 (`PlaybackTraceToggle`).
- **Acceptance**: toggling updates `PlaybackTracer.enabled` without restart; DataStore persists; share/SAF intents compile and emit correct MIME; manual Fire TV smoke reaches share sheet.
- **Amendment (round 2)**:
  - A5: `PlaybackDiagnosticsSection` ships in WP2 but is gated behind `internal const val PLAYBACK_TRACE_UI_ENABLED = false` in a new file `app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceUiFlag.kt`. The LazyColumn item in `DebridSettingsContent.kt` wraps the section: `if (PLAYBACK_TRACE_UI_ENABLED) item { PlaybackDiagnosticsSection(...) }`. WP9 flips the constant to `true` as part of its acceptance. This closes the partial-trace classifier window.
  - C4: Toggling OFF mid-session calls `PlaybackTracer.endSession(currentSessionId)`, which flushes the ring, writes `playback_session_ended { reason = "toggle_off" }`, rotates the file, and sets `current = null`. Toggling ON mid-playback is a no-op at the current MediaSource — the next `createMediaSource()` call creates a fresh sessionId. Documented in spec §A.1 setter path comment. Unit test covers both transitions.
- **Parallelizable?** Yes — pure UI/VM lane.

### WP3 — Session header capture in PlayerMediaSourceFactory
- **Owner**: executor
- **File**: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`.
- **Anchors** (spec §C.1): `createMediaSource()` ~114 allocate sessionId/startedAtNanos; branch decision ~169; PRDS ctor ~173–190 capture `FactoryArgs`; after `PlayerTransportTelemetry.log("pmsf.create",...)` ~200 call `beginSession` then `emit(SESSION,"playback_session_started")`; player-release path calls `endSession`.
- **Deps**: WP1.
- **Acceptance**: single `sessionId` propagates; `playback_session_started` event contains full `SessionHeader`; `playback_session_ended` written on release.
- **Amendment (round 2)**:
  - A3: Session lifetime is **MediaSourceSession**. Each `PlayerMediaSourceFactory.createMediaSource()` call creates a new session. Immediately after `PlaybackTracer.beginSession(header)`, call `TransportValidationRuntimeCollector.bindSession(sessionId)` so the collector attaches the current sessionId to every `rebuffer_start`/`rebuffer_end`/decode event. Binge playback under a single ExoPlayer intentionally produces multiple sessions — this is the pinned behavior.
  - C6: No conditional or control-flow change added to pre-existing code beyond the `if (PlaybackTracer.enabled) PlaybackTracer.emit(...)` pattern (which compiles via `inline` to an `@JvmField` check + early return). Reviewer confirms via diff inspection.
- **Parallelizable?** No — prerequisite for WP4–WP8.

### WP4 — PRDS facade events (P with WP5)
- **Owner**: executor
- **File**: `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`.
- **Anchors** (spec §C.2): `open()` 201, bootstrap 241, fallback 399–402, parallel attach 540–547, `read()` 636, `waitForBytesAt()` 582/596–603, `close()` 776–806.
- **Deps**: WP3.
- **Acceptance**: `prds_open_start/_mode/_resolved/_close` + `read_wait_start/_end/_return/_timeout/_error` present in captured JSONL; no allocation in `read()` steady path (verified by microbenchmark in WP10).
- **Amendment (round 2)**:
  - C6: No conditional or control-flow change added to pre-existing code beyond the `if (PlaybackTracer.enabled) PlaybackTracer.emit(...)` pattern. Reviewer confirms via diff inspection.
  - A1 (consumer side): `ParallelRangeDataSource.Factory` is updated to consume `@Named("playbackTraced") OkHttpClient` from Hilt (see WP5 amendment).
- **Parallelizable?** Yes with WP5 (different files).

### WP5 — Scheduler + OkHttp EventListener (P with WP4)
- **Owner**: executor
- **Files**: `app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`, `DualLaneScheduler.kt`, NEW `app/src/main/java/com/nexio/tv/instrumentation/PlaybackOkHttpEventListener.kt` + `RangeContext.kt`, `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt` (playback client only).
- **Anchors** (spec §C.3): SPTM attach 102–103, detach 112–125, promote 169–192, submit urgent 244, submit prefetch 251, retry 271–330, lane change 336–348, range start 257–269, done 317–330, scratch 394/398/409, budget 547–569. §C.4: DLS 85–95, 141–145, 179–189. §C.7: `Request.Builder().tag(RangeContext::class.java, ...)` in both `downloadRange()` and `downloadRangeIntoScratch()`; `eventListenerFactory { PlaybackOkHttpEventListener(...) }` on playback OkHttpClient only.
- **Deps**: WP3.
- **Acceptance**: range events correlate `chunkIndex`/`lane`/`attempt`; `range_http_*` events present; playback client attaches listener, other clients do not (verify in `NetworkModule`).
- **Amendment (round 2)**:
  - A1 (BLOCKER fix): Introduce a new `@Named("playbackTraced") OkHttpClient` provider in `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`. It is a `.newBuilder()` child of `@Named("playback")` that adds `PlaybackOkHttpEventListener` via `.eventListenerFactory(...)`. `SharedParallelTransportManager` and `ParallelRangeDataSource.Factory` consume only `@Named("playbackTraced")`. Leave Trakt/AddonCatalog/AddonStreams/MDBList untouched. **Scope correction (Critic)**: the actual leak scope is narrower than first claimed — only `@Named("benchmark")` at NetworkModule.kt:261-267 derives from `@Named("playback")`; Trakt (:163), AddonCatalog (:223), AddonStreams (:242), MDBList (:432) all derive from the unnamed base client at line 90 and are unaffected. The fix stands: a new named client prevents the benchmark client from inheriting the playback listener and prevents any future playback-child from picking it up.
  - C6: No conditional or control-flow change added to pre-existing code beyond the `if (PlaybackTracer.enabled) PlaybackTracer.emit(...)` pattern. Reviewer confirms via diff inspection.
- **Parallelizable?** Yes.

### WP6 — Frontier aggregators (hot-path sensitive)
- **Owner**: executor (opus)
- **File**: `app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`.
- **Anchors** (spec §C.5): `onBytesWritten()` 98 lock-wait instrumentation, `advanceFrontier()` 277–286, `publishCompleteChunk()` 195–241, `read()` 150–180, `evictBefore()` 246–259, page lifecycle 25–38; NEW `FrontierStallDetector` polled at 100 ms.
- **Rule**: emits MUST happen outside `synchronized(this)`; use `HotPathHistogram.LongAdder` feed + writer-thread drain at 1 Hz.
- **Deps**: WP1 (`HotPathHistogram`), WP3.
- **Acceptance**: no new allocations inside `synchronized(this)` (verify by inspection + ast-grep); `store_lock_wait_ms`, `store_write_ms`, `store_read_ms`, `frontier_advance`, `frontier_no_progress_band` appear with sane p50/p99; WP10 microbenchmark shows ≤ 1% overhead toggle-off.
- **Amendment (round 2)**:
  - A2: Pin `advanceFrontier()` signature to `internal fun advanceFrontier(): Long` returning the delta (0L if no advance). Callers `onBytesWritten` and `publishCompleteChunk` hold `oldFrontier` as a pre-lock local, release the monitor, then emit `frontier_advance { putLong("delta", delta); putLong("newBytes", oldFrontier + delta) }` **only when `delta > 0L`**. Zero allocation, deterministic ordering.
  - C6: No conditional or control-flow change added to pre-existing code beyond the `if (PlaybackTracer.enabled) PlaybackTracer.emit(...)` pattern. Reviewer confirms via diff inspection.
- **Parallelizable?** No — isolated for risk.

### WP7 — Cache + warm-ahead (P with WP8)
- **Owner**: executor
- **File**: `PlayerMediaSourceFactory.kt` — `buildVodCacheDataSourceFactory()` 881–906 wrap with `TracingCacheDataSourceFactory` decorator; `notifyRebuffer()` 673–675; warm-ahead loop 764.
- **Anchors**: spec §C.1 cache rows.
- **Deps**: WP3.
- **Acceptance**: `cache_active`, `cache_event`, `warm_ahead_start/_stop/_loop_iteration_ms`, `cache_write_latency_ms` present; no changes to cache config.
- **Amendment (round 2)**:
  - C5: PR description must include a one-line proof that `PlayerMediaSourceFactory.notifyRebuffer()` call sites run on the main/player thread (not the Media3 loader thread). Grep for `notifyRebuffer` call sites, list them in the PR body. If any call site runs on the loader thread, the emission must be moved or the caller documented as hot-path-safe.
  - C6: No conditional or control-flow change added to pre-existing code beyond the `if (PlaybackTracer.enabled) PlaybackTracer.emit(...)` pattern. Reviewer confirms via diff inspection.
- **Parallelizable?** Yes.

### WP8 — Collector reuse + DeviceHealthSampler (P with WP7)
- **Owner**: executor
- **File**: `app/src/main/java/com/nexio/tv/debug/passthrough/TransportValidationRuntimeCollector.kt`; NEW `app/src/main/java/com/nexio/tv/instrumentation/DeviceHealthSampler.kt`.
- **Anchors** (spec §C.6): attach 170–178, state 87–92, isPlaying 95–107, firstFrame 109–120, dropped 134–147, underrun 149–167, ttfb 307, peakMem 313–315; DHS owns 1 Hz `memory_snapshot`, `PowerManager.addThermalStatusListener`, `ActivityManager.MemoryInfo`.
- **Deps**: WP3.
- **Acceptance**: `rebuffer_start/_end` with full snapshot, all DECODE events emitted via existing `AnalyticsListener` (no second listener registered), DEVICE family populated at 1 Hz.
- **Amendment (round 2)**:
  - A3: Add `TransportValidationRuntimeCollector.bindSession(sessionId: String)` which stores the current sessionId and attaches it to every `rebuffer_start`/`rebuffer_end`/decode event the collector emits. Called from `PlayerMediaSourceFactory.createMediaSource()` immediately after `PlaybackTracer.beginSession(header)`.
  - A6 (worktree coordination): A concurrent worktree at `.omc/team/omx-plans-public-shadow-collec/worktrees/worker-1/` modifies `TransportValidationRuntimeCollector.kt`. This plan lands AFTER shadow-collec merges. The WP8 executor MUST rebase onto the landed shadow-collec changes before editing the collector. If shadow-collec is still open when WP8 starts, pause WP8 and re-check.
  - C6: No conditional or control-flow change added to pre-existing code beyond the `if (PlaybackTracer.enabled) PlaybackTracer.emit(...)` pattern. Reviewer confirms via diff inspection.
- **Parallelizable?** Yes.

### WP9 — StutterClassifier + fixture test
- **Owner**: test-engineer + executor
- **Files (NEW)**: `app/src/main/java/com/nexio/tv/instrumentation/StutterClassifier.kt`, `ParsedSession.kt`, `RebufferWindow.kt`; test `app/src/test/java/com/nexio/tv/instrumentation/StutterClassifierTest.kt`; fixture `app/src/test/resources/sessions/<captured>.jsonl`.
- **Anchors**: spec §D (6 rules verbatim).
- **Deps**: WP3–WP8 (needs a real capture).
- **Acceptance**: fixture parses; `classify()` returns expected `Cause` for each of the 6 rule branches (TRANSPORT/FACADE/DECODE_RENDER/DEVICE/CACHE_INTERFERENCE/POLICY_MISMATCH) plus UNKNOWN path; test runs under `./gradlew testArm64DebugUnitTest`.
- **Amendment (round 2)**:
  - A5: Flip `PLAYBACK_TRACE_UI_ENABLED` from `false` to `true` in `app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceUiFlag.kt`. This is part of WP9 acceptance.
  - C7 (fallback clause): Primary path requires one real captured stutter session from Fire TV hardware. **Fallback path**: if no reliable stutter reproduction within **2 calendar days** of WP9 start, WP9 may substitute a synthesized JSONL fixture generated by replaying known-bad timing patterns (recorded `tNanos` offsets with injected `range_http_body` gaps > 500 ms and `read_wait` spikes > 200 ms). The fallback fixture is committed under `app/src/test/resources/sessions/synthetic-transport-stutter.jsonl` and the spec acceptance criterion is marked "satisfied by synthetic fixture pending real capture" with a follow-up ticket to backfill with a real capture.
- **Parallelizable?** No.

### WP10 — Microbenchmark gate
- **Owner**: test-engineer
- **File (NEW)**: `app/src/test/java/com/nexio/tv/instrumentation/PlaybackTracerBenchmarkTest.kt`.
- **Deps**: WP1, WP4, WP6.
- **Acceptance**: asserts p99 enqueue ≤ 5 µs (toggle ON), toggle-OFF overhead ≤ 1% vs no-tracer baseline in `ParallelRangeDataSource.read()` simulation. Failing this gate blocks release.
- **Amendment (round 2, C3 — methodology pinned concretely)**:
  - **Harness**: `androidx.benchmark-junit4`. Check `gradle/libs.versions.toml` for existing `androidx.benchmark` entry; if absent, add it in WP1 alongside jctools.
  - **Shape**: `PlaybackTracerBenchmarkTest` drives `PlaybackTracer.emit(FRONTIER, "frontier_advance") { putLong("delta", 65536) }` in a tight loop of **1,000,000 iterations** after a **10,000-iteration warmup**.
  - **Three variants**:
    1. `baseline-no-emit` — same harness with the `emit(...)` call site removed via `if (false) { ... }`.
    2. `toggle-off` — `emit(...)` present, `PlaybackTracer.enabled = false`.
    3. `toggle-on` — `emit(...)` present, `PlaybackTracer.enabled = true`, drop-on-overflow ring + writer thread draining to a `/dev/null`-equivalent sink.
  - **Metric**: p99 nanoseconds per `emit` call, measured via `androidx.benchmark` microbenchmark library.
  - **Gates**:
    - toggle-OFF p99 ≤ **20 ns** (essentially the `if (!enabled) return` cost).
    - toggle-ON p99 ≤ **5 µs (5000 ns)**.
    - |toggle-OFF p99 − baseline-no-emit p99| ≤ **1%** of baseline-no-emit absolute p99.
  - Any gate failure blocks release.
- **Parallelizable?** No — terminal gate.

## 3.5 Current Implementation Status / Handoff

Status snapshot for the next engineer continuing from this document:

- **WP4 — COMPLETE**
  - Implemented in `app/src/main/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSource.kt`.
  - Landed PRDS/session-façade events: `prds_open_start`, `prds_open_mode`, `prds_open_resolved`, `prds_close`, `read_return`, `read_error`, `read_wait_start`, `read_wait_end`, `read_wait_return`, `read_wait_timeout`, `read_wait_error`.
  - Added focused regression: `app/src/test/java/com/nexio/tv/ui/screens/player/ParallelRangeDataSourceTracingTest.kt`.
  - Verification already run: targeted PRDS/tracer unit tests + `./gradlew assembleArm64Debug`.

- **WP5 — COMPLETE**
  - Implemented in:
    - `app/src/main/java/com/nexio/tv/instrumentation/RangeContext.kt`
    - `app/src/main/java/com/nexio/tv/instrumentation/PlaybackOkHttpEventListener.kt`
    - `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
    - `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
    - `app/src/main/java/com/nexio/tv/ui/screens/player/SharedParallelTransportManager.kt`
    - `app/src/main/java/com/nexio/tv/ui/screens/player/DualLaneScheduler.kt`
    - `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt`
  - `@Named("playbackTraced")` exists as a child of `@Named("playback")`; benchmark remains on `@Named("playback")`.
  - Range attempts now carry `chunkIndex` / `lane` / `attempt` context, and playback-only OkHttp emits `range_http_*`.
  - Verification already run: targeted scheduler/client/tracing tests + `./gradlew assembleArm64Debug`.

- **WP6 — COMPLETE**
  - Implemented in `app/src/main/java/com/nexio/tv/ui/screens/player/PagedFrontierBuffer.kt`.
  - `advanceFrontier()` is now `internal fun advanceFrontier(): Long`.
  - `frontier_advance` emits outside the monitor only when `delta > 0L`.
  - Hot-path histograms added for `store_lock_wait_ms`, `store_write_ms`, `store_read_ms`; opportunistic `frontier_no_progress_band` emission added.
  - Regression added in `app/src/test/java/com/nexio/tv/ui/screens/player/PagedFrontierBufferTest.kt`.
  - Fresh verification rerun: `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.player.PagedFrontierBufferTest" --tests "com.nexio.tv.ui.screens.player.PagedFrontierBufferPublishCompleteChunkTest"` and `./gradlew assembleArm64Debug`.

- **WP7 — COMPLETE**
  - Implemented in `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`.
  - Landed cache/warm-ahead events: `cache_active`, `cache_event`, `warm_ahead_start`, `warm_ahead_stop`, `warm_ahead_loop_iteration_ms`, `cache_write_latency_ms`.
  - `buildVodCacheDataSourceFactory()` now attaches `PlaybackTraceCacheEventListener`.
  - Focused tests added in `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactoryTest.kt`.
  - Fresh verification rerun:
    - `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.player.PlayerMediaSourceFactoryTest" --tests "com.nexio.tv.ui.screens.player.PlaybackCallTimeoutTest" --tests "com.nexio.tv.ui.screens.player.WarmAheadIsolationTest"`
    - `./gradlew assembleArm64Debug`
  - `notifyRebuffer()` call-site proof for future PR/body: current grep shows the sole call site at `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt:601`.

- **Next work package**
  - **Start at WP8**.
  - Before editing WP8, re-check the plan note about the concurrent `TransportValidationRuntimeCollector.kt` worktree and confirm the shadow-collec work has landed/rebased cleanly.

- **Known unrelated compile/build note**
  - A minimal compile unblock was applied in `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAddonSubtitleOverlay.kt` (`currentCoroutineContext().isActive`) because that file was already dirty and otherwise blocked fresh verification. This was not part of WP6/WP7 scope, but it is present in the working tree and may need to be reconciled by the owning lane.

## 4. Acceptance Criteria
Verbatim from spec §Acceptance Criteria:
- [ ] Toggle in `DebridSettingsContent.kt` enables/disables tracing without restart; persisted via DataStore.
- [ ] With toggle OFF, microbenchmark of `ParallelRangeDataSource.read()` shows ≤ 1% overhead vs. baseline (`@JvmField` enabled-flag + early return).
- [ ] With toggle ON on a release APK (`./gradlew assembleArm64Release`), playing a 90 Mbps title for 5 minutes produces a JSONL file in `filesDir/playback-traces/<sessionId>.jsonl` containing all 12 event families (SESSION, POLICY, BRANCH, PRDS, RANGE, FRONTIER, READ_WAIT, CACHE, REBUFFER, DECODE, DEVICE, TRACER).
- [ ] Every event in a session shares the same `sessionId` and uses the same monotonic `tNanos` base.
- [ ] One real captured stutter session feeds the `StutterClassifier` and produces the correct cause label.
- [ ] Export action in debrid menu produces a `.jsonl` (or `.zip`) reachable from Fire TV via SAF / share sheet.
- [ ] Hot-path enqueue p99 ≤ 5 µs measured via `PlaybackTracerBenchmarkTest`.
- [ ] Drop-on-overflow counter is itself logged as a `tracer_overflow` event.

Additions:
- [ ] Plan changelog in place (§8 below).
- [ ] No changes to playback policy, scheduler logic, or cache config beyond adding hooks — verified by diff review and by confirming no behavioral branches were added/removed in SPTM/DLS/PMSF.

## 5. Risks & Mitigations
- **PagedFrontierBuffer hot-path regression** → Use `HotPathHistogram` (`LongAdder` feed, writer-thread drain); emit strictly outside `synchronized(this)`. WP10 microbenchmark is a hard gate (p99 ≤ 5 µs, ≤ 1% overhead).
- **jctools dependency (decision committed, amendment A4)** → Add `org.jctools:jctools-core:4.0.5` to `gradle/libs.versions.toml` and `app/build.gradle.kts` in WP1. 200 KB, zero transitive deps, Apache 2.0. No hand-rolled fallback; no hedge.
- **Concurrent worktree on `TransportValidationRuntimeCollector.kt` (amendment A6)** → A worktree at `.omc/team/omx-plans-public-shadow-collec/worktrees/worker-1/` is concurrently modifying the collector. This plan lands AFTER shadow-collec merges. WP8 executor must rebase onto the landed shadow-collec changes before editing the collector. If shadow-collec is still open when WP8 starts, pause WP8 and re-check.
- **OkHttp `EventListener` tagging overhead** → Listener attached ONLY on playback client in `NetworkModule` (not global). Request tags allocated once per range attempt (already allocating `Request`).
- **ProGuard/R8 stripping `inline emit()`** → Add keep rule: `-keep class com.nexio.tv.instrumentation.PlaybackTracer { *; }` and `-keep enum com.nexio.tv.instrumentation.EventFamily { *; }`. Validated by WP1 release-build assembly.
- **Existing `Log.d/Log.w` in retry paths** → Keep; tracer complements, does not replace. No deletions of existing telemetry.
- **FileProvider authority missing `playback-traces`** → WP1 adds `<files-path name="playback-traces" path="playback-traces/"/>` to `res/xml/file_paths.xml`.
- **Drop-on-overflow biases classifier** → `SessionWriter` emits periodic `tracer_overflow { droppedCount }` so downstream analysis detects biased windows.
- **Line-number drift > ±20** → Implementers re-locate by symbol name before editing; spec §C explicitly authorizes this.

## 6. Verification Steps
1. `./gradlew assembleArm64Debug` — clean debug build.
2. `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.instrumentation.*"` — tracer + classifier + benchmark unit tests.
3. `./gradlew assembleArm64Release` — **hard release-build gate** (no `BuildConfig.DEBUG` leakage, ProGuard keep rules correct).
4. Manual Fire TV smoke: install release APK, enable toggle in debrid settings, play a 90 Mbps title ≥ 5 min, export via "Copy to Downloads…" (SAF), confirm `.jsonl` non-empty and contains all 12 event families with shared `sessionId`.
5. Feed captured session through `StutterClassifier` fixture test (WP9) — asserts cause label.

### Manual Fire TV smoke pass/fail checklist (amendment C10)
- [ ] JSONL file exists at `Android/data/com.nexio.tv/files/playback-traces/<sessionId>.jsonl`
- [ ] File size > 16 KB after 5-minute playback
- [ ] All 12 event families present: `jq -r '.fam' file.jsonl | sort -u | wc -l` returns 12
- [ ] sessionId uniqueness: `jq -r '.sid' file.jsonl | sort -u | wc -l` returns 1
- [ ] Monotonic timestamps: `tNs` non-decreasing per thread — verified by a committed `tools/verify-trace-jsonl.sh` (or equivalent `jq` script)
- [ ] Export via "Copy to Downloads" SAF path produces byte-identical content to the source file
- [ ] `tracer_overflow` count (if any) is logged in the `playback_session_ended` summary

## 7. ADR
- **Decision**: Adopt **Option B — staged rollout** following spec §E order (10 work packages, WP4/WP5 and WP7/WP8 parallelized).
- **Drivers**: (1) unblock deterministic stutter classification, (2) zero measurable hot-path overhead, (3) release-build usability on Fire TV without adb/rebuild.
- **Alternatives considered**:
  - Option A (single-pass) — rejected: unbounded blast radius across 6 hot-path files, unable to bisect microbenchmark regressions, review risk.
  - Option C (spec-only) — rejected: does not satisfy the goal, wastes the locked spec.
- **Why chosen**: B is the only option where the hot-path packages (WP4, WP6) can be gated by the terminal microbenchmark (WP10) before merging downstream, while allowing UI (WP2), network listener (WP5), cache (WP7), and collector (WP8) lanes to proceed in parallel.
- **Consequences**:
  - (+) Each package is independently reviewable and revertible.
  - (+) Microbenchmark is a genuine gate, not an afterthought.
  - (−) Requires discipline to keep packages scoped — no behavior changes may sneak in.
  - (−) More PRs / review surface.
- **Follow-ups** (gated on classifier evidence from this instrumentation, NOT in scope here):
  - Prefetch workers hardcoded to `1` in the fallback path.
  - Null-policy fallback `(2, 16 MiB)` — revisit once POLICY_MISMATCH rates are measured.
  - `PagedFrontierBuffer` monitor contention — gated on `store_lock_wait_ms` p99.
  - OkHttp unbounded `callTimeout` — gated on `range_http_call_end` long-tail distribution.
  - Phase 2: Perfetto + `FrameMetricsAggregator` + remote sink (spec §F).

## 8. Changelog

### Round 2 (Architect + Critic consolidated, single pass)
- **A1**: WP5 + §5 Risks + spec §C.7 — introduce `@Named("playbackTraced") OkHttpClient` as `.newBuilder()` child of `@Named("playback")`; SPTM and PRDS.Factory are sole consumers. Recorded Critic scope correction: only `@Named("benchmark")` (NetworkModule.kt:261-267) derives from `@Named("playback")`; Trakt/AddonCatalog/AddonStreams/MDBList derive from the unnamed base client (:90).
- **A2**: WP6 + spec §C.5 — pinned `advanceFrontier(): Long` returning delta; emit `frontier_advance` only when `delta > 0L`, outside the monitor, zero allocation.
- **A3**: WP3 + WP8 + spec §C.1 — session lifetime = MediaSourceSession; added `TransportValidationRuntimeCollector.bindSession(sessionId)` invoked from `createMediaSource()` after `beginSession`.
- **A4**: WP1 — committed `org.jctools:jctools-core:4.0.5` (no hedge, no fallback); added stress sanity test; dropped the "if absent, fall back" clause from §5 Risks.
- **A5**: WP2 — `PlaybackDiagnosticsSection` ships gated behind `internal const val PLAYBACK_TRACE_UI_ENABLED = false` in new `PlaybackTraceUiFlag.kt`; WP9 flips it to `true`. Closes partial-trace classifier window.
- **A6**: §5 Risks + WP8 — acknowledged concurrent `shadow-collec` worktree on `TransportValidationRuntimeCollector.kt`; WP8 executor must rebase onto landed shadow-collec changes.
- **A7**: WP1 + spec §A.3 — pinned `TraceRecord` pool: `ThreadLocal<ArrayDeque<TraceRecord>>` (64-cap per thread) with shared bounded `MpscArrayQueue<TraceRecord>` freelist (capacity 1024); no `ConcurrentLinkedQueue`; documented zero-allocation steady state.
- **C1**: Meta — covers A1–A7 applied above.
- **C2**: Spec §Acceptance Criteria item 3 + plan §4 — "9 event families" → "12 event families" with enum names enumerated.
- **C3**: WP10 — pinned `androidx.benchmark-junit4` harness, 1M iterations + 10k warmup, three variants (baseline-no-emit / toggle-off / toggle-on), gates 20 ns / 5000 ns / 1%.
- **C4**: WP2 + spec §A.1 — documented toggle-OFF mid-session semantics (flush + `playback_session_ended { reason = "toggle_off" }` + rotate + clear) and toggle-ON mid-playback as a no-op until next `createMediaSource()`.
- **C5**: WP7 — PR description must prove `notifyRebuffer()` call sites run on main/player thread (not Media3 loader thread) via grep list.
- **C6**: WP3–WP8 — added "no new conditionals" clause: only the `if (PlaybackTracer.enabled) PlaybackTracer.emit(...)` pattern allowed; reviewer verifies via diff.
- **C7**: WP9 — fallback clause: if no real stutter capture within 2 calendar days, substitute synthetic fixture at `app/src/test/resources/sessions/synthetic-transport-stutter.jsonl` with follow-up ticket to backfill.
- **C8**: WP1 — expanded ProGuard keep rules to `-keep class com.nexio.tv.instrumentation.** { *; }` plus `-keepclassmembers class com.nexio.tv.instrumentation.PlaybackTracer { public static boolean enabled; }`; verified via release mapping file.
- **C9**: §5 Risks — dropped jctools hedge (see A4) and added concurrent worktree entry (see A6).
- **C10**: §6 Verification — added Fire TV manual smoke pass/fail checklist (7 items: file presence, size, family count via `jq`, sessionId uniqueness, monotonic `tNs`, SAF export byte-identity, `tracer_overflow` summary).
