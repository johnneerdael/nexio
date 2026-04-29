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
import com.nexio.tv.data.remote.api.AniSkipApi
import com.nexio.tv.data.repository.SkipInterval
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AniSkipIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val aniSkipApi: AniSkipApi
) {
    suspend fun getSkipIntervals(malId: String, episode: Int): List<SkipInterval> {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.ANISKIP,
            // F2-H-06: Language is intentionally excluded: skip timestamps are audio/subtitle-track-independent
            // (intro/outro positions are based on the video timeline, not the language track).
            cacheKey = "aniskip:$malId:$episode",
            codec = gsonCodec<List<SkipInterval>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 7L * 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 30L * 24L * 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
            apiShapeId = SkipApiShapes.ANISKIP_SKIP_TIMES,
            operationKey = "aniskip.skipTimes.getSkipIntervals",
            load = {
                val response = runCatching {
                    aniSkipApi.getSkipTimes(
                        malId = malId,
                        episode = episode,
                        types = listOf("op", "ed", "recap", "mixed-op", "mixed-ed")
                    )
                }.getOrElse { return@IntegrationSpec IntegrationLoadResult.NetworkError(it) }
                val items = if (response.isSuccessful && response.body()?.found == true) {
                    response.body()!!.results?.map { result ->
                        SkipInterval(
                            startTime = result.interval.startTime,
                            endTime = result.interval.endTime,
                            type = result.skipType,
                            provider = "aniskip"
                        )
                    }.orEmpty()
                } else {
                    emptyList()
                }
                IntegrationLoadResult.Success(items)
            }
        )
        return runtime.get(spec).valueOrNull().orEmpty()
    }
}
