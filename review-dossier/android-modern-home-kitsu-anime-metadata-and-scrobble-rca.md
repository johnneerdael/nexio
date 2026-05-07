# Android Modern Home — Kitsu Anime Rails: Season Duplicates, Missing Episode Metadata, and Broken Scrobble RCA

Date: 2026-05-05

Scope: Android modern home Kitsu rails (`Kitsu Trending Anime - Series`, `Kitsu Popular Anime - Series`) → detail navigation → episode hydration → Trakt/SIMKL scrobble. This is root cause analysis only; no code fixes were applied.

Reproduction signal captured on 192.168.50.98:5555 (modern home, Kitsu Trending rail) showed three distinct "MY HERO ACADEMIA" cards co-existing on the same rail, alongside `Demon Slayer`, `Attack on Titan`, `One Punch Man`, and `Death Note`. Click-through on later-season cards reaches `MetaDetailsViewModel`'s "Episode metadata is unavailable for ..." terminal state.

## Summary

There is **no single bug** here. There are five independent root causes that compound. They cluster into three observable symptoms:

| Symptom | Root causes |
|---|---|
| Same franchise appears multiple times in a Kitsu rail (MHA, MHA S2, MHA S3 …). | RC1, RC2 |
| Clicking a later-season Kitsu entry shows "Episode metadata is unavailable for …". | RC2, RC3 |
| Trakt scrobble silently does nothing when playing anime sourced from Kitsu, or scrobbles with a wrong (season, number) tuple. | RC4, RC5 |

## Evidence: Kitsu API models seasons as separate "anime resources"

`AnimeIdMapAsset` (the bundled `assets/anime/anime-id-map.json`) shows that My Hero Academia is exposed as **eight series-type Kitsu records all sharing the same TVDB show id, IMDB id, and TMDB id**:

```
kitsu=11469  mal=31964  anilist=21459  tmdb=65930  tvdb=305074  imdb=tt5626028  series  TV
kitsu=12268  mal=33486  anilist=21856  tmdb=65930  tvdb=305074  imdb=tt5626028  series  TV
kitsu=13881  mal=36456  anilist=100166 tmdb=65930  tvdb=305074  imdb=tt5626028  series  TV
kitsu=41971  mal=38408  anilist=104276 tmdb=65930  tvdb=305074  imdb=tt5626028  series  TV
kitsu=43108  mal=41587  anilist=117193 tmdb=65930  tvdb=305074  imdb=tt5626028  series  TV
kitsu=45240  mal=49918  anilist=139630 tmdb=65930  tvdb=305074  imdb=tt5626028  series  TV
kitsu=47232  mal=54789  anilist=163139 tmdb=65930  tvdb=305074  imdb=tt5626028  series  TV
kitsu=49279  mal=60098  anilist=182896 tmdb=65930  tvdb=305074  imdb=tt5626028  series  TV
```

One Piece is the inverse pathology — **a single Kitsu series record (`kitsu=12`) covers the entire 1100+ episode run as one flat anime**, while Trakt/TVDB partition it into ~21 seasons. (The other `tvdb=81797` Kitsu records are movies, not seasons.)

## Root Cause RC1 — Kitsu rail mapping is 1:1, no franchise rollup

Each Kitsu API result is mapped 1:1 to a `RailItemPreview`; no franchise- or `tvdb`-level deduplication runs.

- `KitsuDiscoveryService.mapCatalogResults` iterates `KitsuAnimeResource` results unchanged at `app/src/main/java/com/nexio/tv/data/repository/KitsuDiscoveryService.kt:110`.
- `KitsuRailPreviewMapper.mapAnime` produces one preview per Kitsu id at `app/src/main/java/com/nexio/tv/data/integration/railpreview/KitsuRailPreviewMapper.kt:23`.
- The mapper resolves Kitsu→external ids through `AnimeIdMappingService.resolveProviderIdsForKitsu` (`KitsuDiscoveryService.kt:28`), so each rail item *does* know its `tvdb`/`imdb`/`tmdb`, but nothing collapses sibling entries that share those ids.
- `RailItemPreview.sourcePayloadHash` is keyed on `(railId, sourceItemId, kitsu, tmdb, tvdb, imdb, …)` (`KitsuRailPreviewMapper.kt:74-95`). `sourceItemId` is `kitsu:<id>`, so each season is a distinct row to every downstream consumer.

