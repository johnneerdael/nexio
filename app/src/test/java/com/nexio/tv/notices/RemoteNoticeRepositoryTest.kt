package com.nexio.tv.notices

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.github.GitHubRawContentIntegrationProvider
import com.squareup.moshi.Moshi
import java.io.File
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

class RemoteNoticeRepositoryTest {
    private val manifestUrl = "https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/manifest.json"
    private val markdownUrl = "https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/new.md"
    private val now = Instant.parse("2026-05-12T12:00:00Z")

    @Test
    fun `first successful parsed manifest sets baseline before filtering and suppresses existing notices`() = runTest {
        val provider = mockProvider(
            manifest = manifestJson(
                id = "old",
                publishedAt = "2026-05-12T11:59:00Z",
                markdownUrl = markdownUrl
            ),
            markdown = "# Old"
        )
        val prefs = RemoteNoticePreferences(createDataStore())

        val notice = repository(provider, prefs).fetchStartupNotice(now = now)

        assertNull(notice)
        assertEquals(now, prefs.noticeBaselineAt.first())
        assertEquals(now.toEpochMilli(), prefs.lastCheckAtMs.first())
        coVerify(exactly = 0) { provider.fetchNoticeMarkdown(any()) }
    }

    @Test
    fun `notice after existing baseline returns display model without marking seen`() = runTest {
        val provider = mockProvider(
            manifest = manifestJson(
                id = "new",
                title = "Important",
                publishedAt = "2026-05-12T12:01:00Z",
                markdownUrl = markdownUrl
            ),
            markdown = "# Important\n\n![Image](https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/images/a.png)"
        )
        val prefs = RemoteNoticePreferences(createDataStore())
        prefs.setNoticeBaselineAtIfAbsent(Instant.parse("2026-05-12T12:00:00Z"))

        val notice = repository(provider, prefs).fetchStartupNotice(now = Instant.parse("2026-05-12T12:02:00Z"))

        assertEquals("new", notice?.id)
        assertEquals("Important", notice?.title)
        assertTrue(notice?.markdown.orEmpty().contains("![Image]"))
        assertEquals(markdownUrl, notice?.markdownUrl)
        assertTrue(prefs.seenNoticeIds.first().isEmpty())
    }

    @Test
    fun `seen notice is skipped`() = runTest {
        val provider = mockProvider(
            manifest = manifestJson(id = "new", publishedAt = "2026-05-12T12:01:00Z", markdownUrl = markdownUrl),
            markdown = "# Important"
        )
        val prefs = RemoteNoticePreferences(createDataStore())
        prefs.setNoticeBaselineAtIfAbsent(Instant.parse("2026-05-12T12:00:00Z"))
        prefs.markSeen("new")

        val notice = repository(provider, prefs).fetchStartupNotice(now = Instant.parse("2026-05-12T12:02:00Z"))

        assertNull(notice)
        coVerify(exactly = 0) { provider.fetchNoticeMarkdown(any()) }
    }

    @Test
    fun `markdown fetch failure or blank markdown returns no display model and leaves seen ids unchanged`() = runTest {
        val prefs = RemoteNoticePreferences(createDataStore())
        prefs.setNoticeBaselineAtIfAbsent(Instant.parse("2026-05-12T12:00:00Z"))
        val failingMarkdownProvider = mockProvider(
            manifest = manifestJson(id = "new", publishedAt = "2026-05-12T12:01:00Z", markdownUrl = markdownUrl),
            markdownResult = IntegrationCallResult.HttpError(404)
        )

        assertNull(repository(failingMarkdownProvider, prefs).fetchStartupNotice(now = Instant.parse("2026-05-12T12:02:00Z")))
        assertTrue(prefs.seenNoticeIds.first().isEmpty())

        val blankPrefs = RemoteNoticePreferences(createDataStore())
        blankPrefs.setNoticeBaselineAtIfAbsent(Instant.parse("2026-05-12T12:00:00Z"))
        val blankMarkdownProvider = mockProvider(
            manifest = manifestJson(id = "new", publishedAt = "2026-05-12T12:01:00Z", markdownUrl = markdownUrl),
            markdown = "   \n\t"
        )

        assertNull(repository(blankMarkdownProvider, blankPrefs).fetchStartupNotice(now = Instant.parse("2026-05-12T12:02:00Z")))
        assertTrue(blankPrefs.seenNoticeIds.first().isEmpty())
    }

