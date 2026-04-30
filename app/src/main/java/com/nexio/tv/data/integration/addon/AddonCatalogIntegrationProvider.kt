package com.nexio.tv.data.integration.addon

import com.nexio.tv.core.catalog.rails.CatalogRailNotYetUniform
import com.nexio.tv.core.integration.AddonApiShapes
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.remote.api.AddonApi
import com.nexio.tv.data.remote.dto.CatalogResponseDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@CatalogRailNotYetUniform(
    reason = "Routes through IntegrationRuntime, but no IntegrationCachePolicy set; uses bespoke CatalogDiskCacheStore. Migration pending.",
    tracking = "docs/superpowers/plans/2026-04-30-catalog-rails-uniform-contract-foundation.md"
)
@Singleton
class AddonCatalogIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val addonApi: AddonApi
) {
    suspend fun getCatalog(
        addonId: String,
        catalogUrl: String
    ): NetworkResult<CatalogResponseDto> {
        return when (
            val result = runtime.call(
                IntegrationCallSpec(
                    provider = IntegrationProvider.ADDON,
                    workClass = IntegrationWorkClass.USER_VISIBLE,
                    scope = IntegrationScope.ProviderConfig("addon:$addonId"),
                    apiShapeId = AddonApiShapes.CATALOG,
                    operationKey = "addon.catalog.getCatalog",
                    call = {
                        try {
                            val response = addonApi.getCatalog(catalogUrl)
                            if (!response.isSuccessful) {
                                IntegrationCallResult.HttpError(
                                    statusCode = response.code(),
                                    reason = response.message()
                                )
                            } else {
                                response.body()?.let { IntegrationCallResult.Success(it) }
                                    ?: IntegrationCallResult.NetworkError(
                                        IllegalStateException("Empty response body")
                                    )
                            }
                        } catch (exception: Exception) {
                            if (exception is CancellationException) throw exception
                            IntegrationCallResult.NetworkError(exception)
                        }
                    }
                )
            )
        ) {
            is IntegrationCallResult.Success -> NetworkResult.Success(result.value)
            is IntegrationCallResult.HttpError -> NetworkResult.Error(
                message = result.reason ?: "Unknown error occurred",
                code = result.statusCode
            )
            is IntegrationCallResult.NetworkError -> NetworkResult.Error(
                message = result.throwable.message ?: "Unknown error occurred"
            )
            IntegrationCallResult.Missing -> NetworkResult.Error(
                message = "Addon catalog request was skipped by runtime policy (blocked, backoff, or gate)"
            )
        }
    }
}
