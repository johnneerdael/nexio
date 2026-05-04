package com.nexio.tv.data.repository.trakt

import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.AnimeIdSource
import com.nexio.tv.core.anime.AnimeStremioId
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedMovieItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
import com.nexio.tv.data.repository.TraktProgressService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TraktProgressServiceWatchedShowsTest {

    private val traktIntegrationProvider = mockk<TraktIntegrationProvider>(relaxed = true) {
        every { currentTraktProfileId() } returns 1
    }
    private val animeIdMappingService = mockk<AnimeIdMappingService>(relaxed = true)
    private val moshi = com.squareup.moshi.Moshi.Builder()
        .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
        .build()
    private val service = TraktProgressService(
        traktIntegrationProvider = traktIntegrationProvider,
        traktProgressMutationExecutor = mockk(relaxed = true),
        metadataRouterFacade = mockk<MetadataRouterFacade>(relaxed = true),
        animeIdMappingService = animeIdMappingService
    )

    private fun readFixture(path: String): String {
        return javaClass.classLoader!!
            .getResourceAsStream("fixtures/$path")!!
            .bufferedReader()
            .readText()
    }

    private inline fun <reified T> com.squareup.moshi.Moshi.parseList(json: String): List<T> {
        val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, T::class.java)
        val adapter = this.adapter<List<T>>(type)
        return adapter.fromJson(json)!!
    }

    @Test
    fun watchedShowIndexEntry_carries_episode_set_and_alias_keys() = runBlocking {
        val fixture = readFixture("trakt/sync_watched_shows_full.json")
        coEvery { traktIntegrationProvider.getWatchedShows() } returns
            IntegrationCallResult.Success(moshi.parseList<TraktWatchedShowItemDto>(fixture))

        val entries = service.testOnlyProjectWatchedShows()

        val breakingBad = entries.values.first { it.aliasContentIds.contains("tvdb:81189") }
        assertEquals("tvdb:81189", breakingBad.canonicalContentId)
        assertEquals(
            setOf(1 to 1, 1 to 2, 2 to 1, 2 to 2),
            breakingBad.watchedEpisodes
        )
        assertEquals(
            setOf("tvdb:81189", "tmdb:1396", "tt0903747", "trakt:1", "breaking-bad"),
            breakingBad.aliasContentIds
        )
    }

    @Test
    fun reset_at_excludes_older_episodes() = runBlocking {
        val fixture = readFixture("trakt/sync_watched_shows_full.json")
        coEvery { traktIntegrationProvider.getWatchedShows() } returns
            IntegrationCallResult.Success(moshi.parseList<TraktWatchedShowItemDto>(fixture))

        // Parks and Recreation: reset_at = 2019-08-12; (1,1) watched at 2014, (2,1) watched at 2019-08-13.
        val entries = service.testOnlyProjectWatchedShows()
        val parks = entries.values.first { it.aliasContentIds.contains("tvdb:84912") }
        assertEquals(setOf(2 to 1), parks.watchedEpisodes)
    }

    @Test
    fun anime_show_indexed_under_kitsu_canonical_when_resolver_matches() = runBlocking {
        every {
            animeIdMappingService.resolveKitsuId(
                id = AnimeStremioId(AnimeIdSource.TVDB, "81189"),
                mediaKind = ContentMediaKind.SERIES
            )
        } returns "42"  // pretend Breaking Bad is anime for this test

        val fixture = readFixture("trakt/sync_watched_shows_full.json")
        coEvery { traktIntegrationProvider.getWatchedShows() } returns
            IntegrationCallResult.Success(moshi.parseList<TraktWatchedShowItemDto>(fixture))

        val entries = service.testOnlyProjectWatchedShows()
        val anime = entries.values.first { it.canonicalContentId == "kitsu:42" }
        assertEquals(
            setOf("kitsu:42", "tvdb:81189", "tmdb:1396", "tt0903747", "trakt:1", "breaking-bad"),
            anime.aliasContentIds
        )
    }

    @Test
    fun observeEpisodeProgress_matches_show_by_tvdb_id() = runBlocking {
        val fixture = readFixture("trakt/sync_watched_shows_full.json")
        coEvery { traktIntegrationProvider.getWatchedShows() } returns
            IntegrationCallResult.Success(moshi.parseList<TraktWatchedShowItemDto>(fixture))

        val watched = service.observeEpisodeProgress("tvdb:81189").first()
        assertEquals(
            setOf(1 to 1, 1 to 2, 2 to 1, 2 to 2),
            watched.keys
        )
    }

    @Test
    fun observeEpisodeProgress_matches_show_by_imdb_id() = runBlocking {
        val fixture = readFixture("trakt/sync_watched_shows_full.json")
        coEvery { traktIntegrationProvider.getWatchedShows() } returns
            IntegrationCallResult.Success(moshi.parseList<TraktWatchedShowItemDto>(fixture))

        val watched = service.observeEpisodeProgress("tt0903747").first()
        assertEquals(
            setOf(1 to 1, 1 to 2, 2 to 1, 2 to 2),
            watched.keys
        )
    }

    @Test
    fun observeEpisodeProgress_matches_show_by_tmdb_id() = runBlocking {
        val fixture = readFixture("trakt/sync_watched_shows_full.json")
        coEvery { traktIntegrationProvider.getWatchedShows() } returns
            IntegrationCallResult.Success(moshi.parseList<TraktWatchedShowItemDto>(fixture))

        val watched = service.observeEpisodeProgress("tmdb:1396").first()
        assertEquals(
            setOf(1 to 1, 1 to 2, 2 to 1, 2 to 2),
            watched.keys
        )
    }

    @Test
    fun observeMovieWatched_matches_by_any_id_form() = runBlocking {
        val item = TraktWatchedMovieItemDto(
            plays = 4,
            lastWatchedAt = "2014-10-11T17:00:54.000Z",
            lastUpdatedAt = "2014-10-11T17:00:54.000Z",
            movie = TraktMovieDto(
                title = "Batman Begins",
                year = 2005,
                ids = TraktIdsDto(trakt = 6, slug = "batman-begins-2005", imdb = "tt0372784", tmdb = 272)
            )
        )
        coEvery { traktIntegrationProvider.getWatched(type = "movies") } returns
            IntegrationCallResult.Success(listOf(item))

        assertEquals(true, service.observeMovieWatched("tt0372784").first())
        assertEquals(true, service.observeMovieWatched("tmdb:272").first())
        assertEquals(true, service.observeMovieWatched("trakt:6").first())
        assertEquals(true, service.observeMovieWatched("batman-begins-2005").first())
        assertEquals(false, service.observeMovieWatched("tt9999999").first())
    }
}
