# TrueHD Runtime Parity - Strict Audit Phase 3 Fix Plan

**Goal:** Fix the remaining architectural parity gaps and observability issues identified in the second audit to achieve a fully clean, output-driven playback pacing model and accurate metrics observability.

**Architecture:** Native TrueHD AE Engine (`KodiTrueHdAEEngine.cpp`), Java Sink (`KodiTrueHdNativeAudioSink.java`), and Validation Exporter (`TransportValidationDiagnosticsExporter.kt`).

---

### Task 1: Fix P2 Java Query-Time Ownership Mutation
**Goal:** `hasPendingData()` and other query methods must be observational and should not mutate TrueHD startup ownership. Handoff must only trigger as a consequence of actual write progress.
**Location:** `KodiTrueHdNativeAudioSink.java`
**Action:**
- Remove the `maybeExitTrueHdStartupOwnership("hasPendingData")` call inside `hasPendingDataForTrueHd()`.
- Ensure `maybeExitTrueHdStartupOwnership` is only called during the active write path (`handleBuffer`).

### Task 2: Fix P2 Group 3 Observability Incompleteness
**Goal:** Ensure the validation bundle exports complete runtime verdicts and playback stats. Currently, the exporter misses merging the sink continuity truth and potentially fails to capture playback stats.
**Location:** `TransportValidationDiagnosticsExporter.kt`, `TransportValidationSessionStore.kt`, and `TransportValidationRuntimeCollector.kt`.
**Action:**
- **Merge Sink Truth:** Update `TransportValidationDiagnosticsExporter.kt` to call `mergeSinkContinuityTruthIntoRuntimeSnapshot` before exporting `runtime-summary.json` and `playback-stats.json`.
- **Capture Playback Stats:** Investigate and ensure `PlaybackStatsListener` in `TransportValidationRuntimeCollector.kt` is correctly accumulating data and not being cleared before the snapshot is taken.
- **Verdict Serialization:** Ensure the `verdict` and `failureCodes` are correctly serialized into the `runtime-summary.json`.

### Task 3: Fix P3 Partial-Write Context Dropping
**Goal:** Ensure the `AUDIO_WRITE_PARTIAL` events in the runtime exporter include the appended startup/handoff context fields (`startupActive`, `selectedPath`, etc.), exactly as zero-writes do.
**Location:** `KodiTrueHdNativeAudioSink.java`
**Action:**
- In `recordTransportValidationWriteEvent`, for partial writes, ensure the enriched `detail` string (which contains startup handoff fields) is used instead of the raw `nativeDetail`.

---

### Task 4: Plan Document Management
**Goal:** Address user feedback regarding plan storage visibility.
**Action:**
- The plan is currently stored in the session's temporary directory: `/Users/jneerdael/.gemini/tmp/nexio/2e1e3e2c-5bde-4261-b7c2-1d5fd0260eb6/plans/truehd-strict-audit-phase3.md`.
- Upon approval and exit from Plan Mode, I will immediately copy this plan to the workspace at `docs/plans/2026-03-21-truehd-strict-audit-phase3.md` for permanent reference.
