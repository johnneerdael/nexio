package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.IntegrationCacheEntity
import com.nexio.tv.data.local.integration.LocalIntegrationCacheStore
import com.nexio.tv.data.local.integration.MediaIdentityEntity
import com.nexio.tv.data.local.integration.RailCacheEntity
import com.nexio.tv.data.local.integration.RailItemEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationOwnershipServiceTest {
    @Test
    fun `orphan cleanup keeps cache entry while another rail still owns same media`() = runTest {
        val fixture = railOwnershipFixture()
        fixture.seedMediaOwnedByRails(
            mediaKey = "movie:imdb:tt0137523",
            railKeys = listOf("home:tmdb:popular:movies", "home:mdblist:top:horror")
        )

        fixture.ownershipService.removeRail("home:tmdb:popular:movies")
        val retained = fixture.cacheDao.findByMediaKey("movie:imdb:tt0137523")

        assertTrue(retained.isNotEmpty())
    }

    @Test
    fun `orphan cleanup deletes cache entry when final owning rail is removed`() = runTest {
        val fixture = railOwnershipFixture()
        fixture.seedMediaOwnedByRails(
            mediaKey = "movie:imdb:tt0137523",
            railKeys = listOf("home:tmdb:popular:movies")
        )

        fixture.ownershipService.removeRail("home:tmdb:popular:movies")
        val retained = fixture.cacheDao.findByMediaKey("movie:imdb:tt0137523")

        assertFalse(retained.isNotEmpty())
    }

    @Test
    fun `catalog namespace sync does not delete continue watching rail`() = runTest {
        val fixture = railOwnershipFixture()
        val mediaKey = "movie:imdb:tt0137523"
        fixture.seedMediaOwnedByRails(
            mediaKey = mediaKey,
            railKeys = listOf(
                "profile:7:home:catalog:tmdb:popular:movies",
                "profile:7:home:continue_watching"
            )
        )

        fixture.ownershipService.syncRails("profile:7:home:catalog:", emptyList())

        assertEquals(
            1,
            fixture.railStoreDao.itemsForRail("profile:7:home:continue_watching").size
        )
        assertTrue(fixture.cacheDao.findByMediaKey(mediaKey).isNotEmpty())
    }

    @Test
    fun `profile scoped sync does not delete another profile rail`() = runTest {
        val fixture = railOwnershipFixture()
        val mediaKey = "movie:imdb:tt0137523"
        fixture.seedMediaOwnedByRails(
            mediaKey = mediaKey,
            railKeys = listOf(
                "profile:7:home:catalog:tmdb:popular:movies",
                "profile:8:home:catalog:tmdb:popular:movies"
            )
        )

        fixture.ownershipService.syncRails("profile:7:home:catalog:", emptyList())

        assertEquals(
            1,
            fixture.railStoreDao.itemsForRail("profile:8:home:catalog:tmdb:popular:movies").size
        )
        assertTrue(fixture.cacheDao.findByMediaKey(mediaKey).isNotEmpty())
    }
}

private data class RailOwnershipFixture(
    val db: com.nexio.tv.data.local.integration.IntegrationCacheDatabase,
    val cacheDao: com.nexio.tv.data.local.integration.IntegrationCacheDao,
    val cacheStore: LocalIntegrationCacheStore,
    val railStoreDao: com.nexio.tv.data.local.integration.RailStoreDao,
    val mediaIdentityDao: com.nexio.tv.data.local.integration.MediaIdentityDao,
    val orphanCleanupService: IntegrationOrphanCleanupService,
    val ownershipService: IntegrationOwnershipService
) {
    suspend fun seedMediaOwnedByRails(mediaKey: String, railKeys: List<String>) {
        mediaIdentityDao.upsertMediaIdentity(
            MediaIdentityEntity(
                mediaKey = mediaKey,
                mediaType = mediaKey.substringBefore(':'),
                title = mediaKey.substringAfterLast(':'),
                year = null,
                updatedAtEpochMs = 1_000L
            )
        )
        cacheDao.upsertCacheEntry(
            IntegrationCacheEntity(
                cacheKey = "meta:$mediaKey",
                provider = "TEST",
                scopeKey = "global",
                blobPath = "test/$mediaKey",
                mimeType = "application/json",
                expiresAtEpochMs = 10_000L,
                staleUntilEpochMs = 20_000L,
                updatedAtEpochMs = 1_000L,
                ownerToken = mediaKey
            )
        )
        railKeys.forEachIndexed { index, railKey ->
            ownershipService.upsertRailMembership(
                RailMembership(
                    rail = RailCacheEntity(
                        railKey = railKey,
                        provider = railKey.substringAfter(':').substringBefore(':').uppercase(),
                        kind = "TEST",
                        paramsHash = railKey,
                        fetchedAtEpochMs = 1_000L,
                        expiresAtEpochMs = 2_000L,
                        staleUntilEpochMs = 3_000L
                    ),
                    items = listOf(
                        RailItemEntity(
                            key = "$railKey#$mediaKey",
                            railKey = railKey,
                            mediaKey = mediaKey,
                            position = index,
                            updatedAtEpochMs = 1_000L
                        )
                    )
                )
            )
        }
    }
}

private fun railOwnershipFixture(): RailOwnershipFixture {
    val db = inMemoryIntegrationCacheDatabase()
    val cacheDao = db.cacheDao()
    val cacheStore = LocalIntegrationCacheStore(cacheDao, tempIntegrationBlobStore())
    val railStoreDao = db.railStoreDao()
    val mediaIdentityDao = db.mediaIdentityDao()
    val orphanCleanupService = IntegrationOrphanCleanupService(railStoreDao, cacheStore)
    val ownershipService = IntegrationOwnershipService(
        railStoreDao = railStoreDao,
        mediaIdentityDao = mediaIdentityDao,
        orphanCleanupService = orphanCleanupService,
        db = db
    )
    return RailOwnershipFixture(
        db = db,
        cacheDao = cacheDao,
        cacheStore = cacheStore,
        railStoreDao = railStoreDao,
        mediaIdentityDao = mediaIdentityDao,
        orphanCleanupService = orphanCleanupService,
        ownershipService = ownershipService
    )
}
