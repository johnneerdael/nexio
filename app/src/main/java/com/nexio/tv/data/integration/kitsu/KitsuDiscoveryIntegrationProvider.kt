package com.nexio.tv.data.integration.kitsu

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.data.local.KitsuCatalogIds
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.data.remote.api.KitsuApi
import com.nexio.tv.data.repository.KitsuDiscoveryClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KitsuDiscoveryIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val kitsuApi: KitsuApi
) : KitsuDiscoveryClient {
    override suspend fun fetchCatalog(
        catalogId: String,
        preferences: KitsuCatalogPreferences
    ): List<KitsuAnimeResource> {
        val result = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.KITSU,
                apiShapeId = when (catalogId) {
                    KitsuCatalogIds.TRENDING_ANIME -> KitsuApiShapes.DISCOVERY_TRENDING
                    else -> KitsuApiShapes.DISCOVERY_ANIME
                },
                operationKey = "kitsu.fetch_catalog",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = {
                    val response = when (catalogId) {
                        KitsuCatalogIds.TRENDING_ANIME -> kitsuApi.getTrendingAnime()
                        KitsuCatalogIds.HIGHEST_RATED_ANIME -> kitsuApi.getAnimeCollection(sort = "ratingRank")
                        KitsuCatalogIds.POPULAR_ANIME -> kitsuApi.getAnimeCollection(sort = "popularityRank")
                        KitsuCatalogIds.POPULAR_ACTION_ANIME -> kitsuApi.getAnimeCollection(
                            sort = "popularityRank",
                            category = "action"
                        )
                        KitsuCatalogIds.POPULAR_DRAMA_ANIME -> kitsuApi.getAnimeCollection(
                            sort = "popularityRank",
                            category = "drama"
                        )
                        KitsuCatalogIds.POPULAR_COMEDY_ANIME -> kitsuApi.getAnimeCollection(
                            sort = "popularityRank",
                            category = "comedy"
                        )
                        KitsuCatalogIds.POPULAR_FANTASY_ANIME -> kitsuApi.getAnimeCollection(
                            sort = "popularityRank",
                            category = "fantasy"
                        )
                        KitsuCatalogIds.POPULAR_ROMANCE_ANIME -> kitsuApi.getAnimeCollection(
                            sort = "popularityRank",
                            category = "romance"
                        )
                        KitsuCatalogIds.POPULAR_ADVENTURE_ANIME -> kitsuApi.getAnimeCollection(
                            sort = "popularityRank",
                            category = "adventure"
                        )
                        else -> return@IntegrationCallSpec IntegrationCallResult.Success(emptyList())
                    }

                    if (!response.isSuccessful) {
                        IntegrationCallResult.HttpError(response.code())
                    } else {
                        IntegrationCallResult.Success(response.body()?.data.orEmpty())
                    }
                }
            )
        )

        return when (result) {
            is IntegrationCallResult.Success -> result.value
            else -> emptyList()
        }
    }
}
