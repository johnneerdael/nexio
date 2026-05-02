package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.SubtitleApiShapes
import com.nexio.tv.data.integration.subtitles.wyzie.transport.WyzieSubtitleApi
import com.nexio.tv.data.local.WyzieSettingsDataStore
import com.nexio.tv.data.remote.dto.WyzieSubtitleDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.WyzieIdHints
import com.nexio.tv.domain.model.WyzieSettings
import com.nexio.tv.domain.model.WyzieSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class WyzieSubtitleIntegrationProviderTest {

    private fun makeProvider(
        settings: WyzieSettings = WyzieSettings(apiKey = "k", enabled = true),
        api: WyzieSubtitleApi = mockk(),
        runtime: IntegrationRuntime = passthroughRuntime(),
    ): WyzieSubtitleIntegrationProvider {
        val store: WyzieSettingsDataStore = mockk()
        coEvery { store.settings } returns flowOf(settings)
        return WyzieSubtitleIntegrationProvider(runtime, api, store)
    }

    private fun passthroughRuntime(): IntegrationRuntime = object : IntegrationRuntime {
        override suspend fun <T> get(
            spec: com.nexio.tv.core.integration.IntegrationSpec<T>,
            options: com.nexio.tv.core.integration.IntegrationFetchOptions,
        ): com.nexio.tv.core.integration.IntegrationFetchResult<T> =
            throw UnsupportedOperationException("get() not used by Wyzie path")

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
            spec.call.invoke()

        override suspend fun <T> open(
            spec: com.nexio.tv.core.integration.IntegrationStreamSpec<T>,
        ): com.nexio.tv.core.integration.IntegrationStreamHandle<T>? =
            throw UnsupportedOperationException("open() not used by Wyzie path")
    }

    @Test
    fun `skips when settings disabled`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        val provider = makeProvider(settings = WyzieSettings(apiKey = "k", enabled = false), api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt0121955"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
        coVerify(exactly = 0) { api.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `skips when api key blank`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        val provider = makeProvider(settings = WyzieSettings(apiKey = null, enabled = true), api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt0121955"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
        coVerify(exactly = 0) { api.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `skips when no usable id (no imdb and no tmdb)`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        val provider = makeProvider(api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(kitsu = "42"),
            sources = listOf(WyzieSource.JIMAKU),
            season = null, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
        coVerify(exactly = 0) { api.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `skips when source list empty`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        val provider = makeProvider(api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = emptyList(),
            season = null, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
        coVerify(exactly = 0) { api.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `skips on partial season-episode pair (season without episode)`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        val provider = makeProvider(api = api)

        val result = provider.search(
            type = ContentType.SERIES,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = 1, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
        coVerify(exactly = 0) { api.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `prefers imdb id over tmdb when both present`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        val provider = makeProvider(api = api)

        provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt0121955", tmdb = 9876),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )

        coVerify(exactly = 1) {
            api.search(id = "tt0121955", source = "opensubtitles", format = "srt,ass,vtt", season = null, episode = null)
        }
    }

    @Test
    fun `falls back to tmdb id when imdb absent`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        val provider = makeProvider(api = api)

        provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(tmdb = 9876),
            sources = listOf(WyzieSource.OPENSUBTITLES, WyzieSource.SUBDL),
            season = null, episode = null,
        )

        coVerify(exactly = 1) {
            api.search(id = "9876", source = "opensubtitles,subdl", format = "srt,ass,vtt", season = null, episode = null)
        }
    }

    @Test
    fun `joins source list with commas`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        val provider = makeProvider(api = api)

        provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(
                WyzieSource.OPENSUBTITLES, WyzieSource.SUBDL, WyzieSource.SUBF2M, WyzieSource.PODNAPISI
            ),
            season = null, episode = null,
        )
        coVerify(exactly = 1) {
            api.search(id = any(), source = "opensubtitles,subdl,subf2m,podnapisi", format = any(), season = any(), episode = any())
        }
    }

    @Test
    fun `passes season and episode when both present`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        val provider = makeProvider(api = api)

        provider.search(
            type = ContentType.SERIES,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = 1, episode = 2,
        )
        coVerify(exactly = 1) {
            api.search(id = any(), source = any(), format = any(), season = 1, episode = 2)
        }
    }

    @Test
    fun `maps 401 to empty list`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } returns
            Response.error(401, okhttp3.ResponseBody.Companion.create(null, "auth"))
        val provider = makeProvider(api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
    }

    @Test
    fun `maps 500 to empty list`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } returns
            Response.error(500, okhttp3.ResponseBody.Companion.create(null, "boom"))
        val provider = makeProvider(api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
    }

    @Test
    fun `network exception yields empty list`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } throws java.io.IOException("offline")
        val provider = makeProvider(api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )
        assertEquals(emptyList<WyzieSubtitleDto>(), result)
    }

    @Test
    fun `cancellation re-throws`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } throws CancellationException("nope")
        val provider = makeProvider(api = api)

        try {
            provider.search(
                type = ContentType.MOVIE,
                hints = WyzieIdHints(imdb = "tt1"),
                sources = listOf(WyzieSource.OPENSUBTITLES),
                season = null, episode = null,
            )
            throw AssertionError("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("nope", e.message)
        }
    }

    @Test
    fun `successful response is returned as-is`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        val dto = WyzieSubtitleDto(
            id = "1", url = "u", format = "srt", encoding = "UTF-8",
            display = "English", language = "en", media = "X",
            isHearingImpaired = false, source = "opensubtitles",
        )
        coEvery { api.search(any(), any(), any(), any(), any()) } returns Response.success(listOf(dto))
        val provider = makeProvider(api = api)

        val result = provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )
        assertEquals(listOf(dto), result)
    }

    @Test
    fun `runtime spec uses WYZIE_SUBTITLES provider and wyzie_search shape`() = runTest {
        val api: WyzieSubtitleApi = mockk()
        coEvery { api.search(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        val runtime: IntegrationRuntime = mockk()
        val capturedSpec = slot<IntegrationCallSpec<List<WyzieSubtitleDto>>>()
        coEvery { runtime.call(capture(capturedSpec)) } coAnswers {
            capturedSpec.captured.call.invoke()
        }
        val provider = makeProvider(api = api, runtime = runtime)

        provider.search(
            type = ContentType.MOVIE,
            hints = WyzieIdHints(imdb = "tt1"),
            sources = listOf(WyzieSource.OPENSUBTITLES),
            season = null, episode = null,
        )
        assertEquals(IntegrationProvider.WYZIE_SUBTITLES, capturedSpec.captured.provider)
        assertEquals(SubtitleApiShapes.WYZIE_SEARCH, capturedSpec.captured.apiShapeId)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, capturedSpec.captured.workClass)
        assertTrue(capturedSpec.captured.operationKey.startsWith("wyzie."))
    }
}
