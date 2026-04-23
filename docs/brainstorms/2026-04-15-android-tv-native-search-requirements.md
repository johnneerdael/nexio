---
date: 2026-04-15
topic: android-tv-native-search
---

# Android TV Native Search

## Problem Frame

Nexio should appear in Android TV native search results for movies and TV shows that users can open in the app. Today, users must launch Nexio first and use the in-app search surface. Native TV search can make Nexio feel more integrated with the living room device by letting users search from the Android TV home/search UI and jump directly to the relevant Nexio detail page.

The first version should be lightweight and reliable: use live Cinemeta search, return movie and series suggestions, and open the selected title's detail page. It should not try to resolve streams, choose episodes, or start playback from the native search result.

## Requirements

**Native Search Exposure**
- R1. Nexio must expose itself to Android TV native/global search as a searchable app for movies and TV shows.
- R2. Native search integration must be enabled by default and must not require a Nexio in-app setting before Nexio becomes eligible for Android TV search.
- R3. If the Android TV platform requires users to enable searchable apps in system settings, Nexio should still publish the required searchable metadata so the platform can list Nexio as an available searchable source.

**Live Cinemeta Search**
- R4. Native search suggestions must use live Cinemeta search as the v1 result source.
- R5. The provider must search Cinemeta movie and series catalogs only. It must not fan out across all installed addons.
- R6. Search results should include both movies and TV shows when Cinemeta returns relevant matches.
- R7. Native search must degrade quickly when Cinemeta is unavailable, slow, or returns an error: return no suggestions for that query rather than blocking Android TV search or surfacing an app error.
- R8. Recent live query results may be cached briefly to improve repeated native search responsiveness, but cached results must not become a persistent local search index for v1.

**Result Behavior**
- R9. Selecting a native search result must open the Nexio detail page for the selected movie or TV show.
- R10. TV show results must open the show detail page, not an inferred episode or stream route.
- R11. Movie results must open the movie detail page, not stream selection, deterministic autoplay, or the player.
- R12. Result launches from Android TV search should reuse Nexio's existing detail navigation behavior where practical so back behavior and detail-page actions remain consistent with in-app search.

**Suggestion Presentation**
- R13. Suggestion rows should provide enough metadata for Android TV to present useful results: title, content type where available, poster/artwork where available, production year when reliably parseable, and duration/runtime when reliably available.
- R14. Metadata quality should favor correctness over filling every Android TV field. If year or duration cannot be derived confidently from Cinemeta metadata, omit that field rather than fabricating it.
- R15. Result identity must preserve the Cinemeta item ID, content type, and any addon base URL needed to open the same detail page Nexio would open from in-app search.

**Boundaries and Reliability**
- R16. Native search must not trigger stream/source resolution.
- R17. Native search must not require Trakt, SIMKL, TMDB, TVDB, debrid accounts, or any user-specific integration.
- R18. Native search must not expose installed private addon catalogs through global Android TV search in v1.
- R19. Native search must avoid logging raw user search queries at production log levels.
- R20. Search provider failures must not crash Nexio, break app launch, or interfere with existing Android TV recommendation channels.

## User Flow

```text
Android TV search query
  -> Android TV queries Nexio searchable provider
  -> Nexio performs live Cinemeta movie + series search
  -> Android TV displays Nexio suggestions
  -> User selects a result
  -> Nexio opens the movie/show detail page
```

## Success Criteria

- Searching for a known movie from Android TV search can show a Nexio result and open that movie's detail page.
- Searching for a known TV show from Android TV search can show a Nexio result and open that show's detail page, without attempting to choose an episode.
- Native search results come from Cinemeta live search rather than all installed addons.
- A slow or failing Cinemeta response returns an empty suggestion set quickly and does not visibly hang Android TV search.
- Existing in-app search, detail navigation, stream selection, player launch, and Android TV recommendation channels continue to work as before.

## Scope Boundaries

- Do not build a persistent local search index for the first implementation.
- Do not search all installed addons from Android TV native search.
- Do not add a Nexio setting to enable the feature in the first implementation.
- Do not start playback, deterministic autoplay, or stream selection directly from a native search result.
- Do not attempt episode-level TV search behavior.
- Do not include user library, watch history, Trakt, SIMKL, debrid, or private addon results in native search v1.
- Do not redesign the existing in-app search screen.

## Key Decisions

- Use live Cinemeta search: Cinemeta is already the fast shared metadata source for title search, and limiting v1 to Cinemeta avoids leaking private addon catalogs or creating an index freshness problem.
- Open detail pages only: this gives native search a reliable destination for both movies and shows, while avoiding wrong-episode behavior for TV and avoiding slow stream resolution from a system search UI.
- Enable by default: native search is an app integration, not a power-user feature. If the platform exposes app-level searchable-source controls, Nexio should participate without adding a second Nexio-specific gate.

## Dependencies / Assumptions

- Android's searchable app integration uses a searchable configuration and a content provider that returns suggestion rows. The Android TV docs note that home search result matching depends on title, year, and duration when an app wants to appear as a playback option on an entity card.
- Nexio already has Android TV recommendation/channel code in `app/src/main/java/com/nexio/tv/core/recommendations/`.
- Nexio already has detail navigation through `app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt` and recommendation/deep-link style intent handling in `app/src/main/java/com/nexio/tv/MainActivity.kt`.
- Nexio already has in-app search behavior through `app/src/main/java/com/nexio/tv/ui/screens/search/SearchViewModel.kt`, which searches catalogs using `extraArgs = mapOf("search" to query)`.
- `app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt` already builds Stremio catalog URLs that can represent Cinemeta live search queries.
- The repo currently has no verified native Android search provider or `res/xml/searchable.xml` equivalent for Nexio.

## Alternatives Considered

| Approach | Outcome |
| --- | --- |
| Persistent local index | Rejected for v1. It creates freshness and storage complexity that is unnecessary if Cinemeta live search is fast. |
| Fan out across installed addons | Rejected. It is slower, less predictable, and may expose private addon catalogs through global TV search. |
| Open stream selection | Deferred. It is useful for movies, but weaker for TV and more complex than the detail-page destination. |
| Direct autoplay | Rejected for v1. It risks slow or wrong behavior from a system search result and does not fit TV show ambiguity. |
| Live Cinemeta to detail page | Preferred. It is the smallest useful Android TV integration and aligns with current Nexio navigation. |

## Outstanding Questions

### Resolve Before Planning

- None.

### Deferred to Planning

- [Affects R1, R3][Technical] Confirm the exact Android TV searchable metadata, manifest entries, provider authority, searchable XML, and launch intent wiring needed for current target SDK behavior.
- [Affects R4, R5][Technical] Choose the exact Cinemeta source discovery strategy when stock Cinemeta is missing, disabled, or not in the installed addon list.
- [Affects R7][Technical] Choose the provider latency budget and timeout behavior for live Cinemeta queries.
- [Affects R8][Technical] Choose the short-lived cache key, size, and TTL for recent native search responses.
- [Affects R9, R12][Technical] Decide whether selected suggestions should route through `MainActivity` extras, a custom `nexio://` URI, or another existing detail navigation handoff.
- [Affects R13, R14][Technical] Map Cinemeta metadata fields to Android suggestion columns and verify which year/runtime fields Android TV search uses on target devices.
- [Affects R19][Technical] Audit existing catalog/search logging so native search does not emit raw user queries at production log levels.

## Next Steps

-> /ce:plan for structured implementation planning.
