package com.nexio.tv.data.local

import android.content.Context
import com.google.gson.Gson
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `non default profile write does not delete legacy global snapshot`() {
        val prefsByName = linkedMapOf<String, InMemorySharedPreferences>()
        val context = mockContext(prefsByName)
        val store = SimklDiscoverySnapshotStore(context)
        val legacyPrefs = prefsByName.getOrPut("simkl_discovery_snapshot") { InMemorySharedPreferences() }
        legacyPrefs
            .edit()
            .putString(
                "snapshot",
                legacySnapshotJson(
                    item = samplePreview("tt9999999", ContentType.MOVIE, "Legacy Movie"),
                    updatedAtMs = 987
                )
            )
            .commit()
        val profileTwo = SimklDiscoverySnapshot(
            itemsByCatalog = mapOf(SimklCatalogIds.MOVIE_TRENDING_WEEK to listOf(sampleItem("tt2"))),
            updatedAtMs = 2L
        )

        store.write(profileTwo, profileId = 2)

        assertNotNull(legacyPrefs.getString("snapshot", null))
        assertEquals("tt9999999", store.read(profileId = 1)?.itemsByCatalog?.get(SimklCatalogIds.MOVIE_TRENDING_WEEK)?.single()?.id)
        assertNull(legacyPrefs.getString("snapshot", null))
    }

    @Test
    fun `read falls back to legacy global snapshot and migrates to profile store`() {
        val prefsByName = linkedMapOf<String, InMemorySharedPreferences>()
        val context = mockContext(prefsByName)
        val store = SimklDiscoverySnapshotStore(context)
        prefsByName.getOrPut("simkl_discovery_snapshot") { InMemorySharedPreferences() }
            .edit()
            .putString(
                "snapshot",
                legacySnapshotJson(
                    item = samplePreview("tt7777777", ContentType.MOVIE, "Legacy Movie"),
                    updatedAtMs = 456
                )
            )
            .commit()

        val snapshot = store.read(profileId = 1)

        assertEquals("tt7777777", snapshot?.itemsByCatalog?.get(SimklCatalogIds.MOVIE_TRENDING_WEEK)?.single()?.id)
        assertEquals(456L, snapshot?.updatedAtMs)
        assertEquals(snapshot, store.read(profileId = 1))
    }

    @Test
    fun `non default profile read does not consume or delete legacy global snapshot`() {
        val prefsByName = linkedMapOf<String, InMemorySharedPreferences>()
        val context = mockContext(prefsByName)
        val store = SimklDiscoverySnapshotStore(context)
        val legacyPrefs = prefsByName.getOrPut("simkl_discovery_snapshot") { InMemorySharedPreferences() }
        legacyPrefs
            .edit()
            .putString(
                "snapshot",
                legacySnapshotJson(
                    item = samplePreview("tt8888888", ContentType.MOVIE, "Legacy Movie"),
                    updatedAtMs = 789
                )
            )
            .commit()

        val profileTwoSnapshot = store.read(profileId = 2)

        assertNull(profileTwoSnapshot)
        assertNotNull(legacyPrefs.getString("snapshot", null))

        val profileOneSnapshot = store.read(profileId = 1)

        assertEquals("tt8888888", profileOneSnapshot?.itemsByCatalog?.get(SimklCatalogIds.MOVIE_TRENDING_WEEK)?.single()?.id)
        assertEquals(789L, profileOneSnapshot?.updatedAtMs)
        assertNull(legacyPrefs.getString("snapshot", null))
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

    private fun legacySnapshotJson(item: MetaPreview, updatedAtMs: Long): String {
        return Gson().toJson(
            mapOf(
                "itemsByCatalog" to mapOf(SimklCatalogIds.MOVIE_TRENDING_WEEK to listOf(item)),
                "updatedAtMs" to updatedAtMs
            )
        )
    }
}
