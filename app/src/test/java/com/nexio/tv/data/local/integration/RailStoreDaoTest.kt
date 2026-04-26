package com.nexio.tv.data.local.integration

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RailStoreDaoTest {
    @Test
    fun `rail items and media identities persist shared ownership roots`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(
            context,
            IntegrationCacheDatabase::class.java
        ).allowMainThreadQueries().build()

        db.railStoreDao().upsertRail(
            RailCacheEntity(
                railKey = "home:tmdb:popular:movies",
                provider = "TMDB",
                kind = "POPULAR_MOVIES",
                paramsHash = "lang=en-US",
                fetchedAtEpochMs = 1_000L,
                expiresAtEpochMs = 61_000L,
                staleUntilEpochMs = 121_000L
            )
        )
        db.mediaIdentityDao().upsertMediaIdentity(
            MediaIdentityEntity(
                mediaKey = "movie:imdb:tt0137523",
                mediaType = "movie",
                title = "Fight Club",
                year = 1999,
                updatedAtEpochMs = 1_000L
            )
        )
        db.railStoreDao().replaceRailItems(
            railKey = "home:tmdb:popular:movies",
            items = listOf(
                RailItemEntity(
                    key = "home:tmdb:popular:movies#movie:imdb:tt0137523",
                    railKey = "home:tmdb:popular:movies",
                    mediaKey = "movie:imdb:tt0137523",
                    position = 0,
                    updatedAtEpochMs = 1_000L
                )
            )
        )

        assertEquals(1, db.railStoreDao().itemsForRail("home:tmdb:popular:movies").size)
        assertEquals("Fight Club", db.mediaIdentityDao().getMediaIdentity("movie:imdb:tt0137523")?.title)
    }
}
