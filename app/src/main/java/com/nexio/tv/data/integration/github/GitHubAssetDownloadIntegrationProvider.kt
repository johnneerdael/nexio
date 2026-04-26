package com.nexio.tv.data.integration.github

import com.nexio.tv.core.integration.GitHubApiShapes
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationStreamHandle
import com.nexio.tv.core.integration.IntegrationStreamSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.integration.github.transport.GitHubAssetDownloadTransport
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

data class GitHubAssetDownloadStream(
    val inputStream: InputStream,
    val totalBytes: Long?
)

@Singleton
class GitHubAssetDownloadIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val transport: GitHubAssetDownloadTransport
) {
    suspend fun openDownload(url: String): IntegrationStreamHandle<GitHubAssetDownloadStream>? {
        return runtime.open(
            IntegrationStreamSpec(
                provider = IntegrationProvider.GITHUB,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.Account("github:asset"),
                apiShapeId = GitHubApiShapes.ASSET_DOWNLOAD,
                operationKey = "github.asset.openDownload",
                open = {
                    val transportResult = transport.openDownloadStream(url)
                    object : IntegrationStreamHandle<GitHubAssetDownloadStream> {
                        override val value: GitHubAssetDownloadStream =
                            GitHubAssetDownloadStream(
                                inputStream = transportResult.inputStream,
                                totalBytes = transportResult.totalBytes
                            )

                        override fun close() = transportResult.close()
                    }
                }
            )
        )
    }
}
