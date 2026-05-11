package com.nexio.tv.data.trailer.captions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.data.local.SubtitleTranslationSettingsDataStore
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.data.trailer.SelectedTrailerCaptionTrack
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrailerSubtitleCacheTranslationTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var cacheDir: File

    private val srv3Xml = """
        <?xml version="1.0" encoding="utf-8" ?>
        <timedtext format="3">
          <body>
            <p t="1000" d="2000">Hello world</p>
            <p t="5000" d="2500">Second line</p>
          </body>
        </timedtext>
    """.trimIndent()

    private val expectedTranslatedSrt = "1\n00:00:01,000 --> 00:00:03,000\nHallo wereld\n\n" +
        "2\n00:00:05,000 --> 00:00:07,500\nTweede regel\n\n"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.cacheDir, "trailer-subtitles")
        cacheDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `translateTo null returns source SRT URI and writes only source file`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(srv3Xml))
        val service = mockk<SubtitleTranslationService>(relaxed = true)
        val cache = newCache(service, SubtitleTranslationSettings(enabled = false))
        val selected = SelectedTrailerCaptionTrack(
            baseUrl = server.url("/api/timedtext?v=abc").toString(),
            languageCode = "en",
            translateTo = null
        )

        val uri = cache.ensure(selected)

        assertNotNull(uri)
        assertTrue("expected source-only file name, got: $uri", uri!!.endsWith("-en.srt"))
        val files = cacheDir.listFiles().orEmpty().map { it.name }
        assertTrue("no translation file should exist", files.none { it.contains("-en-") })
    }

    @Test
    fun `AI disabled returns source SRT even when translateTo set`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(srv3Xml))
        val service = mockk<SubtitleTranslationService>(relaxed = true)
        val cache = newCache(service, SubtitleTranslationSettings(enabled = false))
        val selected = SelectedTrailerCaptionTrack(
            baseUrl = server.url("/api/timedtext?v=abc").toString(),
            languageCode = "en",
            translateTo = "nl"
        )

        val uri = cache.ensure(selected)

        assertNotNull(uri)
        assertTrue(uri!!.endsWith("-en.srt"))
        coVerify(exactly = 0) { service.translateSrtAtomically(any(), any(), any(), any()) }
    }

    @Test
    fun `AI enabled and translation succeeds returns translated SRT URI`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(srv3Xml))
        val service = mockk<SubtitleTranslationService>()
        coEvery {
            service.translateSrtAtomically(any(), eq("en"), eq("nl"), any())
        } returns expectedTranslatedSrt
        val cache = newCache(
            service,
            SubtitleTranslationSettings(
                enabled = true,
                provider = SubtitleTranslationProvider.OPENAI,
                apiKey = "test-key"
            )
        )
        val selected = SelectedTrailerCaptionTrack(
            baseUrl = server.url("/api/timedtext?v=abc").toString(),
            languageCode = "en",
            translateTo = "nl"
        )

        val uri = cache.ensure(selected)

        assertNotNull(uri)
        assertTrue("expected translated file URI, got: $uri", uri!!.endsWith("-en-nl.srt"))
        val files = cacheDir.listFiles().orEmpty().map { it.name }.toSet()
        // Both files written
        assertTrue("source file missing: $files", files.any { it.endsWith("-en.srt") && !it.contains("-en-nl") })
        assertTrue("translated file missing: $files", files.any { it.endsWith("-en-nl.srt") })
        val translatedFile = cacheDir.listFiles()!!.first { it.name.endsWith("-en-nl.srt") }
        assertEquals(expectedTranslatedSrt, translatedFile.readText())
    }

    @Test
    fun `translation failure falls back to source SRT URI`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(srv3Xml))
        val service = mockk<SubtitleTranslationService>()
        coEvery {
            service.translateSrtAtomically(any(), any(), any(), any())
        } returns null
        val cache = newCache(
            service,
            SubtitleTranslationSettings(enabled = true, apiKey = "test-key")
        )
        val selected = SelectedTrailerCaptionTrack(
            baseUrl = server.url("/api/timedtext?v=abc").toString(),
            languageCode = "en",
            translateTo = "nl"
        )

        val uri = cache.ensure(selected)

        assertNotNull(uri)
        assertTrue(uri!!.endsWith("-en.srt"))
        val files = cacheDir.listFiles().orEmpty().map { it.name }
        assertTrue("no translation file should exist", files.none { it.contains("-en-nl") })
    }

    @Test
    fun `cache hit on translated file does not call translator again`() = runBlocking {
        // Enqueue SRV3 once — second call should hit cache, not refetch.
        server.enqueue(MockResponse().setResponseCode(200).setBody(srv3Xml))
        val service = mockk<SubtitleTranslationService>()
        coEvery {
            service.translateSrtAtomically(any(), any(), any(), any())
        } returns expectedTranslatedSrt
        val cache = newCache(
            service,
            SubtitleTranslationSettings(enabled = true, apiKey = "test-key")
        )
        val selected = SelectedTrailerCaptionTrack(
            baseUrl = server.url("/api/timedtext?v=abc").toString(),
            languageCode = "en",
            translateTo = "nl"
        )

        cache.ensure(selected)
        // Second call should hit the cached translated file
        cache.ensure(selected)

        coVerify(exactly = 1) { service.translateSrtAtomically(any(), any(), any(), any()) }
    }

    private fun newCache(
        service: SubtitleTranslationService,
        settings: SubtitleTranslationSettings
    ): TrailerSubtitleCache {
        val dataStore = mockk<SubtitleTranslationSettingsDataStore>(relaxed = true)
        every { dataStore.settings } returns MutableStateFlow(settings)
        return TrailerSubtitleCache(
            applicationContext = context,
            subtitleTranslationService = service,
            subtitleTranslationSettingsDataStore = dataStore
        )
    }
}
