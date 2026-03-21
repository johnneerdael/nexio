# TrueHD Media3 Parity Checklist (Post Phase 1)

This checklist tracks the behavioral parity between the Nexio TrueHD native sink path, the stock Media3 `DefaultAudioSink`, and the baseline Kodi AE.

## Checklist Status

**Legend:**
- 🟢 `stock-like`: Behaves like Media3 or the baseline custom path.
- 🟡 `intentionally custom`: Different on purpose, but not yet proven necessary.
- 🟠 `suspicious divergence`: Likely candidate for the next runtime issue.
- 🔵 `proven necessary divergence`: Codec-family-specific behavior required by transport.

### 1. Java Sink Contract Boundary
| Behavior | Status | Notes |
|---|---|---|
| `handleBuffer(...)` backpressure | 🟢 `stock-like` | Returns backpressure to Media3 when native writes 0. |
| `hasPendingData()` | 🟢 `stock-like` | Correctly derived from native pending queues (startup and steady-state) plus hardware frames. |
| `isEnded()` | 🟢 `stock-like` | Signals ended when streams are drained. |
| `getCurrentPositionUs(...)` | 🟢 `stock-like` | Driven by native hardware position estimator. |

### 2. Transport & Pacing Semantics
| Behavior | Status | Notes |
|---|---|---|
| Startup vs Steady-State Data Structures | 🟢 `stock-like` | Fixed in Group 1. Lifecycles are cleanly separated. |
| Steady-State Heuristic Gates | 🟢 `stock-like` | Fixed in Group 2. Buffer-fit and playback-head logic removed from control flow. |
| Output Pacing (Backoff) | 🟠 `suspicious divergence` | The native sink still uses an artificial 20ms backoff (`kSteadyStateRetryZeroBackoffUs`) instead of allowing Media3's thread loop to hit the `AudioTrack` non-blocking boundary freely. |
| Diagnostic Truthfulness | 🟢 `stock-like` | Fixed in Group 3. Control events and hardware zero-writes are correctly classified. |

### 3. Output Configuration & Continuity
| Behavior | Status | Notes |
|---|---|---|
| Route Reopen Behavior | 🟢 `stock-like` | Route remains stable post-startup (`routeChangeCount=1`). |
| Sink Underrun | 🟠 `suspicious divergence` | Late sink underrun still occurs. Likely caused by the artificial 20ms backoff and potential `AudioTrack` buffer sizing mismatches for massive MAT bursts. |
| Burst Packing (MAT) | 🔵 `proven necessary divergence` | Required for TrueHD. Transport perfectly validates. |

## Action Items (Phase 2)

- [ ] **Action 1:** Remove `kSteadyStateRetryZeroBackoffUs` entirely from `KodiTrueHdAEEngine.cpp`. Allow every steady-state flush attempt to legitimately ping the hardware `AudioTrack`.
- [ ] **Action 2:** Audit the `AudioTrack` buffer sizing requested during TrueHD IEC output configuration. Ensure the buffer provides sufficient margin (e.g., > 100ms) to accommodate massive MAT bursts and ExoPlayer thread jitter without starving.
