package com.nexio.tv.data.repository

import com.nexio.tv.data.local.KitsuCatalogIds
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.remote.api.KitsuAnimeAttributes
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.data.remote.api.KitsuImage
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.PosterShape
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KitsuDiscoveryServiceTest {
    @Test
    fun `trending anime rail maps to kitsu discovery row`() = runTest {
        val service = FakeKitsuDiscoveryClient(
            catalogResults = mapOf(
                KitsuCatalogIds.TRENDING_ANIME to listOf(
                    animeResult(
                        id = "1",
                        canonicalTitle = "Cowboy Bebop",
                        subtype = "TV",
                        synopsis = "Space bounty hunters.",
                        startDate = "1998-04-03",
                        averageRating = "85.2",
                        poster = "https://media.kitsu.io/poster.jpg",
                        cover = "https://media.kitsu.io/cover.jpg"
                    )
                )
            )
        ).createService()

        val snapshot = service.refreshCatalogs(
            KitsuCatalogPreferences(enabledCatalogs = setOf(KitsuCatalogIds.TRENDING_ANIME)),
            force = true
        )

        val row = snapshot.rowsByCatalog.getValue(KitsuCatalogIds.TRENDING_ANIME)
        assertEquals("Kitsu Trending Anime", row.catalogName)
        assertEquals("kitsu:1", row.items.single().id)
        assertEquals(ContentType.SERIES, row.items.single().type)
        assertEquals(PosterShape.POSTER, row.items.single().posterShape)
        assertEquals("1998", row.items.single().releaseInfo)
        assertNull(row.items.single().imdbRating)
        assertEquals(1, snapshot.rowRecordsByCatalog.getValue(KitsuCatalogIds.TRENDING_ANIME).previews.size)
    }

    @Test
    fun `movie subtype maps to movie preview`() = runTest {
        val service = FakeKitsuDiscoveryClient(
            catalogResults = mapOf(
                KitsuCatalogIds.POPULAR_ANIME to listOf(
                    animeResult(
                        id = "2",
                        canonicalTitle = "Akira",
                        subtype = "movie"
                    )
                )
            )
        ).createService()

        val snapshot = service.refreshCatalogs(
            KitsuCatalogPreferences(enabledCatalogs = setOf(KitsuCatalogIds.POPULAR_ANIME)),
            force = true
        )

        assertEquals(ContentType.MOVIE, snapshot.rowsByCatalog.getValue(KitsuCatalogIds.POPULAR_ANIME).items.single().type)
    }

    @Test
    fun `missing results keep rail empty but present in expected preferences`() = runTest {
        val service = FakeKitsuDiscoveryClient().createService()
        val prefs = KitsuCatalogPreferences(enabledCatalogs = setOf(KitsuCatalogIds.POPULAR_COMEDY_ANIME))

        val snapshot = service.refreshCatalogs(prefs, force = true)

        assertTrue(snapshot.rowsByCatalog.isEmpty())
        assertEquals(setOf(KitsuCatalogIds.POPULAR_COMEDY_ANIME), snapshot.catalogIdsWithCurrentPreferences)
    }

    @Test
    fun `observeSnapshot emits refreshed catalog snapshot after refreshCatalogs`() = runTest {
        val service = FakeKitsuDiscoveryClient(
            catalogResults = mapOf(
                KitsuCatalogIds.POPULAR_ACTION_ANIME to listOf(animeResult(id = "3", canonicalTitle = "Trigun"))
            )
        ).createService()
        val emission = async(start = CoroutineStart.UNDISPATCHED) { service.observeSnapshot().drop(1).first() }

        val refreshed = service.refreshCatalogs(
            KitsuCatalogPreferences(enabledCatalogs = setOf(KitsuCatalogIds.POPULAR_ACTION_ANIME)),
            force = true
        )

        assertEquals(refreshed, emission.await())
    }

    private class FakeKitsuDiscoveryClient(
        private val catalogResults: Map<String, List<KitsuAnimeResource>> = emptyMap()
    ) : KitsuDiscoveryClient {
        override suspend fun fetchCatalog(
            catalogId: String,
            preferences: KitsuCatalogPreferences
        ): List<KitsuAnimeResource> = catalogResults[catalogId].orEmpty()

        fun createService(): KitsuDiscoveryService = KitsuDiscoveryService(this)
    }

    private fun animeResult(
        id: String,
        canonicalTitle: String,
        subtype: String = "TV",
        synopsis: String = "",
        startDate: String? = null,
        averageRating: String? = null,
        poster: String? = null,
        cover: String? = null
    ): KitsuAnimeResource {
        return KitsuAnimeResource(
            id = id,
            type = "anime",
            attributes = KitsuAnimeAttributes(
                canonicalTitle = canonicalTitle,
                synopsis = synopsis.takeIf { it.isNotBlank() },
                subtype = subtype,
                startDate = startDate,
                averageRating = averageRating,
                posterImage = poster?.let { KitsuImage(original = it) },
                coverImage = cover?.let { KitsuImage(original = it) }
            )
        )
    }
}