The "Kitsu Trending Anime" feed therefore exposes whatever Kitsu surfaces — and Kitsu surfaces seasonal records as separate entries.

## Root Cause RC2 — `AnimeIdMapRecord` has no season/episode-range disambiguator

`AnimeIdMapRecord` carries identity for a Kitsu anime resource but **no season number, no episode range, and no franchise/parent reference**:

```
data class AnimeIdMapRecord(
    val kitsu: String,
    val mal: String? = null,
    val anilist: String? = null,
    val anidb: String? = null,
    val tmdb: String? = null,
    val tvdb: String? = null,
    val imdb: String? = null,
    val mediaType: String? = null,
    val sourceType: String? = null
)
```

(`app/src/main/java/com/nexio/tv/core/anime/AnimeIdMapAsset.kt:21-31`.)

Consequences:

1. **The rail cannot roll seasons up to a single franchise card.** A "merge by parent imdb" pass would group MHA S1–S5 onto one `tt5626028`, but it would also merge in the eight movie records that share `tvdb=305074` and have distinct `imdb` ids, and would not know which Kitsu poster to keep. There is no parent identity in the asset to group on, only a many-to-one `kitsu→imdb/tvdb` relation.
2. **The detail screen cannot pick the correct TVDB season.** When the user clicks "MHA Season 3" (`kitsu=13881`), `resolveProviderIdsForKitsu` returns `(tvdb=305074, imdb=tt5626028, tmdb=65930)` — exactly the same tuple as the parent record `kitsu=11469`. There is no signal that this Kitsu id maps to "TVDB show 305074, season 3, episodes 1..25". Downstream code that wants to fetch TVDB episodes for the right season has no input to use.

The asset is generated from `https://raw.githubusercontent.com/Fribb/anime-lists/refs/heads/master/anime-list-full.json` plus `kitsuImdb` and `traktAnimeMovies` overlays (see `assets/anime/anime-id-map.json` `sources`). None of those upstream feeds contain season/cour offsets, so the lossy 1:1 record shape mirrors the upstream model.

## Live evidence captured 2026-05-05 on 192.168.50.98:5555

Filtered logcat (`MetaDetailsViewModel:V AnimeIdMappingService:V TraktScrobbleApi:V AndroidRuntime:E *:S`) while clicking "My Hero Academia Season 3" then "One Piece" from the Kitsu Trending rail:

```
05-05 16:52:54.275 I MetaDetailsViewModel: detail.kitsu_advanced_result metaId=kitsu:13881 sourceId=kitsu:13881 characters=9 productions=3 related=3
05-05 16:52:54.277 I MetaDetailsViewModel: detail.episode_enrichment_required_before_ready metaId=kitsu:13881 provider=KITSU videos=0
05-05 16:52:54.317 I MetaDetailsViewModel: detail.episode_enrichment_result metaId=kitsu:13881 provider=KITSU coreEpisodes=0 fetchedEpisodes=0 targetVideos=0
05-05 16:52:54.317 W MetaDetailsViewModel: Series detail blocked without episode metadata for kitsu:13881

05-05 16:53:12.542 I MetaDetailsViewModel: detail.kitsu_advanced_result metaId=kitsu:12 sourceId=kitsu:12 characters=20 productions=5 related=19
05-05 16:53:12.545 I MetaDetailsViewModel: detail.episode_enrichment_required_before_ready metaId=kitsu:12 provider=KITSU videos=0
05-05 16:53:12.595 I MetaDetailsViewModel: detail.episode_enrichment_result metaId=kitsu:12 provider=KITSU coreEpisodes=0 fetchedEpisodes=791 targetVideos=0
05-05 16:53:12.595 I MetaDetailsViewModel: detail.episode_enrichment_building_video_stubs metaId=kitsu:12 episodeCount=791
05-05 16:53:12.597 I MetaDetailsViewModel: detail.episode_enrichment_ready_before_render metaId=kitsu:12 videos=791
```

