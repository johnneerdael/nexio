---
date: 2026-04-15
topic: android-tv-entity-card-provider-link
---

# Android TV Entity Card Provider Link

## Problem Frame

Android TV/Google TV search can open a Google media detail page for a movie or show. On that page, official providers such as Disney+ may appear as watch options, but Nexio currently may not appear even when the title is available through Nexio. Users should be able to search from Android TV, land on a Google media detail page, and see Nexio as an app option when Nexio has a high-confidence local match for the title.

The existing native-search implementation exposes live Cinemeta suggestions and opens Nexio detail pages, but entity-card provider matching has a stricter requirement: Android TV reconciles app-provided suggestions with Google entities using title, production year, and duration. Nexio should improve that matching by using its local metadata and Home/catalog caches first, because those cached items represent content the app already knows about and can open instantly.

## Requirements

**Entity Card Goal**
- R1. Nexio should appear as an app option on Android TV/Google TV media detail pages when the searched Google entity matches a high-confidence Nexio result.
- R2. The feature should target Android TV entity-card matching first. Broader Google Media Actions ingestion may be investigated later, but it is not the default first implementation.
- R3. Selecting Nexio from a matched entity card should open the Nexio detail page for the title, not stream selection, deterministic autoplay, or the player.

**Instant Local Corpus**
- R4. Native search suggestions should search Nexio's local metadata/home/catalog cache before live Cinemeta.
- R5. The local corpus should include items from modern Home/catalog state that Nexio already persists locally, including visible rows, full cached rows, and hero items when available.
- R6. Local cache results must be returned synchronously/near-instantly and must not wait on network.
- R7. The local corpus should preserve the item ID, content type, title, source addon base URL, release info/year, runtime/duration, poster/backdrop, and description where available.
- R8. Duplicate local results should collapse by stable item identity, preferring the richest metadata and keeping source routing needed to open the same detail page Nexio would open internally.

**Live Fallback**
- R9. Live Cinemeta search should remain as a fallback when the local corpus has no useful match.
- R10. Live fallback should not reduce responsiveness; it should keep the existing short timeout and empty-result fallback behavior.
- R11. Live fallback results should not override a stronger local cached match for the same title/entity.

**Match Quality**
- R12. Suggestions intended for entity-card matching must include `SUGGEST_COLUMN_TEXT_1`, `SUGGEST_COLUMN_PRODUCTION_YEAR`, and `SUGGEST_COLUMN_DURATION` whenever Nexio can derive them confidently.
- R13. Movie duration should use title runtime when available. TV show duration should use an appropriate title-level or representative episode runtime only when it is reliable enough to help matching; otherwise omit duration rather than fabricate it.
- R14. Result metadata should prefer local enriched metadata over raw catalog metadata when both exist.
- R15. Matching should favor exact or normalized title matches before fuzzy/contains matches, so unrelated catalog entries do not pollute Android TV search suggestions.

**Runtime Completeness**
- R16. Nexio should improve runtime/duration completeness for locally searchable Home/catalog items when doing so can be bounded to already-visible or high-priority cached titles.
- R17. Runtime hydration should prioritize movies and title-level TV show metadata that are already present in Home/catalog snapshots, because these are the titles most likely to be surfaced from Android TV search.
- R18. Runtime hydration must reuse existing metadata/cache/enrichment paths where possible and must not introduce an unbounded background crawl of catalogs or addons.
- R19. Runtime hydration failures must not block Home, Android TV search, or live Cinemeta fallback. Missing runtime should reduce match confidence, not crash or hide otherwise useful results.
- R20. Hydrated runtime should be persisted or reused through existing cache structures so repeated Android TV searches do not repeatedly trigger the same enrichment work.

**Boundaries and Reliability**
- R21. The feature must not expose private/non-visible addon catalogs beyond what Nexio has already cached for Home/catalog surfaces.
- R22. The feature must not trigger stream/source resolution from Android TV search.
- R23. The feature must not require Trakt, SIMKL, TVDB, TMDB, debrid accounts, or other user-specific integrations to function, although cached/enriched data from those integrations may improve results when already present.
- R24. Search provider failures must return empty results rather than crashing Android TV search or Nexio.
- R25. The provider must not log raw user search queries at production log levels.

## Success Criteria

- A title present in Nexio's modern Home/catalog cache can be returned from Android TV search without a network request.
- Cached movie results include title, production year, and duration when those fields exist locally.
- Cached show results include title and year, and include duration only when Nexio has reliable runtime metadata.
- Home/catalog titles missing runtime can become stronger Android TV match candidates after bounded metadata hydration, without requiring users to manually open every detail page first.
- When Android TV opens a Google media entity detail page for a locally cached title, Nexio has the required metadata in its provider response to be eligible for the app option/link.
- If no local result exists, live Cinemeta fallback behavior continues to work as in the first native-search implementation.
- Selecting Nexio still opens Nexio detail, not stream selection or playback.

