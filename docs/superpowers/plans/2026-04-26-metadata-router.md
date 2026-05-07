# MetadataRouter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the MetadataRouter stack above IntegrationRuntime so addon metadata renders first, TMDB/TVDB/Kitsu primary authority routing is deterministic, enrichment obeys resolver depth and field ownership, and Continue Watching preserves route context.

**Architecture:** Implement a new `com.nexio.tv.core.metadata.router` package with focused routing, plan execution, resolver orchestration, field resolution, cache-key, and migration-boundary units. Provider networking remains below existing provider integration adapters and IntegrationRuntime; router code only references provider shape IDs and facade-level dependencies. Each stage is accepted only by tests.

**Tech Stack:** Kotlin, coroutines, JUnit4, Android Gradle, Hilt for production wiring, existing IntegrationRuntime audit artifacts under `app/build/reports/integration-runtime-audit`.

---

## Non-Negotiable Implementation Rules

- Identity resolution is performed ONLY inside provider integration adapters or dedicated identity helper services. `MetadataRouter` and `ProviderPlanExecutor` MUST NOT resolve provider-native IDs.
- After routing, provider decisions MUST use `route.mediaKind`. Do not use the original request `ContentType` for provider selection after `MetadataRoute` exists.
- `IdMappingStore.persist()` MUST enforce overwrite priority: `LOCAL > ROUTER_OBSERVED > FRIBB > NEGATIVE`. Lower-priority mappings must not replace higher-priority mappings.
- Fribb is required for correctness, not an optional optimization. Without it, IMDb-only anime rows can fall through to TVDB.
- Addon metadata is the first-render UI baseline. Canonical metadata replaces it later; it is not a reason to ignore addon fields.

## Execution Context

Run this plan in the IntegrationRuntime worktree or a branch that already contains the IntegrationRuntime audit hardening files:

```bash
cd /Users/jneerdael/Scripts/nexio/.worktrees/integration-runtime-phase-a
```

Do not start from `/Users/jneerdael/Scripts/nexio` unless the IntegrationRuntime branch has been merged there. This plan depends on:

- `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt`
- `app/src/test/resources/integration/metadata_router_prerequisites.txt`
- `app/src/test/java/com/nexio/tv/architecture/MetadataRouterReadinessAuditTest.kt`
- `./gradlew :app:generateIntegrationRuntimeAudit`

Before editing, capture the baseline:

```bash
git status --short
./gradlew :app:generateIntegrationRuntimeAudit :app:testDebugUnitTest --tests com.nexio.tv.architecture.MetadataRouterReadinessAuditTest
```

Expected: audit generation and readiness test pass before router work starts. If the build fails, fix that failure first because this plan uses the audit as an executable gate.

## File Structure

Create focused router files:

- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`
  - Owns request, source context, route, depth, provider enum, trace, candidate, and resolved document models.
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizer.kt`
  - Owns `parentIdOf()` and request normalization.
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/AnimeIdentityIndex.kt`
  - Resolves MAL, AniList, AniDB, and IMDb anime identifiers to Kitsu when deterministic packaged identity data exists.
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/IdMappingStore.kt`
  - Defines mapping lookup/persist contracts and in-memory test implementation.
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/LocalIdMappingStore.kt`
  - Persists router-observed and Fribb/Kitsu identity mappings through the existing integration identity DAO.
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouter.kt`
  - Owns provider precedence only; performs no provider fetches.
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutor.kt`
  - Maps route + depth to IntegrationRuntime-covered `apiShapeId`s.
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/ResolverOrchestrator.kt`
  - Schedules secondary resolvers by request depth.
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt`
  - Merges primary and secondary candidates using field ownership.
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataCacheKeys.kt`
  - Builds router decision, resolved document, artwork decision, and image/blob keys.
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
  - Production-facing facade for UI/repository migration.

Modify integration points:

- Modify: `app/src/main/java/com/nexio/tv/core/di/IntegrationProviderModule.kt`
  - Add Hilt bindings for router components when constructor injection is insufficient.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
  - Persist route context and click-time display metadata.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingTimeline.kt`
  - Reuse stored route context and click-time display fallback.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
  - Consume route-aware Continue Watching output.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt`
  - Persist route context at playback start.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
  - Route detail metadata through `MetadataRouterFacade`.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProviderLocalizedMetadataOverlay.kt`
  - Use addon-first display metadata and router enrichment.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`
  - Preserve per-item type and source context when requesting enrichment.

Create tests:

- Create: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizerTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterPrecedenceTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutorTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/metadata/router/ResolverOrchestratorTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataCacheKeysTest.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMetadataRouterTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/MetadataRouterBoundaryTest.kt`

## Input Contract

Every routing decision must use only:

```text
item.id
item.type
```

Do not use `catalog.type`, `addonId`, `catalogId`, `sourceName`, `genre == Animation`, `animeType`, popularity/trend fields, or `links[]` as routing authority. Keep those fields only for trace, rendering, catalog harvest, diagnostics, or click-time metadata capture.

Addon render fields such as `name`, `poster`, `background`, `description`, `releaseInfo`, `runtime`, `imdbRating`, and `genres` are first-paint display inputs only.

Provider-native ids are not anime detection inputs:

```text
tmdb:{id} + movie  -> TMDB directly
tvdb:{id} + series -> TVDB directly
tmdb:{id} + series -> ROUTING_ID_TYPE_CONFLICT, keep targetId=parentId, then explicit item.type fallback
tvdb:{id} + movie  -> ROUTING_ID_TYPE_CONFLICT, keep targetId=parentId, then explicit item.type fallback
```

Initial catalog preview rendering must not execute `MetadataRouter` for every row item. Render addon metadata immediately and defer routing until an item becomes visible, detail opens, playback starts, or enrichment is explicitly requested.

## Task 1: Routing Models And Parent Normalization

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizer.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizerTest.kt`

