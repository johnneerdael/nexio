package com.nexio.tv.data.repository.servicewrap

import com.nexio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DebridAvailabilityResolverTest {

    @Test
    fun `extractRealDebridVariants matches hash keys case-insensitively`() {
        val variants = listOf(
            mapOf(
                "1" to com.nexio.tv.data.remote.dto.debrid.RealDebridInstantAvailabilityFileDto(
                    filename = "Show.S01E02.1080p.WEB-DL.mkv",
                    filesize = 4_000_000_000L
                )
            )
        )

        val availability = mapOf(
            "abcdef0123456789abcdef0123456789abcdef01" to mapOf(
                "rd" to variants
            )
        )

        val resolved = extractRealDebridVariants(
            availabilityBody = availability,
            normalizedInfoHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        )

        assertEquals(variants, resolved)
    }

    @Test
    fun `secure wrap candidate score rejects archive payloads`() {
        val candidate = buildWrapCandidate(sourceFilename = "Movie.2024.2160p.REMUX.mkv")

        val score = scoreSecureWrapCandidateFile(
            filename = "Movie.2024.2160p.REMUX.rar",
            fullPath = "Movie.2024.2160p.REMUX.rar",
            sizeBytes = 120_000_000_000L,
            candidate = candidate,
            requestContext = ServiceWrapRequestContext(contentType = "movie", season = null, episode = null)
        )

        assertNull(score)
    }

    @Test
    fun `secure wrap candidate score accepts supported legacy video containers`() {
        val candidate = buildWrapCandidate(sourceFilename = "Movie.2024.2160p.REMUX.mkv")

        val score = scoreSecureWrapCandidateFile(
            filename = "Movie.2024.2160p.REMUX.divx",
            fullPath = "Movie.2024.2160p.REMUX.divx",
            sizeBytes = 120_000_000_000L,
            candidate = candidate,
            requestContext = ServiceWrapRequestContext(contentType = "movie", season = null, episode = null)
        )

        assertNotNull(score)
    }

    private fun buildWrapCandidate(sourceFilename: String): WrapCandidate {
        val stream = Stream(
            name = sourceFilename,
            title = sourceFilename,
            description = sourceFilename,
            url = "magnet:?xt=urn:btih:abcdef0123456789abcdef0123456789abcdef01",
            ytId = null,
            infoHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            sources = null,
            addonName = "Test",
            addonLogo = null
        )
        return WrapCandidate(
            normalizedInfoHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
            magnetUri = "magnet:?xt=urn:btih:abcdef0123456789abcdef0123456789abcdef01",
            sourceStream = stream,
            sourceAddonName = "Test",
            sourceAddonLogo = null,
            sourceStreamKey = "test-stream-key",
            sourceParsed = parseSourceStream(stream)
        )
    }
}
