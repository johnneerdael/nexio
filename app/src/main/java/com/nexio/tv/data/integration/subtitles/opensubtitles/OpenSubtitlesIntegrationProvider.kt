package com.nexio.tv.data.integration.subtitles.opensubtitles

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.SubtitleApiShapes
import com.nexio.tv.core.integration.gsonCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.OpenSubtitlesApiClient
import com.nexio.tv.data.remote.model.OpenSubtitlesSearchResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class OpenSubtitlesIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val apiClient: OpenSubtitlesApiClient
) {
    suspend fun searchByImdb(imdbId: String): List<OpenSubtitlesSearchResult> =
        execute("imdb:${normalize(imdbId)}") {
            apiClient.searchByImdb(imdbId)
        }

    suspend fun searchSeriesEpisode(
        imdbId: String,
        season: Int,
        episode: Int
    ): List<OpenSubtitlesSearchResult> =
        execute("series:${normalize(imdbId)}:s$season:e$episode") {
            apiClient.searchSeriesEpisode(imdbId, season, episode)
        }

    suspend fun searchByHash(
        movieHash: String,
        movieByteSize: Long
    ): List<OpenSubtitlesSearchResult> =
        execute("hash:${movieHash.lowercase()}:size:$movieByteSize") {
            apiClient.searchByHash(movieHash, movieByteSize)
        }

    private suspend fun execute(
        keySuffix: String,
        load: suspend () -> List<OpenSubtitlesSearchResult>
    ): List<OpenSubtitlesSearchResult> {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.OPEN_SUBTITLES,
            apiShapeId = SubtitleApiShapes.OPEN_SUBTITLES_SEARCH,
            operationKey = "opensubtitles.search",
            cacheKey = "opensubtitles:search:$keySuffix",
            codec = gsonCodec<List<OpenSubtitlesSearchResult>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 6L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 24L * 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
            load = {
                try {
                    IntegrationLoadResult.Success(load())
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    IntegrationLoadResult.NetworkError(exception)
                }
            }
        )
        return runtime.get(spec).valueOrNull().orEmpty()
    }

    private fun normalize(id: String): String =
        id.removePrefix("tt").trimStart('0').ifEmpty { "0" }
}
