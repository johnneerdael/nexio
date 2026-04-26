package com.nexio.tv.core.player

import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.StreamAutoPlaySource
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAutoPlaySelectorPlaceholderTest {

    @Test
    fun `candidateAutoPlayStreams drops streams flagged by predicate in FIRST_STREAM mode`() {
        val a = stream(addonName = "A", url = "https://addon.example/a")
        val b = stream(addonName = "B", url = "https://addon.example/b")

        val candidates = StreamAutoPlaySelector.candidateAutoPlayStreams(
            streams = listOf(a, b),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("A", "B"),
            selectedAddons = emptySet(),
            placeholderPredicate = { it === a }
        )

        assertEquals(listOf(b), candidates)
    }

    @Test
    fun `candidateAutoPlayStreams keeps all when predicate is constant false`() {
        val a = stream(addonName = "A", url = "https://addon.example/a")
        val b = stream(addonName = "B", url = "https://addon.example/b")

        val candidates = StreamAutoPlaySelector.candidateAutoPlayStreams(
            streams = listOf(a, b),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("A", "B"),
            selectedAddons = emptySet(),
            placeholderPredicate = { false }
        )

        assertEquals(listOf(a, b), candidates)
    }

    @Test
    fun `candidateAutoPlayStreams drops placeholder bingeGroup match and falls back`() {
        val placeholder = stream(
            addonName = "A",
            url = "https://addon.example/placeholder",
            bingeGroup = "group-1"
        )
        val real = stream(
            addonName = "B",
            url = "https://addon.example/real",
            bingeGroup = "group-1"
        )

        val candidates = StreamAutoPlaySelector.candidateAutoPlayStreams(
            streams = listOf(placeholder, real),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("A", "B"),
            selectedAddons = emptySet(),
            preferredBingeGroup = "group-1",
            placeholderPredicate = { it === placeholder }
        )

        assertEquals(listOf(real), candidates)
    }

    @Test
    fun `selectAutoPlayStream returns null when all candidates are placeholders`() {
        val a = stream(addonName = "A", url = "https://addon.example/a")
        val b = stream(addonName = "B", url = "https://addon.example/b")

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(a, b),
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
    fun `candidateAutoPlayStreams drops placeholders in REGEX_MATCH mode`() {
        val placeholder = stream(
            addonName = "A",
            url = "https://addon.example/4k-placeholder",
            name = "4K Remux"
        )
        val real = stream(
            addonName = "B",
            url = "https://addon.example/real-4k",
            name = "4K Remux"
        )

        val candidates = StreamAutoPlaySelector.candidateAutoPlayStreams(
            streams = listOf(placeholder, real),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "4K",
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
        bingeGroup: String? = null,
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
