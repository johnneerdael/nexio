package com.nexio.tv.data.integration.skip

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.StringIntegrationCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.AnimeSkipApi
import com.nexio.tv.data.remote.api.AnimeSkipRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnimeSkipIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val animeSkipApi: AnimeSkipApi
) {
    suspend fun validateClientId(clientId: String): Boolean {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.ANIMESKIP,
            cacheKey = "animeskip:validate:${clientId.hashCode()}",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.Disabled,
            workClass = IntegrationWorkClass.USER_VISIBLE,
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
