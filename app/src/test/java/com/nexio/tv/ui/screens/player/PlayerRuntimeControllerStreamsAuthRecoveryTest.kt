package com.nexio.tv.ui.screens.player

import android.util.Log
import com.nexio.tv.core.player.CometProxyUrlResolver
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PlayerRuntimeControllerStreamsAuthRecoveryTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        CometProxyUrlResolver.resetForTesting()
    }

    @After
    fun tearDown() {
        CometProxyUrlResolver.resetForTesting()
        unmockkStatic(Log::class)
    }

    @Test
    fun `prepareMediaSourceUrl resolves comet proxy and returns resolved url`() {
        val proxy = "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n"
        val resolved = "https://1-1.download.real-debrid.com/d/AAAA/movie.mp4"
        CometProxyUrlResolver.setTransportForTesting { _, _ -> resolved }

        val out = prepareMediaSourceUrlForTesting(
            url = proxy,
            headers = emptyMap(),
            addonHost = "comet.feels.legal"
        )

        assertEquals(resolved, out)
        assertEquals(proxy, CometProxyUrlResolver.proxyUrlFor(resolved))
    }

    @Test
    fun `prepareMediaSourceUrl returns input unchanged when not a proxy`() {
        val direct = "https://1-1.download.real-debrid.com/d/AAAA/movie.mp4"
        val out = prepareMediaSourceUrlForTesting(
            url = direct,
            headers = emptyMap(),
            addonHost = null
        )
        assertEquals(direct, out)
    }
}
