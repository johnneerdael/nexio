package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.subtitles.opensubtitles.OpenSubtitlesIntegrationProvider
import com.nexio.tv.data.local.OpenSubtitlesPreferences
import com.nexio.tv.data.remote.model.OpenSubtitlesSearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesSourceImplTest {
    @Test
    fun `hash matches rank ahead of imdb results and carry hash flag`() = runTest {
        val provider = mockk<OpenSubtitlesIntegrationProvider>()
        val preferences = mockPreferences()
        coEvery { provider.searchByImdb("tt0137523") } returns listOf(
            row(id = "imdb", hash = null, trusted = true, downloads = 500)
        )
        coEvery { provider.searchByHash("0123456789abcdef", 123456L) } returns listOf(
            row(id = "hash", hash = "0123456789abcdef", trusted = false, downloads = 1)
        )

        val source = OpenSubtitlesSourceImpl(provider, preferences)
        val subtitles = source.search(
            type = "movie",
            id = "tt0137523",
            videoHash = "0123456789abcdef",
            videoSize = 123456L,
        )

        assertEquals(listOf("opensubtitles:hash", "opensubtitles:imdb"), subtitles.map { it.id })
        assertTrue(subtitles.first().isHashMatch)
        assertEquals("OpenSubtitles", subtitles.first().addonName)
    }

    @Test
    fun `series video id uses episode search when season and episode are present`() = runTest {
        val provider = mockk<OpenSubtitlesIntegrationProvider>()
        val preferences = mockPreferences()
        coEvery { provider.searchSeriesEpisode("tt0903747", 5, 10) } returns listOf(row(id = "episode"))
        coEvery { provider.searchByHash(any(), any()) } returns emptyList()

        val source = OpenSubtitlesSourceImpl(provider, preferences)
        val subtitles = source.search(
            type = "series",
            id = "tt0903747",
            videoId = "tt0903747:5:10",
        )

        assertEquals("opensubtitles:episode", subtitles.single().id)
        coVerify(exactly = 1) { provider.searchSeriesEpisode("tt0903747", 5, 10) }
    }

    @Test
    fun `trusted and ai filters are applied before subtitles reach repository`() = runTest {
        val provider = mockk<OpenSubtitlesIntegrationProvider>()
        val preferences = mockPreferences(onlyTrusted = true, includeAiTranslated = false)
        coEvery { provider.searchByImdb("tt0137523") } returns listOf(
            row(id = "trusted", trusted = true, ai = false),
            row(id = "untrusted", trusted = false, ai = false),
            row(id = "ai", trusted = true, ai = true),
        )
        coEvery { provider.searchByHash(any(), any()) } returns emptyList()

        val source = OpenSubtitlesSourceImpl(provider, preferences)
        val subtitles = source.search(type = "movie", id = "tt0137523")

        assertEquals(listOf("opensubtitles:trusted"), subtitles.map { it.id })
    }

    @Test
    fun `disabled source returns empty without provider calls`() = runTest {
        val provider = mockk<OpenSubtitlesIntegrationProvider>()
        val source = OpenSubtitlesSourceImpl(provider, mockPreferences(enabled = false))

        val subtitles = source.search(type = "movie", id = "tt0137523")

        assertTrue(subtitles.isEmpty())
        coVerify(exactly = 0) { provider.searchByImdb(any()) }
    }

    private fun mockPreferences(
        enabled: Boolean = true,
        onlyTrusted: Boolean = false,
        includeAiTranslated: Boolean = false,
    ): OpenSubtitlesPreferences {
        val preferences = mockk<OpenSubtitlesPreferences>()
        coEvery { preferences.snapshot() } returns OpenSubtitlesPreferences.Snapshot(
            enabled = enabled,
            onlyTrusted = onlyTrusted,
            includeAiTranslated = includeAiTranslated,
        )
        return preferences
    }

    private fun row(
        id: String,
        hash: String? = null,
        trusted: Boolean = false,
        ai: Boolean = false,
        downloads: Int = 0,
    ): OpenSubtitlesSearchResult = OpenSubtitlesSearchResult(
        subtitleId = id,
        language = "English",
        languageCode = "eng",
        downloadUrl = "https://opensubtitles.test/$id.srt",
        filename = "$id.srt",
        movieHash = hash,
        fps = null,
        downloads = downloads,
        trusted = trusted,
        aiTranslated = ai,
        uploadedAtEpochSeconds = null,
    )
}
