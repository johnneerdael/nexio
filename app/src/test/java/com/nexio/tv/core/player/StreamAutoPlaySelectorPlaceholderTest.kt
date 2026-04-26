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

    @Test
    fun `end-to-end - selector skips Placeholder candidate and lands on real Redirected`() = runBlocking {
        val placeholderUrl = "https://comet.feels.legal/A/playback/p/0/0/n/n?torrent_name=t&name=ph"
        val realUrl = "https://comet.feels.legal/B/playback/r/0/0/n/n?torrent_name=t&name=real"
        val cdnUrl = "https://1-1.download.real-debrid.com/d/AAAA/movie.mp4"

        // Resolver classifies placeholderUrl as Placeholder, realUrl as Redirected.
        // Each terminal verdict is written to lastResolutionFor's short cache so
        // the predicate can read it without firing a second request.
        CometProxyUrlResolver.setTransportForTesting { url, _ ->
            when (url) {
                placeholderUrl -> ProxyResolution.Placeholder
                realUrl -> ProxyResolution.Redirected(cdnUrl)
                else -> error("unexpected url=$url")
            }
        }

        // Drive the prewarm pathway directly via resolve() so the verdict cache
        // is populated for both URLs.
        CometProxyUrlResolver.resolve(placeholderUrl, headers = emptyMap())
        CometProxyUrlResolver.resolve(realUrl, headers = emptyMap())

        val placeholder = stream(addonName = "AddonA", url = placeholderUrl)
        val real = stream(addonName = "AddonB", url = realUrl)

        // Predicate matches the production controller wiring: skipEnabled
        // gates lookup; lastResolutionFor surfaces the verdict.
        val skipEnabled = true
        val predicate: (Stream) -> Boolean = predicate@{ s ->
            val u = s.getStreamUrl() ?: return@predicate false
            skipEnabled &&
                CometProxyUrlResolver.lastResolutionFor(u) is ProxyResolution.Placeholder
        }

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(placeholder, real),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            placeholderPredicate = predicate
        )

        assertEquals(real, selected)
    }

    @Test
    fun `end-to-end - toggle off keeps Placeholder candidate and selector picks it`() = runBlocking {
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

        val skipEnabled = false
        val predicate: (Stream) -> Boolean = predicate@{ s ->
            val u = s.getStreamUrl() ?: return@predicate false
            skipEnabled &&
                CometProxyUrlResolver.lastResolutionFor(u) is ProxyResolution.Placeholder
        }

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(placeholder, real),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            placeholderPredicate = predicate
        )

        // Toggle disabled: the placeholder candidate is first in FIRST_STREAM
        // mode and is selected. Confirms the toggle is the only thing gating
        // the new behaviour.
        assertEquals(placeholder, selected)
    }

    @Test
    fun `end-to-end - all candidates Placeholder yields null selection`() = runBlocking {
        val urlA = "https://comet.feels.legal/A/playback/p/0/0/n/n?torrent_name=t&name=a"
        val urlB = "https://comet.feels.legal/B/playback/p/0/0/n/n?torrent_name=t&name=b"

        CometProxyUrlResolver.setTransportForTesting { _, _ -> ProxyResolution.Placeholder }
        CometProxyUrlResolver.resolve(urlA, headers = emptyMap())
        CometProxyUrlResolver.resolve(urlB, headers = emptyMap())

        val a = stream(addonName = "AddonA", url = urlA)
        val b = stream(addonName = "AddonB", url = urlB)

        val predicate: (Stream) -> Boolean = predicate@{ s ->
            val u = s.getStreamUrl() ?: return@predicate false
            CometProxyUrlResolver.lastResolutionFor(u) is ProxyResolution.Placeholder
        }

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(a, b),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            placeholderPredicate = predicate
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
