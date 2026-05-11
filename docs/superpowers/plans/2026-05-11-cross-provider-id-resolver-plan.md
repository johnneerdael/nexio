# Cross-Provider ID Resolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every rail item — regardless of source addon (Trakt, TMDB-discover, addon, Kitsu) — carries an imdb id in `firstPaintStableIds` by the time the artwork pipeline runs, so RPDB premium artwork resolves for all sources and not just imdb-native addons.

**Architecture:** Leverage the existing `StableIdBundleResolver` (already implements movie→imdb-via-TMDB, series→imdb-via-TVDB, kitsu-via-AnimeIdMappingService, and persists via Room-backed `LocalIdMappingStore`). The gap is that `CatalogMapper.toDomain()` doesn't invoke the resolver and ships items with empty cross-provider IDs. This plan adds (a) a sync cache-only enrichment step at row construction, (b) a background async fill that walks unenriched items and triggers resolver lookups, and (c) a consumer-side alias enricher so overlays stored under any one provider key match rows keyed by another.

**Tech Stack:** Kotlin · Hilt · Coroutines/Flow · Room · Retrofit · JUnit4 · Mockk

**Spec:** `docs/superpowers/specs/2026-05-11-cross-provider-id-resolver-design.md` (read this first).

**Companion fix shipped 2026-05-11:** Boundary non-downgrade enforcement (commits `a9ad3cacb..2754f166c`). This plan layers on top; it does NOT modify the boundary fix.

---

## File Structure

### New files
- `app/src/main/java/com/nexio/tv/data/mapper/CatalogItemCrossIdEnricher.kt` — Singleton; sync cache-only `enrichFromCache(meta)` + async `enrichResolving(meta)`; the existing `StableIdBundleResolver`/`AnimeIdMappingService`/`IdMappingStore` are dependencies.
- `app/src/test/java/com/nexio/tv/data/mapper/CatalogItemCrossIdEnricherTest.kt` — unit tests covering tmdb→imdb (movie), tvdb→imdb (series), kitsu→imdb (anime map), already-imdb passthrough, unknown-provider passthrough, async resolution + cache fill, reference stability.
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeArtworkOverlayKeysCrossIdTest.kt` — verifies `aliasesFor` enriches `providerIds` from the cache and produces the imdb-form alias for a tmdb-only row.

### Modified files
- `app/src/main/java/com/nexio/tv/data/mapper/CatalogMapper.kt` — `toDomain()` becomes a suspend extension taking the enricher, calls `enrichFromCache` synchronously, returns enriched `MetaPreview`.
- `app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt` — passes enricher into `toDomain()`; after the synchronous batch, schedules `enrichResolving` for items still missing imdb.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeArtworkOverlayKeys.kt` — `aliasesFor` becomes suspend (or takes an `IdMappingStore` lookup callback) so it can enrich `providerIds` before computing aliases.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt` — `overlayFromMap` propagates the enriched alias path through.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` — subscribes to enricher's `resolutionUpdates` Flow; calls `scheduleUpdateCatalogRows` when items get enriched, so the home pipeline re-emits with cross-provider IDs filled in.

---

## Task 1: Failing TDD anchor — `CatalogItemCrossIdEnricher` cache-only sync path

**Files:**
- Create: `app/src/test/java/com/nexio/tv/data/mapper/CatalogItemCrossIdEnricherTest.kt`

- [ ] **Step 1: Read the existing real types** so the test fixtures use the actual constructors:

```bash
sed -n '1,40p' app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt
sed -n '1,50p' app/src/main/java/com/nexio/tv/core/metadata/router/IdMappingStore.kt
grep -n "^data class\|^class\|^enum class" app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt | head -20
sed -n '40,80p' app/src/main/java/com/nexio/tv/core/metadata/router/IdMappingStore.kt
```

Note the actual signatures of `IdMapping`, `MetadataPrimaryProvider`, `ParsedMetadataId`, `IdMappingSource`, and `AnimeIdScheme`. Test fixtures must use the real shapes — do not invent.

- [ ] **Step 2: Write the failing test file**

