package com.nexio.tv.core.catalog.rails

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogRailFetchSpecTest {

    private val policy: IntegrationCachePolicy = IntegrationCachePolicy.CacheFirst(
        ttlMs = 600_000L,
        staleAfterExpiryMs = 3_600_000L
    )
    private val scope: IntegrationScope = IntegrationScope.GlobalContent

    @Test
    fun `valid spec stores fields verbatim`() {
        val spec = CatalogRailFetchSpec(
            providerId = CatalogRailProvider.TRAKT,
            railId = "trakt:trending:movies",
            operationKey = "trakt.catalog.trendingMovies",
            scope = scope,
            cachePolicy = policy
        )
        assertEquals(CatalogRailProvider.TRAKT, spec.providerId)
        assertEquals("trakt:trending:movies", spec.railId)
        assertEquals("trakt.catalog.trendingMovies", spec.operationKey)
        assertSame(scope, spec.scope)
        assertSame(policy, spec.cachePolicy)
    }

    @Test
    fun `blank railId rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CatalogRailFetchSpec(
                providerId = CatalogRailProvider.TRAKT,
                railId = "",
                operationKey = "x",
                scope = scope,
                cachePolicy = policy
            )
        }
    }

    @Test
    fun `blank operationKey rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CatalogRailFetchSpec(
                providerId = CatalogRailProvider.TRAKT,
                railId = "x",
                operationKey = "",
                scope = scope,
                cachePolicy = policy
            )
        }
    }
}
