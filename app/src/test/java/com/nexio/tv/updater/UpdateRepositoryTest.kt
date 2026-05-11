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
                        name = "nexio-release-arm64-v8a.apk",
                        browserDownloadUrl = "https://github.com/nexio/release/app.apk",
                        size = 42L,
                        contentType = "application/vnd.android.package-archive"
                    )
                )
            )
        )

        val repository = UpdateRepository(provider, UpdateChannel.Stable)
        val result = repository.getLatestUpdate()

        assertTrue(result.isSuccess)
        val update = result.getOrThrow()
        assertEquals("v1.2.3", update.tag)
        assertEquals("Nexio 1.2.3", update.title)
        assertEquals("Patch notes", update.notes)
        assertEquals("https://github.com/nexio/release", update.releaseUrl)
        assertEquals("nexio-release-arm64-v8a.apk", update.assetName)
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

        val repository = UpdateRepository(provider, UpdateChannel.Stable)
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
                        name = "nexio-release-arm64-v8a.apk",
                        browserDownloadUrl = "https://github.com/nexio/release/app.apk",
                        size = 42L,
                        contentType = "application/vnd.android.package-archive"
                    )
                )
            )
        )

        val repository = UpdateRepository(provider, UpdateChannel.Stable)
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
                        name = "nexio-release-arm64-v8a.apk",
                        browserDownloadUrl = "https://github.com/nexio/release/app.apk",
                        size = 42L,
                        contentType = "application/vnd.android.package-archive"
                    )
                )
            )
        )

        val repository = UpdateRepository(provider, UpdateChannel.Stable)
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
                        name = "nexio-release-arm64-v8a.apk",
                        browserDownloadUrl = "https://github.com/nexio/release/app.apk",
                        size = 42L,
                        contentType = "application/vnd.android.package-archive"
                    )
                )
            )
        )

        val repository = UpdateRepository(provider, UpdateChannel.Stable)
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

        val repository = UpdateRepository(provider, UpdateChannel.Stable)
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

        val repository = UpdateRepository(provider, UpdateChannel.Stable)
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

        val repository = UpdateRepository(provider, UpdateChannel.Stable)
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

        val repository = UpdateRepository(provider, UpdateChannel.Stable)

        try {
            repository.getLatestUpdate()
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("cancelled", exception.message)
        }
    }

    @Test
    fun `EA channel returns EA asset from stable release when only stable exists`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.Success(
                GitHubReleaseDto(
                    tagName = "v0.57",
                    prerelease = false,
                    htmlUrl = "https://example.invalid/v0.57",
                    assets = listOf(
                        GitHubAssetDto(
                            name = "nexio-release.apk",
                            browserDownloadUrl = "https://example.invalid/nexio-release.apk",
                            size = 10L,
                            contentType = null
                        ),
                        GitHubAssetDto(
                            name = "nexio-earlyaccess.apk",
                            browserDownloadUrl = "https://example.invalid/nexio-earlyaccess.apk",
                            size = 11L,
                            contentType = null
                        )
                    )
                )
            )
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.Success(emptyList())

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertTrue(result.isSuccess)
        assertEquals("v0.57", result.getOrThrow().tag)
        assertEquals("nexio-earlyaccess.apk", result.getOrThrow().assetName)
    }

    @Test
    fun `EA channel returns prerelease when stable lookup is 404 and prerelease present`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.HttpError(statusCode = 404)
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.Success(
                listOf(
                    GitHubReleaseDto(
                        tagName = "v0.56",
                        prerelease = true,
                        assets = listOf(
                            GitHubAssetDto(
                                name = "nexio-earlyaccess.apk",
                                browserDownloadUrl = "https://example.invalid/ea.apk",
                                size = 5L,
                                contentType = null
                            )
                        )
                    )
                )
            )

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertTrue(result.isSuccess)
        assertEquals("v0.56", result.getOrThrow().tag)
    }

    @Test
    fun `EA channel picks prerelease when prerelease is newer than stable`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.Success(
                GitHubReleaseDto(
                    tagName = "v0.55",
                    prerelease = false,
                    assets = listOf(
                        GitHubAssetDto(
                            name = "nexio-earlyaccess.apk",
                            browserDownloadUrl = "https://example.invalid/v055-ea.apk",
                            size = 1L,
                            contentType = null
                        )
                    )
                )
            )
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.Success(
                listOf(
                    GitHubReleaseDto(
                        tagName = "v0.56",
                        prerelease = true,
                        assets = listOf(
                            GitHubAssetDto(
                                name = "nexio-earlyaccess.apk",
                                browserDownloadUrl = "https://example.invalid/v056-ea.apk",
                                size = 1L,
                                contentType = null
                            )
                        )
                    )
                )
            )

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertEquals("v0.56", result.getOrThrow().tag)
        assertEquals("https://example.invalid/v056-ea.apk", result.getOrThrow().assetUrl)
    }

    @Test
    fun `EA channel picks stable when stable is newer than prerelease`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.Success(
                GitHubReleaseDto(
                    tagName = "v0.57",
                    prerelease = false,
                    assets = listOf(
                        GitHubAssetDto(
                            name = "nexio-earlyaccess.apk",
                            browserDownloadUrl = "https://example.invalid/v057-ea.apk",
                            size = 1L,
                            contentType = null
                        )
                    )
                )
            )
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.Success(
                listOf(
                    GitHubReleaseDto(
                        tagName = "v0.56",
                        prerelease = true,
                        assets = listOf(
                            GitHubAssetDto(
                                name = "nexio-earlyaccess.apk",
                                browserDownloadUrl = "https://example.invalid/v056-ea.apk",
                                size = 1L,
                                contentType = null
                            )
                        )
                    )
                )
            )

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertEquals("v0.57", result.getOrThrow().tag)
        assertEquals("https://example.invalid/v057-ea.apk", result.getOrThrow().assetUrl)
    }

    @Test
    fun `EA channel ties go to stable`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.Success(
                GitHubReleaseDto(
                    tagName = "v0.57",
                    prerelease = false,
                    name = "Stable",
                    assets = listOf(
                        GitHubAssetDto(
                            name = "nexio-earlyaccess.apk",
                            browserDownloadUrl = "https://example.invalid/stable.apk",
                            size = 1L,
                            contentType = null
                        )
                    )
                )
            )
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.Success(
                listOf(
                    GitHubReleaseDto(
                        tagName = "v0.57",
                        prerelease = true,
                        name = "Pre",
                        assets = listOf(
                            GitHubAssetDto(
                                name = "nexio-earlyaccess.apk",
                                browserDownloadUrl = "https://example.invalid/pre.apk",
                                size = 1L,
                                contentType = null
                            )
                        )
                    )
                )
            )

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertEquals("Stable", result.getOrThrow().title)
        assertEquals("https://example.invalid/stable.apk", result.getOrThrow().assetUrl)
    }

    @Test
    fun `EA channel fails when both arms return null candidates`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.HttpError(statusCode = 404)
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.Success(emptyList())

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertTrue(result.isFailure)
        assertEquals(
            "No release found for early-access channel",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun `EA channel propagates stable HttpError when prerelease arm also failed`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.HttpError(statusCode = 503)
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.NetworkError(IllegalStateException("offline"))

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "Expected to surface a GitHub error, got: $message",
            message.startsWith("GitHub API error:") || message.startsWith("Unable to contact GitHub:")
        )
    }

    @Test
    fun `EA channel soft-misses prerelease arm error when stable candidate succeeded`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.Success(
                GitHubReleaseDto(
                    tagName = "v0.57",
                    prerelease = false,
                    assets = listOf(
                        GitHubAssetDto(
                            name = "nexio-earlyaccess.apk",
                            browserDownloadUrl = "https://example.invalid/ea.apk",
                            size = 1L,
                            contentType = null
                        )
                    )
                )
            )
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.NetworkError(IllegalStateException("offline"))

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertTrue(result.isSuccess)
        assertEquals("v0.57", result.getOrThrow().tag)
    }

    @Test
    fun `EA channel fails when winner has no EA asset`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.Success(
                GitHubReleaseDto(
                    tagName = "v0.57",
                    prerelease = false,
                    assets = listOf(
                        GitHubAssetDto(
                            name = "nexio-release.apk",
                            browserDownloadUrl = "https://example.invalid/stable.apk",
                            size = 1L,
                            contentType = null
                        )
                    )
                )
            )
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.Success(emptyList())

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertTrue(result.isFailure)
        assertEquals(
            "Release v0.57 has no nexio-earlyaccess.apk asset",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun `Stable channel fails when release has no nexio-release asset`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.Success(
                GitHubReleaseDto(
                    tagName = "v0.57",
                    prerelease = false,
                    assets = listOf(
                        GitHubAssetDto(
                            name = "nexio-earlyaccess.apk",
                            browserDownloadUrl = "https://example.invalid/ea.apk",
                            size = 1L,
                            contentType = null
                        )
                    )
                )
            )

        val result = UpdateRepository(provider, UpdateChannel.Stable).getLatestUpdate()

        assertTrue(result.isFailure)
        assertEquals(
            "Release v0.57 has no nexio-release.apk asset",
            result.exceptionOrNull()?.message
        )
    }
}
