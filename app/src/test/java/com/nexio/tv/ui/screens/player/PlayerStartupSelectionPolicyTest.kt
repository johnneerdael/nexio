package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.local.AudioLanguageOption
import com.nexio.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStartupSelectionPolicyTest {

    @Test
    fun `original audio preference resolves to original language first`() {
        val resolved = resolvePreferredAudioLanguages(
            preferredAudioLanguage = AudioLanguageOption.ORIGINAL,
            secondaryPreferredAudioLanguage = "en",
            deviceLanguages = listOf("nld"),
            originalLanguage = "ru"
        )

        assertEquals(listOf("ru", "en"), resolved)
    }

    @Test
    fun `startup audio selection picks english track over default non-english track`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "Japanese (AAC)", language = "ja", isSelected = true),
            TrackInfo(index = 1, name = "English (AC3)", language = "en", isSelected = false)
        )

        val index = findBestStartupAudioTrackIndex(tracks, listOf("en"))

        assertEquals(1, index)
    }

    @Test
    fun `startup subtitle selection prefers downloaded preferred language over internal secondary`() {
        val internalTracks = listOf(
            TrackInfo(index = 0, name = "English", language = "en")
        )
        val addonSubs = listOf(
            Subtitle(
                id = "nl-addon",
                url = "file:///tmp/nl.srt",
                lang = "nl",
                addonName = "OpenSubtitles",
                addonLogo = null
            )
        )

        val decision = decideStartupSubtitleAutoSelection(
            subtitleTracks = internalTracks,
            addonSubtitles = addonSubs,
            preferredLanguage = "nl",
            secondaryLanguage = "en",
            hasScannedTextTracksOnce = true,
            playerReady = true,
            aiTranslationConfigured = true,
            startupPhase = true
        )

        assertTrue(decision is StartupSubtitleAutoSelectionDecision.Addon)
        assertEquals("nl-addon", (decision as StartupSubtitleAutoSelectionDecision.Addon).subtitle.id)
        assertEquals(false, decision.enableAiTranslation)
    }

    @Test
    fun `startup subtitle selection falls back to internal secondary with ai when preferred missing everywhere`() {
        val internalTracks = listOf(
            TrackInfo(index = 0, name = "English", language = "en")
        )

        val decision = decideStartupSubtitleAutoSelection(
            subtitleTracks = internalTracks,
            addonSubtitles = emptyList(),
            preferredLanguage = "nl",
            secondaryLanguage = "en",
            hasScannedTextTracksOnce = true,
            playerReady = true,
            aiTranslationConfigured = true,
            startupPhase = true
        )

        assertEquals(
            StartupSubtitleAutoSelectionDecision.Internal(index = 0, enableAiTranslation = true),
            decision
        )
    }

    @Test
    fun `non-startup subtitle fallback does not auto-enable ai`() {
        val internalTracks = listOf(
            TrackInfo(index = 0, name = "English", language = "en")
        )

        val decision = decideStartupSubtitleAutoSelection(
            subtitleTracks = internalTracks,
            addonSubtitles = emptyList(),
            preferredLanguage = "nl",
            secondaryLanguage = "en",
            hasScannedTextTracksOnce = true,
            playerReady = true,
            aiTranslationConfigured = true,
            startupPhase = false
        )

        assertEquals(
            StartupSubtitleAutoSelectionDecision.Internal(index = 0, enableAiTranslation = false),
            decision
        )
    }
}
