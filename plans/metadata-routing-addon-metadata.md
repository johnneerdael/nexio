# Catalog Item Shapes — Per-Addon Field Survey

## Context

Nexio renders home/discover **rows** by calling each installed Stremio addon's `/catalog/{type}/{id}.json` endpoint. Each row is an array of *meta items*. The user is about to build a `MetadataRouter` that, given one of these items, decides whether to enrich via TMDB / TVDB / Kitsu, and whether the item is a movie / tv / anime — but first needs to know **what fields actually ride along on each item**, addon-by-addon, so the routing decision can be grounded in the real wire format.

This plan is the answer: literal field dumps from each addon's catalog endpoint, then a recommendation about which field(s) the router should key off.

---

## Item-shape survey (live data, fetched 2026-04-25)

### A. Streaming Catalogs (Netflix / Disney+ / Prime / HBO / AppleTV) — `/catalog/movie/nfx.json`

Richest payload of any addon. First item *Apex* carries 30+ fields:

```
id            "tt16431404"           ← primary, IMDB form
imdb_id       "tt16431404"           ← duplicate cross-ref
moviedb_id    1318447                ← TMDB cross-ref (numeric, bonus)
type          "movie"                ← reliable
name          "Apex"
description   "..."
poster        "https://images.justwatch.com/..."
background    "https://images.metahub.space/background/medium/tt16431404/img"
logo          "https://images.metahub.space/logo/medium/tt16431404/img"
genre/genres  ["Action","Thriller"]  ← duplicated, both keys
released      "2026-04-24T..."
releaseInfo   "2026"
year          "2026"
runtime       "96 min"
imdbRating    ""                     ← may be empty string, not null
popularity    0.198
popularities  { moviedb, stremio, stremio_lib, trakt }
cast/director/writer/country
slug, trailers, trailerStreams
links         [share, Genres, Cast, Writers, Directors]   ← inter-addon nav
behaviorHints { defaultVideoId:"tt16431404", hasScheduledVideos:false }
```

Series variant (`/catalog/series/nfx.json`, *Beef*) is identical in shape and additionally carries `tvdb_id: 399838`, `awards`, `status: "Continuing"`, and `behaviorHints.hasScheduledVideos: true`.

### B. Anime Kitsu — `/catalog/anime/kitsu-anime-trending.json`

```
id            "kitsu:7442"           ← primary, Kitsu form
kitsu_id      "7442"                 ← duplicate
imdb_id       "tt2560140"            ← IMDB cross-ref (when known)
type          "series"               ← UNRELIABLE — also "series" for anime films
animeType     "TV"                   ← THE field that's right: TV / ONA / OVA / Movie / Special
name          "Attack on Titan"
aliases       ["Attack on Titan","Shingeki no Kyojin","AoT"]
genres        []                     ← empty
poster, background, logo, releaseInfo, runtime, imdbRating, trailers
links         [{name:"8.5", category:"imdb", url:"https://kitsu.io/..."}]
```

No cast/director/writer/country/behaviorHints. Genres empty.

### C. Anime Catalogs (MAL / AniDB / AniList / LiveChart, multi-source)

`/catalog/anime/myanimelist_top-all-time.json` — *Frieren*:

```
id            "kitsu:46474"
kitsu_id      "46474"
imdb_id       "tt22248376"           ← present for MAL-source rows
type          "series"
animeType     "TV"
name, aliases, releaseInfo, runtime, imdbRating, poster, background, logo, trailers, links — same shape
genres        []
```

`/catalog/anime/anidb_popular.json` — *Witch Hat Atelier*:

```
id            "kitsu:46043"
kitsu_id      "46043"
type          "series"
animeType     "TV"
imdb_id       ⟪ ABSENT ⟫            ← AniDB-source rows often have no IMDB cross-ref
logo          "https://images.metahub.space/logo/medium/tt32550889/img"   ← IMDB only encoded in URL
```

Same addon, same `kitsu:` ids, but **inconsistent presence of `imdb_id`** depending on upstream source. There is no `mal_id` / `anidb_id` / `anilist_id` field anywhere.

### D. Marvel — `/catalog/movie/movies.json`

Bare-bones. *Captain America* (1944):