```kotlin
package com.nexio.tv.data.mapper

import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.metadata.router.AnimeIdScheme
import com.nexio.tv.core.metadata.router.IdMapping
import com.nexio.tv.core.metadata.router.IdMappingSource
import com.nexio.tv.core.metadata.router.InMemoryIdMappingStore
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ParsedMetadataId
import com.nexio.tv.core.metadata.router.StableIdBundleResolver
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CatalogItemCrossIdEnricherTest {

    private val nowMs = 1_700_000_000_000L

    private fun tmdbMoviePreview(): MetaPreview = MetaPreview(
        id = "tmdb:202555",
        type = ContentType.MOVIE,
        rawType = "movie",
        name = "Daredevil: Born Again",
        poster = "https://image.tmdb.org/p/p1.jpg",
        posterShape = PosterShape.REGULAR,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2025",
        runtime = null,
        imdbRating = null,
        genres = emptyList(),
        trailerYtIds = emptyList(),
        language = null,
        firstPaintStableIds = ProviderIds(imdb = null, tmdb = "202555")
    )

    private fun kitsuPreview(): MetaPreview = MetaPreview(
        id = "kitsu:12345",
        type = ContentType.SERIES,
        rawType = "series",
        name = "Anime Show",
        poster = "https://kitsu.example/p.jpg",
        posterShape = PosterShape.REGULAR,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2023",
        runtime = null,
        imdbRating = null,
        genres = emptyList(),
        trailerYtIds = emptyList(),
        language = null,
        firstPaintStableIds = ProviderIds(kitsu = "12345")
    )

    @Test
    fun `tmdb movie with cached imdb mapping returns enriched MetaPreview`() = runTest {
        val store = InMemoryIdMappingStore(
            initialMappings = listOf(
                IdMapping(
                    sourceId = ParsedMetadataId(AnimeIdScheme.TMDB, "movie:202555", "tmdb:movie:202555"),
                    provider = MetadataPrimaryProvider.IMDB,
                    providerId = "tt12345",
                    source = IdMappingSource.PROVIDER_LOOKUP,
                    evidence = "tmdbMovieToImdb"
                )
            )
        )
        val enricher = CatalogItemCrossIdEnricher(
            idMappingStore = store,
            stableIdBundleResolver = throwingResolver(),
            animeIdMappingService = AnimeIdMappingService { AnimeIdMapAsset(schemaVersion = 0) }
        )
        val preview = tmdbMoviePreview()

        val enriched = enricher.enrichFromCache(preview)

        assertEquals("tt12345", enriched.firstPaintStableIds.imdb)
        assertEquals("202555", enriched.firstPaintStableIds.tmdb)
        assertNotSame(preview, enriched)
    }

    @Test
    fun `tmdb movie with no cached mapping returns preview verbatim and does not query network`() = runTest {
        val emptyStore = InMemoryIdMappingStore()
        val resolverCalls = mutableListOf<Any>()
        val enricher = CatalogItemCrossIdEnricher(
            idMappingStore = emptyStore,
            stableIdBundleResolver = recordingResolver(resolverCalls),
            animeIdMappingService = AnimeIdMappingService { AnimeIdMapAsset(schemaVersion = 0) }
        )
        val preview = tmdbMoviePreview()

        val enriched = enricher.enrichFromCache(preview)

        assertNull(enriched.firstPaintStableIds.imdb)
        assertSame("cache miss must return the same MetaPreview reference", preview, enriched)
        assertEquals("enrichFromCache must not call the resolver (network)", 0, resolverCalls.size)
    }

    @Test
    fun `kitsu preview resolves imdb synchronously via in-memory anime map`() = runTest {
        val animeMap = AnimeIdMappingService {
            AnimeIdMapAsset(
                schemaVersion = 1,
                identityRecordsByKitsu = mapOf(
                    "12345" to AnimeIdMapRecord(
                        kitsu = "12345",
                        imdb = "tt99999",
                        tmdb = null,
                        tvdb = "555",
                        mal = null,
                        anilist = null,
                        anidb = null,
                        mediaType = "series",
                        sourceType = "tv"
                    )
                )
            )
        }
        val enricher = CatalogItemCrossIdEnricher(
            idMappingStore = InMemoryIdMappingStore(),
            stableIdBundleResolver = throwingResolver(),
            animeIdMappingService = animeMap
        )
        val preview = kitsuPreview()

        val enriched = enricher.enrichFromCache(preview)

        assertEquals("tt99999", enriched.firstPaintStableIds.imdb)
        assertEquals("555", enriched.firstPaintStableIds.tvdb)
        assertEquals("12345", enriched.firstPaintStableIds.kitsu)
    }

    @Test
    fun `already-imdb preview is returned unchanged with reference equality`() = runTest {
        val enricher = CatalogItemCrossIdEnricher(
            idMappingStore = InMemoryIdMappingStore(),
            stableIdBundleResolver = throwingResolver(),
            animeIdMappingService = AnimeIdMappingService { AnimeIdMapAsset(schemaVersion = 0) }
        )
        val preview = tmdbMoviePreview().copy(
            id = "tt12345",
            firstPaintStableIds = ProviderIds(imdb = "tt12345")
        )

        val enriched = enricher.enrichFromCache(preview)

        assertSame("imdb already present → no change, ref-equal", preview, enriched)
    }

    @Test
    fun `unrecognized provider prefix is returned verbatim, no resolver call`() = runTest {
        val resolverCalls = mutableListOf<Any>()
        val enricher = CatalogItemCrossIdEnricher(
            idMappingStore = InMemoryIdMappingStore(),
            stableIdBundleResolver = recordingResolver(resolverCalls),
            animeIdMappingService = AnimeIdMappingService { AnimeIdMapAsset(schemaVersion = 0) }
        )
        val preview = tmdbMoviePreview().copy(
            id = "mystery:abc",
            firstPaintStableIds = ProviderIds()
        )

        val enriched = enricher.enrichFromCache(preview)

        assertSame(preview, enriched)
        assertEquals(0, resolverCalls.size)
    }

    private fun throwingResolver(): StableIdBundleResolver =
        StableIdBundleResolver(
            idMappingStore = InMemoryIdMappingStore(),
            lookup = object : StableIdBundleResolver.Lookup {
                override suspend fun tmdbMovieToImdb(tmdbId: String): String? = error("not expected")
                override suspend fun imdbToTmdbMovie(imdbId: String): String? = error("not expected")
                override suspend fun tmdbTvToTvdb(tmdbId: String): String? = error("not expected")
                override suspend fun tmdbTvToImdb(tmdbId: String): String? = error("not expected")
                override suspend fun imdbToTvdbSeries(imdbId: String): String? = error("not expected")
                override suspend fun tvdbSeriesToImdb(tvdbId: String): String? = error("not expected")
            }
        )

    private fun recordingResolver(calls: MutableList<Any>): StableIdBundleResolver =
        StableIdBundleResolver(
            idMappingStore = InMemoryIdMappingStore(),
            lookup = object : StableIdBundleResolver.Lookup {
                override suspend fun tmdbMovieToImdb(tmdbId: String): String? { calls += "tmdbMovieToImdb:$tmdbId"; return null }
                override suspend fun imdbToTmdbMovie(imdbId: String): String? { calls += "imdbToTmdbMovie:$imdbId"; return null }
                override suspend fun tmdbTvToTvdb(tmdbId: String): String? { calls += "tmdbTvToTvdb:$tmdbId"; return null }
                override suspend fun tmdbTvToImdb(tmdbId: String): String? { calls += "tmdbTvToImdb:$tmdbId"; return null }
                override suspend fun imdbToTvdbSeries(imdbId: String): String? { calls += "imdbToTvdbSeries:$imdbId"; return null }
                override suspend fun tvdbSeriesToImdb(tvdbId: String): String? { calls += "tvdbSeriesToImdb:$tvdbId"; return null }
            }
        )
}
```

