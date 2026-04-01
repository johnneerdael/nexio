# Change: Add Passthrough Runtime Continuity Validation

## Why
The current passthrough validator now separates transport integrity from runtime playback quality,
but the runtime layer still focuses mostly on Media3 control-plane health. Recent TrueHD validation
shows that a run can avoid `BUFFERING`, keep transport byte-perfect, and still produce weak AVR
lock, choppy audio, output restart churn, and sink-side no-progress behavior that the validator does
not score correctly.

## What Changes
- Extend `passthrough-transport-validation` with a second runtime-quality layer for sink continuity
  and route stability
- Add continuous sink/output health collection at the custom sink/native output boundary
- Add route-stability monitoring after playback reaches a stable passthrough start
- Add playback-head health sampling so the validator can score continuous forward audio progress
- Add operator-observation commands and export so human truth can downgrade runtime verdicts
- Split runtime scoring into sub-verdicts that roll up into the existing `runtimeVerdict`
- Export new structured diagnostics for sink continuity, route stability, playback-head health, and
  operator observations

## Impact
- Affected specs: `passthrough-transport-validation`
- Affected code: `app/src/main/java/com/nexio/tv/debug/passthrough/*`, player/runtime collector
  wiring, ADB validator control surface, native sink/output diagnostics, validation docs and skill
