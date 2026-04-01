## 1. Runtime Validation Model
- [x] 1.1 Add runtime verdict and failure-code models to the passthrough validation domain
- [x] 1.2 Add session-scoped runtime summary and event record models for player and analytics data

## 2. Runtime Signal Collection
- [x] 2.1 Attach `Player.Listener` runtime collection to validation playback sessions
- [x] 2.2 Attach `AnalyticsListener` and `PlaybackStatsListener` runtime collection to validation playback sessions
- [x] 2.3 Record startup timing, buffering/rebuffering, dropped frames, audio underruns, errors, and position/stability signals for a validation run

## 3. Verdicts and Export
- [x] 3.1 Compute runtime verdicts independently from transport verdicts
- [x] 3.2 Export `runtime-summary.json`, `playback-stats.json`, `player-events.json`, and `analytics-events.json`
- [x] 3.3 Extend summary export with both `transportVerdict` and `runtimeVerdict`

## 4. Debug Controls and Docs
- [x] 4.1 Extend debug UI and ADB controls with runtime validation settings such as observation window and startup timeout
- [x] 4.2 Update passthrough validation docs to describe transport versus runtime verdicts and runtime ADB collection

## 5. Validation
- [x] 5.1 Add targeted unit tests for runtime verdict calculation and exported runtime summaries
- [x] 5.2 Validate the OpenSpec change with `openspec validate add-passthrough-runtime-validation --strict`