```
id            "tt0036697"            ← primary, IMDB form
type          "movie"                ← reliable
name          "Captian America"      ← (sic, addon typo)
poster        "https://web.archive.org/.../cab9d4002b3be2dbef67a89b3ad39783.jpg"
description   "No description available."
releaseInfo   "1944"
imdbRating    "N/A"                  ← string "N/A" when missing, not null/empty
genres        ["Action","Adventure"]
logo          null
```

No `imdb_id` cross-ref, no `moviedb_id`, no `tvdb_id`, no `behaviorHints`, no `links`, no cast, no runtime, no background. Series catalog is the same minimal shape with `type:"series"`.

### E. TMDB addon — `/catalog/movie/tmdb.top.json`

**Endpoint returned 504 during fetch** across multiple attempts. The user's configured TMDB-addon host was unreachable.

From the published TMDB-addon source and from how nexio already handles it ([MetaRepositoryImpl.buildMetaIdCandidates](app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt:389)) the documented item shape is:

```
id            "tmdb:550"             ← primary (or "tt..." if user enabled IMDB-id mode)
type          "movie" | "series"     ← reliable
name, poster, background, description, releaseInfo, runtime, imdbRating, genres, cast, director, writer, behaviorHints
```

Manifest declares `idPrefixes: ["tmdb."]`. **Re-confirm when host is back up** — flagged in verification.

### F. TOP Streaming 🇺🇸 (FlixPatrol / unified rows) — **`/catalog/series/disney-overall-united-states.json` + `/catalog/series/crunchyroll-overall-united-states.json`**

This is the addon the user explicitly called out — both catalogs are declared as `type: series` in the manifest but the user suspected the rows mix movie + tv. Confirmed and there's a second much more important finding too.

#### F.1 Disney row — manifest `type: series`, but actually MIXED

```
TYPE COUNTS: { 'movie': 6, 'series': 4 }

  movie   tt26443597   Zootopia 2
  series  tt13622956   Zootopia+
  movie   tt2948356    Zootopia
  series  tt31091039   The Secret Lives of Mormon Wives
  movie   tt4900148    Elio
  series  tt27444205   Paradise
  movie   tt7504818    Ron's Gone Wrong
  movie   tt1134859    Your Friend the Rat
  movie   tt5814534    Spies in Disguise
  series  tt26685570   How Not to Draw
```

Item 0 *Zootopia 2* (full shape):

```
id            "tt26443597"           ← IMDB
type          "movie"                ← per-item, correct, NOT inherited from catalog "series"
name          "Zootopia 2"
description, releaseInfo:"2025", runtime:"1h48min", imdbRating:"7.6"
poster, logo, background           ← all from image.tmdb.org
links         [imdb, share, Genres x5, Cast x10]
behaviorHints { defaultVideoId:"tt26443597", hasScheduledVideos:false }

# FlixPatrol-specific extras:
flixpatrolRank        1
originalTitle         "Zoomania 2"
translatedFrom        "de-DE"
dataSource            "popular-global"
popularRank           4
originalGlobalRank    5
popularPoints         179
trend                 { type, value, display, bgColor }
accessTracking        { lastAccessed, accessCount, firstAccessed }
```

Item 1 *Zootopia+* (`type:"series"`) has the standard series-shape extras: `videos: [...]` (full episode list with thumbnails, season/episode numbers, air dates, episode ratings), `behaviorHints.hasScheduledVideos:true`, etc.

**Implication for the rail UI**: the catalog row contains heterogeneous tiles. If nexio currently uses the catalog-level `type` to render the row (e.g. assumes "this is a series row, render series tiles"), it will mis-render the 6 movie items. The per-item `type` is the source of truth.

#### F.2 Crunchyroll row — manifest `type: series`, all items `series`, **all items are anime delivered as `tt…` IMDB ids with NO anime marker**

```
TYPE COUNTS: { 'series': 10 }

  series  tt12343534   JUJUTSU KAISEN
  series  tt22248376   Frieren: Beyond Journey's End
  series  tt13911284   Hell's Paradise
  series  tt9307686    Fire Force
  series  tt32536168   Sentenced to Be a Hero
  series  tt32864316   Fate/strange Fake
  series  tt21209876   Solo Leveling
  series  tt38648925   Jack-of-All-Trades, Party of None
  series  tt27417996   I Got a Cheat Skill in Another World…
  series  tt37901124   ROLL OVER AND DIE…
```

