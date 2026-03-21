## Context

The current validator is designed around APK-bundled golden assets. That has two real costs:

- Debug APK size becomes very large because it includes all media and reference files
- Debug installation to Android TV / Fire OS devices becomes much slower

The hosted validator directory at `https://files.thepi.es/validator/` now contains the full source
of truth asset set, including the manifest and all per-sample files.

## Goals

- Remove large validator sample/reference files from debug APK packaging
- Preserve current validator semantics after files are locally available
- Make sample loading deterministic through manifest version and checksum verification
- Keep the feature debug-only

## Non-Goals

- Rework transport or runtime validation semantics
- Change the golden comparison model
- Add production-build validator asset access
- Guarantee offline validation before a sample has been downloaded at least once

## Decision

Use a remote-first manifest and asset cache model.

The validator will:

1. Fetch `transport_validation_manifest.json` from the hosted validator base URL
2. Resolve sample files from that same base URL
3. Download required files into app-specific validator storage on demand
4. Verify file checksums against the manifest before reuse
5. Continue using local files for playback/reference parsing after download

## Architecture

### Remote manifest source

- The manifest loader gains a remote-loading path in addition to parsing JSON
- The validator catalog/debug UI loads available samples from the remote manifest
- The manifest remains the source of truth for:
  - sample metadata
  - expected transport rules
  - checksums

### Local cache

- Files are stored in app-specific validator cache/files storage
- Cache keys are the remote filenames from the manifest
- A file is reusable only when:
  - it exists locally
  - its checksum matches the manifest entry

### Playback and validation

- Playback launcher uses the local cached source file path, not a packaged asset URI
- Reference parsing uses the local cached reference file path, not `AssetManager`
- Transport/runtime collection remains additive and unchanged once playback starts

### Export metadata

Diagnostics export should record:

- manifest version
- remote validator base URL
- whether files came from cache or fresh download
- local cache file names/checksum state

## Tradeoffs

### Remote-first instead of APK-bundled

Pros:

- Much smaller debug APKs
- Faster device installs
- Easier reference-set updates without inflating the app binary

Cons:

- First run for a sample requires network access
- Validator now has cache/download failure modes

### Cache verification instead of blind reuse

Pros:

- Keeps the hosted manifest as the source of truth
- Prevents stale local files from silently invalidating results

Cons:

- Requires checksum work during sample startup

## Implementation Notes

- Keep the feature debug-only
- Remove large validator file packaging from `app/build.gradle.kts`
- Centralize the hosted base URL so UI/export/docs use the same source
- Keep manifest parsing strict
- Runtime validation must remain additive and must not alter transport capture behavior
