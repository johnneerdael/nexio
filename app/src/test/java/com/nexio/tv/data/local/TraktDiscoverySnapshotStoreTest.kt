package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TraktDiscoverySnapshotStoreTest {
    @Test
    fun `explicit profile id keeps trakt discovery snapshots isolated`() {
        val prefsByName = linkedMapOf<String, InMemorySharedPreferences>()
        val context = mockContext(prefsByName)
        val store = TraktDiscoverySnapshotStore(context)
        val profileOne = TraktDiscoverySnapshot(
            trendingMovieItems = listOf(sampleItem("tt1")),
            updatedAtMs = 1L
        )
        val profileTwo = TraktDiscoverySnapshot(
            trendingMovieItems = listOf(sampleItem("tt2")),
            updatedAtMs = 2L
        )

        store.write(profileOne, profileId = 1)
        store.write(profileTwo, profileId = 2)

        assertEquals(profileOne.trendingMovieItems.single().id, store.read(profileId = 1)?.trendingMovieItems?.single()?.id)
        assertEquals(profileTwo.trendingMovieItems.single().id, store.read(profileId = 2)?.trendingMovieItems?.single()?.id)
    }

    @Test
    fun `public discovery fallback reuses only enabled public rails from another profile`() {
        val prefsByName = linkedMapOf<String, InMemorySharedPreferences>()
        val context = mockContext(prefsByName)
        val store = TraktDiscoverySnapshotStore(context)
        val profileOne = TraktDiscoverySnapshot(
            trendingMovieItems = listOf(sampleItem("tt-trending-movie")),
            trendingShowItems = listOf(sampleItem("tt-trending-show", ContentType.SERIES)),
            popularMovieItems = listOf(sampleItem("tt-popular-movie")),
            recommendationMovieItems = listOf(sampleItem("tt-recommended-movie")),
            updatedAtMs = 123L
        )

        store.write(profileOne, profileId = 1)

        val fallback = store.readReusablePublicSnapshot(
            profileId = 2,
            enabledCatalogs = setOf(
                TraktCatalogIds.TRENDING_MOVIES,
                TraktCatalogIds.TRENDING_SHOWS,
                TraktCatalogIds.RECOMMENDED_MOVIES
            )
        )

        assertNotNull(fallback)
        assertEquals(listOf("tt-trending-movie"), fallback?.trendingMovieItems?.map { it.id })
        assertEquals(listOf("tt-trending-show"), fallback?.trendingShowItems?.map { it.id })
        assertEquals(emptyList<MetaPreview>(), fallback?.popularMovieItems)
        assertEquals(emptyList<MetaPreview>(), fallback?.recommendationMovieItems)
        assertEquals(123L, fallback?.updatedAtMs)
    }

    @Test
    fun `read sanitizes null popular list fields before snapshot hashing`() {
        val prefsByName = linkedMapOf<String, InMemorySharedPreferences>()
        val context = mockContext(prefsByName)
        val store = TraktDiscoverySnapshotStore(context)
        prefsByName.getOrPut(TraktDiscoverySnapshotStore.BASE_PREFS_NAME) { InMemorySharedPreferences() }
            .edit()
            .putString(
                "snapshot",
                """
                {
                  "popularLists": [
                    {
                      "key": "user/list-one",
                      "userId": "user",
                      "listId": "list-one",
                      "catalogIdBase": null,
                      "title": null,
                      "itemCount": 4
                    },
                    {
                      "key": "broken",
                      "userId": null,
                      "listId": null
                    }
                  ],
                  "updatedAtMs": 123
                }
                """.trimIndent()
            )
            .commit()

        val snapshot = store.read(profileId = 1)

        assertNotNull(snapshot)
        assertEquals(1, snapshot?.popularLists?.size)
        assertEquals("trakt_list_user_list_one", snapshot?.popularLists?.single()?.catalogIdBase)
        assertEquals("user/list-one", snapshot?.popularLists?.single()?.title)
        snapshot.hashCode()
    }

    private fun mockContext(prefsByName: MutableMap<String, InMemorySharedPreferences>): Context {
        return mockk {
            every { getSharedPreferences(any(), Context.MODE_PRIVATE) } answers {
                prefsByName.getOrPut(firstArg()) { InMemorySharedPreferences() }
            }
        }
    }

    private fun sampleItem(
        id: String,
        type: ContentType = ContentType.MOVIE
    ) = MetaPreview(
        id = id,
        type = type,
        rawType = if (type == ContentType.SERIES) "series" else "movie",
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}
