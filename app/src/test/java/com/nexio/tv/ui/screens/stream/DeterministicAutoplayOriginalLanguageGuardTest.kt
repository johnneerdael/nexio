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

class DeterministicAutoplayOriginalLanguageGuardTest {

    @Test
    fun `ru only stream is rejected for english original language`() {
        assertTrue(
            shouldRejectDeterministicAutoplayForOriginalLanguage(
                originalLanguage = "en",
                parsedLanguages = listOf("Russian")
            )
        )
    }

    @Test
    fun `matching spoken language is allowed`() {
        assertFalse(
            shouldRejectDeterministicAutoplayForOriginalLanguage(
                originalLanguage = "en",
                parsedLanguages = listOf("English")
            )
        )
    }

    @Test
    fun `multi language stream is allowed`() {
        assertFalse(
            shouldRejectDeterministicAutoplayForOriginalLanguage(
                originalLanguage = "en",
                parsedLanguages = listOf("Russian", "Multi")
            )
        )
    }

    @Test
    fun `missing metadata or parsed languages does not filter`() {
        assertFalse(
            shouldRejectDeterministicAutoplayForOriginalLanguage(
                originalLanguage = null,
                parsedLanguages = listOf("Russian")
            )
        )
        assertFalse(
            shouldRejectDeterministicAutoplayForOriginalLanguage(
                originalLanguage = "en",
                parsedLanguages = emptyList()
            )
        )
    }

    @Test
    fun `guard filters only mismatched deterministic candidates`() {
        val guarded = applyDeterministicOriginalLanguageGuard(
            originalLanguage = "en",
            streams = listOf(
                streamCard("ru-only", listOf("Russian")),
                streamCard("multi", listOf("Multi", "Russian")),
                streamCard("english", listOf("English"))
            )
        )

        assertEquals(listOf("multi", "english"), guarded.map { it.stream.wrappedOriginalStreamKey })
    }

    private fun streamCard(
        key: String,
        languages: List<String>
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
            title = key,
            filename = "$key.mkv",
            sizeBytes = 1_000_000_000L,
            resolution = "2160p",
            quality = "WEB-DL",
            encode = "HEVC",
            visualTags = emptyList(),
            audioTags = emptyList(),
            audioChannels = emptyList(),
            languages = languages,
            year = "2026",
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
