package com.nexio.tv.data.integration.addon

import com.nexio.tv.core.integration.AddonApiShapes
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.remote.api.AddonApi
import com.nexio.tv.data.remote.dto.SubtitleResponseDto
import com.nexio.tv.domain.model.Addon
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class AddonSubtitleIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val addonApi: AddonApi
) {
    suspend fun getSubtitles(
        addon: Addon,
        subtitleUrl: String
    ): IntegrationCallResult<SubtitleResponseDto> {
        return runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.ADDON,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.ProviderConfig("addon:${addon.id}"),
                apiShapeId = AddonApiShapes.SUBTITLES,
                operationKey = "addon.subtitle.getSubtitles",
                call = {
                    try {
                        val response = addonApi.getSubtitles(subtitleUrl)
                        if (!response.isSuccessful) {
                            IntegrationCallResult.HttpError(
                                statusCode = response.code(),
                                reason = "addon_subtitle_fetch_failed"
                            )
                        } else {
                            response.body()?.let { IntegrationCallResult.Success(it) }
                                ?: IntegrationCallResult.Missing
                        }
                    } catch (exception: Exception) {
                        if (exception is CancellationException) throw exception
                        IntegrationCallResult.NetworkError(exception)
                    }
                }
            )
        )
    }
}
