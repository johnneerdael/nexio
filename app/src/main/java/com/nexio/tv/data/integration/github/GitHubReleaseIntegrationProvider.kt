package com.nexio.tv.data.integration.github

import com.nexio.tv.core.integration.GitHubApiShapes
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.remote.api.GitHubReleaseApi
import com.nexio.tv.data.remote.dto.GitHubReleaseDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class GitHubReleaseIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val gitHubReleaseApi: GitHubReleaseApi
) {
    suspend fun fetchLatestRelease(
        owner: String,
        repo: String
    ): IntegrationCallResult<GitHubReleaseDto> {
        return runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.GITHUB,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.ProviderConfig("github:$owner:$repo"),
                apiShapeId = GitHubApiShapes.LATEST_RELEASE,
                operationKey = "github.release.fetchLatestRelease",
                call = {
                    try {
                        val response = gitHubReleaseApi.getLatestRelease(owner = owner, repo = repo)
                        val body = response.body()
                        if (!response.isSuccessful) {
                            IntegrationCallResult.HttpError(
                                statusCode = response.code(),
                                reason = "github_latest_release_failed"
                            )
                        } else if (body == null) {
                            IntegrationCallResult.Missing
                        } else {
                            IntegrationCallResult.Success(body)
                        }
                    } catch (exception: Exception) {
                        if (exception is CancellationException) throw exception
                        IntegrationCallResult.NetworkError(exception)
                    }
                }
            )
        )
    }

    suspend fun fetchReleases(
        owner: String,
        repo: String,
        perPage: Int
    ): IntegrationCallResult<List<GitHubReleaseDto>> {
        return runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.GITHUB,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.ProviderConfig("github:$owner:$repo"),
                apiShapeId = GitHubApiShapes.LIST_RELEASES,
                operationKey = "github.release.fetchReleases",
                call = {
                    try {
                        val response = gitHubReleaseApi.getReleases(
                            owner = owner,
                            repo = repo,
                            perPage = perPage
                        )
                        val body = response.body()
                        if (!response.isSuccessful) {
                            IntegrationCallResult.HttpError(
                                statusCode = response.code(),
                                reason = "github_list_releases_failed"
                            )
                        } else if (body == null) {
                            IntegrationCallResult.Missing
                        } else {
                            IntegrationCallResult.Success(body)
                        }
                    } catch (exception: Exception) {
                        if (exception is CancellationException) throw exception
                        IntegrationCallResult.NetworkError(exception)
                    }
                }
            )
        )
    }
}
