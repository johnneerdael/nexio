package com.nexio.tv.data.integration.mdblist

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.StringIntegrationCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.MDBListApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MDBListIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val mdbListApi: MDBListApi
) {
    suspend fun validateApiKey(apiKey: String): Boolean {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.MDBLIST,
            cacheKey = "mdblist:validate:${apiKey.hashCode()}",
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
