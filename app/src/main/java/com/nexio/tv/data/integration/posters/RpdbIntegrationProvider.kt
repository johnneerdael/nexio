package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.StringIntegrationCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.RpdbApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RpdbIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val rpdbApi: RpdbApi
) {
    suspend fun validateApiKey(apiKey: String): Boolean {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.RPDB,
            cacheKey = "rpdb:validate:${apiKey.hashCode()}",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.Disabled,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                runCatching { rpdbApi.verifyApiKey(apiKey) }
                    .fold(
                        onSuccess = { response ->
                            val body = response.body()?.string()?.trim().orEmpty().lowercase()
                            val valid = response.isSuccessful &&
                                (body.isBlank() || body.contains("true") || body.contains("valid"))
                            IntegrationLoadResult.Success(valid.toString())
                        },
                        onFailure = { IntegrationLoadResult.NetworkError(it) }
                    )
            }
        )
        return runtime.get(spec).valueOrNull()?.toBoolean() == true
    }
}
