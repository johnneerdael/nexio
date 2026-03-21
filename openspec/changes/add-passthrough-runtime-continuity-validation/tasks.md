## 1. Models and Verdicts
- [x] 1.1 Add runtime continuity, route stability, playback-head, and operator-observation data
  models aligned with the existing passthrough validation session/export architecture
- [x] 1.2 Define one shared runtime threshold/config object for startup, steady-state scoring,
  late-noise exclusion, continuity, and route-stability windows, and export it in every runtime
  bundle
- [x] 1.3 Extend runtime verdict calculation with sub-verdicts, explicit scoring-window rules, and
  new runtime failure codes without changing transport verdict semantics
- [x] 1.4 Add unit tests covering roll-up behavior, observation-window handling, and operator-
  observation downgrades

## 2. Sink and Route Instrumentation
- [x] 2.1 Add observational sink continuity counters at the custom sink/native output boundary for
  write progress, zero writes, partial writes, restarts, underruns, playback-head advance, and
  stuck remainders
- [x] 2.2 Add route stability sampling and post-start route-change tracking through existing route
  snapshot plumbing
- [x] 2.3 Ensure the new sink and route signals are merged into validation sessions without mutating
  transport capture behavior

## 3. Debug Control and Export
- [x] 3.1 Add ADB/operator control commands for structured runtime observations such as AVR lock and
  audio quality
- [x] 3.2 Export `sink-health.json`, `route-health.json`, `playback-head-health.json`, and
  `operator-observation.json` alongside the existing runtime artifacts
- [x] 3.3 Update validation docs and `adb-passthrough-validator/SKILL.md` so runtime analysis uses
  the new continuity/route/operator layers

## 4. Verification
- [x] 4.1 Run focused unit tests for runtime verdict and export behavior
- [x] 4.2 Run at least one device-side passthrough validation flow that exports the new artifacts
- [x] 4.3 Validate the OpenSpec change with `openspec validate add-passthrough-runtime-continuity-validation --strict`
