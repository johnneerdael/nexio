package com.nexio.tv.ui.screens.detail

import com.nexio.tv.domain.model.ImdbSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeRatingsRefreshPolicyTest {

    @Test
    fun `inactive partial imdb config changes do not reload ratings`() {
        val previous = ImdbSettings(enabled = false, baseUrl = "", apiKey = "")
            .toEpisodeRatingsRefreshState()
        val current = ImdbSettings(enabled = true, baseUrl = "https://api.nexioapp.org", apiKey = "")
            .toEpisodeRatingsRefreshState()

        assertFalse(shouldReloadEpisodeRatingsForImdbSettingsChange(previous, current))
    }

    @Test
    fun `transition from inactive to active imdb config reloads ratings`() {
        val previous = ImdbSettings(enabled = true, baseUrl = "https://api.nexioapp.org", apiKey = "")
            .toEpisodeRatingsRefreshState()
        val current = ImdbSettings(
            enabled = true,
            baseUrl = "https://api.nexioapp.org",
            apiKey = "secret"
        ).toEpisodeRatingsRefreshState()

        assertTrue(shouldReloadEpisodeRatingsForImdbSettingsChange(previous, current))
    }

    @Test
    fun `active imdb config changes reload ratings`() {
        val previous = ImdbSettings(
            enabled = true,
            baseUrl = "https://api.nexioapp.org",
            apiKey = "secret-a"
        ).toEpisodeRatingsRefreshState()
        val current = ImdbSettings(
            enabled = true,
            baseUrl = "https://api.nexioapp.org/v1",
            apiKey = "secret-b"
        ).toEpisodeRatingsRefreshState()

        assertTrue(shouldReloadEpisodeRatingsForImdbSettingsChange(previous, current))
    }

    @Test
    fun `transition from active to inactive imdb config reloads fallback ratings`() {
        val previous = ImdbSettings(
            enabled = true,
            baseUrl = "https://api.nexioapp.org",
            apiKey = "secret"
        ).toEpisodeRatingsRefreshState()
        val current = ImdbSettings(enabled = false, baseUrl = "", apiKey = "")
            .toEpisodeRatingsRefreshState()

        assertTrue(shouldReloadEpisodeRatingsForImdbSettingsChange(previous, current))
    }
}
