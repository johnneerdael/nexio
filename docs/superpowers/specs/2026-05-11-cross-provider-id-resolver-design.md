# Cross-Provider ID Resolver — Design

**Status:** Spec / brainstorm. Implementation plan to be drafted via `superpowers:writing-plans` once this is approved.

**Date:** 2026-05-11

**Companion documents:**
- `docs/superpowers/plans/2026-05-11-boundary-non-downgrade-enforcement.md` — the **rank-downgrade** half of the artwork problem; shipped 2026-05-11 (commits `a9ad3cacb..753e97268`). Required reading — this spec only fixes the bug it *can't* address.
- `apiblueprints/tmdb.json` — TMDB `external_ids` endpoint schema.
- `apiblueprints/tvdb.yml` — TVDB `series/{id}/extended.remoteIds[]` schema.
- `app/src/main/java/com/nexio/tv/core/anime/AnimeIdMappingService.kt` — anime map asset with imdb cross-references already loaded in memory.

## 1. Problem

UI-confirmed on 2026-05-11: every TMDB-discover rail item renders the first-paint stock poster, while the same content on a Trakt-source rail renders the RPDB premium poster. This is a wholesale rail-class regression, not a per-item one. Screenshots: `trakt.png` vs `tmdb.png`.

The 2026-05-11 boundary fix closes the rank-downgrade hole, but cannot help here: **no RESOLVED slot is ever produced for these items**, so there's nothing for the boundary to protect.

### 1.1 Why the typed authority never gets a RESOLVED slot for TMDB rows

`app/src/main/java/com/nexio/tv/data/mapper/CatalogMapper.kt:34–54` (`deriveAddonStableIds`):

```kotlin
val tmdb = trimmedId.takeIf { it.startsWith("tmdb:", …) }?.substringAfter(':')
val imdb = firstNonBlank(
    canonicalImdbTitleId(imdbId),               // from MetaPreviewDto.imdbId — TMDB-discover addon doesn't set this
    canonicalImdbTitleId(defaultVideoId),       // from behaviorHints.defaultVideoId — same
    canonicalImdbTitleId(trimmedId)             // matches only if id is `tt\d+` — TMDB ids are `tmdb:N`
)
```

For a TMDB-discover row item with `id = "tmdb:202555"`, all three imdb-derivation paths return null. `firstPaintStableIds = ProviderIds(imdb = null, tmdb = "202555")`.

Downstream consequences:
1. **Artwork pipeline can't query RPDB.** RPDB is indexed by imdb (`apiblueprints/rpdb.apib` — every endpoint requires imdb). Without an imdb id, no RPDB query fires, no premium poster overlay is created.
2. **The overlay map's alias resolver in `HomeArtworkOverlayKeys.aliasesFor` operates on `firstPaintStableIds`.** No imdb in stable ids → no imdb-form alias entries → no alias-match against an overlay that some *other* path (a Trakt rail for the same content) registered under `movie:imdb:tt12345`.
3. **All TMDB rails render `MetaPreview.poster`** (the addon-provided TMDB stock URL) forever. Same for kitsu-only rails (no imdb in MetaPreview), generic addon rails that don't yield imdb natively, and any future provider that doesn't piggyback on the stremio imdb convention.

### 1.2 Trakt rails work by accident

The Trakt addon yields items with stremio-standard imdb IDs (`id = "tt12345"`) in the item's primary `id` field. `canonicalImdbTitleId(trimmedId)` matches the `tt\d+` regex, `firstPaintStableIds.imdb` is populated, RPDB query succeeds, overlay lands. Trakt's correctness is a side-effect of the addon's id-naming convention, not the result of any cross-provider mapping in the app.

## 2. Goals

