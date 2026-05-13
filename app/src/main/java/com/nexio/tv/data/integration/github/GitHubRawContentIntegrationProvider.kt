package com.nexio.tv.data.integration.github

import com.nexio.tv.core.integration.GitHubApiShapes
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.remote.api.GitHubRawContentApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class GitHubRawContentIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val gitHubRawContentApi: GitHubRawContentApi
) {
    suspend fun fetchNoticeManifest(url: String): IntegrationCallResult<String> =
        fetchText(
            url = url,
            apiShapeId = GitHubApiShapes.NOTICE_MANIFEST,
            operationKey = "github.notice.fetchManifest",
            reason = "github_notice_manifest_failed"
        )

    suspend fun fetchNoticeMarkdown(url: String): IntegrationCallResult<String> =
        fetchText(
            url = url,
            apiShapeId = GitHubApiShapes.NOTICE_MARKDOWN,
            operationKey = "github.notice.fetchMarkdown",
            reason = "github_notice_markdown_failed"
        )

    private suspend fun fetchText(
        url: String,
        apiShapeId: String,
        operationKey: String,
        reason: String
    ): IntegrationCallResult<String> {
        return runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.GITHUB,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.ProviderConfig("github:notices"),
                apiShapeId = apiShapeId,
                operationKey = operationKey,
                call = {
                    try {
                        val response = gitHubRawContentApi.getText(url)
                        val body = response.body()
                        when {
                            !response.isSuccessful -> IntegrationCallResult.HttpError(
                                statusCode = response.code(),
                                reason = reason
                            )
                            body == null -> IntegrationCallResult.Missing
                            else -> IntegrationCallResult.Success(body.string())
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
