## Context
The current idle screensaver always builds its rotation from stock Cinemeta popular movie and series catalogs. The app already maintains a disk-backed Trakt discovery snapshot containing trending movie and show previews, and those previews are aligned with home catalog enablement and startup refresh behavior.

## Goals / Non-Goals
- Goals:
  - Use Trakt trending content for the screensaver when Trakt is configured and the user has enabled both trending rails on home.
  - Increase the rotation pool from 10 items to 20 items in the Trakt-enabled case.
  - Reuse the persisted Trakt snapshot and existing startup refresh behavior rather than fetching the same discovery data twice.
- Non-Goals:
  - Add a separate screensaver settings toggle.
  - Support partial Trakt activation when only one trending rail is enabled.
  - Replace the existing Cinemeta fallback path.

## Decisions
- Decision: Gate Trakt screensaver sourcing on both Trakt authentication and both home trending rails being enabled.
  - Alternatives considered: switch on any Trakt auth alone, or when either single rail is enabled. Rejected because the user explicitly wants the home rail configuration to control the behavior and expects both rails to be populated.
- Decision: Read top 10 movies and top 10 shows from the persisted Trakt discovery snapshot.
  - Alternatives considered: directly fetch Trakt again from the screensaver repository. Rejected because it duplicates discovery work and weakens cache alignment.
- Decision: Depend on the normal startup snapshot refresh to populate the Trakt source before the screensaver is ever shown.
  - Alternatives considered: trigger a dedicated screensaver refresh if snapshot content is missing. Rejected because the screensaver timeout is longer than the startup refresh window and the user prefers not to fetch twice.
- Decision: Fall back entirely to Cinemeta whenever the Trakt source is not eligible.
  - Alternatives considered: blend Trakt and Cinemeta sources. Rejected to keep source behavior deterministic and minimal.

## Risks / Trade-offs
- Trakt-backed screensaver content now depends on the Trakt snapshot being refreshed successfully during startup.
  - Mitigation: preserve the existing Cinemeta fallback path whenever eligibility or snapshot readiness is not satisfied.
- Reusing snapshot data means screensaver freshness follows the same cadence as Trakt discovery freshness.
  - Mitigation: that is desirable here because it keeps home and screensaver aligned.

## Migration Plan
1. Add source-eligibility checks for Trakt authentication plus home trending rail enablement.
2. Read top 10 movie/show previews from the existing Trakt snapshot when eligible.
3. Fall back to the current Cinemeta path when ineligible.
4. Add targeted tests for selection and limits.

## Open Questions
- None currently.