1. **Every rail item, regardless of source provider, carries the canonical imdb id by the time it reaches the artwork pipeline.** This restores RPDB queryability for TMDB rails, addon rails that yield tmdb/tvdb/kitsu ids, and any future provider that doesn't yield imdb natively.
2. **Cross-provider mapping is cached on disk** so the second cold-start onwards does zero network for already-resolved content. The 2026-05-09 ANR investigation memory (`project_anr_fix_anime_id_map_2026_05_11.md`) and CLAUDE.md rule #3 mandate: large persistent state goes in file-backed streaming JSON, not SharedPreferences / DataStore.
3. **No first-paint regression.** Producers must still emit rail items immediately on addon response. If the imdb id isn't cached yet, the row emits without it (current behavior); a background resolver enriches and the artwork pipeline re-runs once the imdb is available.
4. **No new ANR class.** Resolution is IO-dispatched. The producer thread never blocks on a network call.

## 3. Non-goals

- Replacing the existing `AnimeIdMappingService` (already loaded once at startup; carries 290k+ anime cross-references including imdb). We **consume** it for kitsu items; we do not duplicate it.
- Fixing the boundary-rank downgrade — already shipped today.
- Re-keying the existing `HydratedHomeOverlayStore`. The store already supports multi-alias storage (per `HomeViewModelCatalogPipeline.kt:691–697` comment). The problem is that the alias set built at row-consumption time is too narrow; we fix that upstream.

## 4. Architecture

### 4.1 Resolution policy (user-directive)

Per the user's directive on 2026-05-11:

- **`mediaKind = movie`**: any non-imdb id → resolve imdb through **TMDB**. Endpoint: `GET /3/movie/{tmdb_id}/external_ids` returns `imdb_id` (string, may be null for very obscure content).
- **`mediaKind = series` / `tv`**: any non-imdb id → resolve imdb through **TVDB**. Endpoint: `GET /series/{tvdb_id}/extended` returns `remoteIds: List<RemoteID>` — find the entry whose source name maps to IMDB.
- **kitsu source (any mediaKind)** — never query the network. Consult `AnimeIdMappingService.recordForKitsuId(kitsuId)` which returns an `AnimeIdMapRecord` already carrying `imdb`, `tmdb`, `tvdb`. The anime map is loaded once at app startup (the streaming-moshi load shipped in commit `45984efc7`), so kitsu resolution is in-memory O(1) lookup.
- **Already-imdb input** (`id = "tt..."` or `imdb:tt...`) — identity, no resolution needed.

### 4.2 Two-hop resolution: when the input isn't TMDB / TVDB / kitsu

Stremio addons may yield items keyed by arbitrary provider prefixes (e.g., `mdblist:...`, `simkl:...`, `trakt:...` for movies that lack an imdb id natively). For these:

- **First hop:** ask the source-provider's API for its own cross-id table (`mdblist` exposes imdb in the item payload; `simkl` exposes via `/sync/all-items` ids field; `trakt` exposes via `/search/imdb`).
- **Second hop (if first hop returned tmdb/tvdb but not imdb):** fall through to the policy-canonical resolver (TMDB for movies, TVDB for series).

The blueprints to consult per source:
- `apiblueprints/mdblist.apib` — already in cache; mdblist item responses include `imdb_id` directly.
- `apiblueprints/simkl.apib` — `ids: { imdb, tmdb, tvdb }` on each item.
- `apiblueprints/trakt.apib` — `ids: { trakt, slug, imdb, tmdb, tvdb }` on every standard list item.

For each source, the resolver provides a `resolveFromSource(rawId, sourceContext)` adapter that knows how to pull imdb out of that source's own response shape.

### 4.3 Components

#### 4.3.1 `CrossProviderIdResolver` (new singleton)