Interpretation:

- Both items take the identical code path: router picks `provider=KITSU`, meta arrives with `videos=0`, mandatory-episode-block engages, `applyTvEpisodeEnrichment` calls `KitsuMetadataService.fetchEpisodeEnrichment`.
- For `kitsu:13881` (MHA S3): Kitsu returns **0 episodes** (`fetchedEpisodes=0`). `coreEpisodes=0` (no TVDB core enrichment, because the route is Kitsu, not TVDB). The merged map is empty, no video stubs are built, and `Series detail blocked without episode metadata for kitsu:13881` fires (`MetaDetailsViewModel.kt:790-797`).
- For `kitsu:12` (One Piece): Kitsu returns **791 episodes** (`fetchedEpisodes=791`). `building_video_stubs` runs (`MetaDetailsViewModel.kt:1936-1948`), 791 video stubs are emitted, and the detail screen renders. Those 791 episodes are all `(season=1, episode=N)` per `KitsuMetadataService.fetchEpisodeEnrichment` line 79 (`val season = attributes.seasonNumber ?: 1`) — Kitsu's per-anime endpoint does not partition them.

Direct verification against Kitsu's public API on 2026-05-05 contradicts a "Kitsu data is missing" interpretation:

```
$ curl 'https://kitsu.io/api/edge/anime/13881/episodes?page[limit]=2'
meta.count = 25
  id=243500 number=1 seasonNumber=3 canonicalTitle='Game Start'
  id=243501 number=2 seasonNumber=3 canonicalTitle='Wild, Wild Pussycats'
  ...

$ curl 'https://kitsu.io/api/edge/anime/12/episodes?page[limit]=3'
meta.count = 1387
  id=103482 number=1 seasonNumber=1 canonicalTitle="I'm Luffy! ..."
  id=103483 number=2 seasonNumber=1 canonicalTitle='Enter the Great Swordsman ...'
```

Kitsu has all 25 MHA S3 episodes — and **labels them `seasonNumber=3`** (its franchise-relative season tag). One Piece's 1387 episodes carry `seasonNumber=1` because Kitsu models OP as one flat season.

Combined with `KitsuApi.kt:236` correctly mapping `@Json(name = "seasonNumber") val seasonNumber: Int? = null`, the in-app pipeline definitely receives `seasonNumber=3` for each MHA S3 episode. The `fetchedEpisodes=0` in the trace is therefore caused by a **filter on our side**, not by missing upstream data.

This sharpens RC3 below to a single-line bug: the router defaults the requested season to 1 when the caller has none, Kitsu's mapper preserves the franchise-relative season tag, and the season filter discards every episode whose season tag is not 1.

## Root Cause RC3 — Season-number filter mismatch between router default and Kitsu's franchise-relative season tag

The empty `episodeMap` in the live trace is *not* a Kitsu data gap; it is a filter mismatch entirely on our side. Walk-through with the actual data:

Concrete trace for an MHA S3 (`kitsu:13881`) click, with verified Kitsu response data:

