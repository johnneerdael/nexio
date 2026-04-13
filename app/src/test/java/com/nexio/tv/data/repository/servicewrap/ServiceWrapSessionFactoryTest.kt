package com.nexio.tv.data.repository.servicewrap

import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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
    fun `session resolves a duplicate hash from multiple addons once and emits one wrapped batch`() = runTest {
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
        val resultA = session.processAddonStreams(
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
        val resultB = session.processAddonStreams(
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

        assertTrue(resultA.visibleStreams.isEmpty())
        assertTrue(resultB.visibleStreams.isEmpty())
        assertEquals(1, resultA.launchedWrapCount)
        assertEquals(0, resultB.launchedWrapCount)

        advanceUntilIdle()

        assertEquals(listOf(hash), observedHashes)
        assertEquals(1, batches.size)
        assertEquals("Addon A", batches.single().addonName)
        assertEquals(1, batches.single().wrappedStreams.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session leaves cached direct debrid streams visible instead of rewrapping them`() = runTest {
        val factory = ServiceWrapSessionFactory(
            extractor = WrapCandidateExtractor(),
            resolver = object : ServiceWrapResolver {
                override suspend fun resolve(
                    candidate: WrapCandidate,
                    requestContext: ServiceWrapRequestContext
                ): List<ResolvedServiceWrapStream> = error("resolver should not be called")
            },
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

        val stream = stream(
            name = "RD+ cached",
            infoHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
            url = "https://real-debrid.example/download",
            description = "⚡ RD+ cached\n📄 Show.S01E02.1080p.WEB-DL.mkv"
        )

        val result = session.processAddonStreams(
            addonName = "Addon A",
            addonLogo = null,
            streams = listOf(stream)
        )

        assertEquals(listOf(stream), result.visibleStreams)
        assertEquals(0, result.launchedWrapCount)
        advanceUntilIdle()
        assertTrue(batches.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session emits progressive resolver batches before terminal completion`() = runTest {
        val hash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        val factory = ServiceWrapSessionFactory(
            extractor = WrapCandidateExtractor(),
            resolver = object : ServiceWrapResolver {
                override suspend fun resolve(
                    candidate: WrapCandidate,
                    requestContext: ServiceWrapRequestContext
                ): List<ResolvedServiceWrapStream> = error("progressive path should be used")

                override fun resolveProgressively(
                    candidate: WrapCandidate,
                    requestContext: ServiceWrapRequestContext
                ): Flow<ServiceWrapResolutionBatch> = flow {
                    emit(
                        ServiceWrapResolutionBatch(
                            streams = listOf(resolvedStream(ServiceWrapProvider.REAL_DEBRID, candidate.normalizedInfoHash)),
                            isTerminal = false
                        )
                    )
                    delay(1_000L)
                    emit(
                        ServiceWrapResolutionBatch(
                            streams = listOf(resolvedStream(ServiceWrapProvider.PREMIUMIZE, candidate.normalizedInfoHash)),
                            isTerminal = true
                        )
                    )
                }
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
                    infoHash = hash,
                    url = null,
                    description = "Movie.2024.2160p.REMUX"
                )
            )
        )

        assertTrue(result.visibleStreams.isEmpty())
        assertEquals(1, result.launchedWrapCount)

        runCurrent()
        assertEquals(1, batches.size)
        assertEquals(false, batches.single().isTerminal)
        assertEquals(setOf("RD"), batches.single().wrappedStreams.mapNotNull { it.wrappedProviderId }.toSet())

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, batches.size)
        assertEquals(true, batches.last().isTerminal)
        assertEquals(setOf("PM"), batches.last().wrappedStreams.mapNotNull { it.wrappedProviderId }.toSet())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session limits concurrent hash resolutions`() = runTest {
        val active = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val factory = ServiceWrapSessionFactory(
            extractor = WrapCandidateExtractor(),
            resolver = object : ServiceWrapResolver {
                override suspend fun resolve(
                    candidate: WrapCandidate,
                    requestContext: ServiceWrapRequestContext
                ): List<ResolvedServiceWrapStream> {
                    val current = active.incrementAndGet()
                    maxObserved.updateAndGet { previous -> maxOf(previous, current) }
                    delay(1_000L)
                    active.decrementAndGet()
                    return listOf(resolvedStream(ServiceWrapProvider.REAL_DEBRID, candidate.normalizedInfoHash))
                }
            },
            wrappedStreamBuilder = WrappedStreamBuilder(),
            maxConcurrentResolutions = 2
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
                    name = "Movie Candidate 1",
                    infoHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
                    url = null,
                    description = "Movie.2024.2160p.REMUX"
                ),
                stream(
                    name = "Movie Candidate 2",
                    infoHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF02",
                    url = null,
                    description = "Movie.2024.1080p"
                ),
                stream(
                    name = "Movie Candidate 3",
                    infoHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF03",
                    url = null,
                    description = "Movie.2024.720p"
                )
            )
        )

        assertEquals(3, result.launchedWrapCount)

        runCurrent()
        assertEquals(2, active.get())
        assertEquals(2, maxObserved.get())

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, active.get())
        assertEquals(2, maxObserved.get())

        advanceUntilIdle()
        assertEquals(0, active.get())
        assertEquals(2, maxObserved.get())
        assertEquals(3, batches.size)
    }

    private fun resolvedStream(
        provider: ServiceWrapProvider,
        hash: String
    ): ResolvedServiceWrapStream {
        return ResolvedServiceWrapStream(
            provider = provider,
            normalizedInfoHash = hash,
            playbackUrl = "https://${provider.providerId.lowercase()}.example/$hash",
            selectedFileIndex = 0,
            filename = "Movie.2024.2160p.REMUX.mkv",
            folderName = "Movie",
            sizeBytes = 4_000_000_000L,
            durationMs = 3_600_000L,
            bitrate = 8_000_000L,
            width = 3840,
            height = 2160
        )
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
