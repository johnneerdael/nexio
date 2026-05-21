package com.nexio.tv.ui.screens.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTvdbOverrideScrobbleProjectionContractTest {

    @Test
    fun tvdb_scrobble_projection_runs_for_tmdb_content_when_tvdb_order_override_is_enabled() {
        val src = File(
            "app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerScrobble.kt"
        ).readText()
        val fnStart = src.indexOf(
            "internal suspend fun PlayerRuntimeController.warmTvdbScrobbleCoordinateForCurrentPlayback"
        )
        assertTrue("warmTvdbScrobbleCoordinateForCurrentPlayback must exist", fnStart >= 0)
        val fnBody = src.substring(fnStart)
            .substringBefore("\nprivate fun PlayerRuntimeController.")

        assertFalse(
            "TVDB scrobble projection must not return early just because playback contentId is TMDB",
            fnBody.contains("if (!rawContentId.startsWith(\"tvdb:\"")
        )
        assertTrue(
            "TMDB playback must resolve the per-show TV episode order policy",
            fnBody.contains("tvEpisodeOrderResolver.resolve")
        )
        assertTrue(
            "TMDB playback must only reverse-project scrobble coordinates for TVDB order overrides",
            fnBody.contains("TvEpisodeOrderProvider.TVDB_DEFAULT")
        )
        assertTrue(
            "TMDB playback must fetch the TVDB episode map through the TVDB sidecar, not change canonical playback identity",
            fnBody.contains("\"tvdb:\$tvdbId\"")
        )
    }
}
