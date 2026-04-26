package com.nexio.tv.data.integration.skip

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.SkipApiShapes
import com.nexio.tv.core.integration.gsonCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.ArmApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArmIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val armApi: ArmApi
) {
    private val cachePolicy = IntegrationCachePolicy.CacheFirst(
        ttlMs = 7L * 24L * 60L * 60L * 1000L,
        staleAfterExpiryMs = 30L * 24L * 60L * 60L * 1000L
    )

    suspend fun resolveImdbToAnilist(imdbId: String): List<String> {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.ARM,
            cacheKey = "arm:imdb:$imdbId:anilist",
            codec = gsonCodec<List<String>>(),
            cachePolicy = cachePolicy,
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
            apiShapeId = SkipApiShapes.ARM_IMDB_BRIDGE,
            operationKey = "arm.imdb.resolveImdbToAnilist",
            load = {
                runCatching { armApi.resolveImdbToAnilist(imdbId) }.fold(
                    onSuccess = { IntegrationLoadResult.Success(it.body()?.mapNotNull { entry -> entry.anilist?.toString() }.orEmpty()) },
                    onFailure = { IntegrationLoadResult.NetworkError(it) }
                )
            }
        )
        return runtime.get(spec).valueOrNull().orEmpty()
    }

    suspend fun resolveImdbToMal(imdbId: String): String? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.ARM,
            cacheKey = "arm:imdb:$imdbId:mal",
            codec = gsonCodec<String?>(),
            cachePolicy = cachePolicy,
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
            apiShapeId = SkipApiShapes.ARM_IMDB_BRIDGE,
            operationKey = "arm.imdb.resolveImdbToMal",
            load = {
                runCatching { armApi.resolveImdbToMal(imdbId) }.fold(
                    onSuccess = { response ->
                        if (!response.isSuccessful) {
                            IntegrationLoadResult.HttpError(response.code(), reason = "arm_imdb_to_mal_failed")
                        } else {
                            IntegrationLoadResult.Success(response.body()?.firstOrNull()?.myanimelist?.toString())
                        }
                    },
                    onFailure = { IntegrationLoadResult.NetworkError(it) }
                )
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun resolveMalToAnilist(malId: String): String? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.ARM,
            cacheKey = "arm:mal:$malId:anilist",
            codec = gsonCodec<String?>(),
            cachePolicy = cachePolicy,
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
            apiShapeId = SkipApiShapes.ARM_IDS_BRIDGE,
            operationKey = "arm.ids.resolveMalToAnilist",
            load = {
                runCatching { armApi.resolveMalToAnilist(malId = malId) }.fold(
                    onSuccess = { response ->
                        if (!response.isSuccessful) {
                            IntegrationLoadResult.HttpError(response.code(), reason = "arm_mal_to_anilist_failed")
                        } else {
                            IntegrationLoadResult.Success(response.body()?.anilist?.toString())
                        }
                    },
                    onFailure = { IntegrationLoadResult.NetworkError(it) }
                )
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun resolveMalToImdb(malId: String): String? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.ARM,
            cacheKey = "arm:mal:$malId:imdb",
            codec = gsonCodec<String?>(),
            cachePolicy = cachePolicy,
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
            apiShapeId = SkipApiShapes.ARM_IDS_BRIDGE,
            operationKey = "arm.ids.resolveMalToImdb",
            load = {
                runCatching { armApi.resolveMalToImdb(malId = malId) }.fold(
                    onSuccess = { response ->
                        if (!response.isSuccessful) {
                            IntegrationLoadResult.HttpError(response.code(), reason = "arm_mal_to_imdb_failed")
                        } else {
                            IntegrationLoadResult.Success(response.body()?.imdb)
                        }
                    },
                    onFailure = { IntegrationLoadResult.NetworkError(it) }
                )
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun resolveKitsuToMal(kitsuId: String): String? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.ARM,
            cacheKey = "arm:kitsu:$kitsuId:mal",
            codec = gsonCodec<String?>(),
            cachePolicy = cachePolicy,
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
            apiShapeId = SkipApiShapes.ARM_IDS_BRIDGE,
            operationKey = "arm.ids.resolveKitsuToMal",
            load = {
                runCatching { armApi.resolveKitsuToMal(kitsuId = kitsuId) }.fold(
                    onSuccess = { response ->
                        if (!response.isSuccessful) {
                            IntegrationLoadResult.HttpError(response.code(), reason = "arm_kitsu_to_mal_failed")
                        } else {
                            IntegrationLoadResult.Success(response.body()?.myanimelist?.toString())
                        }
                    },
                    onFailure = { IntegrationLoadResult.NetworkError(it) }
                )
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun resolveKitsuToAnilist(kitsuId: String): String? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.ARM,
            cacheKey = "arm:kitsu:$kitsuId:anilist",
            codec = gsonCodec<String?>(),
            cachePolicy = cachePolicy,
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
            apiShapeId = SkipApiShapes.ARM_IDS_BRIDGE,
            operationKey = "arm.ids.resolveKitsuToAnilist",
            load = {
                runCatching { armApi.resolveKitsuToAnilist(kitsuId = kitsuId) }.fold(
                    onSuccess = { response ->
                        if (!response.isSuccessful) {
                            IntegrationLoadResult.HttpError(response.code(), reason = "arm_kitsu_to_anilist_failed")
                        } else {
                            IntegrationLoadResult.Success(response.body()?.anilist?.toString())
                        }
                    },
                    onFailure = { IntegrationLoadResult.NetworkError(it) }
                )
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun resolveKitsuToImdb(kitsuId: String): String? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.ARM,
            cacheKey = "arm:kitsu:$kitsuId:imdb",
            codec = gsonCodec<String?>(),
            cachePolicy = cachePolicy,
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
            apiShapeId = SkipApiShapes.ARM_IDS_BRIDGE,
            operationKey = "arm.ids.resolveKitsuToImdb",
            load = {
                runCatching { armApi.resolveKitsuToImdb(kitsuId = kitsuId) }.fold(
                    onSuccess = { response ->
                        if (!response.isSuccessful) {
                            IntegrationLoadResult.HttpError(response.code(), reason = "arm_kitsu_to_imdb_failed")
                        } else {
                            IntegrationLoadResult.Success(response.body()?.imdb)
                        }
                    },
                    onFailure = { IntegrationLoadResult.NetworkError(it) }
                )
            }
        )
        return runtime.get(spec).valueOrNull()
    }
}
