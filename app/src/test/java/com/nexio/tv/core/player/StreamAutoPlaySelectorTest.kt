package com.nexio.tv.core.player

import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.StreamAutoPlaySource
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.test.runTest

class StreamAutoPlaySelectorTest {

    @Test
    fun `bingeGroup-first selects matching stream before first stream mode`() = runTest {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "1080p",
            bingeGroup = "other-group"
        )
        val preferred = stream(
            addonName = "AddonB",
            url = "https://example.com/preferred.m3u8",
            name = "720p",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, preferred),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            preferredBingeGroup = "same-group"
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `falls back to normal mode when no bingeGroup match exists`() = runTest {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "First",
            bingeGroup = "group-a"
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            name = "Second",
            bingeGroup = "group-b"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            preferredBingeGroup = "missing-group"
        )

        assertEquals(first, selected)
    }

    @Test
    fun `bingeGroup-first respects installed addon source filter`() = runTest {
        val filteredOutAddonMatch = stream(
            addonName = "AddonFilteredOut",
            url = "https://example.com/addon-match.m3u8",
            bingeGroup = "same-group"
        )
        val allowedAddonMatch = stream(
            addonName = "AddonAllowed",
            url = "https://example.com/addon-allowed.m3u8",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(filteredOutAddonMatch, allowedAddonMatch),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
            installedAddonNames = setOf("AddonAllowed"),
            selectedAddons = emptySet(),
            preferredBingeGroup = "same-group"
        )

        assertEquals(allowedAddonMatch, selected)
    }

    @Test
    fun `regex mode still works when bingeGroup missing or no match`() = runTest {
        val nonMatch = stream(
            addonName = "AddonA",
            url = "https://example.com/a.m3u8",
            name = "720p"
        )
        val regexMatch = stream(
            addonName = "AddonB",
            url = "https://example.com/b.m3u8?signature=test",
            name = "2160p Remux"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(nonMatch, regexMatch),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "2160p|Remux",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            preferredBingeGroup = "unmatched-group"
        )

        assertEquals(regexMatch, selected)
    }

    @Test
    fun `blank preferredBingeGroup behaves as disabled`() = runTest {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            bingeGroup = "group-a"
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            bingeGroup = "group-b"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            preferredBingeGroup = "   "
        )

        assertEquals(first, selected)
    }

    @Test
    fun `bingeGroup matching ignores case and surrounding whitespace`() = runTest {
        val preferred = stream(
            addonName = "AddonA",
            url = "https://example.com/preferred.m3u8",
            bingeGroup = " Show:Same-Group "
        )
        val fallback = stream(
            addonName = "AddonB",
            url = "https://example.com/fallback.m3u8",
            bingeGroup = "other-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(fallback, preferred),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            preferredBingeGroup = "show:same-group"
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `fallback bingeGroup matches equivalent parsed releases when addon omits bingeGroup`() = runTest {
        val current = stream(
            addonName = "AddonA",
            url = "https://example.com/current.m3u8",
            filename = "Shrinking.S03E06.Dereks.Dont.Die.1080p.ATVP.WEB-DL.DDP5.1.Atmos.ENG.ITA.H264-TheShrink.mkv"
        )
        val preferred = stream(
            addonName = "AddonA",
            url = "https://example.com/preferred.m3u8",
            filename = "Shrinking.S03E07.Somebody.Cries.1080p.ATVP.WEB-DL.DDP5.1.Atmos.ENG.ITA.H264-TheShrink.mkv"
        )
        val fallback = stream(
            addonName = "AddonB",
            url = "https://example.com/fallback.m3u8",
            filename = "Shrinking.S03E07.Somebody.Cries.720p.WEB-DL.x264-OtherGroup.mkv"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(fallback, preferred),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            preferredBingeGroup = com.nexio.tv.core.stream.StreamBingeGroupResolver.resolve(current)
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `manual mode remains manual even with matching bingeGroup`() = runTest {
        val matched = stream(
            addonName = "AddonA",
            url = "https://example.com/match.m3u8",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(matched),
            mode = StreamAutoPlayMode.MANUAL,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            preferredBingeGroup = "same-group"
        )

        assertNull(selected)
    }

    @Test
    fun `regex mode uses caller supplied probe for url viability`() = runTest {
        val rejected = stream(
            addonName = "AddonA",
            url = "https://example.com/rejected.m3u8",
            name = "2160p Remux"
        )
        val accepted = stream(
            addonName = "AddonB",
            url = "https://example.com/accepted.m3u8",
            name = "2160p Remux"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(rejected, accepted),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "2160p|Remux",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            probeUrlWorks = { url -> url.contains("accepted") }
        )

        assertEquals(accepted, selected)
    }

    private fun stream(
        addonName: String,
        url: String? = null,
        name: String? = null,
        bingeGroup: String? = null,
        filename: String? = null
    ): Stream = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = bingeGroup,
            countryWhitelist = null,
            proxyHeaders = null,
            filename = filename
        ),
        addonName = addonName,
        addonLogo = null
    )
}
