# Change: Add Passthrough Runtime Validation

## Why
The current passthrough validator proves byte transport integrity through the app boundaries, but it
does not prove that playback actually starts promptly, remains stable, or produces acceptable runtime
quality. Recent DTS-HD testing showed a transport pass can coexist with no audible output, route
repatching, and choppy video.

## What Changes
- Extend `passthrough-transport-validation` with a runtime playback-quality validation layer
- Keep transport and runtime as separate verdicts in the same validation session
- Collect runtime metrics from Media3 listener/analytics surfaces during bundled validation runs
- Export runtime summaries, event timelines, and machine-readable runtime failure codes
- Extend debug UI and ADB control surfaces with runtime validation settings

## Impact
- Affected specs: `passthrough-transport-validation`
- Affected code: `app/src/main/java/com/nexio/tv/debug/passthrough/*`, player initialization and
  listener wiring, validation docs

