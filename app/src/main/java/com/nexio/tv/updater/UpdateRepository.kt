package com.nexio.tv.updater

import com.nexio.tv.BuildConfig
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.github.GitHubReleaseIntegrationProvider
import com.nexio.tv.updater.model.AppUpdate
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubReleaseIntegrationProvider: GitHubReleaseIntegrationProvider
) {

    suspend fun getLatestUpdate(): Result<AppUpdate> {
        return try {
            val owner = BuildConfig.GITHUB_OWNER
            val repo = BuildConfig.GITHUB_REPO

            val dto = when (
                val result = gitHubReleaseIntegrationProvider.fetchLatestRelease(
                    owner = owner,
                    repo = repo
                )
            ) {
                is IntegrationCallResult.Success -> result.value
                is IntegrationCallResult.HttpError -> error("GitHub API error: ${result.statusCode}")
                is IntegrationCallResult.NetworkError -> {
                    val detail = result.throwable.message
                        ?.takeIf { it.isNotBlank() }
                        ?: result.throwable::class.simpleName
                        ?: "unknown error"
                    error("Unable to contact GitHub: $detail")
                }
                IntegrationCallResult.Missing -> error("Empty GitHub release response")
            }
            if (dto.draft || dto.prerelease) {
                error("Latest release is draft/prerelease")
            }

            val tag = dto.tagName?.takeIf { it.isNotBlank() }
                ?: error("Release has no tag")

            val asset = AbiSelector.chooseBestApkAsset(dto.assets)
                ?: error("No APK asset found in release")

            Result.success(
                AppUpdate(
                    tag = tag,
                    title = dto.name?.takeIf { it.isNotBlank() } ?: tag,
                    notes = dto.body.orEmpty(),
                    releaseUrl = dto.htmlUrl,
                    assetName = asset.name,
                    assetUrl = asset.browserDownloadUrl,
                    assetSizeBytes = asset.size
                )
            )
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            Result.failure(exception)
        }
    }
}
