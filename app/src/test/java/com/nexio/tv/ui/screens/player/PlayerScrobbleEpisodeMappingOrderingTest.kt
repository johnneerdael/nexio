package com.nexio.tv.ui.screens.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-inspection test (deferred B.6) that pins the ordering of the Trakt
 * episode-mapping warmup before [PlayerRuntimeController.refreshScrobbleItem]
 * at every co-located call site. If the warmup is moved after the refresh,
 * `/scrobble/{start,stop}` would be sent with absolute episode numbers for
 * anime-style shows before the season tree has been resolved.
 */
class PlayerScrobbleEpisodeMappingOrderingTest {

    @Test
    fun warm_precedes_refresh_in_init_block() {
        val src =
            File("app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt")
                .readText()
        val initStart = src.indexOf("init {")
        assertTrue("init block must exist", initStart >= 0)
        val initBody = src.substring(initStart).substringBefore("\n    }")
        val warmIdx = initBody.indexOf("warmTraktEpisodeMappingForCurrentPlayback")
        val refreshIdx = initBody.indexOf("refreshScrobbleItem")
        assertTrue("warmup must appear in init: $initBody", warmIdx >= 0)
        assertTrue("refreshScrobbleItem must appear in init: $initBody", refreshIdx >= 0)
        assertTrue(
            "warmup must precede refresh (warmIdx=$warmIdx refreshIdx=$refreshIdx)",
            warmIdx < refreshIdx
        )
    }

    @Test
    fun warm_precedes_refresh_at_streams_call_site() {
        val src =
            File("app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt")
                .readText()
        val refreshIdx = src.indexOf("refreshScrobbleItem()")
        assertTrue("refreshScrobbleItem() must appear in Streams file", refreshIdx >= 0)
        val window = src.substring(maxOf(0, refreshIdx - 500), refreshIdx)
        assertTrue(
            "warmup must appear within 500 chars before refreshScrobbleItem(): window=$window",
            window.contains("warmTraktEpisodeMappingForCurrentPlayback")
        )
    }
}