1. `MetaDetailsViewModel.applyMetaWithEnrichment` opens with `meta.id = "kitsu:13881"`, `meta.videos = []` (Kitsu's `/anime/{id}` endpoint does not embed episodes — that is a separate `/anime/{id}/episodes` endpoint).
2. `enrichMeta(...)` routes via `MetadataRouterFacade`; route picks `provider=KITSU`. `tvEnrichment.episodeMetadata` is empty because `KitsuMetadataService.fetchEnrichment` builds a show-level `TvMetadataEnrichment` (`KitsuMetadataService.kt:38-63`) — no episodes are fetched at this stage.
3. `shouldBlockSeriesReadyStateForMandatoryEpisodes` returns true (`MetaDetailsViewModel.kt:844-850`): `isTvContent && useEpisodes && !hasEpisodeRows(meta)`.
4. `applyTvEpisodeEnrichment` (`MetaDetailsViewModel.kt:1889-1923`) computes:
   ```
   val seasonNumbers = targetMeta.videos
       .mapNotNull { it.season }                              // [] (videos is empty)
       .ifEmpty { tvdbCoreEpisodes.keys.map { it.first } }    // [] (tvdbCoreEpisodes is empty)
       .distinct()                                            // []
   ```
   and calls `metadataRouterFacade.fetchTvEpisodeEnrichment(metadataRequest, tvRequest = TvMetadataRequest(seasonNumbers = []))`.
5. **`MetadataRouterFacade.fetchTvEpisodeEnrichment` defaults the season to 1** at `MetadataRouterFacade.kt:644-649`:
   ```
   val seasonMetadataRequest = metadataRequest.copy(
       depth = MetadataDepth.SEASON,
       seasonNumber = tvRequest.seasonNumbers.firstOrNull() ?: metadataRequest.seasonNumber ?: 1
   )
   ```
   `tvRequest.seasonNumbers = []` and `metadataRequest.seasonNumber = null`, so `seasonNumber = 1`.
6. `fetchEpisodeMetadataForRoute(route, seasonNumbers = [], metadataSeasonNumber = null)` (`MetadataRouterFacade.kt:708-724`) applies the same default a second time:
   ```
   return seasonNumbers
       .ifEmpty { listOfNotNull(metadataSeasonNumber) }
       .ifEmpty { listOf(1) }
       .flatMap { seasonNumber -> ... seasonRoute.copy(seasonNumber = seasonNumber) ... }
   ```
   It iterates `[1]` and builds a provider plan keyed at season 1.
7. `ProviderPlanRunner` dispatches to `KitsuMetadataService.fetchEpisodeEnrichment(rawId="kitsu:13881", mediaKind=SERIES, seasonNumbers=[1])`.
8. `KitsuMetadataService` calls `KitsuIntegrationProvider.fetchEpisodeEnrichment` → `fetchEpisodePages` (`KitsuIntegrationProvider.kt:383-412`) → `kitsuApi.getAnimeEpisodes(id="13881", limit=20, offset=0..)`.
9. **Kitsu API returns all 25 episodes**, every one with `attributes.seasonNumber = 3` (verified live).
10. The mapper at `KitsuMetadataService.kt:75-92` builds a `Map<Pair<Int, Int>, TvEpisodeMetadata>`:
    ```
    val season = attributes.seasonNumber ?: 1
    val number = attributes.number ?: return@mapNotNull null
    (season to number) to TvEpisodeMetadata(...)
    ```
    `attributes.seasonNumber = 3` → keys are `(3, 1) … (3, 25)`. `KitsuApi.kt:236` confirms the field is correctly mapped: `@Json(name = "seasonNumber") val seasonNumber: Int? = null`.
11. **The filter at `KitsuMetadataService.kt:94-99` drops every entry**:
    ```
    val acceptedSeasons = seasonNumbers.toSet()                // {1}
    allEpisodes.filterKeys { (season, _) -> season in acceptedSeasons }   // {} — no key has season=1
    ```
12. Empty map returns up the stack. `applyTvEpisodeEnrichment` line 1931 (`if (episodeMap.isEmpty()) return targetMeta`) returns the unmodified empty `targetMeta`.
13. Caller at `MetaDetailsViewModel.kt:789` checks `episodeHydratedMeta.videos.isEmpty()`, it is, so `error = "Episode metadata is unavailable for ${enrichment.meta.name}."` (`MetaDetailsViewModel.kt:794`).

**The single-line root cause:** the router defaults `seasonNumber` to `1` when the caller has none (`MetadataRouterFacade.kt:648`, `MetadataRouterFacade.kt:715`), but Kitsu's episode payload tags each row with its franchise-relative season (3 for MHA S3, 4 for MHA S4, etc.). The Kitsu mapper preserves that tag, and the season filter at `KitsuMetadataService.kt:97-98` then discards everything that is not season 1. The detail screen interprets the empty response as "Kitsu has no episodes" and surfaces the user-facing error.

This is why the symptom is per-anime-resource:
- **MHA S1 (`kitsu:11469`) works** if (and only if) Kitsu labels its 13 episodes with `seasonNumber=1` — needs a confirming curl, but the mechanism predicts it works for S1 and fails for S2..FINAL.
- **MHA S2..FINAL fail** because Kitsu's labels are `seasonNumber=2..7`, all dropped by the season-1 filter.
- **One Piece (`kitsu:12`) works** (with the wrong UI shape) because Kitsu labels its 1387 episodes `seasonNumber=1` — the franchise is modelled as one flat anime resource, not partitioned. The season-1 filter happens to match all 1387, so they render as "season 1, episodes 1..1387", which is also why the show appears as "one giant season" instead of Trakt's 21-season layout. The same data is what would break scrobble (RC5).

The previously-mentioned `expandAnimeAddonSeasons` (`MetaDetailsViewModel.kt:1799`) and the `fallbackContentId` route (`MetadataRouterFacade.kt:726-740`) are not on this path; both are no-ops for the captured failure. The bug is local to the season-default + Kitsu-mapper pair.

Original wider analysis (kept for context):

1. Router resolves `kitsu:13881` to `provider=KITSU` (`MetaDetailsViewModel.kt:1416 isKitsuAnimeByProvider`); the meta arrives with `videos=0`.
2. `shouldBlockSeriesReadyStateForMandatoryEpisodes` (`MetaDetailsViewModel.kt:844-850`) returns true — `isTvContent=true`, `settings.useEpisodes=true`, `hasEpisodeRows=false`.
3. `applyTvEpisodeEnrichment` (`MetaDetailsViewModel.kt:1889`) calls `metadataRouterFacade.fetchTvEpisodeEnrichment(contentId=kitsu:13881, ...)`.
4. `MetadataRouterFacade.fetchTvEpisodeEnrichment` (`app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:640-706`) routes to `KitsuMetadataService` via the Kitsu provider plan (because the route picked KITSU at step 1).
5. `KitsuMetadataService.fetchEpisodeEnrichment` (`app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt:65-100`) calls `provider.fetchEpisodeEnrichment(rawId="kitsu:13881", kitsuId="13881", ...)`.
6. Kitsu's `/anime/13881/episodes` returns **zero rows** (live trace: `fetchedEpisodes=0`).
7. `episodeMap` is empty, `applyTvEpisodeEnrichment` returns the unmodified empty `targetMeta` (`MetaDetailsViewModel.kt:1931 if (episodeMap.isEmpty()) return targetMeta`).
8. Caller checks `episodeHydratedMeta.videos.isEmpty()` (`MetaDetailsViewModel.kt:789`), it is, so `error = "Episode metadata is unavailable for ${enrichment.meta.name}."` is set (`MetaDetailsViewModel.kt:794`).

The architectural defect is at step 4–6: **`MetadataRouterFacade.fetchTvEpisodeEnrichment` does not retry against TVDB when the Kitsu provider returns empty.** The `fallbackRouteForDistinctContentId` at `MetadataRouterFacade.kt:726-740` is only used when `targetIdRequiresIdentityResolution` is true OR when the primary route's episode map was empty AND a *distinct* `fallbackContentId` was supplied by the caller. Inspect the call site at `MetaDetailsViewModel.kt:1907-1923`:

```
val episodeDecision = metadataRouterFacade.fetchTvEpisodeEnrichment(
    metadataRequest = MetadataRequest(contentId = targetMeta.id, ...),
    tvRequest = TvMetadataRequest(
        contentId = targetMeta.id,
        fallbackContentId = itemId,        // <-- equal to targetMeta.id for a fresh detail open
        ...
    )
)
```

`itemId` is the screen's input id, which for a click from a Kitsu rail equals `meta.id` (both `kitsu:13881`). `fallbackRouteForDistinctContentId` short-circuits when `fallbackId == metadataRequest.contentId` (`MetadataRouterFacade.kt:731`), so the fallback branch never fires. There is no path here that converts `kitsu:13881` into `tvdb:305074` (which `AnimeIdMappingService.resolveProviderIdsForKitsu` would have produced) and re-asks TVDB for episodes. **The router locks onto KITSU and gives up.**

This single observation explains both captured cases:

- **MHA S3 (`kitsu:13881`) — error:** Kitsu DB has no episode rows for that anime resource → empty map → no fallback to TVDB show 305074 season 3 → "Episode metadata is unavailable".
- **One Piece (`kitsu:12`) — works but flat:** Kitsu DB has 791 episode rows for that anime resource, all with `attributes.seasonNumber == null` so `KitsuMetadataService.kt:79` defaults them to `season=1`. The `building_video_stubs` path (`MetaDetailsViewModel.kt:1936-1948`) materialises them as `(1, 1)..(1, 791)` videos. TVDB partitions One Piece into ~21 seasons but is never consulted, so the UI is "one giant season 1".

The previously-mentioned `expandAnimeAddonSeasons` (`MetaDetailsViewModel.kt:1799`) is *not* on this path. It runs earlier and is a no-op in both captured cases (`kitsu:12` and `kitsu:13881` both lacked `Franchise: Sequel/Prequel` links pointing at a Stremio detail URL). The captured failure is fully reproducible without a Stremio Kitsu addon and is not gated on it; addon presence would only have helped if a sibling Kitsu record (e.g. `kitsu:11469` parent MHA) had episode rows that could be transitively merged — possible in principle, but unrelated to the actual TVDB-fallback gap.

## Root Cause RC4 — Trakt scrobble drops every kitsu/mal/anilist/anidb playback id silently

`TrackingScrobbleService.toTraktItem` (`app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt:146-165`) is the single funnel that converts a `TrackingScrobbleItem` into the Trakt-shaped envelope:

```
private fun toTraktItem(item: TrackingScrobbleItem): TraktScrobbleItem? {
    val ids = toTraktIds(parseContentIds(item.contentId()))
    if (!ids.hasAnyId()) return null
    ...
}
```

`parseContentIds` (`app/src/main/java/com/nexio/tv/data/repository/TraktIdUtils.kt:13-39`) recognises only:

- `tt…` → `imdb`
- `tmdb:…` → `tmdb`
- `tvdb:…` → `tvdb`
- `trakt:…` → `trakt`
- bare numeric → `trakt`

It does **not** recognise `kitsu:`, `mal:`, `anilist:`, or `anidb:`. Any of those prefixes hits the unconditional `else → ParsedContentIds()` branch at line 37 and returns an empty struct. `toTraktIds(empty).hasAnyId()` is `false`, so `toTraktItem` returns `null`, and `scrobbleStart`/`scrobbleStop`/`scrobblePause`/`checkin` at `TrackingScrobbleService.kt:62/76/90/111` simply return without invoking `traktScrobbleService` at all.

Whenever playback is launched with `contentId` carrying a Kitsu (or other anime-native) scheme — which is the normal case for items coming off a Kitsu rail without an upstream Stremio resolver overwriting the id — **scrobble is silently no-op'd**. There is no log message: the early-`return null` is unobservable from logcat.

This is the `TraktScrobbleApi` log tag (`app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt:186`) being absent on anime playback: not because the request was sent and 4xx'd, but because the envelope was never built.

The same `parseContentIds`/`toTraktIds` pair is used by `TraktProgressService` watch-history reconciliation at `TraktProgressService.kt:839, 910, 1026, 1073, 1346, 1793`, so anime watch-history matching against Trakt is impacted by the same gap (out of scope of this RCA, but worth flagging).

## Root Cause RC5 — Even when ids resolve, `(season, number)` is wrong for Kitsu-flat anime

`TraktScrobbleMutationAdapter.populateItem` (`app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt:272-282`) sends the literal `season` and `number` from `TraktScrobbleItem.Episode` into the Trakt request. Those fields originate from `TrackingScrobbleItem.Episode.season/number` (`TrackingScrobbleService.kt:23-24`), which in turn come from the playing video's metadata.

For Kitsu-sourced playback, that video metadata comes from Kitsu's flat episode model. For One Piece, every episode is `season=1, number=N` where `N` is Kitsu's running counter. Trakt's data model partitions One Piece into ~21 named seasons; sending `season=1, number=850` does not resolve to any Trakt episode and the scrobble request 4xx's (or matches the wrong title silently if Trakt's loose matching falls back).

Even after RC4 is fixed (so the Trakt scrobble request is actually built for anime), RC5 means the request body itself is wrong for any franchise where Kitsu's season layout differs from Trakt's. This is the `One Piece` example called out in the report and applies to any long-running anime.

There is no anime-aware translation layer between the Kitsu video coordinates and the Trakt scrobble payload. `TraktScrobbleService` and `TraktScrobbleMutationAdapter` accept `season` and `number` as plain integers and forward them.

## Why MHA S3 fails but MHA S1 typically does not

- `kitsu:11469` (MHA S1, 25 episodes) is the parent record. When Kitsu's own API returns `videos`, those `videos` align with TVDB season 1, and the merge at `applyTvEpisodeEnrichment` ends with `videos.isNotEmpty()`. The mandatory-episode block clears.
- `kitsu:13881` (MHA S3, 25 episodes) is a sibling record. Kitsu may return `videos` numbered 1..25 — but since the meta id is still the Kitsu id and the merge uses `(season, episode) → episode` keys against TVDB's actual season-3 episodes (which TVDB numbers `(3, 1) … (3, 25)`), the only way to align them is if the upstream addon (`expandAnimeAddonSeasons`) had already rewritten season numbers to `3` while merging siblings. Without the addon, the merge goes nowhere and the meta remains empty after enrichment.

The intermittence is therefore: addon present + parent record → success; addon absent or non-parent record → "Episode metadata is unavailable".

## Compatibility constraint

The user-stated requirement is that any remediation **must keep working for content sourced from TVDB / IMDB / TMDB and must keep scrobble correct**. The constraint is satisfied if all changes happen behind these seams:

- `KitsuRailPreviewMapper` rail composition (RC1).
- `AnimeIdMapAsset` schema and `AnimeIdMappingService` lookups (RC2).
- `MetaDetailsViewModel.expandAnimeAddonSeasons` / `applyTvEpisodeEnrichment` (RC3).
- `TraktIdUtils.parseContentIds` and `TrackingScrobbleService.toTraktItem` (RC4).
- The Kitsu→Trakt episode-coordinate translation (RC5), which has no current home.

Changes confined to those seams cannot regress TVDB/IMDB/TMDB paths, which already use distinct branches in `parseContentIds`, distinct enrichment logic in `enrichMeta` (`isAnimeDetail = false` branch), and a different scrobble payload populated from TVDB/TMDB metadata.

## Hypotheses

Confidence is **high** for RC1, RC2, and RC4 — they are observable directly in the asset shape, the mapper code, and the parser code respectively, with no runtime logging required.

Confidence is **high** for RC3 once you assume `preferredAddonBaseUrl` is blank (typical out-of-the-box state), and **medium-high** when the addon is present — there are subtle paths inside `metadataRouterFacade.fetchTvEpisodeEnrichment` not exhausted in this analysis that may also affect the failure (e.g. how `seasonNumber = null` is treated by the TVDB or KITSU enrichment branch). Adding device logs around `detail.episode_enrichment_required_before_ready` (`MetaDetailsViewModel.kt:771`) and `detail.episode_enrichment_result` (`MetaDetailsViewModel.kt:1925`) would close that gap.

Confidence is **high** for RC5 from the data shape, **medium** for whether Trakt rejects, silently misroutes, or is loosely lenient about a `(1, 850)` payload — empirical confirmation requires playing a One Piece episode end-to-end and inspecting `TraktScrobbleApi` response bodies after RC4 is unblocked.

## Evidence Needed From A Device

To raise confidence on RC3 and RC5 specifically, capture while reproducing:

- Logcat tag `MetaDetailsViewModel`: lines `detail.episode_enrichment_required_before_ready`, `detail.episode_enrichment_result`, `detail.episode_enrichment_building_video_stubs`, `Mandatory episode metadata failed`, `Series detail blocked without episode metadata` — captures whether the router returned an empty episode map vs. threw, and what `seasonNumbers` was used.
- Logcat tag `TraktScrobbleApi`: presence of `request endpoint=POST /scrobble/start` lines while playing a Kitsu-rail anime — absence confirms RC4; presence with `season=1 number=<high>` confirms RC5.
- Settings → Player → enable `traktScrobbleApiLoggingEnabled` (`PlayerSettingsDataStore`) to make `TraktScrobbleApi` request/response bodies visible.
- `preferredAddonBaseUrl` value at the time of the MHA S3 click (logged or via shared-prefs dump) — needed to interpret RC3's first failure mode.

## Existing Coverage

There is no test that asserts:

- A Kitsu rail does not surface duplicate franchise members (RC1).
- `resolveProviderIdsForKitsu` round-trips a season disambiguator (RC2 — would fail today since the field doesn't exist).
- `applyTvEpisodeEnrichment` populates `videos` for a Kitsu series when `expandAnimeAddonSeasons` is a no-op (RC3).
- `parseContentIds` round-trips an anime scheme into Trakt-equivalent ids via `AnimeIdMappingService` (RC4).
- The Trakt scrobble payload uses TVDB-canonical `(season, number)` for an anime that Kitsu represents as flat (RC5).

Tests touched by the current branch (per `git status`) include `AnimeIdMappingServiceTest`, `MetadataRouterFacadeStableIdBundleTest`, `KitsuRailPreviewMapperTest`, `KitsuDiscoveryServiceTest`, `PremiumPosterMetadataProviderAdapterStableIdTest` — none of them assert the seam properties listed above; they verify other guarantees (id mapping correctness for the existing schema, stable-id resolution for premium posters, etc.).

## Why It Is Intermittent

- Without a Stremio Kitsu addon: every later-season Kitsu rail click hits "Episode metadata is unavailable"; every Kitsu-sourced playback has scrobble silently disabled.
- With the Stremio Kitsu addon: parent-record clicks (e.g. MHA S1) succeed because `expandAnimeAddonSeasons` traverses `Franchise: Sequel:` links and the Kitsu addon's own meta carries TVDB-aligned `(season, episode)` numbers; later-season records may also succeed depending on the addon's franchise graph completeness; flat-season anime like One Piece still mis-scrobble.
- Profiles that previously played anime via TVDB/TMDB-resolved ids (e.g. Cinemeta) avoid RC4/RC5 because the playback `contentId` is `tt…` rather than `kitsu:…`.

## Non-Fix Recommendations

These are investigation follow-ups, not implemented fixes:

- Add a failing unit test for `KitsuRailPreviewMapper` / `KitsuDiscoveryService` that constructs a result list containing two Kitsu records with the same `(tvdb, imdb)` and asserts the rail-level dedup behaviour we want (currently: zero dedup).
- Add a failing unit test for `parseContentIds` that asserts `kitsu:11469` resolves to a `ParsedContentIds` carrying the mapped `imdb=tt5626028, tmdb=65930, tvdb=305074` via `AnimeIdMappingService` — surfaces RC4 directly.
- Schema-level decision: extend `AnimeIdMapRecord` with a `parentKitsu`, `tvdbSeason`, and `tvdbEpisodeOffset` (or equivalent), and pick an upstream feed that supplies them (`Trakt anime mappings`, `manami-project/anime-offline-database`, or a curated overlay). RC2 cannot be fixed without an upstream that actually supplies the disambiguator.
- Add a failing test for `applyTvEpisodeEnrichment` that supplies a Kitsu meta with `videos = []` and `tvDecision.provider = KITSU` and asserts the resulting meta is hydrated either from the `episodeMap` stub builder (`MetaDetailsViewModel.kt:1936`) or from a Kitsu-specific episode source — currently it returns the empty meta.
- Add a contract test for `TrackingScrobbleService.toTraktItem` asserting it does not silently return `null` for kitsu/mal/anilist/anidb scheme `contentId`s when an `AnimeIdMappingService` mapping exists — currently it does.
- Add a contract test for the Kitsu→Trakt episode-coordinate translation (RC5) once a translation layer is introduced — at present there is nothing to test because the translation does not exist.

No code changes were applied as part of this RCA.
