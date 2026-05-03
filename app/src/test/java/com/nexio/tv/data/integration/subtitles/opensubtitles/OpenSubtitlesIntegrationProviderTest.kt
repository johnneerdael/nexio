package com.nexio.tv.data.integration.subtitles.opensubtitles

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationFetchOptions
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationStreamHandle
import com.nexio.tv.core.integration.IntegrationStreamSpec
import com.nexio.tv.core.integration.SubtitleApiShapes
import com.nexio.tv.data.remote.api.OpenSubtitlesApiClient
import com.nexio.tv.data.remote.model.OpenSubtitlesSearchResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesIntegrationProviderTest {
    @Test
    fun `search executes through runtime with cache first global scope`() = runTest {
        val runtime = RecordingRuntime()
        val client = mockk<OpenSubtitlesApiClient>()
        coEvery { client.searchByImdb("tt0137523") } returns listOf(row("1"))

        val provider = OpenSubtitlesIntegrationProvider(runtime, client)
        val results = provider.searchByImdb("tt0137523")

        assertEquals(listOf("1"), results.map { it.subtitleId })
        val spec = runtime.specs.single()
        assertEquals(IntegrationProvider.OPEN_SUBTITLES, spec.provider)
        assertEquals(SubtitleApiShapes.OPEN_SUBTITLES_SEARCH, spec.apiShapeId)
        assertEquals("opensubtitles.search", spec.operationKey)
        assertEquals("opensubtitles:search:imdb:137523", spec.cacheKey)
        assertEquals(IntegrationScope.GlobalContent, spec.scope)
        assertTrue(spec.cachePolicy is com.nexio.tv.core.integration.IntegrationCachePolicy.CacheFirst)
    }

    @Test
    fun `series search cache key includes season and episode`() = runTest {
        val runtime = RecordingRuntime()
        val client = mockk<OpenSubtitlesApiClient>()
        coEvery { client.searchSeriesEpisode("tt0903747", 5, 10) } returns emptyList()

        OpenSubtitlesIntegrationProvider(runtime, client)
            .searchSeriesEpisode("tt0903747", 5, 10)

        assertEquals("opensubtitles:search:series:903747:s5:e10", runtime.specs.single().cacheKey)
    }

    private class RecordingRuntime : IntegrationRuntime {
        val specs = mutableListOf<IntegrationSpec<*>>()

        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> {
            specs += spec
            val value = when (val loaded = spec.load()) {
                is com.nexio.tv.core.integration.IntegrationLoadResult.Success -> loaded.value
                else -> null
            }
            @Suppress("UNCHECKED_CAST")
            return if (value != null) {
                IntegrationFetchResult.Fresh(value as T)
            } else {
                IntegrationFetchResult.Missing
            }
        }

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
            error("not used")

        override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
            error("not used")
    }

    private fun row(id: String): OpenSubtitlesSearchResult = OpenSubtitlesSearchResult(
        subtitleId = id,
        language = "English",
        languageCode = "eng",
        downloadUrl = "https://opensubtitles.test/$id.srt",
        filename = null,
        movieHash = null,
        fps = null,
        downloads = null,
        trusted = false,
        aiTranslated = false,
        uploadedAtEpochSeconds = null,
    )
}
