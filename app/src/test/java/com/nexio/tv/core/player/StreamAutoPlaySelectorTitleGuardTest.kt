package com.nexio.tv.core.player

import com.nexio.tv.core.stream.ParsedStreamInfo
import com.nexio.tv.core.stream.StreamCardModel
import com.nexio.tv.core.stream.StreamTransportKind
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAutoPlaySelectorTitleGuardTest {

    @Test
    fun `title guard rejects One Piece request when candidate is Dune Prophecy`() {
        assertTrue(
            StreamAutoPlaySelector.shouldRejectForContentTitle(
                contentName = "One Piece",
                parsedTitle = "Dune Prophecy"
            )
        )
    }

    @Test
    fun `title guard accepts Le Samourai without diacritic for Le Samourai with diacritic`() {
        assertFalse(
            StreamAutoPlaySelector.shouldRejectForContentTitle(
                contentName = "Le Samouraï",
                parsedTitle = "Le Samourai"
            )
        )
    }

    @Test
    fun `title guard accepts identical Survivor title`() {
        assertFalse(
            StreamAutoPlaySelector.shouldRejectForContentTitle(
                contentName = "Survivor",
                parsedTitle = "Survivor"
            )
        )
    }

    @Test
    fun `title guard filters only mismatched deterministic candidates`() {
        val guarded = StreamAutoPlaySelector.filterCandidatesByContentTitle(
            contentName = "One Piece",
            streams = listOf(
                streamCard("one-piece", parsedTitle = "One Piece"),
                streamCard("dune-prophecy", parsedTitle = "Dune Prophecy")
            )
        )

        assertEquals(listOf("one-piece"), guarded.map { it.stream.wrappedOriginalStreamKey })
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