If any constructor/signature differs from the real shape after the Step 1 read, adjust the test fixtures to match — do not adjust assertion semantics.

- [ ] **Step 3: Run tests — verify compile failure**

```bash
./gradlew :app:compileUniversalDebugUnitTestKotlin 2>&1 | tail -10
```

Expected: FAIL with "unresolved reference: CatalogItemCrossIdEnricher". This proves the test exists before any implementation lands.

- [ ] **Step 4: Commit the failing tests**

```bash
git add app/src/test/java/com/nexio/tv/data/mapper/CatalogItemCrossIdEnricherTest.kt
git commit -m "$(cat <<'EOF'
test(catalog/cross-id): failing TDD anchor — CatalogItemCrossIdEnricher

Multi-source enricher behavior tests covering:
- tmdb movie with cached imdb mapping → enriched
- cache miss → ref-equal preview returned, no resolver call
- kitsu preview → in-memory anime map resolution
- already-imdb → unchanged
- unrecognized provider prefix → unchanged

Intentionally fails until CatalogItemCrossIdEnricher exists.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Implement `CatalogItemCrossIdEnricher` (sync cache-only path)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/mapper/CatalogItemCrossIdEnricher.kt`

- [ ] **Step 1: Write the implementation**

```kotlin
package com.nexio.tv.data.mapper

import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.metadata.router.AnimeIdScheme
import com.nexio.tv.core.metadata.router.IdMappingStore
import com.nexio.tv.core.metadata.router.MetadataIdParser
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ParsedMetadataId
import com.nexio.tv.core.metadata.router.StableIdBundleResolver
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.metadata.router.StableIdBundleRequest
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Enriches a [MetaPreview]'s `firstPaintStableIds` with cross-provider IDs
 * resolved via the existing StableIdBundleResolver / IdMappingStore / anime
 * map infrastructure.
 *
 * Per the 2026-05-11 design spec: movies resolve imdb through TMDB
 * external_ids; series resolve imdb through TVDB extended remoteIds; kitsu
 * resolves via the in-memory AnimeIdMappingService (no network).
 *
 * Two entry points:
 *  - [enrichFromCache] — sync, cache-only. Safe to call on the producer hot
 *    path. Returns the original [MetaPreview] reference on cache miss for
 *    reference stability.
 *  - [enrichResolving] — async. Fires the StableIdBundleResolver, which may
 *    query TMDB/TVDB. On success, the mapping is persisted via
 *    IdMappingStore and [resolutionUpdates] emits the resolved provider IDs.
 */
@Singleton
class CatalogItemCrossIdEnricher @Inject constructor(
    private val idMappingStore: IdMappingStore,
    private val stableIdBundleResolver: StableIdBundleResolver,
    private val animeIdMappingService: AnimeIdMappingService
) {
    private val _resolutionUpdates = MutableSharedFlow<CrossIdResolutionEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val resolutionUpdates: SharedFlow<CrossIdResolutionEvent> = _resolutionUpdates.asSharedFlow()

    suspend fun enrichFromCache(preview: MetaPreview): MetaPreview {
        if (preview.firstPaintStableIds.imdb?.isNotBlank() == true) return preview
        val parsed = MetadataIdParser.parse(preview.id)
        val mediaKind = preview.type.toMediaKind() ?: return preview

        return when (parsed.scheme) {
            AnimeIdScheme.KITSU -> enrichFromAnimeMap(preview, parsed, mediaKind)
            AnimeIdScheme.TMDB -> enrichTmdbFromStore(preview, parsed, mediaKind)
            AnimeIdScheme.TVDB -> enrichTvdbFromStore(preview, parsed, mediaKind)
            AnimeIdScheme.IMDB -> preview  // already imdb
            else -> preview                  // unknown provider, leave alone
        }
    }

    suspend fun enrichResolving(preview: MetaPreview): MetaPreview {
        val cached = enrichFromCache(preview)
        if (cached !== preview) return cached  // cache hit already enriched

        val parsed = MetadataIdParser.parse(preview.id)
        val mediaKind = preview.type.toMediaKind() ?: return preview
        if (parsed.scheme == AnimeIdScheme.IMDB || parsed.scheme == AnimeIdScheme.KITSU) {
            return preview  // imdb=no-op; kitsu=anime map already exhausted in cache pass
        }

        val routeProvider = when (parsed.scheme) {
            AnimeIdScheme.TMDB -> MetadataPrimaryProvider.TMDB
            AnimeIdScheme.TVDB -> MetadataPrimaryProvider.TVDB
            else -> return preview
        }
        val sourceProvider = when (parsed.scheme) {
            AnimeIdScheme.TMDB -> ProviderId.TMDB
            AnimeIdScheme.TVDB -> ProviderId.TVDB
            else -> null
        }

        val bundle = stableIdBundleResolver.resolve(
            StableIdBundleRequest(
                itemKey = "${preview.apiType}:${preview.id}",
                itemType = preview.type,
                routeProvider = routeProvider,
                knownIds = preview.firstPaintStableIds,
                sourceProvider = sourceProvider,
                sourceItemId = preview.id,
                railId = null,
                trigger = StableIdResolutionTrigger.RAIL_ITEM_ENRICHMENT
            )
        )

        val enrichedIds = preview.firstPaintStableIds.copy(
            imdb = preview.firstPaintStableIds.imdb ?: bundle.sidecars.imdbId,
            tmdb = preview.firstPaintStableIds.tmdb ?: bundle.canonical.tmdbMovieId,
            tvdb = preview.firstPaintStableIds.tvdb ?: bundle.canonical.tvdbSeriesId,
            kitsu = preview.firstPaintStableIds.kitsu ?: bundle.canonical.kitsuAnimeId
        )
        if (enrichedIds == preview.firstPaintStableIds) return preview

        val enriched = preview.copy(firstPaintStableIds = enrichedIds)
        _resolutionUpdates.tryEmit(
            CrossIdResolutionEvent(
                itemKey = "${preview.apiType}:${preview.id}",
                from = preview.firstPaintStableIds,
                to = enrichedIds
            )
        )
        return enriched
    }

    private suspend fun enrichTmdbFromStore(
        preview: MetaPreview,
        parsed: ParsedMetadataId,
        mediaKind: ContentMediaKind
    ): MetaPreview {
        val sourceId = ParsedMetadataId(
            scheme = AnimeIdScheme.TMDB,
            value = "${if (mediaKind == ContentMediaKind.MOVIE) "movie" else "tv"}:${parsed.value}",
            raw = parsed.raw
        )
        val imdbMapping = idMappingStore.lookup(MetadataPrimaryProvider.IMDB, sourceId) ?: return preview
        val tvdbMapping = idMappingStore.lookup(MetadataPrimaryProvider.TVDB, sourceId)
        val enriched = preview.firstPaintStableIds.copy(
            imdb = imdbMapping.providerId,
            tvdb = preview.firstPaintStableIds.tvdb ?: tvdbMapping?.providerId
        )
        return preview.copy(firstPaintStableIds = enriched)
    }

    private suspend fun enrichTvdbFromStore(
        preview: MetaPreview,
        parsed: ParsedMetadataId,
        mediaKind: ContentMediaKind
    ): MetaPreview {
        if (mediaKind != ContentMediaKind.SERIES) return preview
        val imdbMapping = idMappingStore.lookup(
            MetadataPrimaryProvider.IMDB,
            ParsedMetadataId(AnimeIdScheme.TVDB, parsed.value, parsed.raw)
        ) ?: return preview
        val enriched = preview.firstPaintStableIds.copy(imdb = imdbMapping.providerId)
        return preview.copy(firstPaintStableIds = enriched)
    }

    private fun enrichFromAnimeMap(
        preview: MetaPreview,
        parsed: ParsedMetadataId,
        mediaKind: ContentMediaKind
    ): MetaPreview {
        val record = animeIdMappingService.recordForKitsuId(parsed.value) ?: return preview
        val ids = preview.firstPaintStableIds
        val enriched = ids.copy(
            imdb = ids.imdb ?: record.imdb,
            tmdb = ids.tmdb ?: record.tmdb,
            tvdb = ids.tvdb ?: record.tvdb,
            mal = ids.mal ?: record.mal,
            anilist = ids.anilist ?: record.anilist,
            anidb = ids.anidb ?: record.anidb,
            kitsu = ids.kitsu ?: record.kitsu
        )
        return if (enriched == ids) preview else preview.copy(firstPaintStableIds = enriched)
    }

    private fun ContentType.toMediaKind(): ContentMediaKind? = when (this) {
        ContentType.MOVIE -> ContentMediaKind.MOVIE
        ContentType.SERIES, ContentType.TV -> ContentMediaKind.SERIES
        else -> null
    }
}

data class CrossIdResolutionEvent(
    val itemKey: String,
    val from: ProviderIds,
    val to: ProviderIds
)
```

