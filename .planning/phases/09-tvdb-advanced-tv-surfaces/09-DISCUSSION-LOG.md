# Phase 9: TVDB Advanced TV Surfaces - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-14
**Phase:** 9 - TVDB Advanced TV Surfaces
**Areas discussed:** Season ordering and Trakt matching, TVDB trailer replacement, Advanced metadata mapping, Provider UX and diagnostics

---

## Season Ordering and Trakt Matching

| Option | Description | Selected |
|--------|-------------|----------|
| Preserve default season type with stable canonical numbers | Preserve TVDB default season type metadata and use its episode list as the TVDB display/enrichment source, while keeping canonical season/episode numbers stable for Trakt matching. | ✓ |
| Display-only annotations | Treat TVDB ordering as display-only annotations; never change episode lists from current canonical order. | |
| Full TVDB display/order switch | Fully switch TV episode display/order to TVDB default season type wherever TVDB is active. | |

**User's choice:** Preserve TVDB default season-type metadata while keeping canonical season/episode numbers stable for Trakt matching.
**Notes:** Existing code derives season tabs and progress from `Video.season` / `Video.episode`, so downstream planning should preserve those progress keys.

| Option | Description | Selected |
|--------|-------------|----------|
| Trakt progress wins | Trakt progress matching wins; TVDB ordering enriches metadata but must not make watched/progress state miss. | ✓ |
| TVDB order wins | TVDB default season type wins; adapt Trakt matching around it where possible. | |
| Detached progress | Show TVDB order but keep progress detached if mapping is ambiguous. | |

**User's choice:** Trakt progress matching wins.
**Notes:** TVDB ordering must not destabilize watched-state, ratings, or mutation matching.

| Option | Description | Selected |
|--------|-------------|----------|
| Preserve but only apply clean mappings | Preserve metadata needed to understand specials and non-standard season types, but only display/use seasons that map cleanly to existing season tabs and progress behavior. | ✓ |
| Include immediately | Include specials and alternate-season entries in detail tabs immediately. | |
| Store only | Keep all non-standard season type data hidden and only store it for future work. | |

**User's choice:** Preserve non-standard season-type metadata, but only use clean mappings in this phase.
**Notes:** Full user-facing alternate ordering remains deferred.

| Option | Description | Selected |
|--------|-------------|----------|
| Full season mapping diagnostics | Log when TVDB season type data is present, when Trakt canonical numbering is used, and when alternate ordering is preserved but not applied. | ✓ |
| Conflicts only | Only log conflicts or fallback cases. | |
| Failures only | No new diagnostics unless something fails. | |

**User's choice:** Require full season mapping diagnostics.
**Notes:** These diagnostics help prove TVDB season data is preserved without hiding progress-matching choices.

---

## TVDB Trailer Replacement

| Option | Description | Selected |
|--------|-------------|----------|
| Title first, season when clean | Title-level TV trailers first; season-level TVDB trailers/recaps only if TVDB data cleanly supports the existing season media actions. | ✓ |
| Title and season immediately | Title-level and season-level trailer/recap paths immediately. | |
| Detail title only | Only detail-page title trailer button; leave Home trailer previews and season actions on existing logic. | |

**User's choice:** TVDB title-level TV trailers first, season-level only when they fit existing actions.
**Notes:** Preserve current season trailer/recap behavior rather than creating new actions.

| Option | Description | Selected |
|--------|-------------|----------|
| TVDB, Streailer, fallback IDs, explicit TMDB | TVDB usable trailer, then Streailer/internal sources, then existing fallback YouTube IDs, then explicit TMDB fallback only if TVDB has no usable trailer data. | ✓ |
| TVDB, TMDB, Streailer | TVDB usable trailer, then TMDB, then Streailer/internal sources. | |
| TVDB only | TVDB only; no TMDB fallback for TV trailers. | |

**User's choice:** TVDB first, Streailer/internal second, fallback IDs third, explicit TMDB fallback last.
**Notes:** This preserves existing internal trailer value while keeping TMDB fallback explicit and diagnosable.

| Option | Description | Selected |
|--------|-------------|----------|
| Current playback compatible | A playable/external video URL that can feed the existing trailer playback model or a YouTube/Vimeo-style URL that can be resolved through the current trailer pipeline. | ✓ |
| Direct video only | Only direct playable video URLs. | |
| Metadata only | Any TVDB trailer metadata, even if playback resolution needs later work. | |

**User's choice:** TVDB trailer data must be compatible with the existing playback or external-resolution model.
**Notes:** Avoid counting metadata-only trailer records as usable.

| Option | Description | Selected |
|--------|-------------|----------|
| Preserve current season actions | Preserve existing season trailer/recap UI behavior; route TV season video lookup through TVDB when safe, but do not invent new season actions. | ✓ |
| Add season-order-aware behavior | Add explicit TVDB season-order-aware trailer behavior. | |
| Leave season behavior unchanged | Leave season trailer/recap behavior entirely unchanged and only replace title trailers. | |

