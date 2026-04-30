package com.nexio.tv.data.catalog.rails

import com.nexio.tv.core.catalog.rails.CatalogRailDescriptor
import com.nexio.tv.core.catalog.rails.CatalogRailProvider
import com.nexio.tv.core.catalog.rails.CatalogRailSource
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.data.integration.railpreview.TraktRailPreviewMapper
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.domain.model.RailItemPreview
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Second conformant [CatalogRailSource] (Plan 3 of the catalog-rails uniformity series).
 *
 * Exposes the four **global** Trakt rails — Trending Movies, Trending Shows, Popular Movies,
 * Popular Shows — and delegates fetches to the existing [TraktIntegrationProvider] (which
 * routes through the integration runtime with `IntegrationCachePolicy.CacheFirst(10m/60m)`
 * and `IntegrationScope.GlobalContent`). DTOs are mapped to [RailItemPreview] via the
 * existing [TraktRailPreviewMapper].
 *
 * **Profile-bound Trakt rails are out of scope for this plan.** Recommendations, Calendar,
 * and User Lists are personalised per Trakt account and require auth-state gating in
 * [availableRails] plus `IntegrationScope.Account` handling — those will be migrated in a
 * follow-up plan that extends [TraktRailIdCodec.Kind] and adds the auth-aware filters.
 *
 * **Profile parameter caveat:** [fetchRail] accepts `profileId` for interface compliance,
 * but [TraktIntegrationProvider]'s fetch methods derive their session via
 * `TraktAuthService.currentAuthSession()` (active-profile only). For global rails this is
 * harmless because trending/popular data is not personalised — the session only feeds the
 * cache key. Profile-bound rails (future plan) will need explicit `profileId` threading.
 */
@Singleton
class TraktCatalogRailSource @Inject constructor(
    private val integrationProvider: TraktIntegrationProvider,
    private val mapper: TraktRailPreviewMapper,
    private val railIdCodec: TraktRailIdCodec
) : CatalogRailSource {

    override val providerId: CatalogRailProvider = CatalogRailProvider.TRAKT

    override suspend fun availableRails(profileId: Int): List<CatalogRailDescriptor> {
        return TraktRailIdCodec.Kind.values().mapIndexed { index, kind ->
            CatalogRailDescriptor(
                railId = railIdCodec.encode(kind),
                providerId = CatalogRailProvider.TRAKT,
                displayTitle = displayTitleFor(kind),
                sortOrder = index
            )
        }
    }

    override suspend fun fetchRail(
        profileId: Int,
        rail: CatalogRailDescriptor
    ): IntegrationFetchResult<List<RailItemPreview>> {
        val kind = railIdCodec.decode(rail.railId) ?: return IntegrationFetchResult.Missing
        val now = System.currentTimeMillis()
        val items: List<RailItemPreview> = when (kind) {
            TraktRailIdCodec.Kind.TRENDING_MOVIES -> {
                val dtos = integrationProvider.fetchTrendingMovies(LIMIT)
                    ?: return IntegrationFetchResult.Missing
                dtos.mapIndexedNotNull { idx, dto ->
                    mapper.mapTrendingMovie(rail.railId, dto, idx, now)
                }
            }
            TraktRailIdCodec.Kind.TRENDING_SHOWS -> {
                val dtos = integrationProvider.fetchTrendingShows(LIMIT)
                    ?: return IntegrationFetchResult.Missing
                dtos.mapIndexedNotNull { idx, dto ->
                    mapper.mapTrendingShow(rail.railId, dto, idx, now)
                }
            }
            TraktRailIdCodec.Kind.POPULAR_MOVIES -> {
                val dtos = integrationProvider.fetchPopularMovies(LIMIT)
                    ?: return IntegrationFetchResult.Missing
                dtos.mapIndexedNotNull { idx, dto ->
                    mapper.mapMovie(rail.railId, dto, idx, now)
                }
            }
            TraktRailIdCodec.Kind.POPULAR_SHOWS -> {
                val dtos = integrationProvider.fetchPopularShows(LIMIT)
                    ?: return IntegrationFetchResult.Missing
                dtos.mapIndexedNotNull { idx, dto ->
                    mapper.mapShow(rail.railId, dto, idx, now)
                }
            }
        }
        return IntegrationFetchResult.Updated(items)
    }

    private fun displayTitleFor(kind: TraktRailIdCodec.Kind): String = when (kind) {
        TraktRailIdCodec.Kind.TRENDING_MOVIES -> "Trakt — Trending Movies"
        TraktRailIdCodec.Kind.TRENDING_SHOWS -> "Trakt — Trending Shows"
        TraktRailIdCodec.Kind.POPULAR_MOVIES -> "Trakt — Popular Movies"
        TraktRailIdCodec.Kind.POPULAR_SHOWS -> "Trakt — Popular Shows"
    }

    companion object {
        private const val LIMIT: Int = 30
    }
}
