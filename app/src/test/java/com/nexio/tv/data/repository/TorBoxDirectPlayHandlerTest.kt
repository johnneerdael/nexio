package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.debrid.TorBoxIntegrationProvider
import com.nexio.tv.data.local.TorBoxResumeStore
import com.nexio.tv.data.local.TorBoxSettings
import com.nexio.tv.data.local.TorBoxSettingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorBoxDirectPlayHandlerTest {

    private fun handlerWithApiKey(apiKey: String): Triple<TorBoxDirectPlayHandler, TorBoxIntegrationProvider, TorBoxResumeStore> {
        val provider = mockk<TorBoxIntegrationProvider>()
        val resumeStore = mockk<TorBoxResumeStore>()
        val settings = mockk<TorBoxSettingsDataStore>()
        every { settings.settings } returns MutableStateFlow(TorBoxSettings(apiKey = apiKey))
        return Triple(TorBoxDirectPlayHandler(provider, resumeStore, settings), provider, resumeStore)
    }

    @Test
    fun `resolve returns Resolved with fresh url and resume position`() = runTest {
        val (handler, provider, resumeStore) = handlerWithApiKey("tb-key")
        coEvery { provider.requestDownloadLink(apiKey = "tb-key", torrentId = 7, fileId = 10) } returns
            "https://torbox.example/stream.mkv"
        coEvery { resumeStore.loadPosition(7, 10) } returns 60_000L

        val out = handler.resolve(torrentId = 7, fileId = 10, fileName = "movie.mkv")

        assertTrue(out is TorBoxResolvedPlayback.Resolved)
        out as TorBoxResolvedPlayback.Resolved
        assertEquals("https://torbox.example/stream.mkv", out.url)
        assertEquals(60_000L, out.resumePositionMs)
        coVerify(exactly = 1) {
            provider.requestDownloadLink(apiKey = "tb-key", torrentId = 7, fileId = 10)
        }
    }

    @Test
    fun `resolve returns Resolved with zero resume position when none stored`() = runTest {
        val (handler, provider, resumeStore) = handlerWithApiKey("tb-key")
        coEvery { provider.requestDownloadLink(apiKey = "tb-key", torrentId = 7, fileId = 10) } returns
            "https://torbox.example/stream.mkv"
        coEvery { resumeStore.loadPosition(7, 10) } returns null

        val out = handler.resolve(torrentId = 7, fileId = 10, fileName = "movie.mkv")

        out as TorBoxResolvedPlayback.Resolved
        assertEquals(0L, out.resumePositionMs)
    }

    @Test
    fun `resolve returns Failed when provider returns blank url`() = runTest {
        val (handler, provider, resumeStore) = handlerWithApiKey("tb-key")
        coEvery { provider.requestDownloadLink(apiKey = "tb-key", torrentId = 7, fileId = 10) } returns ""
        coEvery { resumeStore.loadPosition(7, 10) } returns null

        val out = handler.resolve(torrentId = 7, fileId = 10, fileName = "movie.mkv")
        assertTrue(out is TorBoxResolvedPlayback.Failed)
    }

    @Test
    fun `resolve returns Failed when provider returns null`() = runTest {
        val (handler, provider, resumeStore) = handlerWithApiKey("tb-key")
        coEvery { provider.requestDownloadLink(apiKey = "tb-key", torrentId = 7, fileId = 10) } returns null
        coEvery { resumeStore.loadPosition(7, 10) } returns null

        val out = handler.resolve(torrentId = 7, fileId = 10, fileName = "movie.mkv")
        assertTrue(out is TorBoxResolvedPlayback.Failed)
    }

    @Test
    fun `resolve returns Failed when api key is blank`() = runTest {
        val (handler, _, _) = handlerWithApiKey("")
        val out = handler.resolve(torrentId = 7, fileId = 10, fileName = "movie.mkv")
        assertTrue(out is TorBoxResolvedPlayback.Failed)
        out as TorBoxResolvedPlayback.Failed
        assertEquals("TorBox is not connected.", out.message)
    }
}
