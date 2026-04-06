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
    fun `startup audio selection keeps original language ahead of higher quality foreign track`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "eng (AAC Stereo)", language = "eng", codec = "AAC", channelCount = 2),
            TrackInfo(index = 1, name = "ita (AC-3 5.1)", language = "ita", codec = "AC-3", channelCount = 6)
        )

        val index = findBestStartupAudioTrackIndex(
            audioTracks = tracks,
            targets = listOf("en"),
            originalLanguage = "en"
        )

        assertEquals(0, index)
    }

    @Test
    fun `startup audio selection infers original language track from original alias when metadata is und`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "Original Audio (AC-3 5.1)", language = "und", codec = "AC-3", channelCount = 6),
            TrackInfo(index = 1, name = "English Dub (TrueHD 7.1)", language = "en", codec = "TrueHD", channelCount = 8)
        )

        val index = findBestStartupAudioTrackIndex(
            audioTracks = tracks,
            targets = listOf("ja"),
            originalLanguage = "ja",
            capabilitySupport = StartupAudioCapabilitySupport(
                ac3Supported = true,
                truehdSupported = true
            )
        )

        assertEquals(0, index)
    }

    @Test
    fun `startup audio selection infers language from csv aliases when tags are missing`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "Italiano (AC-3 5.1)", language = null, codec = "AC-3", channelCount = 6),
            TrackInfo(index = 1, name = "English (AAC Stereo)", language = null, codec = "AAC", channelCount = 2)
        )

        val index = findBestStartupAudioTrackIndex(
            audioTracks = tracks,
            targets = listOf("en"),
            originalLanguage = "en"
        )

        assertEquals(1, index)
    }

    @Test
    fun `startup audio selection prefers higher quality original track among same language matches`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "Dubbed (DTS-HD 7ch)", language = "ru"),
            TrackInfo(index = 1, name = "Original (AC-3 5.1)", language = "en", codec = "AC-3", channelCount = 6),
            TrackInfo(index = 2, name = "Commentary with Director & Writers (AC-3 Stereo)", language = "en", codec = "AC-3", channelCount = 2),
            TrackInfo(index = 3, name = "Commentary with Design Team (AC-3 Stereo)", language = "en", codec = "AC-3", channelCount = 2),
            TrackInfo(index = 4, name = "Original (TrueHD 7.1)", language = "en", codec = "TrueHD", channelCount = 8)
        )

        val index = findBestStartupAudioTrackIndex(
            tracks,
            listOf("en"),
            originalLanguage = "en",
            capabilitySupport = StartupAudioCapabilitySupport(
                ac3Supported = true,
                truehdSupported = true
            )
        )

        assertEquals(4, index)
    }

    @Test
    fun `startup audio selection prefers original over commentary within same language`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "Commentary with Director (TrueHD 7.1)", language = "en", codec = "TrueHD", channelCount = 8),
            TrackInfo(index = 1, name = "Original (AC-3 5.1)", language = "en", codec = "AC-3", channelCount = 6)
        )

        val index = findBestStartupAudioTrackIndex(tracks, listOf("en"), originalLanguage = "en")

        assertEquals(1, index)
    }

    @Test
    fun `startup audio selection upgrades current english track when better english track exists`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "Dubbed (DTS-HD 7ch)", language = "ru"),
            TrackInfo(index = 1, name = "Original (AC-3 5.1)", language = "en", codec = "AC-3", channelCount = 6, isSelected = true),
            TrackInfo(index = 2, name = "Original (TrueHD 7.1)", language = "en", codec = "TrueHD", channelCount = 8)
        )

        val index = resolveStartupAudioSelectionIndex(
            audioTracks = tracks,
            targets = listOf("en"),
            currentSelectedIndex = 1,
            originalLanguage = "en",
            capabilitySupport = StartupAudioCapabilitySupport(
                ac3Supported = true,
                truehdSupported = true
            )
        )

        assertEquals(2, index)
    }

    @Test
    fun `startup audio selection keeps current track when it is already the best match`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "Dubbed (AC-3 5.1)", language = "ru"),
            TrackInfo(index = 1, name = "Original (TrueHD 7.1)", language = "en", codec = "TrueHD", channelCount = 8, isSelected = true),
            TrackInfo(index = 2, name = "Original (AC-3 5.1)", language = "en", codec = "AC-3", channelCount = 6)
        )

        val index = resolveStartupAudioSelectionIndex(
            audioTracks = tracks,
            targets = listOf("en"),
            currentSelectedIndex = 1,
            originalLanguage = "en",
            capabilitySupport = StartupAudioCapabilitySupport(
                ac3Supported = true,
                truehdSupported = true
            )
        )

        assertEquals(1, index)
    }

    @Test
    fun `startup audio selection prefers supported ac3 over unsupported truehd`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "Original (AC-3 5.1)", language = "en", codec = "AC-3", channelCount = 6),
            TrackInfo(index = 1, name = "Original (TrueHD 7.1)", language = "en", codec = "TrueHD", channelCount = 8)
        )

        val index = findBestStartupAudioTrackIndex(
            audioTracks = tracks,
            targets = listOf("en"),
            originalLanguage = "en",
            capabilitySupport = StartupAudioCapabilitySupport(
                ac3Supported = true,
                truehdSupported = false
            )
        )

        assertEquals(0, index)
    }

    @Test
    fun `startup audio selection does not upgrade current ac3 to unsupported truehd`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "Original (AC-3 5.1)", language = "en", codec = "AC-3", channelCount = 6, isSelected = true),
            TrackInfo(index = 1, name = "Original (TrueHD 7.1)", language = "en", codec = "TrueHD", channelCount = 8)
        )

        val index = resolveStartupAudioSelectionIndex(
            audioTracks = tracks,
            targets = listOf("en"),
            currentSelectedIndex = 0,
            originalLanguage = "en",
            capabilitySupport = StartupAudioCapabilitySupport(
                ac3Supported = true,
                truehdSupported = false
            )
        )

        assertEquals(0, index)
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
            addonSubtitleDiscoveryPending = false,
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
            addonSubtitleDiscoveryPending = false,
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
            addonSubtitleDiscoveryPending = false,
            aiTranslationConfigured = true,
            startupPhase = false
        )

        assertEquals(
            StartupSubtitleAutoSelectionDecision.Internal(index = 0, enableAiTranslation = false),
            decision
        )
    }

    @Test
    fun `startup subtitle selection defers secondary internal fallback while addon discovery is pending`() {
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
            addonSubtitleDiscoveryPending = true,
            aiTranslationConfigured = true,
            startupPhase = true
        )

        assertEquals(StartupSubtitleAutoSelectionDecision.DeferAddonFallback, decision)
    }
}
