package com.nexio.tv.data.integration.playback

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.PlaybackApiShapes
import com.nexio.tv.core.player.OpenSubtitlesHasher
import com.nexio.tv.data.integration.playback.transport.PlaybackProbeTransport
import com.nexio.tv.data.integration.playback.transport.OpenSubtitlesHashTransport
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class OpenSubtitlesHashIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val transport: OpenSubtitlesHashTransport
) {
    suspend fun compute(url: String, headers: Map<String, String>): OpenSubtitlesHasher.Result? {
        return when (val result = runtime.call(spec(url, headers))) {
            is IntegrationCallResult.Success -> result.value
            IntegrationCallResult.Missing -> null
            is IntegrationCallResult.HttpError -> null
            is IntegrationCallResult.NetworkError -> null
        }
    }

    private fun spec(
        url: String,
        headers: Map<String, String>
    ): IntegrationCallSpec<OpenSubtitlesHasher.Result?> =
        IntegrationCallSpec(
            provider = IntegrationProvider.SUBTITLE_SOURCE_DOWNLOAD,
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
            scope = IntegrationScope.Global,
            apiShapeId = PlaybackApiShapes.OPEN_SUBTITLES_HASH,
            operationKey = "playback.opensubtitlesHash.compute",
            call = {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val size = transport.contentLength(url, headers)
                            ?: return@withContext null
                        if (size < OpenSubtitlesHasher.CHUNK_SIZE * 2) {
                            return@withContext null
                        }
                        val first = transport.readRange(
                            url = url,
                            headers = headers,
                            start = 0,
                            endInclusive = OpenSubtitlesHasher.CHUNK_SIZE - 1
                        )
                        val last = transport.readRange(
                            url = url,
                            headers = headers,
                            start = size - OpenSubtitlesHasher.CHUNK_SIZE,
                            endInclusive = size - 1
                        )
                        OpenSubtitlesHasher.computeFromChunks(size, first, last)
                    }
                    IntegrationCallResult.Success(result)
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    IntegrationCallResult.NetworkError(exception)
                }
            }
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class OpenSubtitlesHashTransportModule {
    @Binds
    abstract fun bindsOpenSubtitlesHashTransport(impl: PlaybackProbeTransport): OpenSubtitlesHashTransport
}
