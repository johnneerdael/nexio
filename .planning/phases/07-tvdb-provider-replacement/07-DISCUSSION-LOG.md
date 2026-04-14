# Phase 7: TVDB Provider Replacement - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-14
**Phase:** 7-TVDB Provider Replacement
**Areas discussed:** Provider routing, Surface coverage, Artwork and poster precedence, Settings copy, Caching and diagnostics, Verification

## Invocation Note

The `gsd-discuss-phase` workflow was invoked without `--auto`, but Codex's blocking question tool is unavailable in the active collaboration mode. Per the skill adapter fallback, the discussion proceeded with conservative default selections grounded in the roadmap, requirements, prior context, and codebase scout. These choices are recorded as workflow fallback decisions, not explicit user selections.

## Provider Routing

| Option | Description | Selected |
|--------|-------------|----------|
| TVDB-first for TV only | TVDB replaces normal TV metadata success paths; TMDB remains movies and explicit TV fallback. | yes |
| Parallel TVDB and TMDB enrichment | Fetch both providers and merge fields opportunistically. | |
| Full metadata-provider rewrite | Redesign provider architecture before replacing TV paths. | |

**User's choice:** Workflow fallback selected TVDB-first for TV only.
**Notes:** This aligns with roadmap success criteria requiring normal TV paths to skip TMDB TV metadata fetches when TVDB is enabled.

## Surface Coverage

| Option | Description | Selected |
|--------|-------------|----------|
| Replace scoped Phase 7 TV surfaces | Detail/Home/Continue Watching/episode/artwork roles move to TVDB; classify remaining TMDB TV call sites. | yes |
| Replace every TMDB TV call in one phase | Include trailers, reviews, related content, credits/cast, companies, networks, reference taxonomy, ratings, and season ordering now. | |
| Minimal detail-only replacement | Replace TV detail only and leave Home/Continue Watching for later. | |

**User's choice:** Workflow fallback selected scoped Phase 7 TV surfaces.
**Notes:** Phase 8 and Phase 9 already own exact air-time behavior and advanced TVDB surfaces, so this phase should not absorb that work.

## Artwork And Poster Precedence

| Option | Description | Selected |
|--------|-------------|----------|
| Poster-ratings wins for posters only | RPDB/TopPosters override poster URLs; TVDB can still supply backdrops/logos/episode images. | yes |
| TVDB replaces all TV artwork | TVDB overrides poster-ratings and TMDB for every TV image role. | |
| Keep TMDB artwork fallback by default | TVDB only supplies missing artwork and TMDB remains primary. | |

**User's choice:** Workflow fallback selected poster-ratings wins for posters only.
**Notes:** This carries forward the explicit project decision that poster-ratings providers supersede both TVDB and TMDB poster metadata.

## Settings Copy

| Option | Description | Selected |
|--------|-------------|----------|
| Explain provider precedence in existing integration settings | Make TVDB/TMDB/poster-ratings roles clear without adding a second granular TVDB toggle matrix. | yes |
| Add full TVDB field toggles | Duplicate TMDB's granular toggle matrix for TVDB. | |
| Leave settings mostly unchanged | Avoid settings work except adding API key validation. | |

**User's choice:** Workflow fallback selected provider precedence copy in existing integration settings.
**Notes:** Phase 7 requires settings-facing provider rules; duplicating the toggle matrix would add complexity without changing provider precedence.

## Caching And Diagnostics

| Option | Description | Selected |
|--------|-------------|----------|
| Separate TVDB cache namespace with explicit fallback diagnostics | Keep TVDB data separate from TMDB cache keys and log/diagnose provider choice. | yes |
| Reuse TMDB cache keys for TVDB-shaped data | Minimize cache code by storing TVDB mappings under existing TMDB keys. | |
| Memory-only TVDB data for this phase | Avoid disk cache changes and rely on Phase 10 for cache work. | |

**User's choice:** Workflow fallback selected separate TVDB cache namespace with explicit diagnostics.
**Notes:** This avoids corrupting TMDB cache semantics and supports provider-choice diagnostics.

## Verification

| Option | Description | Selected |
|--------|-------------|----------|
| Mocked no-TMDB-call tests for TVDB success path | Prove at least one TVDB-enabled path skips TMDB TV metadata calls. | yes |
| Manual instrumentation only | Use logs/dev inspection without automated call-count assertions. | |
| End-to-end UI test first | Prefer a full UI flow before unit-level provider assertions. | |

**User's choice:** Workflow fallback selected mocked no-TMDB-call tests.
**Notes:** Existing tests already use mocked TMDB services and call-count assertions, so this matches local patterns.

## the agent's Discretion

- Exact TVDB provider abstraction shape and class names.
- Exact TVDB image/language selection heuristics.
- Exact cache TTL/schema values.
- Exact settings copy, as long as precedence is clear.

## Deferred Ideas

- Phase 8: exact Continue Watching availability instants and re-evaluation scheduling.
- Phase 9: season ordering, trailers, and advanced/remnant TVDB metadata surfaces.
- Phase 10: update-aware cache invalidation, reference-data caching, diagnostics hardening, and user-facing docs.
