package com.nexio.tv.core.integration

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationCacheOwnershipTest {
    @Test
    fun `cache first runtime writes persist canonical media ownership`() = runTest {
        val fixture = realRuntimeFixture()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = TmdbApiShapes.MOVIE_CORE,
            operationKey = "test:tmdb:movie:550:details",
            cacheKey = "tmdb:movie:550:details",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 60_000L,
                staleAfterExpiryMs = 60_000L
            ),
            ownership = IntegrationCacheOwnership.Media("movie:imdb:tt0137523"),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = { IntegrationLoadResult.Success("payload") }
        )

        fixture.runtime.get(spec)

        assertEquals(
            "movie:imdb:tt0137523",
            fixture.cacheDao.getCacheEntry("tmdb:movie:550:details")?.ownerToken
        )
    }

    @Test
    fun `unowned cache rows keep null owner token`() = runTest {
        val fixture = realRuntimeFixture()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.MDBLIST,
            apiShapeId = MDBListApiShapes.VALIDATE_KEY,
            operationKey = "test:mdblist:validate:123",
            cacheKey = "mdblist:validate:123",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 60_000L,
                staleAfterExpiryMs = 60_000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = { IntegrationLoadResult.Success("ok") }
        )

        fixture.runtime.get(spec)

        assertNull(fixture.cacheDao.getCacheEntry("mdblist:validate:123")?.ownerToken)
    }
}