Item 0 *JUJUTSU KAISEN*:

```
id            "tt12343534"           ← IMDB, NOT kitsu:
type          "series"
animeType     ⟪ ABSENT ⟫            ← no anime marker on the item
kitsu_id      ⟪ ABSENT ⟫
imdb_id       ⟪ ABSENT ⟫            ← not even a duplicate cross-ref
mal_id        ⟪ ABSENT ⟫
videos        [27 episodes with season/episode/thumbnails]
links         [imdb rating link, share, Genres "Animation"/"Action & Adventure"/"Sci-Fi & Fantasy"]
flixpatrolRank, dataSource:"translation"
```

**This is the critical case for routing.** This addon delivers obviously-anime content (Frieren, Solo Leveling, Jujutsu Kaisen…) but:

- The `id` is `tt…` — looks identical to a Marvel or Netflix live-action row.
- The `type` is `series` — same as a non-anime drama.
- There is **no `animeType`, no `kitsu_id`, no `mal_id`** — nothing on the item itself flags it as anime.
- The Stremio `links[].category:"Genres"` list contains "Animation" but that's heuristic at best (Pixar movies also have "Animation").
- The **only** unambiguous "this is anime" signal is the catalog id `crunchyroll-overall-united-states` and the addon name "TOP Streaming" containing "Crunchyroll".

---

## What every item carries vs. what only some carry

| Field on item | Netflix | Kitsu | MAL-cat | AniDB-cat | Marvel | TMDB (docs) | Disney-row | Crunchyroll-row |
|---|---|---|---|---|---|---|---|---|
| `id` | `tt…` | `kitsu:…` | `kitsu:…` | `kitsu:…` | `tt…` | `tmdb:…`/`tt…` | `tt…` | `tt…` |
| `type` (`movie`/`series`) | ✓ correct | always `series` | always `series` | always `series` | ✓ correct | ✓ correct | ✓ **per-item correct (mixed in row)** | `series` |
| `animeType` | — | ✓ | ✓ | ✓ | — | — | — | **— (anime, but no marker)** |
| `imdb_id` cross-ref | ✓ | ✓ | ✓ | ✗ | ✗ | usually ✗ | ✗ | ✗ |
| `moviedb_id` cross-ref | ✓ | ✗ | ✗ | ✗ | ✗ | n/a (id IS tmdb) | ✗ | ✗ |
| `tvdb_id` cross-ref | ✓ (series) | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| `kitsu_id` cross-ref | ✗ | ✓ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ |
| `behaviorHints.defaultVideoId` | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ (movies) | ✗ |
| `genres` populated | ✓ | empty `[]` | empty `[]` | empty `[]` | ✓ | ✓ | ✗ (only `links[Genres]`) | ✗ |
| `videos[]` (episodes) | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ (series only) | ✓ |
| addon-specific extras | popularities, awards, status | aliases | aliases | aliases | — | — | flixpatrolRank, originalTitle, translatedFrom, accessTracking, trend | flixpatrolRank, dataSource |

**Two surprises that drive the routing design:**

1. **A row's manifest `type` does not constrain the items inside.** Disney row is declared `series` but is 60% movies. The router/UI must consult per-item `type`.
2. **The item `id` does not always disclose anime-ness.** Crunchyroll anime arrives as plain `tt…` ids with `type:series` and no anime marker. The id-prefix heuristic alone gives a false negative on every anime item from this row.

---

## What this means for the metadata router

### Revised core insight

**No single field on a catalog item is sufficient on its own.** The router needs a *layered* decision using three signals:

| Signal | Where it lives | What it tells you |
|---|---|---|
| **Item `id` prefix** | per item | provider for the lookup, and *if the prefix is anime-only* (`kitsu:`/`mal:`/`anilist:`/`anidb:`) → definitely anime |
| **Item `type` field** | per item | reliable for live-action movie-vs-series (Disney row proves rows can be mixed); unreliable for anime-vs-not |
| **Catalog source hint** | per row, from `CatalogRow.addon.manifest` + the catalog id/name | the *only* signal that flags `tt…`-id anime (Crunchyroll case); also the only way to know an addon-declared "anime" row is anime even when items don't carry kitsu prefixes |

