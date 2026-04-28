# Add rail-preview-first contract

## Why
Built-in API rails for Trakt, MDBList, TMDB, Kitsu, and Simkl are currently assembled as synthetic Home catalog rows. That lets them bypass the addon catalog first-paint contract where a source payload renders immediately and canonical metadata hydration happens later. Trakt rails are the most visible failure mode because they can carry title, year, and stable IDs but no poster, and the current architecture can treat them as empty IDs waiting for metadata before the row is useful.

## What changes
- Introduce `RailItemPreview` as the source/storage preview model for built-in API rails.
- Adapt `RailItemPreview` into the existing Home `MetaPreview` first-paint UI model with `firstPaintSource = RAIL_PREVIEW`.
- Preserve addon previews in the same `MetaPreview` lifecycle with `firstPaintSource = ADDON_META_PREVIEW`.
- Map each built-in provider response item to `RailItemPreview` before any metadata routing or provider-plan execution.
- Persist rail membership separately from preview payloads and canonical provider metadata.
- Render Home cards from the existing `MetaPreview` renderer immediately after source mapping.
- Hydrate only visible, focused, adjacent, hero, or stale active Home previews through the existing MetadataRouter and ProviderPlanRunner path.
- Mark built-in rail payload fields as `SourceRole.RAIL_PREVIEW`, then let `FieldResolver` replace primary-owned fields when canonical metadata succeeds.
- Add metadata execution audit scenarios proving first paint does not route or execute network calls, and visible hydration routes and replaces fields correctly.

## Out of scope
- Changing the primary authority policy for TV or anime. TMDB TV rails remain preview sources unless a later explicit TMDB-TV primary policy is approved.
- Replacing the existing canonical TMDB/TVDB/Kitsu metadata cache.
- Hydrating every item in large provider pages during initial rail fetch.
