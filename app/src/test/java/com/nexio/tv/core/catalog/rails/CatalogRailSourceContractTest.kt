package com.nexio.tv.core.catalog.rails

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.domain.model.RailItemPreview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogRailSourceContractTest {

    /**
     * A minimal in-memory CatalogRailSource. Verifies the interface compiles and
     * the contract is callable without any provider-specific dependencies.
     */
    private class FakeCatalogRailSource : CatalogRailSource {
        override val providerId: CatalogRailProvider = CatalogRailProvider.TRAKT

        override suspend fun availableRails(profileId: Int): List<CatalogRailDescriptor> =
            listOf(
                CatalogRailDescriptor(
                    railId = "trakt:trending:movies",
                    providerId = CatalogRailProvider.TRAKT,
                    displayTitle = "Trending Movies",
                    sortOrder = 0
                )
            )

        override suspend fun fetchRail(
            profileId: Int,
            rail: CatalogRailDescriptor
        ): IntegrationFetchResult<List<RailItemPreview>> =
            IntegrationFetchResult.Missing

        @Suppress("unused")
        fun specForRail(rail: CatalogRailDescriptor): CatalogRailFetchSpec =
            CatalogRailFetchSpec(
                providerId = providerId,
                railId = rail.railId,
                operationKey = "trakt.${rail.railId}",
                scope = IntegrationScope.GlobalContent,
                cachePolicy = IntegrationCachePolicy.CacheFirst(
                    ttlMs = 600_000L,
                    staleAfterExpiryMs = 3_600_000L
                )
            )
    }

    @Test
    fun `interface exposes providerId and the two suspend methods`() = runBlocking {
        val source: CatalogRailSource = FakeCatalogRailSource()
        assertEquals(CatalogRailProvider.TRAKT, source.providerId)
        val rails = source.availableRails(profileId = 1)
        assertEquals(1, rails.size)
        val result = source.fetchRail(profileId = 1, rail = rails.first())
        assertEquals(IntegrationFetchResult.Missing, result)
    }
}
