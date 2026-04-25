package com.nexio.tv.ui.screens.stream

import com.nexio.tv.core.stream.ParsedStreamInfo
import com.nexio.tv.core.stream.StreamCardModel
import com.nexio.tv.core.stream.StreamTransportKind
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicAutoplayTitleGuardTest {

    @Test
    fun `cross-show contamination is rejected — actual logged repro`() {
        // Scenario from probe_173824.log: user requested One Piece S01E01;
        // Comet returned 6 candidates, 4 of them Dune Prophecy. Without this
        // guard the scorer picked the highest-bitrate Dune Prophecy stream.
        val candidates = listOf(
            streamCard("op-g66", parsedTitle = "One Piece"),
            streamCard("op-romance", parsedTitle = "One Piece"),
            streamCard("dune-aktep", parsedTitle = "Dune Prophecy"),
            streamCard("dune-rutracker", parsedTitle = "Dune Prophecy"),
            streamCard("dune-bentm-p5", parsedTitle = "Dune Prophecy"),
            streamCard("dune-bentm-hdr", parsedTitle = "Dune Prophecy")
        )

        val guarded = applyDeterministicTitleGuard(
            contentName = "One Piece",
            streams = candidates
        )

        assertEquals(
            listOf("op-g66", "op-romance"),
            guarded.map { it.stream.wrappedOriginalStreamKey }
        )
    }

    @Test
    fun `exact normalized title matches`() {
        assertFalse(
            shouldRejectDeterministicAutoplayForTitle(
                contentName = "One Piece",
                parsedTitle = "One Piece"
            )
        )
    }

    @Test
    fun `case and punctuation differences are ignored`() {
        assertFalse(
            shouldRejectDeterministicAutoplayForTitle(
                contentName = "One Piece",
                parsedTitle = "ONE.PIECE"
            )
        )
        assertFalse(
            shouldRejectDeterministicAutoplayForTitle(
                contentName = "Marvel's Daredevil",
                parsedTitle = "Marvels Daredevil"
            )
        )
    }

    @Test
    fun `parsed title with trailing year is accepted (accepted ⊆ parsed)`() {
        assertFalse(
            shouldRejectDeterministicAutoplayForTitle(
                contentName = "One Piece",
                parsedTitle = "One Piece 2023"
            )
        )
    }

    @Test
    fun `accepted title with trailing year is accepted (parsed ⊆ accepted)`() {
        assertFalse(
            shouldRejectDeterministicAutoplayForTitle(
                contentName = "Dune Prophecy 2024",
                parsedTitle = "Dune Prophecy"
            )
        )
    }

    @Test
    fun `unrelated title is rejected`() {
        assertTrue(
            shouldRejectDeterministicAutoplayForTitle(
                contentName = "One Piece",
                parsedTitle = "Dune Prophecy"
            )
        )
    }

    @Test
    fun `partial single-token overlap on a multi-token title is rejected`() {
        // "Doctor" is in both "Doctor Who" and "Doctor Strange" — must reject.
        assertTrue(
            shouldRejectDeterministicAutoplayForTitle(
                contentName = "Doctor Strange",
                parsedTitle = "Doctor Who"
            )
        )
    }

    @Test
    fun `null or blank contentName is a no-op`() {
        assertFalse(
            shouldRejectDeterministicAutoplayForTitle(
                contentName = null,
                parsedTitle = "Anything"
            )
        )
        assertFalse(
            shouldRejectDeterministicAutoplayForTitle(
                contentName = "   ",
                parsedTitle = "Anything"
            )
        )
    }

    @Test
    fun `blank parsed title is a no-op (no signal to filter on)`() {
        // If the parser couldn't extract a title we can't make a decision —
        // stay out of the way and let the regular scoring/decision proceed.
        assertFalse(
            shouldRejectDeterministicAutoplayForTitle(
                contentName = "One Piece",
                parsedTitle = null
            )
        )
        assertFalse(
            shouldRejectDeterministicAutoplayForTitle(
                contentName = "One Piece",
                parsedTitle = ""
            )
        )
    }

    @Test
    fun `single-letter parsed tokens do not falsely match`() {
        assertTrue(
            shouldRejectDeterministicAutoplayForTitle(
                contentName = "It",
                parsedTitle = "M"
            )
        )
    }

    @Test
    fun `guard is a no-op when contentName is null`() {
        val candidates = listOf(
            streamCard("a", parsedTitle = "Foo"),
            streamCard("b", parsedTitle = "Bar")
        )
        val guarded = applyDeterministicTitleGuard(contentName = null, streams = candidates)
        assertEquals(candidates, guarded)
    }

    private fun streamCard(
        key: String,
        parsedTitle: String?
    ): StreamCardModel {
        val stream = Stream(
            name = key,
            title = key,
            description = null,
            url = "https://example.com/$key.mkv",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = false,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                filename = "$key.mkv"
            ),
            addonName = "Example Addon",
            addonLogo = null,
            wrappedProviderId = "RD",
            wrappedOriginalStreamKey = key
        )
        val parsed = ParsedStreamInfo(
            stream = stream,
            title = parsedTitle,
            filename = "$key.mkv",
            sizeBytes = 1_000_000_000L,
            resolution = "2160p",
            quality = "WEB-DL",
            encode = "HEVC",
            visualTags = emptyList(),
            audioTags = emptyList(),
            audioChannels = emptyList(),
            languages = emptyList(),
            year = "2024",
            seasons = emptyList(),
            episodes = emptyList(),
            releaseGroup = "GROUP",
            serviceId = "RD",
            isCached = true,
            durationMs = 3_600_000L,
            transportKind = StreamTransportKind.CACHED
        )
        return StreamCardModel(
            stream = stream,
            parsed = parsed,
            title = key,
            subtitle = null,
            detailLines = emptyList()
        )
    }
}
