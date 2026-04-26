package com.nexio.tv.updater

import com.nexio.tv.data.integration.github.GitHubReleaseIntegrationProvider
import com.nexio.tv.data.remote.api.GitHubReleaseApi
import com.nexio.tv.data.remote.dto.GitHubAssetDto
import com.nexio.tv.data.remote.dto.GitHubReleaseDto
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

class GitHubReleaseIntegrationProviderTest {

    @Test
    fun `fetchLatestRelease routes through runtime and maps successful GitHub release response`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val gitHubReleaseApi = mockk<GitHubReleaseApi>()
        val specSlot = slot<IntegrationCallSpec<GitHubReleaseDto>>()
        var runtimeResult: IntegrationCallResult<GitHubReleaseDto>? = null

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            val result = firstArg<IntegrationCallSpec<GitHubReleaseDto>>().call()
            runtimeResult = result
            result
        }

        coEvery { gitHubReleaseApi.getLatestRelease(owner = "owner", repo = "repo") } returns Response.success(
            GitHubReleaseDto(
                tagName = "v1.2.3",
                name = "Nexio 1.2.3",
                body = "Patch notes",
                htmlUrl = "https://github.com/nexio/release",
                assets = listOf(
                    GitHubAssetDto(
                        name = "nexio-arm64-v8a.apk",
                        browserDownloadUrl = "https://github.com/nexio/release/app.apk",
                        size = 42L,
                        contentType = "application/vnd.android.package-archive"
                    )
                )
            )
        )

        val provider = GitHubReleaseIntegrationProvider(runtime, gitHubReleaseApi)
        val result = provider.fetchLatestRelease(owner = "owner", repo = "repo")

        assertTrue(result is IntegrationCallResult.Success)
        assertEquals("v1.2.3", (result as IntegrationCallResult.Success).value.tagName)
        assertTrue(runtimeResult is IntegrationCallResult.Success)

        assertTrue(specSlot.isCaptured)
        val spec = specSlot.captured
        assertEquals(IntegrationProvider.GITHUB, spec.provider)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, spec.workClass)
        assertEquals(IntegrationScope.Account("github:owner:repo"), spec.scope)

        coVerify(exactly = 1) {
            runtime.call(any<IntegrationCallSpec<GitHubReleaseDto>>())
            gitHubReleaseApi.getLatestRelease("owner", "repo")
        }
    }

    @Test
    fun `fetchLatestRelease converts GitHub HTTP error into unavailable API failure`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val gitHubReleaseApi = mockk<GitHubReleaseApi>()
        val specSlot = slot<IntegrationCallSpec<GitHubReleaseDto>>()
        var runtimeResult: IntegrationCallResult<GitHubReleaseDto>? = null

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            val result = firstArg<IntegrationCallSpec<GitHubReleaseDto>>().call()
            runtimeResult = result
            result
        }

        val errorResponse = Response.error<GitHubReleaseDto>(
            503,
            ByteArray(0).toResponseBody("text/plain".toMediaType())
        )
        coEvery { gitHubReleaseApi.getLatestRelease(owner = "owner", repo = "repo") } returns errorResponse

        val provider = GitHubReleaseIntegrationProvider(runtime, gitHubReleaseApi)
        val result = provider.fetchLatestRelease(owner = "owner", repo = "repo")

        assertTrue(result is IntegrationCallResult.HttpError)
        assertEquals(503, (result as IntegrationCallResult.HttpError).statusCode)
        assertTrue(runtimeResult is IntegrationCallResult.HttpError)
        coVerify(exactly = 1) {
            runtime.call(any<IntegrationCallSpec<GitHubReleaseDto>>())
            gitHubReleaseApi.getLatestRelease("owner", "repo")
        }
    }

    @Test
    fun `fetchLatestRelease converts network failures into contact failure`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val gitHubReleaseApi = mockk<GitHubReleaseApi>()
        val specSlot = slot<IntegrationCallSpec<GitHubReleaseDto>>()
        var runtimeResult: IntegrationCallResult<GitHubReleaseDto>? = null

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            val result = firstArg<IntegrationCallSpec<GitHubReleaseDto>>().call()
            runtimeResult = result
            result
        }

        coEvery {
            gitHubReleaseApi.getLatestRelease(owner = "owner", repo = "repo")
        } throws IOException("unable to reach github")

        val provider = GitHubReleaseIntegrationProvider(runtime, gitHubReleaseApi)
        val result = provider.fetchLatestRelease(owner = "owner", repo = "repo")

        assertTrue(result is IntegrationCallResult.NetworkError)
        assertEquals(
            "unable to reach github",
            (result as IntegrationCallResult.NetworkError).throwable.message
        )
        assertTrue(runtimeResult is IntegrationCallResult.NetworkError)
        coVerify(exactly = 1) {
            runtime.call(any<IntegrationCallSpec<GitHubReleaseDto>>())
            gitHubReleaseApi.getLatestRelease("owner", "repo")
        }
    }

    @Test
    fun `fetchLatestRelease rethrows cancellation exceptions from network call`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val gitHubReleaseApi = mockk<GitHubReleaseApi>()
        val specSlot = slot<IntegrationCallSpec<GitHubReleaseDto>>()

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            firstArg<IntegrationCallSpec<GitHubReleaseDto>>().call()
        }

        coEvery {
            gitHubReleaseApi.getLatestRelease(owner = "owner", repo = "repo")
        } throws CancellationException("cancelled")

        val provider = GitHubReleaseIntegrationProvider(runtime, gitHubReleaseApi)

        try {
            provider.fetchLatestRelease(owner = "owner", repo = "repo")
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("cancelled", exception.message)
        }

        assertTrue(specSlot.isCaptured)
        val spec = specSlot.captured
        assertEquals(IntegrationProvider.GITHUB, spec.provider)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, spec.workClass)
        assertEquals(IntegrationScope.Account("github:owner:repo"), spec.scope)

        coVerify(exactly = 1) {
            runtime.call(any<IntegrationCallSpec<GitHubReleaseDto>>())
            gitHubReleaseApi.getLatestRelease("owner", "repo")
        }
    }
}
