package com.nexio.tv.data.integration.mdblist

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationCacheOwnershipFactory
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.MDBListApiShapes
import com.nexio.tv.core.integration.ProfileExecutionContext
import com.nexio.tv.core.integration.ProviderAccountRef
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.integration.StringIntegrationCodec
import com.nexio.tv.core.integration.credentialHash
import com.nexio.tv.core.integration.gsonCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingItemDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingRequestDto
import com.nexio.tv.domain.model.MDBListRatings
import com.nexio.tv.domain.model.MDBListRatingsResult
import javax.inject.Inject
import javax.inject.Singleton

private const val MDBLIST_RAIL_APPEND_TO_RESPONSE = "genres,poster,description,ratings"

@Singleton
class MDBListIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val mdbListApi: MDBListApi,
    private val ownershipFactory: IntegrationCacheOwnershipFactory = IntegrationCacheOwnershipFactory(
        RailMediaIdentityResolver()
    )
) {
    suspend fun getRaw(
        relativeUrl: String,
        apiKey: String,
        profileId: Int? = null
    ): IntegrationCallResult<String> =
        runtime.call(
            accountCallSpec(
                relativeUrl = relativeUrl,
                credential = apiKey,
                profileId = profileId,
                operation = "mdblist.get_raw"
            ) {
                getRawWithinRuntimeLoad(relativeUrl = relativeUrl, apiKey = apiKey)
            }
        )

    suspend fun getRawWithQuery(
        relativeUrl: String,
        query: Map<String, String>,
        profileId: Int? = null,
        accountCredential: String? = query["apikey"] ?: query["apiKey"]
    ): IntegrationCallResult<String> =
        runtime.call(
            accountCallSpec(
                relativeUrl = relativeUrl,
                credential = accountCredential,
                profileId = profileId,
                operation = "mdblist.get_raw_with_query"
            ) {
                getRawWithQueryWithinRuntimeLoad(
                    relativeUrl = relativeUrl,
                    query = withRailAppendToResponse(query)
                )
            }
        )

    private suspend fun getRawWithinRuntimeLoad(
        relativeUrl: String,
        apiKey: String
    ): IntegrationCallResult<String> =
        runCatching {
            mdbListApi.getRaw(relativeUrl = relativeUrl, apiKey = apiKey)
        }.fold(
            onSuccess = ::rawStringResult,
            onFailure = { error -> IntegrationCallResult.NetworkError(error) }
        )

    private suspend fun getRawWithQueryWithinRuntimeLoad(
        relativeUrl: String,
        query: Map<String, String>
    ): IntegrationCallResult<String> =
        runCatching {
            mdbListApi.getRawWithQuery(relativeUrl = relativeUrl, query = query)
        }.fold(
            onSuccess = ::rawStringResult,
            onFailure = { error -> IntegrationCallResult.NetworkError(error) }
        )

    private fun rawStringResult(response: retrofit2.Response<okhttp3.ResponseBody>): IntegrationCallResult<String> =
        if (!response.isSuccessful) {
            IntegrationCallResult.HttpError(response.code())
        } else {
            IntegrationCallResult.Success(response.body()?.string().orEmpty())
        }

    private fun accountCallSpec(
        relativeUrl: String,
        credential: String?,
        profileId: Int?,
        operation: String,
        call: suspend () -> IntegrationCallResult<String>
    ): IntegrationCallSpec<String> {
        val normalizedCredential = credential?.takeIf { it.isNotBlank() }
        if (profileId == null || normalizedCredential == null) {
            return IntegrationCallSpec(
                provider = IntegrationProvider.MDBLIST,
                apiShapeId = MDBListApiShapes.RAW_URL_LIST,
                operationKey = operation,
                workClass = IntegrationWorkClass.BACKGROUND_HYDRATION,
                scope = IntegrationScope.GlobalContent,
                call = call
            )
        }

        val credentialHash = credentialHash(IntegrationProvider.MDBLIST, normalizedCredential)
        val operationKey = "profile:$profileId:provider:MDBLIST:credential:$credentialHash:operation:$operation:url:${relativeUrl.hashCode()}"
        return IntegrationCallSpec(
            provider = IntegrationProvider.MDBLIST,
            apiShapeId = MDBListApiShapes.RAW_URL_LIST,
            operationKey = operationKey,
            workClass = IntegrationWorkClass.BACKGROUND_HYDRATION,
            scope = IntegrationScope.Account(
                profileId = profileId,
                provider = IntegrationProvider.MDBLIST,
                credentialHash = credentialHash
            ),
            profileContext = ProfileExecutionContext(
                profileId = profileId,
                sessionId = "mdblist:$profileId",
                displayLanguage = "en",
                region = "global",
                accounts = mapOf(
                    IntegrationProvider.MDBLIST to ProviderAccountRef(
                        provider = IntegrationProvider.MDBLIST,
                        credentialHash = credentialHash,
                        accountIdHash = null
                    )
                )
            ),
            call = call
        )
    }

    suspend fun fetchRatings(
        ratingId: Any,
        requestProvider: String,
        mediaType: String,
        apiKey: String,
        providers: List<String>
    ): MDBListRatingsResult? {
        val providerHash = providers.sorted().joinToString(",")
        val credentialHash = credentialHash(IntegrationProvider.MDBLIST, apiKey)
        val spec = IntegrationSpec(
            provider = IntegrationProvider.MDBLIST,
            apiShapeId = MDBListApiShapes.RATING_BATCH,
            operationKey = "mdblist.fetch_ratings",
            cacheKey = "mdblist:$mediaType:$requestProvider:$ratingId:$providerHash:credentialHash:$credentialHash",
            codec = gsonCodec<MDBListRatingsResult>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 7L * 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
            ),
            ownership = ownershipFactory.media(
                mediaType = mediaType,
                rawId = "$requestProvider:$ratingId",
                imdbId = ratingId.toString().takeIf { requestProvider == "imdb" && it.startsWith("tt", ignoreCase = true) },
                tmdbId = ratingId.toString().takeIf { requestProvider == "tmdb" }
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val requestBody = MDBListRatingRequestDto(
                    ids = listOf(ratingId),
                    provider = requestProvider
                )
                val ratingsByProvider = mutableMapOf<String, Double?>()
                var firstFailure: Throwable? = null
                for (provider in providers) {
                    val response = runCatching {
                        mdbListApi.getRating(
                            mediaType = mediaType,
                            ratingType = provider,
                            apiKey = apiKey,
                            body = requestBody
                        )
                    }.getOrElse { error ->
                        firstFailure = firstFailure ?: error
                        ratingsByProvider[provider] = null
                        continue
                    }

                    if (!response.isSuccessful) {
                        ratingsByProvider[provider] = null
                    } else {
                        ratingsByProvider[provider] = response.body()?.ratings?.firstOrNull()?.rating
                    }
                }

                val ratings = MDBListRatings(
                    trakt = ratingsByProvider["trakt"],
                    imdb = ratingsByProvider["imdb"],
                    tmdb = ratingsByProvider["tmdb"],
                    letterboxd = ratingsByProvider["letterboxd"],
                    tomatoes = ratingsByProvider["tomatoes"],
                    audience = ratingsByProvider["audience"],
                    metacritic = ratingsByProvider["metacritic"]
                )
                if (ratings.isEmpty()) {
                    firstFailure?.let { return@IntegrationSpec IntegrationLoadResult.NetworkError(it) }
                    return@IntegrationSpec IntegrationLoadResult.HttpError(404, reason = "mdblist_empty")
                }

                IntegrationLoadResult.Success(
                    MDBListRatingsResult(
                        ratings = ratings,
                        hasImdbRating = false
                    )
                )
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchRatingBatch(
        mediaType: String,
        ratingType: String,
        requestProvider: String,
        ids: List<String>,
        apiKey: String
    ): IntegrationCallResult<Map<String, Double>> {
        val cleanIds = ids.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct()
        if (cleanIds.isEmpty()) return IntegrationCallResult.Success(emptyMap())

        val credentialHash = credentialHash(IntegrationProvider.MDBLIST, apiKey)
        return runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.MDBLIST,
                apiShapeId = MDBListApiShapes.RATING_BATCH,
                operationKey = "mdblist.rating_batch:$mediaType:$ratingType:$requestProvider:${cleanIds.joinToString("|").hashCode()}:$credentialHash",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.ProviderConfig("mdblist:$credentialHash"),
                coalesceConcurrent = true,
                call = {
                    runCatching {
                        mdbListApi.getRating(
                            mediaType = mediaType,
                            ratingType = ratingType,
                            apiKey = apiKey,
                            body = MDBListRatingRequestDto(
                                ids = cleanIds.map { id -> id.toIntOrNull() ?: id },
                                provider = requestProvider
                            )
                        )
                    }.fold(
                        onSuccess = { response ->
                            if (!response.isSuccessful) {
                                return@IntegrationCallSpec IntegrationCallResult.HttpError(
                                    statusCode = response.code(),
                                    retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                                    reason = "mdblist_rating_${response.code()}"
                                )
                            }
                            IntegrationCallResult.Success(
                                response.body()
                                    ?.ratings
                                    .orEmpty()
                                    .associateRatingItems()
                            )
                        },
                        onFailure = { error -> IntegrationCallResult.NetworkError(error) }
                    )
                }
            )
        )
    }

    suspend fun fetchEpisodeRatingsForSeason(
        cacheNamespace: String,
        season: Int,
        apiKey: String,
        episodeTmdbIds: Map<Pair<Int, Int>, Int>
    ): Map<Pair<Int, Int>, Double> {
        return emptyMap()
    }

    suspend fun validateApiKey(apiKey: String): Boolean {
        val credentialHash = credentialHash(IntegrationProvider.MDBLIST, apiKey)
        val spec = IntegrationSpec(
            provider = IntegrationProvider.MDBLIST,
            apiShapeId = MDBListApiShapes.VALIDATE_KEY,
            operationKey = "mdblist.validate_api_key",
            cacheKey = "mdblist:validate:credentialHash:$credentialHash",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.Disabled,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                runCatching { mdbListApi.getUser(apiKey) }
                    .fold(
                        onSuccess = { IntegrationLoadResult.Success(it.isSuccessful.toString()) },
                        onFailure = { IntegrationLoadResult.NetworkError(it) }
                    )
            }
        )
        return runtime.get(spec).valueOrNull()?.toBoolean() == true
    }
}