```
@Singleton
class CrossProviderIdResolver @Inject constructor(
    private val tmdbExternalIds: TmdbExternalIdsClient,
    private val tvdbExtendedClient: TvdbExtendedClient,
    private val animeIdMappingService: AnimeIdMappingService,
    private val mdblistClient: MdbListClient,           // existing
    private val simklTrackingClient: SimklTrackingClient, // existing
    private val traktClient: TraktClient,               // existing
    private val cache: CrossProviderIdCache,
    private val scope: CoroutineScope                   // appScope (Dispatchers.IO)
) {
    /**
     * Cache-only sync lookup. Returns the cached ProviderIds for the input
     * (mediaKind, sourceProvider, sourceId), or null on miss. Never queries
     * the network. Safe to call from the producer hot path.
     */
    fun lookupSync(mediaKind: ContentMediaKind, providerIds: ProviderIds): ProviderIds?

    /**
     * Fires async resolution if the entry isn't cached. Returns immediately
     * with the cached result (or whatever's already known from the input).
     * Schedules a network query on `scope` (IO); on success, stores the
     * result and emits a refresh signal via [resolutionUpdates].
     */
    fun resolveAsync(mediaKind: ContentMediaKind, providerIds: ProviderIds): ProviderIds

    /**
     * Hot Flow that emits the union of newly-resolved entries since the
     * subscriber started collecting. Producers/refresh coordinators
     * subscribe to trigger a re-emission of affected rails.
     */
    val resolutionUpdates: SharedFlow<CrossIdResolutionEvent>
}
```

`CrossIdResolutionEvent` carries the `(input providerIds, resolved providerIds)` pair so subscribers can decide which rails to re-emit.

#### 4.3.2 `CrossProviderIdCache` (disk-backed, streaming JSON)

Storage path: `filesDir/cross-provider-ids-v1/p<profileId>.json`. Per CLAUDE.md rule #3:

