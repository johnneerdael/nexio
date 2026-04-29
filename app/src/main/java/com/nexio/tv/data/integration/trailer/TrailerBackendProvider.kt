package com.nexio.tv.data.integration.trailer

import com.nexio.tv.BuildConfig
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.YouTubeTrailerApiShapes
import com.nexio.tv.data.remote.api.TrailerApi
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class TrailerBackendProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val trailerApi: TrailerApi
) {
    suspend fun resolveYouTubePlaybackSource(
        youtubeUrl: String,
        title: String?,
        year: String?
    ): TrailerPlaybackSource? {
        if (BuildConfig.TRAILER_API_URL.isBlank()) {
            return null
        }

        val backendUrl = when (
            val result = runtime.call(
                IntegrationCallSpec(
                    provider = IntegrationProvider.YOUTUBE_TRAILER,
                    apiShapeId = YouTubeTrailerApiShapes.TRANSPORT_EXECUTE,
                    operationKey = "youtube_trailer.backend.resolve",
                    workClass = IntegrationWorkClass.USER_VISIBLE,
                    call = {
                        val response = runCatching {
                            trailerApi.getTrailer(youtubeUrl = youtubeUrl, title = title, year = year)
                        }.getOrElse { error ->
                            if (error is CancellationException) throw error
                            return@IntegrationCallSpec IntegrationCallResult.NetworkError(error)
                        }
                        if (!response.isSuccessful) {
                            IntegrationCallResult.HttpError(response.code())
                        } else {
                            response.body()?.url?.trim()?.let { IntegrationCallResult.Success(it) }
                                ?: IntegrationCallResult.Missing
                        }
                    }
                )
            )
        ) {
            is IntegrationCallResult.Success<String> -> result.value
            else -> null
        }

        return backendUrl
            ?.takeIf(::isValidUrl)
            ?.let { TrailerPlaybackSource(videoUrl = it) }
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val parsed = URI(url)
            parsed.scheme == "http" || parsed.scheme == "https"
        } catch (_: Exception) {
            false
        }
    }
}
