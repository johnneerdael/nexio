package com.nexio.tv.data.repository.trakt

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
import com.nexio.tv.data.repository.TraktProgressService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TraktProgressServiceWatchedShowsTest {

    private val traktIntegrationProvider = mockk<TraktIntegrationProvider>(relaxed = true) {
        every { currentTraktProfileId() } returns 1
    }
    private val service = TraktProgressService(
        traktIntegrationProvider = traktIntegrationProvider,
        traktProgressMutationExecutor = mockk(relaxed = true),
        metadataRouterFacade = mockk<MetadataRouterFacade>(relaxed = true)
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
        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
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
}