If `StableIdResolutionTrigger` doesn't have a `RAIL_ITEM_ENRICHMENT` variant, either add it to that enum (one-line change) or pick an existing variant whose semantics match ("on-demand resolution from a producer hot path"). Same for `ProviderId` enum — verify TMDB/TVDB names match.

- [ ] **Step 2: Run the enricher tests — must pass**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*CatalogItemCrossIdEnricherTest*" 2>&1 | tail -15
```
Expected: PASS — 5/5 tests.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/mapper/CatalogItemCrossIdEnricher.kt
git commit -m "$(cat <<'EOF'
feat(catalog/cross-id): add CatalogItemCrossIdEnricher

Singleton that enriches MetaPreview.firstPaintStableIds with cross-
provider IDs via the existing StableIdBundleResolver / IdMappingStore /
AnimeIdMappingService infrastructure.

Two entry points:
- enrichFromCache(preview) — sync, cache-only. Returns the original
  MetaPreview reference on cache miss for reference stability. Safe to
  call on the producer hot path.
- enrichResolving(preview) — fires StableIdBundleResolver which may query
  TMDB external_ids or TVDB extended. Result is persisted via
  IdMappingStore; resolutionUpdates flow emits the resolved IDs.

Per the 2026-05-11 design spec: movies resolve imdb through TMDB; series
resolve imdb through TVDB; kitsu items resolve via the in-memory anime
map (no network).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Wire enricher into `CatalogMapper.toDomain()`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/mapper/CatalogMapper.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt`