## Scope Boundaries

- Do not build full Google Media Actions/Engage feed ingestion in the first follow-up.
- Do not promise that Google/Android TV will always show Nexio; the app can provide better matching data, but final placement is controlled by the platform.
- Do not start playback from entity cards in this phase.
- Do not infer TV episodes from title-level entity cards.
- Do not scan every installed addon live or expose private addon search results.
- Do not create a long-lived independent search index if existing Home/catalog/metadata caches are sufficient.
- Do not hydrate runtime by crawling every catalog item in the background. Runtime enrichment must stay bounded to locally cached, visible, or otherwise high-priority Home/catalog candidates.

## Key Decisions

- Use local cache first: the Home/catalog snapshot already represents content Nexio has recently loaded and can route to, and it can return instantly with richer metadata than a live Cinemeta search response.
- Add explicit match scoring: local-cache-first only helps if strong local records outrank weak or noisy matches. Ranking should be explainable and based on exact/normalized title, year, duration, metadata richness, and routeability.
- Hydrate runtime selectively: duration is one of the documented Android TV matching fields, so the app should improve runtime completeness for high-priority cached items, but only through bounded reuse of existing metadata paths.
- Keep Cinemeta as fallback: it still helps for titles not currently in local cache, but should not be the primary path for entity-card matching.
- Optimize for entity-card eligibility, not guaranteed placement: Android TV requires title/year/duration for deep links on media detail pages, but Google/launcher behavior remains outside Nexio's direct control.

## Dependencies / Assumptions

- Android TV docs state the search framework requires title, production year, and duration for content matching; when those values match Google-server provider data, the system can provide a deep link to the app in the content details view.
- `HomeCatalogSnapshotStore` persists `catalogRows`, `fullCatalogRows`, and `heroItems` as `MetaPreview` values, with IDs, content type, release info, runtime, and artwork.
- `MetaPreview` includes `id`, `type`, `rawType`, `name`, `poster`, `background`, `description`, `releaseInfo`, `runtime`, genres, and language.
- `MetadataDiskCacheStore` persists fuller `Meta` records, including title-level runtime and episode runtime, but planning must decide how to enumerate or join cached metadata safely because the current read APIs are key-based.
- The current native-search provider already has live Cinemeta search, suggestion mapping, `searchable.xml`, and detail routing.

## Alternatives Considered

| Approach | Outcome |
| --- | --- |
| Keep live Cinemeta only | Too weak for entity-card matching because cached/enriched Home items often have better metadata and instant availability. |
| Local cache first, Cinemeta fallback | Preferred. It improves match quality for titles Nexio already knows about while preserving broader live search. |
| Local cache first plus explicit scoring | Preferred. It prevents weak local/live matches from outranking exact enriched Home results. |
| Runtime hydration for all catalog items | Rejected. Runtime matters for matching, but unbounded hydration would create too much background work. |
| Bounded runtime hydration for searchable Home/cache items | Preferred. It targets the titles most likely to appear in Android TV search while keeping carrying cost controlled. |
| Full Google Media Actions feed now | Higher upside but heavier operationally and likely requires catalog/feed hosting, policy review, and Google ingestion behavior outside the app. Defer until local provider matching is maximized. |
| Direct playback from entity cards | Deferred. It may be required for some Google “watch action” surfaces, but it reintroduces wrong-stream/wrong-episode risk that the current product decision avoided. |

## Outstanding Questions

### Resolve Before Planning

- None.

### Deferred to Planning

- [Affects R4-R8][Technical] Decide whether the local corpus should read only `HomeCatalogSnapshotStore` first or also enumerate/join `MetadataDiskCacheStore` records.
- [Affects R8, R14, R15][Technical] Define the richness and match scoring used to choose between duplicate local/cached/live results.
- [Affects R12, R13, R16-R20][Technical] Define duration parsing, confidence, and hydration rules for movies, series, and episodes, including whether series should use representative episode runtime.
- [Affects R15][Technical] Choose exact normalized-title matching rules for local cache search.
- [Affects R16-R20][Technical] Decide where bounded runtime hydration should run: during Home snapshot write, during Home refresh, during idle/deferred startup, or lazily before provider results are needed.
- [Affects R21][Technical] Confirm whether `fullCatalogRows` can include hidden/private sources that should be excluded from Android TV global search.
- [Affects R1][Needs research] Verify on-device whether Android TV entity-card matching uses only provider suggestion rows or whether Media Actions/Engage is required on target devices for consistent provider placement.
- [Affects R3][Technical] Decide whether entity-card-origin launches should still open detail or whether Android sends a start-playback signal that should be handled differently.

## Next Steps

-> /ce:plan for structured implementation planning.
