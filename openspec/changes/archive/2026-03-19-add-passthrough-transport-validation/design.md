## Context

Nexio needs a deterministic way to validate passthrough transport for bundled golden samples across
TrueHD, DTS-family formats, and Dolby family formats without changing the normal playback path.

## Goals / Non-Goals

- Goals:
  - Provide a debug-only validation workflow from UI and ADB
  - Launch bundled validation assets directly from the tool
  - Compare bursts automatically against golden references
  - Export diagnostics with structured failure codes
- Non-Goals:
  - No production availability
  - No arbitrary live-stream validation
  - No shared playback-path rewrites
  - No claim that validation proves AVR decode success; it only proves transport integrity through
    app boundaries

## Decisions

- Decision: Use repository-root bundled assets plus manifest metadata as the source of truth
  - Alternatives considered: Android assets only, remote fixtures
- Decision: Copy the selected repository-root golden assets into debug-capable Android asset sets at
  build time
  - Alternatives considered: runtime filesystem access, remote asset loading
- Decision: Keep orchestration in the app layer and transport capture at sink/JNI boundaries
  - Alternatives considered: native-only controller
- Decision: Support both in-app debug UI and ADB from the first implementation
  - Alternatives considered: ADB-only first
- Decision: Compare aligned burst `N` against burst `N`, never arbitrary stream prefixes
  - Alternatives considered: prefix-only byte comparison
- Decision: Make codec-specific validation rules part of the first implementation
  - Alternatives considered: generic comparator with a single pass/fail path
- Decision: Include route/config snapshot data in every export bundle
  - Alternatives considered: export logs and raw dumps only

## Risks / Trade-offs

- Adding capture hooks around transport boundaries increases debug-only complexity
  - Mitigation: keep hooks observational and gated by validation mode
- Golden sample packaging and manifest management add maintenance cost
  - Mitigation: keep the library small and codec-focused
- Burst alignment mistakes could create false failures
  - Mitigation: require explicit burst-index alignment and normalized `Pa/Pb/Pc/Pd` interpretation

## Migration Plan

- Add new debug-only plumbing alongside the existing debug settings surface
- Keep validation mode disabled by default
- Verify no normal playback-path regression when disabled

## Open Questions

- Exact export bundle location/retention policy on-device
