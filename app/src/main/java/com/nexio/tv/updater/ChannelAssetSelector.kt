package com.nexio.tv.updater

import com.nexio.tv.data.remote.dto.GitHubAssetDto

internal object ChannelAssetSelector {

    fun choose(
        channel: UpdateChannel,
        assets: List<GitHubAssetDto>
    ): GitHubAssetDto? {
        val scoped = assets.filter { asset ->
            asset.name.startsWith(channel.assetPrefix, ignoreCase = true) &&
                asset.name.endsWith(".apk", ignoreCase = true)
        }
        return AbiSelector.chooseBestApkAsset(scoped)
    }
}
