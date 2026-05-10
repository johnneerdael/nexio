package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * End-to-end regression for the 2026-05-10 dossier scenario:
 * Citadel S1, UI locale Dutch, audio preference Original. Tracks: [pl, en].
 * After Phase C, `originalLanguage` is "eng" — picker must select index 1
 * (English). Before the fix it was "nld" — picker selected index 0 (Polish).
 */
class PlayerStartupSelectionPolicyOriginalLanguageRegressionTest {
    @Test
    fun `Citadel-shape input picks English when originalLanguage is eng`() {
        val tracks = listOf(
            stubTrack(index = 0, language = "pl", name = "Polish (E-AC-3 5.1)"),
            stubTrack(index = 1, language = "en", name = "English (E-AC-3 5.1)")
        )

        val pickedIndex = findBestStartupAudioTrackIndex(
            audioTracks = tracks,
            targets = listOf("en"),     // resolved from originalLanguage="eng"
            originalLanguage = "eng"
        )

        assertEquals(1, pickedIndex)
    }

    @Test
    fun `Citadel-shape input does not pick Polish when targets are nl`() {
        // The pre-fix bug: originalLanguage leaked from UI locale, became "nld",
        // targets resolved to ["nl"], no match, default Polish track 0 won.
        // We assert the picker correctly returns -1 in that scenario, so the
        // fix at the upstream layer is what fixes the user-visible behavior.
        val tracks = listOf(
            stubTrack(index = 0, language = "pl", name = "Polish (E-AC-3 5.1)"),
            stubTrack(index = 1, language = "en", name = "English (E-AC-3 5.1)")
        )

        val pickedIndex = findBestStartupAudioTrackIndex(
            audioTracks = tracks,
            targets = listOf("nl"),
            originalLanguage = "nld"
        )

        assertEquals(
            "Picker returns -1 when no track matches; the audio-track regression " +
                "is fixed upstream by feeding correct originalLanguage, not by " +
                "weakening the picker.",
            -1,
            pickedIndex
        )
    }

    private fun stubTrack(index: Int, language: String, name: String): TrackInfo =
        TrackInfo(
            index = index,
            language = language,
            name = name,
            mimeType = "audio/eac3",
            channelCount = 6,
            codec = "eac3",
            isForced = false,
            trackId = "audio:$index"
        )
}
