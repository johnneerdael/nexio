# Change: Add passthrough transport validation mode

## Why

Passthrough bring-up currently depends on manual log interpretation and AVR behavior, which makes
codec transport regressions slow to isolate and hard to compare against golden references.

## What Changes

- Add a debug-only passthrough transport validation mode
- Add in-app debug controls and ADB controls for launching bundled validation samples
- Add automatic burst-by-burst comparison against bundled golden references
- Add structured diagnostics export and per-codec collection docs

## Impact

- Affected specs: passthrough-transport-validation
- Affected code: debug settings UI, validation controller/playback launcher, custom sink/JNI hooks,
  diagnostics export/docs
