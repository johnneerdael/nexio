package com.nexio.tv.updater

import com.nexio.tv.data.remote.dto.GitHubAssetDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelAssetSelectorTest {

    private fun asset(name: String) = GitHubAssetDto(
        name = name,
        browserDownloadUrl = "https://example.invalid/$name",
        size = 1L,
        contentType = "application/vnd.android.package-archive"
    )

    @Test
    fun `Stable picks nexio-release asset when both channels present`() {
        val picked = ChannelAssetSelector.choose(
            UpdateChannel.Stable,
            listOf(asset("nexio-earlyaccess.apk"), asset("nexio-release.apk"))
        )
        assertEquals("nexio-release.apk", picked?.name)
    }

    @Test
    fun `EarlyAccess picks nexio-earlyaccess asset when both channels present`() {
        val picked = ChannelAssetSelector.choose(
            UpdateChannel.EarlyAccess,
            listOf(asset("nexio-release.apk"), asset("nexio-earlyaccess.apk"))
        )
        assertEquals("nexio-earlyaccess.apk", picked?.name)
    }

    @Test
    fun `Stable rejects unprefixed apk`() {
        val picked = ChannelAssetSelector.choose(
            UpdateChannel.Stable,
            listOf(asset("app-universal-release.apk"))
        )
        assertNull(picked)
    }

    @Test
    fun `EarlyAccess returns null when no early-access asset present`() {
        val picked = ChannelAssetSelector.choose(
            UpdateChannel.EarlyAccess,
            listOf(asset("nexio-release.apk"))
        )
        assertNull(picked)
    }

    @Test
    fun `Stable returns null on empty list`() {
        assertNull(ChannelAssetSelector.choose(UpdateChannel.Stable, emptyList()))
    }

    @Test
    fun `Prefix match is case-insensitive`() {
        val picked = ChannelAssetSelector.choose(
            UpdateChannel.EarlyAccess,
            listOf(asset("NEXIO-EARLYACCESS.APK"))
        )
        assertEquals("NEXIO-EARLYACCESS.APK", picked?.name)
    }

    @Test
    fun `Non-apk files in channel are rejected`() {
        val picked = ChannelAssetSelector.choose(
            UpdateChannel.EarlyAccess,
            listOf(asset("nexio-earlyaccess.apk.sha256"))
        )
        assertNull(picked)
    }
}
