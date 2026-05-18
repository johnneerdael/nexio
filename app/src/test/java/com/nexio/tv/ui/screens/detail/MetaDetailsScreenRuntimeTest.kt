package com.nexio.tv.ui.screens.detail

import com.nexio.tv.R
import com.nexio.tv.data.repository.TvEpisodeOrderProvider
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaDetailsScreenRuntimeTest {

    @Test
    fun `episode playback runtime uses episode runtime when present`() {
        assertEquals(
            64,
            resolveEpisodePlaybackRuntimeMinutes(
                episodeRuntimeMinutes = 64,
                seriesRuntime = "49"
            )
        )
    }

    @Test
    fun `episode playback runtime falls back to series average runtime`() {
        assertEquals(
            49,
            resolveEpisodePlaybackRuntimeMinutes(
                episodeRuntimeMinutes = null,
                seriesRuntime = "49"
            )
        )
    }

    @Test
    fun `episode playback runtime stays null when no runtime source exists`() {
        assertNull(
            resolveEpisodePlaybackRuntimeMinutes(
                episodeRuntimeMinutes = null,
                seriesRuntime = null
            )
        )
    }

    @Test
    fun `tv episode order action uses target provider label and pending enabled state`() {
        val tmdbAction = resolveTvEpisodeOrderToggleAction(
            toggleAvailable = true,
            provider = TvEpisodeOrderProvider.TMDB_DEFAULT,
            togglePending = false
        )
        val tvdbAction = resolveTvEpisodeOrderToggleAction(
            toggleAvailable = true,
            provider = TvEpisodeOrderProvider.TVDB_DEFAULT,
            togglePending = true
        )

        assertNotNull(tmdbAction)
        assertEquals(R.string.detail_use_tvdb_season_numbering, tmdbAction?.labelRes)
        assertTrue(tmdbAction?.enabled == true)
        assertNotNull(tvdbAction)
        assertEquals(R.string.detail_use_tmdb_season_numbering, tvdbAction?.labelRes)
        assertFalse(tvdbAction?.enabled == true)
    }

    @Test
    fun `tv episode order action is hidden when unavailable`() {
        assertNull(
            resolveTvEpisodeOrderToggleAction(
                toggleAvailable = false,
                provider = TvEpisodeOrderProvider.TMDB_DEFAULT,
                togglePending = false
            )
        )
    }

    @Test
    fun `tv episode order strings exist and old metadata provider copy is absent`() {
        val stringsXml = Paths.get("app/src/main/res/values/strings.xml").toFile().readText()

        assertTrue(stringsXml.contains("name=\"detail_use_tvdb_season_numbering\""))
        assertTrue(stringsXml.contains(">Use TheTVDB season numbering<"))
        assertTrue(stringsXml.contains("name=\"detail_use_tmdb_season_numbering\""))
        assertTrue(stringsXml.contains(">Use TMDB season numbering<"))
        assertTrue(stringsXml.contains("name=\"detail_tvdb_numbering_enabled\""))
        assertTrue(stringsXml.contains(">TheTVDB season numbering enabled<"))
        assertTrue(stringsXml.contains("name=\"detail_tmdb_numbering_enabled\""))
        assertTrue(stringsXml.contains(">TMDB season numbering enabled<"))
        assertFalse(stringsXml.contains("Use TVDB metadata provider"))
    }
}