### Decision algorithm

```
def resolveProvider(item, catalog):
    # 1. Anime by item id prefix  (Kitsu addon, Anime Catalogs)
    if AnimeStremioId.parse(item.id)?.isAnimeSource():   # kitsu/mal/anilist/anidb
        return PROVIDER_KITSU, mediaKind = if (item.animeType == "Movie") MOVIE else SERIES

    # 2. Anime by catalog source  (Crunchyroll-style: tt-ids but anime catalog)
    if catalog.isAnimeCatalog():    # see "isAnimeCatalog" definition below
        return PROVIDER_KITSU, mediaKind = item.type            # type is reliable here

    # 3. Live action by id prefix
    when (item.id.prefix):
        "tmdb:"  -> PROVIDER_TMDB
        "tvdb:"  -> PROVIDER_TVDB if item.type == "series" else PROVIDER_TMDB
        "tt"/"imdb:" -> PROVIDER_TVDB if item.type == "series" else PROVIDER_TMDB
        else     -> PROVIDER_TMDB   # safest default
```

`catalog.isAnimeCatalog()` consults the row's parent addon, in this priority order:
1. Addon manifest's `types` array contains `"anime"`, OR `idPrefixes` array contains an anime prefix.
2. The catalog's own `type == "anime"`.
3. The catalog `id` or `name` matches a known anime-source pattern (`/(crunchyroll|hidive|funimation|wakanim|anime)/i`).

The third heuristic exists specifically for the Crunchyroll case — its containing addon (TOP Streaming) declares only `movie, series` in its manifest (no `anime` and no `idPrefixes` for kitsu/mal/etc.), so signals 1 and 2 fail. The catalog id `crunchyroll-overall-united-states` is what makes us recognize the row as anime. Heuristics 1 and 2 cover the cleaner addons.

### Why not "carry an explicit `mediaKind` field on every item"

The user asked "what default id should we carry to indicate tv/movie/anime?" The honest answer from this survey: **there is no single `id` you can carry that captures all three dimensions**, because:

- `id` already serves a different purpose (the provider lookup key) and its prefix only sometimes encodes anime.
- `type` is per-item and reliable for live-action movie/series, but addon-broken for anime.
- The "this is anime" decision genuinely requires either an anime-prefixed id OR a catalog/addon hint — there is no item-level field that's universally present.

So the recommendation is: keep the addon-supplied `id` and `type` verbatim on the item, and **add the catalog source as a routing input** (not as a field on `MetaPreview`). The router's input becomes `(item, catalog)` instead of just `(item)`.

### Concrete consequence: the Disney row UI

Today nexio's Discover uses one row → one `ContentType`. The Disney row breaks that assumption. Two options for the UI:

a) **Render mixed rows as-is**, using each tile's per-item `type` to drive its tile shape (poster vs landscape) and the details/playback path. This matches Stremio's own behavior and is the lowest-friction change. It does mean the row's *header type* shown to the user (e.g. "Series") may be misleading when there are movies in it; we'd want to drop the per-row "Series"/"Movies" label or change it to neutral (e.g. just the addon-supplied row name).

b) **Filter rows to match catalog `type`** — drop the 6 movie items from a "series" row. This loses content and contradicts what the addon explicitly returned, so it's strictly worse.

Recommendation: (a). Stremio addons are explicitly free to return mixed rows; the per-item `type` is the contract.

---

## Where this lands in the existing nexio pipeline

1. **DTO** — [MetaPreviewDto](app/src/main/java/com/nexio/tv/data/remote/dto/CatalogResponseDto.kt) already keeps `id` and `type` as raw strings. Add three optional fields:
   - `animeType: String?` (Kitsu / Anime-Catalogs addons)
   - `imdb_id: String?`, `kitsu_id: String?`, `tmdb_id: Int?`/`moviedb_id: Int?`, `tvdb_id: Int?` (cross-refs that let the router skip a Kitsu API roundtrip when a row already carries the IMDB id)
