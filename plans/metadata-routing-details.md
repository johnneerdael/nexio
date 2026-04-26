
# MetadataRouter — IMDB-as-stable-key + Fribb anime-list + persistent id mapping

## Context

Across the seven catalog addons surveyed (Netflix/Disney+/Prime/etc., Anime Kitsu, Anime Catalogs, TMDB, Marvel, TOP Streaming/Crunchyroll), every catalog item carries an `id` and a `type`. Two facts from that survey drive the design:

1. **IMDB (`tt…`) is the de-facto stable key for every non-anime addon.** Netflix, Marvel, Disney row, Crunchyroll row, even most Kitsu items carry IMDB ids (either as `id` or as `imdb_id`). TMDB-prefixed (`tmdb:NNN`) appears only from the TMDB addon and only when the user hasn't enabled IMDB-id mode.
2. **Anime can ride in on either `kitsu:`-style ids OR plain `tt…` ids.** Anime Kitsu and Anime Catalogs use `kitsu:NNN`. The Crunchyroll row in TOP Streaming uses `tt…` IMDB ids identical in shape to live-action — there is no `animeType` or other anime marker on those items.

The project already bundles the Fribb anime-list (plus rensetsu's anitrakt + the kitsu→IMDB mapping) as a single 3.95 MB asset at [app/src/main/assets/anime/anime-id-map.json](app/src/main/assets/anime/anime-id-map.json), exposed via [AnimeIdMappingService](app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt). It contains `byKitsu`, `byMal`, `byAnilist`, `byAnidb`, `byImdb`, `byTmdbMovie`, `byTmdbSeries`, `byTvdb` lookup tables — i.e. it can resolve an IMDB id to a Kitsu id offline. That makes anime-detection a local lookup, no network.

The plan has four parts: the routing rule itself, a stale-while-revalidate render pipeline that uses addon-bundled metadata for instant paint, a persistent id-mapping cache so any cross-id we resolve once is never resolved again, and Continue-Watching parity so episode rows on the home screen carry the same provider + addon-bundled metadata as the catalog row that started the playback.

---

## Part 1 — The router rule

```
fun route(itemId, type):
    # 1. Anime by id prefix — early return, no lookup
    if itemId starts with "kitsu:" / "mal:" / "anilist:" / "anidb:":
        return KITSU

    # 2. Anime by Fribb local lookup
    parsed = AnimeStremioId.parse(itemId)        # handles tt…, tmdb:, tvdb:, imdb:
    if parsed != null and animeIdMappingService.resolveKitsuId(parsed, mediaKindFromType(type)) != null:
        return KITSU

    # 3. Live action
    return if (type == "series") TVDB else TMDB
```

That's the whole router. No catalog/addon hints needed, no `animeType` plumbing, no `ContentType.ANIME` enum value.

### Why this works for every surveyed addon

| Addon row | First item | Step that fires | Provider |
|---|---|---|---|
| Netflix `nfx` movie | `tt16431404 / movie` | step 2 misses → step 3 | TMDB |
| Netflix `nfx` series | `tt14403178 / series` | step 2 misses → step 3 | TVDB |
| Anime Kitsu | `kitsu:7442 / series` | step 1 | Kitsu |
| Anime Catalogs MAL | `kitsu:46474 / series` | step 1 | Kitsu |
| Anime Catalogs AniDB | `kitsu:46043 / series` | step 1 | Kitsu |
| Marvel | `tt0036697 / movie` | step 2 misses → step 3 | TMDB |
| TMDB addon | `tmdb:550 / movie` | step 2 misses (tmdb non-anime) → step 3 | TMDB |
| Disney mixed row, *Zootopia 2* | `tt26443597 / movie` | step 2 misses → step 3 | TMDB |
| Disney mixed row, *Paradise* | `tt27444205 / series` | step 2 misses → step 3 | TVDB |
| Crunchyroll *Jujutsu Kaisen* | `tt12343534 / series` | step 2 hits Fribb (`byImdb[tt12343534]` → Kitsu id) | Kitsu |
| Crunchyroll *Solo Leveling* | `tt21209876 / series` | step 2 hits Fribb | Kitsu |

The Crunchyroll case is exactly what makes the Fribb step necessary — the items look identical to live-action `tt…` series but Fribb knows they're anime.

### Disney mixed row (movie+series in a `type:series` catalog)

Per-item `type` is the source of truth. The router doesn't care about catalog-level type at all; it just reads the item's own `type` for the live-action branch. Discover-row UI should also use per-item `type` for tile rendering — the Disney row contains 6 movies and 4 series and that's by design.

---

## Part 2 — Stale-while-revalidate rendering

Catalog items already arrive with rich enough payload to paint the row tiles without any provider call: `id`, `name`, `type`, `poster`, `background`, `description`, `releaseInfo`, `runtime`, `imdbRating`, `genres` (where present), `behaviorHints`. Quality varies (Marvel ships typos like *"Captian America"*, Kitsu posters are lower-res than TMDB's, language is whatever the addon picked), but it's good enough for the first frame.

Render pipeline:

1. **Catalog response → addon-bundled `MetaPreview`** lands on screen immediately. No router call yet.
2. **Per-visible-item, `MetadataRouter.route(id, type)`** picks the canonical provider.
3. **Canonical fetch runs in the background.** On success, *replace* the addon fields (don't merge). On failure or empty response, keep the addon-bundled values.

"Replace, don't merge" matters: the addon fields are inconsistent (Marvel typos, Disney `originalTitle:"Zoomania 2"` from a German translation). Merging would let the worst addon win some fields. Overwriting on success is predictable; worst case is "the canonical provider didn't have it, so we kept what we had".

Visibility-driven refresh (only fetch for items currently on screen / about to scroll into view) keeps it cheap on a TV grid where each row may have 50+ items.

---

## Part 3 — Persistent id-mapping cache

Every catalog parse and every router-driven resolution discovers cross-id facts. Persist them so we never resolve the same fact twice.

### What to store

A single table keyed `(source_scheme, source_id)` → `(target_scheme, target_id, resolved_at, source_of_record)`:

| field | example | notes |
|---|---|---|
| `source_scheme` | `imdb` / `tmdb` / `tvdb` / `kitsu` / `mal` / `anilist` / `anidb` | |
| `source_id` | `tt12343534` / `550` / `399838` / `7442` | with prefix where the prefix is part of the canonical form (IMDB keeps `tt`) |
| `target_scheme` | same enum | |
| `target_id` | as above | |
| `resolved_at` | epoch ms | for negative-TTL bookkeeping |
| `source_of_record` | `ADDON` / `FRIBB` / `RESOLVED_TVDB` / `RESOLVED_TMDB` / `RESOLVED_KITSU` | governs re-validation |

Mappings are bidirectional facts: store both `(imdb,tt12343534)→(kitsu,7442)` and the reverse. Don't try to chain — write each pair the source actually states.

### How it gets populated

1. **Catalog parse harvest (free).** Every addon item already gives us cross-refs with no extra network:
   - Netflix items → `(imdb, tt…) ↔ (tmdb, moviedb_id)`, plus `(imdb, tt…) ↔ (tvdb, tvdb_id)` on series
   - Kitsu items → `(kitsu, kitsu_id) ↔ (imdb, tt…)`
   - Anime Catalogs MAL items → `(kitsu, kitsu_id) ↔ (imdb, tt…)`
   - Marvel / Crunchyroll / Disney → `(imdb, tt…)` only — nothing to harvest beyond seeding the row entry
   - TMDB addon → `(tmdb, NNN)` (and `(tmdb,NNN)↔(imdb,tt…)` if the manifest is in IMDB-id mode)
   Tag all of these `source_of_record = ADDON`.
2. **Fribb static seed.** The 3.95 MB asset's per-record block (`recordsByKitsu`) gives a 6-way mapping (`kitsu / mal / anilist / anidb / tvdb / tmdb / imdb`) for ~22k anime. Seed all of these on first run, tagged `source_of_record = FRIBB`. After that, the router's step-2 lookup hits the persistent cache instead of re-parsing the asset.
3. **Router resolution.** When the router needs an id we don't have (e.g. live-action series → TVDB needs a TVDB id given an IMDB id, see existing [TvMetadataRouter.resolveTvdbIdentity](app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt:363)), call the provider, store the result tagged `RESOLVED_TVDB` / `RESOLVED_TMDB` / `RESOLVED_KITSU`. Same for the inverse direction the response often gives us "for free" (TVDB returns `imdb_id` in its remote-ids list, TMDB returns `external_ids`).

### TTL semantics

- **Positive hits** — keep indefinitely. Addon-supplied and Fribb-seeded entries are essentially permanent (IMDB→TVDB pairings are stable). For `RESOLVED_*` entries, "indefinitely" with an opt-in re-validation hook is fine.
- **Negative hits** — store with a 30-day TTL. If TVDB doesn't have *Solo Leveling* today and adds it next month, we want the next lookup to retry. A separate small table (or the same table with a `negative` flag and `expires_at`) is enough.

### Why this matters

The Crunchyroll row hits Fribb 10 times today, every time the user opens the home screen. With the cache, that's 10 disk reads in cold cache and then 10 in-memory `Map` lookups warm. Same for the Netflix row's `tt…→moviedb_id` enrichment — we currently re-parse it from JSON every time; with the cache, the second open reads zero from the addon's cross-refs.

---

## Part 4 — Continue-Watching parity

### The bug

Open a Crunchyroll tile (*Solo Leveling*) → start episode 1 → exit → return to home. The Continue-Watching reel shows the episode tile, but with degraded metadata vs what was on the original Crunchyroll tile: wrong/missing poster, generic description, sometimes the wrong provider's poster (TVDB instead of Kitsu) because the provider decision is being re-made from a partial id without the catalog-source context.

Two root causes in the existing pipeline:

1. **The catalog tile's addon-bundled `HomeDisplayMetadata` is never captured at click-time.** [ContinueWatchingSnapshotService](app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:941) populates `displayMetadataByItemKey` by calling `metaRepository.getMeta(parentId)` again — a second trip that hits whatever provider the router picks, with no memory of which addon row the user came from. If the second fetch returns thinner metadata than the original tile had, `mergeFallback` has nothing to fall back to.
2. **CW items reference episode ids (`tt12343534:1:1`)**. The router today doesn't normalize episode-id → parent-series-id before looking up a provider, so the Crunchyroll case fails the same way as the catalog case did before Part 1 — episode ids look like live-action `tt…` to step 2 unless we strip the suffix first.

### The fix

**4a. Normalize episode ids before routing.** `MetadataRouter.route(...)` strips the `:season:episode` suffix to get the parent id, then runs steps 1–3 unchanged:

```kotlin
fun route(rawId: String, type: ContentType): MetadataProvider {
    val parentId = rawId.substringBefore(':').let { head ->
        // For prefixed ids (kitsu:7442:1:1) keep "kitsu:7442"
        if (rawId.startsWith("$head:") && head in animePrefixes) "$head:" + rawId.removePrefix("$head:").substringBefore(':')
        else rawId.substringBefore(':')
    }
    // …existing steps 1/2/3 against parentId…
}
```
(The exact slicing is a little fiddly because `tt12343534:1:1` and `kitsu:7442:1:1` partition differently — extract a small helper `parentIdOf(rawId): String` and unit-test it directly.)

**4b. Persist provider + parent-id on `WatchProgress`.** Today `WatchProgress` records the per-episode `contentId` and `contentType`. Add two fields:
- `parentId: String` — the series-level id (`tt12343534` for an episode `tt12343534:1:1`; equal to `contentId` for movies).
- `provider: MetadataProvider` — the chosen provider at click-time.

Both get written once by the playback start path (where we already know which catalog tile was clicked and which provider its router decision returned) and never re-derived. CW renders read `provider` directly, no router call needed for known items.

**4c. Capture the originating addon-bundled metadata at click-time.** When a user clicks a catalog tile, the originating `MetaPreview` is in scope. Persist its `HomeDisplayMetadata` keyed by `parentId` into a new tiny store (or extend [ContinueWatchingSnapshotStore](app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt) which already persists `HomeDisplayMetadata`-shaped JSON). On CW render, pull this stored metadata as the fallback floor:

```kotlin
val merged = canonicalRefetch?.toHomeDisplayMetadata()
    .mergeFallback(addonBundledAtClickTime)   // ← new: never null after first play
    .mergeFallback(persistedFallback)         // ← existing fallback chain
```

Result: even when the canonical refresh fails or returns thin data, the CW tile renders with the exact metadata the user saw on the catalog tile they clicked.

**4d. Episode-id resolution shares the parent's id-mapping.** The cache from Part 3 stores parent-level mappings (`(imdb,tt12343534)→(kitsu,7442)`). When the router needs to resolve an episode id (`tt12343534:1:1`), it looks up the parent in the cache, then composes the episode-level id under the chosen provider's scheme (`kitsu:7442:1:1` for Kitsu, TVDB's per-episode endpoint for TVDB, etc.). No new cache rows per episode.

### Why this is the right shape

The current `metaRepository.getMeta(parentId)` round-trip in CW is doing two jobs at once: re-deciding the provider (which is brittle for ids like Crunchyroll's `tt…`) and re-fetching the canonical data (which is the part we actually want). Splitting them — provider decided once at click-time and persisted; metadata always falls back to addon-bundled-at-click-time — makes CW render correctly even when the canonical refetch is offline, slow, or wrong.

---

## Where it lands in the code

1. **New** `app/src/main/java/com/nexio/tv/core/routing/MetadataRouter.kt`:
   ```kotlin
   @Singleton
   class MetadataRouter @Inject constructor(
       private val animeIdMappingService: AnimeIdMappingService,
       private val idMappingStore: IdMappingStore
   ) {
       suspend fun route(itemId: String, type: ContentType): MetadataProvider {
           val parsed = AnimeStremioId.parse(itemId)
           if (parsed != null && parsed.source.isAnimePrefix()) return MetadataProvider.KITSU
           val kind = if (type == ContentType.MOVIE) ContentMediaKind.MOVIE else ContentMediaKind.SERIES
           // step 2: cache → Fribb fallback (cache wins because Fribb is already seeded into it)
           if (parsed != null) {
               val cached = idMappingStore.get(parsed.source, parsed.value, IdScheme.KITSU)
               if (cached != null) return MetadataProvider.KITSU
               animeIdMappingService.resolveKitsuId(parsed, kind)?.let { kitsuId ->
                   idMappingStore.put(parsed.source.toScheme(), parsed.value, IdScheme.KITSU, kitsuId, SourceOfRecord.FRIBB)
                   return MetadataProvider.KITSU
               }
           }
           return if (type == ContentType.SERIES || type == ContentType.TV) MetadataProvider.TVDB else MetadataProvider.TMDB
       }
   }
   enum class MetadataProvider { KITSU, TVDB, TMDB }
   ```
2. **New** `app/src/main/java/com/nexio/tv/core/routing/IdMappingStore.kt` — Room-backed store mirroring the [MetadataDiskCacheStore](app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt) pattern (or DataStore + Moshi if Room isn't already in use here — verify before deciding). Schema as defined in Part 3. Hilt-injected singleton.
3. **First-run seeding** — on app start (or first router call), if the `IdMappingStore` is empty, bulk-insert all of `AnimeIdMapAsset.recordsByKitsu` tagged `FRIBB`. After that, `AnimeIdMappingService` is only consulted as a fallback for misses (or kept in place for the schema-update story).
4. **Catalog-parse harvest** — extend [CatalogMapper.toDomain](app/src/main/java/com/nexio/tv/data/mapper/CatalogMapper.kt:8) (or the calling [CatalogRepositoryImpl](app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt:204)) to also write any cross-refs the DTO carries (`imdb_id`, `kitsu_id`, `tmdb_id`/`moviedb_id`, `tvdb_id`) into `IdMappingStore` tagged `ADDON`. Requires adding those optional fields to [MetaPreviewDto](app/src/main/java/com/nexio/tv/data/remote/dto/CatalogResponseDto.kt) (only the DTO — they don't need to surface on the domain `MetaPreview`).
5. **Resolution-result harvest** — wherever the router or the existing services successfully resolve a cross-id (e.g. [TvdbIdentityService](app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityService.kt) returning a TVDB id from an IMDB id; the TMDB `find` endpoint returning matching tmdb id from an IMDB id), write the result back into the store tagged `RESOLVED_*`.
6. **Tiny extension to** [AnimeStremioId.kt](app/src/main/java/com/nexio/tv/core/anime/AnimeStremioId.kt) — add `AnimeIdSource.isAnimePrefix()` returning true for `KITSU/MAL/ANILIST/ANIDB`.
7. **Refactor** [TvMetadataRouter.fetchEnrichment](app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt:35) to consult `MetadataRouter.route(...)` first instead of doing the inline `firstAnimeId` + `isTv()` checks. Behavioral change vs today: the new step 2 (Fribb / id-mapping lookup) routes Crunchyroll-style anime-with-`tt…`-id items to Kitsu where today they fall through to TVDB.
8. **Discover UI** — ensure rendering uses each item's per-item `type` for tile shape and detail-screen routing, not the catalog's manifest `type`. Render addon-bundled fields immediately; trigger router → canonical fetch in the background per visible tile; replace fields on success.
9. **`MetadataRouter.parentIdOf(rawId)` helper + episode-id normalization** in the router (Part 4a). Add focused unit tests for: `tt12343534:1:1 → tt12343534`, `kitsu:7442:1:1 → kitsu:7442`, `tt0036697 → tt0036697`, `tmdb:550 → tmdb:550`.
10. **`WatchProgress` schema extension** — add `parentId: String` and `provider: MetadataProvider` (Part 4b). Backfill on read for existing rows by computing `parentIdOf(contentId)` and running `MetadataRouter.route(...)` once, then writing back so subsequent reads are free.
11. **Click-time metadata capture** — wherever a catalog tile click leads into playback (likely `MetaDetailsViewModel` and the home → details navigation handoff), pass the originating `MetaPreview` through and persist its `HomeDisplayMetadata` keyed by `parentId`. Either extend [ContinueWatchingSnapshotStore](app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt) with an "originating display metadata" map or add a small sibling store.
12. **CW snapshot merge order** — update [ContinueWatchingSnapshotService.kt:941](app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:941) to insert the click-time metadata as a `mergeFallback` layer between the canonical refetch and the persisted fallback, so the addon-bundled metadata can never be lost again.

No new fields on `MetaPreview` are required for the router itself — `id` and `type` are enough. The DTO additions (`imdb_id`, `kitsu_id`, `tmdb_id`/`moviedb_id`, `tvdb_id`) exist purely to feed the harvest; they don't need to ride into the domain model.

---

## Verification

- **Router unit tests** in `app/src/test/java/com/nexio/tv/core/routing/MetadataRouterTest.kt` with one fixture per row from the survey above. Use a fake `AnimeIdMappingService` whose `resolveKitsuId` returns non-null for `tt12343534` (Jujutsu Kaisen) and null for `tt16431404` (Apex), `tt0036697` (Captain America), etc. Assert the table row-for-row.
- **IdMappingStore tests** — round-trip put/get, idempotent overwrites, `ADDON` tag never overwritten by `RESOLVED_*`, negative TTL expiry.
- **Harvest tests** — feed the literal Netflix / Kitsu / Anime-Catalogs / Crunchyroll item dumps from the survey through `CatalogMapper`, assert the expected cross-refs land in the store with the right `source_of_record`.
- **Behavioral parity check** — temporarily log `MetadataRouter.route(...)` alongside the existing `TvMetadataRouter` decision for one app session, diff the two. Expected divergence: only the Crunchyroll-style anime-with-`tt…`-id case flips from TVDB → Kitsu.
- **Smoke test on device** — install the seven surveyed addons, open Discover, confirm: (i) Disney row paints instantly with mixed movie+series tiles; (ii) tapping *Solo Leveling* logs Kitsu; (iii) tapping a Marvel tile logs TMDB; (iv) reopening the app shows zero TVDB/TMDB resolution calls for previously-seen items in the network log.
- **CW parity smoke test** — start an episode of *Solo Leveling* from the Crunchyroll row, exit, return home. Confirm: (i) the CW tile shows the same poster/title/description as the Crunchyroll row (not a TVDB-shaped fallback), (ii) tapping it resumes via the Kitsu provider in logs (no router re-decision flip), (iii) toggling airplane mode and reopening still renders correct CW tiles from the click-time-captured metadata.
- **Episode-id normalization tests** — `parentIdOf("tt12343534:1:1") == "tt12343534"`, `parentIdOf("kitsu:7442:1:1") == "kitsu:7442"`, `parentIdOf("tmdb:550") == "tmdb:550"`.

## Critical files

- [AnimeIdMappingService.kt](app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt) — already does the lookup; becomes the seed source + miss fallback
- [AnimeStremioId.kt](app/src/main/java/com/nexio/tv/core/anime/AnimeStremioId.kt) — add `isAnimePrefix()` helper
- [TvMetadataRouter.kt:35](app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt:35) — delegate to new `MetadataRouter` for the provider decision
- [TvdbIdentityService.kt](app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityService.kt) — write resolved (imdb→tvdb) pairs to `IdMappingStore`
- [TmdbService.kt](app/src/main/java/com/nexio/tv/core/tmdb/TmdbService.kt) — same, for resolved (imdb→tmdb) pairs
- [CatalogResponseDto.kt](app/src/main/java/com/nexio/tv/data/remote/dto/CatalogResponseDto.kt) — add optional `imdb_id`, `kitsu_id`, `tmdb_id`/`moviedb_id`, `tvdb_id` fields on `MetaPreviewDto`
- [CatalogMapper.kt:8](app/src/main/java/com/nexio/tv/data/mapper/CatalogMapper.kt:8) (or [CatalogRepositoryImpl.kt:204](app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt:204)) — invoke harvest at parse time
- [MetadataDiskCacheStore.kt](app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt) — pattern to mirror for the new `IdMappingStore`
- [MetaPreview.kt](app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt) — read-only; no changes
- [ContentType.kt](app/src/main/java/com/nexio/tv/domain/model/ContentType.kt) — read-only; no `ANIME` value added
- New: `app/src/main/java/com/nexio/tv/core/routing/MetadataRouter.kt`
- New: `app/src/main/java/com/nexio/tv/core/routing/IdMappingStore.kt` (+ Room entity/DAO if Room is in use; otherwise the equivalent DataStore-backed store)
- New: `app/src/test/java/com/nexio/tv/core/routing/MetadataRouterTest.kt`
- New: `app/src/test/java/com/nexio/tv/core/routing/IdMappingStoreTest.kt`
- [ContinueWatchingSnapshotService.kt:941](app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:941) — extend the `mergeFallback` chain with click-time-captured metadata
- [ContinueWatchingSnapshotStore.kt](app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt) — extend persisted shape (or add a sibling store) for originating addon-bundled metadata keyed by `parentId`
- `WatchProgress` model — add `parentId` and `provider` fields (find via `grep -rn "data class WatchProgress"`); plus the schema-migration / backfill path
- [HomeViewModelContinueWatching.kt](app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt) and [MetaDetailsViewModel.kt](app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt) — capture click-time `MetaPreview` and propagate `parentId` + `provider` into the playback handoff