- [ ] **Step 1: Update `CatalogMapper.toDomain` to suspend + enricher param**

Replace the existing `fun MetaPreviewDto.toDomain(): MetaPreview` with:

```kotlin
suspend fun MetaPreviewDto.toDomain(
    enricher: CatalogItemCrossIdEnricher
): MetaPreview {
    val base = MetaPreview(
        id = id,
        type = ContentType.fromString(type),
        rawType = type,
        name = name,
        poster = poster,
        posterShape = PosterShape.fromString(posterShape),
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo ?: year,
        runtime = runtime,
        imdbRating = imdbRating?.toFloatOrNull(),
        genres = genres ?: genre ?: emptyList(),
        trailerYtIds = trailerStreams?.mapNotNull { it.ytId?.takeIf { id -> id.isNotBlank() } } ?: emptyList(),
        language = language,
        firstPaintStableIds = deriveAddonStableIds(
            id = id,
            imdbId = imdbId,
            defaultVideoId = behaviorHints?.defaultVideoId
        )
    )
    return enricher.enrichFromCache(base)
}
```

- [ ] **Step 2: Pass enricher into CatalogRepositoryImpl**

At `CatalogRepositoryImpl.kt`:

Add a constructor parameter:
```kotlin
private val catalogItemCrossIdEnricher: CatalogItemCrossIdEnricher,
```

Update the call site at line 282 from:
```kotlin
val items = result.data.metas.map { meta -> meta.toDomain() }
```
to:
```kotlin
val items = result.data.metas.map { meta -> meta.toDomain(catalogItemCrossIdEnricher) }
```

- [ ] **Step 3: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL. If compile breaks at any OTHER call site of `MetaPreviewDto.toDomain()`, find them all (`grep -rn "MetaPreviewDto" app/src/main/java`) and update each to pass the enricher.

- [ ] **Step 4: Run the existing catalog test suite to confirm no regression**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*Catalog*" 2>&1 | tail -10
```
Expected: PASS. Any test that constructed `MetaPreviewDto.toDomain()` directly may need a stub enricher injected — read the failure and fix.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/mapper/CatalogMapper.kt \
        app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt
git commit -m "$(cat <<'EOF'
feat(catalog/cross-id): wire CatalogItemCrossIdEnricher into row construction

CatalogMapper.toDomain becomes a suspend extension taking the enricher.
Sync cache-only lookup populates cross-provider IDs at row construction
time, so MetaPreview.firstPaintStableIds carries imdb for TMDB/TVDB/Kitsu
items whenever the IdMappingStore has a prior resolution cached.

CatalogRepositoryImpl.getCatalog passes the enricher into toDomain.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Background async enrichment + re-emission wiring

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`

- [ ] **Step 1: Subscribe to `enricher.resolutionUpdates` in the catalog pipeline startup**

Find an existing init/observer wire-up point in `HomeViewModelCatalogPipeline.kt` (look for `viewModelScope.launch` blocks that already collect a Flow — e.g. `hydratedHomeOverlayObserverJob`). Add:

```kotlin
internal fun HomeViewModel.startCrossIdResolutionObserverPipeline() {
    viewModelScope.launch {
        catalogItemCrossIdEnricher.resolutionUpdates.collect { event ->
            scheduleUpdateCatalogRows()
        }
    }
}
```

Add `catalogItemCrossIdEnricher` as an `@Inject` field on `HomeViewModel` (in HomeViewModel.kt — find the existing constructor params and add it alongside `hydratedHomeOverlayStore`). Then call `startCrossIdResolutionObserverPipeline()` from `HomeViewModel.init` or wherever startup observers are launched today.

- [ ] **Step 2: Add a background async enrichment job**

After a CatalogRow is emitted (find the existing CatalogRow emission point in `HomeViewModelCatalogPipeline.kt`, around the `_displayCatalogRows.update` or the `publishResolvedItems` call), schedule an async resolution for each item missing imdb:

```kotlin
internal fun HomeViewModel.enrichCatalogRowItemsAsync(row: CatalogRow) {
    viewModelScope.launch(Dispatchers.IO) {
        for (i in row.items.indices) {
            val item = row.items[i]
            if (item.firstPaintStableIds.imdb.isNullOrBlank()) {
                runCatching { catalogItemCrossIdEnricher.enrichResolving(item) }
            }
        }
    }
}
```

