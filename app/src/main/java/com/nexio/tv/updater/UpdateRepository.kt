package com.nexio.tv.updater

import com.nexio.tv.BuildConfig
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.github.GitHubReleaseIntegrationProvider
import com.nexio.tv.data.remote.dto.GitHubAssetDto
import com.nexio.tv.data.remote.dto.GitHubReleaseDto
import com.nexio.tv.updater.model.AppUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubReleaseIntegrationProvider: GitHubReleaseIntegrationProvider,
    private val channel: UpdateChannel
) {

    suspend fun getLatestUpdate(): Result<AppUpdate> = try {
        when (channel) {
            UpdateChannel.Stable -> resolveStable()
            UpdateChannel.EarlyAccess -> resolveEarlyAccess()
        }
    } catch (exception: Exception) {
        if (exception is CancellationException) throw exception
        Result.failure(exception)
    }

    private suspend fun resolveStable(): Result<AppUpdate> {
        val dto = fetchLatestOrThrow()
        if (dto.draft || dto.prerelease) {
            error("Latest release is draft/prerelease")
        }
        val tag = dto.tagName?.takeIf { it.isNotBlank() }
            ?: error("Release has no tag")
        val asset = ChannelAssetSelector.choose(UpdateChannel.Stable, dto.assets)
            ?: error("Release $tag has no nexio-release.apk asset")
        return Result.success(toAppUpdate(dto, tag, asset))
    }

    private suspend fun resolveEarlyAccess(): Result<AppUpdate> = coroutineScope {
        val stableDeferred = async {
            gitHubReleaseIntegrationProvider.fetchLatestRelease(
                owner = BuildConfig.GITHUB_OWNER,
                repo = BuildConfig.GITHUB_REPO
            )
        }
        val preDeferred = async {
            gitHubReleaseIntegrationProvider.fetchReleases(
                owner = BuildConfig.GITHUB_OWNER,
                repo = BuildConfig.GITHUB_REPO,
                perPage = 10
            )
        }

        val stableResult = stableDeferred.await()
        val preResult = preDeferred.await()

        val stableCandidate: GitHubReleaseDto? = when (stableResult) {
            is IntegrationCallResult.Success -> stableResult.value.takeIf {
                !it.draft && !it.prerelease
            }
            is IntegrationCallResult.HttpError -> if (stableResult.statusCode == 404) {
                null
            } else {
                throw IllegalStateException("GitHub API error: ${stableResult.statusCode}")
            }
            is IntegrationCallResult.NetworkError -> {
                val detail = stableResult.throwable.message?.takeIf { it.isNotBlank() }
                    ?: stableResult.throwable::class.simpleName
                    ?: "unknown error"
                throw IllegalStateException("Unable to contact GitHub: $detail")
            }
            IntegrationCallResult.Missing -> null
        }

        val preCandidate: GitHubReleaseDto? = when (preResult) {
            is IntegrationCallResult.Success -> preResult.value.firstOrNull {
                it.prerelease && !it.draft
            }
            is IntegrationCallResult.HttpError -> null
            is IntegrationCallResult.NetworkError -> null
            IntegrationCallResult.Missing -> null
        }

        if (stableCandidate == null && preCandidate == null) {
            return@coroutineScope Result.failure<AppUpdate>(
                IllegalStateException("No release found for early-access channel")
            )
        }

        val winnerTag = VersionUtils.pickNewer(
            preCandidate?.tagName,
            stableCandidate?.tagName
        ) ?: error("No release found for early-access channel")

        val winner = if (stableCandidate?.tagName == winnerTag) {
            stableCandidate
        } else {
            preCandidate
        } ?: error("No release found for early-access channel")

        val tag = winner.tagName?.takeIf { it.isNotBlank() }
            ?: error("Release has no tag")
        val asset = ChannelAssetSelector.choose(UpdateChannel.EarlyAccess, winner.assets)
            ?: error("Release $tag has no nexio-earlyaccess.apk asset")

        Result.success(toAppUpdate(winner, tag, asset))
    }

    private suspend fun fetchLatestOrThrow(): GitHubReleaseDto {
        return when (val result = gitHubReleaseIntegrationProvider.fetchLatestRelease(
            owner = BuildConfig.GITHUB_OWNER,
            repo = BuildConfig.GITHUB_REPO
        )) {
            is IntegrationCallResult.Success -> result.value
            is IntegrationCallResult.HttpError -> error("GitHub API error: ${result.statusCode}")
            is IntegrationCallResult.NetworkError -> {
                val detail = result.throwable.message?.takeIf { it.isNotBlank() }
                    ?: result.throwable::class.simpleName
                    ?: "unknown error"
                error("Unable to contact GitHub: $detail")
            }
            IntegrationCallResult.Missing -> error("Empty GitHub release response")
        }
    }

    private fun toAppUpdate(
        dto: GitHubReleaseDto,
        tag: String,
        asset: GitHubAssetDto
    ): AppUpdate = AppUpdate(
        tag = tag,
        title = dto.name?.takeIf { it.isNotBlank() } ?: tag,
        notes = dto.body.orEmpty(),
        releaseUrl = dto.htmlUrl,
        assetName = asset.name,
        assetUrl = asset.browserDownloadUrl,
        assetSizeBytes = asset.size
    )
}
