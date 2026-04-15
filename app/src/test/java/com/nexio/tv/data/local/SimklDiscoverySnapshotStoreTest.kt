package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class SimklDiscoverySnapshotStoreTest {
    @Test
    fun `write persists and read restores catalog map snapshot`() {
        val prefs = InMemorySharedPreferences()
        val store = SimklDiscoverySnapshotStore(mockContext(prefs))

        val snapshot = SimklDiscoverySnapshot(
            itemsByCatalog = mapOf(
                SimklCatalogIds.TV_TRENDING_TODAY to listOf(samplePreview("tt1001", ContentType.SERIES, "Trending Show")),
                SimklCatalogIds.MOVIE_TRENDING_WEEK to listOf(samplePreview("tt1002", ContentType.MOVIE, "Trending Movie"))
            ),
            updatedAtMs = 123L
        )

        store.write(snapshot)

        assertEquals(snapshot, store.read())
    }

    @Test
    fun `explicit profile id keeps simkl discovery snapshots isolated`() {
        val prefsByName = linkedMapOf<String, InMemorySharedPreferences>()
        val context = mockContext(prefsByName)
        val store = SimklDiscoverySnapshotStore(context)
        val profileOne = SimklDiscoverySnapshot(
            itemsByCatalog = mapOf(SimklCatalogIds.MOVIE_TRENDING_WEEK to listOf(sampleItem("tt1"))),
            updatedAtMs = 1L
        )
        val profileTwo = SimklDiscoverySnapshot(
            itemsByCatalog = mapOf(SimklCatalogIds.MOVIE_TRENDING_WEEK to listOf(sampleItem("tt2"))),
            updatedAtMs = 2L
        )

        store.write(profileOne, profileId = 1)
        store.write(profileTwo, profileId = 2)

        assertEquals("tt1", store.read(profileId = 1)?.itemsByCatalog?.get(SimklCatalogIds.MOVIE_TRENDING_WEEK)?.single()?.id)
        assertEquals("tt2", store.read(profileId = 2)?.itemsByCatalog?.get(SimklCatalogIds.MOVIE_TRENDING_WEEK)?.single()?.id)
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        return mockk {
            every { getSharedPreferences("simkl_discovery_snapshot_v2", Context.MODE_PRIVATE) } returns prefs
            every { getSharedPreferences("simkl_discovery_snapshot", Context.MODE_PRIVATE) } returns InMemorySharedPreferences()
        }
    }

    private fun mockContext(prefsByName: MutableMap<String, InMemorySharedPreferences>): Context {
        return mockk {
            every { getSharedPreferences(any(), Context.MODE_PRIVATE) } answers {
                prefsByName.getOrPut(firstArg()) { InMemorySharedPreferences() }
            }
        }
    }

    private fun samplePreview(id: String, type: ContentType, name: String): MetaPreview {
        return MetaPreview(
            id = id,
            type = type,
            rawType = type.toApiString(),
            name = name,
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

    private fun sampleItem(id: String) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = "movie",
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
