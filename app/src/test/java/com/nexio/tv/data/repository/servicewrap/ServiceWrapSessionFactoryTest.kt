package com.nexio.tv.data.repository.servicewrap

import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceWrapSessionFactoryTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session hides eligible streams, validates unique hashes once, and emits one wrapped stream per cached provider`() = runTest {
        val observedHashes = mutableListOf<String>()
        val resolver = object : ServiceWrapResolver {
            override suspend fun resolve(
                candidate: WrapCandidate,
                requestContext: ServiceWrapRequestContext
            ): List<ResolvedServiceWrapStream> {
                observedHashes += candidate.normalizedInfoHash
                return listOf(
                    ResolvedServiceWrapStream(
                        provider = ServiceWrapProvider.REAL_DEBRID,
                        normalizedInfoHash = candidate.normalizedInfoHash,
                        playbackUrl = "https://rd.example/${candidate.normalizedInfoHash}",
                        selectedFileIndex = 0,
                        filename = "Show.S01E02.1080p.WEB-DL.mkv",
                        folderName = "Show Season 1",
                        sizeBytes = 4_000_000_000L,
                        durationMs = 3_600_000L,
                        bitrate = 8_000_000L,
                        width = 1920,
                        height = 1080
                    ),
                    ResolvedServiceWrapStream(
                        provider = ServiceWrapProvider.PREMIUMIZE,
                        normalizedInfoHash = candidate.normalizedInfoHash,
                        playbackUrl = "https://pm.example/${candidate.normalizedInfoHash}",
                        selectedFileIndex = 0,
                        filename = "Show.S01E02.1080p.WEB-DL.mkv",
                        folderName = "Show Season 1",
                        sizeBytes = 4_000_000_000L,
                        durationMs = null,
                        bitrate = null,
                        width = null,
                        height = null
                    )
                )
            }
        }
        val factory = ServiceWrapSessionFactory(
            extractor = WrapCandidateExtractor(),
            resolver = resolver,
            wrappedStreamBuilder = WrappedStreamBuilder()
        )
        val batches = mutableListOf<ServiceWrapResolvedBatch>()
        val session = factory.createSession(
            requestContext = ServiceWrapRequestContext(
                contentType = "series",
                season = 1,
                episode = 2
            ),
            scope = this,
            onResolved = { batch -> batches += batch }
        )

        val hash = "0123456789ABCDEF0123456789ABCDEF01234567"
        val eligible = stream(
            name = "P2P Candidate",
            infoHash = hash,
            url = null,
            description = "Show.S01E02.1080p.WEB-DL"
        )
        val duplicateEligible = stream(
            name = "Duplicate Candidate",
            infoHash = hash.lowercase(),
            url = null,
            description = "Show.S01E02.1080p.WEB-DL duplicate"
        )
        val httpStream = stream(
            name = "HTTP Stream",
            infoHash = null,
            url = "https://cdn.example/direct.m3u8",
            description = "Direct stream"
        )

        val result = session.processAddonStreams(
            addonName = "Addon A",
            addonLogo = null,
            streams = listOf(eligible, duplicateEligible, httpStream)
        )

        assertEquals(listOf(httpStream), result.visibleStreams)
        assertEquals(1, result.launchedWrapCount)
        assertEquals(1, session.inFlightCount())

        advanceUntilIdle()

        assertEquals(listOf(hash), observedHashes)
        assertEquals(1, batches.size)
        assertEquals(2, batches.single().wrappedStreams.size)
        assertTrue(batches.single().wrappedStreams.all { it.addonName == "Addon A" })
        assertEquals(setOf("RD", "PM"), batches.single().wrappedStreams.mapNotNull { it.wrappedProviderId }.toSet())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session emits no wrapped streams when resolver reports uncached`() = runTest {
        val factory = ServiceWrapSessionFactory(
            extractor = WrapCandidateExtractor(),
            resolver = object : ServiceWrapResolver {
                override suspend fun resolve(
                    candidate: WrapCandidate,
                    requestContext: ServiceWrapRequestContext
                ): List<ResolvedServiceWrapStream> = emptyList()
            },
            wrappedStreamBuilder = WrappedStreamBuilder()
        )
        val batches = mutableListOf<ServiceWrapResolvedBatch>()
        val session = factory.createSession(
            requestContext = ServiceWrapRequestContext(
                contentType = "movie",
                season = null,
                episode = null
            ),
            scope = this,
            onResolved = { batch -> batches += batch }
        )

        val result = session.processAddonStreams(
            addonName = "Addon A",
            addonLogo = null,
            streams = listOf(
                stream(
                    name = "Movie Candidate",
                    infoHash = "89ABCDEF0123456789ABCDEF0123456789ABCDEF",
                    url = null,
                    description = "Movie.2024.2160p"
                )
            )
        )

        assertTrue(result.visibleStreams.isEmpty())
        assertEquals(1, result.launchedWrapCount)
        advanceUntilIdle()
        assertEquals(1, batches.size)
        assertTrue(batches.single().wrappedStreams.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session reuses one hash resolution while emitting wrapped results for every addon that reported it`() = runTest {
        val observedHashes = mutableListOf<String>()
        val resolver = object : ServiceWrapResolver {
            override suspend fun resolve(
                candidate: WrapCandidate,
                requestContext: ServiceWrapRequestContext
            ): List<ResolvedServiceWrapStream> {
                observedHashes += candidate.normalizedInfoHash
                return listOf(
                    ResolvedServiceWrapStream(
                        provider = ServiceWrapProvider.REAL_DEBRID,
                        normalizedInfoHash = candidate.normalizedInfoHash,
                        playbackUrl = "https://rd.example/${candidate.normalizedInfoHash}",
                        selectedFileIndex = 0,
                        filename = "Show.S01E02.1080p.WEB-DL.mkv",
                        folderName = "Show Season 1",
                        sizeBytes = 4_000_000_000L,
                        durationMs = 3_600_000L,
                        bitrate = 8_000_000L,
                        width = 1920,
                        height = 1080
                    )
                )
            }
        }
        val factory = ServiceWrapSessionFactory(
            extractor = WrapCandidateExtractor(),
            resolver = resolver,
            wrappedStreamBuilder = WrappedStreamBuilder()
        )
        val batches = mutableListOf<ServiceWrapResolvedBatch>()
        val session = factory.createSession(
            requestContext = ServiceWrapRequestContext(
                contentType = "series",
                season = 1,
                episode = 2
            ),
            scope = this,
            onResolved = { batch -> batches += batch }
        )

        val hash = "FEDCBA9876543210FEDCBA9876543210FEDCBA98"
        val addonA = async {
            session.processAddonStreams(
                addonName = "Addon A",
                addonLogo = null,
                streams = listOf(
                    stream(
                        name = "A Candidate",
                        infoHash = hash,
                        url = null,
                        description = "Show.S01E02.1080p.WEB-DL"
                    )
                )
            )
        }
        val addonB = async {
            session.processAddonStreams(
                addonName = "Addon B",
                addonLogo = null,
                streams = listOf(
                    stream(
                        name = "B Candidate",
                        infoHash = hash,
                        url = null,
                        description = "Show.S01E02.1080p.WEB-DL"
                    )
                )
            )
        }

        val results = awaitAll(addonA, addonB)
        assertTrue(results.all { it.visibleStreams.isEmpty() })
        assertEquals(2, results.sumOf { it.launchedWrapCount })

        advanceUntilIdle()

        assertEquals(listOf(hash), observedHashes)
        assertEquals(2, batches.size)
        assertEquals(setOf("Addon A", "Addon B"), batches.map { it.addonName }.toSet())
        assertTrue(batches.all { batch ->
            batch.wrappedStreams.size == 1 && batch.wrappedStreams.single().addonName == batch.addonName
        })
    }

    private fun stream(
        name: String,
        infoHash: String?,
        url: String?,
        description: String
    ): Stream {
        return Stream(
            name = name,
            title = null,
            description = description,
            url = url,
            ytId = null,
            infoHash = infoHash,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Addon A",
            addonLogo = null,
            addonParserPreset = AddonParserPreset.GENERIC
        )
    }
}
