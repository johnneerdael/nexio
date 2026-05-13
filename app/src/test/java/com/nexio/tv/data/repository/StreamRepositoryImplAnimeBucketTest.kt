package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.metadata.router.AnimeIdentityIndex
import com.nexio.tv.core.metadata.router.ParsedMetadataId
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.integration.addon.AddonStreamIntegrationProvider
import com.nexio.tv.data.integration.addon.transport.AddonStreamRequestCanceller
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.remote.dto.StreamDto
import com.nexio.tv.data.remote.dto.StreamResponseDto
import com.nexio.tv.data.repository.servicewrap.ResolvedServiceWrapStream
import com.nexio.tv.data.repository.servicewrap.ServiceWrapProvider
import com.nexio.tv.data.repository.servicewrap.ServiceWrapResolutionBatch
import com.nexio.tv.data.repository.servicewrap.ServiceWrapRequestContext
import com.nexio.tv.data.repository.servicewrap.ServiceWrapResolver
import com.nexio.tv.data.repository.servicewrap.ServiceWrapSessionFactory
import com.nexio.tv.data.repository.servicewrap.WrapCandidate
import com.nexio.tv.data.repository.servicewrap.WrapCandidateExtractor
import com.nexio.tv.data.repository.servicewrap.WrappedStreamBuilder
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.AddonStreams
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRepositoryImplAnimeBucketTest {

    @Test
    fun `bucket is true only when both addon isAnime and contentIsAnime`() = runTest {
        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)
        val genericAddon = streamAddon("https://generic.example", "Generic Addon")
        val index = RecordingAnimeIdentityIndex(contentIsAnime = true)
        val repository = repository(
            addons = listOf(animeAddon, genericAddon),
            animeIdentityIndex = index
        )

        val buckets = repository.successBuckets(videoId = "mal:21")

        assertTrue(buckets.single { it.addonName == "Anime Addon" }.isAnimeBucket)
        assertFalse(buckets.single { it.addonName == "Generic Addon" }.isAnimeBucket)
        assertEquals(listOf("21"), index.lookups.map { it.value })
    }

    @Test
    fun `stream classification trace events include normalized parent and addon bucket`() = runTest {
        val sink = RecordingTraceSink()
        val traceMetadataEvents = TraceMetadataEvents(sink, sessionId = { "trace-stream" })
        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)
        val repository = repository(
            addons = listOf(animeAddon),
            animeIdentityIndex = RecordingAnimeIdentityIndex(contentIsAnime = true),
            traceMetadataEvents = traceMetadataEvents
        )

        repository.successBuckets(videoId = "kitsu:7442:1:1")

        val requestEvents = sink.events.filter { it.eventType == "stream.request_classified" }
        assertEquals(1, requestEvents.size)
        val requestPayload = requestEvents.single().payload as Map<*, *>
        assertEquals("kitsu:7442:1:1", requestPayload["contentId"])
        assertEquals("kitsu:7442", requestPayload["parentId"])
        assertEquals(true, requestPayload["contentIsAnime"])
        assertEquals("AnimeIdentityIndex", requestPayload["evidence"])

        val bucketEvents = sink.events.filter { it.eventType == "stream.addon_bucketed" }
        assertEquals(1, bucketEvents.size)
        val bucketPayload = bucketEvents.single().payload as Map<*, *>
        val addonIdHash = bucketPayload["addonIdHash"] as String
        assertEquals(12, addonIdHash.length)
        assertFalse(addonIdHash == animeAddon.id)
        assertEquals(true, bucketPayload["addonIsAnime"])
        assertEquals(true, bucketPayload["contentIsAnime"])
        assertEquals(true, bucketPayload["isAnimeBucket"])
    }

    @Test
    fun `bucket is false when content is not anime even if addon is tagged`() = runTest {
        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)
        val repository = repository(
            addons = listOf(animeAddon),
            animeIdentityIndex = RecordingAnimeIdentityIndex(contentIsAnime = false)
        )

        val buckets = repository.successBuckets(videoId = "mal:21")

        assertFalse(buckets.single().isAnimeBucket)
    }

    @Test
    fun `unparseable videoId leaves bucket false`() = runTest {
        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)
        val repository = repository(
            addons = listOf(animeAddon),
            animeIdentityIndex = ThrowingAnimeIdentityIndex
        )

        val buckets = repository.successBuckets(videoId = "garbage-id")

        assertFalse(buckets.single().isAnimeBucket)
    }

    @Test
    fun `generic addons do not call anime identity index and still return streams`() = runTest {
        val genericAddon = streamAddon("https://generic.example", "Generic Addon")
        val repository = repository(
            addons = listOf(genericAddon),
            animeIdentityIndex = ThrowingAnimeIdentityIndex
        )

        val buckets = repository.successBuckets(videoId = "mal:21")

        assertFalse(buckets.single().isAnimeBucket)
        assertEquals(1, buckets.single().streams.size)
    }

    @Test
    fun `anime identity lookup failure leaves buckets false and still returns streams`() = runTest {
        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)
        val repository = repository(
            addons = listOf(animeAddon),
            animeIdentityIndex = ThrowingAnimeIdentityIndex
        )

        val buckets = repository.successBuckets(videoId = "mal:21")

        assertFalse(buckets.single().isAnimeBucket)
        assertEquals(1, buckets.single().streams.size)
    }

    @Test
    fun `anime identity lookup cancellation propagates before stream requests`() = runTest {
        mockAndroidLog()

        val addonStreamIntegrationProvider = mockk<AddonStreamIntegrationProvider>()
        val addonRepository = mockk<AddonRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val serviceWrapSessionFactory = mockk<ServiceWrapSessionFactory>(relaxed = true)
        val addonStreamRequestCanceller = mockk<AddonStreamRequestCanceller>(relaxed = true)
        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)

        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(animeAddon))
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())
        coEvery {
            addonStreamIntegrationProvider.getStreams(animeAddon.id, any(), any())
        } returns NetworkResult.Success(StreamResponseDto(streams = listOf(streamDto("Unexpected"))))

        val repository = StreamRepositoryImpl(
            addonStreamIntegrationProvider = addonStreamIntegrationProvider,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            playerSettingsDataStore = playerSettingsDataStore,
            serviceWrapSessionFactory = serviceWrapSessionFactory,
            addonStreamRequestCanceller = addonStreamRequestCanceller,
            animeIdentityIndex = object : AnimeIdentityIndex {
                override suspend fun resolveKitsuId(id: ParsedMetadataId): String? = null

                override suspend fun isAnime(id: ParsedMetadataId): Boolean {
                    throw CancellationException("cancelled")
                }
            },
            traceMetadataEvents = mockk(relaxed = true)
        )
        var thrown: CancellationException? = null

        try {
            repository.getStreamsFromAllAddons(
                type = "movie",
                videoId = "mal:21",
                requestOrigin = "test_anime_bucket_cancellation",
                requestId = "request-anime-bucket-cancellation"
            ).toList()
        } catch (e: CancellationException) {
            thrown = e
        }

        assertEquals("cancelled", thrown?.message)
        coVerify(exactly = 0) {
            addonStreamIntegrationProvider.getStreams(any(), any(), any())
        }
    }

    @Test
    fun `kitsu_episode_id_routes_to_anime_bucket`() = runTest {
        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)
        val index = RecordingAnimeIdentityIndex(contentIsAnime = true)
        val repository = repository(
            addons = listOf(animeAddon),
            animeIdentityIndex = index
        )

        val buckets = repository.successBuckets(videoId = "kitsu:7442:1:1")

        assertEquals(listOf("7442"), index.lookups.map { it.value })
        assertTrue(buckets.single().isAnimeBucket)
    }

    @Test
    fun `imdb_episode_id_routes_to_anime_bucket_when_parent_imdb_is_anime`() = runTest {
        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)
        val index = RecordingAnimeIdentityIndex(contentIsAnime = true)
        val repository = repository(
            addons = listOf(animeAddon),
            animeIdentityIndex = index
        )

        val buckets = repository.successBuckets(videoId = "tt12343534:1:1")

        assertEquals(listOf("tt12343534"), index.lookups.map { it.value })
        assertTrue(buckets.single().isAnimeBucket)
    }

    @Test
    fun `mal_episode_id_routes_to_anime_bucket`() = runTest {
        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)
        val index = RecordingAnimeIdentityIndex(contentIsAnime = true)
        val repository = repository(
            addons = listOf(animeAddon),
            animeIdentityIndex = index
        )

        val buckets = repository.successBuckets(videoId = "mal:21:1:1")

        assertEquals(listOf("21"), index.lookups.map { it.value })
        assertTrue(buckets.single().isAnimeBucket)
    }

    @Test
    fun `imdb_one_to_many_anime_id_sets_contentIsAnime_without_selecting_single_kitsu_record`() = runTest {
        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)
        val repository = repository(
            addons = listOf(animeAddon),
            animeIdentityIndex = object : AnimeIdentityIndex {
                override suspend fun resolveKitsuId(id: ParsedMetadataId): String? {
                    error("resolveKitsuId should not be used for stream bucket classification")
                }

                override suspend fun isAnime(id: ParsedMetadataId): Boolean = true
            }
        )

        val buckets = repository.successBuckets(videoId = "tt12343534:1:1")

        assertTrue(buckets.single().isAnimeBucket)
    }

    @Test
    fun `anime content queries only anime tagged compatible addons when configured`() = runTest {
        mockAndroidLog()

        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)
        val genericAddon = streamAddon("https://generic.example", "Generic Addon")
        val addonStreamIntegrationProvider = mockk<AddonStreamIntegrationProvider>()
        val addonRepository = mockk<AddonRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val serviceWrapSessionFactory = mockk<ServiceWrapSessionFactory>(relaxed = true)
        val addonStreamRequestCanceller = mockk<AddonStreamRequestCanceller>(relaxed = true)

        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(animeAddon, genericAddon))
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())
        coEvery {
            addonStreamIntegrationProvider.getStreams(animeAddon.id, match { it.contains("anime.example") }, any())
        } returns NetworkResult.Success(StreamResponseDto(streams = listOf(streamDto("Anime Stream"))))
        coEvery {
            addonStreamIntegrationProvider.getStreams(genericAddon.id, any(), any())
        } returns NetworkResult.Success(StreamResponseDto(streams = listOf(streamDto("Generic Stream"))))

        val repository = StreamRepositoryImpl(
            addonStreamIntegrationProvider = addonStreamIntegrationProvider,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            playerSettingsDataStore = playerSettingsDataStore,
            serviceWrapSessionFactory = serviceWrapSessionFactory,
            addonStreamRequestCanceller = addonStreamRequestCanceller,
            animeIdentityIndex = RecordingAnimeIdentityIndex(contentIsAnime = true),
            traceMetadataEvents = mockk(relaxed = true)
        )

        val emissions = repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "mal:21",
            requestOrigin = "test_anime_only_addons",
            requestId = "request-anime-only-addons"
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList()

        assertEquals(listOf("Anime Addon"), emissions.last().data.map { it.addonName })
        assertTrue(emissions.last().data.single().isAnimeBucket)
        coVerify(exactly = 1) {
            addonStreamIntegrationProvider.getStreams(animeAddon.id, match { it.contains("anime.example") }, any())
        }
        coVerify(exactly = 0) {
            addonStreamIntegrationProvider.getStreams(genericAddon.id, any(), any())
        }
    }

    @Test
    fun `non anime content still queries anime tagged and generic compatible addons`() = runTest {
        mockAndroidLog()

        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)
        val genericAddon = streamAddon("https://generic.example", "Generic Addon")
        val addonStreamIntegrationProvider = mockk<AddonStreamIntegrationProvider>()
        val addonRepository = mockk<AddonRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val serviceWrapSessionFactory = mockk<ServiceWrapSessionFactory>(relaxed = true)
        val addonStreamRequestCanceller = mockk<AddonStreamRequestCanceller>(relaxed = true)

        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(animeAddon, genericAddon))
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())
        coEvery {
            addonStreamIntegrationProvider.getStreams(animeAddon.id, match { it.contains("anime.example") }, any())
        } returns NetworkResult.Success(StreamResponseDto(streams = listOf(streamDto("Anime Tagged Stream"))))
        coEvery {
            addonStreamIntegrationProvider.getStreams(genericAddon.id, match { it.contains("generic.example") }, any())
        } returns NetworkResult.Success(StreamResponseDto(streams = listOf(streamDto("Generic Stream"))))

        val repository = StreamRepositoryImpl(
            addonStreamIntegrationProvider = addonStreamIntegrationProvider,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            playerSettingsDataStore = playerSettingsDataStore,
            serviceWrapSessionFactory = serviceWrapSessionFactory,
            addonStreamRequestCanceller = addonStreamRequestCanceller,
            animeIdentityIndex = RecordingAnimeIdentityIndex(contentIsAnime = false),
            traceMetadataEvents = mockk(relaxed = true)
        )

        val emissions = repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1234567",
            requestOrigin = "test_non_anime_all_addons",
            requestId = "request-non-anime-all-addons"
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList()

        assertEquals(setOf("Anime Addon", "Generic Addon"), emissions.last().data.map { it.addonName }.toSet())
        assertTrue(emissions.last().data.none { it.isAnimeBucket })
        coVerify(exactly = 1) {
            addonStreamIntegrationProvider.getStreams(animeAddon.id, match { it.contains("anime.example") }, any())
        }
        coVerify(exactly = 1) {
            addonStreamIntegrationProvider.getStreams(genericAddon.id, match { it.contains("generic.example") }, any())
        }
    }

    @Test
    fun `anime tagged addon empty result does not fall back to generic addons`() = runTest {
        val animeAddon = streamAddon(
            baseUrl = "https://anime.example",
            displayName = "Anime Addon",
            isAnime = true,
            returnedStreams = emptyList()
        )
        val genericAddon = streamAddon("https://generic.example", "Generic Addon")
        val repository = repository(
            addons = listOf(animeAddon, genericAddon),
            animeIdentityIndex = RecordingAnimeIdentityIndex(contentIsAnime = true)
        )

        val buckets = repository.successBuckets(videoId = "mal:21")

        assertEquals(listOf("Anime Addon"), buckets.map { it.addonName })
        assertTrue(buckets.single().isAnimeBucket)
        assertTrue(buckets.single().streams.isEmpty())
    }

    @Test
    fun `anime content defers generic progressive success until anime tagged addon finishes`() = runTest {
        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)
        val genericAddon = streamAddon("https://generic.example", "Generic Addon")
        val repository = repository(
            addons = listOf(animeAddon, genericAddon),
            animeIdentityIndex = RecordingAnimeIdentityIndex(contentIsAnime = true),
            addonDelayMs = mapOf(animeAddon.id to 100L, genericAddon.id to 0L)
        )

        val emissions = repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "mal:21",
            requestOrigin = "test_anime_bucket_progressive_gate",
            requestId = "request-anime-bucket-progressive-gate"
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList()

        assertTrue(emissions.isNotEmpty())
        assertTrue(emissions.first().data.any { it.addonName == "Anime Addon" })
        assertTrue(emissions.first().data.single { it.addonName == "Anime Addon" }.isAnimeBucket)
    }

    @Test
    fun `non anime content still emits faster generic result progressively`() = runTest {
        val animeAddon = streamAddon("https://anime.example", "Anime Addon", isAnime = true)
        val genericAddon = streamAddon("https://generic.example", "Generic Addon")
        val repository = repository(
            addons = listOf(animeAddon, genericAddon),
            animeIdentityIndex = RecordingAnimeIdentityIndex(contentIsAnime = false),
            addonDelayMs = mapOf(animeAddon.id to 100L, genericAddon.id to 0L)
        )

        val emissions = repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "mal:21",
            requestOrigin = "test_anime_bucket_non_anime_progressive",
            requestId = "request-anime-bucket-non-anime-progressive"
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList()

        assertEquals(listOf("Generic Addon"), emissions.first().data.map { it.addonName })
        assertFalse(emissions.first().data.single().isAnimeBucket)
    }

    @Test
    fun `bucket flag is preserved when service-wrap processing rebuilds AddonStreams`() = runTest {
        val hash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        val animeAddon = streamAddon(
            baseUrl = "https://anime.example",
            displayName = "Anime Addon",
            isAnime = true,
            returnedStreams = listOf(
                streamDto(
                    name = "P2P Candidate",
                    url = null,
                    infoHash = hash,
                    description = "Movie.2024.2160p.REMUX"
                )
            )
        )
        val repository = repository(
            addons = listOf(animeAddon),
            animeIdentityIndex = RecordingAnimeIdentityIndex(contentIsAnime = true),
            playerSettings = PlayerSettings(serviceWrapEnabled = true),
            serviceWrapSessionFactory = ServiceWrapSessionFactory(
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
                                isTerminal = true
                            )
                        )
                    }
                },
                wrappedStreamBuilder = WrappedStreamBuilder()
            )
        )

        val emissions = repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "mal:21",
            requestOrigin = "test_anime_bucket_service_wrap",
            requestId = "request-anime-bucket-service-wrap"
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList()

        assertTrue(emissions.first().data.single().isAnimeBucket)
        assertTrue(emissions.last().data.single().isAnimeBucket)
        assertEquals(setOf("RD"), emissions.last().data.single().streams.mapNotNull { it.wrappedProviderId }.toSet())
    }

    private fun repository(
        addons: List<Addon>,
        animeIdentityIndex: AnimeIdentityIndex,
        playerSettings: PlayerSettings = PlayerSettings(),
        serviceWrapSessionFactory: ServiceWrapSessionFactory = mockk(relaxed = true),
        traceMetadataEvents: TraceMetadataEvents = mockk(relaxed = true),
        addonDelayMs: Map<String, Long> = emptyMap()
    ): StreamRepositoryImpl {
        mockAndroidLog()

        val addonStreamIntegrationProvider = mockk<AddonStreamIntegrationProvider>()
        val addonRepository = mockk<AddonRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val addonStreamRequestCanceller = mockk<AddonStreamRequestCanceller>(relaxed = true)

        every { addonRepository.getInstalledAddons() } returns flowOf(addons)
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { playerSettingsDataStore.playerSettings } returns flowOf(playerSettings)
        addons.forEach { addon ->
            coEvery {
            addonStreamIntegrationProvider.getStreams(
                addon.id,
                match { it.contains(addon.host) },
                any()
            )
        } coAnswers {
            addonDelayMs[addon.id]?.takeIf { it > 0L }?.let { delay(it) }
            NetworkResult.Success(StreamResponseDto(streams = addonReturnedStreams.getValue(addon.id)))
        }
        }

        return StreamRepositoryImpl(
            addonStreamIntegrationProvider = addonStreamIntegrationProvider,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            playerSettingsDataStore = playerSettingsDataStore,
            serviceWrapSessionFactory = serviceWrapSessionFactory,
            addonStreamRequestCanceller = addonStreamRequestCanceller,
            animeIdentityIndex = animeIdentityIndex,
            traceMetadataEvents = traceMetadataEvents
        )
    }

    private suspend fun StreamRepositoryImpl.successBuckets(videoId: String): List<AddonStreams> {
        return getStreamsFromAllAddons(
            type = "movie",
            videoId = videoId,
            requestOrigin = "test_anime_bucket",
            requestId = "request-anime-bucket"
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList().last().data
    }

    private fun mockAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    private fun streamAddon(
        baseUrl: String,
        displayName: String,
        isAnime: Boolean = false,
        returnedStreams: List<StreamDto> = listOf(streamDto(displayName))
    ): Addon {
        val addon = Addon(
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
            ),
            isAnime = isAnime
        )
        addonReturnedStreams[addon.id] = returnedStreams
        return addon
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

    private val addonReturnedStreams = mutableMapOf<String, List<StreamDto>>()

    private val Addon.host: String
        get() = baseUrl.removePrefix("https://").removePrefix("http://").substringBefore("/")

    private class RecordingAnimeIdentityIndex(
        private val contentIsAnime: Boolean
    ) : AnimeIdentityIndex {
        val lookups = mutableListOf<ParsedMetadataId>()

        override suspend fun resolveKitsuId(id: ParsedMetadataId): String? {
            error("resolveKitsuId should not be used for stream bucket classification")
        }

        override suspend fun isAnime(id: ParsedMetadataId): Boolean {
            lookups += id
            return contentIsAnime
        }
    }

    private object ThrowingAnimeIdentityIndex : AnimeIdentityIndex {
        override suspend fun resolveKitsuId(id: ParsedMetadataId): String? {
            error("AnimeIdentityIndex should not be called for unknown content ids")
        }

        override suspend fun isAnime(id: ParsedMetadataId): Boolean {
            error("AnimeIdentityIndex should not be called for unknown content ids")
        }
    }

    private class RecordingTraceSink : RuntimeTraceSink {
        val events = mutableListOf<TraceEventEnvelope<*>>()

        override fun emit(event: TraceEventEnvelope<*>) {
            events += event
        }

        override fun eventsWritten(): Long = events.size.toLong()

        override fun eventsDropped(): Long = 0L
    }
}
