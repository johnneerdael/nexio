package com.nexio.tv.core.player

import android.util.Log
import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.StreamAutoPlaySource
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class StreamAutoPlaySelectorPlaceholderTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        CometProxyUrlResolver.resetForTesting()
    }

    @After
    fun tearDown() {
        CometProxyUrlResolver.resetForTesting()
        unmockkStatic(Log::class)
    }

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

    @Test
    fun `end-to-end selector skips placeholder verdict and lands on redirected stream`() = runBlocking {
        val placeholderUrl = "https://comet.feels.legal/A/playback/p/0/0/n/n?torrent_name=t&name=ph"
        val realUrl = "https://comet.feels.legal/B/playback/r/0/0/n/n?torrent_name=t&name=real"
        val cdnUrl = "https://1-1.download.real-debrid.com/d/AAAA/movie.mp4"
        CometProxyUrlResolver.setTransportForTesting { url, _ ->
            when (url) {
                placeholderUrl -> ProxyResolution.Placeholder
                realUrl -> ProxyResolution.Redirected(cdnUrl)
                else -> error("unexpected url=$url")
            }
        }
        CometProxyUrlResolver.resolve(placeholderUrl, headers = emptyMap())
        CometProxyUrlResolver.resolve(realUrl, headers = emptyMap())
        val placeholder = stream(addonName = "AddonA", url = placeholderUrl)
        val real = stream(addonName = "AddonB", url = realUrl)

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(placeholder, real),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            placeholderPredicate = { stream ->
                val url = stream.getStreamUrl()
                !url.isNullOrBlank() &&
                    CometProxyUrlResolver.lastResolutionFor(url) == ProxyResolution.Placeholder
            }
        )

        assertEquals(real, selected)
    }

    @Test
    fun `end-to-end toggle off keeps placeholder candidate selectable`() = runBlocking {
        val placeholderUrl = "https://comet.feels.legal/A/playback/p/0/0/n/n?torrent_name=t&name=ph"
        val realUrl = "https://comet.feels.legal/B/playback/r/0/0/n/n?torrent_name=t&name=real"
        CometProxyUrlResolver.setTransportForTesting { url, _ ->
            when (url) {
                placeholderUrl -> ProxyResolution.Placeholder
                realUrl -> ProxyResolution.Redirected("https://cdn.example.test/movie.mp4")
                else -> error("unexpected url=$url")
            }
        }
        CometProxyUrlResolver.resolve(placeholderUrl, headers = emptyMap())
        CometProxyUrlResolver.resolve(realUrl, headers = emptyMap())
        val placeholder = stream(addonName = "AddonA", url = placeholderUrl)
        val real = stream(addonName = "AddonB", url = realUrl)

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(placeholder, real),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            placeholderPredicate = { false }
        )

        assertEquals(placeholder, selected)
    }

    @Test
    fun `end-to-end all placeholder verdicts yield null selection`() = runBlocking {
        val urlA = "https://comet.feels.legal/A/playback/p/0/0/n/n?torrent_name=t&name=a"
        val urlB = "https://comet.feels.legal/B/playback/p/0/0/n/n?torrent_name=t&name=b"
        CometProxyUrlResolver.setTransportForTesting { _, _ -> ProxyResolution.Placeholder }
        CometProxyUrlResolver.resolve(urlA, headers = emptyMap())
        CometProxyUrlResolver.resolve(urlB, headers = emptyMap())
        val a = stream(addonName = "AddonA", url = urlA)
        val b = stream(addonName = "AddonB", url = urlB)

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(a, b),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            placeholderPredicate = { stream ->
                val url = stream.getStreamUrl()
                !url.isNullOrBlank() &&
                    CometProxyUrlResolver.lastResolutionFor(url) == ProxyResolution.Placeholder
            }
        )

        assertNull(selected)
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
