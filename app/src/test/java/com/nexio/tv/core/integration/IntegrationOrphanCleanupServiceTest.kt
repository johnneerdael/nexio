package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.IntegrationCacheEntity
import com.nexio.tv.data.local.integration.LocalIntegrationCacheStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationOrphanCleanupServiceTest {
    @Test
    fun `cleanup removes owned cache row and blob when final owner disappears`() = runTest {
        val db = inMemoryIntegrationCacheDatabase()
        val blobStore = tempIntegrationBlobStore()
        val cacheStore = LocalIntegrationCacheStore(db.cacheDao(), blobStore)
        val cleanup = IntegrationOrphanCleanupService(
            railStoreDao = db.railStoreDao(),
            cacheStore = cacheStore
        )
        val blob = blobStore.fileFor("tmdb/movie-550.bin").apply { writeText("payload") }

        db.cacheDao().upsertCacheEntry(
            IntegrationCacheEntity(
                cacheKey = "tmdb:movie:550:details",
                provider = "TMDB",
                scopeKey = "global",
                blobPath = "tmdb/movie-550.bin",
                mimeType = "application/json",
                expiresAtEpochMs = 10_000L,
                staleUntilEpochMs = 20_000L,
                updatedAtEpochMs = 5_000L,
                ownerToken = "movie:imdb:tt0137523"
            )
        )

        val deleted = cleanup.cleanupIfOrphaned("movie:imdb:tt0137523")

        assertTrue(deleted)
        assertTrue(db.cacheDao().findByMediaKey("movie:imdb:tt0137523").isEmpty())
        assertFalse(blob.exists())
    }
}