**User's choice:** Preserve existing season trailer/recap UI behavior and route lookup through TVDB when safe.
**Notes:** Keep implementation inside current `TrailerService` behavior shape where possible.

---

## Advanced Metadata Mapping

| Option | Description | Selected |
|--------|-------------|----------|
| Existing cast surfaces | Map TVDB characters into existing cast surfaces, preserving character names/photos when available; do not add a new cast UI. | ✓ |
| Richer future model | Add richer TVDB-specific cast fields to the domain model for future UI. | |
| Missing-only fallback | Only use TVDB cast when TMDB cast is missing. | |

**User's choice:** Map TVDB characters into existing cast surfaces.
**Notes:** No new cast UI in Phase 9.

| Option | Description | Selected |
|--------|-------------|----------|
| Existing company/network surfaces | Map TVDB companies/networks into existing `MetaCompany` surfaces, preserving whether each entry is a network vs production company where possible. | ✓ |
| Production only | Collapse all TVDB companies into production companies only. | |
| Richer stored types | Store richer TVDB company types but only display original/latest network. | |

**User's choice:** Map TVDB companies/networks into existing `MetaCompany` surfaces.
**Notes:** Preserve company kind where possible.

| Option | Description | Selected |
|--------|-------------|----------|
| TVDB replaces TV genres/ratings | TVDB replaces TMDB TV genres and content ratings when active; use existing display fields and existing country/language preference behavior where possible. | ✓ |
| Prefer TMDB | Prefer TMDB genres/ratings unless TVDB has a better match. | |
| Merge providers | Merge TVDB and TMDB genres/ratings. | |

**User's choice:** TVDB replaces TMDB TV genres and content ratings when active.
**Notes:** Do not merge TMDB and TVDB TV taxonomy during normal TV success paths.

| Option | Description | Selected |
|--------|-------------|----------|
| No new sections | No new sections; populate existing detail, Home, stream, and screensaver metadata surfaces. | ✓ |
| TVDB Info section | Add one new TVDB Info section on details. | |
| Limited new sections | Add new sections only for content ratings and networks. | |

**User's choice:** No new user-visible metadata sections.
**Notes:** Phase 9 is mapping/provider replacement, not UI expansion.

---

## Provider UX and Diagnostics

| Option | Description | Selected |
|--------|-------------|----------|
| Existing toggles only | No new TVDB-specific toggles; existing metadata toggles still govern categories, and TVDB/TMDB provider routing decides the source. | ✓ |
| Single advanced toggle | Add a single Use TVDB advanced metadata toggle. | |
| Granular TVDB toggles | Add granular TVDB toggles for trailers, cast, networks, genres, and ratings. | |

**User's choice:** No new TVDB-specific toggles.
**Notes:** Existing metadata category toggles remain the user controls.

| Option | Description | Selected |
|--------|-------------|----------|
| Automatic and quiet | Automatic and quiet; no new UI unless a diagnostic/fallback state needs explanation. | ✓ |
| Settings copy | Add settings copy explaining exact-air-time behavior. | |
| Continue Watching labels | Add visible labels on Continue Watching items when TVDB timing is used. | |

**User's choice:** Exact-air-time Continue Watching should be automatic and quiet.
**Notes:** UX-02 is satisfied by behavior, not extra labels or toggles.

| Option | Description | Selected |
|--------|-------------|----------|
| Full advanced-surface diagnostics | Logs/diagnostic state for TVDB surface success, missing TVDB data, explicit TMDB fallback, and TMDB skipped because TVDB supplied this TV surface. | ✓ |
| Fallback/failure only | Only log fallback/failure. | |
| Trailers and season mapping only | Only add diagnostics for trailers and season mapping. | |

**User's choice:** Require full advanced-surface diagnostics.
**Notes:** Diagnostics must make successful TVDB replacement and skipped TMDB paths observable, not only failures.

| Option | Description | Selected |
|--------|-------------|----------|
| Graceful omission/fallback | Graceful omission or existing fallback behavior; no browse-time warning unless the surface becomes visibly inconsistent or empty. | ✓ |
| Settings warning | Show a settings warning whenever advanced TVDB data is incomplete. | |
| Inline detail warnings | Show inline warnings on detail pages for missing advanced TVDB fields. | |

**User's choice:** Missing TVDB data should degrade through graceful omission or existing fallback behavior.
**Notes:** Avoid noisy browse-time warnings.

---

## the agent's Discretion

- Exact class names, mapper structure, diagnostic log tags, cache key names, and test file placement.
- Exact TVDB company type and content-rating preference heuristics, as long as existing UI contracts are preserved.

## Deferred Ideas

- Full user-facing alternate season-order picker remains deferred to v2 requirements.
- New TVDB-specific metadata UI sections are deferred.
- Broad TVDB cache invalidation and reference-data heavy caching remain Phase 10.