- Write via `FileOutputStream` → `BufferedWriter` → `JsonWriter` (streaming). Atomic `Files.move` rename.
- Read via `FileInputStream` → `BufferedReader` → `JsonReader`. No `gson.fromJson(rawString, type)`, no `readText()`.
- Per-profile to allow profile-scoped caching (some providers' content libraries are profile-tied).

Persisted shape:

```json
{
  "schemaVersion": 1,
  "entries": [
    {
      "key": "movie|tmdb|202555",
      "imdb": "tt12345",
      "tmdb": "202555",
      "tvdb": null,
      "trakt": "987",
      "kitsu": null,
      "resolvedAtMs": 1700000000000,
      "source": "TMDB_EXTERNAL_IDS"
    }
  ]
}
```

`key = "{mediaKind.tag}|{primarySourceProvider}|{primarySourceId}"` is the canonical cache key. A successful resolution writes ONE entry; lookups by ANY of the carried provider ids (imdb / tmdb / tvdb / trakt / kitsu) hit the same row via an in-memory secondary index built at load time.

Cache size estimate: 290k anime entries are already in `AnimeIdMappingService` and are NOT duplicated here. Non-anime: ~10k–30k unique titles in active rotation per heavy user. At ~150 bytes per entry, the file caps at ~5 MiB worst case — well below the 50 KB SharedPreferences ban and easily streamable.

Cache invalidation: entries do not expire by default. Cross-id mappings are stable per content (a TMDB id never re-points to a different IMDB id). A periodic `WorkManager` job (weekly) walks the cache and re-validates entries whose `resolvedAtMs` is older than 30 days, evicting any that fail to re-resolve.

#### 4.3.3 `TmdbExternalIdsClient` (new — thin Retrofit interface)

```
interface TmdbExternalIdsApi {
    @GET("3/movie/{movie_id}/external_ids")
    suspend fun movieExternalIds(@Path("movie_id") tmdbId: String): TmdbExternalIdsDto

    @GET("3/tv/{series_id}/external_ids")
    suspend fun seriesExternalIds(@Path("series_id") tmdbId: String): TmdbExternalIdsDto
}

@Serializable
data class TmdbExternalIdsDto(
    val imdb_id: String?,
    val tvdb_id: Int?,
    val tvrage_id: Int? = null,
    val freebase_mid: String? = null,
    val wikidata_id: String? = null,
    val facebook_id: String? = null,
    val instagram_id: String? = null,
    val twitter_id: String? = null
)
```

Endpoint paths confirmed against `apiblueprints/tmdb.json`. Wire via the existing Retrofit `TmdbApi` module (auth, timeouts, rate-limit handling are all reused).

#### 4.3.4 `TvdbExtendedClient` (new — thin Retrofit interface)

```
interface TvdbExtendedApi {
    @GET("series/{id}/extended")
    suspend fun seriesExtended(@Path("id") tvdbId: String): TvdbSeriesExtendedDto

    @GET("movies/{id}/extended")
    suspend fun moviesExtended(@Path("id") tvdbId: String): TvdbMoviesExtendedDto
}

@Serializable
data class TvdbSeriesExtendedDto(
    val data: TvdbSeriesExtendedData
)

@Serializable
data class TvdbSeriesExtendedData(
    val id: Long,
    val name: String,
    val remoteIds: List<TvdbRemoteId> = emptyList()
)

@Serializable
data class TvdbRemoteId(
    val id: String,
    val type: Int,         // TVDB enum — IMDB = 2, see apiblueprints/tvdb.yml RemoteID schema
    val sourceName: String? = null
)
```

Endpoint paths confirmed against `apiblueprints/tvdb.yml`. Existing `TvdbApi` Retrofit module supplies auth + timeouts.

### 4.4 Where the resolver is invoked

The clean integration point is **at the artwork pipeline boundary**, NOT at `CatalogMapper`. Three reasons:

1. **CatalogMapper is purely sync.** Adding an async re-emit hook there means rewiring the producer pipeline (already a delicate seam, per `project_plan_b_session_2026_05_09.md` death-spiral memory). Avoid.
2. **The artwork pipeline already needs imdb for RPDB anyway.** It's the natural place to fan out to cross-id resolution.
3. **`firstPaintStableIds` doesn't need to change shape.** The artwork pipeline can attach the resolved imdb id to the overlay it produces. The overlay alias set then automatically extends; the row-side `HomeArtworkOverlayKeys.aliasesFor` already consults `firstPaintStableIds` AND the overlay's `canonicalProvider` / `canonicalId` (lines 23–35 of `HomeArtworkOverlayKeys.kt`).

Wait — re-reading `HomeArtworkOverlayKeys.aliasesFor`, the second `addStableAliases` call only fires for the overlay's canonical provider, not for a richer ProviderIds. And `overlayFromMap` (the row-consumer) passes `canonicalProvider = null, canonicalId = null`. So extending the overlay alone doesn't help the row-side lookup.

**Revised integration:** the resolver is invoked at BOTH sides:

- **Artwork pipeline side** — before querying RPDB, the pipeline asks the resolver for the full ProviderIds. If the resolver returns imdb (cached or freshly resolved), RPDB query proceeds. The overlay is stored under all known alias forms.
- **Row consumer side (`overlayFromMap`)** — the `aliasesFor` call gets the row's enriched ProviderIds (resolver `lookupSync` → cache-only, fast path). If the cache has imdb for this row's tmdb/kitsu id, the alias set includes the imdb form and the lookup matches an overlay stored under it.

This dual-call pattern is cheap because the second call hits a hot in-memory cache; no network, no IO. And it's correct because both sides converge on the same `ProviderIds` for the same content.

### 4.5 Re-emission after async resolution

When the resolver completes an async resolution that produced a new imdb id, downstream pipelines need to re-emit:

- `HomeViewModelCatalogPipeline.scheduleUpdateCatalogRows()` already debounces re-emissions. The resolver's `resolutionUpdates` flow is collected in the catalog pipeline's startup; on each event, it calls `scheduleUpdateCatalogRows`.
- `HomeViewModelCatalogPipeline.hydratedHomeOverlayStore.observeForItemKeys` already re-collects whenever its key set changes. When the artwork pipeline re-writes an overlay under newly-known alias forms, this Flow emits, and the catalog pipeline updates.

The boundary fix from earlier today guarantees that this re-emission doesn't cause first-paint flicker — the typed authority's RESOLVED slot dominates whatever transient FIRST_PAINT slot the re-emit carries.

## 5. Storage / cache / perf accounting

The user's directive flagged storage/cache/perf as concerns. Quantifying:

| Concern | Impact | Mitigation |
|---|---|---|
| Disk footprint | ~5 MiB cap on `cross-provider-ids-v1/p<profileId>.json` even for power users (excluding anime — separate map already on disk). | Streaming JSON read/write per CLAUDE.md rule #3. No SharedPreferences. No DataStore. |
| Heap footprint | In-memory cache + secondary indexes. At 10k entries × ~250 bytes = 2.5 MiB heap. | Cap the in-memory index to 5k most-recently-used entries (LRU). Disk is the source of truth. |
| Cold-start cost | First read after process launch reads the file once. | File is read on `Dispatchers.IO` in `NexioApplication.onCreate`'s `appScope` block alongside the anime-id-map warm. Doesn't block first paint. |
| Network cost | TMDB external_ids: one GET per unique tmdb id; TVDB extended: one GET per unique tvdb id. Steady-state: zero for repeat sessions (cached). | Persistent cache. Bulk endpoint use where available (TMDB doesn't have batch external_ids; TVDB's extended is per-series). |
| Allocation rate during steady state | Resolver only allocates on resolution; cache lookup is hash-map indexed and allocation-free for hit path. | Indexed-for loops over the entry list during the secondary-index build (CLAUDE.md rule #4). |
| ANR risk | All network IO on `appScope` IO dispatcher. Producer hot path uses `lookupSync` only. | Resolution latency for first-ever encounter of a content: ~200 ms on a healthy network; cached forever after. |
| GC churn | Resolver result is a small `ProviderIds` data class. Memoize: the resolver returns the same instance for the same input on repeated `lookupSync` calls. | Reference-equality preserves downstream cache hits. |

## 6. Migration plan (outline — fleshed out in the implementation plan)

1. **Add `CrossProviderIdCache` + disk format** (streaming JSON). TDD: in-memory entries round-trip through disk; secondary index lookups work in all five id directions.
2. **Add `TmdbExternalIdsClient`** + DTOs + Retrofit wiring. TDD: real-ish DTO with sample TMDB response from `apiblueprints/tmdb.json` deserializes correctly.
3. **Add `TvdbExtendedClient`** + DTOs + Retrofit wiring. TDD: sample TVDB extended response from `apiblueprints/tvdb.yml` deserializes; `RemoteID` array correctly resolves imdb via `sourceName == "IMDB"`.
4. **Add `CrossProviderIdResolver` singleton.** Wire all three clients + `AnimeIdMappingService` + cache + appScope. TDD: (a) kitsu input resolves via anime map (no network); (b) tmdb-only movie input resolves via TMDB external_ids; (c) tvdb-only series input resolves via TVDB extended; (d) tmdb-only series input two-hops via TMDB external_ids → TVDB extended; (e) cache hit short-circuits all of the above; (f) `lookupSync` never blocks.
5. **Wire resolver into the artwork pipeline (RPDB query path).** Before querying RPDB, ask the resolver. If imdb is freshly available, the overlay is stored under all alias forms (extend `HydratedHomeOverlay.itemKey` to include aliasKeys list, OR write multiple overlay entries — TBD per existing store shape).
6. **Wire resolver into `HomeArtworkOverlayKeys.aliasesFor` / `overlayFromMap`.** Before computing aliases, query the resolver's sync cache to enrich `providerIds` with any known cross-ids.
7. **Wire `resolutionUpdates` into `HomeViewModelCatalogPipeline`** so async resolutions trigger row re-emit.
8. **On-device verification**: Daredevil (Trakt rail ✓ today) and the same Daredevil on a TMDB-source rail. Both must render RPDB premium after the boundary fix + resolver land.

## 7. Open questions

1. **Cache invalidation:** weekly `WorkManager` re-validation as proposed, or simpler "never invalidate; let the schema bump on the few months when TMDB renumbers something" approach? Vote: never-invalidate. Cross-provider id mappings change roughly zero times per year. Save the WorkManager schedule.
2. **TVDB rate limit:** TVDB free tier is heavily rate-limited. Do we need a separate per-second throttler in `TvdbExtendedClient`, or rely on existing `TvdbApi` shared limits? Vote: reuse existing — TVDB extended calls are bursty during initial home population, but the cache makes steady state zero. Existing rate limit handles bursts.
3. **Two-hop resolution for `series:tmdb:N`:** confirm TMDB returns tvdb_id reliably in `external_ids` for newer series. Spot-check across `apiblueprints/tmdb.json` — yes, `tvdb_id` is part of the documented response schema. Two-hop is robust.
4. **What about `mediaKind = movie + tvdb:N`?** Per user directive, movies resolve via TMDB. TVDB→TMDB is a less-common path. Resolver should: TVDB extended → look for tmdb in `remoteIds` → TMDB external_ids for the verification → imdb. Or fall back to identity (use whatever tmdb the addon already provided). Default to fall-back, only escalate to TMDB hop if remoteIds yields nothing.
5. **Generic addon ids that don't match any known provider prefix:** these are stremio-style addon ids like `cinemeta:tt12345` (which IS imdb under the hood) or `kitsu:12345`. The resolver's input adapter should normalize the prefix before lookup — `cinemeta:tt12345` → recognize tt-shape → identity-imdb resolution. List of recognized prefixes to seed: `tt`, `imdb:`, `tmdb:`, `tvdb:`, `trakt:`, `kitsu:`, `mal:`, `anilist:`, `anidb:`, `mdblist:`, `simkl:`, `cinemeta:`. Anything else: warn + fall back to title-search (out of scope for v1).

## 8. Acceptance criteria

The feature is complete when, after a fresh cold-start with profile selection (CLAUDE.md rule #8 smoke-test sequence):

1. ✅ A Daredevil Born Again row item appears identically on:
   - Trakt-source rail (imdb-native) — RPDB premium poster
   - TMDB-discover rail (tmdb-native) — RPDB premium poster (was: TMDB stock)
   - Kitsu-anime rail (if applicable) — RPDB premium poster (was: kitsu cover)
   - Any other addon rail that yielded the same content
2. ✅ `cross-provider-ids-v1/p<id>.json` exists on disk after first session; second cold-start shows zero network calls to TMDB external_ids / TVDB extended for already-resolved content.
3. ✅ Heap acceptance: in-memory resolver cache ≤ 2.5 MiB; no new dominator entries; AnimeIdMappingService unchanged at 14.94 MiB.
4. ✅ GC pattern at steady state unchanged from current baseline (24 s+ idle gaps between nexio GCs); resolver does not contribute to the steady-state gson reflection churn we already see (separate fix).
5. ✅ No new ANR class introduced. Async resolution latency invisible to the user.
6. ✅ All boundary regression tests from the 2026-05-11 boundary fix still pass.

## 9. Next steps

Once this spec is approved:

- Invoke `superpowers:writing-plans` with this design as input.
- Plan will produce ~10–12 tasks (TDD-anchored): cache file format → in-memory cache + secondary indexes → TMDB client → TVDB client → resolver core → resolver integration with anime map + per-source adapters → artwork-pipeline integration → row-consumer integration → resolutionUpdates re-emit wiring → on-device verification → memory entry.
- Each task ≤ 1 commit. Subagent-driven execution per the model used today.
