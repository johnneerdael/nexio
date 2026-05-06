package com.nexio.tv.core.artwork

import com.google.gson.Gson
import com.nexio.tv.core.integration.ArtworkApiShapes
import com.nexio.tv.core.integration.ByteArrayIntegrationCodec
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationFetchOptions
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationStreamHandle
import com.nexio.tv.core.integration.IntegrationStreamSpec
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkAssetRepositoryTest {
    private val temp = TemporaryFolder().also { it.create() }

    @Test
    fun `provider template fetch uses runtime and global English image scope`() = runTest {
        val runtime = RecordingIntegrationRuntime(successValue = "image-bytes".toByteArray())
        val repository = repository(runtime = runtime)
        val decision = rpdbTemplateDecision()

        val result = repository.getOrFetch(decision)

        assertNotNull(result)
        result!!
        assertTrue(result.localFile.exists())
        assertArrayEquals("image-bytes".toByteArray(), result.localFile.readBytes())
        assertEquals(ArtworkCacheKeys.assetKeyForProviderTemplate(decision.selectedCandidate.providerTemplate!!).value, runtime.lastSpec!!.cacheKey)
        assertEquals(IntegrationScope.GlobalEnglishImage, runtime.lastSpec!!.scope)
        assertEquals(ArtworkApiShapes.RPDB_POSTER_TEMPLATE, runtime.lastSpec!!.apiShapeId)
        assertEquals(ByteArrayIntegrationCodec, runtime.lastSpec!!.codec)
        assertTrue(runtime.lastSpec!!.cachePolicy is IntegrationCachePolicy.CacheFirst)
        assertEquals(ArtworkApiShapes.RPDB_POSTER_TEMPLATE, result.runtimeApiShapeId)
        assertEquals("HIT", result.cacheDecision)
        assertEquals(ByteArrayIntegrationCodec.mimeType, result.mimeType)
        assertEquals(false, result.networkExecuted)
    }

    @Test
    fun `repository loader executes on runtime Updated path`() = runTest {
        val runtime = LoadingIntegrationRuntime()
        var loaderCalled = false
        val repository = repository(
            runtime = runtime,
            byteLoader = ArtworkByteLoader { _, _ ->
                loaderCalled = true
                IntegrationLoadResult.Success("loaded".toByteArray())
            }
        )

        val result = repository.getOrFetch(rpdbTemplateDecision())

        assertNotNull(result)
        assertTrue(loaderCalled)
        assertArrayEquals("loaded".toByteArray(), result!!.localFile.readBytes())
        assertEquals("MISS_THEN_NETWORK", result.cacheDecision)
        assertEquals(true, result.networkExecuted)
    }

    @Test
    fun `repository marks stale result as network executed when loader was invoked`() = runTest {
        val runtime = StaleAfterLoadingIntegrationRuntime("stale".toByteArray())
        val repository = repository(
            runtime = runtime,
            byteLoader = ArtworkByteLoader { _, _ ->
                IntegrationLoadResult.Success("network-attempt".toByteArray())
            }
        )

        val result = repository.getOrFetch(rpdbTemplateDecision())

        assertNotNull(result)
        assertEquals(true, result!!.networkExecuted)
        assertEquals("STALE_HIT", result.cacheDecision)
        assertArrayEquals("stale".toByteArray(), result.localFile.readBytes())
    }

    @Test
    fun `top posters thumbnail provider template materializes thumbnail shape and path params asset key`() = runTest {
        val runtime = RecordingIntegrationRuntime(successValue = "thumbnail".toByteArray())
        val repository = repository(runtime = runtime)
        val decision = topPostersThumbnailDecision()

        val result = repository.getOrFetch(decision)

        assertNotNull(result)
        assertEquals(ArtworkApiShapes.TOP_POSTERS_THUMBNAIL, runtime.lastSpec!!.apiShapeId)
        assertEquals(ArtworkApiShapes.TOP_POSTERS_THUMBNAIL, result!!.runtimeApiShapeId)
        assertEquals(
            "artwork-asset:TOP_POSTERS:thumbnail:tvdb:1399:badgePosition:top-right:badgeSize:small:blur:false:episode:1:season:1:settings:settingshash:credential:credentialhash:imageLang:en:policy:1",
            runtime.lastSpec!!.cacheKey
        )
    }

    @Test
    fun `remote preview materialization recovers raw source by source hash without persisting raw url`() = runTest {
        val runtime = LoadingIntegrationRuntime()
        val loadedSources = mutableListOf<ArtworkSource>()
        val repository = repository(
            runtime = runtime,
            sourceMaterializer = ArtworkSourceMaterializer(
                mapOf("hash" to SensitiveArtworkUrl.of("https://image.tmdb.org/t/p/w500/abc.jpg"))
            ),
            byteLoader = ArtworkByteLoader { source, _ ->
                loadedSources += source
                IntegrationLoadResult.Success("preview".toByteArray())
            }
        )
        val decision = remotePreviewDecision()

        val result = repository.getOrFetch(decision)

        assertNotNull(result)
        assertArrayEquals("preview".toByteArray(), result!!.localFile.readBytes())
        assertEquals(ArtworkApiShapes.RAIL_PREVIEW_IMAGE_FETCH, runtime.lastSpec!!.apiShapeId)
        assertEquals(ArtworkApiShapes.RAIL_PREVIEW_IMAGE_FETCH, result.runtimeApiShapeId)
        assertEquals("MISS_THEN_NETWORK", result.cacheDecision)
        assertEquals("artwork-asset:RAIL_PREVIEW:poster:urlHash:hash:variant:none:imageLang:en:policy:1", runtime.lastSpec!!.cacheKey)
        assertEquals("https://image.tmdb.org/t/p/w500/<redacted>", decision.selectedCandidate.redactedSourceForTrace)
        assertTrue(loadedSources.single() is ArtworkSource.RemoteUrl)
    }

    @Test
    fun `remote preview fresh cache hit does not require raw source material or loader`() = runTest {
        val runtime = RecordingIntegrationRuntime(successValue = "cached".toByteArray())
        var loaderCalled = false
        val repository = repository(
            runtime = runtime,
            byteLoader = ArtworkByteLoader { _, _ ->
                loaderCalled = true
                IntegrationLoadResult.NetworkError(IllegalStateException("loader should not run"))
            }
        )

        val result = repository.getOrFetch(remotePreviewDecision())

        assertNotNull(result)
        assertEquals(false, loaderCalled)
        assertEquals(false, result!!.networkExecuted)
        assertEquals("HIT", result.cacheDecision)
        assertArrayEquals("cached".toByteArray(), result.localFile.readBytes())
        assertEquals("artwork-asset:RAIL_PREVIEW:poster:urlHash:hash:variant:none:imageLang:en:policy:1", runtime.lastSpec!!.cacheKey)
    }

    @Test
    fun `getOrFetchDecision looks up decision and materializes asset`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val decision = rpdbTemplateDecision()
        cache.put(decision)
        val runtime = LoadingIntegrationRuntime()
        val repository = repository(
            runtime = runtime,
            cache = cache,
            byteLoader = ArtworkByteLoader { _, _ ->
                IntegrationLoadResult.Success("decision-image".toByteArray())
            }
        )

        val result = repository.getOrFetchDecision(decision.decisionKey)

        assertNotNull(result)
        result!!
        assertEquals(ArtworkCacheKeys.assetKeyForProviderTemplate(decision.selectedCandidate.providerTemplate!!), result.assetKey)
        assertArrayEquals("decision-image".toByteArray(), result.localFile.readBytes())
        assertEquals("MISS_THEN_NETWORK", result.cacheDecision)
    }

    @Test
    fun `decision ref materializes from durable cache after repository restart`() = runTest {
        val decisionFile = temp.newFile("decisions.json")
        val diskCache = ArtworkAssetDiskCache(temp.root)
        val decision = rpdbTemplateDecision()

        DurableArtworkDecisionCache(decisionFile, Gson()).put(decision)

        val restartedCache = DurableArtworkDecisionCache(decisionFile, Gson())
        val runtime = LoadingIntegrationRuntime()
        val repository = repository(
            runtime = runtime,
            cache = restartedCache,
            diskCache = diskCache,
            byteLoader = ArtworkByteLoader { _, _ ->
                IntegrationLoadResult.Success("after-restart".toByteArray())
            }
        )

        val result = repository.getOrFetchDecision(decision.decisionKey)

        assertNotNull(result)
        assertArrayEquals("after-restart".toByteArray(), result!!.localFile.readBytes())
        assertEquals("MISS_THEN_NETWORK", result.cacheDecision)
    }

    @Test
    fun `selected provider failure falls back to primary remote candidate`() = runTest {
        val selected = rpdbTemplateDecision()
        val fallback = remotePreviewCandidateFromProductionSource()
        val decision = selected.copy(
            selectedCandidate = selected.selectedCandidate,
            rejectedCandidates = selected.rejectedCandidates + RejectedArtworkCandidate(
                provider = fallback.provider,
                sourceRole = fallback.sourceRole,
                reason = "available_fallback",
                sourceHash = fallback.sourceHash,
                redactedSourceForTrace = fallback.redactedSourceForTrace,
                providerTemplate = fallback.providerTemplate,
                priority = fallback.priority
            )
        )
        val cache = InMemoryArtworkDecisionCache()
        cache.put(decision)
        val runtime = LoadingIntegrationRuntime()
        var loadCount = 0
        val repository = repository(
            runtime = runtime,
            cache = cache,
            byteLoader = ArtworkByteLoader { source, _ ->
                loadCount += 1
                when (source) {
                    is ArtworkSource.ProviderTemplate ->
                        IntegrationLoadResult.NetworkError(IllegalStateException("premium unavailable"))
                    is ArtworkSource.RemoteUrl ->
                        IntegrationLoadResult.Success("fallback-bytes".toByteArray())
                    else ->
                        IntegrationLoadResult.NetworkError(IllegalStateException("fallback source unavailable"))
                }
            }
        )

        val result = repository.getOrFetchDecision(decision.decisionKey)

        assertNotNull(result)
        assertArrayEquals("fallback-bytes".toByteArray(), result!!.localFile.readBytes())
        assertEquals(2, loadCount)
        assertEquals("FALLBACK_MATERIALIZED", result.cacheDecision)
    }

    @Test
    fun `decision materialization returns existing artwork asset before runtime`() = runTest {
        val diskCache = ArtworkAssetDiskCache(temp.root)
        val decision = rpdbTemplateDecision()
        val firstRepository = repository(
            runtime = LoadingIntegrationRuntime(),
            diskCache = diskCache,
            byteLoader = ArtworkByteLoader { _, _ ->
                IntegrationLoadResult.Success("disk-first".toByteArray())
            }
        )
        assertNotNull(firstRepository.getOrFetch(decision))

        val runtime = FailingIfCalledIntegrationRuntime()
        val secondRepository = repository(
            runtime = runtime,
            diskCache = diskCache,
            byteLoader = ArtworkByteLoader { _, _ ->
                IntegrationLoadResult.NetworkError(IllegalStateException("loader should not run"))
            }
        )

        val result = secondRepository.getOrFetch(decision)

        assertNotNull(result)
        assertEquals(false, runtime.called)
        assertEquals(false, result!!.networkExecuted)
        assertEquals("ARTWORK_DISK_HIT", result.cacheDecision)
        assertArrayEquals("disk-first".toByteArray(), result.localFile.readBytes())
    }

    @Test
    fun `decision materialization falls through to runtime when existing artwork asset cannot be read`() = runTest {
        val diskCache = ArtworkAssetDiskCache(temp.root)
        val decision = rpdbTemplateDecision()
        val assetKey = ArtworkCacheKeys.assetKeyForProviderTemplate(decision.selectedCandidate.providerTemplate!!)
        val firstRepository = repository(
            runtime = LoadingIntegrationRuntime(),
            diskCache = diskCache,
            byteLoader = ArtworkByteLoader { _, _ ->
                IntegrationLoadResult.Success("unreadable-disk".toByteArray())
            }
        )
        assertNotNull(firstRepository.getOrFetch(decision))
        val existing = diskCache.getExistingFile(assetKey)!!
        existing.setReadable(false, false)
        assumeTrue("filesystem must enforce unreadable file permissions", !existing.canRead())

        val runtime = RecordingIntegrationRuntime(successValue = "runtime-after-unreadable".toByteArray())
        val secondRepository = repository(runtime = runtime, diskCache = diskCache)

        try {
            val result = secondRepository.getOrFetch(decision)

            assertNotNull(result)
            assertNotNull(runtime.lastSpec)
            assertEquals("HIT", result!!.cacheDecision)
            assertArrayEquals("runtime-after-unreadable".toByteArray(), result.localFile.readBytes())
        } finally {
            existing.setReadable(true, false)
        }
    }

    @Test
    fun `decision materialization falls back to existing artwork asset after runtime missing`() = runTest {
        val diskCache = ArtworkAssetDiskCache(temp.root)
        val decision = rpdbTemplateDecision()
        val materializedAssetKey = ArtworkCacheKeys.assetKeyForProviderTemplate(
            decision.selectedCandidate.providerTemplate!!
        )
        val runtime = MissingAfterConcurrentDiskWriteRuntime(
            diskCache = diskCache,
            decision = decision,
            assetKey = materializedAssetKey
        )
        val repository = repository(
            runtime = runtime,
            diskCache = diskCache,
            byteLoader = ArtworkByteLoader { _, _ ->
                IntegrationLoadResult.NetworkError(IllegalStateException("provider unavailable"))
            }
        )

        val result = repository.getOrFetch(decision)

        assertNotNull(result)
        assertEquals(true, runtime.called)
        assertEquals(false, result!!.networkExecuted)
        assertEquals("ARTWORK_DISK_HIT_AFTER_RUNTIME_MISS", result.cacheDecision)
        assertArrayEquals("late-disk".toByteArray(), result.localFile.readBytes())
    }

    @Test
    fun `decision materialization fallback after runtime missing preserves loader invocation`() = runTest {
        val diskCache = ArtworkAssetDiskCache(temp.root)
        val decision = rpdbTemplateDecision()
        val materializedAssetKey = ArtworkCacheKeys.assetKeyForProviderTemplate(
            decision.selectedCandidate.providerTemplate!!
        )
        val runtime = MissingAfterLoadAndConcurrentDiskWriteRuntime(
            diskCache = diskCache,
            decision = decision,
            assetKey = materializedAssetKey
        )
        val repository = repository(
            runtime = runtime,
            diskCache = diskCache,
            byteLoader = ArtworkByteLoader { _, _ ->
                IntegrationLoadResult.NetworkError(IllegalStateException("provider unavailable"))
            }
        )

        val result = repository.getOrFetch(decision)

        assertNotNull(result)
        assertEquals(true, runtime.called)
        assertEquals(true, result!!.networkExecuted)
        assertEquals("ARTWORK_DISK_HIT_AFTER_RUNTIME_MISS", result.cacheDecision)
        assertArrayEquals("late-disk".toByteArray(), result.localFile.readBytes())
    }

    @Test
    fun `decision materialization returns null when runtime missing fallback file cannot be read`() = runTest {
        val diskCache = ArtworkAssetDiskCache(temp.root)
        val decision = rpdbTemplateDecision()
        val materializedAssetKey = ArtworkCacheKeys.assetKeyForProviderTemplate(
            decision.selectedCandidate.providerTemplate!!
        )
        val runtime = MissingAfterUnreadableDiskWriteRuntime(
            diskCache = diskCache,
            decision = decision,
            assetKey = materializedAssetKey
        )
        val repository = repository(runtime = runtime, diskCache = diskCache)

        try {
            val result = repository.getOrFetch(decision)

            assertNull(result)
        } finally {
            runtime.fallbackFile?.setReadable(true, false)
        }
    }

    @Test
    fun `getOrFetchDecision returns null and traces missing decision`() = runTest {
        val traceSink = RecordingArtworkTraceSink()
        val repository = repository(
            runtime = LoadingIntegrationRuntime(),
            cache = InMemoryArtworkDecisionCache(),
            traceSink = traceSink
        )

        val result = repository.getOrFetchDecision(ArtworkDecisionKey("missing-decision"))

        assertNull(result)
        assertEquals(
            listOf("artwork.decision_lookup", "artwork.decision_missing"),
            traceSink.events.map { it.eventType }
        )
        assertEquals(false, (traceSink.events.first().payload as Map<*, *>)["found"])
        assertEquals(
            "missing-decision",
            (traceSink.events.last().payload as Map<*, *>)["decisionKey"]
        )
    }

    @Test
    fun `provider template decision materializes with empty source materializer`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val decision = rpdbTemplateDecision()
        cache.put(decision)
        var loadedSource: ArtworkSource? = null
        val repository = repository(
            runtime = LoadingIntegrationRuntime(),
            cache = cache,
            sourceMaterializer = ArtworkSourceMaterializer(emptyMap()),
            byteLoader = ArtworkByteLoader { source, _ ->
                loadedSource = source
                IntegrationLoadResult.Success("template-image".toByteArray())
            }
        )

        val result = repository.getOrFetchDecision(decision.decisionKey)

        assertNotNull(result)
        assertArrayEquals("template-image".toByteArray(), result!!.localFile.readBytes())
        assertTrue(loadedSource is ArtworkSource.ProviderTemplate)
    }

    @Test
    fun `remote url decision missing source materializer fails traceably`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val decision = remotePreviewDecision()
        cache.put(decision)
        val traceSink = RecordingArtworkTraceSink()
        val repository = repository(
            runtime = LoadingIntegrationRuntime(),
            cache = cache,
            sourceMaterializer = ArtworkSourceMaterializer(emptyMap()),
            byteLoader = ArtworkByteLoader { source, _ ->
                if (source is UnavailableRemoteArtworkSource) {
                    IntegrationLoadResult.NetworkError(IllegalStateException("raw source unavailable"))
                } else {
                    IntegrationLoadResult.Success("unexpected".toByteArray())
                }
            },
            traceSink = traceSink
        )

        val result = repository.getOrFetchDecision(decision.decisionKey)

        assertNull(result)
        assertEquals("artwork.decision_lookup", traceSink.events.first().eventType)
        assertEquals(true, (traceSink.events.first().payload as Map<*, *>)["found"])
        assertEquals("artwork.asset_materialized", traceSink.events.last().eventType)
        assertEquals(false, (traceSink.events.last().payload as Map<*, *>)["success"])
    }

    private fun repository(
        runtime: IntegrationRuntime,
        cache: ArtworkDecisionCache = InMemoryArtworkDecisionCache(),
        diskCache: ArtworkAssetDiskCache = ArtworkAssetDiskCache(temp.root),
        sourceMaterializer: ArtworkSourceMaterializer = ArtworkSourceMaterializer(emptyMap()),
        byteLoader: ArtworkByteLoader = ArtworkByteLoader { _, _ ->
            IntegrationLoadResult.Success("image-bytes".toByteArray())
        },
        traceSink: RuntimeTraceSink = RecordingArtworkTraceSink()
    ): ArtworkAssetRepository =
        ArtworkAssetRepository(
            runtime = runtime,
            diskCache = diskCache,
            sourceMaterializer = sourceMaterializer,
            byteLoader = byteLoader,
            decisionCache = cache,
            traceSink = traceSink
        )

    private class RecordingArtworkTraceSink : RuntimeTraceSink {
        val events = mutableListOf<TraceEventEnvelope<*>>()

        override fun emit(event: TraceEventEnvelope<*>) {
            events += event
        }

        override fun eventsWritten(): Long = events.size.toLong()

        override fun eventsDropped(): Long = 0L
    }

    private class RecordingIntegrationRuntime(
        private val successValue: ByteArray
    ) : IntegrationRuntime {
        var lastSpec: IntegrationSpec<ByteArray>? = null

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> {
            lastSpec = spec as IntegrationSpec<ByteArray>
            return IntegrationFetchResult.Fresh(successValue as T)
        }

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
            error("not used")

        override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
            error("not used")
    }

    private class LoadingIntegrationRuntime : IntegrationRuntime {
        var lastSpec: IntegrationSpec<ByteArray>? = null

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> {
            lastSpec = spec as IntegrationSpec<ByteArray>
            return when (val loaded = spec.load()) {
                is IntegrationLoadResult.Success -> IntegrationFetchResult.Updated(loaded.value as T)
                else -> IntegrationFetchResult.Missing
            }
        }

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
            error("not used")

        override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
            error("not used")
    }

    private class StaleAfterLoadingIntegrationRuntime(
        private val staleValue: ByteArray
    ) : IntegrationRuntime {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> {
            spec.load()
            return IntegrationFetchResult.Stale(staleValue as T)
        }

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
            error("not used")

        override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
            error("not used")
    }

    private class FailingIfCalledIntegrationRuntime : IntegrationRuntime {
        var called = false

        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> {
            called = true
            error("runtime should not be called when artwork asset disk cache has a hit")
        }

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
            error("not used")

        override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
            error("not used")
    }

    private class MissingAfterConcurrentDiskWriteRuntime(
        private val diskCache: ArtworkAssetDiskCache,
        private val decision: ArtworkDecision,
        private val assetKey: ArtworkAssetKey
    ) : IntegrationRuntime {
        var called = false

        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> {
            called = true
            val bytes = "late-disk".toByteArray()
            val record = diskCache.recordFor(
                assetKey = assetKey,
                decision = decision,
                provider = decision.selectedCandidate.provider,
                sourceHash = decision.selectedCandidate.sourceHash ?: "unknown",
                mimeType = ByteArrayIntegrationCodec.mimeType,
                byteCount = bytes.size.toLong(),
                fetchedAtMs = 123L
            )
            diskCache.write(record, bytes)
            return IntegrationFetchResult.Missing
        }

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
            error("not used")

        override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
            error("not used")
    }

    private class MissingAfterLoadAndConcurrentDiskWriteRuntime(
        private val diskCache: ArtworkAssetDiskCache,
        private val decision: ArtworkDecision,
        private val assetKey: ArtworkAssetKey
    ) : IntegrationRuntime {
        var called = false

        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> {
            called = true
            spec.load()
            val bytes = "late-disk".toByteArray()
            val record = diskCache.recordFor(
                assetKey = assetKey,
                decision = decision,
                provider = decision.selectedCandidate.provider,
                sourceHash = decision.selectedCandidate.sourceHash ?: "unknown",
                mimeType = ByteArrayIntegrationCodec.mimeType,
                byteCount = bytes.size.toLong(),
                fetchedAtMs = 123L
            )
            diskCache.write(record, bytes)
            return IntegrationFetchResult.Missing
        }

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
            error("not used")

        override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
            error("not used")
    }

    private class MissingAfterUnreadableDiskWriteRuntime(
        private val diskCache: ArtworkAssetDiskCache,
        private val decision: ArtworkDecision,
        private val assetKey: ArtworkAssetKey
    ) : IntegrationRuntime {
        var fallbackFile: File? = null

        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> {
            val bytes = "unreadable-late-disk".toByteArray()
            val record = diskCache.recordFor(
                assetKey = assetKey,
                decision = decision,
                provider = decision.selectedCandidate.provider,
                sourceHash = decision.selectedCandidate.sourceHash ?: "unknown",
                mimeType = ByteArrayIntegrationCodec.mimeType,
                byteCount = bytes.size.toLong(),
                fetchedAtMs = 123L
            )
            fallbackFile = diskCache.write(record, bytes).file
            fallbackFile!!.setReadable(false, false)
            assumeTrue("filesystem must enforce unreadable file permissions", !fallbackFile!!.canRead())
            return IntegrationFetchResult.Missing
        }

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
            error("not used")

        override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
            error("not used")
    }

    private fun rpdbTemplateDecision(): ArtworkDecision =
        ArtworkDecision(
            decisionKey = ArtworkDecisionKey("rpdb-decision"),
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            canonicalContentId = "imdb:tt0137523",
            imageType = ArtworkType.POSTER,
            selectedCandidate = PersistedArtworkCandidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                sourceRole = ArtworkSourceRole.PREMIUM,
                sourceHash = "template-hash",
                redactedSourceForTrace = null,
                providerTemplate = PersistedProviderTemplate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                    imageType = ArtworkType.POSTER,
                    idType = "imdb",
                    mediaId = "tt0137523",
                    providerPathHash = "pathhash",
                    settingsHash = "settingshash",
                    credentialHash = "credentialhash",
                    policyVersion = 1
                ),
                priority = 0
            ),
            rejectedCandidates = emptyList(),
            policyVersion = 1,
            settingsHash = "settingshash",
            credentialHash = "credentialhash",
            createdAtMs = 100L,
            expiresAtMs = 200L,
            staleUntilMs = 300L
        )

    private fun remotePreviewDecision(): ArtworkDecision =
        ArtworkDecision(
            decisionKey = ArtworkDecisionKey("preview-decision"),
            ownerKey = ArtworkOwnerKey.PreviewItem("rail-item", "payload"),
            canonicalContentId = null,
            imageType = ArtworkType.POSTER,
            selectedCandidate = PersistedArtworkCandidate(
                provider = ArtworkProviderId.RailPreview,
                sourceRole = ArtworkSourceRole.RAIL_PREVIEW,
                sourceHash = "hash",
                redactedSourceForTrace = "https://image.tmdb.org/t/p/w500/<redacted>",
                providerTemplate = null,
                priority = 1
            ),
            rejectedCandidates = emptyList(),
            policyVersion = 1,
            settingsHash = null,
            credentialHash = null,
            createdAtMs = 100L,
            expiresAtMs = 200L,
            staleUntilMs = 300L
        )

    private fun remotePreviewCandidateFromProductionSource(): PersistedArtworkCandidate =
        ArtworkCandidate(
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            canonicalContentId = "imdb:tt0137523",
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
            imageType = ArtworkType.POSTER,
            sourceRole = ArtworkSourceRole.PRIMARY,
            source = ArtworkSource.RemoteUrl.of(
                rawUrl = SensitiveArtworkUrl.of("https://image.tmdb.org/t/p/w500/fallback.jpg"),
                normalizedUrlHash = "fallbacksourcehash"
            ),
            priority = 10,
            requiresRuntimeFetch = true
        ).toPersistedCandidate(policyVersion = 1)

    private fun topPostersThumbnailDecision(): ArtworkDecision =
        ArtworkDecision(
            decisionKey = ArtworkDecisionKey("thumbnail-decision"),
            ownerKey = ArtworkOwnerKey.CanonicalContent("tvdb:1399:S1E1"),
            canonicalContentId = "tvdb:1399",
            imageType = ArtworkType.THUMBNAIL,
            selectedCandidate = PersistedArtworkCandidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                sourceRole = ArtworkSourceRole.PREMIUM,
                sourceHash = "template-hash",
                redactedSourceForTrace = null,
                providerTemplate = PersistedProviderTemplate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                    imageType = ArtworkType.THUMBNAIL,
                    idType = "tvdb",
                    mediaId = "1399",
                    providerPathHash = "pathhash",
                    settingsHash = "settingshash",
                    credentialHash = "credentialhash",
                    policyVersion = 1,
                    pathParams = mapOf(
                        "season" to "1",
                        "episode" to "1",
                        "badgeSize" to "small",
                        "badgePosition" to "top-right",
                        "blur" to "false"
                    )
                ),
                priority = 0
            ),
            rejectedCandidates = emptyList(),
            policyVersion = 1,
            settingsHash = "settingshash",
            credentialHash = "credentialhash",
            createdAtMs = 100L,
            expiresAtMs = 200L,
            staleUntilMs = 300L
        )
}
