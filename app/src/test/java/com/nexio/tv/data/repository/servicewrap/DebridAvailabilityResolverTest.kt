package com.nexio.tv.data.repository.servicewrap

import com.nexio.tv.domain.model.Stream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridAvailabilityResolverTest {
    private val resolverSource =
        File("app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt")

    @Test
    fun `resolver avoids direct auth service and unused service seam injections`() {
        val source = resolverSource.readText()

        assertTrue(source.contains("private val realDebridAuthDataStore: RealDebridAuthDataStore"))
        assertTrue(source.contains("realDebridAuthDataStore.isAuthenticated.first()"))
        assertFalse(source.contains("RealDebridAuthService"))
        assertFalse(source.contains("realDebridAuthService"))
        assertFalse(source.contains("PremiumizeService"))
        assertFalse(source.contains("premiumizeService"))
        assertFalse(source.contains("TorBoxService"))
        assertFalse(source.contains("torBoxService"))
        assertFalse(source.contains("EasyDebridService"))
        assertFalse(source.contains("easyDebridService"))
    }

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

    @Test
    fun `parse file candidate merges filename and folder metadata like main stream parser`() {
        val parsed = parseFileCandidate(
            "The.Lord.of.the.Rings.The.Return.of.the.King.2003.Extended.2160p.UHD.BluRay.REMUX/00010.m2ts"
        )

        assertEquals("00010.m2ts", parsed?.filename)
        assertEquals(
            "The.Lord.of.the.Rings.The.Return.of.the.King.2003.Extended.2160p.UHD.BluRay.REMUX",
            parsed?.folderName
        )
        assertEquals("2003", parsed?.parsed?.year)
        assertEquals("2160p", parsed?.parsed?.resolution)
    }

    @Test
    fun `secure wrap candidate score uses parent folder metadata for numeric m2ts files`() {
        val candidate = buildWrapCandidate(sourceFilename = "The.Lord.of.the.Rings.The.Return.of.the.King.2003.Extended.2160p.UHD.BluRay.REMUX.mkv")

        val score = scoreSecureWrapCandidateFile(
            filename = "00010.m2ts",
            fullPath = "The.Lord.of.the.Rings.The.Return.of.the.King.2003.Extended.2160p.UHD.BluRay.REMUX/00010.m2ts",
            sizeBytes = 120_000_000_000L,
            candidate = candidate,
            requestContext = ServiceWrapRequestContext(contentType = "movie", season = null, episode = null)
        )

        assertNotNull(score)
        assertTrue(score!! > 1000)
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
