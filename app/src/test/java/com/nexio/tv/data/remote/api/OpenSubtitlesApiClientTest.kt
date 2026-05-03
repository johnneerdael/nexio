package com.nexio.tv.data.remote.api

import com.nexio.tv.data.remote.dto.OpenSubtitlesRestSubtitleDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

class OpenSubtitlesApiClientTest {
    @Test
    fun `movie imdb search builds normalized filter path and maps rows`() = runTest {
        val api = FakeOpenSubtitlesRestApi(
            listOf(dto(id = "1", matchedBy = "imdbid", hash = null))
        )
        val client = OpenSubtitlesApiClient(api, userAgent = "NexioTest", baseUrl = "https://os.test")

        val results = client.searchByImdb("tt000550")

        assertEquals("https://os.test/search/imdbid-550", api.lastUrl)
        assertEquals("NexioTest", api.lastUserAgent)
        assertEquals("1", results.single().subtitleId)
        assertEquals("eng", results.single().languageCode)
    }

    @Test
    fun `series episode search includes episode imdb and season filters`() = runTest {
        val api = FakeOpenSubtitlesRestApi(listOf(dto(id = "1")))
        val client = OpenSubtitlesApiClient(api, userAgent = "NexioTest", baseUrl = "https://os.test")

        client.searchSeriesEpisode("tt0903747", season = 5, episode = 10)

        assertEquals("https://os.test/search/episode-10/imdbid-903747/season-5", api.lastUrl)
    }

    @Test
    fun `hash search marks moviehash rows as hash matches`() = runTest {
        val api = FakeOpenSubtitlesRestApi(
            listOf(dto(id = "hash", matchedBy = "moviehash", hash = "0123456789abcdef"))
        )
        val client = OpenSubtitlesApiClient(api, userAgent = "NexioTest", baseUrl = "https://os.test")

        val results = client.searchByHash("0123456789ABCDEF", 123456L)

        assertEquals("https://os.test/search/moviebytesize-123456/moviehash-0123456789abcdef", api.lastUrl)
        assertEquals("0123456789abcdef", results.single().movieHash)
    }

    @Test
    fun `invalid rows without ids or download urls are dropped`() = runTest {
        val api = FakeOpenSubtitlesRestApi(
            listOf(
                dto(id = null),
                dto(id = "missing-url", downloadUrl = null),
                dto(id = "ok"),
            )
        )
        val client = OpenSubtitlesApiClient(api, userAgent = "NexioTest", baseUrl = "https://os.test")

        val results = client.searchByImdb("tt1")

        assertEquals(listOf("ok"), results.map { it.subtitleId })
    }

    @Test
    fun `http errors return empty results`() = runTest {
        val api = object : OpenSubtitlesRestApi {
            override suspend fun search(
                url: String,
                userAgent: String
            ): Response<List<OpenSubtitlesRestSubtitleDto>> = Response.error(500, "boom".toResponseBody(null))
        }
        val client = OpenSubtitlesApiClient(api, userAgent = "NexioTest", baseUrl = "https://os.test")

        assertTrue(client.searchByImdb("tt1").isEmpty())
    }

    private class FakeOpenSubtitlesRestApi(
        private val response: List<OpenSubtitlesRestSubtitleDto>
    ) : OpenSubtitlesRestApi {
        var lastUrl: String? = null
        var lastUserAgent: String? = null

        override suspend fun search(
            url: String,
            userAgent: String
        ): Response<List<OpenSubtitlesRestSubtitleDto>> {
            lastUrl = url
            lastUserAgent = userAgent
            return Response.success(response)
        }
    }

    private fun dto(
        id: String?,
        matchedBy: String? = "imdbid",
        hash: String? = null,
        downloadUrl: String? = "https://opensubtitles.test/sub.srt",
    ): OpenSubtitlesRestSubtitleDto = OpenSubtitlesRestSubtitleDto(
        matchedBy = matchedBy,
        idSubtitleFile = id,
        subFileName = "sub.srt",
        subLanguageID = "eng",
        iso639 = "en",
        languageName = "English",
        movieHash = hash,
        movieByteSize = "123456",
        movieFPS = "23.976",
        movieKind = "movie",
        subFromTrusted = "1",
        subAutoTranslation = "0",
        subHearingImpaired = "0",
        subDownloadsCnt = "42",
        subDownloadLink = downloadUrl,
        zipDownloadLink = null,
        subAddDate = "2026-04-21 10:15:30",
        subFormat = "srt",
        subRating = "9.0",
        movieReleaseName = "Release",
    )
}