    @Test
    fun `manifest fetch failure and malformed manifest fail closed without setting baseline`() = runTest {
        val failingProvider = mockk<GitHubRawContentIntegrationProvider>()
        coEvery { failingProvider.fetchNoticeManifest(manifestUrl) } returns IntegrationCallResult.NetworkError(RuntimeException("offline"))
        val failingPrefs = RemoteNoticePreferences(createDataStore())

        assertNull(repository(failingProvider, failingPrefs).fetchStartupNotice(now = now))
        assertNull(failingPrefs.noticeBaselineAt.first())

        val malformedProvider = mockk<GitHubRawContentIntegrationProvider>()
        coEvery { malformedProvider.fetchNoticeManifest(manifestUrl) } returns IntegrationCallResult.Success("{")
        val malformedPrefs = RemoteNoticePreferences(createDataStore())

        assertNull(repository(malformedProvider, malformedPrefs).fetchStartupNotice(now = now))
        assertNull(malformedPrefs.noticeBaselineAt.first())
    }

    @Test
    fun `cancellation is rethrown`() = runTest {
        val manifestProvider = mockk<GitHubRawContentIntegrationProvider>()
        coEvery { manifestProvider.fetchNoticeManifest(manifestUrl) } throws CancellationException("manifest cancelled")

        try {
            repository(manifestProvider, RemoteNoticePreferences(createDataStore())).fetchStartupNotice(now = now)
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("manifest cancelled", exception.message)
        }

        val markdownProvider = mockProvider(
            manifest = manifestJson(id = "new", publishedAt = "2026-05-12T12:01:00Z", markdownUrl = markdownUrl),
            markdownException = CancellationException("markdown cancelled")
        )
        val prefs = RemoteNoticePreferences(createDataStore())
        prefs.setNoticeBaselineAtIfAbsent(Instant.parse("2026-05-12T12:00:00Z"))

        try {
            repository(markdownProvider, prefs).fetchStartupNotice(now = Instant.parse("2026-05-12T12:02:00Z"))
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("markdown cancelled", exception.message)
        }
    }

    private fun repository(
        provider: GitHubRawContentIntegrationProvider,
        prefs: RemoteNoticePreferences
    ) = RemoteNoticeRepository(
        gitHubRawContentIntegrationProvider = provider,
        remoteNoticePreferences = prefs,
        moshi = Moshi.Builder().build(),
        manifestUrl = manifestUrl,
        appVersion = "1.5.0"
    )

    private fun mockProvider(
        manifest: String,
        markdown: String = "# Important",
        markdownResult: IntegrationCallResult<String>? = null,
        markdownException: Exception? = null
    ): GitHubRawContentIntegrationProvider {
        val provider = mockk<GitHubRawContentIntegrationProvider>()
        coEvery { provider.fetchNoticeManifest(manifestUrl) } returns IntegrationCallResult.Success(manifest)
        when {
            markdownException != null -> coEvery { provider.fetchNoticeMarkdown(markdownUrl) } throws markdownException
            markdownResult != null -> coEvery { provider.fetchNoticeMarkdown(markdownUrl) } returns markdownResult
            else -> coEvery { provider.fetchNoticeMarkdown(markdownUrl) } returns IntegrationCallResult.Success(markdown)
        }
        return provider
    }

    private fun manifestJson(
        id: String,
        title: String = "Notice",
        publishedAt: String,
        markdownUrl: String
    ): String = """
        {
          "schemaVersion": 1,
          "notices": [
            {
              "id": "$id",
              "title": "$title",
              "publishedAt": "$publishedAt",
              "markdownUrl": "$markdownUrl"
            }
          ]
        }
    """.trimIndent()

    private fun TestScope.createDataStore(): DataStore<Preferences> {
        val tempFile = File.createTempFile("remote_notice_repo_test", ".preferences_pb")
        tempFile.deleteOnExit()
        return PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFile }
        )
    }
}
