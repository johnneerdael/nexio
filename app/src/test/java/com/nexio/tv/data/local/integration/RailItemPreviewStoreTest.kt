package com.nexio.tv.data.local.integration

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RailItemPreviewStoreTest {
    @Test
    fun `preview records persist separately from rail membership`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IntegrationCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        db.railStoreDao().upsertRail(
            RailCacheEntity(
                railKey = "home:trakt:trending:shows",
                provider = "TRAKT",
                kind = "TRENDING_SHOWS",
                paramsHash = "profile=1",
                fetchedAtEpochMs = 1_000L,
                expiresAtEpochMs = 61_000L,
                staleUntilEpochMs = 86_401_000L
            )
        )
        db.railStoreDao().upsertPreview(
            RailItemPreviewEntity(
                itemKey = "series:imdb:tt0903747",
                railId = "trakt_trending_shows",
                railSource = "BUILT_IN_TRAKT",
                sourceProvider = "TRAKT",
                sourceItemId = "trakt:show:1",
                itemType = "series",
                stableIdsJson = "{\"imdb\":\"tt0903747\",\"tmdb\":\"1396\",\"tvdb\":\"81189\",\"trakt\":\"1\"}",
                displaySeedJson = "{\"title\":\"Breaking Bad\",\"year\":2008}",
                rankingJson = "{\"watchers\":541}",
                sourcePayloadQuality = "SPARSE_IDENTITY",
                sourcePayloadHash = "hash-trakt-breaking-bad",
                hydrationState = "PREVIEW_ONLY",
                fetchedAtEpochMs = 1_000L,
                expiresAtEpochMs = 61_000L
            )
        )
        db.railStoreDao().replaceRailItems(
            railKey = "home:trakt:trending:shows",
            items = listOf(
                RailItemEntity(
                    key = "home:trakt:trending:shows#series:imdb:tt0903747",
                    railKey = "home:trakt:trending:shows",
                    mediaKey = "series:imdb:tt0903747",
                    position = 0,
                    updatedAtEpochMs = 1_000L
                )
            )
        )

        db.railStoreDao().deleteRailWithMembership("home:trakt:trending:shows")

        assertEquals(emptyList<RailItemEntity>(), db.railStoreDao().itemsForRail("home:trakt:trending:shows"))
        assertNotNull(db.railStoreDao().preview("series:imdb:tt0903747"))
    }
}
