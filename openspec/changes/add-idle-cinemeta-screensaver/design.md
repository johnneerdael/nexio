## Context
The requested screensaver is a new root-level browsing capability, not a Home-only widget. It must activate from inactivity, render above the current UI without tearing down the underlying navigation stack, and source candidates from stock Cinemeta popular catalogs whether or not those rows are visible in the Home configuration.

The repo already prioritizes startup smoothness and deferred work. The implementation must avoid slowing initial render while still ensuring that the screensaver candidate pool is freshly prepared on each cold boot.

## Goals / Non-Goals
- Goals:
  - Show a full-screen idle screensaver after 300 seconds of no remote input.
  - Use fresh top 5 movie + top 5 series candidates from stock Cinemeta `Popular` feeds on every cold boot.
  - Rotate the combined pool in randomized order with panel-safe subtle motion and smooth transitions.
  - Dismiss instantly on input, with `OK/Select` deep-linking into details.
- Non-Goals:
  - Trailer playback inside the screensaver.
  - Personalized backend CMS feeds.
  - Replacing the existing startup splash behavior.

## Decisions
- Decision: Implement the idle trigger at the app root.
  - Rationale: input arrives across multiple screens, so a root controller is the only reliable place to debounce global inactivity and present an overlay above the active route.

- Decision: Fetch screensaver candidates from a dedicated Cinemeta path instead of visible `HomeUiState`.
  - Rationale: the requested stock rows may be hidden or omitted from current Home rendering, so screensaver content must not depend on visible row selection.

- Decision: Refresh the candidate pool on cold boot in background/deferred startup work, then keep the resulting slides in memory for that app session.
  - Rationale: this satisfies "fresh every cold boot" without forcing a blocking fetch when the idle timer expires later.

- Decision: Use the existing metadata models (`MetaPreview` plus route data) as the base and derive a screensaver-specific slide model containing display artwork, logo/title fallback, metadata chips, and navigation target info.
  - Rationale: avoids parallel metadata parsing and keeps deep-linking aligned with current detail navigation.

- Decision: Prefetch the next slide artwork and animate only alpha/transform values.
  - Rationale: prevents transition flashes and keeps rendering TV-friendly on low-power devices.

## Risks / Trade-offs
- Hidden or missing Cinemeta popular catalogs may not be available in some edge configurations.
  - Mitigation: fall back to whichever of the two stock rows is available; if neither returns usable items, do not show the screensaver.

- Fresh-on-cold-boot network refresh may overlap with startup performance work.
  - Mitigation: schedule it in deferred startup work and keep it isolated from Home's first-render critical path.

- A root idle controller can accidentally interfere with playback or modal flows if broadly armed.
  - Mitigation: arm screensaver only on browsing/navigation surfaces and suppress it for playback and other incompatible routes.

## Migration Plan
1. Introduce the idle controller and screensaver repository/state model.
2. Fetch and store fresh Cinemeta candidate slides on cold boot.
3. Add the root overlay and idle/dismiss interactions.
4. Validate that recommendation/deep-link launches and playback flows remain unaffected.

## Open Questions
- None at proposal time; the content source, trigger behavior, and interaction model are specific enough to implement after approval.
