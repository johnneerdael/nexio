package com.nexio.tv.data.integration.playback

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.PlaybackApiShapes
import com.nexio.tv.data.integration.playback.transport.PlaybackProbeResult
import com.nexio.tv.data.integration.playback.transport.PlaybackProbeTransport
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class PlaybackPreflightIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val transport: PlaybackProbeTransport
) {
    suspend fun isPlayable(url: String?): Boolean {
        val trimmed = url?.trim()?.takeIf { it.isNotBlank() } ?: return false
        if (!isStremThruUrl(trimmed)) return true

        return when (
            val result = runtime.call(
                IntegrationCallSpec(
                    provider = IntegrationProvider.ADDON,
                    workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
                    apiShapeId = PlaybackApiShapes.PREFLIGHT_HEAD,
                    operationKey = "playback.preflight.isPlayable",
                    call = {
                        try {
                            IntegrationCallResult.Success(
                                !isStremThruDownloadFailedRedirectLocation(
                                    withContext(Dispatchers.IO) {
                                        transport.redirectLocationForHead(trimmed)
                                    }
                                )
                            )
                        } catch (exception: Exception) {
                            if (exception is CancellationException) throw exception
                            IntegrationCallResult.NetworkError(exception)
                        }
                    }
                )
            )
        ) {
            is IntegrationCallResult.Success -> result.value
            IntegrationCallResult.Missing -> true
            is IntegrationCallResult.HttpError -> true
            is IntegrationCallResult.NetworkError -> true
        }
    }

    suspend fun rangeProbeWorks(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return false
        if (hasSignedOrTokenizedUrl(trimmed)) return true

        return when (
            val result = runtime.call(
                IntegrationCallSpec(
                    provider = IntegrationProvider.ADDON,
                    workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
                    apiShapeId = PlaybackApiShapes.PREFLIGHT_RANGE,
                    operationKey = "playback.preflight.rangeProbeWorks",
                    call = {
                        try {
                            when (
                                val probeResult = withContext(Dispatchers.IO) {
                                    transport.rangeProbe(trimmed)
                                }
                            ) {
                                PlaybackProbeResult.Success -> IntegrationCallResult.Success(true)
                                PlaybackProbeResult.Missing -> IntegrationCallResult.Missing
                                is PlaybackProbeResult.HttpError -> IntegrationCallResult.HttpError(
                                    statusCode = probeResult.statusCode
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
            is IntegrationCallResult.Success -> result.value
            IntegrationCallResult.Missing -> false
            is IntegrationCallResult.HttpError -> false
            is IntegrationCallResult.NetworkError -> false
        }
    }

    private fun isStremThruUrl(url: String): Boolean {
        return runCatching {
            URL(url).host.orEmpty().lowercase(Locale.US).contains("stremthru")
        }.getOrDefault(false)
    }

    private fun hasSignedOrTokenizedUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return listOf(
            "expires=",
            "signature=",
            "sig=",
            "auth=",
            "key=",
            "hash=",
            "x-amz-",
            "hdnts=",
            "cf_",
            "token="
        ).any(lower::contains)
    }
}

private fun isStremThruDownloadFailedRedirectLocation(location: String?): Boolean {
    return location
        ?.lowercase(Locale.US)
        ?.contains("/static/download_failed.mp4") == true
}
