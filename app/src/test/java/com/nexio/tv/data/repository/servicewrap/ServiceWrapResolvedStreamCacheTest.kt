package com.nexio.tv.data.repository.servicewrap

import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceWrapResolvedStreamCacheTest {
    @Test
    fun `cache keys include episode context`() {
        val cache = ServiceWrapResolvedStreamCache()
        val candidate = candidate("ABCDEF0123456789ABCDEF0123456789ABCDEF01")
        val streams = listOf(resolvedStream("ABCDEF0123456789ABCDEF0123456789ABCDEF01"))

        cache.put(
            provider = ServiceWrapProvider.REAL_DEBRID,
            candidate = candidate,
            requestContext = ServiceWrapRequestContext(contentType = "series", season = 1, episode = 1),
            streams = streams
        )

        assertEquals(
            streams,
            cache.get(
                provider = ServiceWrapProvider.REAL_DEBRID,
                candidate = candidate,
                requestContext = ServiceWrapRequestContext(contentType = "series", season = 1, episode = 1)
            )
        )
        assertNull(
            cache.get(
                provider = ServiceWrapProvider.REAL_DEBRID,
                candidate = candidate,
                requestContext = ServiceWrapRequestContext(contentType = "series", season = 1, episode = 2)
            )
        )
    }

    private fun candidate(hash: String): WrapCandidate {
        val stream = Stream(
            name = "Show.S01E01.1080p",
            title = null,
            description = "Show.S01E01.1080p",
            url = null,
            ytId = null,
            infoHash = hash,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Addon A",
            addonLogo = null,
            addonParserPreset = AddonParserPreset.GENERIC
        )
        return WrapCandidate(
            normalizedInfoHash = hash,
            magnetUri = "magnet:?xt=urn:btih:$hash",
            sourceStream = stream,
            sourceAddonName = "Addon A",
            sourceAddonLogo = null,
            sourceStreamKey = stableWrapStreamKey(stream),
            sourceParsed = parseSourceStream(stream)
        )
    }

    private fun resolvedStream(hash: String): ResolvedServiceWrapStream {
        return ResolvedServiceWrapStream(
            provider = ServiceWrapProvider.REAL_DEBRID,
            normalizedInfoHash = hash,
            playbackUrl = "https://rd.example/$hash",
            selectedFileIndex = 0,
            filename = "Show.S01E01.1080p.mkv",
            folderName = "Show",
            sizeBytes = 1_000_000L,
            durationMs = null,
            bitrate = null,
            width = null,
            height = null
        )
    }
}
