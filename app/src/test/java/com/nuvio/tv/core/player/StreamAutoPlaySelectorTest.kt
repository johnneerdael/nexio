package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAutoPlaySelectorTest {
    @Test
    fun `orders addon streams before plugin streams using installed order`() {
        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(
                AddonStreams("Plugin-X", null, emptyList()),
                AddonStreams("Addon-B", null, emptyList()),
                AddonStreams("Addon-A", null, emptyList())
            ),
            installedOrder = listOf("Addon-A", "Addon-B")
        )

        assertEquals(listOf("Addon-A", "Addon-B", "Plugin-X"), ordered.map { it.addonName })
    }

    @Test
    fun `returns null when mode is manual`() {
        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(stream(addon = "Addon-A", url = "https://a")),
            mode = StreamAutoPlayMode.MANUAL,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Addon-A"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        assertNull(selected)
    }

    @Test
    fun `first stream mode picks first candidate with playable url`() {
        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(
                stream(addon = "Addon-A", url = null),
                stream(addon = "Addon-B", url = "https://playable")
            ),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Addon-A", "Addon-B"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        assertNotNull(selected)
        assertEquals("Addon-B", selected?.addonName)
    }

    @Test
    fun `regex mode respects source and selected filters`() {
        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(
                stream(addon = "Addon-A", url = "https://a", title = "1080p"),
                stream(addon = "Plugin-Z", url = "https://z", title = "4k HDR")
            ),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "4k",
            source = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
            installedAddonNames = setOf("Addon-A"),
            selectedAddons = emptySet(),
            selectedPlugins = setOf("Plugin-Z")
        )

        assertNotNull(selected)
        assertEquals("Plugin-Z", selected?.addonName)
    }

    private fun stream(addon: String, url: String?, title: String? = null): Stream {
        return Stream(
            name = title,
            title = title,
            description = title,
            url = url,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = addon,
            addonLogo = null
        )
    }
}