private fun List<MDBListRatingItemDto>.associateRatingItems(): Map<String, Double> {
    val out = linkedMapOf<String, Double>()
    for (i in indices) {
        val item = this[i]
        val id = item.id.toRatingIdKey() ?: continue
        val rating = item.rating ?: continue
        out[id] = rating
    }
    return out
}

private fun Any?.toRatingIdKey(): String? {
    return when (this) {
        null -> null
        is Int -> toString()
        is Long -> toString()
        is Double -> toLong().takeIf { it.toDouble() == this }?.toString() ?: toString()
        is Float -> toLong().takeIf { it.toFloat() == this }?.toString() ?: toString()
        is String -> trim().takeIf(String::isNotEmpty)
        else -> toString().trim().takeIf(String::isNotEmpty)
    }
}

internal fun withRailAppendToResponse(query: Map<String, String>): Map<String, String> {
    val existingTokens = query["append_to_response"]
        ?.split(',')
        ?.mapNotNull { token -> token.trim().takeIf { it.isNotEmpty() } }
        .orEmpty()
    val mergedTokens = existingTokens.toMutableList()

    MDBLIST_RAIL_APPEND_TO_RESPONSE.split(',').forEach { requiredToken ->
        if (mergedTokens.none { it == requiredToken }) {
            mergedTokens += requiredToken
        }
    }

    return query + ("append_to_response" to mergedTokens.joinToString(","))
}
