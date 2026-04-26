package com.nexio.tv.data.integration.omdb

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationCacheOwnershipFactory
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.OmdbApiShapes
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.integration.StringIntegrationCodec
import com.nexio.tv.core.integration.credentialHash
import com.nexio.tv.core.integration.gsonCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.OmdbApi
import com.nexio.tv.data.repository.OMDB_EPISODE_RATINGS_TTL_MS
import com.nexio.tv.data.repository.parseSeasonRatings
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

private const val OMDB_VALIDATION_SERIES_IMDB_ID = "tt0944947"
private const val OMDB_VALIDATION_SEASON_NUMBER = 1

private data class EpisodeRatingsCacheDto(
    val ratings: Map<String, Double>
)

@Singleton
class OmdbIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val omdbApi: OmdbApi,
    private val ownershipFactory: IntegrationCacheOwnershipFactory = IntegrationCacheOwnershipFactory(
        RailMediaIdentityResolver()
    )
) {
    suspend fun getSeasonRatings(
        seriesImdbId: String,
        apiKey: String,
        season: Int
    ): Map<Pair<Int, Int>, Double> {
        val credentialHash = credentialHash(IntegrationProvider.OMDB, apiKey)
        val spec = IntegrationSpec(
            provider = IntegrationProvider.OMDB,
            apiShapeId = OmdbApiShapes.SEASON_RATINGS,
            operationKey = "omdb.get_season_ratings",
            cacheKey = "omdb:$seriesImdbId:season:$season:credentialHash:$credentialHash",
            codec = gsonCodec<EpisodeRatingsCacheDto>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = OMDB_EPISODE_RATINGS_TTL_MS,
                staleAfterExpiryMs = OMDB_EPISODE_RATINGS_TTL_MS
            ),
            ownership = ownershipFactory.media(
                mediaType = "series",
                rawId = seriesImdbId,
                imdbId = seriesImdbId
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val response = runCatching { omdbApi.getSeason(apiKey, seriesImdbId, season) }
                    .getOrElse {
                        if (it is CancellationException) throw it
                        return@IntegrationSpec IntegrationLoadResult.NetworkError(it)
                    }
                val body = response.body()
                if (!response.isSuccessful || body == null) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(response.code(), reason = "season_lookup_failed")
                }
                IntegrationLoadResult.Success(
                    EpisodeRatingsCacheDto(parseSeasonRatings(season, body).toEpisodeRatingCache())
                )
            }
        )
        return when (val result = runtime.get(spec)) {
            is IntegrationFetchResult.Fresh -> result.value.ratings.toEpisodeRatingMap()
            is IntegrationFetchResult.Updated -> result.value.ratings.toEpisodeRatingMap()
            is IntegrationFetchResult.Stale -> result.value.ratings.toEpisodeRatingMap()
            IntegrationFetchResult.Missing -> emptyMap()
        }
    }

    suspend fun validateApiKey(apiKey: String): Boolean {
        val credentialHash = credentialHash(IntegrationProvider.OMDB, apiKey)
        val spec = IntegrationSpec(
            provider = IntegrationProvider.OMDB,
            apiShapeId = OmdbApiShapes.VALIDATE_KEY,
            operationKey = "omdb.validate_api_key",
            cacheKey = "omdb:validate:credentialHash:$credentialHash",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.Disabled,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                runCatching {
                    omdbApi.getSeason(
                        apiKey = apiKey,
                        seriesImdbId = OMDB_VALIDATION_SERIES_IMDB_ID,
                        season = OMDB_VALIDATION_SEASON_NUMBER
                    )
                }.fold(
                    onSuccess = { response ->
                        val valid = response.isSuccessful &&
                            response.body()?.response.equals("True", ignoreCase = true)
                        IntegrationLoadResult.Success(valid.toString())
                    },
                    onFailure = { IntegrationLoadResult.NetworkError(it) }
                )
            }
        )
        return runtime.get(spec).valueOrNull()?.toBoolean() == true
    }
}

private fun Map<Pair<Int, Int>, Double>.toEpisodeRatingCache(): Map<String, Double> =
    mapKeys { (key, _) -> "${key.first}:${key.second}" }

private fun Map<String, Double>.toEpisodeRatingMap(): Map<Pair<Int, Int>, Double> =
    mapNotNull { (key, rating) ->
        val parts = key.split(':', limit = 2)
        val season = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val episode = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        (season to episode) to rating
    }.toMap()
