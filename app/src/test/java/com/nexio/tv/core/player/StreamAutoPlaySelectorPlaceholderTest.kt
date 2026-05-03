package com.nexio.tv.core.player

import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.StreamAutoPlaySource
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAutoPlaySelectorPlaceholderTest {

    @Test
    fun `candidateAutoPlayStreams drops placeholders in first stream mode`() {
        val placeholder = stream(addonName = "A", url = "https://addon.example/placeholder")
        val real = stream(addonName = "B", url = "https://addon.example/real")

        val candidates = StreamAutoPlaySelector.candidateAutoPlayStreams(
            streams = listOf(placeholder, real),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("A", "B"),
            selectedAddons = emptySet(),
            placeholderPredicate = { it === placeholder }
        )

        assertEquals(listOf(real), candidates)
    }

    @Test
    fun `candidateAutoPlayStreams skips placeholder bingeGroup match and keeps real match`() {
        val placeholder = stream(
            addonName = "A",
            url = "https://addon.example/placeholder",
            bingeGroup = "same"
        )
        val real = stream(
            addonName = "B",
            url = "https://addon.example/real",
            bingeGroup = "same"
        )

        val candidates = StreamAutoPlaySelector.candidateAutoPlayStreams(
            streams = listOf(placeholder, real),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("A", "B"),
            selectedAddons = emptySet(),
            preferredBingeGroup = "same",
            placeholderPredicate = { it === placeholder }
        )

        assertEquals(listOf(real), candidates)
    }

    @Test
    fun `selectAutoPlayStream returns null when all candidates are placeholders`() = runTest {
        val first = stream(addonName = "A", url = "https://addon.example/a")
        val second = stream(addonName = "B", url = "https://addon.example/b")

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("A", "B"),
            selectedAddons = emptySet(),
            placeholderPredicate = { true }
        )

        assertNull(selected)
    }

    @Test
    fun `candidateAutoPlayStreams drops placeholders before regex probing`() {
        val placeholder = stream(
            addonName = "A",
            url = "https://addon.example/placeholder-4k",
            name = "2160p Remux"
        )
        val real = stream(
            addonName = "B",
            url = "https://addon.example/real-4k",
            name = "2160p Remux"
        )

        val candidates = StreamAutoPlaySelector.candidateAutoPlayStreams(
            streams = listOf(placeholder, real),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "2160p|Remux",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("A", "B"),
            selectedAddons = emptySet(),
            placeholderPredicate = { it === placeholder }
        )

        assertEquals(listOf(real), candidates)
    }

    private fun stream(
        addonName: String,
        url: String? = null,
        name: String? = null,
        bingeGroup: String? = null
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
            filename = null
        ),
        addonName = addonName,
        addonLogo = null
    )
}
