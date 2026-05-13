package com.nexio.tv.data.integration.github

import com.nexio.tv.core.integration.GitHubApiShapes
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.remote.api.GitHubRawContentApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class GitHubRawContentIntegrationProviderTest {

    @Test
    fun `fetchText routes manifest request through integration runtime`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val api = mockk<GitHubRawContentApi>()
        val specSlot = slot<IntegrationCallSpec<String>>()

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            firstArg<IntegrationCallSpec<String>>().call()
        }
        coEvery { api.getText("https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/manifest.json") } returns
            Response.success("""{"schemaVersion":1}""".toResponseBody("application/json".toMediaType()))

        val provider = GitHubRawContentIntegrationProvider(runtime, api)
        val result = provider.fetchNoticeManifest(
            "https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/manifest.json"
        )

        assertTrue(result is IntegrationCallResult.Success)
        assertEquals("""{"schemaVersion":1}""", (result as IntegrationCallResult.Success).value)
        assertEquals(IntegrationProvider.GITHUB, specSlot.captured.provider)
        assertEquals(GitHubApiShapes.NOTICE_MANIFEST, specSlot.captured.apiShapeId)
        assertEquals("github.notice.fetchManifest", specSlot.captured.operationKey)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
        assertEquals(IntegrationScope.ProviderConfig("github:notices"), specSlot.captured.scope)
        coVerify(exactly = 1) { api.getText("https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/manifest.json") }
    }

    @Test
    fun `fetchText maps http error`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val api = mockk<GitHubRawContentApi>()
        coEvery { runtime.call(any<IntegrationCallSpec<String>>()) } coAnswers {
            firstArg<IntegrationCallSpec<String>>().call()
        }
        coEvery { api.getText("https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/a.md") } returns
            Response.error(404, ByteArray(0).toResponseBody("text/plain".toMediaType()))

        val result = GitHubRawContentIntegrationProvider(runtime, api)
            .fetchNoticeMarkdown("https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/a.md")

        assertTrue(result is IntegrationCallResult.HttpError)
        assertEquals(404, (result as IntegrationCallResult.HttpError).statusCode)
    }

    @Test
    fun `fetchText maps network error`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val api = mockk<GitHubRawContentApi>()
        coEvery { runtime.call(any<IntegrationCallSpec<String>>()) } coAnswers {
            firstArg<IntegrationCallSpec<String>>().call()
        }
        coEvery { api.getText(any()) } throws IOException("offline")

        val result = GitHubRawContentIntegrationProvider(runtime, api)
            .fetchNoticeManifest("https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/manifest.json")

        assertTrue(result is IntegrationCallResult.NetworkError)
        assertEquals("offline", (result as IntegrationCallResult.NetworkError).throwable.message)
    }

    @Test
    fun `fetchText rethrows cancellation`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val api = mockk<GitHubRawContentApi>()
        coEvery { runtime.call(any<IntegrationCallSpec<String>>()) } coAnswers {
            firstArg<IntegrationCallSpec<String>>().call()
        }
        coEvery { api.getText(any()) } throws CancellationException("cancelled")

        try {
            GitHubRawContentIntegrationProvider(runtime, api)
                .fetchNoticeManifest("https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/manifest.json")
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("cancelled", exception.message)
        }
    }
}