- [ ] **Step 1: Write the failing normalizer tests**

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataRequestNormalizerTest {
    private val normalizer = MetadataRequestNormalizer()

    @Test
    fun `IMDb episode id normalizes to parent IMDb id`() {
        val request = MetadataRequest(
            contentId = "tt12343534:1:1",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext()
        )

        val normalized = normalizer.normalize(request)

        assertEquals("tt12343534", normalized.parentId)
        assertEquals("tt12343534:1:1", normalized.originalContentId)
    }

    @Test
    fun `Kitsu episode id normalizes to parent Kitsu id`() {
        val request = MetadataRequest(
            contentId = "kitsu:7442:1:1",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext()
        )

        val normalized = normalizer.normalize(request)

        assertEquals("kitsu:7442", normalized.parentId)
    }

    @Test
    fun `provider title id remains unchanged`() {
        val request = MetadataRequest(
            contentId = "tmdb:550",
            contentType = ContentType.MOVIE,
            sourceContext = MetadataSourceContext()
        )

        val normalized = normalizer.normalize(request)

        assertEquals("tmdb:550", normalized.parentId)
    }

    @Test
    fun `blank id is preserved as blank and recorded as unsupported`() {
        val request = MetadataRequest(
            contentId = "   ",
            contentType = ContentType.MOVIE,
            sourceContext = MetadataSourceContext()
        )

        val normalized = normalizer.normalize(request)

        assertEquals("", normalized.parentId)
        assertEquals(MetadataMediaKind.MOVIE, normalized.mediaKind)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRequestNormalizerTest
```

Expected: FAIL with unresolved references for `MetadataRequest`, `MetadataSourceContext`, and `MetadataRequestNormalizer`.

- [ ] **Step 3: Add the minimal routing models**

Create `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata

enum class MetadataPrimaryProvider {
    TMDB,
    TVDB,
    KITSU
}

enum class MetadataDecisionReason {
    KITSU_PREFIX_DIRECT,
    ANIME_PREFIX_MAPPED_TO_KITSU,
    ID_MAPPING_TO_KITSU,
    PROVIDER_NATIVE_DIRECT,
    ROUTING_ID_TYPE_CONFLICT,
    ITEM_TYPE_MOVIE,
    ITEM_TYPE_SERIES,
    UNSUPPORTED_TYPE
}

enum class MetadataMediaKind {
    MOVIE,
    SERIES,
    ANIME,
    UNKNOWN
}

enum class MetadataDepth {
    PREVIEW,
    DETAIL_CORE,
    DETAIL_MEDIA,
    DETAIL_SECONDARY,
    SEASON,
    PLAYER
}

data class MetadataSourceContext(
    val addonId: String? = null,
    val catalogId: String? = null,
    val catalogType: String? = null,
    val itemType: String? = null,
    val sourceName: String? = null,
    val addonMetadata: HomeDisplayMetadata? = null,
    val rowItemIds: List<String> = emptyList()
)

data class MetadataRequest(
    val contentId: String,
    val contentType: ContentType,
    val sourceContext: MetadataSourceContext,
    val language: String? = null,
    val seasonNumber: Int? = null,
    val depth: MetadataDepth = MetadataDepth.DETAIL_CORE
)

data class NormalizedMetadataRequest(
    val originalContentId: String,
    val parentId: String,
    val contentType: ContentType,
    val mediaKind: MetadataMediaKind,
    val sourceContext: MetadataSourceContext,
    val language: String?,
    val seasonNumber: Int?,
    val depth: MetadataDepth
)

data class MetadataRouteTrace(
    val reason: MetadataDecisionReason,
    val detail: String
)

data class MetadataRoute(
    val provider: MetadataPrimaryProvider,
    val parentId: String,
    val mediaKind: MetadataMediaKind,
    val reason: MetadataDecisionReason,
    val sourceContext: MetadataSourceContext,
    val language: String? = null,
    val targetIds: Map<MetadataPrimaryProvider, String>,
    val targetIdRequiresIdentityResolution: Boolean = false,
    val trace: List<MetadataRouteTrace>
)
```

- [ ] **Step 4: Add the normalizer implementation**

Create `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizer.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.ContentType
import javax.inject.Inject

class MetadataRequestNormalizer @Inject constructor() {
    fun normalize(request: MetadataRequest): NormalizedMetadataRequest {
        val trimmedId = request.contentId.trim()
        return NormalizedMetadataRequest(
            originalContentId = trimmedId,
            parentId = parentIdOf(trimmedId),
            contentType = request.contentType,
            mediaKind = request.contentType.toMetadataMediaKind(),
            sourceContext = request.sourceContext,
            language = request.language,
            seasonNumber = request.seasonNumber,
            depth = request.depth
        )
    }

    fun parentIdOf(contentId: String): String {
        val id = contentId.trim()
        if (id.isBlank()) return ""

        val parts = id.split(":")
        return when {
            id.startsWith("kitsu:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("mal:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("anilist:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("anidb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("tmdb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("tvdb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("tt", ignoreCase = true) && parts.size >= 3 -> parts[0]
            else -> id
        }
    }

    private fun ContentType.toMetadataMediaKind(): MetadataMediaKind =
        when (this) {
            ContentType.MOVIE -> MetadataMediaKind.MOVIE
            ContentType.SERIES -> MetadataMediaKind.SERIES
            else -> MetadataMediaKind.UNKNOWN
        }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRequestNormalizerTest
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizer.kt app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizerTest.kt
git commit -m "feat(metadata): add metadata request normalization"
```

## Task 2: Anime Prefix Routing And Deterministic ID Mapping

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/AnimeIdentityIndex.kt`
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/IdMappingStore.kt`
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/LocalIdMappingStore.kt`
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/integration/ExternalIdEntity.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDatabase.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterPrecedenceTest.kt`

- [ ] **Step 1: Write the failing precedence tests**

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRouterPrecedenceTest {
    private val mappingStore = InMemoryIdMappingStore()
    private val animeIdentityIndex = InMemoryAnimeIdentityIndex(
        mappings = listOf(
            AnimeIdentityMapping(AnimeIdScheme.MAL, "21", "kitsu:12"),
            AnimeIdentityMapping(AnimeIdScheme.ANILIST, "16498", "kitsu:7442"),
            AnimeIdentityMapping(AnimeIdScheme.ANIDB, "9541", "kitsu:7442"),
            AnimeIdentityMapping(AnimeIdScheme.IMDB, "tt12343534", "kitsu:100")
        )
    )
    private val router = MetadataRouter(
        normalizer = MetadataRequestNormalizer(),
        animeIdentityIndex = animeIdentityIndex,
        idMappingStore = mappingStore
    )

    @Test
    fun `kitsu row item routes directly to Kitsu without Fribb lookup`() = runTest {
        val route = router.route(
            MetadataRequest(
                contentId = "kitsu:7442:1:1",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(itemType = "movie")
            )
        )

        assertEquals(MetadataPrimaryProvider.KITSU, route.provider)
        assertEquals("kitsu:7442", route.parentId)
        assertEquals(MetadataDecisionReason.KITSU_PREFIX_DIRECT, route.reason)
        assertEquals("kitsu:7442", route.targetIds[MetadataPrimaryProvider.KITSU])
        assertEquals(emptyList<AnimeIdentityLookup>(), animeIdentityIndex.lookups)
    }

    @Test
    fun `mal row item maps through Fribb to Kitsu`() = runTest {
        val route = router.route(
            MetadataRequest(
                contentId = "mal:21",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext()
            )
        )

        assertEquals(MetadataPrimaryProvider.KITSU, route.provider)
        assertEquals(MetadataDecisionReason.ANIME_PREFIX_MAPPED_TO_KITSU, route.reason)
        assertEquals("kitsu:12", route.targetIds[MetadataPrimaryProvider.KITSU])
    }

    @Test
    fun `anilist row item maps through Fribb to Kitsu`() = runTest {
        val route = router.route(
            MetadataRequest(
                contentId = "anilist:16498",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext()
            )
        )

        assertEquals(MetadataPrimaryProvider.KITSU, route.provider)
        assertEquals(MetadataDecisionReason.ANIME_PREFIX_MAPPED_TO_KITSU, route.reason)
        assertEquals("kitsu:7442", route.targetIds[MetadataPrimaryProvider.KITSU])
    }

    @Test
    fun `anidb row item maps through Fribb to Kitsu`() = runTest {
        val route = router.route(
            MetadataRequest(
                contentId = "anidb:9541",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext()
            )
        )

        assertEquals(MetadataPrimaryProvider.KITSU, route.provider)
        assertEquals(MetadataDecisionReason.ANIME_PREFIX_MAPPED_TO_KITSU, route.reason)
        assertEquals("kitsu:7442", route.targetIds[MetadataPrimaryProvider.KITSU])
    }

    @Test
    fun `neutral IMDb row item routes to Kitsu from local id mapping before Fribb`() = runTest {
        mappingStore.persist(
            IdMapping(
                sourceId = ParsedMetadataId(AnimeIdScheme.IMDB, "tt12343534", "tt12343534"),
                provider = MetadataPrimaryProvider.KITSU,
                providerId = "kitsu:local",
                source = IdMappingSource.LOCAL,
                evidence = "local user mapping"
            )
        )

        val route = router.route(
            MetadataRequest(
                contentId = "tt12343534",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(
                    addonId = "crunchyroll",
                    catalogId = "crunchyroll-anime",
                    sourceName = "Crunchyroll"
                )
            )
        )

        assertEquals(MetadataPrimaryProvider.KITSU, route.provider)
        assertEquals(MetadataDecisionReason.ID_MAPPING_TO_KITSU, route.reason)
        assertEquals("kitsu:local", route.targetIds[MetadataPrimaryProvider.KITSU])
    }

    @Test
    fun `neutral IMDb row item routes to Kitsu from Fribb when local mapping is absent`() = runTest {
        val route = router.route(
            MetadataRequest(
                contentId = "tt12343534",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(
                    addonId = "mixed-addon",
                    catalogId = "popular",
                    sourceName = "Any Catalog"
                )
            )
        )

        assertEquals(MetadataPrimaryProvider.KITSU, route.provider)
        assertEquals(MetadataDecisionReason.ID_MAPPING_TO_KITSU, route.reason)
        assertEquals("kitsu:100", route.targetIds[MetadataPrimaryProvider.KITSU])
    }

    @Test
    fun `tmdb movie id routes directly to TMDB without Fribb lookup`() = runTest {
        val route = router.route(
            MetadataRequest(
                contentId = "tmdb:550",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext()
            )
        )

        assertEquals(MetadataPrimaryProvider.TMDB, route.provider)
        assertEquals(MetadataDecisionReason.PROVIDER_NATIVE_DIRECT, route.reason)
        assertEquals("tmdb:550", route.targetIds[MetadataPrimaryProvider.TMDB])
        assertEquals(false, animeIdentityIndex.lookups.any { it.scheme == AnimeIdScheme.TMDB })
    }

    @Test
    fun `tvdb series id routes directly to TVDB without Fribb lookup`() = runTest {
        val route = router.route(
            MetadataRequest(
                contentId = "tvdb:81189",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext()
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, route.provider)
        assertEquals(MetadataDecisionReason.PROVIDER_NATIVE_DIRECT, route.reason)
        assertEquals("tvdb:81189", route.targetIds[MetadataPrimaryProvider.TVDB])
        assertEquals(false, animeIdentityIndex.lookups.any { it.scheme == AnimeIdScheme.TVDB })
    }

    @Test
    fun `provider native mismatch records conflict and falls back by item type`() = runTest {
        val route = router.route(
            MetadataRequest(
                contentId = "tmdb:1399",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext()
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, route.provider)
        assertEquals(MetadataDecisionReason.ITEM_TYPE_SERIES, route.reason)
        assertEquals("tmdb:1399", route.targetIds[MetadataPrimaryProvider.TVDB])
        assertEquals(true, route.targetIdRequiresIdentityResolution)
        assertTrue(route.trace.any { it.reason == MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT })
        assertEquals(false, animeIdentityIndex.lookups.any { it.scheme == AnimeIdScheme.TMDB })
    }

    @Test
    fun `anime identity index rejects provider native ids`() = runTest {
        val failure = runCatching {
            animeIdentityIndex.resolveKitsuId(ParsedMetadataId(AnimeIdScheme.TMDB, "550", "tmdb:550"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `negative mapping TTL is thirty days and positive mappings are permanent`() {
        val now = 1_000L

        assertEquals(now + IdMappingTtlPolicy.NEGATIVE_TTL_MS, IdMappingTtlPolicy.expiresAt(IdMappingSource.NEGATIVE, now))
        assertEquals(null, IdMappingTtlPolicy.expiresAt(IdMappingSource.LOCAL, now))
        assertEquals(null, IdMappingTtlPolicy.expiresAt(IdMappingSource.FRIBB, now))
        assertEquals(null, IdMappingTtlPolicy.expiresAt(IdMappingSource.ROUTER_OBSERVED, now))
    }

    @Test
    fun `mapping priority is local then observed then fribb then negative`() {
        assertTrue(IdMappingTtlPolicy.comparePriority(IdMappingSource.FRIBB, IdMappingSource.LOCAL) > 0)
        assertTrue(IdMappingTtlPolicy.comparePriority(IdMappingSource.FRIBB, IdMappingSource.ROUTER_OBSERVED) > 0)
        assertTrue(IdMappingTtlPolicy.comparePriority(IdMappingSource.ROUTER_OBSERVED, IdMappingSource.FRIBB) < 0)
        assertTrue(IdMappingTtlPolicy.comparePriority(IdMappingSource.NEGATIVE, IdMappingSource.FRIBB) > 0)
    }

    @Test
    fun `router rejects preview depth because initial render bypasses routing`() = runTest {
        val failure = runCatching {
            router.route(
                MetadataRequest(
                    contentId = "tt0114709",
                    contentType = ContentType.MOVIE,
                    sourceContext = MetadataSourceContext(),
                    depth = MetadataDepth.PREVIEW
                )
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `catalog anime words do not route neutral ids without deterministic mapping`() = runTest {
        val route = MetadataRouter(
            normalizer = MetadataRequestNormalizer(),
            animeIdentityIndex = InMemoryAnimeIdentityIndex(),
            idMappingStore = InMemoryIdMappingStore()
        ).route(
            MetadataRequest(
                contentId = "tt0000001",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(
                    addonId = "crunchyroll",
                    catalogId = "anime-popular",
                    sourceName = "Anime Catalog"
                )
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, route.provider)
        assertEquals(MetadataDecisionReason.ITEM_TYPE_SERIES, route.reason)
    }

    @Test
    fun `Disney mixed row uses per item type for movie fallback`() = runTest {
        val route = router.route(
            MetadataRequest(
                contentId = "tmdb:550",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(
                    catalogType = "series",
                    itemType = "movie",
                    sourceName = "Disney"
                )
            )
        )

        assertEquals(MetadataPrimaryProvider.TMDB, route.provider)
        assertEquals(MetadataDecisionReason.ITEM_TYPE_MOVIE, route.reason)
    }

    @Test
    fun `Disney mixed row uses per item type for series fallback`() = runTest {
        val route = router.route(
            MetadataRequest(
                contentId = "tt14403178",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(
                    catalogType = "series",
                    itemType = "series",
                    sourceName = "Disney"
                )
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, route.provider)
        assertEquals(MetadataDecisionReason.ITEM_TYPE_SERIES, route.reason)
    }

    @Test
    fun `route trace records decision evidence`() = runTest {
        val route = router.route(
            MetadataRequest(
                contentId = "mal:21",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext()
            )
        )

        assertTrue(route.trace.any { it.reason == MetadataDecisionReason.ANIME_PREFIX_MAPPED_TO_KITSU })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterPrecedenceTest
```

Expected: FAIL with unresolved references for `MetadataRouter`, `AnimeIdentityIndex`, `IdMapping`, `IdMappingSource`, and `InMemoryIdMappingStore`.

- [ ] **Step 3: Implement parsed id and anime identity index contracts**

Create `app/src/main/java/com/nexio/tv/core/metadata/router/AnimeIdentityIndex.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import javax.inject.Inject

enum class AnimeIdScheme {
    KITSU,
    MAL,
    ANILIST,
    ANIDB,
    IMDB,
    TMDB,
    TVDB,
    UNKNOWN
}

data class ParsedMetadataId(
    val scheme: AnimeIdScheme,
    val value: String,
    val raw: String
)

data class AnimeIdentityMapping(
    val scheme: AnimeIdScheme,
    val value: String,
    val kitsuId: String
)

data class AnimeIdentityLookup(
    val scheme: AnimeIdScheme,
    val value: String
)

interface AnimeIdentityIndex {
    suspend fun resolveKitsuId(id: ParsedMetadataId): String?
}

private val AnimeIdentityIndexAllowedSchemes = setOf(
    AnimeIdScheme.MAL,
    AnimeIdScheme.ANILIST,
    AnimeIdScheme.ANIDB,
    AnimeIdScheme.IMDB
)

class MetadataIdParser @Inject constructor() {
    fun parse(parentId: String): ParsedMetadataId {
        val raw = parentId.trim()
        val lower = raw.lowercase()
        return when {
            lower.startsWith("kitsu:") -> ParsedMetadataId(AnimeIdScheme.KITSU, raw.substringAfter(":"), raw)
            lower.startsWith("mal:") -> ParsedMetadataId(AnimeIdScheme.MAL, raw.substringAfter(":"), raw)
            lower.startsWith("anilist:") -> ParsedMetadataId(AnimeIdScheme.ANILIST, raw.substringAfter(":"), raw)
            lower.startsWith("anidb:") -> ParsedMetadataId(AnimeIdScheme.ANIDB, raw.substringAfter(":"), raw)
            lower.startsWith("imdb:") -> ParsedMetadataId(AnimeIdScheme.IMDB, raw.substringAfter(":"), raw)
            lower.startsWith("tt") -> ParsedMetadataId(AnimeIdScheme.IMDB, raw, raw)
            lower.startsWith("tmdb:") -> ParsedMetadataId(AnimeIdScheme.TMDB, raw.substringAfter(":"), raw)
            lower.startsWith("tvdb:") -> ParsedMetadataId(AnimeIdScheme.TVDB, raw.substringAfter(":"), raw)
            else -> ParsedMetadataId(AnimeIdScheme.UNKNOWN, raw, raw)
        }
    }
}

class InMemoryAnimeIdentityIndex(
    private val mappings: List<AnimeIdentityMapping> = emptyList()
) : AnimeIdentityIndex {
    val lookups = mutableListOf<AnimeIdentityLookup>()

    override suspend fun resolveKitsuId(id: ParsedMetadataId): String? {
        require(id.scheme in AnimeIdentityIndexAllowedSchemes) {
            "AnimeIdentityIndex must not resolve ${id.scheme}; TMDB/TVDB provider-native ids are handled by MetadataRouter."
        }
        lookups += AnimeIdentityLookup(id.scheme, id.value)
        return mappings.firstOrNull { it.scheme == id.scheme && it.value == id.value }?.kitsuId
    }
}
```

- [ ] **Step 4: Implement id mapping contracts**

Create `app/src/main/java/com/nexio/tv/core/metadata/router/IdMappingStore.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

enum class IdMappingSource {
    FRIBB,
    LOCAL,
    ROUTER_OBSERVED,
    NEGATIVE
}

data class IdMapping(
    val sourceId: ParsedMetadataId,
    val provider: MetadataPrimaryProvider,
    val providerId: String,
    val source: IdMappingSource,
    val evidence: String,
    val expiresAtEpochMs: Long? = null
)

object IdMappingTtlPolicy {
    const val NEGATIVE_TTL_MS: Long = 30L * 24L * 60L * 60L * 1000L
    private val priority = mapOf(
        IdMappingSource.LOCAL to 4,
        IdMappingSource.ROUTER_OBSERVED to 3,
        IdMappingSource.FRIBB to 2,
        IdMappingSource.NEGATIVE to 1
    )

    fun expiresAt(source: IdMappingSource, nowEpochMs: Long): Long? =
        if (source == IdMappingSource.NEGATIVE) nowEpochMs + NEGATIVE_TTL_MS else null

    fun comparePriority(existing: IdMappingSource, incoming: IdMappingSource): Int =
        priority.getValue(incoming).compareTo(priority.getValue(existing))
}

private fun ParsedMetadataId.mappingKey(): String =
    "${scheme.name}:${value.lowercase()}"

interface IdMappingStore {
    suspend fun lookupKitsu(id: ParsedMetadataId): IdMapping?

    /**
     * Implementations must enforce overwrite priority:
     * LOCAL > ROUTER_OBSERVED > FRIBB > NEGATIVE.
     * Lower-priority mappings must not replace higher-priority mappings.
     */
    suspend fun persist(mapping: IdMapping)
}

class InMemoryIdMappingStore(
    seed: List<IdMapping> = emptyList()
) : IdMappingStore {
    private val mappings = linkedMapOf<String, IdMapping>()

    init {
        seed.forEach { mappings[it.sourceId.mappingKey()] = it }
    }

    override suspend fun lookupKitsu(id: ParsedMetadataId): IdMapping? =
        mappings[id.mappingKey()]?.takeUnless { mapping ->
            mapping.expiresAtEpochMs?.let { it <= System.currentTimeMillis() } == true
        }

    override suspend fun persist(mapping: IdMapping) {
        val key = mapping.sourceId.mappingKey()
        val existing = mappings[key]
        if (existing == null || IdMappingTtlPolicy.comparePriority(existing.source, mapping.source) <= 0) {
            mappings[key] = mapping
        }
    }
}
```

- [ ] **Step 5: Add expiry support to persisted external identity rows**

Modify `app/src/main/java/com/nexio/tv/data/local/integration/ExternalIdEntity.kt`:

```kotlin
@Entity(
    tableName = "integration_external_id",
    indices = [
        Index("mediaKey"),
        Index(value = ["provider", "externalId", "idType"], unique = true)
    ]
)
data class ExternalIdEntity(
    @PrimaryKey val key: String,
    val mediaKey: String,
    val provider: String,
    val externalId: String,
    val idType: String,
    val expiresAtEpochMs: Long? = null
)
```

Modify `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDatabase.kt` by incrementing the Room database version by one. Keep `exportSchema = false` unless the project has since enabled schema export.

- [ ] **Step 6: Implement the durable local id mapping store**

Create `app/src/main/java/com/nexio/tv/core/metadata/router/LocalIdMappingStore.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.data.local.integration.ExternalIdEntity
import com.nexio.tv.data.local.integration.MediaIdentityDao
import com.nexio.tv.data.local.integration.MediaIdentityEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalIdMappingStore @Inject constructor(
    private val mediaIdentityDao: MediaIdentityDao
) : IdMappingStore {
    override suspend fun lookupKitsu(id: ParsedMetadataId): IdMapping? {
        val ids = mediaIdentityDao.externalIdsForMedia(id.mappingKey())
        val kitsu = ids.firstOrNull { it.provider == MetadataPrimaryProvider.KITSU.name }
            ?: return null
        if (kitsu.expiresAtEpochMs?.let { it <= System.currentTimeMillis() } == true) return null

        return IdMapping(
            sourceId = id,
            provider = MetadataPrimaryProvider.KITSU,
            providerId = kitsu.externalId,
            source = runCatching { IdMappingSource.valueOf(kitsu.idType) }.getOrDefault(IdMappingSource.LOCAL),
            evidence = "integration_external_id:${kitsu.key}",
            expiresAtEpochMs = kitsu.expiresAtEpochMs
        )
    }

    override suspend fun persist(mapping: IdMapping) {
        mediaIdentityDao.upsertMediaIdentity(
            MediaIdentityEntity(
                mediaKey = mapping.sourceId.mappingKey(),
                mediaType = mapping.provider.name,
                title = null,
                year = null,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
        mediaIdentityDao.upsertExternalIds(
            listOf(
                ExternalIdEntity(
                    key = "${mapping.sourceId.mappingKey()}:${mapping.provider.name}:${mapping.providerId}",
                    mediaKey = mapping.sourceId.mappingKey(),
                    provider = mapping.provider.name,
                    externalId = mapping.providerId,
                    idType = mapping.source.name,
                    expiresAtEpochMs = mapping.expiresAtEpochMs
                )
            )
        )
    }
}
```

- [ ] **Step 7: Implement MetadataRouter precedence**

Create `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouter.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.ContentType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataRouter @Inject constructor(
    private val normalizer: MetadataRequestNormalizer,
    private val animeIdentityIndex: AnimeIdentityIndex,
    private val idMappingStore: IdMappingStore,
    private val metadataIdParser: MetadataIdParser = MetadataIdParser()
) {
    suspend fun route(request: MetadataRequest): MetadataRoute {
        require(request.depth != MetadataDepth.PREVIEW) {
            "MetadataRouter must not be executed for initial PREVIEW rendering; use MetadataRouterFacade to defer routing."
        }

        val normalized = normalizer.normalize(request)
        val parsed = metadataIdParser.parse(normalized.parentId)
        val trace = mutableListOf<MetadataRouteTrace>()

        when (parsed.scheme) {
            AnimeIdScheme.KITSU -> {
                trace += MetadataRouteTrace(MetadataDecisionReason.KITSU_PREFIX_DIRECT, parsed.raw)
                return route(
                    normalized = normalized.copy(mediaKind = MetadataMediaKind.ANIME),
                    provider = MetadataPrimaryProvider.KITSU,
                    reason = MetadataDecisionReason.KITSU_PREFIX_DIRECT,
                    targetId = "kitsu:${parsed.value}",
                    trace = trace
                )
            }
            AnimeIdScheme.MAL,
            AnimeIdScheme.ANILIST,
            AnimeIdScheme.ANIDB -> {
                val kitsuId = animeIdentityIndex.resolveKitsuId(parsed)
                    ?.also { persistFribbMapping(parsed, it) }
                    ?: idMappingStore.lookupKitsu(parsed)?.providerId
                if (kitsuId != null) {
                    trace += MetadataRouteTrace(MetadataDecisionReason.ANIME_PREFIX_MAPPED_TO_KITSU, parsed.raw)
                    return route(
                        normalized = normalized.copy(mediaKind = MetadataMediaKind.ANIME),
                        provider = MetadataPrimaryProvider.KITSU,
                        reason = MetadataDecisionReason.ANIME_PREFIX_MAPPED_TO_KITSU,
                        targetId = kitsuId,
                        trace = trace
                    )
                }
            }
            AnimeIdScheme.IMDB -> {
                val kitsuId = idMappingStore.lookupKitsu(parsed)?.providerId
                    ?: animeIdentityIndex.resolveKitsuId(parsed)?.also { persistFribbMapping(parsed, it) }
                if (kitsuId != null) {
                    trace += MetadataRouteTrace(MetadataDecisionReason.ID_MAPPING_TO_KITSU, parsed.raw)
                    return route(
                        normalized = normalized.copy(mediaKind = MetadataMediaKind.ANIME),
                        provider = MetadataPrimaryProvider.KITSU,
                        reason = MetadataDecisionReason.ID_MAPPING_TO_KITSU,
                        targetId = kitsuId,
                        trace = trace
                    )
                }
            }
            AnimeIdScheme.TMDB -> {
                if (normalized.contentType == ContentType.MOVIE) {
                    trace += MetadataRouteTrace(MetadataDecisionReason.PROVIDER_NATIVE_DIRECT, parsed.raw)
                    return route(
                        normalized = normalized,
                        provider = MetadataPrimaryProvider.TMDB,
                        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                        targetId = parsed.raw,
                        trace = trace
                    )
                }
                trace += MetadataRouteTrace(MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT, parsed.raw)
            }
            AnimeIdScheme.TVDB -> {
                if (normalized.contentType == ContentType.SERIES) {
                    trace += MetadataRouteTrace(MetadataDecisionReason.PROVIDER_NATIVE_DIRECT, parsed.raw)
                    return route(
                        normalized = normalized,
                        provider = MetadataPrimaryProvider.TVDB,
                        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                        targetId = parsed.raw,
                        trace = trace
                    )
                }
                trace += MetadataRouteTrace(MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT, parsed.raw)
            }
            AnimeIdScheme.UNKNOWN -> Unit
        }

        val provider = when (normalized.contentType) {
            ContentType.MOVIE -> MetadataPrimaryProvider.TMDB
            ContentType.SERIES -> MetadataPrimaryProvider.TVDB
            else -> MetadataPrimaryProvider.TMDB
        }
        val reason = when (normalized.contentType) {
            ContentType.MOVIE -> MetadataDecisionReason.ITEM_TYPE_MOVIE
            ContentType.SERIES -> MetadataDecisionReason.ITEM_TYPE_SERIES
            else -> MetadataDecisionReason.UNSUPPORTED_TYPE
        }
        trace += MetadataRouteTrace(reason, normalized.contentType.name)
        return route(
            normalized = normalized,
            provider = provider,
            reason = reason,
            targetId = normalized.parentId,
            targetIdRequiresIdentityResolution = trace.any { it.reason == MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT },
            trace = trace
        )
    }

    private suspend fun persistFribbMapping(sourceId: ParsedMetadataId, kitsuId: String) {
        idMappingStore.persist(
            IdMapping(
                sourceId = sourceId,
                provider = MetadataPrimaryProvider.KITSU,
                providerId = kitsuId,
                source = IdMappingSource.FRIBB,
                evidence = "fribb_lookup"
            )
        )
    }

    private fun route(
        normalized: NormalizedMetadataRequest,
        provider: MetadataPrimaryProvider,
        reason: MetadataDecisionReason,
        targetId: String,
        targetIdRequiresIdentityResolution: Boolean = false,
        trace: List<MetadataRouteTrace>
    ): MetadataRoute =
        MetadataRoute(
            provider = provider,
            parentId = normalized.parentId,
            mediaKind = normalized.mediaKind,
            reason = reason,
            sourceContext = normalized.sourceContext,
            language = normalized.language,
            targetIds = mapOf(provider to targetId),
            targetIdRequiresIdentityResolution = targetIdRequiresIdentityResolution,
            trace = trace
        )
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterPrecedenceTest
```

Expected: PASS.

- [ ] **Step 9: Commit Task 2**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/AnimeIdentityIndex.kt app/src/main/java/com/nexio/tv/core/metadata/router/IdMappingStore.kt app/src/main/java/com/nexio/tv/core/metadata/router/LocalIdMappingStore.kt app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouter.kt app/src/main/java/com/nexio/tv/data/local/integration/ExternalIdEntity.kt app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDatabase.kt app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterPrecedenceTest.kt
git commit -m "feat(metadata): route primary metadata authority deterministically"
```

## Task 3: Provider Plan Executor And Runtime Shape Gate

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutor.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutorTest.kt`

- [ ] **Step 1: Write the failing primary plan tests**

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.integration.TvdbApiShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProviderPlanExecutorTest {
    private val executor = ProviderPlanExecutor()

    @Test
    fun `TMDB movie detail core uses runtime-covered shape`() {
        val plan = executor.buildPlan(tmdbMovieRoute(), MetadataDepth.DETAIL_CORE)

        assertEquals(listOf(TmdbApiShapes.MOVIE_CORE), plan.steps.map { it.apiShapeId })
        assertRouterPrerequisitesCover(plan)
    }

    @Test
    fun `TMDB media depth adds videos through runtime-covered shape`() {
        val plan = executor.buildPlan(tmdbMovieRoute(), MetadataDepth.DETAIL_MEDIA)

        assertTrue(plan.steps.map { it.apiShapeId }.contains(TmdbApiShapes.MOVIE_CORE))
        assertTrue(plan.steps.map { it.apiShapeId }.contains(TmdbApiShapes.MOVIE_VIDEOS))
        assertRouterPrerequisitesCover(plan)
    }

    @Test
    fun `TMDB secondary depth adds reviews and recommendations through runtime-covered shapes`() {
        val plan = executor.buildPlan(tmdbMovieRoute(), MetadataDepth.DETAIL_SECONDARY)

        assertTrue(plan.steps.map { it.apiShapeId }.contains(TmdbApiShapes.MOVIE_REVIEWS))
        assertTrue(plan.steps.map { it.apiShapeId }.contains(TmdbApiShapes.MOVIE_RECOMMENDATIONS))
        assertRouterPrerequisitesCover(plan)
    }

    @Test
    fun `TVDB detail core uses series extended and translation shapes`() {
        val plan = executor.buildPlan(tvdbSeriesRoute(language = "nl"), MetadataDepth.DETAIL_CORE)

        assertTrue(plan.steps.map { it.apiShapeId }.contains(TvdbApiShapes.SERIES_EXTENDED))
        assertTrue(plan.steps.map { it.apiShapeId }.contains(TvdbApiShapes.SERIES_TRANSLATION))
        assertRouterPrerequisitesCover(plan)
    }

    @Test
    fun `TVDB default language detail core does not require translation`() {
        val plan = executor.buildPlan(tvdbSeriesRoute(language = "en"), MetadataDepth.DETAIL_CORE)

        assertTrue(plan.steps.map { it.apiShapeId }.contains(TvdbApiShapes.SERIES_EXTENDED))
        assertEquals(false, plan.steps.map { it.apiShapeId }.contains(TvdbApiShapes.SERIES_TRANSLATION))
    }

    @Test
    fun `TVDB season depth uses season episode shapes`() {
        val plan = executor.buildPlan(tvdbSeriesRoute(), MetadataDepth.SEASON)

        assertTrue(plan.steps.map { it.apiShapeId }.contains(TvdbApiShapes.SERIES_EPISODES_SEASON_TYPE))
        assertTrue(plan.steps.map { it.apiShapeId }.contains(TvdbApiShapes.SERIES_EPISODES_LANGUAGE))
        assertRouterPrerequisitesCover(plan)
    }

    @Test
    fun `Kitsu detail core uses anime core shape`() {
        val plan = executor.buildPlan(kitsuRoute(), MetadataDepth.DETAIL_CORE)

        assertEquals(MetadataMediaKind.ANIME, plan.route.mediaKind)
        assertEquals(listOf(KitsuApiShapes.ANIME_CORE), plan.steps.map { it.apiShapeId })
        assertRouterPrerequisitesCover(plan)
    }

    @Test
    fun `Kitsu secondary detail uses advanced anime shapes`() {
        val plan = executor.buildPlan(kitsuRoute(), MetadataDepth.DETAIL_SECONDARY)
        val shapes = plan.steps.map { it.apiShapeId }

        assertTrue(shapes.contains(KitsuApiShapes.ANIME_CORE))
        assertTrue(shapes.contains(KitsuApiShapes.ANIME_EPISODES))
        assertTrue(shapes.contains(KitsuApiShapes.CASTINGS))
        assertTrue(shapes.contains(KitsuApiShapes.ANIME_STAFF))
        assertTrue(shapes.contains(KitsuApiShapes.ANIME_PRODUCTIONS))
        assertTrue(shapes.contains(KitsuApiShapes.MEDIA_RELATIONSHIPS))
        assertRouterPrerequisitesCover(plan)
    }

    private fun assertRouterPrerequisitesCover(plan: ProviderExecutionPlan) {
        val prerequisites = File("app/src/test/resources/integration/metadata_router_prerequisites.txt")
            .readLines()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotBlank() }
            .toSet()

        val missing = plan.steps.map { it.apiShapeId }.filterNot(prerequisites::contains)
        assertEquals(emptyList<String>(), missing)
    }

    private fun tmdbMovieRoute() = route(MetadataPrimaryProvider.TMDB, MetadataMediaKind.MOVIE, "tmdb:550")
    private fun tvdbSeriesRoute(language: String = "en") = route(MetadataPrimaryProvider.TVDB, MetadataMediaKind.SERIES, "tvdb:81189", language = language)
    private fun kitsuRoute() = route(MetadataPrimaryProvider.KITSU, MetadataMediaKind.ANIME, "kitsu:7442")

    private fun route(provider: MetadataPrimaryProvider, kind: MetadataMediaKind, id: String, language: String? = null) =
        MetadataRoute(
            provider = provider,
            parentId = id,
            mediaKind = kind,
            reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
            sourceContext = MetadataSourceContext(),
            language = language,
            targetIds = mapOf(provider to id),
            trace = emptyList()
        )

    @Test
    fun `plan executor refuses unresolved provider-native conflict routes`() {
        val route = MetadataRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = "tmdb:1399",
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(MetadataPrimaryProvider.TVDB to "tmdb:1399"),
            targetIdRequiresIdentityResolution = true,
            trace = listOf(MetadataRouteTrace(MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT, "tmdb:1399"))
        )

        val failure = runCatching {
            executor.buildPlan(route, MetadataDepth.DETAIL_CORE)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.ProviderPlanExecutorTest
```

Expected: FAIL with unresolved references for `ProviderPlanExecutor`, `ProviderExecutionPlan`, and `ProviderPlanStep`.

- [ ] **Step 3: Add plan models**

Append to `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`:

```kotlin
enum class ProviderPlanRole {
    PRIMARY_CORE,
    MEDIA,
    SECONDARY,
    SEASON,
    PLAYER
}

data class ProviderPlanStep(
    val apiShapeId: String,
    val provider: MetadataPrimaryProvider,
    val role: ProviderPlanRole,
    val required: Boolean
)

data class ProviderExecutionPlan(
    val route: MetadataRoute,
    val depth: MetadataDepth,
    val steps: List<ProviderPlanStep>
)
```

- [ ] **Step 4: Implement ProviderPlanExecutor**

Create `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutor.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.integration.TvdbApiShapes
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderPlanExecutor @Inject constructor() {
    fun buildPlan(route: MetadataRoute, depth: MetadataDepth): ProviderExecutionPlan {
        check(!route.targetIdRequiresIdentityResolution) {
            "ProviderPlanExecutor cannot build provider calls for ${route.provider} until a provider adapter or identity helper converts ${route.parentId} to a provider-native id."
        }

        val steps = when (route.provider) {
            MetadataPrimaryProvider.TMDB -> tmdbSteps(route, depth)
            MetadataPrimaryProvider.TVDB -> tvdbSteps(route, depth)
            MetadataPrimaryProvider.KITSU -> kitsuSteps(depth)
        }
        return ProviderExecutionPlan(route = route, depth = depth, steps = steps)
    }

    private fun tmdbSteps(route: MetadataRoute, depth: MetadataDepth): List<ProviderPlanStep> {
        val coreShape = if (route.mediaKind == MetadataMediaKind.SERIES) TmdbApiShapes.TV_CORE else TmdbApiShapes.MOVIE_CORE
        val videoShape = if (route.mediaKind == MetadataMediaKind.SERIES) TmdbApiShapes.TV_VIDEOS else TmdbApiShapes.MOVIE_VIDEOS
        val reviewShape = if (route.mediaKind == MetadataMediaKind.SERIES) TmdbApiShapes.TV_REVIEWS else TmdbApiShapes.MOVIE_REVIEWS
        val recommendationShape = if (route.mediaKind == MetadataMediaKind.SERIES) TmdbApiShapes.TV_RECOMMENDATIONS else TmdbApiShapes.MOVIE_RECOMMENDATIONS
        val steps = mutableListOf(step(coreShape, MetadataPrimaryProvider.TMDB, ProviderPlanRole.PRIMARY_CORE))
        if (depth == MetadataDepth.SEASON) steps += step(TmdbApiShapes.SEASON_EPISODES, MetadataPrimaryProvider.TMDB, ProviderPlanRole.SEASON)
        if (depth == MetadataDepth.DETAIL_MEDIA || depth == MetadataDepth.DETAIL_SECONDARY) steps += step(videoShape, MetadataPrimaryProvider.TMDB, ProviderPlanRole.MEDIA)
        if (depth == MetadataDepth.DETAIL_SECONDARY) {
            steps += step(reviewShape, MetadataPrimaryProvider.TMDB, ProviderPlanRole.SECONDARY)
            steps += step(recommendationShape, MetadataPrimaryProvider.TMDB, ProviderPlanRole.SECONDARY)
        }
        return steps
    }

    private fun tvdbSteps(route: MetadataRoute, depth: MetadataDepth): List<ProviderPlanStep> {
        val steps = mutableListOf(
            step(TvdbApiShapes.SERIES_EXTENDED, MetadataPrimaryProvider.TVDB, ProviderPlanRole.PRIMARY_CORE)
        )
        if (routeLanguageRequiresTranslation(route, depth)) {
            steps += step(TvdbApiShapes.SERIES_TRANSLATION, MetadataPrimaryProvider.TVDB, ProviderPlanRole.PRIMARY_CORE, required = false)
        }
        if (depth == MetadataDepth.SEASON) {
            steps += step(TvdbApiShapes.SERIES_EPISODES_SEASON_TYPE, MetadataPrimaryProvider.TVDB, ProviderPlanRole.SEASON)
            steps += step(TvdbApiShapes.SERIES_EPISODES_LANGUAGE, MetadataPrimaryProvider.TVDB, ProviderPlanRole.SEASON, required = false)
        }
        return steps
    }

    private fun routeLanguageRequiresTranslation(route: MetadataRoute, depth: MetadataDepth): Boolean =
        depth == MetadataDepth.DETAIL_CORE && route.language != null && route.language != "en"

    private fun kitsuSteps(depth: MetadataDepth): List<ProviderPlanStep> {
        val steps = mutableListOf(step(KitsuApiShapes.ANIME_CORE, MetadataPrimaryProvider.KITSU, ProviderPlanRole.PRIMARY_CORE))
        if (depth == MetadataDepth.SEASON || depth == MetadataDepth.DETAIL_SECONDARY) {
            steps += step(KitsuApiShapes.ANIME_EPISODES, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SEASON)
        }
        if (depth == MetadataDepth.DETAIL_SECONDARY) {
            steps += step(KitsuApiShapes.CASTINGS, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
            steps += step(KitsuApiShapes.ANIME_STAFF, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
            steps += step(KitsuApiShapes.ANIME_PRODUCTIONS, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
            steps += step(KitsuApiShapes.MEDIA_RELATIONSHIPS, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
        }
        return steps
    }

    private fun step(
        shape: String,
        provider: MetadataPrimaryProvider,
        role: ProviderPlanRole,
        required: Boolean = true
    ) = ProviderPlanStep(apiShapeId = shape, provider = provider, role = role, required = required)
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.ProviderPlanExecutorTest
```

Expected: PASS.

- [ ] **Step 6: Run the generated audit readiness gate**

Run:

```bash
./gradlew :app:generateIntegrationRuntimeAudit :app:testDebugUnitTest --tests com.nexio.tv.architecture.MetadataRouterReadinessAuditTest
```

Expected: PASS. A failure means a planned shape is not runtime-covered, not that the router test should be weakened.

- [ ] **Step 7: Commit Task 3**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutor.kt app/src/test/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutorTest.kt
git commit -m "feat(metadata): map provider plans to runtime shapes"
```

## Task 4: Secondary Resolver Orchestration

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/ResolverOrchestrator.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/ResolverOrchestratorTest.kt`

- [ ] **Step 1: Write the failing resolver-depth tests**

```kotlin
package com.nexio.tv.core.metadata.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverOrchestratorTest {
    private val orchestrator = ResolverOrchestrator()

    @Test
    fun `preview schedules no network secondary resolvers`() {
        val schedule = orchestrator.schedule(MetadataDepth.PREVIEW)

        assertEquals(MetadataDepth.PREVIEW, schedule.depth)
        assertEquals(emptyList<ResolverType>(), schedule.networkResolvers)
        assertTrue(schedule.localResolvers.contains(ResolverType.ADDON_DISPLAY))
    }

    @Test
    fun `detail core schedules cheap cached rating and artwork only`() {
        val schedule = orchestrator.schedule(MetadataDepth.DETAIL_CORE)

        assertTrue(schedule.localResolvers.contains(ResolverType.ARTWORK))
        assertTrue(schedule.localResolvers.contains(ResolverType.RATING))
        assertFalse(schedule.networkResolvers.contains(ResolverType.SKIP_SEGMENTS))
        assertFalse(schedule.networkResolvers.contains(ResolverType.RECOMMENDATIONS))
    }

    @Test
    fun `detail media schedules trailers and artwork media`() {
        val schedule = orchestrator.schedule(MetadataDepth.DETAIL_MEDIA)

        assertTrue(schedule.networkResolvers.contains(ResolverType.TRAILERS))
        assertTrue(schedule.localResolvers.contains(ResolverType.ARTWORK))
    }

    @Test
    fun `detail secondary schedules reviews recommendations and organization enrichment`() {
        val schedule = orchestrator.schedule(MetadataDepth.DETAIL_SECONDARY)

        assertTrue(schedule.networkResolvers.contains(ResolverType.REVIEWS))
        assertTrue(schedule.networkResolvers.contains(ResolverType.RECOMMENDATIONS))
        assertTrue(schedule.networkResolvers.contains(ResolverType.ORGANIZATION_PERSON))
    }

    @Test
    fun `player depth schedules player-only resolvers and no broad metadata`() {
        val schedule = orchestrator.schedule(MetadataDepth.PLAYER)

        assertTrue(schedule.networkResolvers.contains(ResolverType.TRACKING))
        assertTrue(schedule.networkResolvers.contains(ResolverType.SKIP_SEGMENTS))
        assertFalse(schedule.networkResolvers.contains(ResolverType.REVIEWS))
        assertFalse(schedule.networkResolvers.contains(ResolverType.RECOMMENDATIONS))
        assertFalse(schedule.networkResolvers.contains(ResolverType.ORGANIZATION_PERSON))
    }
}
```

Add this preview deferral test to `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterPrecedenceTest.kt`:

```kotlin
@Test
fun `initial preview rendering does not require route execution`() {
    val previewItems = listOf(
        MetadataSourceContext(addonMetadata = HomeDisplayMetadata(title = "One")),
        MetadataSourceContext(addonMetadata = HomeDisplayMetadata(title = "Two"))
    )

    val renderedTitles = previewItems.map { it.addonMetadata?.title }

    assertEquals(listOf("One", "Two"), renderedTitles)
    assertEquals(emptyList<AnimeIdentityLookup>(), animeIdentityIndex.lookups)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.ResolverOrchestratorTest
```

Expected: FAIL with unresolved references for `ResolverOrchestrator`, `ResolverType`, and `ResolverSchedule`.

- [ ] **Step 3: Add resolver models**

Append to `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`:

```kotlin
enum class ResolverType {
    ADDON_DISPLAY,
    RATING,
    ARTWORK,
    REVIEWS,
    TRACKING,
    SKIP_SEGMENTS,
    TRAILERS,
    RECOMMENDATIONS,
    ORGANIZATION_PERSON
}

data class ResolverSchedule(
    val depth: MetadataDepth,
    val localResolvers: List<ResolverType>,
    val networkResolvers: List<ResolverType>
)
```

- [ ] **Step 4: Implement ResolverOrchestrator**

Create `app/src/main/java/com/nexio/tv/core/metadata/router/ResolverOrchestrator.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResolverOrchestrator @Inject constructor() {
    fun schedule(depth: MetadataDepth): ResolverSchedule {
        val local = mutableListOf(ResolverType.ADDON_DISPLAY)
        val network = mutableListOf<ResolverType>()

        when (depth) {
            MetadataDepth.PREVIEW -> Unit
            MetadataDepth.DETAIL_CORE -> {
                local += ResolverType.RATING
                local += ResolverType.ARTWORK
            }
            MetadataDepth.DETAIL_MEDIA -> {
                local += ResolverType.ARTWORK
                network += ResolverType.TRAILERS
            }
            MetadataDepth.DETAIL_SECONDARY -> {
                local += ResolverType.RATING
                local += ResolverType.ARTWORK
                network += ResolverType.REVIEWS
                network += ResolverType.RECOMMENDATIONS
                network += ResolverType.ORGANIZATION_PERSON
            }
            MetadataDepth.SEASON -> {
                local += ResolverType.RATING
            }
            MetadataDepth.PLAYER -> {
                network += ResolverType.TRACKING
                network += ResolverType.SKIP_SEGMENTS
            }
        }

        return ResolverSchedule(depth = depth, localResolvers = local.distinct(), networkResolvers = network.distinct())
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.ResolverOrchestratorTest
```

Expected: PASS.

- [ ] **Step 6: Commit Task 4**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt app/src/main/java/com/nexio/tv/core/metadata/router/ResolverOrchestrator.kt app/src/test/java/com/nexio/tv/core/metadata/router/ResolverOrchestratorTest.kt
git commit -m "feat(metadata): schedule secondary resolvers by depth"
```

## Task 5: Field Ownership Resolver

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverTest.kt`

- [ ] **Step 1: Write the failing field-ownership tests**

```kotlin
package com.nexio.tv.core.metadata.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldResolverTest {
    private val resolver = FieldResolver()

    @Test
    fun `secondary rating cannot overwrite primary title`() {
        val document = resolver.resolve(
            primary = MetadataCandidate(
                provider = MetadataPrimaryProvider.TMDB,
                fields = mapOf(
                    ResolvedField.TITLE to FieldValue("Primary Movie", FieldOwner.PRIMARY),
                    ResolvedField.OVERVIEW to FieldValue("Primary overview", FieldOwner.PRIMARY)
                )
            ),
            secondary = listOf(
                MetadataCandidate(
                    provider = MetadataPrimaryProvider.TMDB,
                    resolverType = ResolverType.RATING,
                    fields = mapOf(
                        ResolvedField.TITLE to FieldValue("Wrong Secondary Title", FieldOwner.RATING),
                        ResolvedField.RATING to FieldValue(8.7f, FieldOwner.RATING)
                    )
                )
            )
        )

        assertEquals("Primary Movie", document.title)
        assertEquals(8.7f, document.rating)
        assertTrue(document.ignoredOverwrites.any { it.field == ResolvedField.TITLE && it.attemptedOwner == FieldOwner.RATING })
    }

    @Test
    fun `artwork provider can fill poster but cannot change canonical identity`() {
        val document = resolver.resolve(
            primary = MetadataCandidate(
                provider = MetadataPrimaryProvider.KITSU,
                fields = mapOf(
                    ResolvedField.CANONICAL_ID to FieldValue("kitsu:7442", FieldOwner.PRIMARY),
                    ResolvedField.TITLE to FieldValue("Anime Title", FieldOwner.PRIMARY)
                )
            ),
            secondary = listOf(
                MetadataCandidate(
                    provider = MetadataPrimaryProvider.KITSU,
                    resolverType = ResolverType.ARTWORK,
                    fields = mapOf(
                        ResolvedField.CANONICAL_ID to FieldValue("tmdb:999", FieldOwner.ARTWORK),
                        ResolvedField.POSTER to FieldValue("https://poster.example/anime.jpg", FieldOwner.ARTWORK)
                    )
                )
            )
        )

        assertEquals("kitsu:7442", document.canonicalId)
        assertEquals("https://poster.example/anime.jpg", document.poster)
        assertTrue(document.ignoredOverwrites.any { it.field == ResolvedField.CANONICAL_ID && it.attemptedOwner == FieldOwner.ARTWORK })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.FieldResolverTest
```

Expected: FAIL with unresolved references for `FieldResolver`, `MetadataCandidate`, `ResolvedField`, and `FieldOwner`.

- [ ] **Step 3: Add field-resolution models**

Append to `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`:

```kotlin
enum class ResolvedField {
    CANONICAL_ID,
    TITLE,
    OVERVIEW,
    RELEASE_DATE,
    RUNTIME,
    GENRES,
    AGE_RATING,
    CAST,
    CREW,
    EPISODES,
    POSTER,
    BACKDROP,
    LOGO,
    RATING,
    REVIEWS,
    TRAILERS,
    RECOMMENDATIONS,
    TRACKING,
    SKIP_SEGMENTS
}

enum class FieldOwner {
    PRIMARY,
    ARTWORK,
    RATING,
    REVIEWS,
    TRAILERS,
    RECOMMENDATIONS,
    TRACKING,
    SKIP_SEGMENTS,
    ORGANIZATION_PERSON
}

data class FieldValue(
    val value: Any,
    val owner: FieldOwner
)

data class MetadataCandidate(
    val provider: MetadataPrimaryProvider,
    val resolverType: ResolverType? = null,
    val fields: Map<ResolvedField, FieldValue>
)

data class IgnoredFieldOverwrite(
    val field: ResolvedField,
    val existingOwner: FieldOwner,
    val attemptedOwner: FieldOwner,
    val attemptedValue: Any
)

data class ResolvedMetadataDocument(
    val canonicalId: String? = null,
    val title: String? = null,
    val overview: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val rating: Float? = null,
    val fieldOwners: Map<ResolvedField, FieldOwner> = emptyMap(),
    val ignoredOverwrites: List<IgnoredFieldOverwrite> = emptyList()
)
```

- [ ] **Step 4: Implement FieldResolver**

Create `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FieldResolver @Inject constructor() {
    private val primaryOwnedFields = setOf(
        ResolvedField.CANONICAL_ID,
        ResolvedField.TITLE,
        ResolvedField.OVERVIEW,
        ResolvedField.RELEASE_DATE,
        ResolvedField.RUNTIME,
        ResolvedField.GENRES,
        ResolvedField.AGE_RATING,
        ResolvedField.CAST,
        ResolvedField.CREW,
        ResolvedField.EPISODES
    )

    fun resolve(primary: MetadataCandidate, secondary: List<MetadataCandidate>): ResolvedMetadataDocument {
        val fields = linkedMapOf<ResolvedField, FieldValue>()
        val ignored = mutableListOf<IgnoredFieldOverwrite>()

        primary.fields.forEach { (field, value) ->
            fields[field] = value.copy(owner = FieldOwner.PRIMARY)
        }

        secondary.flatMap { it.fields.entries }.forEach { (field, incoming) ->
            val existing = fields[field]
            if (existing != null && field in primaryOwnedFields && existing.owner == FieldOwner.PRIMARY) {
                ignored += IgnoredFieldOverwrite(
                    field = field,
                    existingOwner = existing.owner,
                    attemptedOwner = incoming.owner,
                    attemptedValue = incoming.value
                )
            } else if (existing == null || existing.value == "") {
                fields[field] = incoming
            }
        }

        return ResolvedMetadataDocument(
            canonicalId = fields[ResolvedField.CANONICAL_ID]?.value as? String,
            title = fields[ResolvedField.TITLE]?.value as? String,
            overview = fields[ResolvedField.OVERVIEW]?.value as? String,
            poster = fields[ResolvedField.POSTER]?.value as? String,
            backdrop = fields[ResolvedField.BACKDROP]?.value as? String,
            logo = fields[ResolvedField.LOGO]?.value as? String,
            rating = fields[ResolvedField.RATING]?.value as? Float,
            fieldOwners = fields.mapValues { it.value.owner },
            ignoredOverwrites = ignored
        )
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.FieldResolverTest
```

Expected: PASS.

- [ ] **Step 6: Commit Task 5**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverTest.kt
git commit -m "feat(metadata): enforce field ownership in resolved documents"
```

## Task 6: Router Cache Keys And Artwork Policy Separation

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataCacheKeys.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataCacheKeysTest.kt`

- [ ] **Step 1: Write the failing cache-key tests**

```kotlin
package com.nexio.tv.core.metadata.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MetadataCacheKeysTest {
    private val keys = MetadataCacheKeys()

    @Test
    fun `router decision key includes parent id source context and policy version`() {
        val key = keys.routerDecisionKey(
            parentId = "tt123",
            sourceContext = MetadataSourceContext(addonId = "crunchyroll", catalogId = "anime-popular"),
            routingPolicyVersion = 3
        )

        assertEquals("router:v3:parent=tt123:addon=crunchyroll:catalog=anime-popular", key)
    }

    @Test
    fun `resolved document key changes with field policy version`() {
        val route = route("tt123", MetadataPrimaryProvider.TMDB)

        val first = keys.resolvedDocumentKey(route, MetadataDepth.DETAIL_CORE, fieldPolicyVersion = 1, artworkPolicyVersion = 1)
        val second = keys.resolvedDocumentKey(route, MetadataDepth.DETAIL_CORE, fieldPolicyVersion = 2, artworkPolicyVersion = 1)

        assertNotEquals(first, second)
    }

    @Test
    fun `artwork policy change invalidates artwork and resolved document but not provider metadata`() {
        val route = route("tt123", MetadataPrimaryProvider.TMDB)

        val providerBefore = keys.providerMetadataKey(provider = MetadataPrimaryProvider.TMDB, apiShapeId = "tmdb.movie.core", operationKey = "tmdb:movie:123")
        val providerAfter = keys.providerMetadataKey(provider = MetadataPrimaryProvider.TMDB, apiShapeId = "tmdb.movie.core", operationKey = "tmdb:movie:123")
        val artworkBefore = keys.artworkDecisionKey(route, artworkPolicyVersion = 1)
        val artworkAfter = keys.artworkDecisionKey(route, artworkPolicyVersion = 2)
        val resolvedBefore = keys.resolvedDocumentKey(route, MetadataDepth.DETAIL_CORE, fieldPolicyVersion = 1, artworkPolicyVersion = 1)
        val resolvedAfter = keys.resolvedDocumentKey(route, MetadataDepth.DETAIL_CORE, fieldPolicyVersion = 1, artworkPolicyVersion = 2)

        assertEquals(providerBefore, providerAfter)
        assertNotEquals(artworkBefore, artworkAfter)
        assertNotEquals(resolvedBefore, resolvedAfter)
    }

    @Test
    fun `cache keys never include hashCode evidence`() {
        val key = keys.artworkDecisionKey(route("tt123", MetadataPrimaryProvider.TMDB), artworkPolicyVersion = 5)

        assertFalse(key.contains("hashCode"))
    }

    private fun route(parentId: String, provider: MetadataPrimaryProvider) =
        MetadataRoute(
            provider = provider,
            parentId = parentId,
            mediaKind = MetadataMediaKind.MOVIE,
            reason = MetadataDecisionReason.ITEM_TYPE_MOVIE,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(provider to parentId),
            trace = emptyList()
        )
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataCacheKeysTest
```

Expected: FAIL with unresolved reference `MetadataCacheKeys`.

- [ ] **Step 3: Implement cache-key builder**

Create `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataCacheKeys.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataCacheKeys @Inject constructor() {
    fun providerMetadataKey(
        provider: MetadataPrimaryProvider,
        apiShapeId: String,
        operationKey: String
    ): String = "provider:${provider.name.lowercase()}:shape=$apiShapeId:operation=$operationKey"

    fun routerDecisionKey(
        parentId: String,
        sourceContext: MetadataSourceContext,
        routingPolicyVersion: Int
    ): String =
        "router:v$routingPolicyVersion:" +
            "parent=${parentId.trim()}:" +
            "addon=${sourceContext.addonId.orEmpty()}:" +
            "catalog=${sourceContext.catalogId.orEmpty()}"

    fun resolvedDocumentKey(
        route: MetadataRoute,
        depth: MetadataDepth,
        fieldPolicyVersion: Int,
        artworkPolicyVersion: Int
    ): String =
        "resolved:field=v$fieldPolicyVersion:artwork=v$artworkPolicyVersion:" +
            "provider=${route.provider.name.lowercase()}:parent=${route.parentId}:depth=${depth.name.lowercase()}"

    fun artworkDecisionKey(
        route: MetadataRoute,
        artworkPolicyVersion: Int
    ): String =
        "artwork:v$artworkPolicyVersion:provider=${route.provider.name.lowercase()}:parent=${route.parentId}"

    fun imageBlobKey(urlHash: String): String =
        "image:blob:sha256=$urlHash"
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataCacheKeysTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 6**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataCacheKeys.kt app/src/test/java/com/nexio/tv/core/metadata/router/MetadataCacheKeysTest.kt
git commit -m "feat(metadata): separate router artwork and provider cache keys"
```

## Task 7: Continue Watching Route And Click-Time Metadata Contract

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingTimeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMetadataRouterTest.kt`

- [ ] **Step 1: Write the failing Continue Watching contract tests**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.domain.model.HomeDisplayMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingMetadataRouterTest {
    @Test
    fun `playback snapshot stores parent id route provider and click-time addon metadata`() {
        val route = MetadataRoute(
            provider = MetadataPrimaryProvider.KITSU,
            parentId = "tt12343534",
            mediaKind = MetadataMediaKind.ANIME,
            reason = MetadataDecisionReason.ID_MAPPING_TO_KITSU,
            sourceContext = MetadataSourceContext(addonId = "crunchyroll"),
            targetIds = mapOf(MetadataPrimaryProvider.KITSU to "tt12343534"),
            trace = emptyList()
        )
        val clickTime = HomeDisplayMetadata(
            title = "Addon Anime Title",
            poster = "https://addon.example/poster.jpg",
            description = "Addon description"
        )

        val snapshot = ContinueWatchingMetadataSnapshot.fromRoute(route, clickTime)

        assertEquals("tt12343534", snapshot.parentId)
        assertEquals("KITSU", snapshot.primaryProvider)
        assertEquals("ID_MAPPING_TO_KITSU", snapshot.decisionReason)
        assertEquals(ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION, snapshot.routingVersion)
        assertEquals(clickTime, snapshot.clickTimeDisplayMetadata)
    }

    @Test
    fun `routing version mismatch requires one reroute`() {
        assertEquals(true, ContinueWatchingMetadataSnapshot.shouldReroute(0))
        assertEquals(false, ContinueWatchingMetadataSnapshot.shouldReroute(ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION))
    }

    @Test
    fun `offline render uses canonical then click-time then persisted fallback order`() {
        val clickTime = HomeDisplayMetadata(title = "Click Title", poster = "click.jpg", description = "Click description")
        val persisted = HomeDisplayMetadata(title = "Old Title", poster = "old.jpg", description = "Old description")

        val rendered = ContinueWatchingMetadataSnapshot.renderDisplayMetadata(
            canonical = HomeDisplayMetadata(title = null, poster = "canonical.jpg", description = null),
            clickTime = clickTime,
            persistedFallback = persisted
        )

        assertEquals("Click Title", rendered.title)
        assertEquals("canonical.jpg", rendered.poster)
        assertEquals("Click description", rendered.description)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingMetadataRouterTest
```

Expected: FAIL with unresolved reference `ContinueWatchingMetadataSnapshot`.

- [ ] **Step 3: Add a route-aware metadata snapshot helper**

Add this small model near the existing Continue Watching models in `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt` or split it into `ContinueWatchingMetadataSnapshot.kt` if the existing service is already large:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.mergeFallback

data class ContinueWatchingMetadataSnapshot(
    val routingVersion: Int,
    val parentId: String,
    val primaryProvider: String,
    val decisionReason: String,
    val clickTimeDisplayMetadata: HomeDisplayMetadata
) {
    companion object {
        fun fromRoute(
            route: MetadataRoute,
            clickTimeDisplayMetadata: HomeDisplayMetadata
        ): ContinueWatchingMetadataSnapshot =
            ContinueWatchingMetadataSnapshot(
                routingVersion = CURRENT_ROUTING_VERSION,
                parentId = route.parentId,
                primaryProvider = route.provider.name,
                decisionReason = route.reason.name,
                clickTimeDisplayMetadata = clickTimeDisplayMetadata
            )

        const val CURRENT_ROUTING_VERSION = 1

        fun shouldReroute(storedRoutingVersion: Int): Boolean =
            storedRoutingVersion != CURRENT_ROUTING_VERSION

        fun renderDisplayMetadata(
            canonical: HomeDisplayMetadata?,
            clickTime: HomeDisplayMetadata?,
            persistedFallback: HomeDisplayMetadata?
        ): HomeDisplayMetadata =
            (canonical ?: HomeDisplayMetadata())
                .mergeFallback(clickTime)
                .mergeFallback(persistedFallback)
    }
}
```

Increment `CURRENT_ROUTING_VERSION` in the same change whenever routing precedence, AnimeIdentityIndex behavior, or IdMappingStore semantics change.

- [ ] **Step 4: Wire snapshot fields into existing storage paths**

Modify existing `WatchProgress`/snapshot creation code so playback start calls the router before persistence and stores:

```kotlin
val route = metadataRouter.route(metadataRequest)
val snapshot = ContinueWatchingMetadataSnapshot.fromRoute(
    route = route,
    clickTimeDisplayMetadata = item.toHomeDisplayMetadata()
)
```

Persist `snapshot.routingVersion`, `snapshot.parentId`, `snapshot.primaryProvider`, `snapshot.decisionReason`, and serialized `snapshot.clickTimeDisplayMetadata` in the same row or snapshot object that currently stores Continue Watching display fields. If the current storage type already has equivalent nullable columns/fields, use those names instead of adding duplicate fields.

On Continue Watching read, reroute exactly once when:

```kotlin
ContinueWatchingMetadataSnapshot.shouldReroute(storedRoutingVersion)
```

- [ ] **Step 5: Update render merge order**

Modify Continue Watching render assembly so display metadata uses this order:

```kotlin
val displayMetadata = ContinueWatchingMetadataSnapshot.renderDisplayMetadata(
    canonical = canonicalRefresh,
    clickTime = storedClickTimeDisplayMetadata,
    persistedFallback = existingPersistedDisplayMetadata
)
```

- [ ] **Step 6: Run the Continue Watching tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingMetadataRouterTest --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest --tests com.nexio.tv.data.repository.ContinueWatchingTimelineTest
```

Expected: PASS.

- [ ] **Step 7: Commit Task 7**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingTimeline.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMetadataRouterTest.kt
git commit -m "feat(metadata): persist route context for continue watching"
```

## Task 8: Production Facade And Migration Boundary

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/IntegrationProviderModule.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProviderLocalizedMetadataOverlay.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Test: `app/src/test/java/com/nexio/tv/architecture/MetadataRouterBoundaryTest.kt`

- [ ] **Step 1: Write the failing boundary test**

```kotlin
package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRouterBoundaryTest {
    private val productionRoots = listOf(File("app/src/main/java/com/nexio/tv"))

    @Test
    fun `production callers no longer import TvMetadataRouter`() {
        val offenders = productionRoots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.invariantSeparatorsPath.endsWith("/core/tvdb/TvMetadataRouter.kt") }
                .filter { it.readText().contains("TvMetadataRouter") }
                .map { it.invariantSeparatorsPath }
                .toList()
        }

        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `router and resolver layers do not inject raw provider clients`() {
        val forbidden = listOf("TmdbApi", "TvdbApi", "KitsuApi", "OkHttpClient", "Retrofit", "AuthService")
        val offenders = File("app/src/main/java/com/nexio/tv/core/metadata/router")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                forbidden.filter(text::contains).map { "${file.invariantSeparatorsPath}:$it" }
            }
            .toList()

        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `router facade exists as migration target`() {
        val facade = File("app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt")
        assertTrue(facade.isFile)
        assertTrue(facade.readText().contains("class MetadataRouterFacade"))
    }
}
```

- [ ] **Step 2: Run the boundary test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.architecture.MetadataRouterBoundaryTest
```

Expected: FAIL because the facade does not exist and production files still reference `TvMetadataRouter`.

- [ ] **Step 3: Add MetadataRouterFacade**

Create `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.HomeDisplayMetadata
import javax.inject.Inject
import javax.inject.Singleton

data class MetadataFacadeResult(
    val route: MetadataRoute?,
    val plan: ProviderExecutionPlan?,
    val resolverSchedule: ResolverSchedule,
    val displayMetadata: HomeDisplayMetadata
)

@Singleton
class MetadataRouterFacade @Inject constructor(
    private val router: MetadataRouter,
    private val providerPlanExecutor: ProviderPlanExecutor,
    private val resolverOrchestrator: ResolverOrchestrator
) {
    suspend fun resolveRequest(request: MetadataRequest): MetadataFacadeResult {
        if (request.depth == MetadataDepth.PREVIEW) {
            return MetadataFacadeResult(
                route = null,
                plan = null,
                resolverSchedule = resolverOrchestrator.schedule(MetadataDepth.PREVIEW),
                displayMetadata = request.sourceContext.addonMetadata ?: HomeDisplayMetadata()
            )
        }

        val route = router.route(request)
        val plan = providerPlanExecutor.buildPlan(route, request.depth)
        val schedule = resolverOrchestrator.schedule(request.depth)
        return MetadataFacadeResult(
            route = route,
            plan = plan,
            resolverSchedule = schedule,
            displayMetadata = request.sourceContext.addonMetadata ?: HomeDisplayMetadata()
        )
    }
}
```

- [ ] **Step 4: Add DI binding for IdMappingStore**

Modify `app/src/main/java/com/nexio/tv/core/di/IntegrationProviderModule.kt` or create a focused metadata router module if existing DI organization prefers it:

```kotlin
@Provides
@Singleton
fun provideIdMappingStore(localIdMappingStore: LocalIdMappingStore): IdMappingStore = localIdMappingStore
```

Import:

```kotlin
import com.nexio.tv.core.metadata.router.IdMappingStore
import com.nexio.tv.core.metadata.router.LocalIdMappingStore
```

- [ ] **Step 5: Migrate production callers from TvMetadataRouter to MetadataRouterFacade**

For each production caller that imports or injects `TvMetadataRouter`, replace constructor dependencies with `MetadataRouterFacade` and construct `MetadataRequest` using:

```kotlin
MetadataRequest(
    contentId = meta.id,
    contentType = meta.type,
    sourceContext = MetadataSourceContext(
        addonId = addonId,
        catalogId = catalogId,
        catalogType = catalogType,
        itemType = meta.rawType,
        sourceName = sourceName,
        addonMetadata = meta.toHomeDisplayMetadata(),
        rowItemIds = rowItems.map { it.id }
    ),
    language = meta.language,
    depth = MetadataDepth.DETAIL_CORE
)
```

Use `MetadataDepth.PREVIEW` for home/catalog row initial render, `DETAIL_CORE` for detail screen identity/title/overview, `DETAIL_MEDIA` when trailers or media are requested, `SEASON` for episode list, and `PLAYER` for playback start tracking/skip context.

- [ ] **Step 6: Run boundary and migrated caller tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.architecture.MetadataRouterBoundaryTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsTvdbProviderRoutingTest --tests com.nexio.tv.ui.screens.home.HomeViewModelTvdbProviderRoutingTest --tests com.nexio.tv.ui.screens.home.HomeViewModelKitsuCatalogPlanTest
```

Expected: PASS.

- [ ] **Step 7: Commit Task 8**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt app/src/main/java/com/nexio/tv/core/di/IntegrationProviderModule.kt app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeProviderLocalizedMetadataOverlay.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt app/src/test/java/com/nexio/tv/architecture/MetadataRouterBoundaryTest.kt
git commit -m "feat(metadata): migrate callers to metadata router facade"
```

## Task 9: Full Gate Verification And OpenSpec Closure Evidence

**Files:**
- Modify: `openspec/changes/add-metadata-router/tasks.md`
- No production code changes unless verification reveals a real failure.

- [ ] **Step 1: Run focused router unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRequestNormalizerTest --tests com.nexio.tv.core.metadata.router.MetadataRouterPrecedenceTest --tests com.nexio.tv.core.metadata.router.ProviderPlanExecutorTest --tests com.nexio.tv.core.metadata.router.ResolverOrchestratorTest --tests com.nexio.tv.core.metadata.router.FieldResolverTest --tests com.nexio.tv.core.metadata.router.MetadataCacheKeysTest
```

Expected: PASS.

- [ ] **Step 2: Run Continue Watching and migration tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingMetadataRouterTest --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest --tests com.nexio.tv.architecture.MetadataRouterBoundaryTest
```

Expected: PASS.

- [ ] **Step 3: Run IntegrationRuntime audit and MetadataRouter readiness gate**

Run:

```bash
./gradlew :app:generateIntegrationRuntimeAudit :app:testDebugUnitTest --tests com.nexio.tv.architecture.MetadataRouterReadinessAuditTest
```

Expected: PASS. The generated `app/build/reports/integration-runtime-audit/metadata-router-readiness.csv` must contain no `ACTIVE_REQUIRED_MISSING` rows.

- [ ] **Step 4: Run OpenSpec validation**

Run:

```bash
openspec validate add-metadata-router --strict
```

Expected: `Change 'add-metadata-router' is valid`.

- [ ] **Step 5: Run a release compile smoke check**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 6: Mark OpenSpec tasks complete**

Modify `openspec/changes/add-metadata-router/tasks.md` so completed items use checked boxes:

```markdown
- [x] Add request normalization and `parentIdOf()` behavior.
- [x] Add `MetadataRouter` route result model with trace evidence.
- [x] Add `IdMappingStore` and mapping source semantics.
- [x] Add AnimeIdentityIndex / Fribb lookup for MAL, AniList, AniDB, and IMDb anime detection.
- [x] Add tests for `kitsu:` direct routing, MAL/AniList/AniDB-to-Kitsu mapping, IMDb local/Fribb anime mapping, provider-native TMDB/TVDB direct routing, provider-native conflict tracing, forbidden catalog-label routing, item-type fallback, Disney mixed rows, and episode parent normalization.
```

Apply the same checked-box format for Tasks 2 through 7 only after the commands above pass.

- [ ] **Step 7: Commit verification metadata**

```bash
git add openspec/changes/add-metadata-router/tasks.md
git commit -m "docs(metadata): record metadata router gate completion"
```

## Final Verification Commands

Run these before claiming the implementation is complete:

```bash
./gradlew :app:generateIntegrationRuntimeAudit
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.architecture.MetadataRouterReadinessAuditTest
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.architecture.MetadataRouterBoundaryTest
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterPrecedenceTest
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.ProviderPlanExecutorTest
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.FieldResolverTest
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingMetadataRouterTest
openspec validate add-metadata-router --strict
./gradlew :app:compileDebugKotlin
```

Expected: every command passes.

## Self-Review Checklist

- Spec coverage: Tasks 1 and 2 cover deterministic routing, direct Kitsu prefixes, mapped anime prefixes, neutral-id mapping, parent ids, item-type fallback, and trace evidence. Task 3 covers runtime-covered primary shape plans. Task 4 covers resolver depth. Task 5 covers field ownership. Task 6 covers cache and artwork separation. Task 7 covers Continue Watching. Task 8 covers migration and IntegrationRuntime boundary. Task 9 covers generated audit and OpenSpec proof.
- Placeholder scan: This plan contains no deferred implementation markers. Every implementation step includes exact files, code, commands, and expected outcomes.
- Type consistency: Model names introduced in Task 1 are reused by Tasks 2 through 9; provider shape constants use the existing `IntegrationApiShapes.kt` objects; tests reference the exact package `com.nexio.tv.core.metadata.router`.
