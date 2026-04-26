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
import com.nexio.tv.data.remote.api.IntroDbApi
import com.nexio.tv.data.repository.SkipInterval
import com.nexio.tv.data.repository.TheIntroDbSegmentMapper
import com.nexio.tv.data.repository.TheIntroDbSegmentPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntroDbIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val introDbApi: IntroDbApi
) {
    internal suspend fun getIntervals(
        contentId: String,
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        preferences: TheIntroDbSegmentPreferences
    ): List<SkipInterval> {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.THEINTRODB,
            cacheKey = "theintrodb:$contentId:$season:$episode",
            codec = gsonCodec<List<SkipInterval>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 7L * 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 30L * 24L * 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
            apiShapeId = SkipApiShapes.THEINTRODB_MEDIA,
            operationKey = "theintrodb.media.getIntervals",
            load = {
                val response = runCatching {
                    introDbApi.getMedia(
                        tmdbId = tmdbId,
                        imdbId = imdbId,
                        season = season,
                        episode = episode,
                        authorization = null
                    )
                }.getOrElse { return@IntegrationSpec IntegrationLoadResult.NetworkError(it) }
                val body = response.body() ?: return@IntegrationSpec IntegrationLoadResult.HttpError(response.code(), reason = "missing_body")
                if (!response.isSuccessful) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(response.code(), reason = "introdb_failed")
                }
                IntegrationLoadResult.Success(TheIntroDbSegmentMapper.map(body, preferences))
            }
        )
        return runtime.get(spec).valueOrNull().orEmpty()
    }
}