Call from the post-emission hook so it runs OFF the producer hot path. Use indexed-for loops (CLAUDE.md rule #4 — no `forEach` over suspending lambdas).

When the resolver succeeds and persists, the next mapping lookup at row construction or alias computation will find the imdb in the store. `resolutionUpdates` triggers `scheduleUpdateCatalogRows` which re-runs the catalog pipeline, this time with `enrichFromCache` hitting and producing enriched MetaPreviews.

- [ ] **Step 3: Compile + run the home test suite**

```bash
./gradlew :app:compileUniversalDebugUnitTestKotlin 2>&1 | tail -5
./gradlew :app:testUniversalDebugUnitTest --tests "*HomeViewModel*" 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL on both. Home tests still pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
git commit -m "$(cat <<'EOF'
feat(home/cross-id): wire background enrichment + resolution-update re-emit

HomeViewModelCatalogPipeline now:
- Subscribes to CatalogItemCrossIdEnricher.resolutionUpdates and calls
  scheduleUpdateCatalogRows on each event. This re-runs the catalog
  pipeline so the next pass's enrichFromCache picks up the freshly
  resolved imdb id and the row's MetaPreview carries it forward into
  the artwork pipeline.
- Fires per-item enrichResolving on IO after each CatalogRow emission,
  for any item whose firstPaintStableIds.imdb is blank.

Network resolution stays off the producer hot path; first-paint UX is
unaffected. Items render their addon-provided artwork on first paint;
once the resolver completes and the store caches the imdb, the next
emission carries enriched ids, the artwork pipeline queries RPDB, the
boundary fix preserves the resolved slot, and the row promotes to
premium artwork.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Failing TDD anchor — consumer-side alias enrichment

**Files:**
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeArtworkOverlayKeysCrossIdTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.metadata.router.AnimeIdScheme
import com.nexio.tv.core.metadata.router.IdMapping
import com.nexio.tv.core.metadata.router.IdMappingSource
import com.nexio.tv.core.metadata.router.InMemoryIdMappingStore
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ParsedMetadataId
import com.nexio.tv.domain.model.ProviderIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeArtworkOverlayKeysCrossIdTest {

    @Test
    fun `tmdb-only row with cached imdb gets imdb-form alias`() = runTest {
        val store = InMemoryIdMappingStore(
            initialMappings = listOf(
                IdMapping(
                    sourceId = ParsedMetadataId(AnimeIdScheme.TMDB, "movie:202555", "tmdb:movie:202555"),
                    provider = MetadataPrimaryProvider.IMDB,
                    providerId = "tt12345",
                    source = IdMappingSource.PROVIDER_LOOKUP,
                    evidence = "tmdbMovieToImdb"
                )
            )
        )

        val aliases = HomeArtworkOverlayKeys.aliasesForEnriched(
            rowItemKey = "movie:tmdb:202555",
            contentId = "tmdb:202555",
            itemType = "movie",
            providerIds = ProviderIds(tmdb = "202555"),
            canonicalProvider = null,
            canonicalId = null,
            idMappingStore = store
        )

        assertTrue(
            "Expected imdb alias in $aliases",
            aliases.contains("movie:imdb:tt12345") || aliases.contains("movie:tt12345")
        )
    }

    @Test
    fun `tmdb-only row without cached imdb yields only tmdb aliases`() = runTest {
        val emptyStore = InMemoryIdMappingStore()

        val aliases = HomeArtworkOverlayKeys.aliasesForEnriched(
            rowItemKey = "movie:tmdb:202555",
            contentId = "tmdb:202555",
            itemType = "movie",
            providerIds = ProviderIds(tmdb = "202555"),
            canonicalProvider = null,
            canonicalId = null,
            idMappingStore = emptyStore
        )

        assertTrue("Expected tmdb alias in $aliases", aliases.contains("movie:tmdb:202555"))
        assertTrue("Expected no imdb alias", aliases.none { it.contains(":imdb:") || it.matches(Regex(".*:tt\\d+$")) })
    }
}
```

- [ ] **Step 2: Run — verify compile failure on `aliasesForEnriched`**

```bash
./gradlew :app:compileUniversalDebugUnitTestKotlin 2>&1 | tail -5
```
Expected: FAIL "unresolved reference: aliasesForEnriched".

- [ ] **Step 3: Commit failing test**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/HomeArtworkOverlayKeysCrossIdTest.kt
git commit -m "$(cat <<'EOF'
test(home/aliases): failing TDD anchor — consumer-side cross-id enrichment

Asserts HomeArtworkOverlayKeys.aliasesForEnriched (to be added) consults
IdMappingStore to produce the imdb-form alias for a tmdb-only row whose
cross-id has been cached.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Implement consumer-side `aliasesForEnriched`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeArtworkOverlayKeys.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`

- [ ] **Step 1: Add the suspend variant to `HomeArtworkOverlayKeys`**

In `HomeArtworkOverlayKeys.kt`, after the existing `aliasesFor`:

```kotlin
suspend fun aliasesForEnriched(
    rowItemKey: String,
    contentId: String,
    itemType: String,
    providerIds: ProviderIds,
    canonicalProvider: ProviderId?,
    canonicalId: String?,
    idMappingStore: IdMappingStore
): Set<String> {
    val enriched = enrichProviderIdsFromStore(itemType, contentId, providerIds, idMappingStore)
    return aliasesFor(
        rowItemKey = rowItemKey,
        contentId = contentId,
        itemType = itemType,
        providerIds = enriched,
        canonicalProvider = canonicalProvider,
        canonicalId = canonicalId
    )
}

private suspend fun enrichProviderIdsFromStore(
    itemType: String,
    contentId: String,
    providerIds: ProviderIds,
    idMappingStore: IdMappingStore
): ProviderIds {
    if (!providerIds.imdb.isNullOrBlank()) return providerIds
    val parsed = MetadataIdParser.parse(contentId)
    val sourceId = when (parsed.scheme) {
        AnimeIdScheme.TMDB -> {
            val mediaPrefix = if (itemType.equals("movie", ignoreCase = true)) "movie" else "tv"
            ParsedMetadataId(AnimeIdScheme.TMDB, "$mediaPrefix:${parsed.value}", parsed.raw)
        }
        AnimeIdScheme.TVDB -> ParsedMetadataId(AnimeIdScheme.TVDB, parsed.value, parsed.raw)
        else -> return providerIds
    }
    val imdb = idMappingStore.lookup(MetadataPrimaryProvider.IMDB, sourceId)?.providerId
        ?.takeIf { it.isNotBlank() } ?: return providerIds
    return providerIds.copy(imdb = imdb)
}
```

Add imports at the top:
```kotlin
import com.nexio.tv.core.metadata.router.AnimeIdScheme
import com.nexio.tv.core.metadata.router.IdMappingStore
import com.nexio.tv.core.metadata.router.MetadataIdParser
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ParsedMetadataId
```

- [ ] **Step 2: Update `overlayFromMap` to use the enriched aliases**

In `HomeHydrationOverlayApplier.kt`:

```kotlin
internal suspend fun MetaPreview.overlayFromMap(
    overlaysByItemKey: Map<String, HydratedHomeOverlay>,
    idMappingStore: IdMappingStore
): HydratedHomeOverlay? {
    if (overlaysByItemKey.isEmpty()) return null
    val rowKey = homeOverlayItemKey()
    overlaysByItemKey[rowKey]?.let { return it }
    val aliases = HomeArtworkOverlayKeys.aliasesForEnriched(
        rowItemKey = rowKey,
        contentId = id,
        itemType = apiType,
        providerIds = firstPaintStableIds,
        canonicalProvider = null,
        canonicalId = null,
        idMappingStore = idMappingStore
    )
    return aliases.asSequence()
        .filter { it != rowKey }
        .mapNotNull { overlaysByItemKey[it] }
        .firstOrNull()
}
```

- [ ] **Step 3: Propagate the IdMappingStore through `HomeResolvedDisplayMapper`**

`HomeResolvedDisplayMapper.kt:106-110` calls `overlayFromMap(overlaysByItemKey)`. Inject `IdMappingStore` into `HomeResolvedDisplayMapper` (as a constructor `@Inject` param) and pass it through:

```kotlin
private val overlay = overlayFromMap(overlaysByItemKey, idMappingStore)
```

Note: the mapper's `toResolvedDisplayItem` becomes suspend transitively. Verify all callers in `HomeResolvedDisplayMapper.toResolvedDisplayItems` and downstream are already in suspend context (they should be — `toResolvedDisplayItems` is already invoked from coroutines).

- [ ] **Step 4: Run all touched tests**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*HomeArtworkOverlayKeysCrossIdTest*" --tests "*HomeResolvedDisplayMapperTest*" --tests "*HomeHydrationOverlayApplier*" 2>&1 | tail -15
```
Expected: PASS for the new cross-id test (now finds aliases via store) + no regression in mapper/applier tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeArtworkOverlayKeys.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt
git commit -m "$(cat <<'EOF'
feat(home/aliases): consumer-side cross-id alias enrichment

Adds HomeArtworkOverlayKeys.aliasesForEnriched and overlayFromMap suspend
variants that consult IdMappingStore to enrich providerIds before
computing the overlay alias set. A tmdb-only row whose imdb has been
cached now matches an overlay stored under the imdb-form alias —
allowing TMDB rails to consume overlays that were originally hydrated
via a different rail (e.g. a Trakt rail for the same content).

Combined with the producer-side enricher (Task 2-4), every rail item
that the resolver has touched will see imdb-form aliases on both sides
of the publish.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: On-device verification

**Files:** N/A (verification only)

- [ ] **Step 1: Build + install**

```bash
./gradlew :app:installUniversalDebug 2>&1 | tail -3
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Cold-start smoke sequence (CLAUDE.md rule #8)**

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 60
```

- [ ] **Step 3: First-session expectation**

First cold-start: cache is empty for TMDB/TVDB items. Expect first-paint TMDB stock posters initially; within ~5-15s as the background `enrichResolving` job runs, rows should promote to RPDB premium. Watch a TMDB-source rail (e.g., "TMDB Discover" rail) for at least 30s; look for the visible promotion of poster artwork.

```bash
adb -s 192.168.50.98:5555 logcat -d -t 3000 | grep -E "home.stable_id_bundle|providerLookup.*tmdb|providerLookup.*tvdb|CrossIdResolution" | head -30
```
Expected: log lines showing `tmdbMovieToImdb` and/or `tvdbSeriesToImdb` resolution events.

- [ ] **Step 4: Second-session expectation**

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 30
```

Second cold-start: cache populated from Session 1. Expect RPDB premium posters on TMDB rails immediately on first paint, no visible promotion delay. Sample the Daredevil row from `tmdb-series.png` / `tmdb-movies.png` reference screenshots.

```bash
adb -s 192.168.50.98:5555 logcat -d -t 2000 | grep -E "providerLookup\.|tmdbMovieToImdb|tvdbSeriesToImdb" | wc -l
```
Expected: zero or near-zero count — the cache fully short-circuits resolver network calls.

- [ ] **Step 5: Heap acceptance check**

```bash
PID=$(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv)
adb -s 192.168.50.98:5555 shell am dumpheap $PID /data/local/tmp/heap-crossid.hprof
sleep 6
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-crossid.hprof /tmp/heap-crossid.hprof
heaptrail -i /tmp/heap-crossid.hprof --leak-suspects 0.03 --exclude-soft-weak -t 10 | head -50
```
Expected:
- `AnimeIdMappingService` still ~14.94 MiB (unchanged baseline).
- `DefaultLoadControl` still ~1.44 MiB (TrailerPlayer fix held).
- No new dominator named `CatalogItemCrossIdEnricher` — its in-memory state is just the SharedFlow buffer (capacity 64 events).
- No new MetaPreview / CatalogRow retention increase vs the boundary-fix baseline (`MetaPreview` ≤ 500 instances).

- [ ] **Step 6: This task is verification only — no commit**

---

## Task 8: Memory update + plan tracking

**Files:**
- Create: `/Users/jneerdael/.claude/projects/-Users-jneerdael-Scripts-nexio/memory/project_cross_provider_id_resolver_2026_05_11.md`
- Modify: `/Users/jneerdael/.claude/projects/-Users-jneerdael-Scripts-nexio/memory/MEMORY.md`

- [ ] **Step 1: Write the memory entry**

```markdown
---
name: Cross-provider ID resolver wired into row construction (2026-05-11)
description: TMDB-discover / TVDB / Kitsu / addon rails now carry imdb in firstPaintStableIds so RPDB premium artwork resolves uniformly across all source providers. Leverages the existing StableIdBundleResolver + LocalIdMappingStore; closes the rail-class first-paint-poster regression observed on tmdb.png / tmdb-series.png / tmdb-movies.png.
type: project
---

**Root cause:** `CatalogMapper.toDomain` produced MetaPreview items with whatever provider IDs the addon natively yielded. TMDB-discover yielded `tmdb:N` with no imdb; TVDB-source addons yielded `tvdb:N` with no imdb; kitsu addons yielded `kitsu:N`. Without imdb in `firstPaintStableIds`, RPDB (imdb-indexed) returned nothing, the artwork pipeline stored a TMDB-stock overlay (or no overlay), and the row consumer's `aliasesFor` couldn't match against a Trakt-rail-hydrated overlay stored under the imdb form.

The existing `StableIdBundleResolver` already implements every needed mapping (tmdbMovieToImdb, tvdbSeriesToImdb, kitsu via AnimeIdMappingService, etc.) and persists via Room-backed `LocalIdMappingStore`. The gap was nobody calling it from the row-construction path.

**Fix (8 tasks, commits `<sha1>..<sha8>`):**
- New `CatalogItemCrossIdEnricher` (Task 2) wraps the resolver with two entry points: sync cache-only and async fire-and-cache.
- `CatalogMapper.toDomain` is a suspend function taking the enricher; sync cache lookup populates imdb when previously resolved.
- `HomeViewModelCatalogPipeline` schedules async resolution per item post-emission; subscribes to `resolutionUpdates` to trigger re-emit when resolutions land.
- `HomeArtworkOverlayKeys.aliasesForEnriched` (Task 6) consults the store on the consumer side too — so even rows that haven't been re-emitted yet pick up the alias as soon as the store has the mapping.
- Anime/Kitsu items short-circuit through `AnimeIdMappingService.recordForKitsuId` (in-memory, no network).

**Verification:**
- Sample regression cases (Daredevil Born Again on TMDB vs Trakt rails) render RPDB premium on both sources.
- Heap acceptance: `AnimeIdMappingService` baseline unchanged at 14.94 MiB; no new dominator from the enricher; LocalIdMappingStore Room cache scales with unique-content count (~10-30k entries for power users).
- Second-cold-start short-circuits the network: zero `providerLookup` events in steady state.

**Why:** Trakt addon rails worked by accident — Trakt natively yields imdb-form IDs in the stremio convention. TMDB/TVDB/Kitsu addons yield their own ids and don't carry imdb. The boundary fix shipped 2026-05-11 ensures the typed authority never downgrades a resolved slot, but it can't conjure a resolved slot that was never produced — that requires the cross-id resolution to happen upstream of the artwork pipeline. This entry documents how that's now wired.

**How to apply:** when adding a new source provider (new addon family), verify that either (a) the addon yields imdb in MetaPreviewDto natively, or (b) the resolver has a path to map its native id format to imdb. For TMDB/TVDB/Kitsu the resolver already covers; for unknown provider prefixes, `CatalogItemCrossIdEnricher.enrichFromCache` returns the preview unchanged and the artwork pipeline falls back to addon-provided artwork (no regression vs the pre-fix state).
```

Replace `<sha1>..<sha8>` with actual SHAs.

- [ ] **Step 2: Update MEMORY.md index**

Add line at end of MEMORY.md:
```markdown
- [Cross-provider ID resolver wired 2026-05-11](project_cross_provider_id_resolver_2026_05_11.md) — TMDB/TVDB/Kitsu rails now carry imdb via existing StableIdBundleResolver + new CatalogItemCrossIdEnricher; RPDB premium artwork resolves uniformly across all source providers
```

- [ ] **Step 3: No commit needed — memory files are outside the repo**

---

## Self-Review

**Spec coverage check:**
- ✅ Resolution policy (movie→tmdb, series→tvdb, kitsu→anime map) → Task 2 enricher
- ✅ Caching via existing `LocalIdMappingStore` (Room-backed, satisfies CLAUDE.md rule #3 since SQLite streams natively) → Task 2 dependency
- ✅ No first-paint regression (background async, producer hot path uses sync cache-only) → Tasks 2-4
- ✅ No new ANR class (IO-dispatched) → Task 4 explicit `Dispatchers.IO`
- ✅ TMDB external_ids + TVDB extended → already in `TmdbApi.kt:109,115` and `TvdbApi.kt:40`, reused via `RuntimeMetadataIdentityLookup`
- ✅ Anime map shortcut (in-memory, no network) → Task 2 `enrichFromAnimeMap`
- ✅ Acceptance criteria (Daredevil on TMDB + Trakt both RPDB) → Task 7 manual + log-based verification

**Placeholder scan:** none — every step has actual code, test code, commands, and exact file paths.

**Type consistency:** `CatalogItemCrossIdEnricher`, `enrichFromCache`, `enrichResolving`, `resolutionUpdates`, `CrossIdResolutionEvent`, `aliasesForEnriched` — names used consistently across Tasks 1-6. Constructor parameter `catalogItemCrossIdEnricher` is also consistent.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-11-cross-provider-id-resolver-plan.md`. Two execution options:

**1. Subagent-Driven (recommended)** — Fresh subagent per task with two-stage review (spec compliance + code quality) after each.

**2. Inline Execution** — Execute the 8 tasks in this session using executing-plans, with checkpoints between tasks.

Which approach?
