# Change: Add DTS-HD payload classifier to passthrough transport validation

## Why

The current passthrough transport validator can prove that DTS-HD and DTS:X samples remain on the
type-IV transport path and that the DTS-HD wrapper is present, but it still relies primarily on the
golden reference burst comparison to distinguish wrapped HD payloads from wrapped likely-core-only
payload fallback.

FFmpeg's DTS-HD SPDIF muxer can preserve the DTS-HD type-IV transport wrapper while temporarily
reducing the wrapped payload to the core size under repetition-period overflow. That means a
validator that only checks `Pc`, wrapper presence, and full-burst equality misses an important
codec-specific diagnostic layer.

## What Changes

- Extend the passthrough transport validator with DTS-HD/DTS:X payload-level burst classification
- Add explicit machine-readable classifier results for:
  - wrapped HD payload
  - wrapped likely-core-only payload
  - wrapped payload that cannot be classified confidently
- Include the classifier result in comparison diagnostics and export bundles
- Keep burst-by-burst golden comparison as the primary source of truth; the payload classifier is a
  supplemental DTS-family diagnostic layer, not a replacement

## Impact

- Affected specs: `passthrough-transport-validation`
- Affected code: DTS-family burst parsing/comparison, validation export records, DTS-focused tests
