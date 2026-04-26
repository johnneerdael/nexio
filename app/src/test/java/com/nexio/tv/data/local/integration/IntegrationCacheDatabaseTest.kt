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
class IntegrationCacheDatabaseTest {
    @Test
    fun `cache rows and provider backoff rows round-trip`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(
            context,
            IntegrationCacheDatabase::class.java
        ).allowMainThreadQueries().build()

        val cacheEntity = IntegrationCacheEntity(
            cacheKey = "tmdb:movie:550:en-US",
            provider = "TMDB",
            scopeKey = "global",
            blobPath = "tmdb/movie-550.json",
            mimeType = "application/json",
            expiresAtEpochMs = 10_000L,
            staleUntilEpochMs = 20_000L,
            updatedAtEpochMs = 5_000L,
            ownerToken = null
        )
        db.cacheDao().upsertCacheEntry(cacheEntity)

        db.backoffDao().upsert(
            IntegrationProviderBackoffEntity(
                key = "TMDB:global",
                provider = "TMDB",
                scopeKey = "global",
                blockedUntilEpochMs = 8_000L,
                statusCode = 429,
                reason = "Retry-After",
                updatedAtEpochMs = 6_000L
            )
        )

        assertEquals(cacheEntity.cacheKey, db.cacheDao().getCacheEntry(cacheEntity.cacheKey)?.cacheKey)
        assertNotNull(db.backoffDao().get("TMDB", "global"))
    }

    @Test
    fun `same external id can be associated to two media keys without replacement`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(
            context,
            IntegrationCacheDatabase::class.java
        ).allowMainThreadQueries().build()

        db.mediaIdentityDao().upsertMediaIdentity(
            MediaIdentityEntity(
                mediaKey = "imdb:tt0388629",
                mediaType = "IMDB",
                title = null,
                year = null,
                updatedAtEpochMs = 1_000L
            )
        )
        db.mediaIdentityDao().upsertMediaIdentity(
            MediaIdentityEntity(
                mediaKey = "imdb:tt9999999",
                mediaType = "IMDB",
                title = null,
                year = null,
                updatedAtEpochMs = 1_000L
            )
        )
        db.mediaIdentityDao().upsertExternalIds(
            listOf(
                ExternalIdEntity(
                    key = "imdb:tt0388629:kitsu",
                    mediaKey = "imdb:tt0388629",
                    provider = "KITSU",
                    externalId = "7442",
                    idType = "IMDB"
                ),
                ExternalIdEntity(
                    key = "imdb:tt9999999:kitsu",
                    mediaKey = "imdb:tt9999999",
                    provider = "KITSU",
                    externalId = "7442",
                    idType = "IMDB"
                )
            )
        )

        assertEquals(1, db.mediaIdentityDao().externalIdsForMedia("imdb:tt0388629").size)
        assertEquals(1, db.mediaIdentityDao().externalIdsForMedia("imdb:tt9999999").size)
    }
}
