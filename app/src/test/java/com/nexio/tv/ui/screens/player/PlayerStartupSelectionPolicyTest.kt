package com.nexio.tv.ui.screens.player

import com.nexio.tv.core.stream.AioStrictFileParser
import com.nexio.tv.data.local.AudioLanguageOption
import com.nexio.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `startup ai fallback remains allowed until auto selection has committed`() {
        assertTrue(shouldAllowStartupSubtitleAiFallback(autoSubtitleSelected = false))
    }

    @Test
    fun `startup ai fallback stops after auto selection has committed`() {
        assertEquals(false, shouldAllowStartupSubtitleAiFallback(autoSubtitleSelected = true))
    }

    @Test
    fun `startup ai fallback survives first frame render mid-discovery with english-only internal`() {
        // Regression: first frame rendered before addon discovery + text-track
        // scan converged, Media3 had already latched the english internal as
        // default (selectedSubtitleTrackIndex=0), dutch preferred, english
        // secondary. Previously startupPhase flipped to false here and the AI
        // tier was skipped, leaving untranslated english. The gate now only
        // depends on autoSubtitleSelected, so the AI tier still fires.
        val internalTracks = listOf(
            TrackInfo(index = 0, name = "English", language = "en")
        )

        val startupPhase = shouldAllowStartupSubtitleAiFallback(autoSubtitleSelected = false)
        assertTrue(startupPhase)

        val decision = decideStartupSubtitleAutoSelection(
            subtitleTracks = internalTracks,
            addonSubtitles = emptyList(),
            preferredLanguage = "nl",
            secondaryLanguage = "en",
            hasScannedTextTracksOnce = true,
            playerReady = true,
            addonSubtitleDiscoveryPending = false,
            aiTranslationConfigured = true,
            startupPhase = startupPhase
        )

        assertEquals(
            StartupSubtitleAutoSelectionDecision.Internal(index = 0, enableAiTranslation = true),
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

    @Test
    fun `tv episode addon auto pick falls through to ai translation when preferred addon group mismatches`() {
        val internalTracks = listOf(
            TrackInfo(index = 0, name = "English", language = "en", mimeType = "text/vtt")
        )
        val addonSubs = listOf(
            Subtitle(
                id = "v3+|13593282|Paradise.2025.S02E03.1080p.HEVC.x265-MeGusta",
                url = "https://opensubtitles.example/subtitle/Paradise.2025.S02E03.1080p.HEVC.x265-MeGusta.vtt",
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
            startupPhase = true,
            videoRelease = AioStrictFileParser.parse(
                "Paradise.2025.S02E03.1080p.WEB-DL.DDP5.1.H.264-RAWR.mkv"
            )
        )

        assertEquals(
            StartupSubtitleAutoSelectionDecision.Internal(index = 0, enableAiTranslation = true),
            decision
        )
    }

    @Test
    fun `tv episode addon auto pick prefers exact release group match`() {
        val addonSubs = listOf(
            Subtitle(
                id = "v3+|13593282|Paradise.2025.S02E03.1080p.HEVC.x265-MeGusta",
                url = "https://opensubtitles.example/subtitle/Paradise.2025.S02E03.1080p.HEVC.x265-MeGusta.vtt",
                lang = "nl",
                addonName = "OpenSubtitles",
                addonLogo = null
            ),
            Subtitle(
                id = "v3+|13593198|Paradise.2025.S02E03.1080p.WEB-DL.DDP5.1.H.264-RAWR",
                url = "https://opensubtitles.example/subtitle/Paradise.2025.S02E03.1080p.WEB-DL.DDP5.1.H.264-RAWR.vtt",
                lang = "nl",
                addonName = "OpenSubtitles",
                addonLogo = null
            )
        )

        val decision = decideStartupSubtitleAutoSelection(
            subtitleTracks = emptyList(),
            addonSubtitles = addonSubs,
            preferredLanguage = "nl",
            secondaryLanguage = "en",
            hasScannedTextTracksOnce = true,
            playerReady = true,
            addonSubtitleDiscoveryPending = false,
            aiTranslationConfigured = true,
            startupPhase = true,
            videoRelease = AioStrictFileParser.parse(
                "Paradise.2025.S02E03.1080p.WEB-DL.DDP5.1.H.264-RAWR.mkv"
            )
        )

        assertTrue(decision is StartupSubtitleAutoSelectionDecision.Addon)
        assertEquals(
            "v3+|13593198|Paradise.2025.S02E03.1080p.WEB-DL.DDP5.1.H.264-RAWR",
            (decision as StartupSubtitleAutoSelectionDecision.Addon).subtitle.id
        )
    }

    @Test
    fun `addon subtitle release scoring handles v3 identifiers by parsing release suffix`() {
        val best = pickBestAddonSubtitleByRelease(
            candidates = listOf(
                Subtitle(
                    id = "v3+|13593282|Paradise.2025.S02E03.1080p.HEVC.x265-MeGusta",
                    url = "https://opensubtitles.example/subtitle/Paradise.2025.S02E03.1080p.HEVC.x265-MeGusta.vtt",
                    lang = "nl",
                    addonName = "OpenSubtitles",
                    addonLogo = null
                ),
                Subtitle(
                    id = "v3+|13593198|Paradise.2025.S02E03.1080p.WEB-DL.DDP5.1.H.264-RAWR",
                    url = "https://opensubtitles.example/subtitle/Paradise.2025.S02E03.1080p.WEB-DL.DDP5.1.H.264-RAWR.vtt",
                    lang = "nl",
                    addonName = "OpenSubtitles",
                    addonLogo = null
                )
            ),
            videoRelease = AioStrictFileParser.parse(
                "Paradise.2025.S02E03.1080p.WEB-DL.DDP5.1.H.264-RAWR.mkv"
            )
        )

        assertEquals(
            "v3+|13593198|Paradise.2025.S02E03.1080p.WEB-DL.DDP5.1.H.264-RAWR",
            best?.id
        )
    }

    @Test
    fun `french VO track is classified as original not voiceover and selected as english original`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "VFF : French (E-AC-3 7.1)", language = "fr", codec = "E-AC-3", channelCount = 8),
            TrackInfo(index = 1, name = "VFF : French Audio Description (AAC Stereo)", language = "fr", codec = "AAC", channelCount = 2),
            TrackInfo(index = 2, name = "VFQ : French (AC-3 5.1)", language = "fr", codec = "AC-3", channelCount = 6),
            TrackInfo(index = 3, name = "VO : English (E-AC-3 5.1)", language = "en", codec = "E-AC-3", channelCount = 6)
        )

        val index = findBestStartupAudioTrackIndex(
            audioTracks = tracks,
            targets = listOf("en"),
            originalLanguage = "en"
        )

        assertEquals(3, index)
    }

    @Test
    fun `french VO track selected by original language fallback when metadata is missing`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "VFF : French (E-AC-3 7.1)", language = "fr", codec = "E-AC-3", channelCount = 8),
            TrackInfo(index = 1, name = "VFF : French Audio Description (AAC Stereo)", language = "fr", codec = "AAC", channelCount = 2),
            TrackInfo(index = 2, name = "VFQ : French (AC-3 5.1)", language = "fr", codec = "AC-3", channelCount = 6),
            TrackInfo(index = 3, name = "VO : English (E-AC-3 5.1)", language = "en", codec = "E-AC-3", channelCount = 6)
        )

        // When originalLanguage is null, the VO track naming convention should
        // be recognized as ORIGINAL (not VOICEOVER) so the fallback picker
        // can identify it as the original language track.
        val trackTypes = PlayerAudioTrackNamingConventions.trackTypes(tracks[3])
        assertTrue(
            "VO track should be classified as ORIGINAL, got: $trackTypes",
            AudioTrackType.ORIGINAL in trackTypes
        )
        assertTrue(
            "VO track should NOT be classified as VOICEOVER, got: $trackTypes",
            AudioTrackType.VOICEOVER !in trackTypes
        )
    }

    @Test
    fun `french VFF track is inferred as french language`() {
        val track = TrackInfo(index = 0, name = "VFF : French (E-AC-3 7.1)", language = null)
        val inferred = PlayerAudioTrackNamingConventions.inferredLanguageCodes(track)
        assertTrue(
            "VFF track should infer french language, got: $inferred",
            inferred.any { it == "fr" }
        )
    }

    @Test
    fun `addon subtitle release scoring returns null when every candidate is ruled out`() {
        val best = pickBestAddonSubtitleByRelease(
            candidates = listOf(
                Subtitle(
                    id = "Different.Movie.2024.1080p.WEB-DL.H.264-RAWR",
                    url = "https://opensubtitles.example/subtitle/Different.Movie.2024.1080p.WEB-DL.H.264-RAWR.srt",
                    lang = "nl",
                    addonName = "OpenSubtitles",
                    addonLogo = null
                )
            ),
            videoRelease = AioStrictFileParser.parse(
                "Paradise.2025.1080p.WEB-DL.H.264-RAWR.mkv"
            )
        )

        assertNull(best)
    }

    @Test
    fun `findBestInternal picks normal English over forced English`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "English (Forced)", language = "en", isForced = true),
            TrackInfo(index = 1, name = "English", language = "en", isForced = false)
        )

        val index = findBestInternalSubtitleTrackIndexForStartup(
            subtitleTracks = tracks,
            targets = listOf("en")
        )

        assertEquals(1, index)
    }

    @Test
    fun `findBestInternal picks normal English over SDH and forced`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "English (Forced)", language = "en", isForced = true),
            TrackInfo(index = 1, name = "English SDH", language = "en", isForced = false),
            TrackInfo(index = 2, name = "English", language = "en", isForced = false)
        )

        val index = findBestInternalSubtitleTrackIndexForStartup(
            subtitleTracks = tracks,
            targets = listOf("en")
        )

        assertEquals(2, index)
    }

    @Test
    fun `findBestInternal picks SDH over forced when no normal track exists`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "English (Forced)", language = "en", isForced = true),
            TrackInfo(index = 1, name = "English SDH", language = "en", isForced = false)
        )

        val index = findBestInternalSubtitleTrackIndexForStartup(
            subtitleTracks = tracks,
            targets = listOf("en")
        )

        assertEquals(1, index)
    }

    @Test
    fun `findBestInternal falls back to forced as last resort`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "English (Forced)", language = "en", isForced = true)
        )

        val index = findBestInternalSubtitleTrackIndexForStartup(
            subtitleTracks = tracks,
            targets = listOf("en")
        )

        assertEquals(0, index)
    }

    @Test
    fun `findBestInternal pt-br tag preference outranks accessibility`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "Portugues (PT)", language = "pt", isForced = false),
            TrackInfo(index = 1, name = "Portugues (BR) Forced", language = "pt", isForced = true)
        )

        val index = findBestInternalSubtitleTrackIndexForStartup(
            subtitleTracks = tracks,
            targets = listOf("pt-br")
        )

        assertEquals(1, index)
    }
}
