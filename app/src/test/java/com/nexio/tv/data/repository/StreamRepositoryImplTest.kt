package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.metadata.router.AnimeIdentityIndex
import com.nexio.tv.core.metadata.router.ParsedMetadataId
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.integration.addon.AddonStreamIntegrationProvider
import com.nexio.tv.data.integration.addon.transport.AddonStreamRequestCanceller
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.remote.api.StreamSearchRequestTag
import com.nexio.tv.data.remote.dto.StreamDto
import com.nexio.tv.data.remote.dto.StreamResponseDto
import com.nexio.tv.data.repository.servicewrap.ResolvedServiceWrapStream
import com.nexio.tv.data.repository.servicewrap.ServiceWrapProvider
import com.nexio.tv.data.repository.servicewrap.ServiceWrapResolutionBatch
import com.nexio.tv.data.repository.servicewrap.ServiceWrapResolutionChunkBatch
import com.nexio.tv.data.repository.servicewrap.ServiceWrapRequestContext
import com.nexio.tv.data.repository.servicewrap.ServiceWrapResolver
import com.nexio.tv.data.repository.servicewrap.ServiceWrapSessionFactory
import com.nexio.tv.data.repository.servicewrap.WrapCandidate
import com.nexio.tv.data.repository.servicewrap.WrapCandidateExtractor
import com.nexio.tv.data.repository.servicewrap.WrappedStreamBuilder
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRepositoryImplTest {

    private fun mockAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @Test
    fun `getStreamsFromAllAddons emits progressive results and completes`() = runTest {
        mockAndroidLog()

        val addonStreamIntegrationProvider = mockk<AddonStreamIntegrationProvider>()
        val addonRepository = mockk<AddonRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val serviceWrapSessionFactory = mockk<ServiceWrapSessionFactory>(relaxed = true)
        val addonStreamRequestCanceller = mockk<AddonStreamRequestCanceller>(relaxed = true)
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())

        val addonA = streamAddon("https://addon-a.example", "Addon A")
        val addonB = streamAddon("https://addon-b.example", "Addon B")
        val addonC = streamAddon("https://addon-c.example", "Addon C")

        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addonA, addonB, addonC))
        coEvery { addonStreamIntegrationProvider.getStreams(addonA.id, match { it.contains("addon-a.example") }, any()) } coAnswers {
            delay(60)
            NetworkResult.Success(StreamResponseDto(streams = listOf(streamDto("A-1"))))
        }
        coEvery { addonStreamIntegrationProvider.getStreams(addonB.id, match { it.contains("addon-b.example") }, any()) } coAnswers {
            delay(10)
            NetworkResult.Success(StreamResponseDto(streams = emptyList()))
        }
        coEvery { addonStreamIntegrationProvider.getStreams(addonC.id, match { it.contains("addon-c.example") }, any()) } coAnswers {
            delay(20)
            NetworkResult.Success(StreamResponseDto(streams = listOf(streamDto("C-1"))))
        }

        val repository = StreamRepositoryImpl(
            addonStreamIntegrationProvider = addonStreamIntegrationProvider,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            playerSettingsDataStore = playerSettingsDataStore,
            serviceWrapSessionFactory = serviceWrapSessionFactory,
            addonStreamRequestCanceller = addonStreamRequestCanceller,
            animeIdentityIndex = NoAnimeIdentityIndex
        )

        val emissions = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) {
                repository.getStreamsFromAllAddons(
                    type = "movie",
                    videoId = "tt1234567",
                    requestOrigin = "test_progressive",
                    requestId = "request-progressive"
                ).toList()
            }
        }

        assertTrue(emissions.first() is NetworkResult.Loading)
        val successes = emissions.filterIsInstance<NetworkResult.Success<List<com.nexio.tv.domain.model.AddonStreams>>>()
        assertEquals(3, successes.size)
        assertEquals(1, successes[0].data.size)
        assertEquals(2, successes[1].data.size)
        assertEquals(3, successes[2].data.size)
        assertTrue(successes[0].data.single().streams.isEmpty())
        val finalAddonNames = successes.last().data.map { it.addonName }.toSet()
        assertEquals(setOf("Addon A", "Addon B", "Addon C"), finalAddonNames)
    }

    @Test
    fun `getStreamsFromAllAddons completes with empty success when no addon returns links`() = runTest {
        mockAndroidLog()

        val addonStreamIntegrationProvider = mockk<AddonStreamIntegrationProvider>()
        val addonRepository = mockk<AddonRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val serviceWrapSessionFactory = mockk<ServiceWrapSessionFactory>(relaxed = true)
        val addonStreamRequestCanceller = mockk<AddonStreamRequestCanceller>(relaxed = true)
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())

        val addonA = streamAddon("https://addon-a.example", "Addon A")
        val addonB = streamAddon("https://addon-b.example", "Addon B")
        val addonC = streamAddon("https://addon-c.example", "Addon C")

        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addonA, addonB, addonC))
        coEvery {
            addonStreamIntegrationProvider.getStreams(addonA.id, match { it.contains("addon-a.example") }, any())
        } returns NetworkResult.Error("Service unavailable", 503)
        coEvery {
            addonStreamIntegrationProvider.getStreams(addonB.id, match { it.contains("addon-b.example") }, any())
        } throws RuntimeException("boom")
        coEvery {
            addonStreamIntegrationProvider.getStreams(addonC.id, match { it.contains("addon-c.example") }, any())
        } returns NetworkResult.Success(
            StreamResponseDto(streams = emptyList())
        )

        val repository = StreamRepositoryImpl(
            addonStreamIntegrationProvider = addonStreamIntegrationProvider,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            playerSettingsDataStore = playerSettingsDataStore,
            serviceWrapSessionFactory = serviceWrapSessionFactory,
            addonStreamRequestCanceller = addonStreamRequestCanceller,
            animeIdentityIndex = NoAnimeIdentityIndex
        )

        val emissions = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) {
                repository.getStreamsFromAllAddons(
                    type = "movie",
                    videoId = "tt7654321",
                    requestOrigin = "test_no_results",
                    requestId = "request-empty"
                ).toList()
            }
        }

        assertTrue(emissions.first() is NetworkResult.Loading)
        val successes = emissions.filterIsInstance<NetworkResult.Success<List<com.nexio.tv.domain.model.AddonStreams>>>()
        assertEquals(1, successes.size)
        assertEquals(1, successes.single().data.size)
        assertEquals("Addon C", successes.single().data.single().addonName)
        assertTrue(successes.single().data.single().streams.isEmpty())
    }

    @Test
    fun `getStreamsFromAllAddons emits wrapped service streams as each provider resolves`() = runTest {
        mockAndroidLog()

        val addonStreamIntegrationProvider = mockk<AddonStreamIntegrationProvider>()
        val addonRepository = mockk<AddonRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val addonStreamRequestCanceller = mockk<AddonStreamRequestCanceller>(relaxed = true)
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings(serviceWrapEnabled = true))

        val addonA = streamAddon("https://addon-a.example", "Addon A")
        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addonA))
        val hash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        coEvery { addonStreamIntegrationProvider.getStreams(addonA.id, match { it.contains("addon-a.example") }, any()) } returns NetworkResult.Success(
            StreamResponseDto(
                streams = listOf(
                    streamDto(
                        name = "P2P Candidate",
                        url = null,
                        infoHash = hash,
                        description = "Movie.2024.2160p.REMUX"
                    )
                )
            )
        )

        val serviceWrapSessionFactory = ServiceWrapSessionFactory(
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

        val repository = StreamRepositoryImpl(
            addonStreamIntegrationProvider = addonStreamIntegrationProvider,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            playerSettingsDataStore = playerSettingsDataStore,
            serviceWrapSessionFactory = serviceWrapSessionFactory,
            addonStreamRequestCanceller = addonStreamRequestCanceller,
            animeIdentityIndex = NoAnimeIdentityIndex
        )

        val emissions = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) {
                repository.getStreamsFromAllAddons(
                    type = "movie",
                    videoId = "tt1234567",
                    requestOrigin = "test_service_wrap_progressive",
                    requestId = "request-service-wrap-progressive"
                ).toList()
            }
        }

        val successes = emissions.filterIsInstance<NetworkResult.Success<List<com.nexio.tv.domain.model.AddonStreams>>>()
        assertEquals(3, successes.size)
        assertTrue(successes[0].data.single().streams.isEmpty())
        assertEquals(setOf("RD"), successes[1].data.single().streams.mapNotNull { it.wrappedProviderId }.toSet())
        assertEquals(setOf("RD", "PM"), successes[2].data.single().streams.mapNotNull { it.wrappedProviderId }.toSet())
    }

    @Test
    fun `getStreamsFromAllAddons emits first service-wrap chunk before later chunks finish`() = runTest {
        mockAndroidLog()

        val addonStreamIntegrationProvider = mockk<AddonStreamIntegrationProvider>()
        val addonRepository = mockk<AddonRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val addonStreamRequestCanceller = mockk<AddonStreamRequestCanceller>(relaxed = true)
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings(serviceWrapEnabled = true))

        val addonA = streamAddon("https://addon-a.example", "Addon A")
        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addonA))
        coEvery { addonStreamIntegrationProvider.getStreams(addonA.id, match { it.contains("addon-a.example") }, any()) } returns NetworkResult.Success(
            StreamResponseDto(
                streams = (1..25).map { index ->
                    streamDto(
                        name = "P2P Candidate $index",
                        url = null,
                        infoHash = "%040x".format(index),
                        description = "Movie.2024.2160p.REMUX"
                    )
                }
            )
        )

        val serviceWrapSessionFactory = ServiceWrapSessionFactory(
            extractor = WrapCandidateExtractor(),
            resolver = object : ServiceWrapResolver {
                override suspend fun resolve(
                    candidate: WrapCandidate,
                    requestContext: ServiceWrapRequestContext
                ): List<ResolvedServiceWrapStream> = error("chunk path should be used")

                override fun resolveChunkProgressively(
                    candidates: List<WrapCandidate>,
                    requestContext: ServiceWrapRequestContext
                ): Flow<ServiceWrapResolutionChunkBatch> = flow {
                    if (candidates.size == 5) delay(1_000L)
                    emit(
                        ServiceWrapResolutionChunkBatch(
                            streamsByHash = candidates.associate { candidate ->
                                candidate.normalizedInfoHash to listOf(
                                    resolvedStream(ServiceWrapProvider.REAL_DEBRID, candidate.normalizedInfoHash)
                                )
                            },
                            isTerminal = true
                        )
                    )
                }
            },
            wrappedStreamBuilder = WrappedStreamBuilder(),
            maxConcurrentResolutions = 2,
            chunkSize = 20,
            chunkFlushDelayMs = 250L
        )

        val repository = StreamRepositoryImpl(
            addonStreamIntegrationProvider = addonStreamIntegrationProvider,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            playerSettingsDataStore = playerSettingsDataStore,
            serviceWrapSessionFactory = serviceWrapSessionFactory,
            addonStreamRequestCanceller = addonStreamRequestCanceller,
            animeIdentityIndex = NoAnimeIdentityIndex
        )

        val emissions = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) {
                repository.getStreamsFromAllAddons(
                    type = "movie",
                    videoId = "tt1234567",
                    requestOrigin = "test_service_wrap_chunk_early",
                    requestId = "request-service-wrap-chunk-early"
                ).toList()
            }
        }

        val successes = emissions.filterIsInstance<NetworkResult.Success<List<com.nexio.tv.domain.model.AddonStreams>>>()
        assertTrue(successes.any { success -> success.data.single().streams.size == 20 })
        assertEquals(25, successes.last().data.single().streams.size)
    }

    @Test
    fun `cancelActiveStreamRequests delegates to addon stream request canceller`() {
        mockAndroidLog()

        val addonStreamIntegrationProvider = mockk<AddonStreamIntegrationProvider>(relaxed = true)
        val addonRepository = mockk<AddonRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val serviceWrapSessionFactory = mockk<ServiceWrapSessionFactory>(relaxed = true)
        val addonStreamRequestCanceller = mockk<AddonStreamRequestCanceller>()

        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())
        every { addonStreamRequestCanceller.cancelActiveStreamRequests("request-a") } returns 1

        val repository = StreamRepositoryImpl(
            addonStreamIntegrationProvider = addonStreamIntegrationProvider,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            playerSettingsDataStore = playerSettingsDataStore,
            serviceWrapSessionFactory = serviceWrapSessionFactory,
            addonStreamRequestCanceller = addonStreamRequestCanceller,
            animeIdentityIndex = NoAnimeIdentityIndex
        )

        repository.cancelActiveStreamRequests("request-a")

        io.mockk.verify(exactly = 1) { addonStreamRequestCanceller.cancelActiveStreamRequests("request-a") }
    }

    @Test
    fun `getStreamsFromAddon uses normalized base url as standalone addon scope key`() = runTest {
        mockAndroidLog()

        val addonStreamIntegrationProvider = mockk<AddonStreamIntegrationProvider>()
        val addonRepository = mockk<AddonRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val serviceWrapSessionFactory = mockk<ServiceWrapSessionFactory>(relaxed = true)
        val addonStreamRequestCanceller = mockk<AddonStreamRequestCanceller>(relaxed = true)
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())
        coEvery {
            addonStreamIntegrationProvider.getStreams(
                addonId = "https://addon.example/configured?token=abc",
                streamUrl = "https://addon.example/configured/stream/movie/tt1234567.json?token=abc",
                requestTag = StreamSearchRequestTag("standalone")
            )
        } returns NetworkResult.Success(StreamResponseDto(streams = listOf(streamDto("Standalone"))))

        val repository = StreamRepositoryImpl(
            addonStreamIntegrationProvider = addonStreamIntegrationProvider,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            playerSettingsDataStore = playerSettingsDataStore,
            serviceWrapSessionFactory = serviceWrapSessionFactory,
            addonStreamRequestCanceller = addonStreamRequestCanceller,
            animeIdentityIndex = NoAnimeIdentityIndex
        )

        val result = repository.getStreamsFromAddon(
            baseUrl = "https://addon.example/configured/manifest.json?token=abc",
            type = "movie",
            videoId = "tt1234567"
        )

        assertTrue(result is NetworkResult.Success)
        coVerify(exactly = 1) {
            addonStreamIntegrationProvider.getStreams(
                addonId = "https://addon.example/configured?token=abc",
                streamUrl = "https://addon.example/configured/stream/movie/tt1234567.json?token=abc",
                requestTag = StreamSearchRequestTag("standalone")
            )
        }
    }

    private fun streamAddon(baseUrl: String, displayName: String): Addon {
        return Addon(
            id = displayName.lowercase().replace(" ", "_"),
            name = displayName,
            displayName = displayName,
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = baseUrl,
            catalogs = emptyList(),
            types = listOf(ContentType.MOVIE),
            resources = listOf(
                AddonResource(
                    name = "stream",
                    types = listOf("movie"),
                    idPrefixes = null
                )
            )
        )
    }

    private fun streamDto(
        name: String,
        url: String? = "https://cdn.example/$name.m3u8",
        infoHash: String? = null,
        description: String? = null
    ): StreamDto {
        return StreamDto(
            name = name,
            description = description,
            url = url,
            infoHash = infoHash
        )
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
}

private object NoAnimeIdentityIndex : AnimeIdentityIndex {
    override suspend fun resolveKitsuId(id: ParsedMetadataId): String? = null
    override suspend fun isAnime(id: ParsedMetadataId): Boolean = false
}
