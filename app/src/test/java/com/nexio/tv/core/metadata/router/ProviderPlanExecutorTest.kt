package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.integration.TvdbApiShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPlanExecutorTest {
    private val executor = ProviderPlanExecutor()
    private val coveredShapeIds = javaClass.classLoader!!
        .getResourceAsStream("integration/metadata_router_prerequisites.txt")!!
        .bufferedReader()
        .useLines { lines ->
            lines
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        }

    @Test
    fun `TMDB movie DETAIL_CORE uses movie core shape covered by runtime prerequisites`() {
        val plan = executor.buildPlan(
            route = route(
                provider = MetadataPrimaryProvider.TMDB,
                mediaKind = MetadataMediaKind.MOVIE
            ),
            depth = MetadataDepth.DETAIL_CORE
        )

        assertEquals(listOf(TmdbApiShapes.MOVIE_CORE), plan.apiShapeIds())
        assertAllShapesCovered(plan)
    }

    @Test
    fun `TMDB DETAIL_MEDIA includes movie core and videos`() {
        val plan = executor.buildPlan(
            route = route(
                provider = MetadataPrimaryProvider.TMDB,
                mediaKind = MetadataMediaKind.MOVIE
            ),
            depth = MetadataDepth.DETAIL_MEDIA
        )

        assertEquals(
            listOf(TmdbApiShapes.MOVIE_CORE, TmdbApiShapes.MOVIE_VIDEOS),
            plan.apiShapeIds()
        )
        assertAllShapesCovered(plan)
    }

    @Test
    fun `TMDB DETAIL_SECONDARY includes movie secondary shapes`() {
        val plan = executor.buildPlan(
            route = route(
                provider = MetadataPrimaryProvider.TMDB,
                mediaKind = MetadataMediaKind.MOVIE
            ),
            depth = MetadataDepth.DETAIL_SECONDARY
        )

        assertEquals(
            listOf(
                TmdbApiShapes.MOVIE_CORE,
                TmdbApiShapes.MOVIE_VIDEOS,
                TmdbApiShapes.MOVIE_REVIEWS,
                TmdbApiShapes.MOVIE_RECOMMENDATIONS
            ),
            plan.apiShapeIds()
        )
        assertAllShapesCovered(plan)
    }

    @Test
    fun `TMDB series DETAIL_CORE uses route media kind for TV core shape`() {
        val plan = executor.buildPlan(
            route = route(
                provider = MetadataPrimaryProvider.TMDB,
                mediaKind = MetadataMediaKind.SERIES
            ),
            depth = MetadataDepth.DETAIL_CORE
        )

        assertEquals(listOf(TmdbApiShapes.TV_CORE), plan.apiShapeIds())
        assertAllShapesCovered(plan)
    }

    @Test
    fun `TMDB series DETAIL_MEDIA uses route media kind for TV core and videos`() {
        val plan = executor.buildPlan(
            route = route(
                provider = MetadataPrimaryProvider.TMDB,
                mediaKind = MetadataMediaKind.SERIES
            ),
            depth = MetadataDepth.DETAIL_MEDIA
        )

        assertEquals(
            listOf(TmdbApiShapes.TV_CORE, TmdbApiShapes.TV_VIDEOS),
            plan.apiShapeIds()
        )
        assertAllShapesCovered(plan)
    }

    @Test
    fun `TMDB series DETAIL_SECONDARY uses route media kind for TV secondary shapes`() {
        val plan = executor.buildPlan(
            route = route(
                provider = MetadataPrimaryProvider.TMDB,
                mediaKind = MetadataMediaKind.SERIES
            ),
            depth = MetadataDepth.DETAIL_SECONDARY
        )

        assertEquals(
            listOf(
                TmdbApiShapes.TV_CORE,
                TmdbApiShapes.TV_VIDEOS,
                TmdbApiShapes.TV_REVIEWS,
                TmdbApiShapes.TV_RECOMMENDATIONS
            ),
            plan.apiShapeIds()
        )
        assertAllShapesCovered(plan)
    }

    @Test
    fun `TMDB series SEASON includes season episodes`() {
        val plan = executor.buildPlan(
            route = route(
                provider = MetadataPrimaryProvider.TMDB,
                mediaKind = MetadataMediaKind.SERIES
            ),
            depth = MetadataDepth.SEASON
        )

        assertEquals(
            listOf(TmdbApiShapes.TV_CORE, TmdbApiShapes.SEASON_EPISODES),
            plan.apiShapeIds()
        )
        assertAllShapesCovered(plan)
    }

    @Test
    fun `TVDB DETAIL_CORE with non-default language includes optional series translation`() {
        val plan = executor.buildPlan(
            route = route(
                provider = MetadataPrimaryProvider.TVDB,
                mediaKind = MetadataMediaKind.SERIES,
                language = "nl"
            ),
            depth = MetadataDepth.DETAIL_CORE
        )

        assertEquals(
            listOf(TvdbApiShapes.SERIES_EXTENDED, TvdbApiShapes.SERIES_TRANSLATION),
            plan.apiShapeIds()
        )
        assertFalse(plan.step(TvdbApiShapes.SERIES_TRANSLATION).required)
        assertAllShapesCovered(plan)
    }

    @Test
    fun `TVDB DETAIL_CORE with default language does not include series translation`() {
        val plan = executor.buildPlan(
            route = route(
                provider = MetadataPrimaryProvider.TVDB,
                mediaKind = MetadataMediaKind.SERIES,
                language = "en"
            ),
            depth = MetadataDepth.DETAIL_CORE
        )

        assertEquals(listOf(TvdbApiShapes.SERIES_EXTENDED), plan.apiShapeIds())
        assertFalse(plan.apiShapeIds().contains(TvdbApiShapes.SERIES_TRANSLATION))
        assertAllShapesCovered(plan)
    }

    @Test
    fun `TVDB DETAIL_CORE with English language variants does not include series translation`() {
        listOf("EN", "en-US", "en_GB").forEach { language ->
            val plan = executor.buildPlan(
                route = route(
                    provider = MetadataPrimaryProvider.TVDB,
                    mediaKind = MetadataMediaKind.SERIES,
                    language = language
                ),
                depth = MetadataDepth.DETAIL_CORE
            )

            assertEquals("language=$language", listOf(TvdbApiShapes.SERIES_EXTENDED), plan.apiShapeIds())
            assertFalse("language=$language", plan.apiShapeIds().contains(TvdbApiShapes.SERIES_TRANSLATION))
            assertAllShapesCovered(plan)
        }
    }

    @Test
    fun `TVDB DETAIL_CORE with unknown non-English locale includes optional series translation`() {
        listOf("it", "ja-JP").forEach { language ->
            val plan = executor.buildPlan(
                route = route(
                    provider = MetadataPrimaryProvider.TVDB,
                    mediaKind = MetadataMediaKind.SERIES,
                    language = language
                ),
                depth = MetadataDepth.DETAIL_CORE
            )

            assertEquals(
                "language=$language",
                listOf(TvdbApiShapes.SERIES_EXTENDED, TvdbApiShapes.SERIES_TRANSLATION),
                plan.apiShapeIds()
            )
            assertFalse("language=$language", plan.step(TvdbApiShapes.SERIES_TRANSLATION).required)
            assertAllShapesCovered(plan)
        }
    }

    @Test
    fun `TVDB SEASON includes season type and language episode shapes`() {
        val plan = executor.buildPlan(
            route = route(
                provider = MetadataPrimaryProvider.TVDB,
                mediaKind = MetadataMediaKind.SERIES,
                language = "nl"
            ),
            depth = MetadataDepth.SEASON
        )

        assertEquals(
            listOf(
                TvdbApiShapes.SERIES_EXTENDED,
                TvdbApiShapes.SERIES_EPISODES_SEASON_TYPE,
                TvdbApiShapes.SERIES_EPISODES_LANGUAGE
            ),
            plan.apiShapeIds()
        )
        assertFalse(plan.step(TvdbApiShapes.SERIES_EPISODES_LANGUAGE).required)
        assertAllShapesCovered(plan)
    }

    @Test
    fun `Kitsu DETAIL_CORE uses anime core and route media kind is anime`() {
        val route = route(
            provider = MetadataPrimaryProvider.KITSU,
            mediaKind = MetadataMediaKind.ANIME
        )

        val plan = executor.buildPlan(route = route, depth = MetadataDepth.DETAIL_CORE)

        assertEquals(MetadataMediaKind.ANIME, plan.route.mediaKind)
        assertEquals(listOf(KitsuApiShapes.ANIME_CORE), plan.apiShapeIds())
        assertAllShapesCovered(plan)
    }

    @Test
    fun `Kitsu SEASON includes anime episodes with season role`() {
        val plan = executor.buildPlan(
            route = route(
                provider = MetadataPrimaryProvider.KITSU,
                mediaKind = MetadataMediaKind.ANIME
            ),
            depth = MetadataDepth.SEASON
        )

        assertEquals(
            listOf(KitsuApiShapes.ANIME_CORE, KitsuApiShapes.ANIME_EPISODES),
            plan.apiShapeIds()
        )
        assertEquals(ProviderPlanRole.SEASON, plan.step(KitsuApiShapes.ANIME_EPISODES).role)
        assertAllShapesCovered(plan)
    }

    @Test
    fun `Kitsu DETAIL_SECONDARY includes core episodes and relationship shapes`() {
        val plan = executor.buildPlan(
            route = route(
                provider = MetadataPrimaryProvider.KITSU,
                mediaKind = MetadataMediaKind.ANIME
            ),
            depth = MetadataDepth.DETAIL_SECONDARY
        )

        assertEquals(
            listOf(
                KitsuApiShapes.ANIME_CORE,
                KitsuApiShapes.ANIME_EPISODES,
                KitsuApiShapes.CASTINGS,
                KitsuApiShapes.ANIME_STAFF,
                KitsuApiShapes.ANIME_PRODUCTIONS,
                KitsuApiShapes.MEDIA_RELATIONSHIPS
            ),
            plan.apiShapeIds()
        )
        assertAllShapesCovered(plan)
    }

    @Test
    fun `plan executor refuses unresolved provider-native conflict routes`() {
        val error = assertThrows(IllegalStateException::class.java) {
            executor.buildPlan(
                route = route(
                    provider = MetadataPrimaryProvider.TVDB,
                    mediaKind = MetadataMediaKind.SERIES,
                    targetIdRequiresIdentityResolution = true
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        }

        assertTrue(error.message!!.contains("requires identity resolution"))
    }

    @Test
    fun `plan executor rejects unknown media kind for each provider`() {
        MetadataPrimaryProvider.entries.forEach { provider ->
            val error = assertThrows("provider=$provider", IllegalStateException::class.java) {
                executor.buildPlan(
                    route = route(
                        provider = provider,
                        mediaKind = MetadataMediaKind.UNKNOWN
                    ),
                    depth = MetadataDepth.DETAIL_CORE
                )
            }

            assertTrue(error.message!!.contains("Invalid mediaKind UNKNOWN for provider $provider"))
        }
    }

    private fun ProviderExecutionPlan.apiShapeIds(): List<String> = steps.map { it.apiShapeId }

    private fun ProviderExecutionPlan.step(apiShapeId: String): ProviderPlanStep =
        steps.single { it.apiShapeId == apiShapeId }

    private fun assertAllShapesCovered(plan: ProviderExecutionPlan) {
        assertTrue(
            "Plan contains shapes not covered by metadata router prerequisites: ${plan.apiShapeIds() - coveredShapeIds}",
            coveredShapeIds.containsAll(plan.apiShapeIds())
        )
    }

    private fun route(
        provider: MetadataPrimaryProvider,
        mediaKind: MetadataMediaKind,
        language: String? = null,
        targetIdRequiresIdentityResolution: Boolean = false
    ): MetadataRoute =
        MetadataRoute(
            provider = provider,
            parentId = "provider:1",
            mediaKind = mediaKind,
            reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
            sourceContext = MetadataSourceContext(),
            language = language,
            targetIds = mapOf(provider to "provider:1"),
            targetIdRequiresIdentityResolution = targetIdRequiresIdentityResolution,
            trace = listOf(
                MetadataRouteTrace(
                    MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                    "test route"
                )
            )
        )
}
