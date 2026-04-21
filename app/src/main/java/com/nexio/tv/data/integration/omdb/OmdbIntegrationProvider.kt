package com.nexio.tv.data.integration.omdb

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.StringIntegrationCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.OmdbApi
import javax.inject.Inject
import javax.inject.Singleton

private const val OMDB_VALIDATION_SERIES_IMDB_ID = "tt0944947"
private const val OMDB_VALIDATION_SEASON_NUMBER = 1

@Singleton
class OmdbIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val omdbApi: OmdbApi
) {
    suspend fun validateApiKey(apiKey: String): Boolean {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.OMDB,
            cacheKey = "omdb:validate:${apiKey.hashCode()}",
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
