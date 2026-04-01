# Change: Move Passthrough Validator Assets to Remote Hosted Source

## Why

The current debug validator bundles all golden samples and reference files into the debug APK.
That makes the APK extremely large and slows down debug installs and iteration on devices.

The full validator asset set is now hosted at `https://files.thepi.es/validator/`, including
`transport_validation_manifest.json`, playback samples, extracted elementary streams, and golden
reference files. The validator should use that hosted source instead of packaging the files into the
APK.

## What Changes

- Switch the debug passthrough validator from bundled APK assets to a hosted remote asset source
- Download the manifest and required sample/reference files on demand into app-specific local
  storage
- Reuse cached local files when checksums match the manifest
- Keep transport and runtime validation behavior unchanged after the file is local
- Update debug UI, ADB behavior, and diagnostics/export metadata to surface remote asset/cache
  state

## Impact

- Affected specs: `passthrough-transport-validation`
- Affected code:
  - `app/build.gradle.kts`
  - `app/src/main/java/com/nexio/tv/debug/passthrough/*`
  - validator docs and debug settings UI
