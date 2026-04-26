package com.nexio.tv.data.integration.skip

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.SkipApiShapes
import com.nexio.tv.core.integration.StringIntegrationCodec
import com.nexio.tv.core.integration.credentialHash
import com.nexio.tv.core.integration.gsonCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.AnimeSkipApi
import com.nexio.tv.data.remote.api.AnimeSkipEpisode
import com.nexio.tv.data.remote.api.AnimeSkipRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnimeSkipIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val animeSkipApi: AnimeSkipApi
) {
    suspend fun resolveShowIds(anilistId: String, clientId: String): List<String> {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.ANIMESKIP,
            cacheKey = "animeskip:shows:$anilistId",
            codec = gsonCodec<List<String>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 7L * 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 30L * 24L * 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
            apiShapeId = SkipApiShapes.ANIMESKIP_SHOWS,
            operationKey = "animeskip.graphql.resolveShowIds",
            load = {
                runCatching {
                    animeSkipApi.query(
                        clientId = clientId,
                        body = AnimeSkipRequest(
                            query = "{ findShowsByExternalId(service: ANILIST, serviceId: \"$anilistId\") { id } }"
                        )
                    )
                }.fold(
                    onSuccess = { response ->
                        val ids = response.body()?.data?.findShowsByExternalId?.map { it.id }.orEmpty()
                        IntegrationLoadResult.Success(ids)
                    },
                    onFailure = { IntegrationLoadResult.NetworkError(it) }
                )
            }
        )
        return runtime.get(spec).valueOrNull().orEmpty()
    }

    suspend fun queryEpisodes(showId: String, clientId: String): List<AnimeSkipEpisode> {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.ANIMESKIP,
            cacheKey = "animeskip:episodes:$showId",
            codec = gsonCodec<List<AnimeSkipEpisode>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 7L * 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 30L * 24L * 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
            apiShapeId = SkipApiShapes.ANIMESKIP_GRAPHQL,
            operationKey = "animeskip.graphql.queryEpisodes",
            load = {
                runCatching {
                    animeSkipApi.query(
                        clientId = clientId,
                        body = AnimeSkipRequest(
                            query = "{ findEpisodesByShowId(showId: \"$showId\") { season number timestamps { at type { name } } } }"
                        )
                    )
                }.fold(
                    onSuccess = { response ->
                        IntegrationLoadResult.Success(response.body()?.data?.findEpisodesByShowId.orEmpty())
                    },
                    onFailure = { IntegrationLoadResult.NetworkError(it) }
                )
            }
        )
        return runtime.get(spec).valueOrNull().orEmpty()
    }

    suspend fun validateClientId(clientId: String): Boolean {
        val credentialHash = credentialHash(IntegrationProvider.ANIMESKIP, clientId)
        val spec = IntegrationSpec(
            provider = IntegrationProvider.ANIMESKIP,
            cacheKey = "animeskip:validate:credentialHash:$credentialHash",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.Disabled,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            apiShapeId = SkipApiShapes.ANIMESKIP_VALIDATE,
            operationKey = "animeskip.graphql.validateClientId",
            load = {
                runCatching {
                    animeSkipApi.query(
                        clientId = clientId,
                        body = AnimeSkipRequest(
                            query = "{ findShowsByExternalId(service: ANILIST, serviceId: \"1\") { id } }"
                        )
                    )
                }.fold(
                    onSuccess = { response ->
                        val valid = response.isSuccessful && response.body()?.data != null
                        IntegrationLoadResult.Success(valid.toString())
                    },
                    onFailure = { IntegrationLoadResult.NetworkError(it) }
                )
            }
        )
        return runtime.get(spec).valueOrNull()?.toBoolean() == true
    }
}