2. **Domain model** — [MetaPreview](app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt:6) already keeps `id`, `type`, and `rawType: String`. Add `animeType: String?` and an optional `crossRefs: Map<String,String>` for the cross-ref id list. **No `ANIME` value on `ContentType`** — anime is a routing property, not a content type.
3. **Mapper** — extend [CatalogMapper.toDomain](app/src/main/java/com/nexio/tv/data/mapper/CatalogMapper.kt:8) to copy the new fields.
4. **Catalog row** — [CatalogRepositoryImpl](app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt:204) already attaches the addon to `CatalogRow`. Expose the catalog's own `id`/`name`/`type` and the addon's manifest `types`/`idPrefixes` to whoever calls the router. The router takes `(MetaPreview, CatalogRow)` not just `MetaPreview`.
5. **Router seam** — new `MediaKindResolver` in `com.nexio.tv.core.routing` implementing the three-signal decision tree above. Reuses [AnimeStremioId.parse](app/src/main/java/com/nexio/tv/core/anime/AnimeStremioId.kt:18). Refactor [TvMetadataRouter.fetchEnrichment](app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt:35) to delegate the kind/provider decision to it instead of the inline `firstAnimeId` + `isTv()` checks (current behavior already does Kitsu-first when an anime prefix is present; the new resolver also catches the Crunchyroll case).
6. **Discover UI** — when rendering a row, use per-item `type` for tile shape and click-through, not the row's manifest type.

### Verification

- **Unit fixtures** — use the verbatim item dumps in this document as test fixtures, one per addon. Assert each routes correctly:
  - Netflix `tt16431404 / movie` → TMDB / movie
  - Netflix series `tt14403178 / series` → TVDB-then-TMDB / series
  - Kitsu `kitsu:7442 / series / animeType:TV` → Kitsu / series
  - MAL-cat `kitsu:46474 / series / animeType:TV` → Kitsu / series
  - AniDB-cat `kitsu:46043 / series / no imdb_id` → Kitsu / series
  - Marvel `tt0036697 / movie` → TMDB / movie
  - TMDB (when up) `tmdb:550 / movie` → TMDB direct / movie
  - **Disney mixed row** — `tt26443597 / movie` from a `type:series` row → TMDB / movie (per-item type wins)
  - **Crunchyroll** — `tt12343534 / series` from `crunchyroll-overall-united-states` catalog → Kitsu / series (catalog-source signal wins despite `tt…` id)
- **Re-fetch the TMDB addon** when its host is responsive and confirm the documented `tmdb:NNN` shape (or note divergence and update fixtures).
- **Smoke test on device** — install all seven addons (the original six + TOP Streaming), open Discover, confirm: (i) Disney row renders both movie and series tiles correctly, (ii) tapping a Crunchyroll tile (e.g. *Solo Leveling*) routes to Kitsu in the logs, (iii) tapping a Marvel tile routes to TMDB.

### Critical files for the implementation session

- [CatalogResponseDto.kt](app/src/main/java/com/nexio/tv/data/remote/dto/CatalogResponseDto.kt) — extend `MetaPreviewDto` with `animeType`, `imdb_id`, `kitsu_id`, `tvdb_id`, `moviedb_id`
- [CatalogMapper.kt:8](app/src/main/java/com/nexio/tv/data/mapper/CatalogMapper.kt:8) — copy them into `MetaPreview`
- [MetaPreview.kt:6](app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt:6) — add `animeType`, `crossRefs`
- [CatalogRepositoryImpl.kt:204](app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt:204) — ensure the row carries enough addon/catalog metadata for the router (manifest `types`+`idPrefixes`, catalog `id`+`type`+`name`)
- [AnimeStremioId.kt:18](app/src/main/java/com/nexio/tv/core/anime/AnimeStremioId.kt:18) — add `isAnimeSource()` helper (returns true for KITSU/MAL/ANILIST/ANIDB only)
- New: `app/src/main/java/com/nexio/tv/core/routing/MediaKindResolver.kt` implementing the three-signal decision tree
- [TvMetadataRouter.kt:35](app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt:35) — refactor to call `MediaKindResolver` first
- [MetaRepositoryImpl.kt:357-408](app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt:357) — already has `inferCanonicalType` and `buildMetaIdCandidates`; the router should consult these instead of reimplementing prefix logic
- Discover UI (Home / catalog rows) — use per-item `type` for tile rendering decisions
