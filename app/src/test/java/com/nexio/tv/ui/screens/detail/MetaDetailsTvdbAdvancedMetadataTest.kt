package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaCompanyKind
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.Video
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class MetaDetailsTvdbAdvancedMetadataTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `detail uses tvdb cast companies networks genres and age rating`() = runTest(dispatcher) {
        val tvdbCast = listOf(
            MetaCastMember(name = "Actor One", character = "Character A", photo = "https://tvdb.test/actor1.jpg"),
            MetaCastMember(name = "Actor Two", character = "Character B", photo = "https://tvdb.test/actor2.jpg")
        )
        val tvdbCompanies = listOf(
            MetaCompany(name = "TVDB Studio", kind = MetaCompanyKind.COMPANY)
        )
        val tvdbNetworks = listOf(
            MetaCompany(name = "TVDB Network", kind = MetaCompanyKind.NETWORK)
        )
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val tvMetadataRouter = mockk<TvMetadataRouter>(relaxed = true)
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = TvMetadataEnrichment(
                seriesTvdbId = 121361,
                genres = listOf("Drama", "Fantasy"),
                rating = 9.1,
                ageRating = "TV-MA",
                countries = listOf("United Kingdom"),
                language = "en",
                castMembers = tvdbCast,
                productionCompanies = tvdbCompanies,
                networks = tvdbNetworks
            )
        )

        val viewModel = buildMetaDetailsViewModel(
            meta = buildSeriesMetaForAdvanced(),
            tmdbMetadataService = tmdbMetadataService,
            tvMetadataRouter = tvMetadataRouter,
            tmdbSettings = TmdbSettings(
                enabled = true,
                apiKey = "tmdb-key",
                useBasicInfo = true,
                useDetails = true,
                useCredits = true,
                useProductions = true,
                useNetworks = true,
                useEpisodes = false,
                useMoreLikeThis = false,
                useReviews = false,
                useCollections = false
            )
        )

        advanceUntilIdle()

        val meta = viewModel.uiState.value.meta
        assertNotNull("Meta should not be null", meta)

        // Genres from TVDB
        assertEquals(listOf("Drama", "Fantasy"), meta!!.genres)

        // Rating from TVDB
        assertEquals(9.1f, meta.imdbRating)

        // Age rating from TVDB
        assertEquals("TV-MA", meta.ageRating)

        // Country from TVDB
        assertEquals("United Kingdom", meta.country)

        // Language from TVDB
        assertEquals("en", meta.language)

        // Cast from TVDB
        assertEquals(2, meta.castMembers.size)
        assertEquals("Actor One", meta.castMembers[0].name)
        assertEquals("Character A", meta.castMembers[0].character)
        assertEquals("Actor Two", meta.castMembers[1].name)
        assertEquals(listOf("Actor One", "Actor Two"), meta.cast)

        // Production companies from TVDB
        assertEquals(1, meta.productionCompanies.size)
        assertEquals("TVDB Studio", meta.productionCompanies[0].name)
        assertEquals(null, meta.productionCompanies[0].tmdbId)

        // Networks from TVDB
        assertEquals(1, meta.networks.size)
        assertEquals("TVDB Network", meta.networks[0].name)
        assertEquals(null, meta.networks[0].tmdbId)
    }

    @Test
    fun `detail does not add new tvdb metadata section`() {
        // Static source assertion: MetaDetailsScreen.kt must not contain TVDB-specific UI sections
        val projectRoot = System.getProperty("user.dir")
            ?: error("Cannot determine project root")
        val screenFile = File(projectRoot, "app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt")
        assertTrue("MetaDetailsScreen.kt must exist", screenFile.exists())

        val source = screenFile.readText()
        val forbidden = listOf("TVDB Info", "Season Type", "TheTVDB section", "TvdbAdvanced")
        for (term in forbidden) {
            assertFalse(
                "MetaDetailsScreen.kt must not contain '$term'",
                source.contains(term)
            )
        }
    }

    @Test
    fun `detail treats tvdb creator credits as leading credits without requiring tmdb ids`() {
        val projectRoot = System.getProperty("user.dir")
            ?: error("Cannot determine project root")
        val screenFile = File(projectRoot, "app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt")
        assertTrue("MetaDetailsScreen.kt must exist", screenFile.exists())

        val source = screenFile.readText()
        assertFalse(
            "TVDB lead credits have null tmdbId and must still be eligible for the creator row",
            source.contains("it.tmdbId != null && it.character.equals(\"Creator\", ignoreCase = true)")
        )
        assertFalse(
            "TVDB lead credits have null tmdbId and must still be removed from the normal cast row",
            source.contains("id != null && id in leadingIds && isLeadCreditRole(it.character)")
        )
    }

    @Test
    fun `tvdb detail local release info includes release time converted to device timezone`() {
        val enrichment = TvMetadataEnrichment(
            seriesTvdbId = 392256,
            releaseInfo = "2026-04-14",
            airsTime = "21:00",
            originalCountry = "US",
            originalNetwork = "HBO",
            latestNetwork = "HBO",
            platformName = "HBO"
        )

        val label = formatTvdbDetailLocalReleaseInfo(
            enrichment = enrichment,
            deviceZoneId = ZoneId.of("Europe/Amsterdam")
        )

        assertEquals("2026-04-15 03:00", label)
    }

    @Test
    fun `tvdb episode local release info includes release time converted to device timezone`() {
        val enrichment = TvMetadataEnrichment(
            seriesTvdbId = 392256,
            airsTime = "21:00",
            originalCountry = "US",
            originalNetwork = "HBO",
            latestNetwork = "HBO",
            platformName = "HBO"
        )

        val label = formatTvdbEpisodeLocalReleaseInfo(
            airDate = "2026-04-14",
            enrichment = enrichment,
            deviceZoneId = ZoneId.of("Europe/Amsterdam")
        )

        assertEquals("2026-04-15 03:00", label)
    }

    @Test
    fun `detail hero prefers local release info over year only release text`() {
        val projectRoot = System.getProperty("user.dir")
            ?: error("Cannot determine project root")
        val heroFile = File(projectRoot, "app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt")
        assertTrue("HeroSection.kt must exist", heroFile.exists())

        val source = heroFile.readText()
        // Detail hero must check `meta.localReleaseInfo` *first* so a TVDB local
        // release date-time always wins over year-only fallbacks, regardless of
        // whether the release/year fallback comes from `resolvedDetail.fields`
        // (Plan B Surface 5 Task 23) or legacy `meta.releaseInfo`.
        assertTrue(
            "Detail hero must render TVDB local release date-time when it is available",
            source.contains("meta.localReleaseInfo\n            ?: resolvedFields?.releaseDate?.split(\"-\")?.firstOrNull()")
        )
    }

    private fun buildSeriesMetaForAdvanced(): Meta {
        return Meta(
            id = "tt0944947",
            type = ContentType.SERIES,
            rawType = "series",
            name = "Original Series",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = listOf(
                Video(
                    id = "tt0944947:1:1",
                    title = "Pilot",
                    released = null,
                    thumbnail = null,
                    season = 1,
                    episode = 1,
                    overview = null
                )
            ),
            productionCompanies = emptyList(),
            networks = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            trailerYtIds = emptyList()
        )
    }
}
