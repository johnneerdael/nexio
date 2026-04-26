package com.nexio.tv.updater

import com.nexio.tv.BuildConfig
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.github.GitHubReleaseIntegrationProvider
import com.nexio.tv.data.remote.dto.GitHubAssetDto
import com.nexio.tv.data.remote.dto.GitHubReleaseDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateRepositoryTest {

    @Test
    fun `getLatestUpdate maps provider release into app update`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO) } returns IntegrationCallResult.Success(
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

        val repository = UpdateRepository(provider)
        val result = repository.getLatestUpdate()

        assertTrue(result.isSuccess)
        val update = result.getOrThrow()
        assertEquals("v1.2.3", update.tag)
        assertEquals("Nexio 1.2.3", update.title)
        assertEquals("Patch notes", update.notes)
        assertEquals("https://github.com/nexio/release", update.releaseUrl)
        assertEquals("nexio-arm64-v8a.apk", update.assetName)
        assertEquals("https://github.com/nexio/release/app.apk", update.assetUrl)
        assertEquals(42L, update.assetSizeBytes)
        coVerify(exactly = 1) {
            provider.fetchLatestRelease(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO)
        }
    }

    @Test
    fun `getLatestUpdate rejects draft and prerelease responses from provider`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns IntegrationCallResult.Success(
            GitHubReleaseDto(
                tagName = "v1.2.3",
                draft = true,
                prerelease = false
            )
        )

        val repository = UpdateRepository(provider)
        val result = repository.getLatestUpdate()

        assertTrue(result.isFailure)
        assertEquals(
            "Latest release is draft/prerelease",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun `getLatestUpdate uses release name as title when tag is set`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO) } returns IntegrationCallResult.Success(
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

        val repository = UpdateRepository(provider)
        val result = repository.getLatestUpdate()

        assertTrue(result.isSuccess)
        assertEquals("Nexio 1.2.3", result.getOrThrow().title)
    }

    @Test
    fun `getLatestUpdate uses tag as title when release name is blank`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO) } returns IntegrationCallResult.Success(
            GitHubReleaseDto(
                tagName = "v1.2.3",
                name = "   ",
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

        val repository = UpdateRepository(provider)
        val result = repository.getLatestUpdate()

        assertTrue(result.isSuccess)
        assertEquals("v1.2.3", result.getOrThrow().tag)
        assertEquals("v1.2.3", result.getOrThrow().title)
    }

    @Test
    fun `getLatestUpdate fails when tagName is blank`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO) } returns IntegrationCallResult.Success(
            GitHubReleaseDto(
                tagName = null,
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

        val repository = UpdateRepository(provider)
        val result = repository.getLatestUpdate()

        assertTrue(result.isFailure)
        assertEquals("Release has no tag", result.exceptionOrNull()?.message)
    }

    @Test
    fun `getLatestUpdate maps github http failures to user facing error`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery {
            provider.fetchLatestRelease(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO)
        } returns IntegrationCallResult.HttpError(statusCode = 503)

        val repository = UpdateRepository(provider)
        val result = repository.getLatestUpdate()

        assertTrue(result.isFailure)
        assertEquals("GitHub API error: 503", result.exceptionOrNull()?.message)
    }

    @Test
    fun `getLatestUpdate maps github network failures to user facing error`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery {
            provider.fetchLatestRelease(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO)
        } returns IntegrationCallResult.NetworkError(IllegalStateException("service unavailable"))

        val repository = UpdateRepository(provider)
        val result = repository.getLatestUpdate()

        assertTrue(result.isFailure)
        assertEquals("Unable to contact GitHub: service unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    fun `getLatestUpdate maps missing github release response to user facing error`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery {
            provider.fetchLatestRelease(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO)
        } returns IntegrationCallResult.Missing

        val repository = UpdateRepository(provider)
        val result = repository.getLatestUpdate()

        assertTrue(result.isFailure)
        assertEquals("Empty GitHub release response", result.exceptionOrNull()?.message)
    }

    @Test
    fun `getLatestUpdate rethrows cancellation from provider`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery {
            provider.fetchLatestRelease(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO)
        } throws CancellationException("cancelled")

        val repository = UpdateRepository(provider)

        try {
            repository.getLatestUpdate()
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("cancelled", exception.message)
        }
    }
}
