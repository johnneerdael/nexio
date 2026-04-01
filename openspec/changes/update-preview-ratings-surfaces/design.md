## Context
This change touches shared preview metadata consumed by both the idle screensaver and the modern home hero. The detail screen already resolves MDBList ratings through `MDBListRepository`, but preview-level models do not currently carry Rotten Tomatoes, so each surface would otherwise need its own ad hoc enrichment path.

## Goals / Non-Goals
- Goals:
  - Expose Rotten Tomatoes at the shared preview metadata layer.
  - Keep ratings sourcing aligned with the existing detail-screen MDBList flow.
  - Reduce static screensaver text to lower burn-in risk.
  - Keep modern home's existing year and description presentation intact.
- Non-Goals:
  - Generalize all MDBList providers into preview models.
  - Redesign modern home hero layout beyond adding Tomatoes beside IMDb.
  - Change detail-screen ratings behavior.

## Decisions
- Decision: Add a dedicated Tomatoes field to `MetaPreview`, `HomeDisplayMetadata`, and screensaver slide models rather than embedding a full `MDBListRatings` object.
  - Alternatives considered: Thread the full MDBList ratings object through previews. Rejected because the current requirement only needs Tomatoes and would over-expand a hot path model.
- Decision: Reuse `MDBListRepository` for preview enrichment so provider settings, API-key gating, TMDB-to-IMDb resolution, and TTL caching remain consistent with the detail screen.
  - Alternatives considered: Add a screensaver-only fetch path. Rejected because it duplicates detail-screen behavior and would not help modern home.
- Decision: Keep Rotten Tomatoes enrichment best-effort and non-blocking from a UX perspective.
  - Alternatives considered: Require ratings before preview presentation. Rejected because startup/home smoothness is a project constraint.
- Decision: Scope the screensaver metadata row to genres, IMDb, and Tomatoes only, and animate the CTA opacity per visible slide.
  - Alternatives considered: Keep year/runtime and only animate CTA. Rejected because the request explicitly removes description, year, and runtime from screensaver.

## Risks / Trade-offs
- Preview enrichment adds work to metadata preparation paths.
  - Mitigation: Reuse existing MDBList caching and keep the added field narrowly scoped.
- Missing MDBList configuration or unavailable ratings will produce mixed presentation across items.
  - Mitigation: Treat Tomatoes as optional and preserve existing IMDb/genre rendering when absent.
- UI model changes affect multiple constructors/copy paths.
  - Mitigation: Add targeted tests around model propagation and visible rating strings.

## Migration Plan
1. Add Tomatoes to shared preview/home-display models.
2. Update preview enrichment to resolve Tomatoes through `MDBListRepository`.
3. Thread Tomatoes into screensaver and modern home hero view models/UI models.
4. Add tests and verify the affected unit suites.

## Open Questions
- None currently.
